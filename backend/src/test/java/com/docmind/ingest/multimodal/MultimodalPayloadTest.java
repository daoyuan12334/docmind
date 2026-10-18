package com.docmind.ingest.multimodal;

import com.docmind.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多模态请求体的形状测试（不联网，零成本）。
 *
 * <p>守的是两个已经在真实调用里踩过的坑：
 * <ol>
 *   <li>百炼兼容模式下 {@code input_audio.data} 必须是 data URI，
 *       传裸 base64 会被判成非法 URL；</li>
 *   <li>{@code image_url.url} 同样必须是完整 data URI。</li>
 * </ol>
 * 这两处的报错都是同一句 “The provided URL does not appear to be valid”，
 * 极难从错误信息反推，所以直接在载荷层断言。
 */
class MultimodalPayloadTest {

    private final AppProperties props = new AppProperties();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstContentPart(Map<String, Object> body) {
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        List<Map<String, Object>> content = (List<Map<String, Object>>) messages.get(0).get("content");
        return content.get(0);
    }

    @Test
    @DisplayName("音频载荷：input_audio.data 必须是 data:audio/...;base64, 开头的完整 data URI")
    void audioPayloadUsesDataUri() {
        byte[] wav = new byte[]{0x52, 0x49, 0x46, 0x46, 0x01, 0x02};
        Map<String, Object> body = MultimodalService.buildAudioPayload(props, wav, "wav", "转写");

        Map<String, Object> part = firstContentPart(body);
        assertEquals("input_audio", part.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> audio = (Map<String, Object>) part.get("input_audio");
        String expected = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav);
        assertEquals(expected, audio.get("data"), "裸 base64 会被百炼判为非法 URL");
        assertEquals("wav", audio.get("format"));
    }

    @Test
    @DisplayName("音频载荷：未知/带点的扩展名统一归一到 wav")
    void audioFormatNormalized() {
        Map<String, Object> body = MultimodalService.buildAudioPayload(props, new byte[]{1, 2}, ".MP3", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> audio = (Map<String, Object>) firstContentPart(body).get("input_audio");
        assertEquals("mp3", audio.get("format"));
        assertTrue(String.valueOf(audio.get("data")).startsWith("data:audio/mp3;base64,"));

        Map<String, Object> fallback = MultimodalService.buildAudioPayload(props, new byte[]{1, 2}, "xyz", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> fbAudio = (Map<String, Object>) firstContentPart(fallback).get("input_audio");
        assertEquals("wav", fbAudio.get("format"));
    }

    @Test
    @DisplayName("视频帧载荷：每张帧都是 data:image/jpeg;base64, 的完整 data URI，且文本提示在最后")
    void videoFramePayloadUsesDataUri() {
        byte[] f1 = new byte[]{(byte) 0xFF, (byte) 0xD8, 1};
        byte[] f2 = new byte[]{(byte) 0xFF, (byte) 0xD8, 2};
        Map<String, Object> body = MultimodalService.buildVideoFramePayload(props, List.of(f1, f2), "总结");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content =
                (List<Map<String, Object>>) ((Map<String, Object>) ((List<?>) body.get("messages")).get(0))
                        .get("content");
        assertEquals(3, content.size(), "2 帧 + 1 条文本提示");

        assertEquals("image_url", content.get(0).get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> url0 = (Map<String, Object>) content.get(0).get("image_url");
        assertEquals("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(f1), url0.get("url"));

        @SuppressWarnings("unchecked")
        Map<String, Object> url1 = (Map<String, Object>) content.get(1).get("image_url");
        assertEquals("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(f2), url1.get("url"));

        assertEquals("text", content.get(2).get("type"));
        assertEquals("总结", content.get(2).get("text"));
    }

    @Test
    @DisplayName("视频帧载荷：提示为空时要有默认中文提示，不能把 null 发给模型")
    void videoFramePayloadHasDefaultPrompt() {
        Map<String, Object> body = MultimodalService.buildVideoFramePayload(props, List.of(new byte[]{1}), null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content =
                (List<Map<String, Object>>) ((Map<String, Object>) ((List<?>) body.get("messages")).get(0))
                        .get("content");
        Object text = content.get(content.size() - 1).get("text");
        assertTrue(String.valueOf(text).contains("关键帧"), "默认提示丢了");
    }
}
