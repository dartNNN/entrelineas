/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'

// The package format lives one level up (format/*.schema.json), shared with the builder and the Android reader.
const format = fileURLToPath(new URL('../format', import.meta.url))

export default defineConfig({
  // Relative asset paths: the built site works from any folder, e.g. https://<user>.github.io/<repo>/.
  base: './',
  plugins: [react()],
  resolve: { alias: { '@format': format } },
  server: { fs: { allow: ['..'] } },
  test: { environment: 'node', include: ['src/**/*.test.{ts,tsx}'] },
})
