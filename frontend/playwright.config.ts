import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  use: { baseURL: "http://127.0.0.1:8081", channel: process.env.PLAYWRIGHT_CHANNEL || undefined },
  webServer: {
    command: "npm run dev -- --host 127.0.0.1",
    url: "http://127.0.0.1:8081",
    reuseExistingServer: !process.env.CI,
  },
});
