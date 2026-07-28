import { render, screen } from "@testing-library/react";
import { beforeEach, expect, test, vi } from "vitest";
import type { ReactNode } from "react";
import type { Trace } from "../api/contracts";

const route = vi.hoisted(() => ({
  scope: "scope-1",
  navigate: vi.fn(),
}));

vi.mock("../api/client", () => ({
  getTraceDetail: vi.fn(),
  BrowserAPIError: class BrowserAPIError extends Error {
    code: string;
    status: number;
    constructor(code: string, message: string, status: number) {
      super(message);
      this.code = code;
      this.status = status;
    }
  },
}));

vi.mock("react-router", () => ({
  Link: ({ children, to }: { children: ReactNode; to: string }) => (
    <a href={to}>{children}</a>
  ),
  useParams: () => ({ traceId: "trace-1" }),
  useNavigate: () => route.navigate,
  useSearchParams: () => [new URLSearchParams({ targetScopeId: route.scope })],
}));

vi.mock("../target/TargetProvider", () => ({
  useTarget: () => ({
    target: { status: { targetScopeId: "scope-1" } },
    scopeGeneration: 0,
    refresh: vi.fn().mockResolvedValue(undefined),
  }),
}));

import { getTraceDetail } from "../api/client";
import { TraceDetailView } from "./TraceDetail";

const trace: Trace = {
  targetScopeId: "scope-1",
  traceId: "trace-1",
  sessionId: "session-1",
  outcome: "SUCCEEDED",
  finalizedAt: "2026-07-27T10:10:00Z",
  sizeBytes: 4096,
  persistencePolicy: "PERSISTENT",
  applicationTraceExpiresAt: "2026-08-03T10:10:00Z",
};

beforeEach(() => {
  vi.mocked(getTraceDetail).mockReset();
  route.scope = "scope-1";
  route.navigate.mockReset();
});

test("stale trace deep link resets before requesting the identifier", async () => {
  route.scope = "scope-old";
  render(<TraceDetailView />);
  await vi.waitFor(() => expect(route.navigate).toHaveBeenCalled());
  expect(getTraceDetail).not.toHaveBeenCalled();
});

test("trace detail renders facts when loaded", async () => {
  vi.mocked(getTraceDetail).mockResolvedValue(trace);
  render(<TraceDetailView />);
  await vi.waitFor(() => {
    expect(screen.getByText("trace-1")).toBeInTheDocument();
  });
  expect(screen.getByText("session-1")).toBeInTheDocument();
  expect(screen.getByText("SUCCEEDED")).toBeInTheDocument();
  expect(screen.getByText("PERSISTENT")).toBeInTheDocument();
});

test("trace detail renders loading state", () => {
  vi.mocked(getTraceDetail).mockReturnValue(new Promise(() => {}));
  render(<TraceDetailView />);
  expect(screen.getByText("Loading trace detail…")).toBeInTheDocument();
});

test("trace detail renders error state", async () => {
  const { BrowserAPIError } = await import("../api/client");
  vi.mocked(getTraceDetail).mockRejectedValue(
    new BrowserAPIError("NOT_FOUND", "Trace not found", 404),
  );
  render(<TraceDetailView />);
  await vi.waitFor(() => {
    expect(screen.getByText("Trace not found")).toBeInTheDocument();
  });
});
