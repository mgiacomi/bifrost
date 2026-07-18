# Colocate Evidence with Output Schema Properties Implementation Plan

## Overview

Replace the separate top-level `evidence_contract.claims` manifest block with a strict `evidence` annotation on each supported immediate child of the root `output_schema.properties` map. Catalog loading will validate placement and scalar shape, compile the existing Boolean expressions once into the existing `EvidenceContract`, and leave planning, execution credit, output validation, retry, trace, and nested-mission semantics unchanged.

This is an intentional pre-1.0 authoring-contract replacement. All current manifests, fixtures, tests, samples, and authoring documentation will move atomically to the colocated syntax. The removed top-level field will receive no alias, compatibility reader, precedence rule, merge behavior, deprecation window, or tailored migration diagnostic.

## Current State Analysis

Authors currently repeat each supported output property name under `evidence_contract.claims`. `YamlSkillCatalog#validateEvidenceContract` joins that second claim namespace to root output properties case-insensitively, rejects duplicate or drifting claim keys, parses each expression, validates exact direct `allowed_skills` references, restores the schema property's authored casing, and creates the immutable runtime contract (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:441`).

`YamlSkillManifest.OutputSchemaManifest` is already the recursive typed schema dialect used for the root, object properties, and array items. It has no orchestration metadata today, but catalog schema validation already traverses every node with full paths such as `output_schema.properties.<name>` and `.items` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java:533`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:541`). Adding one Java field therefore makes deserialization possible at every depth; catalog validation must deliberately allow it only on immediate root properties.

The compiled boundary is already suitable for the replacement. `YamlSkillDefinition` carries an `EvidenceContract`; planning evaluates every compiled claim, successful execution records exact direct child names, candidate validation evaluates only present contract-backed fields after ordinary schema validation, and nested missions isolate child ledgers. Those consumers do not need a new runtime model (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillDefinition.java:15`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceCoverageValidator.java:15`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/step/StepLoopMissionExecutionEngine.java:854`).

The current output-schema consumers read only ordinary schema attributes and property-map keys. `OutputSchemaValidator`, `OutputSchemaPromptAugmentor`, and `StepPromptBuilder` do not serialize arbitrary DTO properties, so `evidence` can remain orchestration-only metadata; focused regression coverage must lock in that it neither becomes a candidate JSON field nor leaks as a provider/schema keyword (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/outputschema/OutputSchemaValidator.java:30`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/outputschema/OutputSchemaPromptAugmentor.java:33`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/step/StepPromptBuilder.java:297`).

## Desired End State

An LLM-backed YAML skill may attach one nonblank string `evidence` expression to any immediate root output property. Catalog construction uses the containing property-map key, with exact authored casing, as the claim name; parses the expression once; validates every reference against exact direct `allowed_skills`; and stores the result in the existing immutable `EvidenceContract`. A schema with no annotations produces `EvidenceContract.empty()`.

Root-node, nested-property, and array-item annotations fail startup with their complete `output_schema...evidence` paths. Null, blank, Boolean, numeric, list, and object values fail rather than being coerced. The root schema remains an object, ordinary schema validation ignores the annotation, prompts describe only the candidate JSON shape, and no current or future provider-schema path may pass the Bifrost-only annotation through accidentally.

Verification is complete when the starter catalog/manifest/output-schema tests cover the new shape and placement matrix, downstream runtime tests use the compiled contract without the removed manifest DTO, all five sample roots load with the exact ticket expressions colocated on their existing properties, current documentation teaches only the new syntax, stale active syntax searches pass, and `./mvnw.cmd test` succeeds from the repository root.

### Key Discoveries

- Strict unknown-field handling is already centralized in `YamlSkillCatalog#readManifest`; after removing the top-level binding, an old `evidence_contract` naturally fails with its ordinary unknown-field path and needs no special migration branch (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:257`).
- `StrictStringScalarDeserializer` currently protects map values. The new scalar field must reuse or adapt it so explicit YAML `null` and non-string token shapes are rejected with the property path, not accepted as absent or coerced (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java:398`).
- The existing schema traversal distinguishes root from non-root but not immediate-root-property from deeper nodes. The replacement needs an explicit placement context while preserving the existing schema paths (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:541`).
- The containing property map is already case-insensitively unique. Colocation removes the second claim namespace, the claim/property join, unknown-claim validation, and duplicate/case-colliding claim-map fixtures (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:765`).
- `EvidenceContract#fromManifest` is a second manifest-to-runtime construction path with test-only callers. Removing it and migrating narrow runtime tests to `EvidenceContract.compiled(...)` keeps catalog loading as the single production compiler and removes the obsolete manifest DTO cleanly (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContract.java:32`).
- Five sample manifests and their real-catalog smoke test already preserve the exact expected Boolean expressions, so relocating those strings can be verified without changing sample business semantics (`bifrost-sample/src/test/java/com/lokiscale/bifrost/testing/SampleEvidenceContractCatalogTest.java:20`).
- The routed authoring topic currently teaches `evidence_contract.claims`, making this an author-facing documentation change. The topic remains the correct route, but its manifest shape, diagnostics, procedure, limits, anchors, and examples must be rewritten (`ai/skill-authoring/evidence-contracts.md:12`, `ai/skill-authoring/README.md:25`).

## What We're NOT Doing

- Supporting `evidence` on the root schema, nested object properties, array item schemas, or arbitrary property paths.
- Adding per-array-item evidence, optional-ancestor rules, path notation, partial-object semantics, or nested candidate-presence detection.
- Changing expression grammar, precedence, canonical rendering, exact-name/case behavior, reserved operators, monotonic evaluation, or reference scope.
- Inferring requirements from `required`, descriptions, names, allowed skills, prompts, sibling properties, or candidate values.
- Treating successful execution as proof of factual correctness, field-level provenance, data lineage, authorization, ordering, dependency, workflow, or multi-intent completeness.
- Changing which optional fields the model emits, which claims planning evaluates, candidate retry limits, tool-call prohibition during evidence retry, trace content, successful-direct-child credit, or nested mission isolation.
- Renaming `EvidenceContract`, the expression AST/parser, coverage results, advisors, traces, or execution-state abstractions that remain semantically accurate.
- Adding a top-level compatibility reader, alias, merge/precedence rule, deprecation warning, custom migration error, legacy fixture suite, or dual syntax.
- Revising historical tickets, research, or completed plans merely to remove quoted old syntax.
- Changing unrelated sample prompts or business rules, especially the support prompt's instruction to execute every branch required by detected intents.

## Skill-Authoring Documentation Impact

**Impact**: Affected

- **Rationale**: Skill authors must move evidence expressions from a detached claim map onto immediate root output properties and understand that `evidence` is Bifrost orchestration metadata, not candidate JSON or unrestricted JSON Schema. Placement, strict scalar shape, direct-child expression rules, plan-versus-final truth sets, nested isolation, and supportability limitations are all author-facing behavior.
- **Documents to update**: `ai/skill-authoring/evidence-contracts.md`, `ai/skill-authoring/checklists/evaluate-a-skill-design.md`, `ai/skill-authoring/mental-model.md`, and `ai/skill-authoring/README.md`; synchronize the general `README.md` and `bifrost-sample/README.md` examples and terminology.
- **Supporting evidence**: Focused catalog tests and valid/invalid YAML fixtures will establish accepted syntax, scalar shape, placement, expression parsing, exact direct-child matching, casing suggestions, and removed-field rejection. `OutputSchemaCallAdvisorTest` and `StepPromptBuilderTest` will establish that the annotation is not a response field or prompt/schema keyword. Existing planning, advisor, retry, execution-credit, trace, and nested-boundary tests will establish unchanged runtime semantics. The five migrated sample manifests, `SampleEvidenceContractCatalogTest`, and `SampleApplicationTests` will establish representative authoring behavior through the real catalog and application context.
- **Coverage table update**: Required. Keep the existing evidence-contract routing and `Source-verified` confidence, but change the coverage note from a separate contract block to property-level annotations, supported placement, strict shape, direct-child Boolean expressions, enforcement, and nested isolation. No new topic row is needed because the authoring concern remains evidence supportability.
- **LLM-first usability**: Lead the routed topic with applicability and an exact complete-property example; add a compact placement table and plan/final truth-set table; state MUST/MUST NOT constraints for scalar shape, direct-child identity, and unsupported nesting; retain the grammar and limitations without historical narrative. An LLM loading only the routed evidence topic plus the mental model must be able to author, review, and diagnose the new syntax without source inspection.

## Contract and Compatibility Impact

| Surface | Classification and supporting evidence | Planned compatibility treatment |
| --- | --- | --- |
| Application API | No evidence or output-schema authoring declarations live under `com.lokiscale.bifrost.api`; `SkillTemplate` remains downstream of compiled catalog definitions. | Preserve; no API signature or invocation change. |
| Supported SPI | No documented evidence-specific customization point or supported replaceable bean was found; relevant validators and advisors are internal wiring. | Preserve; add no SPI or compatibility extension. |
| Configuration and manifest contracts | `evidence_contract.claims` is documented in the root README and routed authoring guide and is exercised by samples and fixtures. The ticket explicitly replaces it with `output_schema.properties.<name>.evidence`. | Intentional atomic break: update all active in-repository authors/consumers and reject the old field through ordinary strict unknown-field handling. |
| Persisted or serialized contracts | `EvidenceContract` is an in-process compiled value; no durable compiled evidence format or provider serialization of `OutputSchemaManifest` exists. | Preserve runtime value semantics; add no migration or versioned serialized form. Ensure any future provider schema serialization strips/translates `evidence`. |
| Ephemeral diagnostic formats | Catalog field paths change to `output_schema.properties.<name>.evidence`; runtime planning/evidence traces retain canonical expressions, satisfied direct skills, and structured gaps. | Update current-run startup diagnostics coherently; preserve runtime diagnostic usefulness, ordering, security boundaries, and trace semantics. No historical reader. |
| Internal or accidentally exposed implementation | `YamlSkillManifest.EvidenceContractManifest`, manifest accessors/bookkeeping, `EvidenceContract#fromManifest`, catalog compiler methods, and recursive output-schema DTOs live under `internal`; the architecture allowlist classifies public modifiers as internal collaboration. | Remove obsolete manifest APIs and update all repository callers atomically. Retain the compiled runtime types that still represent the feature accurately. |

- **Evidence of supported contracts**: `README.md`, `ai/skill-authoring/evidence-contracts.md`, sample manifests, catalog fixtures/tests, and the approved ticket establish the manifest contract. No documentation, API/SPI allowlist, or verified consumer establishes the removed Java DTO/helper as supported application API or SPI.
- **Intended breaks**: Remove the top-level `evidence_contract` YAML field and its detached `claims` namespace. Skill authors must put each unchanged expression on the corresponding immediate root output property. Startup diagnostic paths move accordingly. Bifrost has no release or supported legacy manifests, and the ticket expressly approves this pre-1.0 break.
- **In-repository consumers to update**: `YamlSkillManifest`, `YamlSkillCatalog`, `EvidenceContract`, `YamlSkillDefinition` tests, catalog fixtures/tests, runtime tests that construct `EvidenceContractManifest`, output-schema prompt/validation tests, five sample root manifests and sample smoke tests, root/sample READMEs, routed skill-authoring guidance/checklist/mental model, comments, diagnostic assertions, and internal architecture/public-surface expectations if removal changes the allowlist.
- **Public-surface delta**: Remove the public nested internal type `YamlSkillManifest.EvidenceContractManifest`, the internal manifest getter/setter, `Field.EVIDENCE_CONTRACT`, and public internal `EvidenceContract#fromManifest(...)`. Add public getter/setter methods for nullable `String evidence` on the public nested internal `OutputSchemaManifest`. No `com.lokiscale.bifrost.api` type, supported SPI, constructor, or Spring extension point changes.
- **Shim decision**: **No shim.** The ticket identifies a development-stage manifest replacement with no released or verified protected legacy consumers. Atomic repository migration is clearer than dual sources, and ordinary strict unknown-field rejection provides visible failure for stale manifests.

## Implementation Approach

Keep `EvidenceContract` as the runtime seam and move only its compilation source. Add a strict string `evidence` property to `OutputSchemaManifest`; teach the existing schema walk which node positions may carry it; and have output-schema validation return or otherwise supply the compiled contract built from the immediate root property map. Each accepted property's map key becomes the canonical claim name, eliminating detached claim-name normalization and joins.

The schema traversal should carry explicit placement state such as root, immediate-root-property, or nested. At every node, reject a non-null annotation unless the state is immediate-root-property. After ordinary structural checks for the root property, parse its annotation, validate referenced names against the declaring manifest's exact `allowed_skills`, and accumulate it into an insertion-ordered compiled map. Keep the current parser and reference-column helper unchanged unless a small extraction is needed to share the existing diagnostic behavior.

Strict binding must cover explicit YAML null as well as other non-string tokens. Reuse `StrictStringScalarDeserializer` on the scalar field and adapt its null handling (or apply an equivalent Jackson null-failure mechanism) so omitted `evidence` remains allowed while authored null fails with the full mapping path. Confirm this through fixtures rather than relying only on Java setter tests.

Remove the old manifest representation and the test-only `EvidenceContract#fromManifest` compiler once all callers have moved. Manifest/catalog tests should exercise the real YAML-to-catalog path; runtime-only tests should construct `EvidenceContract.compiled(...)` with parsed expressions through a focused test helper when catalog behavior is irrelevant. This keeps parsing at startup in production while allowing narrow runtime unit tests to remain isolated.

## Phase 1: Add Property-Level Manifest Binding and Catalog Compilation

### Overview

Establish the new authoring boundary, validate the complete placement and scalar-shape contract, compile annotations once during catalog construction, and remove the obsolete top-level manifest representation without changing runtime evaluation.

### Changes Required

#### 1. Output-schema manifest annotation and strict scalar handling
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java`

**Changes**:
- Add nullable `String evidence` to `OutputSchemaManifest`, using strict string-scalar deserialization and ordinary getter/setter behavior compatible with the record's Jackson defensive-copy path.
- Ensure omitted annotations remain null while explicit null, Boolean, number, list, and object YAML values fail binding rather than coercing or collapsing to absence. Adapt `StrictStringScalarDeserializer` null handling or use an equivalent field-level Jackson null policy, preserving resource-aware mapping paths from `readManifest`.
- Remove the top-level `evidenceContract` field, `getEvidenceContract`, `setEvidenceContract`, `EvidenceContractManifest`, `Field.EVIDENCE_CONTRACT`, and mapped-inapplicable bookkeeping for that removed field.
- Keep strict unknown-property behavior so old `evidence_contract` blocks fail normally. A mapped YAML skill still cannot declare `output_schema`, so property-level evidence needs no separate mapped-skill rule or diagnostic.

```java
public static class OutputSchemaManifest
{
    @JsonDeserialize(using = StrictStringScalarDeserializer.class)
    private String evidence;

    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }
}
```

#### 2. Integrate placement validation and compilation with output-schema validation
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java`

**Changes**:
- Replace the current `validateOutputSchema(...)` plus `validateEvidenceContract(...)` call sequence with one output-schema validation/compilation path that returns `EvidenceContract.empty()` when the schema is absent or no root property is annotated.
- Extend recursive schema validation with explicit placement context. Reject `evidence` on `output_schema`, nested object properties, array nodes/items, and any node other than an immediate entry of root `output_schema.properties`, using the full existing path plus `.evidence`.
- For each annotated immediate root property, require a nonblank string, parse the full expression with `EvidenceExpressionParser`, retain 1-based parser/reference columns, validate every reference against exact direct `allowed_skills`, and retain the existing unique case-insensitive spelling suggestion.
- Use the containing property-map key exactly as authored for the compiled claim name and canonical lookup. Remove the detached-map unknown-claim join, duplicate/case-colliding claim validation, and claim-key trimming/canonicalization.
- Preserve ordering: ordinary output-schema structure and annotation placement/compilation finish before linter validation and definition construction.

```java
EvidenceContract evidenceContract = validateAndCompileOutputSchema(resource, manifest);
validateLinter(resource, manifest);
```

#### 3. Remove the secondary manifest compiler while retaining the runtime contract
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContract.java`

**Changes**:
- Remove the import of `YamlSkillManifest` and `fromManifest(...)` after catalog and tests no longer call it.
- Retain `empty()`, `compiled(...)`, immutable insertion ordering, case-insensitive canonical claim lookup/present-field detection, expression access, and all runtime semantics.
- Do not move expression parsing into planning, advisors, or output validation.

### Success Criteria

#### Automated Verification

- [x] `OutputSchemaManifest` accepts an omitted annotation and a nonblank YAML string on an immediate root property.
- [x] Explicit null, blank, Boolean, numeric, list, and object annotation fixtures fail startup with `output_schema.properties.<claim>.evidence` paths.
- [x] Root, nested-property, and array-item annotations fail with complete paths and the immediate-root-only diagnostic.
- [x] Valid expressions compile with exact property casing, canonical Boolean rendering, exact direct-child validation, parser columns, and unique casing suggestions.
- [x] No annotation compiles `EvidenceContract.empty()`.
- [x] Old top-level `evidence_contract` fails through ordinary unknown-field handling; no compatibility branch exists.
- [x] Production code contains no `EvidenceContractManifest`, `getEvidenceContract`, `setEvidenceContract`, `Field.EVIDENCE_CONTRACT`, or `EvidenceContract.fromManifest` reference.
- [x] Focused manifest/catalog tests pass: `./mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests,YamlSkillEvidenceExpressionCatalogAdditionalTest,YamlSkillDefinitionTest,EvidenceContractTests test`

#### Manual Verification

- [x] Compare valid, missing, blank, wrong-case, malformed, root, nested, item, and old-field diagnostics for actionable skill/resource/field context.
- [x] Review the catalog flow to confirm every accepted expression is parsed exactly once at startup.

---

## Phase 2: Rebuild Fixtures and Lock the Schema-Dialect Boundary

### Overview

Move catalog fixtures to the colocated shape, replace obsolete detached-map cases with reachable property-level failures, and prove that orchestration metadata never becomes part of candidate JSON or prompt/provider schema instructions.

### Changes Required

#### 1. Valid and invalid catalog fixtures
**Files**:
- `bifrost-spring-boot-starter/src/test/resources/skills/valid/evidence-contract-skill.yaml`
- `bifrost-spring-boot-starter/src/test/resources/skills/valid/evidence-contract-hash-public-name-skill.yaml`
- Evidence-related resources under `bifrost-spring-boot-starter/src/test/resources/skills/invalid/`

**Changes**:
- Move valid expressions onto the corresponding root property definitions without dropping type, enum, format, description, nullability, item, or object-shape attributes.
- Rewrite invalid fixtures for unknown/non-direct child, wrong/ambiguous case, malformed/reserved expressions, and every invalid scalar shape at the new property path.
- Add focused root-schema, nested-object-property, and array-item placement fixtures with exact full diagnostic paths.
- Repurpose or delete impossible detached-map cases: unknown claim, duplicate/case-colliding claim keys, missing output schema solely for a separate contract, and duplicate tool/claim namespace cases.
- Retain one explicit old `evidence_contract` fixture to assert ordinary strict unknown-field rejection. Remove mapped-evidence-specific fixtures or convert them to the general mapped `output_schema` rejection already protected by mapped-field tests.

#### 2. Catalog and definition tests
**Files**:
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalogTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/skill/YamlSkillEvidenceExpressionCatalogAdditionalTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/skill/YamlSkillDefinitionTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContractTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java`

**Changes**:
- Assert compiled claim names and expressions originate from annotated root properties and retain exact schema casing.
- Update all diagnostic assertions to `output_schema.properties.<claim>.evidence`, except the one ordinary unknown-field assertion for removed `evidence_contract`.
- Add the full supported-placement and strict-scalar matrix, including explicit null.
- Update `YamlSkillDefinition` defensive-copy coverage to prove property-level evidence survives copies without mutating the source; keep the invariant that mapped definitions cannot carry a nonempty compiled contract.
- Refocus `EvidenceContractTests` on immutable compiled maps, canonical lookup, and present-claim behavior rather than manifest parsing.
- Update the architecture allowlist only if removal/addition of public internal methods changes its explicit internal-collaboration inventory.

#### 3. Candidate validation and prompt/schema leakage regression tests
**Files**:
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/outputschema/OutputSchemaCallAdvisorTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/step/StepPromptBuilderTest.java`

**Changes**:
- Add annotated schema properties to ordinary validation tests and prove candidate JSON is validated only against the property map/type rules: no `evidence` response member is required or accepted merely because it is metadata.
- Assert output-schema system guidance and step-loop final-response examples include the authored output property but do not render `evidence` as a field, constraint, or provider keyword.
- Preserve schema-before-evidence advisor/step-loop ordering tests.

### Success Criteria

#### Automated Verification

- [x] Every valid evidence fixture uses property-level syntax; no valid fixture contains `evidence_contract`.
- [x] Invalid fixtures cover every ticket scalar and placement case with exact paths.
- [x] Obsolete detached claim-map collision/unknown-claim fixtures are removed or repurposed rather than emulated.
- [x] Candidate validation and both direct/step prompt renderers ignore the annotation as orchestration metadata.
- [x] Architecture/public-surface assertions reflect the removed manifest DTO and added internal property accessors.
- [x] Focused schema/catalog suite passes: `./mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests,YamlSkillEvidenceExpressionCatalogAdditionalTest,YamlSkillDefinitionTest,EvidenceContractTests,OutputSchemaCallAdvisorTest,StepPromptBuilderTest,BifrostPublicSurfaceArchitectureTest test`

#### Manual Verification

- [x] Inspect rendered direct and step-loop prompts and confirm they show the normal output property shape without an `evidence` candidate field.
- [x] Review fixtures to ensure complete property definitions were preserved during relocation.

---

## Phase 3: Migrate Runtime-Focused Tests Without Changing Semantics

### Overview

Remove test dependence on the deleted authoring DTO while retaining the existing compiled-contract runtime boundary and regression coverage for planning, successful execution, retries, traces, and nested isolation.

### Changes Required

#### 1. Shared test construction of compiled evidence contracts
**Files**:
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/chat/SkillAdvisorResolverTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/chat/SkillAdvisorResolverEvidenceTraceTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContractAdvisorAdditionalTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/planning/EvidencePlanningIntegrationTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/planning/PlanningServiceTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/step/StepLoopMissionExecutionEngineTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/tool/ToolCallbackFactoryTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/tool/NestedSuccessfulSkillBoundaryTest.java`

**Changes**:
- Replace `EvidenceContractManifest` plus `fromManifest(...)` setup with narrow `EvidenceContract.compiled(...)` helpers using `EvidenceExpressionParser` where the test is about downstream runtime behavior.
- Prefer real catalog loading only in tests that assert authoring compilation or diagnostics; do not create a second production compiler merely for test convenience.
- Preserve assertion coverage for canonical AND/OR expressions, all-claim plan evaluation including optional claims, direct successful-child credit, no credit for failed/cancelled/blank/wrong-case names, repeated-call set behavior, candidate-present claims, retry without tools, schema/evidence retry separation, advisor ordering/traces, and nested mission isolation.

#### 2. Re-run unchanged runtime evidence suites as semantic guardrails
**Files**:
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceExpressionParserTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceExpressionParserAdditionalTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceCoverageValidatorTest.java`
- Existing planning, callback, state, step-loop, advisor, and nested-boundary tests found by repository search.

**Changes**:
- Do not rewrite parser/evaluator behavior for the authoring relocation.
- Adjust setup APIs only where deleted manifest types require it; preserve behavioral assertions and trace payload expectations.

### Success Criteria

#### Automated Verification

- [x] Runtime-focused tests contain no dependency on `EvidenceContractManifest` or `EvidenceContract.fromManifest`.
- [x] Expression grammar, precedence, canonical rendering, reserved-token behavior, and exact-name evaluation tests remain unchanged and pass.
- [x] Planning still evaluates every compiled claim and accepts either OR branch without requiring all alternatives.
- [x] Final validation still evaluates only annotated claims present in schema-valid candidate JSON and permits unsupported optional-field removal on retry.
- [x] Only successful direct children are credited, evidence retry performs no tool calls, and nested child internals remain isolated.
- [x] Focused runtime suite passes: `./mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=EvidenceExpressionParserTest,EvidenceExpressionParserAdditionalTest,EvidenceContractTests,EvidenceCoverageValidatorTest,EvidenceContractAdvisorAdditionalTest,EvidencePlanningIntegrationTest,PlanningServiceTest,SkillAdvisorResolverTests,SkillAdvisorResolverEvidenceTraceTest,ToolCallbackFactoryTest,NestedSuccessfulSkillBoundaryTest,StepLoopMissionExecutionEngineTest test`

#### Manual Verification

- [x] Review representative trace assertions for unchanged canonical expressions, satisfied direct skill sets, structured `all`/`any` gaps, and no nested-boundary leakage.
- [x] Confirm test helpers produce only compiled runtime contracts and are not reusable authoring paths in production code.

---

## Phase 4: Migrate the Five Sample Skills and Real-Catalog Smoke Coverage

### Overview

Move each ticket-specified expression onto its existing root output property, retain every other schema attribute and prompt/business rule, and exercise all five through the actual sample catalog and application context.

### Changes Required

#### 1. Evidence-bearing sample manifests
**Files**:
- `bifrost-sample/src/main/resources/skills/basics/duplicate_invoice_checker.yml`
- `bifrost-sample/src/main/resources/skills/incidents/handle_incident.yml`
- `bifrost-sample/src/main/resources/skills/insurance/process_claim.yml`
- `bifrost-sample/src/main/resources/skills/support/resolve_support_case.yml`
- `bifrost-sample/src/main/resources/skills/travel/plan_trip.yml`

**Changes**:
- Delete each top-level `evidence_contract` block and add the exact ticket expression to the corresponding immediate root property.
- Expand inline property definitions where needed so `type`, `nullable`, `enum`, `items`, `description`, and `evidence` remain unambiguous and valid YAML.
- Preserve property order/casing, requiredness, optional fields, `additionalProperties`, retry settings, prompts, allowed skills, and all business semantics.
- Keep the support prompt's all-detected-intents branch instruction distinct from the minimum OR evidence expression.

#### 2. Sample catalog and application tests
**Files**:
- `bifrost-sample/src/test/java/com/lokiscale/bifrost/testing/SampleEvidenceContractCatalogTest.java`
- `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleApplicationTests.java`

**Changes**:
- Retain the exact expected compiled claim/expression maps for all five skills and clarify method names if they still imply an earlier migration.
- Update raw manifest assertions to inspect `evidence: <expression>` within property definitions, not detached `claim: expression` entries that could match elsewhere.
- Continue loading the real catalog/application context so schema placement, direct-child validation, expression compilation, and all sample manifests are exercised together.

### Success Criteria

#### Automated Verification

- [x] All five samples compile the exact claim/expression maps specified by the ticket.
- [x] Search finds no `evidence_contract` under `bifrost-sample/src/main/resources/skills`.
- [x] Every migrated expression references an exact direct `allowed_skills` name.
- [x] Sample catalog/context tests pass: `./mvnw.cmd -pl bifrost-sample -am -Dtest=SampleEvidenceContractCatalogTest,SampleApplicationTests -Dsurefire.failIfNoSpecifiedTests=false test`

#### Manual Verification

- [x] Compare each before/after property to confirm no type, description, enum, nullable, required, item, or object-shape attribute was lost.
- [x] Review the support sample prompt and property annotations together to confirm workflow completeness remains prompt-level policy, not evidence semantics.

---

## Phase 5: Rewrite Authoring Guidance, Remove Stale Active Syntax, and Verify

### Overview

Synchronize all current author-facing guidance with executable behavior, update the skill-authoring coverage metadata, distinguish active references from historical artifacts, and run focused plus full reactor verification.

### Changes Required

#### 1. General and sample documentation
**Files**:
- `README.md`
- `bifrost-sample/README.md`

**Changes**:
- Replace all `evidence_contract.claims` examples and terminology with complete immediate-root-property examples.
- Explain that `evidence` is a Bifrost-only annotation, not an emitted JSON field or unrestricted provider JSON Schema keyword.
- Preserve the Boolean grammar summary, exact direct-child identity, planning versus final truth sets, nested isolation, supportability-not-truth boundary, and non-workflow limitations.
- Update mapped-skill guidance: mapped skills cannot declare `output_schema`, while an invoking LLM parent may annotate its own root output properties.
- Rewrite sample pattern tables/walkthroughs without implying evidence is a separate root block.

#### 2. Skill-authoring knowledge base
**Files**:
- `ai/skill-authoring/README.md`
- `ai/skill-authoring/evidence-contracts.md`
- `ai/skill-authoring/checklists/evaluate-a-skill-design.md`
- `ai/skill-authoring/mental-model.md`

**Changes**:
- Rewrite the focused evidence topic around property-level placement, strict string shape, exact grammar, a valid complete-property example, an explicit valid/invalid placement table, plan/final truth sets, authoring procedure, diagnostics, nested boundaries, schema-dialect boundary, known limits, and current implementation/test/sample anchors.
- Update the design checklist to ask whether each supported top-level output field carries the correct local annotation and whether nested use or workflow intent has been kept out of the expression.
- Update mental-model references from a separate evidence contract declaration to property annotations compiled into the mission's evidence contract; retain successful-direct-child and nested-boundary vocabulary.
- Update the README evidence coverage note while retaining the route and source-verified status. Validate the changed topic against the LLM-first checklist and avoid adding a redundant general output-schema topic.

#### 3. Stale syntax inventory and final verification
**Files**: Repository-wide current source, tests, fixtures, samples, and documentation.

**Changes**:
- Search for `evidence_contract`, `EvidenceContractManifest`, `getEvidenceContract`, `setEvidenceContract`, `EVIDENCE_CONTRACT`, and `EvidenceContract.fromManifest`.
- Permit old syntax only in the explicit unknown-field rejection fixture and historical ticket/research/plan artifacts, including this plan where the replacement is explained. Remove it from production code, valid fixtures, samples, current tests (apart from rejection assertions), and current authoring/reference documentation.
- Run focused starter and sample suites, then the full Maven reactor. Preserve unrelated working-tree changes and fix only regressions within this ticket.

### Success Criteria

#### Automated Verification

- [x] Current authoring documentation contains no supported example or instruction using `evidence_contract`.
- [x] The skill-authoring coverage row accurately describes property-level evidence and remains source-verified by the cited tests, fixtures, samples, and production path.
- [x] Repository searches leave old syntax only in the explicit rejection fixture/assertion and historical/planning artifacts.
- [x] Focused starter suite passes: `./mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests,YamlSkillEvidenceExpressionCatalogAdditionalTest,YamlSkillDefinitionTest,EvidenceContractTests,OutputSchemaCallAdvisorTest,StepPromptBuilderTest,EvidenceCoverageValidatorTest,EvidencePlanningIntegrationTest,SkillAdvisorResolverTests,SkillAdvisorResolverEvidenceTraceTest,ToolCallbackFactoryTest,NestedSuccessfulSkillBoundaryTest,StepLoopMissionExecutionEngineTest,BifrostPublicSurfaceArchitectureTest test`
- [x] Sample catalog/context suite passes: `./mvnw.cmd -pl bifrost-sample -am -Dtest=SampleEvidenceContractCatalogTest,SampleApplicationTests -Dsurefire.failIfNoSpecifiedTests=false test`
- [x] Full reactor suite passes from the repository root: `./mvnw.cmd test`
- [x] Changed skill-authoring guidance is supported by the cited focused tests, fixtures, samples, and production source.
- [x] `ai/skill-authoring/README.md` routing/coverage and every changed topic satisfy the LLM-First Authoring Standard.

#### Manual Verification

- [x] Give the routed evidence topic alone to a reviewer and confirm they can place, validate, and diagnose a property-level annotation without source inspection.
- [x] Inspect root/sample README snippets to ensure `evidence` appears alongside retained `type` and other schema attributes rather than looking like a replacement for them.
- [x] Review representative startup and runtime diagnostics for accurate paths, canonical expressions, direct-child names, structured gaps, and unchanged security/nesting boundaries.

---

## Testing Strategy

Create a dedicated testing-plan artifact with `ai/commands/3_testing_plan.md` before implementation. It should begin with failing catalog fixtures for the new valid syntax, removed old field, strict scalar shapes, and unsupported placements; then cover output-schema metadata leakage, runtime semantic guardrails, sample migrations, stale-syntax searches, and full reactor exit criteria.

### Unit Tests

- Manifest binding for omitted, valid string, blank, explicit null, Boolean, number, list, and object `evidence` values.
- Catalog placement for immediate root properties versus root, nested object properties, array schemas/items, and deeper item properties, with exact full paths.
- Expression parsing, trailing-token rejection, reference columns, exact direct-child matching, unique/ambiguous case handling, canonical rendering, and property-key claim casing.
- Empty contract behavior, immutable compiled maps, case-insensitive candidate-field lookup, and present-top-level-claim detection.
- Output-schema validation and direct/step prompt rendering with an annotated property but no leaked `evidence` response member or keyword.

### Integration Tests

- Real YAML catalog loading produces the same compiled contract consumed by planning and final validation.
- Planning covers all annotated properties, including optional ones, and correctly accepts either OR alternative.
- Final validation runs after schema validation, checks only present annotated fields, permits unsupported optional-field removal, exhausts required-field retries, and never calls tools during evidence retry.
- Successful direct-child tracking, advisor traces, step-loop retry accounting, repeated calls, wrong-case/failure non-credit, and nested mission isolation remain unchanged.
- All five sample manifests load through the real sample application catalog with exact ticket expressions.

### Manual Testing Steps

1. Load one valid annotated manifest and inspect the compiled contract for exact property casing and canonical expression rendering.
2. Load explicit-null, malformed, wrong-case, root, nested, item, and removed-top-level variants and compare their resource-aware diagnostic paths.
3. Inspect direct and step-loop output prompts to confirm `evidence` is absent from the candidate JSON shape.
4. Exercise one planning OR alternative, one missing conjunction, one unsupported optional candidate field, and one nested YAML child to compare unchanged runtime coverage/traces.
5. Review each sample diff for expression-only relocation and no business-schema or prompt change.

## Performance Considerations

- Parse every property annotation once during catalog construction and reuse the immutable expression AST through planning and final validation.
- The schema is already traversed at startup. Carrying placement/compilation context through that traversal avoids runtime work and can avoid an unnecessary second recursive pass.
- Compile into insertion-ordered maps and retain set membership for direct-child reference/evaluation checks; runtime complexity stays linear in the small expression/contract size.
- Do not add reflection-based annotation discovery, repeated schema serialization, regex reparsing, or per-plan/per-candidate YAML parsing.

## Migration Notes

- This is an approved pre-1.0 breaking change to the manifest contract. Move each existing claim expression unchanged onto its corresponding immediate root output property and delete the entire top-level `evidence_contract` block.
- There is no migration window or runtime shim. Stale manifests fail startup through strict unknown-field handling, and no special diagnostic is required beyond identifying `evidence_contract` as unknown.
- Internal Java/test callers move atomically from the removed manifest DTO/helper to catalog compilation or direct construction of an already compiled runtime contract, depending on the concern under test.
- No persisted data or historical trace migration is required. Historical tickets, research, and completed plans remain unchanged and may quote the removed syntax.
- Rollback, if needed before release, is a repository-wide revert of the atomic manifest/catalog/fixture/sample/documentation change; do not create dual syntax as a rollback mechanism.

## References

- Original ticket: `ai/thoughts/tickets/colocate-evidence-with-output-schema-properties.md`
- Supplied research: `ai/thoughts/research/2026-07-18-colocate-evidence-with-output-schema-properties.md`
- Framework compatibility policy: `ai/thoughts/framework-feature-design-lens.md`
- Planning procedure: `ai/commands/2_create_plan.md`
- Testing-plan procedure: `ai/commands/3_testing_plan.md`
- Skill-authoring routing and LLM-first standard: `ai/skill-authoring/README.md`
- Source verification protocol: `ai/skill-authoring/source-verification.md`
- Current authoring topic: `ai/skill-authoring/evidence-contracts.md`
- Current manifest model: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java:533`
- Current catalog compilation and schema traversal: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:402`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:441`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:541`
- Compiled runtime boundary: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContract.java:13`
- Runtime planning/final coverage: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceCoverageValidator.java:15`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceBackedOutputValidator.java:27`
- Output-schema consumers: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/outputschema/OutputSchemaValidator.java:30`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/outputschema/OutputSchemaPromptAugmentor.java:33`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/step/StepPromptBuilder.java:297`
- Sample catalog verification: `bifrost-sample/src/test/java/com/lokiscale/bifrost/testing/SampleEvidenceContractCatalogTest.java:20`
