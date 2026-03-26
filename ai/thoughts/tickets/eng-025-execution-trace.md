# ENG-025: Replace Journaling Internals With First-Class ExecutionTrace Capture

## Status

Draft

## Summary

Introduce a new first-class execution capture subsystem centered on `ExecutionTrace`, an unsanitized, append-only runtime trace of Bifrost session activity.

`ExecutionTrace` becomes the canonical runtime record of what happened during a session. It is designed to support:

- full-fidelity deep tracing for privileged users
- derived readable views for app developers
- large-payload capture without retaining the entire trace in heap memory

This ticket also repositions `ExecutionJournal` as a derived app-facing view over `ExecutionTrace` and removes `SkillThoughtTrace` entirely.

For the MVP, provider-native thought artifacts are explicitly out of scope for capture. The trace should preserve model request/response behavior and framework-visible execution events, but it does not need to persist vendor thought streams yet.

Because Bifrost is still in development and no external compatibility commitments exist yet, breaking API and model changes are intentional.

## Motivation

The current observability model provides some value via `ExecutionJournal` and `SkillThoughtTrace`, but it does not capture enough information to support:

- frame-by-frame inspection of framework behavior
- complete model request/response visibility
- advisor mutation visibility
- comprehensive tool/model/planning/retry reconstruction
- future offline analysis tooling outside the runtime framework

Deep tracing is not a bolt-on debugging concern for Bifrost. It is a core framework capability and one of the highest-value features of the framework.

## Current State

Today, Bifrost stores an in-memory `ExecutionJournal` in `BifrostSession` and appends a limited set of journal entries for:

- thoughts
- plan creation and updates
- linter outcomes
- structured output outcomes
- tool calls and results
- errors

`SkillThoughtTrace` is currently a projection over selected journal entries filtered by route.

Current limitations:

- the journal is not rich enough to reconstruct execution in full
- model requests and responses are not first-class captured artifacts
- advisor request/response mutations are not first-class captured artifacts
- frame lifecycle is only partially observable
- large payload strategy is undefined
- current observability is biased toward curated narration rather than replay fidelity

## Decisions Locked In

The following architectural decisions are in scope for this ticket and should be treated as resolved unless implementation uncovers a material blocker:

- `ExecutionTrace` is the primary source of truth
- `ExecutionJournal` becomes a derived readable and sanitized view
- `SkillThoughtTrace` is removed
- deep trace is unsanitized and privileged by design
- trace capture and persistence are separate concerns
- trace data is append-only and immutable after append
- physical trace storage uses newline-delimited JSON records
- multiple `TraceRecord`s may contribute to one logical frame
- large payloads remain inline via chunk records in v1
- active trace data is streamed to temp storage during the session
- temp trace files live in Java's default temp directory
- temp trace files are named by `sessionId` so developers can easily locate them
- persistence policy is controlled by `execution-trace.persistence`
- supported initial persistence values are `never`, `onerror`, and `always`
- `onerror` means any linter failure, structured output failure, or thrown exception
- `BifrostSession` should be thinned as part of this work, but only by extracting trace-specific responsibilities and related runtime mechanics needed for trace capture
- `ExecutionFrame` remains a runtime execution-control model and should not be forced to mirror the full trace taxonomy one-to-one
- session trace finalization should become an explicit orchestration step near mission coordination rather than remaining implicit thread-local cleanup

## Goals

- capture everything needed to reconstruct transpired events and data
- support privileged deep inspection without sanitization
- enable efficient live and post-run projection into developer-facing views
- avoid unbounded in-memory growth for large payloads
- establish a trace model suitable for future offline analysis tooling outside the runtime framework

## Non-Goals

- implementing replay in this ticket
- supporting playback, rehydration, or session restoring inside the runtime framework
- making `BifrostSession` a serialized/restored DTO surface; it remains a live runtime aggregate
- introducing attachment or sidecar file storage in v1
- preserving backward compatibility with current journal and trace internals
- exposing unsanitized deep trace to non-privileged consumers
- capturing provider-native or model-thought artifacts in the MVP trace

## New Core Concepts

### ExecutionTrace

`ExecutionTrace` is the canonical logical trace for one session.

Responsibilities:

- own session-level trace identity and metadata
- define the logical frame graph
- define the ordered append-only record stream
- support reconstruction of full execution history
- back all derived observability views

`ExecutionTrace` is a logical object, not necessarily a fully materialized in-memory graph.

### TraceRecord

`TraceRecord` is the append-only physical and logical record unit in the trace stream.

A `TraceRecord` may describe one piece of trace information, such as:

- frame opened
- frame metadata recorded
- payload chunk appended
- frame closed
- error recorded

A frame may be described by multiple records.

### ExecutionFrame

`ExecutionFrame` remains the runtime execution scope object used for live control flow and stack management.

Trace frames are reconstructed from `TraceRecord`s and may be richer than the runtime frame object. The runtime frame model should stay intentionally smaller unless a trace requirement clearly needs additional live state.

### ExecutionJournal

`ExecutionJournal` remains as a framework concept, but its responsibility changes.

It becomes:

- readable
- reduced
- sanitized
- app-developer-oriented
- derived from `ExecutionTrace`

It is no longer the canonical storage mechanism.

### ExecutionTraceRecorder

Runtime append API used by framework internals to write `TraceRecord`s to the active trace stream.

### ExecutionTraceReader

Streaming and read API used to derive views from an in-flight or completed trace.

### ExecutionTraceHandle

Session-owned runtime handle that coordinates:

- trace metadata
- output stream and file lifecycle
- sequence generation
- lightweight indexes
- persistence disposition at session end

## Trace Capture Contract

`ExecutionTrace` must capture at minimum:

- session lifecycle metadata
- frame lifecycle
- frame graph relationships
- planning requests, responses, and transitions
- model request prepared
- model request sent
- advisor request mutations
- model response received
- advisor response mutations
- tool call requested
- tool call started
- tool call completed
- tool call failed
- linter outcomes
- structured output outcomes
- usage and guardrail-relevant traceable actions
- errors and exceptions
- persistence and capture policy state active during the run

The trace should be rich enough to support future offline analysis tooling without requiring a second instrumentation pass, but the runtime framework itself does not need to reopen, rehydrate, or replay completed sessions.

## Frame Model

Frames are first-class nodes with graph identity and lifecycle.

Each frame should be reconstructable with at least:

- `frameId`
- `parentFrameId`
- `frameType`
- `route`
- `openedAt`
- `closedAt`
- `status`
- frame-specific metadata

Initial frame taxonomy:

- `ROOT_MISSION`
- `SKILL_EXECUTION`
- `MODEL_CALL`
- `TOOL_INVOCATION`
- `RETRY`
- `PLANNING`

A frame's lifecycle is described by records, not by in-place mutation.

The runtime `ExecutionFrame` and the reconstructed trace frame model do not need to be identical types. The preferred v1 design is:

- keep `ExecutionFrame` focused on runtime execution bookkeeping
- map runtime frames into `TraceFrameType` and frame metadata when appending trace records
- avoid pushing every trace-specific concern back into the runtime stack object unless implementation proves it is necessary

## Record Model

The NDJSON stream is composed of `TraceRecord`s.

Each record has three conceptual layers.

### 1. Envelope Layer

Fields common to all records.

Initial schema fields:

- `schemaVersion`
- `traceId`
- `sessionId`
- `sequence`
- `timestamp`
- `recordType`
- `frameId`
- `parentFrameId`
- `frameType`
- `route`
- `threadName`

### 2. Typed Metadata Layer

Structured metadata depending on record type.

Initial candidate fields:

- `provider`
- `providerModel`
- `advisorName`
- `advisorType`
- `capabilityName`
- `operationType`
- `retryAttempt`
- `maxRetries`
- `linkedTaskId`
- `planId`
- `status`
- `errorKind`
- `exceptionType`
- `contentType`
- `chunkIndex`
- `chunkCount`
- `payloadId`
- `payloadRole`

### 3. Data Layer

Raw structured payload for the record.

Examples:

- prompt body
- response body
- tool arguments
- tool result
- linter detail
- structured output validation detail
- exception payload
- provider-native artifacts other than thought streams when needed for response/tool reconstruction

Suggested top-level field name for the raw payload body is `data`.

## Initial Record Taxonomy

Initial `recordType` values:

- `TRACE_STARTED`
- `TRACE_CAPTURE_POLICY_RECORDED`
- `FRAME_OPENED`
- `FRAME_METADATA_RECORDED`
- `PAYLOAD_CHUNK_APPENDED`
- `MODEL_REQUEST_PREPARED`
- `MODEL_REQUEST_SENT`
- `ADVISOR_REQUEST_MUTATION_RECORDED`
- `MODEL_RESPONSE_RECEIVED`
- `ADVISOR_RESPONSE_MUTATION_RECORDED`
- `PLAN_CREATED`
- `PLAN_UPDATED`
- `TOOL_CALL_REQUESTED`
- `TOOL_CALL_STARTED`
- `TOOL_CALL_COMPLETED`
- `TOOL_CALL_FAILED`
- `LINTER_RECORDED`
- `STRUCTURED_OUTPUT_RECORDED`
- `ERROR_RECORDED`
- `FRAME_CLOSED`
- `TRACE_COMPLETED`

This taxonomy is intentionally broad enough to cover current Bifrost behavior while remaining extensible. Thought-specific record types can be added later once we decide to persist provider-native thought streams.

## Storage Strategy

### In-Flight Storage

During an active session:

- trace records are appended to an NDJSON file in Java's default temp directory
- file name is session-based so it is easy for developers to locate and inspect
- only lightweight indexes and active state remain in memory

Recommended initial filename shape:

- `<sessionId>.execution-trace.ndjson`

Optional timestamp prefixes or suffixes can be added later if needed, but the session id must remain obvious in the filename.

### In-Memory State

Keep only minimal runtime state in memory:

- trace and session metadata
- current frame stack
- monotonic sequence counter
- current file handle or output stream
- lightweight frame index
- optional record offsets for later optimization

Do not retain full payload history in heap by default.

### Large Payloads

For v1:

- keep payloads inline in the NDJSON stream
- split large logical bodies into `PAYLOAD_CHUNK_APPENDED` records
- reconstruct full bodies by `payloadId` and ordered `chunkIndex`

This keeps the initial storage strategy simple while supporting large prompts, responses, and structured payloads without sidecar file complexity.

### Post-Session Persistence Policy

Property:

- `execution-trace.persistence`

Allowed values:

- `never`
- `onerror`
- `always`

Semantics:

- `never`: discard trace file at session completion
- `onerror`: retain trace file if the session encountered any linter failure, structured output failure, or thrown exception
- `always`: retain trace file unconditionally

## Capture Policy vs Persistence Policy

These are separate concerns and should remain separate in the design and implementation.

### Capture Policy

Initial design assumption:

- deep trace capture is always active while a session is alive

### Persistence Policy

Determines whether the temp trace file survives session teardown.

This separation ensures:

- derived views can be built while the session is active
- trace availability during execution does not depend on retention settings

## Sanitization Model

Deep trace is unsanitized and privileged.

Assumptions:

- anyone with access to deep trace is a privileged user
- secrets and raw provider or tool data may appear in deep trace
- sanitization must not occur in the canonical trace

Sanitized outputs are derived from `ExecutionTrace` on read and projection.

This ticket keeps sanitization in the projection layer only.

## ExecutionJournal Redefinition

`ExecutionJournal` is retained as the app-developer-oriented readable view over `ExecutionTrace`.

It should answer high-value app questions such as:

- what skill ran
- what plan was created or changed
- what tools ran and whether they succeeded
- what major warnings or errors occurred
- what concise narrative should be shown to the app developer

It should not attempt to preserve playback or replay fidelity inside the runtime framework.

## SkillThoughtTrace Removal

`SkillThoughtTrace` should be removed.

Rationale:

- it is redundant once `ExecutionJournal` and future trace-derived views exist
- it is not rich enough for deep trace
- it duplicates projection responsibility that should now belong to trace readers and projectors

Any useful aspects of the current `SkillThoughtTrace` output should be reincorporated into:

- `ExecutionJournal` projection rules, or
- a future narrative or debug projection derived from `ExecutionTrace`

## Runtime Integration Points

Based on the current codebase, initial implementation should instrument these seams.

### Session and State

Current relevant classes:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`

Expected changes:

- replace embedded canonical journal ownership with trace handle ownership
- keep `BifrostSession` as the session aggregate and lock boundary, but extract trace-specific implementation concerns into `ExecutionTraceHandle` and helper components as needed
- keep `BifrostSession` runtime-only; serialized or developer-facing observability should flow through `ExecutionTrace`, `ExecutionJournal`, or dedicated response DTOs instead of session rehydration
- append frame lifecycle and state records through `ExecutionTraceRecorder`
- add explicit trace/session finalization around the coordinator-owned mission execution flow so `TRACE_COMPLETED` and persistence decisions happen in one clear place

### Mission Execution

Current relevant class:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java`

Expected changes:

- record model request preparation and sending
- record model response receipt
- create model and planning frames as appropriate

### Planning

Current relevant class:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java`

Expected changes:

- planning becomes fully trace-backed
- planning prompt and response details captured in planning and model frames
- plan creation and update records remain first-class

### Tool Execution

Current relevant class:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java`

Expected changes:

- create tool invocation frames
- distinguish requested, started, completed, and failed records
- capture raw arguments and results in the trace

### Chat Client Creation

Current relevant class:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java`

Expected changes:

- wrap produced chat clients or advisor chains so model traffic can be recorded centrally
- record provider and model metadata on model frames

### Advisors

Current relevant classes:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java`

Expected changes:

- record request mutation and retry records
- record response validation outcomes
- contribute error disposition for `onerror` persistence

## Configuration

Introduce new properties under an `execution-trace` namespace.

Initial property:

- `execution-trace.persistence`

Values:

- `never`
- `onerror`
- `always`

Potential future properties, not required in this ticket:

- chunk size threshold
- file naming customization
- temp directory override
- reader or index tuning

## Error Semantics for onerror

A session is considered errored for persistence purposes if any of the following occur:

- linter failure
- structured output failure
- thrown exception

These conditions should be tracked during execution and written into final trace metadata before teardown.

The preferred place to apply the final persistence decision is an explicit session finalization seam in the mission coordination flow, not passive thread-local cleanup.

## API and Model Changes

Breaking changes expected:

- remove `SkillThoughtTrace`
- remove `SkillThoughtMapper`
- change `BifrostSession` to own or access `ExecutionTrace` instead of canonical `ExecutionJournal`
- redefine `ExecutionJournal` as derived output, not canonical storage
- add trace recorder, reader, and handle abstractions
- add configuration for trace persistence
- narrow `BifrostSession` responsibilities by extracting trace implementation details without turning this ticket into a broad session redesign

## Proposed New Types

Indicative initial types:

- `ExecutionTrace`
- `ExecutionTraceHandle`
- `ExecutionTraceRecorder`
- `ExecutionTraceReader`
- `TraceRecord`
- `TraceRecordType`
- `TraceFrameType`
- `TracePersistencePolicy`

Possible support types:

- `TracePayloadChunk`
- `TraceFrameSnapshot`
- `ExecutionJournalProjector`
- `TraceRecordWriter`
- `TraceRecordParser`

## Migration Strategy

Because breaking changes are allowed, migration should prioritize clean architecture over compatibility shims.

Recommended implementation order:

1. add new trace abstractions and NDJSON writer and reader
2. introduce explicit trace finalization around mission coordination
3. update session and state ownership to use a trace handle while keeping `BifrostSession` as the aggregate boundary
4. instrument frame lifecycle using runtime-frame-to-trace mapping
5. instrument model, planning, tool, and advisor flows
6. reimplement `ExecutionJournal` as a projector
7. remove `SkillThoughtTrace`
8. update sample and debug endpoints to expose journal derived from trace
9. update tests around trace and journal projection

## Testing Strategy

Add and or replace tests to cover:

- record append ordering via monotonic `sequence`
- frame open and close graph reconstruction
- NDJSON trace file emission to temp dir
- chunked inline payload reconstruction
- `never`, `onerror`, and `always` persistence behavior
- `ExecutionJournal` derivation from trace
- linter, structured output, and exception conditions marking a trace as errored
- session teardown deleting or retaining temp files correctly
- coordinator-driven trace finalization writing terminal metadata before retention or deletion
- reading active and completed trace files

## Future Work Enabled By This Ticket

- offline analysis/debug module as a third Maven module
- richer narrative projections
- graphical trace viewers
- live tailing and debug tooling
- selective query and index optimization
- optional attachment spillover if chunked inline payloads become insufficient

## Follow-Up Questions

These do not block implementation of this ticket, but should be revisited as the subsystem matures:

- optimize random-access indexes if streaming projection becomes too expensive
- decide whether raw provider artifacts need additional normalization helpers
- evolve record taxonomy as new framework features appear
- evaluate whether certain high-volume payloads eventually warrant attachment storage

## Acceptance Criteria

- a new `ExecutionTrace` subsystem exists and is the canonical session execution capture mechanism
- trace capture is append-only and immutable after append
- active traces are streamed to NDJSON files in Java temp storage
- `execution-trace.persistence` governs post-session file retention
- trace completion and persistence decisions occur through an explicit coordinator-level finalization step
- `ExecutionJournal` is derived from `ExecutionTrace`
- `SkillThoughtTrace` and related mapping infrastructure are removed
- trace capture includes frame lifecycle, model interactions, planning, tool execution, validation outcomes, and errors
- initial tests cover trace writing, reading, projection, and persistence behavior
