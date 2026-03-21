# ENG-020 Recursion and Mission Timeout Guardrails Implementation Plan

## Overview

Harden mission execution by turning the existing session depth limit into an explicit recursion guardrail boundary and by adding a configurable timeout around LLM-backed mission execution. The implementation should preserve the current architecture where `BifrostSession` owns execution stack state, `ExecutionCoordinator` owns mission frame lifecycle, and `DefaultMissionExecutionEngine` owns the model-call boundary.

## Current State Analysis

Recursion protection already exists, but only as a low-level session invariant. `BifrostSession.pushFrame(...)` rejects pushes once the frame stack reaches `maxDepth` and throws `BifrostStackOverflowException` before mutating stack state (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:207`). `ExecutionCoordinator.execute(...)` clears the prior plan, opens a mission frame, delegates into `MissionExecutionEngine`, and always closes the frame in `finally` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:61`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:62`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:81`).

Nested unmapped YAML skills already recurse through the same coordinator/session path. `CapabilityExecutionRouter.execute(...)` snapshots the parent plan, routes nested YAML skills back through `ExecutionCoordinator`, and restores the parent plan in `finally` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:55`). That means recursive `callSkill` flows already count against the shared session stack; they just are not covered by end-to-end overflow tests yet.

The missing piece is mission time bounding. `BifrostSessionProperties` exposes only `bifrost.session.max-depth` today (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java:8`), and `DefaultMissionExecutionEngine.executeMission(...)` still performs a direct synchronous `chatClient.prompt()...call().content()` with no timeout (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:28`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:49`).

## Desired End State

Bifrost should expose first-class runtime guardrail properties for both recursion depth and mission timeout, enforce timeout limits in the mission execution layer, and prove via automated tests that recursive YAML missions and stalled model calls fail predictably without leaving session frame state corrupted.

After this work:
- Recursive nested YAML skill loops fail with `BifrostStackOverflowException` at the configured depth and leave the session stack coherent.
- LLM-backed mission calls fail with a dedicated timeout exception once the configured mission timeout elapses.
- Timeout and overflow failures both unwind mission frames cleanly through the existing coordinator/state-service boundary.
- Happy-path mission execution still behaves the same when guardrails are enabled.

### Key Discoveries:
- `BifrostSession` already enforces max depth before mutating the stack, which makes it the correct recursion guardrail source of truth (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:207`).
- Nested YAML execution already re-enters `ExecutionCoordinator`, so recursion guardrail testing should target the existing `CapabilityExecutionRouter` -> `ExecutionCoordinator` path rather than inventing a new recursion mechanism (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:55`).
- Mission timeout enforcement belongs in `DefaultMissionExecutionEngine`, because that is the current provider-agnostic model invocation boundary (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:49`).
- Starter auto-configuration already wires both `BifrostSessionRunner` and `DefaultMissionExecutionEngine`, so the new timeout property can stay first-class and centralized in starter configuration (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:87`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:198`).

## What We're NOT Doing

- Adding token, cost, or quota enforcement
- Exporting Micrometer metrics
- Changing provider-specific chat adapters to own timeout policy
- Reworking YAML skill routing architecture beyond what is needed for guardrail coverage
- Adding sample-app or documentation-focused work beyond property/test coverage

## Implementation Approach

Keep recursion depth enforcement exactly where it already lives, then make the guardrail boundary explicit through configuration and tests. Add a new mission-timeout property alongside `maxDepth`, inject it into `DefaultMissionExecutionEngine`, and enforce it by running the blocking chat call through a bounded future/executor path inside the mission engine. On timeout, cancel the in-flight mission work, raise a dedicated timeout exception with session/skill/timeout context, and let `ExecutionCoordinator`'s existing `finally` block close the mission frame.

This approach keeps session/frame cleanup centralized in the coordinator/state service and keeps timeout policy provider-agnostic, which matches the ticket’s structural requirements.

## Phase 1: Promote Guardrails to First-Class Runtime Configuration

### Overview

Add explicit timeout configuration alongside the existing max-depth property and thread it through starter wiring so both guardrails are visible, validated, and testable from configuration.

### Changes Required:

#### 1. Extend session guardrail properties
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java`
**Changes**:
- Add a `Duration missionTimeout` property under the existing `bifrost.session` prefix.
- Give it a conservative default of `Duration.ofSeconds(60)` so the boundary is explicit without making defaults overly aggressive.
- Validate it as positive and document the property intent in the class.

```java
private static final Duration DEFAULT_MISSION_TIMEOUT = Duration.ofSeconds(60);

@NotNull
private Duration missionTimeout = DEFAULT_MISSION_TIMEOUT;

public void setMissionTimeout(Duration missionTimeout) {
    if (missionTimeout == null || missionTimeout.isZero() || missionTimeout.isNegative()) {
        throw new IllegalArgumentException("missionTimeout must be greater than zero");
    }
    this.missionTimeout = missionTimeout;
}
```

#### 2. Thread guardrail properties through starter beans
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
**Changes**:
- Keep `BifrostSessionRunner` wired from `sessionProperties.getMaxDepth()`.
- Update the `DefaultMissionExecutionEngine` bean construction to receive the configured timeout and any executor/clock dependency chosen for bounded mission execution.

```java
return new DefaultMissionExecutionEngine(
        planningService,
        executionStateService,
        sessionProperties.getMissionTimeout(),
        missionExecutor);
```

#### 3. Expand configuration binding coverage
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionPropertiesTest.java`
**Changes**:
- Assert the new default timeout value.
- Assert that an override like `bifrost.session.mission-timeout=5s` binds successfully.
- Assert invalid values such as `0s` fail binding, just like `max-depth=0` does today.

### Success Criteria:

#### Automated Verification:
- [x] Module compiles with the new property wiring: `./mvnw -pl bifrost-spring-boot-starter -DskipTests compile`
- [x] Property binding tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=BifrostSessionPropertiesTest test`

#### Manual Verification:
- [ ] The new property name is obvious from the code and matches the existing `bifrost.session.*` configuration style.
- [ ] The default timeout is conservative enough for current mission flows while still being a real guardrail.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the property shape and default are acceptable before proceeding to the next phase.

---

## Phase 2: Enforce Mission Timeouts at the Mission Execution Boundary

### Overview

Wrap the blocking chat-model call in a bounded execution path inside `DefaultMissionExecutionEngine` so LLM-backed missions cannot stall forever, while preserving the existing coordinator-owned frame lifecycle.

### Changes Required:

#### 1. Add a dedicated timeout exception
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/BifrostMissionTimeoutException.java`
**Changes**:
- Introduce a runtime exception dedicated to mission timeout failures.
- Include session id, skill name, and configured timeout in the message so callers and tests can identify the failure mode clearly.

```java
throw new BifrostMissionTimeoutException(session.getSessionId(), skillName, missionTimeout, ex);
```

#### 2. Bound the synchronous model call with timeout enforcement
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java`
**Changes**:
- Inject the configured timeout and a provider-agnostic executor strategy.
- Move the raw `chatClient.prompt()...call().content()` logic into a callable submitted to that executor.
- Wait on the future with the configured timeout.
- Cancel the future on timeout/interruption and translate those conditions into `BifrostMissionTimeoutException`.
- Preserve existing planning initialization and execution-prompt behavior.

```java
Future<String> mission = missionExecutor.submit(() -> chatClient.prompt()
        .system(executionPrompt)
        .user(objective)
        .toolCallbacks(visibleTools)
        .call()
        .content());

try {
    return mission.get(missionTimeout.toMillis(), TimeUnit.MILLISECONDS);
}
catch (TimeoutException ex) {
    mission.cancel(true);
    throw new BifrostMissionTimeoutException(session.getSessionId(), skillName, missionTimeout, ex);
}
```

#### 3. Keep cleanup at the coordinator boundary
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
**Changes**:
- Keep the existing `openMissionFrame` / `closeMissionFrame` structure intact.
- If needed, add minimal failure journaling before rethrowing timeout/overflow exceptions, but do not move frame cleanup out of the `finally` block.
- Ensure the plan is still cleared at root mission start and nested-plan restoration remains owned by `CapabilityExecutionRouter`.

#### 4. Cover timeout behavior with focused mission-engine tests
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java`
**Changes**:
- Add a fake chat client that blocks until interrupted so timeout behavior is deterministic.
- Assert planning-enabled and planning-disabled missions still behave as before when the model call completes normally.
- Assert timed-out execution throws `BifrostMissionTimeoutException`.

### Success Criteria:

#### Automated Verification:
- [x] Mission engine tests pass, including the timeout case: `./mvnw -pl bifrost-spring-boot-starter -Dtest=MissionExecutionEngineTest test`
- [x] The module still compiles cleanly after executor/timeout wiring: `./mvnw -pl bifrost-spring-boot-starter -DskipTests compile`

#### Manual Verification:
- [ ] Timeout failures are obvious from the exception type/message without digging into provider internals.
- [ ] The chosen timeout implementation does not leak provider-specific concerns into chat adapters.
- [ ] The implementation keeps cancellation behavior best-effort and does not rely on the model provider honoring interruption to preserve correctness.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that timeout behavior and exception semantics are acceptable before proceeding to the next phase.

---

## Phase 3: Add End-to-End Guardrail Coverage for Overflow, Timeout, and Cleanup

### Overview

Prove the guardrail behavior end to end through the existing coordinator/router/session flow so recursion overflow and mission timeout failures are covered where application teams actually consume them.

### Changes Required:

#### 1. Add recursive YAML overflow coverage in coordinator tests
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
**Changes**:
- Add a fixture where a root YAML skill calls an unmapped child YAML skill that recursively calls itself or cycles back to the root.
- Configure the session with a shallow `maxDepth` such as `2` or `3`.
- Assert the coordinator path throws `BifrostStackOverflowException`.
- Assert frame cleanup leaves the session stack empty after the failure.
- Assert parent plan state is not left in a corrupted nested state after the router `finally` restoration runs.

```java
assertThatThrownBy(() -> coordinator.execute("root.recursive.skill", "loop", session, null))
        .isInstanceOf(BifrostStackOverflowException.class)
        .hasMessageContaining("root.recursive.skill");

assertThat(session.getFramesSnapshot()).isEmpty();
```

#### 2. Add timeout cleanup coverage in coordinator tests
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
**Changes**:
- Inject a mission engine or chat-client fixture that deterministically blocks long enough to trigger the timeout.
- Assert `ExecutionCoordinator.execute(...)` surfaces `BifrostMissionTimeoutException`.
- Assert the mission frame stack is empty after the exception because the coordinator `finally` block still closes the frame.
- Assert any preserved session plan state is coherent for later inspection.

#### 3. Extend integration coverage through Spring configuration
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java`
**Changes**:
- Add an application-context test that sets both `bifrost.session.max-depth` and `bifrost.session.mission-timeout`.
- Verify the timeout property is honored through real starter wiring, not just via direct unit construction.
- Preserve at least one happy-path integration test to prove existing mission behavior does not regress when guardrails are enabled.

#### 4. Keep low-level session overflow coverage as the unit safety net
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionTest.java`
**Changes**:
- Keep the existing direct stack-overflow test and tighten assertions only if the new guardrail work changes message text or invariant expectations.
- Optionally add a small assertion that failed pushes still leave the current frame intact if message formatting changes force the test to be touched anyway.

### Success Criteria:

#### Automated Verification:
- [x] Session overflow unit test passes: `./mvnw -pl bifrost-spring-boot-starter -Dtest=BifrostSessionTest test`
- [x] Coordinator and integration guardrail tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorTest,ExecutionCoordinatorIntegrationTest test`
- [x] Full starter-module test suite passes: `./mvnw -pl bifrost-spring-boot-starter test`

#### Manual Verification:
- [ ] Recursive YAML loops fail quickly instead of hanging.
- [ ] Timed-out missions leave the session with no dangling execution frames.
- [ ] Existing successful nested YAML execution still works when reasonable timeout values are configured.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the end-to-end guardrail behavior matches expectations before considering the ticket complete.

## Testing Strategy

### Unit Tests:
- Extend `BifrostSessionPropertiesTest` for timeout binding defaults, overrides, and validation.
- Extend `MissionExecutionEngineTest` with deterministic timeout fixtures and happy-path regression coverage.
- Keep `BifrostSessionTest` as the direct invariant test for pre-mutation stack overflow behavior.

### Integration Tests:
- Extend `ExecutionCoordinatorTest` to cover recursive YAML overflow and timeout cleanup through the coordinator/router path.
- Extend `ExecutionCoordinatorIntegrationTest` to prove property-driven timeout wiring works in starter auto-configuration.

**Note**: Prefer a dedicated testing plan artifact created via `/testing_plan` for the full matrix of impacted areas, failing-first tests, commands to run, and exit criteria. This implementation plan intentionally keeps the testing section high-level.

### Manual Testing Steps:
1. Configure `bifrost.session.max-depth=2` and run a recursive YAML fixture that loops through `callSkill` until the overflow exception appears.
2. Configure a very small `bifrost.session.mission-timeout` and run a blocking mission fixture to confirm the timeout exception surfaces quickly.
3. Re-run an existing happy-path nested YAML mission with a normal timeout value and confirm the response, plan, and frame cleanup still succeed.

## Performance Considerations

Timeout enforcement adds executor/future overhead around each mission call, but the cost should be negligible relative to an LLM request. Prefer a lightweight virtual-thread or equivalent bounded execution strategy so timeout enforcement does not tie up platform threads while waiting on provider calls.

Cancellation should be treated as best-effort. The correctness requirement is that Bifrost stops waiting and unwinds session state locally once the timeout elapses, not that a remote provider always aborts work immediately.

## Migration Notes

This is a backward-compatible configuration expansion. Existing applications that only set `bifrost.session.max-depth` should continue working with the new default mission timeout. Teams with unusually long-running missions may need to opt into a larger `bifrost.session.mission-timeout` value after upgrade.

## References

- Original ticket: `ai/thoughts/tickets/eng-020-recursion-and-mission-timeout-guardrails.md`
- Related research: `ai/thoughts/research/2026-03-21-ENG-020-recursion-and-mission-timeout-guardrails.md`
- Existing recursion guardrail: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:207`
- Existing coordinator frame lifecycle: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:61`
- Existing nested YAML routing path: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:55`
- Existing mission execution boundary: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:49`
- Similar happy-path nested YAML coverage: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:702`
