package com.docmind.ingest.multimodal;

import com.docmind.common.BizException;
import com.docmind.common.ErrorCode;
import com.docmind.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 多模态能力封装（阿里云百炼 / OpenAI 兼容端点）。
 *
 * <p>图片理解走 Spring AI 的 ChatClient + Media 标准 API；
 * 音频转写与视频帧理解通过原生兼容端点构造 input_audio / 多图 content，
 * 原因是 Spring AI 2.0 的 Media 抽象主要面向图片，
 * 直接用兼容协议可以稳定拿到 qwen3-omni 的语音能力。
 */
@Slf4j
@Service
public class MultimodalService {

    private final ChatClient chatClient;
    private final RestClient http;
    private final AppProperties props;
    private final ObjectMapper mapper;

    public MultimodalService(ChatClient.Builder builder, RestClient.Builder restBuilder,
                             AppProperties props, ObjectMapper mapper) {
        this.chatClient = builder.build();
        this.props = props;
        this.mapper = mapper;
        this.http = restBuilder
                .requestFactory(new JdkClientHttpRequestFactory())
                .build();
    }

    /** 图片理解 / OCR / 图表解读。走 Spring AI 标准路径。 */
    public String describeImage(byte[] image, String mimeType, String prompt) {
        try {
            return chatClient.prompt()
                    .options(OpenAiChatOptions.builder()
                            .model(props.getMultimodal().getVisionModel())
                            .temperature(0.2))
                    .user(spec -> spec.text(prompt)
                            .media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(image)))
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("图片理解失败 mime={} size={}", mimeType, image.length, e);
            throw new BizException(ErrorCode.AI_ERROR, "图片理解失败: " + e.getMessage(), e);
        }
    }

    /** 批量图片理解（视频抽帧、多页扫描件）。逐张调用并按顺序拼接。 */
    public List<String> describeImages(List<byte[]> images, String mimeType, String prompt) {
        List<String> result = new ArrayList<>(images.size());
        for (byte[] img : images) {
            result.add(describeImage(img, mimeType, prompt));
        }
        return result;
    }

    /**
     * 音频转写：调用 qwen3-omni 的输入音频能力。
     *
     * <p>注意：百炼兼容模式要求 {@code input_audio.data} 是完整的 data URI
     * （{@code data:audio/wav;base64,xxx}）。只传裸 base64 会被判成非法 URL，
     * 返回极具误导性的 “The provided URL does not appear to be valid”。
     */
    public String transcribeAudio(byte[] audio, String format, String prompt) {
        return callChat(buildAudioPayload(props, audio, format, prompt));
    }

    /**
     * 构造音频转写请求体。
     *
     * <p>做成静态方法（而不是实例方法）是为了能脱离 Spring 容器直接单测：
     * 载荷形状是最容易出错、又最难从报错反推的地方。
     */
    static Map<String, Object> buildAudioPayload(AppProperties props, byte[] audio,
                                                 String format, String prompt) {
        String fmt = normalizeAudioFormat(format);
        String dataUri = "data:audio/" + fmt + ";base64," + Base64.getEncoder().encodeToString(audio);
        return Map.of(
                "model", props.getMultimodal().getOmniModel(),
                "messages", List.of(Map.of("role", "user", "content", List.of(
                        Map.of("type", "input_audio", "input_audio", Map.of(
                                "data", dataUri,
                                "format", fmt)),
                        Map.of("type", "text", "text", prompt == null ? "请完整转写这段语音，输出简体中文。" : prompt)
                ))));
    }

    /** 视频理解：把抽出的关键帧一次性交给视觉模型做时序摘要。 */
    public String describeVideoFrames(List<byte[]> frames, String prompt) {
        return callChat(buildVideoFramePayload(props, frames, prompt));
    }

    /**
     * 构造视频帧理解的请求体（同音频，{@code image_url.url} 也必须是完整 data URI）。
     */
    static Map<String, Object> buildVideoFramePayload(AppProperties props,
                                                      List<byte[]> frames, String prompt) {
        List<Map<String, Object>> content = new ArrayList<>();
        for (byte[] frame : frames) {
            String url = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(frame);
            content.add(Map.of("type", "image_url", "image_url", Map.of("url", url)));
        }
        content.add(Map.of("type", "text", "text", prompt == null
                ? "这些是视频按顺序抽取的关键帧，请总结视频内容、出现的文字和关键事件，输出简体中文。" : prompt));
        return Map.of(
                "model", props.getMultimodal().getVisionModel(),
                "messages", List.of(Map.of("role", "user", "content", content)));
    }

    /** 生成文档摘要（文本输入）。 */
    public String summarize(String text, int maxChars) {
        String input = text.length() > maxChars ? text.substring(0, maxChars) : text;
        Map<String, Object> body = Map.of(
                "model", props.getAi().getChatModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", "你是企业文档助手，请用 2-3 句话概括文档要点，输出简体中文。"),
                        Map.of("role", "user", "content", input)));
        try {
            return callChat(body);
        } catch (Exception e) {
            log.warn("摘要生成失败，回退为截断文本", e);
            return input.substring(0, Math.min(200, input.length()));
        }
    }

    private String callChat(Map<String, Object> body) {
        try {
            String url = compatEndpoint() + "/chat/completions";
            // 显式序列化成 String 再发送：避免消息转换器对嵌套 Map 的处理差异，
            // 同时拿到确切的请求体用于排障与审计。
            String json = mapper.writeValueAsString(body);
            if (log.isDebugEnabled()) {
                log.debug("chat 请求 url={} payloadLen={} head={}", url, json.length(),
                        json.substring(0, Math.min(200, json.length())));
            }
            var response = http.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey())
                    .body(json)
                    .retrieve()
                    .body(JsonChatResponse.class);
            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                throw new BizException(ErrorCode.AI_ERROR, "模型返回为空");
            }
            var message = response.choices().get(0).message();
            return message == null ? "" : String.valueOf(message.getOrDefault("content", ""));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("多模态调用失败", e);
            throw new BizException(ErrorCode.AI_ERROR, "模型调用失败: " + e.getMessage(), e);
        }
    }

    private static String normalizeAudioFormat(String format) {
        if (format == null) {
            return "wav";
        }
        String f = format.toLowerCase().replace(".", "");
        return switch (f) {
            case "mp3", "wav", "m4a", "aac", "flac", "ogg", "opus", "amr", "pcm" -> f;
            default -> "wav";
        };
    }

    /** 归一化出「兼容模式的 /v1 根」：无论 base-url 是否自带 /v1，都只保留一个。 */
    private String compatEndpoint() {
        String base = props.getAi().getBaseUrl() == null
                ? "https://dashscope.aliyuncs.com/compatible-mode"
                : props.getAi().getBaseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith("/v1") ? base : base + "/v1";
    }

    private String apiKey() {
        String key = props.getAi().getApiKey();
        if (key == null || key.isBlank()) {
            throw new BizException(ErrorCode.AI_ERROR, "未配置 DASHSCOPE_API_KEY");
        }
        return key;
    }

    /** 百炼兼容端点的极简响应结构，避免引入 SDK。 */
    public record JsonChatResponse(List<Choice> choices) {
        public record Choice(Map<String, Object> message) {
        }
    }
}
