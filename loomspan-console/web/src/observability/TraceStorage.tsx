import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router";
import {
  BrowserAPIError,
  clearAllUnusedArtifacts,
  clearExpiredArtifacts,
  getStorageSnapshot,
  removeArtifact,
} from "../api/client";
import type { StorageSnapshot } from "../api/contracts";
import { useTarget } from "../target/TargetProvider";
import { useBrowserSession } from "../security/BrowserSessionProvider";
import { recoverObservabilityError } from "./scope";
import { useScopeBoundRoute } from "./useScopeBoundRoute";

export function TraceStorage() {
  const { target, scopeGeneration, refresh } = useTarget();
  const session = useBrowserSession();
  const [snapshot, setSnapshot] = useState<StorageSnapshot | null>(null);
  const [error, setError] = useState<BrowserAPIError | null>(null);
  const [loading, setLoading] = useState(true);
  const [actionError, setActionError] = useState<BrowserAPIError | null>(null);
  const [confirmRemove, setConfirmRemove] = useState<string | null>(null);
  const [confirmClear, setConfirmClear] = useState<"expired" | "all-unused" | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const refreshTarget = useRef(refresh);
  refreshTarget.current = refresh;
  const routeIsCurrent = useScopeBoundRoute();

  useEffect(() => {
    heading.current?.focus();
  }, []);

  const loadSnapshot = useCallback(async () => {
    const security = session.getSecurity();
    if (!security) {
      setError(new BrowserAPIError("SESSION_REQUIRED", "Pairing is required.", 401));
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const result = await getStorageSnapshot(security);
      setSnapshot(result);
    } catch (err) {
      const recovered = await recoverObservabilityError(err, refreshTarget.current);
      setError(recovered);
    } finally {
      setLoading(false);
    }
  }, [session]);

  useEffect(() => {
    if (!routeIsCurrent) return;
    void loadSnapshot();
  }, [routeIsCurrent, scopeGeneration, target.status.targetScopeId, loadSnapshot]);

  const handleRemove = useCallback(async (traceId: string) => {
    const security = session.getSecurity();
    if (!security) return;
    setActionError(null);
    try {
      await removeArtifact(traceId, security);
      setConfirmRemove(null);
      await loadSnapshot();
    } catch (err) {
      const recovered = await recoverObservabilityError(err, refreshTarget.current);
      setActionError(recovered);
    }
  }, [session, loadSnapshot]);

  const handleClearExpired = useCallback(async () => {
    const security = session.getSecurity();
    if (!security) return;
    setActionError(null);
    try {
      await clearExpiredArtifacts(security);
      setConfirmClear(null);
      await loadSnapshot();
    } catch (err) {
      const recovered = await recoverObservabilityError(err, refreshTarget.current);
      setActionError(recovered);
    }
  }, [session, loadSnapshot]);

  const handleClearAllUnused = useCallback(async () => {
    const security = session.getSecurity();
    if (!security) return;
    setActionError(null);
    try {
      await clearAllUnusedArtifacts(security);
      setConfirmClear(null);
      await loadSnapshot();
    } catch (err) {
      const recovered = await recoverObservabilityError(err, refreshTarget.current);
      setActionError(recovered);
    }
  }, [session, loadSnapshot]);

  return (
    <section aria-labelledby="trace-storage-title" className="overview-card">
      <p className="eyebrow">Artifact cache</p>
      <h2 id="trace-storage-title" ref={heading} tabIndex={-1}>Trace Storage</h2>

      <p>
        <Link to="/traces">Back to Trace Catalog</Link>
      </p>

      {error && (
        <div className="target-error" role="alert">
          <strong>{error.message}</strong>
        </div>
      )}

      {actionError && (
        <div className="target-error" role="alert">
          <strong>{actionError.message}</strong>
        </div>
      )}

      {loading && <p>Loading storage snapshot…</p>}

      {snapshot && (
        <>
          <dl className="status-grid">
            <div><dt>Workspace</dt><dd>{snapshot.workspaceLabel}</dd></div>
            <div><dt>Maximum bytes</dt><dd>{snapshot.unlimited ? "Unlimited" : String(snapshot.maxBytes)}</dd></div>
            <div><dt>Idle TTL</dt><dd>{snapshot.neverExpire ? "Never" : snapshot.idleTtl}</dd></div>
            <div><dt>Charged bytes</dt><dd>{String(snapshot.chargedBytes)}</dd></div>
            <div><dt>Acquired count</dt><dd>{String(snapshot.acquiredCount)}</dd></div>
          </dl>

          <div className="storage-actions">
            {confirmClear === "expired" ? (
              <>
                <button
                  type="button"
                  onClick={() => void handleClearExpired()}
                  aria-label="Confirm clearing expired artifacts"
                >
                  Confirm clear expired
                </button>
                <button type="button" onClick={() => setConfirmClear(null)}>
                  Cancel
                </button>
              </>
            ) : confirmClear === "all-unused" ? (
              <>
                <button
                  type="button"
                  onClick={() => void handleClearAllUnused()}
                  aria-label="Confirm clearing all unused artifacts"
                >
                  Confirm clear all unused
                </button>
                <button type="button" onClick={() => setConfirmClear(null)}>
                  Cancel
                </button>
              </>
            ) : (
              <>
                <button type="button" onClick={() => setConfirmClear("expired")}>
                  Clear expired
                </button>
                <button type="button" onClick={() => setConfirmClear("all-unused")}>
                  Clear all unused
                </button>
              </>
            )}
          </div>

          {snapshot.entries.length === 0 ? (
            <p>No artifacts are currently stored.</p>
          ) : (
            <table className="storage-table">
              <thead>
                <tr>
                  <th scope="col">Trace ID</th>
                  <th scope="col">Session ID</th>
                  <th scope="col">Outcome</th>
                  <th scope="col">Local bytes</th>
                  <th scope="col">App availability at acquisition</th>
                  <th scope="col">Local</th>
                  <th scope="col">Active pin</th>
                  <th scope="col">Acquired at</th>
                  <th scope="col">Last used</th>
                  <th scope="col">Expires at</th>
                  <th scope="col">Actions</th>
                </tr>
              </thead>
              <tbody>
                {snapshot.entries.map((entry) => (
                  <tr key={entry.traceId}>
                    <td>
                      <Link to={`/traces/${encodeURIComponent(entry.traceId)}`}>
                        {entry.traceId}
                      </Link>
                    </td>
                    <td>{entry.sessionId}</td>
                    <td>{entry.outcome}</td>
                    <td>{String(entry.localBytes)}</td>
                    <td>{entry.applicationAvailability}</td>
                    <td>{entry.localAvailable ? "Yes" : "No"}</td>
                    <td>{entry.activePin ? "Yes" : "No"}</td>
                    <td>{entry.acquiredAt}</td>
                    <td>{entry.lastUsedAt}</td>
                    <td>{entry.hasIdleExpiry ? entry.expiresAt : "Never"}</td>
                    <td>
                      {entry.activePin ? (
                        <span aria-label="Cannot remove: artifact is in use">In use</span>
                      ) : confirmRemove === entry.traceId ? (
                        <>
                          <button
                            type="button"
                            onClick={() => void handleRemove(entry.traceId)}
                            aria-label={`Confirm removal of ${entry.traceId}`}
                          >
                            Confirm
                          </button>
                          <button
                            type="button"
                            onClick={() => setConfirmRemove(null)}
                          >
                            Cancel
                          </button>
                        </>
                      ) : (
                        <button
                          type="button"
                          onClick={() => setConfirmRemove(entry.traceId)}
                        >
                          Remove
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </section>
  );
}
