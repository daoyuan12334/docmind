import { describe, it, expect } from 'vitest'
import { parseSseFrame, splitSseChunks } from './sse'

// 守住 sse.js Javadoc 里写的两个行为。这两个行为都出过真 bug：
// Bug 1（2026-10-19 实测）：正则写死 "event: " 带空格，Spring 输出的
//   "event:meta"（无空格）每帧匹配失败被静默丢弃，流式问答一个字都不显示，
//   且控制台零报错 —— 网络层完全正常，极难定位。
// Bug 2（同日顺手加固）：data 含换行时被拆成多行 data:，只取第一行会丢内容。

describe('parseSseFrame', () => {
  it('解析 Spring 风格的无空格帧（event:meta）', () => {
    const f = parseSseFrame('event:meta\ndata:{"sources":[],"conversationId":130}')
    expect(f).toEqual({ event: 'meta', data: '{"sources":[],"conversationId":130}' })
  })

  it('解析带空格的规范宽松帧（event: delta）', () => {
    const f = parseSseFrame('event: delta\ndata: 你好')
    expect(f).toEqual({ event: 'delta', data: '你好' })
  })

  it('data 为纯文本 token（delta 不带 JSON 引号）', () => {
    const f = parseSseFrame('event:delta\ndata:差')
    expect(f).toEqual({ event: 'delta', data: '差' })
  })

  it('data 含换行时多行 data: 用 \\n 重新拼接，不丢内容', () => {
    const f = parseSseFrame('event:delta\ndata:第一行\ndata:第二行')
    expect(f).toEqual({ event: 'delta', data: '第一行\n第二行' })
  })

  it('event 缺失的帧返回 null（调用方 continue 跳过）', () => {
    expect(parseSseFrame('data:只有数据没有事件名')).toBeNull()
    expect(parseSseFrame(': comment 注释帧')).toBeNull()
  })

  it('event 缺失但 data 存在时也必须返回 null，而不是拿 data 充当事件名', () => {
    // 曾经的错误写法：先匹配 data 再判断，会把 data 值误当 event
    const f = parseSseFrame('data:meta')
    expect(f).toBeNull()
  })

  it('CRLF 行尾（\r\n）也能解析', () => {
    const f = parseSseFrame('event:delta\r\ndata:差\r')
    expect(f).toEqual({ event: 'delta', data: '差' })
  })
})

describe('splitSseChunks', () => {
  it('按空行拆帧，最后一段不完整帧留给下一次（缓冲语义）', () => {
    // 模拟一次真实读取：meta 完整帧 + 半个 delta 帧
    const buf = 'event:meta\ndata:{}\n\nevent:delta\ndata:差'
    const [frames, rest] = splitSseChunks(buf)
    expect(frames).toEqual(['event:meta\ndata:{}'])
    expect(rest).toBe('event:delta\ndata:差')

    // 下一块补全后，半帧可以正常解析
    const [frames2, rest2] = splitSseChunks(rest + '\n\n')
    expect(frames2).toEqual(['event:delta\ndata:差'])
    expect(rest2).toBe('')
  })

  it('多个连续帧一次拆开', () => {
    const [frames, rest] = splitSseChunks('a\n\nb\n\nc\n\n')
    expect(frames).toEqual(['a', 'b', 'c'])
    expect(rest).toBe('')
  })
})
