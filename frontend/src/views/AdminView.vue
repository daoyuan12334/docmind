<template>
  <div class="admin-page">
    <el-row :gutter="16" v-loading="loading">
      <el-col :span="6" v-for="c in cards" :key="c.label">
        <el-card shadow="never" class="stat">
          <div class="stat-label">{{ c.label }}</div>
          <div class="stat-value">{{ c.value }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="10">
        <el-card shadow="never">
          <template #header>
            <span>系统拓扑（当前生效的中间件适配器）</span>
            <el-button size="small" text style="float: right" @click="loadAll">刷新</el-button>
          </template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="运行 Profile">{{ system.profile }}</el-descriptions-item>
            <el-descriptions-item label="缓存">{{ system.cache }}</el-descriptions-item>
            <el-descriptions-item label="消息总线">{{ system.messageBus }}</el-descriptions-item>
            <el-descriptions-item label="对象存储">{{ system.storage }}</el-descriptions-item>
            <el-descriptions-item label="对话模型">{{ system.chatModel }}</el-descriptions-item>
            <el-descriptions-item label="向量模型">{{ system.embeddingModel }}</el-descriptions-item>
            <el-descriptions-item label="视觉模型">{{ system.visionModel }}</el-descriptions-item>
            <el-descriptions-item label="语音/全模态模型">{{ system.omniModel }}</el-descriptions-item>
            <el-descriptions-item label="MCP 暴露知识库">
              {{ system.mcpExposedKbIds || '（未配置，MCP 一律拒绝）' }}
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>

      <el-col :span="14">
        <el-card shadow="never">
          <template #header>审计日志（近 {{ audit.length }} 条）</template>
          <el-table :data="audit" size="small" max-height="360">
            <el-table-column prop="createdAt" label="时间" width="170">
              <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column prop="username" label="用户" width="110" />
            <el-table-column prop="action" label="动作" width="150" />
            <el-table-column prop="resource" label="对象" min-width="140" show-overflow-tooltip />
            <el-table-column prop="ip" label="来源 IP" width="140" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="24">
        <el-card shadow="never">
          <template #header>配额与限流</template>
          <el-table :data="quotaRows" size="small" style="max-width: 520px">
            <el-table-column prop="k" label="项" width="220" />
            <el-table-column prop="v" label="值" />
          </el-table>
          <el-alert
            type="warning"
            show-icon
            :closable="false"
            style="margin-top: 12px"
            title="生产建议"
            description="把 JWT_SECRET、DB_PASSWORD、REDIS_PASSWORD、MINIO_SECRET_KEY 换成强随机值；MCP 端点必须配置 DOCMIND_MCP_API_KEY 并显式列出可访问的知识库 ID。"
          />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import http from '../api/http'

const loading = ref(false)
const overview = ref({})
const system = ref({})
const audit = ref([])
const quota = ref({})

const cards = computed(() => [
  { label: '知识库', value: overview.value.knowledgeBases ?? 0 },
  { label: '文档', value: overview.value.documents ?? 0 },
  { label: '切片', value: overview.value.chunks ?? 0 },
  { label: '用户', value: overview.value.users ?? 0 },
  { label: '已索引', value: overview.value.indexed ?? 0 },
  { label: '失败 / 运行中', value: `${overview.value.failed ?? 0} / ${overview.value.runningTasks ?? 0}` }
])

const quotaRows = computed(() => [
  { k: '每用户每日 Token 配额', v: quota.value.limit ?? '-' },
  { k: '每分钟请求数上限', v: quota.value.requestsPerMinute ?? '-' },
  { k: '今日已用 Token', v: quota.value.usedToday ?? 0 },
  { k: '剩余配额', v: quota.value.remaining ?? '-' }
])

function fmtTime(t) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 19)
}

async function loadAll() {
  loading.value = true
  try {
    const [o, s, a, q] = await Promise.all([
      http.get('/admin/overview').catch(() => ({})),
      http.get('/admin/system').catch(() => ({})),
      http.get('/admin/audit', { params: { limit: 50 } }).catch(() => []),
      http.get('/admin/quota').catch(() => ({}))
    ])
    overview.value = o || {}
    system.value = s || {}
    audit.value = Array.isArray(a) ? a : []
    quota.value = q || {}
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.admin-page {
  padding: 4px;
}
.stat {
  text-align: center;
}
.stat-label {
  font-size: 13px;
  color: #909399;
}
.stat-value {
  font-size: 26px;
  font-weight: 600;
  margin-top: 6px;
  color: #303133;
}
</style>
