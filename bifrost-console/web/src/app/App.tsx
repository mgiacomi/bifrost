import { Outlet } from "react-router";
import type { BuildMetadata } from "./metadata";
import { ThemeSelect } from "./ThemeSelect";
import { PairingPage } from "../security/PairingPage";
import { useBrowserSession } from "../security/BrowserSessionProvider";

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
            <Outlet />
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

export function Foundation() {
  return (
    <section aria-labelledby="foundation-title" className="foundation-card">
      <p className="eyebrow">Build foundation</p>
      <h2 id="foundation-title">Console shell ready</h2>
      <p>
        The embedded browser and Go host share one verified Bifrost release.
      </p>
    </section>
  );
}

export function NotFound() {
  return (
    <section aria-labelledby="not-found-title" className="foundation-card">
      <p className="eyebrow">Not found</p>
      <h2 id="not-found-title">This Console route does not exist</h2>
      <p>Check the address and return to the Console foundation.</p>
    </section>
  );
}
