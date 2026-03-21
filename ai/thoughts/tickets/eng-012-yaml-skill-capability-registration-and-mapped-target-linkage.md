# Ticket: eng-012-yaml-skill-capability-registration-and-mapped-target-linkage.md
## Issue: Register YAML Skills as Capabilities and Link Mapped Targets Safely

### Why This Ticket Exists
Loading YAML manifests is only part of the story. The runtime needs those manifests to become real capabilities so they can participate in skill discovery and execution. When a manifest points at a deterministic Spring target, that linkage also needs to be validated and preserved without losing the YAML-facing skill identity.

This ticket isolates the conversion from YAML skill definition to runtime capability metadata.

---

## Goal
Register YAML skills into the runtime capability registry and support safe linkage to mapped deterministic targets.

The main outcome should be:

- YAML-defined skills become registry-visible capabilities
- mapped `target_id` values resolve to discovered targets
- mapped YAML skills preserve YAML-facing names/descriptions while delegating execution correctly
- broken target links fail startup clearly

---

## Non-Goals
This ticket should **not** introduce:

- LLM-backed YAML mission execution
- visibility filtering or RBAC enforcement rules
- planning-mode runtime behavior
- linter support

---

## Required Outcomes

### Functional
- YAML skill definitions are converted into `CapabilityMetadata` and registered at startup.
- A manifest with `mapping.target_id` resolves against discovered runtime targets.
- Unknown `target_id` values fail startup clearly.
- Mapped deterministic YAML skills reuse the target invoker while preserving YAML-facing capability metadata.
- Mapped YAML skills can expose the target's input schema when appropriate.

### Structural
- Capability registration lives in a dedicated registrar rather than inside the catalog.
- The catalog stays focused on loading/validation; the registrar stays focused on runtime registration.

### Testing
- Tests prove mapped YAML skills register correctly.
- Tests prove broken `target_id` values fail startup.
- Tests prove mapped YAML skills preserve YAML-facing names while delegating to target invokers.
- Tests prove transformed deterministic errors still flow through the mapped YAML capability boundary.

---

## Suggested Files
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`

---

## Acceptance Criteria
- YAML skills appear in the runtime capability registry after startup.
- Deterministic mapped YAML skills invoke their linked targets correctly.
- Broken mapped targets fail clearly during startup.
- Mapped-schema behavior is covered by tests.

---

## Definition of Done
This ticket is done when YAML skills register as runtime capabilities and mapped deterministic targets link safely without losing the YAML-facing contract.
