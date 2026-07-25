# Bifrost Console PR 02 Observation Lifecycle and Live Projection Core Implementation Plan

## Overview

Implement one optional, framework-owned observation lifecycle per authoritative
top-level Bifrost session. The lifecycle receives a logical canonical record
only after its complete NDJSON representation has been appended, projects a
small bounded active snapshot and zero or one bounded activity envelope, and
closes exactly once after canonical finalization without changing execution,
trace, journal, timeout, quota, or cleanup results.

This PR establishes only the in-process core that PR 03 will enrich with
finalized-artifact availability, PR 04 will map to REST snapshots, and PR 05
will consume for SSE delivery. It does not expose an application protocol or
enable observability for ordinary auto-configured applications yet.

## Current State Analysis

- `BifrostSession` creates one `DefaultExecutionTraceHandle` during session
  construction. That trace handle immediately appends `TRACE_STARTED` and
  `TRACE_CAPTURE_POLICY_RECORDED`, before the coordinator opens the root mission
  frame
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java:97-128`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:54-121`).
- Both framed and unframed trace writes converge on
  `BifrostSession.appendTraceRecord(...)` while the session's `ReentrantLock` is
  held
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java:713-762`).
- `DefaultExecutionTraceHandle.appendInternal(...)` retains the logical
  `JsonNode`, writes the envelope and every chunk for a large payload, and
  returns only after the complete physical append. Its current return value is
  the payload-less envelope for chunked records, so that returned value cannot
  drive live projection
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:212-296`).
- `ExecutionCoordinator` owns ordinary top-level root-frame closure and
  canonical finalization. `BifrostSessionRunner` is the second, guaranteed
  top-level safety net when the coordinator did not finish the trace
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionCoordinator.java:135-210`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSessionRunner.java:112-190`).
- `BifrostSession.finalizeTrace(...)` can fail because completed-journal
  projection failed, because `TRACE_COMPLETED` or retention finalization
  failed, or both. Those failures currently retain their propagation and
  suppressed-exception behavior
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java:619-692`).
- The completed `SkillTemplate` observer is a supported Application API invoked
  after session completion. It is not a live append observer and is not called
  under the session lock
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skillapi/DefaultSkillTemplate.java:104-123`).
- The session already contains the facts needed for bounded live state:
  hierarchical frame identities and routes, per-session usage, terminal
  outcome, and trace/session identity. There is currently no live projector,
  active registry, replay cursor, availability latch, or execution-wide
  concurrency limit.

## Desired End State

After this PR:

1. Every session created with enabled observation has exactly one observation
   handle installed before either constructor-time trace record is appended.
2. A canonical append publishes exactly one reconstructed logical record only
   after the complete physical append succeeds. Failed or partially chunked
   writes publish nothing.
3. The enabled handle deterministically maintains one bounded current snapshot
   for each live execution, appends only the settled visible activity subset to
   a bounded process-local replay buffer, and never retains a logical trace
   payload after projection returns.
4. Multiple sessions may publish concurrently without sharing per-execution
   frame state or canonical ordering. Registry cardinality follows actual live
   sessions and has no observability admission cap, sampling, or omission.
5. Unexpected projector, registry, or replay-buffer failure is contained,
   logged without application content, and irreversibly changes the enabled
   core's process-local live-monitoring availability from available to
   unavailable. It never changes an execution result.
6. `BifrostSession.finalizeTrace(...)` closes the observation handle once with
   either `CORE_FINALIZATION_SUCCEEDED` or `CORE_FINALIZATION_FAILED`. Successful
   close releases the held canonical `TRACE_COMPLETED` activity; failed core
   finalization discards it and attempts one noncanonical
   `EXECUTION_OBSERVATION_ENDED` activity before active-entry removal.
7. Disabled observation uses a no-op factory/handle and preserves current
   runtime behavior and overhead apart from a constant no-op call.
8. Focused tests prove construction ordering, logical chunk publication,
   boundedness, cursor behavior, concurrency isolation, fail-closed behavior,
   all completion paths, and unchanged canonical exception semantics.

### Key Discoveries

- The trace handle, not a trace-file reader or the current return value from
  `appendInternal(...)`, is the only place that still has both the logical
  payload and knowledge that every chunk write succeeded.
- The observation handle must be created before the trace handle's constructor
  initializes the trace; attaching it later in `BifrostSessionRunner` would
  always miss the first two canonical records.
- `TRACE_COMPLETED` is appended before the trace handle applies its retention
  policy. Therefore the projector must hold that one bounded terminal activity
  until the complete existing finalization operation returns; a successful
  append alone is not sufficient to release a trustworthy normal completion.
- A completed trace snapshot is not proof that the whole core finalization
  operation succeeded: deletion can fail after the handle marks itself
  completed. The observation disposition must come directly from
  `BifrostSession.finalizeTrace(...)`, not be inferred later from
  `ExecutionTrace.completed()`.
- The settled visible activity set is:
  `TRACE_STARTED`; `FRAME_OPENED` and `FRAME_CLOSED` only for
  `SKILL_EXECUTION`; `MODEL_REQUEST_SENT`; `MODEL_RESPONSE_RECEIVED`;
  `PLAN_CREATED`; `PLAN_UPDATED`; `PLAN_VALIDATION_FAILED`;
  `PLAN_RETRY_REQUESTED`; `TOOL_CALL_STARTED`; `TOOL_CALL_COMPLETED`;
  `TOOL_CALL_FAILED`; `STEP_STARTED`; `STEP_ACTION_REJECTED`;
  `STEP_COMPLETED`; `ERROR_RECORDED`; and `TRACE_COMPLETED`.
  `EXECUTION_OBSERVATION_ENDED` is the sole noncanonical kind
  (`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:470-478`).

## Resolved Planning Decisions

The research document's open implementation questions are resolved as follows:

1. **Package/type decomposition:** Put the lifecycle interfaces and completion
   disposition in
   `com.lokiscale.bifrost.internal.runtime.observation`; keep the enabled
   projector, registry, buffer, availability latch, bounded DTOs, and factory in
   that same internal package. Cross-package types may be technically public
   only where Java access requires it and must be added to the internal
   architecture allowlist.
2. **Construction order:** `BifrostSession` receives an
   `ExecutionObservationHandleFactory`, creates the no-op or enabled handle
   first, then passes that handle to `DefaultExecutionTraceHandle`. Trace-handle
   initialization invokes the already-attached handle for both initial records.
   Existing convenience constructors deliberately select the no-op factory;
   this is the supported disabled-observation path, not a temporary
   compatibility shim.
3. **Bounds and cursors:** Use the following initial internal constants:

   | Resource | Initial bound |
   | --- | ---: |
   | Active frame path | 64 entries, preserving the root and most-recent suffix and reporting truncation |
   | Skill, route, phase, and classification text | 256 Unicode code points per value |
   | Concise summary | 512 Unicode code points |
   | Bounded details | allowlisted scalar fields only, at most 32 fields and 8 KiB retained UTF-8 weight |
   | One activity envelope | 12 KiB maximum retained weight including structure |
   | Replay event count | 10,000 complete envelopes |
   | Replay retained bytes | 16 MiB |
   | Delivery cursor and registry ordinal | positive signed `long`; `0` means no prior item |

   The buffer evicts oldest complete envelopes until both limits hold. It never
   blocks or rejects the newest valid envelope. Cursor or ordinal overflow is an
   unexpected publication failure and fails live monitoring closed rather than
   wrapping.
4. **Completion disposition:** Add one internal value with exactly two statuses:
   `CORE_FINALIZATION_SUCCEEDED` and `CORE_FINALIZATION_FAILED`, plus the
   independently known nullable `TraceOutcome` and close timestamp. Do not use
   `ExecutionTrace.completed()` to derive it.
5. **PR 03 handoff:** PR 02's successful disposition releases a normal terminal
   activity without artifact-availability fields. PR 03 atomically evolves
   this internal success value to carry the finalized-artifact descriptor,
   publishes the catalog entry first, and enriches the held terminal activity
   before release. PR 02 does not introduce a placeholder descriptor, public
   `FINALIZING` state, or a second availability event.
6. **Logging:** `DefaultExecutionObservationHandle` owns fail-closed logging. It
   emits one error when availability first transitions to unavailable with only
   a stable operation code, session/trace IDs when known, and exception class.
   It does not log the exception message, stack trace, metadata, details,
   payload, summary, authentication, or application content. Stable internal
   operation codes are `PROJECTION_FAILED`, `REGISTRY_UPDATE_FAILED`,
   `REPLAY_PUBLICATION_FAILED`, and `TERMINAL_PUBLICATION_FAILED`.
7. **Tests and fixtures:** Add focused PR 02 unit and lifecycle tests in the
   starter. Do not add `bifrost-console-fixtures` activity fixtures yet because
   PR 02 creates no Java-to-Go DTO or transport contract; PRs 04-06 own the
   executable external snapshot/SSE agreement.

## What We're NOT Doing

- No REST endpoints, SSE emitters/subscribers, HTTP DTOs, application
  observability configuration, access key, Spring Security integration, or Go
  consumer.
- No skill or finalized-trace catalog and no finalized-artifact descriptor,
  completion grace, trace availability, or catalog TTL; those belong to PR 03.
- No public observer SPI, application callback, replaceable Spring bean,
  `@ConditionalOnMissingBean`, general event bus, durable queue, outbox, retry,
  reconstruction, reconciliation, or automatic availability recovery.
- No active-trace reading, trace-file tailing, filesystem work, network work,
  subscriber fan-out, or blocking capacity wait in projection.
- No active-registry cardinality cap, execution admission control, sampling, or
  partial registry.
- No new trace record type for `EXECUTION_OBSERVATION_ENDED`; it is an
  observability-only activity kind.
- No evidence activity, plan-quality warning, linter, structured-output,
  proposed/accepted step action, model preparation, advisor mutation, captured
  model thought, or payload-chunk live event in the initial subset.
- No cross-execution parent/child registry. Nested YAML skills remain frames in
  the same session and trace.
- No skill-authoring guidance, sample configuration, or browser/manual UI work.

## Skill-Authoring Documentation Impact

**Impact**: No impact

- **Rationale**: PR 02 changes only internal, currently unexposed live
  observability state. It does not change manifest syntax, validation, defaults,
  execution/planning semantics, evidence contracts, input/output behavior,
  capability visibility, RBAC, attachments, model selection, quotas, canonical
  traces, or the supported completed `SkillTemplate` observer. A skill author
  cannot consume the new state until later adapter and console PRs.
- **Documents to update**: None.
- **Supporting evidence**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionObservationHandleTest.java`,
  existing canonical trace tests, and architecture tests will prove that live
  projection is optional and leaves canonical/author-facing behavior
  unchanged.
- **Coverage table update**: Not required. No authoring topic is added and the
  confidence or task boundary of `ai/skill-authoring/traces-and-debugging.md`
  does not change.
- **LLM-first usability**: Not applicable.

## Contract and Compatibility Impact

| Surface | Classification and supporting evidence | Planned compatibility treatment |
| --- | --- | --- |
| Application API | No affected behavior. `SkillTemplate`, `SkillExecutionView`, and `SkillExecutionEvent` remain the documented completed-execution API (`README.md:142-146`). | Preserve all signatures and callback timing. Do not expose live observation through the API. |
| Supported SPI | None exists; architecture tests assert no `.spi` package and an empty bean-override allowlist (`BifrostPublicSurfaceArchitectureTest.java:245-250`, `BifrostAutoConfigurationBoundaryTest.java:26-52`). | Preserve no supported SPI and add no replacement seam. |
| Configuration and manifest contracts | No new `bifrost.*` property and no YAML change. PR 04 owns opt-in observability configuration. | Preserve current properties, defaults, metadata, manifests, and validation. |
| Persisted or serialized contracts | No durable or cross-version contract. Canonical NDJSON bytes and records remain unchanged. | Preserve the current writer/reader representation exactly in this PR. |
| Ephemeral diagnostic formats | Adds bounded in-memory active snapshots and activity envelopes derived from current-release trace records. These are internal until PRs 04-05 define protocol DTOs. | Keep canonical record ordering, failure visibility, bounded summaries, and current-version coherence; do not add schema/version compatibility machinery. |
| Internal or accidentally exposed implementation | `BifrostSession`, `DefaultExecutionTraceHandle`, `ExecutionTraceHandle`, `BifrostSessionRunner`, and new observation types change internally. Their public modifiers are package-collaboration exposure, not supported contracts (`BifrostPublicSurfaceArchitectureTest.java:59-108`, `:152-163`). | Update all in-repository callers/tests atomically. Permit only the minimum new internal types in the architecture allowlist. |

- **Evidence of supported contracts**: `README.md:142-146` and the explicit
  architecture allowlists protect only the seven `com.lokiscale.bifrost.api`
  types. The ticket explicitly excludes a public observer SPI.
- **Intended breaks**: Internal trace-handle construction and
  `BifrostSessionRunner` wiring change atomically. No supported Application API,
  SPI, property, manifest, or serialized-format break is intended.
- **In-repository consumers to update**: `BifrostAutoConfiguration`,
  direct `BifrostSessionRunner`/`BifrostSession`/`DefaultExecutionTraceHandle`
  test construction, architecture allowlists, and focused lifecycle tests.
  Existing no-op convenience construction remains deliberate disabled behavior.
- **Public-surface delta**: No `com.lokiscale.bifrost.api` addition. Add only
  technically public internal observation interfaces/records required across
  internal packages; add no public Spring bean factory method, supported
  constructor, SPI package, or `@ConditionalOnMissingBean`.
- **Shim decision**: **No shim.** Modify internal constructor/wiring paths
  coherently and update in-repository consumers. Existing constructors that
  select the no-op handle are the permanent disabled-observation behavior, not
  a deprecated bridge.
- **Java-to-Go boundary coordination**: **Not required.** PR 02 establishes
  internal semantics only. PRs 04-06 will map bounded internal state to
  REST/SSE DTOs and add coordinated fixtures/tests before Go consumes it.

## Implementation Approach

Use the canonical trace handle as the logical-record publication seam and the
session as the completion authority:

```text
BifrostSession construction
  -> create no-op/enabled ExecutionObservationHandle
  -> construct DefaultExecutionTraceHandle with that handle attached
  -> append initial canonical records
       -> complete storage append
       -> publish bounded logical record to observation handle

ordinary canonical append under the session lock
  -> construct one logical TraceRecord
  -> write one record or envelope + all chunks
  -> after complete write, publish the logical TraceRecord
       -> deterministic bounded projection
       -> replace one active snapshot
       -> append zero/one bounded replay envelope

BifrostSession.finalizeTrace
  -> project completed journal
  -> append canonical TRACE_COMPLETED (observation holds terminal activity)
  -> apply current retention behavior
  -> close observation once with core success/failure
       -> release normal terminal OR emit EXECUTION_OBSERVATION_ENDED
       -> remove active entry in finally
```

The enabled handle owns per-execution mutable projection state and is called
serially by its session. Shared registry, replay buffer, and availability state
are independently concurrency-safe across sessions. All observation entry
points are non-throwing at the core boundary: they catch unexpected optional
failures, transition availability once, and return control to the existing
execution path.

The logical `TraceRecord` used for projection has the canonical envelope's
sequence, timestamp, metadata, frame facts, and complete logical `data`. Chunk
records remain physical storage facts only and never reach the live projector.
After the projector extracts allowlisted bounded scalars, no reference to the
logical record or its data may remain in the handle, registry, or buffer.

## Phase 1: Define the Bounded Observation Domain and Stores

### Overview

Create deterministic internal value types, projection rules, registry,
replay-buffer, and fail-closed availability behavior independently of session
wiring.

### Changes Required

#### 1. Observation lifecycle and bounded domain types

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionObservationHandle.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionObservationHandleFactory.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ObservationCompletionDisposition.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/NoOpExecutionObservationHandle.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionActivity.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionActivityKind.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ActiveExecutionSnapshot.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionObservationLimits.java`

**Changes**:

- Define a minimal handle with non-throwing `recordAppended(TraceRecord)` and
  idempotent `close(ObservationCompletionDisposition)` operations.
- Give the no-op handle/factory stable singleton implementations; do not use
  nullable observation fields or branch throughout the execution engine.
- Model delivery cursor separately from canonical per-trace sequence and model
  registry ordinal separately from both.
- Keep execution outcome nullable on the exceptional observation-ended event;
  never invent success/failure from cleanup failure.
- Define the exact bounds in the resolved-decision table as named constants.
  Truncation is deterministic, Unicode-safe, and explicit through total counts
  and boolean truncation flags.
- Use immutable copies for every published DTO. Bounded details are an
  allowlisted map of scalar values; reject or truncate a projector result that
  would exceed field or retained-byte limits rather than storing a `JsonNode`.

#### 2. Deterministic live projector and shared interpretation helpers

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/LiveActivityProjector.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionProjectionState.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionJournalProjector.java`
- Optional focused helper extracted beside `ExecutionJournalProjector` for
  tool identity and safe error classification

**Changes**:

- Maintain a per-handle frame map/stack from every canonical
  `FRAME_OPENED`/`FRAME_CLOSED`, including frame kinds that do not produce a
  visible envelope, so the active path stays current.
- Project only the settled visible record subset. Unlisted canonical records
  may update snapshot facts when necessary but produce no activity.
- Derive counts from authoritative canonical facts:
  `ROOT_MISSION` opens for skill invocations, tool starts, physical model
  responses, linter/validation retry records, and normalized usage on model
  responses. Replace them with the terminal `SessionUsageSnapshot` carried by
  `TRACE_COMPLETED` when present.
- Extract only the per-kind facts downstream PRs require:
  attempt/retry identity and normalized usage for model activity; capability
  and linked-task identity for tools; step/validation status; failure identity,
  classification, and bounded safe message for errors; and outcome,
  terminal-failure identity, and terminal usage for completion.
- Do not copy model prompts/responses, tool arguments/results, plans, arbitrary
  payload objects, or exception objects into live state. Do not add new secret
  scanning or reinterpret application content.
- Extract shared deterministic tool/error interpretation from
  `ExecutionJournalProjector` only where doing so preserves the completed
  journal byte-for-byte/field-for-field. Keep the live projector separate.
- Return a bounded projection result consisting of one replacement snapshot and
  zero or one envelope. Return a held pending terminal value for
  `TRACE_COMPLETED` rather than an immediately publishable envelope.

#### 3. Active registry, replay buffer, and availability latch

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ActiveExecutionRegistry.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActiveExecutionRegistry.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ActivityReplayBuffer.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActivityReplayBuffer.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/LiveMonitoringAvailability.java`

**Changes**:

- Assign a positive instance-local `registryOrdinal` once, when
  `TRACE_STARTED` first establishes the active entry. Updating a snapshot keeps
  that ordinal; removal leaves no tombstone.
- Store exactly one immutable snapshot per active `sessionId`; use `traceId` as
  a correlated fact, not the registry key.
- Add current lookup, active count, highest ordinal, and newest-first
  high-water/keyset traversal primitives needed by PR 04, without introducing
  public pagination cursors or server-side page sessions.
- Assign one positive instance-local replay cursor while appending an envelope.
  Provide current cursor and replay-after-cursor operations that can
  distinguish available, too-old, future/invalid, and empty ranges internally.
- Enforce both replay bounds by evicting complete oldest envelopes. Define
  cursor `0` as "before the first published activity"; never reuse a cursor.
- Implement availability as a one-way atomic transition. Preserve the first
  internal failure operation/classification only for status/testing; provide no
  retry, reset, or reconstruction method.

### Success Criteria

#### Automated Verification

- [x] `LiveActivityProjectorTest` covers every visible and omitted record kind,
      frame/path maintenance, count/usage updates, held completion, nested
      frames, truncation, scalar allowlisting, and no retained logical payload.
- [x] `ActiveExecutionRegistryTest` covers one entry per session, ordinal
      stability, lookup/removal, newest-first high-water traversal, concurrent
      session updates, and no cardinality cap.
- [x] `ActivityReplayBufferTest` covers cursor monotonicity, cursor `0`, count
      eviction, byte eviction, complete-envelope eviction, stale/future ranges,
      concurrent publishers, and overflow failure.
- [x] `LiveMonitoringAvailabilityTest` proves the first transition wins and
      availability cannot recover in-process.
- [x] Focused tests pass:
      `.\mvnw.cmd -pl bifrost-spring-boot-starter -am -Dtest=LiveActivityProjectorTest,ActiveExecutionRegistryTest,ActivityReplayBufferTest,LiveMonitoringAvailabilityTest -Dsurefire.failIfNoSpecifiedTests=false test`

#### Manual Verification

- [x] Review one representative projected success, nested skill, tool failure,
      timeout/quota error, and held terminal activity to confirm summaries are
      concise and make no causal or hidden-reasoning claims.
- [x] Inspect retained DTOs in a debugger or heap view to confirm they contain
      no canonical `JsonNode`, prompt/response, tool payload, plan body, or
      exception reference.

---

## Phase 2: Wire Post-Append Publication and Exact-Once Completion

### Overview

Attach the observation handle early enough for constructor-time records, publish
logical records after complete storage success, and close the handle at the
canonical finalization boundary while preserving all existing failures.

### Changes Required

#### 1. Publish the logical record from the canonical trace handle

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionTraceHandle.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionTraceHandleTest.java`

**Changes**:

- Require a non-null observation handle when constructing
  `DefaultExecutionTraceHandle`; ordinary constructors use the no-op singleton.
- Construct one logical record before storage transformation. For a large
  payload, derive the payload-less envelope and chunk records for persistence
  without replacing the logical record used for projection.
- Invoke `recordAppended(logicalRecord)` only after `writer.append(...)` has
  succeeded for the ordinary record or for the envelope and every chunk.
- Preserve canonical sequence allocation: the logical record uses the envelope
  sequence, while physical chunks consume their existing later sequences.
- Do not publish `PAYLOAD_CHUNK_APPENDED` records to the observation handle.
- Keep optional observation failure from escaping the trace handle. Canonical
  writer, initialization, and finalization exceptions retain their current
  behavior.
- Prove the existing reader and NDJSON bytes are unchanged for ordinary and
  chunked records.

#### 2. Create and close one handle per session

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSessionRunner.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/state/DefaultExecutionStateService.java`

**Changes**:

- Add a final observation-handle field to `BifrostSession`.
- Have the full session constructor create the handle from its factory before
  constructing `DefaultExecutionTraceHandle`, then pass it into the trace
  handle so `TRACE_STARTED` and `TRACE_CAPTURE_POLICY_RECORDED` are observed.
- On trace/session construction failure, close any already-created enabled
  handle with `CORE_FINALIZATION_FAILED` so a `TRACE_STARTED` publication cannot
  leak an active entry.
- Preserve current session constructors as explicit no-op construction. Add
  one internal runner/factory path that supplies an enabled factory and the
  immutable session quota/limit snapshot used by live state.
- In `BifrostSession.finalizeTrace(...)`, determine the disposition from the
  actual method result:
  successful only when completed-journal projection and trace finalization
  both succeeded; failed for projection failure, append failure, retention
  failure, or their combination.
- Close the observation handle outside canonical trace-handle mutation but
  before returning/throwing from `finalizeTrace(...)`. Preserve the current
  primary and suppressed exception graph exactly; observation close never
  joins that graph.
- Keep handle close idempotent so the coordinator and runner's existing
  finalization safety behavior cannot emit two terminal activities or remove
  twice.

#### 3. Implement enabled handle publication and terminal rules

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandleFactory.java`

**Changes**:

- Serialize each handle's projection state through the existing session/trace
  call ordering; use an atomic close guard for defensive exact-once behavior.
- On each successful projection, replace the registry snapshot first and append
  the visible envelope second. If either operation fails, transition
  availability to unavailable and ignore later live publication while still
  accepting close for cleanup.
- Hold the bounded `TRACE_COMPLETED` activity locally and publish no early
  normal terminal event.
- On `CORE_FINALIZATION_SUCCEEDED`, release the held canonical completion once,
  then remove the active entry in `finally`. Treat a missing held completion as
  an optional publication failure and fail closed.
- On `CORE_FINALIZATION_FAILED`, discard any held completion, attempt exactly
  one `EXECUTION_OBSERVATION_ENDED` envelope with nullable canonical sequence,
  reason `CORE_FINALIZATION_FAILED`, no exception content, and only an
  independently established outcome; then remove the active entry in `finally`.
- If terminal publication fails, still run removal and fail live monitoring
  closed. Do not retry and do not retain a tombstone.
- Log only the sanitized stable diagnostic described in the resolved decisions.

#### 4. Preserve disabled auto-configuration

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostAutoConfigurationBoundaryTest.java`

**Changes**:

- Make the existing `BifrostSessionRunner` bean explicitly use the no-op
  observation factory in PR 02. PR 04 will choose enabled construction only
  when its strict opt-in module configuration is present.
- Do not expose the observation factory, registry, buffer, or availability
  state as application-replaceable beans and do not add
  `@ConditionalOnMissingBean`.
- Keep the framework-owned factory path injectable in focused internal tests
  and ready for PR 03/04 composition.

### Success Criteria

#### Automated Verification

- [x] `ExecutionTraceHandleTest` proves both constructor records are observed,
      ordinary logical records publish after storage, chunked logical data is
      complete, partial chunk failure publishes nothing, and NDJSON bytes and
      sequence ordering remain unchanged.
- [x] `ExecutionObservationHandleTest` proves normal terminal hold/release,
      core-finalization failure substitution, no double terminal activity,
      removal in every close path, sanitized logging fields, and exact-once
      close under concurrent/repeated calls.
- [x] `BifrostSessionRunnerTest` covers success, action failure, standalone
      open-frame cleanup, timeout/interruption, canonical append failure,
      journal-projection failure, finalization/deletion failure, construction
      failure, and preservation of primary/suppressed exceptions while active
      state is removed.
- [x] `ExecutionCoordinatorTest` retains normal, failed, aborted, timeout,
      quota, root-frame-close failure, and finalization failure semantics and
      produces one applicable terminal observation.
- [x] `BifrostAutoConfigurationBoundaryTest` proves no supported override seam
      or conditional bean was introduced and default auto-configuration uses
      the no-op handle.
- [x] Focused lifecycle tests pass:
      `.\mvnw.cmd -pl bifrost-spring-boot-starter -am -Dtest=ExecutionTraceHandleTest,ExecutionObservationHandleTest,BifrostSessionRunnerTest,ExecutionCoordinatorTest,BifrostAutoConfigurationBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false test`

#### Manual Verification

- [x] Run a small internal test harness with enabled observation and verify an
      active entry appears at `TRACE_STARTED`, gains its entry skill on the root
      frame, and disappears after success and after injected finalization
      failure.
- [x] Confirm a large chunked model/tool payload produces one bounded activity
      with no chunk events and no payload retained in the registry or buffer.

---

## Phase 3: Concurrency, Failure Isolation, and Architecture Closure

### Overview

Complete cross-session stress/failure coverage, enforce the internal-only
surface, and document the exact downstream handoff without creating an external
fixture prematurely.

### Changes Required

#### 1. Concurrency and failure-injection coverage

**Files**:

- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionObservationConcurrencyTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/core/BifrostSessionRunnerTest.java`
- Focused test doubles under
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/`

**Changes**:

- Run many virtual-thread sessions through one enabled factory and assert
  unique session/trace identity, one registry entry per live session, isolated
  frame paths/counts, per-trace canonical ordering, globally unique monotonic
  delivery cursors, and complete eventual cleanup.
- Block selected executions at deterministic test latches to prove registry
  cardinality tracks all live executions without admission, sampling, or
  omission.
- Inject projector, registry-update, replay-append, held-terminal, and
  exceptional-terminal failures independently. Assert each leaves the original
  action result/exception and canonical trace behavior unchanged, transitions
  availability once, and prevents later reads from being treated as complete.
- Exercise count/byte eviction during concurrent publication and verify slow
  consumers or callbacks do not exist in this PR's execution path.

#### 2. Internal surface architecture enforcement

**Files**:

- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostAutoConfigurationBoundaryTest.java`

**Changes**:

- Add only unavoidable technically public observation types to the explicit
  internal allowlist.
- Assert no new type appears in `com.lokiscale.bifrost.api` or an `.spi`
  package; no observation implementation becomes an application bean override;
  and no public auto-configuration method/constructor expands the supported
  Spring surface.
- Assert observation domain objects do not expose `Path`, `Resource`,
  `JsonNode`, raw `TraceRecord`, exceptions, callbacks, publishers, streams, or
  mutable collections.

#### 3. Downstream handoff notes

**Files**:

- This implementation plan's completion checkboxes only; no production-facing
  documentation or `bifrost-console-fixtures` change in PR 02.

**Changes**:

- Record in the PR description that PR 03 must publish any retained artifact to
  its catalog before enriching/releasing the held success activity and must
  keep the PR 02 failure disposition unchanged.
- Record that PR 04 maps registry values to protocol DTOs and opaque cursors;
  internal records and numeric positions are not the REST contract.
- Record that PR 05 owns subscriber wake-up/delivery, per-subscriber bounds,
  network deadlines, replay-gap HTTP/SSE behavior, and stream closure on the
  availability transition. PR 02 must not add a callback to anticipate it.
- Record that PR 06 owns Java-produced external live/snapshot fixtures and
  phase-wide integration evidence.

### Success Criteria

#### Automated Verification

- [x] Concurrent-session and failure-injection tests pass repeatedly without
      leaked active entries, duplicate terminal activities, cross-session
      paths, cursor reuse, or changed execution exceptions.
- [x] Architecture tests pass and the supported API/SPI/bean surface remains
      closed.
- [x] Existing canonical trace/journal regression set passes:
      `.\mvnw.cmd -pl bifrost-spring-boot-starter -am -Dtest=ExecutionTraceHandleTest,NdjsonExecutionTraceReaderTest,ExecutionJournalProjectorTest,BifrostSessionRunnerTest,ExecutionCoordinatorTest,MissionExecutionEngineTest,BifrostPublicSurfaceArchitectureTest,BifrostAutoConfigurationBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false test`
- [x] Full repository verification passes:
      `.\mvnw.cmd verify`
- [x] `git diff --check` reports no whitespace errors.

#### Manual Verification

- [x] Review a fail-closed diagnostic and confirm it contains only the stable
      operation, opaque session/trace IDs, and exception class—not exception
      messages, application data, or credentials.
- [x] Review a heap/memory profile from a bounded stress test and confirm replay
      memory remains within its count/byte bounds while registry size is
      proportional only to deliberately blocked live sessions.
- [x] Confirm there is no application-visible route, property, YAML change,
      callback, or supported extension point in the PR.

## Testing Strategy

### Unit Tests

- Test the projector as a pure deterministic state transition for every
  canonical record kind, including omitted kinds and malformed/oversized
  allowlisted fields.
- Test registry and replay-buffer bounds independently from session execution.
- Test exact-once handle close, terminal hold/substitution, and failure
  containment with injected component failures.
- Test constructor-time publication and chunk-completion ordering at the trace
  handle boundary.

### Integration Tests

- Extend session/coordinator tests for normal, failure, timeout, interruption,
  quota, open-frame cleanup, journal projection failure, trace append failure,
  retention deletion failure, and nested skill execution.
- Run concurrent sessions through one shared observation core and verify global
  registry/buffer correctness plus per-session isolation.
- Keep existing writer/reader/projector and public-surface tests in the
  regression set.
- Do not create a cross-language live fixture until PR 04/05 establishes the
  external DTO/cursor contract; PR 06 will make that agreement executable.

**Note**: Run `ai/commands/3_testing_plan.md` before implementation to create the
dedicated PR 02 testing-plan artifact with failing-test order, fault-injection
mechanics, repeated concurrency runs, and exit criteria.

### Manual Testing Steps

1. Run a test-only enabled factory and pause two nested, concurrent sessions;
   inspect their distinct active snapshots and ordered activity.
2. Release one normally and force core finalization failure in the other;
   verify one normal held completion versus one
   `EXECUTION_OBSERVATION_ENDED`, followed by removal of both active entries.
3. Inject an optional replay publication failure; verify the skill result is
   unchanged, availability becomes false once, later publication is ignored,
   and cleanup still removes the active entry.
4. Repeat with a chunked logical payload and inspect retained objects to verify
   the payload and physical chunks are absent.

## Performance Considerations

- The canonical append path gains one synchronous, deterministic, bounded
  in-memory projection while already holding the per-session lock. It must not
  perform filesystem reads, network work, subscriber callbacks, waits, or
  generic traversal/serialization of arbitrary payloads.
- Each live execution retains one bounded snapshot plus small per-handle frame
  bookkeeping. The active path is capped at 64 displayed entries; registry
  cardinality intentionally remains proportional to actual live execution
  count.
- The replay buffer is bounded by both 10,000 complete events and 16 MiB
  retained weight. Eviction is constant/amortized work from an `ArrayDeque`-like
  structure and never blocks the publisher for consumer capacity.
- Project only allowlisted scalar fields from known record kinds. Truncate while
  reading the selected value rather than deep-copying an entire payload into
  another tree.
- Use virtual-thread concurrency tests and a bounded allocation/heap assertion
  to catch accidental retained payloads and cross-session contention. This PR
  does not establish a user-facing throughput SLA.

## Migration Notes

There is no persisted-data, configuration, manifest, Application API, or
supported SPI migration.

Internal constructors and tests are updated atomically. Current production
auto-configuration explicitly selects the no-op factory, so applications that
do not yet have the PR 04 opt-in adapter retain current behavior. No shim,
legacy publisher, dual record format, trace migration, replay reconstruction,
or historical reader is required.

PR 03 may atomically extend the internal successful completion disposition with
its finalized-artifact descriptor because no external consumer exists in PR 02.
It must not change the exceptional `CORE_FINALIZATION_FAILED` meaning or release
two terminal activities.

## References

- Original ticket:
  `ai/thoughts/tickets/bifrost-console-pr-02-observation-lifecycle.md`
- Related research:
  `ai/thoughts/research/2026-07-24-bifrost-console-pr-02-observation-lifecycle.md`
- Settled Phase 1 design:
  `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md`
- Implementation roadmap:
  `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md`
- PR 03 catalog handoff:
  `ai/thoughts/tickets/bifrost-console-pr-03-observability-catalogs.md`
- PR 04 REST handoff:
  `ai/thoughts/tickets/bifrost-console-pr-04-spring-rest-adapter.md`
- PR 05 SSE handoff:
  `ai/thoughts/tickets/bifrost-console-pr-05-live-sse-delivery.md`
- PR 06 integration/fixture handoff:
  `ai/thoughts/tickets/bifrost-console-pr-06-artifact-streaming-integration.md`
- Later UI and MCP consumers:
  `ai/thoughts/tickets/bifrost-console-pr-11-live-execution-experience.md`,
  `ai/thoughts/tickets/bifrost-console-pr-17-mcp-runtime-inspection.md`
- Compatibility policy:
  `ai/thoughts/framework-feature-design-lens.md`
- Skill-authoring routing and trace guidance:
  `ai/skill-authoring/README.md`,
  `ai/skill-authoring/source-verification.md`,
  `ai/skill-authoring/traces-and-debugging.md`
