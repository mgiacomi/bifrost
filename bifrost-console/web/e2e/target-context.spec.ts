import http from "node:http";
import { test as consoleTest, expect } from "./fixtures/consoleProcess";

const test = consoleTest.extend<{ targetApplication: { origin: string; close(): Promise<void> } }>({
  targetApplication: async ({}, use) => {
    const server = http.createServer((request, response) => {
      const path = new URL(request.url ?? "/", "http://127.0.0.1").pathname;
      if (request.method !== "GET") {
        response.writeHead(404).end();
        return;
      }
      if ((request.headers["x-bifrost-api-key"] ?? "").toString().length < 32) {
        response.writeHead(401, { "Content-Type": "application/json" });
        response.end('{"status":401,"code":"BIFROST_API_KEY_REJECTED","message":"Bifrost API key was rejected"}');
        return;
      }
      const headers = {
        "Content-Type": "application/json",
        "X-Bifrost-Instance-Id": "11111111-1111-4111-8111-111111111111",
      };
      if (path === "/_bifrost/observability/v1/instance") {
        response.writeHead(200, headers);
        response.end(
          '{"instanceId":"11111111-1111-4111-8111-111111111111","consoleCompatibilityVersion":"0.1.0-SNAPSHOT","observedAt":"2026-07-27T00:00:00Z","liveMonitoringAvailable":true,"registeredSkillCount":0,"activeExecutionCount":0,"catalogedTraceCount":1,"tracePersistencePolicy":"PERSISTENT","completionGraceTtl":"PT2M","traceCatalogMetadataTtl":"PT168H"}',
        );
        return;
      }
      if (path === "/_bifrost/observability/v1/traces") {
        response.writeHead(200, headers);
        response.end(
          '{"items":[{"traceId":"trace-with-a-long-identifier-1234567890","sessionId":"session-with-a-long-identifier-1234567890","outcome":"SUCCEEDED","finalizedAt":"2026-07-27T00:00:00Z","sizeBytes":4096,"persistencePolicy":"PERSISTENT","applicationTraceExpiresAt":"2026-08-03T00:00:00Z"}],"hasMore":false,"nextCursor":null,"observedAt":"2026-07-27T00:00:00Z"}',
        );
        return;
      }
      response.writeHead(404).end();
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    if (!address || typeof address === "string") throw new Error("Target test server did not bind");
    const close = () => new Promise<void>((resolve, reject) =>
      server.close((error) => error ? reject(error) : resolve()),
    );
    try {
      await use({ origin: `http://127.0.0.1:${address.port}`, close });
    } finally {
      await close();
    }
  },
});

test.use({ trace: "off", screenshot: "off", video: "off" });

test("paired developer connects and refreshes independent target status", async ({
  page,
  consoleProcess,
  targetApplication,
}) => {
  const directApplicationRequests: string[] = [];
  page.on("request", (request) => {
    if (request.url().includes("/_bifrost/observability/")) {
      directApplicationRequests.push(request.url());
    }
  });
  await page.goto(consoleProcess.pairingUrl);
  await page.goto(`${consoleProcess.origin}/target`);
  await page.getByLabel("Target address").fill(targetApplication.origin);
  await page.getByLabel("Application key").fill("E2E_APPLICATION_KEY_12345678901234567890");
  await page.getByRole("button", { name: "Connect" }).click();
  await expect(page.getByRole("heading", { name: "Instance Overview" })).toBeFocused();
  await expect(page.getByText("REACHABLE", { exact: true })).toBeVisible();
  await expect(page.getByText("COMPATIBLE", { exact: true })).toBeVisible();
  await expect(page.getByText("11111111-1111-4111-8111-111111111111", { exact: true })).toBeVisible();
  await expect(page.getByText(/Unencrypted/)).toBeVisible();
  await page.reload();
  await expect(page.getByText("REACHABLE", { exact: true })).toBeVisible();
  await page.setViewportSize({ width: 320, height: 720 });
  await page.goto(`${consoleProcess.origin}/traces`);
  await expect(page.getByRole("heading", { name: "Trace Catalog" })).toBeVisible();
  await expect(page.getByRole("region", { name: "Trace catalog table" })).toBeVisible();
  expect(await page.evaluate(() =>
    document.documentElement.scrollWidth <= document.documentElement.clientWidth
  )).toBe(true);
  expect(directApplicationRequests).toEqual([]);
  expect(page.url()).not.toContain("E2E_APPLICATION_KEY");
  expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain(
    "E2E_APPLICATION_KEY",
  );
});
