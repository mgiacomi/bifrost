---
date: 2026-03-15T22:25:22-07:00
researcher: Codex
git_commit: ca47394c1c4772d60f28c6216455099489e30082
branch: main
repository: bifrost
topic: "ENG-007 ExecutionCoordinator and internal MCP-like routing"
tags: [research, codebase, execution-coordinator, session, yaml-skills, spring-ai]
status: complete
last_updated: 2026-03-15
last_updated_by: Codex
---

# Research: ENG-007 ExecutionCoordinator and internal MCP-like routing

**Date**: 2026-03-15T22:25:22-07:00
**Researcher**: Codex
**Git Commit**: ca47394c1c4772d60f28c6216455099489e30082
**Branch**: main
**Repository**: bifrost

## Research Question
Use the `ai/commands/1_research_codebase.md` workflow to document the current codebase state for [`ai/thoughts/tickets/eng-007-execution-coordinator.md`](C:\opendev\code\bifrost\ai\thoughts\tickets\eng-007-execution-coordinator.md).

## Summary
The codebase contains most of the supporting primitives that ENG-007 references, but it does not currently contain an `ExecutionCoordinator` implementation in either `bifrost-spring-boot-starter` or `bifrost-sample`. The existing runtime wiring provides:

- Spring Boot auto-configuration for the registry, session runner, YAML catalog/registrar, exception transformer, and provider-aware `SkillChatClientFactory` ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:25`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\autoconfigure\BifrostAutoConfiguration.java#L25)).
- A `BifrostSession` model with a bounded execution-frame stack and JSON-serializable `ExecutionJournal` ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:15`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\BifrostSession.java#L15), [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionJournal.java:14`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\ExecutionJournal.java#L14)).
- Capability discovery for Java `@SkillMethod` implementations, registered with Spring AI tool metadata and invoked through `MethodToolCallback` ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:20`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\SkillMethodBeanPostProcessor.java#L20)).
- YAML skill loading that validates model/thinking settings at boot and stores the resolved execution settings in `EffectiveSkillExecutionConfiguration` and `SkillExecutionDescriptor` ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:23`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\YamlSkillCatalog.java#L23), [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillExecutionDescriptor.java:9`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\SkillExecutionDescriptor.java#L9)).
- A provider-aware chat-client factory that can build a `ChatClient` from a validated effective execution configuration, but no class currently consumes that factory to run a `callSkill` loop ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:18`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\chat\SpringAiSkillChatClientFactory.java#L18)).

Within current runtime behavior, YAML skills with `mapping.target_id` delegate to an already-registered Java capability, while YAML skills without a target id still resolve to an invoker that throws `UnsupportedOperationException` for LLM-backed execution ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:39`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\YamlSkillCapabilityRegistrar.java#L39)).

## Detailed Findings

### Auto-configuration and current runtime wiring
`BifrostAutoConfiguration` is the central infrastructure entry point. It enables configuration properties for sessions, model catalog entries, and skill locations, and registers beans for the in-memory capability registry, exception transformer, session runner, YAML skill catalog, YAML skill registrar, four provider adapters, and the `SkillChatClientFactory` when `ChatClient.Builder` is present ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:25`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\autoconfigure\BifrostAutoConfiguration.java#L25)).

No `ExecutionCoordinator` source file is present under `bifrost-spring-boot-starter` or `bifrost-sample` based on a recursive file search for `*ExecutionCoordinator*` performed during this research.

`BifrostSessionRunner` is the current session entry point. It creates a new `BifrostSession` with the configured `maxDepth` and binds it to the thread through `BifrostSessionHolder` for either `Consumer` or `Function` execution ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSessionRunner.java:7`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\BifrostSessionRunner.java#L7)).

### Session and journal model
`BifrostSession` stores:

- `sessionId`
- `maxDepth`
- a `Deque<ExecutionFrame>` guarded by `ReentrantLock`
- an `ExecutionJournal`

It exposes `pushFrame`, `popFrame`, and `peekFrame`, enforcing stack depth in `pushFrame` via `BifrostStackOverflowException` when the frame count reaches `maxDepth` ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:69`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\BifrostSession.java#L69)). It also exposes `logThought`, `logToolExecution`, and `logError`, each of which appends a journal entry with a typed level and entry type ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:57`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\BifrostSession.java#L57)).

`ExecutionJournal` stores a mutable list of `JournalEntry` objects and converts arbitrary payloads to `JsonNode` using a Jackson `ObjectMapper` configured with discovered modules ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionJournal.java:16`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\ExecutionJournal.java#L16)). `ExecutionFrame` is a record containing `frameId`, `parentFrameId`, `operationType`, `route`, `parameters`, and `openedAt` ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionFrame.java:7`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\ExecutionFrame.java#L7)).

The current session model does not define a dedicated field or API for planning-mode task lists; only generic journal entry APIs and frame-stack APIs are present in `BifrostSession`.

### Capability discovery and deterministic invocation path
`SkillMethodBeanPostProcessor` scans initialized beans for methods annotated with `@SkillMethod`, builds a Spring AI `ToolDefinition` and `MethodToolCallback` for each method, and registers a `CapabilityMetadata` entry in the `CapabilityRegistry` ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:47`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\SkillMethodBeanPostProcessor.java#L47)).

The registered `CapabilityMetadata` record contains:

- capability `id`, `name`, and `description`
- Java-side `modelPreference`
- `SkillExecutionDescriptor`
- `rbacRoles`
- `CapabilityInvoker`

([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:6`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\CapabilityMetadata.java#L6))

Deterministic invocation serializes the argument map to JSON and passes it to the `MethodToolCallback`. If the invoked method throws, the exception is transformed by `BifrostExceptionTransformer` and returned as the tool result ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:85`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\SkillMethodBeanPostProcessor.java#L85)).

### YAML skill model and execution metadata
`YamlSkillCatalog` discovers skill resources from `bifrost.skills.locations`, parses YAML manifests, validates the required `name`, `description`, and `model` fields, and resolves the manifest's model name against the configured `bifrost.models` catalog ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:70`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\YamlSkillCatalog.java#L70), [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:92`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\YamlSkillCatalog.java#L92)).

If the YAML manifest omits `thinking_level` and the selected model supports thinking, the effective configuration defaults to `medium`; otherwise it stays `null` ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:103`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\YamlSkillCatalog.java#L103)). The resolved values are stored in `EffectiveSkillExecutionConfiguration` as `frameworkModel`, `provider`, `providerModel`, and `thinkingLevel` ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/EffectiveSkillExecutionConfiguration.java:6`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\EffectiveSkillExecutionConfiguration.java#L6)).

`YamlSkillCapabilityRegistrar` translates each loaded YAML skill into registry-facing `CapabilityMetadata`. It copies the resolved execution settings into a `SkillExecutionDescriptor` and determines how invocation works:

- If `mapping.target_id` is present, it resolves the target capability by matching the target id to an already-discovered capability's `id`, then reuses that target's `invoker` ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:39`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\YamlSkillCapabilityRegistrar.java#L39)).
- If `mapping.target_id` is absent, the invoker throws `UnsupportedOperationException("LLM-backed YAML execution is not implemented yet ...")` ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:50`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\YamlSkillCapabilityRegistrar.java#L50)).

### Provider-aware chat client factory
The configured model catalog lives in `BifrostModelsProperties`, keyed by framework model name. Each catalog entry contains a provider enum, a provider-specific model string, and a set of supported thinking levels ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostModelsProperties.java:14`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\autoconfigure\BifrostModelsProperties.java#L14)). The test fixture model catalog includes `gpt-5`, `claude-sonnet`, `gemini-pro`, and `ollama-llama3` ([`bifrost-spring-boot-starter/src/test/resources/application-test.yml:1`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\resources\application-test.yml#L1)).

`SpringAiSkillChatClientFactory` accepts an `EffectiveSkillExecutionConfiguration`, looks up the provider-specific adapter, builds provider-specific `ChatOptions`, clones the shared `ChatClient.Builder`, applies those options, and builds a `ChatClient` ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:36`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\chat\SpringAiSkillChatClientFactory.java#L36)).

Provider-specific option mapping is implemented as follows:

- OpenAI: `providerModel` plus `reasoningEffort` when `thinkingLevel` is present ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:64`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\chat\SpringAiSkillChatClientFactory.java#L64)).
- Anthropic: `providerModel` plus enabled thinking with a token budget derived from `thinkingLevel` ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:82`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\chat\SpringAiSkillChatClientFactory.java#L82)).
- Gemini: `providerModel` plus `includeThoughts(true)` and a thinking budget ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:100`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\chat\SpringAiSkillChatClientFactory.java#L100)).
- Ollama: `providerModel` only ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:119`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\chat\SpringAiSkillChatClientFactory.java#L119)).

No class in the current source tree invokes this factory as part of a session-aware execution loop.

### Test coverage describing current behavior
`BifrostAutoConfigurationTests` verifies that the starter auto-configures `BifrostSessionRunner`, `CapabilityRegistry`, `BifrostModelsProperties`, and `YamlSkillCatalog`, and that YAML skills are registered with resolved execution metadata ([`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java:60`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\autoconfigure\BifrostAutoConfigurationTests.java#L60), [`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java:95`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\autoconfigure\BifrostAutoConfigurationTests.java#L95)).

`YamlSkillCatalogTests` verifies:

- defaulting `thinking_level` to `medium` for thinking-capable models
- omitting `thinking_level` for non-thinking models
- failing startup for unknown models or unsupported thinking levels
- mapping a YAML skill to a discovered `@SkillMethod` target
- returning transformed errors from that deterministic mapped path

([`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:40`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\skill\YamlSkillCatalogTests.java#L40), [`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:106`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\skill\YamlSkillCatalogTests.java#L106), [`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:123`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\skill\YamlSkillCatalogTests.java#L123)).

There is no end-to-end integration test in the current codebase that exercises a session, a planning step, a `ChatClient.call()` loop, `ref://` resolution, or unwinding around an `ExecutionCoordinator`.

## Code References
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:25` - infrastructure bean registration for registry, session runner, YAML catalog/registrar, provider adapters, and chat client factory.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:57` - session journal APIs for thought/tool/error logging.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:69` - bounded frame stack enforcement through `maxDepth`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionJournal.java:31` - payload-to-JSON journal append logic.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:54` - capability registration for `@SkillMethod`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:85` - deterministic invocation and exception transformation boundary.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:92` - YAML manifest validation and effective execution configuration construction.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:39` - `mapping.target_id` delegation to discovered Java capability.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:50` - current unimplemented LLM-backed YAML invoker.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:36` - provider-aware `ChatClient` creation from validated execution metadata.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java:95` - test asserting YAML capability metadata carries resolved execution configuration.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:123` - test covering transformed errors from deterministic YAML-to-`@SkillMethod` invocation.

## Architecture Documentation
Current implementation separates the runtime pieces this way:

- Java implementation targets are discovered from `@SkillMethod` and registered in the `CapabilityRegistry`.
- YAML manifests are the registry-facing contract for declarative skills and carry provider-aware execution settings.
- `mapping.target_id` lets a YAML skill wrap and delegate to a discovered Java capability.
- Provider-specific model execution settings are resolved at boot into `EffectiveSkillExecutionConfiguration`, then copied into `SkillExecutionDescriptor` and are available for later runtime use.
- The chat-client factory exists independently from the registry/session loop and builds per-skill `ChatClient` instances from that effective configuration.
- The current runtime path that executes end to end is the deterministic path through `CapabilityInvoker`; the LLM-backed YAML branch is represented in metadata and factory support, but not by a coordinator loop in source.

A repository-wide text search across `bifrost-spring-boot-starter` and `bifrost-sample` did not return source matches for `planning_mode`, `Flight Plan`, `Task List`, `ref://`, or `ResourceLoader`, which indicates those concepts are described in the project thoughts/spec documents rather than implemented in the current source tree.

## Historical Context (from ai/thoughts/)
The ticket for ENG-007 defines the intended component as the central service tying together the session, registry, transformers, validated YAML metadata, `SkillChatClientFactory`, and `ChatClient` models, plus planning mode and `ref://` resolution ([`ai/thoughts/tickets/eng-007-execution-coordinator.md:5`](C:\opendev\code\bifrost\ai\thoughts\tickets\eng-007-execution-coordinator.md#L5)).

Phase 2 documents the current state more explicitly: deterministic YAML-to-`@SkillMethod` routing is working, while the YAML-backed `callSkill` branch remains planned; it also names the absence of `ExecutionCoordinator` and places the current exception boundary in `SkillMethodBeanPostProcessor.invokeToolCallback(...)` ([`ai/thoughts/phases/phase2.md:9`](C:\opendev\code\bifrost\ai\thoughts\phases\phase2.md#L9), [`ai/thoughts/phases/phase2.md:23`](C:\opendev\code\bifrost\ai\thoughts\phases\phase2.md#L23), [`ai/thoughts/phases/phase2.md:27`](C:\opendev\code\bifrost\ai\thoughts\phases\phase2.md#L27)).

The master spec in `ai/thoughts/phases/README.md` describes the intended future execution lifecycle, including `callSkill`, `readData` and `writeData`, per-skill `planning_mode`, and the note that a dedicated `ExecutionCoordinator` does not yet exist in code ([`ai/thoughts/phases/README.md:24`](C:\opendev\code\bifrost\ai\thoughts\phases\README.md#L24), [`ai/thoughts/phases/README.md:42`](C:\opendev\code\bifrost\ai\thoughts\phases\README.md#L42), [`ai/thoughts/phases/README.md:56`](C:\opendev\code\bifrost\ai\thoughts\phases\README.md#L56)).

## Related Research
- No prior documents were present under `ai/thoughts/research/` at the time of this research.

## Open Questions
- The current source tree does not show where ENG-007's planning-mode task list would be stored beyond the generic `ExecutionJournal`.
- The current source tree does not show a source-level contract for `ref://` interception or `ResourceLoader`-based payload resolution.
- The current source tree does not show which component will translate `CapabilityRegistry` entries into Spring AI tool functions for the `ChatClient` loop.
