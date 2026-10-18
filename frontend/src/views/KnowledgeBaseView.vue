<template>
  <div>
    <div class="card" style="margin-bottom: 16px">
      <div class="page-title">知识库</div>
      <el-form :inline="true" :model="form">
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="例如：产品手册" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" placeholder="选填" />
        </el-form-item>
        <el-form-item label="可见性">
          <el-select v-model="form.visibility" style="width: 120px">
            <el-option label="私有" value="PRIVATE" />
            <el-option label="团队" value="TEAM" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="create">新建</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="card">
      <el-table :data="bases" style="width: 100%">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        <el-table-column prop="visibility" label="可见性" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.visibility === 'TEAM' ? 'success' : 'info'">{{ row.visibility }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="docCount" label="文档" width="70" />
        <el-table-column prop="chunkCount" label="切片" width="70" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button link type="primary" @click="$router.push('/kb/' + row.id)">文档</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const bases = ref([])
const form = reactive({ name: '', description: '', visibility: 'PRIVATE' })

onMounted(load)

async function load() {
  bases.value = await http.get('/kb')
}

async function create() {
  if (!form.name) {
    ElMessage.warning('请填写名称')
    return
  }
  await http.post('/kb', form)
  ElMessage.success('已创建')
  form.name = ''
  form.description = ''
  load()
}

async function remove(row) {
  await ElMessageBox.confirm('删除知识库会同时移除其文档与向量，确定继续？', '提示', { type: 'warning' })
  await http.delete('/kb/' + row.id)
  ElMessage.success('已删除')
  load()
}
</script>
