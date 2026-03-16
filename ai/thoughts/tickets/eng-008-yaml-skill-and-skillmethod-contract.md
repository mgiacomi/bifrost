# Ticket: eng-008-yaml-skill-and-skillmethod-contract.md
## Issue: Clarify YAML Skill Contracts and `@SkillMethod` Implementation Mapping

### Context
The current design language around `@SkillMethod` has drifted toward treating the annotation itself as a skill definition. That creates confusion around where LLM-facing metadata should live and how model-aware sub-agent skills differ from deterministic Java method targets. For MVP, Bifrost should make YAML the source of truth for the skill contract the LLM sees, while `@SkillMethod` identifies implementation methods that YAML skills may target.

### Requirements
1. **Define Responsibilities Clearly:**
   - Document that YAML manifests define the LLM-facing skill contract.
   - Document that `@SkillMethod` identifies Spring bean methods that are eligible to be exposed through YAML skill mappings.
   - Clarify that `@SkillMethod` is not itself the source of LLM execution configuration such as `model` or `thinking_level`.
2. **Skill Mapping Semantics:**
   - Define how a YAML skill maps to a deterministic Java method target discovered through `@SkillMethod`.
   - Define how a YAML skill maps to an LLM-backed sub-agent execution path.
   - Ensure these two execution paths fit under the same higher-level skill concept from the LLM's perspective.
3. **Validation Boundaries:**
   - Define boot-time validation rules for YAML skills that target `@SkillMethod` methods.
   - Confirm that `@SkillMethod` validation is limited to discoverability and linkability, while YAML owns user-facing skill metadata and LLM execution settings.
4. **Documentation Cleanup:**
   - Update affected tickets and phase docs so they no longer imply that `@SkillMethod` alone defines a complete skill.

### Acceptance Criteria
- The documentation clearly distinguishes YAML skills from `@SkillMethod` implementation targets.
- The expected mapping flow from YAML skill to `@SkillMethod` target is described unambiguously.
- Validation responsibilities between YAML manifests and `@SkillMethod` discovery are documented without overlap or contradiction.
