# ENG-017 Spring AI Linter Call Advisor Plumbing Implementation Plan

## Overview

Wire Spring AI advisor composition into YAML skill chat-client creation so skill-scoped runtime behaviors, including the future linter advisor, can be attached without changing mission orchestration. The implementation should keep provider option mapping intact, move advisor selection behind a dedicated resolver abstraction, and preserve today's behavior for skills with no linter config.

## Current State Analysis

YAML skill execution already has a single runtime seam for model client creation, but that seam only accepts `EffectiveSkillExecutionConfiguration` and therefore cannot see skill-scoped linter metadata. The Spring AI-backed factory currently clones the shared `ChatClient.Builder`, applies provider-specific `ChatOptions`, and builds the client without any advisor attachment step. Meanwhile, `YamlSkillDefinition` already exposes `linter()` alongside the resolved execution configuration, so the missing work is wiring rather than schema expansion.

## Desired End State

`ExecutionCoordinator` hands the full `YamlSkillDefinition` to `SkillChatClientFactory`, the Spring AI implementation composes provider options plus zero-or-more resolved advisors during client creation, and a default no-op advisor resolver keeps non-linter skills on the same path with no behavior change. After this ticket, ENG-018 can add a real linter advisor without reworking the execution pipeline.

Verification at the end of this plan means:
- chat-client creation remains the only place where provider options and advisors are composed
- skill definitions with no linter config resolve an empty advisor list and behave exactly as before
- Spring AI client creation can attach advisors through the builder/request defaults path
- coordinator and auto-config tests cover the new contract and default wiring

### Key Discoveries:
- `SkillChatClientFactory` currently accepts only `EffectiveSkillExecutionConfiguration`, which prevents factory-time access to `YamlSkillDefinition.linter()` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SkillChatClientFactory.java:8`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:23`).
- `ExecutionCoordinator` already loads the full YAML definition before creating the client, so the runtime context for the contract break already exists (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:55`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:64`).
- `SpringAiSkillChatClientFactory` currently composes only `defaultOptions(...)`; advisor composition belongs next to that step rather than inside mission execution (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:37`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:45`).
- Auto-configuration already owns factory assembly from a shared `ChatClient.Builder`, making it the right place to provide a default no-op resolver bean (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:230`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:239`).
- Existing tests assert provider option mapping and coordinator factory delegation, so they should be updated rather than replaced (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java:37`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java:52`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:260`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:609`).

## What We're NOT Doing

- Implementing the actual linter retry loop, hint injection, or bounded retry policy from ENG-018
- Changing YAML linter schema or startup validation that landed in ENG-016
- Refactoring `MissionExecutionEngine` or adding a parallel orchestration path
- Introducing provider-specific retry logic outside Spring AI advisor hooks
- Adding broad telemetry or execution journal schema changes beyond what future advisor work may need

## Implementation Approach

Make the intentional contract break now: pass `YamlSkillDefinition` through the chat-client factory boundary so client construction has access to both execution settings and skill metadata. Add a new `SkillAdvisorResolver` abstraction that converts a skill definition into zero or more Spring AI `Advisor` instances. Keep `SkillChatOptionsAdapter` unchanged in responsibility, still deriving `ChatOptions` from `definition.executionConfiguration()`. In `SpringAiSkillChatClientFactory`, resolve the provider adapter, create options, resolve advisors, and apply both to the cloned builder before building the final `ChatClient`. Auto-configuration should contribute a default no-op resolver bean and inject it into the factory so downstream runtime code never branches on advisor support.

## Phase 1: Break The Factory Contract At The Runtime Boundary

### Overview

Align the runtime contract with the context already available in `ExecutionCoordinator` by making `YamlSkillDefinition` the factory input everywhere. This phase should leave behavior unchanged while preparing the system for advisor-aware client construction.

### Changes Required:

#### 1. Chat client factory contract
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SkillChatClientFactory.java`
**Changes**: Change the single `create(...)` method to accept `YamlSkillDefinition` instead of `EffectiveSkillExecutionConfiguration`, and update imports/documentation to reflect the broader runtime contract.

```java
public interface SkillChatClientFactory {

    ChatClient create(YamlSkillDefinition definition);
}
```

#### 2. Execution coordinator delegation
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
**Changes**: Pass the full definition into the factory and keep the rest of mission execution unchanged. This preserves the current orchestration boundary while unlocking skill-scoped metadata access in the factory.

```java
YamlSkillDefinition definition = requireYamlSkill(skillName);
ChatClient chatClient = skillChatClientFactory.create(definition);
```

#### 3. Test doubles and assertions
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
**Changes**: Update the recording and multi-client factory test doubles to store `YamlSkillDefinition` instances instead of only execution configs, and tighten assertions so tests prove the coordinator forwards the full definition while nested YAML skill routing still works.

```java
private YamlSkillDefinition lastDefinition;

@Override
public ChatClient create(YamlSkillDefinition definition) {
    this.lastDefinition = definition;
    return chatClient;
}
```

### Success Criteria:

#### Automated Verification:
- [x] Starter module compiles after the contract break: `.\mvnw.cmd -pl bifrost-spring-boot-starter -DskipTests compile`
- [x] Coordinator tests pass with the new definition-based contract: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorTest test`
- [x] No stale references to the old factory signature remain: `Select-String -Path 'bifrost-spring-boot-starter\\src\\**\\*.java' -Pattern 'create\\(EffectiveSkillExecutionConfiguration'`

#### Manual Verification:
- [ ] A quick code review confirms runtime orchestration still depends only on the factory abstraction, not advisor internals
- [ ] The contract change clearly exposes `definition.executionConfiguration()` and `definition.linter()` to later phases without adding temporary wrappers

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the contract break and coordinator surface look right before proceeding to the next phase.

---

## Phase 2: Add Advisor Resolution And Compose It In The Spring AI Factory

### Overview

Introduce the dedicated abstraction that converts a YAML skill definition into advisor instances, then compose provider options and advisors together in the Spring AI-backed client factory.

### Changes Required:

#### 1. Advisor resolver abstraction
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SkillAdvisorResolver.java`
**Changes**: Add a small interface that accepts `YamlSkillDefinition` and returns a list of Spring AI advisors. Keep the interface neutral enough for future linter and non-linter skill behaviors.

```java
public interface SkillAdvisorResolver {

    List<Advisor> resolve(YamlSkillDefinition definition);
}
```

#### 2. Default no-op resolver
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/NoOpSkillAdvisorResolver.java`
**Changes**: Add the default implementation that always returns `List.of()`. This ensures all skills flow through the same path even when no linter config is present.

```java
public final class NoOpSkillAdvisorResolver implements SkillAdvisorResolver {

    @Override
    public List<Advisor> resolve(YamlSkillDefinition definition) {
        return List.of();
    }
}
```

#### 3. Spring AI chat-client factory composition
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java`
**Changes**: Inject the resolver, derive `EffectiveSkillExecutionConfiguration` from `definition.executionConfiguration()`, keep provider option logic unchanged, and add advisor composition to the cloned builder. Prefer applying resolved advisors through the builder's default advisor hook so every request made by the returned client inherits the advisors naturally.

```java
public ChatClient create(YamlSkillDefinition definition) {
    EffectiveSkillExecutionConfiguration executionConfiguration = definition.executionConfiguration();
    SkillChatOptionsAdapter adapter = adaptersByProvider.get(executionConfiguration.provider());
    ChatOptions options = adapter.createOptions(executionConfiguration);
    List<Advisor> advisors = skillAdvisorResolver.resolve(definition);

    ChatClient.Builder builder = chatClientBuilder.clone();
    builder.defaultOptions(options);
    if (!advisors.isEmpty()) {
        builder.defaultAdvisors(advisorSpec -> advisorSpec.advisors(advisors));
    }
    return builder.build();
}
```

#### 4. Provider option adapter boundary
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SkillChatOptionsAdapter.java`
**Changes**: Keep the interface focused on provider option creation from `EffectiveSkillExecutionConfiguration`. No responsibility expansion is needed here beyond updating any usage sites to pass `definition.executionConfiguration()`.

### Success Criteria:

#### Automated Verification:
- [x] Factory tests prove provider options still map correctly after advisor support: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=SpringAiSkillChatClientFactoryTests test`
- [x] New resolver-focused tests prove the default path returns no advisors when no linter config exists: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=SpringAiSkillChatClientFactoryTests,SkillAdvisorResolverTests test`
- [x] Starter module still compiles with Spring AI advisor types wired in: `.\mvnw.cmd -pl bifrost-spring-boot-starter test`

#### Manual Verification:
- [ ] Code inspection confirms `ExecutionCoordinator` still knows nothing about advisor internals
- [ ] Code inspection confirms provider-specific model/thinking behavior remains localized to `SkillChatOptionsAdapter` implementations
- [ ] The resolver abstraction is generic enough to host ENG-018's linter advisor without another contract change

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the advisor boundary and Spring AI composition look correct before proceeding to the next phase.

---

## Phase 3: Auto-Configuration And Regression Coverage

### Overview

Finish the plumbing by registering the new resolver in auto-configuration and expanding regression coverage so non-linter and advisor-enabled paths are both protected.

### Changes Required:

#### 1. Auto-configuration updates
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
**Changes**: Add a default `SkillAdvisorResolver` bean, inject it into `skillChatClientFactory(...)`, and keep all existing provider adapter beans unchanged. This preserves override points for applications that want custom advisor resolution.

```java
@Bean
@ConditionalOnMissingBean
public SkillAdvisorResolver skillAdvisorResolver() {
    return new NoOpSkillAdvisorResolver();
}

@Bean
@ConditionalOnBean(ChatClient.Builder.class)
@ConditionalOnMissingBean
public SkillChatClientFactory skillChatClientFactory(
        ChatClient.Builder chatClientBuilder,
        List<SkillChatOptionsAdapter> adapters,
        SkillAdvisorResolver skillAdvisorResolver) {
    return new SpringAiSkillChatClientFactory(chatClientBuilder, adapters, skillAdvisorResolver);
}
```

#### 2. Factory regression tests
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java`
**Changes**: Expand the builder mock assertions to verify advisor attachment when the resolver returns advisors, and verify no advisor configuration occurs when the resolver returns an empty list. Preserve existing provider option assertions.

```java
verify(cloneBuilder).defaultOptions(any(ChatOptions.class));
verify(cloneBuilder).defaultAdvisors(any());
verify(cloneBuilder).build();
```

#### 3. Auto-config bean tests
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
**Changes**: Add coverage showing that the default context exposes a `SkillAdvisorResolver` bean and that custom applications can override it without breaking `SkillChatClientFactory` creation.

#### 4. Optional focused resolver tests
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SkillAdvisorResolverTests.java`
**Changes**: Add a small unit test class if the no-op resolver behavior is clearer in its own file than folded into factory tests.

### Success Criteria:

#### Automated Verification:
- [x] Auto-configuration tests pass with the new resolver bean: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostAutoConfigurationTests test`
- [x] Factory tests cover advisor and non-advisor construction paths: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=SpringAiSkillChatClientFactoryTests test`
- [x] Full starter test suite passes after the wiring changes: `.\mvnw.cmd -pl bifrost-spring-boot-starter test`

#### Manual Verification:
- [ ] Sample application startup or a local Spring context smoke check shows no bean ambiguity after adding the resolver
- [ ] A reviewer can identify a single override point for future skill-scoped advisor behavior in auto-configured applications

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the regression coverage and bean wiring are sufficient before considering the ticket complete.

## Testing Strategy

### Unit Tests:
- Update `ExecutionCoordinatorTest` so the factory doubles capture `YamlSkillDefinition` and assertions verify the full definition is forwarded.
- Extend `SpringAiSkillChatClientFactoryTests` to assert both `defaultOptions(...)` and advisor attachment behavior on the cloned builder.
- Add coverage for the no-op advisor resolver returning an empty list for definitions with and without linter config.
- Add an auto-config test that ensures default bean creation and custom resolver override both work.

### Integration Tests:
- Use the existing Spring auto-configuration test context to verify `SkillChatClientFactory` still materializes when `ChatClient.Builder` is present and now also sees a resolver bean.
- Defer true linter retry-loop integration coverage to ENG-018, but make sure this ticket leaves a stable composition seam for it.

 **Note**: Prefer a dedicated testing plan artifact created via `/testing_plan` for full details. The high-value failing-test-first targets here are `ExecutionCoordinatorTest`, `SpringAiSkillChatClientFactoryTests`, and `BifrostAutoConfigurationTests`.

### Manual Testing Steps:
1. Review the updated factory contract and confirm only the chat-client factory boundary changed from execution code's perspective.
2. Start a minimal Spring context or sample app and confirm `SkillChatClientFactory` still auto-configures with the default resolver bean.
3. Temporarily plug in a stub `SkillAdvisorResolver` that returns a test advisor and confirm the Spring AI factory attaches it without disturbing provider options.
4. Run a non-linter YAML skill path and confirm behavior is unchanged when the resolver returns `List.of()`.

## Performance Considerations

Advisor resolution should be lightweight and per-client creation, not per token or per tool call. Returning `List.of()` for most skills keeps the no-advisor path cheap. This ticket should avoid repeated parsing or validation because YAML linter configuration is already normalized at catalog-load time.

## Migration Notes

This is an internal contract break within the starter: any in-repo or downstream custom `SkillChatClientFactory` implementations will need to switch from `create(EffectiveSkillExecutionConfiguration)` to `create(YamlSkillDefinition)`. The change is intentional and should be called out in the PR description because it affects test doubles and any application overrides of the factory.

## References

- Original ticket: `ai/thoughts/tickets/eng-017-spring-ai-linter-call-advisor-plumbing.md`
- Related research: `ai/thoughts/research/2026-03-21-ENG-017-spring-ai-linter-call-advisor-plumbing.md`
- Phase context: `ai/thoughts/phases/phase4.md`
- Follow-on ticket: `ai/thoughts/tickets/eng-018-linter-call-advisor-bounded-retries-and-observability.md`
- Similar implementation boundary: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:37`
