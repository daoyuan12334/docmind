<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="brand">
        <span class="logo">DM</span>
        <span class="brand-text">DocMind</span>
      </div>
      <el-menu :default-active="activePath" router class="menu">
        <el-menu-item index="/dashboard"><el-icon><DataAnalysis /></el-icon>总览</el-menu-item>
        <el-menu-item index="/kb"><el-icon><Folder /></el-icon>知识库</el-menu-item>
        <el-menu-item index="/search"><el-icon><Search /></el-icon>检索实验室</el-menu-item>
        <el-menu-item index="/chat"><el-icon><ChatDotRound /></el-icon>智能问答</el-menu-item>
        <el-menu-item index="/metrics"><el-icon><TrendCharts /></el-icon>可观测性</el-menu-item>
        <el-menu-item v-if="store.isAdmin" index="/admin"><el-icon><Setting /></el-icon>运维管理</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <div class="title">{{ route.meta.title || 'DocMind' }}</div>
        <div class="user">
          <el-tag :type="store.isAdmin ? 'danger' : 'info'" size="small">{{ store.role || 'MEMBER' }}</el-tag>
          <span class="username">{{ store.username }}</span>
          <el-button link type="danger" @click="handleLogout">退出</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

const activePath = computed(() => {
  const p = route.path
  if (p.startsWith('/kb/')) return '/kb'
  return p
})

function handleLogout() {
  store.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout { height: 100vh; }
.aside { background: #fff; border-right: 1px solid #ebeef5; }
.brand { display: flex; align-items: center; gap: 10px; padding: 18px 20px; }
.logo {
  width: 32px; height: 32px; border-radius: 8px; background: #409eff;
  color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 700;
}
.brand-text { font-size: 18px; font-weight: 600; }
.menu { border-right: none; }
.header {
  display: flex; align-items: center; justify-content: space-between;
  border-bottom: 1px solid #ebeef5; background: #fff;
}
.title { font-size: 16px; font-weight: 600; }
.user { display: flex; align-items: center; gap: 10px; }
.username { font-size: 14px; color: #606266; }
.main { background: #f5f7fa; }
</style>
