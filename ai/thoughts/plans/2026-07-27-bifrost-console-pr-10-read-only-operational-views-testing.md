# PR 10 — Read-Only Operational Views Testing Plan

## Change Summary

Add transport-neutral Go query services for the Phase 1 observability REST contract, expose them through the existing `browserapi` POST-only session layer, and build the first read-only React views (Overview, Skill Catalog, Active Executions, Trace Catalog). The implementation spans:

- `applicationclient.Client.Get` and endpoint helpers (`client.go`, `address.go`).
- New `FailureKind` values and problem-code mapping in `problem.go` / `errors.go`.
- `target.Scope.Upstream` for scope-bound, cancellable, identity-checked upstream GETs.
- A new `internal/observability` package with hand-authored DTOs and query service.
- New read-only `POST /api/console/v1/...` routes in `browserapi`.
- New React routes, `ObservabilityProvider`/reducer, and view components under `web/src/observability`.
- New unit and integration tests across the Go and TypeScript boundary.

This is a new feature, not a bug fix, so the plan uses one pre-implementation failing test to establish the missing seam, then adds the minimum set of automated tests to prove the feature and protect future MCP reuse.

## Impacted Areas

- **Go upstream client**
  - `bifrost-console/internal/applicationclient/client.go` — new bounded `Get`.
  - `bifrost-console/internal/applicationclient/address.go` — Phase 1 endpoint URLs.
  - `bifrost-console/internal/applicationclient/problem.go` — new problem-code mapping.
  - `bifrost-console/internal/applicationclient/errors.go` — new `FailureKind` constants and `ConsoleError` mapping.
- **Target scope**
  - `bifrost-console/internal/target/scope.go` — `ProbeClient` extension and `Scope.Upstream`.
  - `bifrost-console/internal/target/context.go` — shared `Failure` → `consolecore.Error` path.
- **Observability services**
  - `bifrost-console/internal/observability/dto.go` — Go DTOs for instance, skills, active executions, traces, pages.
  - `bifrost-console/internal/observability/service.go` — transport-neutral query service.
- **Browser API**
  - `bifrost-console/internal/browserapi/router.go` — new route registration.
  - `bifrost-console/internal/browserapi/observability.go` — new handlers (to be created).
- **Frontend**
  - `bifrost-console/web/src/app/routes.tsx`
  - `bifrost-console/web/src/api/client.ts` and `contracts.ts`
  - `bifrost-console/web/src/observability/*` (new provider, reducer, views)
  - `bifrost-console/web/src/app/App.tsx`
- **Fixtures (canonical contract)**
  - `bifrost-console-fixtures/application-rest/*.json`

## Risk Assessment

- **Behaviors changing**
  - `applicationclient.Client` moves from a single `Probe` to a generic bounded `Get` used for all upstream reads.
  - Problem-code mapping now recognizes `INVALID_REQUEST`, `INVALID_CURSOR`, `STALE_CURSOR`, `NOT_FOUND`, `LIMIT_EXCEEDED`, and `LIVE_MONITORING_UNAVAILABLE`.
  - `target.Scope` exposes `Upstream` which applies credentials, extracts `X-Bifrost-Instance-Id`, and maps cancellation/identity errors.
  - New `internal/observability` package owns pagination, page-size clamping, and DTO decoding.
  - `browserapi` adds read-only POST routes that do not require CSRF but still require a session.
  - Frontend state resets all observability data when `target.scopeGeneration` changes.

- **High-risk behaviors and edge cases**
  - **Target rotation during a paginated request** must cancel the request and surface `TARGET_CHANGED` without leaking stale data.
  - **Stale `X-Bifrost-Instance-Id` or `STALE_CURSOR`** must be handled as recoverable in the UI (return to first page) rather than a generic crash.
  - **Oversized paginated responses** must fail with `LIMIT_EXCEEDED` instead of unbounded memory growth.
  - **YAML and `sourcePath` display** must not be rendered as HTML, Markdown, links, or filesystem locators.
  - **Read-only route security** must reject unpaired requests before reading the body and must not accidentally require CSRF.
  - **Resume cursor preservation** on `ActivePage` is required for PR 11 even though PR 10 does not implement live SSE.

- **Protected compatibility paths and intentionally removed obsolete paths**
  - **Application API, Supported SPI, Configuration/manifest contracts, Persisted/serialized contracts:** No impact.
  - **Ephemeral diagnostic formats (affected, protected current-run coherence):**
    - Java Phase 1 observability JSON page shapes in `bifrost-console-fixtures/application-rest/`.
    - `problem-*.json` code semantics.
    - Opaque cursors; Go must not reinterpret them or convert keyset pagination to offset pagination.
    - Evidence: plan `Contract and Compatibility Impact` table and `framework-feature-design-lens.md:23-38`.
  - **Internal or accidentally exposed implementation (affected, atomic update):**
    - All new Go and React code is internal to the `bifrost-console` executable.
    - The old `maxResponseBytes = 64 * 1024` bounded `Probe` path is superseded by a configurable `Get`; no external consumer exists, so no shim is retained.
    - Java-to-Go boundary coordination is required for verification; any fixture disagreement must be resolved in the same PR.
  - **Intentionally removed obsolete paths:** None. Existing target routes and `Probe` semantics remain unchanged.

## Existing Test Coverage

- **`bifrost-console/internal/applicationclient/client_test.go`**
  - Tests `Probe` request headers, bounded body, fixture consumption, compatibility, transport, TLS, and problem mapping for `BIFROST_API_KEY_REJECTED` / generic 401/403.
  - **Gaps:** no `Get` method, no redirect/encoding rejection, no body-limit test, no new problem-code mapping, no `X-Bifrost-Instance-Id` extraction for non-instance endpoints.
- **`bifrost-console/internal/target/context_test.go`**
  - Tests scope rotation, `ScopeOwner` invalidation, credential supply, retry, and caller cancellation.
  - **Gaps:** no `Scope.Upstream`, no `ProbeClient.Get` integration, no instance-id mismatch, no upstream problem → `consolecore.Error` mapping.
- **`bifrost-console/internal/browserapi/target_test.go`**
  - Tests shared `writeDomainError` mapping and security-before-body behavior for target routes.
  - **Gaps:** no new observability routes, no read-only/CSRF distinction, no pagination, no error mapping for the new problem codes.
- **`bifrost-console/internal/browserapi/contracts_test.go`**
  - Verifies browser-fixtures/target corpus matches committed output.
  - **Gaps:** no equivalent corpus test for observability browser envelopes.
- **`bifrost-console/web/src/api/client.test.ts`**
  - Tests `post<T>` wrapper, pairing, bootstrap, heartbeat, target operations, and error envelope.
  - **Gaps:** no observability API functions.
- **`bifrost-console/web/src/target/Overview.test.tsx`**
  - Tests target status rendering, forms, transport guidance, and focus after scope reset.
  - **Gaps:** no instance-status counts/TTLs, no catalog navigation, no pagination, no YAML text rendering.

## Bug Reproduction / Failing Test First

This PR is a new feature, not a bug. To prove the missing seam before implementation, use a single failing integration test in `internal/observability`:

- **Type:** integration
- **Location:** `bifrost-console/internal/observability/service_test.go`
- **Name:** `TestObservabilityServiceReturnsSkillPageFromFixture`
- **Arrange/Act/Assert outline:**
  1. Start an `httptest` server that returns `bifrost-console-fixtures/application-rest/skills-page.json` with `X-Bifrost-Instance-Id: 11111111-1111-4111-8111-111111111111`.
  2. Construct a `target.Context` that points at the server and supply a valid credential.
  3. Call `observability.New(targetContext).ListSkills(ctx, scope, ListRequest{})`.
  4. Assert the returned `Page[SkillSummary]` contains one item with `RegisteredName: "CheckDns"`.
- **Expected failure (pre-fix):** The package `internal/observability` does not exist and the test will not compile (or, if it compiles against a stub, it will fail with "not implemented").

## Tests to Add/Update

### 1) `TestClientGetBoundedReadsAndRejectsRedirectsAndEncoding`

- **Type:** unit
- **Location:** `bifrost-console/internal/applicationclient/client_test.go`
- **What it proves:**
  - `Get` sends `Accept: application/json`, `Accept-Encoding: identity`, and `Cache-Control: no-store`.
  - It applies the credential exactly once.
  - It rejects 3xx redirects with `CategoryRedirect`.
  - It rejects non-identity `Content-Encoding`.
  - It extracts `X-Bifrost-Instance-Id` from the response.
  - It returns `*Failure` with `FailureLimitExceeded` when the body exceeds the caller’s `maxBytes`.
- **Fixtures/data:** Inline `httptest` handlers.
- **Mocks:** `testCredential`, `httptest.NewServer`.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** Approved internal path replacement; old `Probe` bounded behavior remains equivalent.

### 2) `TestProblemFixturesMapToNewFailureKindsAndConsoleCodes`

- **Type:** unit
- **Location:** `bifrost-console/internal/applicationclient/client_test.go` or `problem_test.go`
- **What it proves:**
  - Each `bifrost-console-fixtures/application-rest/problem-*.json` fixture produces the expected `FailureKind` and, via `(Failure).ConsoleError`, the expected `consolecore.Code`:
    - `BIFROST_API_KEY_REJECTED` → `TARGET_AUTHENTICATION_REQUIRED`
    - `INVALID_REQUEST` → `INVALID_ARGUMENT`
    - `INVALID_CURSOR` → `INVALID_CURSOR`
    - `STALE_CURSOR` → `STALE_CURSOR`
    - `NOT_FOUND` → `NOT_FOUND`
    - `LIMIT_EXCEEDED` → `LIMIT_EXCEEDED`
    - `LIVE_MONITORING_UNAVAILABLE` → `LIVE_MONITORING_UNAVAILABLE`
    - `APPLICATION_ERROR` (5xx) → `TARGET_UNAVAILABLE` with `transportCategory: upstream_server` and `Retryable=true`
  - Generic `401/403` without a recognized code → `TARGET_ACCESS_BLOCKED`.
  - `404` without `NOT_FOUND` → `TARGET_UNAVAILABLE` with `transportCategory: namespace_not_found`.
- **Fixtures/data:** `bifrost-console-fixtures/application-rest/problem-*.json`.
- **Mocks:** `httptest.NewServer` returning each fixture.
- **Contract classification:** Ephemeral diagnostic format.
- **Compatibility expectation:** Current-run diagnostic coherence; Go problem semantics must match Java Phase 1 problem bodies exactly.

### 3) `TestAddressEndpointEncoding`

- **Type:** unit
- **Location:** `bifrost-console/internal/applicationclient/address_test.go`
- **What it proves:**
  - `SkillsEndpoint`, `SkillEndpoint(registeredName)`, `ActiveExecutionsEndpoint`, `ActiveExecutionEndpoint(sessionId)`, `TracesEndpoint`, and `TraceEndpoint(traceId)` produce the correct paths.
  - Path variables are correctly escaped with `url.PathEscape` and query parameters are appended with `url.QueryEscape`.
- **Fixtures/data:** Table of inputs and expected URL strings.
- **Mocks:** None.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** Atomic update with new `Get` consumer; no external caller.

### 4) `TestScopeUpstreamAppliesCredentialsAndDetectsIdentityMismatchAndCancellation`

- **Type:** unit
- **Location:** `bifrost-console/internal/target/scope_test.go` (new)
- **What it proves:**
  - `Scope.Upstream` calls `ProbeClient.Get` with the scope credential and the combined context.
  - A mismatched `X-Bifrost-Instance-Id` returns a `consolecore.Error` with code `TARGET_CHANGED`.
  - Cancellation from `scope.Context` returns `TARGET_CHANGED`.
  - Caller `ctx` cancellation returns `TARGET_UNAVAILABLE`.
  - Transport/`applicationclient.Failure` errors are mapped through `Failure.ConsoleError`.
- **Fixtures/data:** None.
- **Mocks:** Fake `ProbeClient` returning controlled bytes, instance IDs, and errors.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** Atomic update; target scope semantics remain the single authority for identity and cancellation.

### 5) `TestContextFailureMappingCoversNewFailureKinds`

- **Type:** unit
- **Location:** `bifrost-console/internal/target/context_test.go`
- **What it proves:**
  - `commitFailureLocked` (or its replacement via `Failure.ConsoleError`) maps each new `FailureKind` to the correct `consolecore.Code` and details.
  - `TARGET_CHANGED` details include the current target scope ID.
- **Fixtures/data:** None.
- **Mocks:** Fake client returning each `*applicationclient.Failure`.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** Atomic update; no public surface.

### 6) `TestObservabilityDTOsDecodeEveryCanonicalFixture`

- **Type:** unit
- **Location:** `bifrost-console/internal/observability/dto_test.go` (new)
- **What it proves:**
  - `json.Unmarshal` into `InstanceStatus`, `Page[SkillSummary]`, `SkillDetail`, `ActivePage`, `Page[Trace]`, and `Trace` succeeds for every `application-rest` fixture.
  - `Page` generic handles `hasMore=true/false`, `nextCursor` as opaque string or `null`, and `observedAt` time.
  - `ActivePage` captures `resumeCursor`.
  - `empty-page.json` produces an empty `Items` slice with `HasMore=false`.
- **Fixtures/data:** `bifrost-console-fixtures/application-rest/*.json` (excluding `problem-*.json`).
- **Mocks:** None.
- **Contract classification:** Ephemeral diagnostic format.
- **Compatibility expectation:** Current-run diagnostic coherence; Go DTOs are a faithful mirror of the Java-produced fixtures.

### 7) `TestObservabilityServicePageSizeClampingAndCursorPassThrough`

- **Type:** unit
- **Location:** `bifrost-console/internal/observability/service_test.go` (new)
- **What it proves:**
  - Default page size is 1,000; `0` and values > 5,000 are clamped.
  - Negative or otherwise invalid page sizes return `INVALID_ARGUMENT`.
  - `cursor` is passed through to the upstream URL unchanged.
  - Different endpoint types use the correct `maxBytes` bound (16 MiB for collections, 4 MiB for skill detail, 1 MiB for trace detail).
- **Fixtures/data:** None.
- **Mocks:** Fake `target.Scope` recording the endpoint and maxBytes passed to `Upstream`.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** Atomic update; pagination defaults and bounds match Phase 2 design (`bifrost_console_phase_2_ui_console.md:472-476`).

### 8) `TestObservabilityServiceReturnsExpectedDTOsAgainstHttptest`

- **Type:** integration
- **Location:** `bifrost-console/internal/observability/service_test.go`
- **What it proves:**
  - `GetInstance`, `ListSkills`, `GetSkill`, `ListActiveExecutions`, `GetActiveExecution`, `ListTraces`, and `GetTrace` return DTOs matching the canonical fixtures.
  - `STALE_CURSOR` fixture from upstream returns `consolecore.CodeStaleCursor`.
  - `NOT_FOUND` for a missing skill/active/trace returns `consolecore.CodeNotFound`.
  - `LIVE_MONITORING_UNAVAILABLE` for active-execution detail returns `consolecore.CodeLiveMonitoringUnavailable`.
- **Fixtures/data:** `bifrost-console-fixtures/application-rest/*.json`.
- **Mocks:** `httptest` server with path-based fixture dispatch; real `target.Context` with a test credential.
- **Contract classification:** Ephemeral diagnostic format.
- **Compatibility expectation:** Current-run diagnostic coherence; Java-to-Go boundary agreement verified end-to-end.

### 9) `TestBrowserAPIObservabilityRoutesRequireSessionNotCSRF`

- **Type:** integration
- **Location:** `bifrost-console/internal/browserapi/observability_test.go` (new)
- **What it proves:**
  - Unpaired requests to `/api/console/v1/observability/instance`, `/skills/list`, `/skills/detail`, `/active-executions/list`, `/active-executions/detail`, `/traces/list`, and `/traces/detail` return `401` before the request body is read.
  - Paired requests without CSRF headers succeed for read-only routes.
  - Paired requests with an invalid body return `400`.
- **Fixtures/data:** None.
- **Mocks:** Real `browserapi.Options` with a valid `browserauth.Pairing`/`Registry` and a fake `observability.Service`.
- **Test patterns to follow:** Reuse the `apiRequest` helper from `security_integration_test.go:193-203` for authenticated POST requests. Use the `readSpy` type from `security_integration_test.go:183-191` to verify the body is not read before session validation (same pattern as `TestTargetRoutesApplySecurityBeforeBodyRead` in `target_test.go:38-63`).
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** Atomic update; read-only routes follow the existing `withSession(..., false)` pattern.

### 10) `TestBrowserAPIObservabilityErrorMappingAndEnvelope`

- **Type:** integration
- **Location:** `bifrost-console/internal/browserapi/observability_test.go`
- **What it proves:**
  - Each `consolecore.Code` from the observability service maps to the HTTP status documented in `browserapi/target.go:99-131`:
    - `INVALID_ARGUMENT`, `INVALID_CURSOR` → 400
    - `TARGET_AUTHENTICATION_REQUIRED` → 401
    - `TARGET_ACCESS_BLOCKED` → 403
    - `NOT_FOUND` → 404
    - `INCOMPATIBLE_TARGET`, `TARGET_CHANGED`, `STALE_CURSOR`, `ARTIFACT_EXPIRED`, `LIVE_MONITORING_UNAVAILABLE` → 409
    - `INVALID_ARTIFACT` → 422
    - `LIMIT_EXCEEDED` → 429
    - `TARGET_UNAVAILABLE`, `LOCAL_STORAGE_UNAVAILABLE` → 503
  - The JSON error envelope contains `code`, safe `message`, `targetScopeId`, and bounded `details`.
- **Fixtures/data:** None.
- **Mocks:** Fake `observability.Service` returning controlled `*consolecore.Error` values.
- **Contract classification:** Ephemeral diagnostic format.
- **Compatibility expectation:** Current-run diagnostic coherence; browser error envelope remains the single adapter contract.

### 11) `TestBrowserAPIObservabilityRoutesPassCursorAndIdentifiers`

- **Type:** integration
- **Location:** `bifrost-console/internal/browserapi/observability_test.go`
- **What it proves:**
  - List routes decode `cursor` and `pageSize` and pass them to the service.
  - Detail routes decode `registeredName`, `sessionId`, and `traceId` and pass them to the service.
  - Successful responses are written with `writeJSON` and `Cache-Control: no-store`.
  - The DTO returned to the browser preserves `nextCursor`/`resumeCursor` opaquely.
- **Fixtures/data:** `bifrost-console-fixtures/application-rest/continuation-page.json`, `active-executions-page.json`.
- **Mocks:** Fake `observability.Service` returning fixture-based DTOs.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** Atomic update; browser DTOs must not convert cursors to offset pagination.

### 12) `TestFrontendClientObservabilityFunctions`

- **Type:** unit
- **Location:** `bifrost-console/web/src/api/client.test.ts`
- **What it proves:**
  - `instanceStatus()`, `listSkills(body)`, `getSkill(body)`, `listActiveExecutions(body)`, `getActiveExecution(body)`, `listTraces(body)`, and `getTrace(body)` each use `POST`, `same-origin`, `no-store`, `redirect: "error"`, and the correct path.
  - They do **not** send CSRF headers.
  - They send the request body as JSON.
  - They throw `BrowserAPIError` with `code`, `message`, `status`, `targetScopeId`, and `details` on error.
- **Fixtures/data:** None.
- **Mocks:** `vi.stubGlobal("fetch", ...)` as in existing `client.test.ts`.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** Atomic update; browser API contract remains POST-only and session-scoped.

### 13) `TestObservabilityProviderResetsOnScopeGenerationChange`

- **Type:** unit
- **Location:** `bifrost-console/web/src/observability/ObservabilityProvider.test.tsx` (new)
- **What it proves:**
  - The provider fetches instance status and catalog data on mount and on manual refresh.
  - `scopeGeneration` change clears all cached pages and details and navigates to `/`.
  - Errors are scoped to the current `scopeGeneration` and do not leak across target changes.
- **Fixtures/data:** Mock `InstanceStatus`, `Page<SkillSummary>`, `ActivePage`, `Page<Trace>`.
- **Mocks:** `vi.mock("../target/TargetProvider", ...)` to inject `scopeGeneration` and `target`; stubbed `fetch`.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** Atomic update; follows `TargetProvider`/`targetReducer` patterns.

### 14) `TestOverviewDisplaysInstanceFactsAndCatalogNavigation`

- **Type:** unit
- **Location:** `bifrost-console/web/src/observability/Overview.test.tsx` (new)
- **What it proves:**
  - Renders `instanceId`, `consoleCompatibilityVersion`, counts (skills, active executions, traces), `tracePersistencePolicy`, `completionGraceTtl`, and `traceCatalogMetadataTtl`.
  - Displays text explaining that catalog metadata TTL and core file retention are independent and do not provide cross-restart history.
  - Provides keyboard-accessible navigation links to Skills, Active Executions, and Traces.
  - Presents loading, empty, unavailable, authentication, compatibility, and stale-scope states.
- **Fixtures/data:** `instance-status.json` fields.
- **Mocks:** Mocked `useObservability` and `useTarget`.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** Current-run diagnostic coherence; UI text matches Phase 2 design and guardrails.

### 15) `TestSkillCatalogAndDetailRenderUntrustedText`

- **Type:** unit
- **Location:** `bifrost-console/web/src/observability/SkillCatalog.test.tsx` and `SkillDetail.test.tsx` (new)
- **What it proves:**
  - `SkillCatalog` lists `registeredName` and `sourcePath`; detail links use the registered name.
  - `SkillDetail` renders `sourcePath` as plain text and `yaml` inside a `<pre>` element.
  - YAML containing HTML/Markdown-like content is displayed as text, not parsed or executed.
  - `sourcePath` is not presented as a clickable filesystem link or joined to a local path.
- **Fixtures/data:** `bifrost-console-fixtures/application-rest/skills-page.json`, `skill-detail.json`.
- **Mocks:** Mocked observability provider and `react-router` `MemoryRouter`.
- **Contract classification:** Ephemeral diagnostic format.
- **Compatibility expectation:** Current-run diagnostic usefulness and security; YAML is untrusted display text.

### 16) `TestActiveExecutionsAndTracesPaginationAndDirectLookup`

- **Type:** unit
- **Location:** `bifrost-console/web/src/observability/ActiveExecutions.test.tsx`, `Traces.test.tsx` (new)
- **What it proves:**
  - Lists render the fields specified in the plan for each item.
  - "Load more" calls the list function with `nextCursor` from the previous page.
  - Detail views load when navigated directly by `registeredName`, `sessionId`, or `traceId`.
  - Empty, error, and loading states are presented.
  - `STALE_CURSOR` error resets to the first page.
- **Fixtures/data:** `active-executions-page.json`, `active-execution-detail.json`, `traces-page.json`, `trace-detail.json`, `empty-page.json`, `continuation-page.json`.
- **Mocks:** Stubbed `fetch` with fixture responses; `MemoryRouter`.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** Current-run diagnostic coherence; pagination preserves opaque cursors.

### 17) `TestObservabilityTypeContractsAndBuildArtifacts`

- **Type:** integration / build
- **Location:** `bifrost-console/web` (no new file; run existing scripts)
- **What it proves:**
  - `npm run typecheck` passes with the new `web/src/api/contracts.ts` types.
  - `npm run build:web` produces embedded assets without errors.
  - `go test ./...` in `bifrost-console/` passes after the build step and asset embedding.
  - New frontend files meet the vitest coverage thresholds (80% lines/functions/statements, 70% branches) configured in `web/vitest.config.ts:19-24`.
- **Fixtures/data:** None.
- **Mocks:** None.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** Atomic update; production Go executable ships with matching browser assets.

### 18) `TestObservabilityRoutesDoNotLeakApplicationCredential`

- **Type:** integration
- **Location:** `bifrost-console/internal/console/observability_integration_test.go` (new)
- **What it proves:**
  - The application key sent via `Scope.Upstream`/`Client.Get` never appears in observability service DTOs, browser API JSON responses, error envelopes, or formatted `*consolecore.Error` values.
  - Follows the pattern established by `TestApplicationCredentialNeverAppearsOutsideSelectedRequestHeader` in `console/target_integration_test.go:17-54`.
  - Verifies the credential is confined to exactly one `X-Bifrost-API-Key` header on the upstream request and does not escape into any downstream response visible to the browser.
- **Fixtures/data:** `bifrost-console-fixtures/application-rest/skills-page.json` served by an `httptest` server.
- **Mocks:** `httptest.NewServer` recording received headers; real `target.Context` with a test credential.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** Atomic update; security invariant preserved across the new `Get`/`Upstream` path.

### 19) `TestObservabilityRoutesApplySecurityHeadersAndCacheControl`

- **Type:** integration
- **Location:** `bifrost-console/internal/browserapi/observability_test.go`
- **What it proves:**
  - All observability route responses include `Cache-Control: no-store` (Phase 2 safety requirement #12).
  - Security headers from `browserapi/headers.go:7-15` are applied: CSP, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Permissions-Policy`, `Cross-Origin-Opener-Policy: same-origin`, `Cross-Origin-Resource-Policy: same-origin`.
  - Follows the `Cache-Control` assertion pattern from `security_integration_test.go:61-63`.
- **Fixtures/data:** None.
- **Mocks:** Real `browserapi.Options` with valid pairing/registry and a fake `observability.Service` returning a simple DTO.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** Atomic update; security baseline applies uniformly to all browser-visible responses.

### 20) `TestObservabilityRoutesRejectInvalidJSONAndUnknownFields`

- **Type:** integration
- **Location:** `bifrost-console/internal/browserapi/observability_test.go`
- **What it proves:**
  - Each observability route rejects unknown JSON fields with `400` and `INVALID_REQUEST` (following `DisallowUnknownFields` convention).
  - Oversized bodies are rejected with `400` (following `decodeJSONLimit` convention).
  - Follows the table-driven pattern from `TestEveryEmptyBodyOperationRejectsInvalidJSON` in `security_integration_test.go:136-181`.
- **Fixtures/data:** Inline JSON strings (`{"unexpected":true}`, oversized padding).
- **Mocks:** Real `browserapi.Options` with valid pairing/registry and a fake `observability.Service`.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** Atomic update; input validation matches existing route conventions.

## How to Run

- **Go unit and integration tests**
  ```powershell
  cd bifrost-console
  go test ./...
  go vet ./...
  ```
- **Go with race detection for concurrency-sensitive scope tests**
  ```powershell
  cd bifrost-console
  go test -race ./internal/target/... ./internal/observability/... ./internal/browserapi/...
  ```
- **Frontend type checking, unit tests, and build**
  ```powershell
  cd bifrost-console\web
  npm run typecheck
  npm run test
  npm run build:web
  ```
- **Full console build (Go embeds web assets)**
  ```powershell
  cd bifrost-console\web
  npm run build:web
  cd ..
  go test ./...
  ```
- **Manual verification commands**
  - `go run ./cmd/bifrost-console` and pair the browser.
  - Use browser DevTools or `curl` with the session cookie to call each new `POST /api/console/v1/...` route.
  - Walk through Overview → Skills → Active Executions → Traces, paginate, open detail, rotate the target, and confirm UI recovery.

## Exit Criteria

- [ ] Pre-implementation failing test (`TestObservabilityServiceReturnsSkillPageFromFixture`) exists and fails before the feature is implemented.
- [ ] `go test ./...` in `bifrost-console/` passes, including new `applicationclient`, `target`, `internal/observability`, and `browserapi` tests.
- [ ] `go vet ./...` in `bifrost-console/` passes.
- [ ] `go test -race ./internal/target/... ./internal/observability/... ./internal/browserapi/...` passes (concurrency-sensitive scope and cancellation tests).
- [ ] `npm run typecheck` and `npm run test` in `bifrost-console/web` pass.
- [ ] `npm run build:web` runs before the Go build and `go test ./...` after embedding passes.
- [ ] New frontend files meet vitest coverage thresholds (80% lines/functions/statements, 70% branches) configured in `web/vitest.config.ts:19-24`.
- [ ] New/updated tests cover the four PR-10 acceptance signals:
  - Pagination races, refresh, direct lookup, target rotation, unavailable live monitoring, and catalog expiry at service and UI levels.
  - Keyboard-available and scope-bound navigation.
  - Browser and shared-service DTOs preserving stable identifiers and direct limitations.
- [ ] Protected ephemeral diagnostic formats remain coherent: Java fixtures, Go DTOs, browser envelopes, and problem-code semantics agree.
- [ ] Internal/accidentally exposed implementation is updated atomically; no obsolete `Probe`-only or 64 KiB-bound paths are left without a caller or shim justification.
- [ ] Application credential never appears in observability responses, browser envelopes, or error messages (test #18).
- [ ] Security headers (`Cache-Control: no-store`, CSP, `X-Frame-Options`, etc.) are applied to all observability route responses (test #19).
- [ ] `resumeCursor` is preserved end-to-end: `active-executions-page.json` fixture → Go `ActivePage` DTO → browser API JSON response → frontend `ActivePage` type (tests #6, #11, #16).
- [ ] Existing `fakeClient` in `target/context_test.go:15-32` is updated to implement the new `ProbeClient.Get` method so existing tests continue to compile and pass.
- [ ] If PR 10 creates new `browser-fixtures/observability/` files, a byte-for-byte corpus test (following `contracts_test.go:23-104`) is added to verify fixture inventory and content.
- [ ] Skill-authoring documentation impact is confirmed as **No impact**; no author-facing contract tests are required.
- [ ] Manual verification steps (pair, navigate, paginate, rotate target, trigger `STALE_CURSOR`) are complete.
