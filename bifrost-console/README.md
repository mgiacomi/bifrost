# Bifrost Console

Bifrost Console is an independent Go module containing an embedded
React/TypeScript application. A production build creates one executable; it is
not a Maven module and it does not reuse the deprecated `bifrost-cli`.

PR 07 establishes only the reproducible build, verified embedded assets,
minimal loopback host, and browser shell. PR 08 adds profile/workspace
ownership and the complete local-browser pairing, session, CSRF, Host, Origin,
and security-header model. Target connections and product views arrive in
later Console changes.

## Exact build prerequisites

- Go 1.26.5
- Node.js 24.18.0
- npm 12.0.1

The repository declares these values in `go.mod`, `.node-version`, and
`web/package.json`. Direct frontend dependencies are exact versions and
`web/package-lock.json` fixes the transitive graph. Node and npm are build-time
dependencies only.

## Canonical production commands

From `bifrost-console/`:

```text
go run ./internal/buildtool verify
go run ./internal/buildtool build
```

Both commands check exact toolchain patches, read the direct version from the
root Maven `pom.xml`, install the locked frontend graph, type-check and test the
browser, clean and rebuild assets, generate and verify their integrity
manifest, and run all Go tests. `build` then writes the current-platform
executable beneath `build/` with the same complete version injected.

The product version cannot be overridden. A release caller may add
`--expected-version VERSION` to assert that its expected tag/version equals
the root POM value.

Generated browser assets, dependencies, coverage, Playwright output, and
binaries are ignored. Only
`internal/webassets/generated/embed-placeholder.txt` is tracked; it is not a
valid production asset. Never compile or distribute a manually generated or
previous asset directory.

The executable accepts:

```text
bifrost-console --version
bifrost-console --listen 127.0.0.1:7943
```

PR 07 requires an explicit loopback IP. Its default listener is a build
foundation, not the final Console security lifecycle.

## Development hot reload

Use two terminals. First build and run the minimal Go host:

```text
go run ./internal/buildtool build
./build/bifrost-console --listen 127.0.0.1:7943
```

On Windows, run `.\build\bifrost-console.exe` instead. Then start Vite:

```text
cd web
npm ci
npm run dev
```

Vite is the development browser origin at `127.0.0.1:5173`. It handles HMR and
proxies only `/api/console/` (including the future event subtree) to
`http://127.0.0.1:7943`. Set `BIFROST_CONSOLE_GO_ORIGIN` only to another
explicit HTTP loopback origin. The Java application namespace
`/_bifrost/observability/` is never proxied, and no development proxy or
service worker is included in production.
