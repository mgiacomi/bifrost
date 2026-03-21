# Ticket: eng-023-sample-app-readme-and-release-consumption-docs.md
## Issue: Finish the Sample App and Project Documentation So Bifrost Is Consumable

### Why This Ticket Exists
Phase 5 ends with release prep, but the current sample app is still a bare Spring Boot shell and the repository does not yet have the top-level documentation needed to explain how to use the starter. The codebase now has enough runtime behavior that the missing docs/sample story is itself a real delivery gap.

This ticket makes the project understandable and runnable by someone who did not build it.

---

## Goal
Create a complete end-to-end sample and repo documentation set that shows how to use Bifrost with both `@SkillMethod` targets and YAML-defined skills.

The main outcome should be:

- the sample app demonstrates meaningful starter usage
- the repo has a real `README.md`
- developers can understand when to use Java methods versus YAML manifests

---

## Non-Goals
This ticket should **not** introduce:

- new core runtime features unrelated to demonstrating the framework
- a production-ready UI
- release automation or publishing pipelines unless documentation requires minor setup notes

---

## Required Outcomes

### Functional
- Expand `bifrost-sample` so it demonstrates at least one deterministic `@SkillMethod` capability and one YAML-defined skill flow.
- Include sample configuration showing model catalog setup, skill discovery locations, and any required starter properties.
- Provide a runnable path that lets a developer start the app and observe mission execution or diagnostics locally.

### Documentation
- Add a root `README.md` covering:
- what Bifrost is
- how the modules are structured
- how to configure models and skills
- when to use `@SkillMethod` versus YAML manifests
- how to run the sample app and tests
- Document any current limitations or intentionally unfinished areas so the sample does not over-promise.

### Structural
- Keep the sample aligned with the actual public starter API instead of relying on test-only scaffolding.
- Prefer concise, operator-friendly configuration examples over speculative architecture prose.

### Testing
- Add or update sample-app tests so the documented sample wiring is validated in CI.
- Ensure example resources committed with the sample remain loadable and coherent.

---

## Suggested Files
- `README.md`
- `bifrost-sample/pom.xml`
- `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/...`
- `bifrost-sample/src/main/resources/application.yml`
- `bifrost-sample/src/main/resources/skills/...`
- `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleApplicationTests.java`

---

## Acceptance Criteria
- The repository has a top-level `README.md` that explains setup and usage clearly.
- `bifrost-sample` demonstrates both YAML skill usage and `@SkillMethod` integration.
- A developer can follow the docs to run the sample and understand the framework entry points.
- Sample coverage verifies the documented wiring continues to work.

---

## Definition of Done
This ticket is done when the sample app and documentation make Bifrost understandable, runnable, and credible to a first-time consumer.
