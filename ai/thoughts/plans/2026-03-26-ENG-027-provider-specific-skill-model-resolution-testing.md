# ENG-027 Provider-Specific Skill Model Resolution Testing Plan

## Change Summary
- Fix runtime provider routing so a skill's resolved `AiProvider` selects the underlying `ChatModel`, not just provider-specific `ChatOptions`.
- Replace the current shared `ChatClient.Builder` assumption with resolver-based provider selection in the starter runtime path.
- Preserve existing YAML model catalog resolution, provider-specific options adapters, and advisor behavior.
- Fail at **execution time** with a clear skill-specific error when a skill requests a provider that is not configured.

## Impacted Areas
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SkillChatOptionsAdapter.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/TaalasChatModel.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SkillChatModelResolver.java` (new)
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillChatModelResolver.java` (new)
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
- Manual verification fixture only: `bifrost-sample/src/main/resources/skills/invoice_parser.yml`, `bifrost-sample/src/main/resources/application.yml`

## Risk Assessment
- **High-risk behavior**: provider selection may still silently collapse to a single `ChatModel` if bean qualification or registry assembly is wrong.
- **High-risk behavior**: refactoring `SpringAiSkillChatClientFactory` may preserve options but accidentally break advisor attachment/order.
- **High-risk behavior**: the new resolver may introduce startup-time coupling when the agreed behavior is execution-time failure.
- **Edge case**: multiple `ChatModel` beans may be present and auto-config may resolve by type rather than explicit provider mapping.
- **Edge case**: provider-specific thinking behavior for OpenAI, Anthropic, and Gemini could regress while changing only transport routing.
- **Edge case**: `ExecutionCoordinator` may not need direct provider-routing assertions; over-testing there could duplicate lower-level coverage without improving confidence.

## Existing Test Coverage
- `SpringAiSkillChatClientFactoryTests` already verifies provider-specific `ChatOptions` generation and advisor attachment, but it is built around cloning a shared builder and does **not** prove provider-bound `ChatModel` selection.
- `BifrostAutoConfigurationTests` currently asserts that Taalas auto-configures a shared `ChatClient.Builder`; this coverage will need to shift toward resolver registration and multi-provider wiring.
- `ExecutionCoordinatorTest` already has rich fixture helpers such as `StubYamlSkillCatalog`, `RecordingSkillChatClientFactory`, `MultiClientSkillChatClientFactory`, and `FakeCoordinatorChatClient`, making it suitable for lightweight orchestration regression checks without turning the sample invoice flow into an integration test.
- The sample `invoiceParser` scenario reproduces the bug conditions, but per the agreed scope it should remain a **manual verification path**, not an automated integration test.
- Gap summary:
  - no failing test that demonstrates current provider-routing failure
  - no explicit resolver tests
  - no auto-config test that proves multi-provider contexts do not collapse onto a single backend
  - no explicit test for execution-time unavailable-provider failure messaging

## Bug Reproduction / Failing Test First
- **Type**: unit
- **Location**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java`
- **Arrange/Act/Assert outline**:
  - Arrange a `YamlSkillDefinition` whose `EffectiveSkillExecutionConfiguration` resolves to `AiProvider.OLLAMA`.
  - Arrange the existing/shared-builder style test harness so the backing builder is effectively tied to a non-Ollama model, mirroring the current bug.
  - Act by creating the skill client through `SpringAiSkillChatClientFactory.create(...)`.
  - Assert that the created client still uses the wrong backing model path pre-fix, even though the options are `OllamaChatOptions`.
- **Expected failure (pre-fix)**:
  - The test should prove that provider-specific options are applied while provider-specific transport is **not** selected.
  - If that is too awkward to demonstrate with the current factory shape, an equivalent minimal failing test is acceptable: assert that factory construction is tied to a shared builder rather than a provider-aware `ChatModel` resolver, and mark the current behavior as incorrect.

## Tests to Add/Update
### 1) `DefaultSkillChatModelResolverTests.resolvesConfiguredProviderModel`
- **Type**: unit
- **Location**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/DefaultSkillChatModelResolverTests.java`
- **What it proves**: a configured provider such as `OLLAMA` or `TAALAS` resolves to the exact registered `ChatModel` instance.
- **Fixtures/data**: mocked or fake `ChatModel` instances keyed by `AiProvider`.
- **Mocks**: plain mocks for `ChatModel`; no Spring context needed.

### 2) `DefaultSkillChatModelResolverTests.failsClearlyWhenProviderIsUnavailable`
- **Type**: unit
- **Location**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/DefaultSkillChatModelResolverTests.java`
- **What it proves**: when a skill requests an unavailable provider, resolver/factory behavior fails with a clear execution-time message that includes provider and skill name.
- **Fixtures/data**: missing `AiProvider.OLLAMA` entry plus skill name such as `invoiceParser`.
- **Mocks**: plain mocks for available providers only.

### 3) `SpringAiSkillChatClientFactoryTests.selectsChatModelFromResolvedProvider`
- **Type**: unit
- **Location**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java`
- **What it proves**: the factory chooses the provider-specific `ChatModel` before building the `ChatClient`.
- **Fixtures/data**: `YamlSkillDefinition` values for at least `OLLAMA` and `TAALAS`.
- **Mocks**: mock `SkillChatModelResolver`, `SkillAdvisorResolver`, and provider-specific `ChatModel` doubles. If observing `ChatClient.builder(...)` is awkward, introduce a tiny package-private seam specifically for builder creation and test through that seam.

### 4) `SpringAiSkillChatClientFactoryTests.preservesProviderSpecificOptionsAndAdvisors`
- **Type**: unit
- **Location**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java`
- **What it proves**: refactoring transport selection does not regress `OpenAiChatOptions`, `AnthropicChatOptions`, `GoogleGenAiChatOptions`, `OllamaChatOptions`, or advisor attachment behavior.
- **Fixtures/data**: existing `EffectiveSkillExecutionConfiguration` combinations already used in the current test class.
- **Mocks**: same harness as the updated factory tests.

### 5) `SpringAiSkillChatClientFactoryTests.throwsExecutionTimeErrorForUnavailableProvider`
- **Type**: unit
- **Location**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java`
- **What it proves**: the agreed failure mode occurs at skill execution/client creation time rather than startup.
- **Fixtures/data**: `YamlSkillDefinition` for `invoiceParser` or another representative YAML skill resolving to an unavailable provider.
- **Mocks**: resolver configured to throw the target exception.

### 6) `BifrostAutoConfigurationTests.registersSkillChatModelResolverInsteadOfSharedBuilder`
- **Type**: integration
- **Location**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
- **What it proves**: starter auto-configuration provides resolver-based wiring and no longer depends on a single shared `ChatClient.Builder` bean for skill execution.
- **Fixtures/data**: `ApplicationContextRunner` with Taalas-enabled properties and relevant provider beans.
- **Mocks**: Spring test context; mock provider beans only where necessary.

### 7) `BifrostAutoConfigurationTests.supportsMultiProviderResolverRegistrationWithoutTypeCollapse`
- **Type**: integration
- **Location**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
- **What it proves**: when multiple provider `ChatModel` beans are present, resolver registration remains explicitly mapped by provider and does not accidentally select whichever bean wins by type.
- **Fixtures/data**: multi-provider context with at least two `ChatModel` beans.
- **Mocks**: Spring context plus named or qualified mock/fake beans.

### 8) `BifrostAutoConfigurationTests.exposesSkillChatClientFactoryBackedByResolver`
- **Type**: integration
- **Location**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
- **What it proves**: `SkillChatClientFactory` is created successfully from the resolver/adapters/advisor wiring in the application context.
- **Fixtures/data**: existing YAML skill test properties plus provider bean setup.
- **Mocks**: minimal provider model beans as needed.

### 9) `ExecutionCoordinatorTest.propagatesResolverBasedFactoryBehaviorWithoutChangingOrchestration`
- **Type**: integration
- **Location**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
- **What it proves**: `ExecutionCoordinator` still orchestrates skill execution correctly when `SkillChatClientFactory` behavior is provider-aware, without duplicating the lower-level provider-routing assertions.
- **Fixtures/data**: reuse `StubYamlSkillCatalog`, `RecordingSkillChatClientFactory`, or `MultiClientSkillChatClientFactory` patterns already present in `ExecutionCoordinatorTest`.
- **Mocks**: avoid sample-app wiring; keep this a focused starter-module orchestration check.

## How to Run
- `./mvnw.cmd -pl bifrost-spring-boot-starter -DskipTests compile`
- `./mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=DefaultSkillChatModelResolverTests test`
- `./mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=SpringAiSkillChatClientFactoryTests test`
- `./mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostAutoConfigurationTests test`
- `./mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorTest test`
- `./mvnw.cmd -pl bifrost-spring-boot-starter test`
- `./mvnw.cmd test`

## Required Environment / Data
- Unit tests should not require external model providers.
- Auto-configuration tests should rely on Spring test contexts and mocks/fakes rather than real Ollama or Taalas endpoints.
- Manual verification of the sample scenario requires:
  - local Ollama availability when verifying the `granite4-tiny -> OLLAMA` path
  - Taalas config only when verifying that Taalas-mapped skills still route correctly
  - the existing sample YAML and `application.yml` mapping

## Exit Criteria
- [ ] A minimal failing test exists and fails pre-fix, demonstrating the current provider-routing bug or its shared-builder root cause
- [ ] Resolver unit tests prove successful provider lookup and unavailable-provider failure behavior
- [ ] Factory tests prove provider-specific `ChatModel` routing, preserved options behavior, and preserved advisor attachment
- [ ] Auto-configuration tests prove resolver registration and explicit multi-provider wiring without collapse by type
- [ ] Any `ExecutionCoordinator` regression test added remains focused on orchestration compatibility, not duplicate provider-routing assertions
- [ ] All targeted starter-module tests pass post-fix
- [ ] Full starter-module test suite passes post-fix
- [ ] Full reactor tests pass post-fix
- [ ] Manual verification confirms the sample invoice scenario routes to Ollama and remains outside automated integration coverage
