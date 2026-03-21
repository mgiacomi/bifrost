---
date: 2026-03-20T16:26:12.6843089-07:00
researcher: Unknown
git_commit: 4d9d2a7eb8408918437e7980b87813a8976a4aa2
branch: main
repository: bifrost
topic: "ExecutionCoordinator modularization"
tags: [research, codebase, execution-coordinator, planning, tool-callbacks, bifrost-session]
status: complete
last_updated: 2026-03-20
last_updated_by: Unknown
---

# Research: ExecutionCoordinator modularization

**Date**: 2026-03-20T16:26:12.6843089-07:00
**Researcher**: Unknown
**Git Commit**: `4d9d2a7eb8408918437e7980b87813a8976a4aa2`
**Branch**: `main`
**Repository**: `bifrost`

## Research Question

Use command file `ai/commands/1_research_codebase.md` to perform research for `ai/thoughts/tickets/eng-009-execution-coordinator-modularization.md`.

## Summary

The current coordinator runtime is centered in `ExecutionCoordinator`, with adjacent responsibilities split across `CapabilityToolCallbackAdapter`, `CapabilityExecutionRouter`, `DefaultSkillVisibilityResolver`, `BifrostSession`, and the YAML skill catalog/registrar flow. `ExecutionCoordinator` currently performs mission validation, root RBAC enforcement, frame creation/removal, chat client creation, visible tool resolution, planning bootstrap, execution prompt construction, and the final Spring AI prompt call (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:48`).

Session plan state and journal state live directly on `BifrostSession`, which exposes methods for plan replacement/clearing/updating plus structured journal logging for tool calls, unplanned tool calls, plan creation/updates, and errors (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:66`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:90`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:100`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:120`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:135`).

Tool exposure and tool execution are partially separated today. `DefaultSkillVisibilityResolver` computes the visible YAML skill list from `allowed_skills` and RBAC (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:25`), while `CapabilityToolCallbackAdapter` turns those `CapabilityMetadata` entries into Spring AI `ToolCallback`s and mutates plan/journal state around execution (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolCallbackAdapter.java:32`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolCallbackAdapter.java:53`).

Nested YAML skill execution currently routes back through `ExecutionCoordinator` from `CapabilityExecutionRouter` when the target capability is a YAML skill with no mapped Java target. The router snapshots the parent plan, executes the child mission through the coordinator, and restores the parent plan afterward (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:37`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:47`).

## Detailed Findings

### Coordinator entrypoint and mission loop

- `ExecutionCoordinator` is the public mission entrypoint and accepts `skillName`, `objective`, `BifrostSession`, and optional `Authentication` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:48`).
- The coordinator validates that the objective is non-blank, looks up the YAML skill definition from `YamlSkillCatalog`, looks up the root capability from `CapabilityRegistry`, and performs root RBAC authorization before any planning or model call happens (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:49`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:51`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:52`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:53`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:147`).
- At mission start it clears any existing session plan, creates an `ExecutionFrame` with route/objective metadata, and pushes that frame onto the session stack (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:54`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:56`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:64`).
- During execution it creates a `ChatClient` from the YAML skill's validated `EffectiveSkillExecutionConfiguration`, resolves visible tools, and adapts them into `ToolCallback`s (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:66`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:67`).
- If planning mode is enabled for the skill, the coordinator makes a planning prompt, deserializes the result directly into `ExecutionPlan`, stores the plan on the session, and journals `PLAN_CREATED` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:72`).
- It then builds an execution system prompt from the stored plan when present, otherwise it falls back to a fixed non-planning mission prompt, and makes the final model call with tool callbacks attached (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:82`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:86`).
- The frame is always popped in `finally`, so stack unwind for the top-level mission is handled in the coordinator (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:93`).

### Session, frame, plan, and journal state

- `BifrostSession` owns the session id, max depth, frame stack, `ExecutionJournal`, and current `ExecutionPlan` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:17`).
- Session state is guarded with a `ReentrantLock`, and plan/frame operations are exposed directly on the session object (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:21`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:90`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:100`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:120`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:135`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:149`).
- Journal writes are session methods, not a separate service. The session currently exposes `logThought`, `logToolExecution`, `logUnplannedToolExecution`, `logToolResult`, `logPlanCreated`, `logPlanUpdated`, and `logError` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:62`).
- `ExecutionJournal` stores immutable `JournalEntry` snapshots and converts arbitrary payloads to JSON via Jackson at append time (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionJournal.java:31`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionJournal.java:40`).
- `ExecutionPlan` is an immutable record with plan id, capability name, creation time, status, active task id, and task list. Task mutation happens by returning new plan values from `updateTask`, `withActiveTask`, `clearActiveTask`, and `withStatus` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionPlan.java:8`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionPlan.java:32`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionPlan.java:60`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionPlan.java:68`).
- `PlanTask` is also immutable. Readiness is based on `PENDING` status plus completed dependencies, and task state transitions are modeled by `bindInProgress`, `complete`, and `block` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/PlanTask.java:33`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/PlanTask.java:37`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/PlanTask.java:41`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/PlanTask.java:45`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/PlanTask.java:49`).
- `BifrostSessionPlanStateTest` verifies that plan storage/replacement/clearing is independent from the frame stack (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionPlanStateTest.java:14`).

### Tool visibility and tool callback adaptation

- `DefaultSkillVisibilityResolver` loads the current YAML skill definition, reads `allowedSkills()`, skips self-reference, ignores missing catalog entries, ignores missing capability metadata, and filters by RBAC before returning visible capability metadata in insertion order (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:25`).
- The visibility resolver uses the YAML skill catalog plus capability registry; it does not execute tools and it only returns `CapabilityMetadata` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:13`).
- `SkillVisibilityResolverTest` covers the current behavior that only allowed YAML skills that pass RBAC are visible and that non-YAML capabilities are not surfaced as visible tools through this resolver path (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java:29`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java:50`).
- `CapabilityToolCallbackAdapter` converts each visible capability into a Spring AI `FunctionToolCallback`, preserving the YAML-visible tool name/description and using the capability's input schema (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolCallbackAdapter.java:32`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolCallbackAdapter.java:40`).
- On tool invocation it attempts to link the tool call to the active plan via `PlanTaskLinker`, journals unplanned tool executions when no unique task match is found, journals linked tool calls when a task matches, and updates the stored execution plan directly on the session (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolCallbackAdapter.java:53`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolCallbackAdapter.java:93`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolCallbackAdapter.java:100`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolCallbackAdapter.java:106`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolCallbackAdapter.java:121`).
- `DefaultPlanTaskLinker` links to an already-active in-progress task when the capability name matches; otherwise it looks for exactly one ready task with the same capability name (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/DefaultPlanTaskLinker.java:11`).

### Tool execution routing and `ref://` handoff

- `CapabilityExecutionRouter` enforces RBAC again at execution time before any deterministic or nested YAML invocation happens (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:37`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:73`).
- For YAML capabilities without a mapped target id, the router treats the call as a nested YAML mission. It snapshots the parent plan, calls back into `ExecutionCoordinator.execute(...)` with a serialized objective built from the tool arguments, and restores the parent plan in `finally` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:47`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:61`).
- For mapped capabilities, the router resolves `ref://` arguments through `RefResolver` and invokes the capability's `CapabilityInvoker` directly (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:58`).
- `RefResolverTest` verifies that ref resolution is strict scalar-only at the leaf level: exact `ref://...` strings resolve, prefixed/trailing/spaced variants stay unchanged, nested maps/lists are traversed, binary payloads remain `Resource`s, and resolver failures propagate (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/RefResolverTest.java:21`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/RefResolverTest.java:38`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/RefResolverTest.java:63`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/RefResolverTest.java:77`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/RefResolverTest.java:97`).

### YAML skill registration and execution configuration

- `YamlSkillCatalog` discovers configured YAML resources, validates required manifest fields, validates the referenced model against `BifrostModelsProperties`, computes the effective thinking level, and stores an `EffectiveSkillExecutionConfiguration` per loaded YAML skill (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:48`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:92`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:112`).
- `YamlSkillDefinition` exposes `allowedSkills()`, `rbacRoles()`, `mappingTargetId()`, and `planningModeEnabled(defaultValue)`, which is the method `ExecutionCoordinator` uses to decide whether to perform the planning prompt (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:10`).
- `YamlSkillCapabilityRegistrar` registers every YAML skill as a `CapabilityMetadata` entry. If `mapping.target_id` is present, the YAML skill reuses the target invoker and target input schema. If the mapping target is absent, the registered invoker throws an `UnsupportedOperationException`, while the capability still has `CapabilityKind.YAML_SKILL` metadata and a generic tool schema (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:27`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:44`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:61`).
- `SpringAiSkillChatClientFactory` builds a provider-specific `ChatClient` from the effective skill execution configuration and applies provider-aware thinking settings where supported (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:33`).

### Auto-configuration and runtime wiring

- `BifrostAutoConfiguration` wires the runtime through infrastructure beans in the existing packages rather than dedicated `runtime`, `planning`, `tool`, or `state` packages (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:40`).
- The current bean graph exposes `SkillVisibilityResolver`, `CapabilityExecutionRouter`, `CapabilityToolCallbackAdapter`, and `ExecutionCoordinator` as distinct beans (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:96`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:118`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:140`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:187`).
- The default `ExecutionCoordinator` bean is constructed with a hard-coded `planningModeEnabled` value of `true`, leaving per-skill opt-out to YAML manifest handling (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:193`).

### Tests that define the current accepted behavior

- `ExecutionCoordinatorTest` covers validated YAML execution config usage, plan creation, prompt construction, task completion updates, and journaling around successful tool invocation (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:99`).
- It also covers planning-disabled execution, including clearing a stale prior plan before a non-planning mission starts (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:197`).
- Tool failure behavior is covered by the test that verifies blocked task status, stale plan status, and error journaling when the tool invocation fails (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:344`).
- Nested YAML skill routing is covered by the test that confirms the child skill call flows back through the coordinator and the parent plan is restored afterward (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:424`).
- Ambiguous plan-tool linkage is covered by the test that verifies unplanned tool execution is journaled without mutating multiple matching pending tasks (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:629`).
- `ExecutionCoordinatorIntegrationTest` covers the end-to-end mission path with visible tools, plan state, strict text ref resolution, and binary ref resolution into byte-array tool parameters (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java:37`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java:104`).

## Code References

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:48` - Main mission execution entrypoint.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:72` - Planning bootstrap and plan journaling.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:119` - Execution prompt generation from plan state.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolCallbackAdapter.java:53` - Tool callback execution, plan mutation, and journaling.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:37` - Execution-time routing and authorization.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:47` - Nested YAML skill routing through the coordinator with parent plan restore.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:66` - Structured journal logging methods.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:90` - Execution plan access and mutation methods.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:25` - Visible tool computation from allowed skills plus RBAC.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:92` - Effective execution configuration derivation.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:27` - YAML skill registration into capability metadata.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:187` - Default runtime wiring for `ExecutionCoordinator`.

## Architecture Documentation

The current implementation is organized primarily under `com.lokiscale.bifrost.core`, `com.lokiscale.bifrost.skill`, and `com.lokiscale.bifrost.autoconfigure`. The runtime path is assembled from collaborating components rather than a separate `runtime` package:

- `YamlSkillCatalog` loads YAML manifests and produces validated `YamlSkillDefinition` objects with effective model configuration.
- `YamlSkillCapabilityRegistrar` converts YAML definitions into capability registry entries.
- `ExecutionCoordinator` orchestrates a mission using the catalog, registry, visible tool resolution, chat client factory, and tool callback adapter.
- `DefaultSkillVisibilityResolver` decides which YAML skills become visible tools for the current mission.
- `CapabilityToolCallbackAdapter` adapts visible capabilities into Spring AI `ToolCallback`s and applies plan/journal updates around tool execution.
- `CapabilityExecutionRouter` executes mapped Java-method capabilities or loops nested YAML skills back through the coordinator.
- `BifrostSession` stores frame stack, active plan, and execution journal state for the mission.

Planning is currently represented by a direct `ChatClient` prompt from the coordinator into an `ExecutionPlan` record, followed by later plan mutations inside the tool callback adapter. Session mutation rules and journal writes are currently distributed between `ExecutionCoordinator`, `CapabilityToolCallbackAdapter`, `CapabilityExecutionRouter`, and `BifrostSession`.

## Historical Context (from ai/thoughts/)

- `ai/thoughts/phases/README.md:24` describes `callSkill` as YAML skill delegation through Spring AI `ChatClient`, while also noting that the YAML metadata path exists before the branch is fully implemented end to end.
- `ai/thoughts/phases/README.md:50` describes planning mode as a task-list or flight-plan step that anchors execution and is tracked in the `ExecutionJournal`.
- `ai/thoughts/phases/README.md:56` records an earlier state where deterministic exception transformation lived at `SkillMethodBeanPostProcessor` until an `ExecutionCoordinator` existed in code.
- `ai/thoughts/phases/README.md:58` documents `BifrostSession` as the mission-scoped holder for execution stack and telemetry/journal state.
- `ai/thoughts/tickets/eng-009-execution-coordinator-modularization.md` is the ticket that frames the desired modularization research target for the current runtime shape.

## Related Research

No existing documents were present under `ai/thoughts/research/` at the time of this research run.

## Open Questions

- No additional codebase-only open questions were introduced during this research beyond the open questions already recorded in `ai/thoughts/tickets/eng-009-execution-coordinator-modularization.md`.
