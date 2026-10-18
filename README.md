# DocMind · 企业级私有文档智能系统

> Spring Boot 4.1 + Spring AI 2.0 + RAG + MCP + Redis + Kafka + pgvector + 多模态大模型 + Micrometer + Docker Compose

一套能**真正跑起来**的企业私有知识库：上传 PDF / Word / Excel / PPT / 图片 / 扫描件 / 音频 / 视频，
自动解析、切片、向量化，提供带引用的流式问答，并通过 MCP 把知识库能力开放给外部 Agent。

---

## 一、它到底做了什么

| 能力 | 实现 |
| --- | --- |
| 文档摄取 | 异步任务 + 状态机（PENDING→PARSING→CHUNKING→EMBEDDING→INDEXED/FAILED），失败自动重试 + 死信 |
| 多格式解析 | Tika 文本 / PDFBox PDF（扫描页自动转视觉 OCR）/ POI Excel→Markdown 表格 / POI PPTX / 图片视觉理解 / 音频转写 / 视频抽帧理解 |
| 幂等 | 同知识库内相同内容（SHA-256）不重复处理，连点上传也只跑一次流水线 |
| 检索 | **向量 + 关键词混合检索**，用 RRF（Reciprocal Rank Fusion, k=60）融合排序 |
| 生成 | 强制「只依据上下文作答」+ `[n]` 引用标注，SSE 流式输出 |
| 多模态问答 | 直接上传图片/语音提问，走视觉模型与全模态模型 |
| MCP | 内置 MCP Server（Streamable HTTP，端点 `/mcp`），对外暴露 4 个知识库工具；同时具备 MCP Client 能力 |
| 可观测性 | Micrometer 自定义指标 + Prometheus + Grafana 看板，MDC traceId 贯穿 HTTP→线程池→Kafka |
| 企业级 | JWT + RBAC、审计日志、配额与限流、幂等（checksum 去重）、知识库权限、MCP 白名单与独立密钥 |

---

## 二、技术栈版本（全部取当前最新稳定版）

| 组件 | 版本 |
| --- | --- |
| Spring Boot | 4.1.1（Spring Framework 7.0.9 / Spring Security 7.1.1） |
| Spring AI | 2.0.1 |
| Java | 21（兼容 25） |
| PostgreSQL + pgvector | 18 / 0.8.7 |
| Redis | 8.4 |
| Kafka | 4.1.1（KRaft，无 Zookeeper） |
| MinIO | 2026-09 |
| Prometheus / Grafana | 3.9.1 / 12.3.0 |
| Vue / Vite / Element Plus | 3.5 / 7.1 / 2.11 |
| 模型（阿里百炼 OpenAI 兼容） | `qwen-plus`、`text-embedding-v4`(1024维)、`qwen3-vl-plus`、`qwen3-omni-flash` |

---

## 三、快速开始

### 方式 A：本地零依赖启动（推荐先跑这个）

```bash
cp .env.example .env          # 填入 DASHSCOPE_API_KEY
./tool/dev.sh --build         # 打包并启动（dev profile）
# 打开 http://127.0.0.1:8080
```

dev profile 用 H2 + 内存向量库 + 本地磁盘 + 内存消息总线，**不需要任何中间件**。

前端另开一个终端：

```bash
cd frontend && npm install && npm run dev   # http://localhost:5173
```

### 方式 B：Docker Compose 全栈（生产形态）

```bash
cp .env.example .env          # 必填：DASHSCOPE_API_KEY / 各种密码 / JWT_SECRET
docker compose --env-file .env up -d --build
```

| 服务 | 地址 |
| --- | --- |
| 前端控制台 | http://localhost |
| 后端 API | http://localhost:8080 |
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |
| MinIO 控制台 | http://localhost:9001 |

首次注册的第一个用户自动成为 **ADMIN**。

---

## 四、架构

```
                    ┌─────────────── Vue3 控制台 ───────────────┐
                    │ 登录 / 知识库 / 文档 / 检索实验室 / 问答   │
                    │ 可观测性 / 运维管理（SSE 流式）            │
                    └───────────────────┬───────────────────────┘
                                        │  /api (JWT)
┌───────────────────────────────────────▼──────────────────────────────────┐
│                        Spring Boot 4 + Spring AI 2.0                      │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌────────────────┐  │
│  │  Auth    │ │    KB    │ │  Ingest  │ │   RAG    │ │      MCP       │  │
│  │ JWT/RBAC │ │ 知识库   │ │ 解析管线 │ │ 混合检索 │ │ Server / Client│  │
│  └──────────┘ └──────────┘ └────┬─────┘ └────┬─────┘ └───────┬────────┘  │
│                                 │            │                │           │
│  ┌──────────────────────────────▼────────────▼────────────────▼────────┐ │
│  │              Ports & Adapters（端口/适配器，中间件可替换）            │ │
│  │  CachePort      StoragePort     MessageBus      KeywordSearchPort     │ │
│  │  ├ Local(Caffeine) ├ Local 磁盘  ├ Local(线程池) ├ InMemory(BM25)     │ │
│  │  └ Redis           └ MinIO       └ Kafka(+DLQ)   └ PG(tsvector+trgm)  │ │
│  └──────────────────────────────────────────────────────────────────────┘ │
│  Micrometer ──► /actuator/prometheus ──► Prometheus ──► Grafana           │
└───────────────────────────────────────────────────────────────────────────┘
        │                    │                    │                │
   ┌────▼─────┐        ┌─────▼─────┐        ┌─────▼─────┐    ┌─────▼─────┐
   │ PG+pgvec │        │   Redis   │        │   Kafka   │    │   MinIO   │
   └──────────┘        └───────────┘        └───────────┘    └───────────┘
        ▲                                                          ▲
        │                    ┌──────────────────┐                  │
        └──── 向量检索 ──────│  DashScope 百炼  │──── 文件对象 ────┘
                             │ qwen-plus / v4-emb
                             │ qwen3-vl / omni │
                             └──────────────────┘
```

**双 Profile 设计**：`dev` 零依赖跑通全部功能；`prod` 切到完整中间件。同一套代码，靠 `docmind.*` 配置项切换适配器实现。

---

## 五、检索是怎么做的

1. 文档 → 递归字符切分（段落 → 句子 → 硬切，chunk 800 / overlap 120）
2. 切片 → `text-embedding-v4` 生成 1024 维向量 → pgvector（HNSW + 余弦距离）
3. 提问时双路召回：
   - 向量召回（Spring AI `VectorStore`）
   - 关键词召回（PG `tsvector` + `pg_trgm`；dev 下用内存 BM25 近似）
4. **RRF 融合**：`score = 0.65/(60+vectorRank) + 0.35/(60+keywordRank)`
5. 取 Top-K 拼上下文 → `qwen-plus` 生成，强制 `[n]` 引用

---


## 六、格式支持矩阵（实测）

| 类型 | 扩展名 | 处理方式 | 实测状态 |
| --- | --- | --- | --- |
| 文本 / Markdown | `.md` `.txt` 等 | Tika / Markdown Reader | ✅ 已验证 |
| PDF | `.pdf` | PDFBox 取文本；**扫描页（文本层为空）自动降级到视觉模型 OCR** | ✅ 文本版 + 扫描件 OCR 均已实测 |
| 表格 | `.xlsx` `.xls` `.csv` | POI → Markdown 表格（保留表头与数值） | ✅ 已验证（多 sheet） |
| 幻灯片 | `.pptx` | XSLF 抽每页文字；内嵌图片走视觉模型 | ✅ 已验证（多页） |
| 图片 / 扫描件 | `.png` `.jpg` `.webp` 等 | `qwen3-vl-plus` 视觉理解 + OCR | ✅ 已验证 |
| 音频 | `.mp3` `.wav` `.m4a` `.aac` `.flac` `.ogg` `.opus` `.amr` | `qwen3-omni-flash` 转写 | ✅ 已验证（真实语音 → 准确中文转写） |
| 视频 | `.mp4` `.mov` `.mkv` `.avi` `.flv` `.wmv` `.webm` `.m4v` | ffmpeg 抽帧 → 视觉模型时序理解 | ✅ 已验证（需本机 ffmpeg，Docker 镜像已内置） |
| 旧版 PPT | `.ppt` | 二进制 HSLF，**明确拒绝**并给出「格式不支持」 | ✅ 已验证（不再谎称支持） |

## 七、MCP 怎么用

服务端已内置，端点：`POST http://<host>:8080/mcp`（Streamable HTTP，MCP 2025-06-18）。

暴露 4 个工具：

| 工具 | 说明 |
| --- | --- |
| `list_knowledge_bases` | 列出允许访问的知识库 |
| `search_knowledge_base` | 混合检索，返回原文片段与相关度 |
| `ask_knowledge_base` | 基于知识库作答并返回引用 |
| `get_document_summary` | 查看文档摘要与处理状态 |

**安全边界**：只有在 `DOCMIND_MCP_EXPOSE_KB_IDS` 里列出的知识库 ID 才能被 MCP 访问（默认空 = 全部拒绝）；
配置 `MCP_API_KEY` 后必须携带 `X-MCP-ApiKey` 请求头。

Cursor / Claude Desktop 配置示例：

```json
{
  "mcpServers": {
    "docmind": {
      "type": "http",
      "url": "http://localhost:8080/mcp",
      "headers": { "X-MCP-ApiKey": "你的密钥" }
    }
  }
}
```

---

## 八、可观测性

- 端点：`/actuator/prometheus`；**生产环境 Actuator 跑在独立端口 9091**（compose 不映射到宿主，只有 Prometheus 内网可达），避免公网直接摸到指标
- Prometheus 抓取目标：`backend:9091`
- 自定义指标：
  - `docmind.ai.requests` / `docmind.ai.tokens` / `docmind.ai.duration`
  - `docmind.ingest.duration` / `docmind.ingest.documents` / `docmind.ingest.queue`
  - `docmind.retrieval.duration` / `docmind.retrieval.hits`
  - `docmind.mcp.calls`
- Grafana 看板 `deploy/grafana/dashboards/docmind-overview.json` 随 compose 自动导入
- 每个响应带 `X-Trace-Id`，日志中可回溯整条链路（含异步线程与 Kafka 消费）

---

## 九、目录结构

```
docmind/
├── backend/
│   ├── src/main/java/com/docmind/
│   │   ├── auth/      认证 · 用户 · RBAC
│   │   ├── kb/        知识库 · 文档 · 切片
│   │   ├── ingest/    摄取管线 + parser/ + multimodal/
│   │   ├── rag/       混合检索 · RRF · 生成
│   │   ├── chat/      SSE 流式对话 · 会话记忆 · 配额
│   │   ├── mcp/       MCP Server 工具 + 密钥校验
│   │   ├── observe/   指标 · AI 用量 · 审计
│   │   ├── infra/     cache / storage / bus / lock 适配器
│   │   └── config/ security/ common/
│   ├── src/main/resources/
│   │   ├── application.yml / -dev.yml / -prod.yml
│   │   └── db/migration/   common(V1 核心表) + postgres(V2 全文检索)
│   └── Dockerfile
├── frontend/           Vue3 + Vite + Element Plus
│   ├── src/views/      Login/Dashboard/KB/Document/SearchLab/Chat/Metrics/Admin
│   ├── nginx.conf      SSE 关缓冲、上传 220MB
│   └── Dockerfile
├── deploy/             prometheus / grafana / init SQL
├── docker-compose.yml
├── tool/               mvn.sh（绕开 Git Bash 路径缺陷）/ dev.sh / smoke.sh / verify-middleware.sh
└── .env.example
```

---

## 十、已实测通过的链路（本机 dev profile）

| 步骤 | 结果 |
| --- | --- |
| 启动 | `Started DocMindApplication`，Tomcat 8080，MCP 注册 4 个工具 |
| `/actuator/health` | `UP` |
| 注册 / 登录 | 首个用户自动 ADMIN，返回 JWT |
| 上传 Markdown | 解析 → 3 切片 → 索引完成 → 自动生成摘要 |
| 上传 PNG 图片 | 视觉模型 `qwen3-vl-plus` 描述内容 → 切片入库（实测正确识别"红矩形 + 蓝圆形 + 虚线"） |
| 混合检索 | 返回相关度 0.448 / 0.334 / 0.319 |
| 同步问答 | 带 `[1]` 引用、prompt/completion token、latency 统计 |
| SSE 流式 | `event:meta` → `event:delta`(逐 token) → `event:done` |
| MCP 握手 | `initialize` → `tools/list` → `tools/call` 全通 |
| 上传 xlsx | 2 个 sheet → 4 切片，摘要准确提取数值 |
| 上传 pptx | 2 页 → 2 切片，标题与正文均抽出 |
| 重复上传同一文件 | 第二次 `duplicate=true`，不再重复跑流水线 |
| 上传 mp4 | ffmpeg 抽帧 → 视觉模型时序摘要，1 帧 → 3 切片 |
| 上传 wav | `qwen3-omni-flash` 转写，英文语音 → 中文转写与原文一致 |
| 上传 pdf（文本版） | 2 页 → 2 切片，走文本层，不触发 OCR |
| 上传 pdf（扫描件） | 文本层为空 → 自动降级视觉 OCR，1 页 → 4 切片，识别文字与原文一致 |
| 自动化测试 | `mvn test` → **71 个用例全绿** |

**真中间件验证（不再只靠 mock）：**

| 组件 | 怎么验的 | 结果 |
| --- | --- | --- |
| Redis | `./tool/dev.sh --profile dev-redis` 起真 Redis，跑完上传→问答后用 `redis-cli keys '*'` 看落键 | `docmind:rate:1:29872176`=6（string，TTL 60s）、`docmind:chat:mem:1`（list，len=4，TTL 12h）；`/api/admin/system` 返回 `cache=RedisCacheAdapter`、`cacheAlive=true` |
| Redis 限流 | 临时把 `DOCMIND_QUOTA_REQUESTS_PER_MINUTE` 降到 3，连发 6 次 `/ask` | 前 3 次 `200`，第 4/5/6 次 `429`；等待窗口滚动后恢复 `200` |
| Kafka | `KafkaMessageBusIT` 用 `@EmbeddedKafka`（KRaft，无需 Docker）起真 broker | 4/4 通过：发布→监听器→业务消费、分区键=docId、延迟投递不提前、失败重试耗尽后进 DLQ 且 `handleDeadLetter` 被调用 |
| 前端构建 | `vite build` | 2364 模块，产物正常 |

---

## 十一、测试

```bash
cd backend && ../tool/mvn.sh test
```

| 测试类 | 覆盖 | 用例数 |
| --- | --- | --- |
| `TextSplitterTest` | 切分不丢字、长度约束、空值安全、token 估算 | 5 |
| `DocumentParserTest` | md / xlsx / pptx / pdf 真样本解析，旧 .ppt 被明确拒绝，扫描页空结果判定 | 7 |
| `AuthAndKbApiTest` | 首个用户即 ADMIN、JWT 签发、伪造令牌 401、RBAC 403、上传幂等、traceId、health | 10 |
| `KafkaMessageBusTest` | 分区键 = docId、topic 名、延迟投递、序列化 | 5 |
| `CachePortContractTest` | 缓存端口契约：KV/TTL、计数器、List 窗口 | 5 |
| `RedisCacheAdapterTest` | Lua 限流脚本、PEXPIRE 毫秒单位、窗口裁剪 | 9 |
| `DocumentParserRoutingTest` | **扩展名路由回归**：`.mp4` 必须走视频而非音频，空/未知扩展名必须被拒 | 18 |
| `MultimodalPayloadTest` | 多模态请求体必须是 data URI（见下方注意事项第 7 条） | 4 |

测试跑在 `test` profile：内存 H2 + 本地适配器 + 假密钥，**不连任何中间件、不消耗任何 token**。

另有两组**真中间件联调**用例。它们命名成 `*IT`，而 surefire 默认只收 `*Test`，
所以不会污染日常构建，需要显式执行：

```bash
# 真 Redis：先起一个 redis-server
redis-server.exe --port 6379 &                       # Windows 也可用 WSL / Docker 起一个
cd backend && REDIS_HOST=127.0.0.1 ../tool/mvn.sh test -Dtest=RedisCacheIT

# 真 Kafka：@EmbeddedKafka 自己起 broker，不需要 Docker，也不需要任何环境变量
cd backend && ../tool/mvn.sh test -Dtest=KafkaMessageBusIT
```

`RedisCacheIT` 没有 `REDIS_HOST` 时自动跳过（`@EnabledIfEnvironmentVariable`），不会让 CI 变红。
两个 `*IT` 加起来 10 个用例：Redis 6 个（Lua 自增 + PEXPIRE + 窗口裁剪在真实例上的行为）、
Kafka 4 个（跨进程投递、分区键、延迟、死信）。

## 十二、注意事项 & 已知限制

1. **API Key 安全**：`.env` 已在 `.gitignore` 中排除。若 Key 曾在聊天/日志中明文出现，请在百炼控制台轮换。
2. **视觉模型**：该 Key 实测 `qwen-vl-max-latest` 返回 403（账号未开通），已改用可用的 `qwen3-vl-plus`。换 Key 后可自行改回。
3. **视频解析**依赖 `ffmpeg`。本机没装可以这样补一份（比官网下载快得多）：
   ```bash
   npm i @ffmpeg-installer/ffmpeg          # 或 apt install ffmpeg / 用 Docker 镜像（已内置）
   ```
   然后把 `.env` 里的 `FFMPEG_PATH` 指向那个 `ffmpeg.exe`（Windows 必须是 `C:/...` 形式，
   Git Bash 的 `/c/...` 会被 `CreateProcess` 判为找不到文件）。`tool/dev.sh` 会自动探测常见位置。
   未安装时视频文档会明确报错并标记 FAILED，不会静默产出空内容。
4. **多模态载荷必须是 data URI**：百炼兼容模式下 `input_audio.data` 与 `image_url.url`
   都要求是 `data:audio/wav;base64,xxx` / `data:image/jpeg;base64,xxx` 这样的完整 data URI。
   传裸 base64 会返回一句极具误导性的
   `The provided URL does not appear to be valid` —— 它跟「URL」其实毫无关系。
   `MultimodalPayloadTest` 专门守着这个形状。
5. **`SERVER__PORT` 陷阱**：部分环境会注入 `SERVER__PORT` / `SERVER__HOST`，Spring Boot 宽松绑定会劫持端口。
   本项目统一改用 `DOCMIND_SERVER_PORT` / `DOCMIND_SERVER_ADDRESS`，`tool/dev.sh` 也会显式清空。
6. **Spring AI 2.0 的 base-url**：官方 openai-java SDK 只拼相对路径，因此 `spring.ai.openai.base-url` 必须自带 `/v1`
   （`https://dashscope.aliyuncs.com/compatible-mode/v1`），否则 embedding/chat 会 404。
7. **扩展名判断不能用 `String.contains`**：`"mp3,wav,…,mp4a".contains("mp4")` 为真，
   这个 bug 曾把 `.mp4` 路由到音频解析器，把视频当 wav 送去 ASR。
   现在所有解析器都用精确的 `Set<String>`，`DocumentParserRoutingTest` 守着这条线。
8. **Docker 未在本机安装**：compose 文件已交付但无法在此机器实测；功能验证全部走 dev profile 完成。

## 十三、一键冒烟

`tool/smoke.sh` 把「登录 → 建库 → 上传 → 轮询状态 → 打印解析结果」串成一条命令，
改完代码想确认链路没坏时直接用它：

```bash
./tool/dev.sh &                                   # 先起服务
USERNAME=smoke PASSWORD='Smoke@12345' ./tool/smoke.sh samples/fixture.mp4      # 自动建库
USERNAME=smoke PASSWORD='Smoke@12345' ./tool/smoke.sh samples/fixture.xlsx 33  # 指定知识库
```

账号不存在时会自动注册；不传知识库 ID 会新建一个私有的，避免 MEMBER 撞上别人的库。

## 十四、一键验证真中间件

`tool/verify-middleware.sh` 会把 Redis 和 Kafka 的联调一次性跑完：

```bash
./tool/verify-middleware.sh          # Redis IT + Kafka IT
./tool/verify-middleware.sh redis    # 只跑 Redis（需本机有 Redis 实例）
./tool/verify-middleware.sh kafka    # 只跑 Kafka（内嵌 broker，无需 Docker）
```

日常 `mvn test` 不会带上这两个 `*IT`（Surefire 默认只收 `*Test`），
所以想确认「Redis / Kafka 真的通」时，用这条命令，而不是靠记忆。

想让运行中的应用也走真 Redis，用半真实 profile：

```bash
redis-server.exe --port 6379 &
./tool/dev.sh --profile dev-redis     # dev 的零依赖配置 + 真 Redis 缓存
```

此时 `curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/admin/system`
应返回 `"cache":"RedisCacheAdapter","cacheAlive":true`。
