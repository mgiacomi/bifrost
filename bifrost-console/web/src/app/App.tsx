import { Outlet } from "react-router";
import type { BuildMetadata } from "./metadata";
import { ThemeSelect } from "./ThemeSelect";
import { PairingPage } from "../security/PairingPage";
import { useBrowserSession } from "../security/BrowserSessionProvider";
import { TargetProvider } from "../target/TargetProvider";

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
              <Outlet />
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

export function NotFound() {
  return (
    <section aria-labelledby="not-found-title" className="foundation-card">
      <p className="eyebrow">Not found</p>
      <h2 id="not-found-title">This Console route does not exist</h2>
      <p>Check the address and return to the Console Overview.</p>
    </section>
  );
}
