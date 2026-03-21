# ENG-016 YAML Linter Schema And Startup Validation Implementation Plan

## Overview

Add a typed `linter` block to YAML skill manifests, validate it during catalog startup, and expose the resolved settings on loaded `YamlSkillDefinition` instances so later advisor tickets can consume the contract directly.

## Current State Analysis

`YamlSkillManifest` currently exposes typed fields for model selection, planning mode, RBAC, child-skill visibility, and deterministic mapping, but no `linter` section exists yet (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:9`). `YamlSkillCatalog` already owns fail-fast startup validation for required fields, model existence, duplicate names, and thinking-level compatibility (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:48`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:98`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:152`). `YamlSkillDefinition` is the current runtime-facing typed wrapper, but it only exposes allowed skills, RBAC roles, mapping, and planning mode helpers (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:10`).

The cleanest fit is to keep ENG-016 entirely in the existing startup-validation layer: extend the manifest with typed nested classes, validate linter consistency inside `YamlSkillCatalog.loadDefinition(...)`, and add a definition-level accessor that later advisor work can read without revisiting raw YAML.

## Desired End State

YAML skills can declare an optional typed linter manifest like this:

```yaml
linter:
  type: regex
  max_retries: 2
  regex:
    pattern: '...'
    message: '...'
```

Startup fails with resource-qualified `IllegalStateException` messages when:

- `linter.type` is missing or unsupported
- regex linting is selected without a non-blank `pattern`
- `pattern` is not a compilable Java regex
- `max_retries` is missing, negative, or above the chosen safety bound
- the `regex` block is missing when `type: regex` is selected

Successful startup preserves a normalized typed linter configuration on each loaded `YamlSkillDefinition`.

### Key Discoveries:
- `YamlSkillManifest` is already the repo's typed YAML contract surface and normalizes optional nested structures like `mapping` in setters (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:27`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:89`).
- `YamlSkillCatalog` already formats startup validation failures with resource and field names, so linter errors should use the same `invalidSkill(...)` path (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:146`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:152`).
- Existing catalog tests use `ApplicationContextRunner` plus per-resource fixtures, which is the right pattern for both valid and invalid linter coverage (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:18`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:141`).

## What We're NOT Doing

- Implementing any Spring AI `CallAdvisor`
- Retrying model generations at runtime
- Recording telemetry or journal entries for linter results
- Defining an external-process linter execution contract in live code
- Rejecting unrelated unknown YAML properties globally

## Implementation Approach

Scope ENG-016 to a single MVP linter mode: `regex`. That choice satisfies the ticket's typed-schema and fail-fast-validation requirements, unblocks ENG-017/018 with a stable runtime contract, and avoids inventing an external-hook schema before there is runtime infrastructure to execute it. The manifest should still be structured so future modes can be added as siblings under the same typed `linter` object without breaking callers.

Use explicit nested manifest classes under `YamlSkillManifest` for the linter block and regex settings. Keep validation in `YamlSkillCatalog` so all startup failures continue to happen during catalog load, alongside the existing model and thinking-level checks. Expose the normalized linter object from `YamlSkillDefinition` via a dedicated accessor instead of forcing later tickets to reach through `manifest()`.

Choose a conservative retry contract now:

- `max_retries` is required whenever `linter` is present
- allowed range is `0..3`
- `0` means validate once with no retry budget for future advisor logic

That range is small enough to satisfy the "token burn protection" intent from Phase 4 while remaining straightforward to validate and document.

## Phase 1: Add The Typed Manifest Surface

### Overview

Introduce the `linter` manifest block and definition-level accessors without changing runtime execution behavior.

### Changes Required:

#### 1. Extend `YamlSkillManifest`
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`
**Changes**: Add a nullable `LinterManifest` field, normalize nested values, and model regex-specific settings with typed nested classes.

```java
@JsonProperty("linter")
private LinterManifest linter;

@JsonIgnoreProperties(ignoreUnknown = true)
public static class LinterManifest {

    private String type;

    @JsonProperty("max_retries")
    private Integer maxRetries;

    private RegexManifest regex = new RegexManifest();
}

@JsonIgnoreProperties(ignoreUnknown = true)
public static class RegexManifest {
    private String pattern;
    private String message;
}
```

#### 2. Extend `YamlSkillDefinition`
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java`
**Changes**: Add a `linter()` helper that returns the typed manifest block directly so later chat/advisor tickets use the definition surface instead of reaching back through raw manifest internals.

```java
public YamlSkillManifest.LinterManifest linter() {
    return manifest.getLinter();
}
```

### Success Criteria:

#### Automated Verification:
- [x] Starter module compiles with the new manifest shape: `.\mvnw.cmd -pl bifrost-spring-boot-starter -DskipTests compile`
- [x] Catalog tests still pass after the schema extension: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests test`

#### Manual Verification:
- [ ] A maintainer can read the manifest class and see a single clear typed entry point for `linter`
- [ ] The new definition accessor makes the intended runtime contract obvious for ENG-017 and ENG-018

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manifest shape and accessor naming look right before proceeding to the next phase.

---

## Phase 2: Add Catalog-Time Linter Validation

### Overview

Make invalid linter definitions fail at startup with the same resource-qualified error style as existing catalog validation.

### Changes Required:

#### 1. Validate `linter` inside `YamlSkillCatalog`
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
**Changes**: Call a dedicated `validateLinter(resource, manifest)` method from `loadDefinition(...)` after required base fields are checked and before the definition is returned.

```java
private void validateLinter(Resource resource, YamlSkillManifest manifest) {
    YamlSkillManifest.LinterManifest linter = manifest.getLinter();
    if (linter == null) {
        return;
    }

    validateRequiredField(resource, "linter.type", linter.getType());
    validateRetryBounds(resource, linter.getMaxRetries());

    if (!"regex".equals(linter.getType())) {
        throw invalidSkill(resource, "linter.type", "unsupported linter type '" + linter.getType() + "'");
    }

    validateRegexLinter(resource, linter.getRegex());
}
```

#### 2. Enforce regex-specific consistency
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
**Changes**: Require a non-blank pattern, compile it with `java.util.regex.Pattern.compile(...)`, and surface failures via `invalidSkill(resource, "linter.regex.pattern", ...)`. Treat an optional blank `message` as absent or normalize it to `null`; do not make it required in this ticket.

#### 3. Codify retry bounds
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
**Changes**: Require `max_retries` whenever `linter` exists and reject values outside `0..3` using field-specific messages like `field 'linter.max_retries'`.

### Success Criteria:

#### Automated Verification:
- [x] Targeted catalog validation tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests test`
- [x] Full starter module tests still pass after adding linter validation: `.\mvnw.cmd -pl bifrost-spring-boot-starter test`

#### Manual Verification:
- [ ] Startup failures mention the exact resource filename and linter field path
- [ ] A reader can follow `loadDefinition(...)` and see that linter validation happens before runtime execution is possible

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the validation rules and retry bound feel right before proceeding to the next phase.

---

## Phase 3: Add Fixtures, Tests, And Contract Documentation

### Overview

Cover the new schema with positive and negative startup tests and document the MVP scope in code comments and fixtures.

### Changes Required:

#### 1. Expand `YamlSkillCatalogTests`
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
**Changes**: Add tests for successful typed linter loading plus failures for unsupported type, missing pattern, invalid regex, and invalid retry bounds.

```java
@Test
void loadsTypedRegexLinterConfigurationWhenPresent() { ... }

@Test
void failsStartupWhenRegexLinterPatternIsInvalid() { ... }

@Test
void failsStartupWhenLinterRetryBudgetIsOutOfRange() { ... }
```

#### 2. Add valid and invalid YAML fixtures
**Files**:
- `bifrost-spring-boot-starter/src/test/resources/skills/valid/...`
- `bifrost-spring-boot-starter/src/test/resources/skills/invalid/...`

**Changes**: Add one valid regex-linter fixture and several invalid fixtures that isolate each failure mode so assertions stay precise.

```yaml
name: linted.skill
description: Regex-linted skill
model: gpt-5
linter:
  type: regex
  max_retries: 2
  regex:
    pattern: '^```yaml[\\s\\S]*```$'
    message: 'Return fenced YAML only.'
```

#### 3. Document the supported MVP contract
**Files**:
- `ai/thoughts/plans/2026-03-21-ENG-016-yaml-linter-schema-and-startup-validation.md`
- inline code comments in `YamlSkillManifest` or `YamlSkillCatalog` only where the mode-scope needs clarification

**Changes**: State explicitly that ENG-016 supports only `regex` linter mode and reserves future modes for later tickets.

### Success Criteria:

#### Automated Verification:
- [x] New positive and negative linter tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests test`
- [x] No existing YAML catalog tests regress: `.\mvnw.cmd -pl bifrost-spring-boot-starter test`

#### Manual Verification:
- [ ] Test fixture names clearly communicate which validation rule each file covers
- [ ] The supported manifest contract is obvious to a future implementer reading tests first

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the fixture naming and contract coverage are sufficient before considering the ticket complete.

---

## Testing Strategy

### Unit Tests:
- Verify a valid regex-linter manifest loads and remains attached to `YamlSkillDefinition`
- Verify `linter.max_retries` is required when `linter` is present
- Verify negative retry counts and values above `3` fail startup
- Verify missing `linter.type` or unsupported types fail startup
- Verify blank or missing regex patterns fail startup
- Verify malformed regex syntax fails fast during startup instead of later runtime evaluation

### Integration Tests:
- Continue using `ApplicationContextRunner`-based startup tests in `YamlSkillCatalogTests` as the integration boundary for catalog loading
- Reuse the normal auto-configuration path so failures represent real application startup behavior

**Note**: Prefer a dedicated testing plan artifact created via `/testing_plan` for full details if implementation starts immediately after this planning step.

### Manual Testing Steps:
1. Run the targeted catalog test class and confirm both success and failure cases are asserted through startup behavior.
2. Open one valid and one invalid fixture and confirm the linter field names align with the Java manifest structure.
3. Review one thrown error assertion to ensure it includes both the resource filename and the exact linter field path.

## Performance Considerations

Regex validation happens once per manifest during startup, so the cost is negligible compared with model catalog initialization. Pre-compiling the regex only for validation is sufficient for ENG-016; caching compiled patterns can wait until a runtime consumer exists and proves it is necessary.

## Migration Notes

Existing YAML skills require no changes because `linter` remains optional. New validation only applies when the `linter` block is present.

Implementation note: the shipped change also makes YAML manifest deserialization fail on unknown properties so misspelled linter fields are rejected at startup. That is a deliberate scope tightening for ENG-016 rather than an accidental regression.

## References

- Original ticket: `ai/thoughts/tickets/eng-016-yaml-linter-schema-and-startup-validation.md`
- Related research: `ai/thoughts/research/2026-03-21-ENG-016-yaml-linter-schema-and-startup-validation.md`
- Manifest contract surface: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:9`
- Startup validation path: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:48`
- Runtime definition surface: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:10`
- Existing catalog test pattern: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:18`
