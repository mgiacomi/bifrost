import { render, screen, fireEvent } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ActivityNarrative } from "./ActivityNarrative";
import type { Activity, ActivityKind } from "../api/contracts";

function makeActivity(cursor: string, kind: ActivityKind, summary: string): Activity {
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

describe("ActivityNarrative", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("renders empty state when no activities", () => {
    render(<ActivityNarrative activities={[]} isLive={true} />);
    expect(screen.getByText("No activity yet.")).toBeInTheDocument();
  });

  it("renders activity items in order", () => {
    const activities = [
      makeActivity("1", "TRACE_STARTED", "Execution began"),
      makeActivity("2", "STEP_COMPLETED", "Step done"),
    ];
    render(<ActivityNarrative activities={activities} isLive={true} />);
    expect(screen.getByText("Execution began")).toBeInTheDocument();
    expect(screen.getByText("Step done")).toBeInTheDocument();
    expect(screen.getByText("2 events")).toBeInTheDocument();
  });

  it("follows newest item initially (follow button shows pause)", () => {
    render(<ActivityNarrative activities={[makeActivity("1", "TRACE_STARTED", "Started")]} isLive={true} />);
    const toggle = screen.getByRole("button", { name: "Pause auto-scroll" });
    expect(toggle).toHaveAttribute("aria-pressed", "true");
  });

  it("pauses following when user scrolls backward", () => {
    const activities = [makeActivity("1", "TRACE_STARTED", "Started")];
    render(<ActivityNarrative activities={activities} isLive={true} />);
    const list = screen.getByRole("log");

    Object.defineProperty(list, "scrollHeight", { value: 1000, configurable: true });
    Object.defineProperty(list, "clientHeight", { value: 200, configurable: true });
    Object.defineProperty(list, "scrollTop", { value: 0, configurable: true });

    fireEvent.scroll(list);

    const toggle = screen.getByRole("button", { name: "Resume auto-scroll" });
    expect(toggle).toHaveAttribute("aria-pressed", "false");
  });

  it("resumes following on button click", () => {
    const activities = [makeActivity("1", "TRACE_STARTED", "Started")];
    render(<ActivityNarrative activities={activities} isLive={true} />);

    const list = screen.getByRole("log");
    Object.defineProperty(list, "scrollHeight", { value: 1000, configurable: true });
    Object.defineProperty(list, "clientHeight", { value: 200, configurable: true });
    Object.defineProperty(list, "scrollTop", { value: 0, configurable: true, writable: true });
    fireEvent.scroll(list);

    const toggle = screen.getByRole("button", { name: "Resume auto-scroll" });
    fireEvent.click(toggle);

    expect(screen.getByRole("button", { name: "Pause auto-scroll" })).toHaveAttribute("aria-pressed", "true");
  });

  it("disables following when stream goes offline", () => {
    const { rerender } = render(
      <ActivityNarrative activities={[makeActivity("1", "TRACE_STARTED", "Started")]} isLive={true} />,
    );
    expect(screen.getByRole("button", { name: "Pause auto-scroll" })).toHaveAttribute("aria-pressed", "true");

    rerender(
      <ActivityNarrative activities={[makeActivity("1", "TRACE_STARTED", "Started")]} isLive={false} />,
    );
    expect(screen.getByRole("button", { name: "Resume auto-scroll" })).toHaveAttribute("aria-pressed", "false");
  });

  it("renders untrusted summary as text only", () => {
    const activities = [
      makeActivity("1", "ERROR_RECORDED", "<script>alert('xss')</script>"),
    ];
    const { container } = render(<ActivityNarrative activities={activities} isLive={true} />);
    expect(container.querySelector("script")).toBeNull();
    expect(screen.getByText("<script>alert('xss')</script>")).toBeInTheDocument();
  });

  it("shows outcome for TRACE_COMPLETED", () => {
    const activities = [
      makeActivity("1", "TRACE_COMPLETED", "Execution finished"),
    ];
    render(<ActivityNarrative activities={activities} isLive={true} />);
    expect(screen.getByText(/Outcome:/)).toBeInTheDocument();
  });

  it("does not show outcome for EXECUTION_OBSERVATION_ENDED", () => {
    const activities = [
      makeActivity("1", "EXECUTION_OBSERVATION_ENDED", "Observation ended"),
    ];
    render(<ActivityNarrative activities={activities} isLive={true} />);
    expect(screen.queryByText(/Outcome:/)).toBeNull();
  });

  it("uses role=log and aria-live for accessibility", () => {
    render(<ActivityNarrative activities={[makeActivity("1", "TRACE_STARTED", "Started")]} isLive={true} />);
    const list = screen.getByRole("log");
    expect(list).toHaveAttribute("aria-live", "polite");
    expect(list).toHaveAttribute("aria-relevant", "additions");
  });
});
