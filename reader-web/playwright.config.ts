import { defineConfig } from '@playwright/test'

/**
 * End-to-end tests against the production build (npm run e2e builds it first). The browser is Playwright's Chromium,
 * or the one given by PLAYWRIGHT_CHROMIUM (a system Chromium).
 */
export default defineConfig({
  testDir: 'e2e',
  timeout: 90_000,
  use: {
    baseURL: 'http://127.0.0.1:4173',
    launchOptions: process.env.PLAYWRIGHT_CHROMIUM ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM } : {},
  },
  webServer: { command: 'npx vite preview --host 127.0.0.1 --port 4173 --strictPort', url: 'http://127.0.0.1:4173', reuseExistingServer: false },
})
