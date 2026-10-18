package com.docmind.ingest.parser;

import com.docmind.config.AppProperties;
import com.docmind.ingest.DocumentParser;
import com.docmind.ingest.ParsedDocument;
import com.docmind.ingest.multimodal.MultimodalService;
import com.docmind.kb.KbModels;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** 音频解析：会议录音、客服录音、语音备忘——转写为可检索文本。 */
@Component
@RequiredArgsConstructor
public class AudioDocumentParser implements DocumentParser {

    /**
     * 纯音频容器。注意必须是「精确扩展名集合」而不是字符串 contains：
     * 早先写成 "…,mp4a" 后，mp4 会被 contains 命中（"mp4a" 里含 "mp4"），
     * 导致视频被当成音频送去 ASR。容器型的 mp4/m4v 一律归 VIDEO。
     */
    private static final Set<String> EXTS =
            Set.of("mp3", "wav", "m4a", "aac", "flac", "ogg", "opus", "amr");

    private final MultimodalService multimodal;
    private final AppProperties props;

    @Override
    public boolean supports(KbModels.Modality modality, String ext) {
        return modality == KbModels.Modality.AUDIO || (ext != null && EXTS.contains(ext.toLowerCase(Locale.ROOT)));
    }

    @Override
    public ParsedDocument parse(KbModels.Document doc, byte[] bytes) {
        if (bytes.length > props.getMultimodal().getMaxAudioBytes()) {
            throw new IllegalStateException("音频超过 " + props.getMultimodal().getMaxAudioBytes() / 1024 / 1024 + "MB 限制");
        }
        String transcript = multimodal.transcribeAudio(bytes, doc.ext(),
                "请完整、逐字地转写这段语音，保留说话人切换与关键结论，输出简体中文。");
        ParsedDocument parsed = new ParsedDocument().modality(KbModels.Modality.AUDIO).pageCount(1);
        parsed.add(transcript, 1, ParsedDocument.BlockType.AUDIO_TRANSCRIPT, "{\"source\":\"asr\"}");
        return parsed;
    }
}
