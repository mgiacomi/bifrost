# PR 14 — Trace Explorer Foundation Testing Plan

## Change Summary

- Add internal, session-authenticated browser API projections over the existing
  `traceanalysis.Service`; requests use trace ID while Go resolves the
  current-scope local artifact handle.
- Turn the acquired trace detail into a URL-backed, hierarchy-first explorer
  with selected frame/record/failure context and coordinated Timeline, Usage,
  and Records views.
- Support caller-directed, bounded records, searches, payload ranges, and
  raw-record ranges; raw content is rendered as text only.
- Replace immediate raw attachment download with deliberate confirmation while
  preserving its independent application-stream/cache lifecycle.

## Impacted Areas

- `bifrost-console/internal/browserapi/router.go`, new
  `trace_analysis.go`, and `browserapi.Options`: authenticated routing, scope
  capture/publish, trace-to-handle lookup, DTO serialization, and error mapping.
- `bifrost-console/internal/console/service.go`: production composition root
  wiring of the already existing shared analysis service.
- `bifrost-console/browser-fixtures/trace-analysis/` and
  `internal/browserapi/contracts_test.go`: exact internal browser wire
  projections and TypeScript-consumed current-version coherence.
- `bifrost-console/web/src/api/contracts.ts` and `client.ts`: browser DTO
  mirrors and API calls.
- `bifrost-console/web/src/observability/TraceDetail.tsx`, new explorer state
  and view components, routes, and styles: scope-bound navigation, selection,
  text-only rendering, accessibility, and bounded UI state.
- `bifrost-console/web/e2e/`: paired-session workflow behavior against
  Java-produced trace fixtures.

## Risk Assessment

- **Scope/handle lifecycle**: resolving a trace against a rotated scope,
  removed entry, or expired handle must not display prior evidence. A response
  that finishes after scope rotation must not commit.
- **Authority boundary**: React must not recompute hierarchy, duration, usage,
  retries, failures, or evidence completeness, and browser DTOs must not leak
  opaque handles or local paths.
- **Progressive disclosure and bounds**: loading a trace must not fetch raw or
  reconstructed payload bytes; pages/ranges/searches must preserve their
  service-issued cursor semantics, avoid duplicates, and remain finite.
- **Content security**: payload and raw-record content is untrusted; it must
  render as inert text without markup or generated links.
- **Separate download lifecycle**: confirmation must be required before the
  attachment navigation, and neither confirmation nor download can acquire,
  pin, refresh, or remove the analysis copy.
- **Accessibility**: hierarchy tree keyboard behavior, tab selection, focus on
  route navigation, errors, forced-colors, zoom, and reduced-motion can regress
  independently from data correctness.

### Contract Scope

| Classification | Test treatment |
| --- | --- |
| Application API | No changed Java REST/SSE/artifact/NDJSON boundary; retain the existing Java-produced fixture and raw-download tests rather than introducing protocol compatibility tests. |
| Supported SPI | No change. |
| Configuration and manifest contracts | No change. |
| Persisted or serialized contracts | New current-version browser JSON DTOs require exact fixture inventory coverage and TypeScript shape consumption. No durable/migration compatibility test. |
| Ephemeral diagnostic formats | Verify browser/Go projection coherence, ordering, raw text security, current-scope invalidation, error visibility, and bounded complete inspection; do not test historical trace readability. |
| Internal or accidentally exposed implementation | Add routes and UI state atomically. Do not keep legacy alternate routes, handle-in-URL behavior, or a browser raw-artifact-range operation. |

There are no protected compatibility paths beyond the existing PR 12 raw
download and artifact lifecycle. The approved absence of browser
`ReadRawArtifactRange` is asserted by route inventory tests, not preserved by a
compatibility fallback.

## Existing Test Coverage

- `bifrost-console/internal/traceanalysis/*_test.go` already protects service
  calculations, cursor binding, range encoding, page/range bounds, and finite
  complete reachability; PR 14 must adapt, not duplicate, these semantics.
- `bifrost-console/internal/browserapi/observability_test.go` establishes
  session/no-CSRF read-route, body-limit, canonical DTO, and scoped-response
  conventions. `artifacts_test.go` and `artifact_download_test.go` protect
  acquisition and cache-independent raw streaming.
- `bifrost-console/internal/console/artifact_integration_test.go` already
  exercises production composition, Java fixture acquisition, authentication
  degradation, scope rotation, exact download bytes, and shared analysis
  service queries.
- `TraceDetail.test.tsx` covers existing acquisition, immediate raw link, and
  stale detail route; it must be updated for explorer handoff and confirmation.
- `artifact-storage.spec.ts` provides the paired-console fixture server and raw
  attachment assertions; it is the appropriate foundation for explorer E2E.

Gaps are the browser adapter, DTO fixture corpus, trace-explorer state and
selection behavior, accessibility primitives, deliberate raw/payload readers,
and browser-level proof that raw download stays lifecycle-independent.

## Bug Reproduction / Failing Test First

This is new behavior rather than a regression. Start with one minimal failing
browser-API test before implementing the adapter:

- **Type**: unit (HTTP handler)
- **Location**: `bifrost-console/internal/browserapi/trace_analysis_test.go`
- **Name**: `TestTraceAnalysisSummaryReturnsScopedProjectionForInstalledArtifact`
- **Arrange**: Create the existing router test harness with a captured target
  scope, paired session, installed `nested-frame-usage` fixture artifact, and
  a real `traceanalysis.Service` wired to its artifact service.
- **Act**: POST the planned summary operation with only `traceId`.
- **Assert**: HTTP 200, the returned target scope/trace/session/outcome/root
  frames equal the Go service result, and JSON contains no `artifactHandle` or
  filesystem path.
- **Expected failure (pre-fix)**: the router returns `NOT_FOUND`, because no
  trace-analysis route exists.

Add the first UI failing test immediately after the adapter test:

- **Type**: component
- **Location**: `bifrost-console/web/src/observability/TraceExplorer.test.tsx`
- **Name**: `loadsSummaryAndHierarchyWithoutRequestingPayloadContent`
- **Expected failure (pre-fix)**: no explorer component/client calls exist.
- **Assert**: only summary/initial frame calls run on entry; payload and range
  calls remain untouched until their explicit controls are activated.

## Tests to Add/Update

### 1) Browser adapter exposes Go-owned summary and page projections

- **Type**: unit (HTTP handler)
- **Location**: `bifrost-console/internal/browserapi/trace_analysis_test.go`
- **Names**:
  - `TestTraceAnalysisSummaryReturnsScopedProjectionForInstalledArtifact`
  - `TestTraceAnalysisFramesAndRecordsForwardFiltersAndContinuations`
  - `TestTraceAnalysisFactsAndUsagePreserveOptionalUnknownValues`
- **What it proves**: Read-only routes resolve the local handle from trace ID,
  invoke the matching analysis query, preserve Go ordering/completeness/null
  semantics and opaque cursors, exclude the internal handle, and publish only
  while the captured target scope is current.
- **Fixtures/data**: `nested-frame-usage`, `repeated-skill-invocations`,
  `terminal-failure`, and incomplete-duration fixture bundles installed through
  the existing harness.
- **Mocks**: Real artifact + analysis service where practical; use the router's
  target/session test helpers only for browser security/scoping.
- **Contract classification**: Persisted or serialized contracts; Ephemeral
  diagnostic formats.
- **Compatibility expectation**: Current-version fixture coherence; no durable
  or legacy DTO path.

### 2) Browser adapter rejects unsafe, stale, and unsupported operations

- **Type**: unit (HTTP handler)
- **Location**: `bifrost-console/internal/browserapi/trace_analysis_test.go`
- **Names**:
  - `TestTraceAnalysisRoutesRequireSessionButNotCSRF`
  - `TestTraceAnalysisRoutesRejectMalformedOversizedAndInvalidBodies`
  - `TestTraceAnalysisRejectsMissingExpiredRemovedAndCrossScopeArtifacts`
  - `TestTraceAnalysisDoesNotRegisterRawArtifactRange`
  - `TestTraceAnalysisResponseCannotCommitAfterScopeRotation`
- **What it proves**: Security happens before body read, all parse/body bounds
  match browser conventions, existing domain codes survive adapter mapping,
  stale responses cannot become visible, and PR 14 exposes no browser raw
  artifact-range endpoint.
- **Fixtures/data**: valid and malformed request JSON, an expired/removed
  artifact, scope-rotation synchronization hook, and attempted raw-artifact
  route.
- **Mocks**: Router session/policy helpers; controlled artifact service or
  target publisher only where timing must be deterministic.
- **Contract classification**: Internal or accidentally exposed implementation;
  Ephemeral diagnostic formats.
- **Compatibility expectation**: Approved absence of raw-artifact range; no
  fallback or alias route.

### 3) Range/search adapter preserves deliberate, bounded inspection

- **Type**: unit (HTTP handler)
- **Location**: `bifrost-console/internal/browserapi/trace_analysis_test.go`
- **Names**:
  - `TestTraceAnalysisPayloadRangeRequiresExplicitPayloadIdentifierAndPreservesContinuation`
  - `TestTraceAnalysisRawRecordRangePreservesTextBase64OffsetsAndContinuation`
  - `TestTraceAnalysisSearchPreservesCursorAndRejectsMismatchedCursor`
- **What it proves**: Adapter endpoints do not inline payloads by default,
  preserve text/base64 and actual byte offsets verbatim, and leave cursor
  fingerprint enforcement to the analysis service.
- **Fixtures/data**: chunked JSON payload, binary/non-UTF-8 range test data,
  and a multi-page search/record fixture.
- **Mocks**: Real analysis service to prevent the adapter from accidentally
  redefining range or cursor semantics.
- **Contract classification**: Ephemeral diagnostic formats.
- **Compatibility expectation**: Current-run bounded inspectability, not full
  browser materialization.

### 4) Fixture corpus locks the browser DTO shape

- **Type**: unit/fixture contract
- **Location**: `bifrost-console/internal/browserapi/contracts_test.go`, new
  `bifrost-console/browser-fixtures/trace-analysis/`
- **Name**: `TestBrowserTraceAnalysisFixtureCorpusMatchesCommittedInventoryByteForByte`
- **What it proves**: Representative response JSON has stable casing, nullable
  fields, empty arrays, paging fields, range encoding, and no handle/path leak.
- **Fixtures/data**: Summary, frame page, record page, failure/attempt/validation,
  usage/gap/uncertainty, payload descriptor, text range, base64 range, and
  continuation response fixtures.
- **Mocks**: None; marshal real browser DTOs.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Producer, fixture, TypeScript consumer ship
  atomically for the current console version.

### 5) Explorer route and state do not retain stale selection

- **Type**: component
- **Location**: `bifrost-console/web/src/observability/TraceExplorer.test.tsx`
  and updated `TraceDetail.test.tsx`
- **Names**:
  - `loadsSummaryAndHierarchyWithoutRequestingPayloadContent`
  - `staleExplorerDeepLinkResetsBeforeAnyAnalysisRequest`
  - `responseScopeMismatchClearsExplorerSelectionAndRefreshesTarget`
  - `invalidFrameRecordFailureAndViewParametersAreRemoved`
  - `acquiredTraceOpensExplorerAndExistingLocalArtifactShowsOpenExplorer`
- **What it proves**: URL state is scope-bound and validated; view/selection
  coordinates but never computes evidence; requests are cancelled/reset across
  navigation; handles, payloads, and credentials never enter URLs/state.
- **Fixtures/data**: mocked browser API DTOs for a trace with frames, a failure,
  and a record; deferred promises for response-race coverage.
- **Mocks**: Mock `client.ts`, target provider, session provider, and router as
  existing trace-detail tests do.
- **Contract classification**: Internal or accidentally exposed implementation.
- **Compatibility expectation**: Existing `/traces/:traceId` route remains;
  invalid/stale optional selection is removed rather than interpreted.

### 6) Hierarchy and coordinated view rendering use returned facts only

- **Type**: component
- **Location**: new `TraceHierarchy.test.tsx`, `TraceTimeline.test.tsx`,
  `TraceUsage.test.tsx`, and `TraceRecords.test.tsx`
- **Names**:
  - `hierarchySupportsKeyboardExpansionAndSelectedFrameBreadcrumbs`
  - `repeatedInvocationSelectionPersistsAcrossHierarchyTimelineUsageAndRecords`
  - `timelineAndUsageShowUnknownAndIncompleteFactsWithoutInventingZero`
  - `failureAttemptAndValidationLinksSelectMechanicallyRelatedEvidence`
- **What it proves**: Semantic tree keyboard behavior and selection are
  accessible; selection carries across views; duration/usage/completeness are
  display-only projections; repeated frame IDs/attempt relationships are not
  flattened into inferred causality.
- **Fixtures/data**: repeated-invocation, nested-retry, terminal-failure,
  overlapping/incomplete duration, nested usage expected projections.
- **Mocks**: Component-level returned DTOs only; no independently calculated
  fixture totals in React tests.
- **Contract classification**: Ephemeral diagnostic formats.
- **Compatibility expectation**: Current-run diagnostic coherence.

### 7) Raw and payload content stays inert and continuable

- **Type**: component
- **Location**: `bifrost-console/web/src/observability/TraceEvidenceDetail.test.tsx`
- **Names**:
  - `doesNotFetchPayloadOrRawRecordUntilDeveloperRequestsIt`
  - `rendersTextAndBase64RangeContentWithoutMarkupOrLinks`
  - `continuesFiniteRangesWithoutUnboundedConcatenation`
  - `rangeFailureKeepsPriorSelectionAndShowsPreciseError`
- **What it proves**: Progressive disclosure, plain-text output, base64
  labeling, exact continuation interaction, bounded retained chunks, and error
  behavior for unavailable payloads/stale cursors.
- **Fixtures/data**: malicious `<a>`/Markdown-like strings, text/base64 range
  DTOs, a multi-range payload, and `NOT_FOUND`/`ARTIFACT_EXPIRED` errors.
- **Mocks**: Client range calls and returned continuations.
- **Contract classification**: Ephemeral diagnostic formats.
- **Compatibility expectation**: Complete inspection is deliberate and finite.

### 8) Raw attachment confirmation preserves PR 12 lifecycle

- **Type**: component and e2e
- **Location**: updated `TraceDetail.test.tsx`; focused
  `bifrost-console/web/e2e/trace-explorer.spec.ts` (new) or the existing
  `artifact-storage.spec.ts`
- **Names**:
  - `rawDownloadRequiresConfirmationAndCancellationDoesNotNavigate`
  - `WF-FAILED-EXECUTION trace explorer raw attachment is separate from local analysis`
- **What it proves**: The initial link is not an attachment navigation;
  confirmation is keyboard reachable; cancel creates no navigation; confirmation
  streams exact fixture bytes while Trace Storage/cache accounting remains
  unchanged.
- **Fixtures/data**: existing `single-attempt-success.ndjson` and
  `terminal-failure.ndjson` plus Trace Storage snapshot assertions.
- **Mocks**: Component router navigation mock; real paired console and target
  fixture server for download/cache separation.
- **Contract classification**: Ephemeral diagnostic formats; Internal or
  accidentally exposed implementation.
- **Compatibility expectation**: Protect existing separate raw-download
  lifecycle, not the obsolete immediate-link UI.

### 9) Browser workflow, lifecycle, and accessibility coverage

- **Type**: e2e
- **Location**: new `bifrost-console/web/e2e/trace-explorer.spec.ts`
- **Names**:
  - `WF-FAILED-EXECUTION explore failure hierarchy and supporting evidence`
  - `WF-EXPENSIVE-EXECUTION explore returned usage without recalculation`
  - `WF-UNFAMILIAR-SKILL-PATH navigate nested repeated frames and records`
  - `trace explorer resets stale scope and removed artifact`
  - `trace explorer remains keyboard usable at zoom forced colors and reduced motion`
- **What it proves**: The paired browser sees the coherent trace explorer over
  Java-produced fixtures; hierarchy, timeline, usage, records, payload,
  failure, and links retain selection; lifecycle errors are truthful; and
  baseline keyboard/focus/responsive accessibility works in the actual app.
- **Fixtures/data**: terminal failure, nested retry, repeated invocation,
  chunked payload, incomplete timing, malformed artifact, target rotation, and
  a generated/fixture large page or payload continuation.
- **Mocks**: Existing local target server with per-trace fixture bodies and
  controllable target identity/auth/artifact availability.
- **Contract classification**: Ephemeral diagnostic formats.
- **Compatibility expectation**: Current-scope evidence only; no historical
  bookmark or cache persistence assertion.

## How to Run

From `bifrost-console/`:

```text
go test ./internal/browserapi ./internal/traceanalysis
go test ./internal/console
```

From `bifrost-console/web/`:

```text
npm run typecheck
npm test
npm run test:e2e
```

Then run the canonical complete verification from `bifrost-console/`:

```text
go run ./internal/buildtool verify
```

Use the repository's existing pinned Go/Node/npm versions and the E2E console
fixture process; no external target, credential, or persistent profile is
required.

## Exit Criteria

- [x] The summary adapter failing test fails on the pre-PR-14 route inventory
  and passes only after scoped handler/service wiring exists.
- [x] Browser API tests cover authentication, request bounds, trace-to-handle
  resolution, scope publish race, DTO fixture contract, continuations, and the
  deliberate absence of a browser raw-artifact-range route.
- [x] Existing trace-analysis tests remain green without changing calculation,
  cursor, or range semantics to suit the browser.
- [x] Component tests prove no frontend authoritative calculation, no raw/payload
  prefetch, inert raw rendering, selection persistence, bounded continuation,
  route focus, stale-link reset, and raw-download confirmation.
- [x] Playwright covers hierarchy, repeated invocation, timeline, usage,
  records, payload, failure, cross-view navigation, raw attachment separation,
  stale scope, removal/expiration, and representative keyboard/accessibility
  paths.
- [x] Existing PR 12 raw download/acquisition/cache tests pass, proving the
  independent application-availability and local-analysis-copy lifecycles remain
  intact.
- [x] No Application API, SPI, configuration/manifest, or durable-format
  compatibility test is added because those surfaces are unchanged; new browser
  DTO fixtures are current-version coherent and shipped atomically.
- [x] `go run ./internal/buildtool verify` passes.
- [x] Manual inspection confirms 200% zoom/narrow viewport, forced-colors, and
  reduced-motion behavior preserve visible focus, relationships, and controls.
