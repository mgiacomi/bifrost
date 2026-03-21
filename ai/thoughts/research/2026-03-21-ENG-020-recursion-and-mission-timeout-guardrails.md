---
date: 2026-03-21T12:04:55-07:00
researcher: Codex (GPT-5)
git_commit: 2a5366da719c886d6db9f8e249615979a2166e98
branch: main
repository: bifrost
topic: "Research for eng-020 recursion and mission timeout guardrails"
tags: [research, codebase, session, execution-coordinator, mission-execution, guardrails]
status: complete
last_updated: 2026-03-21
last_updated_by: Codex (GPT-5)
---

# Research: eng-020 recursion and mission timeout guardrails

**Date**: 2026-03-21T12:04:55-07:00
**Researcher**: Codex (GPT-5)
**Git Commit**: `2a5366da719c886d6db9f8e249615979a2166e98`
**Branch**: `main`
**Repository**: `bifrost`

## Research Question
Use `ai/commands/1_research_codebase.md` to perform research for `ai/thoughts/tickets/eng-020-recursion-and-mission-timeout-guardrails.md`.

## Summary
The current recursion guardrail is session-frame based. `BifrostSession` stores a configured `maxDepth`, rejects pushes once the frame stack reaches that depth, and throws `BifrostStackOverflowException` before mutating the stack (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:207`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:211`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostStackOverflowException.java:3`).

Mission execution opens and closes frames at the coordinator boundary. `ExecutionCoordinator.execute(...)` clears any prior plan, opens a mission frame, runs the mission through `MissionExecutionEngine`, and closes the frame in `finally`, so both successful and exceptional mission exits use the same frame-close path (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:61`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:62`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:80`).

Nested YAML skill execution already routes through the same coordinator/session boundary. `CapabilityExecutionRouter` snapshots the parent plan, calls back into `ExecutionCoordinator` for unmapped YAML skills, and restores the parent plan in `finally` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:51`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:54`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:58`).

Current configuration exposes only session stack depth. `BifrostSessionProperties` binds `bifrost.session.max-depth` with default `32`, and auto-configuration passes that value into `BifrostSessionRunner`; no mission-timeout property or timeout enforcement path was found in the runtime source search (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java:8`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java:11`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:86`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:196`).

## Detailed Findings

### Session Depth Guardrail
- `BifrostSession` is the state boundary for frame stack, plan state, journal entries, authentication, and last linter outcome, all guarded by a `ReentrantLock` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:22`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:25`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:28`).
- The depth limit is enforced inside `pushFrame`. If `frames.size() >= maxDepth`, the method throws `BifrostStackOverflowException` using the session id, configured depth, and route of the frame being opened, and does not push the new frame (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:207`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:211`).
- Stack inspection and mutation are explicit operations: `pushFrame`, `popFrame`, `peekFrame`, and immutable snapshots via `getFramesSnapshot()` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:207`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:221`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:247`).
- `BifrostSessionTest` covers depth enforcement directly by asserting the overflow exception message and verifying the preexisting frame remains on the stack after the failed push (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionTest.java:44`).

### Runtime Configuration and Wiring
- `BifrostSessionProperties` is the only guardrail-related configuration class currently present under the `bifrost.session` prefix. It defines `maxDepth` with `@Min(1)` and a default of `32` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java:8`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java:11`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java:13`).
- Auto-configuration creates `BifrostSessionRunner` with `sessionProperties.getMaxDepth()` and separately wires `DefaultMissionExecutionEngine` without additional runtime guardrail inputs (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:86`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:196`).
- `BifrostSessionRunner` uses that configured depth when creating each new `BifrostSession` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSessionRunner.java:10`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSessionRunner.java:27`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSessionRunner.java:37`).
- `BifrostSessionPropertiesTest` documents the binding behavior by asserting the default, an override of `bifrost.session.max-depth=3`, and validation failure when the value is `0` (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionPropertiesTest.java:18`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionPropertiesTest.java:25`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionPropertiesTest.java:36`).

### Mission Frame Lifecycle
- `ExecutionCoordinator.execute(...)` is the mission entry point for YAML skills. It resolves the YAML definition and capability metadata, updates session authentication for root calls, clears any existing plan, and opens a mission frame keyed by the capability name and objective (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:52`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:57`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:61`).
- The actual mission call is delegated to `MissionExecutionEngine.executeMission(...)` inside `BifrostSessionHolder.callWithSession(...)`, with tool visibility resolved before the call (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:64`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:66`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:70`).
- Frame cleanup is outside the session-holder lambda and always occurs in the coordinator `finally` block (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:80`).
- `DefaultExecutionStateService` constructs `ExecutionFrame` objects with parent frame id, route, parameters, and timestamp, pushes them onto the session, and enforces LIFO close ordering through `peekFrame()` before popping (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:26`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:41`).
- `ExecutionStateServiceTest` documents that this boundary manages frames, plan state, linter state, and journaling together, and separately asserts that closing frames out of order is rejected (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/state/ExecutionStateServiceTest.java:25`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/state/ExecutionStateServiceTest.java:81`).

### Mission Execution Path
- `MissionExecutionEngine` is a thin interface with a single synchronous `executeMission(...)` method that returns the mission content string (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/MissionExecutionEngine.java:11`).
- `DefaultMissionExecutionEngine` optionally initializes plan state, derives the execution system prompt from the current plan if one exists, and then calls `chatClient.prompt().system(...).user(...).toolCallbacks(...).call().content()` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:28`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:39`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:49`).
- The mission engine source does not include timeout objects, asynchronous wrappers, executor/future management, or any `.get(timeout)` style boundary. A source-tree search for timeout-related constructs did not return runtime mission-guardrail code in `src/main/java`.
- `MissionExecutionEngineTest` currently covers only two cases: planning-enabled execution prompt construction and planning-disabled execution without plan initialization (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java:27`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java:57`).

### Nested YAML Skill Routing
- `CapabilityExecutionRouter.execute(...)` checks access first, then special-cases capabilities whose kind is `YAML_SKILL` and whose `mappedTargetId` is `null`. In that case it snapshots the current plan, calls `ExecutionCoordinator.execute(...)` with a generated objective string, and restores the parent plan afterward (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:43`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:51`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:58`).
- That router path is what lets nested unmapped YAML skills reuse the same session, frame stack, and mission engine instead of calling the placeholder invoker registered on the capability metadata.
- `YamlSkillCapabilityRegistrar` still registers unmapped YAML skills with an invoker that throws `UnsupportedOperationException("LLM-backed YAML execution is not implemented yet...")`, while mapped skills inherit the target invoker from the mapped capability (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:40`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:52`).
- `ExecutionCoordinatorTest.authorizesProtectedChildYamlSkillFromSessionFallback()` exercises the nested YAML path by configuring a root YAML skill and a child YAML skill with different execution configurations, then asserting that the root tool call receives `"child mission complete"` from the nested mission and that the session ends with an empty frame stack (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:702`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:748`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:790`).

### Current Test Coverage Around Guardrail-Adjacent Behavior
- Happy-path coordinator behavior is covered in both focused and Spring-context integration tests. These tests assert plan creation/update journaling, tool call/result journaling, model-specific chat-client selection, and post-execution empty frame stacks (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:187`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java:30`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java:72`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java:106`).
- Planning-disabled missions are also covered. The coordinator test verifies that when a skill disables planning mode, the system prompt falls back to the default mission string, journaling uses unplanned tool execution plus tool result entries, and frame stacks are empty after execution (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:290`).
- Direct frame overflow is covered at the session unit-test level, but no existing test in `ExecutionCoordinatorTest` or `ExecutionCoordinatorIntegrationTest` drives a recursive YAML skill chain until `BifrostStackOverflowException` is thrown.
- No existing mission-engine or coordinator test exercises a time-bounded chat-model failure or a timeout-specific exception path.

## Code References
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java:8` - `bifrost.session` properties binding.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java:11` - Default max depth value.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:86` - `BifrostSessionRunner` bean uses configured max depth.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:196` - `DefaultMissionExecutionEngine` bean wiring.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:207` - Frame push and overflow enforcement.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:221` - Frame pop behavior.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:247` - Immutable frame snapshots.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:52` - Mission entry flow.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:62` - Mission frame open.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:80` - Mission frame close in `finally`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:51` - Nested unmapped YAML skills re-enter coordinator execution.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:28` - Mission execution implementation.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:26` - Frame creation with parent linkage.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:41` - Ordered frame close behavior.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:40` - Unmapped YAML skill invoker placeholder.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionTest.java:44` - Session overflow behavior test.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionPropertiesTest.java:18` - Session property binding tests.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/state/ExecutionStateServiceTest.java:25` - State-service frame/plan/journal test.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java:27` - Planning-enabled mission-engine test.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:702` - Nested child YAML mission test.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java:30` - End-to-end Spring context coordinator test.

## Architecture Documentation
The current guardrail-related architecture is layered around the session object. `BifrostSession` owns the stack, journals, and mutable mission state. `ExecutionStateService` is the write boundary that creates and closes mission frames and records mission-related session state. `ExecutionCoordinator` is the orchestration boundary for root and nested YAML skill missions. `MissionExecutionEngine` is the model-call boundary that builds the final prompt and performs the synchronous `ChatClient` call.

Nested YAML skills use the same path as root missions once they are routed through `CapabilityExecutionRouter`. The router snapshots and restores plan state around the nested coordinator call, while the session frame stack is shared across the call chain. This means recursion depth is counted across nested skill missions because each mission frame is pushed into the same `BifrostSession`.

Configuration currently enters this architecture only through `bifrost.session.max-depth`. No separate mission-runtime or timeout configuration class was found in the starter, and no timeout-specific abstraction was present between the coordinator and the chat client.

## Historical Context (from ai/thoughts/)
- `ai/thoughts/phases/phase5.md` frames Phase 5 guardrails as stack-depth protection plus `.get(timeout)`-style protection for long-running sub-agents, describing timeouts as a planned hardening step rather than current runtime behavior.
- `ai/thoughts/phases/phase2.md` states that recursion limits belong in the frame-push sequence and describes the YAML `callSkill` branch as planned/not fully implemented at that point in the project.
- `ai/thoughts/phases/README.md` also describes `callSkill` as intended architecture and mentions virtual-thread concurrency plus stack-based recursion tracking as part of the overall model.
- Compared with those historical notes, the live code now contains a nested YAML mission route through `CapabilityExecutionRouter` and `ExecutionCoordinator`, while timeout handling still appears only in the historical/ticket material and not in the current runtime source.

## Related Research
No prior documents were present under `ai/thoughts/research/` at the time of this research.

## Open Questions
- No additional code-local timeout configuration or timeout exception types were found in the current starter source tree.
- Recursive overflow behavior is implemented at the session/frame boundary, but no existing end-to-end recursion-overflow research artifact or test document was present in `ai/thoughts/research/`.
