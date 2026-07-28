import { useEffect, useRef } from "react";
import { Link } from "react-router";
import { useObservability } from "./ObservabilityProvider";
import type { ActiveExecution } from "../api/contracts";
import { scopeBoundPath } from "./scope";

export function ActiveExecutions() {
  const { activeExecutions, loadActiveExecutions } = useObservability();
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    heading.current?.focus();
  }, []);

  useEffect(() => {
    if (!activeExecutions.loaded && !activeExecutions.loading && !activeExecutions.error) {
      void loadActiveExecutions();
    }
  }, [activeExecutions, loadActiveExecutions]);

  return (
    <section aria-labelledby="active-executions-title" className="overview-card">
      <p className="eyebrow">Operational views</p>
      <h2 id="active-executions-title" ref={heading} tabIndex={-1}>Active Executions</h2>
      <button type="button" disabled={activeExecutions.loading} onClick={() => void loadActiveExecutions()}>
        Refresh
      </button>

      {activeExecutions.error && (
        <div className="target-error" role="alert">
          <strong>{activeExecutions.error.message}</strong>
          <div>
            <button type="button" disabled={activeExecutions.loading} onClick={() => void loadActiveExecutions()}>
              Retry
            </button>
          </div>
        </div>
      )}

      {activeExecutions.loading && <p>Loading active executions…</p>}

      {activeExecutions.loaded && !activeExecutions.loading && activeExecutions.items.length === 0 && !activeExecutions.error && (
        <p>No active executions.</p>
      )}

      {activeExecutions.items.length > 0 && (
        <div className="observability-table-region" role="region" aria-label="Active executions table" tabIndex={0}>
          <table className="observability-table">
          <thead>
            <tr>
              <th scope="col">Session</th>
              <th scope="col">Entry skill</th>
              <th scope="col">Status</th>
              <th scope="col">Phase</th>
              <th scope="col">Summary</th>
              <th scope="col">Updated</th>
            </tr>
          </thead>
          <tbody>
            {activeExecutions.items.map((exec) => {
              const e = exec as ActiveExecution;
              return (
                <tr key={e.sessionId}>
                  <td>
                    <Link to={scopeBoundPath(`/active-executions/${encodeURIComponent(e.sessionId)}`, activeExecutions.targetScopeId)}>{e.sessionId}</Link>
                  </td>
                  <td>{e.entrySkill}</td>
                  <td>{e.status}</td>
                  <td>{e.phase}</td>
                  <td>{e.summary}</td>
                  <td>{e.updatedAt}</td>
                </tr>
              );
            })}
          </tbody>
          </table>
        </div>
      )}

      {activeExecutions.hasMore && activeExecutions.nextCursor && (
        <button type="button" disabled={activeExecutions.loading} onClick={() => void loadActiveExecutions(activeExecutions.nextCursor ?? undefined)}>
          Load more
        </button>
      )}

    </section>
  );
}
