import { afterEach, expect, test, vi } from "vitest";
import { bootstrap, createPairingLink, exchangePairing, heartbeatTab } from "./client";

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
