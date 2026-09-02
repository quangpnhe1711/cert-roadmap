import { defineConfig, devices } from '@playwright/test'

/**
 * E2E covers the core learning loop through the real browser against the real
 * backend. It is deliberately not a broad UI suite: one test that proves a
 * learner can go from signup to a graded quiz is worth more than twenty that
 * assert on button labels.
 *
 * The backend must already be running; the dev server is started here.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 180_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    ...devices['Desktop Chrome'],
  },
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 120_000,
  },
})
