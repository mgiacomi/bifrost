# ENG-007 ExecutionCoordinator Testing Plan

## Change Summary
- Add an `ExecutionCoordinator` runtime loop for YAML-defined LLM skills.
- Add typed planning state to `BifrostSession` and structured plan journaling.
- Add strict session-scoped `ref://` resolution through a VFS abstraction.
- Add YAML-only skill discovery using `allowed_skills` plus RBAC filtering.
- Add end-to-end integration coverage for the new coordinator flow.

## Impacted Areas
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionJournal.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/JournalEntryType.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/` new planning types
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/` new visibility resolver
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/` new VFS and ref resolver
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/`
- `bifrost-spring-boot-starter/src/test/resources/skills/`

## Risk Assessment
- High-risk behavior: adding mutable plan state to `BifrostSession` could break existing JSON serialization or thread-safe session behavior.
- High-risk behavior: filtering the LLM-visible tool surface incorrectly could hide required YAML skills or accidentally expose internal implementation targets.
- High-risk behavior: strict `ref://` handling could over-resolve or under-resolve arguments if matching rules are ambiguous.
- High-risk behavior: the coordinator loop could drift from the accepted boot-time execution metadata and accidentally re-resolve model settings.
- High-risk behavior: the new integration path could violate stack unwinding or `MAX_DEPTH` expectations.
- Edge case: YAML manifests with no `allowed_skills` should not expose unrelated skills.
- Edge case: non-ref strings that happen to contain `ref://` as a substring should remain untouched.
- Edge case: plan updates should be journaled without losing the current active session-state view.

## Existing Test Coverage
- Session lifecycle, stack behavior, and journal access are already covered in [`BifrostSessionTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\core\BifrostSessionTest.java) and [`BifrostSessionJsonTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\core\BifrostSessionJsonTest.java).
- Journal serialization behavior is covered in [`ExecutionJournalTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\core\ExecutionJournalTest.java).
- YAML parsing and boot-time validation are covered in [`YamlSkillCatalogTests.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\skill\YamlSkillCatalogTests.java).
- Auto-configuration coverage exists in [`BifrostAutoConfigurationTests.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\autoconfigure\BifrostAutoConfigurationTests.java).
- Provider-specific client creation is covered in [`SpringAiSkillChatClientFactoryTests.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\chat\SpringAiSkillChatClientFactoryTests.java).
- Deterministic capability registration and transformed error handling are covered in [`SkillMethodBeanPostProcessorTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\core\SkillMethodBeanPostProcessorTest.java).

### Gaps
- No test exists for typed planning state in `BifrostSession`.
- No test exists for plan-specific journal entry types or plan snapshots.
- No test exists for `allowed_skills` parsing or YAML-only visibility filtering.
- No test exists for `ref://` resolution or VFS session isolation.
- No test exists for an `ExecutionCoordinator`.
- No end-to-end integration test exists for the accepted ENG-007 loop.

## Bug Reproduction / Failing Test First
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java`
- Arrange/Act/Assert outline:
  - Arrange an application context with one YAML root skill, one allowed YAML sub-skill, one disallowed YAML skill, a fake `ChatClient`, a deterministic target, and a session-local ref fixture.
  - Act by invoking the not-yet-implemented coordinator with a hello-world mission while planning mode is enabled.
  - Assert that:
    - a typed execution plan is stored in the session
    - plan updates are journaled
    - only the allowed YAML sub-skill is exposed
    - the strict `ref://...` argument resolves successfully
    - the session unwinds cleanly without depth overflow
- Expected failure (pre-fix):
  - The test should fail immediately because no `ExecutionCoordinator` bean/class exists yet and there is no runtime LLM-backed YAML execution path.

## Tests to Add/Update
### 1) `BifrostSessionPlanStateTest`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionPlanStateTest.java`
- What it proves:
  - A session can store, replace, read, and clear a typed `ExecutionPlan`.
  - Plan state remains independent from the execution-frame stack.
- Fixtures/data:
  - `ExecutionPlan` with 2-3 `PlanTask` entries in mixed statuses.
- Mocks:
  - None.

### 2) `BifrostSessionJsonTest` update for plan serialization
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionJsonTest.java`
- What it proves:
  - Session JSON round-trips with plan state preserved.
  - Journal snapshots remain serializable alongside plan state.
- Fixtures/data:
  - A session containing both frames and an `ExecutionPlan`.
- Mocks:
  - None.

### 3) `ExecutionJournalTest` update for plan journaling
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionJournalTest.java`
- What it proves:
  - `PLAN_CREATED` and `PLAN_UPDATED` entries serialize as structured payloads.
  - Journal payloads preserve task ordering and status values.
- Fixtures/data:
  - Plan snapshot payload objects.
- Mocks:
  - None.

### 4) `YamlSkillCatalogTests` update for `allowed_skills`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves:
  - YAML manifests parse `allowed_skills` correctly.
  - Missing `allowed_skills` defaults to an empty list.
- Fixtures/data:
  - New YAML fixtures under `src/test/resources/skills/valid/`.
- Mocks:
  - None; follow existing `ApplicationContextRunner` pattern.

### 5) `SkillVisibilityResolverTest`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java`
- What it proves:
  - Only YAML-facing skills are returned to the coordinator.
  - `allowed_skills` restricts visibility to explicitly declared skills.
  - Disallowed YAML skills are excluded.
  - Raw `@SkillMethod` implementation targets are excluded from the LLM-facing result set.
  - RBAC filtering is applied before exposure.
- Fixtures/data:
  - In-memory registry entries for YAML skills plus one raw Java capability.
  - Mock or test authentication with varying authorities.
- Mocks:
  - Lightweight stubs for authentication if needed.

### 6) `RefResolverTest`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/RefResolverTest.java`
- What it proves:
  - Exact scalar `ref://...` strings are resolved.
  - Plain strings and strings containing `ref://` as a substring are not resolved.
  - Non-string inputs are passed through unchanged.
- Fixtures/data:
  - Fake `VirtualFileSystem` returning a known `Resource`.
  - String values like `ref://artifacts/abc123`, `prefix ref://artifacts/abc123`, and `hello`.
- Mocks:
  - Stub VFS implementation; no full Spring context required.

### 7) `VirtualFileSystemTest`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/VirtualFileSystemTest.java`
- What it proves:
  - Session-local refs resolve within the current session namespace.
  - Different sessions do not resolve each other's refs.
- Fixtures/data:
  - Two sessions with different ids and a temp-backed resource fixture.
- Mocks:
  - None if using a temp directory; otherwise a stubbed `ResourceLoader`.

### 8) `ExecutionCoordinatorTest`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
- What it proves:
  - The coordinator consumes `EffectiveSkillExecutionConfiguration` from YAML metadata.
  - Planning mode stores typed plan state and journals updates.
  - Tool exposure is limited to YAML-visible allowed skills.
  - Deterministic invocations pass through strict ref resolution before execution.
- Fixtures/data:
  - Fake root YAML skill metadata, visible skill set, fake session, and fake chat client result sequence.
- Mocks:
  - Mock `SkillChatClientFactory`, fake `ChatClient`, stub visibility resolver, stub ref resolver.

### 9) `BifrostAutoConfigurationTests` update for coordinator wiring
- Type: integration-lite
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
- What it proves:
  - Auto-configuration provides the coordinator and its supporting beans.
- Fixtures/data:
  - Existing `ApplicationContextRunner` setup.
- Mocks:
  - Reuse existing style; only provide additional beans as needed.

### 10) `ExecutionCoordinatorIntegrationTest`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java`
- What it proves:
  - End-to-end ENG-007 acceptance path works with a dummy session and hello-world objective.
  - Planning mode creates typed session plan state.
  - Plan snapshots appear in the journal.
  - The `ChatClient` loop can call mock tools and unwind cleanly.
  - At least one strict `ref://...` injection resolves correctly.
  - `MAX_DEPTH` is not violated in the happy path.
- Fixtures/data:
  - Dedicated YAML fixtures for root skill, allowed sub-skill, disallowed skill, and ref-driven deterministic target.
  - Fake or mocked `ChatClient` loop responses.
- Mocks:
  - Mock `ChatClient.Builder` or coordinator-facing chat abstraction as appropriate.

## How to Run
- Starter module tests only: `./mvnw -pl bifrost-spring-boot-starter test`
- Focused session and journal tests: `./mvnw -pl bifrost-spring-boot-starter -Dtest=BifrostSessionTest,BifrostSessionJsonTest,ExecutionJournalTest test`
- Focused YAML and visibility tests: `./mvnw -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests,SkillVisibilityResolverTest test`
- Focused VFS/ref tests: `./mvnw -pl bifrost-spring-boot-starter -Dtest=RefResolverTest,VirtualFileSystemTest test`
- Focused coordinator tests: `./mvnw -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorTest,ExecutionCoordinatorIntegrationTest test`
- Full repository verification: `./mvnw test`

## Exit Criteria
- [ ] Failing integration test exists and fails pre-fix because the coordinator path does not exist yet
- [x] All new and existing starter tests pass post-fix
- [x] New tests cover typed planning state and plan journaling
- [x] New tests cover `allowed_skills` parsing and YAML-only visibility filtering
- [x] New tests cover strict `ref://` scalar resolution and session isolation
- [x] New unit tests prove the coordinator uses validated execution metadata rather than recomputing it
- [x] End-to-end integration test covers the accepted hello-world mission path without violating `MAX_DEPTH`
- [ ] Manual verification confirms the session snapshot and journal are understandable for debugging and reconstruction
