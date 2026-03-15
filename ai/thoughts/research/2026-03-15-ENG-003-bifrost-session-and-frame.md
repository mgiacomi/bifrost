---
date: 2026-03-15T00:02:38.7831822-07:00
researcher: Unknown
git_commit: 792b380d7e85aebffc345b614d3a5e83ed4847ca
branch: main
repository: bifrost
topic: "eng-003-bifrost-session-and-frame.md"
tags: [research, codebase, bifrost-session, execution-frame, capability-registry, spring-boot-starter]
status: complete
last_updated: 2026-03-15
last_updated_by: Unknown
---

# Research: eng-003-bifrost-session-and-frame.md

**Date**: 2026-03-15T00:02:38.7831822-07:00
**Researcher**: Unknown
**Git Commit**: 792b380d7e85aebffc345b614d3a5e83ed4847ca
**Branch**: main
**Repository**: bifrost

## Research Question
Use command file `ai/commands/1_research_codebase.md` to perform research for `ai/thoughts/tickets/eng-003-bifrost-session-and-frame.md`.

## Summary
The current repository is a Java 21 multi-module Maven project with two modules: `bifrost-spring-boot-starter` and `bifrost-sample` (`pom.xml:39-55`). The implemented framework code in `bifrost-spring-boot-starter` currently provides Spring Boot auto-configuration, a capability registry, capability metadata, a `@SkillMethod` annotation, and a bean post-processor that scans annotated methods and registers them as Spring AI-backed capabilities (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:12-28`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:17-80`).

Within the implemented starter module, there are no source definitions for `BifrostSession`, `ExecutionFrame`, `BifrostStackOverflowException`, `ThreadLocal`, `ScopedValue`, or `ReentrantLock`-based session management. Session and execution-frame behavior is described in the phase documents and in the ENG-003 ticket itself rather than in shipped Java classes (`ai/thoughts/phases/README.md:52-57`, `ai/thoughts/phases/phase2.md:29-34`, `ai/thoughts/tickets/eng-003-bifrost-session-and-frame.md:7-22`).

## Detailed Findings

### Repository Structure and Runtime Baseline
- The parent POM defines `bifrost-spring-boot-starter` and `bifrost-sample` as the repository modules (`pom.xml:39-42`).
- The project is configured for Java 21, and the parent POM imports Spring Boot `3.5.11` and Spring AI `1.1.2` through dependency management (`pom.xml:44-75`).
- The Maven enforcer configuration requires Java 21+ and Maven 3.9+ (`pom.xml:99-125`).
- The sample application is a minimal Spring Boot application used to validate starter loading rather than session execution behavior (`bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/SampleApplication.java:1-10`, `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleApplicationTests.java:11-25`).

### Implemented Framework Components in the Starter
- `BifrostAutoConfiguration` contributes two infrastructure beans: a default `CapabilityRegistry` backed by `InMemoryCapabilityRegistry`, and a static `SkillMethodBeanPostProcessor` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:12-28`).
- The auto-configuration is exported through Spring Boot's auto-configuration imports file at `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, which contains `com.lokiscale.bifrost.autoconfigure.BifrostAutoConfiguration`.
- `CapabilityRegistry` defines the current runtime registry surface as `register`, `getCapability`, and `getAllCapabilities` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityRegistry.java:1-11`).
- `InMemoryCapabilityRegistry` stores capabilities in a `ConcurrentHashMap`, enforces nonblank names, validates that the registry key matches `CapabilityMetadata.name()`, and rejects duplicate names with `CapabilityCollisionException` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistry.java:8-47`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityCollisionException.java:1-11`).
- `CapabilityMetadata` is a record that captures `id`, `name`, `description`, `modelPreference`, `rbacRoles`, and `invoker`, with null/default normalization for `modelPreference` and `rbacRoles` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:6-30`).
- `ModelPreference` currently defines two values, `LIGHT` and `HEAVY` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ModelPreference.java:1-5`).

### Capability Discovery and Invocation Flow
- `@SkillMethod` is a runtime-retained method annotation with `name`, required `description`, and `modelPreference` attributes (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/annotation/SkillMethod.java:10-18`).
- `SkillMethodBeanPostProcessor` scans initialized beans for `@SkillMethod` annotations using `ReflectionUtils.doWithMethods` and registers each matching method with the `CapabilityRegistry` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:31-66`).
- For each annotated method, the processor builds a Spring AI `ToolDefinition`, derives a JSON schema for method input via `JsonSchemaGenerator.generateForMethodInput(method)`, creates a `MethodToolCallback`, and wraps that callback in a `CapabilityInvoker` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:43-65`).
- The capability ID is currently formed as `beanName + "#" + method.getName()`, and the capability description defaults to the method name if the annotation description is blank (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:38-41`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:57-63`).
- Invocation currently serializes the argument map to JSON with Jackson and passes the serialized payload into `MethodToolCallback.call(...)`; JSON serialization and runtime invocation failures are wrapped in `IllegalStateException` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:68-80`).

### Current Test Coverage
- `BifrostAutoConfigurationTests` verifies that `BifrostAutoConfiguration` has the `@AutoConfiguration` annotation and is present in the auto-configuration imports resource (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java:14-27`).
- `SkillMethodBeanPostProcessorTest` verifies that annotated methods are registered as capabilities, that metadata includes the configured `ModelPreference`, and that method invocation works with both required and optional parameters (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java:18-69`).
- `InMemoryCapabilityRegistryTest` verifies missing lookups, registration and retrieval, duplicate-name collision handling, and concurrent registration/read behavior using a fixed thread pool and 1,000 capabilities (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistryTest.java:21-112`).
- The sample app test verifies that the application context loads and that the Bifrost auto-configuration bean definition is present (`bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleApplicationTests.java:17-25`).

### Session and Execution-Frame Material Present in `ai/thoughts`
- The master phase document describes `BifrostSession` as the mission-scoped object carrying identity, resource isolation, an execution stack, and an `ExecutionJournal` (`ai/thoughts/phases/README.md:52-57`).
- The same document describes `callSkill` as using Java Virtual Threads and positions `ExecutionFrame` as part of recursive summarization and stack tracking (`ai/thoughts/phases/README.md:10`, `ai/thoughts/phases/README.md:22-24`, `ai/thoughts/phases/README.md:46-50`).
- `phase2.md` places `BifrostSession` and `ExecutionFrame` lifecycle management directly in Phase 2 scope and describes planned thread-local or scoped-bean session context, `ReentrantLock`-based concurrency control, `MAX_DEPTH` enforcement, and `ExecutionJournal` initialization (`ai/thoughts/phases/phase2.md:11-14`, `ai/thoughts/phases/phase2.md:29-34`).
- The ENG-003 ticket defines the requested runtime shape for `BifrostSession`, `ExecutionFrame`, `BifrostStackOverflowException`, and a thread-scoping access strategy such as `ThreadLocal`, `ScopedValue`, or a custom Spring scope (`ai/thoughts/tickets/eng-003-bifrost-session-and-frame.md:7-22`).
- Related tickets connect later components back to session state: ENG-004 says `ExecutionJournal` attaches directly to `BifrostSession`, and ENG-007 says `ExecutionCoordinator` depends on `BifrostSession` and records planning data into it (`ai/thoughts/tickets/eng-004-execution-journal.md:11-17`, `ai/thoughts/tickets/eng-007-execution-coordinator.md:7-18`).

### What Exists Today for ENG-003-Specific Concepts
- A repository-wide search of implemented starter source files did not return any Java definitions or usages for `BifrostSession`, `ExecutionFrame`, `BifrostStackOverflowException`, `ThreadLocal`, `ScopedValue`, or `ReentrantLock` in `bifrost-spring-boot-starter/src/main/java`.
- The current starter source tree contains nine Java files: `SkillMethod`, `BifrostAutoConfiguration`, `CapabilityCollisionException`, `CapabilityInvoker`, `CapabilityMetadata`, `CapabilityRegistry`, `InMemoryCapabilityRegistry`, `ModelPreference`, and `SkillMethodBeanPostProcessor`.
- The currently implemented concurrency behavior is limited to the use of `ConcurrentHashMap` in `InMemoryCapabilityRegistry` and concurrent registry tests using `ExecutorService`; there is no implemented virtual-thread-specific session boundary mechanism in the shipped source (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistry.java:5-10`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistryTest.java:65-112`).

## Code References
- `pom.xml:39-55` - Multi-module structure and Java 21 / Spring dependency baseline.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:12-28` - Auto-configuration wiring for the capability registry and bean post-processor.
- `bifrost-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports:1` - Exported auto-configuration entry.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/annotation/SkillMethod.java:10-18` - Runtime method annotation used for capability discovery.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:31-80` - Bean scanning, Spring AI tool-definition creation, and callback-based invocation.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityRegistry.java:1-11` - Capability registry contract.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistry.java:8-47` - Current in-memory registry implementation.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:6-30` - Capability metadata record and defaults.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java:18-69` - Tests for skill registration and invocation behavior.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistryTest.java:65-112` - Current concurrent registry test coverage.
- `ai/thoughts/phases/README.md:52-57` - Historical description of the `BifrostSession` model.
- `ai/thoughts/phases/phase2.md:29-34` - Historical Phase 2 session lifecycle plan.
- `ai/thoughts/tickets/eng-003-bifrost-session-and-frame.md:7-22` - Ticket requirements for session and execution-frame lifecycle behavior.

## Architecture Documentation
The current runtime architecture in the repository is centered on Spring Boot auto-configuration plus capability discovery. Application beans annotated with `@SkillMethod` are scanned after initialization by `SkillMethodBeanPostProcessor`, which converts them into Spring AI `ToolDefinition` and `MethodToolCallback` objects and registers them as `CapabilityMetadata` in the `CapabilityRegistry`. `InMemoryCapabilityRegistry` is the default backing store when the auto-configuration is active.

The session-oriented execution architecture described in the phase documents is not yet represented by concrete source classes in the starter module. The current codebase therefore exposes the capability registration layer and the starter wiring, while the `BifrostSession` / `ExecutionFrame` lifecycle remains documented in `ai/thoughts` as project design and ticketed work.

## Historical Context (from ai/thoughts/)
- `ai/thoughts/phases/README.md` describes the intended mission-scoped `BifrostSession`, recursive execution stack, and virtual-thread `callSkill` model.
- `ai/thoughts/phases/phase2.md` places session lifecycle management alongside capability discovery as part of the Phase 2 core engine scope.
- `ai/thoughts/tickets/eng-004-execution-journal.md` defines `ExecutionJournal` as attached directly to `BifrostSession`.
- `ai/thoughts/tickets/eng-007-execution-coordinator.md` defines `ExecutionCoordinator` as a later component that depends on `BifrostSession`.

## Related Research
No existing research documents were present in `ai/thoughts/research/` at the time of this investigation.

## Open Questions
- The repository metadata script references `humanlayer thoughts status` for researcher metadata, but that helper was not available in this environment, so the researcher field is recorded as `Unknown`.
