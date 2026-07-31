import { useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router";
import {
  BrowserAPIError,
  acquireArtifact,
  getTraceDetail,
  rawArtifactDownloadURL,
} from "../api/client";
import type { AcquiredArtifact, Trace } from "../api/contracts";
import { useTarget } from "../target/TargetProvider";
import { useBrowserSession } from "../security/BrowserSessionProvider";
import {
  recoverObservabilityError,
  requireCurrentTargetScope,
} from "./scope";
import { useScopeBoundRoute } from "./useScopeBoundRoute";

export function TraceDetailView() {
  const { traceId } = useParams();
  const { target, scopeGeneration, refresh } = useTarget();
  const session = useBrowserSession();
  const [trace, setTrace] = useState<Trace | null>(null);
  const [error, setError] = useState<BrowserAPIError | null>(null);
  const [loading, setLoading] = useState(true);
  const [acquiring, setAcquiring] = useState(false);
  const [acquireError, setAcquireError] = useState<BrowserAPIError | null>(null);
  const [acquired, setAcquired] = useState<AcquiredArtifact | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const refreshTarget = useRef(refresh);
  refreshTarget.current = refresh;
  const routeIsCurrent = useScopeBoundRoute();

  useEffect(() => {
    heading.current?.focus();
  }, []);

  useEffect(() => {
    if (!traceId || !routeIsCurrent) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    setTrace(null);
    getTraceDetail(traceId)
      .then(async (t) => {
        await requireCurrentTargetScope(t.targetScopeId, target.status.targetScopeId, refreshTarget.current);
        if (!cancelled) setTrace(t);
      })
      .catch(async (err) => {
        const recovered = await recoverObservabilityError(err, refreshTarget.current);
        if (cancelled) return;
        setError(recovered);
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [routeIsCurrent, traceId, scopeGeneration, target.status.targetScopeId]);

  const handleAcquire = async () => {
    if (!traceId) return;
    const security = session.getSecurity();
    if (!security) {
      setAcquireError(new BrowserAPIError("SESSION_REQUIRED", "Pairing is required.", 401));
      return;
    }
    setAcquiring(true);
    setAcquireError(null);
    setAcquired(null);
    try {
      const result = await acquireArtifact(traceId, security);
      setAcquired(result);
    } catch (err) {
      const recovered = await recoverObservabilityError(err, refreshTarget.current);
      setAcquireError(recovered);
    } finally {
      setAcquiring(false);
    }
  };

  return (
    <section aria-labelledby="trace-detail-title" className="overview-card">
      <p className="eyebrow">Operational views</p>
      <h2 id="trace-detail-title" ref={heading} tabIndex={-1}>Trace Detail</h2>

      <p>
        <Link to="/traces">Back to Trace Catalog</Link>
      </p>

      {error && (
        <div className="target-error" role="alert">
          <strong>{error.message}</strong>
        </div>
      )}

      {loading && <p>Loading trace detail…</p>}

      {trace && (
        <>
          <dl className="status-grid">
            <div><dt>Trace ID</dt><dd>{trace.traceId}</dd></div>
            <div><dt>Session ID</dt><dd>{trace.sessionId}</dd></div>
            <div><dt>Outcome</dt><dd>{trace.outcome}</dd></div>
            <div><dt>Finalized at</dt><dd>{trace.finalizedAt}</dd></div>
            <div><dt>Size (bytes)</dt><dd>{String(trace.sizeBytes)}</dd></div>
            <div><dt>Persistence policy</dt><dd>{trace.persistencePolicy}</dd></div>
            <div><dt>Application trace expires at</dt><dd>{trace.applicationTraceExpiresAt}</dd></div>
            <div><dt>Application availability at acquisition</dt><dd>{trace.applicationAvailability ?? "Not observed locally"}</dd></div>
            <div><dt>Local artifact</dt><dd>{trace.localAvailable ? "Available" : "Not installed"}</dd></div>
          </dl>

          <div className="trace-actions">
            <h3>Artifact actions</h3>
            <p>
              <button
                type="button"
                onClick={() => void handleAcquire()}
                disabled={acquiring}
              >
                {acquiring ? "Acquiring…" : "Acquire for analysis"}
              </button>
            </p>
            <p>
              <a
                href={rawArtifactDownloadURL(trace.traceId)}
                download
              >
                Raw artifact download
              </a>
            </p>
            <p className="trace-actions-note">
              Acquire installs a local analysis copy. Raw download streams the
              artifact directly from the application without installing or
              extending a local copy. Semantic explorer views arrive in a future
              update.
            </p>

            {acquireError && (
              <div className="target-error" role="alert">
                <strong>{acquireError.message}</strong>
              </div>
            )}

            {acquired && (
              <div role="status">
                <p>Artifact acquired successfully.</p>
                <dl className="status-grid">
                  <div><dt>Handle</dt><dd>{acquired.artifactHandle}</dd></div>
                  <div><dt>Local bytes</dt><dd>{String(acquired.localBytes)}</dd></div>
                  <div><dt>Acquired at</dt><dd>{acquired.acquiredAt}</dd></div>
                  <div><dt>Expires at</dt><dd>{acquired.hasIdleExpiry ? acquired.expiresAt : "Never"}</dd></div>
                </dl>
              </div>
            )}
          </div>
        </>
      )}
    </section>
  );
}
