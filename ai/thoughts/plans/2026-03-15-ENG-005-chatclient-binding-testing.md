# ENG-005 ChatClient Binding Testing Plan

## Change Summary
- Add framework-managed `bifrost.models` binding for provider-aware model catalog entries.
- Add YAML skill discovery from `classpath:/skills/**/*.yaml` with boot-time model and `thinking_level` validation.
- Add effective execution-configuration resolution with `medium` as the default thinking level when the selected model supports thinking.
- Add a shared `ChatClient` resolver/factory that maps validated execution settings to provider-specific client configuration.
- Extend capability metadata so YAML-backed registrations retain deterministic model-selection details without redefining `@SkillMethod`.

## Impacted Areas
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostModelsProperties.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/...`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/...`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`
- `bifrost-spring-boot-starter/src/test/resources/application-test.yml`
- `bifrost-spring-boot-starter/src/test/resources/skills/...`

## Risk Assessment
- Boot-time binding may silently accept malformed model catalog entries unless validation is exercised through real context startup.
- YAML discovery may miss files or bind them inconsistently if the `classpath:/skills/**/*.yaml` pattern is not tested end-to-end.
- Thinking-level defaulting is easy to regress because behavior differs for thinking-capable versus non-thinking models.
- Unknown-model and unsupported-thinking failures could appear as vague startup errors unless tests assert the failure surface directly.
- Provider-aware factory logic may leak provider-specific details into the shared contract or incorrectly apply thinking settings to providers/models that do not support them.
- Extending `CapabilityMetadata` may accidentally break existing `@SkillMethod` registration tests or default behavior.

## Existing Test Coverage
- [BifrostAutoConfigurationTests.java](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\autoconfigure\BifrostAutoConfigurationTests.java#L20) already uses `ApplicationContextRunner` to verify auto-configuration and bound session properties.
- [BifrostSessionPropertiesTest.java](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\core\BifrostSessionPropertiesTest.java#L15) already proves the project's pattern for validating startup failures via `context.getStartupFailure()`.
- [SkillMethodBeanPostProcessorTest.java](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\core\SkillMethodBeanPostProcessorTest.java#L18) already covers registry registration and invocation behavior for annotation-discovered Java capabilities.
- [SkillMethodTest.java](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\annotation\SkillMethodTest.java#L15) covers annotation defaults and should remain stable because ENG-005 is only documenting future `@SkillMethod` cleanup.
- There are currently no YAML skill fixtures, no model catalog tests, no `ChatClient` factory tests, and no tests for capability metadata carrying effective YAML execution settings.

## Bug Reproduction / Failing Test First
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
- Arrange/Act/Assert outline:
  - Start an `ApplicationContextRunner` with `ConfigurationPropertiesAutoConfiguration`, `BifrostAutoConfiguration`, `application-test.yml`, and a valid YAML skill fixture under `src/test/resources/skills/valid/default-thinking-skill.yaml`.
  - Resolve the new model-catalog properties bean and the YAML skill catalog or registry bean.
  - Assert that the effective execution config for the valid skill resolves to framework model `gpt-5`, provider `openai`, provider model `openai/gpt-5`, and thinking level `medium`.
- Expected failure (pre-fix):
  - The context will fail to start or the expected beans will be missing because `bifrost.models` binding, YAML skill discovery, and effective thinking-level resolution do not exist yet.

## Tests to Add/Update
### 1) `bindsProviderAwareModelCatalogFromApplicationTestYaml`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
- What it proves:
  - `bifrost.models` binds from `application-test.yml`
  - OpenAI, Anthropic, Gemini, and Ollama entries are all visible through strongly typed configuration
  - generic `thinking_levels` are preserved when present and empty when absent
- Fixtures/data:
  - `src/test/resources/application-test.yml`
- Mocks:
  - none

### 2) `rejectsInvalidModelCatalogEntriesAtStartup`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostModelsPropertiesTest.java`
- What it proves:
  - blank provider model values, missing provider declarations, or other invalid catalog entries fail startup with a bind or validation failure
- Fixtures/data:
  - inline `withPropertyValues(...)` overrides on `ApplicationContextRunner`
- Mocks:
  - none

### 3) `defaultsThinkingLevelToMediumWhenModelSupportsThinking`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves:
  - a valid YAML skill with `model: gpt-5` and omitted `thinking_level` resolves to effective `medium`
  - discovery from `classpath:/skills/**/*.yaml` works for the happy path
- Fixtures/data:
  - `src/test/resources/application-test.yml`
  - `src/test/resources/skills/valid/default-thinking-skill.yaml`
- Mocks:
  - none

### 4) `omitsThinkingLevelWhenSelectedModelHasNoThinkingSupport`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves:
  - a valid YAML skill targeting `ollama-llama3` or another non-thinking catalog entry resolves with no thinking-level override
- Fixtures/data:
  - `src/test/resources/skills/valid/non-thinking-skill.yaml`
- Mocks:
  - none

### 5) `failsStartupWhenYamlSkillReferencesUnknownModel`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves:
  - boot fails when a YAML skill names a model key that is not present in the framework catalog
  - the failure message points to the offending skill and field
- Fixtures/data:
  - `src/test/resources/skills/invalid/unknown-model-skill.yaml`
- Mocks:
  - none

### 6) `failsStartupWhenThinkingLevelIsUnsupportedForModel`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves:
  - boot fails when a YAML skill declares a `thinking_level` not present in the selected model's configured `thinking_levels`
- Fixtures/data:
  - `src/test/resources/skills/invalid/unsupported-thinking-skill.yaml`
- Mocks:
  - none

### 7) `loadsYamlSkillsFromClasspathSkillsPattern`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves:
  - the loader is actually reading `classpath:/skills/**/*.yaml`, not a narrower or ad hoc path
  - nested skill fixture directories are discovered
- Fixtures/data:
  - multiple YAML fixtures in nested folders under `src/test/resources/skills`
- Mocks:
  - none

### 8) `mapsDeterministicYamlSkillToDiscoveredSkillMethodTarget`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves:
  - a YAML skill using `mapping.target_id` can link to a discovered Java implementation target without introducing new `@SkillMethod` model configuration
  - ENG-005 stays aligned with the ENG-008 contract boundary
- Fixtures/data:
  - test bean with `@SkillMethod`
  - `src/test/resources/skills/valid/mapped-method-skill.yaml`
- Mocks:
  - none

### 9) `registersYamlCapabilityMetadataWithEffectiveExecutionDescriptor`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
- What it proves:
  - registered capability metadata contains framework model key, provider, provider model, and effective thinking level for YAML-backed skills
  - deterministic model selection is traceable after registration
- Fixtures/data:
  - valid YAML skill fixture
- Mocks:
  - none

### 10) `preservesExistingSkillMethodRegistrationBehavior`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`
- What it proves:
  - existing annotation-discovered capabilities still register with the same name, description, invoker behavior, and legacy `modelPreference`
  - any new metadata field defaults are safe for non-YAML registrations
- Fixtures/data:
  - existing in-test sample beans
- Mocks:
  - none

### 11) `createsClientWithProviderModelAndNoThinkingOptionWhenThinkingIsNull`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java`
- What it proves:
  - factory always applies the provider model
  - no thinking-level option is written when the effective config omits thinking
- Fixtures/data:
  - `EffectiveSkillExecutionConfiguration` fixtures for non-thinking OpenAI and Ollama style cases
- Mocks:
  - mock or fake builder or adapter seam around `ChatClient` construction

### 12) `createsClientWithThinkingOptionWhenEffectiveConfigProvidesIt`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java`
- What it proves:
  - factory propagates `thinking_level` only when present in the validated effective config
- Fixtures/data:
  - `EffectiveSkillExecutionConfiguration` fixture with `thinkingLevel = "medium"`
- Mocks:
  - mock or fake builder or adapter seam around `ChatClient` construction

### 13) `dispatchesToProviderSpecificAdapter`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java`
- What it proves:
  - OpenAI, Anthropic, Gemini, and Ollama each route through the correct provider adapter
  - provider-specific details stay isolated behind the adapter layer
- Fixtures/data:
  - one execution-config fixture per provider
- Mocks:
  - mock provider adapters and verify delegation

## How to Run
- Full starter module tests: `.\mvnw.cmd -pl bifrost-spring-boot-starter test`
- Auto-configuration and model-binding slice: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostAutoConfigurationTests,BifrostModelsPropertiesTest test`
- YAML validation slice: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests test`
- Chat client factory slice: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=SpringAiSkillChatClientFactoryTests test`
- Existing Java capability regression slice: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=SkillMethodBeanPostProcessorTest,SkillMethodTest test`

## Exit Criteria
- [x] A failing integration test exists first for the missing happy-path behavior: valid YAML skill plus thinking-capable model resolves to effective `thinking_level=medium`
- [x] `bifrost.models` binding is covered by integration tests using `application-test.yml`
- [x] YAML discovery from `classpath:/skills/**/*.yaml` is covered by fixture-backed integration tests
- [x] Boot failure paths are covered for unknown model references and unsupported thinking levels
- [x] Factory unit tests cover provider selection plus thinking and no-thinking option application
- [x] Existing `@SkillMethod` tests still pass without semantic changes to the annotation
- [x] Full starter module test suite passes post-fix
- [ ] Manual verification confirms deterministic metadata for YAML-backed registrations and absence of `heavy` and `light` client bindings
