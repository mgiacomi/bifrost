# ENG-027 Provider-Specific Skill Model Resolution Implementation Plan

## Overview

Fix the runtime/provider mismatch so a YAML skill's resolved `provider` determines the underlying Spring AI `ChatModel` used for execution, rather than only influencing `ChatOptions`. The change should preserve the existing YAML model catalog resolution, provider-specific options adapters, and advisor behavior while removing the single shared `ChatClient.Builder` assumption that currently collapses execution onto Taalas.

## Current State Analysis

The YAML loading path already resolves the effective execution configuration correctly. `YamlSkillCatalog.loadDefinition(...)` validates `model`, resolves it through `BifrostModelsProperties`, and stores `frameworkModel`, `provider`, `providerModel`, and `thinkingLevel` in `EffectiveSkillExecutionConfiguration` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:115-137`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/EffectiveSkillExecutionConfiguration.java:6-10`).

The runtime bug happens later. `SpringAiSkillChatClientFactory.create(...)` picks a provider-specific `SkillChatOptionsAdapter`, but then clones one injected `ChatClient.Builder` and applies options to that builder regardless of which provider the skill resolved to (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:46-68`). Auto-configuration currently supplies that builder from a single Taalas-backed `ChatModel` when Taalas is enabled (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:289-357`). `TaalasChatModel` always POSTs to the Taalas endpoint and only uses `ChatOptions.getModel()` as the model string to send, so `OllamaChatOptions` on top of a Taalas-backed client cannot switch providers (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/TaalasChatModel.java:50-113`).

Current tests cover provider-specific options construction and advisor attachment, but they do not assert that the underlying `ChatModel` changes with provider (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java:30-166`). Auto-configuration tests currently assert that Taalas auto-configures a `ChatClient.Builder`, which will need to change as part of this refactor (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java:151-163`).

## Desired End State

A skill's resolved `AiProvider` must control the runtime `ChatModel` selection. For example, the sample `invoiceParser` skill (`bifrost-sample/src/main/resources/skills/invoice_parser.yml:1-30`) configured with `model: granite4-tiny` and mapped to `provider: ollama` in `bifrost-sample/src/main/resources/application.yml:30-44` must execute through the Ollama-backed model, not Taalas.

The end state is complete when:

- skill execution chooses the correct provider-specific `ChatModel`
- provider-specific `SkillChatOptionsAdapter` behavior remains unchanged
- advisor resolution and attachment remain unchanged
- missing provider runtime wiring fails at execution time with a clear, skill-specific exception
- starter auto-configuration no longer depends on a single shared `ChatClient.Builder`
- tests make provider selection behavior explicit

### Key Discoveries:
- `YamlSkillCatalog` already resolves `provider` and `providerModel` correctly into `EffectiveSkillExecutionConfiguration` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:123-137`).
- `SpringAiSkillChatClientFactory` currently loses provider routing by cloning a single injected builder (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:54-61`).
- `BifrostAutoConfiguration` is the place where the shared Taalas-backed builder assumption is introduced (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:299-357`).
- `ExecutionCoordinator` already delegates skill-specific client creation through `SkillChatClientFactory`, so the fix can stay localized to the chat client assembly path (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:66-80`).
- Existing tests validate options/advisors but not provider-bound model selection, so coverage must shift from builder cloning to resolver-driven model routing (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java:94-165`).

## What We're NOT Doing

- Redesigning YAML skill parsing or `BifrostModelsProperties`
- Changing `EffectiveSkillExecutionConfiguration` shape unless a small helper becomes necessary
- Replacing `SkillChatOptionsAdapter` with a new options abstraction
- Changing advisor semantics, ordering, or retry behavior beyond preserving current behavior through the refactor
- Adding startup-time validation that every catalog provider has a matching runtime bean; unavailable providers should fail when the affected skill is executed
- Reworking sample-controller business logic outside of verification coverage

## Implementation Approach

Introduce a small provider-to-`ChatModel` resolution abstraction in the starter module, wire it from available provider model beans, and refactor `SpringAiSkillChatClientFactory` to build a fresh `ChatClient` from the resolved provider-specific `ChatModel` per skill. This keeps the fix close to the point where skill-specific runtime clients are assembled and avoids spreading provider logic into YAML loading or execution orchestration.

The implementation should prefer explicit bean wiring or an internal provider registry over ambiguous `ChatModel`-by-type injection because multiple provider models may coexist. The factory should remain responsible for producing a per-skill `ChatClient`; the new resolver should only answer which `ChatModel` to use for a given `AiProvider`. Tests should make bean qualification and multi-provider wiring explicit so the refactor does not depend on fragile `ChatModel` resolution by type alone.

## Phase 1: Introduce Provider-Aware ChatModel Resolution

### Overview
Create the runtime abstraction that maps a resolved `AiProvider` to the correct Spring AI `ChatModel`, with deterministic execution-time failure when the requested provider is not configured.

### Changes Required:

#### 1. Chat model resolver abstraction
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SkillChatModelResolver.java`
**Changes**: Add a small interface for provider-aware `ChatModel` lookup.

```java
public interface SkillChatModelResolver {
    ChatModel resolve(String skillName, AiProvider provider);
}
```

Using `skillName` in the API keeps error construction local and allows clear failures such as `No ChatModel configured for provider OLLAMA required by skill 'invoiceParser'`.

#### 2. Default resolver implementation
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillChatModelResolver.java`
**Changes**: Add a resolver backed by an `EnumMap<AiProvider, ChatModel>` or equivalent immutable registry.

```java
public ChatModel resolve(String skillName, AiProvider provider) {
    ChatModel model = modelsByProvider.get(provider);
    if (model == null) {
        throw new IllegalStateException(
            "No ChatModel configured for provider " + provider + " required by skill '" + skillName + "'");
    }
    return model;
}
```

#### 3. Auto-configuration wiring
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
**Changes**:
- keep provider model beans such as `taalasChatModel(...)`
- remove the infrastructure assumption that Bifrost must expose a shared `ChatClient.Builder`
- add a `SkillChatModelResolver` bean assembled from available provider `ChatModel` beans
- prefer explicit bean names/qualifiers for Bifrost-owned providers to avoid accidental type-only resolution
- preserve missing-provider behavior as an execution-time error for the specific skill invocation rather than a startup-time catalog validation failure

This phase should preserve compatibility with external provider starters by resolving whichever provider model beans are actually present in the context.

### Success Criteria:

#### Automated Verification:
- [x] New resolver tests pass: `./mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=DefaultSkillChatModelResolverTests test`
- [x] Starter module compiles after adding resolver infrastructure: `./mvnw.cmd -pl bifrost-spring-boot-starter -DskipTests compile`
- [x] Auto-configuration tests covering resolver registration pass: `./mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostAutoConfigurationTests test`
- [x] No separate lint step exists in this repo; compilation/tests remain green via the Maven commands above

#### Manual Verification:
- [ ] Review bean wiring to confirm no shared mutable `ChatClient.Builder` remains in the skill execution path
- [ ] Review exception text for an unavailable provider and confirm it is actionable for a sample skill author
- [ ] Confirm multi-provider contexts remain understandable from logs/debugging output
- [ ] Confirm the resolver API is small enough to remain internal infrastructure rather than a new public extension burden

---

## Phase 2: Refactor Skill ChatClient Assembly to Use the Resolved ChatModel

### Overview
Make `SpringAiSkillChatClientFactory` provider-aware at the `ChatModel` level while preserving existing provider-specific options adapters and advisor behavior.

### Changes Required:

#### 1. Refactor factory constructor and create path
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java`
**Changes**:
- replace the injected `ChatClient.Builder` dependency with `SkillChatModelResolver`
- resolve the provider-specific `ChatModel` from `definition.executionConfiguration().provider()`
- create a fresh builder from `ChatClient.builder(resolvedChatModel)`
- continue applying `SkillChatOptionsAdapter` output and advisors exactly as today

```java
ChatModel chatModel = chatModelResolver.resolve(definition.manifest().getName(), executionConfiguration.provider());
ChatClient.Builder builder = ChatClient.builder(chatModel);
builder.defaultOptions(adapter.createOptions(executionConfiguration));
```

#### 2. Keep provider-specific options logic intact
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java`
**Changes**: Preserve the current adapter set for OpenAI, Anthropic, Gemini, Ollama, and Taalas so the fix only changes provider transport selection, not option semantics.

#### 3. Update factory-focused tests
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java`
**Changes**:
- rewrite tests so they assert the resolved provider selects the correct backing `ChatModel`
- preserve assertions around provider-specific options and advisor attachment
- add a failure-mode test for an unavailable provider
- stop coupling tests to `Builder.clone()` since the builder should now be created fresh from the resolved model
- prefer explicit unit tests for routing behavior, introducing a tiny package-private builder creation seam only if needed to make `ChatClient.builder(...)` observable without brittle mocking

A practical test shape is to mock `SkillChatModelResolver`, return provider-specific `ChatModel` doubles, and intercept `ChatClient.builder(...)` construction indirectly through a seam if needed. If `ChatClient.builder(...)` is hard to observe directly, introduce a tiny package-private builder creation seam to keep tests deterministic.

### Success Criteria:

#### Automated Verification:
- [x] Factory tests prove `OLLAMA` skills use the Ollama `ChatModel`: `./mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=SpringAiSkillChatClientFactoryTests test`
- [x] Factory tests prove `TAALAS` skills use the Taalas `ChatModel`: `./mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=SpringAiSkillChatClientFactoryTests test`
- [x] Factory tests prove advisors still attach correctly: `./mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=SpringAiSkillChatClientFactoryTests test`
- [x] Factory tests prove unavailable providers fail clearly: `./mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=SpringAiSkillChatClientFactoryTests test`

#### Manual Verification:
- [ ] Review the factory diff to confirm provider routing is decided before `ChatClient` creation, not after
- [ ] Confirm logs still include skill name/provider context useful for debugging runtime routing
- [ ] Confirm thinking-level behavior for OpenAI/Anthropic/Gemini remains unchanged by the transport refactor
- [ ] Confirm advisor ordering remains identical to the pre-refactor behavior


---

## Phase 3: Align Auto-Configuration and Add Regression Coverage

### Overview
Update starter auto-configuration tests and add regression coverage for provider-routing behavior without turning the sample invoice scenario into an automated integration test fixture.

### Changes Required:

#### 1. Update auto-configuration expectations
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
**Changes**:
- replace assertions that a shared `ChatClient.Builder` bean is auto-configured
- add assertions that `SkillChatModelResolver` is present when provider model beans are available
- verify `SkillChatClientFactory` is created from resolver-based wiring rather than builder-based wiring
- add a test covering a missing requested provider path where practical
- make multi-provider bean qualification an explicit test concern so resolver registration does not accidentally collapse to whichever `ChatModel` bean wins by type

#### 2. Add execution-path regression coverage
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
**Changes**: Add or extend a test only if it meaningfully verifies that `ExecutionCoordinator` still orchestrates skill execution correctly with the resolver-based factory wiring; keep provider-routing assertions primarily in factory and auto-configuration tests rather than in a sample-specific integration flow.

If the coordinator layer is too indirect for meaningful provider assertions, keep the routing assertion in factory/auto-config tests and use coordinator tests only to ensure existing orchestration still succeeds with the new factory wiring.

#### 3. Keep the sample invoice scenario as manual verification only
**File**: `bifrost-sample/src/main/resources/skills/invoice_parser.yml`
**File**: `bifrost-sample/src/main/resources/application.yml`
**Changes**: No production config changes required for the fix itself; use the existing sample configuration as a manual verification fixture because it already reproduces the bug conditions, but do not promote it to an automated integration test.

### Success Criteria:

#### Automated Verification:
- [x] Auto-configuration regression tests pass: `./mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostAutoConfigurationTests test`
- [x] Full starter module test suite passes: `./mvnw.cmd -pl bifrost-spring-boot-starter test`
- [x] Full reactor tests still pass after the refactor: `./mvnw.cmd test`

#### Manual Verification:
- [ ] Start the sample app with both Taalas and Ollama enabled and ensure Ollama is reachable locally.
- [ ] Run the sample invoice parsing endpoint using the existing `invoiceParser` skill configuration.
- [ ] Confirm the request does not hit `TaalasChatModel` when the skill resolves to `OLLAMA`.
- [ ] Reconfigure a sample skill to `provider: taalas` through `bifrost.models` and confirm it still routes to Taalas.
- [ ] Disable the provider required by a skill and confirm the thrown exception clearly identifies the missing provider and skill name.

## Testing Strategy

### Unit Tests:
- Add focused resolver tests for provider lookup success and failure
- Update `SpringAiSkillChatClientFactoryTests` to assert provider-specific `ChatModel` selection, options application, advisor attachment, and missing-provider errors
- Preserve current options-adapter coverage for OpenAI, Anthropic, Gemini, Ollama, and Taalas

### Integration Tests:
- Extend `BifrostAutoConfigurationTests` to validate resolver registration and skill factory creation in multi-provider contexts
- Add or update `ExecutionCoordinatorTest` only where it adds meaningful regression protection for end-to-end skill execution wiring
- Keep provider-routing assertions focused on starter-module tests rather than the sample invoice scenario; treat the sample as manual verification only

**Note**: Prefer a dedicated testing plan artifact created via `3_testing_plan.md` for full details (impacted areas, failing test first, commands to run, exit criteria). Keep this section as a high-level summary.

### Manual Testing Steps:
1. Run the sample app with both Taalas and Ollama enabled and ensure Ollama is reachable locally.
2. Invoke the sample invoice parsing endpoint using the existing `invoiceParser` skill configuration.
3. Confirm the request does not hit `TaalasChatModel` when the skill resolves to `OLLAMA`.
4. Reconfigure a sample skill to `provider: taalas` through `bifrost.models` and confirm it still routes to Taalas.
5. Disable the provider required by a skill and confirm the thrown exception clearly identifies the missing provider and skill name.

## Performance Considerations

Creating a fresh `ChatClient.Builder` from a resolved `ChatModel` per skill should be inexpensive relative to network-bound model execution and is cleaner than caching a mutable shared builder whose provider binding can be wrong. The main performance risk is accidental repeated registry construction; avoid this by building the provider registry once in auto-configuration and keeping resolver lookup O(1).

## Migration Notes

This change is behavioral rather than data-oriented. Existing skill YAML and `bifrost.models` entries should continue to work unchanged. The only notable migration impact is for consumers that implicitly relied on a shared `ChatClient.Builder` bean for skill execution internals; after this refactor, Bifrost skill execution should depend on `SkillChatModelResolver` plus provider model beans instead. If external customizations override `SkillChatClientFactory`, they should continue to work untouched.

## References

- Original ticket/requirements: `ai/thoughts/tickets/eng-027-provider-specific-skill-model-resolution.md`
- Similar/current implementation: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:46-68`
- YAML model resolution path: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:115-137`
- Shared-builder auto-configuration: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:299-357`
- Taalas transport behavior: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/TaalasChatModel.java:50-113`
- Existing factory tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java:30-166`
- Existing auto-configuration tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java:151-163`
