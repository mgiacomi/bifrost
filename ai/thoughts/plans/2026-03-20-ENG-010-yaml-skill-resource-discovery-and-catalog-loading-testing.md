# ENG-010 YAML Skill Resource Discovery and Catalog Loading Testing Plan

## Change Summary
- Tighten ENG-010 around catalog discovery/loading rather than downstream registration behavior.
- Add explicit automated coverage for missing and empty Spring resource locations.
- Re-scope `YamlSkillCatalogTests` so it proves catalog behavior directly, with later-ticket registry/visibility behavior moved to more appropriate test surfaces.

## Impacted Areas
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java`
- `bifrost-spring-boot-starter/src/test/resources/skills/empty/`
- Potentially `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
- Potentially `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`

## Risk Assessment
- Highest risk is proving the wrong thing: ENG-010 can look complete today only because `YamlSkillCatalogTests` mixes in `ENG-012` and `ENG-013` behaviors.
- Missing or empty resource patterns could regress silently if tests continue to focus only on positive discovery paths.
- Test moves could accidentally reduce coverage for mapped targets, RBAC metadata, or transformed execution errors if those behaviors are not relocated carefully.
- Adding an "empty root" fixture must be done explicitly; otherwise the test may depend on ambiguous Spring classpath matching behavior.

## Existing Test Coverage
- `YamlSkillCatalogTests` already covers:
- model/thinking resolution and validation
- pattern-based classpath discovery
- later-ticket behavior including mapped target registration, RBAC propagation, `allowed_skills`, and transformed mapped execution
- `SkillVisibilityResolverTest` already exists as a natural destination for some `ENG-013` visibility behavior.

## Gaps
- No explicit test proves startup succeeds when `bifrost.skills.locations` points at a missing classpath root.
- No explicit test proves startup succeeds when an existing classpath root contains zero matching YAML files.
- `YamlSkillCatalogTests` currently over-proves later-ticket behavior, which makes ENG-010 review noisy.

## Bug Reproduction / Failing Test First
- This is primarily a boundary-hardening and coverage-completion change, not a product bug fix.
- A mandatory failing test first is not required because the intended runtime behavior for missing roots is already implemented in `YamlSkillCatalog`.
- The closest pre-change proof gap is an absent test, not a known incorrect behavior.
- If we want a fail-first checkpoint, use a structural failing condition: create the missing-root and empty-root tests before any fixture or test reorganization, and treat the empty-root test as expected to fail until the real `skills/empty/` fixture directory exists.

## Tests to Add/Update
### 1) `loadsNoSkillsWhenConfiguredClasspathRootIsMissing`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves: `classpath:/skills/does-not-exist/**/*.yaml` does not fail startup and results in an empty catalog.
- Fixtures/data: no new fixture required.
- Mocks: none; use the existing `ApplicationContextRunner` and real `BifrostAutoConfiguration`.

### 2) `loadsNoSkillsWhenClasspathRootExistsButHasNoYamlMatches`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves: an existing root with zero YAML matches is tolerated and results in an empty catalog.
- Fixtures/data: add `bifrost-spring-boot-starter/src/test/resources/skills/empty/` as a real empty fixture directory.
- Mocks: none.

### 3) `loadsYamlSkillsFromClasspathSkillsPattern`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves: multiple YAML files under a Spring resource pattern load deterministically.
- Fixtures/data: reuse `src/test/resources/skills/pattern/deeper/pattern-two.yaml` and `src/test/resources/skills/pattern/nested/pattern-one.yaml`.
- Mocks: none.
- Update needed: keep this test in the ENG-010 suite and position it near the new missing/empty location tests.

### 4) `failsStartupWhenYamlSkillReferencesUnknownModel`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves: invalid manifest model references still fail fast with clear field/resource context.
- Fixtures/data: reuse `src/test/resources/skills/invalid/unknown-model-skill.yaml`.
- Mocks: none.
- Update needed: retain as part of ENG-010 and `ENG-011` shared catalog-validation coverage.

### 5) `failsStartupWhenThinkingLevelIsUnsupportedForModel`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves: invalid effective execution configuration still fails fast during startup.
- Fixtures/data: reuse `src/test/resources/skills/invalid/unsupported-thinking-skill.yaml`.
- Mocks: none.
- Update needed: retain as part of catalog-validation coverage unless later moved under `ENG-011`.

### 6) Split later-ticket tests out of `YamlSkillCatalogTests`
- Type: integration
- Location: likely new or existing test classes under `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/`
- What it proves: `ENG-012` and `ENG-013` behaviors remain covered after the ENG-010 suite is narrowed.
- Fixtures/data: reuse existing `skills/valid/*.yaml` fixtures.
- Mocks: none.
- Candidate moves:
- mapped target registration and invocation tests to a registrar-focused test class
- RBAC and `allowed_skills` visibility behavior to `SkillVisibilityResolverTest` or a new runtime-metadata test class
- transformed mapped-target error behavior to the registrar-focused test class

## How to Run
- Full starter module tests: `./mvnw -pl bifrost-spring-boot-starter test`
- Catalog-focused suite: `./mvnw -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests test`
- Visibility suite, if touched: `./mvnw -pl bifrost-spring-boot-starter -Dtest=SkillVisibilityResolverTest test`
- Registrar/runtime-metadata suite, if split into a new class: `./mvnw -pl bifrost-spring-boot-starter -Dtest=<NewTestClassName> test`

## Exit Criteria
- [x] Missing-root coverage exists and passes post-change.
- [x] Empty-root coverage exists, uses a real empty fixture directory, and passes post-change.
- [x] `YamlSkillCatalogTests` proves ENG-010 discovery/loading behavior without relying on mapped target, RBAC, or execution assertions.
- [x] Any tests removed from `YamlSkillCatalogTests` are preserved in follow-on test classes.
- [x] Full starter test suite passes: `./mvnw -pl bifrost-spring-boot-starter test`
- [ ] Manual review confirms the ENG-010 boundary is understandable from the test layout alone.
