import { useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router";
import { BrowserAPIError, getActiveExecutionDetail } from "../api/client";
import type { ActiveExecution } from "../api/contracts";
import { useTarget } from "../target/TargetProvider";
import {
  recoverObservabilityError,
  requireCurrentTargetScope,
} from "./scope";
import { useScopeBoundRoute } from "./useScopeBoundRoute";

export function ActiveExecutionDetailView() {
  const { sessionId } = useParams();
  const { target, scopeGeneration, refresh } = useTarget();
  const [execution, setExecution] = useState<ActiveExecution | null>(null);
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
    if (!sessionId || !routeIsCurrent) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    setExecution(null);
    getActiveExecutionDetail(sessionId)
      .then(async (e) => {
        await requireCurrentTargetScope(e.targetScopeId, target.status.targetScopeId, refreshTarget.current);
        if (!cancelled) setExecution(e);
      })
      .catch(async (err) => {
        const recovered = await recoverObservabilityError(err, refreshTarget.current);
        if (cancelled) return;
        setError(recovered);
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [routeIsCurrent, sessionId, scopeGeneration, target.status.targetScopeId]);

  return (
    <section aria-labelledby="active-execution-detail-title" className="overview-card">
      <p className="eyebrow">Operational views</p>
      <h2 id="active-execution-detail-title" ref={heading} tabIndex={-1}>Active Execution Detail</h2>

      <p>
        <Link to="/active-executions">Back to Active Executions</Link>
      </p>

      {error && (
        <div className="target-error" role="alert">
          <strong>{error.message}</strong>
        </div>
      )}

      {loading && <p>Loading execution detail…</p>}

      {execution && (
        <>
          <dl className="status-grid">
            <div><dt>Session ID</dt><dd>{execution.sessionId}</dd></div>
            <div><dt>Trace ID</dt><dd>{execution.traceId}</dd></div>
            <div><dt>Entry skill</dt><dd>{execution.entrySkill}</dd></div>
            <div><dt>Status</dt><dd>{execution.status}</dd></div>
            <div><dt>Phase</dt><dd>{execution.phase}</dd></div>
            <div><dt>Summary</dt><dd>{execution.summary}</dd></div>
            <div><dt>Started at</dt><dd>{execution.startedAt}</dd></div>
            <div><dt>Updated at</dt><dd>{execution.updatedAt}</dd></div>
            <div><dt>Elapsed (ms)</dt><dd>{String(execution.elapsedMillis)}</dd></div>
            <div><dt>Last canonical sequence</dt><dd>{String(execution.lastCanonicalSequence)}</dd></div>
            <div><dt>Total frame depth</dt><dd>{String(execution.totalFrameDepth)}</dd></div>
            <div><dt>Active path truncated</dt><dd>{String(execution.activePathTruncated)}</dd></div>
          </dl>

          <h3>Usage</h3>
          <dl className="status-grid">
            <div><dt>Skill invocations</dt><dd>{String(execution.usage.skillInvocations)}</dd></div>
            <div><dt>Tool invocations</dt><dd>{String(execution.usage.toolInvocations)}</dd></div>
            <div><dt>Linter retries</dt><dd>{String(execution.usage.linterRetries)}</dd></div>
            <div><dt>Model calls</dt><dd>{String(execution.usage.modelCalls)}</dd></div>
            <div><dt>Prompt units</dt><dd>{String(execution.usage.promptUnits)}</dd></div>
            <div><dt>Completion units</dt><dd>{String(execution.usage.completionUnits)}</dd></div>
            <div><dt>Usage units</dt><dd>{String(execution.usage.usageUnits)}</dd></div>
            <div><dt>Exact model responses</dt><dd>{String(execution.usage.exactModelResponses)}</dd></div>
            <div><dt>Heuristic model responses</dt><dd>{String(execution.usage.heuristicModelResponses)}</dd></div>
            <div><dt>Unavailable model responses</dt><dd>{String(execution.usage.unavailableModelResponses)}</dd></div>
          </dl>

          <h3>Configured Limits</h3>
          <dl className="status-grid">
            <div><dt>Max skill invocations</dt><dd>{String(execution.configuredLimits.maxSkillInvocations)}</dd></div>
            <div><dt>Max tool invocations</dt><dd>{String(execution.configuredLimits.maxToolInvocations)}</dd></div>
            <div><dt>Max linter retries</dt><dd>{String(execution.configuredLimits.maxLinterRetries)}</dd></div>
            <div><dt>Max model calls</dt><dd>{String(execution.configuredLimits.maxModelCalls)}</dd></div>
            <div><dt>Max usage units</dt><dd>{String(execution.configuredLimits.maxUsageUnits)}</dd></div>
          </dl>

          {(execution.activePath?.length ?? 0) > 0 && (
            <>
              <h3>Active Path</h3>
              <table className="observability-table">
                <thead>
                  <tr>
                    <th scope="col">Frame ID</th>
                    <th scope="col">Frame type</th>
                    <th scope="col">Route</th>
                  </tr>
                </thead>
                <tbody>
                  {execution.activePath.map((frame, index) => (
                    <tr key={frame.frameId || index}>
                      <td>{frame.frameId}</td>
                      <td>{frame.frameType}</td>
                      <td>{frame.route}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </>
      )}
    </section>
  );
}
