import axios from 'axios'
import { useUserStore } from '../stores/user'
import { ElMessage } from 'element-plus'

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
            const event = chunk.match(/^event: (.*)$/m)?.[1]
            const data = chunk.match(/^data: (.*)$/m)?.[1]
            if (!event) continue
            if (event === 'meta') onMeta && onMeta(safeJson(data))
            else if (event === 'delta') onDelta && onDelta(data)
            else if (event === 'error') onError && onError(data)
            else if (event === 'done') onDone && onDone(safeJson(data))
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
