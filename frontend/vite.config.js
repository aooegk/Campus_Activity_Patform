import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: { port: 5173, proxy: { '/api': 'http://localhost:8080' } },
  // 任务 20：首屏 JS 拆包——Vue 运行时拆为独立 vendor 块（带内容哈希），
  // 复访时 vendor 块走缓存，首屏只下应用块；冷加载总字节不变（见 changes.md）。
  build: {
    rollupOptions: { output: { manualChunks: { vendor: ['vue'] } } },
  },
})
