---
date: 2026-03-20T21:59:34-07:00
researcher: Unknown
git_commit: 8d991702e4bcadcebc25dc05548075cd95c39df6
branch: main
repository: bifrost
topic: "ENG-014 security context propagation and access guard"
tags: [research, codebase, security, authorization, session, execution, visibility]
status: complete
last_updated: 2026-03-20
last_updated_by: Unknown
---

# Research: ENG-014 security context propagation and access guard

**Date**: 2026-03-20T21:59:34-07:00
**Researcher**: Unknown
**Git Commit**: `8d991702e4bcadcebc25dc05548075cd95c39df6`
**Branch**: `main`
**Repository**: `bifrost`

## Research Question
Use `ai/commands/1_research_codebase.md` to perform research for `ai/thoughts/tickets/eng-014-security-context-propagation-and-access-guard.md`.

## Summary

The current starter already carries authorization metadata on `CapabilityMetadata` via the `rbacRoles` field, and that metadata is used in three separate places: runtime execution in `CapabilityExecutionRouter`, root YAML mission entry in `ExecutionCoordinator`, and discovery filtering in `DefaultSkillVisibilityResolver` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:8`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:79`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:94`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:53`).

`Authentication` is propagated explicitly as a nullable method parameter through the execution stack. `ExecutionCoordinator.execute(...)` accepts it, passes it to visibility resolution, tool callback creation, and mission execution, while `DefaultToolCallbackFactory` closes over that same `Authentication` instance and forwards it into `CapabilityExecutionRouter.execute(...)` on each tool call (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:50`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:35`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:43`).

`BifrostSession` does not currently store authentication or any security context. The session contains session identity, execution frames, journal state, and plan state, and the session holder binds only the `BifrostSession` itself to a thread-local (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:19`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSessionHolder.java:8`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSessionRunner.java:18`).

## Detailed Findings

### Capability RBAC Metadata

- Authorization requirements are modeled on `CapabilityMetadata` as `Set<String> rbacRoles`, with a default of `Set.of()` when no roles are supplied (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:8`).
- YAML manifests expose RBAC through the `rbac_roles` field on `YamlSkillManifest`, and `YamlSkillDefinition.rbacRoles()` simply forwards that manifest field (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:21`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:19`).
- `YamlSkillCapabilityRegistrar` copies YAML `rbac_roles` into the registered `CapabilityMetadata` for each YAML skill, whether the YAML skill is mapped to a Java target or remains an unmapped YAML capability (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:27`).
- Java `@SkillMethod` capabilities do not declare RBAC in the annotation. `SkillMethodBeanPostProcessor` registers them with `Set.of()` for `rbacRoles`, so Java method capabilities themselves are currently created without direct RBAC metadata at registration time (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/annotation/SkillMethod.java:12`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:78`).

### Authorization Checks In Runtime Execution

- `ExecutionCoordinator.execute(...)` is the root entry point for YAML mission execution. It resolves the YAML skill, loads the corresponding capability, then calls `ensureAuthorized(...)` before clearing plan state, opening a mission frame, building tool callbacks, or invoking the model-backed mission engine (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:50`).
- `ExecutionCoordinator.ensureAuthorized(...)` treats an absent `Authentication` as an empty authority set, checks whether any required role from `capability.rbacRoles()` matches `authentication.getAuthorities()`, and throws `AccessDeniedException` when no match is found (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:94`).
- `CapabilityExecutionRouter.execute(...)` applies the same authority matching rule before invoking any capability. For unmapped YAML skills, the router snapshots the current plan, delegates back into `ExecutionCoordinator.execute(...)`, and restores the parent plan afterward (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:43`).
- For non-YAML or mapped YAML capabilities, `CapabilityExecutionRouter` resolves ref arguments against the current `BifrostSession` and invokes the capability directly after authorization succeeds (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:64`).

### Visibility Filtering And Tool Surfacing

- `DefaultSkillVisibilityResolver.visibleSkillsFor(...)` is the discovery filter for child tools visible to a YAML skill. It loads the current YAML skill, iterates only over `allowedSkills`, skips self-references, skips entries missing from the YAML catalog, and then filters each candidate capability by RBAC (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:24`).
- The resolver uses the same effective authority model as execution: null authentication becomes `Set.of()`, and a capability is visible when `rbacRoles` is empty or at least one required role is present in the caller's authorities (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:31`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:53`).
- `DefaultToolSurfaceService` is a thin wrapper that delegates visible tool computation to `SkillVisibilityResolver`, and `ExecutionCoordinator` uses that service to decide which callbacks are exposed to the chat client for a mission (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolSurfaceService.java:13`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:60`).

### Authentication Propagation Through Execution

- The current flow passes `Authentication` explicitly from `ExecutionCoordinator.execute(...)` into both tool discovery and mission execution (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:60`).
- `DefaultToolCallbackFactory.createToolCallbacks(...)` receives the `Authentication` value and captures it inside each generated `FunctionToolCallback`, so each callback later invokes the router with the same `Authentication` object that was present when callbacks were created (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:35`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:46`).
- `DefaultMissionExecutionEngine.executeMission(...)` accepts `@Nullable Authentication authentication` in its interface and implementation, but the method body does not currently read that value. It initializes planning state, builds the execution prompt, and calls the chat client with the visible tool callbacks (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:27`).
- The resulting execution path is parameter-based rather than session-based: authorization depends on the `Authentication` argument that is carried into the coordinator and then into each tool callback, not on a security field inside `BifrostSession`.

### Session And Thread-Bound Context

- `BifrostSession` stores `sessionId`, `maxDepth`, execution frames, journal entries, and optional execution plan state; there is no authentication field or Spring Security-specific state on the session object (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:19`).
- `BifrostSessionHolder` uses a `ThreadLocal<BifrostSession>` to bind and restore the current session for nested work on the same thread (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSessionHolder.java:8`).
- `BifrostSessionRunner` creates a fresh `BifrostSession` and binds it through `BifrostSessionHolder` for either `runWithNewSession(...)` or `callWithNewSession(...)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSessionRunner.java:18`).
- Because only the session is bound to the thread-local holder, any security context used by current authorization checks comes from the explicit `Authentication` method parameters, not from `BifrostSessionHolder`.

### Bean Wiring In Auto-Configuration

- `BifrostAutoConfiguration` wires the authorization-related runtime chain by creating `DefaultSkillVisibilityResolver`, `CapabilityExecutionRouter`, `DefaultToolSurfaceService`, `DefaultToolCallbackFactory`, `DefaultMissionExecutionEngine`, and `ExecutionCoordinator` as infrastructure beans (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:103`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:125`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:163`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:170`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:179`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:224`).
- No dedicated authorization bean is currently configured in auto-configuration. Each consumer performs its own role evaluation inline.

### Existing Test Coverage

- `ExecutionCoordinatorTest.deniesRestrictedRootSkillBeforePlanningOrModelExecution()` verifies that a protected root YAML skill throws `AccessDeniedException` before planning, journaling, frame creation, or model execution proceed (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:43`).
- `ExecutionCoordinatorTest.usesValidatedYamlExecutionConfigAndUpdatesPlanThroughToolInvocation()` exercises the standard mission path where authentication is supplied to the coordinator, visible tools are built, and tool invocation proceeds through the callback/router path (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:111`).
- `ExecutionCoordinatorTest.routesUnmappedYamlSkillsBackThroughCoordinatorAndRestoresParentPlan()` verifies that an unmapped child YAML skill invoked as a tool is routed back through the coordinator and that the parent plan remains the active plan after the child call completes (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:438`).
- `ExecutionCoordinatorTest.deniesRestrictedToolInvocationAtExecutionTimeWhenAuthenticationLacksRole()` verifies runtime denial during tool execution, where the restricted child capability results in a tool execution failure and the plan is marked blocked/stale (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:553`).
- `CapabilityExecutionRouterTest.restoresParentPlanViaStateService()` verifies that the router restores the parent plan after delegating an unmapped YAML capability back through the coordinator (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/CapabilityExecutionRouterTest.java:21`).
- `SkillVisibilityResolverTest.returnsOnlyAllowedYamlSkillsThatPassRbac()` verifies that visibility filtering includes only allowed YAML skills whose RBAC matches the caller (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java:29`).
- `SkillVisibilityResolverTest.excludesNonYamlCapabilitiesEvenIfListedInAllowedSkills()` verifies that visibility resolution still requires the listed child to exist as a YAML catalog entry, so non-YAML capabilities listed in `allowed_skills` are not surfaced (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java:50`).

## Code References

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:8` - Shared capability model containing `rbacRoles`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:18` - YAML manifest fields for `allowed_skills`, `rbac_roles`, and `mapping.target_id`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:27` - Registration of YAML capabilities into `CapabilityMetadata`, including RBAC roles and mapped target invokers.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:65` - Registration path for Java `@SkillMethod` capabilities with empty RBAC roles.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:50` - Root YAML execution entry point, explicit `Authentication` propagation, and root authorization check.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:25` - Discovery-time tool visibility and RBAC filtering.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:35` - Callback creation path that captures session plus authentication for later tool execution.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:43` - Runtime capability dispatch, child YAML routing, and inline authorization.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:17` - Session state model without authentication storage.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSessionHolder.java:6` - Thread-local binding of the current `BifrostSession`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:27` - Mission engine signature that receives authentication but does not consume it internally.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:103` - Bean wiring for visibility, router, callback factory, mission engine, and coordinator.

## Architecture Documentation

The current authorization model is capability-centric. YAML manifests and registered capabilities converge on `CapabilityMetadata`, and `rbacRoles` on that object is the common data source used by both discovery-time filtering and runtime execution checks.

The execution path is layered as follows:

- `ExecutionCoordinator` handles root YAML skill entry, plan reset, mission frame lifecycle, visible tool calculation, and handoff to the mission engine.
- `DefaultToolSurfaceService` delegates tool discovery to `SkillVisibilityResolver`.
- `DefaultToolCallbackFactory` converts visible capabilities into Spring AI `ToolCallback` instances and closes over both `BifrostSession` and `Authentication`.
- `CapabilityExecutionRouter` is the runtime dispatch point for tool execution, including nested delegation back into `ExecutionCoordinator` for unmapped YAML skills.
- `BifrostSession` carries execution state and plan/journal data across the mission and nested tool calls.

The security context path is currently method-parameter-based rather than session-bound. `Authentication` is nullable and passed explicitly through coordinator, tool surface resolution, callback generation, mission execution, and router dispatch. The session holder provides thread-local access only for `BifrostSession`.

## Historical Context (from ai/thoughts/)

- `ai/thoughts/tickets/eng-014-security-context-propagation-and-access-guard.md` - Ticket describing the intended consolidation of authorization checks and explicit security-context propagation.
- No additional matching research, plan, or historical notes were present in `ai/thoughts/research/` at the time of this investigation.

## Related Research

- No related research documents were present in `ai/thoughts/research/` at the time of this investigation.

## Open Questions

- No follow-up codebase questions were investigated beyond the ENG-014 scope described in the ticket.
