# ENG-014 Security Context Propagation and Access Guard Testing Plan

## Change Summary
- Centralize capability authorization behind a shared `AccessGuard` used by root execution, runtime tool dispatch, and discovery filtering.
- Make authentication propagation explicit by allowing `BifrostSession` to carry authentication, with invocation authentication taking precedence over session-carried authentication.
- Replace duplicated inline RBAC checks with shared guard calls while preserving fail-closed behavior for protected capabilities.
- Add regression coverage for child YAML execution, visibility filtering, and explicit-versus-session authentication precedence.

## Impacted Areas
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/security/AccessGuard.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/security/DefaultAccessGuard.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSessionRunner.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/SkillVisibilityResolver.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/ToolSurfaceService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolSurfaceService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/CapabilityExecutionRouterTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/security/DefaultAccessGuardTest.java`

## Risk Assessment
- High risk: root execution, child tool execution, and discovery filtering could diverge if one path still evaluates RBAC differently.
- High risk: session-carried authentication could accidentally override explicit invocation authentication instead of acting as fallback only.
- High risk: protected capabilities could become visible in discovery but still fail at runtime, or vice versa.
- Medium risk: changing visibility and tool-surface interfaces to carry `BifrostSession` could break existing test harness wiring.
- Medium risk: storing `Authentication` on `BifrostSession` could create serialization surprises if it is not kept runtime-only.
- Edge case: protected capabilities must fail closed when both invocation authentication and session authentication are absent.
- Edge case: unmapped child YAML skills routed back through `ExecutionCoordinator` must still restore the parent plan after authorization checks.

## Existing Test Coverage
- `ExecutionCoordinatorTest.deniesRestrictedRootSkillBeforePlanningOrModelExecution` already proves protected root YAML skills are denied before planning/model execution.
- `ExecutionCoordinatorTest.routesUnmappedYamlSkillsBackThroughCoordinatorAndRestoresParentPlan` already covers child YAML routing and plan restoration.
- `ExecutionCoordinatorTest.deniesRestrictedToolInvocationAtExecutionTimeWhenAuthenticationLacksRole` already proves runtime denial is surfaced through tool execution.
- `CapabilityExecutionRouterTest.restoresParentPlanViaStateService` already covers router plan restoration for nested YAML delegation.
- `SkillVisibilityResolverTest.returnsOnlyAllowedYamlSkillsThatPassRbac` already covers discovery filtering with RBAC.
- `SkillVisibilityResolverTest.excludesNonYamlCapabilitiesEvenIfListedInAllowedSkills` already covers the YAML-only visibility constraint.
- Gap: there is no dedicated unit test for shared authorization policy or precedence between invocation authentication and session authentication.
- Gap: there is no test proving a protected child capability can authorize via session fallback when callback-time invocation authentication is absent.
- Gap: there is no direct test proving discovery filtering and runtime execution share the exact same authorization source after refactoring.

## Bug Reproduction / Failing Test First
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
- Arrange/Act/Assert outline:
  - Arrange a protected child YAML skill requiring `ROLE_ALLOWED`.
  - Arrange a root YAML mission that seeds the session with an authenticated principal holding `ROLE_ALLOWED`.
  - Force downstream tool invocation to execute with `null` invocation authentication so the runtime must rely on session fallback.
  - Assert the protected child tool succeeds and the plan is updated as completed.
- Expected failure (pre-fix):
  - The child tool invocation fails with `ToolExecutionException` or `AccessDeniedException` because current code only relies on the callback-captured invocation `Authentication` and `BifrostSession` has no authentication fallback.

This is the highest-value failing test because it demonstrates the exact behavior ENG-014 adds rather than merely guarding the refactor.

## Tests to Add/Update
### 1) `DefaultAccessGuardTest.allowsUnprotectedCapabilityWithoutAuthentication`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/security/DefaultAccessGuardTest.java`
- What it proves: `canAccess(...)` and `checkAccess(...)` allow capabilities with empty `rbacRoles` even when both invocation and session authentication are absent.
- Fixtures/data: one `CapabilityMetadata` with empty roles and a fresh `BifrostSession`.
- Mocks: none.

### 2) `DefaultAccessGuardTest.deniesProtectedCapabilityWithoutInvocationOrSessionAuthentication`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/security/DefaultAccessGuardTest.java`
- What it proves: protected capabilities fail closed when no authentication source exists.
- Fixtures/data: one `CapabilityMetadata` with `ROLE_ALLOWED`; session with no authentication.
- Mocks: none.

### 3) `DefaultAccessGuardTest.usesSessionAuthenticationWhenInvocationAuthenticationIsNull`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/security/DefaultAccessGuardTest.java`
- What it proves: session fallback authorizes protected capabilities when invocation authentication is absent.
- Fixtures/data: session seeded with an authenticated principal carrying `ROLE_ALLOWED`.
- Mocks: none.

### 4) `DefaultAccessGuardTest.prefersInvocationAuthenticationOverSessionAuthentication`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/security/DefaultAccessGuardTest.java`
- What it proves: explicit invocation authentication is the authoritative source, even if session authentication differs.
- Fixtures/data: session authentication without role; invocation authentication with role, and the inverse as a second assertion if kept in the same test class.
- Mocks: none.

### 5) `ExecutionCoordinatorTest.authorizesProtectedChildYamlSkillFromSessionFallback`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
- What it proves: a protected child YAML skill invoked through the execution coordinator succeeds when the root mission seeds the session authentication and callback-time invocation authentication is absent.
- Fixtures/data: root and child YAML skill metadata, mission plan containing a child tool task, authenticated principal with `ROLE_ALLOWED`, coordinator wiring that drops callback-time invocation auth to `null`.
- Mocks: reuse the existing fake chat client, in-memory registry, and stub catalog patterns from `ExecutionCoordinatorTest`.

### 6) `ExecutionCoordinatorTest.deniesProtectedChildYamlSkillWhenSessionAndInvocationAuthenticationAreMissing`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
- What it proves: nested protected child execution still fails closed after the refactor.
- Fixtures/data: same as the previous test, but no authentication seeded anywhere.
- Mocks: reuse existing coordinator harness.

### 7) `CapabilityExecutionRouterTest.deniesProtectedCapabilityWithoutMatchingAuthority`
- Type: integration-style unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/CapabilityExecutionRouterTest.java`
- What it proves: the router delegates authorization to the shared guard and throws `AccessDeniedException` for protected capabilities when access is denied.
- Fixtures/data: protected `CapabilityMetadata`, session without matching auth, simple `RefResolver`, mocked `ExecutionStateService`.
- Mocks: mocked `ExecutionCoordinator` only if needed for YAML routing branch isolation.

### 8) `CapabilityExecutionRouterTest.authorizesNestedYamlDelegationUsingSessionFallbackAndRestoresPlan`
- Type: integration-style unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/CapabilityExecutionRouterTest.java`
- What it proves: the router can authorize a protected unmapped YAML capability using session fallback and still restores the parent plan afterward.
- Fixtures/data: protected YAML capability, session with authentication, mocked state service snapshot/restore, mocked coordinator.
- Mocks: mocked `ExecutionCoordinator`, mocked `ExecutionStateService`.

### 9) `SkillVisibilityResolverTest.hidesProtectedSkillsWhenAuthenticationIsMissing`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java`
- What it proves: discovery filtering fails closed for protected child skills when no invocation or session authentication is present.
- Fixtures/data: same YAML fixtures used by the existing visibility tests, with protected `rbac_roles` on the allowed child skill.
- Mocks: none.

### 10) `SkillVisibilityResolverTest.usesSessionFallbackForProtectedSkillVisibility`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java`
- What it proves: discovery filtering uses the same session fallback semantics as runtime execution.
- Fixtures/data: active session seeded with `ROLE_ALLOWED`, protected child YAML skill.
- Mocks: none.

### 11) Update `SkillVisibilityResolverTest.returnsOnlyAllowedYamlSkillsThatPassRbac`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java`
- What it proves: the existing happy path still works after the resolver signature and service plumbing are updated to carry `BifrostSession`.
- Fixtures/data: existing YAML fixtures and authenticated principal.
- Mocks: none.

### 12) Update `ExecutionCoordinatorTest.deniesRestrictedRootSkillBeforePlanningOrModelExecution`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
- What it proves: protected root denial still occurs before planning, journaling, frame creation, or model execution after `AccessGuard` is introduced.
- Fixtures/data: existing test fixture setup.
- Mocks: existing fake chat client and in-memory fixtures.

## How to Run
- Compile the starter module after interface and bean-wiring changes: `./mvnw -pl bifrost-spring-boot-starter -DskipTests compile`
- Run the new guard unit tests alone while iterating on precedence logic: `./mvnw -pl bifrost-spring-boot-starter -Dtest=DefaultAccessGuardTest test`
- Run focused security regression tests while refactoring runtime wiring: `./mvnw -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorTest,CapabilityExecutionRouterTest,SkillVisibilityResolverTest,DefaultAccessGuardTest test`
- Run the full starter test suite before closing the ticket: `./mvnw -pl bifrost-spring-boot-starter test`

## Exit Criteria
- [x] A failing test exists and fails pre-fix for session-fallback authorization of protected child YAML execution.
- [x] `DefaultAccessGuardTest` covers unprotected access, missing-auth denial, session fallback, and explicit-auth precedence.
- [x] `ExecutionCoordinatorTest` proves protected root denial still happens early and protected child YAML execution works with session fallback.
- [x] `CapabilityExecutionRouterTest` proves authorization is enforced through the shared guard and parent plan restoration still works.
- [x] `SkillVisibilityResolverTest` proves protected tool visibility uses the same authorization rule as runtime execution.
- [x] All focused security tests pass post-fix in the starter module.
- [x] Full starter-module tests pass post-fix.
- [ ] Manual verification confirms no runtime class outside the guard still contains direct authority-comparison logic.
