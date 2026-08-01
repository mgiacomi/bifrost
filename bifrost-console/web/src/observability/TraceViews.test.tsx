import { fireEvent, render, screen } from "@testing-library/react";
import { expect, test, vi } from "vitest";
import { TraceHierarchy } from "./TraceHierarchy";
import { TraceTimeline } from "./TraceTimeline";
import { TraceUsage } from "./TraceUsage";
import { TraceRecords } from "./TraceRecords";
import { TraceEvidenceDetail } from "./TraceEvidenceDetail";

const frame = { frameId: "frame-1", parentFrameId: null, childFrameIds: [], frameType: "SKILL", route: "hello", openedTimestampMillis: 10, closedTimestampMillis: 15, inclusiveDurationMillis: 5, selfDurationMillis: null, directUsage: { promptUnits: 1, completionUnits: 1, totalUnits: 2 }, directUsageComplete: true, descendantUsage: { promptUnits: 0, completionUnits: 0, totalUnits: 0 }, descendantUsageComplete: true, inclusiveUsage: { promptUnits: 1, completionUnits: 1, totalUnits: 2 }, inclusiveUsageComplete: true };
test("hierarchy and timeline select returned frames without recalculating them", () => {
  const select = vi.fn();
  const second = { ...frame, frameId: "frame-2", route: "second" };
  const { rerender } = render(<TraceHierarchy frames={[frame, second]} onSelect={select} />);
  fireEvent.click(screen.getByRole("button", { name: "SKILL: hello" }));
  fireEvent.keyDown(screen.getByRole("button", { name: "SKILL: hello" }), { key: "ArrowDown" });
  expect(screen.getByRole("button", { name: "SKILL: second" })).toHaveFocus();
  expect(select).toHaveBeenCalledWith("frame-1");
  rerender(<TraceTimeline frames={[frame]} onSelect={select} />);
  fireEvent.click(screen.getByRole("button", { name: "hello" }));
  expect(select).toHaveBeenLastCalledWith("frame-1");
  expect(screen.getByRole("img", { name: "5 ms, self timing unavailable" })).toBeInTheDocument();
});
test("hierarchy supports semantic expansion and parent keyboard navigation", () => {
  const child = { ...frame, frameId: "frame-2", parentFrameId: "frame-1", route: "child" };
  const root = { ...frame, childFrameIds: ["frame-2"] };
  render(<TraceHierarchy frames={[root, child]} onSelect={vi.fn()} />);
  fireEvent.click(screen.getByRole("button", { name: "Collapse hello" }));
  expect(screen.queryByRole("button", { name: "SKILL: child" })).toBeNull();
  const rootButton = screen.getByRole("button", { name: "SKILL: hello" });
  rootButton.focus();
  fireEvent.keyDown(rootButton, { key: "ArrowRight" });
  const childButton = screen.getByRole("button", { name: "SKILL: child" });
  fireEvent.keyDown(rootButton, { key: "ArrowRight" });
  expect(childButton).toHaveFocus();
  fireEvent.keyDown(childButton, { key: "ArrowLeft" });
  expect(rootButton).toHaveFocus();
});
test("timeline and selected-frame usage preserve unknown and incomplete returned facts", () => {
  const incomplete = { ...frame, frameId: "open", openedTimestampMillis: 20, closedTimestampMillis: null, inclusiveDurationMillis: null, directUsageComplete: false, inclusiveUsageComplete: false };
  const { rerender } = render(<TraceTimeline frames={[incomplete]} onSelect={vi.fn()} />);
  expect(screen.getByText("Timing unavailable or incomplete")).toBeInTheDocument();
  expect(screen.queryByRole("img")).toBeNull();
  rerender(<TraceUsage usage={{ targetScopeId: "scope-1", attributed: frame.inclusiveUsage, unattributed: frame.descendantUsage, unframedAttributed: frame.descendantUsage, terminal: frame.directUsage }} frame={incomplete} />);
  expect(screen.getByRole("table", { name: "Usage facts" })).toHaveTextContent("Selected frame direct (incomplete)");
});
test("usage preserves returned values and records keep evidence actions deliberate", () => {
  const raw = vi.fn(); const payload = vi.fn(); const selectRecord = vi.fn(); const selectFailure = vi.fn();
  render(<><TraceUsage usage={{ targetScopeId: "scope-1", attributed: { promptUnits: 1, completionUnits: 2, totalUnits: 3 }, unattributed: { promptUnits: 0, completionUnits: 0, totalUnits: 0 }, unframedAttributed: { promptUnits: 0, completionUnits: 0, totalUnits: 0 }, terminal: { promptUnits: 1, completionUnits: 2, totalUnits: 3 } }} /><TraceRecords records={[{ sequence: 1, type: "PAYLOAD", frameId: "frame-1", parentFrameId: "", frameType: "SKILL", route: "", threadName: "worker", timestampMillis: 0, representation: "logical", isChunk: false, isEnvelope: true, payloadId: "payload-1" }]} attempts={[{ retrySequenceId: "retry", attemptId: "attempt", attemptNumber: 1, usage: { promptUnits: 1, completionUnits: 0, totalUnits: 1 }, usageComplete: false }]} retries={[{ retrySequenceId: "retry", usage: { promptUnits: 1, completionUnits: 0, totalUnits: 1 }, usageComplete: false }]} failures={[{ failureId: "failure", terminal: true }]} validations={[{ status: "VALID", retrySequenceId: "retry", attemptId: "attempt", attemptNumber: 1 }]} gaps={[{ kind: "GAP", frameId: "", attemptId: "" }]} uncertainties={[{ kind: "UNKNOWN", frameId: "" }]} payloads={[{ payloadId: "payload-2", sequence: 1, contentType: "text/plain", chunkCount: 1, storeLength: 3 }]} onSelectRecord={selectRecord} onSelectFailure={selectFailure} onRaw={raw} onPayload={payload} /></>);
  expect(screen.getByRole("table", { name: "Usage facts" })).toHaveTextContent("3");
  fireEvent.click(screen.getByRole("button", { name: "Read raw record" })); fireEvent.click(screen.getByRole("button", { name: "Read payload" }));
  expect(raw).toHaveBeenCalled(); expect(payload).toHaveBeenCalledWith("payload-1");
  fireEvent.click(screen.getByRole("button", { name: "1: PAYLOAD" })); fireEvent.click(screen.getByRole("button", { name: "failure (terminal)" })); expect(selectRecord).toHaveBeenCalled(); expect(selectFailure).toHaveBeenCalledWith("failure");
  fireEvent.click(screen.getByRole("button", { name: "payload-2" })); expect(payload).toHaveBeenLastCalledWith("payload-2");
});
test("evidence detail renders text inertly and exposes continuation", () => {
  const next = vi.fn(); const clear = vi.fn();
  render(<TraceEvidenceDetail range={{ targetScopeId: "scope-1", actualStart: 0, actualEnd: 2, totalLength: 4, contentType: "text/plain", encoding: "TEXT", content: "<a>unsafe</a>", hasMore: true, nextCursor: "next" }} onNext={next} onClear={clear} />);
  expect(screen.queryByRole("link")).toBeNull(); fireEvent.click(screen.getByRole("button", { name: "Read next range" })); fireEvent.click(screen.getByRole("button", { name: "Clear content" })); expect(next).toHaveBeenCalled(); expect(clear).toHaveBeenCalled();
});
test("evidence detail labels base64 without interpreting content", () => {
  render(<TraceEvidenceDetail range={{ targetScopeId: "scope-1", actualStart: 4, actualEnd: 8, totalLength: 12, contentType: "application/octet-stream", encoding: "BASE64", content: "AQIDBA==", hasMore: false, nextCursor: null }} onNext={vi.fn()} onClear={vi.fn()} />);
  expect(screen.getByText(/Base64-encoded bytes 4/)).toBeInTheDocument();
  expect(screen.getByText("AQIDBA==")).toBeInTheDocument();
});
