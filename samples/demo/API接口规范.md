# DocMind REST API 接口规范

## 一、通用约定

### 1.1 基础信息
- 基础路径：`/api`
- 协议：HTTP/1.1，生产强制 HTTPS
- 字符编码：UTF-8
- 请求与响应体：JSON（`Content-Type: application/json`）
- 文件上传：`multipart/form-data`，字段名 `file`

### 1.2 鉴权
除登录接口外，所有接口须在请求头携带：
```
Authorization: Bearer <jwt>
```
JWT 有效期 12 小时，过期后返回 401 且响应体 `code = UNAUTHORIZED`。
前端须在收到 401 时清除本地 token 并跳转登录页。

MCP 端点使用独立鉴权，请求头为 `X-MCP-ApiKey`，与 JWT 体系互不相通。

### 1.3 统一响应结构
```json
{
  "code": "OK",
  "message": "success",
  "data": {},
  "traceId": "a1b2c3d4e5f6"
}
```
| 字段 | 说明 |
| --- | --- |
| code | 业务码，`OK` 为成功，其余为失败码 |
| message | 人类可读描述，失败时可直接展示 |
| data | 业务数据，失败时为 null |
| traceId | 链路追踪 ID，日志排查时提供此值 |

### 1.4 常见错误码
| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | BAD_REQUEST | 参数校验失败 |
| 401 | UNAUTHORIZED | 未登录或 token 失效 |
| 403 | FORBIDDEN | 无该知识库权限 |
| 404 | NOT_FOUND | 资源不存在 |
| 409 | CONFLICT | 资源冲突，如文档重复上传 |
| 429 | RATE_LIMITED | 触发限流，响应头带 `Retry-After` |
| 500 | INTERNAL_ERROR | 服务内部错误，附 traceId |

---

## 二、认证接口

### POST /api/auth/login
请求：
```json
{ "username": "admin", "password": "******" }
```
响应 `data`：
```json
{ "token": "eyJhbGci...", "username": "admin", "role": "ADMIN", "expiresIn": 43200 }
```

### GET /api/auth/me
返回当前登录用户信息，用于前端刷新后恢复会话。

---

## 三、知识库接口

### GET /api/kb
返回当前用户可见的知识库列表。`data` 为数组：
```json
{
  "id": 97,
  "name": "企业制度中心",
  "description": "人事、行政、财务等内部制度",
  "visibility": "PRIVATE",
  "documentCount": 3,
  "chunkCount": 9,
  "createdAt": "2026-10-19T22:40:11"
}
```

### POST /api/kb
请求：`{ "name": "...", "description": "...", "visibility": "PRIVATE" }`
`visibility` 取值 `PRIVATE` / `PUBLIC`。同名知识库允许存在，建议业务侧自行校验。

### DELETE /api/kb/{id}
删除知识库及其下全部文档、切片与向量数据，不可恢复。
仅知识库创建者或 ADMIN 可操作。

---

## 四、文档接口

### POST /api/kb/{kbId}/documents
上传文档。`multipart/form-data`，字段 `file`。

响应：
```json
{
  "id": 1234,
  "fileName": "产品手册.md",
  "status": "PENDING",
  "duplicate": false
}
```
后端按内容 checksum 判重，重复上传返回 `duplicate: true` 且不重复计费。

状态流转：`PENDING → PARSING → CHUNKING → EMBEDDING → INDEXED`，
失败进入 `FAILED`，可调用重试接口。

### GET /api/kb/{kbId}/documents
分页查询，`page` 默认 0，`size` 默认 20，最大 100。

### POST /api/kb/documents/{docId}/retry
重新触发摄取流水线，仅 `FAILED` 状态允许调用。

### DELETE /api/kb/documents/{docId}
删除文档及其切片、向量。

### GET /api/kb/documents/{docId}/chunks
查看某篇文档切出来的所有切片原文，排查"为什么检索不到"时先看这里。

### GET /api/kb/queue
查看摄取队列水位与死信任务，运维排障入口。

---

## 五、检索接口

### GET /api/chat/{kbId}/retrieve
```
GET /api/chat/97/retrieve?q=差旅住宿标准&topK=6
```
单次只针对一个知识库。跨库检索请在前端多选后逐个调用并自行归并。

响应 `data` 为切片数组，含 `documentId`、`fileName`、`content`、`score`、`chunkIndex`。
此接口只召回不生成，用于检索调优：先在这里确认能不能召回，再去调切片参数。

---

## 六、问答接口

### POST /api/chat/{kbId}/ask（非流式）
```json
{ "question": "...", "conversationId": 88, "topK": 6 }
```
响应 `data` 含 `answer`、`sources`、`conversationId`。

### POST /api/chat/{kbId}/multimodal
`multipart/form-data`，字段 `question` 与 `file`（图片或音频）。
图片走视觉模型理解，音频先转写，再与知识库检索结果合并作答。

### 会话接口
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/chat/conversations | 新建会话，body 为 `{ "kbId": 97, "title": "..." }` |
| GET | /api/chat/conversations | 当前用户的会话列表，`limit` 默认 50 |
| GET | /api/chat/conversations/{id}/messages | 会话历史消息 |

### POST /api/chat/{kbId}/stream（SSE 流式）
响应 `Content-Type: text/event-sent`，事件序列：

| 事件 | 说明 |
| --- | --- |
| `meta` | 首个事件，携带 `conversationId` 与 `sources` 数组 |
| `delta` | 增量文本片段，可能有多个 |
| `done` | 结束标记 |
| `error` | 出错，`data` 为错误信息 |

注意：Spring 输出的事件行形如 `event:meta`（冒号后无空格），
解析时**不应假设冒号后一定有空格**，否则会静默丢弃全部帧。

---

## 七、运维接口

### GET /api/admin/usage
返回调用汇总：
```json
{ "calls": 128, "promptTokens": 45210, "completionTokens": 9833, "avgLatencyMs": 1820 }
```

### GET /api/admin/audit
审计日志，记录登录、上传、删除、权限变更等操作。

### 其他运维端点
| 路径 | 说明 |
| --- | --- |
| GET /api/admin/overview | 总览：知识库数、文档数、今日调用量 |
| GET /api/admin/quota | 配额使用情况 |
| GET /api/admin/system | 系统信息：profile、JVM、中间件连通状态 |

---

## 八、限流

| 场景 | 维度 | 限额 |
| --- | --- | --- |
| 登录 | IP | 10 次 / 分钟 |
| 普通查询 | 用户 | 120 次 / 分钟 |
| 问答 | 用户 | 20 次 / 分钟 |
| 文档上传 | 用户 | 30 次 / 分钟 |

超限返回 429，响应头 `Retry-After` 给出建议重试秒数。
