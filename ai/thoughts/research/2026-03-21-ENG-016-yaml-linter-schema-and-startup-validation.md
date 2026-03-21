---
date: 2026-03-21T00:56:35.9298129-07:00
researcher: Unknown
git_commit: 5638416a041cba3a5f501644458e1bf64639304c
branch: main
repository: bifrost
topic: "eng-016-yaml-linter-schema-and-startup-validation"
tags: [research, codebase, yaml-skills, catalog, validation, linter]
status: complete
last_updated: 2026-03-21
last_updated_by: Unknown
---

# Research: eng-016-yaml-linter-schema-and-startup-validation

**Date**: 2026-03-21T00:56:35.9298129-07:00
**Researcher**: Unknown
**Git Commit**: 5638416a041cba3a5f501644458e1bf64639304c
**Branch**: main
**Repository**: bifrost

## Research Question

Use command `ai/commands/1_research_codebase.md` to perform research for `ai/thoughts/tickets/eng-016-yaml-linter-schema-and-startup-validation.md`.

## Summary

The current YAML skill manifest implementation is typed for `name`, `description`, `model`, `thinking_level`, `allowed_skills`, `rbac_roles`, `planning_mode`, and `mapping.target_id`, but it does not yet define a typed `linter` section in the live manifest class ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:9](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java#L9)).

Catalog-time validation currently happens in `YamlSkillCatalog.afterPropertiesSet()`, which discovers YAML resources, deserializes each manifest, validates required `name` / `description` / `model`, resolves model metadata, applies the default thinking level when needed, validates the selected thinking level, and rejects duplicate YAML skill names ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:47](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java#L47), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:98](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java#L98)).

Loaded YAML definitions are exposed as `YamlSkillDefinition` records containing the original `YamlSkillManifest` plus `EffectiveSkillExecutionConfiguration`; helper accessors currently expose `allowedSkills`, `rbacRoles`, `mappingTargetId`, and planning-mode override behavior, but no linter-specific accessor exists in the runtime definition today ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:10](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java#L10)).

The planning documents describe `linter` as part of the intended YAML architecture and Phase 4 work, but those descriptions are currently in `ai/thoughts/` rather than implemented in the starter module ([ai/thoughts/phases/README.md:35](/C:/opendev/code/bifrost/ai/thoughts/phases/README.md#L35), [ai/thoughts/phases/phase4.md:27](/C:/opendev/code/bifrost/ai/thoughts/phases/phase4.md#L27)).

## Detailed Findings

### Live Manifest Shape

- `YamlSkillManifest` is a Jackson-bound mutable class with `@JsonIgnoreProperties(ignoreUnknown = true)`, so unknown YAML keys are ignored during deserialization rather than rejected by the mapper ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:8](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java#L8)).
- The typed fields currently present in the class are `name`, `description`, `model`, `thinking_level`, `allowed_skills`, `rbac_roles`, `planning_mode`, and `mapping.target_id` ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:11](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java#L11), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:15](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java#L15), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:18](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java#L18), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:21](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java#L21), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:24](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java#L24), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:93](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java#L93)).
- List setters normalize `allowed_skills` and `rbac_roles` to immutable empty lists when omitted or null, and `setMapping` normalizes a missing mapping block to an empty `MappingManifest` instance ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:65](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java#L65), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:73](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java#L73), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:89](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java#L89)).
- There is no `linter` field, nested linter type, retry field, or regex-specific configuration class in the current manifest type ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:9](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java#L9)).

### Catalog Loading And Validation

- `YamlSkillCatalog` is created by auto-configuration as an infrastructure bean, making catalog initialization part of application startup ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:88](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java#L88)).
- On `afterPropertiesSet()`, the catalog clears existing entries, returns immediately if the model catalog is empty, then discovers configured YAML resources and loads each into a `YamlSkillDefinition` ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:47](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java#L47)).
- Resource discovery iterates `bifrost.skills.locations`, resolves each pattern through Spring `ResourcePatternResolver`, ignores missing classpath roots via `FileNotFoundException`, and sorts resources deterministically by URI/description before loading ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:76](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java#L76)).
- Manifest reading uses a YAML `ObjectMapper` configured with `FAIL_ON_UNKNOWN_PROPERTIES = false`, matching the manifest class' ignore-unknown behavior ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:137](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java#L137), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:165](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java#L165)).
- `loadDefinition()` validates only `name`, `description`, and `model` as required string fields, then resolves the selected model from `BifrostModelsProperties`, derives an effective thinking level, and rejects unsupported thinking levels using resource-qualified `IllegalStateException` messages ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:98](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java#L98), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:121](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java#L121), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:152](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java#L152)).
- Duplicate skill names are also rejected during catalog initialization, using the duplicate manifest's resource in the exception message ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:53](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java#L53)).

### Runtime Definition Surface

- `YamlSkillDefinition` is the stable typed object returned by the catalog and passed to later components; it stores the source `Resource`, the original `YamlSkillManifest`, and the resolved `EffectiveSkillExecutionConfiguration` ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:10](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java#L10)).
- The current helper methods expose `allowedSkills()`, `rbacRoles()`, `mappingTargetId()`, and `planningModeEnabled(defaultValue)`; there is no definition-level method exposing linter metadata today ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:15](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java#L15)).
- The execution configuration currently carries only framework model, AI provider, provider model, and optional thinking level ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/EffectiveSkillExecutionConfiguration.java:5](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/EffectiveSkillExecutionConfiguration.java#L5)).

### Downstream Consumers Of Loaded YAML Metadata

- `YamlSkillCapabilityRegistrar` iterates catalog definitions after singleton creation and registers each YAML skill as `CapabilityKind.YAML_SKILL` using manifest name, description, RBAC roles, execution configuration, tool descriptor, and mapped target id ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:27](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java#L27)).
- If `mapping.target_id` is present, the registrar resolves the invoker and tool schema from an already-registered capability; if absent, it installs an invoker that throws `UnsupportedOperationException` for the LLM-backed YAML path ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:44](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java#L44), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:55](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java#L55)).
- Unknown `mapping.target_id` values fail when the registrar tries to resolve the target capability, producing a startup exception scoped to `field 'mapping.target_id'` ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:47](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java#L47)).
- `DefaultSkillVisibilityResolver` uses `allowed_skills` from the current YAML skill, looks up only YAML children present in the catalog, and filters them through `AccessGuard` before exposing visible skills ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:28](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java#L28)).
- `ExecutionCoordinator` retrieves the `YamlSkillDefinition` for a requested skill, constructs the chat client from `definition.executionConfiguration()`, computes visible tools from the skill name, and passes `definition.planningModeEnabled(planningModeEnabled)` into mission execution ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:52](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java#L52)).

### Test Coverage Present Today

- `YamlSkillCatalogTests` cover default thinking-level selection, omission of thinking level for non-thinking models, startup failures for unknown models, startup failures for unsupported `thinking_level`, duplicate name rejection, wildcard resource loading, empty-scan behavior, typed loading of `allowed_skills` and `rbac_roles`, defaulting of missing optional typed fields, and `planning_mode` overrides ([bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:36](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java#L36)).
- Representative valid test manifests show current typed YAML fields:
  - `default-thinking-skill.yaml` uses only `name`, `description`, and `model` ([bifrost-spring-boot-starter/src/test/resources/skills/valid/default-thinking-skill.yaml:1](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/resources/skills/valid/default-thinking-skill.yaml#L1)).
  - `allowed-skills-root.yaml` defines `allowed_skills` ([bifrost-spring-boot-starter/src/test/resources/skills/valid/allowed-skills-root.yaml:1](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/resources/skills/valid/allowed-skills-root.yaml#L1)).
  - `allowed-child-skill.yaml` defines `rbac_roles` and `mapping.target_id` ([bifrost-spring-boot-starter/src/test/resources/skills/valid/allowed-child-skill.yaml:1](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/resources/skills/valid/allowed-child-skill.yaml#L1)).
  - `planning-disabled-skill.yaml` defines `planning_mode` ([bifrost-spring-boot-starter/src/test/resources/skills/valid/planning-disabled-skill.yaml:1](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/resources/skills/valid/planning-disabled-skill.yaml#L1)).
- Current invalid catalog fixtures demonstrate only model and thinking-level failures at the catalog layer:
  - `unknown-model-skill.yaml` uses an undefined model key ([bifrost-spring-boot-starter/src/test/resources/skills/invalid/unknown-model-skill.yaml:1](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/resources/skills/invalid/unknown-model-skill.yaml#L1)).
  - `unsupported-thinking-skill.yaml` uses `thinking_level: high` with `ollama-llama3`, which is configured without thinking levels in `application-test.yml` ([bifrost-spring-boot-starter/src/test/resources/skills/invalid/unsupported-thinking-skill.yaml:1](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/resources/skills/invalid/unsupported-thinking-skill.yaml#L1), [bifrost-spring-boot-starter/src/test/resources/application-test.yml:1](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/resources/application-test.yml#L1)).
- `YamlSkillCapabilityRegistrarTests` cover successful mapping to discovered `@SkillMethod` targets, propagation of manifest RBAC roles into registered capabilities, transformed deterministic-target errors, and startup failure for unknown `mapping.target_id` ([bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java:40](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java#L40)).
- `SkillVisibilityResolverTest` covers `allowed_skills` plus RBAC-driven child visibility for YAML-defined children, including omission of non-YAML entries and session-authentication fallback ([bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java:30](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java#L30)).

## Code References

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:9` - Current typed YAML manifest class and nested `MappingManifest`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:47` - Startup initialization path that discovers and loads YAML skills.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:98` - Per-resource manifest validation and effective execution configuration creation.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:152` - Resource-qualified validation error formatting.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:10` - Runtime definition record exposed by the catalog.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:27` - Registration of YAML skill definitions into the capability registry.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:44` - Mapping-target resolution and unresolved-target startup failure path.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:28` - Visibility filtering based on `allowed_skills` and RBAC.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:52` - Runtime consumption of execution config and planning-mode override.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:67` - Existing startup-failure coverage for catalog validation.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java:94` - Existing startup-failure coverage for unresolved mapping targets.

## Architecture Documentation

The YAML skill path is wired into application startup through `BifrostAutoConfiguration`, which provides `YamlSkillCatalog`, `YamlSkillCapabilityRegistrar`, `SkillVisibilityResolver`, and `ExecutionCoordinator` as infrastructure beans ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:88](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java#L88), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:97](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java#L97), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:112](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java#L112), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:235](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java#L235)).

Within that flow:

- the catalog owns YAML resource discovery, manifest deserialization, and boot-time validation of currently implemented manifest fields;
- the capability registrar converts loaded YAML definitions into registry entries and resolves deterministic `mapping.target_id` targets;
- the visibility resolver uses catalog metadata to compute child-skill visibility;
- the execution coordinator consumes the resolved execution configuration and planning-mode flag when invoking a YAML-backed mission.

In the current codebase, validation is split across two startup stages:

- catalog validation for required manifest fields, duplicate names, model existence, and thinking-level compatibility ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:98](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java#L98));
- registrar validation for `mapping.target_id` linkage against already-registered capabilities ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:47](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java#L47)).

No live runtime component currently consumes a `linter` manifest field, and no live startup validator currently parses or verifies regex patterns, retry counts, or external-hook linter definitions.

## Historical Context (from ai/thoughts/)

- The master spec describes `linter` as an optional private-manifest verification gate alongside `mapping`, `model`, `thinking_level`, and `planning_mode`, indicating the intended long-term YAML surface ([ai/thoughts/phases/README.md:35](/C:/opendev/code/bifrost/ai/thoughts/phases/README.md#L35)).
- Phase 4 explicitly lists typed `linter` support, boot validation, regex or external logic gating, `max_retries`, and observability as planned work for the linter lifecycle ([ai/thoughts/phases/phase4.md:27](/C:/opendev/code/bifrost/ai/thoughts/phases/phase4.md#L27)).
- The ticket for ENG-016 positions typed manifest support and fail-fast startup validation as the first linter-focused implementation step before advisor plumbing and bounded runtime retries ([ai/thoughts/tickets/eng-016-yaml-linter-schema-and-startup-validation.md:12](/C:/opendev/code/bifrost/ai/thoughts/tickets/eng-016-yaml-linter-schema-and-startup-validation.md#L12)).
- ENG-017 and ENG-018 then build on that planned schema by introducing advisor wiring and retry behavior after the manifest contract exists ([ai/thoughts/tickets/eng-017-spring-ai-linter-call-advisor-plumbing.md:12](/C:/opendev/code/bifrost/ai/thoughts/tickets/eng-017-spring-ai-linter-call-advisor-plumbing.md#L12), [ai/thoughts/tickets/eng-018-linter-call-advisor-bounded-retries-and-observability.md:10](/C:/opendev/code/bifrost/ai/thoughts/tickets/eng-018-linter-call-advisor-bounded-retries-and-observability.md#L10)).

## Related Research

No existing files were present in `ai/thoughts/research/` at the time of this research run.

## Open Questions

- The live codebase does not currently define which concrete linter modes, nested fields, or retry bounds will be represented in Java once `linter` is added.
- The live codebase does not currently indicate whether future linter validation will stay entirely inside `YamlSkillCatalog` or whether some linter-specific linkage will be validated in a later startup stage, similar to `mapping.target_id`.
