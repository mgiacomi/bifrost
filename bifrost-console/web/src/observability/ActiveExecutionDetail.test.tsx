import { render, screen } from "@testing-library/react";
import { beforeEach, expect, test, vi } from "vitest";
import type { ReactNode } from "react";
import type { ActiveExecution } from "../api/contracts";

const route = vi.hoisted(() => ({
  scope: "scope-1",
  navigate: vi.fn(),
}));

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
  useParams: () => ({ sessionId: "session-1" }),
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
  route.navigate.mockReset();
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
    expect(screen.getByText("session-1")).toBeInTheDocument();
  });
  expect(screen.getByText("trace-1")).toBeInTheDocument();
  expect(screen.getByText("CheckDns")).toBeInTheDocument();
  expect(screen.getByText("EXECUTING")).toBeInTheDocument();
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
