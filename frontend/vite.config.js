import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 开发服务器把 /api 代理到 Gateway。
 *
 * 这样前端代码里永远只写相对路径 `/api/...`，不需要知道后端在哪个端口。
 * 换成生产部署时由 Nginx 做同一件事（见 nginx.conf），前端代码一个字都不用改。
 *
 * 也可以给前端配一个 VITE_API_BASE 直接打到 18080，但那会引入跨域：
 * 浏览器对每个带自定义头（Authorization、Idempotency-Key）的请求都要先发一次
 * OPTIONS 预检，于是每次下单变成两个 RTT，而且要在 Gateway 上维护 CORS 配置。
 * 同源代理没有这些问题——这也是真实部署里前后端同域的原因。
 *
 * 用 loadEnv 而不是直接读 process.env：Vite 不会把 .env 文件里的变量塞进
 * process.env，只会注入到客户端代码里。配置文件跑在那之前，所以 .env 里写的
 * VITE_GATEWAY_URL 用 process.env 是读不到的（写了没生效，最难查的一类问题）。
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd())

  return {
    plugins: [vue()],
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target: env.VITE_GATEWAY_URL || 'http://localhost:18080',
          changeOrigin: true
        }
      }
    },
    build: {
      outDir: 'dist',
      sourcemap: true
    }
  }
})
