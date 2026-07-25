# Bifrost Console PR 01 Canonical Trace Semantics and Executable Fixtures Testing Plan

## Change Summary

- Replace the outer `traceModelCall(...)` abstraction with a final pre-provider Spring AI call advisor so every physical provider call is traced and accounted for, including calls made by linter, output-schema, evidence, and planning-quality retries.
- Give each logical retry chain a `retrySequenceId`; give every physical provider call in that chain a distinct `attemptId` and positive, monotonic `attemptNumber`.
- Normalize each returned provider response into one `ModelUsageRecord` and use that same value for trace metadata, session usage, quota enforcement, and Micrometer metrics.
- Keep current response-based `modelCalls` semantics: a provider call that throws before returning a response writes prepared/sent diagnostics but does not increment `modelCalls` or usage.
- Remove `TraceRecord.schemaVersion`, `CURRENT_SCHEMA_VERSION`, the obsolete Go field, and the outer model-tracing types without a compatibility reader, constructor, or dual-write path.
- Make `TRACE_COMPLETED` explicitly state `SUCCEEDED`, `FAILED`, or `ABORTED`, carry the terminal `SessionUsageSnapshot`, and link a terminal failure through one shared failure ID when applicable.
- Preserve the completed execution journal and `SkillExecutionView` Application API projection.
- Add a deterministic Java-owned NDJSON/expected-result corpus for later Go Console consumption, including valid and intentionally invalid traces.
- Update skill-authoring guidance to describe physical attempts, retry grouping, response-based accounting, unavailable usage, Go-derived unattributed usage, failure linkage, and the current-run-only diagnostic boundary.

## Impacted Areas

- Trace envelope, NDJSON writer/reader, chunk reconstruction, lifecycle, and finalization:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSessionRunner.java`
- Model-call instrumentation and advisor ordering:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/chat/`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/linter/`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/outputschema/`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/`
- Logical retry-sequence ownership:
  - `DefaultPlanningService`
  - `DefaultMissionExecutionEngine`
  - `StepLoopMissionExecutionEngine`
  - mapped/nested skill execution through `ExecutionCoordinator`
- Usage extraction, session accounting, quota enforcement, and metrics:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/usage/`
- Failure recording, root-frame closure, journal projection, and persistence policy:
  - `ExecutionCoordinator`
  - `DefaultExecutionStateService`
  - `ExecutionJournalProjector`
- Application API and architecture boundaries:
  - `SkillTemplate`
  - `SkillExecutionView`
  - architecture and auto-configuration tests
- Cross-language fixtures:
  - `bifrost-console-fixtures/traces/*.ndjson`
  - `bifrost-console-fixtures/expected/*.json`
  - `bifrost-console-fixtures/README.md`
  - `bifrost-cli/main.go`
- Skill-authoring documentation:
  - `ai/skill-authoring/README.md`
  - `ai/skill-authoring/evidence-contracts.md`
  - `ai/skill-authoring/traces-and-debugging.md`

## Risk Assessment

- **Highest risk — advisor placement and traversal:** the new attempt advisor must execute exactly once for each downstream provider call and immediately before Spring AI's provider advisor. If it is too high in the chain, nested advisor retries remain collapsed; if it is installed twice, usage and trace records double-count.
- **Highest risk — retry context ownership:** linter, output-schema, and evidence validators can be nested. All physical attempts for one top-level validation call must share one `retrySequenceId`, while unrelated or nested skill calls must receive distinct sequence IDs. Context must not leak across concurrent virtual threads or later calls.
- **Highest risk — accounting coherence:** trace usage, terminal usage, quota checks, and metrics must all consume the same normalized response usage. Successful retry responses count; thrown calls do not. A quota exception after recording a returned response must not erase that response's trace/accounting facts.
- **Terminal consistency:** `TRACE_COMPLETED` must be the final record, use the correct outcome, include the last committed usage snapshot, and share a failure ID with the terminal `ERROR_RECORDED` and failed/aborted root-frame close. Earlier nonterminal errors must not force a successful trace to `FAILED`.
- **Failure during finalization:** root close, journal projection, persistence, and completion append can fail independently. When the trace remains appendable, a failed completion must still be attempted. If the completion append itself fails, tests must not accept an invented or in-memory-only completion.
- **Diagnostic safety:** provider exceptions and payloads must retain existing redaction and bounded-data behavior. Failure IDs, retry IDs, and usage metadata must not introduce credentials, raw transport details, or native provider usage objects into NDJSON.
- **Fixture determinism:** clocks, IDs, ordering, paths, line endings, JSON property order, and chunk IDs must be controlled so normal tests byte-compare cleanly on Windows and Unix. Regeneration must be explicit and idempotent.
- **Cross-language semantic drift:** fixtures must make enum values, attempt identity, terminal status, usage reconciliation, and rejection cases executable. PR 01 produces the contract corpus; it does not implement the Go parser or analysis services planned for later PRs.

### Compatibility scope

- **Application API — protected:** `SkillTemplate` observer behavior and the completed `SkillExecutionView`/journal projection must remain compatible and retain their current exact developer-facing semantics.
- **Supported SPI — none:** no SPI package or new supported extension point may appear.
- **Configuration and manifest contracts — protected:** existing skill/advisor/retry configuration syntax and defaults remain valid. Counting every returned retry response toward usage and quota is an intentional correctness change, not a configuration break.
- **Persisted or serialized contracts — protected where applicable:** the completed journal projection remains protected. The new fixture corpus is a version-controlled test contract for the forthcoming Java-to-Go boundary.
- **Ephemeral diagnostic format — intentionally changed:** current writer, reader, projector, CLI debugging view, and fixtures must agree on the new trace shape. Historical `schemaVersion` traces and old fixtures are not compatibility targets.
- **Internal or accidentally exposed implementation — approved removal:** `ModelTraceCallback`, `ModelTraceResult`, `traceModelCall(...)`, and the outer wrapper path must disappear. Tests must not preserve them behind fallbacks.

### Author-facing claims requiring executable evidence

- A retry is represented as multiple physical attempts in one logical retry sequence.
- Returned responses, including rejected retry responses, consume usage and model-call quota.
- Provider calls that throw before returning a response do not increment `modelCalls`.
- `UNAVAILABLE` usage remains distinguishable from exact or estimated usage.
- Java does not emit an `unattributedUsage` counter; future Go analysis derives any component-wise remainder from terminal usage minus attributed response usage and rejects negative/contradictory reconciliation.
- Terminal outcome and terminal failure linkage can be trusted for debugging.
- Traces are current-run diagnostics, not a historical compatibility or durable audit format.
- Evidence/output validation retries do not rerun tools solely because response validation retried.

## Existing Test Coverage

- `ExecutionTraceContractTest` covers the current planning/mission request-prepared/request-sent/response-received trio, frame ownership, planning-quality events, and provider-error redaction. It uses a simplified `ChatClient`, so it does not traverse the real Spring AI advisor chain and cannot currently expose collapsed physical retries.
- `NdjsonTraceRecordWriterTest`, `NdjsonExecutionTraceReaderTest`, and `ExecutionTraceHandleTest` cover monotonic sequence numbers, active-trace reading, chunk reconstruction, trailing partial lines, large streams, and trace finalization. Their constructors and JSON expectations encode `schemaVersion` and need to be moved to the canonical current shape.
- `SpringAiSkillChatClientFactoryTests` covers provider selection, default advisor installation, and omission of validation advisors for step execution. It does not cover the new mandatory attempt advisor or its exact order relative to `ChatModelCallAdvisor`.
- `LinterCallAdvisorTest`, `OutputSchemaCallAdvisorTest`, `EvidenceContractTests`, `EvidenceContractAdvisorAdditionalTest`, and `SkillAdvisorResolverEvidenceTraceTest` cover retry behavior and advisor mutation diagnostics, but do not assert physical attempt identity or link validator facts to exact attempts.
- `PlanningServiceTest` covers rejected planning responses and current session-usage counting at the planning loop. It does not prove one retry sequence across that loop or per-physical-response accounting through the final provider advisor.
- `SessionUsageServiceTest`, `SessionQuotaTest`, `ModelUsageExtractorTest`, and `MicrometerUsageMetricsRecorderTest` cover their local calculations. They do not prove that one normalized value from the provider response reaches all four consumers exactly once.
- `ExecutionCoordinatorTest`, `BifrostSessionRunnerTest`, and `BifrostSessionTest` cover error marking, frame closure, trace retention, terminal status, open-frame rejection, and repeated finalization. They do not cover typed terminal outcome, shared terminal failure identity, terminal usage, or partial finalization failures.
- `ExecutionJournalProjectionContractTest`, `ExecutionJournalProjectorTest`, and `SkillExecutionViewMapperTest` protect the completed developer-facing journal projection. These are the main regression tests for the protected Application API path.
- `BifrostPublicSurfaceArchitectureTest`, `BifrostAutoConfigurationBoundaryTest`, and `BifrostAutoConfigurationTests` protect the public-type inventory and integration boundary.
- `bifrost-cli/main_test.go` covers CLI formatting but not trace semantics. PR 01 only removes its obsolete `schemaVersion` field; the executable semantic consumer work belongs to later Console PRs.
- There is no deterministic top-level Console fixture corpus or generation/byte-comparison test today.

## Bug Reproduction / Failing Test First

- **Test:** `recordsEachAdvisorRetryAsOnePhysicalAttemptInTheSameRetrySequence`
- **Type:** integration
- **Location:** new `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/chat/ModelAttemptCallAdvisorIntegrationTest.java`
- **Arrange:**
  - Bind a real `BifrostSession` with a model-call frame and fixed clock/ID sources.
  - Build a real Spring AI call-advisor chain containing one retrying validation advisor, the proposed final `ModelAttemptCallAdvisor`, and a deterministic fake `ChatModel`.
  - Return an invalid response with exact usage from the first provider call and a valid response with different exact usage from the second.
- **Act:** execute one logical model call through the real chain and consume the returned value through the same `content()` path used by the engines.
- **Assert:**
  - The fake provider is invoked twice.
  - The attempt advisor is traversed exactly twice, once per downstream provider call.
  - Each attempt emits one prepared/sent/received trio.
  - Both trios have the same nonblank `retrySequenceId`, distinct nonblank `attemptId` values, and attempt numbers `1` and `2`.
  - The validation failure/retry fact links to attempt 1 and the passing fact links to attempt 2.
  - Both returned responses are reflected exactly once in trace usage, session usage, quota observation, and the metrics spy.
  - Request context survives `request.copy()` during retry and response context survives the caller's `content()` fallback.
- **Expected failure pre-fix:** current outer tracing observes one logical call after validation completes, so only one model trio and the final response's usage are recorded; no retry/attempt IDs exist.
- **Red/green discipline:** commit or capture the pre-fix failure output before production changes. Keep this as the primary regression test rather than replacing it with mock-only verification.

## Tests to Add/Update

### 1) Canonical trace envelope omits the obsolete schema

- **Tests:**
  - `writesCanonicalRecordsWithoutSchemaVersion`
  - `readsCanonicalRecordsAndReconstructsChunkedPayloads`
  - `rejectsObsoleteSchemaVersionAsAnUnsupportedCurrentShape`
  - update `ignoresTrailingPartialRecordDuringActiveRead` to use a partial canonical record
- **Type:** unit/integration
- **Location:** update `NdjsonTraceRecordWriterTest.java`, `NdjsonExecutionTraceReaderTest.java`, and `ExecutionTraceHandleTest.java`; add focused `TraceRecordJsonTest.java` coverage so the serialized shape is asserted directly.
- **What it proves:** current writer and reader agree on the simplified record; complete obsolete JSON is not silently accepted; active trailing partial-line tolerance still works; chunk envelopes/chunks retain identity and ordering.
- **Fixtures/data:** fixed clock, fixed trace/payload IDs, temporary NDJSON files, one chunked payload.
- **Mocks:** none; use real Jackson, writer, reader, and temporary files.
- **Contract classification:** Ephemeral diagnostic format.
- **Compatibility expectation:** approved removal plus current-run diagnostic coherence; no legacy reader or dual serialization.

### 2) Mandatory advisor installation and exact order

- **Tests:**
  - `installsOneAttemptAdvisorForEveryCreatedClient`
  - `ordersAttemptAdvisorImmediatelyBeforeChatModelCallAdvisor`
  - update `createForStepExecutionOmitsResolvedAdvisors` to prove it omits validation advisors but retains mandatory attempt instrumentation
- **Type:** unit/integration
- **Location:** `SpringAiSkillChatClientFactoryTests.java` and, if necessary, `ModelAttemptCallAdvisorTest.java`.
- **What it proves:** all direct, planning, and step clients reach the provider through exactly one attempt advisor; custom skill advisors cannot bypass it; the order is `Ordered.LOWEST_PRECEDENCE - 1`, directly before Spring AI's provider advisor.
- **Fixtures/data:** one fake provider per client construction mode and representative linter/output-schema/evidence advisor lists.
- **Mocks:** the existing recording builder/fake client for installation; a real advisor chain for traversal order.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** new internal invariant; no new bean override or public extension point.

### 3) Physical attempt identity, response fallback, and isolation

- **Tests:**
  - primary failing test `recordsEachAdvisorRetryAsOnePhysicalAttemptInTheSameRetrySequence`
  - `recordsPreparedAndSentButNoResponseWhenProviderThrows`
  - `startsIndependentSequencesAtAttemptOneWithoutCrossThreadLeakage`
  - `preservesRequestAndResponseContextAcrossRetryCopiesAndContentFallback`
- **Type:** unit plus integration
- **Location:** new `ModelAttemptCallAdvisorTest.java` and `ModelAttemptCallAdvisorIntegrationTest.java`.
- **What it proves:** one traversal per downstream provider call; stable sequence identity; distinct, positive, monotonic attempt identity; no response fact for a thrown call; concurrent virtual-thread calls do not share scope; `content()` fallback still returns and records the actual response.
- **Fixtures/data:** deterministic invalid/valid `ChatClientResponse` sequence, exact-usage responses, a throwing provider, two barriers/latches for concurrent calls.
- **Mocks:** deterministic fake `CallAdvisorChain`/`ChatModel`; spies only for trace/accounting collaborators. Do not mock the entire integration chain in the primary regression.
- **Contract classification:** Ephemeral diagnostic format.
- **Compatibility expectation:** current-run diagnostic accuracy.

### 4) Retry-sequence ownership across planning, validators, steps, and nested skills

- **Tests:**
  - `reusesOneSequenceAcrossPlanningQualityRetries`
  - `sharesOuterSequenceAcrossNestedValidationAdvisors`
  - `linksLinterAndOutputSchemaFactsToTheExactAttempt`
  - `startsOneSequencePerDirectMissionAndStepCall`
  - `startsDistinctSequencesForNestedSkillModelCalls`
- **Type:** integration
- **Location:** update `PlanningServiceTest.java`, `LinterCallAdvisorTest.java`, `OutputSchemaCallAdvisorTest.java`, `ExecutionTraceContractTest.java`, and mapped-skill coverage in `ExecutionCoordinatorTest.java`; use one shared integration helper rather than duplicating advisor-chain construction.
- **What it proves:** retry grouping follows logical call ownership, not individual validators; planning-quality retries intentionally reuse their sequence; nested skill executions do not inherit the parent's sequence; attempt-linked validation records remain unambiguous.
- **Fixtures/data:** weak/corrected plan responses, invalid/valid schema responses, failing/passing linter responses, a parent skill invoking a child skill.
- **Mocks:** deterministic provider response queues and existing tool callbacks; real state/trace/session components.
- **Contract classification:** Ephemeral diagnostic format.
- **Compatibility expectation:** current-run diagnostic accuracy.

### 5) One normalized response usage value reaches every consumer

- **Tests:**
  - `usesTheSameNormalizedUsageForTraceSessionQuotaAndMetrics`
  - `countsEveryReturnedRetryResponseAsAModelCall`
  - `doesNotCountThrownProviderCallsAsModelCalls`
  - `recordsUnavailableUsageWithoutInventingUnits`
  - retain/extend exact and heuristic extraction cases in `ModelUsageExtractorTest`
- **Type:** unit plus integration
- **Location:** `ModelAttemptCallAdvisorIntegrationTest.java`, `SessionUsageServiceTest.java`, `SessionQuotaTest.java`, `ModelUsageExtractorTest.java`, and `MicrometerUsageMetricsRecorderTest.java`.
- **What it proves:** there is no second extraction or divergent usage object; exact/estimated/unavailable precision is serialized consistently; two returned retry responses increment `modelCalls` twice; a thrown provider call increments it zero times; native provider usage is not serialized into trace metadata.
- **Fixtures/data:** responses with exact usage `(10, 4, 14)` and `(8, 3, 11)`, a response requiring heuristic fallback, a response with unavailable usage, and a thrown exception.
- **Mocks:** a capturing metrics recorder and quota configuration; real extractor/session snapshot implementation.
- **Contract classification:** Configuration and manifest contracts for quota behavior; Ephemeral diagnostic format for trace usage fields.
- **Compatibility expectation:** quota configuration remains protected; corrected returned-response counting is intentional.

### 6) Quota failure occurs after the returned response is durably represented

- **Test:** `recordsReturnedResponseAndUsageBeforeRaisingModelCallQuota`
- **Type:** integration
- **Location:** `ModelAttemptCallAdvisorIntegrationTest.java` or a focused usage/advisor integration test.
- **What it proves:** when a retry response crosses `maxModelCalls`, its response trace and usage are present once, metrics observe it once, and the resulting guardrail failure can be linked to terminal failure semantics.
- **Fixtures/data:** `maxModelCalls=1` and a validator that causes a second returned provider response.
- **Mocks:** fake provider and capturing metrics recorder; real quota/session service.
- **Contract classification:** Configuration and manifest contracts.
- **Compatibility expectation:** protected syntax/defaults with intentional accounting correction.

### 7) Terminal success carries final usage and remains the last record

- **Tests:**
  - `writesSucceededCompletionWithTerminalUsageAsTheFinalRecord`
  - update `finalizesStandaloneRunnerSessionsAndWritesTerminalTraceRecord`
  - `doesNotLetEarlierNonterminalErrorsOverrideSuccessfulCompletion`
- **Type:** integration
- **Location:** `BifrostSessionRunnerTest.java`, `ExecutionCoordinatorTest.java`, and `ExecutionTraceHandleTest.java`.
- **What it proves:** success emits `TRACE_COMPLETED/outcome=SUCCEEDED`, no `terminalFailureId`, the exact final `SessionUsageSnapshot`, and no subsequent records. A handled/nonterminal error followed by successful completion remains `SUCCEEDED`.
- **Fixtures/data:** fixed session with known model/tool/retry totals and a trace containing a nonterminal error before successful root close.
- **Mocks:** real trace/session state; a deterministic action callback.
- **Contract classification:** Ephemeral diagnostic format.
- **Compatibility expectation:** current-run diagnostic coherence.

### 8) Failed and aborted completions share one terminal failure identity

- **Tests:**
  - `linksTerminalErrorRootCloseAndFailedCompletion`
  - `linksInterruptedExecutionAsAborted`
  - `usesDistinctIdsForNonterminalErrors`
- **Type:** integration
- **Location:** update `ExecutionCoordinatorTest.java` and `BifrostSessionRunnerTest.java`.
- **What it proves:** a known finished failure writes `TRACE_COMPLETED/outcome=FAILED`; interruption/cancellation writes `ABORTED`; terminal `ERROR_RECORDED`, failed/aborted root close, and completion share one failure ID; unrelated earlier errors do not reuse it; safe exception metadata remains redacted.
- **Fixtures/data:** provider exception with a credential/endpoint sentinel, ordinary mission failure, timeout/interruption, and a handled child error.
- **Mocks:** deterministic failing engines/actions; real trace recorder and reader.
- **Contract classification:** Ephemeral diagnostic format.
- **Compatibility expectation:** current-run failure visibility and redaction.

### 9) Partial finalization failures preserve truthful terminal state

- **Tests:**
  - `writesFailedCompletionWhenRootCloseFailsButTraceRemainsAppendable`
  - `writesFailedCompletionWhenJournalProjectionFailsButTraceRemainsAppendable`
  - `doesNotFabricateCompletionWhenCompletionAppendFails`
  - `preservesPrimaryFailureWhenCleanupAlsoFails`
- **Type:** unit/integration
- **Location:** `BifrostSessionTest.java`, `BifrostSessionRunnerTest.java`, and `ExecutionCoordinatorTest.java`.
- **What it proves:** finalization attempts record `FAILED` when possible; terminal usage is the last committed snapshot; append failure is surfaced and no synthetic completion appears in memory or the file; cleanup failure does not replace the primary exception.
- **Fixtures/data:** package-private/fake writer hooks that fail at selected append numbers, a failing journal projector, and an open root frame.
- **Mocks:** narrowly scoped failing `TraceRecordWriter`/projector test doubles; real lifecycle logic.
- **Contract classification:** Ephemeral diagnostic format.
- **Compatibility expectation:** current-run diagnostic truthfulness.

### 10) Journal and `SkillExecutionView` remain unchanged

- **Tests:**
  - update `projectsCanonicalDeveloperFacingJournalFromRepresentativeTraceStream`
  - update `ignoresRawTraceRecordsThatAreNotPartOfTheDeveloperFacingProjection`
  - retain exact `SkillExecutionViewMapperTest` assertions with traces containing attempt IDs, terminal outcome, usage, and completion
- **Type:** contract/integration
- **Location:** `ExecutionJournalProjectionContractTest.java`, `ExecutionJournalProjectorTest.java`, and `SkillExecutionViewMapperTest.java`.
- **What it proves:** the new attempt and completion records do not leak into or reorder the developer-facing journal; existing observer and `SkillExecutionView` values remain exact.
- **Fixtures/data:** representative trace stream containing retries, nested skill frames, terminal failure, usage, and completion.
- **Mocks:** none for projection; existing mapper fixture/builders.
- **Contract classification:** Application API.
- **Compatibility expectation:** protected path.

### 11) Deterministic executable Console fixture corpus

- **Tests:**
  - `generatedCorpusMatchesCommittedFixturesByteForByte`
  - `corpusInventoryContainsEveryRequiredSemanticCase`
  - `generatedCorpusIsIdempotent`
- **Type:** contract/integration
- **Location:** new `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ConsoleTraceFixtureCorpusTest.java`.
- **What it proves:** normal test execution writes to a temporary directory and byte-compares every file; explicit regeneration is the only operation that changes committed fixtures; filenames, JSON, line endings, clocks, IDs, ordering, and expected results are deterministic.
- **Fixtures/data:**
  - valid: `single-attempt-success`, `terminal-failure`, `terminal-abort`, `advisor-retry`, `nested-retry-sequences`, `validation-exhaustion`, `unavailable-usage`, `unattributed-usage`, `nonterminal-error-then-success`, and `chunked-payload`;
  - invalid: malformed JSON, inconsistent identities, duplicate/nonmonotonic sequence, incomplete/mismatched chunks, missing completion, non-final completion, unsupported consumed enum/value, and contradictory usage reconciliation.
- **Mocks:** fixed clock and deterministic package-private ID source; no UUID, current-time, environment, or provider dependency.
- **Contract classification:** Persisted or serialized contract for the version-controlled Java-to-Go test corpus; the live trace itself remains an Ephemeral diagnostic format.
- **Compatibility expectation:** new executable boundary contract, with no obligation to preserve pre-PR-01 trace files.

### 12) Fixture semantic invariants and future Go-derived unattributed usage

- **Tests:**
  - `validFixturesSatisfyAttemptTerminalAndUsageInvariants`
  - `unattributedUsageExpectedResultIsTerminalMinusAttributedResponses`
  - `contradictoryUsageFixtureIsRejected`
- **Type:** contract
- **Location:** `ConsoleTraceFixtureCorpusTest.java`; expected results under `bifrost-console-fixtures/expected/`.
- **What it proves:** valid traces have monotonic record sequence, valid attempt grouping, exactly one final completion, legal enums, and nonnegative usage; the expected unattributed remainder is derived component-wise rather than emitted by Java; negative or internally contradictory reconciliation is classified invalid.
- **Fixtures/data:** specifically the `unattributed-usage`, `unavailable-usage`, and `contradictory-usage-reconciliation` cases.
- **Mocks:** none.
- **Contract classification:** Persisted or serialized contract.
- **Compatibility expectation:** new Java-produced boundary evidence for future Go PR 13; no Java `unattributedUsage` production field or counter.

### 13) CLI compiles without the removed field

- **Tests:** update any struct literal/formatting assertion affected by removing `SchemaVersion`; run the full Go test package.
- **Type:** unit/build verification
- **Location:** `bifrost-cli/main_test.go` only if compilation or output assertions require a change.
- **What it proves:** the in-repo current-run CLI reader compiles against the canonical trace shape and does not display or depend on `schemaVersion`.
- **Fixtures/data:** a canonical one-line trace sample if the existing formatting test needs it.
- **Mocks:** none.
- **Contract classification:** Ephemeral diagnostic format.
- **Compatibility expectation:** approved removal/current-run coherence, not historical trace support.

### 14) Public-surface and auto-configuration boundaries remain closed

- **Tests:**
  - update the exact classified type inventory for removed outer wrappers and internal `TraceOutcome`
  - `attemptInstrumentationDoesNotCreateAConditionalOverridePoint`
  - retain `noSupportedSpiPackageOrTypeExists`
- **Type:** architecture/integration
- **Location:** `BifrostPublicSurfaceArchitectureTest.java`, `BifrostAutoConfigurationBoundaryTest.java`, and `BifrostAutoConfigurationTests.java`.
- **What it proves:** removed wrapper types are absent; new attempt/scope/ID-source helpers do not enter Application API, Supported SPI, constructor signatures, or conditional-bean override points; `TraceOutcome` has the approved classification.
- **Fixtures/data:** architecture allowlists and a minimal Spring context.
- **Mocks:** none beyond the existing context setup.
- **Contract classification:** Application API and Supported SPI boundary.
- **Compatibility expectation:** protected public boundary; approved internal removal.

### 15) Skill-authoring claims are backed by behavior tests

- **Tests used as evidence, not prose tests:**
  - physical retry grouping: tests 3 and 4;
  - returned retry response accounting/quota: tests 5 and 6;
  - thrown-call `modelCalls` behavior: test 5;
  - unavailable versus derived unattributed usage: tests 5 and 12;
  - terminal outcome/failure linkage: tests 7–9;
  - evidence retry without repeated tool work: retain and, if needed, extend `EvidenceContractTests.evidenceAdvisorPreservesExistingSkillPromptOnRetry` with an integration assertion that tool callbacks execute once while provider attempts execute twice;
  - current-run-only trace boundary: tests 1 and 13.
- **Type:** integration/contract
- **Location:** underlying test classes above; no test should parse Markdown prose.
- **What it proves:** each changed author-facing statement has an executable framework behavior behind it.
- **Fixtures/data:** reuse the canonical retry, evidence, usage, and terminal cases.
- **Mocks:** existing fake tool callback plus deterministic provider.
- **Contract classification:** Configuration and manifest contracts for author-visible runtime behavior; Ephemeral diagnostic format for trace guidance.
- **Compatibility expectation:** protected authoring configuration with clarified diagnostics/accounting.

## How to Run

### Prerequisites

- JDK 21 and the repository-supported Maven version.
- Go toolchain supported by `bifrost-cli/go.mod`.
- No provider credentials, network services, environment profiles, or external Console process. All model responses, time, IDs, metrics, and failures are deterministic test doubles.

### Red test before implementation

```powershell
mvn -pl bifrost-spring-boot-starter -Dtest=ModelAttemptCallAdvisorIntegrationTest#recordsEachAdvisorRetryAsOnePhysicalAttemptInTheSameRetrySequence test
```

Capture the assertion failure showing that the current implementation emits/accounts for one outer logical call rather than two physical responses. If the test initially cannot compile because the new advisor type is not present, introduce the smallest test-facing skeleton, then confirm the test fails on record/accounting assertions before implementing behavior.

### Focused Java tests during implementation

```powershell
mvn -pl bifrost-spring-boot-starter -Dtest=NdjsonTraceRecordWriterTest,NdjsonExecutionTraceReaderTest,ExecutionTraceHandleTest,TraceRecordJsonTest test
mvn -pl bifrost-spring-boot-starter -Dtest=ModelAttemptCallAdvisorTest,ModelAttemptCallAdvisorIntegrationTest,SpringAiSkillChatClientFactoryTests test
mvn -pl bifrost-spring-boot-starter -Dtest=PlanningServiceTest,LinterCallAdvisorTest,OutputSchemaCallAdvisorTest,EvidenceContractTests,ExecutionTraceContractTest test
mvn -pl bifrost-spring-boot-starter -Dtest=SessionUsageServiceTest,SessionQuotaTest,ModelUsageExtractorTest,MicrometerUsageMetricsRecorderTest test
mvn -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorTest,BifrostSessionRunnerTest,BifrostSessionTest test
mvn -pl bifrost-spring-boot-starter -Dtest=ExecutionJournalProjectionContractTest,ExecutionJournalProjectorTest,SkillExecutionViewMapperTest test
mvn -pl bifrost-spring-boot-starter -Dtest=BifrostPublicSurfaceArchitectureTest,BifrostAutoConfigurationBoundaryTest,BifrostAutoConfigurationTests test
```

### Fixture verification and explicit regeneration

Normal verification must not modify the working tree:

```powershell
mvn -pl bifrost-spring-boot-starter -Dtest=ConsoleTraceFixtureCorpusTest test
```

Regenerate only after an intentional semantic change:

```powershell
mvn -pl bifrost-spring-boot-starter -Dtest=ConsoleTraceFixtureCorpusTest -Dbifrost.console.fixtures.regenerate=true test
git diff -- bifrost-console-fixtures
mvn -pl bifrost-spring-boot-starter -Dtest=ConsoleTraceFixtureCorpusTest test
```

### Go and complete repository verification

```powershell
Push-Location bifrost-cli
go test ./...
Pop-Location

mvn -pl bifrost-spring-boot-starter test
mvn verify
```

### Obsolete-path absence checks

These searches must return no production or fixture matches, excluding historical planning/research text:

```powershell
rg -n "schemaVersion|CURRENT_SCHEMA_VERSION" bifrost-spring-boot-starter/src bifrost-cli bifrost-console-fixtures
rg -n "ModelTraceCallback|ModelTraceResult|traceModelCall" bifrost-spring-boot-starter/src/main
rg -n "unattributedUsage" bifrost-spring-boot-starter/src/main bifrost-cli
```

## Manual Verification

1. Run explicit fixture regeneration twice and confirm the second run produces no diff.
2. Inspect one single-attempt trace, one retry trace, one failed trace, the chunked trace, and their expected JSON:
   - records are ordered and completion is final;
   - retry attempts share a sequence but have distinct IDs/numbers;
   - terminal failure IDs link the intended records;
   - no `schemaVersion`, native usage object, credential sentinel, absolute temporary path, or nondeterministic value appears.
3. Confirm `git diff -- bifrost-console-fixtures` contains only intentional semantic fixture changes and uses consistent LF endings.
4. Run a local current-version sample or existing CLI formatting path against a newly produced trace and confirm it renders without relying on `schemaVersion`. Do not use an old trace as an acceptance criterion.
5. Compare the changed skill-authoring claims with the named evidence tests in section 15.

## Exit Criteria

- [x] The primary advisor-chain regression test exists and its pre-fix failure was observed/captured.
- [x] The primary regression and all focused tests pass after implementation.
- [x] Every downstream provider call traverses the attempt advisor exactly once.
- [x] Validator retries preserve one logical `retrySequenceId`, create distinct ordered attempts, and link validation facts to the exact attempt.
- [x] Planning-quality retries share one intended sequence; direct/step/nested calls establish the intended independent sequence boundaries.
- [x] Every returned response, including rejected retry responses, is represented once in trace, session usage, quota observation, and metrics using one normalized usage value.
- [x] A provider call that throws before returning a response writes prepared/sent diagnostics, writes no response/usage fact, and does not increment `modelCalls`.
- [x] Quota-crossing returned responses remain recorded before the quota exception is raised.
- [x] Successful, failed, and aborted completions carry correct outcome and terminal usage, are final, and use truthful terminal failure linkage.
- [x] Partial finalization failure tests prove a failed completion is appended when possible and never fabricated when append fails.
- [x] Existing error/payload redaction tests pass with credential/transport sentinels absent.
- [x] Completed journal and `SkillExecutionView` contract tests pass unchanged in observable meaning.
- [x] Canonical writer, reader, projector, CLI, and fixtures agree on the current trace shape.
- [x] The deterministic fixture corpus contains the complete approved valid/invalid inventory, byte-compares in normal tests, and regenerates idempotently.
- [x] Fixture expected results demonstrate Go-derived unattributed usage without adding a Java production counter.
- [x] Skill-authoring statements are supported by the named runtime tests; no prose-only tests were added.
- [x] Public-surface and auto-configuration tests confirm no new Supported SPI, public advisor/scope/ID helper, or override point.
- [x] `schemaVersion`, `CURRENT_SCHEMA_VERSION`, `ModelTraceCallback`, `ModelTraceResult`, `traceModelCall(...)`, and Java production `unattributedUsage` are absent from their scoped production/fixture paths.
- [x] No legacy trace reader, compatibility constructor, dual-write form, or simultaneous old/new behavior remains.
- [x] `go test ./...`, the starter test suite, and `mvn verify` pass.
- [x] Manual fixture, CLI, redaction, deterministic-regeneration, and documentation-evidence checks are complete.
