import { useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router";
import { BrowserAPIError, getTraceDetail } from "../api/client";
import type { Trace } from "../api/contracts";
import { useTarget } from "../target/TargetProvider";
import {
  recoverObservabilityError,
  requireCurrentTargetScope,
} from "./scope";
import { useScopeBoundRoute } from "./useScopeBoundRoute";

export function TraceDetailView() {
  const { traceId } = useParams();
  const { target, scopeGeneration, refresh } = useTarget();
  const [trace, setTrace] = useState<Trace | null>(null);
  const [error, setError] = useState<BrowserAPIError | null>(null);
  const [loading, setLoading] = useState(true);
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
        <dl className="status-grid">
          <div><dt>Trace ID</dt><dd>{trace.traceId}</dd></div>
          <div><dt>Session ID</dt><dd>{trace.sessionId}</dd></div>
          <div><dt>Outcome</dt><dd>{trace.outcome}</dd></div>
          <div><dt>Finalized at</dt><dd>{trace.finalizedAt}</dd></div>
          <div><dt>Size (bytes)</dt><dd>{String(trace.sizeBytes)}</dd></div>
          <div><dt>Persistence policy</dt><dd>{trace.persistencePolicy}</dd></div>
          <div><dt>Application trace expires at</dt><dd>{trace.applicationTraceExpiresAt}</dd></div>
        </dl>
      )}
    </section>
  );
}
