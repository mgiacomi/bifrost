# ENG-005 ChatClient Binding Implementation Plan

## Overview

Implement framework-managed model catalog binding and YAML skill execution resolution in `bifrost-spring-boot-starter` so YAML-defined LLM-backed skills declare an exact framework model, receive deterministic effective execution settings, and fail application boot when model or thinking-level configuration is invalid.

## Current State Analysis

The starter currently auto-configures only session behavior through `@EnableConfigurationProperties(BifrostSessionProperties.class)` in [BifrostAutoConfiguration.java](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\autoconfigure\BifrostAutoConfiguration.java#L14). Capability metadata still stores an abstract `ModelPreference` enum and defaults null values to `LIGHT` in [CapabilityMetadata.java](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\CapabilityMetadata.java#L6), while `@SkillMethod` exposes only `modelPreference` in [SkillMethod.java](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\annotation\SkillMethod.java#L12). The bean post-processor copies that annotation value directly into registry metadata in [SkillMethodBeanPostProcessor.java](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\SkillMethodBeanPostProcessor.java#L57).

There is no live YAML skill manifest loader, no bound `bifrost.models` catalog, and no `ChatClient` resolver/factory in `src/main`. Existing starter tests only cover auto-configuration registration plus `bifrost.session.max-depth` binding in [BifrostAutoConfigurationTests.java](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\autoconfigure\BifrostAutoConfigurationTests.java#L41) and `ModelPreference` propagation in [SkillMethodBeanPostProcessorTest.java](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\core\SkillMethodBeanPostProcessorTest.java#L18).

## Desired End State

The starter binds a strongly typed `bifrost.models` catalog from configuration, validates all discovered YAML LLM skill declarations during boot, computes an effective execution configuration per skill, stores that deterministic model selection in capability metadata, and resolves `ChatClient` instances through a shared factory/resolver instead of abstract `LIGHT`/`HEAVY` tiers. The model catalog is provider-aware so Bifrost can support OpenAI, Anthropic Claude, Gemini, and Ollama entries without changing YAML skill semantics.

Verification at the end of this plan means:
- starter integration tests can bind `bifrost.models` from `application-test.yml`
- a valid YAML skill using a thinking-capable model defaults `thinking_level` to `medium`
- a valid YAML skill using a non-thinking model resolves without a thinking-level override
- Spring boot fails for unknown models and unsupported thinking levels
- the framework can represent provider-specific runtime details for OpenAI, Claude, Gemini, and Ollama behind a common catalog contract
- resolver/factory tests prove the effective configuration is translated into the correct `ChatClient` setup

### Key Discoveries:
- Auto-configuration currently enables only session properties, so model-catalog support must be introduced as a new properties binding path in [BifrostAutoConfiguration.java](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\autoconfigure\BifrostAutoConfiguration.java#L14).
- Capability metadata currently persists only `ModelPreference`, so deterministic YAML skill execution requires a metadata shape change in [CapabilityMetadata.java](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\CapabilityMetadata.java#L6).
- `SkillMethodBeanPostProcessor` is the current registration seam for Java-backed capabilities, which means YAML skill registration/validation should be introduced as a separate boot-time path rather than folded into annotation scanning in [SkillMethodBeanPostProcessor.java](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\SkillMethodBeanPostProcessor.java#L31).
- The master spec already defines exact-model plus optional-thinking semantics, including `medium` as the default when supported, in [ai/thoughts/phases/README.md](C:\opendev\code\bifrost\ai\thoughts\phases\README.md#L48).
- ENG-008 establishes that YAML is the LLM-facing source of truth and `@SkillMethod` is an implementation target, so ENG-005 should align with that contract now instead of adding YAML-specific model fields to the annotation.

## What We're NOT Doing

- Fully redesigning `@SkillMethod`; ENG-005 should make only the smallest compatibility changes needed to prepare for ENG-008.
- Implementing the full `callSkill` execution coordinator or end-to-end prompt orchestration.
- Expanding beyond YAML LLM-backed skill model resolution into broader YAML manifest feature work unless needed to support minimal boot-time discovery.
- Designing multiple profile aliases such as `heavy` and `light`; this ticket moves away from that abstraction for YAML skills.

## Implementation Approach

Add a minimal but durable execution-configuration layer for YAML skills. The framework should own model catalog validation and effective-thinking defaults, while a dedicated resolver/factory consumes only already-validated execution settings. To keep scope aligned with the ticket and ENG-008, YAML remains the skill contract and `@SkillMethod` remains an implementation-discovery mechanism. Capability metadata is extended so YAML-backed registrations can record exact runtime model choices without making annotation-driven Java targets own LLM execution settings. The new YAML path should be built around four seams:

1. Strongly typed framework configuration for `bifrost.models`.
2. Provider-aware model entries that can describe common runtime settings for OpenAI, Anthropic, Gemini, and Ollama.
3. Boot-time YAML skill loading plus validation that produces an immutable effective execution config.
4. A `ChatClient` resolver/factory that maps that config to Spring AI client options.

## Phase 1: Add Framework Model Catalog Binding

### Overview

Introduce framework-owned model catalog configuration and make it available to the starter through validated configuration properties.

### Changes Required:

#### 1. Framework properties and types
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostModelsProperties.java`
**Changes**: Add a new `@ConfigurationProperties(prefix = "bifrost")` or `prefix = "bifrost.models"` binding model that captures named framework models, provider identity, exact provider model names, and optional supported thinking levels. Include bean validation to reject blank model names/provider values and invalid provider declarations. Treat `thinking_levels` as a generic cross-provider field; providers that do not support thinking simply leave it empty.

```java
@ConfigurationProperties(prefix = "bifrost")
public class BifrostModelsProperties {

    @NotEmpty
    private Map<String, ModelCatalogEntry> models = new LinkedHashMap<>();

    public static class ModelCatalogEntry {
        @NotNull
        private AiProvider provider;
        @NotBlank
        private String providerModel;
        private Set<String> thinkingLevels = Set.of();
    }
}
```

#### 2. Auto-configuration registration
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
**Changes**: Enable the new properties class alongside `BifrostSessionProperties`, and register any catalog-validation bean needed to fail fast when the catalog itself is malformed.

```java
@EnableConfigurationProperties({
        BifrostSessionProperties.class,
        BifrostModelsProperties.class
})
```

#### 3. Test fixture configuration
**File**: `bifrost-spring-boot-starter/src/test/resources/application-test.yml`
**Changes**: Add starter test configuration that exercises multiple providers, including at least one thinking-capable model and one non-thinking model.

```yaml
bifrost:
  models:
    gpt-5:
      provider: openai
      provider-model: openai/gpt-5
      thinking-levels: [low, medium, high]
    claude-sonnet:
      provider: anthropic
      provider-model: anthropic/claude-sonnet-4
      thinking-levels: [low, medium, high]
    gemini-pro:
      provider: gemini
      provider-model: google/gemini-2.5-pro
      thinking-levels: [low, medium, high]
    ollama-llama3:
      provider: ollama
      provider-model: llama3.2
```

### Success Criteria:

#### Automated Verification:
- [x] Starter tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter test`
- [x] Auto-configuration test proves `bifrost.models` binds from test config: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostAutoConfigurationTests test`
- [x] Auto-configuration test proves provider metadata binds for OpenAI, Anthropic, Gemini, and Ollama entries: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostAutoConfigurationTests test`
- [x] Configuration metadata is generated without validation errors: `.\mvnw.cmd -pl bifrost-spring-boot-starter test`

#### Manual Verification:
- [ ] Review confirms the framework catalog is the only source of truth for allowed model names
- [ ] Model entries clearly distinguish framework key from provider runtime name
- [ ] The test config demonstrates both thinking-capable and non-thinking variants across the initial provider set

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Introduce YAML Skill Execution Configuration and Validation

### Overview

Create the minimal YAML skill manifest and validation path needed for boot-time model resolution, including default `thinking_level` behavior.

### Changes Required:

#### 1. YAML manifest binding types
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`
**Changes**: Add manifest classes for the subset this ticket needs: skill name/description, target mapping, `model`, and optional `thinking_level`. Shape them to match the ENG-008 direction where YAML defines the skill contract and `mapping.target_id` points either to a deterministic `@SkillMethod` target or an LLM-backed execution path.

```java
public record YamlSkillManifest(
        String name,
        String description,
        SkillExecutionManifest execution,
        MappingManifest mapping) {
}

public record SkillExecutionManifest(String model, String thinkingLevel) {
}

public record MappingManifest(String targetId) {
}
```

#### 2. Effective execution model
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/EffectiveSkillExecutionConfiguration.java`
**Changes**: Introduce an immutable value object representing validated runtime settings: framework model key, provider identity, provider model name, and optional effective thinking level.

```java
public record EffectiveSkillExecutionConfiguration(
        String frameworkModel,
        AiProvider provider,
        String providerModel,
        @Nullable String thinkingLevel) {
}
```

#### 3. Loader and validator
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
**Changes**: Implement a boot-time loader that discovers YAML skills from `classpath:/skills/**/*.yaml`, binds manifests, validates that `model` exists in the framework catalog, defaults thinking level to `medium` when the selected model supports it, and fails startup for invalid model/thinking combinations.

```java
ModelCatalogEntry catalogEntry = modelsProperties.models().get(execution.model());
if (catalogEntry == null) {
    throw new IllegalStateException("Unknown model '" + execution.model() + "'");
}

String effectiveThinkingLevel = execution.thinkingLevel();
if (effectiveThinkingLevel == null && catalogEntry.supportsThinking()) {
    effectiveThinkingLevel = "medium";
}
if (!catalogEntry.supportsThinkingLevel(effectiveThinkingLevel)) {
    throw new IllegalStateException("Unsupported thinking level");
}
```

#### 4. Capability metadata extension
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java`
**Changes**: Extend capability metadata so YAML-backed capabilities can persist effective model configuration. Preserve compatibility for Java `@SkillMethod` registrations by introducing a dedicated execution descriptor sub-record rather than spreading nullable YAML-only fields across the full metadata shape.

```java
public record CapabilityMetadata(
        String id,
        String name,
        String description,
        ModelPreference modelPreference,
        SkillExecutionDescriptor skillExecution,
        Set<String> rbacRoles,
        CapabilityInvoker invoker) {
}
```

#### 5. ENG-008 documentation bridge for `@SkillMethod`
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/annotation/SkillMethod.java`
**Changes**: Do not change `@SkillMethod` behavior in ENG-005. Instead, document in code comments and planning artifacts that ENG-008 should introduce any neutral implementation identifier such as `targetId`, and that `modelPreference` is legacy behavior not to be expanded for YAML skill execution settings in this ticket.

```java
public @interface SkillMethod {
    ModelPreference modelPreference() default ModelPreference.LIGHT;
}
```

### Success Criteria:

#### Automated Verification:
- [x] Valid YAML skill defaults `thinking_level` to `medium` for a thinking-capable model: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests test`
- [x] Valid YAML skill on a non-thinking model resolves with no thinking level: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests test`
- [x] Boot fails for unknown model references: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests test`
- [x] Boot fails for unsupported thinking levels: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests test`
- [x] Deterministic Java-target skills can map through YAML `target_id` without requiring any new `@SkillMethod` model settings: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests,SkillMethodBeanPostProcessorTest test`

#### Manual Verification:
- [ ] Validation errors clearly identify the offending skill file and invalid field
- [ ] Effective execution config is deterministic even when `thinking_level` is omitted
- [ ] Java `@SkillMethod` capability registration behavior remains unchanged for non-YAML code paths
- [ ] The documentation and code shape make it clear that YAML owns LLM-facing execution config, not `@SkillMethod`
- [ ] ENG-005 leaves `@SkillMethod` behavioral changes to ENG-008 while still aligning the surrounding architecture now

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Add Shared ChatClient Resolver / Factory

### Overview

Encapsulate all client construction behind a shared resolver/factory that accepts validated skill execution configuration and produces the correct Spring AI `ChatClient`.

### Changes Required:

#### 1. Resolver/factory contract
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SkillChatClientFactory.java`
**Changes**: Define a small interface or service that accepts `EffectiveSkillExecutionConfiguration` and returns a configured `ChatClient`.

```java
public interface SkillChatClientFactory {
    ChatClient create(EffectiveSkillExecutionConfiguration executionConfiguration);
}
```

#### 2. Spring AI implementation
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java`
**Changes**: Implement the factory using the starter's Spring AI abstractions where helpful, but keep provider-specific option mapping isolated behind strategy classes or adapter methods so OpenAI, Anthropic, Gemini, and Ollama can differ internally without changing YAML or capability metadata contracts. Treat Spring AI common abstractions as useful infrastructure, not as a requirement to force all providers into an unnatural shared shape.

```java
ChatClient.Builder builder = chatClientBuilder.clone();
builder.defaultOptions(buildOptions(
        executionConfiguration.provider(),
        executionConfiguration.providerModel(),
        executionConfiguration.thinkingLevel()));
return builder.build();
```

#### 3. Auto-configuration seam
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
**Changes**: Register the default factory bean and ensure downstream YAML skill execution code consumes it instead of any future fixed `heavy`/`light` bean names.

### Success Criteria:

#### Automated Verification:
- [x] Factory unit tests verify provider model is always applied: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=SpringAiSkillChatClientFactoryTests test`
- [x] Factory unit tests verify thinking level is omitted for non-thinking configs: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=SpringAiSkillChatClientFactoryTests test`
- [x] Factory unit tests verify thinking level is set when effective config includes it: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=SpringAiSkillChatClientFactoryTests test`
- [x] Factory unit tests verify provider-specific option adapters for OpenAI, Anthropic, Gemini, and Ollama: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=SpringAiSkillChatClientFactoryTests test`
- [x] Full starter test suite still passes: `.\mvnw.cmd -pl bifrost-spring-boot-starter test`

#### Manual Verification:
- [ ] The resolver/factory API is narrow enough that callers cannot bypass validated execution config
- [ ] No `heavy` or `light` `ChatClient` bean naming convention is reintroduced in the new path
- [ ] Factory behavior is traceable from YAML skill model key to final provider model selection
- [ ] Provider-specific quirks stay isolated to factory internals rather than leaking into YAML skill contracts

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 4: Wire Metadata, Registration, and Regression Coverage

### Overview

Finish the end-to-end starter integration by registering YAML-backed capabilities with deterministic execution metadata and locking down regressions in the existing Java capability flow.

### Changes Required:

#### 1. YAML capability registration path
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java`
**Changes**: Register validated YAML skills into `CapabilityRegistry`, attaching the effective execution config to capability metadata so downstream execution remains reproducible and traceable. When `mapping.target_id` points to a deterministic implementation discovered through `@SkillMethod`, link to that target without copying YAML execution config responsibilities back into the annotation.

```java
CapabilityMetadata metadata = new CapabilityMetadata(
        skill.id(),
        skill.name(),
        skill.description(),
        null,
        SkillExecutionDescriptor.from(effectiveConfig),
        skill.rbacRoles(),
        yamlSkillInvoker);
```

#### 2. Regression coverage for existing Java capabilities
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`
**Changes**: Update tests to prove `@SkillMethod` registrations still populate metadata correctly after the metadata shape changes, even though ENG-005 does not change `@SkillMethod` semantics.

#### 3. Starter integration tests
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
**Changes**: Extend or add context-runner tests that load the new YAML-skill beans/resources, validate startup success/failure modes, and assert metadata contents for a valid YAML skill.

### Success Criteria:

#### Automated Verification:
- [x] Capability metadata for valid YAML skills contains exact framework model and effective provider model: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostAutoConfigurationTests,YamlSkillCatalogTests test`
- [x] Existing `SkillMethodBeanPostProcessorTest` remains green after metadata changes: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=SkillMethodBeanPostProcessorTest test`
- [x] Full module tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter test`

#### Manual Verification:
- [ ] Capability inspection shows deterministic metadata for YAML-backed registrations
- [ ] Boot failure paths are understandable to maintainers without reading source code
- [ ] Scope remains limited to YAML skill model resolution rather than broader `callSkill` orchestration

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

## Testing Strategy

### Unit Tests:
- Add properties-binding tests for `BifrostModelsProperties`, including blank provider-model rejection and optional thinking-level lists.
- Add validator tests covering unknown models, unsupported thinking levels, default-to-`medium`, omission for non-thinking models, and provider-specific catalog entries.
- Add factory tests that assert option mapping from `EffectiveSkillExecutionConfiguration` into `ChatClient` construction for OpenAI, Anthropic, Gemini, and Ollama.
- Update `SkillMethodBeanPostProcessorTest` to cover the revised metadata record shape without changing current annotation behavior.

### Integration Tests:
- Use `ApplicationContextRunner` to bind `application-test.yml`, assert `BifrostModelsProperties` bean contents, and validate boot success/failure cases across the initial provider set.
- Add resource-backed YAML skill fixtures under `src/test/resources/skills/...` so discovery exercises `classpath:/skills/**/*.yaml` directly for valid, unknown-model, unsupported-thinking, and non-thinking scenarios.
- Verify a valid YAML skill registration publishes capability metadata that contains effective model-selection details.
- Verify a valid YAML skill can reference a discovered Java implementation target through `mapping.target_id`.

**Note**: Prefer a dedicated testing plan artifact created via `/testing_plan` for full details (impacted areas, failing test first, commands to run, exit criteria). Keep this section as a high-level summary.

### Manual Testing Steps:
1. Start a test application context with a valid thinking-capable model catalog and confirm the resolved YAML skill metadata reports `thinking_level=medium` when omitted in the YAML file.
2. Start a second context with a non-thinking model entry and confirm the same omission results in no thinking-level setting.
3. Start contexts with OpenAI, Anthropic, Gemini, and Ollama catalog entries and confirm the same YAML contract resolves through provider-specific factory behavior without changing the skill manifest shape.
4. Start contexts with an unknown model and unsupported thinking level and confirm boot fails with targeted validation messages.
5. Inspect the default `SkillChatClientFactory` wiring and confirm there are no fixed `heavy` or `light` `ChatClient` bean dependencies.

## Performance Considerations

Model validation and YAML manifest loading occur at boot, so the runtime cost should be negligible after startup. Keep validation single-pass and cache effective execution configuration in capability metadata so repeated execution does not re-read YAML or re-run catalog validation.

## Migration Notes

This change introduces a new framework configuration contract under `bifrost.models`, so sample/test applications will need to define explicit model catalog entries before enabling YAML LLM-backed skills. Existing Java `@SkillMethod` capabilities should remain source-compatible during this ticket even if metadata internals are extended. ENG-005 should document, but not implement, the forthcoming ENG-008 `@SkillMethod` contract cleanup.

## References

- Original ticket/requirements: `ai/thoughts/tickets/eng-005-chatclient-binding.md`
- Related research: `ai/thoughts/research/2026-03-15-ENG-005-chatclient-binding.md`
- Existing auto-configuration seam: [BifrostAutoConfiguration.java](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\autoconfigure\BifrostAutoConfiguration.java#L14)
- Existing capability metadata shape: [CapabilityMetadata.java](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\CapabilityMetadata.java#L6)
- Existing Java capability registration path: [SkillMethodBeanPostProcessor.java](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\SkillMethodBeanPostProcessor.java#L31)
- Existing starter test baseline: [BifrostAutoConfigurationTests.java](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\autoconfigure\BifrostAutoConfigurationTests.java#L41)
- Existing `ModelPreference` abstraction to avoid reusing for YAML skills: [ModelPreference.java](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\ModelPreference.java#L3)
