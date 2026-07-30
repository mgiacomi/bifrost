import { ACTIVITY_KIND_LABELS, type ActivityKind } from "../api/contracts";
import { useActivity } from "./ActivityProvider";
import { ActivityNarrative } from "./ActivityNarrative";

export function LiveActivity() {
  const {
    activities,
    recentCompletions,
    connected,
    connectionFact,
    error,
    loading,
    beginningUnavailable,
    continuity,
    loadRecent,
  } = useActivity();

  const replayGap = connectionFact?.reason === "relay_frame_limit" ||
    connectionFact?.reason === "relay_byte_limit" ||
    connectionFact?.reason === "replay_overflow" ||
    connectionFact?.reason === "subscriber_overflow";

  return (
    <section aria-labelledby="live-activity-title" className="overview-card">
      <p className="eyebrow">Real-time</p>
      <h2 id="live-activity-title">Live Activity</h2>

      <div className="status-indicator" role="status" aria-live="polite">
        <span
          className={`connection-dot ${connected ? "connected" : "disconnected"}`}
          aria-hidden="true"
        />
        {connected ? "Connected" : connectionFact?.reason ? `Disconnected: ${connectionFact.reason}` : "Disconnected"}
      </div>
      <p className="observability-note">
        {connectionFact?.at
          ? `Connection fact observed at ${connectionFact.at}.`
          : "Connection observation time is unavailable."}
        {continuity?.observedAt
          ? ` Upstream continuity observed at ${continuity.observedAt}.`
          : ""}
      </p>

      {continuity?.reset && (
        <div className="continuity-reset-notice" role="status" aria-live="polite">
          Activity window was reset ({continuity.reset.cause}). Earlier events may be unavailable.
        </div>
      )}

      {replayGap && (
        <div className="replay-gap-notice" role="alert">
          Some events were not delivered in real time.
          <button
            type="button"
            className="replay-gap-action"
            onClick={() => void loadRecent()}
          >
            Load recent
          </button>
        </div>
      )}

      {loading && <p aria-live="polite">Loading recent activity…</p>}

      {error && (
        <div className="target-error" role="alert">
          <strong>{error.message}</strong>
        </div>
      )}

      {beginningUnavailable && (
        <div className="activity-notice" role="status">
          Earlier activity is no longer available. Showing the most recent window.
        </div>
      )}

      {activities.length === 0 && !loading && !error && (
        <p className="empty-state">No activity yet. Events will appear here as they occur.</p>
      )}

      {activities.length > 0 && (
        <ActivityNarrative activities={activities} isLive={connected} />
      )}

      {recentCompletions.length > 0 && (
        <details className="recent-completions">
          <summary>Recent completions ({recentCompletions.length})</summary>
          <ol className="activity-list" aria-label="Recently completed executions">
            {recentCompletions.map((activity) => (
              <li key={`completion-${activity.cursor}`} className="activity-item">
                <span className="activity-kind" aria-label={ACTIVITY_KIND_LABELS[activity.kind as ActivityKind] ?? activity.kind}>
                  {ACTIVITY_KIND_LABELS[activity.kind as ActivityKind] ?? activity.kind}
                </span>
                <span className="activity-summary">{activity.summary}</span>
                <span className="activity-meta">
                  {activity.sessionId} · {activity.timestamp}
                </span>
              </li>
            ))}
          </ol>
        </details>
      )}
    </section>
  );
}
