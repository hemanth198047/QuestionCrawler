import react from '@vitejs/plugin-react'
import { createLogger, defineConfig } from 'vite'

// Vite prints a stack trace for every proxied request while the backend is down; the proxy below reports it once, in plain words.
const logger = createLogger()
const logError = logger.error
logger.error = (message, options) => {
  if (message.includes('http proxy error')) return
  logError(message, options)
}

// In dev, /api/* is proxied to the Spring Boot backend so the browser sees a
// single origin (no CORS configuration needed during development).
export default defineConfig({
  customLogger: logger,
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_BACKEND_URL ?? 'http://localhost:8080',
        changeOrigin: true,
        // While the backend is still starting (the first Gradle build can take minutes) or has stopped, answer the browser with a
        // clear message instead of printing a stack trace for every request.
        configure: (proxy) => {
          let lastReport = 0
          proxy.on('error', (_err, _req, res) => {
            const now = Date.now()
            if (now - lastReport > 15000) {
              lastReport = now
              console.warn(`\n[api] The backend at ${process.env.VITE_BACKEND_URL ?? 'http://localhost:8080'} is not answering yet. If it is still starting, wait: the first start compiles it.\n`)
            }
            const response = res as import('node:http').ServerResponse | undefined
            if (response && typeof response.writeHead === 'function' && !response.headersSent) {
              response.writeHead(503, { 'Content-Type': 'application/json' })
              response.end(JSON.stringify({ status: 503, title: 'Backend not reachable', detail: 'The QuestionCrawler backend is not answering yet. If it is still starting (the first start compiles it), wait a moment; this page retries by itself.' }))
            }
          })
        },
      },
    },
  },
})
