# Validate Public YAML Skill Names Implementation Plan

## Overview

Enforce one provider-portable public identity for every YAML skill at catalog load time, then migrate the starter's dotted fixtures and all exact identity references to valid lowerCamelCase names. The YAML `name` remains unchanged from authoring through catalog lookup, capability registration, nested visibility, execution, evidence, telemetry, traces, and provider-facing tool publication; Java `mapping.target_id` remains a separate internal `beanName#methodName` namespace.

## Current State Analysis

`YamlSkillCatalog` currently reads the raw YAML tree, checks that `name` and `description` contain text, performs mapped-manifest structural validation when applicable, converts the tree to `YamlSkillManifest`, and later inserts the definition under `manifest.getName()`. It has no character or length validation for the public name (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:72-87`, `YamlSkillCatalog.java:134-172`, `YamlSkillCatalog.java:235-290`, `YamlSkillCatalog.java:331-337`).

The public name is then copied directly into `CapabilityMetadata` and `CapabilityToolDescriptor` by `YamlSkillCapabilityRegistrar`, and `DefaultToolCallbackFactory` publishes the descriptor name as the Spring AI function/tool name. There is intentionally no provider-name translation boundary (`YamlSkillCapabilityRegistrar.java:44-68`, `YamlSkillCapabilityRegistrar.java:101-116`, `DefaultToolCallbackFactory.java:76-89`).

The two prerequisite changes are present on `main`: public YAML skills and Java implementation targets use separate registries, and mapped manifests now carry only wrapper metadata plus `mapping.target_id`. Current sample manifest names already follow the desired form and must remain behaviorally unchanged.

The migration is broad even though the validator is small:

- 85 of the 91 currently inventoried starter-test and sample manifests have names that do not match the new contract.
- Those represent 82 unique invalid names because duplicate and shared fixture names occur in multiple resources.
- Exact retired-name references currently occur in approximately 113 non-`ai/thoughts` source/resource files. Besides YAML, they appear in catalog/registrar assertions, `allowed_skills`, visibility tests, plans, evidence, metrics, journals, traces, and serialization fixtures.
- `YamlSkillCatalogTests` contains an explicit temporary test, `allowsHashInEvidenceToolNameUntilPublicNameValidationIsImplemented`, that must be converted to the supported public-name form without extending the new validator to unrelated evidence IDs or Java target IDs.

## Desired End State

Every parsed YAML manifest `name` must match `^[A-Za-z_][A-Za-z0-9_]{0,63}$` exactly. A name is 1-64 Java string characters, begins with an ASCII letter or underscore, and thereafter contains only ASCII letters, digits, or underscores. Invalid values fail catalog startup before mapped-field validation, typed deserialization, catalog insertion, registration, planning, or provider calls.

The startup error must identify the YAML resource, field `name`, exact invalid value, regex/length rule, and a valid example/remedy. Validation must not trim, sanitize, normalize, truncate, alias, or otherwise rewrite the name. Existing blank/missing-name behavior must continue through the required-field error path.

All ordinary starter fixtures and their references must use provider-safe lowerCamelCase names. Sample names such as `expenseLookup`, `invoiceParser`, and `feedstockTicketParser` remain unchanged. Tests must prove both the catalog boundary and unchanged identity propagation. Documentation must distinguish the public name rule from internal `mapping.target_id` syntax.

### Key Discoveries

- `readManifest()` is the earliest common boundary for both LLM-backed and mapped YAML. It already has access to the exact parsed string and resource before mapped-specific validation or `treeToValue`, making it the correct validation location (`YamlSkillCatalog.java:235-290`).
- `diagnosticSkillNames` is populated before validation and `invalidSkill()` delegates to `invalidNamedSkill()` when a name is available. The new error can therefore reuse the existing diagnostic shape while including both exact name and resource (`YamlSkillCatalog.java:247-253`, `YamlSkillCatalog.java:854-884`).
- Validation must run after `validateRequiredField(resource, "name", skillName)`. This preserves the existing missing/blank diagnostic and ensures whitespace-padded names are rejected rather than stripped into validity.
- Existing invalid fixtures test other catalog errors. Their public names must be migrated before running the full catalog suite, or the new name error will mask the behavior each fixture was created to test.
- Existing identity-flow tests already cover the important downstream surfaces: catalog keys, capability metadata/tool descriptors, `allowed_skills`, mapped targets, entry invocation, plans, evidence, metrics, journals, and traces. Updating their exact names keeps those tests focused on their original behavior while demonstrating that valid YAML names are not translated.
- A dot-to-lowerCamelCase conversion of the 82 unique invalid manifest names has no collisions in the current inventory. Duplicate-name fixtures must intentionally remain duplicates after conversion (for example, both `duplicate.skill` declarations become `duplicateSkill`).
- Generic replacement of every dotted string is unsafe. Model keys, Java package/class names, trace metadata, and internal target IDs are outside this migration. The replacement inventory must be derived specifically from invalid YAML `name` values, followed by exact-string updates.

## What We're NOT Doing

- No provider-specific aliases, sanitization, fallback lookup, compatibility layer, or translation table.
- No trimming, case normalization, truncation, or case-insensitive collision behavior.
- No lowerCamelCase-only validator; lowerCamelCase is the repository fixture style, while the enforced contract also permits uppercase starts and underscores.
- No validation of `mapping.target_id`, Java bean/method names, input/output property names, evidence IDs, task IDs, model catalog keys, or other author-controlled identifiers.
- No changes to provider selection, schemas, planning, RBAC, evidence semantics, execution routing, or HTTP/UI surfaces.
- No renaming of already-valid sample public names or Java `@SkillMethod` methods.
- No standalone reusable public validator API unless implementation reveals a second real production caller; a focused private catalog helper is sufficient for the current boundary.

## Implementation Approach

Use a test-first catalog seam and an exact-name migration:

1. Add isolated valid and invalid public-name fixtures and catalog tests. Run the new rejection test before production changes to demonstrate that the current catalog incorrectly accepts a nonportable name.
2. Add one precompiled `Pattern` and a focused private validation helper to `YamlSkillCatalog`. Invoke it on the raw parsed `name` immediately after the existing required-field check and before mapped validation/deserialization.
3. Derive the retired-name set from current manifest declarations. Convert dot-delimited fixture names to descriptive lowerCamelCase and update only exact occurrences of those identities across resources and tests. Preserve all `mapping.target_id` strings.
4. Use the renamed integration assertions as propagation coverage; add a narrowly focused assertion only where a downstream identity surface is not already protected.
5. Document the enforced contract and finish with static audits plus focused, module, sample, and full-reactor tests.

## Phase 1: Encode the Catalog Public-Name Contract

### Overview

Create focused boundary coverage and enforce the portable format at the earliest common raw-manifest boundary. This phase intentionally uses isolated test resources so the new contract can be proven before the broad fixture migration is complete.

### Changes Required

#### 1. Add focused public-name fixtures

**Files**: new YAML resources under a dedicated subtree such as:

- `bifrost-spring-boot-starter/src/test/resources/skills/public-name/valid/`
- `bifrost-spring-boot-starter/src/test/resources/skills/public-name/invalid/`

**Changes**:

- Add valid LLM-backed manifests covering:
  - one-character ASCII letter;
  - one-character underscore;
  - representative camelCase;
  - underscore-separated name;
  - digit after the first character;
  - exactly 64 characters.
- Add invalid manifests covering:
  - leading digit;
  - dot;
  - dash;
  - embedded space;
  - leading or trailing whitespace, proving no trim-to-valid behavior;
  - `#`, slash, and colon;
  - Unicode/non-ASCII letter;
  - 65 characters.
- Add a blank or missing-name fixture to protect the existing required-field path rather than treating blank as a format violation.
- Give each fixture all other fields needed to reach name validation cleanly. Keep these resources outside the ordinary `valid/` and `invalid/` fixture globs so focused tests control which rule is exercised.

#### 2. Add test-first catalog coverage

**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`

**Changes**:

- Add a focused test or dynamic-test factory that loads every valid public-name fixture and asserts the exact name is present in the catalog without mutation.
- Add a focused test or dynamic-test factory that loads each invalid fixture independently and asserts startup failure contains:
  - the resource filename/description;
  - field `name`;
  - the exact invalid value;
  - `^[A-Za-z_][A-Za-z0-9_]{0,63}$`;
  - the 1-64/start/allowed-character explanation;
  - a representative valid example.
- Run at least one invalid-name test before implementing the validator and record that it fails because the current catalog accepts the manifest or proceeds to later validation.
- Assert blank/missing names still report `required field is missing or blank`, not the portable-format message.
- Ensure mapped and LLM-backed coverage is represented, either with one invalid mapped fixture or an assertion that rejection occurs before mapped-specific validation.

#### 3. Implement exact validation in the catalog

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`

**Changes**:

- Add a private static final compiled pattern for `^[A-Za-z_][A-Za-z0-9_]{0,63}$` and a constant or stable diagnostic text for the authoring rule.
- Add a focused private helper such as `validatePublicSkillName(Resource resource, String skillName)`.
- Call it in `readManifest()` immediately after the existing raw `name` required-field validation and before `validateRawMappedManifest()` and `treeToValue()`.
- Use the existing `invalidSkill`/`invalidNamedSkill` diagnostic machinery so the failure includes both exact name and resource.
- Match the raw parsed string as-is. Do not call `trim()`, `strip()`, case conversion, replacement, or substring operations.
- Leave the later defensive required-field check in `loadDefinition()` intact unless removing it is independently proven safe and necessary; this ticket should not mix in validation cleanup.

### Success Criteria

#### Automated Verification

- [x] An invalid-name test demonstrably fails before the production validator is added.
- [x] Focused valid-name tests accept all required valid forms, including exactly 64 characters.
- [x] Focused invalid-name tests reject all required invalid forms, including 65 characters and non-ASCII input.
- [x] Blank/missing names retain the existing required-field diagnostic.
- [x] Error assertions prove resource, field, exact value, rule, and remedy are all present.
- [x] Targeted catalog methods pass, using a command of the form: `\.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCatalogTests#<public-name-test-method>" test`.

#### Manual Verification

- [x] No manual runtime verification is required for this phase; inspect one captured startup error for readability while reviewing the automated assertion.

---

## Phase 2: Migrate Fixtures and Exact Identity References

### Overview

Atomically rename every ordinary nonportable starter fixture identity and every exact reference to it, while leaving unrelated dotted identifiers and all Java target IDs unchanged. Restore the entire starter suite to green under the new catalog rule.

### Changes Required

#### 1. Build and apply the exact migration inventory

**Files**:

- YAML manifests under `bifrost-spring-boot-starter/src/test/resources/skills/valid/`
- YAML manifests under `bifrost-spring-boot-starter/src/test/resources/skills/invalid/`
- pattern-loading fixtures under `bifrost-spring-boot-starter/src/test/resources/skills/pattern/`
- all exact references under `bifrost-spring-boot-starter/src/test/`

**Changes**:

- Derive the source list from YAML `name` declarations that fail the new pattern; do not derive it from a generic dotted-string search.
- Convert dot-separated fixture identities to descriptive lowerCamelCase consistently, for example:
  - `mapped.method.skill` -> `mappedMethodSkill`;
  - `root.visible.skill` -> `rootVisibleSkill`;
  - `allowed.visible.skill` -> `allowedVisibleSkill`;
  - `output.schema.skill` -> `outputSchemaSkill`;
  - `invalid.negative.linter.max.retries.skill` -> `invalidNegativeLinterMaxRetriesSkill`.
- Preserve deliberate duplicate semantics: both duplicate-name manifests receive the same valid replacement.
- Update YAML `allowed_skills` and `evidence_contract.tool_evidence` keys when they name public skills. Replace the temporary `review#skill` public-name example with a valid exact name, but do not impose the public-name regex on evidence IDs.
- Do not change filenames solely because their manifest name changes; filenames are resource descriptions and are not public tool identities.
- Do not modify `mapping.target_id` values such as `targetBean#deterministicTarget`, `expenseService#getLatestExpenses`, or missing-target test data.

#### 2. Update catalog, registration, visibility, and entry assertions

**Primary files**:

- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skillapi/SkillTemplateTest.java`

**Changes**:

- Update catalog lookup keys, expected manifest names, duplicate diagnostics, registrar keys, capability metadata names, tool descriptor expectations, visibility inputs/results, and `SkillTemplate` invocation names.
- Rename `allowsHashInEvidenceToolNameUntilPublicNameValidationIsImplemented` so it describes the final supported behavior and update its fixture/assertions to use a valid exact public name.
- Retain the separate-namespace assertions proving the validated YAML name stays public while `beanName#methodName` remains internal.
- Add or strengthen one focused registrar/tool assertion only if existing tests do not explicitly show that the same mixed-case/underscore-capable YAML name reaches `CapabilityToolDescriptor` unchanged.

#### 3. Update planning, evidence, telemetry, journal, and trace fixtures

**Primary areas**:

- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/planning/`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/state/`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/step/`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/tool/`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java`

**Changes**:

- Replace exact retired fixture identities in plan capability names, `PlanTask` text/targets, ready-task prompts, evidence producer keys, metrics tags, frame routes, journals, trace payloads, NDJSON fixtures, and expected serialized JSON.
- Keep each test's original behavioral purpose and assertion structure. Avoid opportunistic refactors or renaming unrelated synthetic identities.
- Where one retired fixture identity is reused by a generic runtime test, update the whole expected event/payload consistently so the test still proves exact-name propagation.

#### 4. Audit ordinary manifests and retired-name references

**Scope**:

- starter test resources and Java tests;
- sample main resources and tests;
- root/sample documentation only where an old fixture identity was quoted.

**Changes**:

- Verify every ordinary manifest name outside the dedicated invalid-public-name fixture directory matches the pattern.
- Verify all 82 pre-migration invalid names have no remaining exact live-code/resource references. Use the pre-change manifest inventory (or derive it from `HEAD`) rather than a broad punctuation search.
- Verify no `mapping.target_id` was changed by comparing or searching target IDs before and after the migration.
- Verify all five sample manifest names remain unchanged and valid.

### Success Criteria

#### Automated Verification

- [x] Every ordinary starter and sample manifest name matches `^[A-Za-z_][A-Za-z0-9_]{0,63}$`; only dedicated rejection fixtures intentionally violate it.
- [x] Exact searches find no retired dotted public names in live source/resources outside historical `ai/thoughts` documents.
- [x] Duplicate-name coverage still fails for a duplicate valid public name.
- [x] Existing tests for their original invalid YAML conditions reach those conditions rather than failing early on `name`.
- [x] Mapped target IDs retain `beanName#methodName` syntax and mapped behavior tests pass.
- [x] Focused identity suites pass: `\.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCatalogTests,YamlSkillCapabilityRegistrarTests,SkillVisibilityResolverTest,ExecutionCoordinatorIntegrationTest,ToolCallbackFactoryTest,SkillTemplateTest" test`.
- [x] Full starter suite passes from a clean module build: `\.\mvnw.cmd -pl bifrost-spring-boot-starter clean test`.

#### Manual Verification

- [x] Review the generated rename diff to confirm replacements are limited to public skill identities and expected payload text; no Java target ID, model key, property name, or evidence ID was mechanically rewritten.

---

## Phase 3: Document the Contract and Verify the Repository

### Overview

Publish the source-verified authoring rule, explicitly preserve the namespace distinction, and run sample plus full-reactor verification.

### Changes Required

#### 1. Document the root authoring rule

**File**: `README.md`

**Changes**:

- In the YAML skill definition/invocation guidance, state that `name` is the single public identity and must match `^[A-Za-z_][A-Za-z0-9_]{0,63}$`.
- Explain in plain language: 1-64 characters, ASCII letter/underscore first, then ASCII letters/digits/underscores.
- State that names are case-sensitive and are not trimmed, sanitized, normalized, truncated, or aliased.
- Recommend descriptive lowerCamelCase as the repository authoring style without presenting it as the only accepted format.
- Reiterate that `allowed_skills`, `SkillTemplate`, and evidence tool keys use the exact YAML name, while `mapping.target_id` is internal `beanName#methodName` syntax governed by a different rule.

#### 2. Update the AI skill-authoring mental model and coverage matrix

**Files**:

- `ai/skill-authoring/mental-model.md`
- `ai/skill-authoring/README.md`

**Changes**:

- Add the enforced public-name contract to the core identity/capability discussion.
- Explain exact identity propagation across catalog, entry invocation, child visibility, evidence, traces, and provider-facing tools.
- Preserve the documented distinction between public YAML identity and internal Java target identity.
- Add `YamlSkillCatalog` and the new focused validator/test methods as implementation anchors in `mental-model.md`.
- Update the coverage matrix to mark public YAML identity/naming as source-verified while leaving the broader full manifest reference accurately incomplete.

#### 3. Run sample and reactor verification

**Files**: no intended sample production manifest changes.

**Changes**:

- Confirm the sample catalog and controller behavior still use the same valid names.
- Run the sample test suite with upstream starter changes.
- Run the full Maven reactor.
- Repeat static audits after formatting/build output to ensure no retired live references or unexpected invalid ordinary manifests remain.

### Success Criteria

#### Automated Verification

- [x] Documentation contains the exact regex, length/start explanation, case sensitivity, no-rewrite policy, valid examples, and separate `mapping.target_id` rule.
- [x] The five current sample names are unchanged and all sample tests pass: `\.\mvnw.cmd -pl bifrost-sample -am "-DskipTests=false" test`.
- [x] Full reactor passes: `\.\mvnw.cmd test`.
- [x] Final static scans report no invalid ordinary manifest names and no retired dotted public-name references outside historical planning/research/ticket artifacts and the dedicated rejection fixtures.
- [x] `git diff --check` passes.

#### Manual Verification

- [x] Read the README and AI guidance together to confirm the enforced regex is clearly distinguished from the lowerCamelCase recommendation and from internal Java target syntax.
- [x] No provider-backed manual call is required; startup validation, propagation, sample context loading, and tool descriptor behavior are covered automatically.

---

## Testing Strategy

### Unit and Catalog Tests

- Protect all valid boundaries, invalid characters, Unicode, length 64/65, missing/blank behavior, exact-case preservation, and no trimming.
- Use independent invalid fixtures so each dynamic test proves one authoring failure and its full diagnostic.
- Demonstrate the failing invalid-name test before production implementation.

### Integration and Propagation Tests

- Reuse catalog/registrar/visibility/entry/integration tests to prove the exact validated string remains the catalog key, registry key, tool name, child name, plan capability, evidence producer, metric tag, journal route, and trace value.
- Preserve mapped-skill tests proving the public YAML name is independent of `mapping.target_id`.
- Keep sample context/controller tests as the application-level migration guard.

### Static Migration Checks

- Generate the retired set from pre-change invalid manifest `name` declarations and search each exact string across live source/resources.
- Validate current ordinary manifest declarations against the Java contract while excluding only the dedicated rejection fixtures.
- Search `mapping.target_id` declarations and representative `#` target assertions to ensure the internal namespace remains intact.
- Inspect the diff for accidental broad string replacement.

### Manual Testing Steps

1. Review one invalid-name startup message for author usability.
2. Review the migration diff by identity category (manifest, `allowed_skills`, evidence, runtime payload/assertion).
3. Confirm documentation examples use valid names while Java target examples retain `#`.

The dedicated testing artifact created by `ai/commands/3_testing_plan.md` should refine fixture names, exact test method names, the pre-fix failure command, and executable PowerShell audit commands before implementation begins.

## Performance Considerations

The validator adds one precompiled regular-expression match per YAML manifest during startup. It adds no per-invocation or provider-call work and does not allocate translation maps. No performance benchmark is warranted; the full startup/context tests are sufficient regression coverage.

## Migration Notes

- This is a pre-release atomic migration with no compatibility aliases or deprecation period.
- Apply name changes and references in the same implementation branch/change set.
- Keep historical tickets, research, and completed plans unchanged when they describe prior repository state; final static scans should explicitly exclude `ai/thoughts` historical artifacts rather than rewriting history.
- File names may remain kebab-case and do not need to mirror public skill names.
- If an exact lowerCamelCase conversion unexpectedly collides with an already-valid fixture during implementation, stop and choose a descriptive valid replacement rather than adding normalization or collision logic to production code.

## References

- Original ticket: `ai/thoughts/tickets/eng-validate-public-yaml-skill-names.md`
- Prerequisite ticket: `ai/thoughts/tickets/eng-separate-public-skills-from-java-targets.md`
- Prerequisite ticket: `ai/thoughts/tickets/eng-simplify-mapped-yaml-skill-manifests.md`
- Catalog validation boundary: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:72-87`, `YamlSkillCatalog.java:134-172`, `YamlSkillCatalog.java:235-290`
- Diagnostic helpers: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:331-337`, `YamlSkillCatalog.java:854-884`
- Public capability projection: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:44-68`, `YamlSkillCapabilityRegistrar.java:101-116`
- Provider tool publication: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:76-89`
- Catalog tests and fixture behavior: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- Registration/identity tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java`
- Visibility tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java`
- Cross-runtime identity tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java`
- Root authoring guide: `README.md`
- AI authoring model: `ai/skill-authoring/mental-model.md`
- AI authoring coverage matrix: `ai/skill-authoring/README.md`
