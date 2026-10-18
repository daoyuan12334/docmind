# DocMind API 速查

所有接口前缀 `/api`，除标注 PUBLIC 外均需 `Authorization: Bearer <token>`。
统一响应体：`{ "code": 0, "message": "ok", "data": ..., "traceId": "..." }`，失败时 `code` 为错误码。

## 认证

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/register` | 注册；**系统第一个用户自动成为 ADMIN** |
| POST | `/api/auth/login` | 登录，返回 `token / userId / role` |
| GET | `/api/auth/me` | 当前用户信息 |

## 知识库

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/kb` | 创建 `{name, description, visibility}` |
| GET | `/api/kb` | 我有权限的知识库列表 |
| GET | `/api/kb/{id}` | 详情 |
| PUT | `/api/kb/{id}` | 更新 |
| DELETE | `/api/kb/{id}` | 删除（软删） |
| GET | `/api/kb/{id}/documents` | 文档列表 |
| POST | `/api/kb/{id}/documents` | 上传（multipart，字段名 `file`）→ `{docId, taskId, duplicate}` |
| GET | `/api/kb/documents/{docId}` | 文档详情（含 status / progress / errorMsg / summary） |
| GET | `/api/kb/documents/{docId}/chunks` | 切片预览 |
| GET | `/api/kb/documents/{docId}/tasks` | 摄取任务与重试记录 |
| POST | `/api/kb/documents/{docId}/retry` | 手工重跑 |
| DELETE | `/api/kb/documents/{docId}` | 删除文档 |
| GET | `/api/kb/queue` | 队列状态 |

文档状态机：`PENDING → PARSING → CHUNKING → EMBEDDING → INDEXED / FAILED`

## 问答 / 检索

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/chat/{kbId}/stream` | **SSE 流式**问答，事件 `meta / delta / done / error` |
| POST | `/api/chat/{kbId}/ask` | 同步问答，返回 `answer / citations / promptTokens / completionTokens / latencyMs` |
| POST | `/api/chat/{kbId}/multimodal` | 带图片/语音附件提问（multipart） |
| GET | `/api/chat/{kbId}/retrieve?q=&topK=` | 纯检索调试，返回向量分/关键词分/融合分 |
| POST | `/api/chat/conversations` | 新建会话 |
| GET | `/api/chat/conversations` | 会话列表 |
| GET | `/api/chat/conversations/{id}/messages` | 历史消息 |
| DELETE | `/api/chat/conversations/{id}` | 删除会话 |

## 运维（需 ADMIN）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/overview` | `{users, knowledgeBases, documents, indexed, failed, chunks, runningTasks}` |
| GET | `/api/admin/usage?hours=24` | Token 消耗分桶统计 |
| GET | `/api/admin/audit?limit=50` | 审计日志 |
| GET | `/api/admin/system` | 当前生效的中间件适配器与模型 |
| GET | `/api/admin/quota` | `{usedToday, limit, remaining, requestsPerMinute}` |

## 监控

| 路径 | 说明 |
| --- | --- |
| `/actuator/health` | 健康检查（compose healthcheck 依赖） |
| `/actuator/prometheus` | Micrometer 指标 |
| `/actuator/metrics`、`/actuator/info` | 其它（生产建议加鉴权） |

## MCP

- 端点：`POST /mcp`（Streamable HTTP，MCP 2025-06-18）
- 鉴权：配置 `docmind.mcp.api-key` 后需请求头 `X-MCP-ApiKey`
- 白名单：`docmind.mcp.expose-kb-ids`（逗号分隔 KB ID，空 = 全部拒绝）
- 工具：`list_knowledge_bases` / `search_knowledge_base` / `ask_knowledge_base` / `get_document_summary`

握手示例：

```bash
# 1) initialize，从响应头取 Mcp-Session-Id
curl -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"demo","version":"1"}}}'

# 2) 通知已初始化
curl -X POST http://localhost:8080/mcp -H 'Mcp-Session-Id: <上一步的 id>' \
  -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

# 3) 列工具 / 调工具
curl -X POST http://localhost:8080/mcp -H 'Mcp-Session-Id: <id>' \
  -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
```
