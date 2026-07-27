# PR 08 - Profile, Workspace, and Local Browser Security Implementation Plan

## Overview

Implement the process-lifetime ownership and local-browser trust boundary that
every later Bifrost Console feature relies on. PR 08 will introduce a strict
versioned local profile, exclusive profile and work-directory ownership, safe
disposable workspace cleanup, exact loopback authority enforcement, one-time
browser pairing, bounded browser sessions and tabs, per-tab CSRF protection,
restrictive browser response policy, and coordinated graceful or fatal
shutdown.

The change remains entirely within the standalone Go/React Console. It does not
connect to an application target or interpret diagnostic artifacts. PR 09 will
put target selection and application credentials behind the browser boundary
created here; PR 12 will consume the verified workspace; and PR 16 will add a
separate MCP authentication realm to the listener and profile established here.

## Current State Analysis

The current PR 07 foundation validates the injected product version and embedded
assets and then immediately starts a minimal HTTP host
(`bifrost-console/cmd/bifrost-console/main.go:18-78`). Its runtime flags are
limited to `--version` and `--listen`; there is no configuration profile,
workspace, browser-opening, or security lifecycle.

`webhost.Host` accepts one explicit IPv4 or IPv6 loopback literal, opens one TCP
listener, calls `OnListen`, and shuts down its `http.Server` when its context is
cancelled (`bifrost-console/internal/webhost/host.go:14-72`). It does not derive
an accepted HTTP authority, validate `Host` or `Origin`, coordinate other
process-owned resources, or propagate a workspace-fatal condition.

`StaticHandler` already reserves `/api/console`, keeps navigation responses out
of caches, caches content-addressed assets immutably, and emits
`X-Content-Type-Options: nosniff`
(`bifrost-console/internal/webhost/static.go:17-96`). It has no API router,
session authentication, CSRF validation, CSP, anti-framing policy, or common
bounded error writer.

The browser is still a build-foundation shell with only the root, a foundation
deep link, and a not-found route
(`bifrost-console/web/src/app/routes.tsx:1-26`). Its sole browser storage is a
presentation-only theme preference in `sessionStorage`
(`bifrost-console/web/src/app/ThemeSelect.tsx:14-49`).

There is no runtime YAML dependency or runtime filesystem package in
`bifrost-console/go.mod`. The closest deletion precedent is the build tool's
strict cleanup of the exact generated-assets child after path and symlink
validation (`bifrost-console/internal/buildtool/cleanup.go:11-42`), but that
helper is not suitable for untrusted runtime workspace contents.

The supplied research describes commit
`601201a78c02adce92d6c9186421807cf20ec0c3`. The current clean checkout is
`4cdb5779654bba1ad3922b5a5e8e6398dedfe4da`; the scoped PR 07 code and its
relevant seams remain consistent with the research.

## Desired End State

Starting Bifrost Console establishes resources in this fixed order:

```text
parse flags and validate release/assets
  -> return immediately for --version
  -> resolve config path and profile directory
  -> create/protect profile directory when needed
  -> acquire exclusive profile lock
  -> strictly create or load schema-version 1 YAML
  -> resolve and verify the managed work root
  -> acquire exclusive work-directory lock
  -> safely delete and recreate only transient/
  -> open one configured loopback listener
  -> derive its exact accepted authority
  -> create the initial one-time pairing challenge
  -> print and optionally open the fragment pairing URL
  -> serve the browser and /api/console/v1/ realm
```

Before the listener opens, any profile, configuration, lock, ownership,
permission, work-root identity, path-safety, or cleanup failure returns a clear
startup error. An existing unmarked work directory is never adopted or cleaned.
Prior-process transient entries are deleted without being inspected, indexed,
or served.

While the process is running, the verified root, marker, lock, transient
directory, and basic managed I/O remain a process invariant. The invariant is
checked before workspace operations and by a 30-second monitor. Losing it
cancels the host and produces an error instead of a reduced-function service.

The listener remains one explicitly selected loopback family. Its actual bound
address determines the only production HTTP authority and origin. Browser API
requests fail closed before business handlers when authority, origin, pairing,
session, or CSRF checks do not satisfy that route's policy.

The first browser receives a 256-bit, five-minute, one-use pairing secret in a
URL fragment. The SPA removes the fragment from the current history entry before
submitting it in a bounded same-origin JSON body. Successful exchange creates
an in-memory browser session and a nonpersistent `HttpOnly`,
`SameSite=Strict` cookie. Bootstrap registers the tab and returns a fresh
256-bit per-tab CSRF token held only in browser memory.

The process admits no more than eight browser sessions, sixteen registered tabs
across them, and one later live relay per tab. Sessions expire after eight hours
of inactivity; an authenticated live relay counts as activity. Disconnected tab
registrations expire after two minutes. Pairing secrets, session state, CSRF
tokens, and tabs never outlive the process.

Graceful shutdown stops admission, closes the HTTP listener, cancels component
users, removes current transient content best-effort where still safe, and then
releases the work and profile locks. The managed root, marker, and unlocked lock
file may remain.

### Key Discoveries

- The current startup dependency seam already proves release and asset
  validation occur before serving, but it must be widened to cover profile and
  workspace ownership and deterministic cleanup
  (`bifrost-console/cmd/bifrost-console/main_test.go:10-56`).
- The host already supports one chosen IPv4 or IPv6 loopback literal, so PR 08
  will preserve that coherent model rather than introduce coordinated dual
  listeners (`bifrost-console/internal/webhost/host_test.go:33-58`).
- The Vite development server binds `127.0.0.1:5173`, preserves the incoming
  host while proxying, and permits only an explicit HTTP loopback Go origin
  (`bifrost-console/web/vite.config.ts:43-73`). Development authority and origin
  allowances therefore need to be paired explicitly.
- `/api/console` is already excluded from SPA fallback, which lets PR 08 mount a
  versioned browser API without changing navigation fallback semantics
  (`bifrost-console/internal/webhost/static.go:71-96`).
- Later browser target replacement is the first target-facing CSRF consumer
  (`ai/thoughts/tickets/bifrost-console-pr-09-target-context.md`), while paired
  generation of another one-time pairing link provides a real sensitive PR 08
  operation with which to prove the middleware now.
- PR 12 requires this PR's one verified `transient` root and fatal workspace
  distinction rather than another storage owner
  (`ai/thoughts/tickets/bifrost-console-pr-12-artifact-service.md`).
- PR 16 needs route-realm selection to precede authentication so its future MCP
  bearer key can never substitute for the browser cookie or CSRF token
  (`ai/thoughts/tickets/bifrost-console-pr-16-mcp-foundation.md`).

## Settled Implementation Decisions

The research document's open implementation questions are resolved as follows:

1. The YAML root field is `version: 1`. PR 08 recognizes only `listener` and
   `trace-workspace` sections. `listener.address` defaults to
   `127.0.0.1:7943`; `trace-workspace.max-bytes` defaults to `4GiB` and accepts
   `unlimited`; `trace-workspace.idle-ttl` defaults to `4h` and accepts `never`.
   The trace settings are established now as configuration contracts even
   though PR 12 first consumes them for artifact retention.
2. The default config paths are:
   - Windows:
     `%AppData%\Bifrost\Console\config.yaml`
   - macOS:
     `~/Library/Application Support/Bifrost Console/config.yaml`
   - Linux:
     `$XDG_CONFIG_HOME/bifrost-console/config.yaml`, falling back to
     `~/.config/bifrost-console/config.yaml`
3. Default workspace parents are:
   - Windows:
     `%LocalAppData%\Bifrost\Console\workspaces`
   - macOS:
     `~/Library/Caches/Bifrost Console/workspaces`
   - Linux:
     `$XDG_STATE_HOME/bifrost-console/workspaces`, falling back to
     `~/.local/state/bifrost-console/workspaces`
4. The profile identity is the resolved absolute config parent. Its default
   workspace leaf is the lowercase full SHA-256 hex digest of the
   platform-normalized profile path. The hash prevents path characters or
   length from escaping the local-state root and makes distinct canonical
   profiles practically collision-free. Windows normalization uses the final
   resolved path and case-insensitive comparison rules.
5. A missing default or explicitly named config file is atomically initialized
   with defaults only after acquiring the profile lock. An existing empty,
   multi-document, duplicate-key, unknown-field, unsafe, or unsupported-version
   file is rejected rather than repaired.
6. `--config` selects an exact config file, `--work-dir` selects an exact work
   root, `--listen` overrides the YAML listener for this process,
   `--development-origin` enables exactly one additional loopback Vite
   authority/origin pair, and `--no-open-browser` suppresses only automatic
   browser opening. Development origin is a process-only option and never a
   persisted production default.
7. The profile lock file is `.bifrost-console.lock` beside the YAML. The managed
   work marker is `.bifrost-console-work` containing the exact marker format
   `bifrost-console-work-v1\n`; its lock file remains `.lock`.
8. Unix uses nonblocking OS advisory file locks plus current-UID and exact
   owner-access mode checks. Windows uses `LockFileEx`, final-path/file-identity
   checks, reparse attributes, current-user ownership, and a protected DACL
   granting necessary access only to the current user and operating-system
   principals required to administer the file. Permission or ownership
   uncertainty fails closed.
9. Runtime cleanup enumerates entries without following links or Windows
   reparse points, rejects any such boundary, deletes leaf entries, and removes
   directories bottom-up. It does not call a generic recursive removal function
   on an untrusted nested subtree.
10. The process opens one listener. IPv4 remains the default; `[::1]` is an
    explicit alternative. Coordinated dual-family listeners and hostname
    listeners are out of scope.
11. Production accepts exactly `http://<actual-bound-authority>`. Explicit
    development mode may additionally accept the configured Vite origin and its
    matching authority. Values are parsed as origins but must serialize exactly:
    HTTP scheme, explicit loopback literal and port, no credentials, path,
    query, fragment, trailing dot, whitespace, or comma-separated authority.
12. Browser API endpoints use `/api/console/v1/`. The initial routes are
    `POST` operations for pairing exchange, manual pairing challenge, paired
    pairing-link creation, paired bootstrap/tab registration, tab heartbeat,
    and tab release.
    Using explicit same-origin `POST` requests makes the browser-supplied
    `Origin` available to the mandatory request policy. JSON request bodies are
    limited to 1 KiB unless a smaller endpoint-specific limit applies.
13. Pairing, session identifiers, and CSRF values each use 32 bytes from
    `crypto/rand`, encoded as unpadded base64url. Pairing lasts five minutes and
    is invalidated atomically on success, replacement, expiry, or shutdown.
14. The cookie is `bifrost_console_session`, `HttpOnly`,
    `SameSite=Strict`, `Path=/`, and a nonpersistent session cookie. It omits
    `Secure` because the deliberately plaintext IP-literal loopback listener
    cannot depend on secure-cookie acceptance. Remote HTTP exposure remains
    impossible.
15. Each registered tab has its own CSRF token so bootstrap or refresh in one
    tab does not invalidate another tab. Bootstrap rotates only that tab's
    token. `X-Bifrost-Console-CSRF` carries the token, and decoded fixed-length
    values are compared in constant time.
16. A manual unpaired pairing-challenge request is limited to one current
    challenge and one request per 30 seconds. It prints the secret to the owning
    terminal and returns no secret or diagnostic state.
17. Paired creation of another short-lived pairing link is the representative
    PR 08 sensitive operation. It requires Host, Origin, session, and CSRF and
    returns `Cache-Control: no-store`.
18. Production CSP is based on:
    `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'
    data:; font-src 'self'; connect-src 'self'; base-uri 'none'; form-action
    'self'; frame-ancestors 'none'; object-src 'none'; manifest-src 'self'`.
    The host also emits `X-Frame-Options: DENY`,
    `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`,
    restrictive `Permissions-Policy`, `Cross-Origin-Opener-Policy:
    same-origin`, and `Cross-Origin-Resource-Policy: same-origin`. HSTS is not
    emitted on plaintext loopback HTTP.
19. Workspace health is checked before each managed operation and every 30
    seconds. A changed root/marker/transient identity, reparse or link boundary,
    lost/closed lock, or failed create-write-sync-remove probe is fatal.
    PR 12's artifact-local error is recoverable only when its partial state is
    removed and this complete health probe succeeds afterward.
20. Local browser adapter errors use bounded JSON, stable browser-local codes,
    generic authentication messages, and `Cache-Control: no-store`. They do not
    expose secrets, raw internal errors, stack traces, filesystem paths, or
    diagnostic payloads.

The Phase 2 fragment pairing URL is the deliberately settled, short-lived
bootstrap exception to the ticket's broad statement that secrets never enter
URLs. It never appears in an HTTP request target or server log and is removed
from browser history before exchange. Reusable session, application, and MCP
credentials remain prohibited from URLs.

## What We're NOT Doing

- Connecting to, authenticating with, or probing a Bifrost application target.
- Adding target URLs, target trust, application credentials, `TargetContext`,
  shared target status, or Java/Go compatibility handling; PR 09 owns these.
- Adding live activity, SSE relay, recent-activity retention, or active
  execution behavior.
- Acquiring, adopting, parsing, indexing, retaining, or serving trace artifacts;
  PRs 12-14 own those behaviors.
- Adding MCP routes, key files, SDK types, or bearer authentication; PR 16 owns
  the sibling realm and persistent MCP credential.
- Adding remote listener support, TLS, `localhost` aliases, wildcard binding,
  coordinated IPv4/IPv6 listeners, accounts, OIDC, or a database.
- Treating startup cleanup as secure erasure or attempting to recover
  prior-process transient content.
- Storing pairing secrets, cookies, CSRF values, diagnostic data, application
  credentials, or future MCP credentials in YAML or browser storage.
- Adding a compatibility shim, legacy config reader, alternate marker reader,
  fallback workspace, or degraded workspace serving mode.
- Completing PR 15's comprehensive untrusted-diagnostic-content and packaged
  workflow hardening. PR 08 will test the security boundary it owns.

## Skill-Authoring Documentation Impact

**Impact**: No impact

- **Rationale**: PR 08 changes only the standalone Console's local process,
  filesystem, HTTP, and browser-session boundaries. It changes no skill
  manifest syntax or validation, mappings, execution/planning semantics,
  evidence behavior, capability visibility, RBAC, attachments, model
  selection, limits, traces, or skill-testing guidance.
- **Documents to update**: None under `ai/skill-authoring/`.
- **Supporting evidence**: The implementation and focused tests are confined to
  `bifrost-console/`; no Java framework or skill fixture is a consumer. The
  scope boundary is recorded by
  `ai/thoughts/tickets/bifrost-console-pr-08-local-security-workspace.md` and
  the Console ownership sections of
  `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md`.
- **Coverage table update**: Not required. No topic boundary, coverage level, or
  confidence statement in `ai/skill-authoring/README.md` changes.
- **LLM-first usability**: Not applicable.

## Contract and Compatibility Impact

| Surface | Classification and supporting evidence | Planned compatibility treatment |
| --- | --- | --- |
| Application API | No impact. No Java application-facing class, method, property, or endpoint changes. | Preserve all existing application APIs. |
| Supported SPI | No impact. The Console has no supported replacement or extension SPI and this PR adds none. | No SPI or compatibility mechanism. |
| Configuration and manifest contracts | Affected. PR 08 introduces the versioned Console YAML, default config resolution, CLI overrides, profile identity, and strict validation. These are developer-facing Console configuration contracts, not Bifrost skill manifests. | Introduce one schema-version 1 contract atomically with fixtures, validation tests, runtime README guidance, and no legacy reader. Later tickets extend the same version intentionally. |
| Persisted or serialized contracts | Affected. The non-secret YAML is durable profile configuration. `.bifrost-console.lock`, `.bifrost-console-work`, and `.lock` are persistent ownership metadata. The browser cookie is serialized but process-local and intentionally has no cross-process lifetime. | Establish one exact YAML and marker format. Fail closed on incompatible content. Do not version or migrate process-local browser state. Never adopt `transient/`. |
| Ephemeral diagnostic formats | No impact. PR 08 consumes no REST/SSE/NDJSON application diagnostics and creates no trace representation. Browser-local security errors are current-process adapter responses. | Keep browser security errors bounded, sanitized, and coherent with the embedded browser; no cross-release compatibility promise. |
| Internal or accidentally exposed implementation | Affected. `/api/console/` changes from a reserved path to a same-release browser adapter. New Go packages for configuration, profile, workspace, lifecycle, and browser security are internal. | Add and update Go/React callers atomically. The embedded browser and Go API ship together; no overload, fallback, alias, or browser API version negotiation. |

- **Evidence of supported contracts**: The approved PR 08 ticket and Phase 2
  local-configuration/workspace design establish the new YAML, profile, marker,
  and workspace contracts. Existing PR 07 Go visibility alone is not treated as
  a compatibility promise.
- **Intended breaks**: The current unauthenticated static host is intentionally
  replaced by the complete paired local-browser boundary. Existing
  `/api/console` 404 behavior and the build-foundation startup message are
  internal and may change atomically.
- **In-repository consumers to update**: Console startup and tests, webhost
  composition and tests, the embedded React application and tests, Vite proxy
  tests, Go module dependencies, and `bifrost-console/README.md`.
- **Public-surface delta**: No Java public types, constructors, signatures, or
  Spring extension points. New Go code remains under `internal/`; new CLI flags
  (`--config`, `--work-dir`, `--listen`, `--development-origin`, and
  `--no-open-browser`) and the schema-version 1 YAML are the only
  developer-facing Console surface.
- **Shim decision**: **No shim.** There is no protected predecessor config,
  workspace marker, session, or browser API contract. PR 07 explicitly labels
  its host as a foundation rather than the final security model, so the
  repository can move atomically to one design.
- **Java-to-Go boundary coordination**: **Not required.** No application
  REST/SSE, acquisition, problem, or consumed NDJSON boundary changes.

## Implementation Approach

Keep ownership layered and prevent HTTP handlers from becoming lifecycle or
filesystem owners:

```text
cmd/bifrost-console
  -> config/profile owner
       -> platform paths and protected config
       -> profile OS lock
  -> workspace owner
       -> marker and work OS lock
       -> non-following transient cleanup
       -> process invariant monitor
  -> process lifecycle coordinator
       -> fatal cancellation
       -> ordered shutdown
  -> webhost
       -> one loopback listener
       -> exact authority capture
       -> realm router
            -> browser security middleware
                 -> pairing/session/CSRF API
                 -> protected static SPA
            -> future MCP realm slot
```

Use small transport-neutral owners with injected clock, entropy, listener,
browser opener, filesystem probe, and terminal-output seams. Production uses
`crypto/rand`, real time, platform syscalls, and the actual listener; focused
tests use deterministic substitutes without weakening the production path.

Use build-tagged platform files for locks, file identity, permissions, default
paths, and browser opening. Common code owns sequencing, exact marker semantics,
canonical errors, non-following traversal, registries, and lifecycle policy.
Use `go.yaml.in/yaml/v4` for strict YAML and `golang.org/x/sys` for platform
primitives, with exact versions recorded by `go.mod` and `go.sum`.

The HTTP assembly first selects the browser or reserved future-MCP realm. The
browser realm then applies security headers and route-specific Host, Origin,
session, and CSRF policy. Static entry/assets need exact Host protection but do
not require a session so an unpaired browser can load the pairing UI. Browser
API operations require exact Host and Origin; individual route declarations
then select unpaired, paired, or paired-and-CSRF policy. Authentication and
authorization checks run before request-body decoding.

## Phase 1: Strict Configuration and Exclusive Profile Ownership

### Overview

Create the versioned profile contract, platform default paths, protected config
creation/loading, CLI override rules, and exclusive profile lock. This phase
must complete before any work directory is selected or listener is opened.

### Changes Required

#### 1. Configuration schema and validation

**Files**:

- `bifrost-console/internal/config/config.go`
- `bifrost-console/internal/config/decode.go`
- `bifrost-console/internal/config/values.go`
- `bifrost-console/internal/config/config_test.go`
- `bifrost-console/internal/config/testdata/*.yaml`
- `bifrost-console/go.mod`
- `bifrost-console/go.sum`

**Changes**:

- Add the exact schema-version 1 DTO and resolved runtime values.
- Decode exactly one YAML document with known-field, unique-key, alias/depth,
  and input-size bounds.
- Parse duration and byte-size strings using explicit units. Preserve the
  semantic sentinels `unlimited` and `never`; reject numeric zero, negatives,
  bare numbers, ambiguous units, overflow, and noncanonical alternatives.
- Validate the listener as one explicit loopback IP literal plus port.
- Keep serialized configuration free of secret-bearing fields.
- Return field-specific, bounded errors that identify the config file and
  invalid field without echoing arbitrary file contents.

The initial generated/default shape is:

```yaml
version: 1
listener:
  address: 127.0.0.1:7943
trace-workspace:
  max-bytes: 4GiB
  idle-ttl: 4h
```

#### 2. Platform paths and profile identity

**Files**:

- `bifrost-console/internal/profile/paths.go`
- `bifrost-console/internal/profile/paths_unix.go`
- `bifrost-console/internal/profile/paths_windows.go`
- `bifrost-console/internal/profile/paths_test.go`
- `bifrost-console/internal/profile/paths_windows_test.go`

**Changes**:

- Resolve the default config and local-state roots exactly as listed in the
  settled decisions.
- Make `--config` an exact file selection rather than a directory prefix.
- Create and resolve the config parent before deriving profile identity.
- Canonicalize the profile path with platform case/path rules and derive the
  full SHA-256 workspace leaf.
- Ensure resolution is independent of the current working directory and that
  distinct tested profiles receive different default workspace paths.
- Reject a config parent that resolves through an unsafe link/reparse boundary
  or cannot receive the required protection.

#### 3. Protected profile creation and OS lock

**Files**:

- `bifrost-console/internal/profile/profile.go`
- `bifrost-console/internal/profile/lock_unix.go`
- `bifrost-console/internal/profile/lock_windows.go`
- `bifrost-console/internal/profile/permissions_unix.go`
- `bifrost-console/internal/profile/permissions_windows.go`
- `bifrost-console/internal/profile/profile_test.go`
- `bifrost-console/internal/profile/lock_platform_test.go`

**Changes**:

- Create the profile directory and lock/config files with the platform's
  enforced owner protection.
- Acquire `.bifrost-console.lock` nonblockingly and retain its open handle until
  shutdown.
- Report lock contention as an actionable startup conflict without reading or
  mutating the owned profile further.
- Verify owner and protection on every later use; do not silently chmod or
  rewrite an existing weakly protected profile.
- After the lock is held, atomically create the default YAML when absent or
  strictly load the existing file.
- Make lock release explicit, idempotent, and testable.

#### 4. CLI resolution

**Files**:

- `bifrost-console/cmd/bifrost-console/main.go`
- `bifrost-console/cmd/bifrost-console/main_test.go`

**Changes**:

- Add `--config`, `--work-dir`, `--listen`, `--development-origin`, and
  `--no-open-browser` parsing.
- Preserve `--version` as a validation-only path that does not create a
  profile, config, lock, workspace, or listener.
- Apply `--listen` after YAML validation as a process-only override.
- Treat `--development-origin` as the sole opt-in for the extra exact loopback
  Vite authority/origin pair; reject non-origin and non-loopback values before
  profile or listener creation.
- Extend injected runtime dependencies so tests prove profile ownership and
  configuration validation precede workspace and serving.

### Success Criteria

#### Automated Verification

- [x] Strict valid/default config tests pass:
  `go test ./internal/config ./internal/profile`.
- [x] Unknown fields, duplicate keys, multiple documents, bad version, unsafe
  values, and YAML bounds are rejected before workspace or listener calls.
- [ ] Default path and profile-ID tests pass in the recorded Windows, Linux,
  and macOS supported-target verification runs.
- [x] Two processes cannot acquire the same profile lock; releasing the first
  permits the second.
- [ ] Weak ownership/permission fixtures fail closed on supported platforms.
- [x] `--version` performs no filesystem or listener mutation.
- [x] Go formatting and vet pass:
  `gofmt -l .` returns no files and `go vet ./...` succeeds.

#### Manual Verification

- [ ] First launch creates a readable default config at the documented platform
  path and prints actionable errors when that path cannot be protected.
- [ ] Launching a second process with the same profile reports the ownership
  conflict without disturbing the first.
- [ ] `--config` selects a stable profile regardless of the shell's current
  working directory.

---

## Phase 2: Verified Managed Workspace and Fatal Lifecycle

### Overview

Establish the exact work-root marker/lock/transient contract, safe startup
cleanup, platform path identity, health probes, and a lifecycle coordinator that
can turn a later workspace-wide failure into ordered process shutdown.

### Changes Required

#### 1. Work-root establishment and metadata

**Files**:

- `bifrost-console/internal/workspace/workspace.go`
- `bifrost-console/internal/workspace/identity.go`
- `bifrost-console/internal/workspace/lock_unix.go`
- `bifrost-console/internal/workspace/lock_windows.go`
- `bifrost-console/internal/workspace/permissions_unix.go`
- `bifrost-console/internal/workspace/permissions_windows.go`
- `bifrost-console/internal/workspace/workspace_test.go`
- `bifrost-console/internal/workspace/workspace_platform_test.go`

**Changes**:

- Resolve the default profile-scoped work root or exact `--work-dir` override
  once at startup.
- On first use, create a protected root, exact marker, work lock, and transient
  child. On later use, require the exact marker before acquiring the work lock
  or deleting anything.
- Reject roots or required children that are symbolic links, mount-like link
  boundaries, junctions, or other Windows reparse points.
- Capture stable root, marker, lock, and transient identities for later health
  validation.
- Acquire the work lock only after the profile lock and retain it until ordered
  shutdown.
- Treat an existing unmarked directory as unrelated user data and leave every
  entry unchanged.

#### 2. Non-following transient cleanup

**Files**:

- `bifrost-console/internal/workspace/cleanup.go`
- `bifrost-console/internal/workspace/cleanup_unix.go`
- `bifrost-console/internal/workspace/cleanup_windows.go`
- `bifrost-console/internal/workspace/cleanup_test.go`
- `bifrost-console/internal/workspace/cleanup_platform_test.go`

**Changes**:

- Delete and recreate only the exact verified `transient` child.
- Enumerate each directory, inspect every child without following it, reject
  link/reparse entries, remove files, and remove directories bottom-up.
- Revalidate containment and directory identity at meaningful boundaries.
- Never scan contents for application meaning, preserve prior-process entries,
  or expose their names through browser responses.
- On unsafe cleanup failure, retain locks until shutdown and return a fatal
  startup error.

#### 3. Health probes and fatality classification

**Files**:

- `bifrost-console/internal/workspace/health.go`
- `bifrost-console/internal/workspace/health_test.go`
- `bifrost-console/internal/lifecycle/coordinator.go`
- `bifrost-console/internal/lifecycle/coordinator_test.go`

**Changes**:

- Add `Workspace.Check` for link/reparse, file identity, marker, lock-handle,
  permission, and create-write-sync-remove probe validation.
- Run the probe before every public workspace operation and on a 30-second
  injected ticker.
- Send the first fatal invariant loss through a process-fatal cancellation
  cause. Make repeated notifications idempotent and preserve the first cause.
- Define the future PR 12 classification seam: an artifact-local failure can be
  returned as a request error only after partial cleanup and a successful full
  workspace check.
- Add a component coordinator that stops admission, cancels component contexts,
  waits for bounded closure, conditionally performs safe best-effort transient
  cleanup, and releases work then profile locks.

#### 4. Startup ordering

**Files**:

- `bifrost-console/cmd/bifrost-console/main.go`
- `bifrost-console/cmd/bifrost-console/main_test.go`

**Changes**:

- Preserve release and embedded-asset validation before profile mutation, then
  assemble profile, workspace, and lifecycle owners before HTTP serving.
- Print the resolved absolute work root only after it is verified and locked.
- Prove no target/browser/listener callback occurs before safe cleanup.
- Return the fatal workspace cause from `run` after coordinated shutdown.

### Success Criteria

#### Automated Verification

- [ ] Workspace tests cover first creation, marked reuse, unmarked rejection,
  profile/work lock conflicts, path escape, root symlink, nested symlink, Windows
  junction/reparse boundaries, permission mismatch, and restart cleanup:
  `go test ./internal/workspace ./internal/lifecycle`.
- [x] Tests prove cleanup changes only the verified `transient` child and never
  adopts or serves prior contents.
- [ ] A failed pre-listen workspace check prevents the listener callback.
- [ ] A simulated post-start identity, marker, lock, or probe failure cancels
  the host and returns the original fatal cause.
- [x] Simulated recoverable artifact-local cleanup plus a passing health probe
  remains request-scoped; a failing follow-up probe becomes fatal.
- [ ] Shutdown order and idempotent release are race-tested:
  `go test -race ./internal/workspace ./internal/lifecycle`.

#### Manual Verification

- [ ] On every supported OS, the visible work root contains only the marker,
  unlocked/locked metadata as appropriate, and `transient/`.
- [ ] Files left under `transient/` by a killed process disappear before the
  next process begins listening.
- [ ] Pointing `--work-dir` at an existing unmarked directory reports refusal
  and leaves sentinel files unchanged.
- [ ] Terminating the Console releases both locks and removes current transient
  content best-effort.

---

## Phase 3: Listener Authority, Route Realms, and Browser Response Policy

### Overview

Upgrade the minimal host into an assembled local service that captures its
actual authority, selects route realms before authentication, validates browser
Host/Origin independently, bounds requests and errors, and applies the complete
production browser header policy.

### Changes Required

#### 1. Bound listener and accepted authority

**Files**:

- `bifrost-console/internal/webhost/host.go`
- `bifrost-console/internal/webhost/authority.go`
- `bifrost-console/internal/webhost/host_test.go`
- `bifrost-console/internal/webhost/authority_test.go`

**Changes**:

- Preserve a single configured explicit loopback listener and reject hostnames,
  wildcards, LAN, public, malformed, or zone-qualified inputs.
- Have the host expose the successfully bound listener/authority to service
  assembly before serving requests, including the actual port selected from
  `:0`.
- Derive the canonical IPv4 or bracketed IPv6 authority and production origin
  once; do not trust the first incoming request to establish it.
- Keep listener closure and HTTP shutdown bounded and safe when lifecycle
  cancellation has a fatal cause.

#### 2. Realm routing and browser request policy

**Files**:

- `bifrost-console/internal/browserapi/router.go`
- `bifrost-console/internal/browserapi/request_policy.go`
- `bifrost-console/internal/browserapi/request_policy_test.go`
- `bifrost-console/internal/webhost/routes.go`
- `bifrost-console/internal/webhost/routes_test.go`

**Changes**:

- Route `/api/console/v1/` to the browser realm and reserve the later MCP prefix
  as a separate realm before credential policy is evaluated.
- Apply exact Host validation to browser static and API requests before session
  lookup or body reads.
- Require a present, exact same-origin `Origin` on browser API operations. In
  explicit development mode, accept only the configured Vite origin and
  authority pair in addition to the actual Go origin/authority pair.
- Reject malformed, duplicated, comma-joined, or ambiguous authority/origin
  values and avoid redirects on validation failures.
- Declare each API route as unpaired, paired, or paired-plus-CSRF so future
  target handlers cannot accidentally omit required middleware.

#### 3. Security headers, cache policy, and bounded errors

**Files**:

- `bifrost-console/internal/browserapi/headers.go`
- `bifrost-console/internal/browserapi/errors.go`
- `bifrost-console/internal/browserapi/headers_test.go`
- `bifrost-console/internal/browserapi/errors_test.go`
- `bifrost-console/internal/webhost/static.go`
- `bifrost-console/internal/webhost/static_test.go`

**Changes**:

- Apply the settled CSP and security headers to entry, asset, API, authentication
  failure, method, not-found, and internal-error responses.
- Retain immutable caching only for verified content-addressed static assets.
  Keep the entry document, pairing responses, authenticated responses,
  credential/security operations, API errors, and later diagnostic responses
  at `Cache-Control: no-store`.
- Add one bounded JSON browser-error envelope with stable local codes. Do not
  include internal causes, stack traces, filesystem paths, request bodies, or
  secret values.
- Add request-body limiting helpers that reject oversized content before JSON
  allocation and disallow trailing JSON documents.
- Keep `HEAD` behavior and explicit content types coherent with the stricter
  policy.

#### 4. Development proxy contract

**Files**:

- `bifrost-console/web/vite.config.ts`
- `bifrost-console/web/vite.config.test.ts`
- `bifrost-console/README.md`

**Changes**:

- Keep the fixed Vite loopback origin and narrow `/api/console/` proxy.
- Document and test how explicit development mode supplies the paired Go
  origin/Vite origin allowance without entering production assets.
- Ensure development mode cannot accept a wildcard, hostname alias, remote
  origin, credentials, path, query, or fragment.

### Success Criteria

#### Automated Verification

- [x] IPv4, IPv6, ephemeral-port, rejected-address, and exact-authority tests
  pass: `go test ./internal/webhost ./internal/browserapi`.
- [x] Foreign/missing/malformed Host and Origin requests fail before session
  lookup and body decoding.
- [x] Production tests reject the development authority/origin; development
  tests accept only the configured paired values.
- [ ] Every entry, asset, API, error, and not-found response has its expected
  CSP/security/cache headers.
- [x] Oversized and multi-document JSON inputs fail with bounded responses.
- [x] Frontend proxy validation tests pass:
  `npm test -- --run vite.config.test.ts`.

#### Manual Verification

- [ ] Production loads correctly through its printed IPv4 authority and through
  an explicitly configured IPv6 authority.
- [ ] Replacing the browser URL host with `localhost`, another loopback literal,
  or a foreign authority fails without redirecting.
- [ ] Vite hot reload reaches only the explicitly configured Go browser API and
  production does not retain the development allowance.
- [ ] Browser developer tools show the expected CSP and security headers without
  blocking the embedded production shell.

---

## Phase 4: Pairing, Sessions, Tabs, and CSRF

### Overview

Add the process-local browser security state and versioned API handlers. Keep
pairing, session authentication, tab ownership, and CSRF as independent checks
with concrete entropy, lifetime, and capacity bounds.

### Changes Required

#### 1. Pairing challenge owner

**Files**:

- `bifrost-console/internal/browserauth/pairing.go`
- `bifrost-console/internal/browserauth/pairing_test.go`
- `bifrost-console/internal/browserauth/entropy.go`

**Changes**:

- Generate unpadded base64url secrets from exactly 32 random bytes and keep only
  the current challenge's verification state, creation time, expiry, and source.
- Atomically consume a matching unexpired secret once. Invalidate it on
  replacement, expiry, successful exchange, or shutdown.
- Enforce one current manual challenge and the 30-second terminal-output rate
  limit.
- Separate secret generation from printing/opening so tests never need real
  credentials and logs cannot receive the value accidentally.
- Use constant-time comparison for decoded fixed-size pairing values.

#### 2. Session and tab registry

**Files**:

- `bifrost-console/internal/browserauth/sessions.go`
- `bifrost-console/internal/browserauth/cookie.go`
- `bifrost-console/internal/browserauth/sessions_test.go`
- `bifrost-console/internal/browserauth/cookie_test.go`

**Changes**:

- Add a mutex-protected registry with maximums of eight sessions and sixteen
  total tabs, eight-hour session idle expiry, two-minute disconnected-tab
  expiry, and one reserved relay slot per registered tab.
- Store opaque 256-bit session IDs in the nonpersistent
  `bifrost_console_session` cookie with the settled attributes.
- Refresh idle time only after successful authenticated requests or while an
  admitted relay is live.
- Expire only the affected session and its tabs/relays. Never rotate future
  target scope, clear shared evidence, or affect another browser session.
- Make shutdown invalidate every session, token, tab, and relay admission.

#### 3. Per-tab CSRF lifecycle

**Files**:

- `bifrost-console/internal/browserauth/csrf.go`
- `bifrost-console/internal/browserauth/csrf_test.go`

**Changes**:

- Register or resume a tab during bootstrap and issue a fresh 32-byte CSRF token
  bound to that session and tab.
- Rotate only that tab's token on bootstrap; keep other tab tokens valid.
- Validate the exact `X-Bifrost-Console-CSRF` header after Host, Origin, and
  session checks and before body processing.
- Reject missing, duplicated, malformed, wrong-tab, expired-session, and stale
  rotated values with a generic browser-security response.

#### 4. Browser security API handlers

**Files**:

- `bifrost-console/internal/browserapi/pairing.go`
- `bifrost-console/internal/browserapi/bootstrap.go`
- `bifrost-console/internal/browserapi/tabs.go`
- `bifrost-console/internal/browserapi/pairing_test.go`
- `bifrost-console/internal/browserapi/bootstrap_test.go`
- `bifrost-console/internal/browserapi/security_integration_test.go`

**Changes**:

- Implement bounded versioned endpoints for pairing exchange, manual challenge,
  paired pairing-link generation, bootstrap/tab registration, and best-effort
  tab release.
- On successful exchange, consume the pairing challenge before issuing the
  cookie. A replay cannot create a second session.
- Return the process identity, resolved work-root display path, registered tab
  identity, current session facts, and CSRF token from paired bootstrap. Keep the
  PR 09 target/status block absent rather than inventing target semantics.
- Protect new-pairing-link creation with the complete
  Host/Origin/session/CSRF chain and `no-store`.
- Ensure security failures do not reveal whether a candidate session, tab, or
  token was close to valid.

### Success Criteria

#### Automated Verification

- [x] Entropy-source, encoding, expiry, replacement, one-use, replay, and
  shutdown pairing tests pass:
  `go test ./internal/browserauth ./internal/browserapi`.
- [x] Exactly the eighth session and sixteenth total tab are admitted; later
  attempts return bounded `LIMIT_EXCEEDED` without eviction.
- [ ] Eight-hour idle expiry, authenticated refresh, active-relay activity, and
  two-minute disconnected-tab expiry are tested with a fake clock.
- [x] Cookie tests assert exact name and attributes and prove no persistent
  lifetime or `Secure` dependency is introduced on plaintext loopback.
- [x] Bootstrap rotation invalidates only the requesting tab's previous CSRF
  token.
- [x] The representative pairing-link operation rejects every missing or wrong
  Host, Origin, session, and CSRF combination independently.
- [ ] Pairing/session tests pass under the race detector:
  `go test -race ./internal/browserauth ./internal/browserapi`.

#### Manual Verification

- [ ] A startup link pairs exactly once and replaying the fragment fails without
  disturbing the established session.
- [ ] Tabs in one browser profile reuse the cookie but receive independent CSRF
  state.
- [ ] An expired session returns to the unpaired experience while another
  browser session remains unaffected.
- [ ] Manual pairing prints a secret only in the owning terminal and the HTTP
  response contains no secret.

---

## Phase 5: Pairing and Bootstrap Browser Experience

### Overview

Replace the foundation-only route with an unpaired/pairing/paired bootstrap
flow that removes secrets from browser history immediately, retains CSRF only
in memory, and preserves the narrow presentation-only storage policy.

### Changes Required

#### 1. Typed same-origin browser client

**Files**:

- `bifrost-console/web/src/api/client.ts`
- `bifrost-console/web/src/api/contracts.ts`
- `bifrost-console/web/src/api/client.test.ts`

**Changes**:

- Add hand-authored same-release DTOs for PR 08 pairing, bootstrap, tab, and
  bounded error responses.
- Use same-origin requests with explicit JSON types, credentials included, no
  client-side response persistence, and the in-memory per-tab CSRF header on
  protected calls.
- Treat authentication/security errors as browser-adapter outcomes, not target
  incompatibility or application failures.
- Clear secret-bearing request variables after completion and never echo them
  through diagnostic logging.

#### 2. Pairing route and fragment disposal

**Files**:

- `bifrost-console/web/src/app/routes.tsx`
- `bifrost-console/web/src/security/PairingPage.tsx`
- `bifrost-console/web/src/security/pairingFragment.ts`
- `bifrost-console/web/src/security/PairingPage.test.tsx`
- `bifrost-console/web/src/security/pairingFragment.test.ts`
- `bifrost-console/web/src/main.tsx`

**Changes**:

- Recognize the conceptual `#/pair/<secret>` bootstrap fragment in
  `web/src/main.tsx` before the browser router and normal route state are
  established.
- Copy the fragment secret into a short-lived local variable, immediately call
  `history.replaceState` to remove it from the visible address and current
  history entry, exchange it once, and clear component/request state.
- Render unpaired, exchanging, expired/invalid, limit, and paired outcomes
  without exposing the submitted value in markup, messages, telemetry, or
  storage.
- Provide the manual challenge/paste path when no valid session remains.

#### 3. Session/bootstrap context

**Files**:

- `bifrost-console/web/src/security/BrowserSessionProvider.tsx`
- `bifrost-console/web/src/security/sessionReducer.ts`
- `bifrost-console/web/src/security/BrowserSessionProvider.test.tsx`
- `bifrost-console/web/src/app/App.tsx`
- `bifrost-console/web/src/app/App.test.tsx`

**Changes**:

- Bootstrap on application load, register the current tab, and keep the tab ID,
  CSRF token, and process identity in React memory.
- On refresh, reuse the HttpOnly cookie and obtain fresh bootstrap/CSRF state.
- On session expiry, discard the in-memory security state and render the
  unpaired path without clearing or mutating server-wide state.
- Send authenticated in-memory heartbeats while a tab is active, re-bootstrap
  after an expired heartbeat or BFCache restoration, and send best-effort tab
  release on ordinary page disposal while relying on the server's stale-tab
  timeout for crashes/suspension.
- Show the resolved workspace path only after paired bootstrap.

#### 4. Storage policy enforcement

**Files**:

- `bifrost-console/web/src/security/storagePolicy.test.ts`
- `bifrost-console/web/src/app/ThemeSelect.tsx`
- `bifrost-console/web/src/app/ThemeSelect.test.tsx`

**Changes**:

- Preserve the current theme preference as allowed presentation-only
  `sessionStorage`.
- Add focused tests/lintable ownership conventions proving pairing values,
  cookies, CSRF, tab security state, bootstrap payloads, and errors are not
  written to `localStorage` or `sessionStorage`.
- Keep future scope-bound presentation state separate from the security
  provider.

### Success Criteria

#### Automated Verification

- [x] Pairing fragment removal occurs before the exchange promise is awaited and
  removes the secret from the current history entry.
- [ ] Invalid, expired, replayed, and capacity-limited pairing states render
  accessible non-secret messages.
- [ ] Refresh with a valid cookie bootstraps without pairing and rotates only
  the current tab's CSRF state.
- [x] Session expiry clears browser memory and returns to the unpaired view.
- [x] Storage-spy tests prove only approved presentation keys are written.
- [x] Frontend unit, type, and coverage commands pass:
  `npm run typecheck`, `npm test`, and `npm run test:coverage`.

#### Manual Verification

- [ ] The fragment disappears from the address bar and Back/Forward history
  immediately during pairing.
- [ ] Browser storage inspection contains the theme preference but no pairing,
  session, CSRF, bootstrap, or diagnostic data.
- [ ] Pairing, refresh, manual re-pairing, and two-tab behavior are keyboard
  usable and keep focus/announcements understandable.
- [ ] The paired shell displays the verified work directory without exposing
  lock or secret state.

---

## Phase 6: Startup/Shutdown Assembly, Cross-Platform Proof, and Documentation

### Overview

Join all owners into one production startup path, open/print the pairing URL,
prove fatal and graceful ordering, update Console runtime guidance, and run the
repository-standard verification pipeline.

### Changes Required

#### 1. Browser opener and pairing URL

**Files**:

- `bifrost-console/internal/browseropen/open_windows.go`
- `bifrost-console/internal/browseropen/open_darwin.go`
- `bifrost-console/internal/browseropen/open_linux.go`
- `bifrost-console/internal/browseropen/open_test.go`
- `bifrost-console/cmd/bifrost-console/main.go`
- `bifrost-console/cmd/bifrost-console/main_test.go`

**Changes**:

- Format the pairing URL from the actual accepted origin and fragment secret.
- Always print the pairing URL after the listener and initial challenge exist.
- Attempt the platform browser opener unless `--no-open-browser` is set.
- Keep opener failure nonfatal and sanitized; never log the command invocation
  or pairing secret separately.
- Inject the opener and terminal writer in tests so no real browser starts and
  secret output can be asserted exactly.

#### 2. Production service assembly

**Files**:

- `bifrost-console/internal/console/service.go`
- `bifrost-console/internal/console/service_test.go`
- `bifrost-console/cmd/bifrost-console/main.go`
- `bifrost-console/cmd/bifrost-console/main_test.go`

**Changes**:

- Assemble config/profile, workspace, lifecycle, browser-auth registries,
  browser API, static assets, and host with explicit ownership.
- Make startup unwind already-acquired resources in reverse order on every
  failure.
- On signal cancellation, close HTTP admission before browser state and
  workspace cleanup, then release work and profile locks.
- On workspace-fatal cancellation, retain and return the fatal cause after the
  same ordered close.
- Ensure browser/session failures remain request-scoped and cannot cancel the
  workspace or process.

#### 3. End-to-end and platform test coverage

**Files**:

- `bifrost-console/internal/console/security_integration_test.go`
- `bifrost-console/internal/console/lifecycle_integration_test.go`
- `bifrost-console/web/e2e/pairing.spec.ts`
- `bifrost-console/web/e2e/session-lifecycle.spec.ts`

**Changes**:

- Exercise a real ephemeral loopback listener through unpaired load, fragment
  exchange, cookie bootstrap, CSRF-protected pairing-link generation, rejection
  paths, expiry, and shutdown.
- Verify no listener opens for invalid config, locks, marker, permissions, or
  cleanup.
- Verify listener closure, session invalidation, transient cleanup, and lock
  release on graceful and simulated fatal shutdown.
- Keep platform-specific path, permission, lock, symlink/reparse, and restart
  tests runnable on Windows x86-64, Linux x86-64, and macOS Apple Silicon.
  This repository currently has no checked-in CI workflow; capture execution
  evidence from each supported target during PR verification rather than
  introducing a provider-specific CI system in PR 08. PR 15 owns release CI.
- Keep PR 15's comprehensive diagnostic-content workflows out of this PR while
  fully proving the security/workspace layers owned here.

#### 4. Runtime documentation

**Files**:

- `bifrost-console/README.md`

**Changes**:

- Document default config/work paths, exact YAML schema, CLI precedence,
  first-run behavior, lock conflicts, marker refusal, restart-only config,
  pairing lifecycle, manual re-pairing, browser-opening behavior, session
  limits, plaintext loopback cookie rationale, and shutdown cleanup.
- State explicitly that `transient/` is disposable, prior contents are never
  adopted, cleanup is not secure erasure, and the empty managed root may remain.
- Document production versus Vite development origin behavior.
- Record the short-lived pairing-fragment exception without weakening the rule
  against reusable credentials in URLs.
- Do not change the future ticket briefs: their existing target, workspace, and
  route-realm dependencies already align with the concrete PR 08 seams.

### Success Criteria

#### Automated Verification

- [ ] All Go tests and race-sensitive packages pass:
  `go test ./...` and
  `go test -race ./internal/profile ./internal/workspace ./internal/lifecycle ./internal/browserauth ./internal/browserapi ./internal/console`.
- [x] Frontend type, unit, coverage, and pairing browser tests pass:
  `npm run typecheck`, `npm test`, `npm run test:coverage`, and
  `npm run test:e2e -- pairing.spec.ts session-lifecycle.spec.ts`.
- [x] The canonical Console verification pipeline passes from
  `bifrost-console/`: `go run ./internal/buildtool verify`.
- [ ] The automated platform suite is executed successfully on Windows x86-64,
  Linux x86-64, and macOS Apple Silicon and its PR verification records prove
  lock, ownership/permission, path identity, and link/reparse behavior.
- [ ] Integration tests prove the fixed startup and reverse shutdown orders.
- [ ] Source/log scans and explicit capture tests find no pairing/session/CSRF
  value outside the allowed fragment, pairing body, cookie, CSRF header, and
  terminal pairing output channels.
- [x] Documentation examples parse through the production strict config loader.
- [x] No `ai/skill-authoring/` files or README coverage entries change.

#### Manual Verification

- [ ] Fresh launch on each supported OS creates the documented profile and work
  layout, prints the absolute work root and pairing URL, and opens the browser
  unless suppressed.
- [ ] A browser-open failure leaves the printed pairing flow usable.
- [ ] Foreign-origin and changed-authority requests fail while the correct
  production and explicit development flows work.
- [ ] Killing and restarting the process demonstrates non-adoption and cleanup
  of prior transient files.
- [ ] Graceful Ctrl+C closes the page's connection, removes current transient
  content best-effort, and lets a new process acquire both locks immediately.
- [ ] Error text is actionable for config, permission, marker, lock, cleanup,
  and browser security failures without revealing secret candidates or raw
  internal details.

## Testing Strategy

Create the dedicated testing-plan artifact with `ai/commands/3_testing_plan.md`
before implementation. It should map each PR 08 acceptance signal and relevant
Phase 2 security/workspace invariant to a failing-first test, platform coverage,
and exit criteria.

### Unit Tests

- Strict YAML structure, sentinels, bounds, defaulting, version, and error
  sanitization.
- Platform path normalization, profile hashing, permissions/ACL interpretation,
  file identity, and lock acquisition/release.
- Marker validation, safe enumeration, containment, symlink/reparse rejection,
  cleanup, and health probes.
- Authority/origin parsing and exact comparison.
- Pairing entropy, expiry, consumption, replacement, and rate limiting.
- Session/tab capacity, activity, expiry, relay admission, and cleanup.
- Per-tab CSRF generation, rotation, binding, and constant-time validation.
- Header/cache/error/body-limit behavior for every response class.
- React pairing-fragment disposal, API state, bootstrap, expiry, and storage
  policy.

### Integration Tests

- Full startup order with injected failure at every boundary.
- Real ephemeral loopback listener with exact authority, pairing exchange,
  cookie, bootstrap, and protected sensitive operation.
- Independent failure of Host, Origin, pairing, session, and CSRF controls.
- Multiple sessions/tabs under concurrent requests and expiry.
- Workspace cleanup/restart and process-fatal health loss.
- Graceful/fatal shutdown ordering and reverse resource release.
- Vite development authority/origin allowance isolated from production.

### Platform Tests

- Unix UID/mode and nonblocking advisory locks on Linux and macOS.
- Windows owner/DACL, `LockFileEx`, final file identity, junction, symlink, and
  general reparse-point handling.
- Default config/local-state root behavior on all three supported targets.
- Cross-process lock contention rather than only same-process mocks.

### Manual Testing Steps

1. Launch with defaults and inspect the generated config and managed work-root
   permissions/ACLs.
2. Pair through the printed fragment URL, confirm immediate address/history
   cleanup, and inspect cookie/storage/header state.
3. Open additional tabs and a separate browser profile; verify shared versus
   independent session/tab behavior and limits.
4. Request a manual pairing challenge and verify the secret appears only in the
   owning terminal.
5. Attempt foreign Host/Origin, stale cookie, replayed pairing, and invalid CSRF
   requests and confirm fail-closed behavior.
6. Leave a sentinel under `transient/`, terminate the process, restart, and
   verify it is deleted before the listener is usable.
7. Point `--work-dir` at an unmarked directory and verify no contents change.
8. Exercise graceful shutdown and verify listener, transient cleanup, and lock
   release.

## Performance Considerations

- Profile/workspace checks occur at startup, before workspace operations, and on
  a 30-second health cadence; they must remain bounded to ownership metadata and
  a tiny probe file rather than scanning future artifact content.
- Startup cleanup is proportional to prior transient entries by necessity but
  must stream directory enumeration and avoid loading a full tree into memory.
- Session and tab registries have small fixed caps, so expiration scans are
  bounded. Use one registry reaper rather than a goroutine per session or tab.
- Pairing and CSRF request bodies are tiny and bounded before JSON decoding.
- Security middleware performs only constant-size parsing, registry lookup, and
  fixed-length secret comparison before handlers.
- Do not add unbounded logging, error detail, challenge history, expired-session
  history, or background work queues.

## Security Considerations

- The threat model protects against malicious web origins and callers that can
  reach the loopback listener. Compromise of the developer's OS account or
  another process running with that account remains outside the initial scope.
- Loopback binding, exact authority, exact origin, one-time pairing, browser
  session authentication, and CSRF remain independent controls.
- Browser and future MCP routes are distinct authentication realms selected
  before authentication.
- Secret values must never enter ordinary logs, errors, YAML, browser storage,
  query strings, or reusable URLs.
- Pairing fragments remain visible to the local browser process and any
  privileged extension until removed; documentation must state this narrow
  exposure honestly.
- Owner-only/closest-platform permissions are verified, not merely requested at
  creation time.
- Cleanup refuses uncertainty. It does not follow a link/reparse boundary or
  fall back to another directory.
- Authenticated and credential-management responses are `no-store`; immutable
  caching remains limited to content-addressed static assets.

## Migration Notes

There is no protected PR 08 predecessor to migrate.

- First run creates schema-version 1 config and the managed work root.
- An existing PR 07 invocation with only `--listen` continues to express a
  process listener override but now also establishes the default profile and
  workspace before serving.
- Existing unmarked directories are never converted automatically. A developer
  must choose a new path or explicitly move/delete unrelated data outside the
  Console.
- Unsupported future/older YAML versions and marker formats fail closed; this
  PR adds no legacy reader or automatic rewrite.
- Browser sessions from a prior process are intentionally unusable and require
  pairing with the new process.

## Rollback Considerations

Rollback is code-only because no application or database state changes.
However, a created profile YAML and empty managed work root may remain. An older
PR 07 executable ignores them because it has no profile/workspace behavior.
Rollback instructions must not recommend recursively deleting an unresolved
path; developers may leave the non-secret config and empty managed metadata in
place.

## References

- Original ticket:
  `ai/thoughts/tickets/bifrost-console-pr-08-local-security-workspace.md`
- Primary research:
  `ai/thoughts/research/2026-07-26-bifrost-console-pr-08-local-security-workspace.md`
- Phase 2 design:
  `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md`
- Implementation roadmap:
  `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md`
- Framework compatibility lens:
  `ai/thoughts/framework-feature-design-lens.md`
- PR 09 consumer:
  `ai/thoughts/tickets/bifrost-console-pr-09-target-context.md`
- PR 12 consumer:
  `ai/thoughts/tickets/bifrost-console-pr-12-artifact-service.md`
- PR 15 hardening:
  `ai/thoughts/tickets/bifrost-console-pr-15-diagnostic-workflows.md`
- PR 16 consumer:
  `ai/thoughts/tickets/bifrost-console-pr-16-mcp-foundation.md`
- Official YAML package:
  `https://pkg.go.dev/go.yaml.in/yaml/v4`
- Official Go OS syscall packages:
  `https://pkg.go.dev/golang.org/x/sys/unix` and
  `https://pkg.go.dev/golang.org/x/sys/windows`
