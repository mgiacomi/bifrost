# ENG-010 YAML Skill Resource Discovery and Catalog Loading Implementation Plan

## Overview

ENG-010 is mostly implemented already in `bifrost-spring-boot-starter`, but the current codebase blends the ticket's catalog-loading foundation with follow-on runtime registration and visibility behavior. This plan finishes ENG-010 by preserving the existing Spring resource discovery and typed catalog loading path, tightening the catalog boundary primarily through test ownership and light documentation, and adding the missing tests that prove empty or missing skill locations do not fail startup unnecessarily.

## Current State Analysis

The starter already exposes configurable YAML skill locations through `bifrost.skills.locations`, defaulting to `classpath:/skills/**/*.yaml` in [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSkillProperties.java:7`](bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSkillProperties.java#L7). `YamlSkillCatalog` already uses Spring's `ResourcePatternResolver`, tolerates missing classpath roots via `FileNotFoundException`, sorts resources deterministically, and turns manifests into `YamlSkillDefinition` entries with resolved execution configuration in [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:23`](bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java#L23).

The gap is not the absence of discovery logic. The gap is that startup wiring and tests currently fold later tickets into the same surface area. `BifrostAutoConfiguration` creates `YamlSkillCatalog` and immediately wires `YamlSkillCapabilityRegistrar`, `SkillVisibilityResolver`, and `ExecutionCoordinator` around it in [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:86`](bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java#L86). `YamlSkillCatalogTests` also mixes ENG-010 concerns with mapped target registration, RBAC propagation, and transformed execution errors in [`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:106`](bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java#L106). For this ticket, we will not force a broader bean-wiring refactor; we will instead keep the runtime layering in place and make the ENG-010 boundary clearer through catalog-focused tests and documentation.

## Desired End State

After this work:

- YAML skill manifests are still discovered from configured Spring resource patterns during startup.
- The source-of-truth catalog remains `YamlSkillCatalog`, exposing stable typed `YamlSkillDefinition` entries.
- ENG-010-specific tests cover classpath pattern loading, deterministic ordering, manifest loading, and missing/empty locations.
- Registration, `target_id` linkage, RBAC, visibility, and execution behavior are clearly treated as downstream concerns owned by later tickets, even if the repo keeps those implementations in place.

### Key Discoveries:
- `BifrostSkillProperties` already provides the correct Spring configuration surface for resource locations in `bifrost.skills.locations` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSkillProperties.java:7`).
- `YamlSkillCatalog.discoverResources()` already contains the ticket's required missing-root tolerance and deterministic sort order (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:70`).
- Auto-configuration currently couples the catalog bean to follow-on runtime components in the same startup path, but the catalog implementation itself remains cleanly separated (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:94`).
- Existing tests prove pattern loading but do not explicitly prove that empty or missing locations leave startup healthy (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:92`).
- Follow-on scope is already defined elsewhere: `mapping.target_id` linkage is owned by `ENG-012`, while `allowed_skills`, `rbac_roles`, and `planning_mode` runtime policy behavior are owned by `ENG-013`.

## What We're NOT Doing

- Registering YAML skills into `CapabilityRegistry` as part of this ticket's core acceptance path.
- Validating `mapping.target_id` linkage beyond preserving the manifest field on the typed definition.
- Enforcing RBAC or `allowed_skills` visibility policy.
- Implementing planning-mode runtime behavior.
- Adding linter configuration, linter runtime behavior, or richer YAML prompt authoring fields.

## Implementation Approach

Treat ENG-010 as a boundary-hardening pass, not a greenfield build. Keep the current `BifrostSkillProperties` and `YamlSkillCatalog` shapes as the foundation, then tighten the tests and light documentation so catalog discovery/loading can be reasoned about independently of runtime registration without forcing a larger runtime wiring refactor.

The safest path is:

1. Keep catalog discovery/loading logic centered in `YamlSkillCatalog`.
2. Narrow ENG-010-focused tests so they assert catalog behavior directly instead of relying on downstream registry side effects.
3. Add explicit startup-success coverage for missing and empty skill locations.
4. Make only small code or documentation changes needed so later registration/linkage work can evolve without re-defining the catalog contract.

## Phase 1: Lock the Catalog Boundary

### Overview

Document and enforce that ENG-010 ends at "discover resources, parse manifests, expose typed definitions." This phase keeps the catalog as the only loading abstraction and removes ambiguity about what belongs to later tickets.

### Changes Required:

#### 1. Catalog contract and typed definition review
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
**Changes**: Preserve the existing discovery and parsing flow, but make the public contract explicit in code comments or method naming if needed: discovery returns deterministic resources, manifest parsing builds typed definitions, and catalog lookups are the only supported access pattern for loaded YAML skills.

```java
public List<YamlSkillDefinition> getSkills() {
    return List.copyOf(skillsByName.values());
}
```

#### 2. Catalog entry shape confirmation
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java`
**Changes**: Keep `Resource`, parsed manifest, and resolved execution configuration as the stable typed shape for downstream consumers. ENG-010 should not expand or emphasize follow-on runtime-policy helpers here; if any documentation is added, it should clarify that downstream concerns such as mapped target linkage, visibility metadata, and RBAC flow are owned by later tickets.

```java
public record YamlSkillDefinition(
        Resource resource,
        YamlSkillManifest manifest,
        EffectiveSkillExecutionConfiguration executionConfiguration) {
}
```

#### 3. Auto-configuration boundary note
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
**Changes**: Keep `yamlSkillCatalog(...)` as the ENG-010-owned infrastructure bean. Do not force a larger bean-wiring split in this ticket. If necessary, add comments or small structural clarity so the catalog bean reads as the foundation and registrars read as downstream infrastructure built on top of it.

```java
@Bean
public YamlSkillCatalog yamlSkillCatalog(BifrostModelsProperties modelsProperties,
                                         BifrostSkillProperties skillProperties) {
    return new YamlSkillCatalog(modelsProperties, skillProperties);
}
```

### Success Criteria:

#### Automated Verification:
- [x] Starter test suite passes: `./mvnw -pl bifrost-spring-boot-starter test`
- [x] Catalog-focused test class passes after boundary cleanup: `./mvnw -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests test`

#### Manual Verification:
- [ ] Reading `YamlSkillCatalog` and `YamlSkillDefinition` makes the catalog boundary obvious without tracing runtime execution code.
- [ ] `BifrostAutoConfiguration` clearly presents the catalog bean as foundational and downstream registry wiring as separate concerns.

**Implementation Note**: After completing this phase and the automated verification passes, pause for manual review of the catalog boundary before proceeding.

---

## Phase 2: Add ENG-010-Specific Discovery and Loading Coverage

### Overview

Fill the acceptance-criteria gap in tests so ENG-010 is independently provable, especially around "no skills found" scenarios.

### Changes Required:

#### 1. Missing-location startup success coverage
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
**Changes**: Add a test that points `bifrost.skills.locations` at a missing classpath root and asserts that startup succeeds and the catalog is empty. This should exercise the `FileNotFoundException` path in `discoverResources()`.

```java
contextRunner
        .withPropertyValues("bifrost.skills.locations=classpath:/skills/does-not-exist/**/*.yaml")
        .run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(YamlSkillCatalog.class).getSkills()).isEmpty();
        });
```

#### 2. Empty-match startup success coverage
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
**Changes**: Add a second test for an existing root with zero matching YAML files so "empty" and "missing" locations are both explicitly covered. Use a real empty fixture directory under `src/test/resources/skills/empty/` so the distinction is unambiguous and stable across Spring resource resolution behavior.

```java
contextRunner
        .withPropertyValues("bifrost.skills.locations=classpath:/skills/empty/**/*.yaml")
        .run(context -> assertThat(context.getBean(YamlSkillCatalog.class).getSkills()).isEmpty());
```

#### 3. Deterministic multi-file loading remains explicit
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
**Changes**: Keep the current classpath-pattern order assertion, but position it alongside the new missing/empty tests so ENG-010 coverage reads as a coherent discovery suite.

```java
assertThat(catalog.getSkills())
        .extracting(definition -> definition.manifest().getName())
        .containsExactly("pattern.two.skill", "pattern.one.skill");
```

### Success Criteria:

#### Automated Verification:
- [x] Pattern-based classpath loading test passes: `./mvnw -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests#loadsYamlSkillsFromClasspathSkillsPattern test`
- [x] Missing-location startup test passes: `./mvnw -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests test`
- [x] Entire starter module test suite still passes: `./mvnw -pl bifrost-spring-boot-starter test`

#### Manual Verification:
- [ ] Test names and assertions make ENG-010 acceptance criteria obvious to a reviewer.
- [ ] A reviewer can distinguish "missing location tolerated" from "real I/O/configuration error still fails" by reading the suite.

**Implementation Note**: After completing this phase and the automated verification passes, pause for manual confirmation that the coverage is specific enough before moving on.

---

## Phase 3: Separate ENG-010 Coverage From Later-Ticket Behavior

### Overview

Reduce confusion by making tests and references reflect the ticket boundary. The goal is not to delete later-phase code or force a broader runtime refactor, but to stop using later-ticket behavior as the primary proof that ENG-010 works.

### Changes Required:

#### 1. Re-scope catalog test class contents
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
**Changes**: Move or split tests covering mapped method invocation, registry registration, RBAC propagation, and transformed target errors into later-ticket-oriented test classes if needed. Leave `YamlSkillCatalogTests` focused on discovery, typed manifest loading, required field/model validation, and deterministic ordering.

```java
// Keep here:
// - resource discovery
// - manifest loading
// - model/thinking validation
// Move elsewhere:
// - registry registration
// - target mapping
// - execution behavior
```

#### 2. Update plan and phase references if they describe ENG-010 too broadly
**File**: `ai/thoughts/phases/phase3.md`
**Changes**: If phase documentation is being used as implementation guidance, add a note that ENG-010 only establishes the discovery/catalog boundary and that runtime registration/linkage is split across later suggested tickets.

```markdown
- `ENG-010` owns resource discovery and typed catalog loading.
- Registration and `target_id` linkage move to follow-on tickets.
```

### Success Criteria:

#### Automated Verification:
- [x] Catalog-specific tests still pass after any test-class split: `./mvnw -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests test`
- [x] Full module tests pass after any test moves: `./mvnw -pl bifrost-spring-boot-starter test`

#### Manual Verification:
- [ ] ENG-010 can be reviewed without reading registry, visibility, or execution tests.
- [ ] Later-ticket work remains possible without redefining the catalog loading contract.

**Implementation Note**: After completing this phase and the automated verification passes, pause for manual review that the ticket boundary is clean before considering the work complete.

---

## Testing Strategy

### Unit Tests:
- Verify `bifrost.skills.locations` defaulting and replacement behavior.
- Verify resource pattern discovery loads multiple YAML files in deterministic order.
- Verify missing classpath roots and empty matches leave the catalog empty without failing startup.
- Verify invalid manifests still fail fast for required fields, unknown models, and unsupported thinking levels.

### Integration Tests:
- Use `ApplicationContextRunner` with `BifrostAutoConfiguration` to prove Spring property binding and `ResourcePatternResolver` behavior together.
- Keep one focused startup-path test that exercises the default catalog bean from auto-configuration.

**Note**: Prefer a dedicated testing plan artifact created via `/testing_plan` for the detailed command matrix and any fail-first sequence. This section is the high-level summary for ENG-010.

### Manual Testing Steps:
1. Add two YAML fixtures under different nested classpath directories and confirm the catalog loads both in deterministic order.
2. Point `bifrost.skills.locations` at a missing classpath subtree and confirm the application context still starts with zero catalog entries.
3. Point `bifrost.skills.locations` at an invalid YAML manifest and confirm startup fails with a message that identifies the resource and field.

## Performance Considerations

Resource discovery happens during startup and currently sorts the full discovered resource set before loading. That is appropriate for ENG-010 because determinism is more important than micro-optimizing startup, but the implementation should avoid repeated rescans after initialization.

## Migration Notes

No data migration is required. The main migration concern is conceptual: existing code and tests that currently treat catalog loading and runtime registration as one feature should be clarified so future tickets can build on the catalog without reworking its contract.

## References

- Original ticket: `ai/thoughts/tickets/eng-010-yaml-skill-resource-discovery-and-catalog-loading.md`
- Related research: `ai/thoughts/research/2026-03-20-ENG-010-yaml-skill-resource-discovery-and-catalog-loading.md`
- Follow-on validation ticket: `ai/thoughts/tickets/eng-011-yaml-skill-manifest-validation-and-execution-config-resolution.md`
- Follow-on linkage ticket: `ai/thoughts/tickets/eng-012-yaml-skill-capability-registration-and-mapped-target-linkage.md`
- Follow-on runtime metadata ticket: `ai/thoughts/tickets/eng-013-yaml-skill-runtime-metadata-and-visibility-controls.md`
- Resource location properties: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSkillProperties.java:7`
- Catalog discovery and loading: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:47`
- Auto-configuration catalog wiring: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:86`
- Current downstream registration coupling: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:16`
- Existing pattern-loading test: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:92`
