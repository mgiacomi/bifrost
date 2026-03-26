# ENG-026 Execution Trace Hardening Testing Plan

## Change Summary
- Harden `ExecutionTrace` as a contract-driven subsystem rather than a set of distributed instrumentation details.
- Introduce a canonical recorder boundary for trace taxonomy and record emission rules.
- Remove direct model-trace writes from planning and mission flows.
- Remove advisor-local raw trace taxonomy usage and move advisors to structured fact/outcome reporting.
- Preserve coordinator-owned finalization while tightening append barriers, active-read behavior, retention behavior, and post-run snapshot/projection behavior.
- Keep `ExecutionJournal` as a derived projection over a stable raw trace contract.

## Impacted Areas
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/ExecutionStateService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/DefaultExecutionTraceHandle.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/NdjsonExecutionTraceReader.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/ExecutionJournalProjector.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/ExecutionTraceProperties.java`
- New contract/recorder classes introduced by ENG-026 under `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/` and `.../runtime/trace/`

## Risk Assessment
- High risk: two partial "authoritative" boundaries emerging, leaving model trace semantics split between chat-client and feature code.
- High risk: old direct-write paths surviving under helper methods and causing semantic drift even while tests still pass.
- High risk: advisors continuing to import `TraceRecordType`, which would undermine the canonical boundary.
- High risk: finalization and append-barrier regressions letting post-completion writes through from one flow but not another.
- High risk: journal projection compensating for inconsistent writers instead of exposing contract drift.
- Medium risk: active trace reads becoming non-deterministic during chunked writes or interrupted payloads.
- Medium risk: post-run session snapshot/projection behavior drifting from the intended serialized `BifrostSession` and derived `ExecutionJournal` semantics.
- Medium risk: retention assumptions drifting away from the explicit framework invariant that session ids are globally unique and non-reusable.

## Existing Test Coverage
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/ExecutionTraceHandleTest.java`
  Covers retention policies, session-named temp files, timestamp overrides, and append rejection after finalization.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/NdjsonExecutionTraceReaderTest.java`
  Covers chunk reconstruction and tolerant reads of incomplete chunked payloads.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/ExecutionJournalProjectorTest.java`
  Covers sanitization, duplicate-collapse behavior for tool/error events, and preservation of repeated legitimate events.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionJsonTest.java`
  Covers canonical execution-trace serialization, disabled live-handle behavior after deserialization, and finalized journal preservation when the trace file is deleted.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/state/ExecutionStateServiceTest.java`
  Covers frame lifecycle, plan state, trace writes against active frames, and journal projection side effects through the state-service boundary.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/planning/PlanningServiceTest.java`
  Covers planning initialization, model-trace metadata in planning, and plan updates on tool lifecycle changes.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/tool/ToolCallbackFactoryTest.java`
  Covers linked/unplanned tool execution through mocked planning/router/state collaborators.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/linter/LinterCallAdvisorTest.java`
  Covers retry/exhaustion behavior, session-visible linter outcomes, and advisor mutation trace records on active frames.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisorTest.java`
  Covers schema retry/exhaustion behavior, session-visible outcomes, and advisor mutation trace records on active frames.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
  Covers mission orchestration, planning-enabled and planning-disabled flows, tool usage, and current journal side effects.

## Gaps
- No test yet asserts the boundary cleanup definition of done: no direct `MODEL_*` writes in planning/mission and no `TraceRecordType` imports in advisor classes.
- No test yet compares equivalent model/tool/advisor events across call paths to prove semantic equivalence.
- No test yet verifies the chosen authoritative model-tracing boundary is truly universal, rather than central only for some call sites.
- No test yet proves finalization remains terminal across all writer entry points after the recorder refactor lands.
- No test yet re-centers projection tests around "do not compensate for inconsistent writers."
- No test yet turns the session-id uniqueness invariant into an explicit assertion in trace retention coverage.

## Bug Reproduction / Failing Test First
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/ExecutionTraceContractTest.java`
- Arrange/Act/Assert outline:
  Create a minimal contract-focused test harness that drives one planning model call and one mission model call through the chosen authoritative boundary, then assert both emit the same semantic record sequence and metadata shape for equivalent events.
- Expected failure (pre-fix):
  Planning and mission still emit `MODEL_*` records from their own feature code, so the sequences or metadata sources diverge and the test fails.

- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/ExecutionTraceBoundaryCleanupTest.java`
- Arrange/Act/Assert outline:
  Inspect the target source files or boundary collaborators and assert that planning/mission no longer emit raw `MODEL_*` record writes directly and advisor classes no longer depend on `TraceRecordType`.
- Expected failure (pre-fix):
  `DefaultPlanningService`, `DefaultMissionExecutionEngine`, `LinterCallAdvisor`, and `OutputSchemaCallAdvisor` still contain the old direct-write behavior.

These are intentionally contract-oriented failing tests. ENG-026 is partly a refactor, but the bug is semantic drift caused by distributed ownership, so the first failing tests should prove that drift exists.

## Tests to Add/Update
### 1) `ExecutionTraceContractTest.modelEventsAreSemanticallyEquivalentAcrossPlanningAndMission`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/ExecutionTraceContractTest.java`
- What it proves:
  Equivalent model events produce the same raw trace semantics regardless of whether they come from planning or mission execution.
- Fixtures/data:
  Fixed clock, deterministic session id, simple fake chat client, real state service plus either real recorder or a recording contract harness.
- Mocks:
  Prefer minimal mocks; use a lightweight recording recorder or trace-handle reader instead.

### 2) `ExecutionTraceBoundaryCleanupTest.planningAndMissionDoNotEmitModelTraceTaxonomyDirectly`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/ExecutionTraceBoundaryCleanupTest.java`
- What it proves:
  The old direct model-trace paths are actually removed from planning and mission code.
- Fixtures/data:
  Source inspection or narrow collaborator-level assertions depending on how the refactor lands.
- Mocks:
  None preferred.

### 3) `ExecutionTraceBoundaryCleanupTest.advisorsDoNotImportTraceRecordType`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/ExecutionTraceBoundaryCleanupTest.java`
- What it proves:
  Advisors publish structured facts/outcomes and no longer own raw trace taxonomy.
- Fixtures/data:
  Advisor class inspection or compile-time assertions if helper abstractions make this straightforward.
- Mocks:
  None.

### 4) `ExecutionTraceHandleTest.rejectsWritesFromAllRecorderPathsAfterFinalization`
- Type: unit update
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/ExecutionTraceHandleTest.java`
- What it proves:
  Finalization is a true append barrier no matter which recorder entry point attempts a late write.
- Fixtures/data:
  Finalized handle plus representative frame/model/tool/advisor writer calls.
- Mocks:
  None preferred.

### 5) `NdjsonExecutionTraceReaderTest.deterministicallyReadsPartialChunkedPayloadDuringActiveWrite`
- Type: unit update
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/NdjsonExecutionTraceReaderTest.java`
- What it proves:
  Active-read behavior remains deterministic for partial chunked payloads and interrupted writes.
- Fixtures/data:
  Synthetic incomplete envelope/chunk sequences plus real reader.
- Mocks:
  None.

### 6) `ExecutionStateServiceTest.routesFrameLifecycleThroughCanonicalRecorderOnly`
- Type: unit update
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/state/ExecutionStateServiceTest.java`
- What it proves:
  Frame open/metadata/close semantics are owned by the canonical boundary and remain reconstructable from raw records.
- Fixtures/data:
  Fixed clock, nested frames, real session/trace handle.
- Mocks:
  Use a recording recorder fake if needed; avoid Mockito-only shape assertions.

### 7) `PlanningServiceTest.doesNotWriteModelTraceRecordsDirectlyAfterBoundaryCentralization`
- Type: unit update
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/planning/PlanningServiceTest.java`
- What it proves:
  Planning still opens the orchestration frames it owns, but model request/response record sequencing now comes from the authoritative boundary.
- Fixtures/data:
  Existing `SimpleChatClient`, fixed clock, deterministic session.
- Mocks:
  Minimal.

### 8) `MissionExecutionEngineTraceTest.doesNotWriteModelTraceRecordsDirectlyAfterBoundaryCentralization`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/MissionExecutionEngineTraceTest.java`
- What it proves:
  Mission execution no longer handcrafts raw `MODEL_*` record emission while still owning mission-time orchestration context.
- Fixtures/data:
  Reuse mission-engine construction patterns from `ExecutionCoordinatorTest`.
- Mocks:
  Fake chat client and lightweight planning service; avoid over-mocking the trace boundary.

### 9) `ToolCallbackFactoryTest.emitsCanonicalToolSequenceForLinkedAndUnplannedCalls`
- Type: unit update
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/tool/ToolCallbackFactoryTest.java`
- What it proves:
  Tool requested/started/completed/failed semantics remain stable through the recorder boundary for both linked and unplanned calls.
- Fixtures/data:
  Existing capability/router/planning patterns.
- Mocks:
  Current router/planning mocks are acceptable; prefer recording actual trace output where easy.

### 10) `LinterCallAdvisorTest.emitsStructuredAdvisorFactsWithoutRawTraceTaxonomyDependency`
- Type: unit update
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/linter/LinterCallAdvisorTest.java`
- What it proves:
  Linter advisor retry/exhaustion behavior still works while no longer importing `TraceRecordType` or directly choosing trace record kinds.
- Fixtures/data:
  Existing `RecordingChain` and `BifrostSessionRunner` patterns.
- Mocks:
  None preferred.

### 11) `OutputSchemaCallAdvisorTest.emitsStructuredAdvisorFactsWithoutRawTraceTaxonomyDependency`
- Type: unit update
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisorTest.java`
- What it proves:
  Output-schema advisor retry/exhaustion behavior still works while trace taxonomy lives entirely in the canonical boundary.
- Fixtures/data:
  Existing `RecordingChain`, schemas, and `BifrostSessionRunner`.
- Mocks:
  None preferred.

### 12) `ExecutionCoordinatorTraceFinalizationTest.finalizesTraceBeforeRetentionDecision`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTraceFinalizationTest.java`
- What it proves:
  Coordinator-owned finalization remains explicit, terminal metadata is written, and retention/deletion is applied only after `TRACE_COMPLETED`.
- Fixtures/data:
  Reuse coordinator harness patterns and real trace handle.
- Mocks:
  Keep mocks low; real state service/mission engine wiring is preferred.

### 13) `ExecutionCoordinatorTraceFinalizationTest.honorsUniqueSessionIdRetentionInvariant`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTraceFinalizationTest.java`
- What it proves:
  Retention logic is tested under the explicit invariant that framework-managed session ids are unique and non-reusable, rather than around duplicated-session-id scenarios.
- Fixtures/data:
  Deterministic unique session ids and retained traces.
- Mocks:
  None preferred.

### 14) `ExecutionJournalProjectorTest.doesNotHideDistinctSemanticEvents`
- Type: unit update
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/ExecutionJournalProjectorTest.java`
- What it proves:
  The projector consumes the hardened raw contract and does not compensate for writer inconsistency by collapsing legitimately distinct events.
- Fixtures/data:
  Synthetic trace streams with repeated-but-distinct tool/advisor/model events.
- Mocks:
  None.

### 15) `BifrostSessionJsonTest.preservesPostRunSnapshotAndProjectionBehavior`
- Type: unit update
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionJsonTest.java`
- What it proves:
  Serialized `BifrostSession`, `ExecutionTrace` snapshots, and derived `ExecutionJournal` behavior remain correct after the boundary refactor.
- Fixtures/data:
  Existing Jackson round-trip fixtures plus finalized traces.
- Mocks:
  None.

## Suggested Execution Order
- Start with the two contract-oriented failing tests: semantic equivalence across planning/mission and boundary cleanup assertions.
- Update/add handle and reader tests next so the lifecycle guarantees stay stable during boundary refactoring.
- Land the recorder-boundary refactor with state/planning/mission/tool tests in the same slice.
- Update advisor tests when advisors move to structured facts/outcomes.
- Finish with coordinator finalization, session JSON, and projector regression coverage.

## How to Run
- Starter compile: `./mvnw -pl bifrost-spring-boot-starter -DskipTests compile`
- Contract-focused tests only: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*ExecutionTraceContract*,*ExecutionTraceBoundaryCleanup* test`
- Trace and lifecycle tests: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*Trace*,*ExecutionCoordinatorTraceFinalization* test`
- State/planning/mission tests: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*ExecutionState*,*Planning*,*MissionExecution* test`
- Tool and advisor tests: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*Tool*,*Linter*,*OutputSchema* test`
- Full starter suite: `./mvnw -pl bifrost-spring-boot-starter test`
- Full repo validation: `./mvnw test`

## Required Environment / Data
- Java and Maven versions supported by the current repo build.
- Filesystem access to the default temp directory or a test-controlled temp directory.
- No external model provider dependencies; use the existing simple/fake chat clients and coordinator harnesses already present in the test suite.

## Exit Criteria
- [x] A minimal semantic-equivalence failing test exists and fails before boundary refactoring begins.
- [x] A minimal boundary-cleanup failing test exists and fails while old direct-write paths still exist.
- [x] New/updated tests prove there is exactly one authoritative model-tracing boundary in the final implementation.
- [x] New/updated tests prove planning and mission no longer emit raw `MODEL_*` records directly.
- [x] New/updated tests prove advisors no longer import `TraceRecordType` and no longer emit raw trace taxonomy directly.
- [x] Handle/reader tests prove deterministic active reads, chunk behavior, and append barriers after completion.
- [x] Coordinator tests prove explicit finalization and retention behavior under the unique-session-id invariant.
- [x] Session JSON and projector tests prove correct post-run snapshot/projection behavior.
- [x] `./mvnw -pl bifrost-spring-boot-starter test` passes.
- [x] `./mvnw test` passes.
- [ ] Manual verification confirms the raw trace, derived journal, and finalization behavior match the contract artifact on a representative real run.
