import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * The dev server proxies the API and the media endpoints to the running Spring app,
 * so `npm run dev` gives a hot-reloading UI without a Node build in Gradle.
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
  }
});
