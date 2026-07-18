# Replace Evidence-Type Mappings with Boolean Skill Expressions Testing Plan

## Change Summary

- Replace `evidence_contract.claims` list values and the separate `tool_evidence` producer map with string-valued Boolean expressions over exact direct `allowed_skills`.
- Add a small immutable parser/AST with case-insensitive whole-token `and`/`or`, `and` precedence, parentheses, canonical lowercase rendering, EOF enforcement, and source-aware diagnostics.
- Compile and direct-child-validate expressions once during catalog construction; reject old syntax, non-string scalars, malformed expressions, reserved operator references, non-direct children, and casing mistakes.
- Evaluate every contract claim during plan validation against exact planned capability names, but evaluate only present contract-backed output fields during final validation against successfully completed direct child names.
- Replace synthetic evidence-ID state and diagnostics with a successful-direct-child set and expression-oriented coverage results, while preserving output retry behavior and nested YAML mission isolation.
- Retain the `EVIDENCE_RECORDED` trace record type, replace its payload with `successfulSkill` and `successfulDirectSkills`, and publish no legacy payload aliases or duplicate compatibility event.
- Migrate starter fixtures/tests, five sample contracts, root/sample documentation, and the AI-first skill-authoring knowledge base atomically. This is an approved pre-1.0 break with no compatibility shim.

## Impacted Areas

- **Expression language and compilation**: new `EvidenceExpression`/`EvidenceExpressionParser`, `YamlSkillManifest.EvidenceContractManifest`, `YamlSkillCatalog#validateEvidenceContract`, `EvidenceContract`, and `YamlSkillDefinition`.
- **Coverage semantics**: `EvidenceCoverageValidator`, `EvidenceCoverageResult`, `EvidenceCoverageIssue`, and `EvidenceBackedOutputValidator`.
- **Planning**: `DefaultPlanningService` initial prompt, retry feedback, all-claim coverage, and planning trace metadata.
- **Final output**: `EvidenceContractCallAdvisor`, `BifrostEvidenceValidationException`, `DefaultSkillAdvisorResolver`, and step-loop evidence validation/retry behavior.
- **Successful execution state**: `BifrostSession`, `ExecutionStateService`, `DefaultExecutionStateService`, evidence/successful-skill snapshot type, `PlanningService#markToolCompleted`, `DefaultToolCallbackFactory`, and `StepLoopMissionExecutionEngine`.
- **Nested mission boundary**: `ExecutionCoordinator` clearing and `CapabilityExecutionRouter` snapshot/restore around nested YAML execution.
- **Current-run diagnostics**: `EVIDENCE_RECORDED`, `EVIDENCE_VALIDATION_*`, planning validation/retry records, advisor metadata, retry messages, and trace-reader assertions.
- **Compatibility boundary**: `BifrostPublicSurfaceArchitectureTest`, existing `SkillTemplate` supported-surface tests, and removal of obsolete internal public signatures.
- **Fixtures and representative applications**: starter valid/invalid evidence fixtures, five root sample YAML files, and `SampleApplicationTests`.
- **Authoring guidance**: `README.md`, `bifrost-sample/README.md`, `ai/skill-authoring/README.md`, `evidence-contracts.md`, `checklists/evaluate-a-skill-design.md`, and `mental-model.md`.

## Risk Assessment

### High-Risk Behaviors

- **Parser truth changes**: an incorrect token boundary, precedence rule, or renderer parenthesis can silently strengthen or weaken claim support requirements.
- **YAML scalar coercion**: Jackson may coerce numbers or booleans to Java strings unless claims values use strict string-scalar binding; list/object values must also fail with the correct field path.
- **Identity normalization**: case-folding or trimming a reference before matching would incorrectly satisfy a contract with the wrong public skill name.
- **Flattened failure explanations**: reducing nested expressions to a missing-skill list would recreate the original ambiguity, especially for OR branches containing conjunctions.
- **Plan/final claim-set confusion**: reusing the wrong entry point could make planning ignore optional claims or make final validation require omitted optional claims.
- **Success recorded at the wrong lifecycle point**: recording planned, started, failed, cancelled, unverified-task, or step-loop callback activity would allow unsupported final claims; recording in both callback and step-loop completion paths would double-log a call.
- **Nested ledger leakage**: restoring the parent too late or merging the child set could let a grandchild satisfy a parent expression; restoring too early without parent-boundary credit could lose the nested child's successful public name.
- **Trace drift**: renaming the `EVIDENCE_RECORDED` event, retaining old payload aliases, or changing only one of planning/direct/step diagnostic writers would leave current-run debugging incoherent.
- **Sample semantic drift**: converting shared evidence types to conjunctions instead of alternatives would force both incident/support branches and change demonstrated behavior.

### Edge Cases

- Empty input, whitespace-only input, empty parentheses, leading/trailing operators, adjacent identifiers, unmatched parentheses, punctuation, and trailing tokens.
- Arbitrary operator casing and operator boundaries next to whitespace/parentheses, while `androidCheck`, `orderLookup`, and `candyParser` remain identifiers.
- Reserved `and`/`or` used where a skill reference is required, including when the same spelling appears in `allowed_skills`.
- Exact-case match, one unambiguous case-insensitive suggestion, and multiple case-insensitive candidates where no suggestion is safe.
- Case-insensitive claim collision and schema-property resolution with canonical output-schema casing.
- Null, list, object, number, and Boolean YAML claim values.
- Blank/null task capability names, wrong-case task names, repeated successful skill calls, failed and cancelled calls, unplanned successful calls, and bound step-loop calls.
- Nested success and failure with pre-existing parent successful skills and successful grandchildren inside the child.
- Candidate output containing one optional contract claim, omitting another, or containing a required unsupported claim.
- Evidence retry after schema success without consuming schema retry budget or allowing another tool call.

### Contract and Compatibility Scope

| Surface | Test obligation |
| --- | --- |
| Application API | Preserve existing `SkillTemplate` and observer behavior through `SupportedSurfaceIntegrationTest`/sample smoke tests; no new evidence types may leak into API signatures. |
| Supported SPI | No supported SPI exists or changes. Architecture tests continue to assert that none is accidentally introduced. |
| Configuration and manifest contracts | Protect output-schema requiredness, claim matching/collision rules, exact public identity, and new expression semantics. Assert approved removal of list-valued claims and `tool_evidence`; never test simultaneous old/new acceptance. |
| Persisted or serialized contracts | No durable contract is protected; add no historical session/trace migration or legacy-reader tests. |
| Ephemeral diagnostic formats | Test current writer/reader coherence, event ordering where relevant, useful field names/expressions, failure visibility, and absence of legacy aliases. Retain `EVIDENCE_RECORDED`. |
| Internal or accidentally exposed implementation | Update/remove tests for evidence-ID APIs atomically; classify only the minimum cross-package expression/state types in the architecture allowlist and verify no internal type leaks through supported API signatures. |

### Authoring Claims Requiring Executable Evidence

| Guidance claim | Supporting tests/fixtures |
| --- | --- |
| Claims contain Boolean strings over direct children | valid catalog fixture, strict-shape catalog tests, direct-child reference tests, five sample catalog assertions |
| `and` binds more tightly than `or`; parentheses override it | parser canonicalization/evaluation parameterized tests |
| Operators are case-insensitive whole tokens; skill names are exact and case-sensitive | parser token tests plus catalog casing/suggestion tests |
| `not`, symbolic aliases, quoted names, punctuation, and reserved operator references are unsupported | parser malformed/reserved-word tests |
| Planning evaluates all claims using planned exact child names | `PlanningServiceTest` coverage matrix |
| Final validation evaluates only present claims using successfully completed children | evaluator/advisor/step-loop tests |
| Failed/cancelled/started/planned calls do not count; supported unplanned success does | callback/planning completion tests |
| Nested child internals do not leak; successful child boundary name is credited | coordinator/router boundary integration tests |
| Expressions enforce supportability, not factual truth or general workflow order | evaluator tests show truth depends only on successful names; docs state the limitation without inventing a factual-correctness test |
| `EVIDENCE_RECORDED` remains, with successful-skill fields and no legacy payload | state/trace tests |

## Existing Test Coverage

- `YamlSkillCatalogTests#loadsEvidenceContractWhenManifestDeclaresOne` loads and inspects the current two-map contract. Adjacent tests protect unknown claims, case-colliding claim keys, blank evidence IDs, duplicate tool keys, exact public producer names, and unknown-property diagnostics.
- `EvidenceContractTests#normalizesClaimsToolsAndPresentClaimLookups` covers current claim canonicalization, candidate-field detection, and evidence-set failure; `evidenceAdvisorPreservesExistingSkillPromptOnRetry` protects prompt augmentation during final retry.
- `PlanningServiceTest#planningPromptIncludesEvidenceConstraints` explicitly asserts the current flattened producer list, while `rejectsContractBackedPlanWhenRequiredEvidenceRemainsUncoveredAfterRetries` and `acceptsContractBackedPlanWhenTaskBindingsCoverAllRequiredEvidence` protect deterministic plan coverage.
- `ToolCallbackFactoryTest` distinguishes linked and unplanned execution, verifies current unplanned evidence recording, confirms failure logging, and protects deferred recording for step-loop-bound callbacks.
- `ExecutionStateServiceTest#restoresParentEvidenceAfterNestedMissionAndClearsWhenNoParentExists` protects snapshot restoration; `recordsProducedEvidenceInLedgerAndTraceWithoutJournalEntries` protects the current ledger/trace boundary and retained `EVIDENCE_RECORDED` type.
- `ExecutionCoordinatorTest#clearsInheritedEvidenceBeforeNestedSkillExecution` and `CapabilityExecutionRouterTest#nestedYamlDelegationStartsWithFreshEvidenceAndRestoresParentEvidenceAfterward` protect mission clearing and nested isolation.
- `StepLoopMissionExecutionEngineTest#evidenceRetriesDoNotConsumeOutputSchemaRetryBudget` protects schema/evidence retry separation, and `executesBoundToolCallbacksWithoutRelinkingOrDuplicatePlanUpdates` protects bound callback ownership.
- `SkillAdvisorResolverTests#createsOutputSchemaThenEvidenceThenLinterAdvisorOrder` protects advisor ordering.
- `ExecutionTraceContractTest` already reads planning validation/retry trace records and asserts current metadata presence/order.
- `BifrostPublicSurfaceArchitectureTest` classifies every externally accessible type, prevents an SPI package, and recursively rejects internal types in API signatures.
- `SampleApplicationTests#contextLoads` currently proves the sample catalog starts as part of Spring context creation, but does not assert each migrated contract or its canonical expression.

### Coverage Gaps

- No parser, AST, canonical renderer, source-column, or strict string-scalar coverage exists.
- No startup validation proves expression references are exact direct `allowed_skills` or supplies a safe casing suggestion.
- Existing planning prompt assertions protect the bug: they expect a flat producer list and cannot distinguish AND from OR.
- Existing coverage results expose flat evidence IDs rather than recursive unsatisfied clauses.
- No test directly proves either incident investigator is sufficient while classification is still mandatory.
- No final-output test matrix explicitly contrasts all-claim planning with present-claim candidate validation under the new expression model.
- Existing callback/state tests credit evidence IDs only when a contract mapping exists; the new ledger must record every successful direct child name independently of an expression lookup.
- Nested tests protect restoration but do not directly prove successful parent-boundary credit and failed-child non-credit in one end-to-end path.
- Current trace tests do not assert the retained `EVIDENCE_RECORDED` type's new exact payload or absence of `evidenceTypes`, `ledger`, and `missingEvidence` aliases.
- Sample context loading does not enumerate all five root contracts or compare their canonical expressions with the ticket.

## Bug Reproduction / Failing Test First

- **Name**: `planningPromptPreservesBooleanAlternatives`
- **Type**: unit/component test using the existing mocked planning client
- **Location**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/planning/PlanningServiceTest.java`
- **Arrange**:
  - Use the current `DefaultPlanningService`, `DefaultExecutionStateService`, fixed clock, and captured `SimpleChatClient`/`SequencePlanningChatClient` pattern.
  - Define the currently expressible semantics for `likelyCause`: classification evidence plus shared investigation evidence produced by either `investigateNetwork` or `investigateApp`.
  - Expose `classifyIncident`, `investigateNetwork`, and `investigateApp` tool callbacks.
- **Act**: call `initializePlan`, allow deterministic plan validation to fail or retry as needed, and capture the first system prompt.
- **Assert**:
  - Prompt contains the semantic requirement `classifyIncident and (investigateNetwork or investigateApp)`.
  - Prompt does not contain a flattened `[classifyIncident, investigateApp, investigateNetwork] tool(s)` instruction or wording that every investigator must run.
- **Expected failure (pre-fix)**: current `buildPlanningPrompt` reconstructs all possible producer tools for missing evidence and prints them as one mandatory list, so the canonical Boolean assertion is absent and the flattened-list prohibition fails.
- **Post-migration fixture update**: replace the old claim/producer setup with the direct expression string while retaining the same behavior assertions. This test protects the user-visible defect rather than preserving old manifest syntax.
- **First-red command**: `./mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=PlanningServiceTest#planningPromptPreservesBooleanAlternatives" test`

After this behavioral red is recorded, add the parser unit tests next and keep them red while implementing the smallest parser/AST slice. Do not begin with a test that merely fails compilation because a class is absent.

## Tests to Add/Update

### 1) `parsesAndCanonicallyRendersSupportedExpressions`

- **Type**: parameterized unit test
- **Location**: new `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceExpressionParserTest.java`
- **What it proves**: single references; repeated `and`/`or`; `and` precedence; parentheses changing truth; arbitrary operator casing; lowercase canonical operators; minimal necessary parentheses; operator adjacency to parentheses; and whole-token behavior for `androidCheck`, `orderLookup`, and `candyParser`.
- **Fixtures/data**: table of raw expression, expected canonical expression, selected satisfied-name sets, and expected truth. Include `a or b and c`, `(a or b) and c`, and `a AND (b Or c)`.
- **Mocks**: none.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: protected new manifest behavior.

### 2) `rejectsMalformedExpressionsWithPreciseColumns`

- **Type**: parameterized unit test
- **Location**: `EvidenceExpressionParserTest.java`
- **What it proves**: deterministic rejection and 1-based source columns for blank input at the parser boundary, missing left/right operands, adjacent identifiers, unmatched opening/closing parentheses, empty parentheses, punctuation, quoted identifiers, `&&`/`||`, trailing tokens, and reserved `and`/`or` in primary position.
- **Fixtures/data**: raw invalid expression, expected diagnostic category/detail, and expected column. Include start/middle/end failures and mixed whitespace.
- **Mocks**: none.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: protected visible-failure behavior; unsupported aliases remain absent.

### 3) `loadsAndCompilesBooleanEvidenceContract`

- **Type**: Spring catalog integration test
- **Location**: update `YamlSkillCatalogTests.java`; migrate `src/test/resources/skills/valid/evidence-contract-skill.yaml` and the public-name fixture.
- **What it proves**: string-valued expressions load, claims use canonical output-schema casing, references preserve exact skill case, canonical rendering is available from the normalized contract, and `EvidenceContract` contains parsed expressions rather than producer/evidence maps.
- **Fixtures/data**: valid contract with `classifyIncident and (investigateNetwork or investigateApp)`, matching `allowed_skills`, and a case-varied claim key that resolves to the schema's canonical property casing.
- **Mocks**: existing `ApplicationContextRunner`; no model/provider call.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: protected new manifest path.

### 4) `rejectsNonStringAndObsoleteEvidenceContractShapes`

- **Type**: parameterized Spring catalog integration test
- **Location**: `YamlSkillCatalogTests.java` plus focused invalid YAML fixtures under `bifrost-spring-boot-starter/src/test/resources/skills/invalid/`
- **What it proves**: blank and null expressions fail; list, object, numeric, and Boolean values are not coerced; old `tool_evidence` fails as an unknown property with a useful full path; no dual syntax is accepted.
- **Fixtures/data**: one fixture per YAML token shape where needed for stable Jackson paths; expected resource, `evidence_contract.claims.<claim>` or `evidence_contract.tool_evidence` path, and specific error fragment.
- **Mocks**: existing `ApplicationContextRunner`.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: approved removal of obsolete syntax; no compatibility fallback.

### 5) `validatesExpressionReferencesAgainstExactDirectAllowedSkills`

- **Type**: parameterized Spring catalog integration test
- **Location**: `YamlSkillCatalogTests.java` plus direct-child/casing fixtures
- **What it proves**: exact direct child succeeds; grandchild/non-allowed reference fails; wrong-case reference fails with the one unambiguous allowed spelling; multiple case-insensitive candidates do not produce an unsafe suggestion; reserved operator spelling cannot be referenced even if listed in `allowed_skills`.
- **Fixtures/data**: exact, non-direct, unique-case-mismatch, ambiguous-case-mismatch, and reserved-word manifests. Assert resource, field path, claim, offending name, column when available, and suggestion presence/absence.
- **Mocks**: existing `ApplicationContextRunner`.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: protected exact-identity/direct-child rule and visible failure.

### 6) `preservesClaimValidationAndRejectsCaseCollisions`

- **Type**: catalog integration regression test
- **Location**: update existing unknown-claim and duplicate-claim tests in `YamlSkillCatalogTests.java`
- **What it proves**: `output_schema` remains required; claim keys remain nonblank; unknown top-level claims fail; case-insensitive claim collisions fail; accepted claim casing resolves to the schema's canonical casing.
- **Fixtures/data**: migrated existing fixtures plus a missing-output-schema expression fixture.
- **Mocks**: `ApplicationContextRunner`.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: protected pre-existing manifest validation that survives the approved syntax break.

### 7) `evaluatesNestedBooleanRequirementsAndExplainsUnsatisfiedClauses`

- **Type**: parameterized unit test
- **Location**: expand `EvidenceContractTests.java` or add a focused `EvidenceCoverageValidatorTest.java` if separation improves readability
- **What it proves**:
  - classification plus network passes;
  - classification plus app passes;
  - classification alone fails with an ANY-of investigator gap;
  - investigator alone fails with the missing classification conjunct;
  - both investigators are not required;
  - wrong-case satisfied name does not match;
  - adding successful names is monotonic;
  - nested OR branches containing conjunctions remain structured rather than flattened.
- **Fixtures/data**: compiled `classifyIncident and (investigateNetwork or investigateApp)` plus a deeper expression such as `(a and b) or (c and d)`; expected canonical expression, satisfied skills, unsatisfied requirement mode/children, and message fragments.
- **Mocks**: none.
- **Contract classification**: Internal or accidentally exposed implementation.
- **Compatibility expectation**: atomic replacement of evidence-ID evaluator with the new runtime truth model.

### 8) `planningEvaluatesAllClaimsAgainstExactTaskCapabilityNames`

- **Type**: planning component test
- **Location**: update/expand `PlanningServiceTest.java`
- **What it proves**: planning evaluates every declared claim, including optional output fields; blank/null capability names do not satisfy references; wrong-case names do not match; either OR branch is enough; retry feedback preserves the canonical expression and ALL/ANY distinction.
- **Fixtures/data**: sequence client plans for classification-only, investigator-only, classification+network, classification+app, wrong-case, and a plan omitting a contract-backed optional field's supporting task.
- **Mocks**: existing `ToolCallback` mocks and `SequencePlanningChatClient`; fixed clock/state service.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: protected existing all-claim planning rule with new expression semantics.

### 9) `planningPromptAndTracePreserveBooleanRequirements`

- **Type**: planning component/current-trace integration test
- **Location**: `PlanningServiceTest.java` and `ExecutionTraceContractTest.java`
- **What it proves**: initial prompt renders one canonical expression per claim; retry text states tasks must satisfy the expression; OR is never presented as run-all; planning validation/retry records contain `unsatisfiedClaims`, `satisfiedSkills`, `requiredExpression`, and `unsatisfiedRequirements`; `missingEvidence` is absent.
- **Fixtures/data**: weak then corrected incident plans, captured system prompts, and `TraceRecord` inspection using existing helpers.
- **Mocks**: existing sequence planning client and mocked tool definitions.
- **Contract classification**: Ephemeral diagnostic formats.
- **Compatibility expectation**: current-run diagnostic coherence; no old metadata alias or historical reader.

### 10) `finalValidationUsesOnlyPresentClaimsAndSuccessfulSkills`

- **Type**: parameterized unit/advisor test
- **Location**: `EvidenceContractTests.java`
- **What it proves**: schema-valid candidates evaluate only present contract-backed fields; a supported present claim passes; an unsupported optional claim can be removed on retry; an omitted optional claim is not evaluated; required unsupported output eventually fails; exact skill case applies; retry retains the existing skill prompt and invokes only the downstream model chain, not tools.
- **Fixtures/data**: output schema with required and optional fields, expression contract, satisfied-name sets, and response sequences containing/removing optional claims.
- **Mocks**: existing `RecordingChain`; pass/fail recorders capture result fields. No tool callbacks should be present or invoked.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: protected candidate-output behavior with expression-based successful-skill truth.

### 11) `directAndStepFinalDiagnosticsUseExpressionFields`

- **Type**: advisor resolver and step-loop component tests
- **Location**: `SkillAdvisorResolverTests.java` and `StepLoopMissionExecutionEngineTest.java`
- **What it proves**: output-schema advisor remains before evidence advisor and linter remains after it; direct and step-loop failures publish the same canonical expression/satisfied/unsatisfied fields; evidence retries remain independent of schema retry budget; final retry calls no tools; terminal failure distinguishes unsuccessful skill completion from missing plan coverage.
- **Fixtures/data**: migrated output-schema/evidence definition, unsupported then corrected response sequence, captured `recordEvidenceValidation` metadata/payload, and tool callback invocation counts.
- **Mocks**: mocked `ExecutionStateService` for resolver metadata; existing `SequenceChatClient`, fixed state service, and mocked callbacks for step loop.
- **Contract classification**: Ephemeral diagnostic formats.
- **Compatibility expectation**: current-run writer coherence and protected retry ordering; no legacy diagnostic fields.

### 12) `recordsSuccessfulDirectSkillsInRetainedEvidenceEvent`

- **Type**: state service unit/integration test
- **Location**: update `ExecutionStateServiceTest.java`
- **What it proves**: the ledger stores exact successful direct names in insertion-preserving set form; repeated success leaves one set member while individual execution events may remain observable; snapshot/restore works; `EVIDENCE_RECORDED` is retained; payload contains `successfulSkill` and `successfulDirectSkills`; payload does not contain `evidenceTypes`, `ledger`, synthetic evidence IDs, or compatibility aliases; evidence records remain out of the projected journal if that existing boundary remains unchanged.
- **Fixtures/data**: fixed session/frame, planned and unplanned successful names, duplicate success, and trace-record reader.
- **Mocks**: real `DefaultExecutionStateService` with fixed clock.
- **Contract classification**: Ephemeral diagnostic formats.
- **Compatibility expectation**: retained event type with deliberate current-version payload replacement.

### 13) `creditsOnlySuccessfullyCompletedDirectCallsAcrossExecutionPaths`

- **Type**: callback/planning component test
- **Location**: `ToolCallbackFactoryTest.java` and `PlanningServiceTest.java`
- **What it proves**:
  - linked direct completion records the capability only after the task is verified completed;
  - successful unplanned calls are credited;
  - failed and `CancellationException` calls are not credited;
  - planned/started calls are not credited before return;
  - step-loop-bound callback does not record or complete independently;
  - subsequent step-loop `markToolCompleted` records exactly once;
  - recording does not depend on whether the completed skill appears in a particular claim expression.
- **Fixtures/data**: mocked router success/failure/cancellation, linked/unlinked plan results, bound `ToolContext`, and definitions with/without references to the child.
- **Mocks**: Mockito `CapabilityExecutionRouter`, `PlanningService`, and `ExecutionStateService`; use `InOrder` and `never()/times(1)` assertions.
- **Contract classification**: Internal or accidentally exposed implementation.
- **Compatibility expectation**: atomic replacement of evidence-ID completion APIs; approved obsolete signatures are removed rather than overloaded.

### 14) `isolatesNestedSuccessfulSkillSetsAndCreditsOnlyTheChildBoundary`

- **Type**: nested execution integration test
- **Location**: expand `CapabilityExecutionRouterTest.java` and `ExecutionCoordinatorTest.java`
- **What it proves**:
  - each YAML mission starts with a fresh set;
  - parent successful names are restored in `finally`;
  - child successes such as `checkDns` never leak to the parent;
  - successful nested `investigateNetwork` completion is credited at the parent callback boundary;
  - nested failure restores the parent and credits neither the child boundary nor internals;
  - a parent expression can pass using `investigateNetwork` but cannot pass using `checkDns`.
- **Fixtures/data**: pre-populated parent set, child engine that records an internal success then returns or throws, real state service/router/coordinator wiring patterned after the existing nested test, and a parent callback/contract over the public child name.
- **Mocks**: mock provider/coordinator only where the existing test harness does; use real state transitions and callback ordering for the boundary assertion.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: protected nested mission isolation and direct-child authoring boundary.

### 15) `loadsAllFiveMigratedSampleEvidenceContracts`

- **Type**: Spring Boot sample integration test
- **Location**: expand `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleApplicationTests.java`
- **What it proves**: `duplicateInvoiceChecker`, `handleIncident`, `processClaim`, `resolveSupportCase`, and `planTrip` all load through the real sample catalog; every expected claim has the exact canonical expression from the ticket; no sample relies on `tool_evidence`; support/incident alternatives remain OR, while insurance/travel conjunctions remain AND.
- **Fixtures/data**: the five production sample YAML files. Autowire `YamlSkillCatalog` inside this repository test and assert a table of skill, claim, expected canonical expression.
- **Mocks**: none for catalog loading; no LLM/provider invocation or credentials.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: protected representative new syntax and exact semantic migration; old sample syntax removed.

### 16) `classifiesExpressionInternalsWithoutExpandingSupportedSurface`

- **Type**: architecture test
- **Location**: update `BifrostPublicSurfaceArchitectureTest.java`
- **What it proves**: every new externally accessible parser/expression/result/snapshot type is either avoided or explicitly classified as internal cross-package collaboration; API packages remain unchanged; no Supported SPI appears; supported API signatures recursively contain no new internal types; obsolete evidence-ID internal public types/signatures are absent where renamed/removed.
- **Fixtures/data**: architecture allowlist entries with nonblank reasons and existing package/type scans.
- **Mocks**: none.
- **Contract classification**: Internal or accidentally exposed implementation.
- **Compatibility expectation**: protected Application API boundary and approved internal public-surface replacement.

### 17) Existing Supported-Path Regression Suite

- **Type**: integration/regression
- **Location**: existing `SupportedSurfaceIntegrationTest`, `SkillAdvisorResolverTests`, output-schema tests, and sample `SkillTemplate` smoke tests
- **What it proves**: root YAML invocation, supported `SkillTemplate` facade, observer behavior, output-schema-before-evidence ordering, mapped child invocation, and unrelated manifest validation remain functional.
- **Fixtures/data**: existing repository fixtures; migrate only evidence-specific construction.
- **Mocks**: existing suite conventions.
- **Contract classification**: Application API.
- **Compatibility expectation**: protected path; no evidence implementation type leaks or behavior regression.

## Test Update and Removal Matrix

| Existing test/fixture | Planned treatment | Reason |
| --- | --- | --- |
| `planningPromptIncludesEvidenceConstraints` | Rename/rewrite as first-red Boolean prompt test | Current assertion protects flattened producer wording. |
| `loadsEvidenceContractWhenManifestDeclaresOne` | Migrate to expression/canonical contract assertions | Protect the new manifest contract, not evidence maps. |
| blank-evidence-ID fixture/test | Replace with blank/null expression cases | Evidence IDs no longer exist. |
| duplicate-tool-case fixture/test | Remove and replace with direct-child casing/ambiguity cases | `tool_evidence` keys no longer exist. |
| public producer-name fixture/test | Migrate to exact expression reference test | Preserve exact public identity at its new authoring location. |
| `normalizesClaimsToolsAndPresentClaimLookups` | Split/migrate to canonical claims, expression truth, and present-claim tests | Tool/evidence normalization is intentionally removed. |
| planning coverage pass/fail tests | Expand to classification plus either investigator matrix | Preserve deterministic plan validation while proving OR semantics. |
| callback evidence recording assertions | Rename to successful direct-skill assertions | Internal approved break; no overload or dual assertion. |
| state evidence snapshot/trace tests | Rename/migrate, retain `EVIDENCE_RECORDED` assertion | Ledger meaning changes; event type deliberately remains. |
| router/coordinator evidence isolation tests | Rename/migrate and add parent-boundary success/failure credit | Preserve protected mission boundary with stronger coverage. |
| step-loop evidence retry test | Migrate satisfied set and assert expression diagnostics/tool-free retry | Preserve retry semantics. |
| advisor order test | Migrate construction only and retain ordering assertion | Existing protected composition behavior. |
| valid/invalid starter YAML fixtures | Replace obsolete shapes atomically | Do not retain old syntax as a compatibility path. |

## How to Run

All commands run from `C:\opendev\code\bifrost` in PowerShell. No provider credentials, network service, database, browser, or live model are required; tests use fixed clocks, local YAML resources, mocked chat clients/callbacks, and the provider-independent sample Spring context.

### Baseline and First Red

1. Before changing production code, run the existing focused baseline:

   ```powershell
   .\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=PlanningServiceTest,YamlSkillCatalogTests,EvidenceContractTests,ToolCallbackFactoryTest,ExecutionStateServiceTest,CapabilityExecutionRouterTest,ExecutionCoordinatorTest,StepLoopMissionExecutionEngineTest,SkillAdvisorResolverTests,ExecutionTraceContractTest,BifrostPublicSurfaceArchitectureTest" test
   ```

2. Add `planningPromptPreservesBooleanAlternatives` using the current semantics and record its expected failure:

   ```powershell
   .\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=PlanningServiceTest#planningPromptPreservesBooleanAlternatives" test
   ```

3. Confirm the failure is the absent canonical Boolean expression/presence of flattened mandatory tools, not unrelated setup or model parsing.

### Focused Development Loops

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=EvidenceExpressionParserTest" test
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCatalogTests,YamlSkillDefinitionTest,BifrostPublicSurfaceArchitectureTest" test
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=EvidenceContractTests,PlanningServiceTest,ExecutionTraceContractTest" test
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=ToolCallbackFactoryTest,ExecutionStateServiceTest,CapabilityExecutionRouterTest,ExecutionCoordinatorTest" test
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=SkillAdvisorResolverTests,StepLoopMissionExecutionEngineTest" test
```

### Sample Catalog Verification

```powershell
.\mvnw.cmd -pl bifrost-sample -am "-Dtest=SampleApplicationTests" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

### Legacy Removal Checks

Search current executable/reference surfaces while deliberately excluding the ticket and plan artifacts that explain the removed syntax:

```powershell
rg -n "tool_evidence|producedEvidenceTypes|evidenceProducedByTool|recordProducedEvidence|missingEvidence|requiredEvidence|availableEvidence" README.md ai/skill-authoring bifrost-sample bifrost-spring-boot-starter/src
```

Expected result: no legacy evidence-ID authoring/runtime/diagnostic reference. Review any deliberate occurrence of the word “evidence” by meaning; the feature name and retained `EVIDENCE_RECORDED` event are not legacy IDs.

Inspect every remaining evidence contract block to ensure claim values are strings rather than lists and no sample/test fixture retains the two-map shape:

```powershell
rg -n -A 12 "evidence_contract:" bifrost-sample/src/main/resources/skills bifrost-spring-boot-starter/src/test/resources/skills
```

### Full Verification

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter test
.\mvnw.cmd test
```

## Manual Verification

1. Inspect parser diagnostics for a start-column error, a nested middle error, a trailing-token error, and a casing suggestion; verify the reported resource, full claim path, claim name, 1-based column, and exact allowed spelling.
2. Compare canonical renderings for `a or b and c`, `(a or b) and c`, and `a AND (b Or c)` with the grammar and truth-table tests.
3. Read initial and retry planning prompts for the incident expression. Confirm classification is mandatory, either investigator suffices, and neither message implies that both investigators must execute.
4. Inspect one direct-advisor failure and one step-loop failure trace. Confirm both show canonical expression, satisfied skills, structured unsatisfied requirements, and no `missingEvidence` field.
5. Inspect successful, repeated, failed/cancelled, unplanned, and nested calls. Confirm only successful direct public child names enter the mission set.
6. Inspect a nested trace: the child may contain `checkDns`, but the restored parent set and validation must contain only `investigateNetwork` at that boundary.
7. Inspect an `EVIDENCE_RECORDED` trace record. Confirm the event type is unchanged, payload uses `successfulSkill`/`successfulDirectSkills`, and no legacy aliases or duplicate compatibility event are present.
8. Compare each of the five sample claim-expression tables with the ticket, especially incident/support OR branches and the support prompt's separate multi-intent workflow guidance.
9. Review updated authoring documents against the executable test matrix so grammar, exact identity, plan/final truth sets, nesting, and unsupported syntax are all evidence-backed and no obsolete ontology guidance remains.

## Exit Criteria

- [ ] The first behavioral test is committed or otherwise recorded red before production changes and fails for the current flattened planning prompt, not for test setup.
- [ ] Parser tests cover every supported grammar rule, operator/token boundary, canonical rendering rule, reserved word, prohibited alias, malformed form, EOF rule, and representative source column.
- [ ] Catalog tests reject blank/null and every non-string scalar shape without coercion, reject non-direct/wrong-case/reserved references actionably, and preserve claim/schema validation.
- [ ] Old list-valued claims and `tool_evidence` are rejected and absent from executable/current guidance surfaces; no old/new dual path or compatibility shim exists.
- [ ] Plan tests evaluate every claim against exact task capability names and prove classification plus either investigator passes without requiring both.
- [ ] Final-output tests evaluate only present contract-backed claims against successful direct skills, preserve optional-removal/required-failure behavior, and perform tool-free retries.
- [ ] Planned, started, failed, cancelled, and unverified completions do not satisfy expressions; linked, step-loop-confirmed, and supported unplanned successes are credited exactly at their authoritative completion boundary.
- [ ] Repeated successes remain set-idempotent without hiding distinct execution trace events.
- [ ] Nested success/failure tests prove fresh child state, parent restoration, no grandchild leakage, successful public-child credit, and failed-child non-credit.
- [ ] `EVIDENCE_RECORDED` remains the event type; its payload contains `successfulSkill` and `successfulDirectSkills` and contains no legacy evidence-ID fields or aliases.
- [ ] Planning, direct advisor, and step-loop diagnostics agree on `unsatisfiedClaims`, `requiredExpression`, `satisfiedSkills`, and structured `unsatisfiedRequirements`, with no flattening of OR groups.
- [ ] Every author-facing semantic claim in the updated skill-authoring guidance is supported by a focused test/fixture/sample named in this plan; limitations about factual truth/workflow behavior remain clearly identified as limitations rather than untestable promises.
- [ ] All five sample root contracts load and their canonical claim expressions exactly match the ticket; sample prompts/business semantics remain otherwise unchanged.
- [ ] Existing protected Application API tests pass, no Supported SPI is introduced, and architecture tests classify only justified internal-public collaboration types without leaking them into API signatures.
- [ ] No persisted/historical compatibility tests, old trace readers, obsolete fixtures, overloads, or dual metadata formats are added.
- [ ] Focused starter tests, sample context tests, starter module tests, and the full Maven reactor pass.
- [ ] Manual diagnostic, prompt, trace, nested-boundary, sample-semantic, and documentation-evidence reviews are complete.
