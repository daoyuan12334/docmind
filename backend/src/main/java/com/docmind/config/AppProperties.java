package com.docmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 系统级配置。分域聚拢，避免散落的 @Value。 */
@Data
@ConfigurationProperties(prefix = "docmind")
public class AppProperties {

    private Ai ai = new Ai();
    private Jwt jwt = new Jwt();
    private Rag rag = new Rag();
    private Ingest ingest = new Ingest();
    private Multimodal multimodal = new Multimodal();
    private Quota quota = new Quota();
    private Storage storage = new Storage();
    private Cache cache = new Cache();
    private Bus bus = new Bus();
    private VectorStoreCfg vector = new VectorStoreCfg();
    private Mcp mcp = new Mcp();
    private OpenApi openApi = new OpenApi();

    @Data
    public static class Ai {
        /** 模型服务端点。原生多模态调用（音频/视频）复用此地址。 */
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode";
        private String apiKey;
        private String chatModel = "qwen-plus";
        private String embeddingModel = "text-embedding-v4";
        private int embeddingDimensions = 1024;
    }

    @Data
    public static class Jwt {
        private String secret;
        private String issuer = "docmind";
        private long expireMinutes = 720;
    }

    @Data
    public static class Rag {
        private int topK = 6;
        private int candidateK = 30;
        private double vectorWeight = 0.65;
        private double keywordWeight = 0.35;
        private double minScore = 0.15;
        private boolean rerankEnabled = true;
        private int maxContextChars = 8000;
        private int retrieveCacheSeconds = 0;
    }

    @Data
    public static class Ingest {
        private int chunkSize = 800;
        private int chunkOverlap = 120;
        private int maxAttempts = 3;
        private int workerConcurrency = 2;
        private long retryBaseDelayMillis = 3_000L;
    }

    @Data
    public static class Multimodal {
        private String visionModel = "qwen-vl-max-latest";
        private String omniModel = "qwen3-omni-flash";
        private boolean ocrEnabled = true;
        private int visionMaxPages = 20;
        private int videoFrameIntervalSeconds = 5;
        private int videoMaxFrames = 12;
        private String ffmpegPath = "ffmpeg";
        private int maxImageBytes = 10 * 1024 * 1024;
        private int maxAudioBytes = 25 * 1024 * 1024;
        private int maxVideoBytes = 100 * 1024 * 1024;
    }

    @Data
    public static class Quota {
        private long dailyTokensPerUser = 200_000L;
        private int requestsPerMinute = 30;
        private boolean enabled = true;
    }

    @Data
    public static class Storage {
        private String type = "local";
        private String localRoot = "./data/storage";
        private String endpoint = "http://minio:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String bucket = "docmind";
    }

    @Data
    public static class Cache {
        private String type = "local";
    }

    @Data
    public static class Search {
        /** pg：PostgreSQL 全文检索；memory：内存词频（无 PG 时）。 */
        private String type = "memory";
    }

    @Data
    public static class Bus {
        private String type = "local";
    }

    @Data
    public static class VectorStoreCfg {
        private String type = "memory";
        private String file = "./data/vector-store.json";
    }

    @Data
    public static class Mcp {
        /** 逗号分隔的知识库 ID 白名单；为空表示不放行任何知识库（默认安全）。 */
        private String exposeKbIds = "";
        private String apiKey = "";
    }

    @Data
    public static class OpenApi {
        /** 是否暴露 OpenAPI 文档与 Swagger UI。prod 建议关闭，或至少把 public 置为 false。 */
        private boolean enabled = true;
        /**
         * true = 匿名可访问 /v3/api-docs 与 /swagger-ui（开发便利）；
         * false = 仅 ROLE_ADMIN 可访问（企业生产默认）。
         *
         * <p>字段名不叫 {@code public}（Java 关键字），也不叫 {@code isPublic}
         * ——后者会让宽松绑定去找 {@code is-public}，与 YAML 里写的
         * {@code public-access} 对不上，属于典型的静默失效。
         */
        private boolean publicAccess = true;
    }
}
