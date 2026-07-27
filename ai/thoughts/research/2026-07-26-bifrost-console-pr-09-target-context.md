---
date: 2026-07-26T22:43:43-07:00
researcher: mgiacomi
git_commit: 6d558a7de7fb3edd0bfa928554c1f33393a56674
branch: main
repository: bifrost
topic: "Bifrost Console PR 09 — TargetContext and Selected-Target Lifecycle"
tags: [research, codebase, bifrost-console, target-context, application-protocol, credentials, lifecycle]
status: complete
last_updated: 2026-07-26
last_updated_by: mgiacomi
---

# Research: Bifrost Console PR 09 — TargetContext and Selected-Target Lifecycle

**Date**: 2026-07-26T22:43:43-07:00
**Researcher**: mgiacomi
**Model**: GPT-5
**Git Commit**: 6d558a7de7fb3edd0bfa928554c1f33393a56674
**Branch**: main
**Repository**: bifrost

## Research Question

Use `ai/commands/1_research_codebase.md` to perform codebase research for
`ai/thoughts/tickets/bifrost-console-pr-09-target-context.md`, consulting the
phase roadmap and future tickets where they clarify how PR 09 is used.

The target ticket asks for the codebase context needed to create the single
transport-neutral authority for selected-target configuration, process-memory
credentials, application identity, exact Java/Go compatibility, target-scope
rotation, cancellation, status, shared domain errors, browser target entry, and
protected terminal credential entry.

## Summary

PR 09 is not implemented in the current checkout. There is no Go
`TargetContext`, application protocol client, target credential provider,
`targetScopeId`, scope-owner registry, `ConsoleStatusSnapshot`, shared
transport-neutral domain-error type, selected-target browser state, target UI,
or terminal no-echo credential path. The Go module currently contains the PR 07
build foundation and PR 08 profile/workspace/browser-security foundation. Its
runtime starts a loopback HTTP server, establishes pairing and browser sessions,
and coordinates process shutdown, but it does not contact a Bifrost application
(`bifrost-console/internal/console/service.go:33-145`).

The application-side protocol that PR 09 will consume already exists. It is a
fixed authenticated `GET` namespace rooted at
`/_bifrost/observability/v1`, using exactly one
`X-Bifrost-Api-Key` request header and returning
`X-Bifrost-Instance-Id` on authenticated responses
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiPaths.java:3-13`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiKeyFilter.java:22-68`).
The authenticated `/instance` body supplies `instanceId`,
`consoleCompatibilityVersion`, `observedAt`, `liveMonitoringAvailable`, counts,
and retention facts
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/dto/ObservabilityDtos.java:18-29`).
Java generates and byte-compares representative REST and problem fixtures;
`bifrost-console-fixtures/application-rest/instance-status.json` currently
records compatibility `0.1.0-SNAPSHOT`
(`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ConsoleRestFixtureCorpusTest.java:29-54`,
`:79-119`).

The settled Phase 2 design supplies the detailed PR 09 semantics that are not
yet executable code. One `TargetContext` exclusively commits selected target
identity and rotates an opaque UUID scope. Every operation captures an
immutable snapshot and revalidates it before publishing; rotation cancels old
work and calls registered state owners, while a final scope check suppresses
late results. Target replacement, connection-authority changes, credential
replacement, changed established `instanceId`, and a new console process rotate
scope. Ordinary timeout changes, authentication failure, incompatibility, and a
temporary reconnect with the same identity update facts without rotating scope
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:329-349`).

The current browser boundary is ready to host sensitive operations but does not
yet expose them. Every console API request first passes exact Host and Origin
checks; protected operations additionally require the paired session and
session/tab-bound CSRF headers. Requests are bounded to 1 KiB JSON, reject
unknown fields, and responses are `no-store`
(`bifrost-console/internal/browserapi/router.go:34-65`,
`:110-127`; `bifrost-console/internal/browserapi/errors.go:10-49`).
The TypeScript client already sends protected CSRF headers, uses same-origin
credentials, disables fetch redirects, and requests `no-store`, but it only
knows pairing, bootstrap, link, release, and heartbeat operations
(`bifrost-console/web/src/api/client.ts:19-73`).

PRs 10–19 make PR 09 a long-lived shared boundary rather than a browser-only
feature. PR 10 uses its scope/status/errors for operational queries; PR 11 uses
its cancellation and identity decisions for the one SSE interval; PRs 12–14
bind artifacts, continuations, analysis, and browser links to its scope; PR 15
hardens reset and degraded paths; and PRs 16–18 adapt the same status, target
services, errors, and scope into MCP without creating a second target or
credential lifecycle
(`ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:108-129`).

## Detailed Findings

### 1. Current Go Console composition and the PR 09 insertion points

#### Process assembly

`console.Run` currently owns startup in this order:

1. Open and exclusively lock the configuration profile.
2. Resolve, open, lock, and clean the managed workspace.
3. Create a process lifecycle coordinator.
4. Start profile and workspace invariant monitors against that coordinator.
5. Create in-memory browser pairing and session registries.
6. Bind the loopback listener, derive its canonical authority, build the
   browser request policy and router, create the initial pairing secret, and
   serve static/browser API routes.
7. On cancellation or fatal failure, stop the coordinator, close sessions and
   pairing, clean transient workspace state best-effort, and release locks
   (`bifrost-console/internal/console/service.go:33-145`).

The dependency struct supplies only embedded files, an output writer, and a
browser opener. Runtime options contain config path, work directory, listener
override, development origin, and browser-opening choice. No target client,
credential input/output terminal, clock, UUID source, trust loader, or
scope-owner dependency exists today
(`bifrost-console/internal/console/service.go:19-31`).

The executable parses `--version`, `--config`, `--work-dir`, `--listen`,
`--development-origin`, and `--no-open-browser`. Positional arguments are
rejected. There is no target or application-key command-line option and no
no-echo prompt option
(`bifrost-console/cmd/bifrost-console/main.go:47-103`).

#### Process cancellation

The existing `lifecycle.Coordinator` wraps the process parent with
`context.WithCancelCause`, preserves the first fatal cause using `sync.Once`,
and exposes `Context`, `Fatal`, `Stop`, and `Cause`
(`bifrost-console/internal/lifecycle/coordinator.go:8-36`). Its current consumers
are profile/workspace monitors and the HTTP host. It is process-wide; no
target-scope cancellation mechanism exists.

`webhost.Host.Run` uses the process context to trigger a five-second HTTP server
shutdown and returns the context cancellation cause when it is not ordinary
cancellation. Server bounds are already explicit: five-second header read,
ten-second request read, thirty-second write, sixty-second idle, and 16 KiB
maximum headers (`bifrost-console/internal/webhost/host.go:45-104`).

#### Current configuration contract

The version-1 Console YAML has only:

- `listener.address`
- `trace-workspace.max-bytes`
- `trace-workspace.idle-ttl`

The resolved value contains only listener address, capacity/unlimited state,
idle TTL, and never-expire state
(`bifrost-console/internal/config/config.go:5-52`).
The decoder has an explicit allowlist for only those root and nested fields and
rejects unknown fields (`bifrost-console/internal/config/decode.go:42-63`,
`:85-101`). The default-config test also asserts that the emitted YAML contains
none of `secret`, `credential`, `csrf`, `cookie`, or `key:`
(`bifrost-console/internal/config/config_test.go:64-71`).

Consequently, Phase 2's non-secret default target, timeouts, and custom-CA
settings are not present in the live configuration model. The profile already
provides the persistent, locked, restart-only configuration boundary into which
such non-secret values fit; the application key remains excluded by the
existing configuration shape and tests.

### 2. Existing local browser authority and state

#### Request security and routing

`browserapi.Policy` canonicalizes and accepts only exact HTTP loopback origins
for the Console and optional Vite development server. It validates the request
Host independently from its single Origin header
(`bifrost-console/internal/browserapi/request_policy.go:10-69`).

The router applies controls in the following order:

1. security/no-store response headers;
2. Host validation;
3. Origin validation;
4. POST method enforcement;
5. route selection;
6. route-specific browser session and CSRF validation;
7. JSON body decoding and operation handling
   (`bifrost-console/internal/browserapi/router.go:34-65`, `:110-127`).

The current authenticated routes are bootstrap, pairing-link creation, tab
release, and heartbeat. Pairing exchange and manual pairing challenge are the
two pre-session operations
(`bifrost-console/internal/browserapi/router.go:50-64`).
There is no target selection, credential submission/replacement, target status,
retry, or recheck route.

`decodeJSON` reads no more than 1,025 bytes, rejects bodies over 1,024 bytes,
disallows unknown fields, and requires exactly one JSON value
(`bifrost-console/internal/browserapi/errors.go:10`, `:36-50`).
The current local error envelope contains only `code` and `message`; it has no
`targetScopeId` or code-specific details
(`bifrost-console/internal/browserapi/errors.go:12-30`).

#### Existing secret handling

Browser pairing values, session IDs, and CSRF tokens use 32 bytes from
`crypto/rand` encoded with unpadded URL-safe Base64. Candidate comparisons
decode to the canonical fixed length and use `subtle.ConstantTimeCompare`
(`bifrost-console/internal/browserauth/entropy.go:10-35`).
The pairing registry retains decoded secret bytes only in memory, expires a
pairing after five minutes, consumes it once, and clears it on shutdown
(`bifrost-console/internal/browserauth/pairing.go:10-80`).

The browser session registry is also in-memory and bounded to eight sessions
and sixteen tabs. It expires idle sessions after eight hours and disconnected
tab registrations after two minutes
(`bifrost-console/internal/browserauth/sessions.go:12-16`, `:52-111`,
`:163-210`). Session lookup and CSRF validation use constant-time secret
comparison (`bifrost-console/internal/browserauth/sessions.go:168-181`).

These are Console-owned local credentials. The current code has no application
credential type or provider. The Go module dependencies are `go.yaml.in/yaml/v4`
and `golang.org/x/sys`; it contains no terminal password/no-echo package
(`bifrost-console/go.mod:1-10`). The repository search found no existing
`ReadPassword` or equivalent no-echo input implementation in the Console.

#### Frontend state

The frontend's only domain state is `BrowserSessionState` with `loading`,
`unpaired`, and `paired` variants. A paired state carries the bootstrap
`processId`, workspace path, tab ID, and CSRF token
(`bifrost-console/web/src/security/sessionReducer.ts:1-24`,
`bifrost-console/web/src/api/contracts.ts:19-29`).

`BrowserSessionProvider` retains tab ID and CSRF only in refs, heartbeats an
active tab, re-bootstraps on local session-security rejection, and avoids
re-bootstrap when disposal races a rejected heartbeat
(`bifrost-console/web/src/security/BrowserSessionProvider.tsx:42-144`;
`bifrost-console/web/src/security/BrowserSessionProvider.test.tsx:88-166`).
The browser tests assert that CSRF is absent from rendered content and that both
`localStorage` and `sessionStorage` remain empty
(`bifrost-console/web/src/security/BrowserSessionProvider.test.tsx:20-50`).

The application shell renders either the pairing page or a single “Console
shell ready” foundation route. There is no Overview target store, selected
target state, `targetScopeId` reset behavior, or credential form
(`bifrost-console/web/src/app/App.tsx:6-53`,
`bifrost-console/web/src/app/routes.tsx:5-28`).

### 3. Existing Java application protocol consumed by PR 09

#### Route and authentication shape

The application adapter reserves the fixed root
`/_bifrost/observability/v1`; its instance probe is
`GET /_bifrost/observability/v1/instance`
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiPaths.java:3-12`).
The web auto-configuration registers the API-key filter on the root and all
children and supports every servlet dispatcher type
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostObservabilityWebAutoConfiguration.java:112-126`).

The filter requires exactly one `X-Bifrost-Api-Key`. A presented key is valid in
shape only when it contains 32–512 printable non-space ASCII characters. It
compares UTF-8 bytes through `MessageDigest.isEqual`; missing, duplicate,
malformed, or unequal keys receive the same sanitized 401
`BIFROST_API_KEY_REJECTED` problem
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiKeyFilter.java:46-64`,
`:105-130`).

After authentication, the filter establishes the framework-owned
`BIFROST_OPERATOR` authentication, sets `X-Bifrost-Instance-Id`, allows only
GET, and restores the caller's previous Spring Security context in `finally`
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiKeyFilter.java:65-101`).
It logs only a fixed debug statement on success. For mapped application errors,
it logs only the exception class
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiKeyFilter.java:69`,
`:89-95`).

All filter responses carry `Cache-Control: no-store`. Unauthenticated problems
clear the instance header; authenticated responses and authenticated problems
retain it (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiKeyFilter.java:50-68`,
`:133-147`).

#### Instance status and compatibility

The `/instance` controller requires operator authority, rejects query
parameters, obtains the active runtime, and assembles the response from one
runtime snapshot (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestController.java:54-65`).
Its `InstanceStatus` fields are:

- `instanceId`
- `consoleCompatibilityVersion`
- `observedAt`
- `liveMonitoringAvailable`
- registered skill, active execution, and cataloged trace counts
- `tracePersistencePolicy`
- `completionGraceTtl`
- `traceCatalogMetadataTtl`

(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/dto/ObservabilityDtos.java:18-29`).

The compatibility value comes from `BifrostReleaseVersion.load()` and is loaded
when the controller is constructed
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestController.java:32-51`).
The committed fixture's complete value is `0.1.0-SNAPSHOT`
(`bifrost-console-fixtures/application-rest/instance-status.json:1`).
The Console product version is independently embedded into the Go/browser
build today and is available from `release.ProductVersion()` during executable
assembly (`bifrost-console/cmd/bifrost-console/main.go:25-42`).

Authenticated responses also carry the same startup-scoped identity in
`X-Bifrost-Instance-Id`. The settled design uses the status body to establish
identity and requires other authenticated responses to match that committed
identity rather than adopting it independently.

#### Application problems

The Java serialized problem is the record `{status, code, message}`. Current
codes are:

- `BIFROST_API_KEY_REJECTED`
- `INVALID_REQUEST`
- `INVALID_CURSOR`
- `STALE_CURSOR`
- `NOT_FOUND`
- `LIVE_MONITORING_UNAVAILABLE`
- `LIMIT_EXCEEDED`
- `APPLICATION_ERROR`

(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityProblem.java:3-17`).

`ObservabilityProblemMapper` walks the cause chain for an
`ObservabilityException`; otherwise it returns a sanitized 500
`APPLICATION_ERROR` with no underlying text
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityProblemMapper.java:3-19`).
The fixture corpus generates all eight problem bodies and byte-compares their
complete inventory against committed JSON
(`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ConsoleRestFixtureCorpusTest.java:29-54`,
`:97-119`).

The Java protocol cannot itself produce the distinction between
`BIFROST_API_KEY_REJECTED` and a generic host/proxy 401 or 403: the first is a
recognized Java problem body; the latter occurs outside or before this
application filter. The settled Go domain mapping preserves that distinction as
`TARGET_AUTHENTICATION_REQUIRED` versus `TARGET_ACCESS_BLOCKED`
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:389-405`).

#### Protocol fixtures and protected consumers

`bifrost-console-fixtures/README.md` calls this directory the current-release
Java-to-Go semantic contract. `application-rest/` holds Java-generated REST and
problem bodies, `application-sse/` holds Java-generated stream frames, and
`application-artifact/` holds download-response metadata. It explicitly states
that PR 09 must reject a compatibility mismatch before any snapshot, SSE,
catalog, or artifact request
(`bifrost-console-fixtures/README.md:1-34`).

There is no Go consumer of those application fixtures in the current checkout.
The only `bifrost-console` application network requests found by repository
search are absent; the current browser E2E test actively records any accidental
request to `/_bifrost/observability/` and expects none
(`bifrost-console/web/e2e/shell.spec.ts:10-45`).

### 4. Settled target URL, HTTP, TLS, and timeout semantics

The Phase 2 design describes the selected target as an application base URL,
including an externally visible servlet or reverse-proxy context path. Go
appends the fixed observability namespace
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:283-291`).

Before selection or scope rotation, the designed validation rejects unsupported
schemes, embedded user information, malformed or ambiguous authority,
unsupported path form, fragments, and other invalid syntax. Redirect discovery
is not part of the initial client
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:802-815`).

The accepted transport policy is:

- HTTP and HTTPS targets are supported.
- HTTPS uses ordinary certificate and hostname validation.
- Private infrastructure may configure a custom CA bundle.
- HTTP remains allowed and is presented persistently as unencrypted.
- HTTPS verification is never silently weakened.

(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:258-268`).

The target is a network-authority boundary: redirects are disabled and
credential material is never forwarded to another origin. Upstream targets may
be loopback, LAN, or remote; only the Console's browser/MCP listener is
loopback-restricted
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:1084-1088`).

The current Go code has no `http.Client`, custom `http.Transport`,
`tls.Config`, CA-pool loading, redirect policy, upstream response bound,
upstream request timeout, or application URL normalization routine. The
existing server-side HTTP timeouts in `webhost.Host` govern browser-to-Go
traffic, not Go-to-application traffic.

### 5. Settled credential and terminal lifecycle

The application key is entered through either the paired browser or a protected
interactive terminal prompt, then retained only in Go process memory behind the
same credential provider used by browser-originated and future MCP-originated
operations. It does not enter ordinary YAML, command-line arguments, URLs,
browser storage, or logs
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:283-301`).

Browser entry is a same-origin, CSRF-protected state change. The target address
and key are separate inputs. The browser clears both the input and submission
state after completion and never displays the accepted key or a suffix
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:816-820`).

The terminal alternative is described as a non-secret option such as
`--prompt-for-application-key`. It requires an already selected non-secret
target, reads without echo, populates the same provider, and creates no
terminal-owned target session or credential lifecycle
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:822-830`).
No corresponding option, terminal abstraction, no-echo reader, or terminal
test seam exists in current Go code.

Credential replacement is an authoritative change even when the bytes happen
to equal the old bytes. The settled lifecycle therefore rotates scope without
performing old/new secret comparison
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:337-341`,
`:832-838`). This differs from current browser credential authentication,
where constant-time comparison is appropriate because the operation is
verifying a presented pairing/session secret rather than deciding whether an
authoritative replacement occurred.

### 6. TargetContext state, rotation, scope owners, and late-result suppression

The settled `TargetContext` owns:

- normalized target address and connection-authority settings;
- opaque credential generation and protected credential provider;
- connection, authentication, and exact-version compatibility facts;
- established application `instanceId`;
- the lifecycle of the one upstream SSE connection.

The raw credential is excluded from all snapshots given to browser, MCP,
analysis, and other consumers
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:329-335`).

Each target operation captures an immutable scope snapshot. Before returning,
installing local state, updating a shared store, or publishing an event, it
checks that the captured scope is still current. Status changes may publish new
immutable snapshots with the same `targetScopeId`; equal scope therefore does
not mean equal current status
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:333-335`).

Scope rotates for:

- every new Console process;
- selected-target replacement;
- connection-authority setting changes;
- every accepted credential replacement;
- a changed `instanceId` after identity was already established.

The first authenticated compatible status result establishes identity inside
the already-created scope. Later identity changes can be proposed only by a
serialized reconnect status check or the upstream SSE handshake and are
committed only by `TargetContext`
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:337-341`).

Rotation cancels prior-scope work, closes the old stream, and asks the owners of
application-derived state to clear their own state. Named future owners include
skill/YAML state, active snapshots, recent activity and relay state, acquired
artifacts, indexes, derived analysis, and browser/MCP runtime views.
`TargetContext` coordinates invalidation rather than storing those domains
itself. Cancellation is advisory; the current-scope check is the final
late-result barrier
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:343-347`).

No scope-owner registration interface or implementation exists yet. Current
shutdown uses direct `defer`/`Close` calls for browser authentication and the
workspace. Current browser reducers have no scope action or shared domain reset
event.

### 7. Transport-neutral status and shared errors

The designed `ConsoleStatusSnapshot` is a side-effect-free projection assembled
from an already-current immutable target snapshot. It does not probe, reconnect,
mutate credentials, repair workspace state, acquire evidence, or query a
catalog. It reports independent facts rather than aggregate health
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:351-379`).

Its conceptual fields are:

- `observedAt`
- optional `targetScopeId`
- `targetSelection`: `NONE | SELECTED`
- `targetConnection`: `NOT_APPLICABLE | UNKNOWN | REACHABLE | UNAVAILABLE`
- `targetAuthentication`:
  `NOT_APPLICABLE | UNKNOWN | REQUIRED | ESTABLISHED | BLOCKED`
- `javaGoCompatibility`:
  `NOT_APPLICABLE | NOT_CHECKED | COMPATIBLE | INCOMPATIBLE`
- `runtimeIdentity`:
  `NOT_APPLICABLE | NOT_ESTABLISHED | ESTABLISHED`
- optional `instanceId`
- `liveMonitoring`:
  `NOT_APPLICABLE | UNKNOWN | AVAILABLE | UNAVAILABLE`

(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:355-369`).

There is deliberately no `healthy`, `ready`, `degraded`,
`traceCatalogReachable`, MCP-enabled, or workspace-degraded field. Current
application authentication and already-acquired evidence availability remain
separate facts
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:371-379`).

The designed shared error contains a stable code, safe message, target scope
when applicable, and bounded code-specific details. Initial codes are
`INVALID_ARGUMENT`, `TARGET_AUTHENTICATION_REQUIRED`,
`TARGET_ACCESS_BLOCKED`, `TARGET_UNAVAILABLE`, `INCOMPATIBLE_TARGET`,
`TARGET_CHANGED`, `INVALID_CURSOR`, `STALE_CURSOR`, `NOT_FOUND`,
`ARTIFACT_EXPIRED`, `INVALID_ARTIFACT`, `LIVE_MONITORING_UNAVAILABLE`,
`LIMIT_EXCEEDED`, `LOCAL_STORAGE_UNAVAILABLE`, and `CONSOLE_ERROR`
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:381-409`).

Browser pairing/session/Host/Origin/CSRF failures remain adapter errors that
occur before these shared services. The current TypeScript `BrowserErrorCode`
union contains only local browser API codes and does not yet distinguish the
shared target codes (`bifrost-console/web/src/api/contracts.ts:1-17`).

### 8. Future PR consumers

PR 09's downstream usage is explicit:

- **PR 10** adds shared skill, active-execution, and trace-catalog query services
  and binds pagination, observations, and UI navigation to identity and target
  scope (`ai/thoughts/tickets/bifrost-console-pr-10-operational-views.md`).
- **PR 11** owns one SSE connection and one continuous recent-activity interval
  for the selected target scope; changed instance, stale upstream cursor, or
  rotation clears the interval before new events are admitted
  (`ai/thoughts/tickets/bifrost-console-pr-11-live-execution-experience.md`).
- **PR 12** binds the single shared artifact copy, handle, acquisition,
  cancellation, and raw-download pass-through to scope and acquisition-time
  authorization (`ai/thoughts/tickets/bifrost-console-pr-12-artifact-service.md`).
- **PRs 13–14** bind parsing queries, continuations, calculated evidence, deep
  links, and stale-link resets to current artifact handles and target scope
  (`ai/thoughts/tickets/bifrost-console-pr-13-trace-analysis-services.md`;
  `ai/thoughts/tickets/bifrost-console-pr-14-trace-explorer.md`).
- **PR 15** supplies browser workflow and Playwright coverage for reconnect,
  reset, authentication, and stale-scope paths
  (`ai/thoughts/tickets/bifrost-console-pr-15-diagnostic-workflows.md`).
- **PR 16** exposes the shared status snapshot to MCP as
  `bifrost_get_runtime`; its independent MCP credential generation and sessions
  do not alter browser or target state
  (`ai/thoughts/tickets/bifrost-console-pr-16-mcp-foundation.md`).
- **PR 17** adapts PR 09–11 services and shared errors into MCP without direct
  application contact or a second target store
  (`ai/thoughts/tickets/bifrost-console-pr-17-mcp-runtime-inspection.md`).
- **PR 18** reuses PR 12–13 artifact and query lifecycles, including scope-bound
  continuations and identical error meanings
  (`ai/thoughts/tickets/bifrost-console-pr-18-mcp-trace-inspection.md`).
- **PR 19** treats target compatibility, authentication, protocol capability,
  and evidence availability as distinct failure meanings presented to an IDE
  agent (`ai/thoughts/tickets/bifrost-console-pr-19-debugging-skill.md`).

The workflow document also preserves one important separation across these
consumers: execution outcome, application trace availability, local artifact
availability, target authentication, connection state, and continuity facts
cannot be collapsed into one health label
(`ai/thoughts/phases/bifrost_console_workflows.md:78-94`).

## Contract and Compatibility Inventory

### Application API

No ordinary application-developer Java API is changed or consumed directly by
PR 09. The target client talks only to the observability web adapter. The
application API classes under `com.lokiscale.bifrost.api` are outside this
ticket's live integration path.

### Supported SPI

No supported application SPI or Go extension SPI for target handling currently
exists. The Java observability web components are public classes because they
participate in framework wiring, but the architecture allowlist describes
`ObservabilityException`, `ObservabilityProblem`, and
`ObservabilityProblemMapper` as internal web/problem propagation surfaces
(`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:58-60`).

The web auto-configuration declares infrastructure beans but does not annotate
them with `@ConditionalOnMissingBean`; no application replacement contract is
present
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostObservabilityWebAutoConfiguration.java:39-126`).

### Configuration and manifest contracts

Two deliberate configuration contracts are relevant:

- Application `bifrost.observability.enabled` and
  `bifrost.observability.auth.api-key`, including documented key shape
  (`bifrost-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json:3-19`).
- Console version-1 YAML, which currently contains only listener and workspace
  fields and rejects all target fields as unknown
  (`bifrost-console/internal/config/config.go:5-52`;
  `bifrost-console/internal/config/decode.go:42-63`).

No target configuration schema is present in live code. Phase 2 describes its
future non-secret contents but does not settle the exact YAML field spelling.

### Persisted or serialized contracts

The authenticated application REST/SSE/artifact boundary is a deliberately
consumed, current-release serialized contract. Evidence includes the Phase 1
adapter, project documentation, Java-generated fixture corpus, and explicit
future Go consumer statements
(`README.md:282-312`; `bifrost-console-fixtures/README.md:21-34`).
The exact complete `consoleCompatibilityVersion` gates every other application
boundary.

The local browser JSON boundary is also technically serialized, but browser and
Go ship atomically in one executable and the Phase 2 design states that the
initial product has no independent browser API compatibility version
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:145-149`).

### Ephemeral diagnostic formats

The NDJSON trace corpus is an ephemeral current-release diagnostic format, not
a cross-version archive contract. PR 09 does not parse it; it establishes the
exact compatibility and scope prerequisite used by PRs 12–13 before acquisition
and analysis (`bifrost-console-fixtures/README.md:1-18`, `:31-34`).

### Internal or accidentally exposed implementation

The current Go `browserapi`, `browserauth`, `console`, `lifecycle`, `profile`,
and `workspace` packages are under `internal` and have no external Go consumer.
The future exact package subdivision, constructors, scope-owner interface,
snapshot structs, and adapter mapping functions are not established in the
repository.

Java DTO records, controller constructors, filters, problem mappers, and
infrastructure beans are technically public or visible to Spring, but their
package, architecture allowlist, and configuration role provide evidence that
they are framework-owned implementation behind the deliberately serialized web
boundary rather than supported Java API/SPI.

## Architecture Documentation

The current and settled flow is:

```text
paired browser / protected terminal / future MCP
                    |
                    | local adapter authentication
                    v
        Go transport-neutral TargetContext
          |  selected target + credential provider
          |  immutable scope snapshot
          |  status + shared domain errors
          |  cancellation + owner invalidation
          v
        Go application protocol client
          |  exact selected origin only
          |  X-Bifrost-Api-Key
          |  no redirects; HTTP or verified HTTPS
          v
Spring observability adapter /instance first
          |  exact consoleCompatibilityVersion
          |  startup-scoped instanceId
          v
later shared snapshot, SSE, catalog, and artifact services
```

Only the bottom Java boundary and the top local browser-security foundation
exist in the live checkout. The central Go target authority and its browser and
terminal adapters are the unimplemented PR 09 layer.

## Historical Context (from `ai/thoughts/`)

- `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:108-129`
  places PR 09 after local security and before all operational/application
  features and later MCP adapters.
- `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:329-413`
  is the authoritative detailed ownership, status, and error design summarized
  by the ticket.
- `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:782-869`
  defines the paired-browser and protected-terminal experience, replacement
  behavior, result presentation, and reconnect semantics.
- `ai/thoughts/phases/bifrost_console_workflows.md:78-94` records the cross-flow
  requirement to expose independent lifecycle and availability facts and to
  share error meanings between browser and MCP.
- `ai/thoughts/tickets/bifrost-console-pr-08-local-security-workspace.md`
  explicitly leaves application targets out of PR 08 while establishing the
  local security controls PR 09 uses.
- `ai/thoughts/tickets/bifrost-console-pr-10-operational-views.md` through
  `ai/thoughts/tickets/bifrost-console-pr-18-mcp-trace-inspection.md` identify the concrete services
  and references that later bind to PR 09 scope and errors.
- `ai/thoughts/framework-feature-design-lens.md:13-34` classifies supported and
  internal framework surfaces and states that public visibility, constructors,
  beans, and fixtures are evidence of exposure/behavior rather than proof of a
  supported API or SPI.

## Related Research

No other files currently exist in `ai/thoughts/research/`.

## Open Questions

The following details are not established in the current code or ticket brief
and remain unclassified implementation-planning details:

- exact Console YAML field names and defaults for target URL, timeouts, and
  custom CA configuration;
- exact Go package names, public-within-module interfaces, constructors, and
  immutable snapshot representation;
- exact scope-owner registration and shutdown ordering;
- exact concrete timeout, body/header, connection-pool, retry-backoff, and
  status-probe bounds;
- exact accepted target path normalization rules beyond the settled rejection
  categories;
- exact custom-CA input form and loading semantics;
- exact terminal no-echo implementation/library, availability checks, prompt
  input/output seams, and interruption behavior;
- exact browser route names, request/response DTO spelling, HTTP status mapping,
  and code-specific error-detail shapes;
- exact browser store/reducer split and focus/reset behavior on scope rotation;
- exact safe transport categories exposed for DNS, connection, TLS, timeout,
  redirect, namespace-not-found, and upstream-server failures;
- whether PR 09 consumes only the existing Java fixtures or adds Go-specific
  local browser fixtures for its new status and error DTOs.

These are unknowns in the current repository rather than existing behavior.
