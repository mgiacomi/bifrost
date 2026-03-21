# ENG-020 Recursion and Mission Timeout Guardrails Testing Plan

## Change Summary
- Add a first-class `bifrost.session.mission-timeout` property alongside the existing `bifrost.session.max-depth` setting.
- Enforce a bounded timeout around `DefaultMissionExecutionEngine`'s blocking LLM call.
- Preserve the existing `BifrostSession` stack-depth guardrail as the recursion limit for nested unmapped YAML `callSkill` flows.
- Prove timeout and overflow failures unwind mission frames cleanly and do not regress happy-path mission execution.

## Impacted Areas
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/MissionExecutionEngine.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/BifrostMissionTimeoutException.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionPropertiesTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java`

## Risk Assessment
- High risk: timeout enforcement could fail to interrupt waiting code cleanly, leaving hanging tasks or brittle tests.
- High risk: recursive YAML fixtures could accidentally assert the wrong failure mode if the test setup does not actually route through `CapabilityExecutionRouter`.
- Medium risk: coordinator frame cleanup could regress if timeout exceptions bypass the existing `finally` path.
- Medium risk: plan state restoration for nested YAML skills could be left stale after overflow or timeout failures.
- Medium risk: the new timeout property could bind inconsistently if validation or defaulting is implemented in a non-standard way.
- Edge case: very small timeout values should fail deterministically rather than race.
- Edge case: the existing happy-path mission loop must still pass when guardrails are present but not triggered.

## Existing Test Coverage
- `BifrostSessionTest.throwsWhenPushingBeyondMaxDepthWithoutMutatingStack()` already proves direct stack overflow does not mutate the stack.
- `BifrostSessionPropertiesTest.bindsDefaultAndOverriddenSessionProperties()` already covers default and overridden `bifrost.session.max-depth`.
- `MissionExecutionEngineTest.executesPlanningEnabledMissionLoop()` and `MissionExecutionEngineTest.skipsPlanningForPlanningDisabledMission()` cover current happy-path mission-engine behavior.
- `ExecutionCoordinatorTest.authorizesProtectedChildYamlSkillFromSessionFallback()` already proves nested unmapped YAML skills re-enter the coordinator and end with an empty frame stack.
- `ExecutionCoordinatorIntegrationTest.executesCoordinatorFlowEndToEndWithPlanStateVisibleToolsAndStrictRefResolution()` covers the starter-wired happy path.
- Gap: no test currently drives recursive YAML execution until `BifrostStackOverflowException`.
- Gap: no test currently proves mission execution times out.
- Gap: no test currently verifies timeout cleanup through the coordinator path.
- Gap: no test currently binds and consumes a mission-timeout property through starter auto-configuration.

## Bug Reproduction / Failing Test First
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java`
- Arrange/Act/Assert outline:
  - Arrange a `DefaultMissionExecutionEngine` with a very small timeout such as `25ms`.
  - Use a fake `ChatClient` that blocks on a latch or sleeps until interrupted.
  - Invoke `executeMission(...)`.
  - Assert that a `BifrostMissionTimeoutException` is thrown and that the fake client observed interruption or cancellation.
- Expected failure (pre-fix):
  - The current code path blocks indefinitely in `chatClient.prompt()...call().content()` and does not throw a timeout exception at all.

This is the cheapest failing-first test because it isolates the missing behavior at the exact boundary where the timeout must be enforced.

## Tests to Add/Update
### 1) Mission timeout triggers at the mission engine boundary
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java`
- What it proves: `DefaultMissionExecutionEngine` converts a stalled model call into `BifrostMissionTimeoutException` using the configured timeout.
- Fixtures/data: blocking fake `ChatClient`, `BifrostSession`, small timeout value, no-op or mocked `PlanningService`.
- Mocks: mock `PlanningService`; use a purpose-built fake `ChatClient` instead of Mockito for the blocking call.

### 2) Planning-enabled happy path still works with timeout wiring present
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java`
- What it proves: the new timeout path does not change planning initialization, prompt construction, or content return when the model responds normally.
- Fixtures/data: the existing mission plan fixture from `executesPlanningEnabledMissionLoop()`.
- Mocks: existing mocked `PlanningService`; existing simple chat client fixture.

### 3) Planning-disabled happy path still skips plan initialization
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java`
- What it proves: timeout enforcement does not regress the planning-disabled behavior already covered today.
- Fixtures/data: the existing planning-disabled mission scenario.
- Mocks: existing mocked `PlanningService`; existing simple chat client fixture.

### 4) Mission-timeout property binds with default, override, and invalid values
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionPropertiesTest.java`
- What it proves: `bifrost.session.mission-timeout` has a stable default, accepts a valid override such as `5s`, and rejects `0s`.
- Fixtures/data: `ApplicationContextRunner` with property overrides.
- Mocks: none.

### 5) Recursive YAML loop fails at configured max depth
- Type: integration-style unit test
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
- What it proves: nested unmapped YAML routing through `CapabilityExecutionRouter` hits `BifrostSession.pushFrame(...)` overflow and surfaces `BifrostStackOverflowException`.
- Fixtures/data: root and child YAML skill definitions whose tool flow cycles back into another unmapped YAML mission, shallow session depth such as `2` or `3`, fake coordinator chat clients that deterministically request the recursive tool.
- Mocks: existing fake chat-client infrastructure from `ExecutionCoordinatorTest`; no external providers.

### 6) Recursive overflow leaves frame stack empty and plan state coherent
- Type: integration-style unit test
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
- What it proves: after overflow, the coordinator `finally` and router plan restoration leave the session in an inspectable state.
- Fixtures/data: same recursive fixture as the prior test.
- Mocks: existing fake chat-client infrastructure; reuse the same coordinator setup.

### 7) Timeout failure unwinds coordinator mission frames
- Type: integration-style unit test
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
- What it proves: when the mission engine times out, `ExecutionCoordinator.execute(...)` still closes the mission frame and exposes `BifrostMissionTimeoutException`.
- Fixtures/data: coordinator wired with a timeout-enabled mission engine and a blocking chat-client fixture.
- Mocks: fake chat client or stub mission engine, depending on which produces the least brittle test while still exercising coordinator cleanup.

### 8) Starter wiring honors `bifrost.session.mission-timeout`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java`
- What it proves: the new timeout property flows through `BifrostAutoConfiguration` into the runtime path under an `ApplicationContextRunner`.
- Fixtures/data: context runner with `bifrost.session.mission-timeout` set, blocking or delay-capable chat-client bean.
- Mocks: use the existing integration test bean wiring style rather than provider mocks.

### 9) Happy-path coordinator flow still succeeds with guardrails configured
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java`
- What it proves: existing success cases still pass when both `bifrost.session.max-depth` and `bifrost.session.mission-timeout` are present but not exceeded.
- Fixtures/data: existing root/child YAML integration fixtures with a non-trivial timeout such as `5s`.
- Mocks: existing `RecordingSkillChatClientFactory` fixture.

### 10) Direct stack-overflow invariant remains intact
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionTest.java`
- What it proves: low-level overflow behavior still throws before mutating the stack, regardless of the new timeout work.
- Fixtures/data: the existing two-frame overflow scenario.
- Mocks: none.

## How to Run
- Compile the module: `./mvnw -pl bifrost-spring-boot-starter -DskipTests compile`
- Run property binding coverage: `./mvnw -pl bifrost-spring-boot-starter -Dtest=BifrostSessionPropertiesTest test`
- Run mission-engine tests: `./mvnw -pl bifrost-spring-boot-starter -Dtest=MissionExecutionEngineTest test`
- Run session invariant tests: `./mvnw -pl bifrost-spring-boot-starter -Dtest=BifrostSessionTest test`
- Run coordinator guardrail tests: `./mvnw -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorTest test`
- Run starter-wiring integration tests: `./mvnw -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorIntegrationTest test`
- Run the full starter module suite: `./mvnw -pl bifrost-spring-boot-starter test`

## Exit Criteria
- [x] A minimal failing timeout test exists first in `MissionExecutionEngineTest` and fails against the pre-fix implementation.
- [x] `BifrostSessionPropertiesTest` covers the new timeout property's default, override, and invalid-value behavior.
- [x] `MissionExecutionEngineTest` covers both timeout failure and happy-path regressions.
- [x] `ExecutionCoordinatorTest` proves timeout cleanup leaves no dangling frames, while `ExecutionCoordinatorIntegrationTest` covers recursive YAML overflow through the real nested routing path.
- [x] `ExecutionCoordinatorIntegrationTest` proves starter auto-configuration honors the timeout property and does not regress happy-path execution.
- [x] `BifrostSessionTest` still proves overflow happens before stack mutation.
- [x] `./mvnw -pl bifrost-spring-boot-starter test` passes after implementation.
- [ ] Manual verification confirms a recursive YAML loop fails quickly at low max depth and a blocking mission fails quickly at low mission timeout.
