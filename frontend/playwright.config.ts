import { defineConfig, devices } from "@playwright/test";

const browserExecutablePath = process.env.NOTEWEAVE_E2E_BROWSER_EXECUTABLE_PATH?.trim() || undefined;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 20_000 },
  reporter: [["list"], ["html", { outputFolder: "playwright-report", open: "never" }]],
  outputDir: "test-results/playwright",
  use: {
    baseURL: process.env.NOTEWEAVE_E2E_BASE_URL ?? "http://127.0.0.1:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure"
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        channel: browserExecutablePath ? undefined : process.env.NOTEWEAVE_E2E_BROWSER_CHANNEL,
        launchOptions: browserExecutablePath ? { executablePath: browserExecutablePath } : undefined
      }
    }
  ]
});
