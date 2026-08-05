import { useCallback, useEffect, useRef, useState } from "react";
import type { Activity } from "../api/contracts";
import { presentActivity, formatTimestamp } from "./activityPresentation";

type ActivityNarrativeProps = {
  activities: Activity[];
  isLive: boolean;
};

export function ActivityNarrative({ activities, isLive }: ActivityNarrativeProps) {
  const [following, setFollowing] = useState(true);
  const listRef = useRef<HTMLOListElement>(null);
  const wasAtBottomRef = useRef(true);

  const isAtBottom = useCallback(() => {
    const el = listRef.current;
    if (!el) return true;
    return el.scrollHeight - el.scrollTop - el.clientHeight < 4;
  }, []);

  const scrollToBottom = useCallback(() => {
    const el = listRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, []);

  useEffect(() => {
    if (following && wasAtBottomRef.current) {
      scrollToBottom();
    }
  }, [activities, following, scrollToBottom]);

  const handleScroll = useCallback(() => {
    const atBottom = isAtBottom();
    wasAtBottomRef.current = atBottom;
    if (!atBottom && following) {
      setFollowing(false);
    }
  }, [isAtBottom, following]);

  const handleFollowToggle = useCallback(() => {
    setFollowing((prev) => {
      if (!prev) {
        wasAtBottomRef.current = true;
        scrollToBottom();
      }
      return !prev;
    });
  }, [scrollToBottom]);

  useEffect(() => {
    if (!isLive && following) {
      setFollowing(false);
    }
  }, [isLive, following]);

  return (
    <div className="activity-narrative">
      <div className="activity-narrative-controls">
        <button
          type="button"
          className="follow-toggle"
          onClick={handleFollowToggle}
          aria-pressed={following}
          aria-label={following ? "Pause auto-scroll" : "Resume auto-scroll"}
        >
          {following ? "⏸ Pause" : "▶ Follow"}
        </button>
        <span className="activity-count" aria-live="polite">
          {activities.length} event{activities.length !== 1 ? "s" : ""}
        </span>
      </div>
      <ol
        ref={listRef}
        onScroll={handleScroll}
        className="activity-narrative-list"
        aria-label="Activity narrative"
        role="log"
        aria-live="polite"
        aria-relevant="additions"
      >
        {activities.length === 0 && (
          <li className="activity-narrative-empty">No activity yet.</li>
        )}
        {activities.map((activity) => {
          const p = presentActivity(activity);
          return (
            <li
              key={activity.cursor}
              className={`activity-narrative-item${p.isError ? " error" : ""}${p.isTerminal ? " terminal" : ""}${p.isFrameBoundary ? " frame-boundary" : ""}`}
            >
              <span className="activity-narrative-time" aria-hidden="true">
                {formatTimestamp(activity.timestamp)}
              </span>
              <span className="activity-narrative-kind">{p.label}</span>
              <span className="activity-narrative-summary">{activity.summary}</span>
              {p.outcome && (
                <span className="activity-narrative-outcome">Outcome: {p.outcome}</span>
              )}
              {p.artifactAvailable && (
                <span className="activity-narrative-artifact">Artifact available</span>
              )}
              <span className="activity-narrative-meta">
                {activity.sessionId}
                {activity.frameId ? ` · ${activity.frameId}` : ""}
              </span>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
