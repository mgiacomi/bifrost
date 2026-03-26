# ENG-025 Execution Trace Implementation Plan

## Overview

Replace the current in-memory, journal-first observability model with a first-class `ExecutionTrace` subsystem that streams append-only NDJSON trace records to temp storage during session execution. The trace becomes the canonical runtime record, while `ExecutionJournal` is rebuilt as a sanitized derived projection and `SkillThoughtTrace` is removed.

## Current State Analysis

The current implementation centralizes execution observability inside `BifrostSession`, which directly owns the canonical `ExecutionJournal`, current frame stack, plan state, and last advisor outcomes (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:22`). Runtime services mostly write through `ExecutionStateService`, but those writes still terminate in heap-backed journal entries rather than a dedicated trace subsystem (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:19`).

Planning and mission execution each make model calls, but neither path records model request and response artifacts as first-class runtime records today (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:78`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:61`). Tool execution records only coarse tool call/result journal events, and advisors record validation outcomes without explicit request/response mutation trace records (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:71`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java:43`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java:46`).

## Desired End State

Each `BifrostSession` owns an `ExecutionTraceHandle` rather than a canonical in-memory journal. Framework internals append immutable `TraceRecord`s through a recorder API, active traces stream to `java.io.tmpdir/<sessionId>.execution-trace.ndjson`, and a reader/projector layer derives sanitized `ExecutionJournal` views and any future narrative projections. `ExecutionTrace` is the canonical runtime artifact and architectural center of the system; `ExecutionJournal` remains available only as a derived developer-facing projection, not as the primary session API.

The completed implementation should let us:

- reconstruct frame lifecycle and graph relationships from append-only trace records
- inspect unsanitized model, advisor, planning, tool, validation, and error artifacts in sequence order
- keep large payloads out of heap growth by chunking them inline in NDJSON
- retain or delete completed trace files according to `execution-trace.persistence`, with `onerror` as the default behavior
- remove `SkillThoughtTrace` and related mapping infrastructure without leaving journal-first code paths behind

### Key Discoveries:

- `BifrostSession` currently owns the canonical `ExecutionJournal` and uses the active frame to stamp `frameId` and `route` on appended entries (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:32`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:338`).
- `DefaultExecutionStateService` is already the main runtime boundary for frame lifecycle, plan lifecycle, tool logging, linter outcomes, output-schema outcomes, and errors, so it is the cleanest initial insertion point for a recorder-backed trace API (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:31`).
- Planning, mission execution, tool callbacks, and advisor chains are already separated into distinct services, which gives us natural instrumentation seams for planning frames, model frames, tool frames, and advisor mutation records (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:34`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:29`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:20`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java:15`).
- [ExecutionCoordinator.java](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\ExecutionCoordinator.java) is the clearest current orchestration seam for explicit trace finalization because it already opens the mission frame, runs the session-scoped work, and closes the frame in `finally`, while [DefaultMissionExecutionEngine.java](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\runtime\DefaultMissionExecutionEngine.java) is the right ownership seam for mission-time frame cleanup.

## What We're NOT Doing

- Implementing replay or playback tooling
- Supporting session restoring, trace rehydration, or completed-session readback inside the runtime framework
- Treating `BifrostSession` itself as a deserializable Jackson DTO rather than a live runtime aggregate
- Introducing sidecar payload attachments or alternate storage formats in v1
- Preserving backward-compatible journal payload shapes or `SkillThoughtTrace` internals
- Adding non-privileged access to raw unsanitized trace data
- Optimizing random-access indexing beyond lightweight reader support needed for v1 projections

## Implementation Approach

Build the trace subsystem in four layers:

1. Introduce trace domain types, writer/reader infrastructure, temp-file lifecycle, and persistence policy configuration.
2. Introduce an explicit trace finalization seam in mission coordination so completion metadata and retention behavior are deterministic.
3. Move session ownership from canonical journal state to trace-handle state while keeping `BifrostSession` as the aggregate and lock boundary.
4. Instrument the existing planning, mission, tool, chat, and advisor seams to emit rich trace records without changing their high-level responsibilities.
5. Rebuild `ExecutionJournal` as a projection over trace records, remove `SkillThoughtTrace`, simplify projection payloads around the new trace model, and update tests plus any developer-facing session/debug surfaces.

This order minimizes churn by establishing the append/read primitives before replacing current journal-centric behavior.

## Phase 1: Establish Trace Domain and Storage

### Overview

Create the foundational trace model, append/read APIs, NDJSON storage implementation, and retention policy wiring.

### Changes Required:

#### 1. Add new core trace types
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionTrace.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionTraceHandle.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionTraceRecorder.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionTraceReader.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/TraceRecord.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/TraceRecordType.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/TraceFrameType.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/TracePersistencePolicy.java`
**Changes**: Define the envelope, typed metadata, and raw `data` payload shape described in the ticket. Include sequence, timestamps, session/trace ids, frame identity, record type, and enough metadata fields to support planning, tool, advisor, and model flows without revisiting the schema immediately.

```java
public record TraceRecord(
        int schemaVersion,
        String traceId,
        String sessionId,
        long sequence,
        Instant timestamp,
        TraceRecordType recordType,
        @Nullable String frameId,
        @Nullable String parentFrameId,
        @Nullable TraceFrameType frameType,
        @Nullable String route,
        String threadName,
        Map<String, Object> metadata,
        @Nullable JsonNode data) {
}
```

#### 2. Implement NDJSON writer/reader and temp-file lifecycle
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/NdjsonTraceRecordWriter.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/NdjsonExecutionTraceReader.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/DefaultExecutionTraceHandle.java`
**Changes**: Stream active records to `java.io.tmpdir` using `<sessionId>.execution-trace.ndjson`, maintain a monotonic sequence counter, keep only lightweight frame/index state in memory, and support chunked inline payload appends for large logical bodies.

#### 3. Add configuration for persistence policy
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostProperties.java`
- related auto-configuration classes under `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/`
**Changes**: Add `execution-trace.persistence` with `never`, `onerror`, and `always`, default it to `onerror`, and wire it into trace-handle teardown behavior.

### Success Criteria:

#### Automated Verification:
- [x] Starter module compiles with the new trace types: `./mvnw -pl bifrost-spring-boot-starter -DskipTests compile`
- [x] Trace writer/reader unit tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*Trace* test`
- [x] Full starter test suite remains green: `./mvnw -pl bifrost-spring-boot-starter test`
- [x] No dedicated lint/format command exists in the repo; compile and test stay clean without new warnings requiring additional tooling changes

#### Manual Verification:
- [ ] Starting a traced session creates a temp NDJSON file with the session id in the filename
- [ ] Appending large payloads emits ordered chunk records without retaining the full payload graph in heap-backed session state
- [ ] Switching `execution-trace.persistence` between `never`, `onerror`, and `always` changes post-session file retention as expected
- [ ] The raw NDJSON file is readable with ordinary text tooling and preserves append order

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Add Explicit Trace Finalization and Thin Session Ownership

### Overview

Add an explicit trace finalization step around mission coordination, then replace journal-first session ownership with trace-handle ownership without turning this ticket into a broad `BifrostSession` redesign.

### Changes Required:

#### 1. Add coordinator-level trace finalization
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/ExecutionStateService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java`
**Changes**: Introduce an explicit finalization step around the mission execution flow so `TRACE_COMPLETED`, terminal metadata, and `execution-trace.persistence` retention/deletion decisions happen in one visible orchestration seam rather than being inferred from thread-local session cleanup. Keep coordinator ownership focused on terminal trace finalization, while mission-time frame cleanup belongs to the mission execution layer.

#### 2. Refactor `BifrostSession` to own trace state
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
**Changes**: Remove the canonical `ExecutionJournal` field and the `SkillThoughtTrace` helper path, add `ExecutionTraceHandle` ownership, preserve locking semantics, and keep only session-aggregate responsibilities plus accessors needed for active frame stack, canonical trace snapshot, plan snapshot, validation outcomes, usage, and authentication. Keep the journal as an explicit derived projection, not the primary runtime/session-facing surface. `BifrostSession` remains a live runtime object rather than a supported deserializable/restored session DTO; any serialized observability surface should be trace-first or use dedicated response objects. If the edit grows noisy, extract targeted helpers such as frame-stack or trace-state support classes rather than broadening the refactor into unrelated session concerns.

#### 3. Redefine the frame model for trace reconstruction
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionFrame.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/OperationType.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/ExecutionStateService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java`
**Changes**: Keep `ExecutionFrame` as the smaller runtime execution-control object and map it into `TraceFrameType` plus frame metadata when appending records. Have frame open/close paths emit `FRAME_OPENED`, `FRAME_METADATA_RECORDED`, and `FRAME_CLOSED` records instead of relying on implicit in-memory mutation history.

```java
ExecutionFrame frame = new ExecutionFrame(
        frameId,
        parentFrameId,
        OperationType.SKILL,
        route,
        parameters,
        openedAt);
traceRecorder.appendFrameOpened(session, frame, TraceFrameType.SKILL_EXECUTION);
traceRecorder.appendFrameMetadata(session, frame, Map.of("parameters", parameters));
```

#### 4. Track trace error disposition in session lifecycle
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java`
**Changes**: Mark the trace as errored when linter validation exhausts, output-schema validation exhausts, or runtime exceptions are recorded, then emit terminal metadata and `TRACE_COMPLETED` from the coordinator-managed finalization path before file deletion/retention decisions are applied.

### Success Criteria:

#### Automated Verification:
- [x] Session/state tests covering frame open/close and plan snapshot behavior pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*Session*,*ExecutionState* test`
- [x] Coordinator/finalization tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*Coordinator*,*ExecutionState* test`
- [x] Starter module compiles after session API changes: `./mvnw -pl bifrost-spring-boot-starter -DskipTests compile`
- [x] Full starter test suite remains green: `./mvnw -pl bifrost-spring-boot-starter test`
- [x] Root build still passes across modules: `./mvnw test`

#### Manual Verification:
- [ ] Opening and closing nested mission frames yields a reconstructable parent/child frame graph in the trace
- [ ] Mission completion writes a terminal trace record before retention or deletion happens
- [ ] Sessions that encounter no tracked errors delete or retain the temp trace according to policy
- [ ] Sessions that hit a linter failure, output-schema exhaustion, or thrown exception are flagged as errored before teardown
- [ ] The session object no longer retains a growing in-memory journal payload history

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Instrument Planning, Model, Tool, and Advisor Flows

### Overview

Capture the runtime seams called out in ENG-025 so the trace can reconstruct actual session behavior end to end.

### Changes Required:

#### 1. Add planning and mission model-call trace instrumentation
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java`
**Changes**: Create planning/model frames as appropriate and record `MODEL_REQUEST_PREPARED`, `MODEL_REQUEST_SENT`, `MODEL_RESPONSE_RECEIVED`, `PLAN_CREATED`, and `PLAN_UPDATED` records, including prompt body, response body, provider/model metadata, usage-relevant details, and retries where applicable. Ensure mission-time frame cleanup and timeout unwinding live in the mission execution layer rather than being split across coordinator and mission code.

#### 2. Centralize chat/advisor model metadata capture
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java`
**Changes**: Wrap or decorate produced chat clients and/or advisor chains so model traffic can be recorded consistently in one place, and ensure provider/model metadata from the selected chat options is attached to model frames and records.

#### 3. Expand tool and advisor tracing
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java`
**Changes**: Emit explicit `TOOL_CALL_REQUESTED`, `TOOL_CALL_STARTED`, `TOOL_CALL_COMPLETED`, and `TOOL_CALL_FAILED` records, capture raw arguments/results, and add `ADVISOR_REQUEST_MUTATION_RECORDED`, `ADVISOR_RESPONSE_MUTATION_RECORDED`, `LINTER_RECORDED`, `STRUCTURED_OUTPUT_RECORDED`, and retry/error records for validation loops.

### Success Criteria:

#### Automated Verification:
- [x] Planning and mission execution tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*Planning*,*MissionExecution* test`
- [x] Tool and advisor tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*Tool*,*Linter*,*OutputSchema* test`
- [x] Full starter test suite remains green: `./mvnw -pl bifrost-spring-boot-starter test`
- [x] Root build still passes across modules: `./mvnw test`

#### Manual Verification:
- [ ] A planning-enabled mission produces distinct planning and mission model-call trace segments
- [ ] Tool success and failure paths create the expected requested/started/completed or failed record sequence
- [ ] Linter retries and output-schema retries show both the mutation detail and final outcome in the trace
- [ ] Provider/model metadata are visible in raw trace output for privileged inspection

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 4: Rebuild Journal Projection and Remove Legacy Thought Tracing

### Overview

Make `ExecutionJournal` a sanitized derived view over `ExecutionTrace`, remove `SkillThoughtTrace`, and align developer-facing surfaces and tests with the new architecture.

### Changes Required:

#### 1. Implement journal projection from trace
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionJournal.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/JournalEntry.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/JournalEntryType.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/ExecutionJournalProjector.java`
**Changes**: Project high-value developer-facing entries from the raw trace while sanitizing sensitive data and keeping the journal readable rather than replay-complete. Do not preserve legacy payload shapes solely for compatibility; the projection should reflect the new trace-first model cleanly.

#### 2. Remove `SkillThoughtTrace` and its mapper
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillThoughtTrace.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillThoughtMapper.java`
- all usages discovered via search during implementation
**Changes**: Delete the redundant route-based thought projection and migrate any remaining callers to trace-derived journal or other trace readers. No compatibility shim should remain once the migration is complete.

#### 3. Update developer-facing endpoints, samples, and tests
**Files**:
- session/debug/sample surfaces identified during implementation
- impacted tests under `bifrost-spring-boot-starter/src/test/java/`
- sample app tests under `bifrost-sample/` if they assert prior journal/thought behavior
**Changes**: Ensure exposed observability surfaces read from canonical trace plus explicit journal projection where helpful, update serialization expectations so `executionTrace` is the primary surfaced artifact, and use dedicated response payloads rather than treating `BifrostSession` itself as a round-trippable serialized contract.

### Success Criteria:

#### Automated Verification:
- [x] Journal projection and legacy-removal tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*Journal*,*Trace*,*SkillThought* test`
- [x] Full starter test suite remains green: `./mvnw -pl bifrost-spring-boot-starter test`
- [x] Root build and sample module tests remain green: `./mvnw test`
- [x] No dedicated lint/format command exists in the repo; no new quality gate regressions are introduced beyond compile/test coverage

#### Manual Verification:
- [ ] Developer-facing session inspection returns a readable sanitized journal derived from the trace
- [ ] No runtime path still depends on `SkillThoughtTrace`
- [ ] Raw privileged trace output remains unsanitized while the projected journal omits or summarizes sensitive payload details
- [ ] A retained trace from an errored session can be reopened after completion and still project the expected journal view

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

## Testing Strategy

### Unit Tests:

- Trace record serialization/deserialization and NDJSON append ordering
- Frame reconstruction from `FRAME_OPENED`, metadata, and `FRAME_CLOSED` records
- Chunked payload reconstruction by `payloadId` and `chunkIndex`
- Persistence behavior for `never`, `onerror`, and `always`
- Error flagging when linter validation exhausts, output-schema validation exhausts, or runtime exceptions are logged
- Journal projection and sanitization rules for plan, tool, warning, and error narratives
- Thought capture is intentionally deferred for the MVP and should not be treated as missing coverage in this plan

### Integration Tests:

- Planning-enabled mission flow with both planning and execution model calls
- Tool invocation success and failure flows linked to plan tasks
- Advisor retry loops producing request mutation, response mutation, and terminal outcome records
- Session teardown retaining or deleting temp trace files according to configured policy
- Coordinator-driven finalization writing terminal trace metadata before retention decisions are applied

**Note**: Prefer a dedicated testing plan artifact created via `3_testing_plan.md` for full details, especially around failing tests first, exact test classes to add, and the final exit criteria for the refactor.

### Manual Testing Steps:

1. Run a normal mission with planning enabled and inspect the temp NDJSON file while the session is active.
2. Run a mission that triggers at least one tool call and confirm the trace contains frame, tool, and response sequencing that matches the observed behavior.
3. Run one mission that triggers linter or output-schema exhaustion and another that succeeds cleanly, then verify the resulting file-retention behavior under each persistence mode.
4. Inspect the developer-facing journal/debug surface and confirm it reads as a sanitized projection over the raw trace rather than exposing the unsanitized payloads directly.

## Performance Considerations

- Keep the canonical trace append-only and file-backed so prompts, responses, and tool payloads do not accumulate unboundedly in heap memory.
- Avoid eager full-file materialization in readers; use streaming projection for active traces and only add lightweight indexes needed to support phase-4 projections.
- Keep chunking thresholds configurable in code structure even if the first public property only covers persistence.

## Migration Notes

- Breaking API changes are expected and acceptable for this ticket, so prefer removing journal-first and `SkillThoughtTrace` internals outright rather than layering compatibility shims.
- Thin `BifrostSession` by extraction only where ENG-025 needs it; do not broaden the ticket into a general session redesign.
- Update any developer-facing JSON fixtures or debug responses so `executionTrace` is the canonical serialized observability artifact and `executionJournal` is treated as optional derived output rather than embedded session state; do not treat `BifrostSession` deserialization as a supported runtime contract.
- If debug/sample endpoints currently serialize `SkillThoughtTrace` or direct journal snapshots, move them to trace-first session inspection plus optional journal projection before deleting legacy types.

## References

- Original ticket: `ai/thoughts/tickets/eng-025-execution-trace.md`
- Related research: `ai/thoughts/research/2026-03-24-ENG-025-execution-trace.md`
- Canonical journal ownership today: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:32`
- Current orchestration seam for mission lifecycle: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:49`
- State service write boundary: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:31`
- Planning flow: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:78`
- Mission execution flow: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:61`
- Tool execution flow: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:71`
- Advisor wiring: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java:34`
