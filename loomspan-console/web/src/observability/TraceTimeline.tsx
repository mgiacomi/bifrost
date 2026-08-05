import type { TraceFrame } from "../api/contracts";

export function TraceTimeline({ frames, selectedFrameId, onSelect }: { frames: TraceFrame[]; selectedFrameId?: string; onSelect: (frameId: string) => void }) {
  const complete = frames.filter((frame) => frame.closedTimestampMillis != null);
  const start = complete.length ? Math.min(...complete.map((frame) => frame.openedTimestampMillis)) : 0;
  const end = complete.length ? Math.max(...complete.map((frame) => frame.closedTimestampMillis as number)) : start;
  const span = Math.max(1, end - start);
  return <div className="trace-timeline" aria-label="Trace timeline">
    {frames.map((frame) => {
      const closed = frame.closedTimestampMillis;
      const available = closed != null && frame.inclusiveDurationMillis != null;
      const x = available ? ((frame.openedTimestampMillis - start) / span) * 1000 : 0;
      const width = available ? Math.max(2, ((closed - frame.openedTimestampMillis) / span) * 1000) : 0;
      return <div className="trace-timeline-row" key={frame.frameId} aria-current={selectedFrameId === frame.frameId ? "true" : undefined}>
        <button type="button" onClick={() => onSelect(frame.frameId)}>{frame.route || frame.frameId}</button>
        {available ? <svg viewBox="0 0 1000 20" role="img" aria-label={`${frame.inclusiveDurationMillis} ms, ${frame.selfDurationMillis == null ? "self timing unavailable" : `${frame.selfDurationMillis} ms self`}`} preserveAspectRatio="none"><rect className="trace-timeline-track" x="0" y="5" width="1000" height="10" /><rect className="trace-timeline-bar" x={x} y="5" width={width} height="10" /></svg> : <span>Timing unavailable or incomplete</span>}
      </div>;
    })}
  </div>;
}
