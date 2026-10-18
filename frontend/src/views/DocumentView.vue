<template>
  <div>
    <div class="card" style="margin-bottom: 16px">
      <div class="page-title">文档上传 · 支持 PDF / Word / Excel / PPT / 图片 / 音频 / 视频</div>
      <el-upload
        drag
        multiple
        :action="uploadAction"
        :headers="headers"
        :show-file-list="true"
        :on-success="onUploaded"
        :on-error="onError"
      >
        <div style="padding: 20px">
          <div style="font-size: 14px">拖拽文件到此处，或点击上传</div>
          <div class="muted" style="margin-top: 6px">扫描件 PDF 与图片会自动走视觉模型做 OCR</div>
        </div>
      </el-upload>
    </div>

    <div class="card">
      <div class="page-title">文档列表</div>
      <el-table :data="docs" style="width: 100%">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="名称" min-width="200" show-overflow-tooltip />
        <el-table-column prop="modality" label="模态" width="90">
          <template #default="{ row }">
            <el-tag size="small">{{ row.modality }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="180">
          <template #default="{ row }">
            <el-progress :percentage="row.progress" :status="row.status === 'FAILED' ? 'exception' : (row.status === 'INDEXED' ? 'success' : undefined)" />
          </template>
        </el-table-column>
        <el-table-column prop="chunkCount" label="切片" width="70" />
        <el-table-column prop="errorMsg" label="错误" min-width="160" show-overflow-tooltip />
        <el-table-column label="操作" width="240">
          <template #default="{ row }">
            <el-button link type="primary" @click="viewChunks(row)">切片</el-button>
            <el-button link type="warning" @click="retry(row)">重试</el-button>
            <el-button link type="info" @click="viewTasks(row)">任务</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="chunkVisible" :title="'切片预览 - ' + currentDoc.name" width="70%">
      <el-empty v-if="!chunks.length" description="暂无切片" />
      <div v-for="c in chunks" :key="c.id" style="margin-bottom: 12px; border-bottom: 1px dashed #ebeef5; padding-bottom: 10px">
        <div class="muted">#{{ c.chunkIndex }} · {{ c.contentType }} · {{ c.tokenCount }} tokens
          <span v-if="c.pageNo"> · 第 {{ c.pageNo }} 页</span>
        </div>
        <div class="chunk-content">{{ c.content }}</div>
      </div>
    </el-dialog>

    <el-dialog v-model="taskVisible" title="摄取任务" width="60%">
      <el-table :data="tasks" size="small">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column prop="stage" label="阶段" width="100" />
        <el-table-column prop="attempt" label="尝试" width="70" />
        <el-table-column prop="costMs" label="耗时(ms)" width="100" />
        <el-table-column prop="error" label="错误" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import { useUserStore } from '../stores/user'

const route = useRoute()
const store = useUserStore()
const kbId = computed(() => route.params.id)
const docs = ref([])
const chunks = ref([])
const tasks = ref([])
const chunkVisible = ref(false)
const taskVisible = ref(false)
const currentDoc = ref({ name: '' })
let timer = null

const uploadAction = computed(() => `/api/kb/${kbId.value}/documents`)
const headers = computed(() => ({ Authorization: 'Bearer ' + store.token }))

onMounted(() => {
  load()
  timer = setInterval(load, 3000)
})
onUnmounted(() => clearInterval(timer))

async function load() {
  try {
    docs.value = await http.get(`/kb/${kbId.value}/documents`)
  } catch (e) { /* 忽略轮询错误 */ }
}

function statusType(status) {
  return { INDEXED: 'success', FAILED: 'danger', PENDING: 'info' }[status] || 'warning'
}

function onUploaded() {
  ElMessage.success('已提交处理')
  load()
}
function onError(e) {
  ElMessage.error('上传失败：' + (e.message || ''))
}

async function viewChunks(row) {
  currentDoc.value = row
  chunks.value = await http.get(`/kb/documents/${row.id}/chunks`)
  chunkVisible.value = true
}

async function viewTasks(row) {
  currentDoc.value = row
  tasks.value = await http.get(`/kb/documents/${row.id}/tasks`)
  taskVisible.value = true
}

async function retry(row) {
  await http.post(`/kb/documents/${row.id}/retry`)
  ElMessage.success('已重新入队')
  load()
}

async function remove(row) {
  await ElMessageBox.confirm('确定删除该文档及其向量？', '提示', { type: 'warning' })
  await http.delete(`/kb/documents/${row.id}`)
  ElMessage.success('已删除')
  load()
}
</script>
