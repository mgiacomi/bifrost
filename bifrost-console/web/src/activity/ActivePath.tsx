import type { ActiveExecution } from "../api/contracts";

type ActivePathProps = {
  execution: ActiveExecution | null;
  maxFrames?: number;
};

export function ActivePath({ execution, maxFrames = 8 }: ActivePathProps) {
  if (!execution || execution.activePath.length === 0) {
    return null;
  }

  const display = execution.activePath.slice(-maxFrames);
  const truncated =
    execution.activePathTruncated ||
    execution.totalFrameDepth > execution.activePath.length ||
    execution.activePath.length > maxFrames;

  return (
    <nav className="active-path" aria-label="Current bounded active skill path">
      <p className="observability-note">Current bounded path from the latest application snapshot.</p>
      {truncated && <span className="active-path-truncated" aria-label="Earlier frames truncated">…</span>}
      <ol className="active-path-list">
        {display.map((frame, index) => (
          <li
            key={frame.frameId}
            className={`active-path-item${index === display.length - 1 ? " current" : ""}`}
          >
            <span className="active-path-frame-type">{frame.frameType}</span>
            {frame.route && <span className="active-path-route">{frame.route}</span>}
          </li>
        ))}
      </ol>
    </nav>
  );
}
