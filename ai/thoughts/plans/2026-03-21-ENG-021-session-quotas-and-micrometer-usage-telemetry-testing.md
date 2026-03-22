# ENG-021 Session Quotas and Micrometer Usage Telemetry Testing Plan

## Change Summary
- Add session quota configuration for bounded mission, tool, linter, and model usage.
- Introduce session-local usage accounting state and quota enforcement in the runtime path.
- Capture Spring AI `ChatResponse` usage metadata when present and fall back to heuristic usage estimation when it is absent.
- Emit Micrometer metrics for model usage, tool activity, linter outcomes, and quota/guardrail trips.

## Impacted Areas
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/usage/...`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- `bifrost-spring-boot-starter/pom.xml`

## Risk Assessment
- Quota checks may fire too early or too late, changing mission/tool/linter behavior in ways that are hard to diagnose.
- Mission execution may regress if the switch from `.content()` to `ChatResponse` handling changes empty-response or timeout behavior.
- Usage accounting may undercount or double-count when exact provider usage is absent and heuristic fallback is used.
- Session serialization may break if the new usage state is not backward-compatible with existing JSON tests and persisted sessions.
- Metrics may introduce high-cardinality tags or miss important guardrail events if they are emitted from the wrong runtime seam.

## Existing Test Coverage
- `BifrostSessionPropertiesTest` already covers session property binding and validation patterns and is the right place to extend quota-property assertions.
- `MissionExecutionEngineTest` already covers mission execution and timeout behavior and is the best place to prove response-aware model usage capture does not regress content handling.
- `ExecutionCoordinatorIntegrationTest` already covers coordinator wiring, recursive stack overflow, and timeout guardrails, so it is the right integration seam for quota-trip behavior.
- `ExecutionCoordinatorLinterIntegrationTest` already exercises linter retry flow and recorded outcomes, which gives a stable place to verify linter usage accounting.
- `ExecutionStateServiceTest`, `ToolCallbackFactoryTest`, and `LinterCallAdvisorTest` already cover the runtime seams where plan/tool/linter accounting hooks will be added.
- `BifrostSessionJsonTest` already protects session JSON shape and should be extended for the new usage snapshot.
- `BifrostAutoConfigurationTests` already covers starter bean wiring and should be expanded for usage-service and metrics-recorder beans.
- Gap: there are currently no checked-in Micrometer-focused tests in `bifrost-spring-boot-starter/src/test/java`, so meter names/tags/counts need fresh coverage.

## Bug Reproduction / Failing Test First
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/usage/SessionUsageServiceTest.java`
- Arrange/Act/Assert outline:
  - Arrange a `BifrostSession`, low quota settings, and the default usage service.
  - Record usage events that remain under the limit, then record one more event that exceeds the configured quota.
  - Assert that a `BifrostQuotaExceededException` is thrown with the expected guardrail type, observed value, and configured limit.
- Expected failure (pre-fix):
  - The usage service and quota exception types do not exist yet, or no exception is thrown because quota enforcement is not implemented.

## Tests to Add/Update
### 1) `bindsQuotaSettingsAndRejectsInvalidQuotaValues`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionPropertiesTest.java`
- What it proves: new `bifrost.session.quotas.*` properties bind correctly and invalid zero/negative values are rejected.
- Fixtures/data: property maps for valid defaults, explicit custom quotas, and invalid quota values.
- Mocks: none.

### 2) `roundTripsSessionWithUsageSnapshotThroughJackson`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionJsonTest.java`
- What it proves: session-local usage state serializes and deserializes without breaking existing session JSON behavior.
- Fixtures/data: a populated `BifrostSession` with journal entries, linter outcome, and usage snapshot fields.
- Mocks: none.

### 3) `extractsExactUsageFromChatResponseMetadata`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/usage/ModelUsageExtractorTest.java`
- What it proves: Spring AI `Usage` metadata is converted into an exact `ModelUsageRecord` with prompt, completion, total units, and native usage preserved.
- Fixtures/data: a `ChatResponse` containing metadata usage values and representative response content.
- Mocks: none if Spring AI builders are usable; otherwise a minimal mocked `ChatResponse` graph.

### 4) `fallsBackToHeuristicUsageWhenProviderUsageMissing`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/usage/ModelUsageExtractorTest.java`
- What it proves: heuristic accounting is used when provider usage is missing and the result is marked with `HEURISTIC` precision.
- Fixtures/data: `ChatResponse` variants with missing metadata, missing usage, and empty output text.
- Mocks: none or a minimal mocked `ChatResponse` graph.

### 5) `throwsWhenModelCallQuotaExceeded`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/usage/SessionUsageServiceTest.java`
- What it proves: repeated model-call accounting trips the configured model-call guardrail at the correct threshold.
- Fixtures/data: low `maxModelCalls` quota and a small set of `ModelUsageRecord` values.
- Mocks: a no-op metrics recorder.

### 6) `throwsWhenToolInvocationQuotaExceeded`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/usage/SessionUsageServiceTest.java`
- What it proves: tool invocation accounting trips the tool guardrail and preserves the tripped guardrail type in the exception.
- Fixtures/data: low `maxToolInvocations` quota, skill/tool names, and a session.
- Mocks: a no-op metrics recorder.

### 7) `throwsWhenLinterRetryQuotaExceeded`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/usage/SessionUsageServiceTest.java`
- What it proves: linter retry accounting accumulates across outcomes and fails once the configured retry quota is exceeded.
- Fixtures/data: a sequence of retry/final `LinterOutcome` values and a low retry quota.
- Mocks: a no-op metrics recorder.

### 8) `capturesModelUsageInMissionExecution`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java`
- What it proves: mission execution reads a `ChatResponse`, records usage metadata, and still returns the same mission output content.
- Fixtures/data: a fake or mocked `ChatClient` that returns a `ChatResponse` with usage metadata plus output text.
- Mocks: mocked chat client/call chain and usage service.

### 9) `failsMissionWhenConfiguredModelQuotaExceeded`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java`
- What it proves: an end-to-end mission fails in-band with `BifrostQuotaExceededException` when model-call or usage-unit quotas are exhausted.
- Fixtures/data: a test application context with low quota configuration and a mission that requires multiple model calls.
- Mocks: only the model/client seam already used by adjacent integration tests.

### 10) `recordsToolUsageAndGuardrailTripDuringCallbackExecution`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/tool/ToolCallbackFactoryTest.java`
- What it proves: tool execution records usage before/around capability invocation and surfaces a quota trip without skipping expected runtime logging behavior.
- Fixtures/data: a capability callback, session, and low tool quota.
- Mocks: usage service or metrics recorder only where the existing test style already uses mocks.

### 11) `recordsLinterRetryUsageAndFinalOutcome`
- Type: unit or integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/linter/LinterCallAdvisorTest.java`
- What it proves: linter retries and final outcomes flow through the shared accounting path without regressing retry semantics.
- Fixtures/data: retryable linter responses and a final passing or exhausted outcome.
- Mocks: existing linter/retry collaborators plus usage service where appropriate.

### 12) `autoConfiguresUsageServicesAndMetricsRecorder`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
- What it proves: starter auto-configuration creates the usage-accounting and metrics-recorder beans and wires them into runtime components.
- Fixtures/data: application context runner configuration with and without a `MeterRegistry`.
- Mocks: none.

### 13) `emitsMicrometerMetersForModelToolLinterAndGuardrailEvents`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/usage/MicrometerUsageMetricsRecorderTest.java`
- What it proves: the Micrometer recorder emits the intended meter names, bounded tags, and counts for model usage, tool activity, linter outcomes, and guardrail trips.
- Fixtures/data: `SimpleMeterRegistry`, representative skill/tool/outcome/guardrail values.
- Mocks: none.

### 14) `emitsMetricsWhenQuotaTripsInIntegratedMission`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorMetricsIntegrationTest.java`
- What it proves: an integrated mission that trips a quota increments the corresponding guardrail metric and leaves mission failure behavior observable at the coordinator boundary.
- Fixtures/data: low quota configuration plus a `SimpleMeterRegistry` or test registry bean in the application context.
- Mocks: only existing test doubles required to control model responses.

## How to Run
- `./mvnw -pl bifrost-spring-boot-starter test`
- `./mvnw -pl bifrost-spring-boot-starter -Dtest=BifrostSessionPropertiesTest,BifrostSessionJsonTest test`
- `./mvnw -pl bifrost-spring-boot-starter -Dtest=MissionExecutionEngineTest,ExecutionCoordinatorIntegrationTest,ExecutionCoordinatorLinterIntegrationTest test`
- `./mvnw -pl bifrost-spring-boot-starter -Dtest=*Usage*Test,*Quota*Test,*Metrics*Test test`
- `./mvnw verify`

## Exit Criteria
- [ ] Failing test exists and fails pre-fix for quota enforcement or response-metadata usage capture.
- [x] All tests pass post-fix.
- [x] New and updated tests cover quota enforcement, exact-versus-heuristic usage accounting, session serialization, runtime integration, and Micrometer emission.
- [ ] Manual verification confirms a below-threshold mission still succeeds and an intentionally constrained mission fails with a clear quota error.
- [ ] Manual verification confirms emitted meter names and tags are bounded and align with observable runtime behavior.

## References
- [ENG-021 implementation plan](C:/opendev/code/bifrost/ai/thoughts/plans/2026-03-21-ENG-021-session-quotas-and-micrometer-usage-telemetry.md)
- [ENG-021 research](C:/opendev/code/bifrost/ai/thoughts/research/2026-03-21-ENG-021-session-quotas-and-micrometer-usage-telemetry.md)
- [ENG-021 ticket](C:/opendev/code/bifrost/ai/thoughts/tickets/eng-021-session-quotas-and-micrometer-usage-telemetry.md)
