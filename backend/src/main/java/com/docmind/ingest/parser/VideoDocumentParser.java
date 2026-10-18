package com.docmind.ingest.parser;

import com.docmind.config.AppProperties;
import com.docmind.ingest.DocumentParser;
import com.docmind.ingest.ParsedDocument;
import com.docmind.ingest.multimodal.MultimodalService;
import com.docmind.kb.KbModels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 视频解析：按固定间隔抽关键帧，交给视觉模型做时序摘要。依赖本机/容器内的 ffmpeg。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoDocumentParser implements DocumentParser {

    /** 视频容器。mp4/m4v 同时也装音频，但一律按视频处理（抽帧 + 视觉模型）。 */
    private static final Set<String> EXTS =
            Set.of("mp4", "mov", "mkv", "avi", "flv", "wmv", "webm", "m4v");

    private final MultimodalService multimodal;
    private final AppProperties props;

    @Override
    public boolean supports(KbModels.Modality modality, String ext) {
        return modality == KbModels.Modality.VIDEO
                || (ext != null && EXTS.contains(ext.toLowerCase(Locale.ROOT)));
    }

    @Override
    public ParsedDocument parse(KbModels.Document doc, byte[] bytes) throws Exception {
        if (bytes.length > props.getMultimodal().getMaxVideoBytes()) {
            throw new IllegalStateException("视频超过 " + props.getMultimodal().getMaxVideoBytes() / 1024 / 1024 + "MB 限制");
        }
        Path dir = Files.createTempDirectory("docmind-video-");
        File source = dir.resolve("source." + doc.ext()).toFile();
        Files.write(source.toPath(), bytes);
        try {
            List<byte[]> frames = extractFrames(source.toPath(), dir);
            if (frames.isEmpty()) {
                throw new IllegalStateException("未能抽取视频帧，请确认已安装 ffmpeg 且可被进程调用");
            }
            String summary = multimodal.describeVideoFrames(frames,
                    "以下是视频按顺序抽取的关键帧，请总结视频主题、出现的文字、关键数据与结论，输出简体中文。");
            ParsedDocument parsed = new ParsedDocument().modality(KbModels.Modality.VIDEO).pageCount(frames.size());
            parsed.add(summary, 1, ParsedDocument.BlockType.VIDEO_FRAME,
                    "{\"frames\":" + frames.size() + "}");
            return parsed;
        } finally {
            deleteRecursively(dir);
        }
    }

    private List<byte[]> extractFrames(Path source, Path dir) throws Exception {
        Path pattern = dir.resolve("frame-%04d.jpg");
        var cmd = new ArrayList<String>();
        cmd.add(props.getMultimodal().getFfmpegPath());
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(source.toAbsolutePath().toString());
        cmd.add("-vf");
        cmd.add("fps=1/" + Math.max(1, props.getMultimodal().getVideoFrameIntervalSeconds()));
        cmd.add("-frames:v");
        cmd.add(String.valueOf(props.getMultimodal().getVideoMaxFrames()));
        cmd.add("-q:v");
        cmd.add("4");
        cmd.add(pattern.toAbsolutePath().toString());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int code = process.waitFor();
        if (code != 0) {
            log.warn("ffmpeg 抽帧返回 {}，输出：{}", code, output.substring(0, Math.min(500, output.length())));
        }
        List<byte[]> frames = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            List<Path> files = stream
                    .filter(p -> p.getFileName().toString().startsWith("frame-"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            for (Path f : files) {
                byte[] data = Files.readAllBytes(f);
                if (data.length > 0 && data.length <= props.getMultimodal().getMaxImageBytes()) {
                    frames.add(data);
                }
            }
        }
        return frames;
    }

    private void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
