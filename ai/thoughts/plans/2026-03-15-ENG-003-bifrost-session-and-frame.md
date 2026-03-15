# ENG-003 Bifrost Session and Frame Implementation Plan

## Overview

Implement the first session-aware execution primitive in `bifrost-spring-boot-starter` by introducing a mission-scoped `BifrostSession`, a stack-based `ExecutionFrame`, fail-fast recursion protection, and a Java 21 session-scoping mechanism that works cleanly with virtual threads.

## Current State Analysis

The starter currently stops at capability discovery and invocation plumbing. `BifrostAutoConfiguration` only wires `CapabilityRegistry` and `SkillMethodBeanPostProcessor`, so there is no existing session lifecycle infrastructure to extend (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:12-28`). Capability invocation is stateless and wraps Spring AI tool callbacks directly, which means there is not yet any execution-bound context for recursive skill tracking or per-mission isolation (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:17-80`).

The implemented concurrency model today is limited to `ConcurrentHashMap` in `InMemoryCapabilityRegistry` plus concurrent tests around registry registration and reads (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistry.java:8-47`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistryTest.java:65-112`). No `BifrostSession`, `ExecutionFrame`, `BifrostStackOverflowException`, `ThreadLocal`, `ScopedValue`, or `ReentrantLock` usage exists yet in shipped starter code, matching the ENG-003 research findings in `ai/thoughts/research/2026-03-15-ENG-003-bifrost-session-and-frame.md`.

## Desired End State

After this work, the starter exposes a concrete `BifrostSession` domain object with:
- a unique session identifier,
- a lock-protected mutable execution stack,
- push/pop APIs that enforce a configured max depth,
- static `BifrostSession.getCurrentSession()` access inside an active execution boundary that fails clearly outside one,
- and a Spring-managed runner/service that establishes and tears down those boundaries around user code.

Verification of the final state is straightforward:
- virtual-thread-based tests prove that concurrent flows see their own current session and do not leak state across boundaries,
- stack tests prove the max-depth circuit breaker throws `BifrostStackOverflowException`,
- configuration tests prove the default `MAX_DEPTH` can be overridden through Spring Boot properties,
- and auto-configuration tests prove the session infrastructure is available in the starter context.

### Key Discoveries:
- The current auto-configuration surface is intentionally small, so ENG-003 can add new infrastructure beans without untangling existing lifecycle code (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:16-27`).
- Existing tests use focused JUnit 5 unit tests in the `com.lokiscale.bifrost.core` package, which is the right home for new session and context coverage (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java:14-106`).
- The Phase 2 design explicitly calls for virtual-thread-friendly locking and a thread-local or scoped-bean session model. During implementation we switched from `ScopedValue` to a disciplined `ThreadLocal` holder so the starter does not require Java preview features while still preserving per-virtual-thread isolation and nested boundary restoration (`ai/thoughts/phases/phase2.md:29-34`).

## What We're NOT Doing

- Implementing `ExecutionJournal`; that is reserved for ENG-004.
- Building `ExecutionCoordinator`, sub-agent orchestration, or Spring AI chat-model routing; those belong to later tickets such as ENG-007.
- Adding persistence, resumability, or cross-request session restoration.
- Introducing a custom Spring scope unless the `ThreadLocal` holder plus a runner bean proves insufficient during implementation.

## Implementation Approach

Use a small `ThreadLocal<BifrostSession>` holder as the primary session-boundary mechanism. That keeps the implementation compatible with the project Java baseline without enabling preview APIs, while still fitting virtual-thread execution because each virtual thread has its own thread-local state. Pair it with a small Spring-managed `BifrostSessionRunner` bean that creates new sessions, executes callbacks inside a `try/finally` session boundary, restores any previously bound session for nested flows, and makes the feature usable both through injection and through static access via `BifrostSession.getCurrentSession()`.

Inside `BifrostSession`, keep mutable stack operations behind a `ReentrantLock` and store frames in an `ArrayDeque<ExecutionFrame>`. Depth enforcement should happen before the frame is pushed so failures are fail-fast and do not leave partially-mutated state behind. `BifrostSession` should own session ID generation by default so callers do not have to supply identifiers for ordinary missions. `MAX_DEPTH` should have a starter default exposed through Spring Boot configuration properties and still remain directly settable in tests and low-level constructors.

`ExecutionFrame` should be richer than only route plus parameters, because later phase docs point toward nested execution, recursive summarization, and orchestration tracing (`ai/thoughts/phases/README.md:10`, `ai/thoughts/phases/README.md:52-57`, `ai/thoughts/phases/phase5.md:11-15`). The recommended v1 shape is still intentionally small: `frameId`, optional `parentFrameId`, `operationType`, `route`, immutable `parameters`, and `openedAt`. That gives future work stable correlation hooks without prematurely embedding journal entries, execution summaries, or security state into the frame itself.

## Phase 1: Add Core Session Domain Types

### Overview

Create the new core types that represent session state and execution-frame stack tracking, including max-depth enforcement and lock-based mutation.

### Changes Required:

#### 1. Session and frame model
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
**Changes**: Add the mission-scoped session object with self-generated `sessionId`, `maxDepth`, `ReentrantLock`, and an internal `Deque<ExecutionFrame>` plus APIs for `pushFrame`, `popFrame`, `peekFrame`, `getFramesSnapshot`, and `getCurrentSession`. `getCurrentSession` and empty-stack `popFrame` should fail fast instead of returning `null`.

```java
public final class BifrostSession {
    private final String sessionId;
    private final int maxDepth;
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<ExecutionFrame> frames = new ArrayDeque<>();

    public void pushFrame(ExecutionFrame frame) {
        lock.lock();
        try {
            if (frames.size() >= maxDepth) {
                throw new BifrostStackOverflowException(sessionId, maxDepth, frame.route());
            }
            frames.push(frame);
        }
        finally {
            lock.unlock();
        }
    }

    public ExecutionFrame popFrame() {
        lock.lock();
        try {
            if (frames.isEmpty()) {
                throw new IllegalStateException("Cannot pop execution frame from an empty session stack.");
            }
            return frames.pop();
        }
        finally {
            lock.unlock();
        }
    }
}
```

#### 2. Execution frame value object
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionFrame.java`
**Changes**: Introduce an immutable POJO or record that captures the frame id, optional parent-frame id, operation kind, routed method/skill name, a defensive copy of parameters, and an `openedAt` timestamp. Keep journaling fields and execution summaries out of scope for ENG-003.

```java
public record ExecutionFrame(
        String frameId,
        String parentFrameId,
        OperationType operationType,
        String route,
        Map<String, Object> parameters,
        Instant openedAt) {
    public ExecutionFrame {
        frameId = Objects.requireNonNull(frameId, "frameId must not be null");
        operationType = Objects.requireNonNull(operationType, "operationType must not be null");
        route = Objects.requireNonNull(route, "route must not be null");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        openedAt = Objects.requireNonNull(openedAt, "openedAt must not be null");
    }
}
```

#### 3. Overflow exception
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostStackOverflowException.java`
**Changes**: Add a focused runtime exception for recursion-limit violations with a message that includes the session id, attempted route, and configured depth.

#### 4. Session configuration properties
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java`
**Changes**: Add `@ConfigurationProperties` for session settings with a starter default `maxDepth` and validation for positive values so `application.yml` can override the recursion limit cleanly.

### Success Criteria:

#### Automated Verification:
- [x] New core classes compile successfully: `mvn -pl bifrost-spring-boot-starter -DskipTests compile`
- [x] Session stack tests pass: `mvn -pl bifrost-spring-boot-starter -Dtest=BifrostSessionTest,ExecutionFrameTest test`

#### Manual Verification:
- [ ] Code review confirms all mutable session state is guarded by `ReentrantLock` rather than `synchronized`.
- [ ] Code review confirms frame push failures do not mutate stack state when max depth is exceeded.
- [ ] Code review confirms the frame shape is limited to correlation metadata and does not prematurely absorb journal or security payloads.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Add Session Boundary and Spring Wiring

### Overview

Establish a reusable execution-boundary mechanism so the current session can be resolved statically or through dependency injection without threading it through every method signature.

### Changes Required:

#### 1. Scoped session holder
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSessionHolder.java`
**Changes**: Add a package-level holder around `ThreadLocal<BifrostSession>` with `runWithSession`, `callWithSession`, `currentSession`, and `requireCurrentSession`. Restore any previously-bound session in `finally` blocks so nested boundaries remain correct and no session leaks survive after execution.

```java
final class BifrostSessionHolder {
    private static final ThreadLocal<BifrostSession> CURRENT = new ThreadLocal<>();

    static <T> T callWithSession(BifrostSession session, Supplier<T> action) {
        BifrostSession previous = CURRENT.get();
        CURRENT.set(session);
        try {
            return action.get();
        }
        finally {
            if (previous == null) {
                CURRENT.remove();
            }
            else {
                CURRENT.set(previous);
            }
        }
    }
}
```

#### 2. Spring-facing runner/service
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSessionRunner.java`
**Changes**: Add an injectable service that creates a new `BifrostSession` with starter defaults and runs user work inside the scoped boundary. This becomes the preferred integration point for future orchestration components, and it should rely on `BifrostSession` to generate the session id unless a test-only override is explicitly needed.

#### 3. Auto-configuration updates
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
**Changes**: Register `BifrostSessionRunner` as an infrastructure bean and wire `BifrostSessionProperties` so the default `MAX_DEPTH` can be overridden from `application.yml`.

### Success Criteria:

#### Automated Verification:
- [x] Scoped session tests pass: `mvn -pl bifrost-spring-boot-starter -Dtest=BifrostSessionHolderTest,BifrostSessionRunnerTest,BifrostSessionPropertiesTest test`
- [x] Starter auto-configuration tests pass with the new infrastructure bean: `mvn -pl bifrost-spring-boot-starter -Dtest=BifrostAutoConfigurationTests test`

#### Manual Verification:
- [ ] `BifrostSession.getCurrentSession()` is only valid inside a runner-established boundary and fails clearly outside it.
- [ ] The injected runner API is simple enough to use from future orchestration code without leaking session-plumbing concerns into business methods.
- [ ] `application.yml` override semantics for `MAX_DEPTH` are straightforward and documented in code-level configuration metadata.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Add Virtual-Thread and Concurrency Coverage

### Overview

Prove the session implementation behaves correctly under the concurrency model called out by the ticket: multiple concurrent virtual threads, isolated session boundaries, and fail-fast depth protection.

### Changes Required:

#### 1. Session unit tests
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionTest.java`
**Changes**: Add tests for push/pop behavior, defensive parameter copying, empty-stack handling, max-depth overflow, and fail-fast access outside a scoped session boundary.

#### 2. Session-boundary isolation tests
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionRunnerTest.java`
**Changes**: Use `Executors.newVirtualThreadPerTaskExecutor()` to run concurrent tasks that each open a distinct session and assert that `BifrostSession.getCurrentSession()` resolves only to that task's session and that frame mutations remain isolated per task.

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    Future<String> one = executor.submit(() ->
        sessionRunner.callWithNewSession(session -> BifrostSession.getCurrentSession().getSessionId()));
    Future<String> two = executor.submit(() ->
        sessionRunner.callWithNewSession(session -> BifrostSession.getCurrentSession().getSessionId()));
    assertThat(one.get()).isNotEqualTo(two.get());
}
```

#### 3. Regression coverage for auto-configured starter usage
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
**Changes**: Extend the existing context assertions so the starter test fails if the session runner bean or session properties binding is no longer auto-configured.

### Success Criteria:

#### Automated Verification:
- [x] All starter-module tests pass: `mvn -pl bifrost-spring-boot-starter test`
- [x] Full repository test suite still passes: `mvn test`

#### Manual Verification:
- [ ] Review confirms concurrent virtual-thread tests exercise distinct session boundaries rather than reused worker threads.
- [ ] Review confirms the public API names and exception messages are understandable enough for later framework consumers.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

## Testing Strategy

### Unit Tests:
- Verify `BifrostSession` initializes with a generated id, empty frame stack, and configured max depth.
- Verify `pushFrame` and `popFrame` preserve LIFO ordering and leave the stack unchanged on overflow.
- Verify `ExecutionFrame` carries stable correlation metadata and makes defensive, immutable parameter snapshots.
- Verify `BifrostSession.getCurrentSession()` throws a clear exception outside a scoped boundary.
- Verify popping an empty stack throws a clear exception.

### Integration Tests:
- Validate the Spring Boot starter context auto-configures `BifrostSessionRunner` alongside the existing registry beans.
- Validate multiple concurrent virtual-thread executions each observe their own current session and isolated stack state.
- Validate `bifrost.session.max-depth` overrides the starter default from Spring Boot configuration.

**Note**: Prefer a dedicated testing plan artifact created via `/testing_plan` before implementation begins so failing-test-first sequencing and exact commands are captured separately.

### Manual Testing Steps:
1. Run the starter-module tests and inspect the new concurrency-focused test output for flakiness across repeated runs.
2. Read the session classes to confirm every mutable stack mutation is wrapped in `ReentrantLock` and not `synchronized`.
3. Confirm the API surface is small and future tickets can open a session without changing existing `@SkillMethod` signatures.
4. Confirm the configuration default is sensible and that a sample `application.yml` override would be unambiguous for framework consumers.

## Performance Considerations

`ReentrantLock` keeps the ticket’s virtual-thread requirement intact by avoiding monitor pinning risks from `synchronized` blocks during potentially blocking work. A disciplined `ThreadLocal` holder keeps the boundary mechanism compatible with non-preview Java 21 builds, and explicit restoration in `finally` blocks avoids session leaks across nested or reused execution contexts. The session stack should expose snapshots rather than its live `Deque` so callers cannot bypass locking or mutate internal state. Keeping `ExecutionFrame` limited to correlation metadata also avoids creating an oversized object that later journaling or telemetry work would need to unwind.

## Migration Notes

This is additive starter work with no existing session API to preserve, so migration risk is low. The main compatibility concern is keeping the new infrastructure orthogonal to the current capability registry so existing tests and sample application startup remain unchanged except for the presence of the new bean.

## References

- Original ticket: `ai/thoughts/tickets/eng-003-bifrost-session-and-frame.md`
- Related research: `ai/thoughts/research/2026-03-15-ENG-003-bifrost-session-and-frame.md`
- Existing auto-configuration baseline: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:12-28`
- Existing invocation flow: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:17-80`
- Existing concurrency test style: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistryTest.java:65-112`
- Historical phase intent: `ai/thoughts/phases/phase2.md:29-34`
