# ENG-009 ExecutionCoordinator Modularization Implementation Plan

## Overview

Refactor the current YAML-skill mission runtime so `ExecutionCoordinator` becomes a thin entrypoint that delegates state mutation, planning, tool adaptation, and mission-loop execution to focused runtime services. The goal is to preserve the accepted `ENG-007` behavior while creating stable seams for later planning and mission-loop changes.

## Current State Analysis

`ExecutionCoordinator` currently validates the mission, enforces root RBAC, clears prior plan state, pushes the execution frame, creates the `ChatClient`, resolves visible tools, bootstraps planning, builds the execution prompt, performs the final tool-enabled LLM call, and unwinds the frame stack in one class (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:48`).

Plan state and journal state are stored directly on `BifrostSession`, which currently exposes both mutation and logging methods for plans, frames, tool events, and errors (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:62`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:90`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:135`). That makes the session a useful state container, but it also means mutation policy is spread across the coordinator, callback adapter, and router.

Tool visibility and tool execution are only partially separated today. Visibility is resolved by `DefaultSkillVisibilityResolver` from YAML `allowed_skills` plus RBAC (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:25`), while `CapabilityToolCallbackAdapter` both creates Spring AI `ToolCallback`s and mutates plan/journal state around execution (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolCallbackAdapter.java:32`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolCallbackAdapter.java:53`).

Nested YAML skill execution also preserves the parent plan inside `CapabilityExecutionRouter`, which snapshots the plan, routes back through the coordinator, and restores the parent plan in `finally` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:47`). That behavior must survive the refactor, but the state semantics should move behind an explicit runtime boundary.

## Desired End State

The runtime is organized around a small set of single-purpose services:

- `ExecutionCoordinator` validates the requested mission, resolves the YAML skill and capability, creates the chat client, asks for visible tools, builds tool callbacks, and delegates mission execution.
- `ExecutionStateService` owns frame lifecycle, active plan replacement/clearing, parent-plan snapshot/restore for nested YAML execution, and all structured journal writes.
- `PlanningService` owns planning-mode initialization and plan progress updates tied to tool start/completion/failure.
- `MissionExecutionEngine` owns the accepted mission loop, including planning bootstrap and the final tool-enabled model call, but not visibility policy.
- `ToolSurfaceService` exposes the visible YAML tool surface for a mission by wrapping existing visibility logic.
- `ToolCallbackFactory` converts visible capabilities into `ToolCallback`s and delegates plan/journal side effects to the planning and state services.

The user-visible behavior after the refactor remains unchanged:

- YAML-defined skills remain the LLM-facing surface.
- `allowed_skills` and RBAC still gate visibility.
- Strict scalar `ref://` resolution still happens before deterministic invocation.
- `BifrostSession` remains the holder for typed plan state and the journal payloads.
- Nested YAML execution still restores the parent mission plan.

### Key Discoveries:

- `ExecutionCoordinator` currently owns nearly the whole mission flow from validation through prompt execution, which is the main modularization pressure point (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:48`).
- Tool invocation side effects are coupled directly to callback creation today, including unplanned execution journaling, task start/completion, blocked-task handling, and error logging (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolCallbackAdapter.java:53`).
- The accepted behavior is already pinned by unit and integration tests for planning-enabled flow, planning-disabled flow, blocked tasks, ambiguous tool linkage, nested YAML routing, and strict text/binary ref resolution (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:99`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:197`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:344`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:424`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:629`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java:37`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java:104`).
- Auto-configuration currently wires the runtime directly to `CapabilityToolCallbackAdapter`, `CapabilityExecutionRouter`, and `ExecutionCoordinator`, so bean wiring will need to change as part of the extraction (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:118`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:140`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:187`).

## What We're NOT Doing

- Changing the planning strategy itself.
- Adding iterative replanning or planner/executor multi-agent behavior.
- Expanding tool visibility beyond current YAML `allowed_skills` and RBAC behavior.
- Changing `ref://` semantics or non-filesystem VFS behavior.
- Redesigning provider-specific chat model behavior.
- Redesigning mission prompts beyond what is needed to preserve the current accepted flow.

## Implementation Approach

Use incremental extraction, not a big-bang rewrite. Keep the runtime behavior green at every step by first introducing interfaces and default implementations that wrap the current semantics, then moving responsibility one concern at a time. Prefer a new `com.lokiscale.bifrost.runtime` package family so the resulting design is obvious from the package structure rather than hidden behind renamed classes in `core`.

Recommended package layout:

- `com.lokiscale.bifrost.runtime`
- `com.lokiscale.bifrost.runtime.state`
- `com.lokiscale.bifrost.runtime.planning`
- `com.lokiscale.bifrost.runtime.tool`

Recommended dependency direction:

- `ExecutionCoordinator`
- `ToolSurfaceService`
- `ToolCallbackFactory`
- `MissionExecutionEngine`
- `PlanningService`
- `ExecutionStateService`

`CapabilityExecutionRouter` should stay focused on RBAC, nested YAML routing, and `ref://`-aware deterministic execution, but it should no longer be responsible for direct plan restore semantics. That restore behavior should move into the state service so nested execution and future retry/replanning flows share one mutation policy.

## Phase 1: Extract Runtime State Boundaries

### Overview

Create the dedicated state abstraction first, because it is the safest seam and reduces duplication before any larger mission-loop extraction.

### Changes Required:

#### 1. Add runtime state interfaces and default implementation
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/ExecutionStateService.java`
**Changes**: Add a narrow interface for frame lifecycle, plan storage, parent-plan snapshot/restore, and structured journal writes.

```java
public interface ExecutionStateService {
    ExecutionFrame openMissionFrame(BifrostSession session, String route, Map<String, Object> parameters);
    void closeMissionFrame(BifrostSession session, ExecutionFrame frame);
    void storePlan(BifrostSession session, ExecutionPlan plan);
    void clearPlan(BifrostSession session);
    Optional<ExecutionPlan> currentPlan(BifrostSession session);
    PlanSnapshot snapshotPlan(BifrostSession session);
    void restorePlan(BifrostSession session, PlanSnapshot snapshot);
    void logPlanCreated(BifrostSession session, ExecutionPlan plan);
    void logPlanUpdated(BifrostSession session, ExecutionPlan plan);
    void logToolCall(BifrostSession session, TaskExecutionEvent event);
    void logUnplannedToolCall(BifrostSession session, TaskExecutionEvent event);
    void logToolResult(BifrostSession session, TaskExecutionEvent event);
    void logError(BifrostSession session, Map<String, Object> payload);
}
```

#### 2. Implement the default state service as a thin wrapper over `BifrostSession`
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java`
**Changes**: Centralize the existing session mutations and journal writes without changing `BifrostSession` storage shape.

#### 3. Move frame open/close and plan clear/store calls out of the coordinator
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
**Changes**: Replace direct `session.clearExecutionPlan()`, `session.pushFrame(...)`, `session.popFrame()`, and plan journaling calls with `ExecutionStateService`.

### Success Criteria:

#### Automated Verification:
- [x] Starter module tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter test`
- [x] Targeted session-state regression tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostSessionPlanStateTest,ExecutionCoordinatorTest test`

#### Manual Verification:
- [ ] The extracted state service API reads as the only intended place for runtime state mutation.
- [ ] The coordinator no longer performs direct frame push/pop or plan storage calls.
- [ ] Nested mission state handling still has a single obvious home in the runtime design.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the extracted state boundary is clear before proceeding to the next phase.

---

## Phase 2: Isolate Planning Semantics

### Overview

Move plan bootstrap and tool-progress mutation rules behind a dedicated planning boundary while preserving the current task-linking behavior.

### Changes Required:

#### 1. Add planning service interface and default implementation
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/PlanningService.java`
**Changes**: Add methods for plan initialization and transitions for tool start, completion, and failure.

```java
public interface PlanningService {
    Optional<ExecutionPlan> initializePlan(
            BifrostSession session,
            String objective,
            String capabilityName,
            ChatClient chatClient,
            List<ToolCallback> visibleTools);

    Optional<ExecutionPlan> markToolStarted(BifrostSession session, CapabilityMetadata capability, Map<String, Object> arguments);
    Optional<ExecutionPlan> markToolCompleted(BifrostSession session, String taskId, String capabilityName, @Nullable Object result);
    Optional<ExecutionPlan> markToolFailed(BifrostSession session, String taskId, String capabilityName, RuntimeException ex);
}
```

#### 2. Move current plan-task linkage and updates out of the callback adapter
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolCallbackAdapter.java`
**Changes**: Strip out direct session plan mutation and plan journaling; instead ask `PlanningService` and `ExecutionStateService` to perform those changes.

#### 3. Keep prompt construction behavior stable while removing planning logic from the coordinator
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
**Changes**: The coordinator should no longer perform the planning prompt itself. That work moves into the mission engine in Phase 4, but this phase should already place the planning semantics behind an interface.

### Success Criteria:

#### Automated Verification:
- [x] Planning-related coordinator tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorTest test`
- [x] New planning service tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=PlanningServiceTest test`

#### Manual Verification:
- [ ] A future planning redesign would obviously land in `runtime/planning` without coordinator rewrites.
- [ ] Planning-disabled behavior remains explicit rather than being inferred from missing state.
- [ ] The rules for linked, unlinked, completed, and blocked tasks are described by the planning service rather than callback plumbing.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the planning boundary is correct before proceeding to the next phase.

---

## Phase 3: Separate Tool Surface From Tool Execution

### Overview

Turn the current visibility-plus-callback path into two explicit services: one for deciding what is visible and one for adapting visible capabilities into executable callbacks.

### Changes Required:

#### 1. Add tool surface wrapper around current visibility resolution
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/ToolSurfaceService.java`
**Changes**: Introduce a runtime-facing service that delegates to the current visibility resolver so the coordinator no longer talks directly to the visibility implementation.

#### 2. Replace `CapabilityToolCallbackAdapter` with a runtime tool callback factory
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/ToolCallbackFactory.java`
**Changes**: Introduce a factory that creates `ToolCallback`s and routes tool call/result/error side effects through the state and planning services.

```java
public interface ToolCallbackFactory {
    List<ToolCallback> createToolCallbacks(
            BifrostSession session,
            List<CapabilityMetadata> capabilities,
            @Nullable Authentication authentication);
}
```

#### 3. Preserve router behavior while moving state mutation responsibility out
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java`
**Changes**: Keep execution-time RBAC, nested YAML dispatch, and `ref://`-aware deterministic invocation. Replace direct parent-plan save/restore with `ExecutionStateService.snapshotPlan(...)` and `restorePlan(...)`.

### Success Criteria:

#### Automated Verification:
- [x] Visibility and callback adaptation tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=SkillVisibilityResolverTest,ToolCallbackFactoryTest,ExecutionCoordinatorTest test`
- [x] Ref-resolution and routing regression tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=RefResolverTest,ExecutionCoordinatorIntegrationTest test`

#### Manual Verification:
- [ ] It is easy to answer "which tools are visible?" without reading callback execution logic.
- [ ] It is easy to answer "how does a visible tool become executable?" without reading visibility policy.
- [ ] Nested YAML execution still clearly preserves parent mission state through the state boundary.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the tool-surface and tool-execution split is clear before proceeding to the next phase.

---

## Phase 4: Introduce MissionExecutionEngine and Thin the Coordinator

### Overview

Create the dedicated mission loop service and reduce `ExecutionCoordinator` to a small orchestration entrypoint.

### Changes Required:

#### 1. Introduce mission execution engine
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/MissionExecutionEngine.java`
**Changes**: Own the accepted loop: planning bootstrap when enabled, prompt construction from current plan state, and the final `ChatClient` execution with tool callbacks attached.

```java
public interface MissionExecutionEngine {
    String executeMission(
            BifrostSession session,
            String skillName,
            String objective,
            ChatClient chatClient,
            List<ToolCallback> visibleTools,
            boolean planningEnabled,
            @Nullable Authentication authentication);
}
```

#### 2. Slim `ExecutionCoordinator` down to wiring logic
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
**Changes**: Keep mission validation, YAML/capability lookup, root authorization, chat-client creation, tool-surface lookup, callback creation, and engine delegation. Remove execution prompt construction and direct planning/state behavior.

#### 3. Update auto-configuration to wire runtime services explicitly
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
**Changes**: Register default beans for `ExecutionStateService`, `PlanningService`, `ToolSurfaceService`, `ToolCallbackFactory`, and `MissionExecutionEngine`, then construct `ExecutionCoordinator` from those services.

### Success Criteria:

#### Automated Verification:
- [x] Full starter test suite passes: `.\mvnw.cmd -pl bifrost-spring-boot-starter test`
- [x] Full project test suite passes: `.\mvnw.cmd test`

#### Manual Verification:
- [ ] `ExecutionCoordinator` reads as a thin facade and no longer as the mission-loop implementation.
- [ ] Mission execution behavior can be understood by reading a small set of focused runtime classes.
- [ ] Auto-configuration makes the runtime architecture explicit.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the coordinator is thin enough and the mission-loop ownership is in the right place before proceeding to the next phase.

---

## Phase 5: Rebalance and Expand Tests Around the New Boundaries

### Overview

Move assertions toward the new components without weakening the existing end-to-end behavior checks.

### Changes Required:

#### 1. Add focused unit tests for new runtime services
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/state/ExecutionStateServiceTest.java`
**Changes**: Verify frame lifecycle, plan store/clear/restore, and journal writes.

#### 2. Add focused planning and callback tests
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/planning/PlanningServiceTest.java`
**Changes**: Verify plan bootstrap, planning-disabled behavior, task-link start/completion/failure semantics, and task-status journaling.

#### 3. Keep the coordinator integration test as the behavior anchor
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java`
**Changes**: Update wiring expectations only as needed; preserve end-to-end assertions for visible tools, plan state, journaling, and strict text/binary ref resolution.

### Success Criteria:

#### Automated Verification:
- [x] New state service tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=ExecutionStateServiceTest test`
- [x] New planning and callback tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=PlanningServiceTest,ToolCallbackFactoryTest,MissionExecutionEngineTest test`
- [x] Existing coordinator and integration tests still pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorTest,ExecutionCoordinatorIntegrationTest test`

#### Manual Verification:
- [ ] The new tests map cleanly to the new runtime boundaries rather than to internal implementation details.
- [ ] At least one integration test still proves the accepted mission path end to end.
- [ ] Test names make the intended ownership of state, planning, tools, and mission loop obvious.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the test suite reflects the new architecture before calling the ticket complete.

## Testing Strategy

### Unit Tests:

- `ExecutionStateServiceTest` for frame open/close, plan store/clear, plan snapshot/restore, and journal event mapping.
- `PlanningServiceTest` for planning bootstrap, planning-disabled no-op behavior, task linking, task completion, task failure, and stale-plan transitions.
- `ToolCallbackFactoryTest` for tool definition shape, unplanned tool execution journaling, linked task progression, and error propagation.
- `MissionExecutionEngineTest` for prompt construction from plan state and the accepted mission loop with planning enabled and disabled via an explicit mission input.

### Integration Tests:

- Preserve end-to-end `ExecutionCoordinatorIntegrationTest` coverage for visible tools, plan journaling, strict text `ref://` resolution, and binary `ref://` resolution.
- Preserve nested YAML routing behavior currently covered in `ExecutionCoordinatorTest`.

**Note**: Prefer a dedicated testing plan artifact created via `/testing_plan` for the full failing-test-first breakdown and final exit criteria before implementation starts.

### Manual Testing Steps:

1. Read the final `ExecutionCoordinator` implementation and confirm it only orchestrates dependency calls plus validation/authorization.
2. Read the runtime package structure and confirm each class has one primary responsibility.
3. Trace one successful planning-enabled mission from coordinator to engine to callback execution and verify the state transitions are discoverable in one place.
4. Trace one nested YAML mission and verify parent plan restoration is handled by the state service rather than ad hoc session calls.

## Performance Considerations

The refactor should not materially change runtime behavior or add extra model calls. Keep new service boundaries lightweight and avoid copying large plan or journal payloads beyond what the current immutable-plan/session APIs already require. Parent-plan snapshot/restore should continue to preserve the current constant-size semantics of storing or clearing the active plan reference.

## Migration Notes

This is an internal refactor with no expected public API or configuration migration. `BifrostSession` should remain backward-compatible as the session serialization shape for frames, journal entries, and active plan state. If any package moves are introduced, preserve Spring bean wiring through auto-configuration so existing starter consumers do not need changes.

## References

- Original ticket: `ai/thoughts/tickets/eng-009-execution-coordinator-modularization.md`
- Related research: `ai/thoughts/research/2026-03-20-ENG-009-execution-coordinator-modularization.md`
- Current coordinator flow: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:48`
- Current tool callback mutation flow: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolCallbackAdapter.java:53`
- Current nested YAML routing flow: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:47`
- Current visibility policy: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:25`
- Runtime wiring today: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:118`
