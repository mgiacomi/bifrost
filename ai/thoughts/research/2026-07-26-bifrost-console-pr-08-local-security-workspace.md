---
date: 2026-07-26 18:51:19 PDT
researcher: Codex
git_commit: 601201a78c02adce92d6c9186421807cf20ec0c3
branch: main
repository: bifrost
topic: "PR 08 — Profile, Workspace, and Local Browser Security"
tags: [research, codebase, bifrost-console, go, profile, workspace, browser-security]
status: complete
last_updated: 2026-07-26
last_updated_by: Codex
---

# Research: PR 08 — Profile, Workspace, and Local Browser Security

**Date**: 2026-07-26 18:51:19 PDT  
**Researcher**: Codex  
**Git Commit**: 601201a78c02adce92d6c9186421807cf20ec0c3  
**Branch**: main  
**Repository**: bifrost

## Research Question

Research the current repository state needed to plan
`ai/thoughts/tickets/bifrost-console-pr-08-local-security-workspace.md`,
using the Phase 2 roadmap and later tickets to document how the profile,
workspace, listener, pairing, browser-session, CSRF, and browser-security
foundation is expected to connect to later Console work.

## Summary

PR 07 is present as a small, passing Console foundation. The executable
validates the injected release and embedded browser assets and then delegates
directly to a minimal HTTP host. That host validates an explicit loopback IP,
opens one TCP listener, serves a static SPA, and shuts down on context
cancellation. The current runtime has no local YAML configuration, profile
identity, runtime work directory, operating-system locks, browser pairing,
session registry, CSRF state, Host or Origin middleware, browser API handler,
browser bootstrap, or process-wide workspace-fatality mechanism
(`bifrost-console/cmd/bifrost-console/main.go:18-78`,
`bifrost-console/internal/webhost/host.go:14-72`).

The existing host deliberately exposes seams for PR 08:

- release and asset validation already complete before the listener is opened;
- the listener factory and `OnListen` callback are injectable;
- the default address is `127.0.0.1:7943`, and explicit IPv4 and IPv6 loopback
  literals are accepted while wildcard, named, LAN, and public addresses are
  rejected;
- `/api/console` is reserved from SPA fallback but has no handlers yet; and
- the static handler already distinguishes immutable hashed assets from
  `no-store` entry/navigation responses and emits `nosniff`.

The settled Phase 2 design supplies the detailed behavior behind the short
ticket. A resolved configuration file parent defines the profile. Startup must
take the profile lock, establish and lock a separately managed work root, clean
and recreate only its `transient` child, and complete all of this before
serving. Browser security is a per-process pairing and bounded-session model:
the initial secret is delivered in a URL fragment, exchanged once in a
same-origin request body, and replaced by an `HttpOnly`, `SameSite=Strict`
cookie plus a session-bound CSRF token held only in browser memory. Host,
Origin, session, and CSRF validation remain independent controls
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:210-256`,
`:270-281`, `:438-440`, `:782-889`).

Later PRs consume this foundation rather than create competing ownership.
PR 09 places target entry and credential replacement behind the paired,
CSRF-protected browser boundary. PR 12 stores all acquired and derived trace
state beneath the already verified transient workspace and uses its fatal
workspace rule. PR 15 performs full security, lifecycle, accessibility, and
packaging hardening. PR 16 adds a distinct MCP authentication realm and sibling
credential file under the same profile and mounts MCP on the same loopback
listener.

## Detailed Findings

### Current executable startup and lifecycle

`main` currently performs four runtime actions:

1. resolve the embedded browser filesystem;
2. construct release, verification, and serving dependencies;
3. create a signal-cancelled context; and
4. parse flags, validate the release, verify assets, and serve
   (`bifrost-console/cmd/bifrost-console/main.go:24-51`,
   `:54-78`).

The only runtime flags are `--version` and `--listen`. The listener defaults to
`127.0.0.1:7943`; there is no `--config`, `--work-dir`,
`--no-open-browser`, or protected application-key prompt flag
(`bifrost-console/cmd/bifrost-console/main.go:54-64`).

The `runtimeDependencies` test seam separates asset verification from serving.
Tests prove that an invalid release or invalid embedded asset set prevents the
serve callback and that validation happens before serving
(`bifrost-console/cmd/bifrost-console/main_test.go:10-56`). There is not yet a
profile/workspace establishment dependency or cleanup phase in this sequence.

`webhost.Host.Run` validates the configured address, requires a handler, opens
the listener, announces the actual bound address, and runs `http.Server`.
Cancellation calls `Shutdown` with a five-second deadline and then observes the
serve result (`bifrost-console/internal/webhost/host.go:33-72`). The server
currently sets only a five-second `ReadHeaderTimeout`; no other request,
response, idle, or body bounds are assembled here
(`bifrost-console/internal/webhost/host.go:51-54`).

### Present loopback and authority behavior

`ValidateLoopbackAddress` uses `net.SplitHostPort`, `net.ParseIP`, and
`IP.IsLoopback`. This makes the current accepted listener syntax an explicit IP
literal and port, not a hostname. Tests accept `127.0.0.1:0` and `[::1]:0` and
reject `0.0.0.0`, `[::]`, a LAN address, `localhost`, and `example.com`
before calling the listener factory
(`bifrost-console/internal/webhost/host.go:21-31`,
`bifrost-console/internal/webhost/host_test.go:33-58`).

The host opens one `"tcp"` listener for one configured address. It does not
currently open a coordinated IPv4/IPv6 listener set or derive accepted HTTP
authorities from the actual bound address. `OnListen` receives the bound
`net.Addr` and currently prints it in an HTTP URL-like startup message
(`bifrost-console/cmd/bifrost-console/main.go:35-44`).

There is no request middleware that inspects `Request.Host` or `Origin`.
Consequently, current loopback enforcement is a bind-address property only.
The design separately requires exact listener-authority validation and
same-origin browser requests
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:243-256`).

Development Vite already uses the corresponding narrow local topology. It
binds `127.0.0.1:5173`, uses a strict port, and proxies only
`/api/console/` to an absolute plain-HTTP explicit-loopback Go origin.
`changeOrigin` is false
(`bifrost-console/web/vite.config.ts:43-55`, `:61-73`). Production builds do
not include this development server configuration
(`bifrost-console/web/vite.config.ts:18-42`).

The Phase 2 design allows only the configured Vite development origin as an
additional browser origin in development mode; that allowance does not replace
production Host, Origin, pairing, session, or CSRF checks
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:192-194`).

### Present HTTP routing, caching, and headers

`StaticHandler` accepts `GET` and `HEAD`, emits
`X-Content-Type-Options: nosniff`, assigns explicit common content types, and
sets:

- `Cache-Control: no-store` for `index.html`, including SPA navigation
  fallback;
- immutable one-year caching for content-addressed assets; and
- `no-store` for other non-hashed assets
  (`bifrost-console/internal/webhost/static.go:17-68`).

The path classifier prevents traversal-like escaped forms and does not turn
missing asset-like requests into the entry document. `/api/console` and every
path beneath `/api/console/` are explicitly ineligible for SPA fallback, but
because no API router exists, they currently return not found
(`bifrost-console/internal/webhost/static.go:71-96`,
`bifrost-console/internal/webhost/static_test.go:34-46`).

The current handler does not emit Content Security Policy, anti-framing,
referrer, permissions, or cross-origin isolation/resource headers. It also has
no distinction between unauthenticated static/bootstrap responses and
authenticated diagnostic or credential-management responses. The Phase 2
design requires restrictive CSP, anti-framing, MIME-sniffing prevention, a
restrictive referrer policy, and `no-store` on every authenticated diagnostic
and credential-management response
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:1068-1082`).

### Configuration and profile state

There is no Go configuration package, YAML file, configuration fixture, or YAML
dependency in `bifrost-console/go.mod`. The module currently declares only the
module path and pinned Go toolchain
(`bifrost-console/go.mod:1-5`).

The settled configuration contract is:

- one versioned, strictly validated YAML file;
- a platform-appropriate default user configuration path plus `--config`;
- the resolved configuration file parent as the profile identity;
- unknown-field rejection;
- explicit units for durations and byte sizes;
- rejection of unsafe and nonpositive numeric values;
- explicit `unlimited` for `trace-workspace.max-bytes`;
- explicit `never` for `trace-workspace.idle-ttl`;
- restart-only application of static YAML; and
- no secrets in the YAML
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:210-216`).

The profile owns the YAML, one exclusive OS-level profile lock, one resolved
managed work directory, and later the Phase 3 sibling MCP credential file. The
profile lock is retained until shutdown, and a lock conflict causes startup
failure before concurrent configuration or credential mutation can occur
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:212-216`).

The design leaves the exact platform roots and profile-to-work-directory
encoding as implementation details, while requiring the mapping to be stable,
current-working-directory independent, profile-specific, and collision-free
for distinct profiles
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:220-229`).

### Managed work-directory contract

There is no runtime workspace package or runtime filesystem lock today. The
settled on-disk shape is:

```text
<resolved-work-root>/
  .bifrost-console-work
  .lock
  transient/
```

The root is visible and profile-specific by default. `--work-dir` selects the
exact replacement root. Startup prints its absolute path, and later paired
status exposes it
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:218-235`).

First use creates the root, marker, lock file, and transient child with
owner-only access or the closest enforceable platform equivalent. Later use
must verify the exact root, reject a symlink or Windows reparse-point root,
require the marker, acquire the work-directory lock, and only then clean. An
unmarked existing directory is treated as unrelated user data
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:229-233`).

The fixed startup ownership order is:

```text
resolve profile
  -> acquire profile lock
  -> resolve and verify managed work root
  -> acquire work-directory lock
  -> delete and recreate only transient/
  -> open listener
```

Cleanup cannot follow symbolic links, junctions, or reparse points beneath the
verified root. Prior-process files under `transient` are deleted, never scanned,
adopted, indexed, or served. Persistent configuration, trust material, profile
lock state, and the later MCP key remain outside the disposable subtree
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:216-241`).

The build tool contains a narrower compile-time cleanup precedent. Before
removing generated browser assets it resolves the owning asset root and target,
requires the exact `generated` relative child, checks the boundary with
`Lstat`, and then uses `RemoveAll`
(`bifrost-console/internal/buildtool/cleanup.go:11-42`). Tests cover the exact
child, an escaped directory, and a symlink/reparse boundary where the platform
permits creation
(`bifrost-console/internal/buildtool/cleanup_test.go:9-78`). This code operates
on repository build output, not on the runtime profile/workspace contract, and
does not implement runtime ownership markers, OS locks, owner permissions,
nested-entry traversal rules, or fatal workspace monitoring.

### Workspace startup, shutdown, and fatality

Workspace establishment is mandatory and precedes target connection, browser
service, MCP service, and any listener. Resolution, creation, identification,
protection, locking, cleanup, or verification failure is a fatal startup error;
there is no fallback directory or partially cleaned reuse
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:235-241`).

After startup, losing the work lock, path-safety guarantee, or required general
ability to manage console-owned workspace content is process-fatal. The design
sequence stops admission, cancels in-flight operations, closes browser and MCP
service, attempts cleanup only where still safe, releases locks, and returns an
error. Artifact-local malformed input, configured-capacity rejection, and
recoverable acquisition-local storage failures remain request-scoped
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:239-241`).

Current `Host.Run` already provides the signal/context-driven HTTP shutdown
portion and tests that cancellation closes the listener
(`bifrost-console/internal/webhost/host_test.go:60-92`). It has no component
lifecycle coordinator for workspace-fatal cancellation, ordered service
closure, transient cleanup, or lock release.

Graceful shutdown is expected to close listeners and upstream streams, cancel
users of transient files, remove current transient content best-effort, and
release work-directory and profile locks. The empty managed root, marker, and
unlocked lock file may remain
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:237-241`,
`:1090-1103`).

### Pairing, sessions, bootstrap, and CSRF

No pairing or browser API code exists in either Go or React today. The frontend
contains only a shell, a foundation route, a not-found route, and a theme
selector (`bifrost-console/web/src/app/routes.tsx:1-26`,
`bifrost-console/web/src/app/App.tsx:1-45`).

The startup pairing design begins only after profile and workspace validation.
The Console opens the loopback listener, generates a cryptographically strong,
short-lived, one-time pairing secret, attempts to open the default browser
unless `--no-open-browser` is set, and always prints the pairing URL. Failure to
open the browser is nonfatal
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:782-800`).

The conceptual URL places the secret in the fragment:

```text
http://127.0.0.1:<port>/#/pair/<one-time-secret>
```

The fragment is absent from the initial HTTP request. The SPA reads it into
memory, immediately removes it from the address and current history entry with
`history.replaceState`, submits it once in a same-origin request body, and
clears its application state. Success invalidates the secret and creates the
browser session; expiry and shutdown also invalidate it
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:790-800`).

The browser session contract is concrete:

- no more than eight paired sessions per process;
- no more than sixteen registered tabs across sessions;
- at most one live relay per tab;
- an eight-hour idle timeout;
- no session lifetime beyond the Console process;
- successful authenticated requests refresh idle time;
- an admitted live relay keeps its session active;
- expiration closes that session's relays and removes its server-side state
  without changing target scope or shared evidence; and
- refresh reuses the cookie and obtains fresh bootstrap state and CSRF
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:871-883`).

The session cookie is `HttpOnly` and `SameSite=Strict`. Bootstrap supplies a
fresh session-bound CSRF token for browser memory along with current status,
scope, and route reload facts. State-changing or sensitive operations require
the custom CSRF header in addition to exact Host, same-origin Origin, and
session authentication. The required protected operations include target or
application-key changes and the later MCP enable, disable, reveal, and
regenerate operations
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:438-440`,
`:875-881`).

The existing only browser storage use is the theme preference in
`sessionStorage`
(`bifrost-console/web/src/app/ThemeSelect.tsx:14-23`, `:40-49`). The settled
policy permits only scope-bound presentation state there. Pairing secrets,
application/MCP keys, cookies, CSRF tokens, activity, YAML, trace records,
payloads, prompts, model responses, and complete diagnostic responses cannot be
stored there
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:879-883`).

After expiry, an unpaired same-origin page can request a manual challenge. The
server prints the secret to the owning terminal rather than returning it to the
HTTP caller. That unauthenticated request still passes Host and Origin checks,
allows only one current challenge, is rate-limited, and does not touch target
or evidence state
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:885-889`).

### Independent browser security controls

The design explicitly treats these as complementary and non-substitutable:

1. loopback listener binding;
2. exact expected `Host`/authority;
3. same-origin `Origin`;
4. one-time pairing;
5. browser session authentication; and
6. CSRF on sensitive/state-changing operations.

Browser security failures occur before transport-neutral target services and
remain browser-adapter errors rather than target errors
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:413`,
`:438-440`).

The ticket does not add target access. PR 08 therefore establishes the
middleware and representative sensitive-operation boundary that PR 09 will
use; it does not implement application targets, application credentials,
Java/Go compatibility, acquisition, or trace interpretation
(`ai/thoughts/tickets/bifrost-console-pr-08-local-security-workspace.md:14-50`).

### Current and future route realms

The local browser API is a same-release internal adapter under
`/api/console/`. Its browser caller ships inside the same executable, so there
is no separate browser/Go compatibility version or cross-release negotiation
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:415-424`).

The later MCP adapter shares the listener but not browser authentication.
Routing must select the browser or MCP realm before authentication. Browser
routes accept the browser session and browser Host/Origin/CSRF policy; MCP
routes accept the MCP bearer key and MCP-specific Host/Origin policy. Neither
realm accepts pairing, CSRF, browser cookie, MCP key, or upstream application
key as a substitute for its own credential
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:438-440`).

PR 16 specifically depends on this composition. It adds the sibling MCP key
file, browser-controlled key operations, and stateful Streamable HTTP on the
existing listener. Its acceptance criteria require key-generation changes to
close MCP sessions without affecting browser or target state
(`ai/thoughts/tickets/bifrost-console-pr-16-mcp-foundation.md:14-49`).

### Later PR consumers

#### PR 09 — selected target

PR 09 depends on PRs 06 and 08. Its browser target-entry, replacement, retry,
and status UI runs only after pairing and uses the PR 08 CSRF-sensitive
operation boundary. The application key remains in Go process memory and is
not added to the profile YAML, arguments, URLs, browser storage, or logs
(`ai/thoughts/tickets/bifrost-console-pr-09-target-context.md:5-54`).

#### PRs 10 and 11 — authenticated operational/browser state

Read-only views and live execution delivery rely on authenticated browser
requests, paired bootstrap, tab registration, per-tab relay ownership, and
authenticated no-store responses. Session expiry closes only that session's
tab relays; it does not terminate the one shared upstream connection or clear
shared recent activity
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:434-440`,
`:871-883`).

#### PRs 12 through 14 — transient trace state

PR 12 uses the PR 08 workspace as the sole storage root for partial downloads,
installed immutable traces, indexes, and derived files. It adds artifact-local
admission, cleanup, eviction, and recovery while preserving PR 08's
process-fatal workspace-wide rule. It never exposes filesystem paths as
browser or protocol identities
(`ai/thoughts/tickets/bifrost-console-pr-12-artifact-service.md:9-60`).

PRs 13 and 14 build parsing and trace views over the same centralized copies;
they do not create another workspace or durable store. Authenticated trace
responses and downloads inherit the PR 08 session, cache, and browser-header
boundary.

#### PR 15 — Phase 2 hardening

PR 15 adds full untrusted-content security tests, response-bound tests,
Playwright lifecycle scenarios, target-reset behavior, and supported-platform
packaging. It verifies the owning security/workspace layers rather than
creating remote access or durable history
(`ai/thoughts/tickets/bifrost-console-pr-15-diagnostic-workflows.md:9-54`).

#### PR 16 and later MCP work

PR 16 reuses the profile, work root, listener, and transport-neutral status
seams. Later MCP trace inspection reuses PR 12's copies and PR 13's queries.
The profile lock continues to represent one Console process, while that process
may own multiple paired browser sessions and authenticated MCP clients
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:212-216`,
`:508-524`).

### Contract and compatibility classification

- **Application API:** PR 08 adds no Java application-facing API and changes no
  existing Bifrost Java public declaration.
- **Supported SPI:** no extension point or replacement bean is in scope or
  present in the Console foundation.
- **Configuration and manifest contracts:** the local YAML, default resolution,
  profile identity, work marker, and CLI flags are the relevant future
  contracts. None exists in live code yet. The embedded asset manifest is an
  existing strict internal build/runtime contract and remains unchanged
  (`bifrost-console/internal/webassets/manifest.go:3-26`).
- **Persisted or serialized contracts:** the YAML is persistent non-secret
  profile configuration. The managed marker/lock files are ownership metadata.
  The transient subtree is explicitly disposable and never adopted. Browser
  cookies, pairing secrets, CSRF tokens, and sessions are process-local rather
  than durable contracts.
- **Ephemeral diagnostic formats:** PR 08 does not consume or change REST, SSE,
  problem, artifact, or NDJSON semantics.
- **Internal or accidentally exposed implementation:** `/api/console/` is an
  internal same-release browser adapter seam. Its current reserved-path
  behavior does not establish a supported cross-release protocol.

There are no Java-to-Go protocol producers, executable fixtures, or protected
NDJSON consumers changed by this ticket. Later target and artifact handlers
will operate behind the PR 08 local security layer, but the current PR does not
change the Phase 1 application fixtures.

## Code References

- `bifrost-console/cmd/bifrost-console/main.go:18-78` — current dependency,
  flag, validation, and serving sequence.
- `bifrost-console/cmd/bifrost-console/main_test.go:10-56` — pre-listen release
  and asset validation tests.
- `bifrost-console/internal/webhost/host.go:14-72` — current loopback listener
  and graceful HTTP shutdown.
- `bifrost-console/internal/webhost/host_test.go:33-92` — IPv4/IPv6 validation
  and cancellation tests.
- `bifrost-console/internal/webhost/static.go:17-96` — static routing, current
  cache policy, `nosniff`, and reserved API prefix.
- `bifrost-console/internal/webhost/static_test.go:12-67` — SPA, reserved path,
  hashed-asset cache, method, and header tests.
- `bifrost-console/internal/buildtool/cleanup.go:11-42` — current repository
  build-output containment and cleanup pattern.
- `bifrost-console/internal/buildtool/cleanup_test.go:9-78` — contained,
  escaped, and symlink-boundary cleanup cases.
- `bifrost-console/web/vite.config.ts:43-73` — fixed development listener and
  narrow explicit-loopback proxy.
- `bifrost-console/web/src/app/routes.tsx:1-26` — current frontend route surface.
- `bifrost-console/web/src/app/ThemeSelect.tsx:14-49` — current
  presentation-only `sessionStorage` use.
- `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:210-256` — settled
  profile, workspace, and transport policy.
- `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:270-301` — local
  pairing and credential-separation policy.
- `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:415-440` — local
  browser API, bootstrap/reset, caching, CSRF, and route-realm policy.
- `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:782-889` — startup
  pairing and concrete session behavior.
- `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:1068-1107` —
  browser-security and resource/lifecycle requirements.
- `ai/thoughts/tickets/bifrost-console-pr-09-target-context.md` — first paired
  sensitive-operation consumer.
- `ai/thoughts/tickets/bifrost-console-pr-12-artifact-service.md` — primary
  transient-workspace consumer.
- `ai/thoughts/tickets/bifrost-console-pr-15-diagnostic-workflows.md` — Phase 2
  security and lifecycle hardening consumer.
- `ai/thoughts/tickets/bifrost-console-pr-16-mcp-foundation.md` — profile and
  shared-listener consumer.

## Architecture Documentation

The current Console has one straightforward ownership chain:

```text
cmd/bifrost-console
  -> release validation
  -> embedded-asset validation
  -> webhost.Host
       -> one loopback TCP listener
       -> StaticHandler
            -> embedded SPA
```

The settled PR 08 architecture adds profile/workspace ownership before the
listener and browser security around route dispatch:

```text
resolved config profile
  -> exclusive profile lock
  -> verified managed work root
  -> exclusive work lock
  -> clean transient/
  -> loopback listener and exact authority
       -> browser realm
            -> Host / Origin
            -> pairing or session
            -> CSRF for sensitive operations
            -> browser API or embedded SPA
       -> future MCP realm
            -> MCP Host / supplied-Origin / bearer key
```

The browser and MCP realms share process lifecycle and low-level utilities, but
not credential acceptance policy. The transient workspace is process-owned
storage below transport-neutral services, not browser-owned storage. The
profile is persistent ownership and configuration context; the transient
workspace, sessions, pairing state, CSRF state, browser tab state, and future
target credential are process-local.

## Historical Context (from ai/thoughts/)

- `ai/thoughts/research/2026-07-26-bifrost-console-pr-07-build-foundation.md`
  documented the pre-PR 07 repository and identified PR 08 as the direct owner
  of the final local-browser security and workspace lifecycle.
- `ai/thoughts/plans/2026-07-26-bifrost-console-pr-07-console-build-foundation.md`
  deliberately kept the host seam small for PR 08 and did not claim the PR 07
  listener as the completed security model.
- `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md`
  places PR 08 after the build foundation and before every target-facing
  Phase 2 behavior.
- `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md` is the settled
  design source for the concrete profile, workspace, pairing, session, cache,
  and lifecycle semantics summarized above.

## Related Research

- `ai/thoughts/research/2026-07-26-bifrost-console-pr-07-build-foundation.md`

## Open Questions

The ticket and Phase 2 design intentionally leave the following
implementation-planning details unselected in the current repository:

- exact YAML schema version field, complete PR 08 key set, and local error
  presentation;
- exact Windows, macOS, and Linux default configuration/local-state roots and
  deterministic profile-to-work-directory encoding;
- OS-specific profile/work lock implementation and owner-only
  permission/ownership verification;
- Windows reparse-point inspection and safe nested transient cleanup mechanics;
- whether the configured browser listener represents one chosen loopback family
  or coordinated IPv4 and IPv6 listeners, and how the exact accepted
  authorities are derived;
- exact production and development Host/Origin comparison rules;
- pairing secret entropy, lifetime, endpoint names, exchange body limits, and
  browser-opening integration;
- session identifier/cookie name, cookie attributes appropriate to the
  plaintext loopback listener, registry synchronization, and stale-tab timeout;
- CSRF token representation, rotation behavior, header name, and constant-time
  validation mechanics;
- exact CSP directives and the complete security-header set for embedded assets,
  unpaired pages, API responses, SSE, errors, and attachments;
- the representative sensitive operation used to exercise PR 08 middleware
  before target behavior arrives in PR 09; and
- the runtime signals/checks that distinguish artifact-local storage failure
  from loss of process-wide workspace safety.

These details have no existing live-code implementation or repository test
fixture at commit `601201a78c02adce92d6c9186421807cf20ec0c3`.

## Verification Performed

- `go test ./...` from `bifrost-console/` passed all current Go packages.
- `npm test -- --run` from `bifrost-console/web/` passed 3 test files and
  8 tests.
- The working tree was clean before this research document was added.
