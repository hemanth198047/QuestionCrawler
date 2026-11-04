import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// In dev, /api/* is proxied to the Spring Boot backend so the browser sees a
// single origin (no CORS configuration needed during development).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_BACKEND_URL ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
