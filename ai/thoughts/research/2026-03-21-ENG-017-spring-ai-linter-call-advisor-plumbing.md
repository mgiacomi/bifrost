---
date: 2026-03-21T02:01:04.1177080-07:00
researcher: Unknown
git_commit: 1a62a869c0eaa46d6b92c088de548960ba327843
branch: main
repository: bifrost
topic: "ENG-017 Spring AI linter call advisor plumbing"
tags: [research, codebase, spring-ai, chat-client, yaml-skills, linter]
status: complete
last_updated: 2026-03-21
last_updated_by: Unknown
last_updated_note: "Added follow-up research for implementation recommendations and contract shape"
---

# Research: ENG-017 Spring AI linter call advisor plumbing

**Date**: 2026-03-21T02:01:04.1177080-07:00
**Researcher**: Unknown
**Git Commit**: 1a62a869c0eaa46d6b92c088de548960ba327843
**Branch**: main
**Repository**: bifrost

## Research Question
Use command file `ai/commands/1_research_codebase.md` to perform research for `ai/thoughts/tickets/eng-017-spring-ai-linter-call-advisor-plumbing.md`.

## Summary
The current YAML-skill execution path already centralizes chat-client creation in `SkillChatClientFactory`, and `ExecutionCoordinator` only asks that factory for a `ChatClient` before invoking mission execution. Today, the Spring AI-backed implementation composes provider-specific `ChatOptions` only, using one `SkillChatOptionsAdapter` per provider, and then builds a cloned `ChatClient.Builder` with those default options. No advisor abstraction, advisor bean registration, or `Advisor`/`CallAdvisor` usage exists in `bifrost-spring-boot-starter/src/main/java` at the time of this research.

The linter configuration expected to feed future advisor wiring already exists in the YAML manifest model and is validated during catalog loading. `YamlSkillManifest` exposes a typed `linter` block, `YamlSkillCatalog` validates `type`, `max_retries`, and regex settings, and `YamlSkillDefinition` carries the resolved linter configuration alongside the effective execution configuration used by runtime code.

## Detailed Findings

### Chat-client construction boundary
- `SkillChatClientFactory` is the single runtime abstraction for creating a `ChatClient` from an `EffectiveSkillExecutionConfiguration` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SkillChatClientFactory.java:6`).
- `SpringAiSkillChatClientFactory` stores a root `ChatClient.Builder` plus provider-keyed `SkillChatOptionsAdapter` instances (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:24`).
- Its `create(...)` method resolves the provider adapter from `executionConfiguration.provider()`, builds provider-specific `ChatOptions`, clones the shared builder, applies `defaultOptions(options)`, and returns `builder.build()` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:36`).
- `SkillChatOptionsAdapter` is focused only on turning an effective execution configuration into `ChatOptions`; it does not model advisors or broader client decoration (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SkillChatOptionsAdapter.java:7`).

### Provider-specific option wiring
- OpenAI options map `providerModel` and optional `thinkingLevel` into `OpenAiChatOptions.reasoningEffort(...)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:57`).
- Anthropic options map `providerModel` and optional thinking into `AnthropicApi.ThinkingType.ENABLED` plus a token budget derived from `low|medium|high` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:75`).
- Gemini options map `providerModel` and optional thinking into `includeThoughts(true)` with the same budget mapping (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:93`).
- Ollama options set only the provider model and ignore thinking (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:112`).
- The current implementation’s only factory composition step after adapter selection is `builder.defaultOptions(options)`; there is no adjacent hook for zero-or-more runtime advisors in the class today (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:43`).

### YAML skill definitions already carry linter data
- `YamlSkillManifest` includes a top-level `linter` property plus nested `LinterManifest` and `RegexManifest` types (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:29`).
- `LinterManifest` normalizes `type` to lowercase, stores `max_retries`, and nests `regex` configuration (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:105`).
- `YamlSkillDefinition` exposes the manifest-backed linter through `linter()` alongside `executionConfiguration()` and other typed manifest accessors (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:23`).
- `YamlSkillCatalog.loadDefinition(...)` validates required root fields, validates the linter block, resolves the model catalog entry, resolves the effective thinking level, and returns a `YamlSkillDefinition` with an `EffectiveSkillExecutionConfiguration` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:103`).
- Linter validation currently allows only `type: regex`, requires `max_retries`, bounds retries to `0..3`, requires a regex block, and compiles the regex during startup (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:164`).

### Runtime execution flow
- `ExecutionCoordinator.execute(...)` loads the `YamlSkillDefinition` from `YamlSkillCatalog`, resolves the root capability, performs access checks, clears the current plan, and opens a mission frame before model execution (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:52`).
- The coordinator then creates the model client by calling `skillChatClientFactory.create(definition.executionConfiguration())` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:64`).
- After the client is created, the coordinator computes visible tools and delegates the rest of the run to `MissionExecutionEngine.executeMission(...)` with the created `ChatClient` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:65`).
- Because only `executionConfiguration()` is passed to the factory today, the current execution path does not hand the full `YamlSkillDefinition`, `linter()` settings, or a separate advisor-selection object into chat-client construction.

### Auto-configuration and bean wiring
- `BifrostAutoConfiguration` publishes four default `SkillChatOptionsAdapter` beans by indexing into `SpringAiSkillChatClientFactory.defaultAdapters()` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:198`).
- It auto-configures `SkillChatClientFactory` only when a `ChatClient.Builder` bean is present, using the root builder plus the adapter list (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:226`).
- `ExecutionCoordinator` is only auto-configured when a `SkillChatClientFactory` bean exists, reinforcing the factory as the runtime boundary between YAML skill execution and Spring AI client creation (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:235`).

### Current test coverage
- `SpringAiSkillChatClientFactoryTests` verify that the factory clones the builder, applies `defaultOptions(...)`, and produces provider-specific option objects for OpenAI, Anthropic, Gemini, and Ollama (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java:23`).
- Those tests assert model and thinking-option mapping only; they do not assert any advisor attachment behavior (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java:92`).
- `ExecutionCoordinatorTest` verifies that the coordinator passes the validated `EffectiveSkillExecutionConfiguration` into `SkillChatClientFactory` before mission execution (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:187`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:260`).
- The recording test factory used by those coordinator tests stores only the last `EffectiveSkillExecutionConfiguration` received, mirroring the current factory contract (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:1028`).
- `YamlSkillCatalogTests` verify that a valid regex linter manifest loads, normalizes the type to `regex`, preserves `max_retries`, trims the regex pattern, and leaves `linter()` null for manifests that do not declare one (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:184`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:201`).
- `BifrostAutoConfigurationTests` verify that `ExecutionCoordinator` appears when a `SkillChatClientFactory` is available and that YAML capability metadata contains the resolved provider-aware execution descriptor (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java:87`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java:114`).

## Code References
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SkillChatClientFactory.java:6` - Runtime factory contract for YAML skill chat-client creation.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SkillChatOptionsAdapter.java:7` - Provider adapter contract used by the Spring AI chat-client factory.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:27` - Constructor wiring for root builder plus adapters.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:36` - Current `create(...)` flow that resolves options and builds the client.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:57` - OpenAI option adapter.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:75` - Anthropic option adapter.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:93` - Gemini option adapter.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:112` - Ollama option adapter.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:52` - YAML skill execution entry point.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:64` - Factory invocation that currently passes only `executionConfiguration()`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:29` - Manifest field for `linter`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:105` - Typed linter manifest structure.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:23` - Accessor exposing the loaded linter configuration.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:103` - Definition loading and effective execution configuration assembly.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:164` - Linter validation rules enforced during startup.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:198` - Default provider adapter beans.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:226` - Auto-configured Spring AI-backed `SkillChatClientFactory`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:235` - `ExecutionCoordinator` bean creation conditioned on the factory.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java:23` - Factory test coverage for provider option attachment.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:187` - Coordinator test showing execution configuration is sourced from the YAML skill definition.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java:1028` - Recording factory test double matching the current factory contract.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:184` - Valid regex linter manifest loading coverage.
- `bifrost-spring-boot-starter/src/test/resources/skills/valid/regex-linter-skill.yaml:1` - Example manifest containing a typed regex linter.

## Architecture Documentation
The current architecture separates YAML skill loading from runtime client creation. `YamlSkillCatalog` parses and validates manifests into `YamlSkillDefinition` records, including effective provider execution settings and optional linter metadata. `ExecutionCoordinator` reads that definition and converts it into a `ChatClient` through the `SkillChatClientFactory` boundary just before mission execution. The Spring AI implementation of that boundary is provider-agnostic at the top level and delegates provider-specific option creation to `SkillChatOptionsAdapter` implementations.

Within this arrangement, provider model selection and thinking settings are already centralized in the chat-client factory path. Linter state exists in the loaded skill definition, but the current factory contract does not receive or resolve linter-specific runtime components, and there is no dedicated advisor-resolution abstraction in main code yet.

## Historical Context (from ai/thoughts/)
- `ai/thoughts/phases/phase4.md` describes Phase 4 as the stage where linter behavior is expected to move into Spring AI `CallAdvisor` hooks, including bounded retries and observability.
- `ai/thoughts/tickets/eng-016-yaml-linter-schema-and-startup-validation.md` positions typed manifest loading and startup validation as the prerequisite for later runtime advisor work.
- `ai/thoughts/tickets/eng-017-spring-ai-linter-call-advisor-plumbing.md` defines this ticket as the wiring layer that should make advisor composition part of chat-client construction.
- `ai/thoughts/tickets/eng-018-linter-call-advisor-bounded-retries-and-observability.md` assumes advisor wiring exists and scopes the actual self-correction loop and observability on top of it.

## Related Research
- No prior documents were present in `ai/thoughts/research/` during this research run.

## Open Questions
- No main-source implementation currently expresses how advisor selection should be modeled or injected; phase and ticket notes describe that boundary, but the live codebase does not yet contain a corresponding abstraction.

## Follow-up Research 2026-03-21T02:01:04.1177080-07:00

The recommendation from this research is to make a clean contract break in ENG-017 rather than preserving the current narrow factory input.

### Recommended contract shape
- Change `SkillChatClientFactory` so `create(...)` accepts `YamlSkillDefinition` directly instead of only `EffectiveSkillExecutionConfiguration`.
- This matches the current runtime flow, because `ExecutionCoordinator` already loads the full `YamlSkillDefinition` before invoking the factory (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:55`).
- It also aligns the chat-client factory boundary with the data already carried by `YamlSkillDefinition`, including both `executionConfiguration()` and `linter()` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:10`).

### Recommended advisor plumbing
- Introduce a dedicated resolver abstraction that accepts `YamlSkillDefinition` and returns zero or more Spring AI advisors for that skill.
- Keep provider-specific `ChatOptions` assembly behind `SkillChatOptionsAdapter`, but source those options from `definition.executionConfiguration()`.
- Keep the composition point in `SpringAiSkillChatClientFactory`, so provider options and resolved advisors are attached together before `build()` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:36`).
- Provide a default no-op advisor resolver bean so non-linter skills continue through the same chat-client construction path without special-case branching.

### Why this recommendation fits the current codebase
- The present factory contract is narrower than the runtime context: `ExecutionCoordinator` has the full `YamlSkillDefinition`, but the factory currently receives only `definition.executionConfiguration()` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:64`).
- Linter metadata already exists on the loaded definition and does not need additional schema work to support skill-scoped resolver logic (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:29`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:164`).
- Because there are no existing customers and breaking changes are acceptable for this effort, the direct `YamlSkillDefinition` contract is the simpler path compared with introducing a temporary wrapper or compatibility layer.
