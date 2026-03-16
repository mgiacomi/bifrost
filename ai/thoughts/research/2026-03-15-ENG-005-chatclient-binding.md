---
date: 2026-03-15T13:50:33-07:00
researcher: Codex
git_commit: 71cffe65f015126cd142ad9acf318236c2ce1141
branch: main
repository: bifrost
topic: "Research current codebase state for ENG-005 ChatClient binding and model resolution"
tags: [research, codebase, eng-005, chatclient, model-resolution, capability-registry]
status: complete
last_updated: 2026-03-15
last_updated_by: Codex
---

# Research: Research current codebase state for ENG-005 ChatClient binding and model resolution

**Date**: 2026-03-15T13:50:33-07:00
**Researcher**: Codex
**Git Commit**: `71cffe65f015126cd142ad9acf318236c2ce1141`
**Branch**: `main`
**Repository**: `bifrost`

## Research Question
Use the `ai/commands/1_research_codebase.md` workflow to document the current codebase state relevant to `ai/thoughts/tickets/eng-005-chatclient-binding.md`, which describes model catalog binding, YAML skill model validation, thinking-level defaults, and a shared `ChatClient` resolver/factory.

## Summary
The current implementation in `bifrost-spring-boot-starter` is centered on annotation-driven Java capabilities discovered through `@SkillMethod` and registered into an in-memory `CapabilityRegistry`. Capability metadata currently stores a `ModelPreference` enum with only `LIGHT` and `HEAVY` values, and that preference is sourced directly from the annotation. There is not yet a framework model catalog bound from `application.yml`, there are no YAML skill manifest classes or loaders in `src/main`, and there is no `ChatClient` resolver/factory component in the live code that maps a skill execution configuration to a client instance.

The only framework configuration properties currently bound in `src/main` are `bifrost.session.*` through `BifrostSessionProperties`. The sample application's `application.yml` only defines the application name and server port. Historical planning documents in `ai/thoughts/phases` describe the intended YAML skill architecture and model-resolution behavior, including explicit `model` and optional `thinking_level`, but those behaviors are not yet represented in the live starter code reviewed here.

## Detailed Findings

### Annotation-Driven Capability Registration
- `@SkillMethod` is the current mechanism for marking executable capabilities on Spring beans. It exposes `name`, `description`, and `modelPreference`, with `modelPreference` defaulting to `ModelPreference.LIGHT` ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/annotation/SkillMethod.java:10](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/annotation/SkillMethod.java), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/annotation/SkillMethod.java:18](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/annotation/SkillMethod.java)).
- `ModelPreference` is a two-value enum: `LIGHT` and `HEAVY` ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ModelPreference.java:3](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ModelPreference.java), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ModelPreference.java:5](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ModelPreference.java)).
- `SkillMethodBeanPostProcessor` scans initialized beans for methods annotated with `@SkillMethod`, builds a Spring AI `ToolDefinition`, wraps the method in a `MethodToolCallback`, then registers a `CapabilityMetadata` record in the `CapabilityRegistry` ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:31](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:43](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:57](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:65](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java)).
- The capability invoker serializes the argument map to JSON and passes that payload to the Spring AI tool callback ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:68](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:71](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:72](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java)).

### Capability Metadata and Registry Shape
- `CapabilityMetadata` currently stores `id`, `name`, `description`, `modelPreference`, `rbacRoles`, and `invoker` ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:6](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java)).
- When `modelPreference` is null, the record constructor normalizes it to `ModelPreference.LIGHT` ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:14](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:18](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java)).
- The in-memory registry is a `ConcurrentHashMap` keyed by capability name. Registration enforces that the registry key matches `metadata.name()` and rejects duplicates with `CapabilityCollisionException` ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistry.java:8](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistry.java), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistry.java:13](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistry.java), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistry.java:21](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistry.java)).
- The metadata shape currently preserves only the abstract `LIGHT`/`HEAVY` preference and does not include a concrete provider model name or thinking-level field.

### Auto-Configuration and Bound Properties
- `BifrostAutoConfiguration` enables only `BifrostSessionProperties` and wires three infrastructure beans: `CapabilityRegistry`, `SkillMethodBeanPostProcessor`, and `BifrostSessionRunner` ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:14](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:19](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:26](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:33](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java)).
- `BifrostSessionProperties` binds the `bifrost.session` prefix and currently contains one property, `maxDepth`, defaulting to `32` and validated with `@Min(1)` ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java:7](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java:11](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java:13](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java)).
- A repository-wide search of `bifrost-spring-boot-starter/src/main` found no additional `@ConfigurationProperties` classes beyond `BifrostSessionProperties`, and no `application-test.yml` file for starter integration tests.

### Current Spring AI Integration Boundary
- The starter module depends on `org.springframework.ai:spring-ai-model`, which provides Spring AI model abstractions, but the reviewed live source uses Spring AI directly for tool definitions and method callbacks rather than for constructing a `ChatClient` bean or factory ([bifrost-spring-boot-starter/pom.xml:23](../../bifrost-spring-boot-starter/pom.xml), [bifrost-spring-boot-starter/pom.xml:25](../../bifrost-spring-boot-starter/pom.xml), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:6](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:7](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java)).
- No `ChatClient` type usages were found in `bifrost-spring-boot-starter/src/main`; current source files instead focus on session management, registry behavior, and method-tool registration.

### Sample Application Configuration
- The sample app's `application.yml` defines only `spring.application.name` and `server.port`; it does not define `bifrost.models`, `bifrost.session.max-depth`, or any skill-related model catalog entries ([bifrost-sample/src/main/resources/application.yml:1](../../bifrost-sample/src/main/resources/application.yml), [bifrost-sample/src/main/resources/application.yml:5](../../bifrost-sample/src/main/resources/application.yml)).

### Existing Test Coverage Relevant to ENG-005
- `BifrostAutoConfigurationTests` verifies the starter's auto-configuration annotation, confirms the auto-configuration import registration, and checks binding of `bifrost.session.max-depth=5` into `BifrostSessionProperties` alongside `BifrostSessionRunner` and `CapabilityRegistry` bean creation ([bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java:20](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java), [bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java:41](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java)).
- `SkillMethodBeanPostProcessorTest` verifies that an annotated method is registered with the capability name and description from the annotation, and that `modelPreference` is copied into capability metadata. The test fixture uses `ModelPreference.HEAVY` on one method and leaves the default on others ([bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java:18](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java), [bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java:25](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java), [bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java:30](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java), [bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java:82](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java)).
- No tests were found for YAML skill manifest binding, model catalog binding from `application-test.yml`, thinking-level defaults, boot-time skill model validation, or `ChatClient` resolution.

## Code References
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/annotation/SkillMethod.java:10` - `@SkillMethod` annotation declaration.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/annotation/SkillMethod.java:18` - Default `ModelPreference.LIGHT`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ModelPreference.java:3` - Current model abstraction enum.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:6` - Capability metadata record fields.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:18` - Null `modelPreference` normalization to `LIGHT`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:31` - Bean scanning for `@SkillMethod`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:43` - Spring AI `ToolDefinition` construction.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:57` - `CapabilityMetadata` creation from annotation values.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:14` - Starter auto-configuration and enabled properties.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java:8` - Only live configuration-properties prefix found in starter source.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java:41` - Existing property-binding test coverage.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java:82` - Test fixture showing `ModelPreference.HEAVY` on a skill method.
- `bifrost-sample/src/main/resources/application.yml:1` - Current sample configuration contents.

## Architecture Documentation
The live starter follows an annotation-first architecture for deterministic Java capabilities. Spring auto-configuration creates an in-memory registry and a bean post-processor. During bean initialization, annotated methods are converted into Spring AI tool callbacks and stored as `CapabilityMetadata`. The metadata currently uses an abstract model tier (`LIGHT` or `HEAVY`) rather than a concrete provider model catalog entry.

Configuration binding in the live starter is narrow and session-focused. `BifrostSessionProperties` is the only bound framework configuration class in the reviewed starter source. The sample application does not currently exercise framework-level model catalog binding. Within the reviewed source tree, there is no YAML manifest loader, no typed manifest model for skills, and no shared resolver/factory that produces `ChatClient` instances from validated skill execution settings.

## Historical Context (from ai/thoughts/)
- The master spec describes YAML as the LLM-facing skill contract and lists `model` as a required exact model name plus `thinking_level` as an optional execution hint ([ai/thoughts/phases/README.md:26](../phases/README.md), [ai/thoughts/phases/README.md:38](../phases/README.md), [ai/thoughts/phases/README.md:39](../phases/README.md)).
- The same document describes the intended runtime behavior for reproducible model selection, `medium` as the default thinking level when supported, and a shared resolver/factory for effective model settings ([ai/thoughts/phases/README.md:48](../phases/README.md), [ai/thoughts/phases/README.md:50](../phases/README.md), [ai/thoughts/phases/README.md:51](../phases/README.md), [ai/thoughts/phases/README.md:52](../phases/README.md)).
- Phase 2 planning also names model catalog binding, YAML skill validation, and replacing fixed `heavy`/`light` bindings with a shared `ChatClient` resolver/factory as part of the intended scope for skill model resolution ([ai/thoughts/phases/phase2.md:36](../phases/phase2.md), [ai/thoughts/phases/phase2.md:37](../phases/phase2.md), [ai/thoughts/phases/phase2.md:38](../phases/phase2.md), [ai/thoughts/phases/phase2.md:39](../phases/phase2.md)).

## Related Research
No existing documents were present under `ai/thoughts/research/` at the time of this research beyond this note.

## Open Questions
- The current live source reviewed here does not include YAML skill loading classes, so a deeper code-path trace for manifest parsing or boot-time YAML skill validation is not available in the existing implementation.
- The current live source reviewed here does not include a `ChatClient` construction path, so there is no implemented resolver/factory flow to trace yet from skill configuration into client instantiation.
