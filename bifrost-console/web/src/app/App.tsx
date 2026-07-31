import { useEffect } from "react";
import { NavLink, Outlet } from "react-router";
import type { BuildMetadata } from "./metadata";
import { ThemeSelect } from "./ThemeSelect";
import { PairingPage } from "../security/PairingPage";
import { useBrowserSession } from "../security/BrowserSessionProvider";
import { TargetProvider, useTarget } from "../target/TargetProvider";
import { ObservabilityProvider, useObservability } from "../observability/ObservabilityProvider";
import { ActivityProvider } from "../activity/ActivityProvider";
import type { InstanceStatus } from "../api/contracts";

export function App({ metadata }: { metadata: BuildMetadata }) {
  const session = useBrowserSession();
  return (
    <div className="app-frame">
      <header className="shell-header">
        <div>
          <p className="eyebrow">Local developer tools</p>
          <h1>Bifrost Console</h1>
        </div>
        <ThemeSelect />
      </header>
      <main className="shell-main" id="main-content">
        {session.status === "paired" ? (
          <>
            <p className="workspace-path">
              Verified workspace <code>{session.bootstrap.workspacePath}</code>
            </p>
            <TargetProvider initial={session.bootstrap.target}>
              <ObservabilityProvider>
                <ActivityProvider>
                  <ConsoleWorkspace />
                </ActivityProvider>
              </ObservabilityProvider>
            </TargetProvider>
          </>
        ) : (
          <PairingPage />
        )}
      </main>
      <footer className="shell-footer">
        <span>Build version</span>
        <code data-testid="build-version">{metadata.version}</code>
      </footer>
    </div>
  );
}

function ConsoleWorkspace() {
  const { target } = useTarget();
  const { instance, loadInstance } = useObservability();
  const established =
    target.status.targetAuthentication === "ESTABLISHED" &&
    target.status.javaGoCompatibility === "COMPATIBLE";

  useEffect(() => {
    if (established && instance === null) void loadInstance();
  }, [established, instance, loadInstance]);

  const status = instance?.status as InstanceStatus | undefined;
  return (
    <>
      <aside className="global-context" aria-label="Current target and live context">
        <strong>{target.address ?? "No target selected"}</strong>
        <span>Connection: {target.status.targetConnection}</span>
        <span>Authentication: {target.status.targetAuthentication}</span>
        <span>Compatibility: {target.status.javaGoCompatibility}</span>
        <span>Runtime: {target.status.runtimeIdentity}</span>
        <span>Instance: {status?.instanceId ?? target.status.instanceId ?? "Not established"}</span>
        {target.unencrypted && <strong>Unencrypted target connection</strong>}
        <NavLink to="/active-executions">
          Active executions: {status?.activeExecutionCount ?? "Unavailable"}
        </NavLink>
      </aside>
      <nav className="global-nav" aria-label="Console">
        <NavLink to="/" end>Overview</NavLink>
        <NavLink to="/target">Target</NavLink>
        <NavLink to="/skills">Skills</NavLink>
        <NavLink to="/active-executions">Active Executions</NavLink>
        <NavLink to="/traces">Traces</NavLink>
        <NavLink to="/trace-storage">Trace Storage</NavLink>
      </nav>
      <Outlet />
    </>
  );
}

export function NotFound() {
  return (
    <section aria-labelledby="not-found-title" className="foundation-card">
      <p className="eyebrow">Not found</p>
      <h2 id="not-found-title">This Console route does not exist</h2>
      <p>Check the address and return to the Console Overview.</p>
    </section>
  );
}
