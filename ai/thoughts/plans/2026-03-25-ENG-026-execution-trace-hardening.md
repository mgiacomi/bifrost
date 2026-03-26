# ENG-026 Execution Trace Hardening Implementation Plan

## Overview

Harden the `ExecutionTrace` subsystem introduced by ENG-025 so trace semantics are defined once, written through approved boundaries only, and validated as the canonical runtime record across mission, planning, tool, advisor, readback, finalization, retention, and post-run snapshot/projection flows.

This work is not a feature expansion. It is a contract-and-boundary hardening pass that reduces semantic drift, closes lifecycle correctness gaps, and makes trace behavior easier to reason about and harder to regress.

ENG-026 assumes framework-managed session ids are globally unique and non-reusable. Handling duplicated session ids is out of scope for this ticket.

## Current State Analysis

The trace subsystem is already structurally close to what ENG-026 wants, but semantic ownership is still spread across multiple runtime layers:

- `BifrostSession` is still the low-level append surface for model thought, tool, plan, linter, output-schema, error, projection, and finalization behavior ([BifrostSession.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java#L140), [BifrostSession.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java#L375), [BifrostSession.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java#L510), [BifrostSession.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java#L528)).
- `DefaultExecutionStateService` owns frame lifecycle and some routing rules, but it still switches on record type and delegates to a mix of session helpers that embed trace semantics locally ([DefaultExecutionStateService.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java#L43), [DefaultExecutionStateService.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java#L154), [DefaultExecutionStateService.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java#L200)).
- Planning and mission paths emit model trace records directly, which means model-call semantics still depend on the calling flow ([DefaultPlanningService.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java#L121), [DefaultMissionExecutionEngine.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java#L103)).
- Tool and advisor paths still shape trace behavior at feature seams rather than through one canonical recorder contract ([DefaultToolCallbackFactory.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java#L78), [LinterCallAdvisor.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java#L52), [OutputSchemaCallAdvisor.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java#L53)).
- Finalization, append barriers, chunking, and retention are enforced in `DefaultExecutionTraceHandle`, but the lifecycle contract is not yet documented or protected by cross-flow tests ([DefaultExecutionTraceHandle.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/DefaultExecutionTraceHandle.java#L130), [DefaultExecutionTraceHandle.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/DefaultExecutionTraceHandle.java#L162), [DefaultExecutionTraceHandle.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/DefaultExecutionTraceHandle.java#L205)).
- Active reads already try to tolerate in-flight chunked payloads, but the reader contract is subtle and needs contract-level verification instead of just implementation-local coverage ([NdjsonExecutionTraceReader.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/NdjsonExecutionTraceReader.java#L28), [NdjsonExecutionTraceReader.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/NdjsonExecutionTraceReader.java#L88), [NdjsonExecutionTraceReader.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/NdjsonExecutionTraceReader.java#L126)).
- Property naming and persistence behavior are part of the subsystem contract, and that contract currently hangs off the top-level `execution-trace` prefix ([ExecutionTraceProperties.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/ExecutionTraceProperties.java#L9)).

## Desired End State

`ExecutionTrace` is a documented, contract-driven subsystem with a narrow set of approved writers. Runtime flows no longer decide trace taxonomy or sequencing ad hoc. Instead, they call a small recorder API that enforces frame linkage, record ordering, terminal lifecycle rules, and readback/finalization semantics consistently across planning, mission, tool, and advisor execution.

After this plan is complete:

- a single trace contract artifact defines allowed writers, record taxonomy, frame lifecycle, model/tool/advisor sequencing, readback guarantees, finalization rules, retention behavior, and post-run snapshot/projection behavior
- raw trace records alone are sufficient to reconstruct representative mission, planning, tool, and advisor frame graphs and event sequences
- active reads during chunked writes are deterministic and do not fail on valid in-flight traces
- finalized traces reject new writes consistently across all call paths
- trace retention and naming behavior are deterministic under repeated or concurrent runs
- `ExecutionJournal` remains readable as a derived projection without compensating for inconsistent raw writers

### Key Discoveries:

- `DefaultExecutionStateService` is already the best consolidation seam for frame lifecycle and trace-aware runtime operations, but it currently routes semantics by record type instead of exposing a narrower trace-recorder contract ([DefaultExecutionStateService.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java#L200)).
- `ExecutionCoordinator` is the visible top-level terminal seam today, which makes it the right place to preserve explicit finalization ownership while moving terminal semantics into a hardened contract ([ExecutionCoordinator.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java#L52), [ExecutionCoordinator.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java#L95)).
- The biggest remaining risk is not missing instrumentation alone; it is lifecycle correctness across append, partial reads, finalization, retention, and post-run snapshot/projection behavior ([DefaultExecutionTraceHandle.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/DefaultExecutionTraceHandle.java#L130), [NdjsonExecutionTraceReader.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/NdjsonExecutionTraceReader.java#L28)).

## What We're NOT Doing

- Adding new end-user observability features beyond ENG-025’s trace scope
- Implementing replay or timeline playback
- Replacing NDJSON storage unless hardening reveals a blocker that cannot be addressed within the current design
- Redesigning unrelated runtime abstractions outside the trace subsystem
- Expanding the persistence property surface beyond the naming and behavioral clarifications needed for a stable subsystem contract

## Implementation Approach

Treat ENG-026 as a contract-hardening refactor with four ordered goals:

1. Write down the trace contract and make it the source of truth for what may write, what each record means, and what lifecycle guarantees readers can depend on.
2. Narrow trace write ownership to canonical recorder boundaries so planning, mission, tool, and advisor code stop shaping trace semantics directly.
3. Harden lifecycle correctness around active reads, append barriers, retention, finalization, and post-run snapshot/projection behavior so the raw trace is trustworthy under real runtime conditions.
4. Add contract-focused tests that compare semantics across flows and lifecycle states instead of only checking local implementation details.

This sequence keeps the refactor grounded. We define the contract first, centralize behavior second, then lock in correctness with lifecycle and cross-flow tests.

### Boundary Decisions Locked For ENG-026

- There is exactly one authoritative boundary for model request/response trace semantics.
- Planning and mission code continue to own the higher-level orchestration context and the frames they conceptually own.
- Planning and mission code do not handcraft low-level model request/response trace sequencing if the authoritative boundary can own it reliably.
- The canonical recorder owns trace taxonomy, record emission rules, and semantic equivalence across call paths.
- Old direct model-trace write paths are removed rather than retained as fallback behavior.
- If the chat-client boundary cannot reliably own model tracing for every relevant path, the authoritative boundary must stay one layer higher and the implementation must make that explicit rather than pretending the chat-client seam is central.
- Advisors should not import or emit raw trace record taxonomy directly; they should publish structured facts/outcomes for the canonical recorder to translate.

### Default Boundary Choice

The default implementation target is to make the chat-client boundary the authoritative source of model request/response trace semantics, but only if it can cover every relevant model call path cleanly and consistently. If that cannot be achieved without exceptions or fallback paths, the authoritative boundary must remain one layer higher at the canonical recorder boundary instead.

### Boundary Cleanup Definition Of Done

- No planning or mission class emits `MODEL_*` trace records directly.
- No advisor class imports `TraceRecordType`.
- No feature-layer code emits raw trace taxonomy except through the approved canonical recorder boundary.
- The old direct model/advisor trace write paths are removed rather than left dormant as fallback code.

### Post-Run Snapshot/Projection Scope

For this plan, post-run snapshot/projection behavior includes serialized `BifrostSession` behavior, `ExecutionTrace` snapshot visibility, and derived `ExecutionJournal` projection behavior after completion.

## Phase 1: Define the Contract and Approved Writer Boundaries

### Overview

Document the execution-trace contract and create the explicit recorder boundary that all runtime writers must use.

### Changes Required:

#### 1. Add a trace contract artifact
**File**: [`ai/thoughts/specs/2026-03-25-ENG-026-execution-trace-contract.md`](/C:/opendev/code/bifrost/ai/thoughts/specs/2026-03-25-ENG-026-execution-trace-contract.md)
**Changes**: Create the canonical subsystem contract document linked from this plan before any boundary refactor begins. This artifact is a required first deliverable and the implementation should not proceed without it. Define:

- approved writer boundaries
- required record taxonomy and sequencing
- frame lifecycle invariants and parent-child linkage rules
- model, tool, advisor, error, and finalization semantics
- active-read guarantees during chunked writes
- finalization as an append barrier
- retention, deletion, naming, and post-run snapshot/projection rules

#### 2. Introduce a canonical trace-recorder API
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionTraceRecorder.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/DefaultExecutionTraceRecorder.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/ExecutionStateService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java`
**Changes**: Move trace-semantic ownership out of `BifrostSession` helper methods and out of `switch`-based per-type routing inside `DefaultExecutionStateService`. Replace generic record-type branching with explicit recorder operations such as frame opened/closed, model request/response, tool requested/started/completed/failed, advisor request/response mutation, error recorded, and trace finalized.

```java
public interface ExecutionTraceRecorder {
    void recordFrameOpened(BifrostSession session, ExecutionFrame frame);
    void recordModelRequest(BifrostSession session, ExecutionFrame frame, ModelTraceContext context, Object payload);
    void recordToolCompleted(BifrostSession session, ExecutionFrame frame, ToolTraceContext context, Object payload);
    void recordAdvisorMutation(BifrostSession session, AdvisorTraceContext context, TraceRecordType type, Object payload);
    void finalizeTrace(BifrostSession session, TraceCompletion completion);
}
```

#### 3. Reduce `BifrostSession` to trace-handle ownership and projection access
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
**Changes**: Keep `BifrostSession` as the aggregate, lock boundary, live handle owner, and projection surface, but remove trace-semantic helper methods that let feature paths define record meaning locally. Session should expose narrow append/finalization primitives needed by the recorder implementation, not a broad mix of domain-specific trace writers.

### Success Criteria:

#### Automated Verification:
- [x] Starter module compiles after recorder API introduction: `./mvnw -pl bifrost-spring-boot-starter -DskipTests compile`
- [x] Session/state/trace tests remain green after boundary refactor: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*Session*,*ExecutionState*,*Trace* test`
- [x] Full starter test suite remains green: `./mvnw -pl bifrost-spring-boot-starter test`

#### Manual Verification:
- [ ] The contract artifact exists and is linked from this plan
- [ ] Planning, mission, tool, and advisor code no longer append raw trace semantics through ad hoc `recordTrace(...)` or session helper patterns except through the approved recorder boundary
- [ ] The approved writer list is short enough to audit in one review pass

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Centralize Model, Tool, Advisor, and Frame Semantics

### Overview

Route all runtime flows through the new recorder boundary so equivalent logical events emit equivalent trace semantics regardless of call path.

### Changes Required:

#### 1. Move frame and plan semantics behind the recorder boundary
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionFrame.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/TraceFrameType.java`
**Changes**: Ensure frame open/close and frame metadata records are emitted from one place only, with consistent rules for route, frame type, parent frame id, timestamps, and close metadata. Keep frame reconstruction possible from raw records without depending on implicit session history.

#### 2. Move model trace semantics out of planning and mission feature code
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java`
**Changes**: Remove flow-specific `MODEL_REQUEST_PREPARED`, `MODEL_REQUEST_SENT`, and `MODEL_RESPONSE_RECEIVED` emission logic from planning and mission code. Replace it with a shared model-tracing boundary that can cover all chat-client usage consistently, including provider/model metadata and segment attribution. These legacy direct-write paths should be deleted, not retained as fallback helpers.

#### 3. Move tool and advisor semantics behind explicit recorder operations
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java`
**Changes**: Replace direct feature-local trace emission with recorder calls that enforce canonical tool sequencing and advisor mutation linkage. Preserve unplanned-tool and retry semantics, but make the recorder the only owner of how those events are represented in raw traces. The target end state is that advisor implementations no longer import or emit `TraceRecordType` directly; they should publish structured advisor outcomes or mutation facts and let the recorder translate them into canonical trace events.

#### 4. Remove superseded direct-write paths
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java`
**Changes**: Delete the old direct trace-writing behavior from these classes once the canonical recorder boundary is in place. The goal is to prevent semantic drift by removing parallel write paths, not by deprecating them in place.

### Success Criteria:

#### Automated Verification:
- [x] Planning and mission tracing tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*Planning*,*MissionExecution* test`
- [x] Tool and advisor tracing tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*Tool*,*Linter*,*OutputSchema* test`
- [x] Cross-flow trace semantics tests pass for equivalent events: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*ExecutionTraceContract* test`
- [x] Full starter test suite remains green: `./mvnw -pl bifrost-spring-boot-starter test`

#### Manual Verification:
- [ ] A planning model call and a mission model call record the same semantic envelope for equivalent events
- [ ] Tool success and failure paths emit the same requested/started/completed-or-failed sequencing regardless of whether the call is linked to a plan task
- [ ] Advisor retries and exhausted outcomes remain frame-aware and no longer bypass the approved writer boundary
- [ ] The deleted direct-write paths are not still present in planning, mission, or advisor code under alternate helper names

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Harden Lifecycle, Readback, Finalization, and Retention

### Overview

Make the raw trace safe to trust during active execution, after completion, and when producing post-run snapshots and derived views.

### Changes Required:

#### 1. Tighten append-barrier and terminal lifecycle enforcement
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/DefaultExecutionTraceHandle.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java`
**Changes**: Preserve coordinator-owned top-level finalization, but make the terminal boundary explicit in the recorder/handle contract. Ensure `TRACE_COMPLETED` is always the last accepted semantic event and that later writes fail deterministically across all paths.

#### 2. Clarify active-read semantics during chunked writes
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/DefaultExecutionTraceHandle.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/NdjsonExecutionTraceReader.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/NdjsonTraceRecordWriter.java`
**Changes**: Lock in the intended behavior for partial chunked payloads, envelope visibility, partial-read tolerance, and eventual reconstruction. Make reader behavior deterministic for both active sessions and interrupted writes.

#### 3. Make retention, naming, and post-run snapshot/projection rules explicit and testable
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/DefaultExecutionTraceHandle.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/ExecutionTraceProperties.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
**Changes**: Confirm the property namespace and file naming strategy as part of the subsystem contract. Make the framework invariant explicit that session ids are unique and non-reusable, and rely on that invariant directly if `<sessionId>.execution-trace.ndjson` remains the naming strategy. Add tests that verify retention and post-run snapshot/projection behavior under that invariant rather than implying support for duplicated session ids.

### Success Criteria:

#### Automated Verification:
- [x] Trace handle lifecycle tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*ExecutionTraceHandle*,*ExecutionCoordinator* test`
- [x] Live-reader and chunked-write tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*NdjsonExecutionTraceReader* test`
- [x] Post-run snapshot and JSON round-trip tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*BifrostSessionJson* test`
- [x] Full starter test suite remains green: `./mvnw -pl bifrost-spring-boot-starter test`

#### Manual Verification:
- [ ] Reading an active trace during chunked payload writes is deterministic and does not crash on legitimate in-flight data
- [ ] Finalized traces reject later writes from every runtime path
- [ ] Retained traces remain readable and deterministic under the framework invariant that session ids are unique and non-reusable
- [ ] Post-run session snapshots and derived views preserve the intended behavior when trace metadata is present and degrade predictably when it is absent

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 4: Align Projection and Add Contract-Focused Coverage

### Overview

Make `ExecutionJournal` depend on a stable raw trace contract and add the tests that specifically guard against semantic drift.

### Changes Required:

#### 1. Tighten journal projection assumptions
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/ExecutionJournalProjector.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionJournal.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/JournalEntry.java`
**Changes**: Remove any projector logic that compensates for inconsistent raw writers. Projection should consume the hardened trace contract directly and preserve distinct real events instead of hiding semantic drift.

#### 2. Add contract-level tests for cross-flow equivalence and lifecycle guarantees
**Files**:
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/state/`
- other impacted tests under `bifrost-spring-boot-starter/src/test/java/`
**Changes**: Add tests that compare record semantics across planning, mission, tool, and advisor flows; verify deterministic active reads; verify append barriers after completion; verify retention behavior under the unique-session-id invariant; and verify post-run snapshot plus projection behavior.

#### 3. Create the dedicated testing plan artifact
**File**: `ai/thoughts/plans/2026-03-25-ENG-026-execution-trace-hardening-testing.md` or equivalent output from `ai/commands/3_testing_plan.md`
**Changes**: Follow this implementation plan with a dedicated testing-plan artifact that lists exact classes, failing-test-first targets, concurrency scenarios, and exit criteria for ENG-026.

### Success Criteria:

#### Automated Verification:
- [x] Contract-focused trace tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*ExecutionTraceContract*,*Trace*,*Journal* test`
- [x] Full starter suite remains green: `./mvnw -pl bifrost-spring-boot-starter test`
- [x] Root build remains green across modules: `./mvnw test`

#### Manual Verification:
- [ ] Journal projection remains readable while preserving semantically distinct events from the hardened raw trace
- [ ] Reviewers can validate the subsystem contract by reading the contract artifact and the contract-focused tests together
- [ ] No runtime path still depends on projector compensation for writer inconsistency

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

## Testing Strategy

### Unit Tests:

- Recorder tests that verify only approved boundaries can emit each trace semantic
- Frame lifecycle tests that reconstruct parent-child graphs from raw records only
- Model/tool/advisor equivalence tests that compare trace semantics across call paths
- Trace-handle tests for append barriers, error disposition, retention, and deterministic completion semantics
- Reader tests for active reads during chunked writes, partial traces, and interrupted payload reconstruction
- Projection tests that assert the journal reflects the hardened raw contract without deduplicating away distinct real events

### Integration Tests:

- Planning-enabled mission flow that proves planning and mission model calls share one trace contract
- Tool success/failure flows with both linked and unplanned tool calls
- Advisor retry/exhaustion flows that prove frame-aware request and response mutation linkage
- Top-level session execution that verifies finalization, retention, deletion, and post-run snapshot/projection behavior
- Session-lifecycle coverage that verifies retained traces remain deterministic under the invariant that framework-managed session ids are unique and non-reusable

**Note**: The next recommended step is to run `ai/commands/3_testing_plan.md` for a dedicated ENG-026 testing artifact with explicit failing tests first, exact test classes, and final exit criteria.

### Manual Testing Steps:

1. Run a planning-enabled mission and inspect the raw trace during execution to confirm active reads remain stable while model/tool records are still being appended.
2. Run representative mission, planning, tool, and advisor flows and compare the emitted raw trace to the contract artifact rather than to local implementation details.
3. Finalize a successful run and a failing run, then verify retention/deletion behavior and append rejection after completion.
4. Reopen retained traces from completed runs and confirm journal projection and other post-run derived views behave as documented.

## Performance Considerations

- Keep semantic hardening compatible with the existing append-only NDJSON design so raw payloads stay file-backed instead of accumulating in heap state.
- Avoid widening locks or projection work on hot paths while moving semantics into the recorder boundary.
- Prefer deterministic stream-oriented readback rules over more complex indexing or replay infrastructure in this hardening pass.

## Migration Notes

- This ticket is allowed to refactor internal writer boundaries aggressively because the goal is to prevent future semantic drift, not preserve the current distributed implementation shape.
- If recorder introduction exposes overlapping responsibilities between `BifrostSession` and `DefaultExecutionStateService`, prefer moving semantics out of session helpers rather than documenting the overlap as acceptable.
- If retention-safe naming ever changes later, update reader, post-run snapshot/projection behavior, tests, and the contract artifact together in one phase rather than splitting the contract.

## References

- Original ticket: `ai/thoughts/tickets/eng-026-execution-trace-hardening.md`
- Related research: `ai/thoughts/research/2026-03-25-ENG-026-execution-trace-hardening.md`
- Prior implementation plan: `ai/thoughts/plans/2026-03-24-ENG-025-execution-trace.md`
- Current session trace ownership: [BifrostSession.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java#L33)
- Current state-service routing seam: [DefaultExecutionStateService.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java#L200)
- Current planning model trace seam: [DefaultPlanningService.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java#L121)
- Current mission model trace seam: [DefaultMissionExecutionEngine.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java#L103)
- Current tool trace seam: [DefaultToolCallbackFactory.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java#L78)
- Current advisor-local trace seam: [LinterCallAdvisor.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java#L143)
- Current advisor-local trace seam: [OutputSchemaCallAdvisor.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java#L226)
- Current finalization seam: [ExecutionCoordinator.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java#L95)
- Current lifecycle and chunking seam: [DefaultExecutionTraceHandle.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/DefaultExecutionTraceHandle.java#L130)
- Current active-read seam: [NdjsonExecutionTraceReader.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/NdjsonExecutionTraceReader.java#L28)
- Current persistence property seam: [ExecutionTraceProperties.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/ExecutionTraceProperties.java#L9)
