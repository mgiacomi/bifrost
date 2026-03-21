---
date: 2026-03-20T20:12:56.5332363-07:00
researcher: Codex
git_commit: 9e0ae3e3e2d8ac328a11f76f8628d26188065cd4
branch: main
repository: bifrost
topic: "ENG-010 YAML skill resource discovery and catalog loading"
tags: [research, codebase, yaml-skills, spring-resources, catalog-loading]
status: complete
last_updated: 2026-03-20
last_updated_by: Codex
---

# Research: ENG-010 YAML skill resource discovery and catalog loading

**Date**: 2026-03-20T20:12:56.5332363-07:00
**Researcher**: Codex
**Git Commit**: 9e0ae3e3e2d8ac328a11f76f8628d26188065cd4
**Branch**: main
**Repository**: bifrost

## Research Question
Use command file `ai/commands/1_research_codebase.md` to perform research for `ai/thoughts/tickets/eng-010-yaml-skill-resource-discovery-and-catalog-loading.md`.

## Summary
The `bifrost-spring-boot-starter` module already contains a Spring-configured YAML skill loading path. `BifrostSkillProperties` exposes configurable resource locations under `bifrost.skills.locations`, defaulting to `classpath:/skills/**/*.yaml` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSkillProperties.java:7`). `BifrostAutoConfiguration` wires a `YamlSkillCatalog` bean and a downstream `YamlSkillCapabilityRegistrar` bean during startup (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:86`).

`YamlSkillCatalog` uses Spring’s `PathMatchingResourcePatternResolver` to resolve configured resource patterns, ignores missing classpath roots by catching `FileNotFoundException`, sorts discovered resources by URI/description for deterministic loading, and parses each YAML file into a `YamlSkillManifest` plus an `EffectiveSkillExecutionConfiguration` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:33`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:70`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:92`).

The resulting typed catalog entries are exposed as `YamlSkillDefinition` records, which retain the source `Resource`, parsed manifest, and execution configuration for later consumers (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:7`). Current downstream consumers include capability registration and visibility filtering, so the catalog already functions as the central source of YAML skill definitions during boot and runtime (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:27`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:25`).

## Detailed Findings

### Skill resource configuration
- `BifrostSkillProperties` is a Spring `@ConfigurationProperties` type with prefix `bifrost.skills` and a `locations` list (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSkillProperties.java:7`).
- The default resource pattern is `classpath:/skills/**/*.yaml` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSkillProperties.java:10`).
- Setting `locations` to `null` or an empty list restores the same default pattern (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSkillProperties.java:16`).
- `BifrostAutoConfiguration` enables `BifrostSkillProperties` alongside the other Bifrost property classes and creates the `YamlSkillCatalog` infrastructure bean from `BifrostModelsProperties` and `BifrostSkillProperties` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:47`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:89`).

### Catalog discovery and loading flow
- `YamlSkillCatalog` implements `InitializingBean`, so loading occurs during bean initialization after properties are bound (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:23`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:47`).
- The public constructor instantiates a Spring `PathMatchingResourcePatternResolver` and a YAML `ObjectMapper` backed by `YAMLFactory` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:33`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:149`).
- During `afterPropertiesSet`, the catalog clears its internal map, returns immediately if no models are configured, then discovers and loads YAML resources one by one (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:48`).
- Resource discovery iterates over each configured location, calls `resourcePatternResolver.getResources(location)`, keeps only resources whose `exists()` flag is true, and catches `java.io.FileNotFoundException` so missing classpath roots are treated as “no skills at this location” (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:70`).
- Any other `IOException` during resource discovery is converted into `IllegalStateException` with the originating location in the message (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:84`).
- Discovered resources are sorted by the catalog’s `describe(resource)` string, which uses the resource URI when available and falls back to `getDescription()` otherwise (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:88`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:140`).
- Duplicate skill names are rejected when inserting into the internal `LinkedHashMap<String, YamlSkillDefinition>` keyed by manifest name (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:31`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:53`).

### Typed manifest and definition shape
- `YamlSkillManifest` is the typed YAML binding model. It includes `name`, `description`, `model`, `thinking_level`, `allowed_skills`, `rbac_roles`, `planning_mode`, and nested `mapping.target_id` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:8`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:15`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:18`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:24`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:93`).
- The manifest class ignores unknown YAML properties and normalizes list-valued fields to immutable empty lists when not provided (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:8`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:65`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:73`).
- `YamlSkillCatalog.loadDefinition` validates required `name`, `description`, and `model` fields, looks up the selected model in `BifrostModelsProperties`, and computes an `EffectiveSkillExecutionConfiguration` from the manifest plus model catalog entry (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:92`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:98`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:112`).
- `BifrostModelsProperties` stores the configured model catalog under `bifrost.models`, with provider, provider model, and an optional set of supported thinking levels (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostModelsProperties.java:15`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostModelsProperties.java:29`).
- The test fixture `application-test.yml` defines example model entries such as `gpt-5`, `claude-sonnet`, `gemini-pro`, and `ollama-llama3`, which the catalog uses during startup tests (`bifrost-spring-boot-starter/src/test/resources/application-test.yml:1`).
- If the manifest omits `thinking_level`, the catalog defaults to `"medium"` when the selected model advertises thinking support, otherwise it leaves the effective thinking level as `null` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:25`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:103`).
- `YamlSkillDefinition` is the catalog entry type. It carries the source `Resource`, parsed `YamlSkillManifest`, and resolved `EffectiveSkillExecutionConfiguration`, and exposes convenience accessors for `allowed_skills`, `rbac_roles`, `mapping.target_id`, and `planning_mode` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:7`).

### Startup wiring and downstream consumers
- `BifrostAutoConfiguration` creates a `YamlSkillCapabilityRegistrar` bean immediately after the catalog bean and also injects the catalog into the `SkillVisibilityResolver` and `ExecutionCoordinator` beans (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:94`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:102`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:223`).
- `YamlSkillCapabilityRegistrar` implements `SmartInitializingSingleton`, so it runs after singleton creation and registers one `CapabilityMetadata` entry per catalog definition (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:16`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:27`).
- Registered YAML capabilities use the manifest’s YAML-facing name and description, create IDs in the form `yaml:<skillName>:<resourceDescription>`, and attach execution metadata derived from the catalog entry (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:29`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:78`).
- When a YAML skill declares `mapping.target_id`, the registrar looks up an existing capability by ID and reuses its invoker and tool schema; otherwise it installs a placeholder invoker that throws `UnsupportedOperationException` for LLM-backed execution (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:44`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:61`).
- Deterministic Java targets come from `SkillMethodBeanPostProcessor`, which registers each `@SkillMethod` bean method into the `CapabilityRegistry` with IDs in the form `<beanName>#<methodName>` and a generated input schema (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:58`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:65`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:78`).
- `DefaultSkillVisibilityResolver` reads `allowed_skills` from the current YAML skill definition, resolves the named YAML skills back through the catalog, then filters visible capabilities through the registry and RBAC authorities (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:25`).

### Current test coverage and fixture resources
- `YamlSkillCatalogTests` uses `ApplicationContextRunner` with `BifrostAutoConfiguration` and loads `application-test.yml` into the Spring environment before each run (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:23`).
- The test `loadsYamlSkillsFromClasspathSkillsPattern` sets `bifrost.skills.locations=classpath:/skills/pattern/**/*.yaml` and asserts that two skills load in deterministic order: `pattern.two.skill`, then `pattern.one.skill` (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:92`).
- The fixture files for that test live under nested classpath directories, demonstrating pattern-based discovery across multiple resource subdirectories (`bifrost-spring-boot-starter/src/test/resources/skills/pattern/nested/pattern-one.yaml:1`, `bifrost-spring-boot-starter/src/test/resources/skills/pattern/deeper/pattern-two.yaml:1`).
- Additional tests in the same class verify startup failure for unknown models and unsupported thinking levels, manifest parsing for `allowed_skills`, `rbac_roles`, and `planning_mode`, and catalog-to-registry mapping for deterministic target methods (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:66`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:79`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:106`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:126`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:155`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:169`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:184`).

## Code References
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSkillProperties.java:7` - Spring property binding for `bifrost.skills.locations`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:89` - Auto-configured `YamlSkillCatalog` bean.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:97` - Auto-configured `YamlSkillCapabilityRegistrar` bean.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:70` - Resource pattern discovery and missing-root handling.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:92` - Manifest loading and effective execution configuration resolution.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:8` - Typed YAML manifest structure.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:7` - Catalog entry shape exposed to downstream consumers.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:27` - Registration of YAML definitions into `CapabilityRegistry`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:78` - Registration shape for deterministic `@SkillMethod` targets used by `mapping.target_id`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:25` - Visibility resolution that reads from the YAML catalog.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:92` - Pattern-loading test for classpath YAML discovery.

## Architecture Documentation
The current implementation separates YAML skill loading into a dedicated catalog class rather than embedding the parsing logic directly inside auto-configuration. Spring Boot property binding provides the resource patterns and model catalog. Auto-configuration wires the catalog as infrastructure, and the catalog performs boot-time resource discovery, manifest parsing, validation, and typed definition creation. Downstream infrastructure beans read those definitions from the catalog instead of re-reading YAML resources.

A YAML skill’s lifecycle in the current codebase is: bind `bifrost.skills.locations` and `bifrost.models` properties, create `YamlSkillCatalog`, resolve Spring resource patterns, deserialize each YAML resource into `YamlSkillManifest`, compute `EffectiveSkillExecutionConfiguration`, store the result as `YamlSkillDefinition`, then hand those definitions to `YamlSkillCapabilityRegistrar`, `DefaultSkillVisibilityResolver`, and `ExecutionCoordinator` through dependency injection.

## Historical Context (from ai/thoughts/)
- `ai/thoughts/phases/phase3.md:3` describes Phase 3 as the point where YAML manifests are discovered from Spring resource locations, validated at startup, and registered into the runtime capability registry.
- `ai/thoughts/phases/phase3.md:23` lists declarative parsing, `ResourcePatternResolver` discovery, validation/linkage, and runtime registration as the planned Phase 3 tasks.
- `ai/thoughts/phases/phase4.md:17` places Spring Security RBAC and linter-related behavior into a later phase, which is separate from the catalog-loading foundation described in ENG-010.
- `ai/thoughts/tickets/eng-010-yaml-skill-resource-discovery-and-catalog-loading.md` defines ENG-010 as the resource discovery and typed catalog boundary, while follow-on tickets expand into validation/linkage and runtime behavior.

## Related Research
No existing documents were present under `ai/thoughts/research/` at the time of this research pass.

## Open Questions
- No additional open questions were required to document the current YAML discovery and catalog-loading path.
