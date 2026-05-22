import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:5177',
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'android-landscape',
      use: {
        ...devices['Pixel 5'],
        viewport: { width: 851, height: 393 },
        hasTouch: true,
        isMobile: true,
      },
    },
  ],
  webServer: {
    command: 'python3 -m http.server 5177',
    url: 'http://127.0.0.1:5177/ufl/ufl-mobile.html',
    reuseExistingServer: !process.env.CI,
    timeout: 10_000,
  },
});
