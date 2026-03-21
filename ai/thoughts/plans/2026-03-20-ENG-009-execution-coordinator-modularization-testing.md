# ENG-009 ExecutionCoordinator Modularization Testing Plan

## Change Summary
- Refactor the YAML-skill mission runtime so `ExecutionCoordinator` becomes a thin entrypoint and runtime responsibilities move into dedicated state, planning, tool, and mission-loop services.
- Preserve all accepted `ENG-007` behavior, especially YAML-visible tool gating, planning-mode behavior, structured plan/journal state, nested YAML delegation, and strict `ref://` resolution.
- Add isolated tests for the new service boundaries without weakening the current end-to-end coordinator coverage.

## Impacted Areas
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolCallbackAdapter.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- New runtime packages under `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/`
- New runtime tests under `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/`

## Risk Assessment
- Highest risk is behavioral regression in the accepted mission loop because the current behavior is split across `ExecutionCoordinator`, `CapabilityToolCallbackAdapter`, `CapabilityExecutionRouter`, and `BifrostSession`.
- Planning state can regress if plan creation, task linking, task completion, blocked-task handling, or stale-plan clearing move to new services with slightly different semantics.
- Nested YAML execution is high risk because parent-plan snapshot and restore currently happen in the router and must remain correct after extraction.
- Tool visibility and tool execution separation can accidentally widen or narrow the visible surface if `allowed_skills` and RBAC filtering no longer match the current resolver behavior.
- Execution-time RBAC is a separate risk because a tool can still require authorization when invoked even after it appears in the visible surface.
- Ref resolution must remain strict scalar-only and must continue to preserve binary payload behavior for byte-array tools.
- Auto-configuration is a structural risk because the runtime bean graph is changing and the integration path depends on the right defaults being present.

## Existing Test Coverage
- `ExecutionCoordinatorTest` already anchors the current acceptance behavior for planning-enabled flow, planning-disabled flow, blocked tasks, nested YAML delegation, ambiguous task linking, and mapped tool schema handling.
- `ExecutionCoordinatorIntegrationTest` already proves the end-to-end runtime path with visible tools, plan state, strict text `ref://` resolution, and binary `ref://` resolution.
- `BifrostSessionPlanStateTest` already proves that active-plan storage is independent from frame stack state.
- `SkillVisibilityResolverTest` already proves that only allowed YAML skills that pass RBAC are exposed.
- `RefResolverTest` already proves strict exact-match ref resolution plus binary payload preservation.
- Gap: there are no tests yet for `ExecutionStateService`, `PlanningService`, `ToolCallbackFactory`, or `MissionExecutionEngine` because those boundaries do not exist yet.
- Gap: there is no direct test that the extracted router/state interaction preserves parent-plan restore semantics independently of the coordinator monolith.

## Bug Reproduction / Failing Test First
- This is a behavior-preserving refactor, not a bug fix.
- No mandatory failing test should be added before implementation because the current behavior is already correct and the main risk is regression during extraction.
- Instead, treat the current `ExecutionCoordinatorTest` and `ExecutionCoordinatorIntegrationTest` suite as the pre-refactor baseline that must stay green while new focused tests are added around each new service.
- If the implementation is done in very small phases, add the new service tests as soon as each boundary exists, and verify they would fail if the extracted logic were omitted or wired incorrectly.

## Tests to Add/Update
### 1) `ExecutionStateServiceTest`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/state/ExecutionStateServiceTest.java`
- What it proves: the state service owns frame open/close, plan store/clear, parent-plan snapshot/restore, and structured journal writes without changing the serialized `BifrostSession` shape.
- Fixtures/data: `BifrostSession`, `ExecutionFrame`, `ExecutionPlan`, `PlanTask`, and representative `TaskExecutionEvent` payloads.
- Mocks: none; prefer the real default state service over mock-heavy tests.

### 2) `ExecutionStateServiceRestoresParentPlanAfterNestedMission`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/state/ExecutionStateServiceTest.java`
- What it proves: snapshot/restore returns the active plan to its exact prior value after a nested YAML mission path and clears it correctly when no parent plan existed.
- Fixtures/data: one session with a parent plan and one session without a parent plan.
- Mocks: none.

### 3) `PlanningServiceInitializesPlanOnlyWhenPlanningEnabled`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/planning/PlanningServiceTest.java`
- What it proves: planning bootstrap is isolated from coordinator wiring and leaves session plan state untouched when planning mode is disabled.
- Fixtures/data: fake `ChatClient` responses for a valid `ExecutionPlan`, mission objective text, visible tool list.
- Mocks: fake or stub `ChatClient`; real state service if practical.

### 4) `PlanningServiceMarksLinkedTaskStartedCompletedAndBlocked`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/planning/PlanningServiceTest.java`
- What it proves: task-link semantics match current behavior for start, completion, blocked-task notes, stale-plan transitions, and active-task clearing.
- Fixtures/data: immutable `ExecutionPlan` with one matching task, one ambiguous-task plan, and one already-active-task case.
- Mocks: stub `PlanTaskLinker` only if needed; otherwise use `DefaultPlanTaskLinker`.

### 5) `ToolCallbackFactoryBuildsVisibleToolDefinitions`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/tool/ToolCallbackFactoryTest.java`
- What it proves: visible capabilities are converted into Spring AI `ToolCallback`s with the same tool name, description, and schema that the current adapter exposes.
- What it also proves: the callback factory carries the caller authentication context needed for execution-time RBAC enforcement.
- Fixtures/data: `CapabilityMetadata` for mapped YAML skills and generic visible tools.
- Mocks: mock router/state/planning collaborators only at the service seam.

### 6) `ToolCallbackFactoryRoutesUnplannedAndLinkedExecutionsThroughServices`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/tool/ToolCallbackFactoryTest.java`
- What it proves: unplanned executions journal correctly, linked tasks advance correctly, and tool results/errors flow through `ExecutionStateService` and `PlanningService` instead of direct `BifrostSession` mutations.
- What it also proves: tool failures are delegated through explicit planning-service failure semantics instead of ad hoc callback logic.
- Fixtures/data: session with a plan containing unique and ambiguous matching tasks, sample tool args/result payloads.
- Mocks: mock state service and planning service to assert collaboration boundaries; use a stub router result.

### 7) `CapabilityExecutionRouterRestoresParentPlanViaStateService`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/CapabilityExecutionRouterTest.java`
- What it proves: nested YAML routing still restores the parent plan correctly after delegation, but now through the extracted state boundary rather than ad hoc session calls.
- Fixtures/data: unmapped YAML `CapabilityMetadata`, parent `ExecutionPlan`, serialized child objective arguments.
- Mocks: mock `ExecutionCoordinator` provider and `ExecutionStateService`; real `ObjectMapper`.

### 8) `MissionExecutionEngineExecutesPlanningEnabledMissionLoop`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/MissionExecutionEngineTest.java`
- What it proves: the mission engine performs planning bootstrap, derives the execution prompt from current plan state, and invokes the final model call with tool callbacks attached.
- Fixtures/data: fake `ChatClient`, plan with ready and blocked tasks, visible tool callback list.
- Mocks: mock planning service and state service only where needed to keep the loop assertions focused.

### 9) `MissionExecutionEngineSkipsPlanningForPlanningDisabledMission`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/MissionExecutionEngineTest.java`
- What it proves: the engine preserves the current fixed non-planning mission prompt and does not create or mutate plan state when planning is disabled.
- What it also proves: planning-disabled execution is selected through an explicit engine input rather than inferred from missing plan state.
- Fixtures/data: fake `ChatClient` and empty tool list or a simple visible tool list.
- Mocks: mock planning service to assert no planning bootstrap call.

### 10) Update `ExecutionCoordinatorTest`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
- What it proves: the coordinator still validates root skill/objective/authentication and wires the extracted services correctly, but no longer owns planning or prompt-building internals.
- Fixtures/data: existing `FakeCoordinatorChatClient`, YAML definitions, capability registry fixtures, and authentication fixtures.
- Mocks: replace direct adapter wiring with mocks or fakes for `ToolSurfaceService`, `ToolCallbackFactory`, `MissionExecutionEngine`, and `ExecutionStateService` where constructor changes require it.

### 11) Keep and minimally update `ExecutionCoordinatorIntegrationTest`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java`
- What it proves: the full accepted mission path still works end to end with visible-tool constraints, plan journaling, strict text `ref://` resolution, and binary `ref://` resolution.
- Fixtures/data: existing `ApplicationContextRunner`, YAML skills, temp VFS files, `RecordingSkillChatClientFactory`, and target bean methods.
- Mocks: none; keep the integration path real.

### 12) Update `SkillVisibilityResolverTest` only if the wrapper changes entrypoints
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java`
- What it proves: the underlying visibility rules remain unchanged even if the coordinator now uses a `ToolSurfaceService`.
- Fixtures/data: existing YAML fixtures and RBAC auth token.
- Mocks: none.

## How to Run
- Build and run starter tests: `.\mvnw.cmd -pl bifrost-spring-boot-starter test`
- Run focused regression tests during extraction: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorTest,ExecutionCoordinatorIntegrationTest,BifrostSessionPlanStateTest,SkillVisibilityResolverTest,RefResolverTest test`
- Run new state/planning/tool/engine tests once introduced: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=ExecutionStateServiceTest,PlanningServiceTest,ToolCallbackFactoryTest,MissionExecutionEngineTest,CapabilityExecutionRouterTest test`
- Run full project verification before merge: `.\mvnw.cmd test`

## Exit Criteria
- [x] Existing `ExecutionCoordinatorTest` scenarios still pass for planning-enabled, planning-disabled, blocked-task, nested YAML, ambiguous-tool, and mapped-schema behavior.
- [x] Existing `ExecutionCoordinatorIntegrationTest` scenarios still pass for visible-tool gating, plan journaling, strict text `ref://` resolution, and binary `ref://` resolution.
- [x] `BifrostSessionPlanStateTest`, `SkillVisibilityResolverTest`, and `RefResolverTest` remain green to guard the underlying state, visibility, and ref-resolution invariants.
- [x] New `ExecutionStateServiceTest`, `PlanningServiceTest`, `ToolCallbackFactoryTest`, and `MissionExecutionEngineTest` exist and cover the extracted boundaries directly.
- [x] Parent-plan restore semantics are covered explicitly after the router/state extraction.
- [x] Auto-configuration still wires a working runtime path in integration coverage.
- [x] Execution-time RBAC remains test-covered at the callback boundary.
- [x] Tool-failure transitions remain test-covered at the planning boundary.
- [x] Planning-disabled execution remains test-covered through an explicit mission-engine contract.
- [ ] No new tests weaken existing assertions just to accommodate the refactor.
- [ ] Manual verification confirms `ExecutionCoordinator` is now a thin facade and the extracted runtime classes each own one primary responsibility.
