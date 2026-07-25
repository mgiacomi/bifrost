---
date: 2026-07-24 18:13:44 PDT
researcher: Codex
git_commit: 1d2810efbe91624dc2930fdbe89bfd9d2d08e089
branch: main
repository: bifrost
topic: "Bifrost Console PR 02 observation lifecycle and live projection core"
tags: [research, codebase, bifrost-console, observability, execution-lifecycle, trace-projection]
status: complete
last_updated: 2026-07-24
last_updated_by: Codex
---

# Research: Bifrost Console PR 02 Observation Lifecycle and Live Projection Core

**Date**: 2026-07-24 18:13:44 PDT  
**Researcher**: Codex (GPT-5)  
**Git Commit**: `1d2810efbe91624dc2930fdbe89bfd9d2d08e089`  
**Branch**: `main`  
**Repository**: `bifrost`

## Research Question

Use `ai/commands/1_research_codebase.md` to research
`ai/thoughts/tickets/bifrost-console-pr-02-observation-lifecycle.md`, using the
phase roadmap and later tickets where they clarify how PR 02 will be used.

## Summary

PR 01 is present in the researched checkout: commit `98799ce` introduced the
canonical trace semantics and HEAD `1d2810e` contains its cleanup. The runtime
has one canonical trace handle per `BifrostSession`, a session-local
`ReentrantLock`, monotonic per-trace sequence allocation, incremental NDJSON
storage, logical-payload chunking, replay-based journal projection, and
top-level completion paths that preserve execution failures and attach cleanup
failures as suppressed exceptions.

The central append seam is `BifrostSession.appendTraceRecord(...)`. Both framed
and unframed overloads acquire the session lock and call the session's
`ExecutionTraceHandle`. `DefaultExecutionTraceHandle.appendInternal(...)`
constructs the record, writes either one full record or an envelope followed by
all payload chunks, and returns only after those writes finish. That gives PR 02
one post-storage-success publication position with per-execution ordering.
Chunked appends currently return the payload-less storage envelope, while the
logical `JsonNode` remains local to `appendInternal`; live projection therefore
has to be connected where that logical value still exists rather than to the
returned envelope.

Execution lifecycle ownership is layered:

- `BifrostSessionRunner` creates the session and provides the standalone
  completion safety net.
- `ExecutionCoordinator` detects a top-level YAML mission, opens the root
  mission frame, closes it in `finally`, and performs canonical trace
  finalization only for that top-level invocation.
- Nested YAML skills reuse the same session and trace and do not independently
  finalize it.
- Both mission engines arbitrate timeout cleanup between worker and caller and
  unwind frames back to the invocation's baseline.
- `BifrostSession.finalizeTrace(...)` performs completed-journal projection,
  records projection failure canonically where possible, appends
  `TRACE_COMPLETED`, applies the persistence policy, and preserves the existing
  finalization exception behavior.

No PR 02 observation handle, activity projector, active-execution registry,
registry ordinal, delivery cursor, replay buffer, or
`liveMonitoringAvailable` state exists yet. There is also no global engine
concurrency ceiling: auto-configuration uses a virtual-thread-per-task mission
executor. The planned active registry consequently follows the number of live
top-level sessions rather than an existing engine admission limit.

The existing public `SkillTemplate` observer is a separate completed-execution
Application API. It receives one derived `SkillExecutionView` after
`BifrostSessionRunner.callWithNewSession(...)` has returned and finalized the
session. It is not a live append observer or an internal lifecycle hook.

## Detailed Findings

### 1. Execution identity and creation boundary

`DefaultSkillTemplate.invoke(...)` validates input, obtains the current Spring
Security authentication, and delegates to
`BifrostSessionRunner.callWithNewSession(...)`
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skillapi/DefaultSkillTemplate.java:85-120`).
The runner creates a new UUID session and constructs exactly one
`BifrostSession` for the call
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSessionRunner.java:82-109`).

The session constructor creates one `DefaultExecutionTraceHandle`; that handle
creates a distinct trace UUID, resets its file, and immediately appends
`TRACE_STARTED` and `TRACE_CAPTURE_POLICY_RECORDED`
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java:97-128`;
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:54-60`;
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:108-121`). Those two canonical records
are therefore produced during session construction, before execution enters
the coordinator.

`ExecutionCoordinator.execute(...)` determines whether an invocation is
top-level from the pre-open frame stack, then opens the root mission frame
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionCoordinator.java:65-97`).
Nested YAML invocations route back through the same coordinator while preserving
the parent plan and successful-skill ledger
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/CapabilityExecutionRouter.java:74-87`).
Only the invocation that observed an empty initial frame stack performs trace
finalization
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionCoordinator.java:161-187`).

### 2. Session serialization boundary

`BifrostSession` owns a `ReentrantLock` that protects the frame deque, plan,
usage, completed journal, authentication, and trace-handle access
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java:38-58`).
The lock is reentrant: serialized getters and journal projection can call other
serialized session methods.

Both trace append paths hold this lock for the complete handle append:

- framed append:
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java:718-735`;
- active-frame/unframed append:
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java:738-762`.

The current canonical NDJSON filesystem work therefore occurs within the same
session boundary that orders appends. `DefaultExecutionTraceHandle` also
synchronizes its append/finalize/read methods
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:124-147`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:167-198`), and the NDJSON writer synchronizes each physical write
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/NdjsonTraceRecordWriter.java:21-39`).
There is no network, fan-out, or live subscriber work in this path today.

The Phase 1 design assigns only deterministic bounded in-memory projection and
state publication to this ordered transaction. Subscriber signaling is merely
a wake-up for independent delivery work; filesystem discovery, network
delivery, callbacks, subscriber-capacity waits, and arbitrary payload copying
remain outside it
(`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:162-180`).

### 3. Canonical append and chunk completion seam

`DefaultExecutionTraceHandle.appendInternal(...)` performs these current steps:

1. rejects appends after completion;
2. initializes the trace if needed;
3. converts the logical payload to a copied `JsonNode`;
4. increments the canonical trace sequence;
5. for a large logical payload, builds and writes a payload-less envelope,
   writes every `PAYLOAD_CHUNK_APPENDED` record, then returns the envelope;
6. otherwise writes and returns the complete record.

The implementation is at
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:212-262`.
Chunk sequences and physical writes are in the same synchronized handle method
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:264-296`). Consequently, a successful
return means the complete storage representation has been appended, including
all chunks.

For a chunked payload, the logical value is available as `jsonData` before
storage chunking, but the returned `TraceRecord envelope` has `data == null`
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:227-255`). The existing reader later
reconstructs the logical record from envelope and chunks for retrospective
projection
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/NdjsonExecutionTraceReader.java:84-118`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/NdjsonExecutionTraceReader.java:131-209`). PR 02's planned live projector consumes the logical record
immediately after complete append and does not reread or tail the file
(`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:101-121`,
`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:162-176`).

### 4. Existing record production routes

`DefaultExecutionStateService` is the common state-and-recording facade. Frame
open pushes the frame before recording `FRAME_OPENED` and rolls the push back if
recording fails (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/state/DefaultExecutionStateService.java:59-90`).
Frame close records `FRAME_CLOSED` while the frame is still active, then pops it
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/state/DefaultExecutionStateService.java:98-143`).

Most trace kinds flow through `DefaultExecutionTraceRecorder`, including frame,
model, plan, tool, advisor, validation, error, and completion records
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/DefaultExecutionTraceRecorder.java:22-166`).
Planning, evidence, and step event methods also call
`session.appendTraceRecord(...)` directly through the state service
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/state/DefaultExecutionStateService.java:220-233`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/state/DefaultExecutionStateService.java:335-365`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/state/DefaultExecutionStateService.java:419-435`).
All of these paths converge at the session append methods, so the live
publication boundary does not need separate hooks in each engine subsystem.

The existing canonical vocabulary is `TraceRecordType`
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/TraceRecordType.java:3-38`).
The settled initial visible live subset reuses selected names from that enum;
`EXECUTION_OBSERVATION_ENDED` is the sole observability-owned addition
(`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:470-478`).

### 5. Current completed-journal projection and reusable interpretation

`ExecutionJournalProjector` is replay-derived. It accepts either a list or a
trace handle, walks logical reader output in order, and creates an immutable
snapshot list
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionJournalProjector.java:23-49`;
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionJournal.java:8-23`).

It currently selects thoughts, plan creation/update, linter and output-schema
outcomes, tool request/completion/failure, and errors; all other records,
including frame, ordinary model, step, evidence, and trace lifecycle records,
are omitted
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionJournalProjector.java:61-80`).
Tool identity is resolved
from canonical metadata/data/route, error summaries retain the source record
type and safe message/classification, and recursively named secret-like fields
are replaced with `[redacted]`
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionJournalProjector.java:121-281`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionJournalProjector.java:284-325`).

The journal does not bound copied application content; its role is a completed
execution projection. Phase 1 names its JSON, error-summary, and tool-identity
interpretation as reusable input while defining the live projector as a
separate bounded projection that also handles lifecycle and phase records
(`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:186-201`).

### 6. Usage, invocation counts, and active path facts

The session already stores an immutable `SessionUsageSnapshot` under its lock
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java:444-500`).
The snapshot contains skill, tool, linter
retry, and model counts; prompt, completion, and total usage; and exact,
heuristic, and unavailable response counts
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/usage/SessionUsageSnapshot.java:5-33`).

`DefaultSessionUsageService` updates those values at mission start, physical
model response, tool call, and linter retry, then applies the configured quota
checks (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/usage/DefaultSessionUsageService.java:24-78`).
Quota violations are thrown after the relevant observed count has been stored
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/usage/DefaultSessionUsageService.java:97-124`). The coordinator's ordinary
failure and finalization path therefore covers quota exceptions as execution
failures.

The current active path is the session's copied frame deque. Each
`ExecutionFrame` has frame and parent IDs, frame type, route, parameters, and
opened time
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionFrame.java:7-24`).
`getFramesSnapshot()` returns the current stack in deque iteration order with
the active frame first
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java:331-343`).

### 7. Normal, failure, timeout, quota, and cleanup completion

`ExecutionCoordinator` catches runtime failures and errors, assigns the terminal
failure ID, records `ERROR_RECORDED`, marks the trace errored, and rethrows the
original failure
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionCoordinator.java:100-134`).
Its `finally` block:

1. attempts root-frame closure;
2. captures a closure failure without skipping top-level finalization;
3. computes `SUCCEEDED`, `FAILED`, or `ABORTED`;
4. finalizes the trace for top-level execution;
5. adds finalization failure to the closure failure when both occur;
6. suppresses cleanup failure onto an existing execution failure, or throws it
   when execution otherwise succeeded.

This behavior is implemented at
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionCoordinator.java:135-210`.

`BifrostSession.finalizeTrace(...)` holds the session lock, projects the
completed journal before canonical completion, converts a journal-projection
failure into a canonical failed completion where possible, calls
`handle.finalizeTrace(...)`, and stores the completed journal only when both
operations succeed
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java:619-692`).
The trace handle appends
`TRACE_COMPLETED`, marks itself completed, and applies `NEVER`, `ONERROR`, or
`ALWAYS` deletion
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:167-192`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:354-371`).
The method currently returns `void`; finalized-artifact descriptors and grace
retention belong to PR 03.

`BifrostSessionRunner.completeSession(...)` is a second top-level safety net. It
does nothing when the coordinator has already completed the trace, otherwise it
records standalone failure/open-frame facts and finalizes. Its cleanup failure
is suppressed onto an existing action failure or thrown for an otherwise
successful action
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSessionRunner.java:112-190`).

Both direct and step-loop mission engines use `CleanupOwner` plus a latch to
ensure either the worker or timed-out/interrupted caller unwinds frames to the
pre-invocation depth. The direct engine implementation is
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/DefaultMissionExecutionEngine.java:120-238`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/DefaultMissionExecutionEngine.java:240-308`; the step engine has the same ownership pattern at
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/step/StepLoopMissionExecutionEngine.java:221-288`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/step/StepLoopMissionExecutionEngine.java:1078-1144`.

These are the current authoritative cleanup paths to which one per-execution
observation close disposition can be correlated. The Phase 1 terminal rule is
that successful core finalization may release the pending enriched canonical
completion, while failed core finalization discards it, attempts
`EXECUTION_OBSERVATION_ENDED`, and removes active state regardless
(`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:321-339`).

### 8. Current concurrency model

Auto-configuration creates one framework-owned
`Executors.newVirtualThreadPerTaskExecutor()` and injects it into both mission
engines
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:320-344`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:419-441`). There is no semaphore, queue capacity, execution registry, or
process-wide concurrency admission control in this wiring.

Sessions are isolated by independently created IDs, locks, frame stacks, usage
snapshots, trace handles, and files. The planned registry therefore has one
bounded entry per authoritative live top-level session and no independent
observability cap
(`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:211-231`).
Its `registryOrdinal` is instance-local pagination metadata. The planned replay
buffer has a separate instance-local monotonically increasing delivery cursor;
canonical sequence remains per trace
(`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:233-257`).

### 9. Existing public observer versus planned internal observation handle

The supported Application API includes `SkillTemplate` overloads accepting
`Consumer<SkillExecutionView>`
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/api/SkillTemplate.java:6-14`).
After the session runner and canonical finalization return, `DefaultSkillTemplate`
maps the finalized journal and invokes that application callback
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skillapi/DefaultSkillTemplate.java:104-123`;
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skillapi/SkillExecutionViewMapper.java:32-53`).
The callback receives completed, derived journal events; it is not called under
the session append lock and is not a source of live activity.

The PR 02 handle described by the phase design is instead one optional
framework-owned internal lifecycle coordinator attached to each execution. It
receives only successful canonical logical appends and is closed exactly once
from guaranteed core cleanup. Disabled observability supplies a no-op handle
(`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:178-184`,
`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:359-375`).

### 10. Failure isolation and fail-closed state

Current canonical append, journal projection, and finalization failures are core
failures: append I/O is wrapped as `IllegalStateException`, journal projection
or finalization failure propagates, and cleanup suppression is retained
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java:619-692`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java:713-762`;
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionCoordinator.java:135-199`;
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSessionRunner.java:166-190`).

No optional live-publication code or `liveMonitoringAvailable` value exists in
the checkout. The settled PR 02 behavior classifies only new projection,
registry, and replay-buffer failures as optional. Those failures are contained,
logged in sanitized form, change the process-local availability flag from true
to false, and cause later active snapshot/stream operations to report
`LIVE_MONITORING_UNAVAILABLE`; execution and canonical failures keep their
current results (`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:154-180`).

### 11. Contract and compatibility inventory

#### Application API

- `SkillTemplate`, `SkillExecutionView`, and `SkillExecutionEvent` are
  deliberately supported and documented (`README.md:142-146`).
- The completed-execution observer overload is an in-repository consumer surface
  in samples and integration tests. It is separate from PR 02's internal live
  observation lifecycle.
- PR 02's ticket explicitly excludes a new public observer SPI.

#### Supported SPI

- None exists. Architecture tests assert that there is no `.spi` package and
  that the supported bean-override allowlist is empty
  (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:245-250`;
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostAutoConfigurationBoundaryTest.java:26-52`).

#### Configuration and manifest contracts

- Current relevant documented configuration is `bifrost.session.*` for timeout,
  depth, and quotas and `execution-trace.persistence` for `NEVER`, `ONERROR`, or
  `ALWAYS` (`README.md:252-269`;
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/ExecutionTraceProperties.java:9-23`).
- PR 02 does not yet add a property in the live code. PR 04 owns opt-in
  observability configuration, while PR 03 owns completion-grace/catalog TTL
  integration.
- Skill manifests are not changed by the PR 02 ticket.

#### Persisted or serialized contracts

- There is no deliberately durable or cross-version trace contract.
- `TraceRecord` is technically serialized as native NDJSON, but the framework
  policy classifies it as current-run diagnostic evidence rather than a durable
  interchange format
  (`ai/thoughts/framework-feature-design-lens.md:19-38`).

#### Ephemeral diagnostic formats

- `TraceRecord`, `TraceRecordType`, NDJSON envelope/chunk layout, and completed
  journal projection are the current ephemeral diagnostic formats.
- PR 01's Java-produced fixtures in `bifrost-console-fixtures` are the executable
  current-release Java-to-future-Go agreement. PR 02 live activity will become
  another bounded instance-local diagnostic representation consumed later by
  REST/SSE, not durable history.

#### Internal or accidentally exposed implementation

- `BifrostSession`, `BifrostSessionRunner`, `ExecutionCoordinator`,
  `ExecutionTraceHandle`, `ExecutionTraceRecorder`,
  `DefaultExecutionTraceHandle`, `ExecutionStateService`, and
  `ExecutionJournalProjector` are public Java types only for collaboration
  across internal packages. The architecture allowlist explicitly classifies
  them that way
(`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:59-108`,
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:152-163`).
- Auto-configuration bean factory methods are package-private,
  framework-owned, and do not use `@ConditionalOnMissingBean`
  (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostAutoConfigurationBoundaryTest.java:55-92`).
- The observation handle, projector, registry, buffer, and availability state
  described by PR 02 belong to this internal category until later adapter PRs
  map bounded DTOs onto REST/SSE.

### 12. Downstream PR leverage

- **PR 03 — catalogs:** adds finalized-artifact descriptors and coordinates
  catalog publication with the same observation handle. It enriches the one
  terminal activity with application trace availability before active-entry
  removal
  (`ai/thoughts/tickets/bifrost-console-pr-03-observability-catalogs.md`).
- **PR 04 — REST snapshots:** exposes instance status and the active registry's
  bounded list/detail snapshots. It must map protocol DTOs rather than exposing
  PR 02 internal Java types
  (`ai/thoughts/tickets/bifrost-console-pr-04-spring-rest-adapter.md`).
- **PR 05 — SSE:** reads PR 02's cursor replay buffer and availability state,
  adds bounded subscriber delivery outside execution locks, and closes streams
  when live monitoring becomes unavailable
  (`ai/thoughts/tickets/bifrost-console-pr-05-live-sse-delivery.md`).
- **PR 06 — integration:** verifies snapshots, SSE, catalog, availability, and
  exact trace bytes together and completes Java-side fixtures
  (`ai/thoughts/tickets/bifrost-console-pr-06-artifact-streaming-integration.md`).
- **PR 11 — UI live experience:** the Go console maintains one upstream
  connection and one continuous recent-activity window, using the PR 02 active
  baseline and cursor stream to show current summary, active path, recent
  narrative, completion, gaps, and trace availability
  (`ai/thoughts/tickets/bifrost-console-pr-11-live-execution-experience.md`).
- **PR 17 — MCP runtime inspection:** later adapts the same shared active and
  recent-activity services rather than creating another execution registry or
  subscription (`ai/thoughts/tickets/bifrost-console-pr-17-mcp-runtime-inspection.md:1-39`).

The Java-to-Go protected consumers do not exist in executable Go code yet.
They are specified by the roadmap and Phase 1/2 documents. PR 02 establishes
internal semantics; PRs 04-06 establish and exercise the external REST/SSE
boundary that later Go PRs consume.

## Tests and Fixtures

Current tests that exercise the seams PR 02 builds upon include:

- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/core/BifrostSessionRunnerTest.java:127-231`
  — concurrent sessions, frame isolation, and journal isolation across virtual
  threads.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/core/BifrostSessionRunnerTest.java:90-199`
  — terminal failure linkage and standalone open-frame cleanup.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/core/ExecutionCoordinatorTest.java:731-799`
  — the original mission failure remains primary when frame cleanup and
  finalization also fail.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/core/ExecutionCoordinatorTest.java:1283-1346`
  — timeout leaves no frames and produces terminal trace completion.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/skill/MissionExecutionEngineTest.java:276-349`
  — timeout/interruption cleanup for model and planning work.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionTraceHandleTest.java:20-94`
  — retention policies, trace identity, timestamps, and rejection of late
  appends.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/NdjsonExecutionTraceReaderTest.java:26-165`
  — chunk reconstruction, chunk ordering, and active-trace incomplete-chunk
  behavior.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionJournalProjectorTest.java:22-213`
  — tool/error summaries, redaction, repeated events, and non-inference.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:197-250`
  — closed public API and no supported SPI.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostAutoConfigurationBoundaryTest.java:49-92`
  — framework-owned beans and absence of replacement seams.

On this checkout, the focused Maven run passed all 65 selected tests:

```text
ExecutionTraceHandleTest
NdjsonExecutionTraceReaderTest
ExecutionJournalProjectorTest
BifrostSessionRunnerTest
ExecutionCoordinatorTest
MissionExecutionEngineTest
BifrostPublicSurfaceArchitectureTest
BifrostAutoConfigurationBoundaryTest

Tests run: 65, Failures: 0, Errors: 0, Skipped: 0
```

`bifrost-console-fixtures` already contains Java-produced PR 01 NDJSON and
expected semantic results, including chunked payloads, terminal failure/abort,
retry, validation, and usage cases
(`bifrost-console-fixtures/README.md:1-19`). PR 02 has no live-activity fixtures
in the current tree.

## Architecture Documentation

The current-to-planned data flow is:

```text
SkillTemplate
  -> BifrostSessionRunner creates one session + canonical trace handle
  -> ExecutionCoordinator opens the top-level mission frame
  -> engines/state service/recorders call BifrostSession.appendTraceRecord
  -> session lock orders append
  -> DefaultExecutionTraceHandle writes full logical storage representation
  -> [PR 02 post-success bounded logical projection]
       -> one current snapshot in active registry
       -> zero or one bounded envelope in cursor replay buffer
  -> coordinator closes root frame and attempts canonical finalization
  -> [PR 02 observation handle closes exactly once and removes active entry]
  -> BifrostSessionRunner verifies/finalizes any remaining standalone lifecycle
  -> existing public observer receives the completed journal view
```

Canonical trace sequence is per execution/trace. Registry ordinal and delivery
cursor are planned instance-local process-wide orderings serving different
purposes: keyset pagination and reconnect replay, respectively. Neither
replaces canonical sequence.

## Historical Context (from `ai/thoughts/`)

- `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md` is
  the settled Phase 1 source for the canonical publisher, bounded projector,
  active registry, cursor buffer, fail-closed behavior, and terminal release
  rules.
- `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md`
  places PR 02 after canonical trace semantics and before catalogs, REST, SSE,
  and integration.
- `ai/thoughts/tickets/bifrost-console-pr-01-canonical-trace-semantics.md`
  describes the prerequisite now present at HEAD.
- `ai/thoughts/framework-feature-design-lens.md` classifies traces as ephemeral
  diagnostic formats and technically public internals as exposure rather than
  supported contracts.
- `ai/thoughts/future/possible-nested-execution-observability.md` documents a
  separate possible future cross-execution correlation model. Current PR 02
  treats nested skills as frames in one session/trace and does not create a
  parent/child execution registry.

## Related Research

No earlier documents were present in `ai/thoughts/research/` at the time of
this research.

## Open Questions

These are implementation details not represented by current production types
and left to PR 02 detailed planning:

1. The package/type decomposition for the no-op/enabled observation handle,
   deterministic projector, active registry, replay buffer, and availability
   state has not been established in code.
2. The exact construction order that attaches an enabled observation handle
   early enough to observe the two constructor-time trace records has not been
   established in code.
3. Concrete bounds for active path length, summary/details size, replay event
   count/bytes, and cursor representation remain implementation-planning
   decisions.
4. The exact internal completion-disposition type that crosses from canonical
   finalization to exactly-once observation cleanup does not exist yet.
5. PR 03 will establish the finalized-artifact descriptor and completion-grace
   return shape; PR 02's terminal holding behavior precedes that enrichment but
   does not currently have a descriptor to carry.
6. The logging owner and sanitized diagnostic vocabulary for fail-closed live
   publication failure are not yet represented in production code.
7. No PR 02-specific concurrency, failure-injection, boundedness, or live
   projection fixture classes exist yet; the current tests listed above provide
   the underlying lifecycle and canonical behavior coverage.
