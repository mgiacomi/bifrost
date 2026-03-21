# Ticket: eng-012-yaml-skill-capability-registration-and-mapped-target-linkage.md
## Issue: Verify YAML Skill Capability Registration and Add Missing Broken-Link Coverage

### Why This Ticket Exists
YAML skill capability registration and deterministic target linkage appear to already be implemented in the runtime. This ticket exists to verify that behavior against the intended contract and add any missing regression coverage, especially for broken `mapping.target_id` startup failures.

---

## Goal
Confirm that YAML-defined skills are fully registered as runtime capabilities and that mapped deterministic targets preserve the YAML-facing contract.

If acceptance criteria are already satisfied by the current codebase, do not re-implement them. Instead, tighten tests and close remaining gaps.

---

## Non-Goals
This ticket should **not** introduce:

- new YAML execution modes
- LLM-backed YAML mission execution
- RBAC redesign
- planning-mode runtime behavior changes
- catalog/registrar refactors unless needed for a failing test
- linter support

---

## Validation Targets

### Functional
- YAML skill definitions are visible in the runtime capability registry at startup.
- A manifest with `mapping.target_id` resolves to an already discovered deterministic target.
- Mapped YAML skills preserve YAML-facing name and description while delegating execution to the linked target invoker.
- Mapped YAML skills expose the linked target input schema when appropriate.
- Unknown `mapping.target_id` values fail startup with a clear message.

### Structural
- YAML loading remains in `YamlSkillCatalog`.
- Runtime capability registration remains in `YamlSkillCapabilityRegistrar`.

### Testing
- Existing coverage is reviewed and retained where it already proves the contract.
- Add or update tests only for uncovered behavior.
- Ensure there is explicit test coverage for broken `mapping.target_id` startup failure.
- Keep coverage for mapped YAML registration, delegation, YAML-facing metadata, schema propagation, and transformed deterministic errors.

---

## Suggested Files
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
- `bifrost-spring-boot-starter/src/test/resources/skills/invalid/...`

---

## Acceptance Criteria
- No duplicate implementation is introduced for behavior already present in the codebase.
- Existing registrar behavior is verified against the intended contract.
- There is an automated test proving startup fails clearly when `mapping.target_id` does not resolve.
- Existing mapped-skill registration, delegation, YAML-facing metadata, and schema behavior remain covered.

---

## Definition of Done
This ticket is done when the current implementation is verified against the contract, missing regression coverage is added, and the codebase has explicit proof that broken mapped targets fail clearly during startup.
