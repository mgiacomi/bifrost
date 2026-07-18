# Replace Evidence-Type Mappings with Boolean Skill Expressions Implementation Plan

## Overview

Replace the development-stage, two-map evidence contract (`claims` plus `tool_evidence`) with claim-to-expression strings over exact direct child skill names. Compile each expression once while loading the YAML catalog, evaluate it against planned child names or successfully completed child names as appropriate, preserve nested mission isolation, and migrate every in-repository consumer atomically without a legacy reader or dual runtime representation.

## Current State Analysis

The existing contract makes authors define semantic evidence IDs and then separately map tools to those IDs. Catalog loading validates the two maps but does not verify that producers are direct `allowed_skills`; runtime planning converts task capability names to evidence IDs, execution records produced evidence IDs after successful calls, and final validation compares present claims to the current mission's evidence-ID set.

This indirection already encodes both conjunction and alternatives, but planning guidance and diagnostics flatten the possible producer tools. For example, a shared evidence ID produced by either investigator is rendered as one ambiguous list of tools, even though only one branch is required. The ticket replaces that model with a small monotonic Boolean language so the authored contract, deterministic validator, prompts, retries, and traces all describe the same requirement.

The current implementation also has two successful-call paths that must remain coherent: direct/model-driven callbacks record unplanned calls themselves, while step-loop calls defer recording until `PlanningService.markToolCompleted` confirms the bound task completed. Nested YAML execution snapshots the parent's ledger, starts the child with an empty ledger, restores the parent after the child returns, and then the parent's successful callback can credit the nested child's public skill name.

## Desired End State

Each `evidence_contract.claims` value is a YAML string containing a parsed Boolean expression over the declaring skill's exact direct `allowed_skills`. The catalog rejects non-string values, blanks, malformed syntax, reserved operator references, trailing input, non-direct names, and casing mistakes with resource/field/claim/column context. The normalized `EvidenceContract` retains immutable parsed expressions and canonical rendering; planning evaluates all claims against case-sensitive nonblank `PlanTask.capabilityName()` values, while final-output validation evaluates only present contract-backed fields against successfully completed direct child names.

The session ledger contains successful direct child skill names rather than synthetic evidence IDs. Planned, started, failed, cancelled, or merely visible children do not satisfy expressions. Nested missions receive a fresh set, do not leak internal child calls upward, and credit only the successfully completed nested child's public name at the parent callback boundary. Planning prompts, retry feedback, exceptions, and current-version trace metadata preserve AND/OR semantics and identify required expressions, satisfied skills, and structured unsatisfied requirements.

Verification is complete when the focused parser/catalog/evaluator/planning/execution/nesting tests and the sample catalog-loading coverage pass, the five sample contracts exactly match the ticket, legacy syntax is absent from executable/current guidance surfaces, and `./mvnw.cmd test` passes from the repository root.

### Key Discoveries

- `YamlSkillManifest.EvidenceContractManifest` currently normalizes `Map<String, List<String>>` values for both `claims` and `tool_evidence`, so changing only the Java generic type would not by itself guarantee rejection of YAML numbers/booleans that Jackson may coerce to strings (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java:294`).
- `YamlSkillCatalog#validateEvidenceContract` preserves case-insensitive claim collision/matching behavior but validates evidence IDs rather than parsing expressions or checking direct children (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:441`).
- `EvidenceContract` owns the normalized runtime lookup model, including case-insensitive claim lookup and the obsolete case-insensitive tool-to-evidence lookup (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContract.java:16`).
- `EvidenceCoverageValidator#validatePlanCoverage` evaluates every claim, but it collapses claim requirements and tool producers into evidence-ID sets and ambiguous supporting-tool lists (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceCoverageValidator.java:13`).
- Planning prompt construction explicitly says to use the flattened tool list, and planning trace metadata publishes `missingEvidence`; both must be replaced by canonical expressions and structured gaps (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/planning/DefaultPlanningService.java:522`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/planning/DefaultPlanningService.java:381`).
- Successful calls are credited in `DefaultPlanningService#markToolCompleted` for linked/step-loop work and in `DefaultToolCallbackFactory` for successful unplanned work; failures already avoid the credit path (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/planning/DefaultPlanningService.java:221`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/tool/DefaultToolCallbackFactory.java:85`).
- `ExecutionCoordinator` clears the ledger at every YAML mission start, while `CapabilityExecutionRouter` snapshots and restores the parent's ledger around nested YAML execution. Recording the nested capability after the router returns successfully therefore credits only the child's public boundary name (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionCoordinator.java:80`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/CapabilityExecutionRouter.java:73`).
- Candidate validation already distinguishes planning from final behavior: the output validator derives present top-level contract claims, whereas planning passes the full contract claim set (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceBackedOutputValidator.java:48`).
- Current authoring guidance documents the soon-to-be-obsolete evidence ontology and calls out the flattened-prompt discrepancy as a limitation, so this is an author-facing documentation migration rather than an internal-only refactor (`ai/skill-authoring/evidence-contracts.md:18`, `ai/skill-authoring/evidence-contracts.md:236`).
- Bifrost is explicitly pre-1.0 and the ticket approves replacing this manifest syntax. No production release, API/SPI allowlist, or verified external consumer was found that requires dual syntax; the in-repository manifests, fixtures, tests, and docs can be changed atomically (`ai/thoughts/framework-feature-design-lens.md:20`).

## What We're NOT Doing

- Adding `not`, `&&`, `||`, quoted/escaped identifiers, implicit operators, arbitrary predicates, output-value conditions, ordering, or workflow dependency semantics.
- Normalizing, trimming, case-folding, repairing, truncating, or aliasing a referenced skill name before matching it to `allowed_skills`.
- Treating evidence supportability as proof that a successful child result is factually correct.
- Inferring which optional output claims a planner will omit; planning continues to cover all declared claims.
- Exporting a nested mission's internal successful skills or allowing parent expressions to name grandchildren.
- Changing RBAC/visibility filtering, input/output schema behavior, sample business rules, or the support sample's separate prompt instruction to run every branch required by detected intents.
- Adding a legacy `tool_evidence` reader, overload, adapter, deprecated path, dual manifest shape, or synthetic evidence IDs behind the new syntax.
- Broadly renaming every class or trace record containing the word “evidence” when it still describes the evidence-contract feature; only evidence-ID-specific APIs and fields are replaced.

## Skill-Authoring Documentation Impact

**Impact**: Affected

- **Rationale**: Skill authors must replace a two-map ontology with a Boolean expression language and understand precedence, operator casing, exact skill-name matching, direct-child scope, plan versus execution truth sets, nested isolation, and the distinction between supportability and workflow control.
- **Documents to update**: `ai/skill-authoring/evidence-contracts.md`, `ai/skill-authoring/checklists/evaluate-a-skill-design.md`, `ai/skill-authoring/mental-model.md`, and the Evidence contracts row in `ai/skill-authoring/README.md`; also synchronize the general `README.md` and `bifrost-sample/README.md` examples and terminology.
- **Supporting evidence**: parser/evaluator tests, valid and invalid catalog fixtures, planning and final-output tests, successful/failing/unplanned callback tests, state/nested-router tests, all five migrated sample manifests, and `SampleApplicationTests` context loading establish the documented behavior.
- **Coverage table update**: Required. The existing row describes AND-only semantic evidence types and multiple producers; it must instead describe Boolean direct-child expressions, exact identity rules, enforcement, and nested isolation. Routing remains unchanged because `evidence-contracts.md` remains the focused topic.
- **LLM-first usability**: Rewrite the focused topic around an exact grammar, compact truth-set table, minimal AND/OR examples, deterministic authoring checklist, explicit prohibited syntax, diagnostics, and stable implementation/test anchors. Remove the obsolete ontology narrative and resolved “no Boolean language” limitation so an LLM loading only the routed evidence topic plus the mental model can author a valid contract without reconstructing runtime code.

## Contract and Compatibility Impact

| Surface | Classification and supporting evidence | Planned compatibility treatment |
| --- | --- | --- |
| Application API | No supported `com.lokiscale.bifrost.api` entry point changes. `SkillTemplate` invocation and observer behavior are unaffected. | Preserve. |
| Supported SPI | No supported customization point is changed. Evidence/runtime classes live under `internal`; public modifiers and cross-package use are technical exposure only. | Preserve supported SPI; atomically change internal signatures. |
| Configuration and manifest contracts | Affected: documented `evidence_contract.claims` changes from lists of author-defined IDs plus `tool_evidence` to string expressions over exact direct `allowed_skills`; startup validation and diagnostics also change. | Intentional pre-1.0 atomic break approved by the ticket. Reject old `tool_evidence` through strict unknown-property handling and migrate all current manifests, fixtures, samples, and guidance together. |
| Persisted or serialized contracts | No durable/cross-version evidence contract or session ledger format is promised. `BifrostSession` is a live in-process session and traces are current-version diagnostics. | No migration/legacy reader. Keep current runtime coherent. |
| Ephemeral diagnostic formats | Affected: evidence-recorded payloads, validation results, advisor/planning metadata, retry text, and exception messages currently expose evidence IDs and `missingEvidence`. | Replace atomically with canonical expressions, successful/satisfied skill names, `unsatisfiedClaims`, and structured `unsatisfiedRequirements`; retain useful failure visibility and current-run ordering. |
| Internal or accidentally exposed implementation | Affected: manifest DTO setters, `EvidenceContract`, coverage records/validator, planning service completion signature, execution state/session ledger and snapshot APIs, callback recording, advisor resolver metadata, and architecture allowlist entries. `ExecutionStateService` is an infrastructure bean, not an approved SPI. | Rename/remove obsolete evidence-ID methods and types in one repository change; update every internal caller and test. Add only the minimum internal-public expression types needed for cross-package collaboration. |

- **Evidence of supported contracts**: Current repository documentation and the approved ticket establish the YAML authoring contract. The framework design lens states that public modifiers, Spring beans, constructors, and tests alone do not establish API/SPI support; no contrary API/SPI allowlist or external consumer evidence was found.
- **Intended breaks**: Old list-valued `claims` and the `tool_evidence` property stop loading. Evidence-ID-oriented Java methods/records and current-version trace fields change with their in-repository callers.
- **In-repository consumers to update**: starter production code, architecture allowlist, catalog fixtures/tests, definition/advisor/planning/step-loop/tool/state/router/coordinator tests, five sample manifests, sample context coverage, root/sample READMEs, and skill-authoring guidance/checklist/mental model/coverage table.
- **Public-surface delta**: No Application API or Supported SPI delta. Internal public signatures lose evidence-type maps and the `EvidenceContract` argument on completion recording; the evidence package gains the minimal expression/parser/result structures required across internal packages, recorded in `BifrostPublicSurfaceArchitectureTest` as internal collaboration only.
- **Shim decision**: **No shim.** The ticket explicitly approves replacing development-stage manifest syntax, the repository has no production release, no protected external consumer was found, and dual syntax would preserve the conceptual indirection this work removes. Strict unknown-property rejection provides a visible migration failure.

## Implementation Approach

Implement from the contract boundary inward. First add an immutable AST/parser/canonical renderer and make catalog loading compile expressions exactly once with strict scalar and direct-child validation. Then reshape coverage results around expression truth and structured unsatisfied clauses, so planning and final validation share one evaluator without losing their different claim sets. Next replace the session ledger and all successful-call recording paths with direct child names while retaining the current callback/step-loop ownership and nesting snapshot boundary. Finally migrate fixtures, sample manifests, and authoring documentation, then run legacy searches and focused/full tests.

Use `and`-before-`or` recursive descent with whole-token operator recognition. Render the minimum parentheses necessary to preserve precedence, lowercase operators, and original case-sensitive skill identifiers. Expression evaluation should accept a `Set<String>`/predicate and produce both Boolean truth and a structured explanation: a missing leaf identifies that skill, a failed conjunction reports every still-required child clause, and a failed disjunction reports that any one of its alternative clauses would satisfy the group. Do not reduce nested expressions to a flat missing-skill list.

## Phase 1: Compile the Manifest Expression Language at Catalog Load

### Overview

Introduce the expression AST/parser/renderer and make catalog construction the single parsing and direct-child validation boundary. Preserve claim-name validation and canonical output-schema casing while rejecting every obsolete or malformed manifest form with actionable diagnostics.

### Changes Required

#### 1. Expression AST, parser, renderer, and parse diagnostics
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceExpression.java` (new)
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceExpressionParser.java` (new)
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceExpressionParserTest.java` (new)

**Changes**:
- Define a minimal immutable sealed/nested-node model for an exact skill reference, conjunction, and disjunction. Retain source columns on references/errors where needed for catalog diagnostics without exposing mutable parser state.
- Implement `expression -> or -> and -> primary` recursive descent, whole-token case-insensitive `and`/`or`, whitespace skipping only between tokens, `and` precedence, parentheses, and EOF enforcement.
- Reject missing operands, adjacent identifiers, unmatched parentheses, empty groups, unexpected punctuation, trailing tokens, and reserved operators where a primary/skill reference is expected. Do not accept quoted names or symbolic aliases.
- Canonically render lowercase operators and only the parentheses required by precedence/grouping. Flatten adjacent same-operator nodes if useful, while retaining equivalent truth and readable grouped diagnostics.
- Add focused tests for single references, repeated operators, precedence, parentheses, arbitrary operator casing, canonical rendering, operator substrings (`androidCheck`, `orderLookup`, `candyParser`), all malformed forms, EOF/trailing tokens, and reserved-word diagnostics with 1-based columns.

#### 2. Strict manifest scalar shape and removal of `tool_evidence`
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java`

**Changes**:
- Change `EvidenceContractManifest.claims` to an insertion-ordered immutable `Map<String, String>` and delete the `tool_evidence` field, getter, setter, and list-map normalization path.
- Preserve current claim-key trimming/collision behavior, but keep expression text intact; the parser alone ignores surrounding/inter-token whitespace and must never normalize characters within an identifier.
- Use strict Jackson scalar handling (for example, a claims-map content deserializer that accepts only `VALUE_STRING`) so YAML list, object, numeric, and Boolean values are rejected instead of coerced. Preserve null long enough to produce the specific nonblank-expression validation diagnostic.
- Retain `@JsonIgnoreProperties(ignoreUnknown = false)` so old `tool_evidence` fails manifest loading with its full property path.

#### 3. Catalog compilation, claim checks, and direct-child resolution
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContract.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillDefinition.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalogTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/skill/YamlSkillDefinitionTest.java`
- `bifrost-spring-boot-starter/src/test/resources/skills/valid/evidence-contract-skill.yaml`
- `bifrost-spring-boot-starter/src/test/resources/skills/valid/evidence-contract-hash-public-name-skill.yaml`
- `bifrost-spring-boot-starter/src/test/resources/skills/invalid/evidence-contract-*.yaml`

**Changes**:
- Refactor evidence validation to return the compiled `EvidenceContract` used by `YamlSkillDefinition`, avoiding a validation parse followed by a second runtime parse.
- Preserve `output_schema` requiredness, nonblank claim keys, case-insensitive claim-key collision rejection, case-insensitive top-level schema-property matching, and canonical schema property casing in the runtime contract.
- For each expression, require a nonblank string, parse the entire value, walk every reference, and require an exact case-sensitive member of the declaring manifest's `allowed_skills`.
- If an unknown reference has exactly one case-insensitive match in `allowed_skills`, reject it with a “did you mean” spelling; otherwise identify it as not a direct allowed child of the declaring skill. Never repair the reference.
- Wrap parser/scalar/reference failures in existing resource-aware catalog diagnostics with `evidence_contract.claims.<claim>`, canonical claim name, 1-based expression column/offset where available, and the specific fault.
- Replace obsolete blank-evidence/duplicate-tool fixtures with table-driven or focused fixtures for null/blank, list/object/non-string scalars, all parser failure classes, unknown/non-direct names, casing suggestions, reserved words, and old `tool_evidence`. Retain unknown-claim and case-colliding-claim coverage.
- Update definition tests and architecture allowlisting for the smallest new internal-public expression types; do not classify them as Application API or SPI.

### Success Criteria

#### Automated Verification
- [x] Parser and catalog tests pass: `./mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=EvidenceExpressionParserTest,YamlSkillCatalogTests,YamlSkillDefinitionTest test`
- [x] Valid expressions compile once into immutable runtime contracts with canonical output claim casing and canonical lowercase rendering.
- [x] Every malformed/scalar/direct-child/casing/reserved-word form produces a deterministic resource/field/claim diagnostic and old `tool_evidence` is rejected as unknown.
- [x] `BifrostPublicSurfaceArchitectureTest` passes with only explicitly justified internal collaboration types.

#### Manual Verification
- [ ] Review representative diagnostics at the start, middle, and end of expressions to confirm columns point to the offending token and suggestions preserve the exact allowed skill spelling.
- [ ] Review canonical rendering for nested mixed AND/OR expressions to confirm it communicates the authored truth conditions without redundant or missing parentheses.

---

## Phase 2: Evaluate Expressions and Preserve Their Semantics in Prompts and Diagnostics

### Overview

Replace evidence-set aggregation with a shared expression evaluator and structured unsatisfied requirements. Apply it to all-claim planning coverage and present-claim final coverage, then update prompts, retries, exceptions, advisor metadata, and step-loop/planning trace payloads so OR alternatives remain alternatives.

### Changes Required

#### 1. Runtime contract and coverage result model
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContract.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceCoverageValidator.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceCoverageResult.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceCoverageIssue.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceBackedOutputValidator.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContractTests.java`

**Changes**:
- Store canonical claim-to-expression mappings and retain case-insensitive candidate-field lookup only for canonical output claim resolution; remove evidence-by-tool, required-evidence union, and tool-name normalization APIs.
- Evaluate a reference only by exact membership in the supplied satisfied-name set. For plan coverage, build that set from nonblank task `capabilityName()` values without normalization and evaluate all contract claims. For final coverage, preserve the current schema-valid candidate behavior and evaluate only contract-backed top-level claims present in the candidate.
- Redesign coverage results/issues to contain evaluated/unsatisfied claims, canonical required expression, satisfied direct skills, and structured unsatisfied leaf/AND/OR requirements. Messages must distinguish “include tasks” for planning from “completed successfully” for final execution.
- Ensure explanation generation reports all outstanding conjunctive clauses but describes a disjunction as any one acceptable alternative, including nested alternatives of conjunctions; do not expose a flat `missingEvidence` or ambiguous `supportingTools` list.
- Expand evaluator tests around `classifyIncident and (investigateNetwork or investigateApp)`: either investigator plus classification passes, classification-only and investigator-only fail with the correct clause structure, and both investigators are not required. Also retain present optional claim removal and required-claim failure behavior.

#### 2. Planning prompt, validation retries, and planning traces
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/planning/DefaultPlanningService.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/planning/PlanningServiceTest.java`

**Changes**:
- Render one canonical expression per claim in the initial “Evidence Constraints” section and state that the plan must include tasks satisfying it. Remove producer-list reconstruction entirely.
- Feed structured expression-aware retry messages back into plan retries and preserve the existing quality-validation retry budget/order.
- Replace planning event `missingEvidence` metadata with `unsatisfiedClaims`, `satisfiedSkills`, and payload issues containing `requiredExpression` plus structured `unsatisfiedRequirements`.
- Add tests proving initial prompts and retry prompts show `classifyIncident and (investigateNetwork or investigateApp)`, accept either branch, reject incomplete conjunctions, preserve exact case sensitivity, evaluate every claim, and never tell the planner to run every OR alternative.

#### 3. Final advisor, step-loop validation, and current-version trace metadata
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContractCallAdvisor.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/BifrostEvidenceValidationException.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/chat/DefaultSkillAdvisorResolver.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/step/StepLoopMissionExecutionEngine.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/chat/SkillAdvisorResolverTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/step/StepLoopMissionExecutionEngineTest.java`

**Changes**:
- Keep the existing retry ordering: schema-valid candidate first, evidence validation second, output regeneration with no tool calls, optional unsupported claim removal allowed, and required unsupported fields eventually terminal.
- Rewrite retry hints and terminal messages in terms of successfully completed skills and canonical expressions. Preserve the existing skill prompt when augmenting the retry and retain bounded issue output.
- Align direct-advisor and step-loop evidence validation metadata on `unsatisfiedClaims`, `requiredExpression`, `satisfiedSkills`, and `unsatisfiedRequirements`; remove `missingEvidence` and `availableEvidence`/`evidence` names that imply evidence IDs.
- Update trace assertions deliberately as current-version ephemeral diagnostics; do not add legacy metadata aliases or readers.

### Success Criteria

#### Automated Verification
- [x] Evaluator, advisor, planning, and step-loop tests pass: `./mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=EvidenceContractTests,PlanningServiceTest,SkillAdvisorResolverTests,StepLoopMissionExecutionEngineTest test`
- [x] Plan validation evaluates every declared expression against exact task capability names; final validation evaluates only present contract-backed claims against successful names.
- [x] Initial prompts, retries, exceptions, and traces retain mixed AND/OR semantics and contain no `missingEvidence` or synthetic evidence-ID fields.
- [x] Evidence retries remain tool-free and retain their current retry-budget relationship with schema validation.

#### Manual Verification
- [ ] Read a planning prompt and final retry for the incident expression and confirm a developer/model would understand that classification plus either investigator is sufficient.
- [ ] Inspect one failed current-version trace and confirm it identifies the claim, full required expression, already satisfied skills, and whether all or any remaining clauses are required.

---

## Phase 3: Track Successful Direct Child Skills and Preserve Mission Isolation

### Overview

Replace the produced-evidence-ID session ledger with an exact set of successfully completed direct child skill names. Keep callback versus step-loop completion ownership, failure behavior, unplanned-call support, and nested mission snapshot/restore semantics unchanged except for the ledger's meaning.

### Changes Required

#### 1. Session, state service, snapshots, and evidence-recorded trace payload
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/state/ExecutionStateService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/state/DefaultExecutionStateService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/state/EvidenceSnapshot.java` (rename to a successful-skill snapshot name if that keeps the API coherent)
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/state/ExecutionStateServiceTest.java`

**Changes**:
- Rename the in-memory collection and state APIs from produced evidence types to successful direct child skill names. Store an insertion-preserving set; repeated successes remain idempotent.
- Replace `recordProducedEvidence(..., evidenceTypes)` with a single-success operation carrying capability name, linked task ID, and unplanned status. Record only after the capability returns successfully.
- Retain the existing `EVIDENCE_RECORDED` trace record type because the feature still records evidence-supporting mission progress. Replace payload `evidenceTypes`/generic `ledger` with `successfulSkill` and `successfulDirectSkills`; do not publish legacy payload aliases or a duplicate compatibility event. Update architecture allowlisting if the snapshot type is renamed.
- Update state tests to assert exact names, idempotence, snapshot restoration, and coherent trace payloads without evidence IDs.

#### 2. Planned, direct, and unplanned successful completion paths
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/planning/PlanningService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/planning/DefaultPlanningService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/tool/DefaultToolCallbackFactory.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/step/StepLoopMissionExecutionEngine.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/tool/ToolCallbackFactoryTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/planning/PlanningServiceTest.java`

**Changes**:
- Remove the `EvidenceContract` parameter and evidence lookup from `markToolCompleted`; after the bound task is verified/completed, record its exact capability name once.
- For model-directed calls without a step-loop-bound task, record successful unplanned calls in the callback; for step-loop-bound calls, continue deferring the record until the engine invokes `markToolCompleted` after the callback returns.
- Ensure linked direct calls and unplanned calls are both credited, while thrown/failed/cancelled calls and calls whose task cannot be completed are not. Add explicit tests for a successful unplanned call and a failed call.
- Pass the renamed successful-skill set into both direct advisor and step-loop final validation.

#### 3. Nested YAML mission boundary
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionCoordinator.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/CapabilityExecutionRouter.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/core/ExecutionCoordinatorTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/core/CapabilityExecutionRouterTest.java`

**Changes**:
- Clear successful direct skills at every YAML mission start, snapshot the parent's set before nested YAML execution, and restore it in `finally` for success and failure.
- Rely on the parent tool callback/completion path to add the nested child's public skill name only after the nested execution returns successfully. Never merge the child's internal set into the parent.
- Test that child internals are visible to the child contract only, parent state is restored, successful nested completion credits the nested public name, and failed nested completion credits nothing.

### Success Criteria

#### Automated Verification
- [x] State/callback/nesting tests pass: `./mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=ExecutionStateServiceTest,ToolCallbackFactoryTest,ExecutionCoordinatorTest,CapabilityExecutionRouterTest,PlanningServiceTest test`
- [x] Successful planned, step-loop-bound, direct, and supported unplanned calls record exact public skill names once; failed/cancelled calls do not.
- [x] A nested mission starts empty, cannot leak grandchildren, restores its parent set, and credits only its successfully completed public boundary name.
- [x] No runtime Java API or trace payload retains produced evidence IDs merely to emulate the removed syntax.

#### Manual Verification
- [ ] Inspect a nested incident execution trace and confirm parent validation sees `investigateNetwork` or `investigateApp`, never the specialist's internal probes.
- [ ] Confirm repeated successful calls do not duplicate ledger entries or change expression truth.

---

## Phase 4: Migrate Starter Fixtures and All Sample Contracts

### Overview

Convert every executable/test manifest to the new shape, preserve each sample's current AND/OR semantics exactly, and prove the sample application catalog loads all five migrated root contracts.

### Changes Required

#### 1. Starter tests and fixtures
**Files**:
- `bifrost-spring-boot-starter/src/test/resources/skills/valid/evidence-contract-*.yaml`
- `bifrost-spring-boot-starter/src/test/resources/skills/invalid/evidence-contract-*.yaml`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalogTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/skill/YamlSkillDefinitionTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContractTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/planning/PlanningServiceTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/tool/ToolCallbackFactoryTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/step/StepLoopMissionExecutionEngineTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/chat/SkillAdvisorResolverTests.java`

**Changes**:
- Replace programmatic `EvidenceContractManifest` list/tool mappings with expression strings and update assertions from evidence IDs to expressions/satisfied skills/structured gaps.
- Keep focused coverage for advisor order, schema-before-evidence behavior, retries, present claims, and case-colliding/unknown output claims while replacing obsolete blank-ID and duplicate-producer scenarios.
- Add parser/catalog/evaluator scenarios specified in the ticket rather than relying only on broad migrated tests.

#### 2. Exact five-sample migration
**Files**:
- `bifrost-sample/src/main/resources/skills/basics/duplicate_invoice_checker.yml`
- `bifrost-sample/src/main/resources/skills/incidents/handle_incident.yml`
- `bifrost-sample/src/main/resources/skills/insurance/process_claim.yml`
- `bifrost-sample/src/main/resources/skills/support/resolve_support_case.yml`
- `bifrost-sample/src/main/resources/skills/travel/plan_trip.yml`

**Changes**:
- Apply the ticket's claim expressions verbatim, including `classifyIncident and (investigateNetwork or investigateApp)` for incident cause and `understandIntent and (handleBilling or handleTechnical or handleHowTo) and composeReply` for support disposition/internal notes.
- Preserve all other manifest fields and prompts. In particular, do not weaken the support prompt's requirement to execute every branch indicated by multiple detected intents; the evidence expression remains only minimum claim supportability.

#### 3. Sample catalog loading coverage
**File**: `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleApplicationTests.java`

**Changes**:
- Strengthen context/catalog smoke coverage so the Spring test proves all five named root skills load with their migrated contracts (either by targeted catalog assertions within this internal repository or by an equivalent supported-facing registry check).
- Keep this deterministic and provider-independent; it should validate catalog construction, not execute LLM missions.

### Success Criteria

#### Automated Verification
- [x] Starter evidence-focused suite passes with expression fixtures and no old constructor/API usage.
- [x] Sample context loads all five migrated contracts: `./mvnw.cmd -pl bifrost-sample -am -Dtest=SampleApplicationTests -Dsurefire.failIfNoSpecifiedTests=false test`
- [x] All five sample YAML blocks exactly match the ticket's specified expressions and no current sample/test resource contains `tool_evidence` or list-valued evidence claims.
- [x] Support and incident alternatives are covered by assertions showing one acceptable specialist is sufficient without requiring all alternatives.

#### Manual Verification
- [ ] Compare each migrated sample expression against its previous claim/producer truth table to confirm no requirement was silently strengthened or weakened.
- [ ] Review the support prompt and evidence block together to confirm workflow branching guidance remains distinct from minimum supportability.

---

## Phase 5: Synchronize Documentation, Remove Legacy Terminology, and Verify the Repository

### Overview

Rewrite current documentation around the executable Boolean contract, update the AI-first routing/coverage metadata, remove obsolete evidence-type authoring guidance, and perform legacy searches plus focused and full Maven verification.

### Changes Required

#### 1. General and sample documentation
**Files**:
- `README.md`
- `bifrost-sample/README.md`

**Changes**:
- Replace examples with expression-valued claims and remove all instructions to define evidence types or `tool_evidence`.
- Document grammar/precedence, case-insensitive operators, case-sensitive exact child names, direct-child-only scope, planning versus successful-execution sets, nested isolation, conjunction/alternative examples, supportability versus truth, and the warning that expressions are not a workflow DSL.
- Rewrite sample tables/sections that refer to shared `investigation_digest`/`case_facts`, L2 producer keys, evidence tags, or AND-all claim lists into direct Boolean requirements over L2 child skill names.

#### 2. Skill-authoring knowledge base
**Files**:
- `ai/skill-authoring/README.md`
- `ai/skill-authoring/evidence-contracts.md`
- `ai/skill-authoring/checklists/evaluate-a-skill-design.md`
- `ai/skill-authoring/mental-model.md`

**Changes**:
- Rewrite `evidence-contracts.md` as the authoritative authoring topic with the exact grammar, truth-set table, validation rules, decision procedure, valid/invalid examples, nested boundary semantics, diagnostics, known limits, and updated implementation/test/sample anchors.
- Update the design checklist from evidence IDs/shared producers to direct child expressions, precedence/alternatives, exact identity, runtime visibility satisfiability, and the workflow-DSL boundary.
- Update the mental model's identity and nesting terminology from evidence producer keys/tags to references and successful direct child names.
- Update the README coverage row; keep existing routing unless the topic boundary changes. Ensure an LLM reading only the routed evidence topic and mental model can author, review, and diagnose the new contract without source inspection.

#### 3. Legacy searches and verification sequence
**Files**: Repository-wide current source, tests, fixtures, samples, and documentation.

**Changes**:
- Search for `tool_evidence`, evidence-type APIs/fields (`producedEvidenceTypes`, `evidenceProducedByTool`, `missingEvidence`, `requiredEvidence`, `availableEvidence`), list-valued `evidence_contract.claims`, and old shared-evidence terminology. Treat the original ticket and this plan as historical/planning artifacts that deliberately quote removed syntax; no executable fixture or current authoring/reference documentation may retain it.
- Run formatting/compilation through the repository's Maven lifecycle, then focused tests, sample context, architecture tests, and finally the full reactor test suite.
- Fix only regressions within this ticket's scope and preserve unrelated working-tree changes.

### Success Criteria

#### Automated Verification
- [x] Legacy searches find no old syntax or evidence-ID runtime API in production source, current tests/fixtures, samples, or current guidance (excluding the requirement ticket/implementation plan where removal is discussed).
- [x] Focused starter tests pass: `./mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=EvidenceExpressionParserTest,EvidenceContractTests,YamlSkillCatalogTests,YamlSkillDefinitionTest,PlanningServiceTest,ToolCallbackFactoryTest,ExecutionStateServiceTest,CapabilityExecutionRouterTest,ExecutionCoordinatorTest,SkillAdvisorResolverTests,StepLoopMissionExecutionEngineTest,BifrostPublicSurfaceArchitectureTest test`
- [x] Sample catalog smoke test passes: `./mvnw.cmd -pl bifrost-sample -am -Dtest=SampleApplicationTests -Dsurefire.failIfNoSpecifiedTests=false test`
- [x] Full reactor test suite passes from the repository root: `./mvnw.cmd test`
- [x] Updated authoring claims are backed by the cited parser/catalog/evaluator/planning/execution/nesting tests and migrated sample fixtures.
- [x] The skill-authoring coverage row and all routed documents satisfy the README's LLM-first checklist.

#### Manual Verification
- [ ] Review the root README, focused authoring topic, checklist, and each sample section for consistent terminology and no implication that every OR alternative must run.
- [ ] Walk one valid and one invalid manifest through the documented authoring procedure and confirm the documented result matches catalog behavior.
- [ ] Inspect representative planning, successful execution, failed execution, and nested trace records for accurate current-version expression/skill semantics and no sensitive-data or boundary regression.

---

## Testing Strategy

Create a dedicated testing-plan artifact with `ai/commands/3_testing_plan.md` before implementation. It should organize the ticket's matrix around the first failing parser/catalog tests, expression truth tables, successful-call state transitions, nested boundaries, prompt/diagnostic rendering, and the five sample catalog loads.

### Unit Tests

- Parser tokenization, precedence, parentheses, canonical rendering, operator casing/substrings, source columns, trailing input, reserved words, and every malformed grammar form.
- Catalog scalar shape, blank/null values, claim/schema matching, collision behavior, exact allowed-child references, helpful unique casing suggestions, and strict old-property rejection.
- Evaluator truth/explanation behavior for leaves, nested AND/OR, all-claim planning, present-claim final validation, exact case-sensitive satisfied sets, and optional versus required output fields.
- Session/state idempotence, trace payloads, planned/unplanned/failure recording, and snapshot restoration.

### Integration Tests

- Planning prompt and retries preserve canonical Boolean requirements and accept either OR branch.
- Direct advisor and step-loop final validation use successfully completed children only and never call tools during output retry.
- Nested YAML execution isolates the child set and credits only a successfully returned child boundary name to the parent.
- Sample Spring context loads all five exact migrated contracts.
- Architecture test confirms any new public Java types remain explicitly internal collaboration surfaces.

### Manual Testing Steps

1. Load a valid incident manifest and inspect its normalized expression rendering.
2. Load malformed/case-mismatched variants and verify resource, field path, claim, column, and suggestion text.
3. Inspect a plan containing classification plus one investigator and a plan missing one conjunct to compare prompt/retry explanations.
4. Inspect successful, failed, unplanned, and nested child traces to verify the successful-direct-skill ledger and boundary isolation.

## Performance Considerations

- Parse and validate each expression once during catalog construction; planning and final validation reuse immutable ASTs.
- Evaluation is linear in the expression size and uses set membership for exact skill names. The mission ledger remains a set, so repeated successful calls do not grow it.
- Canonical rendering may be cached with the compiled expression/contract if profiling shows repeated prompt/diagnostic rendering, but no extra cache is required initially because contracts and expressions are small.
- Do not introduce regex-based reparsing or repeated YAML/string compilation in planning/final-output hot paths.

## Migration Notes

- This is an intentional pre-1.0 breaking manifest migration. Authors must replace each evidence-ID list with its equivalent direct-child Boolean expression and delete `tool_evidence`.
- There is no compatibility window or runtime shim. Old manifests fail startup visibly through strict unknown-property/type handling.
- Trace and internal Java consumers must move atomically to successful direct skill names and expression-oriented result fields; no historical trace reader or serialized-session migration is required.
- The original ticket and this plan may retain old syntax solely to explain the migration. Current executable resources and author-facing reference documentation must not.

## References

- Original ticket: `ai/thoughts/tickets/replace-evidence-contract-evidence-types-with-boolean-skill-expressions.md`
- Framework compatibility policy: `ai/thoughts/framework-feature-design-lens.md`
- Planning procedure: `ai/commands/2_create_plan.md`
- Testing-plan procedure: `ai/commands/3_testing_plan.md`
- Current authoring topic: `ai/skill-authoring/evidence-contracts.md`
- Authoring routing/standard: `ai/skill-authoring/README.md`
- Source verification protocol: `ai/skill-authoring/source-verification.md`
- Current manifest model: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java:294`
- Current catalog validation: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:441`
- Current runtime contract/evaluator: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContract.java:16`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceCoverageValidator.java:13`
- Current successful-completion recording: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/planning/DefaultPlanningService.java:221`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/tool/DefaultToolCallbackFactory.java:85`
- Current nested boundary: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/CapabilityExecutionRouter.java:73`
