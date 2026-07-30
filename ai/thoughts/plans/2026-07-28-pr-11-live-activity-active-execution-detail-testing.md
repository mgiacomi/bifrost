# PR 11 — Live Activity and Active-Execution Detail Testing Plan

## Change Summary

- Add a strict Go client for the existing Java live-activity SSE boundary.
- Activate exactly one background activity coordinator for each established
  target scope.
- Maintain one bounded, in-memory, single-continuity recent-activity interval
  with explicit reset and observation facts.
- Maintain an active-execution baseline and signal periodic/recovery refreshes.
- Expose finite recent-activity queries and bounded authenticated browser relay
  subscriptions over the same coordinator.
- Extend React state and presentation with live active executions, temporary
  recent completions, continuity/freshness notices, and a selected-execution
  summary/path/narrative experience.
- Preserve selection and scroll/follow state through updates and in-place
  terminal transition.

This is a new behavior rather than a bug fix. Test-first work should begin at
the lowest new executable boundary, then add one red invariant test before each
larger layer is implemented.

## Impacted Areas

### Java-to-Go current-release boundary

- `bifrost-console-fixtures/application-sse/`
- `bifrost-console-fixtures/application-rest/`
- `bifrost-console/internal/applicationclient/address.go`
- `bifrost-console/internal/applicationclient/client.go`
- New `bifrost-console/internal/applicationclient/activity.go`
- Existing Java producer tests under:
  - `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/`
  - `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/`

### Target lifecycle and shared service

- `bifrost-console/internal/target/context.go`
- `bifrost-console/internal/target/scope.go`
- `bifrost-console/internal/observability/dto.go`
- `bifrost-console/internal/observability/service.go`
- New activity/window/coordinator files under
  `bifrost-console/internal/observability/`
- `bifrost-console/internal/console/service.go`

### Browser adapter and session lifecycle

- `bifrost-console/internal/browserapi/router.go`
- New `bifrost-console/internal/browserapi/activity.go`
- `bifrost-console/internal/browserauth/sessions.go`
- `bifrost-console/web/vite.config.ts`

### Embedded React client

- `bifrost-console/web/src/api/contracts.ts`
- `bifrost-console/web/src/api/client.ts`
- New `bifrost-console/web/src/api/activityStream.ts`
- `bifrost-console/web/src/observability/ObservabilityProvider.tsx`
- `bifrost-console/web/src/observability/reducer.ts`
- `bifrost-console/web/src/observability/ActiveExecutions.tsx`
- `bifrost-console/web/src/observability/ActiveExecutionDetail.tsx`
- New live-summary, active-path, narrative, and presentation helpers
- `bifrost-console/web/src/styles.css`

### Cross-layer verification

- `bifrost-console/internal/console/observability_integration_test.go`
- New `bifrost-console/web/e2e/live-executions.spec.ts`
- The target application fixture/harness used by Playwright
- `bifrost-console/README.md`

## Risk Assessment

### Critical invariants

1. **No cross-boundary activity**: target rotation, changed `instanceId`, and
   upstream `STALE_CURSOR` must clear the old interval before accepting a new
   envelope.
2. **One upstream owner**: tab count, route changes, and future recent queries
   must not create extra Java subscriptions.
3. **Race-free replay/live handoff**: an envelope arriving while a tab
   subscribes must appear exactly once, either in replay or live delivery.
4. **Nonblocking fan-out**: a slow tab must not delay upstream ingestion or
   another tab.
5. **Authoritative snapshot precedence**: retained envelopes must not be used to
   invent full path, usage, configured limits, or missed state.
6. **Scope security**: late work, released/expired tabs, stale deep links, and
   old-scope responses must not publish diagnostic data.
7. **Protocol strictness**: malformed framing, identity, cursor order, duplicate
   semantics, content encoding, or oversized data must fail visibly rather than
   be silently skipped.
8. **Evidence semantics**: elapsed time, repetition, and silence must remain
   facts; UI copy and state must not manufacture slow/stuck/outage/cause labels.

### High-risk edge cases

- The stream disconnects after headers but in the middle of a UTF-8 sequence or
  SSE frame.
- The first active page is loaded, activity begins, and later high-water pages
  are still being traversed.
- An exact duplicate cursor arrives after reconnect versus a conflicting
  duplicate payload for the same cursor.
- The ring evicts by byte bound before item bound, including an oversized
  single envelope.
- A query filters by `sessionId` while unrelated events cause ring eviction.
- The requested tab cursor has left an otherwise continuous ring.
- `LIMIT_EXCEEDED` is returned when opening the upstream stream.
- `LIVE_MONITORING_UNAVAILABLE` occurs after target identity was established.
- A terminal event is missed but a periodic baseline refresh removes the active
  execution.
- `EXECUTION_OBSERVATION_ENDED` arrives without a trustworthy outcome.
- Target scope changes between browser request validation and first SSE flush.
- Tab release or session expiry races a blocked relay write.
- Provider reconnect, baseline refresh, and target generation reset race in the
  same render cycle.
- Follow mode is paused when new activity and terminal transition arrive.
- Untrusted summaries/details contain HTML, Markdown-looking links, bidi text,
  or very long content.

### Contract classifications and compatibility expectations

| Surface | Classification | Test expectation |
| --- | --- | --- |
| Java REST/SSE activity boundary and canonical fixtures | Ephemeral diagnostic format plus current-release Java-to-Go consumed boundary | Current Java writer/Go reader coherence, exact identity/order/failure visibility, safe bounds, and exact release-string rejection. No historical format reader. |
| Java application API and Spring SPI | Application API / Supported SPI | No change; existing producer and module tests remain green. No new public type or extension point. |
| Bifrost and Console configuration | Configuration and manifest contracts | No change; existing strict config tests remain green and no activity-retention property is accepted. |
| Activity ring/browser state | Internal or accidentally exposed implementation | Test only the new coherent behavior. Do not preserve a nonexistent old relay/window/provider path or add fallback behavior. |
| Browser API DTOs/routes | Internal or accidentally exposed implementation | Go and embedded browser change atomically. Security, bounds, and semantic parity are tested; no cross-release compatibility test is needed. |
| Persisted formats | Persisted or serialized contracts | No impact. Tests must prove restart begins with an empty interval rather than adopting old state. |

### Skill-authoring evidence

The implementation plan classifies skill-authoring documentation impact as
`No impact`. No authoring claim or `ai/skill-authoring/` document requires a new
test. Tests remain focused on Console current-run diagnostics.

## Existing Test Coverage

### Reusable patterns

- `bifrost-console/internal/applicationclient/client_test.go`
  already consumes `instance-status.json`, rejects a nonexact
  `consoleCompatibilityVersion`, checks direct transport/credential behavior,
  and maps committed problem fixtures.
- `bifrost-console/internal/applicationclient/get_test.go`
  checks required headers, redirect/compression rejection, bounded bodies,
  problem mapping, and instance-ID header cardinality for one-shot GETs.
- `bifrost-console/internal/target/context_test.go` and `scope_test.go`
  cover scope rotation, stale probe results, cancellation, owner invalidation,
  instance mismatch, and shared-error mapping.
- `bifrost-console/internal/observability/dto_test.go` and `service_test.go`
  consume canonical application REST fixtures, preserve `resumeCursor`, clamp
  page sizes, validate success payloads, and map stale/not-found/live-unavailable
  results.
- `bifrost-console/internal/browserapi/observability_test.go` covers session
  requirements, read-only CSRF policy, JSON bounds, cache/security headers, and
  canonical DTO mapping.
- `bifrost-console/internal/browserapi/security_integration_test.go` covers
  security-check ordering and independently required session/tab/CSRF inputs.
- `bifrost-console/internal/console/observability_integration_test.go` proves
  application credentials do not leak through browser observability routes.
- `bifrost-console/web/src/observability/ObservabilityProvider.test.tsx` covers
  scope-generation reset, stale request suppression, cursor restart, and
  collection loading.
- Existing reducer/list/detail tests provide the component fixture and
  Testing Library conventions to extend.
- `bifrost-console/web/e2e/target-context.spec.ts` provides the in-process
  target application and real Console/paired-browser pattern.
- `bifrost-console/web/vite.config.test.ts` proves development proxy scope.

### Gaps

- No Go activity endpoint, SSE response-body lifetime, or SSE parser tests.
- No Go activity DTO validation, bounded ring, continuity, query, reconnect,
  subscriber, or periodic-baseline tests.
- No target activation callback or long-lived scoped-stream lifecycle tests.
- No browser SSE route, flush, tab cancellation, or per-tab backpressure tests.
- No incremental frontend SSE decoder or stream reconnect tests.
- No frontend activity/continuity reducer state.
- No recent-completion, narrative, follow/pause, terminal, continuity,
  freshness, or live-unavailable presentation tests.
- No two-tab live workflow, application restart, or terminal live transition
  Playwright scenario.

## Bug Reproduction / Failing Test First

- **Name**: `TestOpenActivityConsumesCanonicalReplayFixture`
- **Type**: Go integration-style unit test using `httptest.Server`
- **Location**:
  `bifrost-console/internal/applicationclient/activity_test.go`
- **Arrange**:
  - Read
    `bifrost-console-fixtures/application-sse/replay.sse`.
  - Serve it with status `200`, exact
    `Content-Type: text/event-stream; charset=UTF-8`, and one matching
    `X-Bifrost-Instance-Id`.
  - Record request method, query, and headers.
- **Act**:
  - Construct the existing direct application client with exact release string
    `0.1.0-SNAPSHOT`.
  - Open activity for the fixture instance after cursor `0`.
  - Read the handshake and two activity frames, then cancel/close the stream.
- **Assert**:
  - Request is authenticated `GET` with exact `instanceId`/`afterCursor`,
    `Accept: text/event-stream`, identity encoding, no-store, and no
    `Last-Event-ID`.
  - Handshake is first and has no delivery ID.
  - Activity cursors are `7`, then `8`; payload cursor and SSE ID agree.
  - `TRACE_COMPLETED` and `EXECUTION_OBSERVATION_ENDED` remain distinct.
  - Cancellation closes the response body promptly.
- **Expected failure on current checkout**:
  compilation fails because `Address.ActivityEndpoint`,
  `Client.OpenActivity`, and the stream reader do not exist. This is the
  smallest test that establishes the missing current-release boundary without
  requiring target lifecycle, coordinator, browser, or React code.

### Subsequent red-test order

After the first boundary test passes, add these red tests before implementing
each next slice:

1. `TestScopeActivityStreamCancelsAndRejectsLateIdentityAfterRotation`
2. `TestRecentActivityResetClearsBeforePostBoundaryAdmission`
3. `TestSubscribeAtomicallyHandsOffReplayToLiveWithoutLossOrDuplicate`
4. `TestActivityStreamRouteDisconnectsOnlyLaggingTab`
5. `provider never combines activity from different continuity intervals`
6. `selected execution remains selected when terminal activity arrives`

Each test should fail for the missing behavior, not because of sleeps or
uncontrolled wall-clock timing.

## Tests to Add or Update

### 1. Canonical upstream activity request and fixture reader

- **Names**:
  - `TestActivityEndpointUsesExactPathAndEncodedQuery`
  - `TestOpenActivityConsumesCanonicalSSEFixtures`
  - `TestOpenActivitySendsRequiredHeadersAndCredentialOnce`
  - `TestOpenActivityDoesNotApplyOneShotBodyDeadline`
  - `TestOpenActivityCancellationClosesBody`
- **Type**: Go unit/integration with `httptest.Server`
- **Location**:
  `bifrost-console/internal/applicationclient/address_test.go`,
  new `bifrost-console/internal/applicationclient/activity_test.go`
- **What it proves**:
  exact URL/header/authentication behavior, response-body lifetime, fixture
  framing, and cancellation.
- **Fixtures/data**:
  all committed `bifrost-console-fixtures/application-sse/*.sse`.
- **Mocks**:
  real `httptest.Server`; injected/chunked response writer where needed.
- **Contract classification**: Ephemeral diagnostic format / consumed
  Java-to-Go boundary.
- **Compatibility expectation**: current-run diagnostic coherence.

### 2. Strict SSE and upstream failure rejection

- **Name**: `TestOpenActivityRejectsMalformedOrUnsafeResponses`
- **Type**: table-driven Go unit test
- **Location**:
  `bifrost-console/internal/applicationclient/activity_test.go`
- **What it proves**:
  rejects redirect, encoded response, wrong/missing content type,
  missing/duplicate/invalid instance header, missing or duplicate handshake,
  activity before handshake, unknown event, `Last-Event-ID`-style ambiguity,
  mismatched SSE ID/payload cursor, invalid JSON, duplicate data/id fields,
  oversized line/frame/data, truncated UTF-8/frame, cursor regression, and
  trailing unsupported fields when the contract requires strictness.
- **Fixtures/data**:
  inline minimal invalid frames; committed problem fixtures for
  `STALE_CURSOR`, `LIMIT_EXCEEDED`, `LIVE_MONITORING_UNAVAILABLE`,
  authentication, invalid cursor, and generic application failure.
- **Mocks**: `httptest.Server`.
- **Contract classification**: Ephemeral diagnostic format.
- **Compatibility expectation**: current-run diagnostic accuracy, bounds, and
  failure visibility; no permissive legacy parser.

### 3. Exact compatibility gate remains authoritative

- **Names**:
  - update `TestClientConsumesCommittedInstanceFixtureOnlyAfterExactCompatibility`
  - `TestIncompatibleReleaseNeverOpensActivityStream`
- **Type**: Go unit and console integration
- **Location**:
  `bifrost-console/internal/applicationclient/client_test.go`,
  `bifrost-console/internal/console/observability_integration_test.go`
- **What it proves**:
  `0.1.0` remains incompatible with exact expected
  `0.1.0-SNAPSHOT`; the coordinator cannot bypass the instance probe and never
  requests `/activity` after mismatch.
- **Fixtures/data**:
  `application-rest/instance-status.json` with a test-mutated mismatch.
- **Mocks**:
  `httptest.Server` request recorder.
- **Contract classification**: Current-release Java-to-Go compatibility gate.
- **Compatibility expectation**: protected exact release-string rejection.

### 4. Activity DTO validation and size accounting

- **Names**:
  - `TestActivityEnvelopeDecodesCanonicalFrames`
  - `TestActivityEnvelopeRejectsInvalidIdentityCursorAndBounds`
  - `TestActivitySerializedWeightUsesCompleteEnvelopeBytes`
- **Type**: table-driven Go unit
- **Location**:
  `bifrost-console/internal/observability/dto_test.go`,
  new `bifrost-console/internal/observability/activity_test.go`
- **What it proves**:
  all 18 activity kinds decode, optional frame fields remain optional,
  timestamps/cursors/identities are validated, untrusted details stay data, and
  byte accounting cannot be defeated by a small item count.
- **Fixtures/data**:
  canonical SSE fixtures plus generated bounded/oversized envelopes. Generate
  boundary data in the test; do not add duplicate semantic fixture files.
- **Mocks**: none.
- **Contract classification**: Ephemeral diagnostic format.
- **Compatibility expectation**: current-run diagnostic coherence.

### 5. Target activation and scoped stream lifetime

- **Names**:
  - `TestOwnerActivatesOnlyAfterIdentityCommit`
  - `TestOwnerActivationAndInvalidationRemainPaired`
  - `TestScopeActivityStreamUsesCapturedCredentialAndIdentity`
  - `TestScopeActivityStreamCancelsOnRotation`
  - `TestLateActivationOrFrameCannotPublishAfterRotation`
  - `TestOwnerCallbacksRunOutsideTargetLock`
- **Type**: deterministic Go unit/concurrency
- **Location**:
  `bifrost-console/internal/target/context_test.go`,
  `bifrost-console/internal/target/scope_test.go`
- **What it proves**:
  lifecycle ordering, credential containment, old-scope cancellation,
  mismatch revalidation, stale result rejection, and absence of target-lock
  callback deadlock.
- **Fixtures/data**:
  channel-controlled fake probe/activity client and fixed scope IDs.
- **Mocks**:
  existing fake-client pattern extended with explicit started/release/closed
  channels; no sleeps.
- **Contract classification**: Internal implementation.
- **Compatibility expectation**: new coherent lifecycle; update all fakes
  atomically with no optional legacy interface.

### 6. Ring bounds, ordering, duplicates, and reset

- **Names**:
  - `TestRecentActivityEvictsOldestByCountAndBytes`
  - `TestRecentActivityIgnoresExactDuplicate`
  - `TestRecentActivityRejectsConflictingDuplicateAndRegression`
  - `TestRecentActivityResetClearsBeforePostBoundaryAdmission`
  - `TestRecentActivityNeverReturnsMultipleIntervals`
  - `TestRecentActivityShutdownLeavesNoAdoptableState`
- **Type**: Go unit
- **Location**:
  `bifrost-console/internal/observability/live_service_test.go`
- **What it proves**:
  2,048-item/8-MiB dual bounds, exact duplicate semantics, cursor monotonicity,
  clear-before-admit for every reset cause, single-interval results, and
  process-memory-only retention.
- **Fixtures/data**:
  small envelope factory with explicit encoded sizes and injected interval IDs,
  cursor source, and clock.
- **Mocks**: none beyond injected deterministic sources.
- **Contract classification**: Internal implementation carrying ephemeral
  diagnostic semantics.
- **Compatibility expectation**: current-run diagnostic ordering and
  continuity; no old interval or restart adoption.

### 7. Recent query semantics reusable by future MCP

- **Names**:
  - `TestRecentActivityQueryFiltersExecutionAndCursorInDeliveryOrder`
  - `TestRecentActivityQueryClampsPageSizeAndContinues`
  - `TestRecentActivityQueryReportsEvictedBeginningAsFact`
  - `TestRecentActivityQueryCarriesObservationAndResetFacts`
  - `TestRecentActivityQueryRejectsStaleScopeAndMalformedCursor`
- **Type**: Go unit
- **Location**:
  `bifrost-console/internal/observability/live_service_test.go`
- **What it proves**:
  finite default/max page size, deterministic continuation, session filtering,
  explicit suffix/gap semantics, target scope, observed range/time, and
  adapter-neutral DTOs.
- **Fixtures/data**:
  multiple sessions interleaved in one interval; eviction and reset cases.
- **Mocks**: injected clock only.
- **Contract classification**: Internal transport-neutral service.
- **Compatibility expectation**: one shared current behavior for browser and
  future MCP; no adapter-specific history.

### 8. Atomic replay/live subscription and backpressure

- **Names**:
  - `TestSubscribeAtomicallyHandsOffReplayToLiveWithoutLossOrDuplicate`
  - `TestSubscribersMaintainIndependentCursors`
  - `TestSlowSubscriberDoesNotBlockWindowOrPeer`
  - `TestSubscriberOverflowReturnsLocalGapWithoutSharedReset`
  - `TestSubscriberCancellationRemovesPendingDelivery`
- **Type**: deterministic Go concurrency unit
- **Location**:
  `bifrost-console/internal/observability/live_service_test.go`
- **What it proves**:
  atomic registration/replay snapshot, per-tab queue bounds of 256 frames and
  1 MiB, independent failure, and prompt cleanup.
- **Fixtures/data**:
  channel barriers around the replay/subscription critical section; envelopes
  sized to trigger frame versus byte overflow separately.
- **Mocks**:
  controlled subscribers and contexts; no socket buffer assumptions or sleeps.
- **Contract classification**: Internal implementation.
- **Compatibility expectation**: new coherent fan-out semantics.

### 9. Coordinator baseline, stream, reconnect, and recovery

- **Names**:
  - `TestCoordinatorStartsStreamFromFirstPageResumeCursorWhilePaging`
  - `TestCoordinatorOwnsOneUpstreamStreamRegardlessOfSubscribers`
  - `TestCoordinatorRefreshesBaselineEveryThirtySeconds`
  - `TestCoordinatorReconnectsFromLastAcceptedCursor`
  - `TestCoordinatorRetriesLimitExceededWithoutRotatingOrResetting`
  - `TestCoordinatorClearsAndRebaselinesOnStaleCursor`
  - `TestCoordinatorClearsAndRebaselinesOnInstanceChange`
  - `TestCoordinatorStopsForLiveMonitoringUnavailable`
  - `TestCoordinatorReconcilesMissedCompletionFromBaseline`
  - `TestCoordinatorPreservesObservationEndedWithoutInventedOutcome`
- **Type**: Go unit/integration
- **Location**:
  `bifrost-console/internal/observability/live_service_test.go`,
  `bifrost-console/internal/console/observability_integration_test.go`
- **What it proves**:
  the first-page cursor seam, paging/stream race behavior, single connection,
  injected periodic clock, capped retry decisions, reset ordering, baseline
  signal, missed terminal reconciliation, and finalization-failure semantics.
- **Fixtures/data**:
  scripted fake active-page source and activity stream; canonical terminal SSE
  fixtures.
- **Mocks**:
  injected ticker, jitter/backoff, and channel-scripted transport. Assert retry
  sequence directly rather than waiting wall-clock time.
- **Contract classification**: Internal coordinator consuming ephemeral
  diagnostics.
- **Compatibility expectation**: current-run continuity and failure visibility.

### 10. Console construction and shutdown ownership

- **Names**:
  - `TestConsoleRegistersOneActivityOwnerBeforeServing`
  - `TestConsoleWithNoTargetDoesNotOpenActivity`
  - `TestConsoleShutdownCancelsActivityAndSubscribers`
- **Type**: Go integration
- **Location**:
  `bifrost-console/internal/console/observability_integration_test.go`
- **What it proves**:
  correct construction order, zero subscription without established scope, one
  owner/service instance, and complete shutdown cleanup.
- **Fixtures/data**:
  real `target.Context` with recorded application server.
- **Mocks**:
  temporary profile/workspace and `httptest.Server`, following current console
  integration patterns.
- **Contract classification**: Internal implementation.
- **Compatibility expectation**: no obsolete constructor or optional activity
  service fallback.

### 11. Browser route authentication, framing, and bounds

- **Names**:
  - `TestActivityRoutesRequireSessionAndRegisteredTab`
  - `TestActivityRoutesAreReadOnlyAndDoNotRequireCSRF`
  - `TestActivityRoutesRejectInvalidOrOversizedSubscribeBodies`
  - `TestActivityRecentReturnsCanonicalContinuityDTO`
  - `TestActivityStreamFlushesHandshakeReplayAndLiveInOrder`
  - `TestActivityStreamSeparatesBifrostAndConsoleNamespaces`
  - `TestActivityStreamUsesNoStoreAndNosniff`
- **Type**: Go handler unit/integration
- **Location**:
  new `bifrost-console/internal/browserapi/activity_test.go`,
  `bifrost-console/internal/browserapi/security_integration_test.go`,
  `bifrost-console/internal/browserapi/contracts_test.go`
- **What it proves**:
  browser-realm security, POST JSON request contract, prompt SSE flushing,
  semantic namespace separation, response security headers, scope checks, and
  finite DTO mapping.
- **Fixtures/data**:
  canonical activity envelopes and table-driven `console.*` events.
- **Mocks**:
  fake recent-query/subscription service with channel-controlled delivery;
  `httptest.ResponseRecorder` plus a flush-capable recorder where necessary.
- **Contract classification**: Internal browser adapter.
- **Compatibility expectation**: Go/assets atomic current behavior.

### 12. Tab release, expiry, and local overflow

- **Names**:
  - `TestTabReleaseCancelsActivityStream`
  - `TestTabExpiryCancelsActivityStream`
  - `TestActivityStreamOverflowDisconnectsOnlyLaggingTab`
  - `TestTargetScopeMismatchStopsBeforeDiagnosticFlush`
- **Type**: Go integration/concurrency
- **Location**:
  `bifrost-console/internal/browserauth/sessions_test.go`,
  `bifrost-console/internal/browserapi/activity_test.go`
- **What it proves**:
  lifecycle-driven cancellation, no polling leak, independent tab failure, and
  no old-scope diagnostic response.
- **Fixtures/data**:
  injected session clock and two subscribed tab registrations.
- **Mocks**:
  controlled writers and session registry clock.
- **Contract classification**: Internal security implementation.
- **Compatibility expectation**: preserve paired-browser authority boundaries.

### 13. Development proxy remains local and narrow

- **Name**:
  update `development config binds loopback and proxies only console paths`
- **Type**: TypeScript unit
- **Location**: `bifrost-console/web/vite.config.test.ts`
- **What it proves**:
  `/api/console/` including activity streaming is proxied only to the configured
  loopback Go origin; `/_bifrost/observability/` remains unavailable to the
  browser.
- **Fixtures/data**: configuration values only.
- **Mocks**: none.
- **Contract classification**: Internal development configuration.
- **Compatibility expectation**: preserve security boundary; no direct browser
  application access.

### 14. Incremental frontend SSE decoder

- **Names**:
  - `decodes split utf8 and split sse fields incrementally`
  - `emits only complete validated namespaced frames`
  - `rejects oversized malformed duplicate and unknown frames`
  - `aborting stream stops parsing without reconnect classification`
- **Type**: TypeScript unit
- **Location**:
  new `bifrost-console/web/src/api/activityStream.test.ts`
- **What it proves**:
  chunk-safe parsing, bounds, exact event names, JSON validation, abort
  distinction, and no interpretation of text content.
- **Fixtures/data**:
  encoded canonical frames split at every relevant delimiter and inside a
  multibyte code point; inline invalid cases.
- **Mocks**:
  `ReadableStream<Uint8Array>` with deterministic chunks and `AbortController`.
- **Contract classification**: Internal browser adapter over ephemeral
  diagnostics.
- **Compatibility expectation**: current Go/browser coherence; no legacy event
  alias.

### 15. Activity reducer continuity and snapshot precedence

- **Names**:
  - `reducer appends ordered activity once per interval cursor`
  - `reducer clears old interval before accepting reset activity`
  - `reducer reports local gap without resetting shared continuity`
  - `snapshot refresh replaces authoritative summary without losing selection`
  - `terminal activity moves active execution to recent completion in place`
  - `observation ended does not invent outcome or trace availability`
- **Type**: TypeScript unit
- **Location**:
  `bifrost-console/web/src/observability/reducer.test.ts`
- **What it proves**:
  deduplication, one-interval state, local/shared gap distinction, authoritative
  snapshot precedence, stable list insertion order, and terminal semantics.
- **Fixtures/data**:
  typed execution/activity builders for two intervals and sessions.
- **Mocks**: none.
- **Contract classification**: Internal browser state.
- **Compatibility expectation**: new coherent state model; no parallel legacy
  reducer.

### 16. Provider stream lifecycle and recovery

- **Names**:
  - `provider starts one stream after first active baseline`
  - `provider reconnects after last applied cursor with bounded backoff`
  - `provider refreshes list and selected detail on baseline signal`
  - `provider refreshes only current tab after local gap`
  - `provider aborts stream and pending loads on scope generation`
  - `provider does not reconnect while live monitoring is unavailable`
  - `late old interval response cannot overwrite current state`
- **Type**: React/Vitest integration
- **Location**:
  `bifrost-console/web/src/observability/ObservabilityProvider.test.tsx`
- **What it proves**:
  stream ownership, cursor resume, timer behavior, refresh coalescing,
  unavailable state, and generation/interval stale-work rejection.
- **Fixtures/data**:
  deferred promises and scripted async frame iterator.
- **Mocks**:
  mocked API/stream functions, fake timers, existing `useTarget` test harness.
- **Contract classification**: Internal browser state/lifecycle.
- **Compatibility expectation**: preserve target-scope reset guarantees.

### 17. Live collections and terminology

- **Names**:
  - `active and recent completion collections remain distinct`
  - `live updates do not reorder active rows`
  - `recent completions are labeled temporary`
  - `elapsed and silent activity never produce health labels`
- **Type**: React component
- **Location**:
  `bifrost-console/web/src/observability/ActiveExecutions.test.tsx`
- **What it proves**:
  lifecycle separation, stable ordering, temporary retention language, and
  guardrail terminology.
- **Fixtures/data**:
  multiple active rows plus terminal events and time progression.
- **Mocks**:
  provider context values and fake timers.
- **Contract classification**: Internal current-run diagnostic presentation.
- **Compatibility expectation**: diagnostic usefulness without causal claims.

### 18. Selected live execution and all activity kinds

- **Names**:
  - `selected execution renders current summary path and recent narrative`
  - `each activity kind has a distinct concise presentation`
  - `untrusted activity content renders only as text`
  - `selected execution stays selected through live and terminal updates`
  - `trace completed separates outcome and artifact availability`
  - `observation ended presents incomplete observation without outcome`
  - `inspect trace appears only for available finalized trace`
  - `active path is labeled bounded and exposes truncation`
- **Type**: React component/table-driven
- **Location**:
  `bifrost-console/web/src/observability/ActiveExecutionDetail.test.tsx`,
  new `activityPresentation.test.ts`
- **What it proves**:
  complete 18-kind coverage, safe untrusted content, selected-context
  preservation, correct terminal/finalization semantics, and no complete-tree
  implication.
- **Fixtures/data**:
  one typed envelope per kind, including skill `FRAME_OPENED`/`FRAME_CLOSED`,
  canonical terminal fixture facts, HTML/Markdown-looking strings.
- **Mocks**:
  provider context and route parameter; no DOM HTML injection helper.
- **Contract classification**: Ephemeral diagnostic presentation.
- **Compatibility expectation**: current Java/Go/browser semantic coherence.

### 19. Follow mode, focus, announcements, and motion

- **Names**:
  - `narrative follows newest item initially`
  - `scrolling backward or selecting earlier item pauses following`
  - `paused following preserves selection and scroll while summary updates`
  - `resume live focuses or reveals newest activity deliberately`
  - `announces reset disconnect and terminal changes but not timer increments`
  - `reduced motion suppresses new-row highlight`
- **Type**: React component
- **Location**:
  new `bifrost-console/web/src/observability/ActivityNarrative.test.tsx`,
  `bifrost-console/web/src/observability/ActiveExecutionDetail.test.tsx`
- **What it proves**:
  follow/pause contract, no stolen focus/scroll, restrained live-region use,
  keyboard behavior, and reduced-motion support.
- **Fixtures/data**:
  controlled scroll metrics, incoming frame sequence, matchMedia variants.
- **Mocks**:
  `scrollIntoView`, element scroll dimensions, `window.matchMedia`; assert calls
  and selected DOM identity rather than relying on jsdom layout.
- **Contract classification**: Internal accessible presentation.
- **Compatibility expectation**: Phase 2 accessibility/current-context
  behavior.

### 20. End-to-end slow-execution and continuity workflows

- **Names**:
  - `WF-SE live execution preserves selection while activity advances`
  - `WF-SE one slow tab does not delay another tab`
  - `WF-SE application restart creates a new continuity interval`
  - `WF-SE terminal and observation-ended transitions remain in place`
  - `WF-SE target change discards prior live state`
- **Type**: Playwright end-to-end
- **Location**:
  new `bifrost-console/web/e2e/live-executions.spec.ts`,
  extend the existing target application fixture
- **What it proves**:
  real paired session, embedded assets, Go coordinator, browser relay, two-tab
  independence, restart/reset, stable route/selection, terminal semantics, and
  no direct browser requests to the Java namespace.
- **Fixtures/data**:
  deterministic target server with controllable instance ID, active pages, SSE
  clients, replay/stale responses, completion, and finalization failure.
- **Mocks**:
  local HTTP target application only; run the real Console executable and real
  browser.
- **Contract classification**: Cross-layer current-run diagnostic behavior.
- **Compatibility expectation**: Java-to-Go-to-browser coherence and security.

## Acceptance-Signal Traceability

| Ticket/plan signal | Primary automated evidence |
| --- | --- |
| Replay and duplicate delivery | Tests 1, 6, 8, 9, 15 |
| Gap reset and no mixed intervals | Tests 6, 7, 9, 15, 20 |
| Reconnect | Tests 9, 16, 20 |
| Tab backpressure | Tests 8, 12, 20 |
| Missed completion | Tests 9 and 16 |
| Finalization failure | Tests 1, 9, 15, 18, 20 |
| Target change | Tests 5, 9, 12, 16, 20 |
| Stable selected execution | Tests 15, 18, 19, 20 |
| Reusable recent query seam | Test 7 |
| One upstream subscription | Tests 9, 10, 20 |
| Exact release rejection | Test 3 |
| Browser/credential boundary | Tests 1, 11, 12, 13, 20 |
| Accessibility and reduced motion | Tests 19 and manual verification |

## How to Run

All commands are from the repository root unless a `cd` is shown.

### Fast red/green loops

```powershell
cd bifrost-console
go test ./internal/applicationclient -run TestOpenActivity
go test ./internal/target -run 'TestOwner|TestScopeActivity'
go test ./internal/observability -run 'TestRecentActivity|TestSubscribe|TestCoordinator'
go test ./internal/browserapi ./internal/browserauth -run 'TestActivity|TestTab'
```

```powershell
cd bifrost-console/web
npm test -- src/api/activityStream.test.ts
npm test -- src/observability/reducer.test.ts
npm test -- src/observability/ObservabilityProvider.test.tsx
npm test -- src/observability/ActiveExecutions.test.tsx src/observability/ActiveExecutionDetail.test.tsx
```

### Layer suites

```powershell
cd bifrost-console
go test ./internal/applicationclient ./internal/target ./internal/observability ./internal/browserauth ./internal/browserapi ./internal/console
go test -race ./internal/applicationclient ./internal/target ./internal/observability ./internal/browserauth ./internal/browserapi ./internal/console
```

```powershell
cd bifrost-console/web
npm run typecheck
npm test
npm run test:e2e
```

### Existing Java producer coherence

The Java producer is unchanged, but its current-release projection, replay, and
delivery behavior remains part of the consumed boundary:

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter '-Dtest=LiveActivityProjectorTest,InMemoryActivityReplayBufferTest,ObservabilityActivityStreamTest,ObservabilityActivityDeliveryTest,ConsoleSseFixtureCorpusTest,ConsoleRestFixtureCorpusTest' test
```

Do not run fixture regeneration for ordinary verification. If implementation
finds a genuine producer/fixture discrepancy, update Java, Go, fixtures, and
tests atomically, then run the repository's documented regeneration command
and review the exact fixture diff.

### Canonical Console verification

```powershell
cd bifrost-console
go run ./internal/buildtool verify
```

This verifies exact toolchain patches, locked frontend installation,
typechecking, frontend coverage, embedded assets, and all Go tests.

### Required environment and test data

- Go `1.26.5`, Node.js `24.18.0`, and npm `12.0.2`, as declared by
  `bifrost-console/README.md`.
- Canonical fixtures under `bifrost-console-fixtures/`; tests must locate them
  repository-relatively and must not copy their semantic payloads into another
  fixture corpus.
- No external target, credential, network service, or environment proxy is
  required for automated tests.
- Playwright uses loopback processes and temporary profile/work directories.
  Tests must allocate ports dynamically and close streams/processes even after
  failure.
- Clocks, tickers, backoff, and jitter are injected in unit/integration tests.
  Do not use production 30-second refresh intervals or nondeterministic sleeps.

## Manual Verification

1. Start Console against a compatible application with live monitoring
   available and begin a nested execution.
2. Confirm Live Executions shows active and recent completion collections
   separately and does not classify elapsed execution as slow or stuck.
3. Open the execution and confirm the sticky summary, bounded active path,
   ordered recent narrative, freshness, and continuity state update.
4. Scroll backward or select an older item, generate new activity, and verify
   the summary updates while selection, focus, and scroll remain unchanged.
   Activate Resume live and confirm deliberate return to the newest activity.
5. Open a second paired tab, throttle/pause its network processing, and verify
   the first tab and one upstream application subscription continue.
6. Temporarily disconnect and reconnect the application. Confirm retained state
   is marked stale during disconnection and replay resumes without a false gap
   when the cursor remains available.
7. Force upstream `STALE_CURSOR` or restart with a changed `instanceId`.
   Confirm the old narrative is cleared before the new interval and a reset
   divider/fact is visible.
8. Complete one execution normally and one through
   `EXECUTION_OBSERVATION_ENDED`/`CORE_FINALIZATION_FAILED`. Confirm both remain
   selected in place, have distinct terminal semantics, and do not navigate
   automatically.
9. Change the selected target while detail is open. Confirm old activity,
   completions, selection, and routes are discarded and the app returns to
   Overview.
10. Repeat the detail workflow at 200% zoom, 320 CSS pixels wide, keyboard-only,
    forced-colors mode, and reduced-motion mode. Confirm readable layout,
    visible focus, restrained announcements, and no essential animation.

## Exit Criteria

- [ ] `TestOpenActivityConsumesCanonicalReplayFixture` is added first and
  observed failing on the pre-feature checkout.
- [ ] Each subsequent layer begins with the named red invariant test and passes
  after its owning implementation is added.
- [ ] Canonical REST/SSE fixtures are consumed directly with no duplicate
  semantic fixture corpus.
- [ ] Exact `consoleCompatibilityVersion` mismatch still prevents all live
  activity access.
- [ ] Java projection/replay/delivery fixture coherence tests pass without a
  historical or permissive reader.
- [ ] Scope rotation, changed instance, upstream stale cursor, and shutdown all
  clear before post-boundary admission.
- [ ] Every successful recent query and browser narrative contains activity
  from exactly one continuity interval.
- [ ] Exact duplicates are harmless; conflicting duplicates, regression, and
  malformed/oversized frames fail explicitly.
- [ ] Ring, query, per-tab frame, and per-tab byte bounds are each exercised at
  and just beyond their boundary.
- [ ] Replay-to-live subscription is proven lossless/nonduplicating under a
  controlled race.
- [ ] A lagging tab cannot block upstream ingestion or another tab and does not
  reset shared continuity.
- [ ] There is exactly one upstream stream per established target scope,
  independent of browser tabs and recent queries.
- [ ] `LIMIT_EXCEEDED`, ordinary disconnect, `STALE_CURSOR`, changed instance,
  target change, and `LIVE_MONITORING_UNAVAILABLE` remain distinguishable.
- [ ] Baseline/activity race, periodic refresh, missed completion, normal
  completion, and core-finalization failure are covered.
- [ ] Browser stream routes enforce Host, Origin, paired session, registered tab,
  no-store, response bounds, and cancellation; secrets never enter URLs or
  payloads.
- [ ] Browser activity and `console.*` lifecycle events remain separately
  namespaced and presented.
- [ ] Provider and reducer tests prove old generations/intervals cannot overwrite
  current state and snapshots remain authoritative for full summary facts.
- [ ] All 18 activity kinds have safe, concise, text-only presentation coverage.
- [ ] Selection, focus, scroll, follow/pause/resume, terminal transition,
  live-region announcements, and reduced motion are covered.
- [ ] Existing strict Console configuration tests pass and no undocumented
  activity-retention setting is accepted.
- [ ] No obsolete/parallel activity client, browser-owned upstream connection,
  adapter-specific history, compatibility shim, or legacy parser remains.
- [ ] Focused Go suites and race detector pass.
- [ ] Frontend typecheck and Vitest suites pass.
- [ ] Playwright live workflow scenarios pass.
- [ ] Canonical `go run ./internal/buildtool verify` passes.
- [ ] Manual two-tab, restart/reset, terminal, target-change, responsive,
  keyboard, forced-colors, zoom, and reduced-motion verification is complete.

## References

- Implementation plan:
  `ai/thoughts/plans/2026-07-28-pr-11-live-activity-active-execution-detail.md`
- Ticket:
  `ai/thoughts/tickets/bifrost-console-pr-11-live-execution-experience.md`
- Research:
  `ai/thoughts/research/2026-07-28-pr-11-live-activity-active-execution-detail.md`
- Phase 2 design:
  `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md`
- Slow-execution workflow:
  `ai/thoughts/phases/bifrost_console_workflows.md`
- Current-release fixture contract:
  `bifrost-console-fixtures/README.md`
