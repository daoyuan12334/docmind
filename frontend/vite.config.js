import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发环境：/api 走 Vite 代理到后端，避免跨域与 Cookie 问题
//
// 代理规则必须同时覆盖 **/api 和 /actuator** 两条前缀：
//   - /api      → 业务接口
//   - /actuator → Spring Boot 管理端点（可观测性面板要用）
// Actuator 不能被塞进 /api 前缀里，否则请求变成 /api/actuator/metrics，
// 后端没有这个映射，会掉进静态资源处理器返回 500。
const BACKEND = process.env.VITE_API_TARGET || 'http://localhost:8080'
const proxy = {
  '/api': {
    target: BACKEND,
    changeOrigin: true
  },
  '/actuator': {
    target: BACKEND,
    changeOrigin: true
  }
}

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy
  },
  // 预览构建产物（npm run preview）时同样需要代理，否则 /api 全部 404
  preview: {
    port: 4173,
    proxy
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500
  }
})
