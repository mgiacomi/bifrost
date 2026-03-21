# Ticket: eng-010-yaml-skill-resource-discovery-and-catalog-loading.md
## Issue: Discover YAML Skills From Spring Resources and Load Them Into a Catalog

### Why This Ticket Exists
Phase 3 starts with a simple but important foundation: YAML-defined skills need to be discoverable from standard Spring resource locations so application teams can add skills without changing Java wiring.

This ticket establishes the catalog-loading boundary for YAML skills. It is intentionally focused on resource discovery and manifest loading, not on downstream runtime behavior such as capability registration, visibility policy, or execution.

---

## Goal
Implement Spring-native discovery and loading of YAML skill manifests into a dedicated catalog abstraction.

The main outcome should be:

- YAML skill files can be loaded from configured Spring resource patterns.
- Loaded manifests are represented as typed runtime definitions in one catalog.
- The catalog can act as the source of truth for later validation, registration, and runtime wiring.

---

## Non-Goals
This ticket should **not** introduce:

- capability registration into the runtime registry
- `target_id` linkage validation
- RBAC or `allowed_skills` enforcement
- planning-mode execution behavior
- linter configuration or linter runtime behavior

Those belong in follow-on tickets.

---

## Required Outcomes

### Functional
- A configurable property exists for YAML skill resource locations.
- Spring `ResourcePatternResolver` is used to discover YAML resources.
- Missing resource roots are tolerated when they simply mean "no skills found."
- YAML manifests are read into a typed catalog entry/definition shape.

### Structural
- YAML loading lives behind a dedicated catalog abstraction rather than being spread across auto-configuration.
- Resource discovery and manifest parsing are easy to reason about independently of execution behavior.

### Testing
- Tests prove classpath pattern loading works.
- Tests prove multiple YAML files are loaded deterministically.
- Tests prove empty or missing locations do not crash startup unnecessarily.

---

## Suggested Files
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSkillProperties.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`

---

## Acceptance Criteria
- YAML files placed under configured Spring resource patterns are discovered during startup.
- The catalog exposes loaded YAML skill definitions in a stable typed form.
- Discovery failures surface clearly when they are real I/O/configuration problems.
- Pattern-based loading is covered by tests.

---

## Definition of Done
This ticket is done when YAML manifests can be discovered from Spring resources and loaded into a typed catalog that later tickets can build on.
