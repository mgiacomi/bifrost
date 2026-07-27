# Bifrost Console

Bifrost Console is an independent Go module containing an embedded
React/TypeScript application. A production build creates one executable; it is
not a Maven module and it does not reuse the deprecated `bifrost-cli`.

The executable owns one local profile, one disposable workspace, and a paired
browser security realm. Target connections and diagnostic views arrive in
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

## Runtime configuration

The executable accepts:

```text
bifrost-console --version
bifrost-console [--config FILE] [--work-dir DIRECTORY]
  [--listen 127.0.0.1:7943] [--development-origin http://127.0.0.1:5173]
  [--no-open-browser]
```

`--version` validates the release and embedded assets without creating files or
opening a listener. `--config` selects one exact YAML file and its resolved
parent identifies the profile. `--work-dir` selects one exact managed work
root. `--listen` overrides the YAML listener for this process only.
`--development-origin` adds exactly one canonical HTTP loopback Vite
authority/origin pair for this process; it is never persisted or enabled by a
production default. `--no-open-browser` suppresses only the browser-opening
attempt. A pairing URL is still printed.

The schema-version 1 configuration is strict and restart-only:

```yaml
version: 1
listener:
  address: 127.0.0.1:7943
trace-workspace:
  max-bytes: 4GiB
  idle-ttl: 4h
```

Unknown fields, duplicate keys, multiple documents, aliases, unsafe bounds, and
unsupported versions are rejected. Listener addresses must contain one
explicit IPv4 or IPv6 loopback literal and a port. `max-bytes` accepts positive
integer `KiB`, `MiB`, `GiB`, or `TiB` values and the exact sentinel
`unlimited`. `idle-ttl` accepts positive integer `s`, `m`, or `h` values and the
exact sentinel `never`. Numeric zero is invalid. Configuration never contains
pairing, session, CSRF, application, or MCP credentials.

Default configuration files are:

- Windows: `%AppData%\Bifrost\Console\config.yaml`
- macOS: `~/Library/Application Support/Bifrost Console/config.yaml`
- Linux: `$XDG_CONFIG_HOME/bifrost-console/config.yaml`, or
  `~/.config/bifrost-console/config.yaml`

Default workspace parents are:

- Windows: `%LocalAppData%\Bifrost\Console\workspaces`
- macOS: `~/Library/Caches/Bifrost Console/workspaces`
- Linux: `$XDG_STATE_HOME/bifrost-console/workspaces`, or
  `~/.local/state/bifrost-console/workspaces`

The workspace leaf is the full lowercase SHA-256 identity of the resolved
profile directory. A managed root contains the exact
`.bifrost-console-work` marker, `.lock`, and `transient/`. Existing unmarked or
wrongly marked directories are refused without mutation. Profile and work
locks exclude another Console process. `transient/` is disposable: prior
process contents are never adopted, indexed, or served and are removed before
listening. Cleanup does not follow symbolic links, junctions, or reparse
points, and it is not secure erasure. On shutdown, current transient content is
removed best-effort; the empty root, marker, and unlocked lock file may remain.
Loss of the verified workspace invariant terminates the service instead of
creating a degraded or fallback workspace.

## Browser pairing and sessions

After the listener is bound, Console prints a five-minute, one-use pairing URL
and normally asks the operating system to open it. Browser-opening failure is
nonfatal. The 256-bit pairing value is carried only in the URL fragment; the
SPA removes it from the current address/history entry before exchanging it in
a same-origin JSON body. This short-lived fragment is the sole URL exception:
reusable credentials must never appear in URLs, logs, YAML, or browser
storage.

Successful pairing creates a process-local `HttpOnly`, `SameSite=Strict`,
nonpersistent `bifrost_console_session` cookie. It intentionally omits
`Secure` because the listener is plaintext HTTP bound to an explicit loopback
IP; remote and wildcard binding remain prohibited. A process admits eight
browser sessions and sixteen tabs total. Sessions expire after eight idle
hours, and disconnected tab registrations expire after two minutes. Open tabs
send an authenticated in-memory heartbeat; a resumed or restored tab
re-registers automatically if its prior registration expired. Each tab holds
its independent CSRF token only in React memory. Refresh reuses a valid cookie
and receives fresh bootstrap/tab state. If pairing expires, the
same-origin unpaired page can request a rate-limited new value printed only to
the owning terminal, or another paired tab can create a new fragment link.

Every browser API request requires the exact bound Host and matching Origin.
Sensitive operations additionally require the browser cookie, tab identity,
and CSRF header. Browser/MCP route realms are selected before authentication,
so a future MCP bearer credential cannot substitute for browser controls.
Authenticated and security responses are `no-store`; only verified
content-addressed static assets are immutable. The browser stores only the
presentation theme in `sessionStorage`.

## Development hot reload

Use two terminals. First build and run the Go host with explicit development
origin permission:

```text
go run ./internal/buildtool build
./build/bifrost-console --listen 127.0.0.1:7943 --development-origin http://127.0.0.1:5173
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
service worker is included in production. The Go
`--development-origin` value must exactly match the Vite authority/origin;
near-matches remain rejected.
