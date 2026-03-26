# ENG-025 Execution Trace Testing Plan

## Change Summary
- Replace the in-memory `ExecutionJournal` as the canonical runtime record with a file-backed, append-only `ExecutionTrace` subsystem.
- Add NDJSON trace writing/reading, chunked inline payload support, and `execution-trace.persistence` retention behavior.
- Move session observability ownership to `ExecutionTraceHandle` while keeping `BifrostSession` as the aggregate/lock boundary.
- Introduce explicit coordinator-level trace finalization so `TRACE_COMPLETED` and retention decisions happen deterministically, while mission-time cleanup remains owned by the mission execution layer.
- Rebuild `ExecutionJournal` as a sanitized projection over trace records, treat it as a convenience view rather than the primary API, and remove `SkillThoughtTrace` plus its mapper.
- Defer provider-native thought capture from the MVP trace contract; tests should not treat missing thought records as a failure for ENG-025.

## Impacted Areas
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java`
- new trace package/classes under `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/`
- projection and legacy-removal surfaces under `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/`

## Risk Assessment
- High risk: losing observability fidelity while replacing the canonical journal path.
- High risk: incorrect or missing `TRACE_COMPLETED` and retention behavior when execution succeeds, fails, or times out.
- Medium risk: developer-facing JSON/debug surfaces breaking when `ExecutionTrace` replaces the embedded journal as the canonical serialized observability artifact.
- High risk: model/advisor/tool instrumentation changing call sequencing or mutating prompts incorrectly.
- Medium risk: frame mapping regressions if runtime `ExecutionFrame` and trace-frame reconstruction drift apart.
- Medium risk: projection/sanitization regressions when replacing `SkillThoughtTrace` with trace-derived journal output and simplifying payloads away from legacy shapes.
- Edge case: large prompt/response payloads requiring chunk reassembly by `payloadId` and `chunkIndex`.
- Edge case: linter failure, output-schema exhaustion, and thrown exceptions must all count as `onerror`.

## Existing Test Coverage
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/state/ExecutionStateServiceTest.java`
  Covers frame open/close ordering, plan state, and current journal writes through the state-service boundary.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java`
  Covers planning-enabled and planning-disabled mission execution plus timeout behavior.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/planning/PlanningServiceTest.java`
  Covers plan initialization, status normalization, and plan updates on tool start/completion/failure.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/tool/ToolCallbackFactoryTest.java`
  Covers linked and unplanned tool-call flows against mocked collaborators.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/linter/LinterCallAdvisorTest.java`
  Covers retry loop behavior and session-visible outcome recording for linter validation.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisorTest.java`
  Covers retry/exhaustion logic, prompt augmentation, and session-visible output-schema outcomes.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
  Covers mission orchestration, planning mode behavior, tool usage, and journal side effects.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorLinterIntegrationTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorOutputSchemaIntegrationTest.java`
  Cover end-to-end validation-loop behavior through the coordinator path.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionJournalTest.java`
  Covers current journal serialization and payload shape.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/ExecutionJournalProjectorTest.java`
  Covers developer-facing sanitization and trace-derived journal payload projection.

## Gaps
- No tests yet for NDJSON trace writing/reading.
- No tests yet for monotonic trace sequence generation or chunk reconstruction.
- No tests yet for coordinator-owned finalization writing terminal metadata before retention/deletion.
- No tests yet for `execution-trace.persistence` modes.
- No tests yet for deriving `ExecutionJournal` from raw trace records.
- No further test gaps remain for `SkillThoughtTrace`; coverage should live in projector/session tests instead of a dedicated legacy type.

## Bug Reproduction / Failing Test First
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTraceFinalizationTest.java`
- Arrange/Act/Assert outline:
  Create a session with a trace handle configured for temp-file output, execute a minimal successful mission through `ExecutionCoordinator`, then assert the retained trace contains `TRACE_STARTED` and `TRACE_COMPLETED` in order and that terminal metadata is written before retention/deletion.
- Expected failure (pre-fix):
  No trace file exists, or the trace lacks `TRACE_COMPLETED`, or finalization behavior is still implicit and cannot be asserted deterministically.

- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/NdjsonExecutionTraceReaderTest.java`
- Arrange/Act/Assert outline:
  Append a large logical payload through the new writer API so it must be chunked, read it back with the trace reader, and assert payload reassembly by `payloadId` and ordered `chunkIndex`.
- Expected failure (pre-fix):
  Trace classes do not exist yet, payload chunking is absent, or reconstruction returns missing/out-of-order content.

## Tests to Add/Update
### 1) `NdjsonTraceRecordWriterTest.writesMonotonicSequenceAndSessionNamedTempFile`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/NdjsonTraceRecordWriterTest.java`
- What it proves:
  Trace records are appended in monotonic `sequence` order and the temp file path clearly includes `<sessionId>.execution-trace.ndjson`.
- Fixtures/data:
  Fixed clock, deterministic session id, temp directory override via JUnit temp dir if supported by the implementation.
- Mocks:
  None preferred; use real writer and filesystem.

### 2) `NdjsonExecutionTraceReaderTest.reconstructsChunkedPayloadsByPayloadId`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/NdjsonExecutionTraceReaderTest.java`
- What it proves:
  Inline chunk records are read and reassembled correctly for large prompt/response/tool payloads.
- Fixtures/data:
  Synthetic trace file with `PAYLOAD_CHUNK_APPENDED` records for at least one large body.
- Mocks:
  None preferred.

### 3) `ExecutionTraceHandleTest.appliesNeverOnErrorAndAlwaysPersistencePolicies`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/ExecutionTraceHandleTest.java`
- What it proves:
  `never`, `onerror`, and `always` produce the correct retention result after finalization.
- Fixtures/data:
  One clean session and three errored scenarios: linter exhaustion, output-schema exhaustion, thrown exception.
- Mocks:
  Minimal; real temp files are better than mocking the filesystem.

### 4) `ExecutionStateServiceTraceTest.recordsFrameLifecycleViaRecorderBoundary`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/state/ExecutionStateServiceTraceTest.java`
- What it proves:
  Opening and closing mission frames emits `FRAME_OPENED`, `FRAME_METADATA_RECORDED`, and `FRAME_CLOSED` using runtime-frame-to-trace mapping, while preserving stack discipline.
- Fixtures/data:
  Fixed clock, session with nested frames.
- Mocks:
  Prefer a recording trace-recorder fake rather than a Mockito-heavy assertion-only test.

### 5) `ExecutionCoordinatorTraceFinalizationTest.finalizesTraceOnSuccessfulMission`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTraceFinalizationTest.java`
- What it proves:
  A successful mission writes terminal trace metadata and `TRACE_COMPLETED` from the coordinator seam before retention logic runs, while mission cleanup remains owned by the mission execution layer.
- Fixtures/data:
  Reuse existing fake chat client and coordinator wiring patterns from `ExecutionCoordinatorTest`.
- Mocks:
  Keep mocks low; use real `DefaultExecutionStateService`, planning service, mission engine, and a real trace handle.

### 6) `ExecutionCoordinatorTraceFinalizationTest.retainsTraceForOnErrorConditions`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTraceFinalizationTest.java`
- What it proves:
  `onerror` retains trace files for each supported error source: linter failure, output-schema failure, and thrown exception.
- Fixtures/data:
  Reuse existing linter/output-schema integration harnesses plus a capability or chat client that throws.
- Mocks:
  None preferred beyond the existing fake clients.

### 7) `MissionExecutionEngineTraceTest.recordsPreparedSentAndReceivedModelArtifacts`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTraceTest.java`
- What it proves:
  Mission execution emits model request prepared/sent/received records with prompt and response payloads plus model metadata.
- Fixtures/data:
  Deterministic fake chat client that exposes system/user/tool inputs and returns a stable response.
- Mocks:
  Mock planning service when needed; use a recording trace-recorder fake.

### 8) `PlanningServiceTraceTest.recordsPlanningFramesAndPlanEvents`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/planning/PlanningServiceTraceTest.java`
- What it proves:
  Planning produces planning/model trace segments and still records `PLAN_CREATED` and `PLAN_UPDATED` at the expected moments.
- Fixtures/data:
  Reuse YAML/JSON planning payloads already present in `PlanningServiceTest`.
- Mocks:
  Real planning service with recording trace-recorder fake.

### 9) `ToolCallbackFactoryTraceTest.recordsRequestedStartedCompletedAndFailedToolEvents`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/tool/ToolCallbackFactoryTraceTest.java`
- What it proves:
  Tool invocation emits the new explicit tool-event record sequence for both linked and unplanned paths, including raw arguments/results.
- Fixtures/data:
  Existing capability metadata and task-linking patterns from `ToolCallbackFactoryTest`.
- Mocks:
  Mock router/planning service as current tests do, plus recording trace-recorder fake.

### 10) `LinterCallAdvisorTraceTest.recordsAdvisorMutationsAndTerminalOutcome`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/linter/LinterCallAdvisorTraceTest.java`
- What it proves:
  Linter retries emit request mutation records, outcome records, and mark the session as errored when retries exhaust.
- Fixtures/data:
  Existing `RecordingChain` pattern from `LinterCallAdvisorTest`.
- Mocks:
  None preferred.

### 11) `OutputSchemaCallAdvisorTraceTest.recordsAdvisorMutationsAndExhaustionAsError`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisorTraceTest.java`
- What it proves:
  Output-schema retries/exhaustion emit request mutation and structured-output records and contribute to `onerror` finalization behavior.
- Fixtures/data:
  Existing schemas and `RecordingChain` pattern from `OutputSchemaCallAdvisorTest`.
- Mocks:
  None preferred.

### 12) `ExecutionJournalProjectorTest.derivesSanitizedDeveloperFacingJournalFromTrace`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/ExecutionJournalProjectorTest.java`
- What it proves:
  The projected journal remains readable and sanitized while the raw trace retains unsanitized data.
- Fixtures/data:
  Synthetic trace records with secrets in tool args/results and error payloads.
- Mocks:
  None.

### 13) Debug/session response coverage updates
- Type: unit or integration update
- Location:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/`
  plus any sample/debug endpoint tests discovered during implementation
- What it proves:
  Developer-facing JSON/debug responses continue to expose the supported observability surface after journal-first ownership is removed, without requiring `BifrostSession` itself to round-trip through Jackson.
- Fixtures/data:
  Update any existing JSON fixtures to assert `executionTrace` metadata and optional projected journal output rather than session deserialization.
- Mocks:
  None.

### 14) `ExecutionJournalTest` rewrite to projection semantics
- Type: unit update
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionJournalTest.java`
- What it proves:
  `ExecutionJournal` is now a derived, sanitized view and no longer the canonical append target.
- Fixtures/data:
  Synthetic trace stream rather than direct journal appends.
- Mocks:
  None.

### 15) `SkillThoughtTraceTest` removal/migration
- Type: unit update or delete
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillThoughtTraceTest.java`
- What it proves:
  Legacy `SkillThoughtTrace` behavior has been intentionally removed and its useful sanitization behavior is preserved by the new journal projector, without a compatibility shim.
- Fixtures/data:
  Migrate the existing secret-sanitization expectations into `ExecutionJournalProjectorTest`.
- Mocks:
  None.

### 16) `BifrostAutoConfigurationTests.bindsExecutionTracePersistenceProperties`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
- What it proves:
  The new `execution-trace.persistence` property binds correctly and auto-configures any required trace beans.
- Fixtures/data:
  Application context with each enum value.
- Mocks:
  Existing Spring test context only.

### 17) `ExecutionCoordinatorLinterIntegrationTest` and `ExecutionCoordinatorOutputSchemaIntegrationTest` updates
- Type: integration update
- Location:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorLinterIntegrationTest.java`
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorOutputSchemaIntegrationTest.java`
- What it proves:
  Existing end-to-end validation flows still work while also asserting trace retention/finalization behavior.
- Fixtures/data:
  Existing integration harnesses.
- Mocks:
  Keep current setup style.

## Suggested Execution Order
- Start with the two failing tests for trace finalization and chunked readback.
- Add foundational writer/reader/handle tests before touching orchestration.
- Update state/coordinator tests as Phase 2 lands.
- Add mission/planning/tool/advisor trace tests as Phase 3 lands.
- Finish by rewriting journal/debug-surface tests around the trace-first surface and deleting/migrating `SkillThoughtTrace` tests in Phase 4.

## How to Run
- Starter compile: `./mvnw -pl bifrost-spring-boot-starter -DskipTests compile`
- Focused trace tests: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*Trace*,*ExecutionCoordinator* test`
- State and planning tests: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*ExecutionState*,*Planning*,*MissionExecution* test`
- Tool and advisor tests: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*Tool*,*Linter*,*OutputSchema* test`
- Full starter suite: `./mvnw -pl bifrost-spring-boot-starter test`
- Full repo validation: `./mvnw test`

## Required Environment / Data
- Java 21 and Maven 3.9+ per the parent build.
- Filesystem access to the default temp directory or a test-controlled temp directory.
- No external model providers should be required for unit/integration tests; use the existing fake/simple chat clients already present under `src/test/java`.

## Exit Criteria
- [ ] A minimal failing finalization test exists and fails before the coordinator-level finalization work lands.
- [ ] A minimal failing chunked-trace readback test exists and fails before NDJSON trace support lands.
- [x] New trace writer/reader/handle tests cover sequence ordering, chunk reconstruction, and persistence behavior.
- [x] Coordinator/state tests prove explicit `TRACE_COMPLETED` finalization and `onerror` retention behavior.
- [x] Mission/planning/tool/advisor tests prove the new trace capture contract without regressing current control flow.
- [x] Developer-facing JSON/debug coverage is updated to the post-journal ownership model and pass.
- [x] Journal projection tests prove sanitized developer-facing output from unsanitized trace input.
- [x] `SkillThoughtTrace` tests are removed or migrated, with sanitization expectations preserved in projector coverage.
- [x] `./mvnw -pl bifrost-spring-boot-starter test` passes.
- [x] `./mvnw test` passes.
- [ ] Manual verification confirms temp trace files, retention behavior, and projected journal readability on a real run.
