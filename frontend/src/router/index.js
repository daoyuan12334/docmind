import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue') },
  {
    path: '/',
    component: () => import('../layout/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'dashboard', component: () => import('../views/DashboardView.vue'), meta: { title: '总览' } },
      { path: 'kb', name: 'kb', component: () => import('../views/KnowledgeBaseView.vue'), meta: { title: '知识库' } },
      { path: 'kb/:id', name: 'kb-detail', component: () => import('../views/DocumentView.vue'), meta: { title: '文档管理' } },
      { path: 'search', name: 'search', component: () => import('../views/SearchLabView.vue'), meta: { title: '检索实验室' } },
      { path: 'chat', name: 'chat', component: () => import('../views/ChatView.vue'), meta: { title: '智能问答' } },
      { path: 'metrics', name: 'metrics', component: () => import('../views/MetricsView.vue'), meta: { title: '可观测性' } },
      { path: 'admin', name: 'admin', component: () => import('../views/AdminView.vue'), meta: { title: '运维管理', admin: true } }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const store = useUserStore()
  if (to.path !== '/login' && !store.isLogin) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.admin && !store.isAdmin) {
    return { path: '/dashboard' }
  }
})

export default router
