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
import { useOptionalActivity } from "../activity/ActivityProvider";
import { ActivityNarrative } from "../activity/ActivityNarrative";
import { ActivePath } from "../activity/ActivePath";
import { CurrentExecutionSummary } from "../activity/CurrentExecutionSummary";
import { scopeBoundPath } from "./scope";
import { useOptionalObservability } from "./ObservabilityProvider";

export function ActiveExecutionDetailView() {
  const { sessionId } = useParams();
  const { target, scopeGeneration, refresh } = useTarget();
  const observability = useOptionalObservability();
  const baselineExecution = observability?.activeExecutions.items.find(
    (item) => (item as ActiveExecution).sessionId === sessionId,
  ) as ActiveExecution | undefined;
  const [execution, setExecution] = useState<ActiveExecution | null>(
    () => baselineExecution ?? null,
  );
  const selectedExecution =
    execution?.sessionId === sessionId ? execution : (baselineExecution ?? null);
  const [error, setError] = useState<BrowserAPIError | null>(null);
  const [loading, setLoading] = useState(true);
  const [observationEnded, setObservationEnded] = useState(false);
  const heading = useRef<HTMLHeadingElement>(null);
  const refreshTarget = useRef(refresh);
  refreshTarget.current = refresh;
  const routeIsCurrent = useScopeBoundRoute();
  const activity = useOptionalActivity();
  const selectedActivity = (activity?.activities ?? []).filter((item) => item.sessionId === sessionId);
  const terminalActivity = selectedActivity.findLast((item) =>
    item.kind === "TRACE_COMPLETED" || item.kind === "EXECUTION_OBSERVATION_ENDED"
  );
  const terminalActivityRef = useRef(terminalActivity);
  terminalActivityRef.current = terminalActivity;
  const artifactAvailable =
    terminalActivity?.kind === "TRACE_COMPLETED" &&
    terminalActivity.details?.artifactAvailability === "AVAILABLE";
  const finalizationFailed =
    terminalActivity?.kind === "EXECUTION_OBSERVATION_ENDED" &&
    terminalActivity.details?.artifactAvailability === "CORE_FINALIZATION_FAILED";
  const traceID = selectedExecution?.traceId ?? terminalActivity?.traceId;
  const traceScopeID =
    selectedExecution?.targetScopeId ?? target.status.targetScopeId;
  const displayedStatus = terminalActivity?.executionStatus ??
    (observationEnded ? "OBSERVATION ENDED" : selectedExecution?.status);

  useEffect(() => {
    heading.current?.focus();
  }, []);

  useEffect(() => {
    if (terminalActivity) setObservationEnded(true);
  }, [terminalActivity?.cursor]);

  useEffect(() => {
    if (!selectedExecution && baselineExecution) setExecution(baselineExecution);
  }, [baselineExecution, selectedExecution]);

  useEffect(() => {
    if (!sessionId || !routeIsCurrent) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    if (execution?.sessionId !== sessionId) {
      setExecution(baselineExecution ?? null);
      setObservationEnded(false);
    }
    getActiveExecutionDetail(sessionId)
      .then(async (e) => {
        await requireCurrentTargetScope(e.targetScopeId, target.status.targetScopeId, refreshTarget.current);
        if (!cancelled) {
          setExecution(e);
          if (!terminalActivityRef.current) setObservationEnded(false);
        }
      })
      .catch(async (err) => {
        const recovered = await recoverObservabilityError(err, refreshTarget.current);
        if (cancelled) return;
        if (
          recovered.code === "NOT_FOUND" &&
          (selectedExecution || terminalActivityRef.current)
        ) {
          setObservationEnded(true);
        } else {
          setError(recovered);
        }
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [routeIsCurrent, sessionId, scopeGeneration, target.status.targetScopeId, activity?.baselineObservedAt]);

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

      {(selectedExecution || selectedActivity.length > 0) && (
        <>
          <div className="selected-live-execution" aria-label="Selected execution live activity">
            <h3>Live activity</h3>
            {activity?.continuity?.reset && (
              <p role="status">Activity continuity reset: {activity.continuity.reset.cause}</p>
            )}
            {terminalActivity && (
              <p role="status">
                {terminalActivity.kind === "TRACE_COMPLETED"
                  ? "Execution completed. Context is preserved."
                  : finalizationFailed
                    ? "Execution observation ended without an outcome because trace finalization failed."
                    : "Execution observation ended before a trustworthy outcome was available."}
              </p>
            )}
            {observationEnded && !terminalActivity && (
              <p role="status">
                This execution is no longer present in the active baseline. No
                terminal activity was observed, so outcome and trace
                availability are unknown.
              </p>
            )}
            {artifactAvailable && traceID && (
              <p>
                <Link to={scopeBoundPath(`/traces/${encodeURIComponent(traceID)}`, traceScopeID)}>
                  Inspect trace
                </Link>
              </p>
            )}
            <CurrentExecutionSummary
              execution={selectedExecution}
              activities={selectedActivity}
              observedAt={
                activity?.baselineObservedAt ?? activity?.continuity?.observedAt
              }
              connected={activity?.connected ?? false}
              observationEnded={observationEnded}
            />
            <ActivePath execution={selectedExecution} />
            <ActivityNarrative activities={selectedActivity} isLive={activity?.connected ?? false} />
          </div>

          {selectedExecution && (
            <>
          <h3>{observationEnded ? "Last active snapshot" : "Active snapshot"}</h3>
          <dl className="status-grid">
            <div><dt>Session ID</dt><dd>{selectedExecution.sessionId}</dd></div>
            <div><dt>Trace ID</dt><dd>{selectedExecution.traceId}</dd></div>
            <div><dt>Entry skill</dt><dd>{selectedExecution.entrySkill}</dd></div>
            <div><dt>Status</dt><dd>{displayedStatus}</dd></div>
            <div><dt>Phase</dt><dd>{selectedExecution.phase}</dd></div>
            <div><dt>Summary</dt><dd>{selectedExecution.summary}</dd></div>
            <div><dt>Started at</dt><dd>{selectedExecution.startedAt}</dd></div>
            <div><dt>Updated at</dt><dd>{selectedExecution.updatedAt}</dd></div>
            <div><dt>Elapsed (ms)</dt><dd>{String(selectedExecution.elapsedMillis)}</dd></div>
            <div><dt>Last canonical sequence</dt><dd>{String(selectedExecution.lastCanonicalSequence)}</dd></div>
            <div><dt>Total frame depth</dt><dd>{String(selectedExecution.totalFrameDepth)}</dd></div>
            <div><dt>Active path truncated</dt><dd>{String(selectedExecution.activePathTruncated)}</dd></div>
          </dl>

          <h3>Usage</h3>
          <dl className="status-grid">
            <div><dt>Skill invocations</dt><dd>{String(selectedExecution.usage.skillInvocations)}</dd></div>
            <div><dt>Tool invocations</dt><dd>{String(selectedExecution.usage.toolInvocations)}</dd></div>
            <div><dt>Linter retries</dt><dd>{String(selectedExecution.usage.linterRetries)}</dd></div>
            <div><dt>Model calls</dt><dd>{String(selectedExecution.usage.modelCalls)}</dd></div>
            <div><dt>Prompt units</dt><dd>{String(selectedExecution.usage.promptUnits)}</dd></div>
            <div><dt>Completion units</dt><dd>{String(selectedExecution.usage.completionUnits)}</dd></div>
            <div><dt>Usage units</dt><dd>{String(selectedExecution.usage.usageUnits)}</dd></div>
            <div><dt>Exact model responses</dt><dd>{String(selectedExecution.usage.exactModelResponses)}</dd></div>
            <div><dt>Heuristic model responses</dt><dd>{String(selectedExecution.usage.heuristicModelResponses)}</dd></div>
            <div><dt>Unavailable model responses</dt><dd>{String(selectedExecution.usage.unavailableModelResponses)}</dd></div>
          </dl>

          <h3>Configured Limits</h3>
          <dl className="status-grid">
            <div><dt>Max skill invocations</dt><dd>{String(selectedExecution.configuredLimits.maxSkillInvocations)}</dd></div>
            <div><dt>Max tool invocations</dt><dd>{String(selectedExecution.configuredLimits.maxToolInvocations)}</dd></div>
            <div><dt>Max linter retries</dt><dd>{String(selectedExecution.configuredLimits.maxLinterRetries)}</dd></div>
            <div><dt>Max model calls</dt><dd>{String(selectedExecution.configuredLimits.maxModelCalls)}</dd></div>
            <div><dt>Max usage units</dt><dd>{String(selectedExecution.configuredLimits.maxUsageUnits)}</dd></div>
          </dl>

            </>
          )}
        </>
      )}
    </section>
  );
}
