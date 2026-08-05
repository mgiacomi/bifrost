import { useEffect, useRef } from "react";
import { Link, useLocation } from "react-router";
import { useObservability } from "./ObservabilityProvider";
import type { InstanceStatus } from "../api/contracts";
import { useTarget } from "../target/TargetProvider";
import { Overview as TargetOverview } from "../target/Overview";
import { LiveActivity } from "../activity/LiveActivity";

export function ObservabilityOverview() {
  const { instance, loadInstance } = useObservability();
  const { target } = useTarget();
  const location = useLocation();
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    heading.current?.focus();
  }, []);

  const status = instance?.status as InstanceStatus | undefined;
  const error = instance?.error;
  const loading = instance?.loading ?? false;
  const established =
    target.status.targetAuthentication === "ESTABLISHED" &&
    target.status.javaGoCompatibility === "COMPATIBLE" &&
    target.status.runtimeIdentity === "ESTABLISHED";

  return (
    <section aria-labelledby="observability-overview-title" className="overview-card">
      <p className="eyebrow">Operational views</p>
      <h2 id="observability-overview-title" ref={heading} tabIndex={-1}>Instance Overview</h2>
      {Boolean((location.state as { staleTargetScope?: boolean } | null)?.staleTargetScope) && (
        <div className="target-error" role="alert">
          <strong>The selected target changed. The previous view was discarded.</strong>
        </div>
      )}
      {!established && <TargetOverview />}
      {established && (
        <button type="button" disabled={loading} onClick={() => void loadInstance()}>
          Refresh
        </button>
      )}

      <dl className="status-grid" aria-label="Selected target context">
        <Fact name="Target" value={target.address ?? "Not selected"} />
        <Fact name="Connection" value={target.status.targetConnection} />
        <Fact name="Authentication" value={target.status.targetAuthentication} />
        <Fact name="Java/Go compatibility" value={target.status.javaGoCompatibility} />
        <Fact name="Live monitoring" value={target.status.liveMonitoring} />
      </dl>

      {error && (
        <div className="target-error" role="alert">
          <strong>{error.message}</strong>
        </div>
      )}

      {loading && <p>Loading instance overview…</p>}

      {status && (
        <>
          <dl className="status-grid" aria-label="Instance facts">
            <Fact name="Instance ID" value={status.instanceId} />
            <Fact name="Compatibility" value={status.consoleCompatibilityVersion} />
            <Fact name="Observed at" value={status.observedAt} />
            <Fact name="Live monitoring" value={status.liveMonitoringAvailable ? "Available" : "Unavailable"} />
            <Fact name="Registered skills" value={String(status.registeredSkillCount)} />
            <Fact name="Active executions" value={String(status.activeExecutionCount)} />
            <Fact name="Cataloged traces" value={String(status.catalogedTraceCount)} />
            <Fact name="Trace persistence" value={status.tracePersistencePolicy} />
            <Fact name="Completion grace TTL" value={status.completionGraceTtl} />
            <Fact name="Trace catalog metadata TTL" value={status.traceCatalogMetadataTtl} />
          </dl>

          <p className="observability-note">
            Catalog metadata TTL and core file retention are independent. Neither provides cross-restart history.
          </p>

          <nav className="observability-nav" aria-label="Operational views">
            <Link to="/skills">Skill Catalog</Link>
            <Link to="/active-executions">Active Executions</Link>
            <Link to="/traces">Trace Catalog</Link>
          </nav>

          <LiveActivity />
        </>
      )}
    </section>
  );
}

function Fact({ name, value }: { name: string; value: string }) {
  return (
    <div>
      <dt>{name}</dt>
      <dd>{value}</dd>
    </div>
  );
}
