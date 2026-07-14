# Simplify Mapped YAML Skill Manifests Testing Plan

## Change Summary

- Classify YAML manifests as LLM-backed or mapped before model/runtime validation.
- Permit mapped manifests to declare only `name`, `description`, optional `rbac_roles`, and a nonblank `mapping.target_id`.
- Reject every other mapped model/runtime field by declaration presence, including explicit null, blank, false, zero, and empty values.
- Preserve declaration presence through manifest normalization and `YamlSkillDefinition` defensive copying.
- Require execution configuration for LLM-backed definitions, omit it for mapped definitions, and publish mapped metadata with `SkillExecutionDescriptor.none()`.
- Remove duplicate mapped `input_schema` compatibility; mapped input and output behavior is Java-owned.
- Discover and invoke mapped YAML when no framework models or provider `ChatModel` are configured.
- Keep the already-completed destructive `ModelPreference` removal in the same PR and continue verifying that no compatibility API remains.

## Impacted Areas

- Manifest parsing and declaration tracking: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`
- Execution-kind/configuration invariant and defensive copying: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java`
- Discovery, classification, validation ordering, and diagnostics: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
- Execution metadata, mapped target resolution, and inherited contracts: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java`
- Duplicate mapped-schema compatibility: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputContractResolver.java`
- Model-backed consumers of `YamlSkillDefinition.executionConfiguration()` in chat, mission, planning, step-loop, and attachment packages
- Model-free auto-configuration/runtime path: `BifrostAutoConfiguration`, `CapabilityExecutionRouter`, and `SkillTemplate`
- Mapped valid/invalid fixtures under `bifrost-spring-boot-starter/src/test/resources/skills/`
- Mapped sample manifests and sample context tests
- `ModelPreference`-affected annotation, target, metadata, processor, registrar, and test construction paths already changed in the working tree

## Risk Assessment

### High Risk

- **False declaration flags after defensive copying.** Jackson `convertValue` can call setters for serialized null/default properties and make omitted fields appear declared unless the source declaration set is restored explicitly.
- **Incorrect validation precedence.** A mapped resource could report unknown model, malformed schema/linter/evidence, retry range, or unknown target instead of the contradictory mapped field.
- **Typed binding preempting applicability.** An inapplicable mapped field with an unbindable value could throw a Jackson type error before execution-kind validation unless raw declaration presence is inspected first.
- **A false-positive model-free test.** Existing test runners import `application-test.yml`, which supplies a model catalog. The model-free runner must not use that initializer or register a provider `ChatModel`.
- **Nullable execution configuration escaping its boundary.** Model-backed consumers must fail at an explicit invariant method rather than encounter a later null dereference.
- **Contract regression while deleting compatibility code.** Removing mapped schema comparison/marker merging must not weaken pure LLM `input_schema` support or Java-reflected runtime markers.

### Medium Risk

- Blank, null, and incomplete mappings could still collapse into the LLM branch.
- Mapped diagnostics may omit either the public skill name or resource identity.
- Valid mapped RBAC, shared-target, proxy, lazy/prototype, exception, runtime-ref, evidence, and tracing behavior could regress while fixtures are rewritten.
- Parent-owned `allowed_skills` and `evidence_contract.tool_evidence` might be rejected accidentally because the child field rule is applied too broadly.
- Stale mapped sample/fixture fields could prevent application contexts from loading after validation becomes strict.

### Low Risk

- Resource discovery with no configured locations/models remains a startup-only cost.
- `ModelPreference` removal is already implemented and verified; remaining risk is accidental reintroduction or an unupdated API assertion.

## Existing Test Coverage

- `YamlSkillCatalogTests` covers model/thinking resolution, prompt handling, schemas, retry validation, linter/evidence validation, implementation-type projection, malformed/unknown fields, resource discovery, and defensive copies.
- `YamlSkillCapabilityRegistrarTests` covers mapped registration, current fabricated model metadata, inherited Java contracts, shared targets/RBAC, same-name wrappers, proxy/lazy/prototype targets, exception behavior, and unknown targets.
- `SkillInputContractResolverTest` covers explicit YAML resolution and mapped structural compatibility; compatibility-specific coverage must be removed with the production path.
- `BifrostAutoConfigurationTests` covers bean creation, configured model catalogs, LLM execution descriptors, target/public registry separation, and the application-context seams needed for model-free invocation.
- `CapabilityExecutionRouterTest` covers LLM versus direct mapped routing, parent state/evidence preservation, RBAC, input validation, and runtime-ref binding; its root mapped fixture already uses `SkillExecutionDescriptor.none()`.
- `SkillTemplateTest` covers YAML-only public invocation and entry input validation.
- `SampleApplicationTests` loads the real sample catalog and asserts mapped public capabilities exist; `SampleControllerTest` verifies endpoint delegation through YAML names.
- `SkillMethodTest`, `SkillImplementationTargetTest`, and `CapabilityMetadataTest`, plus the full starter suite, already cover the changed surfaces after `ModelPreference` removal.

### Current Gaps

- No test discovers a mapped skill when the configured model catalog is empty.
- No context test invokes a mapped skill without both framework models and a provider `ChatModel`.
- No test distinguishes omitted root fields from explicit null/blank/false/zero/empty declarations after defensive copying.
- Blank or incomplete mappings are currently normalized into the LLM-backed path.
- Only truthy/nonblank mapped `prompt` has an applicability test; the other ten forbidden fields and falsey declarations are uncovered.
- No multi-error fixture locks mapped validation precedence.
- Mapped public metadata is currently expected to contain a configured model descriptor.
- Existing mapped-schema tests establish compatibility rather than Java-only ownership.
- No focused negative test proves an LLM resource still fails when YAML discovery runs against an empty model catalog.

## Bug Reproduction / Failing Test First

- **Name**: `discoversMappedSkillWhenModelCatalogIsEmpty`
- **Type**: integration-style unit test using `ApplicationContextRunner`
- **Location**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- **Fixture**: new `bifrost-spring-boot-starter/src/test/resources/skills/valid/model-free-mapped-skill.yaml` containing only:

  ```yaml
  name: model.free.mapped.skill
  description: Mapped skill that requires no framework model.
  mapping:
    target_id: targetBean#deterministicTarget
  ```

- **Arrange**: Create a second `ApplicationContextRunner` with `ConfigurationPropertiesAutoConfiguration` and `BifrostAutoConfiguration`, but without the initializer that loads `application-test.yml`. Add `TargetBeanConfiguration` and point `bifrost.skills.locations` at the new fixture.
- **Act**: Start the context and read `YamlSkillCatalog`.
- **Assert**: The context starts, `catalog.getSkill("model.free.mapped.skill")` is present, the definition is `MAPPED_JAVA`, and its execution configuration is absent.
- **Expected failure before the fix**: The context starts, but `YamlSkillCatalog.afterPropertiesSet()` returns before discovery because the model catalog is empty, so the skill assertion fails as null. This is deterministic and isolates the earliest incorrect behavior without requiring model or runtime mocks.
- **Red/green discipline**: Commit or otherwise record this test failing before removing the catalog early return. Do not begin with the broader runtime test because a context failure could obscure the discovery defect.

## Test Fixtures and Data Matrix

Keep valid mapped fixtures minimal. Except for intentional invalid cases, they may contain only `name`, `description`, optional `rbac_roles`, and `mapping.target_id`.

### Malformed Mapping Fixtures

| Fixture | Declaration | Expected field |
| --- | --- | --- |
| `mapped-null-mapping.yaml` | `mapping: null` | `mapping.target_id` |
| `mapped-empty-mapping.yaml` | `mapping: {}` | `mapping.target_id` |
| `mapped-non-object-mapping.yaml` | `mapping: []` | `mapping.target_id` |
| `mapped-non-string-target.yaml` | `mapping: { target_id: 123 }` | `mapping.target_id` |
| `mapped-null-target.yaml` | `mapping: { target_id: null }` | `mapping.target_id` |
| `mapped-blank-target.yaml` | `mapping: { target_id: " " }` | `mapping.target_id` |

Each error must include the public skill name, resource filename, the field, and the remedy that a declared mapping requires a nonblank target.

### Inapplicable Mapped Field Fixtures

| Fixture | Deliberate declaration | Presence edge case | Expected explanation category |
| --- | --- | --- | --- |
| `mapped-with-model-null.yaml` | `model: null` | explicit null | no model executes |
| `mapped-with-thinking-level-blank.yaml` | `thinking_level: " "` | blank | no model executes |
| `mapped-with-prompt-null.yaml` | `prompt: null` | explicit null after prompt normalization | no model executes |
| `mapped-with-input-schema-null.yaml` | `input_schema: null` | explicit null | Java reflected input ownership |
| `mapped-with-output-schema-null.yaml` | `output_schema: null` | explicit null | Java return ownership |
| `mapped-with-planning-mode-false.yaml` | `planning_mode: false` | false | no model/planning loop executes |
| `mapped-with-planning-mode-object.yaml` | `planning_mode: {}` | unbindable object | no model/planning loop executes before typed binding |
| `mapped-with-max-steps-zero.yaml` | `max_steps: 0` | zero | no model/planning loop executes |
| `mapped-with-max-steps-text.yaml` | `max_steps: nope` | unbindable string | no model/planning loop executes before typed binding |
| `mapped-with-allowed-skills-empty.yaml` | `allowed_skills: []` | empty collection | child selection belongs to LLM parent |
| `mapped-with-linter-null.yaml` | `linter: null` | explicit null | model-output validation does not run |
| `mapped-with-output-schema-retries-zero.yaml` | `output_schema_max_retries: 0` | zero | model-output retry does not run |
| `mapped-with-evidence-contract-null.yaml` | `evidence_contract: null` | explicit null | child evidence contract is not evaluated |

All fixtures use a valid `mapping.target_id` and omit every other forbidden field. The field-specific assertion must check the public name, filename, field, reason, and remedy so a generic missing-model failure cannot satisfy the test accidentally.

### Validation-Order Fixture

Add `mapped-with-multiple-inapplicable-fields.yaml` with a valid public identity and target plus several contradictory fields in a YAML order different from the required validation order—for example `evidence_contract`, malformed `output_schema`, `planning_mode`, and unknown `model`. Assert `model` is the first reported field. Also assert target lookup is not attempted by using a missing target ID or a mocked target registry and verifying no lookup; this proves applicability validation precedes Java-target resolution.

## Tests to Add or Update

### 1. `discoversMappedSkillWhenModelCatalogIsEmpty`

- **Type**: integration-style unit / failing-first
- **Location**: `YamlSkillCatalogTests`
- **What it proves**: YAML discovery is independent of model-catalog population and mapped definitions have no execution configuration.
- **Fixtures/data**: `model-free-mapped-skill.yaml`; clean model-free runner; real target test configuration.
- **Mocks**: None.

### 2. `rejectsLlmSkillWhenModelCatalogIsEmpty`

- **Type**: integration-style unit
- **Location**: `YamlSkillCatalogTests`
- **What it proves**: Removing the early return does not silently accept LLM YAML without a resolvable model.
- **Fixtures/data**: one LLM fixture omitting `model`, plus an existing LLM fixture naming `gpt-5` against the empty model catalog if both missing and unknown cases are retained.
- **Mocks**: None.
- **Assertions**: Startup fails at `model`, includes public skill/resource identity, and distinguishes missing/blank from unknown.

### 3. `rejectsDeclaredMappingWithoutNonBlankTarget`

- **Type**: parameterized integration-style unit
- **Location**: `YamlSkillCatalogTests`
- **What it proves**: Declaration of `mapping` controls classification and null/empty/blank forms never collapse into LLM-backed validation.
- **Fixtures/data**: all malformed mapping fixtures from the matrix, including non-object mapping and non-string target forms.
- **Mocks**: None; target lookup must not be reached.

### 4. `rejectsInapplicableMappedFieldByDeclarationPresence`

- **Type**: parameterized integration-style unit
- **Location**: `YamlSkillCatalogTests`
- **What it proves**: All eleven fields are rejected by occurrence, including normalized falsey values, with the required field-specific diagnostic.
- **Fixtures/data**: the eleven field categories plus unbindable-value variants and expected explanation fragments from the matrix.
- **Mocks**: None; use the real target configuration only if context construction requires it after catalog validation.
- **Implementation note**: If JUnit parameterized tests are not already available through the test starter, use one named test method per fixture rather than adding a dependency solely for this matrix.

### 5. `reportsMappedApplicabilityErrorsInStableOrderBeforeContentAndTargetValidation`

- **Type**: integration-style unit
- **Location**: `YamlSkillCatalogTests`
- **What it proves**: Stable field order is implementation-defined rather than YAML order, malformed nested content is not evaluated first, and target resolution is not reached.
- **Fixtures/data**: `mapped-with-multiple-inapplicable-fields.yaml` with reordered fields, malformed nested data, and a missing target.
- **Mocks**: Optional mocked `SkillImplementationTargetRegistry` only to verify zero interactions; the field assertion is required even without the mock.
- **Order lock**: Assert the complete eleven-field applicability order so reordering after `model` also fails coverage.

### 6. `preservesDeclaredFieldsAcrossDefinitionDefensiveCopies`

- **Type**: unit
- **Location**: new `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillDefinitionTest.java`
- **What it proves**: An omitted field remains omitted and explicitly null/blank/false/zero/empty fields remain declared after constructor copying and `manifest()` copying.
- **Fixtures/data**: Programmatic manifests using setters plus a manifest with untouched defaults. Include `allowed_skills: []` semantics and explicit `mapping` presence.
- **Mocks**: A `ByteArrayResource` or mocked `Resource`; no Spring context.
- **Assertions**: Declaration sets are equal before/after copies, returned copies cannot mutate stored state, and serialization metadata itself is not exposed as a YAML field.

### 7. `enforcesExecutionConfigurationInvariantByImplementationType`

- **Type**: unit
- **Location**: `YamlSkillDefinitionTest`
- **What it proves**: LLM definitions require configuration, malformed mapped definitions without a nonblank target fail construction, mapped definitions reject configuration, valid mapped definitions accept absence, and valid LLM definitions return the exact value from `requireExecutionConfiguration()`.
- **Fixtures/data**: Programmatic omitted/declared mapping manifests and a real `EffectiveSkillExecutionConfiguration`.
- **Mocks**: Resource only.
- **Assertions**: Invalid combinations fail with invariant-oriented messages rather than author-facing catalog messages.

### 8. `mappedYamlSkillPublishesNoExecutionConfigurationAndInheritsJavaContract`

- **Type**: integration-style unit; update the existing mapped registration test
- **Location**: `YamlSkillCapabilityRegistrarTests`
- **What it proves**: Mapped metadata uses `SkillExecutionDescriptor.none()`, retains target ID/public metadata/RBAC, publishes `YAML_INHERITED`, and validates/invokes with the Java-reflected schema.
- **Fixtures/data**: cleaned `mapped-method-skill.yaml` with no model or other rejected fields.
- **Mocks**: Existing real application context and target bean.
- **Assertions**: `configured()` is false and all descriptor components are null; input requires the Java `input` parameter; invocation returns the deterministic result.

### 9. `llmYamlSkillStillPublishesConfiguredExecutionAndExplicitInputContract`

- **Type**: integration regression
- **Location**: update/retain `BifrostAutoConfigurationTests#registersYamlCapabilityMetadataWithEffectiveExecutionDescriptor` and `YamlSkillCapabilityRegistrarTests#explicitYamlInputSchemaPublishesConcreteToolSchemaForUnmappedSkill`
- **What it proves**: Conditional mapped logic does not weaken LLM model resolution or explicit/generic YAML input contracts.
- **Fixtures/data**: existing `default-thinking-skill.yaml` and explicit LLM schema fixture.
- **Mocks**: Existing context setup.

### 10. `rejectsMappedInputSchemaInsteadOfComparingItWithJava`

- **Type**: integration-style unit
- **Location**: `YamlSkillCatalogTests`
- **What it proves**: Any mapped `input_schema`, even null or structurally identical, fails at applicability before compatibility/content validation.
- **Fixtures/data**: repurpose the compatible and mismatch fixtures or replace them with one minimal null case plus one malformed/otherwise-compatible concrete schema case.
- **Mocks**: None.
- **Cleanup assertion**: Remove compatibility-only tests from `SkillInputContractResolverTest`; production search for `validateStructuralCompatibility` and mapped marker merging has no remaining caller unless explicitly justified.

### 11. `invokesMappedSkillWithoutModelsOrChatModel`

- **Type**: application-context integration
- **Location**: `BifrostAutoConfigurationTests`
- **What it proves**: The complete public path works with no `bifrost.models`, provider `ChatModel`, or model coordinator requirement.
- **Fixtures/data**: `model-free-mapped-skill.yaml`; clean runner without the `application-test.yml` initializer; real `MappedSkillTargetConfiguration`.
- **Mocks**: None.
- **Assertions**: Context has the catalog, target registry, public registry, router, and `SkillTemplate`; no provider `ChatModel` bean is present; mapped metadata is unconfigured; `SkillTemplate.invoke("model.free.mapped.skill", Map.of("input", "alpha"))` returns the serialized deterministic result.

### 12. `parentLlmGovernanceCanReferenceMappedChild`

- **Type**: integration regression
- **Location**: retain/update existing catalog/registrar/router visibility and evidence tests
- **What it proves**: The forbidden child declarations do not prohibit parent-owned `allowed_skills` or `evidence_contract.tool_evidence` entries naming a mapped child.
- **Fixtures/data**: existing LLM parent evidence/allowlist fixture plus cleaned mapped child fixture.
- **Mocks**: Existing router/session mocks where already used.
- **Assertions**: Parent loads, mapped child remains visible subject to RBAC, and successful child execution preserves parent evidence attribution.

### 13. `mappedRuntimeBehaviorRemainsUnchanged`

- **Type**: regression suite, not one new omnibus test
- **Location**: existing `YamlSkillCapabilityRegistrarTests`, `CapabilityExecutionRouterTest`, and `SkillTemplateTest`
- **What it proves**: Reflected root/nested validation, argument binding, runtime refs, RBAC, proxy/lazy/prototype target lookup, shared wrappers, transformed exceptions, and public-name tracing remain intact.
- **Fixtures/data**: Clean existing mapped fixtures by removing model/runtime fields unrelated to their purpose.
- **Mocks**: Preserve existing targeted mocks; do not mock catalog parsing or target contract generation.

### 14. `sampleMappedSkillsUseStrictWrapperManifestAndLoadSuccessfully`

- **Type**: sample application integration
- **Location**: update `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleApplicationTests.java`
- **What it proves**: `expenseLookup` and `feedstockTicketParser` load after removal of `model` and `planning_mode: false`, remain mapped to their Java targets, use unconfigured execution descriptors, and preserve Java-owned contracts.
- **Fixtures/data**: real sample YAML and Spring context.
- **Mocks**: Existing sample test profile/mocks only; do not invoke the external feedstock API in context tests.
- **Related regression**: Retain `SampleControllerTest` delegation assertions for both public YAML names.

### 15. `legacyModelPreferenceApiRemainsRemoved`

- **Type**: API/source regression
- **Location**: existing `SkillMethodTest`, `SkillImplementationTargetTest`, `CapabilityMetadataTest`, processor/registrar tests, plus repository search exit criterion
- **What it proves**: The completed removal remains part of this PR with no enum, annotation member, record component, compatibility constructor, or stale test expectation.
- **Fixtures/data**: Existing tests already updated in the working tree.
- **Mocks**: Existing unit-test mocks only.

## Tests to Remove or Rewrite

- Replace `YamlSkillCapabilityRegistrarTests#normalizesBlankMappingTargetToLlmBackedMetadata` with malformed-mapping catalog coverage.
- Rewrite the mapped registrar assertion that currently expects `frameworkModel == "gpt-5"` to assert `SkillExecutionDescriptor.none()`.
- Delete `mapped-method-skill-with-input-schema.yaml` as a valid fixture or move it to invalid applicability coverage.
- Replace mapped schema mismatch/format mismatch tests with `input_schema` inapplicability tests; the nested mismatch is no longer the contract being validated.
- Remove `SkillInputContractResolverTest` cases and production helpers used only for mapped structural compatibility and runtime-marker merging.
- Remove `model` from unrelated valid/invalid mapped fixtures so target, RBAC, proxy, and mapping diagnostics are not masked by applicability validation.
- Remove `model` and `planning_mode: false` from both mapped sample manifests.
- Keep all LLM-backed schema, linter, retry, evidence, planning, model, and thinking tests unchanged except for any diagnostic helper signature updates.

## Mocking and Test Isolation Strategy

- Prefer `ApplicationContextRunner` with real configuration binding, YAML parsing, catalog, registrar, and target discovery for manifest behavior.
- Maintain two explicit runners: the existing configured-model runner and a new model-free runner with no `application-test.yml` initializer. Do not clear map properties indirectly; absence must be real.
- Use real lightweight `@SkillMethod` target beans for mapped registration/invocation. Mocking the target registry would skip the contract-reflection behavior being protected.
- Use mocks only to verify a downstream subsystem is not reached (for example, optional zero-interaction target lookup) or where existing router tests already isolate session/RBAC/evidence collaborators.
- Do not add a fake `ChatModel`, fake model catalog entry, or `model: none`; each would invalidate the model-free acceptance test.
- Keep external provider calls out of automated tests. Sample feedstock behavior is verified through context loading and controller delegation, with live provider use reserved for manual testing.

## Test Execution Order

1. Add and run only `discoversMappedSkillWhenModelCatalogIsEmpty`; record the expected red failure.
2. Add declaration-copy and definition-invariant unit tests; implement Phase 1 until green.
3. Add malformed mapping, field matrix, diagnostics, order, and empty-model LLM tests; implement catalog classification/validation until green.
4. Rewrite registrar and contract tests; remove compatibility-specific coverage and implement honest metadata/Java ownership until green.
5. Add the model-free `SkillTemplate` application-context test and run router/template regressions.
6. Migrate all remaining fixtures and samples; run sample tests.
7. Run starter clean tests, full reactor tests, source audits, and manual verification.

## How to Run

No provider credentials, network access, special Maven profile, or external test data should be required for automated verification.

### First Red Test

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCatalogTests#discoversMappedSkillWhenModelCatalogIsEmpty" test
```

Expected before implementation: test failure because the mapped skill is absent from the catalog.

### Manifest, Catalog, and Definition Tests

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillDefinitionTest,YamlSkillCatalogTests" test
```

### Registration and Input-Contract Tests

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCapabilityRegistrarTests,SkillInputContractResolverTest" test
```

### Model-Free Runtime and Mapped Regressions

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=BifrostAutoConfigurationTests,CapabilityExecutionRouterTest,SkillTemplateTest" test
```

### Completed ModelPreference Removal Regression

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=SkillMethodTest,SkillImplementationTargetTest,CapabilityMetadataTest,SkillMethodBeanPostProcessorTest,YamlSkillCapabilityRegistrarTests" test
```

```powershell
rg "ModelPreference|modelPreference" bifrost-spring-boot-starter/src/main bifrost-spring-boot-starter/src/test
```

Expected search result: no matches.

### Sample Verification

```powershell
.\mvnw.cmd -pl bifrost-sample -am "-Dtest=SampleApplicationTests,SampleControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

### Module and Reactor Exit Runs

Use `clean` for the starter exit run so deletion of the legacy class cannot be masked by stale compiled output.

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter clean test
```

```powershell
.\mvnw.cmd test
```

### Source and Fixture Audits

```powershell
rg "\.executionConfiguration\(\)" bifrost-spring-boot-starter/src/main/java
```

Expected: only intentional definition/invariant access; model consumers use `requireExecutionConfiguration()`.

```powershell
rg "validateStructuralCompatibility|mergeRuntimeMarkers" bifrost-spring-boot-starter/src/main/java
```

Expected: no matches unless a non-mapped caller is explicitly retained and covered.

```powershell
rg -l "target_id:" bifrost-spring-boot-starter/src/test/resources bifrost-sample/src/main/resources
```

Review every listed valid/sample mapped resource against the four-field allowlist. Intentional invalid applicability fixtures are the only exceptions.

## Manual Verification

1. Start the sample using its normal local configuration and call `GET /expenses`; confirm `expenseLookup` returns the deterministic Java result.
2. Exercise the mapped feedstock endpoint with its normal provider configuration when credentials are available; confirm removal of YAML model fields does not change the Java service's explicit external API behavior.
3. Inspect registered metadata for one LLM-backed and one mapped skill; confirm configured versus `none()` execution descriptors and Java-inherited mapped input.
4. Start with one malformed mapping and one contradictory mapped field; confirm diagnostics name the public skill, YAML resource, field, reason, and remedy.
5. Inspect one mapped trace/journal and confirm public attribution remains the YAML name while `mapping.target_id` remains trusted implementation metadata.

## Exit Criteria

- [ ] The failing-first catalog test is recorded failing on the pre-fix behavior for the expected reason.
- [x] The model-free catalog and full `SkillTemplate` integration tests pass without model properties or any provider `ChatModel` bean.
- [x] Omitted mapping selects LLM-backed validation; null/empty/non-object/null-target/non-string-target/blank-target mappings fail at `mapping.target_id`.
- [x] All eleven inapplicable mapped fields are covered independently, including null, blank, false, zero, and empty declarations.
- [x] Multi-error and unbindable-value coverage prove the complete stable applicability order precedes typed content validation and target resolution.
- [x] Applicable mapped diagnostics include public skill name, resource, contradictory field, reason, and remedy; post-parse LLM validation diagnostics include public skill name and resource.
- [x] Declaration-presence metadata survives every defensive-copy path without exposing mutable state.
- [x] Malformed mapped definitions and both impossible definition/configuration combinations fail, and model consumers use the required-configuration boundary.
- [x] Mapped metadata uses `SkillExecutionDescriptor.none()`; LLM metadata remains configured.
- [x] Mapped input is inherited only from Java, mapped output schema is rejected, and pure LLM input/output validation remains green.
- [x] Parent-owned allowlist/evidence references to mapped children continue to work.
- [x] Reflected validation, binding, runtime refs, RBAC, shared targets, proxy/lazy/prototype targets, exceptions, evidence, and tracing regressions pass.
- [x] All valid mapped fixtures and sample manifests contain only the allowed wrapper fields.
- [x] Sample context and controller tests pass after destructive manifest cleanup.
- [x] `ModelPreference` removal remains visible in the PR, its focused regression tests pass, and source search returns no legacy reference.
- [x] Starter `clean test` passes.
- [x] Full reactor `test` passes.
- [x] Required manual sample/diagnostic/metadata verification is complete or explicitly recorded as environment-blocked.

### Implementation Verification Note

Automated context tests verified mapped invocation, configured-versus-none metadata, inherited Java input, field-specific startup diagnostics, and mapped tool-trace attribution through the public YAML name while the Java target ID remains internal metadata. Live `/expenses`, external feedstock-provider, and persisted trace inspection steps were not run because this implementation session did not start the sample server or provide external provider credentials; those manual checks remain environment-blocked. The failing-first test was added after production implementation began, so no pre-fix red run was recorded and that exit item intentionally remains unchecked.

## References

- Ticket: `ai/thoughts/tickets/eng-simplify-mapped-yaml-skill-manifests.md`
- Implementation plan: `ai/thoughts/plans/2026-07-13-simplify-mapped-yaml-skill-manifests.md`
- Research: `ai/thoughts/research/2026-07-13-simplify-mapped-yaml-skill-manifests.md`
- Existing catalog tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- Existing registrar tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java`
- Existing auto-configuration tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
- Existing input-contract tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/input/SkillInputContractResolverTest.java`
- Existing mapped runtime tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/CapabilityExecutionRouterTest.java`
- Existing entry API tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skillapi/SkillTemplateTest.java`
