import { afterEach, expect, test, vi } from "vitest";
import {
  bootstrap,
  BrowserAPIError,
  connectTarget,
  createPairingLink,
  exchangePairing,
  getObservabilityInstance,
  getActiveExecutionDetail,
  getSkillDetail,
  getTraceDetail,
  heartbeatTab,
  listActiveExecutions,
  listSkills,
  listTraces,
  recheckTarget,
  supplyTargetCredential,
  targetStatus,
} from "./client";

afterEach(() => vi.unstubAllGlobals());

test("submits pairing once in a same-origin no-store request", async () => {
  const fetch = vi.fn().mockResolvedValue(
    new Response(JSON.stringify({ paired: true }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }),
  );
  vi.stubGlobal("fetch", fetch);
  await exchangePairing("A".repeat(43));
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(fetch).toHaveBeenCalledWith(
    "/api/console/v1/pairing/exchange",
    expect.objectContaining({
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
      body: JSON.stringify({ secret: "A".repeat(43) }),
    }),
  );
  expect(localStorage.length).toBe(0);
  expect(sessionStorage.length).toBe(0);
});

test("uses memory security values only as protected request headers", async () => {
  const fetch = vi.fn().mockResolvedValue(
    new Response(JSON.stringify({ pairingUrl: "http://127.0.0.1/#/pair/value" }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }),
  );
  vi.stubGlobal("fetch", fetch);
  await createPairingLink({ tabId: "tab", csrfToken: "csrf" });
  const options = fetch.mock.calls[0]?.[1] as RequestInit;
  expect(options.headers).toMatchObject({
    "X-Bifrost-Console-Tab": "tab",
    "X-Bifrost-Console-CSRF": "csrf",
  });
  expect(localStorage.length).toBe(0);
  expect(sessionStorage.length).toBe(0);
});

test("bootstraps through the HttpOnly cookie without client credential state", async () => {
  const fetch = vi.fn().mockResolvedValue(
    new Response(
      JSON.stringify({
        processId: "process",
        workspacePath: "workspace",
        tabId: "tab",
        csrfToken: "csrf",
      }),
      { status: 200, headers: { "Content-Type": "application/json" } },
    ),
  );
  vi.stubGlobal("fetch", fetch);
  expect(await bootstrap()).toMatchObject({ processId: "process", tabId: "tab" });
  expect(fetch.mock.calls[0]?.[1]).toMatchObject({ credentials: "same-origin" });
});

test("heartbeats with the current in-memory tab security state", async () => {
  const fetch = vi.fn().mockResolvedValue(
    new Response(JSON.stringify({ active: true }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }),
  );
  vi.stubGlobal("fetch", fetch);
  await heartbeatTab({ tabId: "tab", csrfToken: "csrf" });
  expect(fetch).toHaveBeenCalledWith(
    "/api/console/v1/tabs/heartbeat",
    expect.objectContaining({
      headers: expect.objectContaining({
        "X-Bifrost-Console-Tab": "tab",
        "X-Bifrost-Console-CSRF": "csrf",
      }),
    }),
  );
});

test("submits target operations with session-only status and protected mutations", async () => {
  const target = {
    unencrypted: false,
    status: {
      observedAt: "2026-07-27T00:00:00Z",
      targetSelection: "NONE",
      targetConnection: "NOT_APPLICABLE",
      targetAuthentication: "NOT_APPLICABLE",
      javaGoCompatibility: "NOT_APPLICABLE",
      runtimeIdentity: "NOT_APPLICABLE",
      liveMonitoring: "NOT_APPLICABLE",
    },
  };
  const fetch = vi.fn().mockImplementation(() => Promise.resolve(
    new Response(JSON.stringify(target), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }),
  ));
  vi.stubGlobal("fetch", fetch);
  const security = { tabId: "tab", csrfToken: "csrf" };
  await targetStatus();
  await connectTarget("https://application.example", "k".repeat(32), security);
  await supplyTargetCredential("r".repeat(32), security);
  await recheckTarget(security);
  expect(fetch.mock.calls.map((call) => call[0])).toEqual([
    "/api/console/v1/target/status",
    "/api/console/v1/target/connect",
    "/api/console/v1/target/credential",
    "/api/console/v1/target/recheck",
  ]);
  expect((fetch.mock.calls[0]?.[1] as RequestInit).headers).not.toHaveProperty(
    "X-Bifrost-Console-CSRF",
  );
  expect((fetch.mock.calls[1]?.[1] as RequestInit).headers).toMatchObject({
    "X-Bifrost-Console-Tab": "tab",
    "X-Bifrost-Console-CSRF": "csrf",
  });
});

test("preserves shared target error scope and typed details", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          error: {
            code: "TARGET_CHANGED",
            message: "Target changed.",
            targetScopeId: "old",
            details: { currentTargetScopeId: "new" },
          },
        }),
        { status: 409, headers: { "Content-Type": "application/json" } },
      ),
    ),
  );
  const error = await targetStatus().catch((value: unknown) => value);
  expect(error).toBeInstanceOf(BrowserAPIError);
  expect(error).toMatchObject({
    code: "TARGET_CHANGED",
    targetScopeId: "old",
    details: { currentTargetScopeId: "new" },
  });
});

test("observability instance posts without CSRF headers", async () => {
  const fetch = vi.fn().mockResolvedValue(
    new Response(JSON.stringify({ instanceId: "test" }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }),
  );
  vi.stubGlobal("fetch", fetch);
  await getObservabilityInstance();
  expect(fetch.mock.calls[0]?.[0]).toBe("/api/console/v1/observability/instance");
  expect((fetch.mock.calls[0]?.[1] as RequestInit).headers).not.toHaveProperty(
    "X-Bifrost-Console-CSRF",
  );
});

test("list skills sends cursor and pageSize in body", async () => {
  const fetch = vi.fn().mockResolvedValue(
    new Response(JSON.stringify({ items: [], hasMore: false, nextCursor: null, observedAt: "2026-07-27T00:00:00Z" }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }),
  );
  vi.stubGlobal("fetch", fetch);
  await listSkills("cursor-1", 50);
  const body = JSON.parse((fetch.mock.calls[0]?.[1] as RequestInit).body as string);
  expect(body).toMatchObject({ cursor: "cursor-1", pageSize: 50 });
});

test("get skill detail sends registeredName in body", async () => {
  const fetch = vi.fn().mockResolvedValue(
    new Response(JSON.stringify({ registeredName: "MySkill", sourcePath: "/path", yaml: "key: value" }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }),
  );
  vi.stubGlobal("fetch", fetch);
  await getSkillDetail("MySkill");
  const body = JSON.parse((fetch.mock.calls[0]?.[1] as RequestInit).body as string);
  expect(body).toMatchObject({ registeredName: "MySkill" });
});

test("list active executions returns page with resumeCursor", async () => {
  const fetch = vi.fn().mockResolvedValue(
    new Response(JSON.stringify({ items: [], hasMore: true, nextCursor: "next", resumeCursor: "resume", observedAt: "2026-07-27T00:00:00Z" }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }),
  );
  vi.stubGlobal("fetch", fetch);
  const page = await listActiveExecutions();
  expect(page.resumeCursor).toBe("resume");
  expect(page.hasMore).toBe(true);
});

test("list traces sends request to traces list endpoint", async () => {
  const fetch = vi.fn().mockResolvedValue(
    new Response(JSON.stringify({ items: [], hasMore: false, nextCursor: null, observedAt: "2026-07-27T00:00:00Z" }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }),
  );
  vi.stubGlobal("fetch", fetch);
  await listTraces();
  expect(fetch.mock.calls[0]?.[0]).toBe("/api/console/v1/traces/list");
});

test("active execution and trace detail functions preserve identifiers", async () => {
  const fetch = vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify({ sessionId: "session-1" }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ traceId: "trace-1" }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }));
  vi.stubGlobal("fetch", fetch);

  await getActiveExecutionDetail("session-1");
  await getTraceDetail("trace-1");

  expect(fetch.mock.calls[0]?.[0]).toBe("/api/console/v1/active-executions/detail");
  expect(JSON.parse((fetch.mock.calls[0]?.[1] as RequestInit).body as string))
    .toEqual({ sessionId: "session-1" });
  expect(fetch.mock.calls[1]?.[0]).toBe("/api/console/v1/traces/detail");
  expect(JSON.parse((fetch.mock.calls[1]?.[1] as RequestInit).body as string))
    .toEqual({ traceId: "trace-1" });
});
