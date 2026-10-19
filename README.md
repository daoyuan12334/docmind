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
配置密钥后必须携带 `X-MCP-ApiKey` 请求头。

> **两个已踩过的坑（改这块时务必注意）：**
>
> 1. **环境变量名必须用 `DOCMIND_MCP_API_KEY`**，不是 `MCP_API_KEY`。
>    它与 `application.yml` 里的 `docmind.mcp.api-key: ${DOCMIND_MCP_API_KEY:}` 严格对应；
>    写成 `MCP_API_KEY` 不会报错，只是永远读不到（静默失效），表现为"配了密钥却没鉴权"。
> 2. **`@McpToolParam` 的 `required` 默认是 `true`**。
>    想让参数可选就必须显式写 `@McpToolParam(required = false, ...)`。
>    曾经 `search_knowledge_base` 的 `topK` 就漏了这一项：代码里明明写了
>    `topK == null ? 5 : topK`（支持省略），但生成的 JSON Schema 把它标成必填，
>    于是客户端省略 `topK` 会直接被 schema 校验拒掉
>    （报 `未找到所需属性"topK"`）—— **契约与实现对不上，且不抛任何业务异常。**

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
| MCP 握手 | `initialize` → `tools/list` → `tools/call` 全通（MCP 2025-06-18 / Streamable HTTP） |
| MCP 鉴权 | 不带 `X-MCP-ApiKey` → `401`；带正确密钥 → 正常建 session |
| MCP 工具调用 | `list_knowledge_bases` 返回 `id=1 \| 名称=redis-verify \| 文档数=1 \| 切片数=3` 等；`search_knowledge_base` 返回带相关度的原文片段 |
| MCP 白名单 | 未列入 `DOCMIND_MCP_EXPOSE_KB_IDS` 的库（如 999）调用被拒：`知识库 999 未授权给 MCP 访问` |
| 上传 xlsx | 2 个 sheet → 4 切片，摘要准确提取数值 |
| 上传 pptx | 2 页 → 2 切片，标题与正文均抽出 |
| 重复上传同一文件 | 第二次 `duplicate=true`，不再重复跑流水线 |
| 上传 mp4 | ffmpeg 抽帧 → 视觉模型时序摘要，1 帧 → 3 切片 |
| 上传 wav | `qwen3-omni-flash` 转写，英文语音 → 中文转写与原文一致 |
| 上传 pdf（文本版） | 2 页 → 2 切片，走文本层，不触发 OCR |
| 上传 pdf（扫描件） | 文本层为空 → 自动降级视觉 OCR，1 页 → 4 切片，识别文字与原文一致 |
| 自动化测试 | `mvn test` → **85 个用例全绿**；前端 `npm test`（vitest）→ **9 个用例全绿** |

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
| `OpenApiContractTest` | **接口文档契约**：文档端点匿名可达、OpenAPI 3.1、四个分组齐全、鉴权标注没跑偏 | 6 |
| `KafkaMessageBusTest` | 分区键 = docId、topic 名、延迟投递、序列化 | 5 |
| `CachePortContractTest` | 缓存端口契约：KV/TTL、计数器、List 窗口 | 5 |
| `RedisCacheAdapterTest` | Lua 限流脚本、PEXPIRE 毫秒单位、窗口裁剪 | 9 |
| `DocumentParserRoutingTest` | **扩展名路由回归**：`.mp4` 必须走视频而非音频，空/未知扩展名必须被拒 | 18 |
| `MultimodalPayloadTest` | 多模态请求体必须是 data URI（见下方注意事项第 7 条） | 4 |
| `HybridRetrievalTest` | **关键词通道回归**：分页参数不能写反，中文查询必须能召回 | 4 |
| `McpToolContractTest` | **MCP 工具契约**：生成的 Schema 的 required 必须与方法实现对得上（见注意事项第 10 条） | 4 |

测试跑在 `test` profile：内存 H2 + 本地适配器 + 假密钥，**不连任何中间件、不消耗任何 token**。

### 前端测试（vitest）

```bash
cd frontend && npm test
```

| 测试文件 | 覆盖 | 用例数 |
| --- | --- | --- |
| `src/api/sse.test.js` | **SSE 帧解析回归**：无空格/带空格帧都要认（Spring 输出 `event:meta` 无空格，正则写死带空格会把每帧静默丢弃）、多行 `data:` 用 `\n` 拼回、CRLF 行尾、半帧缓冲语义 | 9 |

这条测试守的是 2026-10-19 真浏览器走查抓到的实 bug：流式问答一个字都不显示、
控制台零报错，根因是 `http.js` 手写 SSE 解析器的正则不认无空格帧。
解析逻辑已抽到零依赖的 `src/api/sse.js`，`streamChat` 只负责读流与分发。

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
8. **`LIMIT ? OFFSET ?` 的参数顺序**：`ChunkRepository.listByKb(kbId, limit, offset)`
   曾把参数写成 `params(kbId, offset, limit)`，于是 `limit=5000 / offset=0`
   到了 SQL 里变成 `LIMIT 0 OFFSET 5000` —— 永远返回空集。
   后果是**关键词通道静默失效**：接口照样返回 200 和看似合理的分数，
   只是每个结果的 `keywordScore` 恒为 0，混合检索退化成纯向量检索，
   而没有任何报错。`HybridRetrievalTest` 专门守这条线。
9. **`.env` 会覆盖命令行环境变量**：`tool/dev.sh` 早期版本直接 `source .env`，
   于是 `.env` 里那些**留空的占位项**（如 `DOCMIND_MCP_EXPOSE_KB_IDS=`）
   会把调用方显式传入的值静默覆盖成空 —— 不报错、不告警，但功能就是不通。
   现已改成「`.env` 只提供默认值，不覆盖外部已设置的变量」。
   **推论**：任何"读取 .env 再启动"的脚本都要注意这个方向，否则命令行传参会失效。
10. **`@McpToolParam.required` 默认是 `true`**：可选参数必须显式写 `required = false`。
    契约（生成的 JSON Schema）和实现对不上时，失败发生在框架校验层，
    业务日志里什么都不会有 —— 排查时容易一直在错误的层面上找原因。
11. **Docker 未在本机安装**：compose 文件已交付但无法在此机器实测；功能验证全部走 dev profile 完成。

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

## 十五、API 文档（OpenAPI 3.1）

服务启动后直接打开 **http://127.0.0.1:8080/swagger-ui.html**，
或取机器可读的规格 **http://127.0.0.1:8080/v3/api-docs**（可直接导入 Postman / Apifox）。

当前收录 **23 条路径 / 29 个接口**，分四组：

| 分组 | 接口数 | 说明 |
| --- | --- | --- |
| 知识库与文档 | 13 | 建库、上传、分块、任务查询、失败重试、队列状态 |
| 智能问答 | 8 | 同步问答、SSE 流式、多模态提问、检索诊断、会话管理 |
| 运维控制台 | 5 | 概览、AI 用量、审计日志、组件拓扑、配额 |
| 认证与账号 | 3 | 注册、登录、当前登录人 |

联调时点右上角 **Authorize**，把 `/api/auth/login` 返回的 `token` 粘进去即可
（无需手写 `Bearer ` 前缀）。除登录/注册外，其余接口均需鉴权。

**生产默认关闭**：`application-prod.yml` 里 `docmind.open-api.enabled=false`，
同时把 `springdoc.api-docs.enabled` / `springdoc.swagger-ui.enabled` 一并关掉
——只让 `OpenApiConfig` 这个 Bean 不装配是不够的，springdoc 的自动配置仍会注册端点。

## 十六、CI

`.github/workflows/ci.yml` 三个 job：

| job | 内容 |
| --- | --- |
| `backend` | `mvn test`（85 个用例）+ `package` + 上传 jar |
| `frontend` | `npm ci` + `npm test`（vitest 9 个用例）+ `npm run build` + 上传 dist |
| `compose` | `check_compose.py` 静态校验 + 校验器自检 + `docker compose config` |

需要真中间件的两个 `*IT` 不在 CI 里跑（日常构建不该依赖 Redis/Kafka），
用 `./tool/verify-middleware.sh` 单独触发。
