# Ticket: eng-016-yaml-linter-schema-and-startup-validation.md
## Issue: Add Typed YAML Linter Configuration and Fail-Fast Startup Validation

### Why This Ticket Exists
The phase plan expects linting to become a first-class part of YAML skill execution, but the manifest currently has no typed `linter` section. Before Bifrost can retry model outputs or wire Spring AI advisors, the framework needs a validated schema that clearly describes what a linter is and how it should behave.

This ticket creates that configuration surface and ensures invalid definitions fail before runtime.

---

## Goal
Add typed `linter` support to YAML manifests and validate each definition during catalog loading/startup.

The main outcome should be:

- YAML skills can declare a linter in a typed manifest shape
- bad linter config fails fast at startup with clear errors
- valid linter config is attached to loaded skill definitions for later runtime use

---

## Non-Goals
This ticket should **not** introduce:

- Spring AI `CallAdvisor` retry behavior
- prompt mutation or hint-injection logic
- telemetry export for linter outcomes
- external process execution unless it is already needed to validate the schema contract

---

## Required Outcomes

### Functional
- Extend `YamlSkillManifest` with a typed `linter` block.
- Decide and document the MVP linter modes supported now, such as regex-based validation and any minimal external-hook shape if Phase 4 still requires it.
- Support a bounded retry setting such as `max_retries`.
- Validate that required linter fields are present and internally consistent.
- Reject malformed regex definitions and impossible retry values with resource-specific startup errors.

### Structural
- Linter config is represented in typed Java objects rather than ad hoc maps.
- Validation happens during catalog/build time, not inside the runtime execution loop.
- `YamlSkillDefinition` exposes resolved linter settings in a way later runtime tickets can consume directly.
- YAML manifest loading now fails on unknown properties so linter-field typos are rejected during startup instead of being silently ignored.

### Testing
- Tests prove valid linter manifests load successfully.
- Tests prove malformed linter schemas fail startup clearly.
- Tests prove invalid regex patterns fail fast.
- Tests prove invalid or missing retry bounds fail fast.

---

## Suggested Files
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- `bifrost-spring-boot-starter/src/test/resources/skills/valid/...`
- `bifrost-spring-boot-starter/src/test/resources/skills/invalid/...`

---

## Acceptance Criteria
- YAML manifests can declare typed linter configuration.
- Invalid linter definitions fail the Spring application context during startup with clear resource/field messages.
- Valid linter configuration is preserved on loaded skill definitions for runtime consumption.
- Retry bounds are part of the validated manifest contract.

---

## Definition of Done
This ticket is done when linter configuration is a validated first-class part of YAML skill loading and invalid definitions fail fast before any mission execution begins.

## Review Note
As implemented, ENG-016 also tightens YAML manifest parsing to reject unknown properties during startup. This is intentional so misspelled `linter` fields fail fast, and reviewers should treat that stricter manifest contract as part of the accepted scope for this ticket.
