<template>
  <div>
    <div class="card" style="margin-bottom: 16px">
      <div class="page-title">检索实验室（只召回、不生成，用于调优切片与权重）</div>
      <el-form :inline="true">
        <el-form-item label="知识库">
          <el-select v-model="kbId" style="width: 220px" placeholder="选择知识库">
            <el-option v-for="b in bases" :key="b.id" :label="b.name" :value="b.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="TopK">
          <el-input-number v-model="topK" :min="1" :max="30" />
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="query" style="width: 320px" placeholder="输入要检索的问题" @keyup.enter="search" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="search">检索</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="card">
      <el-empty v-if="!results.length && searched" description="没有命中内容" />
      <div v-for="(r, i) in results" :key="r.chunkId" style="margin-bottom: 14px; padding-bottom: 12px; border-bottom: 1px dashed #ebeef5">
        <div style="display: flex; justify-content: space-between; align-items: center">
          <div>
            <el-tag size="small" type="primary">#{{ i + 1 }}</el-tag>
            <span style="margin-left: 8px; font-weight: 600">{{ r.docName }}</span>
            <span class="muted" v-if="r.pageNo"> · 第 {{ r.pageNo }} 页</span>
          </div>
          <div class="muted">
            综合 {{ r.score.toFixed(4) }} | 向量 {{ r.vectorScore.toFixed(4) }} | 关键词 {{ r.keywordScore.toFixed(4) }}
          </div>
        </div>
        <div class="chunk-content" style="margin-top: 6px">{{ r.content }}</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import http from '../api/http'

const bases = ref([])
const kbId = ref(null)
const topK = ref(6)
const query = ref('')
const results = ref([])
const loading = ref(false)
const searched = ref(false)

onMounted(async () => {
  bases.value = await http.get('/kb')
  if (bases.value.length) kbId.value = bases.value[0].id
})

async function search() {
  if (!kbId.value || !query.value) return
  loading.value = true
  searched.value = true
  try {
    results.value = await http.get(`/chat/${kbId.value}/retrieve`, { params: { q: query.value, topK: topK.value } })
  } finally {
    loading.value = false
  }
}
</script>
