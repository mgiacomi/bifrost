# PR 10 — Read-Only Operational Views Implementation Plan

## Overview

Add transport-neutral Go query services for the Phase 1 observability REST contract and expose them through the existing browser API, then build the first read-only browser views: **Instance Overview**, **Skill Catalog** (list + detail), **Active Executions** (list + detail snapshots), and **Trace Catalog** (list + detail). This PR depends on PR 09 (`TargetContext`) and provides the shared service seam that PR 11 (live activity), PR 12 (artifact acquisition), PR 13 (trace analysis), PR 14 (trace explorer), and PR 15 (diagnostic workflows) will reuse.

## Current State Analysis

- **Java Phase 1 contract is complete** under `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/`:
  - `ObservabilityApiPaths.java:5-11` defines `/_bifrost/observability/v1` routes.
  - `ObservabilityRestController.java:54-249` implements `GET /instance`, `/skills`, `/skills/{registeredName}`, `/active-executions`, `/active-executions/{sessionId}`, `/traces`, `/traces/{traceId}`, and `/traces/{traceId}/artifact`.
  - `ObservabilityDtos.java:18-84` defines `InstanceStatus`, `SkillSummary`, `SkillDetail`, `ActiveExecution`, `Trace`, `Page<T>`, and `ActivePage`.
- **Go console has the transport and security foundation**:
  - `applicationclient.Client.Probe` (`client.go:89-130`) can call `GET /instance` but is hard-coded to a 64 KiB response body (`maxResponseBytes = 64 * 1024`, `client.go:20`) and has no generic paginated GET.
  - `target.Context` (`context.go:94-110`) provides `ScopeOwner` registration, `Capture()`/`Snapshot()`/`RequireCurrent()`, and scope invalidation.
  - `browserapi.Router` (`router.go:47-75`) is POST-only, with session/CSRF wrappers and existing target status/connect/credential/recheck routes.
  - `web/src/app/routes.tsx:6-16` has only `/` (Overview) and `*` (NotFound); `web/src/api/client.ts:27-107` has only `post<T>` and target operations.
- **Canonical fixtures exist** in `bifrost-console-fixtures/application-rest/`:
  - `instance-status.json`, `skills-page.json`, `skill-detail.json`, `active-executions-page.json`, `active-execution-detail.json`, `traces-page.json`, `trace-detail.json`, `continuation-page.json`, `empty-page.json`, and `problem-*.json`.

## Desired End State

After this PR:

1. `applicationclient.Client` has a bounded, generic `Get` and `Address` exposes Phase 1 endpoint URLs.
2. `target.Scope` exposes a scope-bound `Upstream` call that applies credentials, checks `X-Bifrost-Instance-Id`, and maps cancellation/identity errors to shared `consolecore` errors.
3. A new `internal/observability` package owns transport-neutral query services for instance status, skills, active executions, and traces; it returns hand-authored DTOs and shares `consolecore` domain-error codes.
4. `browserapi` adds read-only POST routes that adapt those services and return browser DTOs.
5. The React app adds routes, API client functions/contracts, an `ObservabilityProvider`, and components for the four operational views.
6. Tests exercise the new Go client methods, the observability service against fixtures, the browser API routes, and the frontend API/components.

### Key Discoveries

- `applicationclient.Client` already handles TLS, redirects, compression, and credential application; the only missing piece is a generic GET with a configurable response bound (`client.go:17-130`).
- `target.Context.RegisterOwner` is available before `StartServing` (`context.go:94-110`), but PR 10's query services do not retain cross-request per-scope state, so they do not need to register a `ScopeOwner` yet.
- The browser router is strictly POST (`router.go:47-51`); the new read-only routes should follow the same POST-with-JSON-body pattern to reuse `decodeJSONLimit` and session validation.
- `maxResponseBytes = 64 * 1024` (`client.go:20`) is too small for paginated responses; Phase 2 specifies a 16 MiB uncompressed JSON response limit (`bifrost_console_phase_2_ui_console.md:474`).
- `applicationclient/problem.go:9-36` currently maps only by HTTP status and `BIFROST_API_KEY_REJECTED`; it must be extended to recognize `INVALID_REQUEST`, `INVALID_CURSOR`, `STALE_CURSOR`, `NOT_FOUND`, `LIMIT_EXCEEDED`, and `LIVE_MONITORING_UNAVAILABLE` (`bifrost-console-fixtures/application-rest/problem-*.json`).
- The existing `writeDomainError` mapping in `browserapi/target.go:99-131` already handles `INVALID_ARGUMENT`, `INVALID_CURSOR`, `STALE_CURSOR`, `NOT_FOUND`, `LIMIT_EXCEEDED`, `LIVE_MONITORING_UNAVAILABLE`, `TARGET_UNAVAILABLE`, and `TARGET_CHANGED`, so no new HTTP mapping is needed.

## What We’re NOT Doing

- **Live SSE activity relay** (PR 11).
- **Artifact acquisition, streaming, trace storage, or raw download** (PR 12).
- **Trace parsing, indexing, hierarchy/timeline/usage calculations** (PR 13).
- **Full trace explorer with frames, records, payloads, and deep links** (PR 14).
- **Diagnostic workflow hardening, Playwright E2E, release packaging** (PR 15).
- **MCP adapter surface** (PRs 16–19).
- Skill YAML parsing, model-selection display, effective-definition DTOs, or `sourcePath` as a filesystem locator.
- Historical/cross-restart trace recovery; views are current-process, best-effort snapshots.
- Virtualized rendering; simple paginated “load more” is sufficient for the initial read-only views.

## Skill-Authoring Documentation Impact

**Impact:** No impact.

- **Rationale:** PR 10 is a console observability feature. It does not change skill manifest syntax, YAML validation, `bifrost.*` configuration, model selection, evidence contracts, or any other author-facing behavior. The skill catalog displays only the already-public registered skill name, `sourcePath`, and raw YAML text from the application; it does not expose registration internals, parsed defaults, or provider wiring.
- **Documents to update:** None.
- **Supporting evidence:** `ai/skill-authoring/README.md` coverage table; `bifrost-console-fixtures/application-rest/skill-detail.json`; `bifrost_console_phase_2_ui_console.md:943-945`.
- **Coverage table update:** Not required.
- **LLM-first usability:** Not applicable — no skill-authoring guidance is added or changed.

## Contract and Compatibility Impact

| Surface | Classification and supporting evidence | Planned compatibility treatment |
| --- | --- | --- |
| Application API | No impact. The Java observability endpoints are deliberately internal diagnostic surfaces, not supported application-developer APIs. | Not applicable. |
| Supported SPI | No impact. No framework SPI is added or changed. | Not applicable. |
| Configuration and manifest contracts | No impact. No `bifrost.*` properties or YAML skill syntax change. | Not applicable. |
| Persisted or serialized contracts | No impact. The trace catalog and skill catalog are current-run, in-memory observability data. | Not applicable. |
| Ephemeral diagnostic formats | Affected. The Java observability JSON pages and DTOs (`ObservabilityDtos.java:18-84`) are consumed by Go and re-exposed to the browser. `bifrost-console-fixtures/application-rest/` is the cross-boundary fixture corpus. | Preserve current-run coherence; Go must not reinterpret cursors or convert keyset pagination to offset pagination. Browser DTOs use opaque cursors. |
| Internal or accidentally exposed implementation | Affected. New `applicationclient.Client.Get`, `target.Scope.Upstream`, `internal/observability`, new `browserapi` routes, new React types/routes, and new tests are all internal to the console executable. | Atomic update of Go client, services, browser API, fixtures, and tests. No shim because there is no supported external consumer. |

- **Evidence of supported contracts:** None; all affected surfaces are internal or ephemeral diagnostic.
- **Intended breaks:** None.
- **In-repository consumers to update:** `applicationclient`, `target`, `browserapi`, `console/service.go`, `web/src/api/*`, `web/src/app/*`, `web/src/observability/*`, and tests/fixtures under `bifrost-console`.
- **Public-surface delta:** Internal only. New Go internal packages, methods, and React files; no supported Java or Go public API.
- **Shim decision:** **No shim.** All consumers are in the same repository and the same executable; obsolete paths are removed atomically.
- **Java-to-Go boundary coordination:** **Required for verification.** PR 10 is a new Go consumer of the existing Java Phase 1 REST contract. The Java endpoints, fixtures, Go DTOs, and Go tests must agree on request/response shape and problem-code semantics. No Java contract change is expected; any discrepancy must be resolved atomically in the same PR.

## Implementation Approach

This plan resolves the eight open questions from the research document as follows. Each resolution was verified against future PR tickets (11–19) and the implementation roadmap to ensure downstream compatibility:

1. **Service placement:** Create `internal/observability` for transport-neutral query services and DTOs. It sits below `browserapi` and the future MCP adapter. Confirmed by PR 17: *"adapting the same transport-neutral services used by the browser"* for skills, executions, and activity; PR 18 does the same for traces.
2. **Client extension vs. separate service:** Extend `applicationclient.Client` with a bounded, generic `Get` method used by `target.Scope.Upstream` and the observability services. This reuses the existing TLS, redirect, compression, and credential infrastructure. Confirmed by PR 17 guardrail: *"MCP does not contact the application directly or own an upstream subscription."*
3. **ScopeOwner registration:** PR 10 services do not cache per-scope state, so they do not register as `ScopeOwner`. Each request captures a fresh `target.Scope`; scope cancellation and `X-Bifrost-Instance-Id` mismatch provide stale-scope rejection. PR 11 will need `ScopeOwner` for its SSE connection and activity window; PR 12 for artifact cache lifecycle.
4. **Browser API transport for pagination:** Keep the existing POST-only pattern. Add `POST /api/console/v1/...` routes with JSON bodies containing `cursor`/`pageSize` or resource identifiers. This reuses `decodeJSONLimit`, `withSession`, and CSRF handling. PR 17 uses MCP tool calls (`bifrost_list_skills`, etc.), not REST, so the browser API format is independent of MCP.
5. **Go DTO types:** Use hand-authored Go structs and a generic `Page[T]` matching the canonical fixtures, per `bifrost_console_phase_2_ui_console.md:1111`. PR 17 confirms: *"Browser and MCP mappings preserve identical Bifrost identifiers, calculations, availability facts, limitations, and shared domain-error codes"* — the same Go structs serve both adapters.
6. **YAML rendering:** Render `sourcePath` and the raw `yaml` field as plain text inside a `<pre>` element with `white-space: pre-wrap`; never as HTML, Markdown, or executable links. Confirmed by PR 19: *"sourcePath is not a local filesystem locator or provenance claim"*; PR 17: *"Skill YAML and activity content are untrusted returned data, never instructions to the server."*
7. **Overview enrichment:** The current `applicationclient.Instance` struct captures only `InstanceID`, `ConsoleCompatibilityVersion`, `ObservedAt`, `LiveMonitoringAvailable`. Rather than extending it, the new `observability.Service.GetInstance` method fetches the full `InstanceStatus` DTO (with counts, TTLs, persistence policy) from the Java `/instance` endpoint. Confirmed by PR 16: `bifrost_get_runtime` uses the shared `StatusSnapshot` (local target state), not the full `InstanceStatus` — these are distinct concepts.
8. **Active executions `resumeCursor`:** The `ActivePage` Go DTO embeds `Page[ActiveExecution]` and adds `ResumeCursor *string`. This preserves the field for PR 11's SSE connection, which uses it to establish the activity stream baseline. Confirmed by PR 11: *"Maintain one upstream SSE connection for the selected target scope"* and *"Store one bounded recent-activity window with cursor range, observation time, and explicit gap/reset facts."*

The backend flow is: browser handler → `observability` service → `target.Scope.Upstream` → `applicationclient.Client.Get` → Java Phase 1 endpoint. Error mapping flows back through `applicationclient.Failure` → `consolecore.Error` → `browserapi` envelope. Pagination defaults to 1,000 items per request, clamps to a 5,000 maximum, and respects a 16 MiB per-response bound.

## Phase 1: Upstream Transport and Scope Query Seam

### Overview

Give `applicationclient.Client` a generic bounded GET, extend `Address` with Phase 1 endpoint URLs, update problem-code mapping, and add `target.Scope.Upstream` so higher-level services can call the application without duplicating transport logic.

### Changes Required:

#### 1. `applicationclient/client.go`

**File:** `bifrost-console/internal/applicationclient/client.go`
**Changes:**
- Extract request construction, header validation, and bounded body reading from `Probe` into shared unexported helpers.
- Add `Get(parent context.Context, endpoint string, maxBytes int64, credential Credential) (body []byte, instanceID string, err error)`.
- `Get` enforces `Accept-Encoding: identity`, no redirects, `Cache-Control: no-store`, bounded body size, and `X-Bifrost-Instance-Id` header extraction.
- Return `*Failure` with `FailureLimitExceeded` when the caller’s `maxBytes` is exceeded.

```go
// sketch (not production code)
func (client *Client) Get(parent context.Context, endpoint string, maxBytes int64, credential Credential) ([]byte, string, error) {
    ctx, cancel := context.WithTimeout(parent, client.requestTimeout)
    defer cancel()
    req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
    if err != nil { return nil, "", protocolFailure() }
    req.Header.Set("Accept", "application/json")
    req.Header.Set("Accept-Encoding", "identity")
    req.Header.Set("Cache-Control", "no-store")
    if err := credential.Apply(req); err != nil { return nil, "", newFailure(FailureAuthentication, "", nil) }
    resp, err := client.http.Do(req)
    if err != nil { return nil, "", classifyTransport(err) }
    defer resp.Body.Close()
    if resp.StatusCode >= 300 && resp.StatusCode < 400 { return nil, "", newFailure(FailureUnavailable, CategoryRedirect, nil) }
    if encoding := resp.Header.Get("Content-Encoding"); encoding != "" && !strings.EqualFold(encoding, "identity") {
        return nil, "", protocolFailure()
    }
    body, err := readBounded(resp.Body, maxBytes)
    if err != nil { return nil, "", newFailure(FailureLimitExceeded, "", nil) }
    if resp.StatusCode != http.StatusOK { return nil, "", mapProblem(resp.StatusCode, resp.Header.Get("Content-Type"), body) }
    return body, firstHeader(resp.Header.Values(InstanceIDHeader)), nil
}
```

#### 2. `applicationclient/address.go`

**File:** `bifrost-console/internal/applicationclient/address.go`
**Changes:**
- Add endpoint methods that mirror `ObservabilityApiPaths.java:5-11`:
  - `SkillsEndpoint()`, `SkillEndpoint(registeredName string)`,
  - `ActiveExecutionsEndpoint()`, `ActiveExecutionEndpoint(sessionId string)`,
  - `TracesEndpoint()`, `TraceEndpoint(traceId string)`.
- URL-encode path variables with `url.PathEscape`/`url.QueryEscape` as appropriate.

#### 3. `applicationclient/problem.go`

**File:** `bifrost-console/internal/applicationclient/problem.go`
**Changes:**
- Add `FailureKind` values for `invalid_argument`, `invalid_cursor`, `stale_cursor`, `not_found`, `limit_exceeded`, and `live_monitoring_unavailable`.
- Map problem bodies by `code` field first, then fall back to HTTP status:
  - `BIFROST_API_KEY_REJECTED` → `FailureAuthentication`
  - `INVALID_REQUEST` → `FailureInvalidArgument`
  - `INVALID_CURSOR` → `FailureInvalidCursor`
  - `STALE_CURSOR` → `FailureStaleCursor`
  - `NOT_FOUND` → `FailureNotFound`
  - `LIMIT_EXCEEDED` → `FailureLimitExceeded`
  - `LIVE_MONITORING_UNAVAILABLE` → `FailureLiveMonitoringUnavailable`
  - generic `401/403` → `FailureAccess`
  - `404` without `NOT_FOUND` → `FailureUnavailable` with `CategoryNamespaceNotFound`
  - `5xx` (including `APPLICATION_ERROR`) → `FailureUnavailable` with `CategoryUpstreamServer` and `Retryable=true`
  - all other → `protocolFailure()`

#### 4. `applicationclient/errors.go`

**File:** `bifrost-console/internal/applicationclient/errors.go`
**Changes:**
- Add the new `FailureKind` constants.
- Optionally add `func (f *Failure) ConsoleError(scopeID string) *consolecore.Error` so both `target.Context` and `observability` services share one mapping from `Failure` to `consolecore.Error`.

#### 5. `target/scope.go` and `target/context.go`

**File:** `bifrost-console/internal/target/scope.go`
**Changes:**
- Extend the `ProbeClient` interface with `Get(context.Context, string, int64, applicationclient.Credential) ([]byte, string, error)`.
- Add `Scope.Upstream(ctx context.Context, endpoint string, maxBytes int64) ([]byte, *consolecore.Error)`:
  - Combine `ctx` with `scope.Context` so target rotation cancels in-flight requests.
  - Apply `scope.client.Get` with `scope.credential`.
  - Compare the returned `X-Bifrost-Instance-Id` with `scope.InstanceID`; mismatch returns `TARGET_CHANGED`.
  - Map `context.Canceled` from `scope.Context` to `TARGET_CHANGED`; caller cancellation from `ctx` to `TARGET_UNAVAILABLE`; transport errors through `applicationclient.Failure.ConsoleError`.

**File:** `bifrost-console/internal/target/context.go`
**Changes:**
- Update `commitFailureLocked` to handle the new `FailureKind` values via the shared mapping (or remove duplication by calling `failure.ConsoleError`).

### Success Criteria:

#### Automated Verification:
- [ ] `go test ./...` in `bifrost-console/` passes, including new `applicationclient` tests for `Get`, redirects, body limits, and problem fixtures.
- [ ] `go vet ./...` in `bifrost-console/` passes.
- [ ] Unit tests verify each `problem-*.json` fixture maps to the expected `FailureKind` and `consolecore.Code`.

#### Manual Verification:
- [ ] A local httptest server returning observability fixtures can be queried through `Client.Get` and `Scope.Upstream`.
- [ ] Stale `X-Bifrost-Instance-Id` returns `TARGET_CHANGED`.

## Phase 2: Transport-Neutral Observability Services

### Overview

Create `internal/observability` with hand-authored DTOs, the shared query service, and a `consolecore.Error`-based API used by both browser and future MCP handlers.

### Changes Required:

#### 1. `internal/observability/dto.go`

**File:** `bifrost-console/internal/observability/dto.go`
**Changes:**
- Define Go structs matching the fixture corpus:
  - `InstanceStatus`, `SkillSummary`, `SkillDetail`, `FramePathEntry`, `Usage`, `ConfiguredLimits`, `ActiveExecution`, `Trace`.
  - `Page[T]` with `Items []T`, `HasMore bool`, `NextCursor *string`, `ObservedAt time.Time`.
  - `ActivePage` that embeds `Page[ActiveExecution]` and adds `ResumeCursor *string`.
- TTL fields (`CompletionGraceTtl`, `TraceCatalogMetadataTtl`) are `string` in this PR and displayed as ISO-8601 durations; friendly formatting is deferred to later hardening.

#### 2. `internal/observability/service.go`

**File:** `bifrost-console/internal/observability/service.go`
**Changes:**
- Define `Service` with `target *target.Context` and constants for collection default/max page size and per-response byte limits.
- Add methods:
  - `GetInstance(ctx, scope) (InstanceStatus, *consolecore.Error)`
  - `ListSkills(ctx, scope, ListRequest{Cursor, PageSize}) (Page[SkillSummary], *consolecore.Error)`
  - `GetSkill(ctx, scope, registeredName) (SkillDetail, *consolecore.Error)`
  - `ListActiveExecutions(ctx, scope, ListRequest) (ActivePage, *consolecore.Error)`
  - `GetActiveExecution(ctx, scope, sessionId) (ActiveExecution, *consolecore.Error)`
  - `ListTraces(ctx, scope, ListRequest) (Page[Trace], *consolecore.Error)`
  - `GetTrace(ctx, scope, traceId) (Trace, *consolecore.Error)`
- Each method:
  - Calls `target.Capture()` (or accepts a `Scope` from the caller).
  - Clamps `PageSize` to `[1, 5000]` with default `1000`; invalid values return `INVALID_ARGUMENT`.
  - Builds the query URL from `Address` methods plus `pageSize`/`cursor`.
  - Calls `scope.Upstream` with the appropriate `maxBytes` (e.g., `16 MiB` for collection pages, `4 MiB` for skill detail, `1 MiB` for trace detail).
  - Decodes JSON into the DTO; schema mismatch returns `CONSOLE_ERROR` (sanitized).

#### 3. `internal/observability/serialization.go` (optional)

**File:** `bifrost-console/internal/observability/serialization.go`
**Changes:**
- If needed, add a small ISO-8601 duration string helper for display, or keep TTLs as strings and defer formatting.

### Success Criteria:

#### Automated Verification:
- [ ] `go test ./...` passes, including `internal/observability` tests that load every `bifrost-console-fixtures/application-rest/*.json` and assert the service returns the expected DTOs.
- [ ] Tests verify page-size clamping, cursor pass-through, `STALE_CURSOR` handling, `NOT_FOUND` for missing resources, and `LIVE_MONITORING_UNAVAILABLE` for active executions.

#### Manual Verification:
- [ ] A local Java or mock Phase 1 server returns fixture data and the Go service produces the expected DTOs.

## Phase 3: Browser API Routes

### Overview

Add POST routes under `/api/console/v1/` that adapt `internal/observability` and return browser DTOs in the existing error envelope.

### Changes Required:

#### 1. `browserapi/router.go`

**File:** `bifrost-console/internal/browserapi/router.go`
**Changes:**
- Add `Observability *observability.Service` to `Options`.
- Register new read-only routes using `withSession(response, request, false, handler)`:
  - `/api/console/v1/observability/instance`
  - `/api/console/v1/skills/list`
  - `/api/console/v1/skills/detail`
  - `/api/console/v1/active-executions/list`
  - `/api/console/v1/active-executions/detail`
  - `/api/console/v1/traces/list`
  - `/api/console/v1/traces/detail`

#### 2. `browserapi/observability.go` (new)

**File:** `bifrost-console/internal/browserapi/observability.go`
**Changes:**
- Implement one handler per route:
  - Decode a small JSON body with `decodeJSONLimit`.
  - Capture a `target.Scope` from `router.options.Target`.
  - Call the observability service.
  - On `*consolecore.Error`, call `writeDomainError`.
  - On success, call `writeJSON` with the DTO.
- Request body schemas (examples):
  - list: `{ "cursor"?: string, "pageSize"?: number }`
  - detail: `{ "registeredName": string }`, `{ "sessionId": string }`, `{ "traceId": string }`

#### 3. `console/service.go`

**File:** `bifrost-console/internal/console/service.go`
**Changes:**
- Construct `observability.New(targetContext)` and pass it to `browserapi.Options.Observability`.

### Success Criteria:

#### Automated Verification:
- [ ] New `browserapi` tests in `browserapi/observability_test.go` hit every route with session cookies and fixture-backed mock responses, asserting correct HTTP status, JSON envelope, and problem-code mapping.
- [ ] Tests verify that unauthenticated/unpaired requests are rejected before the body is read, and that read-only routes do not require CSRF.

#### Manual Verification:
- [ ] `curl` or browser DevTools can call each route with a valid session and receive the expected page or detail DTO.

## Phase 4: Frontend Operational Views

### Overview

Add React routes, API client functions/contracts, an `ObservabilityProvider`, and the four read-only view components. The Overview is extended to show instance counts and TTLs with navigation to the catalog views.

### Changes Required:

#### 1. `web/src/app/routes.tsx`

**File:** `bifrost-console/web/src/app/routes.tsx`
**Changes:**
- Add child routes:
  - `/skills`, `/skills/:registeredName`
  - `/active-executions`, `/active-executions/:sessionId`
  - `/traces`, `/traces/:traceId`
- Keep index `/` as Overview and `*` as NotFound.

#### 2. `web/src/api/client.ts` and `web/src/api/contracts.ts`

**File:** `bifrost-console/web/src/api/client.ts`
**Changes:**
- Add `post<T>`-based functions (no CSRF required):
  - `instanceStatus()`, `listSkills(body)`, `getSkill(body)`,
  - `listActiveExecutions(body)`, `getActiveExecution(body)`,
  - `listTraces(body)`, `getTrace(body)`.

**File:** `bifrost-console/web/src/api/contracts.ts`
**Changes:**
- Add TypeScript DTO types: `InstanceStatus`, `Page<T>`, `ActivePage`, `SkillSummary`, `SkillDetail`, `ActiveExecution`, `Trace`, `Usage`, `ConfiguredLimits`, `FramePathEntry`.

#### 3. New `web/src/observability/` package

**File:** `bifrost-console/web/src/observability/ObservabilityProvider.tsx`
**Changes:**
- Create a provider that:
  - Tracks `instanceStatus`, `skills`, `active`, `traces`, and per-detail state.
  - Resets all application-derived state when `target.scopeGeneration` changes (from `useTarget`).
  - Exposes fetch/refresh/continue helpers.

**File:** `bifrost-console/web/src/observability/reducer.ts`
**Changes:**
- Mirror `targetReducer.ts` patterns: `replace`, `error`, `clear-error`, `append` (for pagination), and `scope-reset`.

**File:** `bifrost-console/web/src/observability/Overview.tsx` (or update `web/src/target/Overview.tsx`)
**Changes:**
- Display target address, `instanceId`, `consoleCompatibilityVersion`, connection/auth/compatibility/live-monitoring state, registered skill count, active execution count, cataloged trace count, `tracePersistencePolicy`, `completionGraceTtl`, and `traceCatalogMetadataTtl`.
- Add explanatory text that catalog metadata TTL and core file retention are independent and neither provides cross-restart history.
- Add navigation links to Skills, Active Executions, and Traces.

**File:** `bifrost-console/web/src/observability/SkillCatalog.tsx`, `SkillDetail.tsx`
**Changes:**
- `SkillCatalog`: paginated list showing `registeredName` and `sourcePath`; detail links.
- `SkillDetail`: display `sourcePath` as plain text and `yaml` inside a `<pre>` element; no syntax highlighting or parsing in this PR.

**File:** `bifrost-console/web/src/observability/ActiveExecutions.tsx`, `ActiveExecutionDetail.tsx`
**Changes:**
- `ActiveExecutions`: paginated list with `entrySkill`, `sessionId`, `traceId`, status, phase, summary, elapsed time.
- `ActiveExecutionDetail`: display the full `ActiveExecution` snapshot; no frame tree or payload inspection.

**File:** `bifrost-console/web/src/observability/Traces.tsx`, `TraceDetail.tsx`
**Changes:**
- `Traces`: paginated list with `traceId`, `sessionId`, `outcome`, `finalizedAt`, `sizeBytes`, `persistencePolicy`, `applicationTraceExpiresAt`.
- `TraceDetail`: display the same fields for one trace.

#### 4. `web/src/app/App.tsx`

**File:** `bifrost-console/web/src/app/App.tsx`
**Changes:**
- Wrap `Outlet` with `ObservabilityProvider` when paired.
- Optionally add a small top navigation with links to the four areas.

### Success Criteria:

#### Automated Verification:
- [ ] `npm run typecheck` passes in `bifrost-console/web`.
- [ ] `npm run test` passes, including new `client.test.ts` cases for the new API functions and component tests for catalog/detail views.
- [ ] `npm run build:web` produces embedded assets with no errors.

#### Manual Verification:
- [ ] Browser navigation between Overview, Skills, Active Executions, and Traces works with keyboard.
- [ ] Pagination “load more” correctly passes `nextCursor`.
- [ ] Scope rotation or target change resets catalog state and returns the user to Overview.

## Phase 5: Contract Tests and Fixture Verification

### Overview

Ensure the new Go consumer and browser API agree with the canonical Java-produced fixtures and that no contract drift is introduced.

### Changes Required:

#### 1. `bifrost-console-fixtures/application-rest/expected/` (if needed)

**File:** `bifrost-console-fixtures/expected/`
**Changes:**
- If PR 10 introduces new expected outputs for the Go service, add them; otherwise the existing `application-rest/` fixtures remain authoritative.

#### 2. Go tests

**File:** `bifrost-console/internal/applicationclient/client_test.go`
**Changes:**
- Add `TestGet*` covering bounded bodies, problem-code mapping, `InstanceID` header extraction, and redirect/encoding rejection.

**File:** `bifrost-console/internal/observability/service_test.go` (new)
**Changes:**
- Test each service method against every relevant fixture under `bifrost-console-fixtures/application-rest/`.
- Assert `Page[SkillSummary]`, `ActivePage`, and `Page[Trace]` decode correctly, including `nextCursor=null` and `nextCursor=<opaque>`.

**File:** `bifrost-console/internal/browserapi/observability_test.go` (new)
**Changes:**
- Table-driven tests for all new routes with a mock `observability.Service` or a real httptest upstream.

#### 3. Frontend tests

**File:** `bifrost-console/web/src/api/client.test.ts`
**Changes:**
- Add tests that assert correct URL, POST method, headers, and body for each new function.

**File:** `bifrost-console/web/src/observability/*.test.tsx` (new)
**Changes:**
- Component tests for loading, empty, error, and presentation states; YAML rendered as text; pagination continuation.

### Success Criteria:

#### Automated Verification:
- [ ] `go test ./...` in `bifrost-console/` passes.
- [ ] `npm run test` in `bifrost-console/web` passes.
- [ ] `npm run build:web` in `bifrost-console/web` runs before the Go build; `go test ./...` after asset build passes.

#### Manual Verification:
- [ ] A representative manual run through each view with the supported fixture set succeeds.

## Testing Strategy

### Unit Tests:
- `applicationclient.Client.Get` bounded reads, header enforcement, redirect/encoding rejection, and problem-code mapping.
- `target.Scope.Upstream` scope-cancellation, `InstanceID` mismatch, and `consolecore.Error` mapping.
- `internal/observability` service decoding of every fixture and page-size clamping.
- `browserapi` route dispatch and error-status mapping for each new endpoint.

### Integration Tests:
- Go `httptest` server returning `bifrost-console-fixtures/application-rest/` files, driven end-to-end through `Service` and `browserapi` handlers.
- React `memoryRouter` routes with mock `fetch` and `ObservabilityProvider`.

> **Note:** A dedicated testing plan should be created with `3_testing_plan.md` before implementation begins. The test plan must reference the four PR-10 acceptance signals in the ticket and the relevant Phase 2 invariants.

### Manual Testing Steps:
1. Pair the console, select a target, and observe the Overview counts.
2. Navigate to Skills, paginate, and open a skill detail; confirm raw YAML is text, not HTML.
3. Navigate to Active Executions, paginate, and open a detail snapshot.
4. Navigate to Traces, paginate, and open a trace detail.
5. Rotate the target credential or change the target; confirm all catalog state resets and the UI returns to Overview.
6. Trigger `STALE_CURSOR` (e.g., by restarting the target application) and confirm the UI recovers from the first page without a generic error.

## Performance Considerations

- Collection requests default to 1,000 items and clamp to 5,000 (`bifrost_console_phase_2_ui_console.md:474`).
- Per-response JSON is bounded to 16 MiB; detail requests use smaller per-type limits.
- Go does not materialize the full application catalog locally; it fetches pages on demand.
- `target.Scope.Upstream` ties request cancellation to both the HTTP request and the target scope context, so scope rotation aborts in-flight work.
- The browser keeps only the currently fetched pages and cursors in memory; no full collection mirror.

## Migration Notes

Not applicable. This PR only adds new read-only views and does not change existing persisted state, configuration, or skill behavior.

## References

- Ticket: `ai/thoughts/tickets/bifrost-console-pr-10-operational-views.md`
- Research: `ai/thoughts/research/2026-07-27-bifrost-console-pr-10-operational-views-research.md`
- Roadmap: `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md`
- Phase 2 design: `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md`
- Design lens: `ai/thoughts/framework-feature-design-lens.md`
- Java contract: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestController.java:54-249`
- Java DTOs: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/dto/ObservabilityDtos.java:18-84`
- Go client: `bifrost-console/internal/applicationclient/client.go:17-130`
- Go target context: `bifrost-console/internal/target/context.go:54-289`
- Browser router: `bifrost-console/internal/browserapi/router.go:36-75`
- Frontend routes: `bifrost-console/web/src/app/routes.tsx:6-16`
- Fixtures: `bifrost-console-fixtures/application-rest/`
- Future PR — live activity: `ai/thoughts/tickets/bifrost-console-pr-11-live-execution-experience.md` (confirms `resumeCursor` preservation, `ScopeOwner` deferral)
- Future PR — artifact service: `ai/thoughts/tickets/bifrost-console-pr-12-artifact-service.md` (confirms `ScopeOwner` for cache lifecycle)
- Future PR — MCP runtime inspection: `ai/thoughts/tickets/bifrost-console-pr-17-mcp-runtime-inspection.md` (confirms transport-neutral service reuse, client extension, DTO sharing)
- Future PR — MCP trace inspection: `ai/thoughts/tickets/bifrost-console-pr-18-mcp-trace-inspection.md` (confirms trace service reuse)
- Future PR — debugging skill: `ai/thoughts/tickets/bifrost-console-pr-19-debugging-skill.md` (confirms `sourcePath` is not a filesystem locator)
