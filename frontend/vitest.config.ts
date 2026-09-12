import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Separate from vite.config.ts so the dev-server proxy, which tests never want,
// stays out of the test run.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    // Every test gets a clean localStorage and a clean module registry, since
    // the API client is a module-level singleton with interceptors on it.
    restoreMocks: true,
    clearMocks: true,
  },
})
