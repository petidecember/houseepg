/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * The dev server proxies the API and the media endpoints to the running Spring app,
 * so `npm run dev` gives a hot-reloading UI without a Node build in Gradle.
 *
 * `test` is vitest's half of the same configuration: the panel's rules are plain functions
 * (`reviewState`), and jsdom is there for the parts that touch `localStorage`.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8094',
      '/media': 'http://localhost:8094',
      '/actuator': 'http://localhost:8094'
    }
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx']
  }
});
