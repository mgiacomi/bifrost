# ENG-016 YAML Linter Schema And Startup Validation Testing Plan

## Change Summary
- Add a typed `linter` block to YAML skill manifests for the `regex` MVP mode only.
- Validate linter configuration during `YamlSkillCatalog` startup instead of deferring errors to runtime.
- Expose the typed linter contract on `YamlSkillDefinition` so later advisor work can consume it directly.

## Impacted Areas
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- `bifrost-spring-boot-starter/src/test/resources/skills/valid/`
- `bifrost-spring-boot-starter/src/test/resources/skills/invalid/`

## Risk Assessment
- High risk: malformed linter manifests silently loading because only the new typed fields were added without catalog validation.
- High risk: validation error messages losing the existing resource-qualified `field '...'` format expected by current startup-failure tests.
- High risk: a valid regex linter loading into the manifest but not being exposed from `YamlSkillDefinition`, leaving ENG-017/018 without a stable runtime contract.
- Edge case: `max_retries` omitted, negative, or above the selected upper bound.
- Edge case: `type: regex` present but `regex` block missing or `pattern` blank.
- Edge case: an invalid Java regex syntax only fails later at runtime instead of during startup.
- Regression risk: existing YAML skills with no `linter` block should still load exactly as before.

## Existing Test Coverage
- `YamlSkillCatalogTests` already covers successful typed manifest loading for `allowed_skills`, `rbac_roles`, and `planning_mode`, which is the closest pattern for adding `linter` assertions.
- `YamlSkillCatalogTests` already covers startup-failure assertions for invalid manifests, including resource filename and field-path expectations.
- Existing valid fixtures under `bifrost-spring-boot-starter/src/test/resources/skills/valid/` show the convention of one small YAML file per scenario.
- Existing invalid fixtures under `bifrost-spring-boot-starter/src/test/resources/skills/invalid/` show the convention of isolating one failure mode per file or directory.
- Gap: there is no current coverage for typed `linter` loading, linter-specific startup validation, or invalid regex compilation.

## Bug Reproduction / Failing Test First
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- Arrange/Act/Assert outline:
  - Arrange a new invalid fixture such as `skills/invalid/invalid-regex-linter-skill.yaml` with `linter.type: regex`, a retry value in range, and a broken regex like `'([a-z'`.
  - Start the application context with `bifrost.skills.locations=classpath:/skills/invalid/invalid-regex-linter-skill.yaml`.
  - Assert that startup fails and that the message contains the fixture name plus `field 'linter.regex.pattern'`.
- Expected failure (pre-fix):
  - The test should fail before implementation because the current code does not recognize or validate `linter`, so the context will either start successfully or fail without the linter-specific field message.

## Tests to Add/Update
### 1) `loadsTypedRegexLinterConfigurationWhenPresent`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves:
  - A valid `regex` linter manifest loads successfully.
  - The linter contract is attached to the loaded `YamlSkillDefinition`.
  - The chosen retry budget survives deserialization and normalization.
- Fixtures/data:
  - Add `bifrost-spring-boot-starter/src/test/resources/skills/valid/regex-linter-skill.yaml`.
- Mocks:
  - None. Reuse the existing `ApplicationContextRunner` startup path.

### 2) `defaultsLinterToAbsentWhenManifestDoesNotDeclareOne`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves:
  - Existing manifests without `linter` remain valid.
  - The new definition accessor returns `null` or the agreed absent state for old fixtures.
- Fixtures/data:
  - Reuse `bifrost-spring-boot-starter/src/test/resources/skills/valid/default-thinking-skill.yaml`.
- Mocks:
  - None.

### 3) `failsStartupWhenLinterTypeIsMissing`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves:
  - If a `linter` block exists, `linter.type` is required.
  - The startup error points to `field 'linter.type'`.
- Fixtures/data:
  - Add `bifrost-spring-boot-starter/src/test/resources/skills/invalid/missing-linter-type-skill.yaml`.
- Mocks:
  - None.

### 4) `failsStartupWhenLinterTypeIsUnsupported`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves:
  - ENG-016 supports `regex` only.
  - Declaring any other type fails during startup instead of being ignored.
- Fixtures/data:
  - Add `bifrost-spring-boot-starter/src/test/resources/skills/invalid/unsupported-linter-type-skill.yaml`.
- Mocks:
  - None.

### 5) `failsStartupWhenRegexBlockIsMissingForRegexType`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves:
  - `type: regex` requires the nested `regex` configuration block.
  - The startup error reports the nested linter field clearly.
- Fixtures/data:
  - Add `bifrost-spring-boot-starter/src/test/resources/skills/invalid/missing-regex-block-skill.yaml`.
- Mocks:
  - None.

### 6) `failsStartupWhenRegexPatternIsMissingOrBlank`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves:
  - Regex linting requires a non-blank `pattern`.
  - Blank and omitted pattern values do not silently pass startup.
- Fixtures/data:
  - Add one or two fixtures depending on implementation style:
    - `bifrost-spring-boot-starter/src/test/resources/skills/invalid/missing-regex-pattern-skill.yaml`
    - `bifrost-spring-boot-starter/src/test/resources/skills/invalid/blank-regex-pattern-skill.yaml`
- Mocks:
  - None.

### 7) `failsStartupWhenRegexPatternIsInvalid`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves:
  - Broken regex syntax is compiled and rejected at startup.
  - The error is attributed to `field 'linter.regex.pattern'`.
- Fixtures/data:
  - Add `bifrost-spring-boot-starter/src/test/resources/skills/invalid/invalid-regex-linter-skill.yaml`.
- Mocks:
  - None.

### 8) `failsStartupWhenLinterMaxRetriesIsMissing`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves:
  - `max_retries` is required whenever `linter` is declared.
  - The error is attributed to `field 'linter.max_retries'`.
- Fixtures/data:
  - Add `bifrost-spring-boot-starter/src/test/resources/skills/invalid/missing-linter-max-retries-skill.yaml`.
- Mocks:
  - None.

### 9) `failsStartupWhenLinterMaxRetriesIsOutOfRange`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves:
  - Negative retry counts fail startup.
  - Values above the agreed maximum fail startup.
  - The bound is enforced at catalog load, not deferred to runtime.
- Fixtures/data:
  - Add one or two fixtures depending on assertion style:
    - `bifrost-spring-boot-starter/src/test/resources/skills/invalid/negative-linter-max-retries-skill.yaml`
    - `bifrost-spring-boot-starter/src/test/resources/skills/invalid/excessive-linter-max-retries-skill.yaml`
- Mocks:
  - None.

## How to Run
- Compile the starter module after schema changes: `.\mvnw.cmd -pl bifrost-spring-boot-starter -DskipTests compile`
- Run the targeted YAML catalog suite while iterating: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests test`
- Run the full starter module test suite before finishing: `.\mvnw.cmd -pl bifrost-spring-boot-starter test`

## Exit Criteria
- [ ] A failing test for invalid regex linting exists and fails pre-fix.
- [x] A valid regex-linter manifest loads successfully and is observable from `YamlSkillDefinition`.
- [x] Existing manifests without `linter` still load successfully.
- [x] Missing or unsupported `linter.type` fails startup with a resource-specific field message.
- [x] Missing `regex` config or missing/blank `pattern` fails startup with a resource-specific field message.
- [x] Invalid regex syntax fails startup with a resource-specific field message.
- [x] Missing or out-of-range `max_retries` fails startup with a resource-specific field message.
- [x] `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests test` passes post-fix.
- [x] `.\mvnw.cmd -pl bifrost-spring-boot-starter test` passes post-fix.
- [ ] Manual review confirms ENG-016 remains scoped to `regex` only and does not introduce external or LLM-backed linting behavior.
