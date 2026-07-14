# Validate Public YAML Skill Names Testing Plan

## Change Summary

- Enforce `^[A-Za-z_][A-Za-z0-9_]{0,63}$` on every YAML manifest `name` during catalog loading.
- Preserve the exact parsed name; do not trim, sanitize, normalize, truncate, alias, or translate it.
- Keep missing/blank names on the existing required-field failure path.
- Reject invalid names before mapped-manifest validation, typed deserialization, catalog insertion, registration, or provider tool creation.
- Migrate the starter's dotted fixture identities to lowerCamelCase and update every exact live reference while preserving Java `mapping.target_id` values.
- Prove that a validated name remains unchanged across catalog lookup, capability metadata, provider-facing tool descriptors, nested visibility, entry invocation, plans, evidence, metrics, journals, and traces.
- Verify the already-valid sample names and behavior remain unchanged.

## Impacted Areas

- Catalog parsing, validation ordering, and diagnostics:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
  - `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- Focused YAML boundary fixtures:
  - new `bifrost-spring-boot-starter/src/test/resources/skills/public-name/valid/`
  - new `bifrost-spring-boot-starter/src/test/resources/skills/public-name/invalid/`
- Existing starter fixtures and cross-references:
  - `bifrost-spring-boot-starter/src/test/resources/skills/valid/`
  - `bifrost-spring-boot-starter/src/test/resources/skills/invalid/`
  - `bifrost-spring-boot-starter/src/test/resources/skills/pattern/`
  - Java tests throughout `bifrost-spring-boot-starter/src/test/java/`
- Registration and provider tool identity:
  - `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java`
  - `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/tool/ToolCallbackFactoryTest.java`
- Public entry and nested visibility:
  - `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skillapi/SkillTemplateTest.java`
  - `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java`
  - `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java`
- Plans, evidence, metrics, state, journals, and traces:
  - tests under `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/`
  - tests under `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/`
  - `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java`
- Application-level regression coverage:
  - `bifrost-sample/src/main/resources/skills/`
  - `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleApplicationTests.java`
  - `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleControllerTest.java`
- Documentation assertions are manual/static review targets:
  - `README.md`
  - `ai/skill-authoring/mental-model.md`
  - `ai/skill-authoring/README.md`

## Risk Assessment

### High-Risk Behaviors

- **Validation ordering:** Most existing `invalid/` fixtures currently have dotted names. If they are not renamed, their tests will fail on `name` instead of the schema, mapping, model, linter, or evidence error they were designed to protect.
- **Incomplete atomic rename:** A public identity can appear in YAML, `allowed_skills`, evidence producer keys, registry lookups, `SkillTemplate`, plan tasks, metrics, journals, traces, and serialized JSON. Missing one exact reference can cause startup failure, missing visibility, lookup failure, or misleading expected payloads.
- **Over-broad replacement:** Generic punctuation replacement could corrupt model keys, package/class names, property names, evidence IDs, or internal `beanName#methodName` target IDs.
- **Hidden rewriting:** Trimming or sanitizing would make authoring appear successful while causing the stored/provider identity to differ from source YAML, violating the single-identity contract.
- **Provider boundary gap:** Catalog tests alone do not prove that `CapabilityToolDescriptor` and Spring AI `ToolCallback` retain the exact validated name.
- **Diagnostic regression:** An invalid name could be rejected but omit the exact value, resource, regex/length rule, or remedy required for actionable startup failures.

### Boundary and Edge Cases

- One-character letter and underscore.
- ASCII upper- and lowercase starts.
- Digits permitted after, but not as, the first character.
- Underscores throughout the name.
- Exactly 64 versus 65 characters.
- Dot, dash, embedded space, `#`, slash, and colon.
- Leading and trailing whitespace in quoted YAML values.
- Unicode/non-ASCII letters that Java regex shorthands might otherwise admit.
- Missing and blank names retaining the required-field path.
- Case-sensitive names differing only by case remaining distinct.
- Invalid mapped YAML failing on `name` before `mapping.target_id` or mapped-field applicability.
- Duplicate valid names still reaching the duplicate-name check.
- `mapping.target_id` continuing to accept internal `beanName#methodName` syntax because it is outside this validator.
- Sample `.yml` and `.yaml` resources both remaining loadable.

## Existing Test Coverage

### Current Baseline

The current checkout's catalog suite is green before implementation:

```text
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCatalogTests" test
Tests run: 76, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The baseline was run on 2026-07-14 and completed in approximately 16.6 seconds wall-clock time.

### Coverage to Preserve and Reuse

- `YamlSkillCatalogTests` already covers required fields, duplicate names, deterministic resource order, mapped/LLM classification, mapping validation order, unknown fields, models, schemas, attachments, linters, evidence contracts, and defensive catalog copies.
- `YamlSkillCapabilityRegistrarTests` already covers YAML-to-registry publication, mapped target separation, shared targets, public metadata, inherited input contracts, RBAC, and public-name error boundaries.
- `SkillVisibilityResolverTest` covers exact `allowed_skills` lookup and prevents Java target IDs from becoming visible public children.
- `SkillTemplateTest` covers YAML-only entry lookup and invocation.
- `ExecutionCoordinatorIntegrationTest` carries public names through plan tasks, prompts, execution, and visible child tools.
- `ToolCallbackFactoryTest` carries capability/tool names through callback invocation, evidence production, metrics, journals, and failure paths.
- State/trace tests protect serialized plans, frames, events, projections, NDJSON, and public trace payloads.
- Sample tests protect application context loading, catalog contents, mapped targets, and controller behavior.

### Current Gaps

- No character or length contract is tested for YAML `name`.
- No test distinguishes 64 from 65 characters.
- No test proves leading/trailing whitespace is rejected rather than trimmed.
- No test proves non-ASCII letters are rejected.
- No test asserts the complete actionable diagnostic for a malformed public name.
- The current hash-name evidence fixture explicitly documents temporary unsupported behavior rather than the final contract.
- Existing propagation tests use dotted names but do not frame their assertions as exact validated-name propagation.
- There is no automated repository audit that distinguishes retired public names from unrelated dotted/internal identifiers.

## Bug Reproduction / Failing Test First

- **Name:** `rejectsNonPortablePublicSkillNames`
- **Type:** Catalog integration test using `ApplicationContextRunner`
- **Location:** `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- **Primary fixture:** `bifrost-spring-boot-starter/src/test/resources/skills/public-name/invalid/dot.yaml`
- **Fixture content:** a complete otherwise-valid LLM-backed manifest with `name: mapped.method.skill`, a description, and configured model `gpt-5`.
- **Mocks:** None. Reuse the existing `contextRunner` and `application-test.yml` model catalog.

### Arrange / Act / Assert

1. Configure `bifrost.skills.locations` to load only `classpath:/skills/public-name/invalid/dot.yaml`.
2. Start the `ApplicationContextRunner` context.
3. Assert a startup failure exists.
4. Assert the message contains:
   - `dot.yaml`;
   - field `name`;
   - exact invalid value `mapped.method.skill`;
   - `^[A-Za-z_][A-Za-z0-9_]{0,63}$`;
   - the 1-64/start/allowed-character explanation;
   - a valid example such as `mappedMethodSkill`.

### Expected Failure Before the Fix

The current catalog accepts `mapped.method.skill`. Because the fixture is otherwise valid, the application context starts successfully and the assertion that startup failed does not hold. This is a reliable, low-cost reproduction of the missing authoring boundary without involving a real model provider.

### Red-Test Command

After adding only the fixture and test method, run:

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCatalogTests#rejectsNonPortablePublicSkillNames" test
```

Record the failure before changing `YamlSkillCatalog`. Do not run the full catalog suite between enabling the validator and migrating the ordinary dotted fixtures; those expected secondary failures are migration noise, not additional bug reproductions.

## Tests to Add or Update

### 1. `acceptsProviderPortablePublicSkillNames`

- **Type:** Catalog integration / dynamic test factory.
- **Location:** `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`.
- **What it proves:** Every supported boundary/style loads and is stored under the exact unmodified YAML value.
- **Fixtures/data:** Valid resources under `skills/public-name/valid/` for:
  - `A`;
  - `_`;
  - `expenseLookup`;
  - `expense_lookup`;
  - `_internalStyleAllowed`;
  - `Skill2`;
  - two names differing only by case, such as `CaseName` and `caseName`;
  - an ASCII name of exactly 64 characters.
- **Assertions:** Context starts, catalog contains each exact key, returned `manifest().getName()` equals the fixture value, both case variants coexist, and no normalized alias key appears.
- **Mocks:** None; reuse `ApplicationContextRunner` and configured `gpt-5`.

### 2. `rejectsNonPortablePublicSkillNames`

- **Type:** Catalog integration / dynamic test factory.
- **Location:** `YamlSkillCatalogTests.java`.
- **What it proves:** The catalog rejects the full invalid matrix at startup with actionable, stable diagnostics.
- **Fixtures/data:** One independently loaded resource per case under `skills/public-name/invalid/`:

| Fixture | Exact YAML value | Rule exercised |
| --- | --- | --- |
| `leading-digit.yaml` | `2expenseLookup` | first character |
| `dot.yaml` | `mapped.method.skill` | dot punctuation / red test |
| `dash.yaml` | `expense-lookup` | dash punctuation |
| `space.yaml` | `"expense lookup"` | embedded whitespace |
| `leading-space.yaml` | `" expenseLookup"` | no trim-to-valid |
| `trailing-space.yaml` | `"expenseLookup "` | no trim-to-valid |
| `hash.yaml` | `"expenseService#getLatestExpenses"` | public/internal namespace distinction |
| `slash.yaml` | `"expense/lookup"` | slash punctuation |
| `colon.yaml` | `"expense:lookup"` | colon punctuation |
| `unicode.yaml` | `expénsèLookup` | ASCII-only rule |
| `too-long.yaml` | 65 ASCII characters | maximum length |

- **Assertions:** Every failure contains its resource, field `name`, exact parsed value, regex, length/character explanation, and valid example. The assertion must not require provider startup or inspect only a nested root cause that could hide the author-facing message.
- **Mocks:** None.
- **Fixture caution:** Quote values containing `#`, leading/trailing spaces, colon, or other YAML-sensitive text so the parser receives the intended exact Java string.

### 3. `keepsMissingAndBlankNamesOnRequiredFieldPath`

- **Type:** Catalog integration / dynamic test factory.
- **Location:** `YamlSkillCatalogTests.java`.
- **What it proves:** The new format helper runs only after the existing nonblank requirement.
- **Fixtures/data:** `missing.yaml` with no `name`, and `blank.yaml` with `name: " "`.
- **Assertions:** Startup failure contains resource, field `name`, and `required field is missing or blank`; it does not claim the value merely failed the public-name regex.
- **Mocks:** None.

### 4. `validatesPublicNameBeforeMappedManifestFields`

- **Type:** Catalog integration.
- **Location:** `YamlSkillCatalogTests.java`.
- **What it proves:** Both mapped and LLM-backed manifests share the same earliest public-name boundary.
- **Fixtures/data:** An invalid mapped resource such as:

```yaml
name: mapped.method.skill
description: Invalid name must win
mapping: null
```

- **Assertions:** Startup fails for field `name` with the portable rule and does not report field `mapping.target_id`.
- **Mocks:** None; no target bean is required because the failure must precede mapping resolution.

### 5. `failsStartupWhenYamlSkillsShareDuplicatePortableName` (update existing duplicate test)

- **Type:** Catalog integration regression.
- **Location:** `YamlSkillCatalogTests.java` and the two existing duplicate-name fixtures.
- **What it proves:** Introducing format validation does not remove or reorder duplicate detection for two individually valid names.
- **Fixtures/data:** Rename both declarations from `duplicate.skill` to the same valid value `duplicateSkill`.
- **Assertions:** Startup failure points to the second deterministic resource and contains `duplicate skill name 'duplicateSkill'` for field `name`.
- **Mocks:** None.

### 6. `publishesExactValidatedYamlName` (strengthen registrar coverage)

- **Type:** Spring application-context integration.
- **Location:** Prefer strengthening `mapsDeterministicYamlSkillToDiscoveredSkillMethodTarget` in `YamlSkillCapabilityRegistrarTests.java`; add a separate method only if clearer.
- **What it proves:** The migrated manifest name, catalog key, `CapabilityMetadata.name()`, and `CapabilityToolDescriptor.name()` are identical; `mappedTargetId()` remains `targetBean#deterministicTarget`.
- **Fixtures/data:** Migrated `mapped-method-skill.yaml` with `name: mappedMethodSkill`.
- **Assertions:** Registry lookup by `mappedMethodSkill` succeeds, metadata and tool descriptor both expose `mappedMethodSkill`, lookup by the retired dotted value is absent, and target ID remains unchanged.
- **Mocks:** Existing application context and target bean only.

### 7. `createsCallbackWithExactValidatedPublicName` (strengthen callback coverage)

- **Type:** Unit/integration at Spring AI callback boundary.
- **Location:** `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/tool/ToolCallbackFactoryTest.java`.
- **What it proves:** The callback definition published to a model provider uses the exact `CapabilityToolDescriptor` name, with no hidden translation.
- **Fixtures/data:** Use a provider-safe mixed-case or underscore name already projected by the migrated capability fixture.
- **Assertions:** Callback/tool definition name equals the exact capability name; invocation, evidence producer lookup, metric tag, and journal/trace assertions use the same value.
- **Mocks:** Reuse the existing mocked execution collaborators; no external provider call.

### 8. Migrate and retain `allowed_skills` visibility coverage

- **Type:** Catalog and visibility integration regression.
- **Locations:**
  - `skills/valid/allowed-skills-root.yaml`
  - `skills/valid/allowed-child-skill.yaml`
  - `skills/valid/disallowed-child-skill.yaml`
  - `YamlSkillCatalogTests.java`
  - `SkillVisibilityResolverTest.java`
  - `ExecutionCoordinatorIntegrationTest.java`
- **What it proves:** Exact valid names resolve through YAML `allowed_skills`, RBAC filtering, ready-task prompts, plan targets, and nested tool surfaces.
- **Fixtures/data:** Migrate `root.visible.skill`, `allowed.visible.skill`, and `disallowed.visible.skill` consistently to lowerCamelCase; preserve `targetBean#deterministicTarget` as a deliberately non-public allowlist entry in the separation test.
- **Assertions:** Only the valid, authorized child becomes visible; target IDs remain unavailable as public children; plan/prompt strings carry the exact migrated child name.
- **Mocks:** Existing test doubles only.

### 9. Replace the temporary hash public-name evidence test

- **Type:** Catalog integration regression.
- **Location:** `YamlSkillCatalogTests.java` and `skills/valid/evidence-contract-hash-public-name-skill.yaml` (the filename may remain unchanged).
- **What it proves:** Evidence tool producer keys use the exact valid public skill name, while evidence IDs remain outside the public-name validator.
- **Fixtures/data:** Migrate the manifest to a valid name and replace `review#skill` with a valid public name such as `reviewSkill`; keep `review_result` unchanged.
- **Assertions:** Catalog loads and `evidenceProducedByTool("reviewSkill")` returns `review_result`. The test name must describe final supported behavior rather than “until validation is implemented.”
- **Mocks:** None.

### 10. Update all existing invalid-fixture tests without weakening them

- **Type:** Catalog integration regression.
- **Location:** Existing YAML under `skills/invalid/` and corresponding assertions in `YamlSkillCatalogTests.java` and `YamlSkillCapabilityRegistrarTests.java`.
- **What it proves:** Each existing fixture still reaches and reports its original invalid field after its public name becomes valid.
- **Fixtures/data:** Exact lowerCamelCase replacements derived from the pre-change name inventory.
- **Assertions:** Preserve every original field/detail/remedy assertion; change only the expected public identity. Do not replace specific assertions with generic “context failed” checks.
- **Mocks:** Existing configuration only.

### 11. Update plans, state, evidence, metrics, journals, and trace expectations atomically

- **Type:** Unit and integration regression across existing suites.
- **Locations:** Existing tests under `core/`, `runtime/`, and `skill/MissionExecutionEngineTest.java` that contain one of the retired manifest names.
- **What it proves:** The migrated public identity is unchanged across every runtime representation.
- **Fixtures/data:** Update exact public-name strings in `ExecutionPlan`, `PlanTask`, frame routes, ready tasks, evidence producer keys, metrics, tool events, journal payloads, projections, trace contracts, and NDJSON.
- **Assertions:** Preserve structural and behavioral assertions. Where one payload contains a public name in several fields, assert the same migrated value consistently in all fields.
- **Mocks:** Preserve current mocks; no new provider or persistence infrastructure.

### 12. Preserve sample names and application behavior

- **Type:** Spring Boot application context/controller integration.
- **Locations:** `SampleApplicationTests.java` and `SampleControllerTest.java`.
- **What it proves:** The sample's five already-valid names remain unchanged, catalog registration succeeds under the validator, mapped targets still resolve, and endpoints continue to invoke the same skills.
- **Fixtures/data:** Existing sample `.yml`/`.yaml` manifests; no production sample rename.
- **Assertions:** Catalog names contain exactly the expected sample set; controller tests retain existing invocation expectations.
- **Mocks:** Existing sample test model/client mocks.

## Static Migration Audits

Run these from `C:\opendev\code\bifrost` after the migration. The commands are intentionally scoped to public manifest declarations and exact retired names; they do not treat every dotted string as a skill name.

### 1. Validate Every Ordinary Manifest Name

Exclude only the dedicated rejection fixtures. Fail if an ordinary starter or sample manifest has no `name` line or has a nonmatching parsed scalar. The implementation may package this as an equivalent temporary shell snippet, but it must not leave a generated audit file in the repository.

```powershell
$pattern = '^[A-Za-z_][A-Za-z0-9_]{0,63}$'
$roots = @(
  'bifrost-spring-boot-starter/src/test/resources/skills',
  'bifrost-sample/src/main/resources/skills'
)
$violations = foreach ($file in Get-ChildItem $roots -Recurse -File -Include *.yaml,*.yml) {
  if ($file.FullName -like '*\skills\public-name\invalid\*') { continue }
  $match = Select-String -Path $file.FullName -Pattern '^name:\s*(.+?)\s*$' | Select-Object -First 1
  if (-not $match) {
    [pscustomobject]@{ File = $file.FullName; Name = '<missing>' }
    continue
  }
  $name = $match.Matches[0].Groups[1].Value.Trim('"', "'")
  if ($name -cnotmatch $pattern) {
    [pscustomobject]@{ File = $file.FullName; Name = $name }
  }
}
if ($violations) {
  $violations | Format-Table -AutoSize
  throw 'Ordinary YAML skill manifests contain invalid public names.'
}
```

### 2. Prove Retired Manifest Names Are Gone from Live Source

Derive the retired set from `HEAD`, before the working-tree migration, then exact-search live source/resources. Exclude historical `ai/thoughts`, build output, and the dedicated rejection fixtures.

```powershell
$pattern = '^[A-Za-z_][A-Za-z0-9_]{0,63}$'
$retired = git grep -h '^name:' HEAD -- `
  ':(glob)bifrost-spring-boot-starter/src/test/resources/skills/**/*.yaml' `
  ':(glob)bifrost-spring-boot-starter/src/test/resources/skills/**/*.yml' |
  ForEach-Object { ($_ -replace '^name:\s*', '').Trim('"', "'") } |
  Where-Object { $_ -cnotmatch $pattern } |
  Sort-Object -Unique
$remaining = foreach ($name in $retired) {
  $matches = rg -l -F `
    --glob '!ai/thoughts/**' `
    --glob '!**/target/**' `
    --glob '!bifrost-spring-boot-starter/src/test/resources/skills/public-name/invalid/**' `
    -- $name .
  if ($matches) {
    [pscustomobject]@{ Name = $name; Files = ($matches -join ', ') }
  }
}
if ($remaining) {
  $remaining | Format-Table -Wrap
  throw 'Retired public skill names remain in live source/resources.'
}
```

### 3. Preserve Manifest Target IDs

Compare the multiset of YAML `mapping.target_id` declarations before and after the migration:

```powershell
$paths = @(
  'bifrost-spring-boot-starter/src/test/resources',
  'bifrost-sample/src/main/resources'
)
$before = git grep -h '^\s*target_id:' HEAD -- `
  ':(glob)bifrost-spring-boot-starter/src/test/resources/**/*.yaml' `
  ':(glob)bifrost-spring-boot-starter/src/test/resources/**/*.yml' `
  ':(glob)bifrost-sample/src/main/resources/**/*.yaml' `
  ':(glob)bifrost-sample/src/main/resources/**/*.yml' | Sort-Object
$after = rg --no-filename --glob '*.yaml' --glob '*.yml' '^\s*target_id:' $paths | Sort-Object
$difference = Compare-Object $before $after
if ($difference) {
  $difference | Format-Table
  throw 'mapping.target_id declarations changed during the public-name migration.'
}
```

### 4. Preserve the Exact Sample Name Set

```powershell
$expected = @(
  'duplicateInvoiceChecker',
  'expenseLookup',
  'feedstockTicketParser',
  'feedstockTicketParserBySkill',
  'invoiceParser'
) | Sort-Object
$actual = Get-ChildItem 'bifrost-sample/src/main/resources/skills' -Recurse -File -Include *.yaml,*.yml |
  ForEach-Object {
    $match = Select-String -Path $_.FullName -Pattern '^name:\s*(.+?)\s*$' | Select-Object -First 1
    $match.Matches[0].Groups[1].Value.Trim('"', "'")
  } | Sort-Object
$difference = Compare-Object $expected $actual
if ($difference) {
  $difference | Format-Table
  throw 'Sample public skill names changed unexpectedly.'
}
```

## How to Run

### Prerequisites

- Run from repository root `C:\opendev\code\bifrost`.
- Java 21 or newer and Maven 3.9 or newer; use the checked-in Windows wrapper `mvnw.cmd`.
- No provider credentials, live model, database, network service, Spring profile, or environment variables are required for these tests.
- The existing `bifrost-spring-boot-starter/src/test/resources/application-test.yml` supplies model catalog entries to `ApplicationContextRunner`.

### 1. Record the Pre-Fix Failure

Add only the initial invalid fixture and red test, then run:

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCatalogTests#rejectsNonPortablePublicSkillNames" test
```

Expected: test failure because the current context starts successfully.

### 2. Run Focused Public-Name Tests After the Validator

Use exact method names selected during implementation. With the names proposed above:

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCatalogTests#acceptsProviderPortablePublicSkillNames+rejectsNonPortablePublicSkillNames+keepsMissingAndBlankNamesOnRequiredFieldPath+validatesPublicNameBeforeMappedManifestFields" test
```

Expected: all focused boundary tests pass even before the broad fixture migration is complete.

### 3. Run the Catalog Suite After Fixture Migration

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCatalogTests" test
```

Expected: all new and existing catalog cases pass, proving existing invalid fixtures reach their intended validation paths.

### 4. Run Focused Identity and Runtime Suites

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCapabilityRegistrarTests,SkillVisibilityResolverTest,SkillTemplateTest,ExecutionCoordinatorIntegrationTest,ToolCallbackFactoryTest" test
```

Expected: registration, nested visibility, entry lookup, plans, evidence, metrics, journals, traces, and provider callback names all use the exact migrated identities.

### 5. Run Static Audits

Run all four PowerShell audits in the previous section. Expected: no output other than normal command completion and no thrown audit errors.

### 6. Run the Full Starter Suite from a Clean Module Build

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter clean test
```

Expected: starter compilation and all starter tests pass with freshly copied test resources.

### 7. Run Sample Tests with the Updated Starter

```powershell
.\mvnw.cmd -pl bifrost-sample -am "-DskipTests=false" test
```

Expected: starter and sample tests pass; the sample catalog retains the exact five expected names.

### 8. Run the Full Reactor and Diff Checks

```powershell
.\mvnw.cmd test
git diff --check
git status --short
```

Expected: full reactor succeeds, diff check emits nothing, and status contains only intended implementation, fixture, test, documentation, and plan changes.

## Exit Criteria

- [x] The proposed red test is added before production code and fails because `mapped.method.skill` is currently accepted.
- [x] The focused valid matrix covers one-character boundaries, camelCase, underscores, post-first digits, case sensitivity, and exactly 64 characters.
- [x] The focused invalid matrix covers leading digit, dot, dash, whitespace, `#`, slash, colon, non-ASCII, and 65 characters.
- [x] Missing/blank names remain on the existing required-field path.
- [x] An invalid mapped manifest fails on `name` before mapped-specific validation.
- [x] Every invalid-name error includes resource, field, exact value, regex/length rule, and valid remedy/example.
- [x] Tests prove no trimming, sanitization, normalization, truncation, aliasing, or case folding.
- [x] Duplicate valid public names still fail through duplicate detection.
- [x] Every ordinary starter/sample manifest name is valid; only dedicated rejection fixtures intentionally violate the pattern.
- [x] All 82 retired invalid manifest names are absent from live source/resources outside historical `ai/thoughts` and dedicated rejection fixtures.
- [x] Existing invalid fixtures still exercise and assert their original validation errors.
- [x] Exact-name propagation is covered through catalog, registry metadata, tool descriptor/callback, `SkillTemplate`, `allowed_skills`, plan/evidence/metrics/journal/trace representations.
- [x] `mapping.target_id` declarations and representative `beanName#methodName` assertions remain unchanged and mapped tests pass.
- [x] The five sample public names remain unchanged and sample tests pass.
- [x] Full clean starter suite passes.
- [x] Full Maven reactor passes.
- [x] All static migration audits pass.
- [x] `git diff --check` passes.
- [x] Manual diff review confirms no unrelated identifier or behavior was rewritten.

## Manual Verification

- Inspect one captured invalid-name startup message as an author would see it; confirm it is concise, identifies the source and bad value, and explains the remedy without requiring a provider error.
- Review the migration diff by exact identity rather than file count. Confirm changes are confined to YAML public names and their propagated test/payload references.
- Spot-check several `mapping.target_id` values containing `#`, including a valid mapped target and the unknown-target fixture, to confirm the internal namespace is untouched.
- Read the root README and AI authoring guidance together. Confirm the regex is enforced, lowerCamelCase is only recommended style, and internal target IDs are explicitly separate.

## References

- Implementation plan: `ai/thoughts/plans/2026-07-14-validate-public-yaml-skill-names.md`
- Original ticket: `ai/thoughts/tickets/eng-validate-public-yaml-skill-names.md`
- Test planning command: `ai/commands/3_testing_plan.md`
- Catalog implementation: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
- Primary catalog tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- Registration tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java`
- Provider callback tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/tool/ToolCallbackFactoryTest.java`
- Visibility tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java`
- Entry API tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skillapi/SkillTemplateTest.java`
- Cross-runtime integration tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java`
