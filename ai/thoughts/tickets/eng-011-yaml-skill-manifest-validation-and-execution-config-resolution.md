# Ticket: eng-011-yaml-skill-manifest-validation-and-execution-config-resolution.md
## Issue: Validate YAML Skill Manifests and Resolve Effective Execution Configuration

### Why This Ticket Exists
Once YAML skill manifests can be loaded, the next risk is accepting invalid or ambiguous configuration and failing much later at runtime. We want startup-time validation so bad manifests fail fast and valid manifests resolve to an explicit execution configuration.

This ticket focuses on validating the manifest fields that define execution behavior, especially model and thinking-level compatibility.

---

## Goal
Validate current YAML manifest fields at startup and resolve each skill's effective execution configuration.

The main outcome should be:

- required manifest fields are enforced
- model references resolve against configured model catalog entries
- thinking-level defaults and compatibility are applied consistently
- invalid manifests fail startup with clear messages

---

## Non-Goals
This ticket should **not** introduce:

- runtime execution of YAML skills
- `PromptTemplate`-driven prompt assembly
- linter schema or linter validation
- full target registration behavior beyond validation needs

---

## Required Outcomes

### Functional
- Required manifest fields such as `name`, `description`, and `model` are validated.
- Unknown model names fail startup clearly.
- Thinking-level defaults are applied when supported by the selected model.
- Unsupported thinking levels fail startup clearly.
- Duplicate skill names are rejected.

### Structural
- Effective execution configuration is resolved once at catalog/build time rather than ad hoc during execution.
- Validation errors name the resource and field that failed.

### Testing
- Tests prove unknown models fail.
- Tests prove unsupported thinking levels fail.
- Tests prove thinking defaults behave correctly for models that do and do not support thinking.
- Tests prove duplicate names are rejected.

---

## Suggested Files
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/EffectiveSkillExecutionConfiguration.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`

---

## Acceptance Criteria
- Invalid manifest fields fail the Spring application context during startup.
- Effective execution settings are attached to each loaded YAML skill definition.
- Thinking-level rules are covered by tests and behave consistently across supported providers/models.

---

## Definition of Done
This ticket is done when YAML manifests fail fast for invalid execution settings and every valid skill has a resolved effective execution configuration.
