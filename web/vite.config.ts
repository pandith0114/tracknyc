/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server proxies the API to the Spring Boot service (default http://localhost:8080).
const backend = process.env.TRACKNYC_API ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: backend, changeOrigin: true },
      '/actuator': { target: backend, changeOrigin: true },
    },
  },
  build: {
    sourcemap: true,
    chunkSizeWarningLimit: 2000,
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
