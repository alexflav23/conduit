import { defineConfig, devices } from '@playwright/test';

// The desk dev server proxies /api -> http://localhost:8080 (the Conduit API, started by run-e2e.sh).
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:3060',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run start -- --port 3060',
    url: 'http://localhost:3060',
    reuseExistingServer: true,
    timeout: 120_000,
  },
});
