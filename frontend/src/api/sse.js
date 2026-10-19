/**
 * SSE 帧解析（零依赖纯函数，独立成模块以便单测直接覆盖）。
 *
 * 两个必须守住的行为（回归测试 sse.test.js）：
 * 1. SSE 规范（whatwg）里冒号后的空格是可选的。Spring 的 ServerSentEvent
 *    输出形如 "event:meta"/"data:xxx"（无空格），而某些网关/手写服务会带空格。
 *    正则一旦写死带空格，Spring 的每一帧都匹配失败 → 调用方 if (!frame) continue
 *    全部静默丢弃 —— 症状是流连接开着、光标闪着、一个字都不出现、控制台零报错
 *    （网络层完全正常，curl 直连/走代理都能看到帧，极难定位）。
 * 2. data 值含换行时，规范要求拆成多个 data: 行、由客户端用 \n 重新拼接。
 *    只取第一行的写法会静默丢掉多行 token 里换行后的内容。
 */
export function parseSseFrame(chunk) {
  const event = chunk.match(/^event:[ \t]?(.*)$/m)?.[1]
  if (!event) return null
  const dataLines = [...chunk.matchAll(/^data:[ \t]?(.*)$/gm)].map((m) => m[1])
  return { event, data: dataLines.join('\n') }
}

/**
 * 把一段可能包含多个完整帧的字节流文本拆成帧列表。
 * 流式读取时字节块边界不一定对齐帧边界，调用方需要自己维护半帧缓冲：
 *   buffer += decoder.decode(value, { stream: true })
 *   const [frames, rest] = splitSseChunks(buffer)   // rest 留给下一块
 *   buffer = rest
 */
export function splitSseChunks(buffer) {
  const parts = buffer.split('\n\n')
  const rest = parts.pop() ?? ''
  return [parts, rest]
}
