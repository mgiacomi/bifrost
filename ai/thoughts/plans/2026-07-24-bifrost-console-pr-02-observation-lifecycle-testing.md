# Bifrost Console PR 02 Observation Lifecycle and Live Projection Core Testing Plan

## Change Summary

- Add one optional internal observation handle per authoritative Bifrost
  session, attached before canonical trace initialization.
- Publish a complete logical trace record to that handle only after its entire
  canonical storage representation succeeds, including all payload chunks.
- Deterministically project bounded active state and the settled visible
  activity subset into a concurrent registry and count/byte-bounded replay
  buffer.
- Fail live monitoring closed after an unexpected optional projection or
  publication failure without changing execution or canonical trace/journal
  results.
- Hold canonical completion activity until the complete core finalization
  operation succeeds; substitute one noncanonical
  `EXECUTION_OBSERVATION_ENDED` activity when core finalization fails.
- Remove active state and close the observation handle exactly once on every
  normal, failure, timeout, quota, and cleanup path.
- Keep current auto-configuration on the permanent no-op observation path until
  PR 04 introduces strict opt-in application observability.

The change is a new internal feature rather than a repair of currently
incorrect supported behavior. Testing therefore uses a feature-oriented
red/green sequence: introduce the smallest observation contract needed by the
first test, prove the current construction path cannot satisfy it, then
implement one behavior at a time.

## Requirements and Traceability

The following local IDs make the implementation plan and ticket acceptance
signals traceable to concrete tests:

| ID | Required behavior | Primary evidence |
| --- | --- | --- |
| `PR02-R1` | The handle exists before `TRACE_STARTED` and receives both constructor-time records once. | `BifrostSessionRunnerTest`, `ExecutionTraceHandleTest` |
| `PR02-R2` | Logical activity publishes only after complete canonical append; chunks never become live events. | `ExecutionTraceHandleTest`, `NdjsonExecutionTraceReaderTest` |
| `PR02-R3` | Snapshots, paths, details, summaries, envelopes, and replay are deterministically bounded without retained logical payload. | `LiveActivityProjectorTest`, `InMemoryActivityReplayBufferTest`, architecture tests |
| `PR02-R4` | Every live execution is represented; concurrent sessions/nested frames do not leak state or ordering; observability adds no cardinality cap. | `ExecutionObservationConcurrencyTest` |
| `PR02-R5` | Optional failures are contained, logged safely, and make availability irreversibly false without changing execution/canonical results. | `DefaultExecutionObservationHandleTest`, `BifrostSessionRunnerTest` |
| `PR02-R6` | Normal completion is held/released once; core finalization failure substitutes the exceptional terminal activity; active state is always removed. | `DefaultExecutionObservationHandleTest`, `BifrostSessionRunnerTest`, `ExecutionCoordinatorTest` |
| `PR02-R7` | Disabled/no-op behavior preserves the supported `SkillTemplate` observer, canonical trace/journal behavior, configuration, and default auto-configuration. | `DefaultSkillTemplateTest`, trace regression tests, `BifrostAutoConfigurationBoundaryTest` |
| `PR02-R8` | No supported API, SPI, bean override, serialized format, or Java-to-Go boundary is introduced accidentally. | `BifrostPublicSurfaceArchitectureTest`, `BifrostAutoConfigurationBoundaryTest` |

The relevant roadmap workflow is **Diagnose a currently slow execution**. PR 02
contributes the live-state and activity evidence only; REST, SSE, browser, and
MCP workflow coverage remains assigned to later PRs.

## Impacted Areas

- Canonical append and chunking:
  `ExecutionTraceHandle`, `DefaultExecutionTraceHandle`,
  `NdjsonTraceRecordWriter`, and `NdjsonExecutionTraceReader`.
- Session construction and cleanup:
  `BifrostSession`, `BifrostSessionRunner`, `ExecutionCoordinator`, and
  `DefaultExecutionStateService`.
- New internal observation package:
  lifecycle factory/handle, completion disposition, projector, projection
  state, immutable activity/snapshot DTOs, active registry, replay buffer,
  availability latch, and enabled/no-op implementations.
- Existing diagnostic summarization:
  any tool-identity/error-classification helper extracted from
  `ExecutionJournalProjector`.
- Auto-configuration:
  `BifrostAutoConfiguration` must choose the no-op factory without adding a
  supported bean override or configuration property.
- Architecture boundaries:
  public API allowlist, technically public internal allowlist, SPI absence,
  package-private bean factories, and absence of `@ConditionalOnMissingBean`.
- Protected completed observer:
  `DefaultSkillTemplate` must still invoke the supported
  `Consumer<SkillExecutionView>` only after execution/session finalization.

## Risk Assessment

### Highest-Risk Behaviors

1. Publishing the payload-less chunk envelope or publishing before the final
   chunk write would make live evidence incomplete or contradict the canonical
   trace.
2. Attaching the handle after session construction would permanently omit
   `TRACE_STARTED` and `TRACE_CAPTURE_POLICY_RECORDED`.
3. Releasing `TRACE_COMPLETED` immediately after its append would report normal
   completion even when journal projection or retention deletion later makes
   core finalization fail.
4. Inferring finalization success from `ExecutionTrace.completed()` would miss
   failures occurring after that flag is set.
5. Allowing projector/registry/buffer exceptions into the execution path would
   replace a successful result or alter the existing primary/suppressed
   exception graph.
6. Removing the registry entry only on successful terminal publication would
   leak completed, failed, timed-out, quota-exceeded, or cleanup-failed
   executions.
7. Global mutable projection state could mix frame paths, summaries, counts, or
   canonical sequences between concurrent sessions.
8. Unbounded strings, details, frame paths, payload references, or replay
   retention could make optional monitoring materially affect execution memory.
9. Cursor or ordinal wraparound could silently reuse ordering positions.
10. Adding convenient public constructors, beans, callback types, or
    `@ConditionalOnMissingBean` could accidentally create an unsupported SPI.

### Edge Cases

- Empty/null payload, scalar payload, JSON payload exactly at the chunk
  threshold, and payload one unit above it.
- A writer failure on the envelope, a middle chunk, the final chunk, and the
  normal unchunked record.
- Constructor initialization failure after zero or one initial record.
- Frame close with an already missing frame, nested frames of all
  `TraceFrameType` values, and more than 64 path entries.
- Non-BMP Unicode at every text truncation boundary.
- Details with exactly 32 versus 33 allowlisted fields, exactly 8 KiB versus
  one byte over, and an envelope at versus over 12 KiB retained weight.
- Replay at cursor `0`, empty replay, oldest retained cursor, one-before-oldest,
  current cursor, future cursor, count eviction, byte eviction, and cursor
  overflow.
- Registry ordinal stability across updates, removal between keyset pages, and
  ordinal overflow.
- Duplicate/concurrent `close(...)` calls with the same and conflicting
  dispositions.
- `TRACE_COMPLETED` missing when a success disposition arrives.
- Core finalization failure with and without an independently known outcome.
- Optional failure before identity is known, after registry insertion, while
  publishing ordinary activity, while releasing normal completion, and while
  publishing the exceptional terminal event.
- Execution failure plus frame-close failure plus finalization failure, where
  the original execution failure must remain primary.
- Timeout/interruption cleanup ownership in both direct and step-loop mission
  engines.
- Quota exceptions after the authoritative count has already been stored.

### Protected Compatibility Paths

- **Application API:** The seven documented `com.lokiscale.bifrost.api` types
  and completed `SkillTemplate` observer timing remain protected.
- **Supported SPI:** None exists; tests must continue to prove an empty
  supported override allowlist and no `.spi` package.
- **Configuration and manifest contracts:** No property, default, metadata, or
  YAML change is allowed in PR 02.
- **Persisted or serialized contracts:** No durable contract is affected.
  Current NDJSON bytes, sequence allocation, reader reconstruction, and
  retention behavior must remain coherent.
- **Ephemeral diagnostic formats:** The current writer, reader, completed
  journal, and new live projector must agree on current-run identity, order,
  usage, terminal failure, chunking, and bounded diagnostic meaning.
- **Internal or accidentally exposed implementation:** Constructor/wiring
  changes are approved internal changes. Tests should verify the new coherent
  behavior, not retain old and new construction paths as dual implementations.
  Existing no-op convenience constructors remain because they implement the
  deliberate disabled-observation state, not as deprecated compatibility
  shims.

### Intentionally Absent or Removed Paths

- No public observer SPI, general callback, event bus, subscriber, durable
  queue, retry loop, projector reconstruction, active-trace reader, or trace
  tailer.
- No observability cardinality/admission cap, sampling, or omitted live
  sessions.
- No public `FINALIZING` state, artifact descriptor, trace-availability field,
  or second terminal availability event in PR 02.
- No REST/SSE DTO, opaque external cursor, fixture, or Java-to-Go protocol
  change.
- No old/new dual canonical append path and no alternate live instrumentation
  in individual engine subsystems.

## Existing Test Coverage

- `ExecutionTraceHandleTest` covers persistence policies, timestamp overrides,
  trace-file identity, and rejection of late appends. It does not currently
  observe logical records or inject physical write/delete failures.
- `NdjsonExecutionTraceReaderTest` covers chunk reconstruction, chunk-index
  ordering, incomplete active chunks, trailing partial records, and streaming
  reads. It is the regression oracle for unchanged physical trace semantics.
- `ExecutionJournalProjectorTest` covers sanitized developer-facing
  projection, tool/error interpretation, repeated events, and non-inference.
  It must protect any shared-helper extraction.
- `BifrostSessionRunnerTest` covers standalone finalization, failed actions,
  open-frame cleanup, virtual-thread session/frame/journal isolation,
  authentication, and the configured clock. It lacks an observation factory
  and exact-once cleanup assertions.
- `ExecutionCoordinatorTest` covers ordinary terminal trace behavior,
  root-frame closure, cleanup/finalization suppression, nested YAML routing,
  and mission timeout cleanup. It provides the existing exception-graph and
  nesting patterns to extend.
- `MissionExecutionEngineTest` covers direct-engine timeout/interruption and
  frame cleanup; `StepLoopMissionExecutionEngineTest` covers step-loop terminal
  and retry paths.
- `DefaultSkillTemplateTest#skillTemplateNullInputAndObserverLifecycle` and
  `#observerExceptionPropagatesAfterExecutionCompletes` protect the existing
  completed observer.
- `BifrostPublicSurfaceArchitectureTest` classifies every externally accessible
  type and proves the public API/SPI boundary.
- `BifrostAutoConfigurationBoundaryTest` classifies package-private
  framework-owned bean factories and prohibits
  `@ConditionalOnMissingBean`.
- `SensitiveConnectionDataRedactionTest` demonstrates the repository's
  `OutputCaptureExtension`/`CapturedOutput` pattern for asserting safe logs.
- `InMemoryCapabilityRegistryTest#supportsConcurrentRegistrationAndReads`
  demonstrates latch-based concurrency testing without sleeps.

### Coverage Gaps

- No observation lifecycle, projector, registry, replay buffer, cursor,
  availability, or terminal-substitution test exists.
- No deterministic test can currently fail a selected physical trace write or
  retention delete. Implementation must add narrow package-private injection
  seams rather than rely on OS permissions, locked files, or timing races.
- No existing test proves that optional failures leave the exact original
  execution result and exception graph unchanged.
- No existing test asserts per-entry and aggregate live-state bounds or absence
  of retained payload types.
- No existing test holds many sessions live simultaneously to prove registry
  cardinality has no independent cap.

## Testability Requirements

Production design must expose only package-private/internal test seams:

- A trace writer/file-operations collaborator or package-private trace-handle
  constructor that can fail the Nth append and final deletion deterministically.
- An injectable observation factory at the full `BifrostSessionRunner` /
  `BifrostSession` construction path.
- Internal projector, registry, replay-buffer, clock, and ordinal/cursor
  suppliers that tests can replace with recording, blocking, or throwing
  implementations.
- A deterministic retained-weight calculator shared by production bounds and
  tests.
- Snapshot/replay read methods that return immutable copies and explicit range
  results without exposing mutable backing storage.

These seams must not be Spring bean overrides, public API/SPI, or
`@ConditionalOnMissingBean`. Tests must not use:

- `Thread.sleep(...)` for ordering or cleanup;
- OS file permissions or Windows file locks to induce I/O failures;
- forced garbage collection or `WeakReference` timing to claim no retention;
- reflection to mutate production private state when a package-private
  constructor/supplier is clearer;
- real network, SSE, browser, or Go components.

## Bug Reproduction / Failing Test First

- **Name**:
  `BifrostSessionRunnerTest#attachesObservationBeforeTraceInitialization`
- **Type**: integration-style unit test
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/core/BifrostSessionRunnerTest.java`
- **Arrange**:
  create a `RecordingExecutionObservationHandleFactory`, construct a runner
  through its internal enabled-factory path, and make the action return
  immediately without appending any record itself.
- **Act**:
  run one new session and capture the records received by its one recording
  handle.
- **Assert**:
  exactly one handle was created; its first two records are
  `TRACE_STARTED` and `TRACE_CAPTURE_POLICY_RECORDED`; both have the same
  session/trace IDs and canonical sequences `1` and `2`; `TRACE_STARTED` was
  observed before the action began.
- **Expected failure pre-fix**:
  current production code has no observation factory/handle path and constructs
  and initializes `DefaultExecutionTraceHandle` directly inside
  `BifrostSession`. The test cannot compile until the minimal contract exists;
  if the handle is attached after construction, it compiles but observes zero
  constructor records.
- **Requirements**: `PR02-R1`, `PR02-R7`.

Immediately after this first red/green slice, add
`ExecutionTraceHandleTest#publishesCompleteLogicalChunkedRecordOnlyAfterAllChunksSucceed`.
That second red test prevents an implementation from satisfying construction
ordering by publishing the current payload-less return envelope.

## Test Doubles and Fixtures

Place focused reusable doubles under:
`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/`.

- `RecordingExecutionObservationHandleFactory`:
  records handle count, per-handle logical records, close calls, and close
  dispositions with immutable copies.
- `RecordingExecutionObservationHandle`:
  provides latches for "registered", "record received", and "closed"; it never
  sleeps.
- `ControllableTraceRecordWriter` or `ControllableTraceFileOperations`:
  records physical writes and can fail exactly before or after a selected write
  number or on final deletion.
- `ThrowingLiveActivityProjector`, `ThrowingActiveExecutionRegistry`, and
  `ThrowingActivityReplayBuffer`:
  fail one selected operation while recording all attempted calls.
- `ControllableOrdinalSupplier`:
  supplies normal positions or `Long.MAX_VALUE` to exercise overflow without
  billions of operations.
- `TestTraceRecords`:
  builds canonical records with fixed UUID-like IDs, fixed clock values,
  explicit frames, usage, and terminal metadata. Keep payload builders local
  and minimal rather than introducing external golden files.
- Use `Clock.fixed(...)`, `CountDownLatch`, virtual-thread executors, AssertJ,
  Mockito where interaction ordering helps, and Spring Boot
  `OutputCaptureExtension` for logs.

No `bifrost-console-fixtures` changes are expected. PRs 04-06 own external
snapshot/SSE DTOs and Java-to-Go semantic fixtures.

## Tests to Add or Update

### 1. Constructor-Time Observation

- **Names**:
  - `BifrostSessionRunnerTest#attachesObservationBeforeTraceInitialization`
  - `ExecutionTraceHandleTest#publishesTraceStartedAndCapturePolicyExactlyOnce`
  - `BifrostSessionRunnerTest#closesHandleWhenTraceConstructionFailsAfterRegistration`
- **Type**: unit/integration
- **Locations**:
  existing runner and trace-handle test classes
- **What they prove**:
  one handle per session; initial record order/identity; action sees already
  registered active state; a constructor failure cannot leak an entry; no-op
  construction remains functional.
- **Fixtures/data**:
  fixed clock, recording factory, writer failing on the first or second
  initialization append.
- **Mocks**:
  custom recording/failing doubles; no Mockito required.
- **Contract classification**:
  Internal or accidentally exposed implementation; ephemeral diagnostic
  ordering.
- **Compatibility expectation**:
  approved internal construction change plus current-run diagnostic coherence.
- **Requirements**: `PR02-R1`, `PR02-R5`, `PR02-R7`.

### 2. Post-Append Logical Publication and Chunk Integrity

- **Names**:
  - `ExecutionTraceHandleTest#publishesUnchunkedLogicalRecordAfterWriterReturns`
  - `ExecutionTraceHandleTest#publishesCompleteLogicalChunkedRecordOnlyAfterAllChunksSucceed`
  - `ExecutionTraceHandleTest#doesNotPublishWhenEnvelopeWriteFails`
  - `ExecutionTraceHandleTest#doesNotPublishWhenMiddleChunkWriteFails`
  - `ExecutionTraceHandleTest#doesNotPublishWhenFinalChunkWriteFails`
  - `ExecutionTraceHandleTest#neverPublishesPayloadChunkRecords`
  - `ExecutionTraceHandleTest#preservesPhysicalBytesAndSequencesWithObservationEnabled`
- **Type**: unit
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionTraceHandleTest.java`
- **What they prove**:
  callback timing follows complete storage success; logical data is present for
  chunked records; physical chunks retain existing sequence/layout; no partial
  write produces live state; enabled observation does not alter NDJSON.
- **Fixtures/data**:
  payloads at 4,096 characters and 4,097 characters, deterministic payload ID,
  fixed clock, controllable writer, recording handle, reader reconstruction.
- **Mocks**:
  custom controllable writer; use an ordered call log instead of elapsed time.
- **Contract classification**:
  Ephemeral diagnostic formats.
- **Compatibility expectation**:
  current writer/reader/projector coherence; no historical-format promise.
- **Requirements**: `PR02-R1`, `PR02-R2`, `PR02-R7`.

### 3. Visible-Subset and Snapshot Projection

- **Names**:
  - `LiveActivityProjectorTest#projectsExactlyTheSettledVisibleRecordKinds`
  - `LiveActivityProjectorTest#updatesFrameStateForInvisibleFrameLifecycleWithoutPublishingActivity`
  - `LiveActivityProjectorTest#publishesSkillFrameEventsOnlyForSkillExecutionFrames`
  - `LiveActivityProjectorTest#derivesEntrySkillFromFirstRootMission`
  - `LiveActivityProjectorTest#derivesCountsAndUsageFromCanonicalFacts`
  - `LiveActivityProjectorTest#replacesDerivedCountsWithTerminalUsageSnapshot`
  - `LiveActivityProjectorTest#holdsTraceCompletedInsteadOfPublishingItImmediately`
  - `LiveActivityProjectorTest#keepsExecutionOutcomeSeparateFromObservationFailure`
- **Type**: parameterized unit tests where appropriate
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/LiveActivityProjectorTest.java`
- **What they prove**:
  every `TraceRecordType` is intentionally visible, state-only, held-terminal,
  or omitted; active phase/path/counts and terminal facts remain deterministic;
  no hidden reasoning or invented outcome is produced.
- **Fixtures/data**:
  one fixed record per enum value; nested frames of every
  `TraceFrameType`; exact/heuristic/unavailable usage; failed and aborted
  completions.
- **Mocks**:
  none; projector is tested as a pure transition.
- **Contract classification**:
  Ephemeral diagnostic formats.
- **Compatibility expectation**:
  current-release projection accuracy and failure visibility.
- **Requirements**: `PR02-R3`, `PR02-R6`.

### 4. Bounded Text, Details, Paths, and Retention

- **Names**:
  - `LiveActivityProjectorTest#truncatesTextByUnicodeCodePointWithoutSplittingSurrogates`
  - `LiveActivityProjectorTest#boundsActivePathAndPreservesRootAndMostRecentSuffix`
  - `LiveActivityProjectorTest#boundsAllowlistedDetailsByFieldCountAndUtf8Weight`
  - `LiveActivityProjectorTest#rejectsOrTruncatesEnvelopeAboveRetainedWeightLimit`
  - `LiveActivityProjectorTest#doesNotRetainTraceRecordJsonNodePlanOrException`
  - `BifrostPublicSurfaceArchitectureTest#observationDtosExposeOnlyBoundedImmutableDomainTypes`
- **Type**: unit and architecture
- **Locations**:
  projector and public-surface architecture tests
- **What they prove**:
  exact 64/256/512/32/8-KiB/12-KiB bounds; explicit truncation facts; no
  `JsonNode`, raw `TraceRecord`, `Path`, `Resource`, exception, callback,
  stream, publisher, or mutable collection crosses into retained DTO state.
- **Fixtures/data**:
  non-BMP strings at boundaries; 65+ nested frames; allowlisted maps at and
  over field/byte bounds; multi-megabyte sentinel payload whose unique text
  must not appear in any retained snapshot/envelope.
- **Mocks**:
  none. Use recursive value assertions and architecture field/signature checks,
  not GC-dependent heap assertions.
- **Contract classification**:
  Ephemeral diagnostic formats and Internal or accidentally exposed
  implementation.
- **Compatibility expectation**:
  current-run security/resource coherence; no public-surface leak.
- **Requirements**: `PR02-R3`, `PR02-R8`.

### 5. Active Registry Semantics

- **Names**:
  - `InMemoryActiveExecutionRegistryTest#assignsOneStableOrdinalPerSession`
  - `InMemoryActiveExecutionRegistryTest#replacesSnapshotWithoutChangingOrdinal`
  - `InMemoryActiveExecutionRegistryTest#looksUpAndRemovesBySessionIdWithoutTombstone`
  - `InMemoryActiveExecutionRegistryTest#traversesNewestFirstAtOrBelowHighWaterMark`
  - `InMemoryActiveExecutionRegistryTest#toleratesRemovalBetweenTraversalPages`
  - `InMemoryActiveExecutionRegistryTest#supportsConcurrentIndependentSessionUpdates`
  - `InMemoryActiveExecutionRegistryTest#failsInsteadOfWrappingRegistryOrdinal`
- **Type**: unit/concurrency
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActiveExecutionRegistryTest.java`
- **What they prove**:
  key/ordinal semantics required by PR 04; immutable reads; current-entry
  removal; no pagination copy or tombstone; safe concurrent updates; no ordinal
  reuse.
- **Fixtures/data**:
  fixed snapshots, controllable ordinal supplier, latches for simultaneous
  updates/removals.
- **Mocks**:
  none.
- **Contract classification**:
  Internal or accidentally exposed implementation.
- **Compatibility expectation**:
  approved new internal semantics; future REST mapping must not expose the
  internal numeric cursor directly.
- **Requirements**: `PR02-R3`, `PR02-R4`.

### 6. Replay Buffer Bounds and Cursor Semantics

- **Names**:
  - `InMemoryActivityReplayBufferTest#usesZeroAsBeforeFirstAndAssignsPositiveMonotonicCursors`
  - `InMemoryActivityReplayBufferTest#replaysCompleteEventsStrictlyAfterCursor`
  - `InMemoryActivityReplayBufferTest#distinguishesEmptyStaleCurrentAndFutureRanges`
  - `InMemoryActivityReplayBufferTest#evictsOldestCompleteEventsAtCountLimit`
  - `InMemoryActivityReplayBufferTest#evictsOldestCompleteEventsAtByteLimit`
  - `InMemoryActivityReplayBufferTest#enforcesBothBoundsSimultaneously`
  - `InMemoryActivityReplayBufferTest#supportsConcurrentPublishersWithoutCursorReuse`
  - `InMemoryActivityReplayBufferTest#failsInsteadOfWrappingDeliveryCursor`
- **Type**: unit/concurrency
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActivityReplayBufferTest.java`
- **What they prove**:
  10,000-event/16-MiB limits, whole-envelope eviction, cursor ordering and
  expiration inputs for PR 05, overwrite-without-blocking semantics, and safe
  overflow.
- **Fixtures/data**:
  lightweight envelopes with exact retained weights; small injected limits for
  most boundary tests plus one test using production constants; controllable
  cursor supplier.
- **Mocks**:
  none.
- **Contract classification**:
  Internal or accidentally exposed implementation; future ephemeral live
  protocol input.
- **Compatibility expectation**:
  approved internal semantics, not yet an external cursor contract.
- **Requirements**: `PR02-R3`, `PR02-R4`.

### 7. One-Way Availability and Safe Diagnostics

- **Names**:
  - `LiveMonitoringAvailabilityTest#transitionsFromAvailableToUnavailableOnce`
  - `LiveMonitoringAvailabilityTest#preservesFirstFailureClassification`
  - `LiveMonitoringAvailabilityTest#cannotResetOrRecoverInProcess`
  - `DefaultExecutionObservationHandleTest#logsOneSanitizedDiagnosticOnFirstFailure`
- **Type**: unit
- **Locations**:
  observation tests
- **What they prove**:
  one-way state, first-failure ownership, absence of a recovery API, and a log
  containing only the stable operation, opaque IDs, and exception class.
- **Fixtures/data**:
  exceptions whose messages, stack frames, payloads, metadata, and summaries
  contain unique secret sentinels.
- **Mocks**:
  `OutputCaptureExtension`/`CapturedOutput`; throwing component doubles.
- **Contract classification**:
  Ephemeral diagnostic formats and Internal or accidentally exposed
  implementation.
- **Compatibility expectation**:
  current-run security and truthful availability.
- **Requirements**: `PR02-R5`.

### 8. Optional Failure Isolation

- **Names**:
  - `DefaultExecutionObservationHandleTest#containsProjectorFailureAndFailsClosed`
  - `DefaultExecutionObservationHandleTest#containsRegistryFailureAndFailsClosed`
  - `DefaultExecutionObservationHandleTest#containsReplayFailureAndFailsClosed`
  - `DefaultExecutionObservationHandleTest#ignoresLaterPublicationAfterFailClosed`
  - `BifrostSessionRunnerTest#optionalObservationFailureDoesNotChangeSuccessfulResult`
  - `BifrostSessionRunnerTest#optionalObservationFailureDoesNotReplaceOrSuppressActionFailure`
  - `BifrostSessionRunnerTest#optionalObservationFailureDoesNotChangeCanonicalTraceBytes`
- **Type**: unit/integration
- **Locations**:
  enabled-handle and runner tests
- **What they prove**:
  every optional failure boundary is non-throwing; availability becomes false;
  later live state is not served as trustworthy; successful results and
  existing exception/suppression behavior are bit-for-bit/identity equivalent
  to the no-op control run.
- **Fixtures/data**:
  one no-op control execution and one enabled/failing execution with identical
  fixed IDs/clocks/records; throwing projector/registry/buffer.
- **Mocks**:
  custom throwing doubles; capture original exception instances and suppressed
  arrays rather than comparing message text alone.
- **Contract classification**:
  Application API protection, Ephemeral diagnostic formats, and Internal or
  accidentally exposed implementation.
- **Compatibility expectation**:
  protected execution result and canonical failure behavior.
- **Requirements**: `PR02-R5`, `PR02-R7`.

### 9. Normal and Exceptional Terminal Lifecycle

- **Names**:
  - `DefaultExecutionObservationHandleTest#holdsCanonicalCompletionUntilCoreSuccessClose`
  - `DefaultExecutionObservationHandleTest#releasesExactlyOneCanonicalCompletionOnCoreSuccess`
  - `DefaultExecutionObservationHandleTest#discardsHeldCompletionAndPublishesObservationEndedOnCoreFailure`
  - `DefaultExecutionObservationHandleTest#observationEndedOmitsUntrustedOutcomeAndExceptionContent`
  - `DefaultExecutionObservationHandleTest#removesActiveEntryWhenTerminalReplayPublicationFails`
  - `DefaultExecutionObservationHandleTest#failsClosedWhenExceptionalTerminalPublicationFails`
  - `DefaultExecutionObservationHandleTest#treatsMissingHeldCompletionOnSuccessAsFailClosed`
  - `DefaultExecutionObservationHandleTest#closesExactlyOnceUnderConcurrentConflictingCalls`
- **Type**: unit/concurrency
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandleTest.java`
- **What they prove**:
  at-most-one outward terminal event; correct normal/exceptional substitution;
  nullable trustworthy outcome; no artifact placeholder; removal in `finally`;
  no retries/tombstones; exact-once close.
- **Fixtures/data**:
  held `TRACE_COMPLETED`, success/failure dispositions, latches to race close
  callers, terminal replay failure, fixed close clock.
- **Mocks**:
  recording/throwing registry and replay buffer.
- **Contract classification**:
  Ephemeral diagnostic formats and Internal or accidentally exposed
  implementation.
- **Compatibility expectation**:
  current-release terminal truthfulness; PR 03 may atomically enrich only the
  internal success path.
- **Requirements**: `PR02-R5`, `PR02-R6`.

### 10. Core Finalization Disposition and Exception Preservation

- **Names**:
  - `BifrostSessionRunnerTest#closesObservationWithSuccessOnlyAfterJournalTraceAndRetentionSucceed`
  - `BifrostSessionRunnerTest#usesCoreFailureDispositionWhenJournalProjectionFails`
  - `BifrostSessionRunnerTest#usesCoreFailureDispositionWhenCompletionAppendFails`
  - `BifrostSessionRunnerTest#usesCoreFailureDispositionWhenRetentionDeletionFailsAfterCompletedFlag`
  - `BifrostSessionRunnerTest#preservesProjectionAndFinalizationSuppressionGraph`
  - `ExecutionCoordinatorTest#preservesMissionFailureWhenObservationAndCoreCleanupAlsoFail`
- **Type**: integration-style unit tests
- **Locations**:
  runner and coordinator tests
- **What they prove**:
  disposition comes from the actual `finalizeTrace(...)` result, never from the
  completed flag; existing projection/finalization and mission/cleanup
  exception precedence is unchanged; observation failures never enter the
  suppressed list.
- **Fixtures/data**:
  injectable journal projector/trace handle or file operations; failures at
  journal projection, completion append, and post-completion delete; original
  action and cleanup exception instances.
- **Mocks**:
  narrow package-private fakes or Mockito for journal/trace collaborators.
- **Contract classification**:
  Ephemeral diagnostic formats and Internal or accidentally exposed
  implementation.
- **Compatibility expectation**:
  protected current canonical failure semantics.
- **Requirements**: `PR02-R5`, `PR02-R6`, `PR02-R7`.

### 11. Success, Failure, Timeout, Quota, and Cleanup Coverage

- **Names**:
  - `BifrostSessionRunnerTest#removesActiveEntryAfterStandaloneSuccess`
  - `BifrostSessionRunnerTest#removesActiveEntryAfterStandaloneActionFailure`
  - `BifrostSessionRunnerTest#removesActiveEntryAfterOpenFrameCleanupFailure`
  - `ExecutionCoordinatorTest#removesActiveEntryAfterMissionSuccessAndFailure`
  - `ExecutionCoordinatorTest#removesActiveEntryAfterMissionTimeout`
  - `ExecutionCoordinatorTest#removesActiveEntryAfterQuotaExceeded`
  - Extend direct/step engine timeout tests to assert one final handle close and
    no remaining active entry.
- **Type**: integration-style unit tests
- **Locations**:
  runner, coordinator, direct engine, and step-loop engine tests
- **What they prove**:
  every authoritative lifecycle path closes once and removes the session;
  timeout cleanup ownership still unwinds frames before terminal observation;
  quota counts remain visible and original exceptions remain primary.
- **Fixtures/data**:
  existing blocking chat clients and cleanup doubles, enabled recording
  factory, low quota configuration, latches.
- **Mocks**:
  reuse current coordinator/engine patterns; no real provider/network.
- **Contract classification**:
  Application API protection, Ephemeral diagnostic formats, and Internal or
  accidentally exposed implementation.
- **Compatibility expectation**:
  protected execution/timeout/quota behavior plus new internal cleanup.
- **Requirements**: `PR02-R5`, `PR02-R6`, `PR02-R7`.

### 12. Concurrent Sessions, Nested Frames, and No Observability Cap

- **Names**:
  - `ExecutionObservationConcurrencyTest#representsEveryBlockedLiveSessionWithoutSamplingOrAdmission`
  - `ExecutionObservationConcurrencyTest#isolatesNestedFramePathsCountsAndCanonicalOrder`
  - `ExecutionObservationConcurrencyTest#assignsGloballyUniqueReplayCursorsAcrossSessions`
  - `ExecutionObservationConcurrencyTest#removesAllEntriesAfterConcurrentRelease`
- **Type**: repeated concurrency integration test
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionObservationConcurrencyTest.java`
- **What they prove**:
  128 simultaneously blocked virtual-thread sessions all appear; nested frames
  and counts stay session-local; per-trace canonical sequences increase;
  delivery cursors are globally unique/monotonic; all entries disappear after
  deterministic release.
- **Fixtures/data**:
  `@RepeatedTest(10)`, virtual-thread executor, start/ready/release latches,
  unique routes and payload sentinels per session, one shared enabled factory.
- **Mocks**:
  recording engine/action only; no sleeps and no network.
- **Contract classification**:
  Internal or accidentally exposed implementation and Ephemeral diagnostic
  formats.
- **Compatibility expectation**:
  new internal concurrency semantics and the ticket's no-cap guardrail.
- **Requirements**: `PR02-R4`, `PR02-R6`.

### 13. Completed Journal and Supported Observer Regression

- **Names**:
  - Keep all `ExecutionJournalProjectorTest` cases unchanged or update only
    construction helpers after shared-helper extraction.
  - Re-run
    `DefaultSkillTemplateTest#skillTemplateNullInputAndObserverLifecycle`.
  - Re-run
    `DefaultSkillTemplateTest#observerExceptionPropagatesAfterExecutionCompletes`.
  - Add
    `DefaultSkillTemplateTest#liveObservationNeverInvokesCompletedApplicationObserverEarly`
    using the same internal runner/factory injection seam exercised by the
    lifecycle tests; do not widen production API to arrange it.
- **Type**: regression/integration
- **Locations**:
  existing journal and skill-template tests
- **What they prove**:
  completed journal entries retain current content/redaction; the supported
  observer remains post-finalization and separate from live observation; its
  exception behavior remains unchanged.
- **Fixtures/data**:
  existing plans/tool/error records and a recording application observer.
- **Mocks**:
  existing Mockito router/registry patterns.
- **Contract classification**:
  Application API and Ephemeral diagnostic formats.
- **Compatibility expectation**:
  protected completed observer and current journal coherence.
- **Requirements**: `PR02-R7`.

### 14. Disabled Auto-Configuration and Public-Surface Closure

- **Names**:
  - `BifrostAutoConfigurationBoundaryTest#bifrostSessionRunnerUsesFrameworkOwnedNoOpObservation`
  - `BifrostAutoConfigurationBoundaryTest#everyBeanFactoryIsClassifiedAndPackagePrivate`
  - `BifrostAutoConfigurationBoundaryTest#productionTypesDoNotUseConditionalOnMissingBean`
  - `BifrostPublicSurfaceArchitectureTest#everyExternallyAccessibleTopLevelTypeIsClassified`
  - `BifrostPublicSurfaceArchitectureTest#noSupportedSpiPackageOrTypeExists`
  - `BifrostPublicSurfaceArchitectureTest#apiSignaturesRecursivelyExcludeInternalAndAutoconfigureTypes`
- **Type**: architecture/integration
- **Locations**:
  existing architecture tests
- **What they prove**:
  PR 02 creates no opt-in property, public bean, supported override, SPI, or API
  signature leak; default runner uses the no-op path; technically public
  internal types have explicit reasons.
- **Fixtures/data**:
  reflection/ArchUnit import of production classes and direct invocation of the
  package-private auto-configuration factory where useful.
- **Mocks**:
  none; construct validated `BifrostProperties` and `ExecutionTraceProperties`
  values directly when invoking the package-private runner factory.
- **Contract classification**:
  Application API, Supported SPI, Configuration and manifest contracts, and
  Internal or accidentally exposed implementation.
- **Compatibility expectation**:
  protected closed surface; approved internal types classified explicitly.
- **Requirements**: `PR02-R7`, `PR02-R8`.

## Recommended Red/Green Execution Order

1. Add the minimal observation contract and make
   `attachesObservationBeforeTraceInitialization` red, then green.
2. Make logical ordinary/chunked post-append publication tests red, then green;
   run reader/byte regressions immediately.
3. Implement immutable domain values and make projector visible-subset/bounds
   tests green.
4. Implement registry, replay buffer, and availability independently with their
   full boundary tests.
5. Implement the enabled handle and make ordinary publication/fail-closed tests
   green.
6. Add held terminal/exceptional substitution and exact-once close tests before
   changing `BifrostSession.finalizeTrace(...)`.
7. Wire finalization disposition and run the complete existing
   runner/coordinator exception regression set after each failure case.
8. Add lifecycle matrix and repeated concurrency tests.
9. Close architecture and completed observer/journal regressions.
10. Run the focused suite repeatedly, then the full module and repository
    verification.

Do not implement all production classes first and add tests afterward; the
append seam, terminal disposition, and failure isolation each need an
independent red/green checkpoint.

## How to Run

No external provider, credential, browser, Go toolchain, network service,
profile, or environment variable is required. Tests use fixed clocks, temporary
files, in-memory stores, and local doubles.

### First Red Test

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter -am `
  -Dtest=BifrostSessionRunnerTest#attachesObservationBeforeTraceInitialization `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

### Trace Append and Chunking

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter -am `
  -Dtest=ExecutionTraceHandleTest,NdjsonExecutionTraceReaderTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

### Observation Unit Suite

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter -am `
  -Dtest=LiveActivityProjectorTest,InMemoryActiveExecutionRegistryTest,InMemoryActivityReplayBufferTest,LiveMonitoringAvailabilityTest,DefaultExecutionObservationHandleTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

### Lifecycle and Concurrency Suite

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter -am `
  -Dtest=BifrostSessionRunnerTest,ExecutionCoordinatorTest,MissionExecutionEngineTest,StepLoopMissionExecutionEngineTest,ExecutionObservationConcurrencyTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

### Compatibility and Diagnostic Regression Suite

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter -am `
  -Dtest=ExecutionTraceHandleTest,NdjsonExecutionTraceReaderTest,ExecutionJournalProjectorTest,ExecutionTraceContractTest,DefaultSkillTemplateTest,BifrostPublicSurfaceArchitectureTest,BifrostAutoConfigurationBoundaryTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

### Repeat the Concurrency Class

The class itself uses `@RepeatedTest(10)`; run it at least three independent
Maven invocations to vary scheduling:

```powershell
1..3 | ForEach-Object {
  .\mvnw.cmd -pl bifrost-spring-boot-starter -am `
    -Dtest=ExecutionObservationConcurrencyTest `
    -Dsurefire.failIfNoSpecifiedTests=false test
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
```

### Module and Repository Verification

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter -am test
.\mvnw.cmd verify
git diff --check
```

## Manual Verification

PR 02 has no user-visible route or UI, so manual verification is limited to
internal evidence review:

1. Pause two enabled, nested executions on latches and inspect immutable
   snapshots: both sessions must appear, routes/frames/counts must not cross,
   and no execution may be omitted.
2. Release one normally and inject core finalization failure into the other.
   Inspect replay: one held/released `TRACE_COMPLETED`, one
   `EXECUTION_OBSERVATION_ENDED`, no duplicate terminal activity, and no active
   entries afterward.
3. Inspect one chunked-payload run: the physical file contains the envelope and
   chunks, the logical callback contains the complete payload, and retained
   live state contains neither payload nor chunk activity.
4. Inspect captured fail-closed output: it contains only the stable operation,
   opaque IDs, and exception class. Secret sentinels from exception message,
   stack, metadata, summary, and payload are absent.
5. Review the produced public/bean surface diff and configuration metadata:
   there must be no route, property, YAML change, public callback, supported
   override, or `.spi` addition.
6. If allocation profiling is available, run the bounded stress test and
   confirm replay retained weight stays below production limits while registry
   size follows the deliberately blocked live session count. This profile is
   supplementary; deterministic automated bounds remain the release gate.

## Exit Criteria

- [x] The first failing test exists and demonstrates that current construction
      cannot observe constructor-time records; its minimal implementation turns
      green before broader observation work proceeds.
- [x] Ordinary and chunked logical records publish only after complete storage
      success, and every selected write failure publishes nothing for that
      logical record.
- [x] Current NDJSON bytes, sequence allocation, chunk reconstruction,
      persistence policies, journal projection, and canonical failure
      visibility remain coherent.
- [x] Every `TraceRecordType` has an explicit visible/state-only/held/omitted
      projector assertion; only the settled subset emits activity.
- [x] Exact 64-path, 256-text, 512-summary, 32-detail-field, 8-KiB-detail,
      12-KiB-envelope, 10,000-event, and 16-MiB-replay bounds pass at, below,
      and above their boundaries.
- [x] Retained DTOs and public signatures expose no logical payload tree,
      canonical `TraceRecord`, filesystem path/resource, exception, callback,
      stream, publisher, or mutable collection.
- [x] Registry ordinals and delivery cursors are positive, monotonic, distinct,
      do not wrap, and remain separate from canonical trace sequence.
- [x] At least 128 deliberately simultaneous live sessions are all represented
      in repeated tests without admission, sampling, omission, or state leakage.
- [x] Projector, registry, replay, normal-terminal, and exceptional-terminal
      failures are injected independently; each fails availability closed while
      preserving execution results and canonical exception identity/suppression.
- [x] Success, action failure, root-frame cleanup failure, timeout,
      interruption, quota, journal-projection failure, completion-append
      failure, retention-deletion failure, and constructor failure each close
      at most once and leave no active entry.
- [x] A successful core finalization releases exactly one held canonical
      completion. A failed core finalization discards it and attempts exactly
      one noncanonical `EXECUTION_OBSERVATION_ENDED` without inventing outcome
      or exposing cause content.
- [x] Fail-closed logs contain the stable operation, opaque IDs when known, and
      exception class only; all sentinel application/credential content is
      absent.
- [x] The supported completed `SkillTemplate` observer still runs after
      execution finalization and retains its existing exception behavior.
- [x] No skill-authoring document or coverage-table update is required because
      no author-facing behavior is exposed by PR 02.
- [x] No new configuration/manifest contract, supported API/SPI, replaceable
      Spring bean, `@ConditionalOnMissingBean`, persisted format, external
      fixture, or Java-to-Go boundary exists.
- [x] No compatibility shim, legacy publisher, dual append path, alternate
      engine instrumentation, retry, reconstruction, or tombstone remains.
- [x] All focused suites, three independent concurrency invocations, full
      starter tests, `.\mvnw.cmd verify`, and `git diff --check` pass.
- [x] Manual evidence review is complete; no PR 02 route/UI/E2E test is
      incorrectly required.

## References

- Implementation plan:
  `ai/thoughts/plans/2026-07-24-bifrost-console-pr-02-observation-lifecycle.md`
- Ticket:
  `ai/thoughts/tickets/bifrost-console-pr-02-observation-lifecycle.md`
- Research:
  `ai/thoughts/research/2026-07-24-bifrost-console-pr-02-observation-lifecycle.md`
- Settled Phase 1 design:
  `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md`
- Testing-plan command:
  `ai/commands/3_testing_plan.md`
