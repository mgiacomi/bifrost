# Colocate Evidence with Output Schema Properties Testing Plan

## Change Summary

- Replace the supported top-level `evidence_contract.claims` YAML block with a strict `evidence` string annotation on immediate root `output_schema.properties` entries.
- Validate annotation shape and placement during catalog startup, use the containing property key as the exact claim name, and compile the unchanged Boolean expression language once into the existing `EvidenceContract`.
- Reject root-schema, nested-property, and array-item annotations with full paths; reject null, blank, Boolean, numeric, list, and object values without coercion.
- Remove the obsolete top-level manifest DTO/accessors/bookkeeping and test-only `EvidenceContract#fromManifest` compiler. Do not support old and new syntax simultaneously.
- Preserve planning, successful-direct-child credit, final candidate coverage, retry, advisor/trace, and nested mission semantics below the compiled contract boundary.
- Treat `evidence` as Bifrost orchestration metadata: ordinary output validation and prompt/provider schema rendering must not expose it as candidate JSON.
- Migrate all starter fixtures, five evidence-bearing sample manifests, current tests, and authoring guidance atomically under the approved pre-1.0 no-shim posture.

## Impacted Areas

- **Manifest binding and copying**: `YamlSkillManifest.OutputSchemaManifest`, `StrictStringScalarDeserializer`, removed `EvidenceContractManifest`, and `YamlSkillDefinition`'s Jackson defensive-copy path.
- **Catalog validation and compilation**: `YamlSkillCatalog#readManifest`, output-schema traversal/placement context, expression parsing, exact direct-child reference validation, casing suggestions, and construction of `EvidenceContract.empty()` or `compiled(...)`.
- **Schema-dialect consumers**: `OutputSchemaValidator`, `OutputSchemaPromptAugmentor`, and `StepPromptBuilder` must ignore the annotation as orchestration metadata.
- **Runtime compiled-contract consumers**: `EvidenceContract`, `EvidenceCoverageValidator`, `EvidenceBackedOutputValidator`, planning, advisors, step loop, successful-skill tracking, retry, traces, and nested mission boundaries.
- **Internal/public boundary**: removal of public nested internal manifest types/methods and addition of internal output-property accessors, covered by `BifrostPublicSurfaceArchitectureTest`.
- **Starter fixtures and catalog tests**: valid/invalid YAML under `bifrost-spring-boot-starter/src/test/resources/skills`, `YamlSkillCatalogTests`, `YamlSkillEvidenceExpressionCatalogAdditionalTest`, `YamlSkillDefinitionTest`, and `EvidenceContractTests`.
- **Representative sample applications**: five root YAML manifests, `SampleEvidenceContractCatalogTest`, and `SampleApplicationTests`.
- **Authoring evidence**: `README.md`, `bifrost-sample/README.md`, and the routed `ai/skill-authoring/` evidence topic, checklist, mental model, and coverage table.

## Risk Assessment

### High-Risk Behaviors

- **Authored null versus absence**: Jackson may represent both as Java null. An unconditional field-level null failure may correctly reject `evidence: null` but also break `YamlSkillDefinition#copyManifest`/`copyValue` when `ObjectMapper.convertValue` serializes an omitted nullable getter as null.
- **Recursive placement leakage**: adding `evidence` to a recursive DTO makes it syntactically bindable at the root, nested properties, arrays, and items unless catalog traversal carries explicit root/immediate/nested placement state.
- **Wrong canonical claim identity**: retaining the old detached-map join, trimming/case-folding the property key, or compiling a sibling name would reintroduce drift that colocation is intended to remove.
- **Scalar coercion**: YAML booleans, numbers, lists, objects, or explicit null may be accepted/coerced unless strict binding is tested through the real YAML/Jackson/catalog path.
- **Multiple production compilers**: preserving or recreating `EvidenceContract#fromManifest` could let catalog and test/runtime construction diverge. Catalog loading must remain the sole authoring compiler.
- **Metadata leakage**: generic DTO serialization or prompt rendering could cause the model to emit an `evidence` field or send an unsupported keyword to a provider.
- **Runtime semantic drift**: moving authoring location must not change all-claim planning, present-claim final validation, exact successful-child truth sets, retry ordering/budgets, tool-free evidence retry, diagnostics, or nested isolation.
- **False compatibility coverage**: retaining tests that require old `evidence_contract` success would create prohibited dual syntax. The only active old-syntax test should prove ordinary unknown-field rejection.
- **Sample schema loss**: converting compact YAML properties can accidentally drop `type`, `enum`, `format`, `description`, `nullable`, `items`, requiredness, or `additionalProperties`.

### Edge Cases

- No output schema, an output schema with no annotations, one annotation, multiple annotations, optional annotated fields, and unannotated sibling fields.
- Evidence on an immediate root scalar, object, or array property is valid; evidence on the root, a nested object property, `items`, or a property below an item object is invalid.
- Omitted evidence versus explicit null; empty string; whitespace-only string; Boolean; integer; decimal; list; and object values.
- Valid leaf, conjunction, disjunction, mixed precedence, and parenthesized expressions; malformed/trailing tokens; reserved operators; operator substrings in identifiers.
- Exact direct-child name, wrong-case name with one safe suggestion, ambiguous case-insensitive matches with no suggestion, non-direct/grandchild, blank allowed-skill entries if existing validation permits them, and `allowed_skills` absence.
- Exact property casing retained in compiled claims and diagnostics; case-insensitive duplicate output properties remain rejected by existing schema validation.
- Defensive copies of unannotated and annotated recursive schemas, including nested properties/items, without loss or mutation.
- Candidate JSON containing the annotated property, omitting an optional annotated property, and containing a literal unknown `evidence` response member.
- Direct advisor and step-loop prompts with annotated schemas; both must render output property names/types but not annotation metadata.
- Planned, started, failed, cancelled, wrong-case, blank, repeated, unplanned-successful, and step-loop-confirmed child calls.
- Successful and failed nested YAML children with successful grandchildren; only a successful direct child boundary may satisfy the parent contract.

### Contract and Compatibility Scope

| Surface | Test obligation |
| --- | --- |
| Application API | Run existing `SkillTemplate`/sample supported-facade tests; no evidence manifest or runtime implementation type may leak into API signatures. |
| Supported SPI | No affected SPI exists. Architecture tests must confirm no new SPI/customization surface is introduced. |
| Configuration and manifest contracts | Protect the new property-level syntax, strict shape, placement, direct-child expressions, and unchanged runtime semantics. Assert approved removal of top-level `evidence_contract`; never assert simultaneous acceptance. |
| Persisted or serialized contracts | No durable evidence format exists. Add no historical reader/version/migration tests; test only that current prompt/schema consumers do not leak the annotation. |
| Ephemeral diagnostic formats | Assert current startup paths and runtime expression/satisfied-skill/structured-gap diagnostics remain useful and coherent. Do not preserve old startup paths or historical trace schemas. |
| Internal or accidentally exposed implementation | Remove/update tests using `EvidenceContractManifest` and `fromManifest(...)` atomically. Use architecture tests to classify the remaining/new public internal methods and prevent leakage through supported APIs. |

### Authoring Claims Requiring Executable Evidence

| Guidance claim | Supporting tests and fixtures |
| --- | --- |
| `evidence` belongs on an immediate root output property | first-red valid catalog fixture plus root/nested/item invalid placement fixtures |
| The annotation is one nonblank YAML string | parameterized strict-shape catalog tests covering blank, null, Boolean, number, list, and object |
| The containing property key is the claim name with exact authored casing | compiled-contract assertions from mixed-case root property fixture |
| Expressions use the existing grammar and exact direct `allowed_skills` names | existing parser tests plus migrated catalog direct-child/casing/reserved-token tests |
| Omitted annotations produce an empty contract; unannotated properties have no inferred evidence | valid no-annotation catalog fixture and compiled-contract assertions |
| Planning evaluates all annotated properties, including optional ones | existing `EvidencePlanningIntegrationTest`/`PlanningServiceTest` semantic regression coverage using compiled contracts |
| Final validation evaluates only annotated fields present in schema-valid candidate output | `EvidenceCoverageValidatorTest`, advisor, and step-loop present/optional/required cases |
| Only successful direct children count and nested internals do not leak | callback/planning completion and `NestedSuccessfulSkillBoundaryTest` regression coverage |
| `evidence` is supportability metadata, not candidate JSON or provider schema | `OutputSchemaCallAdvisorTest` and `StepPromptBuilderTest` non-leakage assertions |
| Old top-level syntax is removed without a compatibility reader | one strict unknown-field fixture/test plus stale-reference searches |

## Existing Test Coverage

- `YamlSkillCatalogTests#loadsEvidenceContractWhenManifestDeclaresOne` uses `ApplicationContextRunner` and a real YAML resource to inspect the compiled contract. Adjacent tests cover blank expressions, wrong-case suggestions, exact public names, non-string values, and strict unknown fields, but all paths currently refer to `evidence_contract.claims`.
- `YamlSkillEvidenceExpressionCatalogAdditionalTest` already parameterizes Boolean, number, list, object, and null shapes and protects exact reference columns, reserved operators, non-direct children, and ambiguous casing. Fixtures must move to the new property location.
- `YamlSkillDefinitionTest#preservesDeclaredFieldsAcrossDefinitionDefensiveCopies` protects the Jackson copy mechanism, and `rejectsMappedDeclarationsAndResolvedEvidenceThatBypassCatalogValidation` protects mapped-definition invariants.
- `EvidenceContractTests` protects immutable/canonical claim lookup and present-candidate behavior, but currently constructs the removed manifest DTO through `EvidenceContract#fromManifest`.
- `EvidenceExpressionParserTest`, `EvidenceExpressionParserAdditionalTest`, and `EvidenceCoverageValidatorTest` already protect the grammar, canonical rendering, Boolean truth sets, all-claim planning, and present-claim final evaluation independently of YAML location.
- `EvidencePlanningIntegrationTest`, `PlanningServiceTest`, `SkillAdvisorResolverTests`, `SkillAdvisorResolverEvidenceTraceTest`, `ToolCallbackFactoryTest`, `StepLoopMissionExecutionEngineTest`, and `NestedSuccessfulSkillBoundaryTest` protect planning, advisor ordering/diagnostics, successful execution credit, retries, and mission isolation, although several construct `EvidenceContractManifest` directly.
- `OutputSchemaCallAdvisorTest` protects ordinary schema validation and direct prompt augmentation. `StepPromptBuilderTest#buildStepPromptShowsOutputSchemaInFinalResponseMode` protects the step-loop candidate JSON example. Neither currently uses an annotated schema.
- `BifrostPublicSurfaceArchitectureTest` inventories publicly accessible internal collaboration types and recursively prevents internal types from leaking through supported API signatures.
- `SampleEvidenceContractCatalogTest` already loads all five sample roots through the real catalog and compares exact canonical expression maps. `SampleApplicationTests` loads the sample context and currently searches manifest text for detached claim entries.

### Coverage Gaps

- No current fixture can load `output_schema.properties.<name>.evidence`; strict DTO binding rejects it as unknown.
- No placement test distinguishes a valid immediate root object/array property annotation from invalid root, nested, and item annotations.
- No test proves explicit YAML null is rejected while an omitted nullable annotation still survives `YamlSkillDefinition`'s Jackson copy path.
- No test proves the property-map key directly supplies exact compiled claim casing without the removed claim/property join.
- Existing duplicate/unknown claim-map cases protect a namespace that will no longer exist and must be removed, not recreated.
- No output-schema test proves the new annotation is ignored by candidate validation and both prompt renderers.
- Runtime tests that use `EvidenceContract#fromManifest` will not compile after the approved internal removal; they need a compiled-contract test helper without becoming a second production authoring path.
- Raw sample text assertions are not scoped tightly enough to prove expressions are under their matching property definitions.
- No explicit stale-reference check distinguishes the one intentional rejection fixture/assertion and historical artifacts from active syntax.

## Bug Reproduction / Failing Test First

- **Name**: `loadsEvidenceAnnotationFromImmediateRootOutputProperty`
- **Type**: catalog integration/component test using the existing `ApplicationContextRunner`
- **Location**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalogTests.java`
- **Fixture**: add `bifrost-spring-boot-starter/src/test/resources/skills/valid/output-schema-property-evidence-skill.yaml` with one LLM-backed skill, direct child `reviewSkill`, and:

  ```yaml
  output_schema:
    type: object
    properties:
      result:
        type: string
        evidence: reviewSkill
    required: [result]
    additionalProperties: false
  ```

- **Arrange**: configure the existing context runner to load only this fixture and the normal test model catalog.
- **Act**: start the context and retrieve `YamlSkillCatalog#getSkill(...).evidenceContract()`.
- **Assert**: the context starts successfully; the contract contains exactly claim `result`; `canonicalExpressionForClaim("result")` is `reviewSkill`; the schema still reports `result` as a string and required.
- **Expected failure pre-fix**: context startup fails in strict Jackson binding because `output_schema.properties.result.evidence` is an unknown field. The recorded red must be this missing new manifest capability, not missing model configuration or an invalid fixture.
- **Why this test first**: it is the lowest-cost test across the real authoring boundary and proves deserialization, supported placement, containing-key identity, expression parsing, direct-child validation, and compiled-contract construction in one focused case.

After recording that red, add the explicit old-syntax rejection test. It is not the first red because old `evidence_contract` currently succeeds; it becomes green only after the approved removal and must never be made green by accepting both forms.

## Tests to Add/Update

### 1. `loadsEvidenceAnnotationFromImmediateRootOutputProperty`

- **Type**: catalog integration/component test.
- **Location**: `YamlSkillCatalogTests.java` with `valid/output-schema-property-evidence-skill.yaml`.
- **What it proves**: the new authoring shape loads through the real catalog, the containing property name is compiled once as the claim, and ordinary schema attributes remain intact.
- **Fixtures/data**: minimal fixture from the failing-test section; exact direct child `reviewSkill`.
- **Mocks**: none beyond the existing `ApplicationContextRunner` test configuration and model properties.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: protected replacement syntax; first red on current code.

### 2. `compilesEveryAnnotatedRootPropertyAndLeavesUnannotatedPropertiesUnconstrained`

- **Type**: catalog integration test.
- **Location**: `YamlSkillCatalogTests.java`; migrate/expand `valid/evidence-contract-skill.yaml`.
- **What it proves**: multiple immediate root annotations compile in property order; mixed-case property keys remain canonical; optional annotated properties are included; an unannotated sibling has no inferred claim; a schema with no annotations produces `EvidenceContract.empty()`.
- **Fixtures/data**: properties covering scalar, object, and array root children; exact expressions with AND/OR; one unannotated property and an existing no-evidence output-schema fixture.
- **Mocks**: existing context runner only.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: protected new authoring behavior and preserved no-evidence behavior.

### 3. `rejectsEvidenceOutsideImmediateRootPropertiesWithFullPath`

- **Type**: parameterized catalog integration test.
- **Location**: `YamlSkillEvidenceExpressionCatalogAdditionalTest.java`; new invalid fixtures under `src/test/resources/skills/invalid/`.
- **What it proves**: root `output_schema.evidence`, nested `output_schema.properties.result.properties.detail.evidence`, array `output_schema.properties.results.items.evidence`, and a deeper item-object property all fail startup with exact paths and the immediate-root-only message. An immediate root object/array property remains valid.
- **Fixtures/data**: one fixture per invalid path plus valid object/array root-property cases.
- **Mocks**: none; real YAML/Jackson/catalog path.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: protected placement boundary and current-run diagnostic coherence.

### 4. `rejectsNonStringOrBlankPropertyEvidenceWithoutCoercion`

- **Type**: parameterized catalog integration test.
- **Location**: migrate `YamlSkillEvidenceExpressionCatalogAdditionalTest#rejectsEveryNonStringExpressionShapeWithoutCoercion`; rewrite invalid evidence fixtures.
- **What it proves**: blank/whitespace, explicit null, Boolean, integer/number, list, and object values fail with `output_schema.properties.<claim>.evidence`; none is coerced or treated as absent.
- **Fixtures/data**: one minimal YAML fixture per token shape, preserving complete root property definitions.
- **Mocks**: none.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: protected strict scalar contract and actionable startup diagnostics.

### 5. `preservesEvidenceAndAbsenceAcrossDefinitionDefensiveCopies`

- **Type**: unit test.
- **Location**: `YamlSkillDefinitionTest.java`.
- **What it proves**: an annotated root property retains the exact expression across `manifest()`, `outputSchema()`, and constructor copies; unannotated recursive properties/items remain absent; mutating returned copies cannot affect catalog state; explicit-null rejection machinery does not cause `ObjectMapper.convertValue` to fail on omitted annotations.
- **Fixtures/data**: programmatic recursive `OutputSchemaManifest` with annotated and unannotated siblings/items plus a nonempty compiled contract.
- **Mocks**: none; existing resource and execution-configuration helpers.
- **Contract classification**: Internal or accidentally exposed implementation.
- **Compatibility expectation**: preserve internal defensive-copy invariant while atomically replacing the manifest field.

### 6. `retainsExpressionDiagnosticsAtPropertyEvidencePath`

- **Type**: parameterized catalog integration test.
- **Location**: migrated `YamlSkillCatalogTests` and `YamlSkillEvidenceExpressionCatalogAdditionalTest` cases.
- **What it proves**: malformed/trailing expressions retain 1-based parser columns; non-direct/grandchild references fail at their exact column; wrong-case references provide one safe suggestion; ambiguous case matches omit suggestions; reserved `and`/`or` remain invalid references; operator substrings remain valid skill names.
- **Fixtures/data**: migrate existing malformed, non-direct, wrong-case, ambiguous, reserved, and exact-name fixtures to `properties.<claim>.evidence`.
- **Mocks**: none.
- **Contract classification**: Ephemeral diagnostic formats for message coherence, backed by the Configuration and manifest contract.
- **Compatibility expectation**: preserve current expression semantics while changing startup paths.

### 7. `rejectsRemovedTopLevelEvidenceContractAsUnknownField`

- **Type**: catalog integration test.
- **Location**: `YamlSkillCatalogTests.java` with one retained/renamed invalid old-syntax fixture.
- **What it proves**: the former top-level field no longer binds; startup identifies `evidence_contract` as unknown; no compatibility reader, precedence, merging, alias, or tailored migration diagnostic exists.
- **Fixtures/data**: one old `evidence_contract.claims` manifest that would otherwise be valid.
- **Mocks**: none.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: approved removal. Do not retain any test that expects old syntax to load.

### 8. `removesImpossibleDetachedClaimNamespaceTests`

- **Type**: test/fixture migration rather than a new executable case.
- **Location**: evidence block in `YamlSkillCatalogTests.java` and invalid YAML resources.
- **What it proves**: test inventory matches reachable new states.
- **Fixtures/data**: remove or repurpose unknown-claim, duplicate/case-colliding claim-key, missing-output-schema-only-for-contract, and duplicate tool/claim namespace fixtures. Continue relying on existing case-insensitive output-property duplicate tests for the single remaining property namespace.
- **Mocks**: none.
- **Contract classification**: Internal or accidentally exposed implementation plus approved Configuration contract replacement.
- **Compatibility expectation**: approved obsolete-path removal; never manufacture embedded equivalents for impossible detached-map failures.

### 9. `treatsPropertyEvidenceAsOrchestrationMetadata`

- **Type**: output-schema advisor/unit integration test.
- **Location**: `OutputSchemaCallAdvisorTest.java`.
- **What it proves**: an annotated property validates its ordinary type/enum/requiredness exactly as before; a valid candidate containing the property passes without an `evidence` member; a candidate literal `evidence` member remains an unknown output field when `additionalProperties` is false; direct prompt guidance names the actual property and omits the annotation expression/keyword.
- **Fixtures/data**: programmatic annotated output schema, passing candidate, and candidate with an extra `evidence` field.
- **Mocks**: existing recording chat chain/advisor harness; no tools.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: protected schema-dialect boundary and preserved ordinary output validation.

### 10. `stepPromptDoesNotRenderPropertyEvidenceAsResponseShape`

- **Type**: unit test.
- **Location**: `StepPromptBuilderTest.java`, adjacent to `buildStepPromptShowsOutputSchemaInFinalResponseMode`.
- **What it proves**: final-response JSON examples include the annotated property with its ordinary type placeholder but never render `evidence`, its expression, or a sibling metadata field; required/nullable behavior remains unchanged.
- **Fixtures/data**: completed plan and annotated output schema with one required property and one annotated optional object/array property.
- **Mocks**: existing test plan/tool helpers only.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: protected provider/model-facing response-shape behavior; no annotation leakage.

### 11. `compiledContractRuntimeSemanticsRemainUnchanged`

- **Type**: unit/component regression group.
- **Location**: `EvidenceContractTests`, `EvidenceCoverageValidatorTest`, `EvidencePlanningIntegrationTest`, and `PlanningServiceTest`.
- **What it proves**: runtime-only tests construct `EvidenceContract.compiled(...)` through a small test helper; planning still checks every claim including optional ones; either OR branch suffices; final evaluation uses only present claims; exact case applies; canonical expressions and structured ALL/ANY gaps remain unchanged.
- **Fixtures/data**: parsed expressions over classification/investigator alternatives and required/optional claims.
- **Mocks**: existing planning chat clients and callbacks where already used; no YAML catalog in evaluator-only tests.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: protected runtime semantics below the changed authoring boundary; approved removal of the internal manifest compiler.

### 12. `successfulExecutionRetryAndNestedBoundariesRemainUnchanged`

- **Type**: component/integration regression group.
- **Location**: `SkillAdvisorResolverTests`, `SkillAdvisorResolverEvidenceTraceTest`, `EvidenceContractAdvisorAdditionalTest`, `ToolCallbackFactoryTest`, `StepLoopMissionExecutionEngineTest`, and `NestedSuccessfulSkillBoundaryTest`.
- **What it proves**: advisor order remains schema then evidence then linter; only successful direct children count; failed/cancelled/blank/wrong-case names do not; repeated success is set-idempotent; evidence retry invokes no tools and has a separate budget; runtime traces retain canonical expressions/satisfied skills/structured gaps; nested internals do not bubble and only successful direct child boundary names receive parent credit.
- **Fixtures/data**: migrate construction from `EvidenceContractManifest#fromManifest` to parsed `EvidenceContract.compiled(...)`; preserve current success/failure response sequences.
- **Mocks**: existing mocked router/chat clients/callbacks and real state where current tests use it.
- **Contract classification**: Configuration and manifest contracts plus Ephemeral diagnostic formats.
- **Compatibility expectation**: protected runtime and current-run diagnostic behavior; internal authoring DTO removal only.

### 13. `loadsAllFiveColocatedSampleEvidenceContractsThroughTheRealCatalog`

- **Type**: Spring Boot sample integration test.
- **Location**: update `SampleEvidenceContractCatalogTest.java` and `SampleApplicationTests.java`.
- **What it proves**: all five migrated root manifests load; every claim/expression exactly matches the ticket; expressions reference exact direct allowed children; annotations reside under their corresponding property definitions; the sample context and supported `SkillTemplate` facade still load without provider credentials.
- **Fixtures/data**: the five production sample YAML files and existing expected expression maps.
- **Mocks**: none; local Spring context only, no live model invocation.
- **Contract classification**: Configuration and manifest contracts plus Application API smoke coverage.
- **Compatibility expectation**: protected representative new syntax and supported facade; old sample syntax absent.

### 14. `classifiesChangedInternalManifestSurfaceWithoutApiOrSpiLeakage`

- **Type**: architecture test.
- **Location**: `BifrostPublicSurfaceArchitectureTest.java`.
- **What it proves**: removed `EvidenceContractManifest`/`fromManifest` signatures are absent; new `OutputSchemaManifest#getEvidence/setEvidence` remain classified internal collaboration; no `com.lokiscale.bifrost.api` signature contains the internal schema/evidence types; no Supported SPI package/type is introduced.
- **Fixtures/data**: existing allowlist and recursive signature scans with updated reasons only where required.
- **Mocks**: none.
- **Contract classification**: Application API boundary and Internal or accidentally exposed implementation.
- **Compatibility expectation**: protected API boundary with approved internal public-surface replacement.

### 15. Existing Supported-Path Regression Suite

- **Type**: integration/regression.
- **Location**: existing supported-surface, output-schema, advisor, execution, sample, and architecture tests.
- **What it proves**: `SkillTemplate` invocation, mapped skill routing, schemas without evidence, linter composition, model configuration, output retry ordering, and unrelated strict manifest validation remain functional.
- **Fixtures/data**: existing repository fixtures, changing only evidence-specific construction/resources.
- **Mocks**: existing suite conventions.
- **Contract classification**: Application API and Configuration and manifest contracts.
- **Compatibility expectation**: protected paths; no broad framework regression from recursive DTO/catalog changes.

## Test Update and Removal Matrix

| Existing test or fixture | Planned treatment | Reason |
| --- | --- | --- |
| `loadsEvidenceContractWhenManifestDeclaresOne` | Rename/rewrite as the first-red property-annotation catalog test | Protect the replacement syntax at the real authoring boundary. |
| valid `evidence-contract-*.yaml` fixtures | Move expressions into complete root property definitions; rename when clarity improves | Preserve expression semantics while replacing location. |
| blank/null/Boolean/number/list/object expression fixtures | Rewrite at `output_schema.properties.<claim>.evidence` | Preserve strict scalar coverage and update paths. |
| unknown-claim fixture/test | Remove | There is no second claim namespace; the containing property always exists. |
| duplicate/case-colliding claim-key fixture/test | Remove | Impossible after colocation; existing output property collision validation owns this rule. |
| missing-output-schema evidence-contract fixture/test | Remove or repurpose as old-field unknown rejection | An annotation cannot exist without an output-schema node. |
| obsolete `tool_evidence`/duplicate-tool fixture | Remove | Not part of either the old-current or replacement authoring contract after this ticket. |
| mapped-with-evidence-contract fixtures/cases | Remove evidence-specific case or rely on ordinary unknown field; retain general mapped `output_schema` rejection | Mapped skills cannot declare `output_schema`, so no separate property-evidence rule is reachable. |
| `YamlSkillDefinitionTest` manifest declaration checks | Remove top-level field assertion; add annotated/unannotated recursive copy assertions | Protect the actual copy trap introduced by the new nullable recursive field. |
| `EvidenceContractTests` manifest construction | Replace with `compiled(...)` helper | Catalog is the sole production authoring compiler. |
| runtime tests constructing `EvidenceContractManifest` | Replace setup only with compiled contract helper; keep behavior assertions | Approved internal removal without changing runtime semantics. |
| output-schema advisor/step prompt tests | Add annotated schemas and negative leakage assertions | Protect Bifrost-only metadata boundary. |
| `SampleEvidenceContractCatalogTest` | Retain expression table; rename method to colocated syntax | Exact sample semantics are already valuable coverage. |
| `SampleApplicationTests` raw expression substring assertions | Scope assertions to property-level `evidence:` or parse/load through catalog | Detached `claim: expression` substrings no longer prove placement. |

## How to Run

Run all commands from `C:\opendev\code\bifrost` in PowerShell. No network access, provider credentials, browser, database, or live model is required. Tests use local YAML resources, `ApplicationContextRunner`, mocked/recording chat clients, fixed state, and the provider-independent sample Spring context.

### Baseline Before Test Changes

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCatalogTests,YamlSkillEvidenceExpressionCatalogAdditionalTest,YamlSkillDefinitionTest,EvidenceContractTests,OutputSchemaCallAdvisorTest,StepPromptBuilderTest,EvidenceCoverageValidatorTest,EvidencePlanningIntegrationTest,PlanningServiceTest,SkillAdvisorResolverTests,SkillAdvisorResolverEvidenceTraceTest,ToolCallbackFactoryTest,NestedSuccessfulSkillBoundaryTest,StepLoopMissionExecutionEngineTest,BifrostPublicSurfaceArchitectureTest" test
```

Record any pre-existing failure separately before adding the first-red fixture/test.

### First Red

Add only `output-schema-property-evidence-skill.yaml` and `loadsEvidenceAnnotationFromImmediateRootOutputProperty`, then run:

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCatalogTests#loadsEvidenceAnnotationFromImmediateRootOutputProperty" test
```

Expected pre-fix result: context startup fails because `output_schema.properties.result.evidence` is unknown. Do not proceed until the failure is confirmed to be the missing replacement feature rather than fixture/model setup.

### Focused Catalog and Schema Loop

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCatalogTests,YamlSkillEvidenceExpressionCatalogAdditionalTest,YamlSkillDefinitionTest,EvidenceContractTests,OutputSchemaCallAdvisorTest,StepPromptBuilderTest,BifrostPublicSurfaceArchitectureTest" test
```

### Focused Runtime Semantic Regression Loop

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=EvidenceExpressionParserTest,EvidenceExpressionParserAdditionalTest,EvidenceCoverageValidatorTest,EvidencePlanningIntegrationTest,PlanningServiceTest,EvidenceContractAdvisorAdditionalTest,SkillAdvisorResolverTests,SkillAdvisorResolverEvidenceTraceTest,ToolCallbackFactoryTest,NestedSuccessfulSkillBoundaryTest,StepLoopMissionExecutionEngineTest" test
```

### Sample Catalog and Supported-Facade Verification

```powershell
.\mvnw.cmd -pl bifrost-sample -am "-Dtest=SampleEvidenceContractCatalogTest,SampleApplicationTests" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

### Stale Syntax and Obsolete API Checks

Search active production, fixtures, samples, tests, and current guidance:

```powershell
rg -n "evidence_contract|EvidenceContractManifest|getEvidenceContract|setEvidenceContract|EVIDENCE_CONTRACT|EvidenceContract\.fromManifest" README.md ai/skill-authoring bifrost-sample bifrost-spring-boot-starter/src
```

Expected remaining matches are limited to:

- one invalid old-syntax fixture and its ordinary unknown-field assertion;
- wording that explicitly says the former syntax is removed, if current documentation intentionally includes that fact.

Historical tickets, research, and completed plans are excluded from this active-surface search and need not be rewritten.

Verify no supported sample or valid starter fixture retains the old field:

```powershell
rg -n "evidence_contract" bifrost-sample/src/main/resources/skills bifrost-spring-boot-starter/src/test/resources/skills/valid
```

Expected result: no matches.

### Module and Full Verification

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter test
.\mvnw.cmd test
```

## Manual Verification

1. Compare diagnostics for valid, explicit-null, wrong-case, malformed, root, nested-property, item, and removed-top-level fixtures. Confirm skill/resource identity, full field path, parser/reference column where relevant, and safe casing suggestion behavior.
2. Inspect `YamlSkillDefinition` copy tests and implementation together. Confirm authored `evidence: null` is distinguishable from an omitted annotation without causing unannotated recursive schemas to fail `ObjectMapper.convertValue`.
3. Inspect direct advisor guidance and step-loop final JSON examples. Confirm they contain normal output property names/types and never present `evidence` or its Boolean expression as response shape.
4. Compare a plan containing classification plus either investigator with one missing classification. Confirm all annotated claims are planned, either OR branch suffices, and runtime diagnostics remain canonical/structured.
5. Inspect successful, failed/cancelled, repeated, unplanned, and nested calls. Confirm only successful direct public names enter the mission set and child internals never bubble upward.
6. Review all five sample manifest diffs property by property. Confirm expressions moved unchanged and no `type`, description, enum, nullable, required, items, object shape, prompt, allowed skill, or business rule changed.
7. Review the routed `ai/skill-authoring/evidence-contracts.md` against the test matrix. Confirm placement, scalar shape, exact direct-child language, plan/final truth sets, metadata boundary, nesting, and limitations are each supported by named executable evidence.

## Exit Criteria

- [x] The first catalog test is recorded red before production changes and fails because `output_schema.properties.result.evidence` is unknown.
- [x] Immediate root scalar, object, and array properties accept nonblank string annotations and compile exact property-key claims once during catalog construction.
- [x] Root, nested-property, array-item, and deeper item-property annotations fail with complete actionable paths.
- [x] Omitted evidence remains allowed; explicit null, blank, Boolean, number, list, and object values fail without coercion.
- [x] Annotated and unannotated recursive schemas survive `YamlSkillDefinition` defensive copies without losing expressions, mutating catalog state, or converting absence into an invalid authored null.
- [x] Malformed expressions, trailing tokens, non-direct references, reserved words, wrong casing, ambiguous casing, and operator-substring identifiers retain current grammar/reference behavior and useful columns/suggestions at the new property path.
- [x] A schema with no annotations produces `EvidenceContract.empty()` and unannotated properties receive no inferred requirement.
- [x] The old top-level field fails as an ordinary unknown field; no compatibility reader, alias, merge, precedence, tailored migration diagnostic, or simultaneous old/new success test exists.
- [x] Detached-map-only tests/fixtures and the obsolete manifest DTO/accessors/bookkeeping/from-manifest helper are absent rather than preserved behind adapters.
- [x] Ordinary candidate validation ignores annotation metadata, a literal extra `evidence` response member remains subject to normal additional-property rules, and direct/step prompts do not leak the annotation or expression.
- [x] Planning still evaluates every compiled claim including optional ones, while final validation evaluates only present annotated fields after schema validity.
- [x] Only successful direct children satisfy expressions; failed/cancelled/blank/wrong-case names do not, repeated successes remain set-idempotent, retry calls no tools, and nested internals remain isolated.
- [x] Runtime planning/advisor/step-loop traces retain canonical expressions, satisfied direct skills, structured requirements, ordering, failure visibility, and current-run security boundaries.
- [x] Runtime-focused tests use compiled-contract helpers only where YAML authoring is irrelevant; catalog loading remains the sole production authoring compiler.
- [x] All five sample roots load through the real catalog with exact ticket expressions colocated on the matching properties, and their prompts/business schemas remain otherwise unchanged.
- [x] Tests cited as evidence for `ai/skill-authoring/` changes establish every documented placement, shape, identity, planning/final, nesting, and schema-dialect claim.
- [x] Existing Application API/supported-facade tests pass, no Supported SPI is introduced, and architecture tests show no internal evidence/schema type leakage through supported signatures.
- [x] No persisted/historical compatibility tests or old trace readers are added; approved obsolete paths are removed atomically.
- [x] Active-surface stale searches meet the documented expected-match rules.
- [x] Focused catalog/schema tests, runtime regression tests, sample context tests, starter module tests, and the full Maven reactor pass.
- [x] Manual diagnostic, Jackson-copy, prompt, runtime-boundary, sample-diff, and authoring-evidence reviews are complete.

## References

- Implementation plan: `ai/thoughts/plans/2026-07-18-colocate-evidence-with-output-schema-properties.md`
- Original ticket: `ai/thoughts/tickets/colocate-evidence-with-output-schema-properties.md`
- Supplied research: `ai/thoughts/research/2026-07-18-colocate-evidence-with-output-schema-properties.md`
- Testing-plan procedure: `ai/commands/3_testing_plan.md`
- Framework compatibility policy: `ai/thoughts/framework-feature-design-lens.md`
- Skill-authoring routing/standard: `ai/skill-authoring/README.md`
- Focused authoring topic: `ai/skill-authoring/evidence-contracts.md`
- Current catalog tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalogTests.java`
- Current strict-shape/reference tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/skill/YamlSkillEvidenceExpressionCatalogAdditionalTest.java`
- Current defensive-copy tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/skill/YamlSkillDefinitionTest.java`
- Current output-schema prompt/validation tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/outputschema/OutputSchemaCallAdvisorTest.java`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/step/StepPromptBuilderTest.java`
- Current runtime semantic tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceCoverageValidatorTest.java`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/planning/EvidencePlanningIntegrationTest.java`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/tool/NestedSuccessfulSkillBoundaryTest.java`
- Sample catalog verification: `bifrost-sample/src/test/java/com/lokiscale/bifrost/testing/SampleEvidenceContractCatalogTest.java`
