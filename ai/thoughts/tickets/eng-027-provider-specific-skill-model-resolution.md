# ENG-027: Skill-defined model provider is resolved but not used at runtime

## Summary

Bifrost correctly loads a skill's `model` value from YAML and resolves it through `bifrost.models` into an effective execution configuration containing:

- framework model name
- provider
- provider-specific model name
- optional thinking level

However, the runtime chat execution path does **not** use the resolved provider to select the underlying `ChatModel`. Instead, all skills are executed through a single shared `ChatClient.Builder` bean that is currently backed by the Taalas `ChatModel` whenever Taalas is enabled.

As a result, a skill configured to use an Ollama-backed model still executes against Taalas, which is why the sample `invoiceParser` skill produced a Taalas 429 even after the YAML was changed to `model: granite4-tiny`.

## User-visible symptom

A request to the sample endpoint:

`GET /invoice/parse?filePath=...`

fails with an error similar to:

```text
Taalas request failed with status 429: {"detail":"Server is busy. Try again later."}
java.lang.IllegalStateException: Taalas request failed with status 429: {"detail":"Server is busy. Try again later."}
    at com.lokiscale.bifrost.chat.TaalasChatModel.call(TaalasChatModel.java:71)
```

The sample skill was configured as:

```yaml
name: invoiceParser
model: granite4-tiny
```

and `application.yml` mapped that model as:

```yaml
bifrost:
  models:
    granite4-tiny:
      provider: ollama
      provider-model: ibm/granite4:tiny-h
```

The expectation is that this skill should execute through Ollama. The actual behavior is that it still executes through Taalas.

## Root cause

### What is working correctly

The YAML skill loader and model catalog resolution are functioning correctly.

1. `YamlSkillManifest` defines a `model` field.
2. `YamlSkillCatalog` loads each YAML skill and validates that `model` is present.
3. `YamlSkillCatalog.resolveModelCatalogEntry(...)` looks up the model in `BifrostModelsProperties`.
4. `YamlSkillCatalog.loadDefinition(...)` constructs `EffectiveSkillExecutionConfiguration` with:
   - `frameworkModel`
   - `provider`
   - `providerModel`
   - `thinkingLevel`

This means the `invoiceParser` skill correctly resolves to provider `OLLAMA` and provider model `ibm/granite4:tiny-h`.

### What is broken

`SpringAiSkillChatClientFactory` chooses provider-specific `ChatOptions`, but it does **not** choose a provider-specific `ChatModel`.

Instead, it clones one injected `ChatClient.Builder`:

- `SpringAiSkillChatClientFactory.create(...)`

That shared builder comes from auto-configuration:

- `BifrostAutoConfiguration.taalasChatClientBuilder(...)`

That builder is constructed like this:

```java
return ChatClient.builder(taalasChatModel);
```

So when the skill factory later does:

- resolve provider = `OLLAMA`
- create `OllamaChatOptions`
- call `builder.defaultOptions(options)`

it is still using a `ChatClient.Builder` whose backing `ChatModel` is `TaalasChatModel`.

### Why provider-specific options are insufficient

`TaalasChatModel` ignores the provider type entirely. It always sends an HTTP request to the configured Taalas endpoint. The only part of `ChatOptions` it uses is `options.getModel()` to determine the string model name to send in the Taalas request.

That means this runtime combination is currently possible:

- backing `ChatModel`: Taalas
- `ChatOptions` type: Ollama options
- `ChatOptions.model`: `ibm/granite4:tiny-h`

This does **not** switch execution to Ollama. It only causes Taalas to receive a request with a different model name.

## Impact

This bug affects any skill that relies on provider-specific model routing through `bifrost.models`.

Current effective behavior appears to be:

- if Taalas is enabled and provides the shared `ChatClient.Builder`, all skills route through Taalas regardless of resolved provider
- provider-specific adapters only affect `ChatOptions`, not the actual execution provider

Likely impact includes:

- skills configured for Ollama still calling Taalas
- future provider-specific skills for OpenAI / Anthropic / Gemini also being incorrectly routed if the same shared builder pattern is used
- misleading logs and runtime behavior where effective config says one provider but the underlying transport is another

## Recommended fix

### Preferred approach

The best fit for the current architecture is:

- introduce a provider-aware `ChatModel` resolver/registry
- make `SpringAiSkillChatClientFactory` select the correct `ChatModel` per skill based on `definition.executionConfiguration().provider()`
- build a fresh `ChatClient.Builder` from that selected `ChatModel`
- continue using the existing provider-specific `SkillChatOptionsAdapter` infrastructure for options construction

### Why this is the best fit

This approach aligns with the current design:

- YAML loading is already correct
- `EffectiveSkillExecutionConfiguration` already contains the resolved provider
- `SkillChatOptionsAdapter` already encapsulates provider-specific options
- `SpringAiSkillChatClientFactory` is already the place where a skill-specific client is assembled

So the smallest coherent fix is to make the factory provider-aware at the `ChatModel` level.

### Why not use provider -> ChatClient.Builder as the primary design

This is possible, but less clean than resolving provider -> `ChatModel` because:

- the real provider binding is at the `ChatModel`
- builders are inexpensive to create from the selected model
- introducing multiple builder beans adds wiring complexity without solving a separate problem
- the current bug exists specifically because a single builder hides a single bound model

## Proposed design

Introduce a small abstraction, for example:

- `SkillChatModelResolver`
- or `ProviderChatModelRegistry`

Suggested responsibility:

- given an `AiProvider`, return the corresponding Spring AI `ChatModel`
- fail with a clear error if the provider is requested but no model is configured/enabled

### Example behavior

For a skill whose effective execution configuration is:

- provider = `OLLAMA`
- providerModel = `ibm/granite4:tiny-h`

`SpringAiSkillChatClientFactory.create(...)` should:

1. read `executionConfiguration.provider()`
2. ask the resolver for the `OLLAMA` `ChatModel`
3. create a builder using `ChatClient.builder(resolvedChatModel)`
4. apply provider-specific options from `SkillChatOptionsAdapter`
5. apply advisors
6. build and return the skill-specific `ChatClient`

## Concrete implementation plan

### 1. Add provider-aware `ChatModel` resolution

Create a small infrastructure abstraction in the starter module, such as:

- `com.lokiscale.bifrost.chat.SkillChatModelResolver`

Potential API:

```java
public interface SkillChatModelResolver {
    ChatModel resolve(AiProvider provider);
}
```

Then create a default implementation backed by configured provider beans.

### 2. Wire available providers into the resolver

Use Spring auto-configuration to register available provider models.

Important detail: the resolver should handle the fact that some providers may be disabled. It should only expose configured providers and fail clearly when a skill requests an unavailable one.

Potential error message:

```text
No ChatModel configured for provider OLLAMA required by skill 'invoiceParser'
```

Whether the skill name should be included can be decided at the factory layer.

### 3. Update `SpringAiSkillChatClientFactory`

Refactor the factory constructor to receive the resolver instead of a shared `ChatClient.Builder`.

Current constructor:

```java
public SpringAiSkillChatClientFactory(ChatClient.Builder chatClientBuilder,
                                      List<SkillChatOptionsAdapter> adapters,
                                      SkillAdvisorResolver skillAdvisorResolver)
```

Target direction:

```java
public SpringAiSkillChatClientFactory(SkillChatModelResolver chatModelResolver,
                                      List<SkillChatOptionsAdapter> adapters,
                                      SkillAdvisorResolver skillAdvisorResolver)
```

Within `create(...)`:

- resolve provider-specific `ChatModel`
- construct `ChatClient.Builder` from that model directly
- apply options and advisors as before

### 4. Update auto-configuration

Remove the implicit assumption that a single `ChatClient.Builder` bean is the runtime provider for all skills.

Instead:

- keep provider model beans
- register the new resolver bean
- register `SkillChatClientFactory` against the resolver

### 5. Preserve existing provider-specific options adapters

The existing adapters in `SpringAiSkillChatClientFactory.defaultAdapters()` appear structurally fine and should remain in use.

Examples:

- `OpenAiOptionsAdapter`
- `AnthropicOptionsAdapter`
- `GeminiOptionsAdapter`
- `OllamaOptionsAdapter`
- `TaalasOptionsAdapter`

These should continue to translate `EffectiveSkillExecutionConfiguration` into provider-appropriate `ChatOptions`.

## Important files to inspect/change

### YAML loading and effective model resolution

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/EffectiveSkillExecutionConfiguration.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostModelsProperties.java`

### Runtime chat client assembly

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SkillChatOptionsAdapter.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SkillAdvisorResolver.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java`

### Auto-configuration and provider beans

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/TaalasChatModel.java`

### Execution path

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`

## Specific code facts already verified

### `YamlSkillCatalog` correctly resolves the configured model

`YamlSkillCatalog.loadDefinition(...)` validates `manifest.getModel()` and resolves it through `modelsProperties.getModels().get(manifest.getModel())`, then creates:

```java
new EffectiveSkillExecutionConfiguration(
    manifest.getModel(),
    catalogEntry.getProvider(),
    catalogEntry.getProviderModel(),
    effectiveThinkingLevel);
```

### `SpringAiSkillChatClientFactory` currently uses one shared builder

Current logic:

```java
ChatOptions options = adapter.createOptions(executionConfiguration);
ChatClient.Builder builder = chatClientBuilder.clone();
builder.defaultOptions(options);
...
ChatClient delegate = builder.build();
```

This is the critical flaw because the builder is already bound to a specific underlying `ChatModel`.

### `BifrostAutoConfiguration` currently creates a Taalas-backed builder

Current bean:

```java
@Bean
@ConditionalOnBean(ChatModel.class)
@ConditionalOnMissingBean(ChatClient.Builder.class)
@ConditionalOnProperty(prefix = "spring.ai.taalas", name = "enabled", havingValue = "true")
public ChatClient.Builder taalasChatClientBuilder(ChatModel taalasChatModel) {
    return ChatClient.builder(taalasChatModel);
}
```

This effectively makes Taalas the shared backend for skill execution.

### `TaalasChatModel` always calls Taalas

`TaalasChatModel.call(...)` always POSTs to the Taalas endpoint and only reads `ChatOptions.getModel()` as a string model selector.

This proves that using `OllamaChatOptions` on top of a Taalas-backed client cannot switch providers.

## Test coverage to add or update

Existing tests worth reviewing:

- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorOutputSchemaIntegrationTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorLinterIntegrationTest.java`

### Recommended new/updated tests

#### Unit tests for provider-aware factory behavior

Add tests proving that:

1. a skill resolved to `OLLAMA` uses the Ollama `ChatModel`
2. a skill resolved to `TAALAS` uses the Taalas `ChatModel`
3. provider-specific options are still applied correctly
4. advisors are still attached correctly
5. requesting an unavailable provider fails with a clear exception

#### Auto-configuration tests

Add tests proving that:

1. enabling multiple providers does not collapse runtime execution to a single shared provider
2. `SkillChatClientFactory` can resolve the correct provider model at runtime
3. when a provider is disabled but referenced by a skill, startup or execution fails deterministically and clearly

#### Integration test for the sample scenario

If practical, add an integration test covering the sample invoice parser scenario:

- `invoiceParser` configured with `model: granite4-tiny`
- model catalog maps `granite4-tiny` to `OLLAMA`
- execution path uses the Ollama-backed model rather than Taalas

## Acceptance criteria

The task is complete when all of the following are true:

1. a skill's resolved `provider` controls the underlying `ChatModel` used for execution
2. provider-specific `ChatOptions` remain in effect
3. `invoiceParser` configured to use `granite4-tiny` executes via Ollama rather than Taalas
4. Taalas is only used when the skill resolves to provider `TAALAS`
5. errors for unavailable providers are clear and actionable
6. existing advisor behavior remains intact
7. tests cover provider selection and failure modes

## Risks and edge cases

- Some Spring AI providers may auto-register their own `ChatModel` beans differently; bean qualification must be handled carefully.
- If multiple `ChatModel` beans exist, avoid accidental resolution by type alone. Use explicit bean wiring or an internal registry abstraction.
- Ensure the refactor does not break advisor application order.
- Ensure thinking-level behavior remains provider-specific and unchanged.
- Avoid keeping a shared mutable builder if provider binding should vary per skill.

## Suggested implementation notes for a fresh-context LLM

1. Start by reading `YamlSkillCatalog`, `SpringAiSkillChatClientFactory`, `BifrostAutoConfiguration`, and `TaalasChatModel`.
2. Confirm that model/provider resolution is already correct before making changes.
3. Implement the smallest coherent fix: provider-aware `ChatModel` selection in the factory path.
4. Prefer a new resolver/registry abstraction over ad hoc bean-name lookups.
5. Update tests before or alongside the refactor so the provider-selection behavior is explicit.
6. Validate that the sample `invoiceParser` path now routes to Ollama when configured that way.

## Non-goals

- Do not redesign YAML skill parsing; it is already working.
- Do not remove provider-specific `SkillChatOptionsAdapter` logic unless necessary.
- Do not change linter or output-schema advisor behavior except as needed to preserve existing functionality after the refactor.

## Final diagnosis in one sentence

Bifrost correctly resolves the skill's configured model to provider `OLLAMA`, but the runtime always executes through a single Taalas-backed `ChatClient.Builder`, so provider selection is lost before the model call is made.
