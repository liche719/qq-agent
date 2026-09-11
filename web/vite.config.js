import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  base: '/',
  build: {
    // 直接产出到后端静态资源目录：mvn package 会把它们打进 jar，
    // 部署链路不变（仍是单容器：nginx 网关 → agent）。
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    assetsDir: 'assets',
    chunkSizeWarningLimit: 900
  },
  server: {
    port: 5173,
    // 本地开发时把接口代理到本机后端
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true }
    }
  }
})
