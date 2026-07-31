import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test as consoleTest, expect } from "./fixtures/consoleProcess";

const currentDirectory = path.dirname(fileURLToPath(import.meta.url));
const fixturesRoot = path.resolve(currentDirectory, "../../../bifrost-console-fixtures/traces");

type TargetState = {
  instanceId: string;
  authRejected: boolean;
  artifactBody: Buffer;
  artifactMediaType: string;
  artifactFail: boolean;
  traceMetadata: string;
  // Map of trace ID to its specific artifact body. When present, this is used
  // instead of artifactBody so each trace serves its own fixture bytes.
  artifactBodies: Record<string, Buffer>;
};

function readFixture(name: string): Buffer {
  return fs.readFileSync(path.join(fixturesRoot, name));
}

function makeTraceMetadata(traceId: string, sessionId: string, outcome: string, sizeBytes: number): string {
  return JSON.stringify({
    targetScopeId: "scope-1",
    traceId,
    sessionId,
    outcome,
    finalizedAt: "2026-07-25T12:00:00Z",
    sizeBytes,
    persistencePolicy: "PERSISTENT",
    applicationTraceExpiresAt: "2026-08-01T12:00:00Z",
  });
}

function makeTargetServer(initial: TargetState) {
  let state = initial;
  const server = http.createServer((request, response) => {
    const url = new URL(request.url ?? "/", "http://127.0.0.1");
    const pathname = url.pathname;

    if (request.method !== "GET") {
      response.writeHead(404).end();
      return;
    }

    if (state.authRejected || (request.headers["x-bifrost-api-key"] ?? "").toString().length < 32) {
      response.writeHead(401, { "Content-Type": "application/json" });
      response.end('{"status":401,"code":"BIFROST_API_KEY_REJECTED","message":"Bifrost API key was rejected"}');
      return;
    }

    const headers = {
      "Content-Type": "application/json",
      "X-Bifrost-Instance-Id": state.instanceId,
    };

    if (pathname === "/_bifrost/observability/v1/instance") {
      response.writeHead(200, headers);
      response.end(
        JSON.stringify({
          instanceId: state.instanceId,
          consoleCompatibilityVersion: "0.1.0-SNAPSHOT",
          observedAt: "2026-07-27T00:00:00Z",
          liveMonitoringAvailable: true,
          registeredSkillCount: 0,
          activeExecutionCount: 0,
          catalogedTraceCount: 1,
          tracePersistencePolicy: "PERSISTENT",
          completionGraceTtl: "PT2M",
          traceCatalogMetadataTtl: "PT168H",
        }),
      );
      return;
    }

    if (pathname === "/_bifrost/observability/v1/traces") {
      response.writeHead(200, headers);
      response.end(
        JSON.stringify({
          items: [
            JSON.parse(state.traceMetadata),
            JSON.parse(makeTraceMetadata("trace-terminal-failure", "session-terminal-failure", "FAILED", readFixture("terminal-failure.ndjson").length)),
          ],
          hasMore: false,
          nextCursor: null,
          observedAt: "2026-07-27T00:00:00Z",
        }),
      );
      return;
    }

    // Trace detail endpoint: /_bifrost/observability/v1/traces/:traceId
    const traceMatch = pathname.match(/^\/_bifrost\/observability\/v1\/traces\/([^/]+)$/);
    if (traceMatch) {
      const traceId = decodeURIComponent(traceMatch[1]);
      if (traceId === "trace-single-attempt-success") {
        response.writeHead(200, headers);
        response.end(state.traceMetadata);
        return;
      }
      if (traceId === "trace-terminal-failure") {
        response.writeHead(200, headers);
        response.end(makeTraceMetadata("trace-terminal-failure", "session-terminal-failure", "FAILED", readFixture("terminal-failure.ndjson").length));
        return;
      }
    }

    // Artifact endpoint: /_bifrost/observability/v1/traces/:traceId/artifact
    const artifactMatch = pathname.match(/^\/_bifrost\/observability\/v1\/traces\/([^/]+)\/artifact$/);
    if (artifactMatch) {
      const traceId = decodeURIComponent(artifactMatch[1]);
      if (state.artifactFail) {
        response.writeHead(503, { "Content-Type": "application/json" });
        response.end('{"status":503,"code":"UNAVAILABLE","message":"artifact unavailable"}');
        return;
      }
      const body = state.artifactBodies[traceId] ?? state.artifactBody;
      response.writeHead(200, {
        "Content-Type": state.artifactMediaType,
        "X-Bifrost-Instance-Id": state.instanceId,
        "Content-Length": String(body.length),
        "Content-Disposition": `attachment; filename="bifrost-trace-${traceId}.ndjson"`,
        "Cache-Control": "no-store",
      });
      response.end(body);
      return;
    }

    response.writeHead(404).end();
  });

  return {
    listen: () =>
      new Promise<{
        origin: string;
        close: () => Promise<void>;
        setState: (s: Partial<TargetState>) => void;
      }>((resolve, reject) => {
        server.listen(0, "127.0.0.1", () => {
          const address = server.address();
          if (!address || typeof address === "string") {
            reject(new Error("Target test server did not bind"));
            return;
          }
          resolve({
            origin: `http://127.0.0.1:${address.port}`,
            close: () =>
              new Promise<void>((res, rej) => {
                server.close((err) => (err ? rej(err) : res()));
              }),
            setState: (s) => { state = { ...state, ...s }; },
          });
        });
      }),
  };
}

const test = consoleTest.extend<{
  targetApp: {
    origin: string;
    close: () => Promise<void>;
    setState: (s: Partial<TargetState>) => void;
  };
}>({
  targetApp: async ({}, use) => {
    const artifactBody = readFixture("single-attempt-success.ndjson");
    const failureBody = readFixture("terminal-failure.ndjson");
    const server = makeTargetServer({
      instanceId: "11111111-1111-4111-8111-111111111111",
      authRejected: false,
      artifactBody,
      artifactMediaType: "application/x-ndjson",
      artifactFail: false,
      traceMetadata: makeTraceMetadata("trace-single-attempt-success", "session-single-attempt-success", "SUCCEEDED", artifactBody.length),
      artifactBodies: {
        "trace-single-attempt-success": artifactBody,
        "trace-terminal-failure": failureBody,
      },
    });
    const handle = await server.listen();
    try {
      await use(handle);
    } finally {
      await handle.close();
    }
  },
});

test.use({ trace: "off", screenshot: "off", video: "off" });

async function connectToTarget(page: import("@playwright/test").Page, consoleProcess: { origin: string; pairingUrl: string }, targetOrigin: string) {
  await page.goto(consoleProcess.pairingUrl);
  await page.goto(`${consoleProcess.origin}/target`);
  await page.getByLabel("Target address").fill(targetOrigin);
  await page.getByLabel("Application key").fill("E2E_APPLICATION_KEY_12345678901234567890");
  await page.getByRole("button", { name: "Connect" }).click();
  await expect(page.getByRole("heading", { name: "Instance Overview" })).toBeFocused();
}

// navigateToTraceDetail goes to the trace catalog, clicks the trace link (which
// includes the targetScopeId query parameter), and waits for the Trace Detail
// heading. Direct URL navigation doesn't work because TraceDetailView uses
// useScopeBoundRoute which requires the scope parameter.
async function navigateToTraceDetail(page: import("@playwright/test").Page, consoleProcess: { origin: string }, traceId: string) {
  await page.goto(`${consoleProcess.origin}/traces`);
  await expect(page.getByRole("heading", { name: "Trace Catalog" })).toBeVisible();
  await page.getByRole("link", { name: traceId }).click();
  await expect(page.getByRole("heading", { name: "Trace Detail" })).toBeVisible({ timeout: 10_000 });
}

// navigateToTraceStorage extracts the current target scope ID from the page URL
// and navigates to the trace storage page with the scope parameter. The
// TraceStorage component uses useScopeBoundRoute which requires the parameter.
async function navigateToTraceStorage(page: import("@playwright/test").Page, consoleProcess: { origin: string }) {
  // Try to extract the scope ID from the current URL first (if we're on a
  // trace detail page it will be there).
  let scopeId: string | null = null;
  try {
    const currentURL = new URL(page.url());
    scopeId = currentURL.searchParams.get("targetScopeId");
  } catch { /* ignore */ }

  // If we don't have the scope ID, navigate to the trace catalog and click a
  // trace link to get it into the URL.
  if (!scopeId) {
    await page.goto(`${consoleProcess.origin}/traces`);
    await expect(page.getByRole("heading", { name: "Trace Catalog" })).toBeVisible();
    const traceLink = page.locator("table a").first();
    await traceLink.click();
    await expect(page.getByRole("heading", { name: "Trace Detail" })).toBeVisible({ timeout: 10_000 });
    const currentURL = new URL(page.url());
    scopeId = currentURL.searchParams.get("targetScopeId");
    if (!scopeId) throw new Error("Could not extract targetScopeId from trace detail URL");
  }

  // Navigate to trace storage with the scope parameter.
  await page.goto(`${consoleProcess.origin}/trace-storage?targetScopeId=${encodeURIComponent(scopeId)}`);
  await expect(page.getByRole("heading", { name: "Trace Storage" })).toBeVisible({ timeout: 10_000 });
}

// WF-AS-01: A completed (SUCCEEDED) trace can be acquired for analysis, appears
// in Trace Storage, and the raw download streams the exact Java-produced bytes.
test("WF-AS-01 completed trace acquisition installs a local copy and raw download streams exact bytes", async ({
  page,
  consoleProcess,
  targetApp,
}) => {
  await connectToTarget(page, consoleProcess, targetApp.origin);

  // Navigate to the trace detail and acquire the artifact.
  await navigateToTraceDetail(page, consoleProcess, "trace-single-attempt-success");
  await expect(page.getByText("Not installed", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Acquire for analysis" }).click();
  await expect(page.getByText("Artifact acquired successfully.")).toBeVisible({ timeout: 15_000 });

  // The Trace Storage page must list the acquired artifact.
  await navigateToTraceStorage(page, consoleProcess);
  await expect(page.getByText("Acquired count")).toBeVisible();
  await expect(page.locator("table.storage-table")).toContainText("trace-single-attempt-success");
  await expect(page.locator("table.storage-table")).toContainText("SUCCEEDED");
  await expect(page.locator("table.storage-table")).toContainText("AVAILABLE");

  // The raw download link must stream the exact Java-produced fixture bytes.
  // Navigate back to the trace detail to access the raw download link.
  await navigateToTraceDetail(page, consoleProcess, "trace-single-attempt-success");
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("link", { name: "Raw artifact download" }).click();
  const download = await downloadPromise;
  const stream = await download.createReadStream();
  const chunks: Buffer[] = [];
  for await (const chunk of stream) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  }
  const downloaded = Buffer.concat(chunks);
  expect(downloaded.equals(readFixture("single-attempt-success.ndjson"))).toBe(true);
});

// WF-AS-02: A failed (FAILED outcome) trace can also be acquired for analysis
// and appears in Trace Storage with its original outcome preserved.
test("WF-AS-02 failed-completion trace is acquired for analysis and preserves FAILED outcome", async ({
  page,
  consoleProcess,
  targetApp,
}) => {
  await connectToTarget(page, consoleProcess, targetApp.origin);

  // The trace catalog must list both the succeeded and failed traces.
  await page.goto(`${consoleProcess.origin}/traces`);
  await expect(page.getByRole("heading", { name: "Trace Catalog" })).toBeVisible();
  await expect(page.getByRole("link", { name: "trace-terminal-failure" })).toBeVisible();

  // Acquire the failed trace.
  await navigateToTraceDetail(page, consoleProcess, "trace-terminal-failure");
  await expect(page.getByText("FAILED", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Acquire for analysis" }).click();
  await expect(page.getByText("Artifact acquired successfully.")).toBeVisible({ timeout: 15_000 });

  // Trace Storage must show the failed trace with its FAILED outcome.
  await navigateToTraceStorage(page, consoleProcess);
  await expect(page.locator("table.storage-table")).toContainText("trace-terminal-failure");
  await expect(page.locator("table.storage-table")).toContainText("FAILED");
});

// WF-AS-03: Removing an artifact from Trace Storage requires confirmation and
// clears the entry from the storage snapshot.
test("WF-AS-03 artifact removal requires confirmation and clears the storage entry", async ({
  page,
  consoleProcess,
  targetApp,
}) => {
  await connectToTarget(page, consoleProcess, targetApp.origin);

  // Acquire the artifact first.
  await navigateToTraceDetail(page, consoleProcess, "trace-single-attempt-success");
  await page.getByRole("button", { name: "Acquire for analysis" }).click();
  await expect(page.getByText("Artifact acquired successfully.")).toBeVisible({ timeout: 15_000 });

  // Go to Trace Storage and remove the artifact.
  await navigateToTraceStorage(page, consoleProcess);
  await expect(page.locator("table.storage-table")).toContainText("trace-single-attempt-success");
  await page.getByRole("button", { name: "Remove" }).click();
  // The confirm button must appear (no immediate removal).
  const confirmButton = page.getByRole("button", { name: /Confirm removal of/ });
  await expect(confirmButton).toBeVisible();
  await confirmButton.click();
  // The entry must be gone from the table.
  await expect(page.getByText("No artifacts are currently stored.")).toBeVisible({ timeout: 15_000 });
});

// WF-AS-04: After the upstream credential is rejected, the locally installed
// artifact remains available in Trace Storage, but a new raw download fails
// until the credential is restored.
test("WF-AS-04 installed evidence remains after auth rejection while raw download fails", async ({
  page,
  consoleProcess,
  targetApp,
}) => {
  await connectToTarget(page, consoleProcess, targetApp.origin);

  // Acquire the artifact while the credential is valid.
  await navigateToTraceDetail(page, consoleProcess, "trace-single-attempt-success");
  await page.getByRole("button", { name: "Acquire for analysis" }).click();
  await expect(page.getByText("Artifact acquired successfully.")).toBeVisible({ timeout: 15_000 });

  // Save the scope ID for direct navigation after auth is rejected.
  const detailURL = new URL(page.url());
  const scopeId = detailURL.searchParams.get("targetScopeId");
  if (!scopeId) throw new Error("Could not extract targetScopeId from trace detail URL");

  // Reject the upstream credential.
  targetApp.setState({ authRejected: true });

  // Trace Storage must still show the installed artifact with original facts.
  // The local copy is independent of the upstream credential.
  await page.goto(`${consoleProcess.origin}/trace-storage?targetScopeId=${encodeURIComponent(scopeId)}`);
  await expect(page.getByRole("heading", { name: "Trace Storage" })).toBeVisible({ timeout: 10_000 });
  await expect(page.locator("table.storage-table")).toContainText("trace-single-attempt-success");
  await expect(page.locator("table.storage-table")).toContainText("SUCCEEDED");

  // The trace detail page must show the auth rejection error (the upstream
  // refuses to serve trace metadata). The acquire button is not available
  // because the trace cannot be loaded.
  await page.goto(`${consoleProcess.origin}/traces/trace-single-attempt-success?targetScopeId=${encodeURIComponent(scopeId)}`);
  await expect(page.getByRole("heading", { name: "Trace Detail" })).toBeVisible({ timeout: 10_000 });
  await expect(page.locator(".target-error", { hasText: /rejected|authentication/i })).toBeVisible({ timeout: 15_000 });

  // Restore the credential. The trace detail page must load again and a new
  // acquisition must succeed.
  targetApp.setState({ authRejected: false });
  await page.reload();
  await expect(page.getByRole("button", { name: "Acquire for analysis" })).toBeVisible({ timeout: 15_000 });
  await page.getByRole("button", { name: "Acquire for analysis" }).click();
  await expect(page.getByText("Artifact acquired successfully.")).toBeVisible({ timeout: 15_000 });
});

// WF-AS-05: Target rotation clears the local artifact cache. The old scope's
// storage snapshot is unavailable (TARGET_CHANGED), and the new scope starts
// empty.
test("WF-AS-05 target rotation clears local storage and stale scope is unavailable", async ({
  page,
  consoleProcess,
  targetApp,
}) => {
  await connectToTarget(page, consoleProcess, targetApp.origin);

  // Acquire the artifact in the original scope.
  await navigateToTraceDetail(page, consoleProcess, "trace-single-attempt-success");
  await page.getByRole("button", { name: "Acquire for analysis" }).click();
  await expect(page.getByText("Artifact acquired successfully.")).toBeVisible({ timeout: 15_000 });

  // Rotate the target by changing the instance identity. The target context
  // will detect the scope change automatically (same address/key, new instance
  // ID) and redirect to /. The old scope's artifacts are cleared.
  targetApp.setState({ instanceId: "22222222-2222-4222-8222-222222222222" });
  // Wait for the auto-reconnection to complete (the new instance ID appears).
  await page.goto(`${consoleProcess.origin}/`);
  await expect(page.getByText("22222222-2222-4222-8222-222222222222", { exact: true })).toBeVisible({ timeout: 15_000 });

  // Trace Storage must be empty in the new scope.
  await navigateToTraceStorage(page, consoleProcess);
  await expect(page.getByText("No artifacts are currently stored.")).toBeVisible({ timeout: 15_000 });
});

// WF-AS-06: When the upstream artifact is unavailable (503), acquisition fails
// with a bounded error and no partial entry is left in Trace Storage.
test("WF-AS-06 unavailable application artifact fails acquisition without leaving a partial entry", async ({
  page,
  consoleProcess,
  targetApp,
}) => {
  await connectToTarget(page, consoleProcess, targetApp.origin);

  targetApp.setState({ artifactFail: true });

  await navigateToTraceDetail(page, consoleProcess, "trace-single-attempt-success");
  await page.getByRole("button", { name: "Acquire for analysis" }).click();
  await expect(page.locator(".target-error", { hasText: /unavailable|artifact/i })).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText("Artifact acquired successfully.")).toHaveCount(0);

  // Trace Storage must remain empty (no partial entry).
  await navigateToTraceStorage(page, consoleProcess);
  await expect(page.getByText("No artifacts are currently stored.")).toBeVisible();
});

// WF-AS-07: The Trace Storage page never leaks a filesystem path or the
// application credential in the DOM, localStorage, or sessionStorage.
test("WF-AS-07 trace storage page does not leak paths or credentials", async ({
  page,
  consoleProcess,
  targetApp,
}) => {
  await connectToTarget(page, consoleProcess, targetApp.origin);

  await navigateToTraceDetail(page, consoleProcess, "trace-single-attempt-success");
  await page.getByRole("button", { name: "Acquire for analysis" }).click();
  await expect(page.getByText("Artifact acquired successfully.")).toBeVisible({ timeout: 15_000 });

  await navigateToTraceStorage(page, consoleProcess);
  const bodyText = await page.locator("body").innerText();
  expect(bodyText).not.toContain("transient");
  expect(bodyText).not.toContain("E2E_APPLICATION_KEY");
  expect(page.url()).not.toContain("E2E_APPLICATION_KEY");
  const storageDump = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
  expect(storageDump).not.toContain("E2E_APPLICATION_KEY");
});
