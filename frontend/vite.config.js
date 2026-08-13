import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './pruebas/preparar.js',
    // Las guardas son un script aparte, no pruebas: se ejecutan con
    // `npm run guardas` y encadenadas al build.
    include: ['pruebas/**/*.prueba.jsx', 'pruebas/**/*.prueba.js'],
  },
})
