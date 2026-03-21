# ENG-017 Spring AI Linter Call Advisor Plumbing Testing Plan

## Change Summary
- Change `SkillChatClientFactory` to accept `YamlSkillDefinition` instead of only `EffectiveSkillExecutionConfiguration`.
- Add a `SkillAdvisorResolver` abstraction that returns zero or more Spring AI advisors for a YAML skill.
- Update `SpringAiSkillChatClientFactory` to compose provider-specific `ChatOptions` and resolved advisors during client construction.
- Add default no-op advisor resolver auto-configuration so skills without linter config keep current behavior.

## Impacted Areas
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SkillChatClientFactory.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SkillAdvisorResolver.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/NoOpSkillAdvisorResolver.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`

## Risk Assessment
- High risk: the factory contract break can silently miss test doubles or downstream overrides if only production code is updated.
- High risk: advisor attachment could accidentally replace or skip existing provider option mapping if the builder composition order is wrong.
- Medium risk: non-linter skills could pick up unnecessary advisor configuration if the no-op path is not tested explicitly.
- Medium risk: Spring auto-configuration could fail with missing or duplicate resolver beans once the new abstraction is introduced.
- Edge case: nested YAML skill execution should still route through the updated factory boundary for both root and child skills.
- Edge case: resolver output should be empty and side-effect free even when a definition contains no linter block.

## Existing Test Coverage
- `SpringAiSkillChatClientFactoryTests` already verify provider-specific option mapping and cloned-builder `defaultOptions(...)` behavior, but they do not assert any advisor attachment path yet (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java:37`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java:52`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java:92`).
- `ExecutionCoordinatorTest` already proves the coordinator forwards execution configuration to the factory, but its test doubles still capture only `EffectiveSkillExecutionConfiguration` (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:187`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:260`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:1031`).
- `ExecutionCoordinatorTest` also covers nested child YAML skill routing, which makes it the right place to prove the new definition-based contract holds for multiple invocations (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:609`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:697`).
- `BifrostAutoConfigurationTests` already verify execution coordinator auto-configuration and catalog/model property wiring, but they do not yet assert presence or override behavior for an advisor resolver bean (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java:67`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java:88`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java:115`).
- `YamlSkillCatalogTests` already cover valid regex-linter manifest loading, so this ticket does not need new schema-validation tests unless resolver tests need a definition fixture with linter config.

## Bug Reproduction / Failing Test First
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java`
- Arrange/Act/Assert outline:
  - Arrange a mocked root builder and clone builder as current tests do.
  - Arrange a stub `SkillAdvisorResolver` that returns a singleton advisor list for a `YamlSkillDefinition`.
  - Call `factory.create(definition)`.
  - Assert `defaultOptions(...)` is still invoked with provider-specific options.
  - Assert the cloned builder also receives `defaultAdvisors(...)`.
- Expected failure (pre-fix):
  - The test cannot compile or cannot be satisfied because `SpringAiSkillChatClientFactory` has no resolver dependency and no advisor attachment step.

## Tests to Add/Update
### 1) `usesFullYamlSkillDefinitionWhenCreatingChatClient`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
- What it proves: `ExecutionCoordinator` passes the full `YamlSkillDefinition` into the factory, not only `definition.executionConfiguration()`.
- Fixtures/data: reuse the existing `StubYamlSkillCatalog`, `manifest(...)` helper, and a `YamlSkillDefinition` with a normal execution config.
- Mocks: existing recording `SkillChatClientFactory` fake updated to capture `YamlSkillDefinition`.

### 2) `routesNestedYamlSkillsThroughDefinitionBasedFactoryContract`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
- What it proves: both root and child YAML skill executions flow through the updated definition-based factory boundary, preserving nested routing behavior.
- Fixtures/data: reuse the existing root/child YAML definitions from `authorizesProtectedChildYamlSkillFromSessionFallback`.
- Mocks: update the multi-client factory fake to record seen `YamlSkillDefinition` instances and assert their framework models or manifest names.

### 3) `createsClientWithResolvedAdvisorsAndProviderOptions`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java`
- What it proves: the Spring AI factory composes advisor attachments and provider-specific options together on the cloned builder.
- Fixtures/data: a `YamlSkillDefinition` for one provider, a synthetic advisor instance, and the current mocked builder/clone builder setup.
- Mocks: mock `ChatClient.Builder`; stub `defaultOptions(...)`, `defaultAdvisors(...)`, and `build()`.

### 4) `doesNotAttachAdvisorsWhenResolverReturnsEmptyList`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java`
- What it proves: non-linter skills remain on the same path and do not receive unnecessary advisor behavior.
- Fixtures/data: a `YamlSkillDefinition` without linter config and a no-op resolver returning `List.of()`.
- Mocks: verify `defaultOptions(...)` is invoked and `defaultAdvisors(...)` is not invoked.

### 5) `preservesProviderSpecificOptionMappingAfterAdvisorSupport`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java`
- What it proves: all existing OpenAI, Anthropic, Gemini, and Ollama option assertions continue to hold after advisor support is added.
- Fixtures/data: reuse the existing per-provider effective execution configurations.
- Mocks: existing builder mocks; resolver can be no-op for these assertions.

### 6) `noOpResolverReturnsEmptyAdvisorListForSkillWithoutLinter`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SkillAdvisorResolverTests.java`
- What it proves: the default resolver path is explicitly empty and stable for normal skills.
- Fixtures/data: a minimal `YamlSkillDefinition` without linter config.
- Mocks: none.

### 7) `noOpResolverAlsoReturnsEmptyAdvisorListForSkillWithLinterMetadata`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SkillAdvisorResolverTests.java`
- What it proves: ENG-017 wiring remains inert even when a skill definition carries linter metadata, leaving behavior changes to ENG-018.
- Fixtures/data: build a `YamlSkillDefinition` using the existing regex-linter fixture pattern or a manifest object with a populated linter block.
- Mocks: none.

### 8) `autoConfiguresDefaultSkillAdvisorResolver`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
- What it proves: the default application context exposes a `SkillAdvisorResolver` bean alongside the rest of the starter infrastructure.
- Fixtures/data: existing `ApplicationContextRunner` setup and standard skill-location properties.
- Mocks: none.

### 9) `allowsCustomSkillAdvisorResolverOverride`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
- What it proves: applications can override the default resolver bean without breaking `SkillChatClientFactory` or `ExecutionCoordinator` creation.
- Fixtures/data: existing `ApplicationContextRunner` plus a user-supplied resolver bean in a nested test configuration.
- Mocks: optional Mockito mock resolver bean if easier than a concrete stub.

## How to Run
- Compile the starter after the contract change: `.\mvnw.cmd -pl bifrost-spring-boot-starter -DskipTests compile`
- Run coordinator-focused regression tests: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorTest test`
- Run factory and resolver tests: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=SpringAiSkillChatClientFactoryTests,SkillAdvisorResolverTests test`
- Run auto-configuration tests: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostAutoConfigurationTests test`
- Run the full starter test suite before merging: `.\mvnw.cmd -pl bifrost-spring-boot-starter test`

## Exit Criteria
- [ ] A failing factory test exists first and demonstrates that advisor attachment is missing before implementation.
- [x] `ExecutionCoordinatorTest` proves the coordinator forwards `YamlSkillDefinition` for both single-skill and nested-skill execution paths.
- [x] `SpringAiSkillChatClientFactoryTests` prove advisor attachment works and the empty-resolver path does not attach advisors.
- [x] Existing provider-specific option assertions still pass for OpenAI, Anthropic, Gemini, and Ollama after advisor support is added.
- [x] `SkillAdvisorResolver` default behavior is covered by dedicated unit tests or equivalent factory-level assertions.
- [x] `BifrostAutoConfigurationTests` prove default resolver bean registration and application override behavior.
- [x] Full starter-module tests pass post-fix.
- [ ] Manual review confirms ENG-017 adds only wiring and does not introduce retry behavior that belongs to ENG-018.

Note: The advisor factory tests were added as part of the implementation and now pass, but a separate preserved red-test-first step was not captured during execution for this ticket.
