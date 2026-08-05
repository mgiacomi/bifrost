import { render, screen, fireEvent } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { LiveActivity } from "./LiveActivity";
import { BrowserAPIError } from "../api/client";
import type { Activity, ActivityKind, ConnectionFact, Continuity } from "../api/contracts";

function makeActivity(
  cursor: string,
  kind: ActivityKind,
  summary: string,
): Activity {
  return {
    instanceId: "11111111-1111-4111-8111-111111111111",
    cursor,
    sessionId: "session-1",
    traceId: "trace-1",
    canonicalSequence: parseInt(cursor, 10),
    timestamp: "2026-07-25T12:00:00Z",
    kind,
    executionStatus: "RUNNING",
    summary,
    details: {},
  };
}

type ActivityView = {
  activities: Activity[];
  recentCompletions: Activity[];
  connected: boolean;
  connectionFact: ConnectionFact | null;
  error: BrowserAPIError | null;
  loading: boolean;
  lastCursor: string | null;
  continuity: Continuity | null;
  baselineObservedAt: string | null;
  beginningUnavailable: boolean;
  reconnectAttempt: number;
  loadRecent: () => Promise<void>;
};

const baseView: ActivityView = {
  activities: [],
  recentCompletions: [],
  connected: false,
  connectionFact: null,
  error: null,
  loading: false,
  lastCursor: null,
  continuity: null,
  baselineObservedAt: null,
  beginningUnavailable: false,
  reconnectAttempt: 0,
  loadRecent: vi.fn(),
};

let view: ActivityView;

vi.mock("./ActivityProvider", () => ({
  useActivity: () => view,
}));

describe("LiveActivity", () => {
  beforeEach(() => {
    view = { ...baseView, loadRecent: vi.fn() };
  });

  it("renders title and connection status when disconnected", () => {
    render(<LiveActivity />);
    expect(screen.getByRole("heading", { name: "Live Activity" })).toBeInTheDocument();
    expect(screen.getByText("Disconnected")).toBeInTheDocument();
  });

  it("renders connected status", () => {
    view.connected = true;
    render(<LiveActivity />);
    expect(screen.getByText("Connected")).toBeInTheDocument();
  });

  it("renders disconnected reason from connectionFact", () => {
    view.connectionFact = { connected: false, reason: "stream_closed" };
    render(<LiveActivity />);
    expect(screen.getByText("Disconnected: stream_closed")).toBeInTheDocument();
  });

  it("renders empty state when no activities and not loading", () => {
    render(<LiveActivity />);
    expect(screen.getByText("No activity yet. Events will appear here as they occur.")).toBeInTheDocument();
  });

  it("renders loading indicator", () => {
    view.loading = true;
    render(<LiveActivity />);
    expect(screen.getByText("Loading recent activity…")).toBeInTheDocument();
  });

  it("renders error message", () => {
    view.error = new BrowserAPIError("CONSOLE_ERROR", "Something went wrong", 500);
    render(<LiveActivity />);
    expect(screen.getByText("Something went wrong")).toBeInTheDocument();
  });

  it("renders beginning unavailable notice", () => {
    view.beginningUnavailable = true;
    render(<LiveActivity />);
    expect(screen.getByText(/Earlier activity is no longer available/)).toBeInTheDocument();
  });

  it("renders continuity reset notice", () => {
    view.continuity = {
      targetScopeId: "scope-1",
      instanceId: "inst-1",
      reset: { cause: "target_scope_changed", timestamp: "2026-07-25T12:00:00Z" },
    } as Continuity;
    render(<LiveActivity />);
    expect(screen.getByText(/Activity window was reset/)).toBeInTheDocument();
    expect(screen.getByText(/target_scope_changed/)).toBeInTheDocument();
  });

  it("renders replay gap notice with load recent button", () => {
    view.connectionFact = { connected: false, reason: "relay_frame_limit" };
    render(<LiveActivity />);
    expect(screen.getByRole("alert")).toHaveTextContent(/Some events were not delivered/);
    const button = screen.getByRole("button", { name: "Load recent" });
    fireEvent.click(button);
    expect(view.loadRecent).toHaveBeenCalledOnce();
  });

  it("does not render replay gap for non-gap reasons", () => {
    view.connectionFact = { connected: false, reason: "stream_closed" };
    render(<LiveActivity />);
    expect(screen.queryByText(/Some events were not delivered/)).toBeNull();
  });

  it("renders activity components when activities exist", () => {
    view.activities = [
      makeActivity("1", "TRACE_STARTED", "Started"),
      makeActivity("2", "STEP_COMPLETED", "Step done"),
    ];
    view.connected = true;
    render(<LiveActivity />);
    expect(screen.getByText("Started")).toBeInTheDocument();
    expect(screen.getAllByText("Step done").length).toBeGreaterThanOrEqual(1);
  });

  it("renders recent completions section", () => {
    view.recentCompletions = [
      makeActivity("1", "TRACE_COMPLETED", "Execution finished"),
    ];
    render(<LiveActivity />);
    expect(screen.getByText("Recent completions (1)")).toBeInTheDocument();
    expect(screen.getByText("Execution finished")).toBeInTheDocument();
  });

  it("does not render recent completions when empty", () => {
    render(<LiveActivity />);
    expect(screen.queryByText(/Recent completions/)).toBeNull();
  });
});
