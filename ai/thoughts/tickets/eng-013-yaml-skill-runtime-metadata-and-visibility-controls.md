# Ticket: eng-013-yaml-skill-runtime-metadata-and-visibility-controls.md
## Issue: Support YAML Runtime Metadata for Visibility, RBAC, and Planning Overrides

### Why This Ticket Exists
YAML manifests do more than describe a skill name and model. They also shape the runtime policy surface: which skills are visible to a parent skill, which roles can discover or execute a skill, and whether a skill opts out of default planning behavior.

This ticket captures those manifest-driven runtime controls as a focused slice of Phase 3.

---

## Goal
Support the current manifest-driven runtime metadata needed for visibility and execution policy.

The main outcome should be:

- `allowed_skills` constrains the YAML-visible tool surface
- `rbac_roles` flow into runtime capability metadata and can later be enforced consistently
- `planning_mode` can override the global default for a YAML skill

---

## Non-Goals
This ticket should **not** introduce:

- a new planning strategy
- linter behavior
- manifest tags as a discovery/filtering feature
- prompt templating or structured prompt authoring

---

## Required Outcomes

### Functional
- `allowed_skills` is parsed from YAML and attached to the skill definition.
- `rbac_roles` is parsed from YAML and attached to runtime capability metadata.
- `planning_mode` can override the global planning default for a specific YAML skill.
- Visibility resolution respects YAML `allowed_skills` plus RBAC constraints.

### Structural
- Visibility policy is derived from YAML metadata rather than hardcoded routing logic.
- Planning override behavior remains part of YAML skill definition/config resolution rather than hidden in execution code.

### Testing
- Tests prove `allowed_skills` parsing works.
- Tests prove missing `allowed_skills` defaults safely.
- Tests prove `rbac_roles` are preserved in runtime metadata.
- Tests prove `planning_mode` override behavior is explicit and test-covered.
- Tests prove visibility resolution continues to enforce allowed-skill plus RBAC filtering.

---

## Suggested Files
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java`

---

## Acceptance Criteria
- YAML runtime metadata for `allowed_skills`, `rbac_roles`, and `planning_mode` is loaded and preserved.
- Visibility filtering uses YAML policy plus RBAC as intended.
- Planning overrides are explicit and testable.

---

## Definition of Done
This ticket is done when YAML manifest runtime metadata controls visibility and execution policy in a test-covered, explicit way.
