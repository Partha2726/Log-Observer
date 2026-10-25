import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // Foundation-level proxy so the API client can call relative
      // "/api/..." paths during local development without CORS setup.
      // Later accounts may replace this with an explicit base URL per
      // environment.
      '/api': {
        target: process.env.VITE_API_PROXY_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    // Playwright specs in e2e/ run against the stack with `make e2e`, not in Vitest.
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
