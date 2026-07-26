# Bifrost Console PR 05 Live SSE Delivery Testing Plan

## Change Summary

- Add authenticated `GET /_bifrost/observability/v1/activity` with required
  `instanceId` and `afterCursor`, an initial `handshake` event, and ordered
  `activity` events.
- Extend the current-run activity projection with `parentFrameId` and optional
  truthful `executionStatus`, then map it into an instance-scoped external DTO
  rather than serializing the runtime record.
- Add a constant-time observation-to-delivery signal, bounded replay
  dispatcher, fixed process admission, bounded per-subscriber pending
  delivery, non-blocking servlet output, write-readiness deadlines, and
  idempotent subscriber cleanup.
- Close open streams after replay gaps, subscriber overflow, write deadline,
  client cancellation, live-projection failure, adapter shutdown, or container
  failure without affecting execution or unrelated clients.
- Preserve existing REST, problem, configuration, skill, trace-catalog,
  canonical trace, and exact release-string behavior.

The highest-risk correctness arguments are concurrency isolation, replay/
subscribe races, pre-stream JSON versus post-start closure, exactly-once
admission release, and deterministic shutdown. Tests must make those arguments
directly rather than relying on broad happy-path coverage.

## Impacted Areas

### Runtime observation

- `ExecutionActivity` structural identity, bounds, cursor-copy, and terminal
  enrichment.
- `LiveActivityProjector` parent identity and execution-status projection.
- `DefaultExecutionObservationHandle` successful-append and first-failure
  signaling.
- `DefaultExecutionObservationHandleFactory` internal constructor graph.
- Existing replay, fail-closed, terminal activity, and concurrent-execution
  tests.

### Delivery lifecycle

- New fixed delivery limits.
- New coalescing `LiveActivitySignal`.
- New process-owned dispatcher, subscriber registry, replay batching,
  serialized-frame sharing, queue accounting, admission, deadline scheduler,
  and close state machine.
- `ObservabilityRuntime` startup rollback and close order.

### Servlet/Spring boundary

- Exact route and `ACTIVITY_SUBSCRIBE` authorization.
- Request, `Accept`, identity, and cursor validation.
- Servlet `AsyncContext`, `AsyncListener`, `ServletOutputStream`, and
  `WriteListener` ownership.
- UTF-8 SSE framing, response headers, context path, authentication filter, and
  committed-response behavior.
- Route registration, rollback, and infrastructure classification.

### Cross-component protocol

- Exact event names, field names, omission policy, cursor string rendering, SSE
  `id`, problem status/code mapping, and lack of
  `consoleCompatibilityVersion` inside stream events.
- Preservation of exact instance-status `consoleCompatibilityVersion`.
- PR 05 Java semantic/framing evidence only; PR 06 remains responsible for the
  committed SSE fixture corpus, and PR 11 remains responsible for the Go
  consumer.

### Deliberately unaffected areas

- `BifrostProperties.Observability` retains only `enabled`, `auth.api-key`,
  `completion-grace-ttl`, and `trace-catalog-metadata-ttl`.
- Skill manifests and skill-authoring guidance do not change.
- Canonical trace writer/reader and committed trace fixtures do not change.
- Existing REST fixture bytes do not change.
- No application API, supported SPI, replacement Spring bean, WebFlux path,
  `SseEmitter` path, `Last-Event-ID`, heartbeat, or compatibility shim is added.

## Risk Assessment

### Critical risks

1. **Execution coupling**: subscriber iteration, JSON serialization, servlet
   calls, executor backpressure, or a blocking signal accidentally runs under
   a trace/session/observation lock.
2. **Lost or reordered activity**: a publication between replay validation,
   subscriber registration, handshake, and first dispatch is missed or
   delivered out of cursor order.
3. **Unbounded or unfair pressure**: a slow client grows memory, occupies a
   shared write thread, delays another subscriber, or blocks execution.
4. **Admission leak/underflow**: overlapping completion, error, timeout,
   overflow, cancellation, initialization failure, and shutdown callbacks
   release a slot zero or multiple times.
5. **Incorrect response boundary**: a stale/capacity/unavailable request starts
   SSE before returning its JSON problem, or a post-`startAsync()` failure
   attempts to replace the stream with JSON.
6. **Deadline race**: a cancelled deadline for frame N closes the stream while
   frame N+1 is current, or a busy dispatcher delays deadline enforcement.
7. **Shutdown leak**: admitted subscribers, pending frames, deadline tasks, or
   Bifrost delivery threads survive runtime close.

### High risks

- `parentFrameId` or execution status is missing, unbounded, copied
  incorrectly, or invented for `EXECUTION_OBSERVATION_ENDED`.
- Decimal cursor JSON differs from SSE `id`, overflows, or is emitted as a JSON
  number.
- Replay `TOO_OLD`, `FUTURE`, and mismatched instance are assigned the wrong
  stable problem.
- Handshake is not the first frame or is assigned an activity ID.
- Event and byte bounds disagree because framing overhead is excluded.
- Shared serialized bytes are charged only once globally instead of once per
  subscriber.
- First live failure closes only some streams, closes unrelated skill/trace
  operations, or emits a synthetic control activity.
- A client disconnect is not observed until another event arrives.
- Async redispatch loses no-store/instance metadata or leaks the temporary
  Bifrost operator security context.
- Partial activation failure leaves threads/resources behind.

### Edge cases

- Cursor `0`, current cursor, `Long.MAX_VALUE`, `Long.MAX_VALUE + 1`, negative,
  signed, whitespace-padded, empty, repeated, and non-decimal values.
- Malformed versus well-formed-but-different instance UUID.
- Missing, wildcard, valid, quality-zero, malformed, and incompatible
  `Accept` values.
- Missing, duplicate, invalid, and valid API-key headers while admission is
  saturated.
- Empty replay, exactly one batch, batch plus one, queue exactly at each bound,
  and the first enqueue that would exceed a bound.
- New activity during initial replay, while a subscriber is not ready, during
  close, and after delivery shutdown.
- Empty optional activity fields, multibyte UTF-8, embedded newline/CR text,
  maximum details, and maximum retained activity.
- Async initialization failure before ownership, error before handshake write,
  error after handshake write, duplicate container callbacks, and completion
  racing the deadline task.
- Runtime close invoked more than once or concurrently with signal/subscribe.

### Compatibility scope

| Surface | Test obligation |
| --- | --- |
| Application API | Existing exact seven-type API test remains unchanged; no activity implementation type may enter `com.lokiscale.bifrost.api` or an API signature. |
| Supported SPI | Existing no-SPI architecture test remains green; no subscriber/emitter/executor replacement point is introduced. |
| Configuration and manifest contracts | Existing observability values still bind; representative SSE tuning properties fail as unknown because limits remain fixed. Skill manifests and authoring tests need no change. |
| Persisted or serialized contracts | Exact current-release handshake/activity JSON and SSE framing are asserted in Java. Existing REST/problem fixture bytes and exact instance-status release string remain unchanged. PR 06 owns committed SSE fixtures. |
| Ephemeral diagnostic formats | Projector, activity DTO, replay, terminal outcome, parent identity, ordering, boundedness, failure visibility, and authentication-secret exclusion remain coherent for the current checkout. No historical activity compatibility test is added. |
| Internal or accidentally exposed implementation | Architecture tests classify new technically public internal types. Removed internal constructor shapes are absent rather than retained as overloads; all repository callers use the new coherent graph. |

### Java-to-Go coordination

PR 05 has no Go module or Go client to exercise exact-version rejection.
Therefore this PR must:

- preserve the exact complete release string in authenticated instance status;
- prove it is not repeated in handshake/activity envelopes;
- make the Java producer framing and semantics exact and deterministic; and
- leave the committed SSE corpus to PR 06 and exact mismatch rejection to PR
  09/PR 11, as assigned by the roadmap.

Changing those downstream assignments or adding a second protocol version is a
design change, not a test implementation detail.

## Existing Test Coverage

### Coverage to reuse

- `InMemoryActivityReplayBufferTest` covers before-first cursor `0`,
  count/byte eviction, empty/current/future distinctions, concurrent cursor
  assignment, cursor exhaustion, and production replay bounds.
- `LiveActivityProjectorTest` covers the settled visible activity vocabulary,
  skill-frame visibility, path/text/detail/activity bounds, payload exclusion,
  terminal hold, and normalized usage projection.
- `DefaultExecutionObservationHandleTest` covers catalog-before-terminal
  publication, trace availability, exceptional terminal behavior, replay/
  projector/registry failure containment, exactly-once close, cleanup, and
  sanitized first-failure logging.
- `ExecutionObservationConcurrencyTest` proves all live engine sessions remain
  represented without observability sampling or admission.
- `LiveMonitoringAvailabilityTest` proves one-way first-failure retention.
- `ObservabilityRestIntegrationTest` covers API-key authentication, no-store,
  instance identity, exact release string, query/Accept rejection, active
  baseline `resumeCursor`, pagination high-water, and stale instance
  continuations.
- `ObservabilityApiKeyFilterTest` covers exact internal authentication,
  duplicate/oversized credentials, host-context restoration, uncommitted
  problem mapping, committed failure propagation, and sanitized logs.
- `ObservabilityContextPathIntegrationTest` and
  `ObservabilityHostSecurityIntegrationTest` provide random-port Java
  `HttpClient` patterns for context-path and host-security behavior.
- `ObservabilityRouteRegistrarTest` covers activation validation, scheduler
  startup cleanup, and host path-matching configuration.
- `ObservabilityProblemMapperTest` covers preservation of the PR 05 stable
  problems and sanitization of unexpected failures.
- `ConsoleRestFixtureCorpusTest` and `ConsoleTraceFixtureCorpusTest` protect the
  existing deterministic Java boundary corpora.
- `BifrostPublicSurfaceArchitectureTest` protects API/SPI boundaries,
  technically public internal classification, and bounded observation/wire
  DTOs.

### Coverage gaps

- No activity route or SSE response is currently opened.
- No test exercises servlet async/non-blocking output or its callbacks.
- No delivery coordinator, subscriber queue, admission counter, deadline, or
  delivery shutdown exists to test.
- No test covers replay-to-subscriber fan-out, baseline-plus-subscribe races,
  reconnect, or duplicate tolerance.
- No test proves live failure closes already-open streams.
- No test distinguishes all pre-async JSON failures from post-start close-only
  failures.
- No exact handshake/activity framing assertion or SSE fixture exists.

## Test Design Constraints

- Test locations abbreviated as `.../runtime/...`,
  `.../observability/...`, or `.../architecture/...` below are rooted at
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/`.
- Do not use real five-second sleeps. Inject package-private executor/scheduler
  seams into internal delivery classes and use a manual executor plus a
  recording/manual scheduler to run deadline tasks deterministically while
  still asserting the production delay is exactly five seconds.
- Do not depend on filling a loopback socket buffer in CI. A controllable
  `ServletOutputStream` test double owns readiness/backpressure evidence.
  Random-port tests verify actual container framing and cancellation; manual
  verification owns prolonged real-network backpressure.
- Do not use timing as proof of lock isolation. Block the delivery executor or
  a subscriber sink with latches, publish on a separate future, and require the
  publisher to finish without releasing the blocked delivery side.
- Use barriers/latches and bounded `Future.get(...)` only as deadlock guards.
  Avoid arbitrary sleeps. For eventual container cancellation, use a
  deadline-bounded polling helper that reports the last observed state.
- Give every streaming HTTP read a hard test timeout and close response input
  streams in `finally`/try-with-resources so a failed assertion cannot leak an
  admitted connection.
- Unit tests must use small injected replay/queue limits where the production
  path permits package-private construction; production-limit assertions
  separately verify 16/256/1 MiB/256/5 seconds.
- Do not regenerate or add committed SSE fixtures in PR 05.

## Bug Reproduction / Failing Test First

This is a new vertical feature rather than a regression bug. The first test is
nevertheless an executable failing acceptance test against the current
repository.

- **Name**:
  `opensAuthenticatedStreamWithHandshakeAndReplaysActivityAfterCursor`
- **Type**: Integration, real embedded servlet container.
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ObservabilitySseIntegrationTest.java`
- **Arrange**:
  - Start the existing observability test application on a random port.
  - Read the active baseline and capture its instance header and
    `resumeCursor`.
  - Append one bounded `ExecutionActivity` directly to the current runtime
    replay buffer after the baseline.
  - Build a Java `HttpClient` GET using the literal
    `ObservabilityApiPaths.ROOT + "/activity"` so the test compiles before an
    `ACTIVITY` constant exists; supply API key, `Accept: text/event-stream`,
    `instanceId`, and `afterCursor`.
- **Act**:
  Send with `BodyHandlers.ofInputStream()`, assert headers, and use a
  deadline-bounded SSE line reader to parse the first two complete events.
- **Assert**:
  - HTTP `200`, `text/event-stream`, `Cache-Control: no-store`, and the same
    `X-Bifrost-Instance-Id`;
  - first event is `handshake`, has no `id`, and carries the requested cursor;
  - second event is `activity`, has the appended delivery cursor as `id`, and
    carries the same cursor and instance ID in its JSON.
- **Expected failure before implementation**:
  The activity route is not registered, so the namespace fallback applies its
  JSON-only `Accept` validation and returns `400 INVALID_REQUEST` for
  `Accept: text/event-stream`.
- **Why this is first**:
  It proves the smallest complete product slice—routing, authentication,
  identity/cursor input, async ownership, framing, replay, and response
  metadata—without requiring a future Go consumer or committed fixture.

After recording this red test, implement lower-level unit tests alongside each
production seam. Do not make the vertical test pass with a synchronous/static
response that bypasses delivery lifecycle coverage.

## Tests to Add or Update

### 1. Activity projection and bounded runtime record

- **Names**:
  - `projectsParentIdentityAndTruthfulExecutionStatus`
  - `observationEndedOmitsStatusUnlessOutcomeWasEstablished`
  - update `activityDtoEnforcesTextDetailAndEnvelopeBounds`
  - `cursorAndTraceAvailabilityCopiesPreserveNewIdentityFields`
- **Type**: Unit.
- **Locations**:
  - `.../runtime/observation/LiveActivityProjectorTest.java`
  - `.../runtime/observation/DefaultExecutionObservationHandleTest.java`
  - `.../runtime/observation/InMemoryActivityReplayBufferTest.java`
- **What it proves**:
  `parentFrameId` comes from the canonical record; nonterminal activity is
  `ACTIVE`; canonical completion uses the recorded outcome; exceptional
  terminal activity does not invent a status; cursor assignment and terminal
  enrichment preserve both new fields; UTF-8 retained-weight limits include
  them.
- **Fixtures/data**:
  Canonical records with nested frame identity, all terminal outcomes,
  finalization failure with and without independently known outcome, multibyte
  boundary strings, and a maximum-size activity.
- **Mocks**:
  Existing in-memory replay/catalog helpers; no servlet mocks.
- **Contract classification**: Ephemeral diagnostic formats.
- **Compatibility expectation**: Current-run diagnostic coherence; update all
  internal record constructors atomically, with no historical shape test.

### 2. Exact external DTO and JSON semantics

- **Names**:
  - `mapsActivityToFlatInstanceScopedEnvelope`
  - `mapsExceptionalTerminalWithoutInventingStatus`
  - `serializesCanonicalDecimalCursorAndOmitsNullableFields`
  - `doesNotSerializeRuntimeOnlyOrAuthenticationFields`
- **Type**: Unit.
- **Locations**:
  - `.../observability/web/ObservabilityDtoMapperTest.java`
  - `.../observability/web/ObservabilityJsonCodecTest.java` or the existing
    codec test class if serialization assertions already belong there.
- **What it proves**:
  Exact field names and values, string cursor, ISO timestamp, enum spelling,
  nullable omission, copied scalar details, instance identity, absence of
  runtime weight/thread/payload objects, and absence of release version/API
  keys/authentication headers.
- **Fixtures/data**:
  One ordinary nested-frame event, one canonical terminal event, and one
  `EXECUTION_OBSERVATION_ENDED`; include CR/LF, Unicode, quote, and backslash
  text to prove valid one-line JSON.
- **Mocks**:
  None; use the production JSON codec and fixed instants/UUIDs.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Establish the exact current-release Java SSE
  producer contract; no legacy DTO or alternate envelope.

### 3. Byte-exact SSE framing

- **Names**:
  - `framesHandshakeWithoutId`
  - `framesActivityWithCursorAsIdAndSingleJsonDataLine`
  - `escapesDiagnosticNewlinesInsideJsonWithoutCreatingExtraSseFields`
  - `countsCompleteUtf8FrameBytesAgainstPendingLimit`
- **Type**: Unit/golden assertion, not a committed fixture.
- **Location**:
  `.../observability/web/ObservabilityActivityStreamTest.java`
- **What it proves**:
  Exact `event: handshake`, `event: activity`, `id:`, `data:`, newline, and
  blank-line framing; cursor equality between JSON and SSE ID; no handshake
  ID; diagnostic newlines remain JSON escapes; pending byte accounting includes
  all framing bytes.
- **Fixtures/data**:
  Literal expected UTF-8 byte arrays for fixed handshake/activity DTOs.
- **Mocks**:
  None for framing; production JSON codec only.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Exact current-release producer semantics;
  PR 06 later promotes reviewed examples into the shared corpus.

### 4. Activity request validation and stable pre-stream problems

- **Names**:
  - `acceptsOnlyExactActivityQueryAndSseAccept`
  - `mapsMalformedAndFutureCursorToInvalidCursor`
  - `mapsTooOldCursorAndChangedInstanceToStaleCursor`
  - `mapsUnavailableLiveProjectionBeforeAdmission`
  - `rejectsLastEventIdInsteadOfCreatingSecondCursorInput`
- **Type**: Parameterized controller/unit plus selected random-port integration.
- **Locations**:
  - `.../observability/web/ObservabilityActivityRequestTest.java` (new), or
    focused methods in `ObservabilityRestIntegrationTest`
  - `.../observability/web/ObservabilitySseIntegrationTest.java`
- **What it proves**:
  Required single query values, supported `Accept`, precise
  `INVALID_REQUEST`/`INVALID_CURSOR`/`STALE_CURSOR`/
  `LIVE_MONITORING_UNAVAILABLE` mapping, and no async ownership/body bytes for
  rejected requests.
- **Fixtures/data**:
  The cursor/UUID/Accept edge-case table from Risk Assessment and small replay
  buffers that force `TOO_OLD`, `EMPTY`, and `FUTURE`. Accept
  `text/event-stream` and positive-quality compatible wildcards; reject a
  missing header, quality-zero SSE, malformed/incompatible media types,
  malformed instance UUID, and `Last-Event-ID` with `400 INVALID_REQUEST`.
- **Mocks**:
  Unit layer uses mock request/response plus real small replay buffer and a
  recording admission service. Integration layer uses Java `HttpClient`.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Protected stable problem meanings and the new
  exact SSE request contract; no `Last-Event-ID` fallback.

### 5. Authentication, authorization, and admission order

- **Names**:
  - `requiresOperatorAuthenticationBeforeActivityAdmission`
  - `rejectsSeventeenthValidStreamWithoutQueuing`
  - `invalidCredentialStillReturns401WhenCapacityIsFull`
  - `invalidOrStaleRequestDoesNotConsumeOrReleaseAValidSlot`
  - `activityOperationRejectsHostLookalikeAuthority`
- **Type**: Unit and integration.
- **Locations**:
  - `.../observability/web/ObservabilityApiKeyFilterTest.java`
  - `.../observability/web/ObservabilityActivityDeliveryTest.java`
  - `.../observability/web/ObservabilitySseIntegrationTest.java`
- **What it proves**:
  Only adapter-established `BIFROST_OPERATOR` can subscribe; authentication and
  semantic validation happen before capacity accounting; capacity is exactly
  16; the 17th request is immediate `429 LIMIT_EXCEEDED`; no queue or identity
  disclosure is introduced for rejected credentials.
- **Fixtures/data**:
  Sixteen open streams, one excess request, missing/invalid/duplicate key,
  malformed cursor, and random different instance ID.
- **Mocks**:
  Recording delivery admission for filter/controller unit tests; real
  container/client streams for capacity.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Preserve existing authentication/problem
  semantics while adding one authorized operation.

### 6. Publication signaling and execution isolation

- **Names**:
  - `signalsAfterEachSuccessfulReplayPublication`
  - `doesNotSignalWhenProjectionRegistryOrReplayPublicationFails`
  - `signalsLiveUnavailableExactlyOnceOnFirstFailClosedTransition`
  - `containsUnexpectedSignalFailureWithoutChangingExecutionOutcome`
  - `publisherDoesNotWaitForBlockedDispatcherOrSubscriber`
- **Type**: Unit/concurrency.
- **Locations**:
  - `.../runtime/observation/DefaultExecutionObservationHandleTest.java`
  - `.../runtime/observation/ExecutionObservationConcurrencyTest.java`
- **What it proves**:
  Signal ordering follows successful replay append, failure signaling is
  one-shot, signal faults remain optional-observability faults, and a blocked
  delivery side cannot block canonical/observation publication.
- **Fixtures/data**:
  Recording/throwing signal, failing projector/registry/replay, ordinary and
  terminal records, latches that hold the manual delivery executor.
- **Mocks**:
  Small recording fakes are preferred over Mockito for order/thread assertions;
  existing mocks remain suitable for failure seams.
- **Contract classification**: Internal or accidentally exposed implementation.
- **Compatibility expectation**: Atomic internal change; preserve execution
  isolation and fail-closed diagnostic accuracy, not old constructor behavior.

### 7. Dispatcher ordering, replay batching, and fan-out

- **Names**:
  - `coalescesSignalsAndFetchesReplayInBatchesOf256`
  - `fansOutStrictCursorOrderAcrossBatchBoundaries`
  - `serializesEachActivityOnceAndChargesEverySubscriber`
  - `publicationDuringInitialReplayIsDeliveredWithoutGap`
  - `closesSubscriberIfReplayExpiresBeforeItCanCatchUp`
  - `dispatcherYieldsBetweenBatchesSoCloseAndUnavailableAreNotStarved`
- **Type**: Deterministic unit/concurrency.
- **Location**:
  `.../observability/web/ObservabilityActivityDeliveryTest.java`
- **What it proves**:
  Coalesced scheduling, exact batch bound, no lost registration race,
  monotonically increasing per-subscriber cursors, one serialization per
  activity, conservative subscriber accounting, explicit gap closure, and
  fair processing of lifecycle signals.
- **Fixtures/data**:
  Small injectable replay buffer, 257+ activities, manual executor, two or more
  recording subscribers, and barriers around replay reads/registration.
- **Mocks**:
  Manual executor and recording subscriber sinks; spy serializer only where
  invocation count is the assertion.
- **Contract classification**: Internal or accidentally exposed implementation.
- **Compatibility expectation**: New coherent internal delivery path; no
  subscriber callback from the publisher.

### 8. Event and byte overflow isolation

- **Names**:
  - `allowsQueueExactlyAtEventAndByteBounds`
  - `closesOnlySubscriberWhoseNextEventExceedsEventBound`
  - `closesOnlySubscriberWhoseNextFrameExceedsByteBound`
  - `overflowDropsPendingFramesAndReleasesAdmissionOnce`
  - `healthySubscriberContinuesAfterPeerOverflow`
- **Type**: Unit/concurrency.
- **Location**:
  `.../observability/web/ObservabilityActivityDeliveryTest.java`
- **What it proves**:
  Exact 256-event/1 MiB inclusive bounds, first-over-limit behavior, no
  unbounded growth, isolation between clients, queue cleanup, and idempotent
  admission release.
- **Fixtures/data**:
  Small test limits for boundary mechanics plus a separate assertion of
  production constants; shared multibyte frames with exact byte counts.
- **Mocks**:
  One always-ready subscriber and one deliberately non-draining subscriber.
- **Contract classification**: Internal or accidentally exposed implementation.
- **Compatibility expectation**: Implement the new fixed delivery guard; no
  configurable/legacy unbounded mode.

### 9. Non-blocking output and deadline generation

- **Names**:
  - `writesOnlyWhileServletOutputIsReady`
  - `startsFiveSecondDeadlineWhenFrameBecomesHead`
  - `cancelsDeadlineOnlyAfterCompleteFrameIsAccepted`
  - `staleDeadlineCannotCloseAReplacementHeadFrame`
  - `deadlineClosesOnlyTheBlockedSubscriber`
  - `idleStreamHasZeroAsyncTimeoutAndNoHeartbeatTask`
- **Type**: Deterministic unit.
- **Location**:
  `.../observability/web/ObservabilityActivityStreamTest.java`
- **What it proves**:
  Servlet readiness gating, exact deadline meaning, generation/token race
  protection, scheduler independence, subscriber isolation, disabled idle
  timeout, and absence of heartbeat work.
- **Fixtures/data**:
  Controllable `ServletOutputStream` that toggles `isReady`, captures
  `WriteListener`, records writes, and injects errors; manual scheduled executor
  that records the requested delay and runs tasks on demand.
- **Mocks**:
  Purpose-built fake `AsyncContext`, request/response, and output stream rather
  than time-based Mockito stubbing.
- **Contract classification**: Internal or accidentally exposed implementation.
- **Compatibility expectation**: New servlet adapter behavior; explicitly no
  `SseEmitter` compatibility path.

### 10. Async ownership and idempotent close convergence

- **Names**:
  - `initializationFailureReleasesAdmissionAndLeavesJsonProblemAvailable`
  - `handshakeIsQueuedBeforeActivityCanFanOut`
  - `postStartFailureClosesWithoutWritingJsonProblem`
  - `everyCloseCauseReleasesAdmissionExactlyOnce`
  - `completionRacingDeadlineAndShutdownConvergesOnOneClose`
- **Type**: Unit/parameterized concurrency.
- **Location**:
  `.../observability/web/ObservabilityActivityStreamTest.java`
- **What it proves**:
  The `startAsync()` ownership boundary, handshake-first order, no post-start
  problem rewriting, and one cleanup path for async completion, timeout,
  async error, write-listener error, client cancellation, write exception,
  overflow, replay gap, live failure, initialization failure, and shutdown.
- **Fixtures/data**:
  `CloseCause` parameter source, failing `startAsync`, pre/post-handshake
  failures, barriers that release competing callbacks simultaneously.
- **Mocks**:
  Recording subscriber/admission and custom servlet fakes.
- **Contract classification**: Internal or accidentally exposed implementation.
- **Compatibility expectation**: Current-version servlet lifecycle coherence;
  preserve existing uncommitted filter problem behavior.

### 11. Live failure and independent observability operations

- **Names**:
  - `liveFailureClosesAllCurrentStreamsWithoutControlEvent`
  - `liveFailureRejectsNewStreamAndActiveSnapshots`
  - `liveFailureLeavesInstanceSkillTraceCatalogAndTraceDetailUsable`
  - update `logsOneSanitizedDiagnosticOnFirstFailure`
- **Type**: Unit and real-container integration.
- **Locations**:
  - `.../observability/web/ObservabilityActivityDeliveryTest.java`
  - `.../observability/web/ObservabilitySseIntegrationTest.java`
  - `.../runtime/observation/DefaultExecutionObservationHandleTest.java`
- **What it proves**:
  First fail-closed transition closes all streams, emits no fabricated
  activity/problem after commit, active/new-live paths return `503`, and
  unrelated status/skill/finalized-trace paths remain usable. Diagnostics
  contain operation/identity/exception class but not event details, exception
  messages, credentials, or payloads.
- **Fixtures/data**:
  Open client stream, one current skill, one cataloged temporary trace, and an
  observation record that deliberately fails projection to trigger the real
  fail-closed seam.
- **Mocks**:
  Unit delivery sinks; integration uses the production runtime and a malformed
  record through its observation handle rather than mutating availability by
  reflection.
- **Contract classification**: Ephemeral diagnostic formats.
- **Compatibility expectation**: Preserve current-run failure visibility and
  independent skill/trace availability.

### 12. Runtime construction, rollback, and shutdown

- **Names**:
  - `runtimeClosesDeliveryBeforeRetentionAndCatalog`
  - `runtimeCloseIsIdempotentAcrossConcurrentCallers`
  - `activationRollbackClosesPartiallyConstructedDelivery`
  - `shutdownRejectsAdmissionClosesStreamsDropsPendingAndStopsOwnedThreads`
  - `routeDestroyUnregistersActivityBeforeClosingRuntime`
- **Type**: Unit/concurrency and one dedicated lifecycle integration test.
- **Locations**:
  - `.../internal/observability/ObservabilityActivationCoordinatorTest.java`
  - `.../observability/web/ObservabilityRouteRegistrarTest.java`
  - `.../observability/web/ObservabilitySseShutdownIntegrationTest.java` (new)
- **What it proves**:
  Exact close order, reverse-order startup cleanup, no queue drain, no new
  admission, stream EOF, exactly-once release, and eventual absence of named
  delivery/deadline threads.
- **Fixtures/data**:
  Ordered recording closeables; manually started `SpringApplication` context
  for shutdown evidence so closing it does not corrupt the shared JUnit context
  cache.
- **Mocks**:
  Mockito `InOrder` or recording closeables for unit order; real context/client
  for final lifecycle.
- **Contract classification**: Internal or accidentally exposed implementation.
- **Compatibility expectation**: Atomic runtime ownership change; no legacy
  runtime constructor or orphan resource graph.

### 13. Route, context-path, host-security, and filter regressions

- **Names**:
  - `registersExactActivityGetBeforeNamespaceFallback`
  - `activityRouteRemainsBeneathServletContext`
  - `hostPassThroughStillRequiresBifrostKeyForActivity`
  - `asyncLifecycleRestoresHostSecurityContext`
  - `committedAsyncFailureDoesNotResetResponseToJson`
- **Type**: Unit and integration.
- **Locations**:
  - `.../observability/web/ObservabilityRouteRegistrarTest.java`
  - `.../observability/web/ObservabilityContextPathIntegrationTest.java`
  - `.../observability/web/ObservabilityHostSecurityIntegrationTest.java`
  - `.../observability/web/ObservabilityApiKeyFilterTest.java`
- **What it proves**:
  Exact namespace ownership, context-relative path, host filter pass-through,
  adapter authentication, host-context restoration, async support across
  dispatcher types, and committed-response protection.
- **Fixtures/data**:
  Existing test applications, one short-lived stream per test, and a synthetic
  post-start error.
- **Mocks**:
  Existing Spring mock request/response for filter unit tests; real
  random-port server where container behavior matters.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Preserve the protected PR 04
  authentication/namespace behavior while adding one route.

### 14. Baseline, replay, reconnect, and duplicate tolerance

- **Names**:
  - `deliversActivityPublishedBeforeDuringAndAfterSubscriptionInCursorOrder`
  - `reconnectsAfterLastProcessedCursorWithoutSkippingLaterActivity`
  - `allowsDuplicateApplicationWithoutReordering`
  - `tooOldReconnectReturnsStaleBeforeStartingStream`
  - `changedInstanceRequiresFreshBaseline`
- **Type**: Coordinator concurrency plus real-container integration.
- **Locations**:
  - `.../observability/web/ObservabilityActivityDeliveryTest.java`
  - `.../observability/web/ObservabilitySseIntegrationTest.java`
- **What it proves**:
  Baseline-plus-stream race handling, continuous increasing cursor interval,
  reconnect semantics, explicit replay gap, changed-instance reset boundary,
  and duplicate tolerance without an exactly-once claim.
- **Fixtures/data**:
  Active baseline cursor, barriers around subscribe registration/dispatch,
  activities on multiple sessions/traces, small replay eviction, and reconnect
  from the last processed ID.
- **Mocks**:
  Manual executor/barriers for the during-admission race; Java `HttpClient` for
  actual framing/reconnect.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Establish the contract consumed by PR 11;
  do not add transactional baseline semantics or gap reconstruction.

### 15. Public surface, no-shim, and fixed configuration

- **Names**:
  - update `technicallyPublicInternalTypesHaveNonblankReasons`
  - update `observationDtosExposeOnlyBoundedImmutableDomainTypes`
  - `activityWireDtosExcludeRuntimeServletAndThrowableTypes`
  - `removedInternalConstructorsAreNotRetainedAsCompatibilityOverloads`
  - `rejectsSseTuningPropertiesBecauseDeliveryLimitsAreFixed`
- **Type**: Architecture/reflection/configuration binding.
- **Locations**:
  - `.../architecture/BifrostPublicSurfaceArchitectureTest.java`
  - `.../autoconfigure/BifrostPropertiesTest.java`
- **What it proves**:
  New public modifiers remain explicitly internal, no API/SPI leak occurs,
  wire DTOs remain bounded and transport-neutral, old internal constructor
  shapes are absent, and no admission/queue/deadline/executor/heartbeat
  property silently becomes supported.
- **Fixtures/data**:
  Reflection over new DTOs/constructors and representative unknown
  `bifrost.observability.sse.*` properties.
- **Mocks**:
  None.
- **Contract classification**: Application API, Supported SPI, Configuration
  and manifest contracts, and Internal or accidentally exposed implementation.
- **Compatibility expectation**: Protected public/configuration paths remain;
  approved internal shapes are removed atomically with no shim.

### 16. Existing fixture and exact-version regressions

- **Names**:
  - preserve `authenticatesAndReturnsExactReleaseIdentityAndNoStore`
  - preserve `generatedCorpusMatchesCommittedFixturesByteForByte`
  - `streamEnvelopeDoesNotRepeatConsoleCompatibilityVersion`
- **Type**: Integration and fixture regression.
- **Locations**:
  - `.../observability/web/ObservabilityRestIntegrationTest.java`
  - `.../observability/web/ConsoleRestFixtureCorpusTest.java`
  - `.../runtime/trace/ConsoleTraceFixtureCorpusTest.java`
  - exact SSE DTO/framing unit test from items 2–3.
- **What it proves**:
  Exact complete release string remains the compatibility umbrella, existing
  REST/problem and trace fixtures remain byte-identical, and SSE does not add a
  second version fact.
- **Fixtures/data**:
  Existing committed corpora; fixed handshake/activity literals remain
  test-local in PR 05.
- **Mocks**:
  None.
- **Contract classification**: Persisted or serialized contracts and
  Ephemeral diagnostic formats.
- **Compatibility expectation**: Preserve PR 04 and canonical-trace boundaries;
  defer new committed SSE fixtures to PR 06 and Go mismatch rejection to PR 09.

## Workflow Evidence Mapping

Representative tests should use `@DisplayName` with the most specific approved
workflow requirement IDs below. Low-level protocol/concurrency tests do not
need artificial workflow labels when they prove an implementation invariant
rather than a developer workflow.

| Requirement | Representative evidence |
| --- | --- |
| `WF-X-R5` — keep outcome, trace availability, connection, and continuity facts separate | `projectsParentIdentityAndTruthfulExecutionStatus`, `liveFailureLeavesInstanceSkillTraceCatalogAndTraceDetailUsable` |
| `WF-X-R6` / `WF-SE-R3` — expose observation time and continuity boundaries | the red vertical test, `changedInstanceRequiresFreshBaseline`, `tooOldReconnectReturnsStaleBeforeStartingStream` |
| `WF-X-R7` / `WF-SE-R9` — browser and MCP ultimately share the same continuity semantics | `mapsActivityToFlatInstanceScopedEnvelope`, `reconnectsAfterLastProcessedCursorWithoutSkippingLaterActivity`, exact framing assertions handed to PR 11 |
| `WF-X-R10` — represent reset/unavailable evidence explicitly | `mapsTooOldCursorAndChangedInstanceToStaleCursor`, `mapsUnavailableLiveProjectionBeforeAdmission` |
| `WF-FE-R3` — keep terminal outcome and application trace availability separate | canonical and exceptional terminal projection/DTO tests |
| `WF-FE-R10` — recent activity is bounded, not durable or lossless history | overflow, replay-gap, and changed-instance tests |
| `WF-SE-R6` / `WF-SP-R2` — active evidence is a bounded path, not a complete hierarchy | parent-frame/activity DTO tests plus the existing bounded active snapshot tests |
| `WF-SE-R7` — silence or elapsed time is not proof of a stuck execution | idle-stream/no-heartbeat and slow-subscriber tests assert transport facts only and introduce no health classification |
| `WF-SE-R8` — preserve a truthful terminal transition | canonical completion versus `EXECUTION_OBSERVATION_ENDED` tests |

## Real-Container Test Scenarios

`ObservabilitySseIntegrationTest` should use the existing random-port
configuration pattern and Java `HttpClient`. A small test-only SSE reader
should:

- read complete fields/events from `InputStream`;
- bound every read with a future timeout;
- preserve duplicate IDs for assertions rather than deduplicating;
- expose response EOF distinctly from timeout; and
- close the response stream on every path.

The real-container suite should cover only behavior the actual container must
prove:

1. endpoint registration, headers, handshake-first framing, and replay;
2. context-path and host-security integration;
3. sixteen open streams, immediate 17th rejection, and slot reclamation after
   client close;
4. reconnect from an emitted cursor;
5. JSON response for pre-start stale/invalid/unavailable/capacity failures;
6. EOF without synthetic events after live failure; and
7. manually owned context shutdown closing a stream.

Do not make CI depend on an unread loopback client filling the OS/container
socket buffer. The fake-output unit suite is authoritative for not-ready
output, five-second deadlines, and slow-client isolation.

## Manual Verification

1. Start `bifrost-sample` with observability enabled and a valid externalized
   API key.
2. Fetch instance status and the first active baseline. Record the exact
   `instanceId` and `resumeCursor`.
3. Open the activity route with `curl -N` and verify:
   - `handshake` is first and has no ID;
   - each activity ID equals the JSON cursor;
   - IDs increase;
   - nullable fields are omitted;
   - no heartbeat or retry event appears while idle.
4. Run two simultaneous executions and verify both are multiplexed without
   cross-trace identity leakage.
5. Disconnect after recording an ID, publish more activity, reconnect after
   that ID, and verify ordered replay while tolerating a duplicate.
6. Reconnect with an expired cursor and a previous instance UUID and verify
   `410 STALE_CURSOR` JSON before SSE starts.
7. Hold sixteen streams, verify the 17th receives
   `429 LIMIT_EXCEEDED`, close one, and verify a new stream is admitted.
8. Use a throttled/non-reading client or network proxy to stall one subscriber.
   Verify it closes after bounded pressure/deadline while a healthy subscriber
   and observed executions continue.
9. Terminate a client without graceful SSE closure and verify capacity is
   eventually reclaimed without publishing another event.
10. Trigger a live projection failure. Verify existing streams close, active
    and new activity operations return `503`, and status/skill/trace operations
    remain available.
11. Stop the application with streams open. Verify clients receive EOF, no
    pending event drain occurs, and no named Bifrost delivery/deadline thread
    remains.
12. Review logs from overflow, timeout, send failure, live failure, and
    shutdown. Confirm they contain no API key, event details, recorded payload,
    exception message, or stack trace unless an existing safe logging policy
    explicitly requires it.

## How to Run

No profile, external service, API key, or committed test-data regeneration is
required. Tests use embedded Spring Boot applications, test-local keys,
temporary trace files, manual executors/schedulers, and Java's built-in HTTP
client.

### Red test

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter `
  -Dtest=ObservabilitySseIntegrationTest#opensAuthenticatedStreamWithHandshakeAndReplaysActivityAfterCursor `
  test
```

Before implementation this must fail with the namespace fallback returning
`400 INVALID_REQUEST` for the SSE `Accept` header. After implementation it must
pass without weakening the namespace fallback.

### Focused development tests

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter `
  -Dtest=LiveActivityProjectorTest,InMemoryActivityReplayBufferTest,DefaultExecutionObservationHandleTest,ExecutionObservationConcurrencyTest `
  test

.\mvnw.cmd -pl bifrost-spring-boot-starter `
  -Dtest=ObservabilityDtoMapperTest,ObservabilityActivityRequestTest,ObservabilityActivityDeliveryTest,ObservabilityActivityStreamTest `
  test

.\mvnw.cmd -pl bifrost-spring-boot-starter `
  -Dtest=ObservabilityApiKeyFilterTest,ObservabilityRouteRegistrarTest,ObservabilitySseIntegrationTest,ObservabilitySseShutdownIntegrationTest `
  test

.\mvnw.cmd -pl bifrost-spring-boot-starter `
  -Dtest=BifrostPublicSurfaceArchitectureTest,BifrostPropertiesTest `
  test
```

### Existing boundary regressions

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter `
  -Dtest=ObservabilityRestIntegrationTest,ObservabilityContextPathIntegrationTest,ObservabilityHostSecurityIntegrationTest,ObservabilityProblemMapperTest `
  test

.\mvnw.cmd -pl bifrost-spring-boot-starter `
  -Dtest=ConsoleRestFixtureCorpusTest,ConsoleTraceFixtureCorpusTest `
  test
```

Do not pass `-Dbifrost.console.fixtures.regenerate=true`. Both commands must
leave `bifrost-console-fixtures/` unchanged.

### Full verification

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter test
.\mvnw.cmd test
```

Run the full reactor command twice after the final change. The second run must
not reveal ordering flakes, port/thread leaks, or filesystem diffs.

### Static no-shim/no-scope checks

```powershell
rg -n "SseEmitter|Last-Event-ID|WebMvcConfigurer|ConditionalOnMissingBean" `
  bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost

rg -n "subscription|pending|write-deadline|heartbeat|async-executor" `
  bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostProperties.java
```

Review every match. The first command must not reveal an SSE compatibility
path, host MVC mutation, or replacement extension point; the second must not
reveal new dynamic delivery configuration. An empty result is expected for the
new terms unless a pre-existing unrelated match is documented.

## Exit Criteria

### Failing-test and feature evidence

- [ ] The vertical acceptance test is committed red first and fails on the
  pre-implementation repository because the namespace fallback returns
  `400 INVALID_REQUEST` for the SSE `Accept` header.
- [ ] The same test passes after implementation with a real servlet container,
  handshake, and replayed activity.
- [ ] Exact test-local handshake/activity bytes match the approved protocol;
  no committed SSE fixture is added before PR 06.

### Ordering, replay, and diagnostic coherence

- [ ] Parent-frame identity and execution status remain truthful and bounded
  through projection, replay cursor assignment, terminal enrichment, external
  DTO mapping, JSON, and SSE framing.
- [ ] Per-subscriber cursor order holds across 256-event batch boundaries and
  concurrent publication.
- [ ] Publication before/during/after subscription has no silent gap; duplicate
  tolerance is covered without claiming exactly-once behavior.
- [ ] `TOO_OLD` and changed instance produce `410 STALE_CURSOR`; malformed,
  negative, overflowing, and future cursors produce `400 INVALID_CURSOR`.
- [ ] Live failure closes existing streams and preserves status/skill/trace
  availability as specified.

### Bounds, isolation, and lifecycle

- [ ] Production constants are exactly 16 subscriptions, 256 pending events,
  1 MiB pending frame bytes, 256 replay batch, and 5-second head-frame
  readiness deadline.
- [ ] Boundary tests cover exactly-at-limit success and first-over-limit
  closure/rejection.
- [ ] A blocked dispatcher or subscriber cannot block or alter an execution
  publisher or a healthy subscriber.
- [ ] All close causes converge on one queue cleanup and one admission release,
  including callback/deadline/shutdown races.
- [ ] Async initialization failure retains uncommitted JSON problem handling;
  every post-`startAsync()` failure is close-only.
- [ ] Runtime shutdown rejects new admission, closes current streams without
  draining, and leaves no named delivery/deadline thread.
- [ ] Tests contain no real five-second sleep or socket-buffer-dependent
  backpressure assertion.

### Security and protocol boundary

- [ ] Authentication precedes admission, invalid credentials disclose no
  instance, and a host principal with a lookalike authority cannot subscribe.
- [ ] Activity responses preserve no-store and instance metadata beneath the
  servlet context path and host pass-through security.
- [ ] SSE fields contain no API key, authentication header, exception message,
  stack trace, arbitrary payload, runtime object, or repeated compatibility
  version.
- [ ] Existing stable problem codes/statuses and exact instance-status release
  string remain unchanged.
- [ ] Existing REST and trace fixture corpora are byte-identical and the
  worktree remains unchanged after fixture tests.

### Compatibility and scope

- [ ] The exact supported Application API remains unchanged and no Supported
  SPI is added.
- [ ] New technically public internal types have explicit architecture
  classifications; activity wire DTOs contain no runtime/servlet types.
- [ ] Obsolete internal constructor shapes are removed without legacy
  overloads, aliases, fallbacks, or dual behavior.
- [ ] Representative SSE tuning properties remain unknown; no new
  configuration or manifest contract is introduced.
- [ ] No `SseEmitter`, `Last-Event-ID`, heartbeat, host
  `WebMvcConfigurer`, WebFlux path, or configurable executor is present.
- [ ] Skill-authoring documentation remains unchanged because no author-facing
  semantics changed.
- [ ] PR 06 fixture ownership and PR 09/PR 11 Go compatibility/reconnect
  obligations remain explicit and are not pulled partially into this PR.

### Completion

- [ ] All focused test commands pass.
- [ ] `.\mvnw.cmd -pl bifrost-spring-boot-starter test` passes.
- [ ] `.\mvnw.cmd test` passes twice consecutively.
- [ ] Manual streaming, stalled-client, cancellation, live-failure, and
  shutdown verification is complete.
- [ ] No source, fixture, thread, port, or temporary-file diff/leak remains
  after verification.

## References

- Implementation plan:
  `ai/thoughts/plans/2026-07-26-bifrost-console-pr-05-live-sse-delivery.md`
- Ticket:
  `ai/thoughts/tickets/bifrost-console-pr-05-live-sse-delivery.md`
- Research:
  `ai/thoughts/research/2026-07-26-live-sse-delivery.md`
- Phase 1 design:
  `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md`
- Fixture ownership:
  `bifrost-console-fixtures/README.md`
- Future Go live consumer:
  `ai/thoughts/tickets/bifrost-console-pr-11-live-execution-experience.md`
- Framework compatibility policy:
  `ai/thoughts/framework-feature-design-lens.md`
