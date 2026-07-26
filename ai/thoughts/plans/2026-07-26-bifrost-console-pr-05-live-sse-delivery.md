# Bifrost Console PR 05 Live SSE Delivery Implementation Plan

## Overview

Add the authenticated `GET /_bifrost/observability/v1/activity` server-sent-event
boundary that turns the existing bounded replay projection into one multiplexed,
resumable, process-local activity stream. Delivery remains outside execution
locks, admits only a fixed number of authenticated streams, bounds every
subscriber's pending bytes and events, closes slow or failed streams, and
preserves the baseline-plus-stream recovery contract required by PR 11.

This plan deliberately uses Servlet non-blocking async output rather than
`SseEmitter.send(...)`. In Spring MVC 6.2.16, `SseEmitter.send(...)` performs
serialization and flush on the caller thread while holding the emitter write
lock. A second thread calling `complete()` must acquire that same lock, so it
cannot independently enforce a deadline against a blocked send. A small
framework-owned `AsyncContext`/`WriteListener` adapter can instead write only
while `ServletOutputStream.isReady()` is true and close a subscriber when its
head frame exceeds the fixed write-readiness deadline.

## Current State Analysis

PRs 02–04 already provide the complete producer-side foundation:

- Canonical append finishes before observation publication, and the observation
  failure boundary prevents optional monitoring from changing execution
  outcomes
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:221`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:401`).
- The observation handle synchronously projects bounded active state and
  appends zero or one activity to the replay buffer, but does not notify
  independently running delivery work
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java:92`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java:127`).
- The replay buffer assigns a process-local monotonic cursor, retains at most
  10,000 events and 16 MiB, and distinguishes available, empty, too-old, and
  future cursors
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActivityReplayBuffer.java:41`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActivityReplayBuffer.java:75`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionObservationLimits.java:13`).
- The first active-execution page already returns the decimal
  `resumeCursor`, and authenticated responses already carry
  `X-Bifrost-Instance-Id`
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestController.java:98`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiKeyFilter.java:68`).
- The reserved namespace, route collision detection, API-key filter,
  centralized operator access service, stable `STALE_CURSOR`,
  `LIVE_MONITORING_UNAVAILABLE`, and `LIMIT_EXCEEDED` problems all exist.
  The filter is already async-supported and mapped for all dispatcher types
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostObservabilityWebAutoConfiguration.java:120`).
- `LiveMonitoringAvailability` is one-way and retains only the first sanitized
  failure, but has no delivery notification seam. `ObservabilityRuntime.close()`
  has no live-delivery resource to close
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/LiveMonitoringAvailability.java:7`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/ObservabilityRuntime.java:31`).
- The internal activity record does not yet carry the canonical
  `parentFrameId` or an explicit optional execution status required by the
  settled wire contract
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionActivity.java:12`).

There is currently no activity route, activity wire DTO, subscriber registry,
admission accounting, pending-delivery bound, delivery executor, write
deadline, async cancellation handling, or open-stream shutdown behavior.

## Desired End State

An authenticated console client can:

1. request `GET /_bifrost/observability/v1/activity` with exactly one
   `instanceId` and one non-negative decimal `afterCursor`;
2. receive an initial `handshake` event followed by ordered `activity` events;
3. use each activity's decimal delivery cursor as the SSE `id`;
4. tolerate duplicates and reconnect after the last processed cursor;
5. receive an ordinary JSON problem before async streaming begins when the
   request is invalid, stale, unavailable, or over process capacity; and
6. observe connection closure, without a fabricated control event, after
   commit-time overflow, write deadline, cancellation, live-projection failure,
   or runtime shutdown.

The stream protocol is:

```text
GET /_bifrost/observability/v1/activity
    ?instanceId=<startup UUID>
    &afterCursor=<non-negative decimal long>
Accept: text/event-stream
X-Bifrost-Api-Key: <configured key>

event: handshake
data: {"instanceId":"...","observedAt":"...","afterCursor":"..."}

id: <delivery cursor>
event: activity
data: {
  "instanceId":"...",
  "cursor":"...",
  "sessionId":"...",
  "traceId":"...",
  "canonicalSequence":1,
  "timestamp":"...",
  "kind":"TRACE_STARTED",
  "executionStatus":"ACTIVE",
  "frameId":null,
  "parentFrameId":null,
  "frameType":null,
  "route":null,
  "summary":"Execution started",
  "details":{}
}
```

Nullable fields follow the existing DTO serialization policy and are omitted
rather than serialized as `null`. `consoleCompatibilityVersion` is established
by the instance-status probe and is not repeated in the stream.

The initial fixed limits are:

| Limit | Value | Treatment |
| --- | ---: | --- |
| Open authenticated subscriptions | 16 per process | The 17th valid request receives `429 LIMIT_EXCEEDED`; requests are never queued. |
| Pending activity frames | 256 per subscriber | An enqueue that would exceed either pending limit closes only that subscriber. |
| Pending serialized bytes | 1 MiB per subscriber | Count the complete UTF-8 SSE frame bytes; shared immutable serialized frames are still charged to each subscriber. |
| Replay fetch batch | 256 activities | The dispatcher continues bounded batches until caught up or the subscriber closes. |
| Head-frame write-readiness deadline | 5 seconds | Start when a frame becomes queue head; cancel when its complete bytes are accepted by non-blocking servlet output. Expiry closes only that subscriber. |
| Servlet async idle timeout | Disabled (`0`) | Idle streams are intentionally long-lived; this PR adds no heartbeat. Transport/proxy idle closure is handled by reconnect. |

These constants are internal release choices, not new `bifrost.*`
configuration.

### Key Discoveries

- The replay buffer is the reconnect source, not a subscriber queue. Delivery
  must retain only bounded per-subscriber serialized frames and cursor state.
- `SseEmitter` early sends are buffered before handler initialization, and
  direct sends flush on the caller thread. The implementation must not use
  early-send buffering or assume the servlet async timeout is a per-write
  deadline.
- `startAsync()` is the ownership boundary and the first handshake write is the
  body-commit point. All request, identity, availability, cursor, and capacity
  decisions occur before `startAsync()`; every later failure is close-only,
  even if the container has not yet physically committed the handshake bytes.
- PR 11 will own one upstream stream, clear its recent window on
  `STALE_CURSOR`, changed instance, or target-scope rotation, and share that
  bounded interval with browser and later MCP consumers
  (`ai/thoughts/tickets/bifrost-console-pr-11-live-execution-experience.md:14`).
- PR 06 owns the reviewed cross-boundary SSE fixture corpus and full Phase 1
  transport integration. PR 05 still locks exact framing and serialization in
  focused Java tests, without prematurely changing
  `bifrost-console-fixtures/`
  (`bifrost-console-fixtures/README.md:22`).

## Resolved Planning Decisions

| Research question | Decision |
| --- | --- |
| Route and query | `GET /_bifrost/observability/v1/activity` with required `instanceId` and `afterCursor`; reject every other query parameter and do not support `Last-Event-ID`. |
| Event names | `handshake` for the first event and `activity` for every diagnostic activity. |
| Handshake | `{instanceId, observedAt, afterCursor}`; no SSE `id`, because it is not an activity. |
| Activity envelope | A flat external DTO containing `instanceId` plus the settled activity fields; never serialize `ExecutionActivity` directly. Decimal delivery cursors are strings in JSON and SSE `id`. |
| Cursor mapping | Malformed, negative, overflowing, or future cursor is `400 INVALID_CURSOR`; a too-old cursor or mismatched well-formed instance UUID is `410 STALE_CURSOR`. |
| Replay batching | 256 activities per fetch. |
| Admission | 16 authenticated open streams per process. Admission follows validation and precedes `startAsync()`. |
| Pending delivery | 256 complete activity frames or 1 MiB of serialized frame bytes per subscriber, whichever is reached first. |
| Write deadline | 5 seconds for the current head frame to be accepted through ready non-blocking servlet output. No claim is made about bytes already accepted into the servlet/container/network stack. |
| Delivery ownership | One runtime-owned single-thread dispatcher serializes activities once and fans out immutable frames; one separate single-thread scheduler owns deadlines. Servlet callbacks perform only subscriber-local non-blocking drain/cleanup work. |
| Publication notification | Inject a non-throwing, constant-time internal `LiveActivitySignal` into observation handles. Successful replay append signals new activity; the first fail-closed transition signals unavailability. Both only schedule/coalesce dispatcher work and invoke no subscriber callback under execution locks. |
| Accounting | One idempotent subscriber close boundary removes the registry entry, cancels its deadline, clears its queue, decrements admission once, and completes the async context when appropriate. Async completion, timeout, error, client cancellation, write failure, overflow, live failure, startup failure, and runtime shutdown all converge there. |
| Shutdown | Stop admission, mark delivery closed, detach and close all streams, stop deadline scheduling, stop the dispatcher, then allow `ObservabilityRuntime` to close completion retention and the trace catalog. Do not drain queued diagnostic events. |
| Fixtures | Add exact Java framing/DTO assertions in PR 05. Leave the deterministic cross-language SSE fixture corpus and end-to-end Phase 1 fixture inventory to PR 06 as already assigned. |

## What We're NOT Doing

- Browser relay, Go SSE consumption, recent-activity reduction, or MCP access.
- `Last-Event-ID`, heartbeat events, retry directives, automatic browser
  reconnect policy, or low-frequency refresh policy.
- Durable delivery, exactly-once semantics, transactional baseline/cursor
  snapshots, gap reconstruction, or projection recovery.
- A configurable subscription limit, queue size, replay batch, write deadline,
  executor, scheduler, or async timeout.
- Per-key, per-source, or fair-share admission; rate limiting; bandwidth
  governance; or defense against a malicious authenticated operator.
- Raw trace streaming, artifact acquisition, or the PR 06 cross-language
  fixture corpus.
- A public Java subscriber API, Spring replacement bean, supported delivery
  SPI, WebFlux dependency, or host `WebMvcConfigurer` mutation.
- An SSE control event for stale cursors, projection failure, overflow, timeout,
  or shutdown. Before commit these are JSON problems where defined; after
  commit they are stream closure.

## Skill-Authoring Documentation Impact

**Impact**: No impact

- **Rationale**: This PR changes only the authenticated operator transport for
  already-produced bounded activity. It does not change YAML syntax,
  validation, defaults, skill inputs or outputs, planning/execution semantics,
  capability visibility/RBAC, model selection, execution limits, canonical
  trace facts, or the guidance a skill author uses to diagnose a trace.
- **Documents to update**: None.
- **Supporting evidence**:
  `ai/skill-authoring/README.md` routes author-facing diagnostic semantics to
  `ai/skill-authoring/traces-and-debugging.md`, while this ticket leaves those
  trace semantics unchanged. Focused SSE delivery and adapter integration
  tests will establish the new operator-only behavior.
- **Coverage table update**: Not required; no authoring topic is added and no
  existing topic's coverage or confidence changes.
- **LLM-first usability**: Not applicable.

## Contract and Compatibility Impact

| Surface | Classification and supporting evidence | Planned compatibility treatment |
| --- | --- | --- |
| Application API | No ordinary application API changes. The activity, delivery, controller, and runtime types remain beneath `internal`; the architecture allowlist classifies technically public observability types as framework composition (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:45`). | Preserve the supported application API. |
| Supported SPI | No supported SPI exists or is introduced. The observability beans remain infrastructure-role beans without replacement semantics. | Preserve the absence of an SSE/subscriber SPI. |
| Configuration and manifest contracts | No `bifrost.*` property or YAML manifest behavior changes. Admission, pending, replay-batch, deadline, and executor values are fixed internal constants as required by the ticket. | Preserve existing configuration; add no aliases or migration path. |
| Persisted or serialized contracts | Add the current-release SSE handshake and activity envelope consumed later by Go. It is a coordinated serialized protocol, not persisted durable history. | Establish one exact current-release shape and cover it with Java semantic/framing tests; PR 06 adds the reviewed fixture corpus. |
| Ephemeral diagnostic formats | Add `parentFrameId` and optional `executionStatus` to the bounded in-memory activity projection so the wire contract does not infer them from display details. Canonical NDJSON is unchanged. | Update projector, replay, activity, DTO mapper, and tests atomically; preserve current-run ordering, boundedness, truthfulness, and secret policy. |
| Internal or accidentally exposed implementation | Change observation-factory construction, runtime ownership, route registration, DTO mapping, and architecture allowlisting; add the delivery signal/coordinator and servlet stream adapter. Public modifiers exist only where cross-package Spring composition requires them. | Make one atomic internal change and update every in-repository constructor/caller/test. Do not retain compatibility overloads or legacy paths. |

- **Evidence of supported contracts**: The PR 05 ticket, Phase 1 live-delivery
  and minimal Java/Go agreement, and PR 11's future single-connection consumer
  establish the serialized SSE contract. The architecture test establishes
  that the Java implementation types are internal rather than supported API or
  SPI.
- **Intended breaks**: None to an existing protected contract. Internal
  `ExecutionActivity` and observation/runtime constructors change atomically.
- **In-repository consumers to update**: Observation handle/factory tests,
  replay/projector tests, activation/filter/route tests, REST integration
  setup, DTO tests, architecture allowlist, and any direct runtime
  construction. No Go consumer exists yet.
- **Public-surface delta**: Add technically public internal delivery/controller
  types required for cross-package servlet and auto-configuration composition;
  add `parentFrameId` and optional `executionStatus` to the technically public
  internal `ExecutionActivity` record; change internal factory/runtime
  signatures. Add no `com.lokiscale.bifrost.api` type, supported SPI, property,
  or `@ConditionalOnMissingBean` extension point.
- **Shim decision**: **No shim.** All affected Java surfaces are explicitly
  internal and all repository consumers can be updated atomically.
- **Java-to-Go boundary coordination**: **Required.** PR 05 establishes the Java
  producer semantics and exact Java tests. PR 06 adds deterministic SSE
  fixtures and Phase 1 integration coverage; PR 09 verifies exact release
  compatibility before use; PR 11 implements the Go consumer and reset rules.
  Any later change to path, query, event name, field meaning, problem mapping,
  or framing must update Java, Go, fixtures, semantic tests, and boundary
  documentation in the same Bifrost release. No independent protocol version
  is added.

## Implementation Approach

Keep three responsibilities separate:

```text
execution lock
  -> replay append
  -> constant-time coalesced LiveActivitySignal

runtime-owned dispatcher
  -> replayAfter(cursor, 256)
  -> map/serialize each activity once
  -> bounded enqueue to each subscriber

subscriber / servlet async lifecycle
  -> handshake first
  -> write only while ServletOutputStream.isReady()
  -> deadline, cancellation, overflow, failure, or shutdown
  -> idempotent close and admission release
```

The replay buffer remains authoritative for ordered reconnect delivery. The
dispatcher tracks each subscriber's highest enqueued cursor, reads later
activities in bounded batches, and closes a subscriber if replay becomes
`TOO_OLD` before it can be enqueued. It never blocks the publisher and never
holds a replay-buffer monitor while serializing, touching servlet state, or
iterating subscribers.

The stream controller performs all pre-stream checks synchronously. Only after
validation and admission succeed does it set `200`, `text/event-stream`,
UTF-8, and no-store headers, create the async context with idle timeout `0`,
register lifecycle listeners, and enqueue the handshake. From that point the
HTTP response is stream-owned: failures are sanitized diagnostic logs plus
idempotent closure, never MVC error redispatch intended to replace the body.

## Phase 1: Finalize the Activity and Cursor Contract

### Overview

Complete the bounded internal projection and external DTOs needed to serialize
the settled SSE protocol without exposing runtime records directly.

### Changes Required

#### 1. Complete bounded activity identity and status

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionActivity.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/LiveActivityProjector.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/LiveActivityProjectorTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActivityReplayBufferTest.java`

**Changes**:

- Add bounded nullable `parentFrameId` and `executionStatus` fields to
  `ExecutionActivity`; propagate them through cursor assignment and terminal
  trace-availability enrichment.
- Populate `parentFrameId` from the canonical record.
- Set `executionStatus` to `ACTIVE` for ordinary nonterminal activity, the
  canonical terminal outcome for `TRACE_COMPLETED`, and only a separately
  established outcome for `EXECUTION_OBSERVATION_ENDED`; never invent a status
  after failed finalization.
- Include the new structural strings in retained-weight accounting and retain
  the existing 12 KiB activity bound.
- Update every direct activity construction and assertion atomically.

#### 2. Add external SSE DTOs and mapping

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/dto/ObservabilityDtos.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityDtoMapper.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityDtoMapperTest.java`

**Changes**:

- Add `ActivityHandshake(instanceId, observedAt, afterCursor)` and the flat
  `ActivityEnvelope` described above.
- Serialize delivery cursors as canonical base-10 strings and timestamps as the
  existing Jackson ISO-8601 representation.
- Keep `details` as a copied bounded scalar map and omit nullable optional
  fields.
- Add exact mapping tests for ordinary, terminal, and
  `EXECUTION_OBSERVATION_ENDED` activity, including instance identity,
  parent-frame identity, status absence, and decimal cursor rendering.

#### 3. Add activity-request parsing and problem mapping

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestController.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestIntegrationTest.java`

**Changes**:

- Add a dedicated activity query parser rather than weakening the JSON
  collection helpers.
- Require an SSE-compatible `Accept` value and exactly one nonblank
  `instanceId` and `afterCursor`.
- Map malformed parameter shape to `INVALID_REQUEST`; malformed/negative/
  overflowing/future delivery cursors to `INVALID_CURSOR`; and a well-formed
  different UUID or `TOO_OLD` replay result to `STALE_CURSOR`.
- Check live availability before admission and preserve the existing
  `LIVE_MONITORING_UNAVAILABLE` problem.

### Success Criteria

#### Automated Verification

- [x] Activity bounds and projector behavior pass:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=LiveActivityProjectorTest,InMemoryActivityReplayBufferTest test`
- [x] DTO and request/problem mapping tests pass:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=ObservabilityDtoMapperTest,ObservabilityRestIntegrationTest test`
- [x] Architecture checks still reject runtime types in wire DTOs:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostPublicSurfaceArchitectureTest test`

#### Manual Verification

- [x] Review one handshake and one event frame to confirm exact field names,
  omitted-null policy, decimal cursor equality with SSE `id`, and absence of
  `consoleCompatibilityVersion`.
- [x] Confirm no arbitrary payload, exception, stack trace, thread name, API
  key, or authentication header is introduced into the envelope.

---

## Phase 2: Build Bounded Runtime Delivery

### Overview

Add the constant-time execution-to-delivery signal and the runtime-owned
dispatcher, subscriber registry, queue bounds, admission, deadlines, and
idempotent lifecycle accounting.

### Changes Required

#### 1. Add fixed delivery limits and publication signal

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/LiveActivitySignal.java` (new)
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandleFactory.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityDeliveryLimits.java` (new)

**Changes**:

- Define a non-throwing `LiveActivitySignal` with activity-available and
  live-unavailable signals plus a no-op implementation for disabled or isolated
  factory tests.
- After each successful ordinary or terminal replay append, release only the
  coalesced activity signal. On the first fail-closed availability transition,
  release only the unavailable signal.
- Keep signaling constant-time and independent of subscriber count. Catch and
  sanitize unexpected signal implementation failures so they cannot escape the
  existing observation isolation boundary.
- Centralize the fixed limits listed in Desired End State; do not add
  configuration properties.

#### 2. Add the delivery coordinator and subscriber state machine

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityActivityDelivery.java` (new)
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityActivityDeliveryTest.java` (new)

**Changes**:

- Own a maximum of 16 subscriber records, a single dispatcher executor, and a
  separate single-thread deadline scheduler with Bifrost-named daemon threads.
- Coalesce publication signals so an execution never waits for executor queue
  capacity. The dispatcher fetches replay in batches of 256 and performs all
  subscriber iteration, DTO mapping, JSON serialization, and fan-out outside
  execution and replay-buffer locks.
- Serialize each activity frame once, then enqueue the immutable bytes and
  cursor to each eligible subscriber while charging the full byte count to
  each subscriber.
- Preserve increasing cursor order per subscriber. Never silently skip an
  event; close on queue event/byte overflow or a replay gap.
- Keep duplicate tolerance at the protocol boundary: a reconnect after an
  already processed cursor can legally repeat activity if the caller had not
  durably advanced its local cursor.
- Model subscriber lifecycle explicitly as admitted/open/closing/closed with
  one atomic close operation. Release admission exactly once across every
  callback and failure path.
- On first live-unavailable signal, stop accepting activity, detach and close
  every current subscriber without sending a synthetic SSE event.

#### 3. Make delivery part of runtime ownership

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/ObservabilityRuntime.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRouteRegistrar.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/ObservabilityActivationCoordinatorTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRouteRegistrarTest.java`

**Changes**:

- Generate the instance UUID, replay buffer, availability, mapper/codec
  collaborators, and delivery coordinator before constructing the observation
  factory; inject the coordinator only through `LiveActivitySignal`.
- Add delivery to `ObservabilityRuntime` and close it before completion
  retention and trace catalog resources.
- On partial startup failure, close delivery, grace retention, and catalog in
  reverse ownership order and attach close failures as suppressed exceptions.
- Update all internal runtime/factory constructor consumers atomically; add no
  compatibility constructor.

### Success Criteria

#### Automated Verification

- [x] Delivery unit tests prove cursor order, batching, signal coalescing,
  serialized-frame reuse, event and byte overflow, replay-gap closure,
  independent subscribers, capacity rejection/release, deadline tokens, live
  failure, and shutdown:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=ObservabilityActivityDeliveryTest test`
- [x] Observation tests prove successful append signaling and first-failure
  signaling without callback/fan-out work under the observation monitor:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=DefaultExecutionObservationHandleTest,ExecutionObservationConcurrencyTest,LiveMonitoringAvailabilityTest test`
- [x] Activation and startup-failure tests show no delivery thread or admitted
  subscriber survives rollback or shutdown:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=ObservabilityActivationCoordinatorTest,ObservabilityRouteRegistrarTest test`

#### Manual Verification

- [ ] Thread dump during an active execution shows no servlet write or
  subscriber callback on the execution thread.
- [ ] Review sanitized logs for overflow, deadline, send error, live failure,
  and shutdown; logs contain operation/instance/cursor and exception class
  where useful, but no event details or exception message.

---

## Phase 3: Add the Authenticated Non-Blocking SSE Route

### Overview

Expose delivery through the reserved Spring MVC namespace using Servlet async
non-blocking output, with a precise pre-commit/problem boundary and complete
stream callback cleanup.

### Changes Required

#### 1. Implement servlet SSE framing and lifecycle

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityActivityStream.java` (new)
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityActivityStreamTest.java` (new)

**Changes**:

- Encode exact UTF-8 SSE frames with one blank line terminator, JSON data on a
  single logical `data:` field, activity `id`, and the settled event names.
- After all synchronous checks and admission succeed, set response metadata,
  call `request.startAsync(request, response)`, set timeout `0`, register an
  `AsyncListener`, and register a `WriteListener`.
- If async initialization itself fails before ownership transfers, release the
  admitted slot and let the existing uncommitted-response filter produce a
  sanitized JSON problem. Once `startAsync()` succeeds, do not return to JSON
  problem rendering.
- Queue the handshake before allowing activity fan-out, ensuring it is always
  the first frame and the point that starts the streaming body.
- Drain only when the servlet output is ready. Start a 5-second generation-
  checked deadline when a frame becomes queue head; cancel it only after that
  complete frame is accepted.
- Treat `onComplete`, `onTimeout`, `onError`, `WriteListener.onError`, explicit
  client cancellation, write exception, overflow, live failure, and adapter
  shutdown as idempotent close inputs.
- Never call MVC problem handling after `startAsync()`. Container-originated
  failures close the async response and are not passed through
  `completeWithError`-style status rewriting.

#### 2. Register and authorize the activity operation

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiPaths.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityAccessService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestController.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRouteRegistrar.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostObservabilityWebAutoConfiguration.java`

**Changes**:

- Add `ACTIVITY = ROOT + "/activity"` and
  `ACTIVITY_SUBSCRIBE`, still mapped only to `BIFROST_OPERATOR`.
- Register the exact GET handler with both request and response parameters
  before the namespace fallback.
- Preserve the existing filter's API-key comparison, instance header,
  no-store policy, security-context restoration, and async-supported/all-
  dispatcher registration.
- Keep host MVC async configuration untouched; delivery does not use Spring's
  `AsyncTaskExecutor` or a host-provided executor.
- Ensure route rollback closes the created runtime and unregisters the activity
  mapping just like existing routes.

#### 3. Update internal surface classification

**File**:
`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java`

**Changes**:

- Allowlist only new technically public internal types that must cross
  auto-configuration/web package boundaries, with explicit reasons.
- Extend bounded DTO assertions to cover the activity wire DTO and continue to
  forbid servlet, runtime, trace-record, throwable, path, resource, stream, and
  publisher types in serialized records.

### Success Criteria

#### Automated Verification

- [x] Stream unit tests verify byte-exact framing, handshake-first ordering,
  ready/not-ready behavior, deadline expiry, callback convergence, and exactly-
  once slot release:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=ObservabilityActivityStreamTest test`
- [x] Route/filter tests verify authentication occurs before admission, activity
  authorization, exact path registration, async support, no-store and instance
  headers, rollback, and reserved-namespace behavior:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=ObservabilityApiKeyFilterTest,ObservabilityRouteRegistrarTest,ObservabilityRouteCollisionDetectorTest test`
- [x] Public-surface classification passes:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostPublicSurfaceArchitectureTest test`

#### Manual Verification

- [ ] With a running sample, `curl -N` receives `handshake` first and then
  increasing activity IDs while an execution runs.
- [ ] Missing/invalid API key, changed instance, stale cursor, unavailable live
  monitoring, and the 17th connection return JSON before any SSE bytes.
- [ ] Closing `curl` promptly removes the subscriber; restarting/stopping the
  application closes all open streams without changing execution results.

---

## Phase 4: Prove Races, Reconnect, Isolation, and Shutdown

### Overview

Add focused real-container and concurrency coverage for the ticket's acceptance
signals while leaving the reviewed reusable fixture corpus to PR 06.

### Changes Required

#### 1. Add real-server SSE integration coverage

**File**:
`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ObservabilitySseIntegrationTest.java` (new)

**Changes**:

- Use a random-port Spring Boot test and Java's streaming HTTP client so the
  real servlet async/non-blocking lifecycle is exercised rather than only
  `MockMvc` response buffering.
- Cover:
  - baseline cursor followed by activity published before, during, and after
    subscribe admission;
  - strict per-stream cursor order and allowed duplicate replay;
  - disconnect and reconnect after the last processed cursor;
  - `STALE_CURSOR` after replay overwrite and after a changed `instanceId`;
  - malformed/future cursors and unsupported `Last-Event-ID`;
  - the fixed 16-stream capacity and immediate 17th-request rejection;
  - slot reuse after normal close, client cancellation, initialization failure,
    overflow, write failure, deadline, and shutdown;
  - live failure closing existing streams while status, active snapshots, new
    streams, skill operations, and finalized-trace operations exhibit their
    required independent availability behavior; and
  - one slow stream not delaying a healthy stream or an execution publisher.
- Tag representative tests with the applicable slow-execution workflow
  requirement IDs used by the Phase 1/PR 11 handoff.

#### 2. Extend existing observation and REST regression suites

**Files**:

- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandleTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionObservationConcurrencyTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestIntegrationTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ConsoleRestFixtureCorpusTest.java`

**Changes**:

- Preserve active fail-closed behavior and prove skill/trace operations remain
  usable after delivery or projection failure.
- Prove concurrent sessions preserve per-trace canonical order and global
  delivery-cursor order without subscriber state entering observation locks.
- Keep PR 04 REST fixtures byte-identical; this PR must not accidentally alter
  existing REST DTO or problem serialization.

#### 3. Verify the PR 06 fixture handoff (no planned file changes)

During review, verify `bifrost-console-fixtures/README.md` and
`ai/thoughts/tickets/bifrost-console-pr-06-artifact-streaming-integration.md`
still assign deterministic SSE fixture generation and the complete transport
corpus to PR 06.

- If the implemented wire differs from this plan, stop and revise the
  coordinated boundary decision before changing fixtures or downstream
  tickets.

### Success Criteria

#### Automated Verification

- [x] Real-container SSE tests pass repeatedly:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=ObservabilitySseIntegrationTest test`
- [x] All starter tests pass:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter test`
- [x] Full reactor verification passes:
  `.\mvnw.cmd test`
- [x] Existing deterministic REST and trace fixture tests produce no diff:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=ConsoleRestFixtureCorpusTest,ConsoleTraceFixtureCorpusTest test`
- [x] A second full test run leaves the worktree unchanged.

#### Manual Verification

- [ ] Run at least two concurrent observed executions with one healthy and one
  deliberately stalled SSE client; execution latency/outcomes and the healthy
  stream remain unaffected.
- [ ] Kill a client without a graceful close and confirm its slot is reclaimed
  through container callbacks.
- [ ] Stop the application with open streams and confirm all connections close,
  no Bifrost delivery/deadline thread remains, and queued diagnostic events are
  not drained during shutdown.
- [x] Confirm the implementation contains no hidden compatibility overload,
  configurable limit, heartbeat, or second cursor-input mechanism.

## Testing Strategy

### Unit Tests

- Activity DTO completeness, nullable-field policy, bounds, retained-weight
  accounting, and exact mapping.
- Cursor parsing/status mapping and request-shape validation.
- Dispatcher batching, ordering, coalescing, serialization reuse, queue
  accounting, overflow, gap, admission, and idempotent release.
- Non-blocking writer readiness, frame boundary, deadline generation, and all
  async lifecycle callbacks.
- Runtime construction rollback and close ordering.

### Integration Tests

- Use a real embedded servlet container for continuous response framing,
  cancellation, backpressure, and shutdown.
- Exercise baseline-plus-stream publication races and reconnect from actual
  emitted IDs.
- Prove pre-start JSON problems remain JSON and post-start failures only close
  the stream.
- Preserve authentication, instance metadata, no-store, route ownership, and
  unrelated skill/trace availability.

Before implementation, create the dedicated testing plan with
`ai/commands/3_testing_plan.md`. It should identify the first failing tests,
test doubles needed for deterministic output readiness/deadlines, workflow
requirement IDs, repeated-run strategy, and exact exit criteria.

### Manual Testing Steps

1. Start `bifrost-sample` with observability enabled and obtain the instance ID
   plus first active baseline cursor.
2. Open the activity route with `curl -N`, then run a skill and verify
   handshake-first framing and increasing activity IDs.
3. Disconnect, publish more activity, reconnect after the last processed ID,
   and verify ordered replay with duplicate tolerance.
4. Reconnect with an overwritten cursor and a previous-process instance ID and
   verify `410 STALE_CURSOR` JSON before streaming.
5. Saturate 16 streams, verify the 17th receives `429 LIMIT_EXCEEDED`, close one,
   and verify the next request is admitted.
6. Stall one client, verify its bounded deadline/overflow closure, and confirm
   another client and the observed execution continue normally.
7. Force the existing live availability failure seam and verify current
   streams close, active/new-live operations fail closed, and skill/trace
   operations remain usable.

## Performance Considerations

- Publication adds only one constant-time coalesced signal after a successful
  bounded replay append. It performs no subscriber iteration, serialization,
  servlet access, queue wait, or network work.
- Process-wide subscriber pending data is bounded to 16 MiB charged capacity
  plus small metadata. Immutable serialized frames may be shared, but accounting
  must remain conservative per subscriber.
- A single dispatcher avoids per-event thread creation and serializes each
  activity once. The 16-subscriber cap and 256-event replay batch bound each
  dispatch cycle; the dispatcher should yield/reschedule between batches when
  more work remains so close/unavailable signals are not starved.
- Servlet non-blocking readiness prevents a slow socket from occupying the
  dispatcher. Deadline scheduling is separate so a busy dispatcher cannot
  delay slow-client closure.
- Do not add heartbeat work without measured proxy requirements; reconnection
  and baseline refresh already define idle transport recovery.

## Migration Notes

There is no persisted-data or configuration migration. This is the first SSE
producer contract in the repository. Internal constructor and record changes
are updated atomically with all Java callers and tests, with no shim.

Rollback removes the activity route, delivery resource, signal injection, and
new activity wire fields together. Existing REST snapshots, replay buffering,
active projection, skill catalog, and finalized-trace catalog remain the PR 04
behavior.

## References

- Original ticket:
  `ai/thoughts/tickets/bifrost-console-pr-05-live-sse-delivery.md`
- Research:
  `ai/thoughts/research/2026-07-26-live-sse-delivery.md`
- Phase 1 design:
  `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md`
- Implementation roadmap:
  `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md`
- Future Go live consumer:
  `ai/thoughts/tickets/bifrost-console-pr-11-live-execution-experience.md`
- Future MCP reuse:
  `ai/thoughts/tickets/bifrost-console-pr-17-mcp-runtime-inspection.md`
- Cross-boundary fixture/integration handoff:
  `ai/thoughts/tickets/bifrost-console-pr-06-artifact-streaming-integration.md`
- Framework compatibility policy:
  `ai/thoughts/framework-feature-design-lens.md`
- Spring 6.2.16 sources inspected from the resolved local source JAR:
  `org.springframework.web.servlet.mvc.method.annotation.SseEmitter` and
  `ResponseBodyEmitter`
