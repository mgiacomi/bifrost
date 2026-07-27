import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig } from "@playwright/test";

const webDirectory = path.dirname(fileURLToPath(import.meta.url));
const executable = process.platform === "win32" ? "bifrost-console.exe" : "bifrost-console";
const binary = path.resolve(webDirectory, "../build", executable);
const port = 17943;

export default defineConfig({
  testDir: "./e2e",
  outputDir: "./test-results",
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    browserName: "chromium",
    trace: "retain-on-failure"
  },
  webServer: {
    command: `"${binary}" --listen 127.0.0.1:${port}`,
    url: `http://127.0.0.1:${port}`,
    reuseExistingServer: false,
    timeout: 15_000
  }
});
