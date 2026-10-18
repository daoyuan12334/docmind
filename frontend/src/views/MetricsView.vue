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
import http from '../api/http'
import * as echarts from 'echarts'

const rows = ref([])
const loading = ref(false)
const auto = ref(false)
const chartEl = ref(null)
let chart = null
let timer = null

const WATCHED = [
  'docmind_ai_requests_total',
  'docmind_ai_tokens_total',
  'docmind_ai_duration_seconds_count',
  'docmind_ingest_documents_total',
  'docmind_ingest_duration_seconds_count',
  'docmind_retrieval_duration_seconds_count',
  'docmind_mcp_calls_total',
  'docmind_ingest_queue',
  'http_server_requests_seconds_count',
  'jvm_memory_used_bytes',
  'process_cpu_usage'
]

async function load() {
  loading.value = true
  try {
    // Actuator 单指标查询需要鉴权，这里用管理端聚合接口 + 直连 Prometheus 端点兜底
    const names = await http.get('/actuator/metrics').then((d) => (d?.names || []).filter((n) => WATCHED.includes(n)))
    const picked = names.length ? names : WATCHED
    const out = []
    for (const n of picked) {
      try {
        const m = await http.get(`/actuator/metrics/${n}`)
        const total = (m?.measurements || []).reduce((a, b) => a + (b?.value ?? 0), 0)
        out.push({ name: n, value: fmt(total, n) })
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

function fmt(v, name) {
  if (name.endsWith('_bytes')) return (v / 1024 / 1024).toFixed(1) + ' MB'
  if (name === 'process_cpu_usage') return (v * 100).toFixed(2) + ' %'
  if (name.includes('duration')) return v.toFixed(2) + ' 次'
  return Number.isInteger(v) ? v.toLocaleString() : v.toFixed(2)
}

async function renderUsage() {
  let list = []
  try {
    list = await http.get('/admin/usage', { params: { hours: 24 } })
  } catch {
    return
  }
  await nextTick()
  if (!chartEl.value) return
  chart = chart || echarts.init(chartEl.value)
  const buckets = Array.isArray(list) ? list : list?.items || []
  chart.setOption({
    grid: { left: 48, right: 16, top: 24, bottom: 32 },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: buckets.map((b) => b.bucket || b.hour || b.time || ''),
      axisLabel: { rotate: 30, fontSize: 10 }
    },
    yAxis: { type: 'value', name: 'tokens', nameTextStyle: { fontSize: 10 } },
    series: [
      {
        type: 'bar',
        data: buckets.map((b) => b.tokens ?? b.totalTokens ?? 0),
        itemStyle: { color: '#409eff', borderRadius: [3, 3, 0, 0] }
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
