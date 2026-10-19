<template>
  <div class="metrics-page">
    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="可观测性面板"
      description="指标由 Micrometer 暴露在 /actuator/prometheus，由 Prometheus 抓取、Grafana 展示。下面直接读取 Actuator 端点，无需 Grafana 也能看实时数据。"
      style="margin-bottom: 16px"
    />

    <el-row :gutter="16">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>关键指标（实时采样）</template>
          <el-table :data="rows" size="small" max-height="420" v-loading="loading">
            <el-table-column prop="name" label="指标" min-width="240" show-overflow-tooltip />
            <el-table-column label="值" width="180" align="right">
              <template #default="{ row }">
                <span class="mono">{{ row.value }}</span>
              </template>
            </el-table-column>
          </el-table>
          <div class="foot">
            <el-button size="small" @click="load" :loading="loading">刷新</el-button>
            <el-switch v-model="auto" active-text="60s 自动刷新" style="margin-left: 12px" />
          </div>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never">
          <template #header>观测栈接入方式</template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="Prometheus 抓取">
              <code>GET http://&lt;backend&gt;:8080/actuator/prometheus</code>
            </el-descriptions-item>
            <el-descriptions-item label="Grafana 地址">
              <code>http://localhost:3000</code>（compose 默认 admin / GRAFANA_PASSWORD）
            </el-descriptions-item>
            <el-descriptions-item label="内置看板">
              DocMind 私有文档智能系统 · 总览（随 compose 自动导入）
            </el-descriptions-item>
            <el-descriptions-item label="链路追踪">
              MDC traceId 贯穿 HTTP → 异步线程池 → Kafka 消费，响应头 X-Trace-Id 可回溯
            </el-descriptions-item>
            <el-descriptions-item label="自定义指标">
              docmind.ai.requests / docmind.ai.tokens / docmind.ai.duration /
              docmind.ingest.duration / docmind.retrieval.duration / docmind.mcp.calls
            </el-descriptions-item>
          </el-descriptions>

          <el-divider content-position="left">Token 消耗（近 24h）</el-divider>
          <div ref="chartEl" class="chart"></div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import http, { actuator } from '../api/http'
import * as echarts from 'echarts'

const rows = ref([])
const loading = ref(false)
const auto = ref(false)
const chartEl = ref(null)
let chart = null
let timer = null

// 注意：这里写的是 **Actuator 原生名（点分）**，不是 Prometheus 名字。
// /actuator/metrics/<name> 只认原生名；Prometheus 端点里那种下划线形式
// 是抓取时做名字转换后的产物。早期版本直接拿 `docmind_ai_requests_total`
// 这类下划线名去请求这个端点，结果 12 个指标全部 404 —— 页面骨架正常渲染、
// 但数据是空的，控制台一堆请求失败，不细看根本发现不了。
//
// 用「前缀过滤」而不是写死清单：以后新增业务指标（docmind.xxx）会自动出现，
// 不用回来改代码。
const INTEREST_PREFIX = [
  'docmind.',
  'http.server.requests',
  'jvm.memory.used',
  'jvm.threads.live',
  'process.cpu.usage'
]

async function load() {
  loading.value = true
  try {
    // 走 actuator 实例而不是 http：http 带 /api 前缀，会把请求打到
    // /api/actuator/metrics（后端无此映射 → 500）。
    const all = await actuator.get('/actuator/metrics').then((d) => d?.names || [])
    const picked = all
      .filter((n) => INTEREST_PREFIX.some((p) => n === p || n.startsWith(p)))
      .sort()
    const out = []
    for (const real of picked) {
      try {
        const m = await actuator.get(`/actuator/metrics/${encodeURIComponent(real)}`)
        const total = (m?.measurements || []).reduce((a, b) => a + (b?.value ?? 0), 0)
        out.push({ name: real, value: fmt(total, real) })
      } catch {
        /* 单个指标不可用不影响整体 */
      }
    }
    rows.value = out
    await renderUsage()
  } finally {
    loading.value = false
  }
}

// 格式化依据的是 Actuator 原生名
function fmt(v, name) {
  if (name.includes('memory')) return (v / 1024 / 1024).toFixed(1) + ' MB'
  if (name.includes('cpu.usage')) return (v * 100).toFixed(2) + ' %'
  if (name.includes('duration')) return v.toFixed(2) + ' 次'
  if (name.includes('queue') || name.includes('hits')) return v.toLocaleString()
  return Number.isInteger(v) ? v.toLocaleString() : v.toFixed(2)
}

async function renderUsage() {
  let summary = null
  try {
    summary = await http.get('/admin/usage', { params: { hours: 24 } })
  } catch {
    return
  }
  await nextTick()
  if (!chartEl.value) return
  chart = chart || echarts.init(chartEl.value)

  // /admin/usage 返回的是近 24h 的**汇总对象**
  //   { calls, promptTokens, completionTokens, avgLatencyMs }
  // 不是按小时分桶的数组。早先这里按 buckets 数组去 `.map`，
  // 拿到的是 {}，于是 `list?.items || []` 恒为空 → 图表永远一片空白。
  const d = summary || {}
  const bars = [
    { label: 'Prompt tokens', value: d.promptTokens ?? 0 },
    { label: 'Completion tokens', value: d.completionTokens ?? 0 },
    { label: '调用次数', value: d.calls ?? 0 }
  ]
  chart.setOption({
    grid: { left: 64, right: 16, top: 24, bottom: 32 },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: bars.map((b) => b.label),
      axisLabel: { rotate: 20, fontSize: 10 }
    },
    yAxis: { type: 'value', name: '近 24h', nameTextStyle: { fontSize: 10 } },
    series: [
      {
        type: 'bar',
        data: bars.map((b) => b.value),
        barWidth: '45%',
        itemStyle: { color: '#409eff', borderRadius: [3, 3, 0, 0] },
        label: { show: true, position: 'top', fontSize: 10 }
      }
    ]
  })
}

watch(auto, (on) => {
  clearInterval(timer)
  if (on) timer = setInterval(load, 60000)
})

onMounted(load)
onBeforeUnmount(() => {
  clearInterval(timer)
  chart && chart.dispose()
})
</script>

<style scoped>
.metrics-page {
  padding: 4px;
}
.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
}
.foot {
  margin-top: 12px;
  display: flex;
  align-items: center;
}
.chart {
  height: 260px;
  width: 100%;
}
code {
  background: #f5f7fa;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 12px;
}
</style>
