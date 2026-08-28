import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const gatewayTarget = env.VITE_AI_GATEWAY_BASE_URL || 'http://localhost:8088'

  return {
    plugins: [vue()],
    server: {
      proxy: {
        '/api': {
          target: gatewayTarget,
          changeOrigin: true,
        },
      },
    },
  }
})
