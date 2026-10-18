<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="brand">
        <span class="logo">DM</span>
        <div>
          <div class="title">DocMind</div>
          <div class="subtitle">私有文档智能系统 · RAG + 多模态</div>
        </div>
      </div>
      <el-tabs v-model="mode">
        <el-tab-pane label="登录" name="login" />
        <el-tab-pane label="注册" name="register" />
      </el-tabs>
      <el-form :model="form" label-position="top" @keyup.enter="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password placeholder="请输入密码" />
        </el-form-item>
        <el-form-item v-if="mode === 'register'" label="显示名称">
          <el-input v-model="form.displayName" placeholder="选填" />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loading" @click="submit">
          {{ mode === 'login' ? '登录' : '注册并登录' }}
        </el-button>
      </el-form>
      <div class="tip muted">首个注册的账号自动成为管理员</div>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api/http'
import { useUserStore } from '../stores/user'

const router = useRouter()
const store = useUserStore()
const mode = ref('login')
const loading = ref(false)
const form = reactive({ username: '', password: '', displayName: '' })

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    let data
    if (mode.value === 'register') {
      await http.post('/auth/register', {
        username: form.username,
        password: form.password,
        displayName: form.displayName
      })
      data = await http.post('/auth/login', { username: form.username, password: form.password })
    } else {
      data = await http.post('/auth/login', { username: form.username, password: form.password })
    }
    store.setAuth(data.token)
    store.setProfile({ userId: data.userId, username: data.username, role: data.role })
    ElMessage.success('欢迎回来')
    router.push('/dashboard')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #eef4ff 0%, #f7f9fc 100%);
}
.login-card { width: 420px; padding: 10px; }
.brand { display: flex; gap: 12px; align-items: center; margin-bottom: 18px; }
.logo {
  width: 44px; height: 44px; border-radius: 10px; background: #409eff;
  color: #fff; display: flex; align-items: center; justify-content: center;
  font-weight: 700; font-size: 18px;
}
.title { font-size: 20px; font-weight: 700; }
.subtitle { font-size: 12px; color: #909399; }
.tip { margin-top: 12px; text-align: center; }
</style>
