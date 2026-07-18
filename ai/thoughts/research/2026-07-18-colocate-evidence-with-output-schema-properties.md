---
date: 2026-07-18T11:58:32-07:00
researcher: Codex
git_commit: 067e38a123a4d82a001763ac355040ba77846a6c
branch: main
repository: bifrost
topic: "Colocate evidence requirements with output schema properties"
tags: [research, codebase, yaml-skills, output-schema, evidence, catalog]
status: complete
last_updated: 2026-07-18
last_updated_by: Codex
---

# Research: Colocate Evidence Requirements with Output Schema Properties

**Date**: 2026-07-18T11:58:32-07:00
**Researcher**: Codex
**Git Commit**: 067e38a123a4d82a001763ac355040ba77846a6c
**Branch**: main
**Repository**: bifrost

## Research Question

Research the current codebase in support of `ai/thoughts/tickets/colocate-evidence-with-output-schema-properties.md`, which replaces the separate top-level `evidence_contract.claims` authoring structure with an `evidence` annotation on immediate root `output_schema.properties` entries while retaining the existing runtime evidence semantics.

## Summary

The current implementation has a distinct authoring layer and runtime layer. YAML authors declare a top-level `evidence_contract.claims` map. `YamlSkillCatalog` validates that map against the root output-schema property names and the declaring skill's exact direct `allowed_skills`, parses each Boolean expression once, restores the output property's canonical casing, and builds an immutable `EvidenceContract`. Planning, execution-state tracking, final-output validation, retries, advisors, and traces consume that compiled contract rather than the manifest DTO (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:441`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContract.java:13`).

`OutputSchemaManifest` is a recursive Java DTO used for root schemas, object properties, and array items. It currently contains type, properties, required fields, additional-properties behavior, items, enum, description, format, and nullability, but no evidence metadata (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java:533`). The catalog already walks this recursive structure with full field paths, which is the live validation boundary for distinguishing the root node, immediate root properties, nested properties, and array items (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:541`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:765`).

Runtime semantics already match the ticket's preserved behavior. Plan coverage evaluates every compiled claim against exact, nonblank plan capability names. Successful execution records exact direct child names only after successful completion. Candidate evidence validation occurs after ordinary output-schema validation and evaluates only contract-backed fields present in the candidate. Nested YAML missions isolate their successful-skill sets and expose only the successfully completed direct child name at the parent boundary (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceCoverageValidator.java:15`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/step/StepLoopMissionExecutionEngine.java:854`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/tool/DefaultToolCallbackFactory.java:140`).

The repository currently has five evidence-bearing sample manifests, two valid starter fixtures, numerous invalid evidence fixtures, Java tests that construct `EvidenceContractManifest`, and authoring documentation that names `evidence_contract`. The requested ticket therefore touches the configuration/manifest contract and its in-repository authoring consumers, while most runtime evidence classes remain downstream of the compiled `EvidenceContract` boundary.

## Detailed Findings

### 1. Current manifest representation and strict YAML boundary

- `YamlSkillManifest` is strict at every manifest DTO level through `@JsonIgnoreProperties(ignoreUnknown = false)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java:22`).
- The top-level field registry contains `Field.EVIDENCE_CONTRACT`, and the field is included in the ordered list of fields that mapped YAML wrappers may not declare (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java:25`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java:53`).
- The manifest binds `evidence_contract` to a nullable `EvidenceContractManifest` and records declaration presence in `setEvidenceContract`, including an explicitly authored YAML `null` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java:98`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java:254`).
- `EvidenceContractManifest` contains an insertion-ordered defensive `Map<String, String> claims`. Its values use `StrictStringScalarDeserializer`, which accepts only Jackson `VALUE_STRING` tokens and delegates every other token shape to Jackson's unexpected-token handling (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java:375`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java:392`).
- `OutputSchemaManifest` is recursive: `properties` maps names to `OutputSchemaManifest`, and `items` is another `OutputSchemaManifest`. Its collection setters preserve insertion order and expose immutable copies (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java:533`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java:558`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java:593`).
- `YamlSkillCatalog.readManifest` first parses the YAML into a tree for early name/mapped-wrapper checks, then converts it to `YamlSkillManifest`. Unknown fields become resource-aware `unknown field` diagnostics using Jackson's property path; other mapping failures also retain the Jackson-derived field path (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:257`).

### 2. Catalog loading, schema validation, and evidence compilation

- LLM-backed definitions validate input schema, then output schema, then compile the evidence contract, then validate the linter. Mapped definitions return earlier with `EvidenceContract.empty()` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:155`).
- Output-schema validation requires a root object, supports the restricted type set, supplies default output-schema retry configuration, and recursively validates object properties and array items with paths rooted at `output_schema` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:402`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:541`).
- Object validation iterates every property as `fieldPath + ".properties." + propertyName`; array validation recurses through `fieldPath + ".items"`. The current traversal therefore has the authored paths needed to identify root, immediate-property, nested-property, and item positions (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:765`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:817`).
- Current output-schema property names are unique case-insensitively, while `required` entries must match the property map exactly (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:779`).
- `validateEvidenceContract` returns `EvidenceContract.empty()` when the top-level manifest block is absent and requires `output_schema` when the block is present (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:441`).
- The method builds a lowercased root-property lookup, detects case-insensitive duplicate claim keys, joins each claim key to a root schema property, checks a nonblank expression, parses it, validates every referenced name against the exact direct `allowed_skills` set, and adds a casing suggestion only when the case-insensitive match is unique (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:453`).
- Compilation stores the output property's exact authored casing as the claim name and also builds a lowercased canonical-claim lookup before calling `EvidenceContract.compiled` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:515`).
- The catalog is the normal production compile-once path. `EvidenceContract.fromManifest` contains a second manifest-to-contract construction path used by tests; repository production searches found no production caller for it (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContract.java:32`).

### 3. Compiled expression and contract behavior

- `EvidenceExpressionParser` implements recursive descent in the order OR → AND → primary, so `and` binds more tightly than `or`; it enforces EOF and produces 1-based columns (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceExpressionParser.java:12`).
- Identifiers begin with a letter or underscore and continue with letters, digits, or underscores. Whole-token `and` and `or` are recognized case-insensitively as reserved operators; substrings remain identifiers (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceExpressionParser.java:94`).
- `EvidenceExpression` evaluates exact set membership, canonically renders lowercase operators with precedence-preserving parentheses, retains referenced skill names, and produces structured unsatisfied requirements for leaf, all-of, and any-of nodes (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceExpression.java:9`).
- `EvidenceContract` holds immutable insertion-ordered expression and canonical-name maps. Claim lookup and candidate field detection are currently case-insensitive through trimmed lowercase normalization (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContract.java:13`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContract.java:76`).
- `presentClaims` examines only the candidate object's top-level field names and returns only names that exist in the compiled contract (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContract.java:88`).

### 4. Planning semantics

- `DefaultPlanningService` obtains the already compiled contract from `YamlSkillDefinition` and passes it into plan prompt construction and deterministic plan coverage validation (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/planning/DefaultPlanningService.java:283`).
- `EvidenceCoverageValidator.validatePlanCoverage` collects every nonblank `PlanTask.capabilityName()` into an exact-name set and evaluates `contract.claims()`, so planning covers every compiled claim rather than predicting which optional output fields will be emitted (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceCoverageValidator.java:15`).
- An evidence gap participates in the existing bounded plan-quality retry loop. Retry feedback retains canonical expressions, and planning trace metadata includes evaluated claims, unsatisfied claims, required expressions, satisfied skills, and structured unsatisfied requirements (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/planning/DefaultPlanningService.java:320`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/planning/DefaultPlanningService.java:369`).

### 5. Successful direct-skill tracking and nested boundaries

- `DefaultPlanningService.markToolCompleted` records a successful skill only after it finds the bound task, verifies the exact capability binding, transitions the task to completed, and confirms the completed task remains present (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/planning/DefaultPlanningService.java:221`).
- `DefaultToolCallbackFactory` records an unplanned/model-directed capability after `CapabilityExecutionRouter.execute` returns successfully. Step-loop-bound executions defer recording to the planning-service completion path, avoiding an earlier callback credit (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/tool/DefaultToolCallbackFactory.java:140`).
- `DefaultExecutionStateService.recordSuccessfulSkill` adds the exact capability name to the session set and emits `EVIDENCE_RECORDED` with `successfulSkill` and the current `successfulDirectSkills` set (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/state/DefaultExecutionStateService.java:351`).
- `BifrostSession` stores successful direct names in a `LinkedHashSet`, making repeated successful calls idempotent for evidence truth (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java:521`).
- Nested-boundary tests establish current behavior: a successful nested call credits only its public boundary name, while a failed nested call restores the parent and credits neither the boundary nor child internals (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/tool/NestedSuccessfulSkillBoundaryTest.java:42`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/tool/NestedSuccessfulSkillBoundaryTest.java:59`).

### 6. Candidate validation, advisor ordering, and retries

- `EvidenceBackedOutputValidator` expects schema-valid JSON. It parses the candidate, derives top-level present claims through the compiled contract, and validates only those claims against successful direct skills (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceBackedOutputValidator.java:27`).
- The direct-call advisor list is assembled in output-schema, evidence, then linter order (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/chat/DefaultSkillAdvisorResolver.java:93`).
- The step-loop final-response path explicitly runs ordinary output-schema validation first, returns immediately on a schema failure, then runs evidence validation, then the linter (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/step/StepLoopMissionExecutionEngine.java:854`).
- Evidence retry instructions prohibit new tool calls and tell the model to use completed work, remove unsupported optional claims, or limit output to supported successful skills (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContractCallAdvisor.java:110`).
- Step-loop output-schema and evidence retry attempts are tracked separately. Evidence failures produce their own attempt/result path after schema success (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/step/StepLoopMissionExecutionEngine.java:972`).

### 7. Output-schema dialect consumers

- `OutputSchemaValidator` switches only on type and reads nullability, object properties, required fields, additional-properties behavior, array items, and string enum constraints. It does not generically interpret unknown DTO attributes as candidate fields or validator rules (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/outputschema/OutputSchemaValidator.java:30`).
- `OutputSchemaPromptAugmentor` renders only property-map keys plus normal type, nullable, array item, object child-name, enum, format, description, and requiredness information (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/outputschema/OutputSchemaPromptAugmentor.java:33`).
- `StepPromptBuilder` generates a JSON example from schema property-map keys and node types; it does not serialize the DTO or enumerate Java bean attributes (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/step/StepPromptBuilder.java:297`).
- A production search found no current serialization of `OutputSchemaManifest` into provider JSON Schema. Current output-schema consumers are prompt rendering and local validation. The repository's JSON Schema serialization paths apply to input/tool contracts instead (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/input/SkillInputContractResolver.java:74`).

### 8. Fixtures and Java test surface

- Valid catalog fixtures currently include `valid/evidence-contract-skill.yaml` and `valid/evidence-contract-hash-public-name-skill.yaml`; both use the separate top-level block.
- Invalid fixture coverage currently includes blank, null, Boolean, numeric, list, and object expression shapes; malformed/reserved expressions; non-direct and wrong-case references; ambiguous case matches; unknown claims; duplicate claim casing; missing output schema; obsolete `tool_evidence`; and mapped-wrapper declarations under `bifrost-spring-boot-starter/src/test/resources/skills/invalid/`.
- Catalog tests load and inspect the compiled contract and assert current `evidence_contract.claims.<claim>` diagnostics (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalogTests.java:653`). Additional catalog tests assert exact reference columns, reserved-token behavior, ambiguous casing, and the output-schema prerequisite (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/skill/YamlSkillEvidenceExpressionCatalogAdditionalTest.java:57`).
- Runtime coverage tests separately establish nested Boolean evaluation, non-flattened alternatives, all-claim planning, and present-claim final validation (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceCoverageValidatorTest.java:23`).
- Planning integration tests establish that either investigator satisfies the disjunction and that trace data retains structured Boolean requirements (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/planning/EvidencePlanningIntegrationTest.java:39`).
- Advisor-order tests assert output schema → evidence → linter (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/chat/SkillAdvisorResolverTests.java:65`). Step-loop tests assert schema/evidence retry-budget separation (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/step/StepLoopMissionExecutionEngineTest.java:300`).
- Several focused tests construct `YamlSkillManifest.EvidenceContractManifest` and call `EvidenceContract.fromManifest` directly, including definition, advisor, planning, tool-callback, step-loop, and nested-boundary tests. Tests concerned only with evaluator behavior already construct `EvidenceContract.compiled` directly (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceCoverageValidatorTest.java:124`).

### 9. Sample manifests and sample catalog coverage

The five current evidence-bearing sample manifests are:

- Duplicate invoice checker: five claims over `invoiceParser` and `expenseLookup` (`bifrost-sample/src/main/resources/skills/basics/duplicate_invoice_checker.yml:11`).
- Incident handling: six claims over `classifyIncident`, the investigator alternative, and `draftIncidentResponse` (`bifrost-sample/src/main/resources/skills/incidents/handle_incident.yml:31`).
- Insurance claim processing: seven claims over the four root specialists (`bifrost-sample/src/main/resources/skills/insurance/process_claim.yml:31`).
- Support case resolution: six claims over intent understanding, handler alternatives, and reply composition (`bifrost-sample/src/main/resources/skills/support/resolve_support_case.yml:41`).
- Trip planning: six claims over preferences, transport, stay, and itinerary assembly (`bifrost-sample/src/main/resources/skills/travel/plan_trip.yml:27`).

`SampleEvidenceContractCatalogTest` loads all five through the real catalog (`bifrost-sample/src/test/java/com/lokiscale/bifrost/testing/SampleEvidenceContractCatalogTest.java:20`). `SampleApplicationTests` also loads the application context and checks representative manifest text (`bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleApplicationTests.java:25`, `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleApplicationTests.java:30`).

### 10. Documentation surface

- The root README uses `evidence_contract.claims` in its primary YAML example, field reference, identity guidance, and mapped-wrapper discussion (`README.md:154`, `README.md:169`, `README.md:209`, `README.md:218`).
- `ai/skill-authoring/evidence-contracts.md` is the focused authoring reference. It documents the current manifest block, expression grammar, exact direct-child names, planning/final truth sets, nested isolation, diagnostics, and known limits (`ai/skill-authoring/evidence-contracts.md:12`, `ai/skill-authoring/evidence-contracts.md:22`, `ai/skill-authoring/evidence-contracts.md:90`).
- The authoring coverage table classifies evidence contracts as source-verified, while the design checklist and mental model link evidence behavior to direct-child supportability and nested isolation (`ai/skill-authoring/README.md:50`, `ai/skill-authoring/checklists/evaluate-a-skill-design.md:80`, `ai/skill-authoring/mental-model.md:138`).
- `bifrost-sample/README.md` names the top-level block throughout its pattern table and incident, insurance, support, and travel walkthroughs (`bifrost-sample/README.md:13`, `bifrost-sample/README.md:253`, `bifrost-sample/README.md:342`, `bifrost-sample/README.md:499`, `bifrost-sample/README.md:610`).

## Contract and Compatibility Classification

The classifications below use the exact categories from `ai/thoughts/framework-feature-design-lens.md`.

| Surface | Current exposure and evidence | Current classification status |
| --- | --- | --- |
| Application API | No evidence/output-schema declarations were found under `com.lokiscale.bifrost.api`; `SkillTemplate` invocation is downstream of catalog definitions. | No affected Application API found. |
| Supported SPI | No evidence-specific `@ConditionalOnMissingBean`, supported customization interface, or documented replacement point was found. Validators/advisors are instantiated inside internal services. | No affected Supported SPI found. |
| Configuration and manifest contracts | Root README, focused skill-authoring guidance, samples, fixtures, and catalog diagnostics document and enforce `evidence_contract.claims`. | Deliberately supported author-facing manifest behavior; this is the primary affected contract. |
| Persisted or serialized contracts | `EvidenceContract` is an in-process compiled value. No durable manifest-compiled contract or provider output-schema serialization was found. | No deliberate durable/cross-version evidence format found. |
| Ephemeral diagnostic formats | Planning/advisor/step-loop traces expose canonical expressions, satisfied skills, issues, and structured requirements through current trace records. | Current-run diagnostic behavior. |
| Internal or accidentally exposed implementation | `YamlSkillManifest`, `YamlSkillDefinition`, `EvidenceContract`, parser/evaluator/coverage types, advisors, planning services, state services, and output-schema validators live under `internal`. The architecture test explicitly describes their public visibility as internal cross-package collaboration (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:114`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:132`). | Explicitly internal despite public Java modifiers and constructors. |

Technical exposure exists through public constructors and methods on internal classes, production cross-package imports, and focused tests. The repository's architecture allowlist supplies affirmative evidence that those Java surfaces are internal collaboration rather than Application API or Supported SPI. No verified in-repository use treats `EvidenceContractManifest` as an application-facing Java contract; its callers are catalog code and tests.

## Architecture Documentation

The current component flow is:

```text
YAML evidence_contract.claims
        |
        v
YamlSkillManifest.EvidenceContractManifest
        |
        v
YamlSkillCatalog.validateEvidenceContract
  - joins claim keys to root output properties
  - parses Boolean expressions
  - validates exact direct allowed_skills
        |
        v
YamlSkillDefinition.evidenceContract
        |
        +--> DefaultPlanningService / EvidenceCoverageValidator
        |      evaluates every compiled claim against planned capability names
        |
        +--> successful-direct-skill session set
        |      records exact names only after successful direct completion
        |
        +--> EvidenceBackedOutputValidator
               evaluates present top-level claims after schema validation
```

The ticket's new authoring location intersects this flow only above the compiled `EvidenceContract` boundary: the recursive output-schema DTO and catalog traversal become the discovery source, while the existing planning, successful-skill, evaluator, retry, trace, and nested-isolation consumers continue to receive a compiled contract.

## Historical Context (from ai/thoughts/)

- `ai/thoughts/plans/2026-07-17-replace-evidence-contract-evidence-types-with-boolean-skill-expressions.md` documents the immediately preceding migration from evidence-type producer maps to Boolean expressions over direct child names. Its completed state establishes the current parser, immutable contract, successful-direct-skill set, structured traces, nested isolation, five sample expressions, and no-shim pre-1.0 posture.
- `ai/thoughts/plans/2026-07-17-replace-evidence-contract-evidence-types-with-boolean-skill-expressions-testing.md` maps the focused parser, catalog, planning, execution, advisor, trace, nested-boundary, and sample verification surface introduced with that migration.
- `ai/thoughts/tickets/replace-evidence-contract-evidence-types-with-boolean-skill-expressions.md` is the requirement source for the current Boolean language and runtime truth sets.
- `ai/thoughts/framework-feature-design-lens.md:17` defines the pre-1.0 compatibility categories used above. It distinguishes technical exposure from deliberately supported contracts and treats documented manifest behavior as a configuration/manifest contract.
- The `ai/thoughts/research/` directory contained no earlier research documents at the time of this research.

## Related Research

No prior research documents were present in `ai/thoughts/research/`.

## Open Questions

- The repository metadata script emitted date, commit, branch, and repository but no HumanLayer thoughts-status researcher because that optional command was unavailable; this document records the active researcher as Codex.
- No external consumer inventory is stored in this checkout. The current classification therefore records verified in-repository usage and documentation evidence only.

## Code References

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java:25` - declared-field bookkeeping, including the current top-level evidence field.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java:375` - current evidence manifest DTO and strict string values.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java:533` - recursive output-schema DTO.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:257` - strict Jackson/catalog error boundary.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:402` - output-schema validation entry point.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:441` - current evidence compilation entry point.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:541` - recursive output-schema node validation.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillDefinition.java:15` - immutable catalog definition carrying the compiled contract.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContract.java:13` - compiled runtime representation.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceExpressionParser.java:12` - unchanged Boolean grammar implementation.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceCoverageValidator.java:15` - plan and final truth-set evaluation.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/outputschema/OutputSchemaValidator.java:30` - ordinary candidate validation.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/step/StepLoopMissionExecutionEngine.java:854` - schema-before-evidence final validation.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/state/DefaultExecutionStateService.java:351` - successful direct-skill recording and trace event.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:132` - internal public-surface classification.
