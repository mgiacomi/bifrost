---
date: 2026-03-15T20:50:26.0129144-07:00
researcher: Codex
git_commit: 891d7e3d9a6e5dccfbdff9bc9cca9bc9d77c26ea
branch: main
repository: bifrost
topic: "Research the current exception-handling path for @SkillMethod execution described by eng-006-exception-transformer"
tags: [research, codebase, exception-handling, skillmethod, capability-registry, spring-ai]
status: complete
last_updated: 2026-03-15
last_updated_by: Codex
last_updated_note: "Added follow-up research for handling the open questions"
---

# Research: Research the current exception-handling path for @SkillMethod execution described by eng-006-exception-transformer

**Date**: 2026-03-15T20:50:26.0129144-07:00
**Researcher**: Codex
**Git Commit**: 891d7e3d9a6e5dccfbdff9bc9cca9bc9d77c26ea
**Branch**: main
**Repository**: bifrost

## Research Question
Use `ai/commands/1_research_codebase.md` to research the current code paths relevant to `ai/thoughts/tickets/eng-006-exception-transformer.md`.

## Summary
The current `@SkillMethod` execution path is registered by `SkillMethodBeanPostProcessor`, which scans Spring beans, builds a Spring AI `MethodToolCallback`, and stores a `CapabilityInvoker` lambda inside `CapabilityMetadata` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:31-66`).

At invocation time, the registry-facing invoker serializes the argument map to JSON and delegates to `MethodToolCallback.call(...)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:69-80`). In the current code, Bifrost catches `JsonProcessingException` and `RuntimeException` at that boundary and rethrows `IllegalStateException`; there is no Bifrost-specific exception transformer interface or AI-readable failure string in the repository today (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:75-79`).

YAML skills that declare `mapping.target_id` reuse the same underlying invoker by looking up the discovered target capability and returning its `invoker()` unchanged, so deterministic YAML-to-method execution currently shares the same exception behavior as direct `@SkillMethod` invocation (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:39-47`).

The ticket's behavior is also reflected in historical planning notes: phase 2 describes a future `BifrostExceptionTransformer` that would intercept `@SkillMethod` exceptions and translate them into an AI-readable string, but that component is not present in the current source tree (`ai/thoughts/phases/phase2.md:22-27`).

## Detailed Findings

### `@SkillMethod` discovery and capability registration
- `BifrostAutoConfiguration` contributes the infrastructure beans that wire this path together: `CapabilityRegistry`, `SkillMethodBeanPostProcessor`, `YamlSkillCatalog`, and `YamlSkillCapabilityRegistrar` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:32-66`).
- `SkillMethodBeanPostProcessor.postProcessAfterInitialization(...)` scans each initialized bean for methods annotated with `@SkillMethod` and calls `registerSkillMethod(...)` for each one (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:31-35`).
- `registerSkillMethod(...)` derives the capability name and description from the annotation, creates a Spring AI `ToolDefinition`, builds a `MethodToolCallback`, and stores a `CapabilityInvoker` lambda in `CapabilityMetadata` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:38-64`).
- The capability id for a discovered Java target is `beanName#methodName`, which is later used by YAML `mapping.target_id` lookups (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:57-58`).

### Current invocation boundary and exception behavior
- `CapabilityInvoker` is a single-method functional interface: `Object invoke(Map<String, Object> arguments)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityInvoker.java:5-8`).
- The concrete invoker for `@SkillMethod` capabilities delegates into `invokeToolCallback(...)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:55`).
- `invokeToolCallback(...)` normalizes null arguments to an empty map, serializes the input map with Jackson, and calls `toolCallback.call(requestPayload)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:69-73`).
- The current catch blocks are local to this method. Serialization failures are wrapped as `IllegalStateException("Failed to serialize capability arguments for ...", ex)`, and all `RuntimeException` values from the callback are wrapped as `IllegalStateException("Failed to invoke capability ...", ex)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:75-79`).
- There is no repository class or interface named `BifrostExceptionTransformer`, and there is no SLF4J logging in `SkillMethodBeanPostProcessor` today.

### Spring AI handoff used by the current implementation
- The repository imports `org.springframework.ai.tool.method.MethodToolCallback` in the invocation path (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:7`).
- The root `pom.xml` pins Spring AI through the BOM at version `1.1.2`, which is the dependency line backing that callback (`pom.xml:49-50`, `pom.xml:67-72`).
- In the local Spring AI 1.0.1 source jar available on disk, `MethodToolCallback.call(...)` parses the JSON tool input, builds method arguments, reflectively invokes the method, and converts the return value to a string. Its internal `callMethod(...)` catches `InvocationTargetException` and throws `ToolExecutionException(this.toolDefinition, ex.getCause())`. This is the reflective invocation layer that Bifrost currently calls through when executing a discovered `@SkillMethod` target. This dependency source was inspected from the local Maven cache at `C:\Users\mgiacomi\.m2\repository\org\springframework\ai\spring-ai-model\1.0.1\spring-ai-model-1.0.1-sources.jar`.

### How YAML deterministic skills connect to the same path
- `YamlSkillCapabilityRegistrar.afterSingletonsInstantiated()` registers each YAML manifest as a `CapabilityMetadata` entry in the same registry (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:24-35`).
- When a YAML skill contains `mapping.target_id`, `resolveInvoker(...)` searches all registered capabilities by `id()` and returns `target.invoker()` directly (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:39-47`).
- Because the YAML capability reuses the discovered target's invoker object, the method-execution and exception path is identical for:
  - direct access to the discovered `@SkillMethod` capability
  - indirect execution through a YAML manifest that points at that capability id
- If a YAML skill does not map to a Java target, the current branch throws `UnsupportedOperationException` because LLM-backed YAML execution is not implemented yet (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:50-53`).

### Capability metadata and registry behavior
- `CapabilityMetadata` stores the id, name, description, model preference, execution descriptor, RBAC roles, and invoker for each capability; it enforces non-blank ids, names, and descriptions, and non-null invokers (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:6-31`).
- `InMemoryCapabilityRegistry` indexes capabilities by name with a `ConcurrentHashMap`, validates that the registration key matches `metadata.name()`, and rejects duplicates with `CapabilityCollisionException` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistry.java:8-47`).
- Because YAML-mapped skills register separate capability names while reusing the same invoker instance, the registry can expose both the underlying Java target and the YAML-facing capability name at the same time (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java:110-120`).

### Current tests around this area
- `SkillMethodBeanPostProcessorTest` verifies that annotated methods are registered as capabilities and that invocation returns Spring AI's JSON-encoded string result (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java:18-53`).
- The same test class also verifies optional-parameter handling through the current invoker path (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java:55-69`).
- `YamlSkillCatalogTests` verifies that a YAML skill mapped to a discovered target executes through `metadata.invoker().invoke(...)` and returns the same JSON-encoded result (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:106-120`).
- `BifrostAutoConfigurationTests` verifies that both the discovered target capability and the YAML capability exist in the registry when a mapping is configured (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java:110-120`).
- No current test in the repository throws an exception from a mocked or fixture `@SkillMethod` and asserts on the returned payload or logging behavior.

## Code References
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:32-66` - Auto-configures the registry, bean post-processor, YAML catalog, and YAML capability registrar.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:31-66` - Scans beans for `@SkillMethod`, creates `MethodToolCallback`, and registers the resulting capability metadata.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:69-80` - Current argument serialization and callback invocation boundary, including the existing exception wrapping.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityInvoker.java:5-8` - Functional invoker contract used by both discovered and YAML-registered capabilities.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:6-31` - Registry metadata record containing the invoker and execution descriptors.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistry.java:12-37` - Name-based registration and lookup for capabilities.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:24-53` - Registers YAML skills and reuses discovered invokers when `mapping.target_id` is present.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java:36-69` - Current invocation tests for direct `@SkillMethod` execution.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:106-120` - Current invocation test for YAML mapping to a discovered target.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java:110-120` - Confirms registry contains both target and mapped YAML capabilities together.
- `ai/thoughts/phases/phase2.md:22-27` - Historical planning note that names `BifrostExceptionTransformer` as part of the intended execution orchestration.
- `ai/thoughts/tickets/eng-006-exception-transformer.md:1-19` - Ticket requirements for the interface, default implementation, execution hook point, and test/logging expectations.

## Architecture Documentation
The current deterministic execution path is registry-centric. Spring Boot auto-configuration creates the registry and the scanners/registrars. Discovered Java methods become capabilities first. YAML skills are then registered separately and may either reuse a discovered capability invoker by `mapping.target_id` or expose a placeholder invoker for the not-yet-implemented LLM-backed path. Within the deterministic method path, the only repository-level execution boundary is `SkillMethodBeanPostProcessor.invokeToolCallback(...)`, which passes serialized arguments into Spring AI's tool-calling callback and currently converts callback failures into `IllegalStateException`.

This means the existing call chain for a mapped deterministic YAML skill is:

1. Spring registers an `@SkillMethod` capability through `SkillMethodBeanPostProcessor`.
2. Spring registers a YAML capability through `YamlSkillCapabilityRegistrar`.
3. The YAML capability resolves `mapping.target_id` to the discovered capability id.
4. The YAML capability stores and reuses that target capability's `CapabilityInvoker`.
5. Invocation reaches `SkillMethodBeanPostProcessor.invokeToolCallback(...)`.
6. Bifrost serializes the argument map and calls `MethodToolCallback.call(...)`.
7. Spring AI reflectively invokes the underlying Java method and converts the result to a string.

## Historical Context (from ai/thoughts/)
- `ai/thoughts/phases/phase2.md:8-9` describes the current state of phase 2 as having the deterministic YAML-to-`@SkillMethod` route working while the YAML-backed `callSkill` branch remains planned.
- `ai/thoughts/phases/phase2.md:22-27` explicitly calls out `BifrostExceptionTransformer` as part of the intended execution orchestration around `@SkillMethod` failures.
- `ai/thoughts/phases/README.md:22-27` documents `callMethod` as deterministic execution through a Spring bean flagged with `@SkillMethod`, with YAML remaining the LLM-facing contract.

## Related Research
- No existing documents were present in `ai/thoughts/research/` at the time of this research.

## Open Questions
- The repository does not yet contain the future `ExecutionCoordinator` referenced in `ai/thoughts/phases/phase2.md`, so the present framework boundary for deterministic method execution is the invoker path inside `SkillMethodBeanPostProcessor`. For this iteration, that boundary is the planned implementation point for `eng-006`.
- The local repository pins Spring AI `1.1.2`, while the inspected `MethodToolCallback` source on disk came from the locally cached `1.0.1` source jar; the Bifrost source itself only depends on the public `MethodToolCallback` API and does not vendor Spring AI source into the repository. For this iteration, the recommended approach is to define behavior at the Bifrost-owned boundary and avoid coupling the feature to Spring AI internal wrapper types.

## Follow-up Research 2026-03-15T20:58:46.8813145-07:00

### Open question 1: no `ExecutionCoordinator` exists yet
- The phase documents describe an eventual coordinator-owned execution flow, but the current source tree does not contain that component. In the code as it exists today, the practical framework boundary is the invoker lambda created in `SkillMethodBeanPostProcessor`, specifically `invokeToolCallback(...)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:69-80`).
- Because `YamlSkillCapabilityRegistrar` reuses `target.invoker()` for deterministic YAML skills (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:39-47`), handling exceptions at this boundary would cover both direct `@SkillMethod` execution and YAML-mapped method execution without requiring a broader orchestration layer first.
- Suggested handling:
  - Treat `SkillMethodBeanPostProcessor.invokeToolCallback(...)` as the implementation point for `eng-006`.
  - Add the transformer and logging there, because it is the narrowest shared boundary already present in the repository.
  - Keep the transformation concern isolated behind a dedicated interface so the logic can later move into an `ExecutionCoordinator` if that component is introduced, without changing the public behavior of `CapabilityInvoker`.
- Tradeoff:
  - Implementing at the current invoker boundary is the least disruptive option and aligns with the code that actually exists.
  - If a coordinator is added later, the code may be relocated, but the interface and behavior can remain stable if the transformation is introduced as a dependency rather than inlined logic.

### Open question 2: Spring AI source version mismatch during research
- The repository declares Spring AI `1.1.2` in the root BOM (`pom.xml:49-50`, `pom.xml:67-72`), but the local source jar I was able to inspect for `MethodToolCallback` was `1.0.1`.
- The Bifrost code path under discussion depends only on the public `MethodToolCallback` class and its `call(String)` method (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:49-53`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:72-73`), so the research conclusion about Bifrost's own boundary still stands. The uncertain part is the exact internal exception type or reflective flow inside the currently resolved Spring AI `1.1.2` binary.
- Suggested handling:
  - For implementation, do not rely on undocumented internal behavior of `MethodToolCallback`.
  - Catch the broad failure shape at Bifrost's boundary, where the code already catches `RuntimeException`, and transform there.
  - If the implementation needs precise assertions about wrapped exception types from Spring AI, verify them against the project's resolved dependency set during test execution rather than assuming the 1.0.1 source matches 1.1.2 exactly.
- Tradeoff:
  - Relying only on Bifrost's own boundary makes the feature less sensitive to Spring AI internals and version changes.
  - Writing tests against Bifrost-observable behavior, such as returned transformed strings and logged exceptions, will be more durable than testing a particular Spring AI exception wrapper class.
