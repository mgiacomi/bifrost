---
date: 2026-03-14T21:32:00-07:00
researcher: Antigravity
git_commit: 6dd21d00250e6af165461cf2fdf055ff3bae65312
branch: main
repository: bifrost
topic: "Research for eng-002-skillmethod-bpp.md"
tags: [research, codebase, skillmethod, spring-ai, bean-post-processor]
status: complete
last_updated: 2026-03-14
last_updated_by: Antigravity
last_updated_note: "Added follow-up research for JSON Schema generation strategy"
---

# Research: Research for eng-002-skillmethod-bpp.md

**Date**: 2026-03-14 21:32:00 PDT
**Researcher**: Antigravity
**Git Commit**: 6dd21d00250e6af165461cf2fdf055ff3bae65312
**Branch**: main
**Repository**: bifrost

## Research Question
Use command `1_research_codebase.md` to perform research for `eng-002-skillmethod-bpp.md`. The focus is on the implementation state of the `SkillMethodBeanPostProcessor`, Spring AI function mapping, and the environment these integrations operate within.

## Summary
The codebase currently contains the foundational models and annotations for the Bifröst phase 2 capability discovery but lacks the actual `SkillMethodBeanPostProcessor` integration and the Spring AI function wrapping. Foundational domain models like `CapabilityRegistry`, `CapabilityMetadata`, `CapabilityInvoker`, and `@SkillMethod` exist in functional states. The integration logic defined in ticket `eng-002-skillmethod-bpp.md` has not yet been authored in the repository.

## Detailed Findings

### Spring AI Dependency Status
- The `bifrost-parent` module configures `spring-ai-bom` (`1.1.2`) in `pom.xml:70` under `dependencyManagement`. 
- However, the `bifrost-spring-boot-starter` does not currently list any dependencies for Spring AI in its `<dependencies>` block, nor does it configure any Spring `ChatClient` instances yet.

### `@SkillMethod` Annotation
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/annotation/SkillMethod.java:12`
- Discovered custom annotation `@SkillMethod` configured with `RetentionPolicy.RUNTIME` and targeted at `ElementType.METHOD`. It exposes attributes `name()`, `description()`, and `modelPreference()`. These map attributes represent the metadata meant to be extracted.

### Capability Registry Models
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:6`
- The `CapabilityMetadata` record defines properties required to register a skill: `id`, `name`, `description`, `modelPreference`, `rbacRoles`, and `CapabilityInvoker`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityInvoker.java:6`
- A `@FunctionalInterface` `CapabilityInvoker` defines an `invoke` method taking `Map<String, Object> arguments`.

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityRegistry.java:5`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistry.java:8`
- Provide the service definitions and an in-memory runtime implementation designed to hold these capabilities. The `register` method adds skills by a String capability name constraint.

### `SkillMethodBeanPostProcessor`
- Exhaustive searches across the current `main` branch codebase indicate that `SkillMethodBeanPostProcessor` does **not** exist yet. No components are currently implementing Spring's `BeanPostProcessor` to scan beans for `@SkillMethod` annotations. 
- Similarly, no logic currently converts existing Java methods into `java.util.function.Function` suitable for native Spring AI tools.

## Code References
- `pom.xml:68-73` - Configuration of `spring-ai-bom`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/annotation/SkillMethod.java:10-20` - `@SkillMethod` defining fields for `name`, `description`, and `ModelPreference`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:6-21` - The expected metadata object configuration designed to wrap discovered capabilities.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityInvoker.java:5-9` - Functional interface meant to support reflection calls.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistry.java:12-25` - Thread-safe `register` implementation that will consume the BeanPostProcessor output.

## Architecture Documentation
In its current state, the system employs annotation-driven definitions (`@SkillMethod`) and relies on `java.util.concurrent.ConcurrentHashMap` for holding runtime state within `InMemoryCapabilityRegistry`. The architecture delegates execution behavior generically through the `CapabilityInvoker` functional interface, abstracting away reflective constraints from the core engine itself.

## Historical Context (from ai/thoughts/)
- `ai/thoughts/tickets/eng-001-skill-annotation-and-registry.md` - Confirms that earlier foundational work was successfully merged, providing `@SkillMethod` and `CapabilityRegistry`.
- `ai/thoughts/tickets/eng-002-skillmethod-bpp.md` - Defines the next required steps of interpreting `@SkillMethod` via a BeanPostProcessor and utilizing reflection to drive `java.util.function.Function` creation.
- `ai/thoughts/phases/phase2.md` - Contextualizes the importance of the missing BeanPostProcessor within the larger phase, explicitly denoting that the current goal is integrating Spring AI `ChatClient` inside a tool-calling framework.

## Related Research
- None at this time.

## Open Questions
- none

## Follow-up Research [2026-03-14T21:33:26-07:00]
Following the user's guidance, we will use `org.springframework.ai.util.json.schema.JsonSchemaGenerator` for JSON Schema generation. The ticket plan has been updated to reflect this requirement.
