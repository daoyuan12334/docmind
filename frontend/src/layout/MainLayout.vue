<template>
  <el-container class="layout">
    <!-- 宽屏：常驻侧边栏 -->
    <el-aside v-if="!isNarrow" width="220px" class="aside">
      <div class="brand">
        <span class="logo">DM</span>
        <span class="brand-text">DocMind</span>
      </div>
      <el-menu :default-active="activePath" router class="menu">
        <el-menu-item v-for="m in visibleMenus" :key="m.path" :index="m.path">
          <el-icon><component :is="m.icon" /></el-icon>
          <span>{{ m.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <!-- 窄屏：侧边栏改成抽屉，这里给入口 -->
          <button v-if="isNarrow" class="hamburger" aria-label="打开菜单" @click="drawer = true">
            <span></span><span></span><span></span>
          </button>
          <div class="title">{{ route.meta.title || 'DocMind' }}</div>
        </div>
        <div class="user">
          <el-tag :type="store.isAdmin ? 'danger' : 'info'" size="small">{{ store.role || 'MEMBER' }}</el-tag>
          <span class="username">{{ store.username }}</span>
          <el-button link type="danger" class="logout" @click="handleLogout">退出</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>

    <!-- 窄屏抽屉（与侧边栏共用同一份菜单定义，避免两处维护） -->
    <el-drawer v-model="drawer" direction="ltr" size="220px" :with-header="false" class="nav-drawer">
      <div class="brand">
        <span class="logo">DM</span>
        <span class="brand-text">DocMind</span>
      </div>
      <el-menu :default-active="activePath" router class="menu" @select="drawer = false">
        <el-menu-item v-for="m in visibleMenus" :key="m.path" :index="m.path">
          <el-icon><component :is="m.icon" /></el-icon>
          <span>{{ m.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-drawer>
  </el-container>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

// 菜单单点定义：侧边栏与抽屉都从这里渲染，加/改菜单只动这一处
const menus = [
  { path: '/dashboard', title: '总览', icon: 'DataAnalysis' },
  { path: '/kb', title: '知识库', icon: 'Folder' },
  { path: '/search', title: '检索实验室', icon: 'Search' },
  { path: '/chat', title: '智能问答', icon: 'ChatDotRound' },
  { path: '/metrics', title: '可观测性', icon: 'TrendCharts' },
  { path: '/admin', title: '运维管理', icon: 'Setting', adminOnly: true },
]
const visibleMenus = computed(() => menus.filter((m) => !m.adminOnly || store.isAdmin))

const activePath = computed(() => (route.path.startsWith('/kb/') ? '/kb' : route.path))

// 992px 是 Element Plus 常用的 lg 断点：以下视为窄屏，侧边栏收进抽屉
const drawer = ref(false)
const isNarrow = ref(false)
function syncWidth() {
  const narrow = window.innerWidth < 992
  isNarrow.value = narrow
  if (!narrow) drawer.value = false // 变宽时关掉抽屉，避免遮罩残留
}
onMounted(() => {
  syncWidth()
  window.addEventListener('resize', syncWidth)
})
onBeforeUnmount(() => window.removeEventListener('resize', syncWidth))

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
  /* 窄屏下标题+用户区可能挤，允许换行而不是撑破页面 */
  flex-wrap: wrap; row-gap: 6px;
}
.header-left { display: flex; align-items: center; gap: 10px; min-width: 0; }
.title { font-size: 16px; font-weight: 600; }
.user { display: flex; align-items: center; gap: 10px; }
.username { font-size: 14px; color: #606266; }
.main { background: #f5f7fa; }

/* 汉堡按钮：只在窄屏出现 */
.hamburger {
  width: 34px; height: 34px; padding: 0; border: 1px solid #dcdfe6; border-radius: 6px;
  background: #fff; cursor: pointer; display: flex;
  flex-direction: column; align-items: center; justify-content: center; gap: 4px;
}
.hamburger span { display: block; width: 16px; height: 2px; background: #606266; border-radius: 1px; }
.hamburger:hover { border-color: #409eff; }

/* 退出按钮加大点击区（默认 link 按钮只有 34x20，移动端不好点） */
.logout { padding: 6px 8px; min-height: 32px; }

@media (max-width: 992px) {
  /* 窄屏优先保证标题和退出按钮可见，用户名让位 */
  .username { display: none; }
  .header { padding: 0 12px; }
}
</style>
