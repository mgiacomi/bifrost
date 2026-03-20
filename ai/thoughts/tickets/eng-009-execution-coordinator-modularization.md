# Ticket: eng-009-execution-coordinator-modularization.md
## Issue: Break `ExecutionCoordinator` Into Modular Runtime Components

### Why This Ticket Exists
`ENG-007` successfully delivered the first working end-to-end coordinator loop for YAML-defined LLM skills. That was the right move because it proved the runtime path and gave us concrete behavior to anchor on.

It also exposed an architectural smell: too much runtime responsibility now sits inside or immediately around `ExecutionCoordinator`. The current implementation handles mission entry, frame lifecycle, planning bootstrapping, tool exposure, ref resolution handoff, journal updates, and the LLM call flow in one tightly coupled path.

This ticket is not about adding new product behavior. It is about splitting the coordinator into clearer modules so future work can evolve planning, tool execution, and mission flow without turning the coordinator into a god object.

This is especially important because the next likely area of iteration is planning behavior, and we do not want future planning experiments to force broad edits across unrelated runtime concerns.

---

## Problem Statement
The current coordinator path works, but it has these structural risks:

1. `ExecutionCoordinator` is accumulating orchestration, policy, and state-mutation responsibilities in one place.
2. Planning logic is too close to mission execution logic, making it harder to replace or evolve the planning approach independently.
3. Session/journal mutations are spread across runtime components in a way that may become inconsistent as new flows are added.
4. Tool-surface construction and tool execution bridging are conceptually distinct, but today they are only partially separated.
5. Future changes such as planner/executor split, multi-step replanning, richer tool result handling, or alternate mission loop strategies will be harder than necessary if we keep the current shape.

---

## Goal
Refactor the coordinator runtime into a set of smaller, explicitly-scoped services while preserving the accepted `ENG-007` behavior.

The main outcome should be:

- `ExecutionCoordinator` becomes a thin façade / entrypoint.
- Planning can evolve without rewriting mission execution.
- Tool exposure can evolve without rewriting mission state handling.
- Session and journal updates happen through consistent runtime services.
- Existing `ENG-007` tests continue to pass, with additional tests proving the new boundaries.

---

## Non-Goals
This ticket should **not** introduce:

- a new planning strategy
- planner/executor multi-agent behavior
- tag-based skill discovery
- globally visible skills
- new `ref://` semantics
- non-filesystem VFS backends
- provider-specific chat-model behavior changes
- major user-facing prompt redesigns

Those can be handled in later tickets after the runtime is modularized.

---

## High-Level Refactor Direction
The target architecture should separate concerns into something close to the following:

### 1. `ExecutionCoordinator`
Role:
- public orchestration entrypoint
- validates mission inputs
- delegates work to specialized services
- owns almost no domain logic

It should answer questions like:
- what mission is being executed?
- who are the collaborators for this mission?

It should not answer questions like:
- how is a plan requested or updated?
- how are tool callbacks built?
- how are journal mutations written?
- how does the LLM loop progress?

### 2. `ExecutionStateService`
Role:
- owns session mutation semantics for mission runtime
- manages frame push/pop
- manages active plan replacement/clearing
- manages structured journal writes for mission events

This service should centralize consistency around:
- plan state vs journal snapshots
- tool call / tool result journaling
- mission lifecycle transitions

This is the main guard against state mutation becoming fragmented across multiple classes.

### 3. `PlanningService`
Role:
- owns planning-specific model interaction
- requests initial plan when planning mode is enabled
- applies plan updates in a way isolated from mission-loop details

For this ticket, the existing behavior may stay simple, but the planning concerns should move behind a dedicated interface so later tickets can replace the internals without destabilizing the rest of the runtime.

This service should make it possible for a future ticket to swap:
- strict structured planning
- planner/executor split
- iterative replanning
- plan repair after tool failure

without redesigning the coordinator itself.

### 4. `MissionExecutionEngine` or `MissionLoop`
Role:
- owns the actual LLM execution loop for a mission
- accepts already-prepared dependencies:
  - chat client
  - visible tools
  - planning policy/service
  - mission objective
  - state service
- runs the accepted loop

This is where future loop strategies can live, without overloading the top-level coordinator.

### 5. `ToolSurfaceService`
Role:
- computes the LLM-visible skill surface for the active YAML skill
- composes YAML visibility and RBAC filtering
- returns the list of visible tool metadata for the mission

This can likely wrap or build on the existing `SkillVisibilityResolver`, but the important design point is that the coordinator should ask for a tool surface, not assemble one itself.

### 6. `ToolExecutionBridge` / `ToolCallbackFactory`
Role:
- turns visible capability metadata into Spring AI `ToolCallback`s
- routes deterministic calls through ref resolution
- delegates session/journal state updates to the state service rather than writing them ad hoc

This is the correct place for “how a capability becomes a tool the model can call.”

---

## Proposed Concrete Boundaries
The final class names can vary, but the responsibilities should land in approximately these interfaces:

```java
public interface ExecutionStateService {
    ExecutionFrame openMissionFrame(BifrostSession session, String route, Map<String, Object> parameters);
    void closeMissionFrame(BifrostSession session, ExecutionFrame frame);

    void storePlan(BifrostSession session, ExecutionPlan plan);
    void clearPlan(BifrostSession session);
    Optional<ExecutionPlan> getPlan(BifrostSession session);

    void logPlanCreated(BifrostSession session, ExecutionPlan plan);
    void logPlanUpdated(BifrostSession session, ExecutionPlan plan);
    void logToolCall(BifrostSession session, String toolName, Map<String, Object> arguments);
    void logToolResult(BifrostSession session, String toolName, Object result);
}
```

```java
public interface PlanningService {
    Optional<ExecutionPlan> initializePlan(
            BifrostSession session,
            String objective,
            String capabilityName,
            ChatClient chatClient,
            List<ToolCallback> visibleTools);

    Optional<ExecutionPlan> markToolStarted(
            BifrostSession session,
            String toolName);

    Optional<ExecutionPlan> markToolCompleted(
            BifrostSession session,
            String toolName,
            @Nullable Object result);
}
```

```java
public interface ToolSurfaceService {
    List<CapabilityMetadata> visibleToolsFor(
            String rootSkillName,
            @Nullable Authentication authentication);
}
```

```java
public interface ToolCallbackFactory {
    List<ToolCallback> createToolCallbacks(
            BifrostSession session,
            List<CapabilityMetadata> capabilities);
}
```

```java
public interface MissionExecutionEngine {
    String executeMission(
            BifrostSession session,
            String skillName,
            String objective,
            ChatClient chatClient,
            List<ToolCallback> visibleTools,
            @Nullable Authentication authentication);
}
```

The key design principle is that each component should be testable in isolation and should only own one kind of policy.

---

## Required Refactor Outcomes

### A. Thin coordinator
Refactor `ExecutionCoordinator` so it primarily:
- resolves the active YAML skill
- obtains the validated `EffectiveSkillExecutionConfiguration`
- creates the `ChatClient`
- asks the tool-surface service for visible tools
- asks the tool-callback factory to build callbacks
- delegates mission execution to the mission engine

`ExecutionCoordinator` should no longer directly own:
- planning state mutation rules
- tool progress mutation rules
- journaling details
- callback execution semantics

### B. Centralized session/journal mutation rules
All runtime writes to:
- execution frames
- active execution plan
- plan journal entries
- tool journal entries

should flow through a dedicated state-focused service or a similarly narrow abstraction.

This is important because future behaviors like retries, replanning, cancellation, and mission checkpoints will otherwise duplicate mutation logic.

### C. Planning isolation
Even if the planning implementation remains simplistic for now, the planning interaction must move behind a dedicated service boundary.

The service should make it obvious where later tickets can change:
- how the plan is requested
- how it is parsed
- when replanning happens
- how task progress maps to real tool execution

### D. Tool exposure vs tool execution separation
There should be a clean distinction between:
- which tools are visible
- how visible tools are adapted into executable callbacks

This means the current visibility behavior should stay, but the runtime should separate policy from execution plumbing.

### E. Preserve `ENG-007` behavior
This refactor must preserve the accepted behavior from the previous ticket:
- YAML-defined skills remain the LLM-facing surface
- `allowed_skills` plus RBAC still gate tool visibility
- `ref://` resolution remains strict scalar-only resolution
- typed plan state still lives in `BifrostSession`
- structured plan journaling remains separate from session state
- stack unwind behavior remains safe

---

## Files Likely Impacted
This list is directional, not mandatory:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolCallbackAdapter.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java`
- new packages or classes under:
  - `.../core/`
  - `.../runtime/`
  - `.../planning/`
  - `.../tool/`

If introducing new packages makes the boundaries clearer, that is encouraged.

---

## Architectural Guidance

### Preferred package direction
Consider moving coordinator-adjacent runtime logic into a clearer runtime-oriented structure, for example:

- `com.lokiscale.bifrost.runtime`
- `com.lokiscale.bifrost.runtime.planning`
- `com.lokiscale.bifrost.runtime.tool`
- `com.lokiscale.bifrost.runtime.state`

This is not required, but a more explicit package structure would make the architecture easier to understand than leaving everything in `core`.

### Preferred dependency direction
Try to keep dependency flow roughly like this:

- `ExecutionCoordinator`
  -> `ToolSurfaceService`
  -> `ToolCallbackFactory`
  -> `MissionExecutionEngine`
  -> `PlanningService`
  -> `ExecutionStateService`

Not every component needs to depend on every other component. Prefer one-way dependencies and small constructor surfaces.

### Avoid
- static utility sprawl
- “manager” classes that still hide multiple concerns
- moving code into new classes without actually reducing responsibility
- putting planning logic into callback adapters
- putting session mutation logic into tool visibility code

---

## Acceptance Criteria

### Functional
- `ExecutionCoordinator` remains the entrypoint for executing a YAML-defined skill mission.
- The accepted `ENG-007` mission flow still works without behavioral regression.
- Planning initialization happens through a planning-specific service boundary.
- Runtime session and journal mutations happen through a state-specific service boundary.
- Tool visibility and tool callback adaptation happen through distinct boundaries.

### Structural
- `ExecutionCoordinator` is materially smaller and easier to understand.
- Mission execution behavior can be followed by reading a small number of focused classes with clearly named responsibilities.
- A future planning redesign can be implemented primarily inside the planning module/service without rewriting the coordinator.

### Testing
- Existing `ENG-007` unit and integration tests continue to pass or are cleanly updated to reflect the new modular boundaries without weakening assertions.
- New tests verify the new components in isolation:
  - state service tests
  - planning service tests
  - tool callback factory / bridge tests
  - mission execution engine tests
- At least one integration test still proves the full accepted mission path end to end.

---

## Suggested Test Additions

### 1. `ExecutionStateServiceTest`
Should verify:
- frame lifecycle is consistent
- plan creation/update/clearing is consistent
- journal writes remain structured and correct

### 2. `PlanningServiceTest`
Should verify:
- planning initialization is isolated from coordinator wiring
- task status updates happen through well-defined semantics
- planning-disabled behavior is explicit and testable

### 3. `ToolCallbackFactoryTest`
Should verify:
- visible capabilities become tool callbacks correctly
- strict `ref://` resolution still happens before deterministic execution
- tool call / result mutations are routed through the state service

### 4. `MissionExecutionEngineTest`
Should verify:
- the engine performs the accepted mission loop
- planning bootstrap and final execution stay decoupled
- the engine does not own visibility policy

### 5. `ExecutionCoordinatorIntegrationTest`
Should continue to verify:
- full mission path still works
- tool visibility stays constrained
- plan state and journal entries remain reconstructable

---

## Implementation Notes for a Future Session

### Recommended order of work
1. Introduce new interfaces and default implementations without changing behavior.
2. Move session/journal mutation logic behind the state service.
3. Move planning initialization/update logic behind the planning service.
4. Move tool callback creation behind a dedicated factory/bridge.
5. Introduce a mission execution engine and slim down the coordinator.
6. Update auto-configuration.
7. Adjust tests to target the new boundaries.
8. Run full verification.

### Refactor style
Prefer incremental extraction over big-bang rewrite.

The safest approach is:
- preserve current tests
- move one concern at a time
- keep the coordinator behavior stable while extracting logic
- only rename/move packages once boundaries are stable

---

## Open Questions
These do not need to be resolved in this ticket unless they block clean modularization:

1. Should the mission loop eventually support iterative replanning as a first-class concept?
2. Should plan progress continue to be inferred from tool calls, or should it become model-declared / runtime-validated?
3. Should tool-call journaling remain a state service concern forever, or later move into a dedicated mission audit component?
4. Should runtime code move into a new `runtime` package now, or should that wait until planning architecture stabilizes?

---

## Definition of Done
This ticket is done when:

- the coordinator has been decomposed into focused runtime services
- behavior from `ENG-007` is preserved
- tests clearly reflect the new boundaries
- a new session can understand the runtime by reading the smaller components instead of reverse-engineering a single orchestration class

