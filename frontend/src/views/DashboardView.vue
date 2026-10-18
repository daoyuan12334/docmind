<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="6" v-for="s in stats" :key="s.label">
        <div class="card" style="margin-bottom: 16px">
          <div class="stat-value">{{ s.value }}</div>
          <div class="stat-label">{{ s.label }}</div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <el-col :span="14">
        <div class="card">
          <div class="page-title">我的知识库</div>
          <el-table :data="bases" style="width: 100%" @row-click="gotoKb">
            <el-table-column prop="name" label="名称" min-width="160" />
            <el-table-column prop="visibility" label="可见性" width="100">
              <template #default="{ row }">
                <el-tag size="small">{{ row.visibility }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="docCount" label="文档" width="80" />
            <el-table-column prop="chunkCount" label="切片" width="80" />
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button link type="primary" @click.stop="gotoKb(row)">进入</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-col>
      <el-col :span="10">
        <div class="card">
          <div class="page-title">运行状态</div>
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="运行环境">{{ system.profile || '-' }}</el-descriptions-item>
            <el-descriptions-item label="消息总线">{{ system.messageBus || '-' }}</el-descriptions-item>
            <el-descriptions-item label="缓存">{{ system.cache || '-' }}</el-descriptions-item>
            <el-descriptions-item label="对象存储">{{ system.storage || '-' }}</el-descriptions-item>
            <el-descriptions-item label="对话模型">{{ system.chatModel || '-' }}</el-descriptions-item>
            <el-descriptions-item label="向量模型">{{ system.embeddingModel || '-' }}</el-descriptions-item>
            <el-descriptions-item label="视觉模型">{{ system.visionModel || '-' }}</el-descriptions-item>
            <el-descriptions-item label="语音模型">{{ system.omniModel || '-' }}</el-descriptions-item>
          </el-descriptions>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import http from '../api/http'
import { useUserStore } from '../stores/user'

const router = useRouter()
const store = useUserStore()
const bases = ref([])
const system = ref({})
const stats = ref([])

onMounted(async () => {
  bases.value = await http.get('/kb')
  if (store.isAdmin) {
    const overview = await http.get('/admin/overview')
    system.value = await http.get('/admin/system')
    stats.value = [
      { label: '知识库', value: overview.knowledgeBases },
      { label: '文档总数', value: overview.documents },
      { label: '切片总数', value: overview.chunks },
      { label: '运行中任务', value: overview.runningTasks }
    ]
  } else {
    stats.value = [
      { label: '可访问知识库', value: bases.value.length },
      { label: '文档总数', value: bases.value.reduce((s, b) => s + b.docCount, 0) },
      { label: '切片总数', value: bases.value.reduce((s, b) => s + b.chunkCount, 0) },
      { label: '我的角色', value: store.role }
    ]
  }
})

function gotoKb(row) {
  router.push('/kb/' + row.id)
}
</script>
