import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, expect, test, vi } from "vitest";
import type { ReactNode } from "react";
import type { AcquiredArtifact, Trace } from "../api/contracts";

const route = vi.hoisted(() => ({
  scope: "scope-1",
  navigate: vi.fn(),
}));

vi.mock("../api/client", () => ({
  getTraceDetail: vi.fn(),
  acquireArtifact: vi.fn(),
  rawArtifactDownloadURL: (traceId: string) => `/api/console/v1/artifacts/${encodeURIComponent(traceId)}/raw`,
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

vi.mock("../security/BrowserSessionProvider", () => ({
  useBrowserSession: () => ({
    getSecurity: () => ({ tabId: "test-tab", csrfToken: "test-token" }),
  }),
}));

import { getTraceDetail, acquireArtifact } from "../api/client";
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
  localAvailable: false,
};

beforeEach(() => {
  vi.mocked(getTraceDetail).mockReset();
  vi.mocked(acquireArtifact).mockReset();
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

const acquiredArtifact: AcquiredArtifact = {
  artifactHandle: "handle-abc",
  traceId: "trace-1",
  sessionId: "session-1",
  outcome: "SUCCEEDED",
  finalizedAt: "2026-07-27T10:10:00Z",
  localBytes: 4096,
  acquiredAt: "2026-07-27T10:15:00Z",
  lastUsedAt: "2026-07-27T10:15:00Z",
  expiresAt: "2026-07-27T10:20:00Z",
  hasIdleExpiry: true,
};

test("trace detail renders acquire button and raw download link", async () => {
  vi.mocked(getTraceDetail).mockResolvedValue(trace);
  render(<TraceDetailView />);
  await vi.waitFor(() => {
    expect(screen.getByText("trace-1")).toBeInTheDocument();
  });
  expect(screen.getByRole("button", { name: "Acquire for analysis" })).toBeInTheDocument();
  const link = screen.getByRole("link", { name: "Raw artifact download" });
  expect(link).toBeInTheDocument();
  expect(link).toHaveAttribute("download");
  expect(link.getAttribute("href")).toContain(encodeURIComponent("trace-1"));
});

test("acquire button calls acquireArtifact and shows success state", async () => {
  vi.mocked(getTraceDetail).mockResolvedValue(trace);
  vi.mocked(acquireArtifact).mockResolvedValue(acquiredArtifact);
  render(<TraceDetailView />);
  await vi.waitFor(() => {
    expect(screen.getByText("trace-1")).toBeInTheDocument();
  });
  fireEvent.click(screen.getByRole("button", { name: "Acquire for analysis" }));
  await vi.waitFor(() => {
    expect(screen.getByText("Artifact acquired successfully.")).toBeInTheDocument();
  });
  expect(acquireArtifact).toHaveBeenCalledWith("trace-1", { tabId: "test-tab", csrfToken: "test-token" });
  expect(screen.getByText("handle-abc")).toBeInTheDocument();
  expect(screen.getAllByText("4096").length).toBeGreaterThan(0);
});

test("acquire button shows error on failure", async () => {
  const { BrowserAPIError } = await import("../api/client");
  vi.mocked(getTraceDetail).mockResolvedValue(trace);
  vi.mocked(acquireArtifact).mockRejectedValue(
    new BrowserAPIError("ARTIFACT_IN_USE", "Artifact in use", 409),
  );
  render(<TraceDetailView />);
  await vi.waitFor(() => {
    expect(screen.getByText("trace-1")).toBeInTheDocument();
  });
  fireEvent.click(screen.getByRole("button", { name: "Acquire for analysis" }));
  await vi.waitFor(() => {
    expect(screen.getByText("Artifact in use")).toBeInTheDocument();
  });
  expect(screen.getByRole("alert")).toBeInTheDocument();
});

test("trace detail shows application availability and local artifact status", async () => {
  const traceWithAvailability: Trace = {
    ...trace,
    applicationAvailability: "AVAILABLE",
    localAvailable: true,
  };
  vi.mocked(getTraceDetail).mockResolvedValue(traceWithAvailability);
  render(<TraceDetailView />);
  await vi.waitFor(() => {
    expect(screen.getByText("AVAILABLE")).toBeInTheDocument();
  });
  expect(screen.getByText("Available")).toBeInTheDocument();
});

test("trace detail shows that local acquisition availability was not observed", async () => {
  vi.mocked(getTraceDetail).mockResolvedValue(trace);
  render(<TraceDetailView />);
  await vi.waitFor(() => {
    expect(screen.getByText("Not observed locally")).toBeInTheDocument();
  });
});
