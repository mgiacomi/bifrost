# ENG-014 Security Context Propagation and Access Guard Implementation Plan

## Overview

Centralize Bifrost capability authorization behind a single Spring Security-aware `AccessGuard`, and make authentication propagation an explicit part of session-backed execution so root YAML execution, child tool execution, and discovery filtering all evaluate the same policy in the same way.

## Current State Analysis

Authorization rules already converge on `CapabilityMetadata.rbacRoles()`, but they are enforced in three separate places with duplicated authority-matching code: root mission entry in [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:50`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\ExecutionCoordinator.java:50), runtime capability dispatch in [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:43`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\CapabilityExecutionRouter.java:43), and discovery filtering in [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:25`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\DefaultSkillVisibilityResolver.java:25).

Authentication is propagated today as a nullable method parameter. `ExecutionCoordinator.execute(...)` forwards it into visible tool resolution, callback creation, and mission execution, and `DefaultToolCallbackFactory` closes over that exact `Authentication` object for later tool dispatch ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:60`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\ExecutionCoordinator.java:60), [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:36`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\runtime\tool\DefaultToolCallbackFactory.java:36)). `BifrostSession` itself carries execution frames, journal state, and plan state, but no authentication or security context ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:17`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\BifrostSession.java:17)). `BifrostSessionRunner` also always constructs a session without any security payload ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSessionRunner.java:18`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\BifrostSessionRunner.java:18)).

Existing coverage already proves the major security behavior we need to preserve, including root denial, child YAML routing, and discovery filtering ([`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:43`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\core\ExecutionCoordinatorTest.java:43), [`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:438`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\core\ExecutionCoordinatorTest.java:438), [`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:553`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\core\ExecutionCoordinatorTest.java:553), [`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java:29`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\skill\SkillVisibilityResolverTest.java:29)).

## Desired End State

Bifrost has one shared authorization bridge that evaluates capability RBAC against Spring Security `Authentication`, and every protected capability discovery or execution path uses it consistently. Explicit invocation authentication remains the highest-precedence source when available, but execution can also fall back to authentication stored on the active `BifrostSession` so detached or nested session-driven execution paths still enforce RBAC correctly.

Verification at the end of this work means:

- `ExecutionCoordinator`, `CapabilityExecutionRouter`, and `DefaultSkillVisibilityResolver` no longer contain their own authority-matching logic.
- Protected capabilities fail closed with `AccessDeniedException` when the caller has no matching authority, whether the call starts at root execution, discovery filtering, or child YAML tool execution.
- The current or session-carried authentication source is explicit and test-covered.
- Auto-configuration wires a default `AccessGuard` bean so both YAML-defined and mapped capabilities share the same enforcement path.

### Key Discoveries:
- `ExecutionCoordinator` enforces root authorization before planning or tool surfacing, but does so with a private inline method rather than a shared service ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:55`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\ExecutionCoordinator.java:55), [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:94`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\ExecutionCoordinator.java:94)).
- `CapabilityExecutionRouter` repeats the same rule before direct invocation and before delegating unmapped YAML skills back into `ExecutionCoordinator` ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:50`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\CapabilityExecutionRouter.java:50), [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:79`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\CapabilityExecutionRouter.java:79)).
- `DefaultSkillVisibilityResolver` filters child tools using the same rule but computes its own authority set instead of delegating to shared policy code ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:31`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\DefaultSkillVisibilityResolver.java:31), [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:53`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\DefaultSkillVisibilityResolver.java:53)).
- Tool callbacks currently preserve security only by closing over a nullable `Authentication` parameter at callback creation time ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:42`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\runtime\tool\DefaultToolCallbackFactory.java:42)).
- Auto-configuration has no dedicated authorization bean today, so authorization behavior is distributed across constructor wiring rather than centralized infrastructure ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:106`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\autoconfigure\BifrostAutoConfiguration.java:106), [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:128`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\autoconfigure\BifrostAutoConfiguration.java:128), [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:228`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\autoconfigure\BifrostAutoConfiguration.java:228)).

## What We're NOT Doing

- Adding new RBAC metadata formats or changing the meaning of existing YAML `rbac_roles`.
- Introducing prompt-generated policy, retry behavior, linter schema, or new storage/session backends.
- Redesigning `@SkillMethod` annotations to declare new security metadata in this ticket.
- Reworking non-YAML tool visibility semantics beyond replacing duplicated authorization checks with the shared guard.

## Implementation Approach

Introduce a small security abstraction layer in the starter module:

1. `AccessGuard` becomes the single component that knows how to resolve the effective `Authentication`, evaluate required roles, expose a boolean visibility decision, and raise `AccessDeniedException` for protected runtime invocations.
2. `BifrostSession` becomes capable of carrying optional authentication so security context survives nested execution or future detached/session-resumed flows.
3. Coordinator, router, and visibility resolver are refactored to delegate authorization decisions to `AccessGuard`, removing inline RBAC logic and making the fallback order explicit: invocation authentication first, session authentication second, then fail closed for protected capabilities.
4. Tests are updated so current coverage still passes and new assertions prove the session fallback behavior, especially for child YAML execution routed through the coordinator.

## Phase 1: Introduce The Shared Access Guard

### Overview

Add the new security abstraction and default implementation without changing behavior yet, so the rest of the runtime can depend on a single authorization API.

### Changes Required:

#### 1. Security Guard Contracts
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/security/AccessGuard.java`
**Changes**: Add an interface that exposes the minimum shared operations the runtime needs:

```java
public interface AccessGuard {

    Authentication resolveAuthentication(@Nullable Authentication invocationAuthentication,
                                         BifrostSession session);

    boolean canAccess(CapabilityMetadata capability,
                      BifrostSession session,
                      @Nullable Authentication invocationAuthentication);

    void checkAccess(CapabilityMetadata capability,
                     BifrostSession session,
                     @Nullable Authentication invocationAuthentication);
}
```

#### 2. Default Spring Security Implementation
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/security/DefaultAccessGuard.java`
**Changes**: Implement the shared policy:

- If `capability.rbacRoles()` is empty, allow access immediately.
- Resolve the effective authentication from explicit invocation authentication first, then `session.getAuthentication()` if present.
- Convert authorities into a `Set<String>` once and reuse it for evaluation.
- Return `false` from `canAccess(...)` for missing authentication on protected capabilities.
- Throw `AccessDeniedException` from `checkAccess(...)` with the existing capability-specific message shape.

```java
Authentication effective = invocationAuthentication != null
        ? invocationAuthentication
        : session.getAuthentication().orElse(null);

boolean authorized = capability.rbacRoles().isEmpty()
        || authorities(effective).stream().anyMatch(capability.rbacRoles()::contains);

if (!authorized) {
    throw new AccessDeniedException("Access denied for capability '" + capability.name() + "'");
}
```

#### 3. Auto-Configuration Wiring
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
**Changes**: Register `AccessGuard` as an infrastructure bean and thread it into all authorization consumers.

### Success Criteria:

#### Automated Verification:
- [x] Starter module compiles with the new security package: `./mvnw -pl bifrost-spring-boot-starter -DskipTests compile`
- [x] Existing authorization-focused tests still pass after bean wiring: `./mvnw -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorTest,CapabilityExecutionRouterTest,SkillVisibilityResolverTest test`

#### Manual Verification:
- [ ] The new guard API is small enough that runtime callers do not need to understand Spring Security internals.
- [ ] The default bean wiring makes it obvious where authorization policy lives for future Phase 4 RBAC work.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual review of the security abstraction and bean wiring was successful before proceeding to the next phase.

---

## Phase 2: Make Security Context Session-Carried And Explicit

### Overview

Extend session state so authentication can survive beyond a single method-parameter chain while preserving explicit invocation precedence.

### Changes Required:

#### 1. Session Security State
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
**Changes**: Add optional authentication storage and accessors.

- Add a nullable `Authentication authentication` field that is ignored for session serialization unless the repo already has a supported serialization strategy for it.
- Extend constructors so existing call sites keep working while new entry points can seed authentication.
- Add `Optional<Authentication> getAuthentication()` and a setter/update method for explicit propagation.

```java
@JsonIgnore
private Authentication authentication;

public Optional<Authentication> getAuthentication() {
    lock.lock();
    try {
        return Optional.ofNullable(authentication);
    }
    finally {
        lock.unlock();
    }
}
```

#### 2. Session Creation And Propagation
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSessionRunner.java`
**Changes**: Add overloads or helpers that can initialize a new session with authentication for callers that already have it, while preserving current APIs for existing consumers.

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
**Changes**: At mission entry, persist the provided invocation authentication onto the session before creating visible tools or invoking child flows. This makes the root execution the source of truth for later nested routing.

#### 3. Fallback Semantics Documentation In Code
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/security/DefaultAccessGuard.java`
**Changes**: Add a concise comment near authentication resolution clarifying the intended precedence and why session fallback exists.

### Success Criteria:

#### Automated Verification:
- [x] Session and runner changes compile cleanly: `./mvnw -pl bifrost-spring-boot-starter -DskipTests compile`
- [x] Authorization tests that rely on nested execution still pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorTest,CapabilityExecutionRouterTest test`

#### Manual Verification:
- [ ] It is clear from the code path where authentication is first attached to a session and when fallback is expected to apply.
- [ ] Existing callers that do not provide authentication still construct valid sessions and behave predictably for public capabilities.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing and code review of session-bound authentication were successful before proceeding to the next phase.

---

## Phase 3: Refactor Discovery And Execution To Use The Guard

### Overview

Replace duplicated inline RBAC checks with `AccessGuard` across the runtime so discovery and execution share one authorization rule.

### Changes Required:

#### 1. Root YAML Execution Authorization
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
**Changes**:

- Inject `AccessGuard`.
- Replace `ensureAuthorized(...)` with `accessGuard.checkAccess(rootCapability, session, authentication)`.
- Persist invocation authentication to the session before visible-tool computation and mission execution.
- Remove the private inline authority evaluation helper.

```java
session.setAuthentication(authentication);
accessGuard.checkAccess(rootCapability, session, authentication);
List<ToolCallback> visibleTools = toolCallbackFactory.createToolCallbacks(
        session,
        toolSurfaceService.visibleToolsFor(skillName, authentication),
        authentication);
```

#### 2. Runtime Tool Dispatch Authorization
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java`
**Changes**:

- Inject `AccessGuard`.
- Replace inline `ensureAuthorized(...)` with `accessGuard.checkAccess(...)`.
- Preserve the current plan snapshot/restore behavior for unmapped child YAML skills.
- Continue passing invocation authentication into nested coordinator execution so explicit auth still wins when available.

#### 3. Discovery Filtering Authorization
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java`
**Changes**:

- Inject `AccessGuard`.
- Remove local authority extraction and `isAuthorized(...)`.
- Use `accessGuard.canAccess(metadata, BifrostSession.getCurrentSession(), authentication)` when a thread-bound session exists, or adapt the resolver signature/constructor flow so a session is available explicitly.

Because `visibleSkillsFor(...)` currently only receives `Authentication`, the safest implementation is to extend the visibility API to accept `BifrostSession` explicitly and update `ToolSurfaceService` plus call sites to pass the active session rather than relying on thread-local recovery.

#### 4. Tool Surface And Callback Plumbing
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/ToolSurfaceService.java`
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolSurfaceService.java`
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/SkillVisibilityResolver.java`
**Changes**: Update interfaces to carry `BifrostSession` explicitly, keeping authentication as an optional override argument.

### Success Criteria:

#### Automated Verification:
- [x] The starter module compiles after refactoring all runtime consumers to the shared guard: `./mvnw -pl bifrost-spring-boot-starter -DskipTests compile`
- [x] Root, runtime, and visibility tests pass with shared guard enforcement: `./mvnw -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorTest,CapabilityExecutionRouterTest,SkillVisibilityResolverTest test`

#### Manual Verification:
- [ ] No class outside the guard is still performing direct role-set comparison for capability authorization.
- [ ] The runtime API makes the session dependency for authorization explicit instead of relying on accidental callback capture.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the shared-guard refactor was successful before proceeding to the next phase.

---

## Phase 4: Expand And Tighten Security Coverage

### Overview

Add focused tests that lock in the new session fallback behavior and ensure discovery and execution remain aligned.

### Changes Required:

#### 1. Execution Coordinator Coverage
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
**Changes**:

- Preserve the existing root-denial and restricted-child tests.
- Add a test proving a protected child YAML skill still authorizes when callback invocation authentication is absent but the session carries authentication seeded at mission entry.
- Add a test proving missing authentication on a protected root or child capability still fails closed with `AccessDeniedException` or wrapped tool execution failure as appropriate.

#### 2. Router Coverage
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/CapabilityExecutionRouterTest.java`
**Changes**:

- Add a direct router test for `AccessGuard`-backed denial on protected capabilities.
- Add a router test proving nested coordinator delegation uses session fallback without regressing plan restoration.

#### 3. Visibility Coverage
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java`
**Changes**:

- Update existing visibility tests to pass the active session if the interface changes.
- Add a test proving protected skills are hidden when authentication is absent.
- Add a test proving the same authority that allows runtime execution also surfaces the tool in discovery.

#### 4. Dedicated Guard Unit Tests
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/security/DefaultAccessGuardTest.java`
**Changes**: Add direct unit tests for precedence rules:

- invocation authentication wins over session authentication
- session authentication is used when invocation authentication is null
- protected capability with no matching authority is denied
- unprotected capability remains visible/executable without authentication

### Success Criteria:

#### Automated Verification:
- [x] New and updated security tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=DefaultAccessGuardTest,ExecutionCoordinatorTest,CapabilityExecutionRouterTest,SkillVisibilityResolverTest test`
- [x] Full starter-module test suite passes: `./mvnw -pl bifrost-spring-boot-starter test`

#### Manual Verification:
- [ ] Test names clearly document the supported precedence and fail-closed behavior for future maintainers.
- [ ] The child YAML invocation path is covered well enough that future async or detached execution work has a safety net.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the final security scenarios were successfully reviewed before considering the ticket implementation complete.

---

## Testing Strategy

### Unit Tests:
- `DefaultAccessGuardTest` should cover authorization decisions in isolation, especially precedence between invocation authentication and session authentication.
- Add negative tests for protected capabilities with `null` authentication and for mismatched authorities.
- Add positive tests for public capabilities and matching authorities across both YAML and mapped capability metadata.

### Integration Tests:
- Keep `ExecutionCoordinatorTest` as the main mission-level integration harness for root execution, visible tool surfacing, and nested YAML tool invocation.
- Use `CapabilityExecutionRouterTest` to verify nested coordinator delegation still restores the parent plan while applying the shared guard.
- Use `SkillVisibilityResolverTest` to prove the same guard decision governs discovery filtering and runtime execution.

**Note**: Prefer a dedicated testing plan artifact created via `/testing_plan` for full details, including a failing-test-first sequence and final exit criteria. This implementation plan keeps the testing section at the high level needed to scope the work.

### Manual Testing Steps:
1. Review the wiring path from `BifrostAutoConfiguration` through `ExecutionCoordinator`, `ToolSurfaceService`, `SkillVisibilityResolver`, and `CapabilityExecutionRouter` to confirm all authorization decisions terminate at `AccessGuard`.
2. Run a protected root YAML scenario with no authentication and confirm the mission is denied before planning or tool exposure.
3. Run a nested protected child YAML scenario where mission entry seeds session authentication and confirm the child tool remains executable even if downstream code relies on session fallback.
4. Run a discovery scenario for a protected child skill and confirm it is hidden without authentication and visible with the matching authority.

## Performance Considerations

Authorization remains lightweight because capability RBAC is already represented as small in-memory role sets on `CapabilityMetadata`. The guard should avoid repeated stream work by normalizing authorities once per decision and should not introduce any I/O or catalog lookups beyond what discovery and execution already perform.

## Migration Notes

This change is an internal runtime refactor with no storage migration. The only compatibility-sensitive area is constructor/interface churn:

- Preserve existing public constructors where possible by adding overloads rather than forcing downstream code changes immediately.
- If visibility or tool-surface interfaces must change to carry `BifrostSession`, update all starter-internal call sites in one commit so there is no partial authorization path.
- Keep the existing `AccessDeniedException` message format for protected capabilities to minimize test churn and preserve caller expectations.

## References

- Original ticket/requirements: `ai/thoughts/tickets/eng-014-security-context-propagation-and-access-guard.md`
- Related research: `ai/thoughts/research/2026-03-20-ENG-014-security-context-propagation-and-access-guard.md`
- Similar implementation points:
  - [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:50`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\ExecutionCoordinator.java:50)
  - [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:43`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\CapabilityExecutionRouter.java:43)
  - [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:25`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\DefaultSkillVisibilityResolver.java:25)
  - [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:36`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\runtime\tool\DefaultToolCallbackFactory.java:36)
