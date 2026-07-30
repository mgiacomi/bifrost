import { render, screen } from "@testing-library/react";
import { beforeEach, expect, test, vi } from "vitest";
import type { ReactNode } from "react";
import type { ActiveExecution } from "../api/contracts";

const route = vi.hoisted(() => ({
  scope: "scope-1",
  sessionId: "session-1",
  navigate: vi.fn(),
}));
const activityView = vi.hoisted(() => ({ current: undefined as any }));

vi.mock("../api/client", () => ({
  getActiveExecutionDetail: vi.fn(),
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
  useParams: () => ({ sessionId: route.sessionId }),
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

vi.mock("../activity/ActivityProvider", () => ({
  useOptionalActivity: () => activityView.current,
}));

import { getActiveExecutionDetail } from "../api/client";
import { ActiveExecutionDetailView } from "./ActiveExecutionDetail";

const execution: ActiveExecution = {
  targetScopeId: "scope-1",
  sessionId: "session-1",
  traceId: "trace-1",
  lastCanonicalSequence: 42,
  startedAt: "2026-07-27T10:00:00Z",
  updatedAt: "2026-07-27T10:05:00Z",
  elapsedMillis: 300000,
  entrySkill: "CheckDns",
  status: "ACTIVE",
  phase: "EXECUTING",
  summary: "Checking DNS records",
  activePath: [],
  totalFrameDepth: 1,
  activePathTruncated: false,
  usage: {
    skillInvocations: 1, toolInvocations: 2, linterRetries: 0, modelCalls: 3,
    promptUnits: 100, completionUnits: 50, usageUnits: 150,
    exactModelResponses: 2, heuristicModelResponses: 1, unavailableModelResponses: 0,
  },
  configuredLimits: {
    maxSkillInvocations: 10, maxToolInvocations: 20, maxLinterRetries: 5,
    maxModelCalls: 30, maxUsageUnits: 1000,
  },
};

beforeEach(() => {
  vi.mocked(getActiveExecutionDetail).mockReset();
  route.scope = "scope-1";
  route.sessionId = "session-1";
  route.navigate.mockReset();
  activityView.current = undefined;
});

test("stale execution deep link resets before requesting the identifier", async () => {
  route.scope = "scope-old";
  render(<ActiveExecutionDetailView />);
  await vi.waitFor(() => expect(route.navigate).toHaveBeenCalled());
  expect(getActiveExecutionDetail).not.toHaveBeenCalled();
});

test("active execution detail renders facts when loaded", async () => {
  vi.mocked(getActiveExecutionDetail).mockResolvedValue(execution);
  render(<ActiveExecutionDetailView />);
  await vi.waitFor(() => {
    expect(screen.getAllByText("session-1").length).toBeGreaterThan(0);
  });
  expect(screen.getAllByText("trace-1").length).toBeGreaterThan(0);
  expect(screen.getByText("CheckDns")).toBeInTheDocument();
  expect(screen.getAllByText("EXECUTING").length).toBeGreaterThan(0);
});

test("active execution detail renders loading state", () => {
  vi.mocked(getActiveExecutionDetail).mockReturnValue(new Promise(() => {}));
  render(<ActiveExecutionDetailView />);
  expect(screen.getByText("Loading execution detail…")).toBeInTheDocument();
});

test("active execution detail renders error state", async () => {
  const { BrowserAPIError } = await import("../api/client");
  vi.mocked(getActiveExecutionDetail).mockRejectedValue(
    new BrowserAPIError("NOT_FOUND", "Execution not found", 404),
  );
  render(<ActiveExecutionDetailView />);
  await vi.waitFor(() => {
    expect(screen.getByText("Execution not found")).toBeInTheDocument();
  });
});

test("terminal activity preserves selected context when the active detail request returns not found", async () => {
  const { BrowserAPIError } = await import("../api/client");
  activityView.current = {
    activities: [{
      instanceId: "11111111-1111-4111-8111-111111111111",
      cursor: "7",
      sessionId: "session-1",
      traceId: "trace-1",
      canonicalSequence: 7,
      timestamp: "2026-07-27T10:05:00Z",
      kind: "TRACE_COMPLETED",
      executionStatus: "COMPLETED",
      summary: "Execution completed",
      details: { outcome: "succeeded" },
    }],
    connected: true,
    continuity: null,
  };
  vi.mocked(getActiveExecutionDetail).mockRejectedValue(
    new BrowserAPIError("NOT_FOUND", "Execution not found", 404),
  );

  render(<ActiveExecutionDetailView />);

  await vi.waitFor(() => {
    expect(screen.getByText("Execution completed. Context is preserved.")).toBeInTheDocument();
  });
  expect(screen.queryByText("Execution not found")).toBeNull();
  expect(screen.getByLabelText("Current execution summary")).toBeInTheDocument();
  expect(screen.getAllByText("Execution completed", { exact: true }).length).toBeGreaterThan(0);
});

test("missed terminal reconciliation preserves context without retaining an active claim", async () => {
  const { BrowserAPIError } = await import("../api/client");
  activityView.current = {
    activities: [],
    connected: true,
    continuity: null,
    baselineObservedAt: "2026-07-27T10:05:00Z",
  };
  vi.mocked(getActiveExecutionDetail)
    .mockResolvedValueOnce(execution)
    .mockRejectedValueOnce(
      new BrowserAPIError("NOT_FOUND", "Execution not found", 404),
    );

  const { rerender } = render(<ActiveExecutionDetailView />);
  await vi.waitFor(() => {
    expect(screen.getByLabelText("Status: ACTIVE")).toBeInTheDocument();
  });

  activityView.current = {
    ...activityView.current,
    baselineObservedAt: "2026-07-27T10:05:30Z",
  };
  rerender(<ActiveExecutionDetailView />);

  await vi.waitFor(() => {
    expect(
      screen.getByText(/No terminal activity was observed/),
    ).toBeInTheDocument();
    expect(
      screen.getByLabelText("Status: OBSERVATION ENDED"),
    ).toBeInTheDocument();
  });
  expect(screen.queryByLabelText("Status: ACTIVE")).toBeNull();
  expect(screen.queryByText("ACTIVE", { exact: true })).toBeNull();
  expect(screen.queryByLabelText("Terminal")).toBeNull();
  expect(screen.queryByRole("link", { name: "Inspect trace" })).toBeNull();
});

test("switching execution identifiers never renders the previous execution snapshot", async () => {
  vi.mocked(getActiveExecutionDetail)
    .mockResolvedValueOnce(execution)
    .mockReturnValueOnce(new Promise(() => {}));
  const { rerender } = render(<ActiveExecutionDetailView />);
  await vi.waitFor(() => {
    expect(screen.getAllByText("session-1").length).toBeGreaterThan(0);
  });

  route.sessionId = "session-2";
  rerender(<ActiveExecutionDetailView />);

  expect(screen.queryByText("session-1")).toBeNull();
});

test("finalization failure is distinct from a completed outcome", async () => {
  const { BrowserAPIError } = await import("../api/client");
  activityView.current = {
    activities: [{
      instanceId: "11111111-1111-4111-8111-111111111111",
      cursor: "8",
      sessionId: "session-1",
      traceId: "trace-1",
      canonicalSequence: 8,
      timestamp: "2026-07-27T10:05:01Z",
      kind: "EXECUTION_OBSERVATION_ENDED",
      executionStatus: "COMPLETED",
      summary: "Trace finalization failed",
      details: { artifactAvailability: "CORE_FINALIZATION_FAILED" },
    }],
    connected: false,
    continuity: null,
  };
  vi.mocked(getActiveExecutionDetail).mockRejectedValue(
    new BrowserAPIError("NOT_FOUND", "Execution not found", 404),
  );

  render(<ActiveExecutionDetailView />);

  await vi.waitFor(() => {
    expect(
      screen.getByText(
        "Execution observation ended without an outcome because trace finalization failed.",
      ),
    ).toBeInTheDocument();
  });
  expect(screen.getByLabelText("Terminal")).toHaveTextContent("observation ended");
  expect(screen.queryByRole("link", { name: "Inspect trace" })).toBeNull();
});
