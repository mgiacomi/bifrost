import { render, screen } from "@testing-library/react";
import { beforeEach, expect, test, vi } from "vitest";
import type { ReactNode } from "react";
import type { InstanceStatus, SkillSummary } from "../api/contracts";
import { BrowserAPIError } from "../api/client";

const observabilityView = vi.hoisted(() => ({
  current: undefined as unknown as {
    instance: { status: InstanceStatus | undefined; error: BrowserAPIError | undefined };
    skills: { items: SkillSummary[]; hasMore: boolean; nextCursor: string | null; loading: boolean; error?: BrowserAPIError };
    loadInstance: ReturnType<typeof vi.fn>;
    loadSkills: ReturnType<typeof vi.fn>;
    loadActiveExecutions: ReturnType<typeof vi.fn>;
    loadTraces: ReturnType<typeof vi.fn>;
  },
}));
const routerView = vi.hoisted(() => ({
  state: null as { staleTargetScope?: boolean } | null,
}));

vi.mock("./ObservabilityProvider", () => ({
  useObservability: () => observabilityView.current,
  ObservabilityProvider: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

vi.mock("../target/TargetProvider", () => ({
  useTarget: () => ({
    target: {
      address: "https://application.example",
      status: {
        targetScopeId: "scope-1",
        targetSelection: "SELECTED",
        targetConnection: "REACHABLE",
        targetAuthentication: "ESTABLISHED",
        javaGoCompatibility: "COMPATIBLE",
        runtimeIdentity: "ESTABLISHED",
        liveMonitoring: "AVAILABLE",
      },
    },
  }),
}));

const activityView = vi.hoisted(() => ({
  current: {
    activities: [],
    recentCompletions: [],
    connected: false,
    connectionFact: null,
    error: null,
    loading: false,
    beginningUnavailable: false,
    continuity: null,
    loadRecent: vi.fn(),
  },
}));

vi.mock("../activity/ActivityProvider", () => ({
  useActivity: () => activityView.current,
  ActivityProvider: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

vi.mock("react-router", () => ({
  Link: ({ children, to }: { children: ReactNode; to: string }) => (
    <a href={to}>{children}</a>
  ),
  useLocation: () => ({ state: routerView.state }),
}));

import { ObservabilityOverview } from "./Overview";

const instanceStatus: InstanceStatus = {
  targetScopeId: "scope-1",
  instanceId: "11111111-1111-4111-8111-111111111111",
  consoleCompatibilityVersion: "0.1.0-SNAPSHOT",
  observedAt: "2026-07-27T00:00:00Z",
  liveMonitoringAvailable: true,
  registeredSkillCount: 3,
  activeExecutionCount: 1,
  catalogedTraceCount: 5,
  tracePersistencePolicy: "PERSISTENT",
  completionGraceTtl: "PT2M",
  traceCatalogMetadataTtl: "PT168H",
};

beforeEach(() => {
  routerView.state = null;
  observabilityView.current = {
    instance: { status: undefined, error: undefined },
    skills: { items: [], hasMore: false, nextCursor: null, loading: false },
    loadInstance: vi.fn(),
    loadSkills: vi.fn(),
    loadActiveExecutions: vi.fn(),
    loadTraces: vi.fn(),
  };
});

test("overview renders instance facts when status is available", () => {
  observabilityView.current.instance.status = instanceStatus;
  render(<ObservabilityOverview />);
  expect(screen.getByText("11111111-1111-4111-8111-111111111111")).toBeInTheDocument();
  expect(screen.getByText("3")).toBeInTheDocument();
  expect(screen.getByText("1")).toBeInTheDocument();
  expect(screen.getByText("5")).toBeInTheDocument();
  expect(screen.getByText("PERSISTENT")).toBeInTheDocument();
  expect(screen.getByText("PT2M")).toBeInTheDocument();
  expect(screen.getByText("PT168H")).toBeInTheDocument();
  expect(screen.getByText("https://application.example")).toBeInTheDocument();
});

test("overview renders navigation links to catalog views", () => {
  observabilityView.current.instance.status = instanceStatus;
  render(<ObservabilityOverview />);
  expect(screen.getByText("Skill Catalog")).toBeInTheDocument();
  expect(screen.getByText("Active Executions")).toBeInTheDocument();
  expect(screen.getByText("Trace Catalog")).toBeInTheDocument();
});

test("overview renders error message when instance load fails", () => {
  const error = new BrowserAPIError("TARGET_UNAVAILABLE", "Target is down", 503);
  observabilityView.current.instance.error = error;
  render(<ObservabilityOverview />);
  expect(screen.getByText("Target is down")).toBeInTheDocument();
});

test("overview does not duplicate the workspace instance request", () => {
  const loadInstance = vi.fn();
  observabilityView.current.instance = null as never;
  observabilityView.current.loadInstance = loadInstance;
  render(<ObservabilityOverview />);
  expect(loadInstance).not.toHaveBeenCalled();
});

test("overview explains that a stale target-bound view was discarded", () => {
  routerView.state = { staleTargetScope: true };
  render(<ObservabilityOverview />);
  expect(
    screen.getByText("The selected target changed. The previous view was discarded."),
  ).toBeInTheDocument();
});

test("overview renders loading state when instance is loading", () => {
  observabilityView.current.instance = { status: undefined, error: undefined, loading: true } as never;
  render(<ObservabilityOverview />);
  expect(screen.getByText("Loading instance overview…")).toBeInTheDocument();
});
