---
date: 2026-07-27T15:54:44-07:00
researcher: mgiacomi
git_commit: f4da18a3b2d2c455047693969c595fce5ae05d0f
branch: main
repository: bifrost
topic: "PR 10 — Read-Only Operational Views: current codebase state and supporting infrastructure"
tags: [research, bifrost-console, pr-10, operational-views, target-context, browserapi, observability, pagination]
status: complete
last_updated: 2026-07-27
last_updated_by: mgiacomi
---

# Research: PR 10 — Read-Only Operational Views

**Date**: 2026-07-27T15:54:44-07:00  
**Researcher**: mgiacomi  
**Git Commit**: `f4da18a3b2d2c455047693969c595fce5ae05d0f`  
**Branch**: `main`  
**Repository**: `bifrost`

## Research Question

What currently exists in the `bifrost-console` Go module and the upstream Bifrost Spring observability boundary to support PR 10's read-only operational views (Overview, Skill Catalog, Active Executions, trace-catalog browsing), and what patterns from PRs 07–09 should PR 10 reuse?

## Summary

The repository already contains the complete Phase 1 Java observability REST contract under `bifrost-spring-boot-starter/internal/observability/web/` and the first Phase 2 console foundations under `bifrost-console/`. The Java side exposes paginated `/instance`, `/skills`, `/active-executions`, `/traces`, `/activity` SSE, and `/traces/{traceId}/artifact` endpoints. The Go console has a working `target.Context`, `applicationclient`, `browserapi`, `webhost`, and React/Vite frontend, but **no Go-side query services or browser routes yet for skills, active executions, or traces**. PR 10 would add new transport-neutral Go services that call the existing Java REST endpoints, expose them through the existing `browserapi` POST-handler pattern, and render them with new React routes and components following the `TargetProvider`/`targetReducer` and `BrowserSessionProvider` patterns. The canonical JSON fixtures in `bifrost-console-fixtures/application-rest/` already define the exact request/response shapes.

## Detailed Findings

### 1. PR 10 scope and roadmap position

- PR 10 is in Phase 2 and depends on PR 09 (`TargetContext`). It is a prerequisite for PR 11 (live activity/detail), PR 12 (artifact service), PR 13 (trace analysis), PR 14 (trace explorer), and PR 15 (diagnostic workflows). `ai/thoughts/tickets/bifrost-console-pr-10-operational-views.md:7-45`.
- Outcome: deliver Overview landing, Skill Catalog list/detail, Active Executions list/detail snapshots, and trace-catalog browsing through reusable transport-neutral services. `bifrost-console-pr-10-operational-views.md:8-23`.
- Guardrails: Go must not continuously materialize complete registries; browser handlers adapt shared services; YAML is rendered as untrusted text; `sourcePath` is not a filesystem link; active snapshots are best-effort facts, not history. `bifrost-console-pr-10-operational-views.md:26-31`.
- The roadmap explicitly states that PR 10 must preserve keyset pagination, high-water semantics, opaque continuations, identity, observation time, and target scope from Phase 1. `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:70-120`.

### 2. Phase 1 Java observability REST contract

The Java side's REST surface lives in `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/`.

- **Base path**: `/_bifrost/observability/v1` (`ObservabilityApiPaths.java:5`).
- **Registered routes** (`ObservabilityRouteRegistrar.java:100-116`):
  - `GET /instance` -> `ObservabilityRestController.instance` (`ObservabilityRestController.java:54-66`)
  - `GET /skills?pageSize=&cursor=` -> `skills` (`ObservabilityRestController.java:68-94`)
  - `GET /skills/{registeredName}` -> `skill` (`ObservabilityRestController.java:96-102`)
  - `GET /active-executions?pageSize=&cursor=` -> `active` (`ObservabilityRestController.java:104-136`)
  - `GET /active-executions/{sessionId}` -> `active(sessionId)` (`ObservabilityRestController.java:138-147`)
  - `GET /activity?instanceId=&afterCursor=` SSE -> `activity` (`ObservabilityRestController.java:149-203`)
  - `GET /traces?pageSize=&cursor=` -> `traces` (`ObservabilityRestController.java:205-241`)
  - `GET /traces/{traceId}` -> `trace` (`ObservabilityRestController.java:243-249`)
  - `GET /traces/{traceId}/artifact` -> `artifact` (`ObservabilityRestController.java:251-290`)
  - Fallback `/**` -> `fallback` for unmatched observability paths.
- **DTO records** (`ObservabilityDtos.java:18-107`):
  - `InstanceStatus(instanceId, consoleCompatibilityVersion, observedAt, liveMonitoringAvailable, registeredSkillCount, activeExecutionCount, catalogedTraceCount, tracePersistencePolicy, completionGraceTtl, traceCatalogMetadataTtl)`.
  - `SkillSummary(registeredName, sourcePath, href)` where `href` is the URL-encoded relative path `skills/{registeredName}`.
  - `SkillDetail(registeredName, sourcePath, yaml)` where `yaml` is the uninterpreted YAML text.
  - `ActiveExecution(sessionId, traceId, lastCanonicalSequence, startedAt, updatedAt, elapsedMillis, entrySkill, status, phase, summary, activePath, totalFrameDepth, activePathTruncated, usage, configuredLimits)`.
  - `Trace(traceId, sessionId, outcome, finalizedAt, sizeBytes, persistencePolicy, applicationTraceExpiresAt)`.
  - `Page<T>(items, hasMore, nextCursor, observedAt)`.
  - `ActivePage(items, hasMore, nextCursor, observedAt, resumeCursor)`.
- **Pagination and cursors** (`ObservabilityCursorCodec.java:17-71`): cursors are Base64-URL JSON objects with fields `version`, `instanceId`, `collection`, `order`, `filter`, `highWater`, `beforeOrdinal`, `afterName`. Validation enforces `version == 1`, `order == "keyset"`, `filter == "none"`, matching `instanceId` and `collection`, and cursor freshness (`410` `STALE_CURSOR` if the cursor belongs to another instance). `ObservabilityRestController.java:74-92` (skills) and `104-134` (active) and `205-239` (traces) show how `highWater`/`beforeOrdinal`/`afterName` are used and how `nextCursor` is produced.
- **JSON page writer** (`BoundedJsonPageWriter`/`ObservabilityJsonCodec`): produces page bodies with `hasMore`, `nextCursor`/`nextCursor=null`, and `observedAt`. `ObservabilityRestController.java:86-92`.
- **Access control**: `ObservabilityApiKeyFilter` guards `/_bifrost/observability/v1/**`; `ObservabilityAccessService` defines operations `INSTANCE_READ`, `SKILL_READ`, `ACTIVE_READ`, `ACTIVITY_SUBSCRIBE`, `TRACE_READ`, `TRACE_ARTIFACT_READ`. `ObservabilityAccessService.java:8-11`.
- **Route activation**: `BifrostObservabilityWebAutoConfiguration.java:40-127` wires the controller, mapper, cursor codec, page writer, JSON codec, access service, and route registrar. `ObservabilityRouteRegistrar.createRuntime` (`ObservabilityRouteRegistrar.java:128-162`) builds the `ObservabilityRuntime` from in-memory registries (`InMemoryActiveExecutionRegistry`, `InMemoryActivityReplayBuffer`, `DefaultRegisteredSkillCatalog`, `InMemoryFinalizedTraceCatalog`, `LiveMonitoringAvailability`, etc.).
- **Configuration**: `BifrostProperties.Observability` (`BifrostProperties.java:328-364`) has `enabled`, `auth.apiKey` (32–512 printable ASCII), `completionGraceTtl` (default `PT15M`), and `traceCatalogMetadataTtl` (default `PT24H`). `ObservabilityRouteRegistrar.validate` checks these at activation (`ObservabilityRouteRegistrar.java:214-244`).

### 3. Go console current state

#### 3.1 `applicationclient` — upstream HTTP client

- `applicationclient/client.go:34-40` defines `Client{address, expectedVersion, requestTimeout, http, transport}`.
- Only `Probe(parent, credential)` is implemented (`client.go:89-130`). It sends `GET {address.ObservabilityRoot}/instance` with the credential header, validates `InstanceID` and `consoleCompatibilityVersion`, and returns `applicationclient.Instance`.
- `Address.ObservabilityRoot()` returns `{display}/_bifrost/observability/v1` (`address.go:84-87`), and `InstanceEndpoint()` returns that root plus `/instance` (`address.go:126-130`).
- `Client` enforces no redirects (`CheckRedirect` returns `http.ErrUseLastResponse`), `Accept-Encoding: identity`, bounded 64 KiB response bodies (`maxResponseBytes = 64 * 1024`), and strict transport categorization (`errors.go`, `problem.go`).
- Transport configuration (`client.go:63-76`): `ForceAttemptHTTP2: true`, `MaxIdleConns: 16`, `MaxIdleConnsPerHost: 4`, `IdleConnTimeout: 90s`, `DisableCompression: true`, `MaxResponseHeaderBytes: 64 KiB`, `TLSClientConfig: MinVersion: tls.VersionTLS12`, `Proxy: nil` (no proxy). Custom CA bundle is appended to system cert pool if provided via `NetworkPolicy.CABundlePEM`.
- `Address.ObservabilityRoot()` returns `{display}/_bifrost/observability/v1` (`address.go:86`), and `InstanceEndpoint()` returns that root plus `/instance` (`address.go:129`). PR 10 would add `SkillsEndpoint()`, `ActiveExecutionsEndpoint()`, `TracesEndpoint()`, etc. following the same pattern, or a general `Endpoint(path string)` method.
- `Credential` interface (`client.go:23-25`) has `Apply(*http.Request) error`. The `target.Scope` holds a `credential` field (private) obtained from `target.Context`'s credential provider; any new upstream GET methods would need to apply the credential the same way `Probe` does.
- `Failure` classification (`errors.go:5-13`): `FailureAuthentication`, `FailureAccess`, `FailureIncompatible`, `FailureUnavailable`, `FailureProtocol`. `TransportCategory` (`errors.go:15-30`): 12 categories including `dns`, `connection`, `timeout`, `tls_*` variants, `redirect`, `namespace_not_found`, `upstream_server`, `upstream_protocol`.
- `problem.go:9-36` maps upstream HTTP status codes to `Failure` types: `401 + BIFROST_API_KEY_REJECTED` -> `FailureAuthentication`; `401/403` without that code -> `FailureAccess`; `404` -> `FailureUnavailable/CategoryNamespaceNotFound`; `5xx` -> `FailureUnavailable/CategoryUpstreamServer` (retryable); other -> `protocolFailure()`.
- `applicationclient/client_test.go:68-87` already consumes `bifrost-console-fixtures/application-rest/instance-status.json` for compatibility testing.
- No methods exist yet for `GET /skills`, `/active-executions`, or `/traces`. The `Client` struct has `address`, `expectedVersion`, `requestTimeout`, `http`, and `transport` fields — all the infrastructure needed for new GET methods, but `maxResponseBytes = 64 KiB` may need to be increased for larger paginated responses (the Phase 2 design specifies a 16 MiB uncompressed JSON response limit, `bifrost_console_phase_2_ui_console.md:474`).

#### 3.2 `target` — selected target lifecycle

- `target/context.go:54-71` defines `Context` with mutex, factory, scope ID source, credentials, current state, registered owners, retry logic, etc.
- `ScopeOwner` interface (`context.go:29-31`) and `RegisterOwner` (`context.go:94-110`) provide the seam by which services can be notified when a target scope is invalidated; this is the intended integration point for operational-view services that cache per-scope data.
- `Context.Capture()` (`context.go:238-252`) returns a `Scope` with `ID`, `Context`, `Target`, `InstanceID`, `client` (private `ProbeClient`), and `credential`. A service holding a `Scope` can call `scope.Probe()` (`scope.go:22-33`) or extend it with new GET methods.
- `Context.Snapshot()` (`context.go:277-289`) returns the side-effect-free `StatusSnapshot` already shown in the browser Overview.
- `Context.RequireCurrent(scopeID)` (`context.go:263-275`) and `IsCurrent` (`context.go:254-261`) are the helpers for rejecting stale-scope results.
- `credentials.go` (not read in full) stores the application key in process memory.

#### 3.3 `browserapi` — HTTP handler layer

- `browserapi/router.go:36-75` currently only dispatches `POST` routes under `/api/console/v1/`: pairing, bootstrap, tab release/heartbeat, and target status/connect/credential/recheck.
- `browserapi/target.go:27-97` implements the target handlers, using `target.Context` and returning a `targetDTO` containing `address`, `unencrypted`, and `status`.
- `browserapi/errors.go:26-40` provides `writeError` and `writeJSON`; `target.go:99-131` (`writeDomainError`) maps `consolecore.Error` codes to HTTP status:
  | Code | HTTP Status |
  |---|---|
  | `INVALID_ARGUMENT`, `INVALID_CURSOR` | 400 |
  | `TARGET_AUTHENTICATION_REQUIRED` | 401 |
  | `TARGET_ACCESS_BLOCKED` | 403 |
  | `NOT_FOUND` | 404 |
  | `INCOMPATIBLE_TARGET`, `TARGET_CHANGED`, `STALE_CURSOR`, `ARTIFACT_EXPIRED`, `LIVE_MONITORING_UNAVAILABLE` | 409 |
  | `INVALID_ARTIFACT` | 422 |
  | `LIMIT_EXCEEDED` | 429 |
  | `TARGET_UNAVAILABLE`, `LOCAL_STORAGE_UNAVAILABLE` | 503 |
  | `CONSOLE_ERROR` (default) | 500 |
- `browserapi/headers.go:7-15` (`ApplyHeaders`) sets CSP, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Permissions-Policy` (camera, microphone, geolocation, payment, usb all `()`), `Cross-Origin-Opener-Policy: same-origin`, `Cross-Origin-Resource-Policy: same-origin`.
- `browserapi/router.go:36-76` dispatches all routes as `POST` only; `withSession` (`router.go:120-137`) handles session cookie authentication and optional CSRF validation (tab ID + CSRF token headers). Read-only operational-view endpoints would likely use `withSession` with `csrf=false` since they don't change state.
- `browserapi/request_policy.go:15-69` enforces same-origin/loopback host and origin validation; `webhost/routes.go:11-32` forwards `/api/console/v1/**` to the `browserapi` handler and serves the embedded Vite SPA for everything else.
- No console endpoints yet exist for skills, active executions, or traces.

#### 3.4 `console` / `webhost` — runtime wiring

- `console/service.go:82-88` builds the `target.Context` using `applicationclient.New` as the client factory.
- `console/service.go:150-177` constructs `browserapi.Options` with `Policy`, `Pairing`, `Sessions`, `ProcessID`, `Workspace`, `PairingURL`, `PrintPairing`, and `Target`, then passes the resulting handler to `webhost.Routes`.
- `webhost/host.go:34-103` runs an `http.Server` on a loopback address; `webhost/static.go:20-68` serves embedded assets with content-addressed immutable cache headers and a strict CSP (`static.go:71-83`).

### 4. Frontend state/routing patterns

- **Build stack**: `bifrost-console/web/package.json` pins React `19.2.8`, React Router `8.3.0`, Tailwind `4.3.3`, `react-aria-components`, Vite `8.1.5`, Vitest, and Playwright.
- **Entry point**: `web/src/main.tsx:13-19` renders `StrictMode`, `BrowserSessionProvider`, and `RouterProvider` with `browserRouter()`.
- **Routing**: `web/src/app/routes.tsx:6-16` currently defines only `/` (index `Overview`) and `*` (`NotFound`). New routes for `/skills`, `/skills/:registeredName`, `/active-executions`, `/active-executions/:sessionId`, `/traces`, `/traces/:traceId` would be added here.
- **Session state**: `web/src/security/BrowserSessionProvider.tsx:68-207` handles pairing, bootstrap, tab heartbeat, release, and target-operation callbacks. It keeps `tabId` and `csrfToken` in a ref (`currentSecurity`) and passes them as headers to protected endpoints.
- **Target state**: `web/src/target/TargetProvider.tsx:27-104` uses `useReducer(targetReducer, ...)` and exposes `target`, `error`, `scopeGeneration`, `connect`, `credential`, `recheck`, `refresh`. It navigates to `/` when the scope changes (`TargetProvider.tsx:46`).
- **Reducer**: `web/src/target/targetReducer.ts:15-40` supports `replace`, `error`, `clear-error`, increments `generation` on scope change, and preserves errors only when they belong to the current scope.
- **Overview UI**: `web/src/target/Overview.tsx:36-158` is a form-driven status view with `status-grid` facts, transport-guidance mapping, target-connect/credential/recheck forms, and focus management. It is the model for read-only presentation states (loading, empty, unavailable, authentication, compatibility, stale-scope) required by PR 10.
- **API client**: `web/src/api/client.ts:27-107` exports only `post<T>` plus the pairing/session/target functions. `web/src/api/contracts.ts:1-82` defines `BrowserErrorCode`, `TargetStatus`, `TargetResponse`, `BrowserErrorDetails`, `ErrorEnvelope`, `BootstrapResponse`, `PairingLinkResponse` — but no skill/active/trace DTO types yet.
- **Tests**: `web/src/api/client.test.ts` uses `vi.stubGlobal("fetch", ...)` and asserts exact URL, method, headers, and body. `web/src/target/Overview.test.tsx` mocks `useTarget` and checks rendering, form interaction, and error guidance. These are the existing test patterns PR 10 should follow.

### 5. Fixtures and canonical JSON

- `bifrost-console-fixtures/README.md:21-35` says `application-rest/` contains deterministic REST bodies produced by Java. PRs 11–13 consume these fixtures; PR 10 should use them as the source of truth for shape and compatibility.
- Existing fixtures relevant to PR 10:
  - `instance-status.json` — `consoleCompatibilityVersion: "0.1.0-SNAPSHOT"`, counts, TTLs.
  - `skills-page.json` — `Page<SkillSummary>` with `href: "skills/CheckDns"`.
  - `skill-detail.json` — `SkillDetail` with raw `yaml` text.
  - `active-executions-page.json` — `ActivePage` with `resumeCursor: "9"`.
  - `active-execution-detail.json` — full `ActiveExecution`.
  - `traces-page.json` — `Page<Trace>`.
  - `trace-detail.json` — `Trace`.
  - `continuation-page.json` — `Page<SkillSummary>` with `hasMore: true` and a non-null `nextCursor`.
  - `empty-page.json` — `{"items":[],"hasMore":false,"nextCursor":null,"observedAt":"..."}`.
  - `problem-*.json` — upstream problem bodies (`BIFROST_API_KEY_REJECTED`, `INVALID_CURSOR`, `STALE_CURSOR`, `NOT_FOUND`, `LIMIT_EXCEEDED`, etc.).

### 6. Framework design lens and contract classification

- `ai/thoughts/framework-feature-design-lens.md:23-38` lists six categories: Application API, Supported SPI, Configuration/manifest, Persisted/serialized, Ephemeral diagnostic, Internal/accidentally exposed. The console observability REST endpoints and NDJSON traces are ephemeral diagnostic formats, not supported public API.
- The lens requires recording evidence of protected consumers and distinguishing technical exposure from deliberately supported contracts. For PR 10, the protected consumers are the browser and future MCP; the Java observability endpoints are deliberately provided but not a public application API.
- Cross-component coordination is required whenever REST, SSE, acquisition, problem, or consumed-NDJSON meaning changes (`framework-feature-design-lens.md:34` and `2026-07-23-bifrost-console-implementation-roadmap.md:145-148`). PR 10 changes the Go consumer of the Java REST contract, so Java endpoints, Go application client, fixtures, and tests should be updated atomically if the meaning changes.
- Phase 2 design doc (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:197-203`) divides state:
  - **Bifrost app**: authoritative skill catalog, active executions, activity stream, trace catalog.
  - **Go console**: selected target, credential, scope, one upstream SSE, bounded recent-activity window, per-tab relay, browser pairing, transient trace cache.
  - **Browser**: rendered live view, navigation, filters, selection, expansion.
  Go services must not become a second authoritative store for executions or traces (`bifrost_console_phase_2_ui_console.md:1047`).

## Code References

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiPaths.java:5-11` — REST route namespace.
- `ObservabilityRestController.java:54-249` — all Phase 1 observability GET handlers.
- `ObservabilityDtos.java:18-107` — DTO records for instance, skills, active executions, traces, pages, and SSE handshake.
- `ObservabilityDtoMapper.java:15-79` — mapping from Java runtime catalog/runtime objects to DTOs.
- `ObservabilityCursorCodec.java:17-102` — opaque keyset cursor encoding/decoding rules.
- `ObservabilityAccessService.java:8-11` — required authorities for each operation.
- `BifrostObservabilityWebAutoConfiguration.java:40-127` — Spring bean wiring.
- `BifrostProperties.java:328-364` — `bifrost.observability.*` configuration contract.
- `bifrost-console/internal/applicationclient/client.go:34-166` — existing `Client.Probe` and response validation.
- `bifrost-console/internal/applicationclient/address.go:84-87,126-130` — observability root and instance endpoint computation.
- `bifrost-console/internal/target/context.go:29-289` — `ScopeOwner`, `Capture`, `Snapshot`, `IsCurrent`, `RequireCurrent`, `RegisterOwner`.
- `bifrost-console/internal/target/scope.go:13-33` — `Scope` value and `Probe`.
- `bifrost-console/internal/browserapi/router.go:36-75` — current browser API route dispatch.
- `bifrost-console/internal/browserapi/target.go:18-131` — target response DTO and handlers.
- `bifrost-console/internal/browserapi/errors.go:26-40` — shared JSON envelope helpers.
- `bifrost-console/internal/consolecore/status.go:45-89` — `StatusSnapshot` and validation.
- `bifrost-console/internal/consolecore/errors.go:7-64` — shared error codes and `Details`.
- `bifrost-console/internal/console/service.go:82-177` — runtime wiring of `target.Context`, `browserapi.Options`, and `webhost.Routes`.
- `bifrost-console/internal/webhost/routes.go:11-32` — API/static route split.
- `bifrost-console/web/src/app/routes.tsx:6-16` — current route tree (`/` + `*`).
- `bifrost-console/web/src/security/BrowserSessionProvider.tsx:68-207` — session, bootstrap, heartbeat, and target-operation callbacks.
- `bifrost-console/web/src/target/TargetProvider.tsx:27-104` — target context and reducer usage.
- `bifrost-console/web/src/target/targetReducer.ts:15-40` — target state transitions.
- `bifrost-console/web/src/target/Overview.tsx:36-158` — current Overview view and status/error presentation.
- `bifrost-console/web/src/api/client.ts:27-107` — current browser API client (`post<T>` and target functions).
- `bifrost-console/web/src/api/contracts.ts:1-82` — current browser TypeScript contracts.
- `bifrost-console-fixtures/application-rest/` — canonical REST fixtures for skills, active executions, traces, and problems.
- `bifrost-console-fixtures/README.md:21-35` — fixture semantics and release-model notes.

### 7. Phase 2 design doc specifics for PR 10's four UI areas

The Phase 2 design doc (`bifrost_console_phase_2_ui_console.md:926-974`) defines candidate information for each area:

- **Instance Overview** (`:928-939`): target name and address; application `instanceId`; `consoleCompatibilityVersion`; connection, authentication, and transport state; registered skill count; active execution count; trace count cataloged by the current application instance; completion grace TTL, application trace-catalog metadata TTL, and trace-retention policy with an explicit explanation that catalog metadata and core file retention are independent and neither provides cross-restart console history.
- **Skill Catalog** (`:941-947`): displays unique registered skill name and normalized skills-root-relative `sourcePath`. Skill detail displays unchanged UTF-8 YAML content from the application. Does not display effective-definition DTO, parsed defaults, resolved model connections, provider identifiers, compiled evidence contracts, Java objects, or registration facts. `sourcePath` is descriptive metadata for display, grouping, and ordering — never treated as a filesystem locator, joined to a local path, or accepted as arbitrary file input. Go follows the server-generated link keyed by the registered skill name. Go and the browser may syntax-highlight or search the YAML as text but do not normalize, reserialize, or maintain an authoritative parsed skill model. The same transport-neutral skill file is available to the future MCP adapter. The UI must not present recent execution failure as proof that the skill itself is unhealthy.
- **Active Executions** (`:949-974`): candidate information includes entry skill; session and trace identifiers; start time and elapsed time; current phase and active skill path; execution status; invocation and usage counts; configured limits; latest concise activity summary. Go may refresh one selected active execution through Phase 1 lookup by `sessionId`. That lookup returns the same bounded current registry snapshot, not event history, active trace, or frame tree. Once an execution leaves the registry, lookup returns `NOT_FOUND`. Detailed frames, records, and payloads are not available for an active execution — they become inspectable after its trace is finalized. The UI must keep execution outcome separate from diagnostic artifact availability and should not invent combined states such as "completed with trace" or expose a `FINALIZING` execution phase.
- **Trace Catalog browsing**: The trace catalog is the application's current-process catalog of finalized traces. The Phase 2 design doc (`:691-720`) describes the trace explorer as hierarchy-first with four coordinated views (hierarchy, timeline, usage, records). PR 10 delivers only the catalog browsing (list + detail), not the full explorer. The `Trace` DTO (`traceId, sessionId, outcome, finalizedAt, sizeBytes, persistencePolicy, applicationTraceExpiresAt`) from `traces-page.json` and `trace-detail.json` fixtures defines the shape. The trace explorer itself is PR 14.

### 8. Collection pagination through Go

The Phase 2 design doc (`bifrost_console_phase_2_ui_console.md:472-476`) specifies:
- Go consumes the Phase 1 keyset-pagination contract for trace, active-execution, and skill-summary collections.
- Go should normally request large pages and must not turn application defaults into many tiny upstream requests.
- Starting application and local-browser collection defaults: **1,000 items**.
- Maximum requested size: **5,000 items**.
- One uncompressed JSON response is limited to **16 MiB**.
- Go preserves `hasMore`, current-scope identity, observation time, and explicit stale-cursor behavior.
- Browser DTOs may adapt cursor representation but must not convert keyset traversal into offset pagination or imply a transactional collection snapshot.
- The browser network page size is independent of visible row count — it may receive thousands of concise summaries while rendering only a virtualized visible window.
- A target-scope reset invalidates every local continuation and reloads the application.

### 9. Transport-neutral Go service error contract

The Phase 2 design doc (`bifrost_console_phase_2_ui_console.md:380-413`) defines the shared error contract used by all transport-neutral services below browser and MCP adapters:
- A service error has a stable `code`, safe human-readable `message`, the operation's `targetScopeId` when target-specific, and only bounded code-specific details.
- Adapters and callers branch on `code`, never on message text or internal Go error strings.
- The contract has no universal `retryable`, `requiredAction`, `restartRequired`, `configurationRequired`, or `evidenceAvailable` fields — recovery semantics stay with the specific code.
- Phase 1 problems map into this contract before reaching either adapter: `BIFROST_API_KEY_REJECTED` -> `TARGET_AUTHENTICATION_REQUIRED`; generic `401/403` -> `TARGET_ACCESS_BLOCKED`; connection/TLS/timeout/`APPLICATION_ERROR` -> `TARGET_UNAVAILABLE`; compatibility mismatch -> `INCOMPATIBLE_TARGET`; `INVALID_REQUEST` -> `INVALID_ARGUMENT`; cursor, not-found, live-monitoring, and `LIMIT_EXCEEDED` codes retain their Go meanings.
- `CONSOLE_ERROR` is the final Go-owned fallback, not a wrapper for errors that already have a specific code.
- Browser HTTP handlers map shared errors to HTTP status codes and a common local error envelope preserving `code`, safe `message`, optional `targetScopeId`, and bounded details. HTTP status is coarse transport; browser behavior is keyed by the shared code.

### 10. Browser and network safety requirements

The Phase 2 design doc (`bifrost_console_phase_2_ui_console.md:1064-1088`) enumerates 13 safety requirements:
1. Render diagnostic strings as text by default; never inject trace or model content as HTML.
2. Do not render diagnostic content as Markdown or embedded HTML in the initial release.
3. Establish a restrictive CSP suitable for the embedded SPA.
4. Prevent framing and MIME sniffing; use restrictive referrer policy.
5. Do not construct executable links or resource URLs directly from trace payloads.
6. Treat downloaded traces as attachments with safe filenames and explicit content types.
7. Bound JSON/SSE message sizes, decompression, nesting, list rendering, and retained in-memory event state.
8. Ensure malformed or oversized records fail one view or request safely rather than destabilizing the console process.
9. Never log credentials, pairing secrets, authorization headers, raw sensitive payloads, or URLs containing user information.
10. Keep browser pairing, host/origin validation, and loopback binding independent.
11. Require session-bound CSRF header for target/credential changes, MCP enablement/disablement, MCP-key reveal/regeneration, and other sensitive operations.
12. Keep CSRF token in browser memory only; apply `Cache-Control: no-store` to all authenticated diagnostic and credential-management responses.
13. Keep browser and MCP route authentication and request validation fail-closed and non-interchangeable.

### 11. Phase 2 invariants relevant to PR 10

The Phase 2 design doc (`bifrost_console_phase_2_ui_console.md:1040-1062`) lists 22 invariants. Those most directly relevant to PR 10:
- **#1**: The browser never depends directly on Bifrost application endpoints.
- **#7**: The console does not become a second authoritative store for executions or traces.
- **#9**: The product remains observational and read-only initially.
- **#11**: Browser handlers adapt transport-neutral Go services; they do not own runtime semantics needed by MCP.
- **#12**: Every target-specific response and event follows the authoritative `TargetContext` scope snapshot; stale scopes are discarded.
- **#15**: Application authentication is enforced for each new upstream acquisition, while complete evidence already admitted into Go remains governed by local authentication and its normal scope, handle, capacity, and process lifecycle.
- **#17**: The production browser assets and Go browser API ship atomically in one executable. No browser/API version negotiation.
- **#19**: Browser and MCP status adapt from the same side-effect-free `ConsoleStatusSnapshot` of independent target, identity, and live-monitoring facts.
- **#20**: Go, browser handlers, and browser state do not become execution-lifecycle owners. They consume Phase 1 snapshots, activity, and catalog results.

## Architecture Documentation

- **Transport-neutral services below browser handlers**: The Phase 2 plan expects Go to own transport-neutral services that fetch from the selected target, while the browser adapts them without defining runtime meaning (`bifrost_console_phase_2_ui_console.md:81`, `bifrost_console_phase_2_ui_console.md:1047`, `bifrost-console-pr-10-operational-views.md:27`).
- **Target scope invalidation**: `target.Context` is the single authority for target identity and scope rotation. Any operational-view service that caches upstream data should implement `ScopeOwner` and register with `Context.RegisterOwner` so stale work is cancelled when the target changes (`target/context.go:29-110`).
- **Error contract**: `consolecore.Error` and `consolecore.Details` are the shared transport-neutral errors; `browserapi` maps them to HTTP status and a JSON envelope (`browserapi/target.go:99-131`, `consolecore/errors.go`).
- **Pagination model**: Java uses opaque Base64-URL JSON cursors with `highWater`, `beforeOrdinal`, and `afterName`; pages include `hasMore` and `observedAt`. Go services should preserve the cursor opaquely and should not reinterpret `highWater` semantics.
- **Frontend patterns**: API calls are typed `post<T>` wrappers around `fetch`; state is managed with `useReducer` in provider components; routes are declared in `app/routes.tsx`; CSRF/tab headers are added by `client.ts`; pairing/session state is in `BrowserSessionProvider`.
- **Security baseline**: CSP, frame options, referrer policy, permissions policy are applied to all browser-visible responses (`webhost/static.go:71-83`). Application content is treated as untrusted and must not trigger server-side execution or filesystem access (`framework-feature-design-lens.md:64-68`).

## Historical Context (from ai/thoughts/)

- `ai/thoughts/tickets/bifrost-console-pr-10-operational-views.md` — PR 10 brief: read-only views, shared services, pagination, YAML display, presentation states.
- `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md` — Phase 2 dependency chain and cross-cutting invariants; PR 10 is the first target-facing read-only UI after PR 09.
- `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md` — settled Phase 2 design: state ownership split, browser builds on transport-neutral Go services, Vite/SPA baseline, theme/focus/keyboard requirements.
- `ai/thoughts/framework-feature-design-lens.md` — contract classification, no second authoritative store, and atomic update rule for REST/SSE/acquisition/NDJSON boundaries.

## Related Research

- None found in `ai/thoughts/research/` at the time of writing.

## Open Questions

1. **Service placement**: Where should PR 10 place the new transport-neutral Go query services? The Phase 2 design doc (`bifrost_console_phase_2_ui_console.md:143`) states `internal` contains "transport-neutral services plus browser and MCP adapters" but leaves "the exact internal package subdivision" as an implementation detail. A new `internal/services` or `internal/operations` package would keep `browserapi` thin and allow reuse by future MCP adapters.
2. **Client extension vs. separate service**: Should `applicationclient.Client` grow `Skills(...)`, `ActiveExecutions(...)`, `Traces(...)`, `Skill(...)`, `ActiveExecution(...)`, `Trace(...)` methods, or should a separate `query` service own the upstream GET calls and re-use `Client` as an HTTP transport? The existing `Client` already knows address, version, timeouts, TLS, and credential application. However, `maxResponseBytes = 64 KiB` (`client.go:20`) is insufficient for paginated responses — the Phase 2 design specifies a 16 MiB limit (`bifrost_console_phase_2_ui_console.md:474`). Either `Client` needs a configurable response size limit per endpoint, or a separate service should own its own HTTP transport with appropriate limits.
3. **ScopeOwner registration**: How should operational-view services register as `ScopeOwner`s with `target.Context` and cancel in-flight requests when the scope rotates? `RegisterOwner` is callable before `StartServing` (`context.go:94-110`); the design should decide which services register and when. The Phase 2 design doc (`:331`) states that "status handlers, the upstream SSE connection manager, trace acquisition, analysis services, browser handlers, and MCP handlers must not independently adopt a target" — they all consume `TargetContext` scope snapshots.
4. **Browser API transport for pagination**: How will the browser pass pagination `cursor` and `pageSize` to the Go console? The current `browserapi` only accepts `POST` with JSON bodies (`router.go:47-51`). The Phase 2 design doc (`:421-424`) specifies REST for "paired bootstrap, target and compatibility status, current snapshots, skills, current-process trace discovery, trace queries, and sensitive local operations." PR 10 could either add `POST /api/console/v1/skills/list`, `POST /api/console/v1/active-executions/list`, `POST /api/console/v1/traces/list` endpoints with JSON bodies containing `cursor` and `pageSize`, or extend the router to accept `GET` with query parameters. The existing `POST`-only pattern with `decodeJSONLimit` and `DisallowUnknownFields` is the established convention.
5. **Go DTO types**: Which Go DTO types are needed to mirror `ObservabilityDtos`, and can they be generated or kept as plain structs matching the JSON fixtures? The Phase 2 design doc (`:1111`) states: "The initial implementation uses hand-authored boundary DTOs and executable cross-boundary fixtures without adding a schema or code-generation system." The fixture corpus in `bifrost-console-fixtures/application-rest/` serves as the contract test input.
6. **YAML rendering**: How will the browser display `sourcePath` and raw `yaml` without making them executable or clickable? The Phase 2 design doc (`:943-945`) states: "Go and the browser may syntax-highlight or search the YAML as text but do not normalize, reserialize, or maintain an authoritative parsed skill model." The safety requirements (`:1070-1071`) state: "Render diagnostic strings as text by default; never inject trace or model content as HTML" and "Do not render diagnostic content as Markdown or embedded HTML in the initial release." The exact rendering component is not yet designed.
7. **Overview enrichment**: The current `Overview.tsx` shows only `StatusSnapshot` fields from `target.Context.Snapshot()`. PR 10's Overview needs additional instance facts (registered skill count, active execution count, cataloged trace count, TTLs, persistence policy) from the Java `/instance` endpoint. The `applicationclient.Instance` struct (`client.go:42-47`) currently captures only `InstanceID`, `ConsoleCompatibilityVersion`, `ObservedAt`, `LiveMonitoringAvailable` — it does not capture the counts and TTLs from `instance-status.json`. Should `Instance` be extended, or should a separate instance-status query service fetch the full `InstanceStatus` DTO?
8. **Active executions `resumeCursor`**: The `active-executions-page.json` fixture includes a `resumeCursor` field unique to `ActivePage`. The Phase 2 design doc (`:458`) explains: "The first active-execution page captures a registry high-water mark and includes that `instanceId`, a `resumeCursor` observed near baseline collection, and an `observedAt` time." PR 10 delivers only read-only snapshots, but the `resumeCursor` must be preserved in the Go DTO and browser response for PR 11's SSE connection to use.
