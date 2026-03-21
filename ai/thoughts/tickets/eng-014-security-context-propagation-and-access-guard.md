# Ticket: eng-014-security-context-propagation-and-access-guard.md
## Issue: Centralize Authorization and Carry Security Context Through Bifrost Execution

### Why This Ticket Exists
Phase 4 calls for Spring Security-backed RBAC enforcement, but the current codebase already performs authorization checks in multiple places. `CapabilityExecutionRouter`, `ExecutionCoordinator`, and visibility resolution all reason about authorities independently, which creates drift risk and makes future session-scoped security behavior harder to extend.

This ticket turns the existing behavior into an explicit Spring Security bridge: one authorization component, one authority-evaluation rule, and a clear story for propagating authentication through mission execution.

---

## Goal
Introduce a dedicated `AccessGuard` and use it as the single authorization boundary for Bifrost capability discovery and execution.

The main outcome should be:

- authorization logic lives in one place
- current Spring `Authentication` is consumed consistently from either the active invocation or session context
- YAML skill RBAC and `@SkillMethod`-backed capability RBAC fail closed with clear Spring Security exceptions

---

## Non-Goals
This ticket should **not** introduce:

- linter schema or retry behavior
- new storage backends
- prompt-level policy generation
- a redesign of YAML visibility rules beyond switching them to the shared guard

---

## Required Outcomes

### Functional
- Add an `AccessGuard` abstraction that evaluates required authorities against Spring Security authentication.
- Prefer explicit invocation authentication when present, with a documented fallback to session-carried authentication if the runtime supports detached or async execution paths.
- Replace duplicated inline RBAC checks in execution and visibility code with `AccessGuard`.
- Unauthorized execution throws `AccessDeniedException`.
- Unauthenticated access behaves predictably for protected capabilities.

### Structural
- Capability authorization rules are not duplicated across router, coordinator, and visibility layers.
- Security-context propagation is explicit in the session/execution path rather than being an accidental method parameter convention.
- The guard can support both YAML-defined skills and mapped Java capabilities without separate policy codepaths.

### Testing
- Tests prove protected capabilities are denied when authentication is missing.
- Tests prove matching authorities allow execution.
- Tests prove discovery/visibility filtering uses the same guard as runtime execution.
- Tests prove authorization still works when a child YAML skill is invoked through the execution coordinator.

---

## Suggested Files
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/security/AccessGuard.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/security/DefaultAccessGuard.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/CapabilityExecutionRouterTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java`

---

## Acceptance Criteria
- There is one shared authorization component used by runtime execution and visibility filtering.
- Protected skills and mapped capabilities throw `AccessDeniedException` when the caller lacks required authority.
- Authentication propagation is explicit and test-covered.
- Existing RBAC behavior does not regress for already supported YAML metadata.

---

## Definition of Done
This ticket is done when Bifrost has a single Spring Security authorization bridge and all protected capability discovery and execution flow through it consistently.
