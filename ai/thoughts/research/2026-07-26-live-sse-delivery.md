---
date: 2026-07-26T00:12:00-07:00
researcher: Codex
git_commit: 97feaf938b961f97306e77fcc1250d504af10cff
branch: main
repository: bifrost
topic: "Bifrost Console PR 05 — Live SSE Delivery"
tags: [research, codebase, observability, sse, replay, spring-mvc]
status: complete
last_updated: 2026-07-26
last_updated_by: Codex
---

# Research: Bifrost Console PR 05 — Live SSE Delivery

**Date**: 2026-07-26T00:12:00-07:00  
**Researcher**: Codex  
**Git Commit**: 97feaf938b961f97306e77fcc1250d504af10cff  
**Branch**: main  
**Repository**: bifrost

## Research Question

Research the current codebase for
`ai/thoughts/tickets/bifrost-console-pr-05-live-sse-delivery.md`, using the
phase roadmap and later tickets to document how the existing implementation
supports live SSE delivery and how later PRs consume it.

## Summary

The current checkout contains the execution-side live projection, active
registry, bounded replay buffer, fail-closed availability state, authenticated
Spring MVC REST adapter, instance identity, active baseline resume cursor, and
stable problem codes that PR 05 builds upon. It does not currently contain an
SSE route, subscription registry, delivery worker, per-subscriber pending
bound, write deadline, process-wide subscription admission counter, activity
wire DTO, SSE fixture, or logic that closes open streams when live monitoring
becomes unavailable.

Canonical records are published to observation only after the canonical writer
has appended the record. The observation handle synchronously projects bounded
state and appends zero or one activity to the replay buffer. Both the trace
handle append and observation publication paths are synchronized, and
observation exceptions are contained so they do not change canonical execution
behavior (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:221`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:401`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:407`).
The replay buffer assigns a strictly increasing process-local delivery cursor
and evicts whole oldest events when either its count or retained-byte bound is
exceeded
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActivityReplayBuffer.java:41`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActivityReplayBuffer.java:61`).

PR 04 already exposes the baseline half of the baseline-plus-stream workflow.
The first active-execution page reads the replay buffer's current cursor and
returns it as `resumeCursor`; later active pages do not repeat it
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestController.java:98`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestController.java:121`).
The runtime creates a new random `instanceId` when observability activates and
the authentication filter applies that identity as
`X-Bifrost-Instance-Id` to authenticated responses
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRouteRegistrar.java:117`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRouteRegistrar.java:133`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiKeyFilter.java:68`).

The phase design classifies the SSE activity protocol as a current-version,
coordinated Java-to-Go console contract covered by the exact Bifrost release
string. The implementation classes and Spring beans are separately classified
by the repository's architecture test as internal framework composition, not
application API or supported SPI
(`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:57`,
`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:59`,
`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:187`).

## Detailed Findings

### 1. End-to-end publication path that exists today

The current flow is:

```text
canonical trace append (per-trace synchronized)
  -> optional observation callback
     -> per-execution synchronized projection
        -> active registry replacement
        -> bounded replay-buffer append
```

`DefaultExecutionTraceHandle.append(...)` is synchronized. For ordinary
records it writes the canonical record and then calls `publish(record)`. For
chunked payloads it writes the envelope and all chunks before publishing the
logical record with reconstructed data
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:221`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:393`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:396`).
The observation callback is wrapped in a `RuntimeException` containment
boundary
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:407`).

`DefaultExecutionObservationHandle.recordAppended(...)` is also synchronized.
It stops processing once the handle is closed or the shared live availability
has failed, then performs projection, registry replacement, and replay
publication in sequence
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java:92`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java:94`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java:111`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java:127`).
There is currently no subscriber callback or network call from this path.
`ActivityReplayBuffer` exposes only `append`, `currentCursor`, and
`replayAfter`; it has no notification/listener method
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ActivityReplayBuffer.java:3`).

Terminal publication has a separate lifecycle. Canonical `TRACE_COMPLETED`
activity is held until core finalization establishes trace availability.
Successful finalization publishes the catalog entry and enriched terminal
activity; failed finalization publishes the observability-owned
`EXECUTION_OBSERVATION_ENDED` activity. Active-registry removal occurs in a
`finally` block
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java:137`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java:196`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java:169`).
`BifrostSession` releases its session lock before it calls the observation
close boundary
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java:774`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java:778`).

### 2. Replay buffer and cursor semantics

The production replay bounds are 10,000 events and 16 MiB retained UTF-8
weight. A single activity is capped at 12 KiB, with separate detail field and
detail byte bounds
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionObservationLimits.java:10`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionObservationLimits.java:12`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionObservationLimits.java:13`).
These are hard-coded internal limits, not `bifrost.*` configuration.

The replay buffer:

- assigns positive, strictly increasing delivery cursors;
- uses `0` as the before-first cursor;
- rejects cursor exhaustion or non-increasing supplied cursors;
- evicts whole oldest events until both configured bounds hold;
- returns `FUTURE` when the requested cursor exceeds the published cursor;
- returns `EMPTY` when no later event exists;
- returns `TOO_OLD` when the next event after the requested cursor has already
  been evicted; and
- returns at most the caller-provided positive limit.

These behaviors are implemented in
`InMemoryActivityReplayBuffer.replayAfter(...)`
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActivityReplayBuffer.java:75`)
and covered for eviction, empty/future ranges, concurrent publishers, cursor
reuse, and production bounds
(`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActivityReplayBufferTest.java:16`,
`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActivityReplayBufferTest.java:36`,
`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActivityReplayBufferTest.java:71`,
`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActivityReplayBufferTest.java:93`).

`ReplayResult.Status` is currently an internal four-value result:
`AVAILABLE`, `EMPTY`, `TOO_OLD`, and `FUTURE`
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ReplayResult.java:12`).
No web adapter currently translates those statuses for an activity
subscription.

### 3. Baseline, identity, and stale-state primitives

The active baseline endpoint already checks live availability before returning
state. The instance status reports the availability boolean, while active list
and detail calls return `LIVE_MONITORING_UNAVAILABLE` after the shared state has
failed
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestController.java:55`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestController.java:103`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestController.java:206`).
Skill and trace catalog handlers do not call `requireLive`.

The first active collection page captures:

- active-registry high water for its keyset traversal;
- one `observedAt`;
- the replay buffer's current delivery cursor as a decimal string
  `resumeCursor`; and
- instance identity through the centrally applied response header.

The DTO includes `resumeCursor` only when non-null
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/dto/ObservabilityDtos.java:75`).
The baseline behavior is exercised in the REST integration test
(`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestIntegrationTest.java:155`)
and represented in
`bifrost-console-fixtures/application-rest/active-executions-page.json:1`.

The ordinary REST continuation codec already distinguishes malformed/wrong
collection cursors (`INVALID_CURSOR`) from another application instance
(`STALE_CURSOR`)
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityCursorCodec.java:46`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityCursorCodec.java:65`).
That opaque REST cursor format is separate from the decimal activity delivery
cursor returned by the active baseline.

### 4. Fail-closed availability and shutdown ownership

`LiveMonitoringAvailability` is a one-way `AtomicReference`: the first failure
wins, and it retains only the sanitized operation name and exception class
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/LiveMonitoringAvailability.java:8`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/LiveMonitoringAvailability.java:21`).
Projection, registry, replay publication, and terminal publication failures
call this shared failure boundary. The log contains operation, session ID,
trace ID, and exception class, but not the exception message
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java:246`).

The availability object does not currently publish a change notification.
Consequently, there is no existing mechanism that an open stream can register
with to close immediately on the first failure. Existing request-time
fail-closed behavior is implemented only by reading `isAvailable()`.

`ObservabilityActivationCoordinator.close()` sets state to disabled, clears its
runtime reference, and closes the runtime
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/ObservabilityActivationCoordinator.java:61`).
`ObservabilityRuntime.close()` currently closes completion retention and the
trace catalog only
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/ObservabilityRuntime.java:29`).
There is no live-delivery resource in the runtime close graph in this checkout.
`ObservabilityRouteRegistrar.destroy()` unregisters routes and then closes the
activation coordinator
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRouteRegistrar.java:239`).

### 5. Spring MVC route, authentication, and async request foundation

The current route constants include instance, skills, active executions, and
traces, but no activity route
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiPaths.java:5`).
The registrar programmatically registers only the PR 04 JSON GET handlers and
a reserved-namespace fallback
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRouteRegistrar.java:94`).
The wildcard collision detector already treats a host mapping under
`/_bifrost/observability/v1/activity` as a namespace collision
(`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRouteCollisionDetectorTest.java:66`).

Authentication is a route-scoped servlet filter. It:

- applies `Cache-Control: no-store`;
- validates exactly one printable ASCII `X-Bifrost-Api-Key`;
- uses constant-time byte comparison;
- installs an internal authenticated operator in the Spring Security context;
- applies `X-Bifrost-Instance-Id` only after authentication;
- allows GET only;
- maps uncommitted exceptions to JSON problems; and
- rethrows errors after the response is committed.

The committed-response branch is at
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiKeyFilter.java:83`.
The filter is registered for every dispatcher type and is explicitly
async-supported
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostObservabilityWebAutoConfiguration.java:118`).
This means servlet async redispatches pass through the same filter mapping.

`ObservabilityAccessService.Operation` currently has instance, skill, active,
and trace read authorities; there is no activity-subscribe operation
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityAccessService.java:7`).

The project is built against Spring Boot 3.5.11 and resolves Spring Web MVC
6.2.16 (`pom.xml:49`, `bifrost-spring-boot-starter/pom.xml:79`). Inspection of
the resolved Spring 6.2.16 source JAR establishes these lifecycle facts:

- `SseEmitter` is a `ResponseBodyEmitter` that defaults the response content
  type to `text/event-stream`.
- Each `SseEmitter.send(...)` is guarded by the emitter write lock and the MVC
  handler serializes via an `HttpMessageConverter`, then flushes the response.
- The return-value handler starts `DeferredResult` processing before it
  initializes the emitter handler.
- Sends performed before handler initialization are retained by
  `ResponseBodyEmitter` as early sends and are flushed during initialization.
- `onTimeout`, `onError`, and `onCompletion` are backed by the deferred result;
  in Spring 6.2 multiple callbacks may be registered.
- `onCompletion` runs for normal completion, timeout, and network error and is
  the general lifecycle signal that the emitter is no longer usable.
- An `IOException` from `send` is expected to result in container notification
  and redispatch; Spring's API documentation says not to call
  `completeWithError` again for that container-originated send failure.
- `completeWithError` redispatches through MVC, but once streaming has begun
  the response is committed and its HTTP status cannot be changed.
- The emitter timeout is a servlet async request timeout. It begins after the
  initial request-processing thread exits; it is not a per-write deadline.
- Spring MVC's configured `AsyncTaskExecutor` owns `Callable` work and blocking
  writes for reactive streaming return values. Direct application calls to
  `SseEmitter.send(...)` execute and flush on the calling thread.

The application currently declares no observability-owned MVC async executor,
`WebMvcConfigurer.configureAsyncSupport`, delivery executor, or scheduler.

### 6. Current protocol and fixture inventory

The stable problem enum already contains all PR 05 named outcomes:
`STALE_CURSOR`, `LIVE_MONITORING_UNAVAILABLE`, and `LIMIT_EXCEEDED`
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityProblem.java:5`).
The problem mapper preserves an `ObservabilityException` found in the cause
chain and otherwise emits sanitized `APPLICATION_ERROR`
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityProblemMapper.java:5`).

The deterministic fixture corpus currently contains PR 04 REST bodies for:

- instance status;
- active baseline with `resumeCursor`;
- stale cursor;
- live monitoring unavailable; and
- limit exceeded.

There is no SSE fixture directory or activity envelope fixture in the current
tree. `bifrost-console-fixtures/README.md:22` records that the existing
`application-rest/` corpus was produced by PR 04 and that PR 06 extends the
transport corpus with SSE and artifact streaming.

There is also no Go module or Go source in this checkout yet. The roadmap places
the console build foundation in PR 07 and target lifecycle in PR 09
(`ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:112`,
`ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:114`).

### 7. Framework surface classification

Using the categories in
`ai/thoughts/framework-feature-design-lens.md`, the affected surfaces currently
classify as follows.

#### Application API

No ordinary application-developer Java API is exposed for live subscription.
The architecture allowlist explicitly describes the observation and web Java
types as public only for internal cross-package composition
(`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:187`).

#### Supported SPI

No supported subscriber, delivery, executor, callback, or emitter SPI exists.
The observability auto-configuration beans are infrastructure-role beans and
are not guarded by `@ConditionalOnMissingBean`
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostObservabilityWebAutoConfiguration.java:40`).
The repository's architecture test describes the controllers, DTOs, replay
buffer, availability, and related public declarations as internal composition,
not replacement points.

#### Configuration and manifest contracts

The current `BifrostProperties.Observability` configuration has only:

- `enabled`;
- `auth.apiKey`;
- `completionGraceTtl`; and
- `traceCatalogMetadataTtl`.

There are no SSE admission, per-subscriber pending, write-deadline, heartbeat,
executor, replay, or timeout properties
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostProperties.java:328`).
The PR 05 ticket and phase text state that the initial process-wide
subscription limit is fixed rather than dynamically configurable.

#### Persisted or serialized contracts

The live SSE envelope is a serialized cross-component protocol surface, but it
is not persisted or durable. The phase documentation identifies it as part of
the coordinated Java-to-Go contract covered by
`consoleCompatibilityVersion`
(`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:77`).
No activity envelope DTO or serialized SSE fixture exists in the live code yet.

#### Ephemeral diagnostic formats

`ExecutionActivity` is the current in-memory diagnostic projection. It includes
delivery cursor, session and trace identity, optional canonical sequence,
timestamp, kind, optional frame/route context, bounded summary/details, and
retained weight
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionActivity.java:12`).
The phase design distinguishes this concise current-version live activity
contract from the more detailed finalized NDJSON trace
(`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:66`).

#### Internal or accidentally exposed implementation

The replay buffer interfaces and constructors, observation handle/factory,
availability state, runtime record, Spring controller, filter, DTO mapper,
route registrar, and infrastructure beans are technically exposed to Java
because cross-package composition requires public declarations. The explicit
architecture allowlist records them as internal
(`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:46`,
`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:187`).

### 8. Public declarations, constructors, beans, tests, and usage inventory

#### Public declarations and constructors

- `ActivityReplayBuffer`, `ReplayResult`, `ExecutionActivity`,
  `LiveMonitoringAvailability`, and `InMemoryActivityReplayBuffer` form the
  internal adapter-facing replay surface.
- `ObservabilityRuntime` owns instance identity and references to active,
  replay, availability, skills, and traces.
- `ObservabilityRestController`, `ObservabilityApiKeyFilter`,
  `ObservabilityAccessService`, and `ObservabilityProblem` form the current
  internal web boundary.
- `DefaultExecutionObservationHandleFactory` has public constructors for
  internal composition, including injection of replay and availability
  collaborators.

The architecture test supplies explicit evidence that these public modifiers
support internal Java collaboration only.

#### Spring beans and extension points

`BifrostObservabilityWebAutoConfiguration` declares infrastructure-role beans
for JSON, problems, access, DTO mapping, cursor coding, bounded page writing,
REST controller, collision detection, route registration, and the API-key
filter. None uses `@ConditionalOnMissingBean`. The filter is async-supported;
no async executor bean or MVC async configurer is declared.

#### Tests and fixtures

Existing tests cover:

- replay count/byte eviction and stale/future distinction;
- concurrent cursor assignment;
- per-trace canonical ordering under concurrent sessions;
- first-failure fail-closed state;
- observation failure containment and terminal cleanup;
- active baseline identity/observation/resume cursor;
- REST stale instance continuation;
- authentication, no-store response metadata, and filter behavior;
- route ownership/collision under the future activity path; and
- deterministic JSON problem bodies.

No existing test opens an SSE response or exercises subscriber admission,
pending overflow, write timeout, emitter callbacks, cancellation, reconnect,
or shutdown of an open stream.

#### Documentation and verified in-repository consumers

The Phase 1 design and PR 05 ticket are the supporting contract evidence. The
only executable in-repository consumer today is Java-side REST/fixture testing;
the Go consumer is roadmap work. The fixture README explicitly stages SSE
fixture completion for PR 06.

### 9. Later PR usage

PR 05 is the application-side producer boundary for later console work:

- PR 09 establishes the Go `TargetContext`, authenticated target identity,
  exact-version compatibility, cancellation, and scope rotation. Continuous
  SSE is explicitly out of PR 09
  (`ai/thoughts/tickets/bifrost-console-pr-09-target-context.md:58`).
- PR 10 consumes the active baseline and preserves upstream identity,
  observation time, high-water pagination, and continuations
  (`ai/thoughts/tickets/bifrost-console-pr-10-operational-views.md:15`).
- PR 11 owns one upstream SSE connection, keeps one bounded continuous recent
  activity interval, clears it on `STALE_CURSOR`, changed `instanceId`, or
  target-scope rotation, and relays it to browser tabs
  (`ai/thoughts/tickets/bifrost-console-pr-11-live-execution-experience.md:14`).
- PR 17 adapts the shared recent-activity query service for MCP rather than
  opening a second upstream subscription
  (`ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:127`,
  `ai/thoughts/tickets/bifrost-console-pr-11-live-execution-experience.md:38`).

The roadmap's Phase 1 gate requires executable authentication, compatibility,
pagination, SSE, artifact streaming, and consumed-trace fixtures before
target-facing Phase 2 behavior begins
(`ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:199`).

## Code References

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:221`
  — synchronized canonical append entry point.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:401`
  — canonical writer completes before observation publication.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java:92`
  — synchronized bounded live projection and replay publication.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActivityReplayBuffer.java:41`
  — delivery cursor assignment and bounded append.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActivityReplayBuffer.java:75`
  — replay status computation.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestController.java:98`
  — active baseline endpoint.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestController.java:121`
  — first-page activity resume cursor.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRouteRegistrar.java:94`
  — currently registered JSON routes.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiKeyFilter.java:68`
  — centrally applied authenticated instance metadata.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostObservabilityWebAutoConfiguration.java:119`
  — filter participates in async dispatch and is async-supported.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:187`
  — explicit internal classification of observation declarations.

## Architecture Documentation

The implementation separates three lifetimes:

1. Per-execution observation state is serialized by one observation handle.
2. Process-wide live state is held by the active registry, replay buffer, and
   first-failure availability instance created with the observability runtime.
3. HTTP request and future stream lifetimes are owned by the Spring MVC
   adapter.

The process-wide runtime is created only after configuration and reserved-route
validation succeeds. Activation is decided once. Disabled activation supplies
no-op observation and immediate trace-retention behavior; enabled activation
connects session creation to the shared runtime observation factory
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:131`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:174`).

The replay buffer is a reconnect window, not a subscriber queue. It owns the
global delivery cursor and retained activity interval. Subscriber-specific
pending delivery and stream lifecycle are not represented in the current
runtime object graph.

## Historical Context (from ai/thoughts/)

- `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:142`
  — records REST plus SSE as the initial one-way monitoring transport choice.
- `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:164`
  — places activity publication after successful canonical append.
- `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:233`
  — defines the bounded, instance-local replay contract.
- `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:247`
  — defines baseline and stream as non-atomic, best-effort views.
- `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:253`
  — defines per-subscriber overflow and write deadline as disconnect behavior.
- `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:255`
  — defines fixed process-wide authenticated SSE admission.
- `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:494`
  — defines `afterCursor` plus `instanceId`, SSE `id`, and `STALE_CURSOR`.
- `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:615`
  — requires instance identity in the handshake and every envelope.
- `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:105`
  — positions PR 05 between REST foundation and artifact/integration work.
- `ai/thoughts/tickets/bifrost-console-pr-11-live-execution-experience.md:14`
  — records the future Go connection manager and recent-activity consumer.

## Related Research

No earlier research documents are present in `ai/thoughts/research/` in this
checkout.

## Open Questions

The ticket deliberately leaves the following values or detailed shapes for PR
planning; the live code and current configuration do not yet answer them:

- the activity route path and exact query parameter spelling beyond the phase
  terms `afterCursor` and `instanceId`;
- the SSE handshake event name and serialized handshake DTO;
- the activity envelope DTO shape, including whether `instanceId` wraps or
  accompanies the existing `ExecutionActivity` fields;
- replay batch size used by delivery workers;
- fixed process-wide subscription capacity;
- per-subscriber pending event and/or byte bounds;
- per-write deadline duration and the mechanism that enforces it independently
  of servlet async timeout;
- delivery worker/executor ownership and runtime shutdown order;
- the notification seam between replay append, availability failure, and
  independently running delivery work;
- subscriber slot release/accounting across completion, timeout, client
  cancellation, send failure, initialization failure, and adapter shutdown;
- the exact response-commit point used to distinguish pre-stream JSON problems
  from post-commit stream closure; and
- whether PR 05 itself adds deterministic SSE fixtures or leaves the complete
  reviewed transport corpus to the PR 06 integration step recorded by the
  fixture README.
