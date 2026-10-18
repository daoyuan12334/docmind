import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发环境：/api 走 Vite 代理到后端，避免跨域与 Cookie 问题
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  // 预览构建产物（npm run preview）时同样需要代理，否则 /api 全部 404
  preview: {
    port: 4173,
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500
  }
})
