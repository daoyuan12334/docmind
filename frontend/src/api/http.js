import axios from 'axios'
import { useUserStore } from '../stores/user'
import { ElMessage } from 'element-plus'
import { parseSseFrame, splitSseChunks } from './sse'

const http = axios.create({
  baseURL: '/api',
  timeout: 60000
})

http.interceptors.request.use((config) => {
  const store = useUserStore()
  if (store.token) {
    config.headers.Authorization = 'Bearer ' + store.token
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    // 后端统一包装 {code, message, data}
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return body.data
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body
  },
  (error) => {
    const status = error.response?.status
    const msg = error.response?.data?.message || error.message
    if (status === 401) {
      const store = useUserStore()
      store.logout()
      window.location.href = '/login'
    }
    ElMessage.error(msg || '网络异常')
    return Promise.reject(error)
  }
)

/**
 * 访问 Actuator 管理端点专用。
 *
 * 为什么不能用上面的 `http`：`http` 的 baseURL 是 '/api'，
 * `http.get('/actuator/metrics')` 会拼成 /api/actuator/metrics。
 * 后端根本没有这个映射，请求会掉进静态资源处理器，最终返回
 * 500 "No static resource api/actuator/metrics" ——
 * 页面骨架照常渲染、表格一行数据都没有，控制台只看到 500，
 * 从表象完全看不出是前缀加错了。
 *
 * ⚠ 注释里千万别写「加粗星号 + 斜杠 + 路径」这种写法：其中紧挨着的
 * 那个注释结束符会提前闭合这个块注释，后面的中文全被当成代码，
 * 构建时报 "Unexpected character"，而且报错位置很有迷惑性。
 *
 * Actuator 是 Spring Boot 的标准管理端点，**不属于业务 API**，
 * 所以这里 baseURL 必须为空。
 * 注意响应**没有**后端统一包装 {code,message,data}，直接就是 JSON 本体。
 */
export const actuator = axios.create({ baseURL: '', timeout: 20000 })

actuator.interceptors.request.use((config) => {
  const store = useUserStore()
  if (store.token) {
    config.headers.Authorization = 'Bearer ' + store.token
  }
  return config
})

// 与 http 不同：Actuator 的响应**没有** {code,message,data} 包装，
// 直接就是 JSON 本体，所以这里把 AxiosResponse 解包成 data。
// 不解包的话调用方拿到的还是 AxiosResponse，`res.names` 恒为 undefined，
// 页面表格会「无报错但永远空」—— 比直接报错更难查。
actuator.interceptors.response.use((response) => response.data)

/** SSE 流式对话：用 fetch 手动读事件流，axios 不支持流式。 */
export function streamChat(kbId, payload, { onMeta, onDelta, onDone, onError }) {
  const store = useUserStore()
  return fetch(`/api/chat/${kbId}/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: 'Bearer ' + store.token
    },
    body: JSON.stringify(payload)
  })
    .then((response) => {
      if (!response.ok) throw new Error('HTTP ' + response.status)
      const reader = response.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      const read = () =>
        reader.read().then(({ done, value }) => {
          if (done) {
            onDone && onDone()
            return
          }
          buffer += decoder.decode(value, { stream: true })
          const chunks = buffer.split('\n\n')
          buffer = chunks.pop()
          for (const chunk of chunks) {
            const frame = parseSseFrame(chunk)
            if (!frame) continue
            if (frame.event === 'meta') onMeta && onMeta(safeJson(frame.data))
            else if (frame.event === 'delta') onDelta && onDelta(frame.data)
            else if (frame.event === 'error') onError && onError(frame.data)
            else if (frame.event === 'done') onDone && onDone(safeJson(frame.data))
          }
          return read()
        })
      return read()
    })
    .catch((e) => {
      onError && onError(String(e.message || e))
      throw e
    })
}

function safeJson(text) {
  try {
    return JSON.parse(text)
  } catch {
    return {}
  }
}

export default http
