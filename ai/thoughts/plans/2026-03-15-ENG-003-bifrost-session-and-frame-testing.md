# ENG-003 Bifrost Session and Frame Testing Plan

## Change Summary
- Add a new mission-scoped `BifrostSession` domain type to the starter module.
- Add `ExecutionFrame` stack tracking with fail-fast max-depth enforcement and empty-stack protection.
- Add `ThreadLocal`-based session resolution through `BifrostSession.getCurrentSession()` with strict boundary cleanup.
- Add Spring Boot configuration support for `bifrost.session.max-depth`.
- Add virtual-thread coverage proving session isolation across concurrent execution flows.

## Impacted Areas
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionFrame.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostStackOverflowException.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSessionHolder.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSessionRunner.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/`

## Risk Assessment
- High risk: session context leaks across concurrent virtual threads or nested execution boundaries.
- High risk: stack overflow checks mutate the frame stack before throwing, leaving the session in a bad state.
- High risk: `getCurrentSession()` or `popFrame()` quietly return `null` and hide misuse.
- Medium risk: `ExecutionFrame` does not defensively snapshot parameters, allowing external mutation after push.
- Medium risk: Spring Boot property binding for `bifrost.session.max-depth` is missing, misnamed, or accepts invalid values.
- Medium risk: auto-configuration regressions break starter startup by omitting the new runner or properties bean.

## Existing Test Coverage
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistryTest.java`
  Proves the project already favors direct unit tests, AssertJ, and concurrency checks in the `core` package.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`
  Shows the preferred style for focused behavioral tests without Spring test overhead.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
  Covers annotation/import registration only; it does not yet validate beans or properties.

## Bug Reproduction / Failing Test First
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionTest.java`
- Arrange/Act/Assert outline:
  - Create a `BifrostSession` with a small `maxDepth` such as `1`.
  - Push one valid `ExecutionFrame`.
  - Attempt to push a second frame.
  - Assert that `BifrostStackOverflowException` is thrown.
  - Assert that the original frame is still the top frame and the stack size remains `1`.
- Expected failure (pre-fix):
  - The test fails to compile because `BifrostSession` and related types do not exist yet.
  - After skeleton classes exist but before full behavior is implemented, this test should fail because no depth guard or stack-preservation logic exists.

## Tests to Add/Update
### 1) `createsSessionWithGeneratedIdAndConfiguredMaxDepth`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionTest.java`
- What it proves:
  - A new session has a nonblank generated id.
  - The configured max depth is retained.
  - The initial frame stack is empty.
- Fixtures/data:
  - Construct `BifrostSession` directly with a small explicit max depth.
- Mocks:
  - None.

### 2) `pushAndPopFrameUsesLifoOrder`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionTest.java`
- What it proves:
  - Frames are pushed and popped in stack order.
  - `peekFrame` and snapshot access reflect current stack state correctly.
- Fixtures/data:
  - Two `ExecutionFrame` instances with distinct ids/routes.
- Mocks:
  - None.

### 3) `throwsWhenPushingBeyondMaxDepthWithoutMutatingStack`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionTest.java`
- What it proves:
  - `BifrostStackOverflowException` is thrown exactly when max depth is exceeded.
  - The stack remains unchanged after the failed push.
- Fixtures/data:
  - `BifrostSession` with `maxDepth=1`.
  - Two frames.
- Mocks:
  - None.

### 4) `throwsWhenPoppingEmptyStack`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionTest.java`
- What it proves:
  - Empty-stack access is fail-fast and explicit.
- Fixtures/data:
  - Fresh `BifrostSession`.
- Mocks:
  - None.

### 5) `defensivelyCopiesExecutionFrameParameters`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionFrameTest.java`
- What it proves:
  - `ExecutionFrame` snapshots the input map.
  - The exposed parameters map is immutable.
- Fixtures/data:
  - Mutable source `Map<String, Object>`.
- Mocks:
  - None.

### 6) `requiresCorrelationMetadataOnExecutionFrame`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionFrameTest.java`
- What it proves:
  - Required fields such as `frameId`, `operationType`, `route`, and `openedAt` cannot be null.
  - Optional `parentFrameId` remains nullable.
- Fixtures/data:
  - Constructor calls with null values.
- Mocks:
  - None.

### 7) `throwsWhenCurrentSessionIsAccessedOutsideScope`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionHolderTest.java`
- What it proves:
  - `BifrostSession.getCurrentSession()` fails clearly outside an active session boundary.
- Fixtures/data:
  - No special fixtures.
- Mocks:
  - None.

### 8) `returnsCurrentSessionInsideScopedBoundary`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionHolderTest.java`
- What it proves:
  - Code running inside the session boundary sees the expected session instance.
- Fixtures/data:
  - One `BifrostSession`.
- Mocks:
  - None.

### 9) `createsDistinctSessionsAcrossConcurrentVirtualThreads`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionRunnerTest.java`
- What it proves:
  - Separate virtual-thread tasks each receive a distinct current session.
  - Session ids do not leak between tasks.
- Fixtures/data:
  - `Executors.newVirtualThreadPerTaskExecutor()`.
  - `BifrostSessionRunner`.
- Mocks:
  - None.

### 10) `isolatesFrameMutationAcrossConcurrentVirtualThreads`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionRunnerTest.java`
- What it proves:
  - Each concurrent session maintains its own frame stack.
  - Pushing a frame in one task does not affect another task’s stack snapshot.
- Fixtures/data:
  - Two virtual-thread tasks.
  - Per-task frame creation.
- Mocks:
  - None.

### 11) `bindsDefaultAndOverriddenSessionProperties`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionPropertiesTest.java`
- What it proves:
  - `BifrostSessionProperties` exposes a sensible default max depth.
  - `bifrost.session.max-depth` from Spring Boot property binding overrides that default.
  - Invalid values such as `0` or negative depths are rejected if validation is enabled.
- Fixtures/data:
  - `ApplicationContextRunner` or equivalent lightweight Boot test utility.
  - Property values such as `bifrost.session.max-depth=3`.
- Mocks:
  - None.

### 12) `autoConfiguresSessionRunnerAndProperties`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
- What it proves:
  - The starter contributes `BifrostSessionRunner`.
  - `BifrostSessionProperties` binding is active in the application context.
  - Existing registry bean creation still works.
- Fixtures/data:
  - `ApplicationContextRunner` configured with `BifrostAutoConfiguration`.
- Mocks:
  - None.

## How to Run
- Compile the starter module: `mvn -pl bifrost-spring-boot-starter -DskipTests compile`
- Run the new focused unit tests first: `mvn -pl bifrost-spring-boot-starter -Dtest=BifrostSessionTest,ExecutionFrameTest,BifrostSessionHolderTest,BifrostSessionRunnerTest,BifrostSessionPropertiesTest test`
- Run the starter module suite: `mvn -pl bifrost-spring-boot-starter test`
- Run the full repository suite before merging: `mvn test`

## Exit Criteria
- [ ] A failing test exists first for max-depth overflow and demonstrates the missing behavior before implementation.
- [ ] `BifrostSession`, `ExecutionFrame`, and session-boundary tests all pass after implementation.
- [ ] Virtual-thread tests prove isolation of both current-session lookup and frame-stack mutation.
- [ ] Spring Boot configuration tests prove the default max depth and `application.yml` override path.
- [ ] Starter auto-configuration tests prove the new session infrastructure is registered without regressing current beans.
- [ ] New and updated tests follow existing JUnit 5 + AssertJ conventions in the starter module.
- [ ] Manual verification confirms no `synchronized` blocks were introduced for session mutation paths.
