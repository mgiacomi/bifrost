# Colocate evidence requirements with output schema properties

Status: Ready for implementation

## Summary

Replace the separate top-level `evidence_contract.claims` authoring structure with an `evidence` attribute declared directly on each supported top-level `output_schema` property.

Current syntax:

```yaml
evidence_contract:
  claims:
    severity: classifyIncident
    category: classifyIncident
    likelyCause: classifyIncident and (investigateNetwork or investigateApp)
    evidenceSummary: investigateNetwork or investigateApp
    recommendedAction: investigateNetwork or investigateApp
    userMessage: draftIncidentResponse

output_schema:
  type: object
  properties:
    severity:
      type: string
      enum: [SEV1, SEV2, SEV3, SEV4]
    category:
      type: string
      enum: [network, application, mixed, unknown]
    likelyCause: { type: string }
    evidenceSummary: { type: string }
    recommendedAction: { type: string }
    userMessage: { type: string }
  required: [severity, category, likelyCause, evidenceSummary, recommendedAction, userMessage]
  additionalProperties: false
```

Replacement syntax:

```yaml
output_schema:
  type: object
  properties:
    severity:
      type: string
      enum: [SEV1, SEV2, SEV3, SEV4]
      evidence: classifyIncident
    category:
      type: string
      enum: [network, application, mixed, unknown]
      evidence: classifyIncident
    likelyCause:
      type: string
      evidence: classifyIncident and (investigateNetwork or investigateApp)
    evidenceSummary:
      type: string
      evidence: investigateNetwork or investigateApp
    recommendedAction:
      type: string
      evidence: investigateNetwork or investigateApp
    userMessage:
      type: string
      evidence: draftIncidentResponse
  required: [severity, category, likelyCause, evidenceSummary, recommendedAction, userMessage]
  additionalProperties: false
```

This is an authoring-syntax replacement, not a change to evidence semantics. Keep `EvidenceContract` as the compiled runtime representation unless implementation work reveals a concrete reason to rename or replace it. Catalog loading should extract `evidence` annotations from the output properties and compile the same Boolean expressions currently compiled from `evidence_contract.claims`.

This is also a replacement rather than an additional equivalent syntax. Remove the top-level `evidence_contract` manifest field and migrate all repository examples, sample application configs, fixtures, tests, and documentation. Do not maintain two permanent sources for the same requirement.

## Development-stage compatibility posture

Bifrost is still in development and has had no releases. There are no released manifests, external compatibility commitments, or supported legacy consumers to migrate. Treat this ticket as one atomic repository change:

- replace the authoring syntax directly;
- update all in-repository configs, fixtures, tests, samples, and documentation together;
- do not add a migration path, compatibility reader, deprecation window, fallback, alias, merge rule, or dual-syntax support;
- do not spend implementation effort producing a special migration diagnostic for `evidence_contract`.

After the top-level binding is removed, an old `evidence_contract` declaration may fail through the catalog's ordinary strict unknown-field handling. Identifying the unsupported field is sufficient; no tailored instruction to move claims is required.

## Motivation

An evidence requirement applies to a specific output assertion. The current authoring model declares that relationship in a separate map, forcing authors and reviewers to correlate two sections and repeat every covered property name:

```yaml
evidence_contract:
  claims:
    likelyCause: classifyIncident and (investigateNetwork or investigateApp)

output_schema:
  properties:
    likelyCause:
      type: string
```

The catalog then performs a case-insensitive join between the claim key and a top-level schema property, restores canonical schema casing, rejects duplicate claim keys, and reports drift. Colocation makes the relationship structural instead:

```yaml
output_schema:
  properties:
    likelyCause:
      type: string
      evidence: classifyIncident and (investigateNetwork or investigateApp)
```

Benefits:

- each output property is declared once;
- renaming a property cannot leave a detached evidence-map key behind;
- type, description, enum, nullability, requiredness, and evidence support are reviewable in one place;
- the catalog no longer needs to join two author-facing maps or canonicalize claim keys;
- it is visually obvious which output properties have deterministic supportability enforcement and which do not;
- the runtime can retain its existing evidence evaluation, planning, retry, trace, and mission-isolation behavior.

`evidence` remains a supportability requirement, not proof of factual correctness or field-level data lineage. It means that the named direct child skills must have completed successfully before the model may assert that output property. It does not prove that the returned value was copied from, calculated from, or factually justified by those child results.

## Decisions already made

- The property attribute is named exactly `evidence`.
- `evidence` is a Bifrost output-schema annotation. It is not an output field and is not part of the candidate JSON instance.
- In this ticket, `evidence` is supported only on immediate children of the root `output_schema.properties` map.
- Nested object properties, array item schemas, the root schema node, and other schema positions must reject `evidence` with an actionable startup diagnostic.
- The value is one nonblank YAML string containing the existing Boolean evidence expression language.
- Expressions reference exact public names from the declaring skill's direct `allowed_skills`.
- Expression grammar, precedence, canonical rendering, case rules, and runtime truth sets do not change.
- The top-level `evidence_contract` field is removed from the supported manifest syntax.
- Do not support simultaneous embedded and top-level declarations, precedence rules, merging, or silent fallback.
- Existing `EvidenceContract`, `EvidenceExpression`, coverage result, advisor, planning, trace, and successful-direct-skill abstractions may remain internally.
- Planning continues to evaluate every evidence-annotated top-level property, including optional properties.
- Final-output validation continues to evaluate only evidence-annotated top-level properties present in the schema-valid candidate.
- Mapped YAML skills remain unable to use evidence requirements. They already cannot declare `output_schema`, so no new mapped-skill behavior is needed.
- `evidence` must not be forwarded to a model/provider as though it were a standard JSON Schema validation keyword.

## Schema-dialect boundary

`output_schema` is Bifrost's typed and deliberately restricted schema model, not an arbitrary pass-through JSON Schema document. Adding `evidence` makes that dialect boundary explicit.

Treat `evidence` as orchestration metadata attached to a schema property:

- `OutputSchemaValidator` must ignore it when checking the JSON value's type, enum, format, object shape, array shape, required fields, and additional properties.
- Output-schema prompt rendering may continue to describe the property's normal schema characteristics. It must not accidentally tell the model to emit an `evidence` JSON field.
- Any present or future serialization of `OutputSchemaManifest` into provider JSON Schema must strip or deliberately translate the Bifrost-only annotation. Do not assume every provider accepts unknown schema keywords.
- Evidence planning guidance and evidence retry diagnostics should continue to be generated by the evidence subsystem using the compiled `EvidenceContract`, not by treating `evidence` as a JSON Schema validator rule.

The annotation name is intentionally the concise `evidence`, rather than `evidence_claim`: the schema property is the claim, while the annotation states the evidence requirement for asserting it.

## Supported placement and shape

Valid:

```yaml
output_schema:
  type: object
  properties:
    result:
      type: string
      evidence: primaryLookup and (fraudCheck or manualReview)
```

Invalid on the root node:

```yaml
output_schema:
  type: object
  evidence: primaryLookup
  properties:
    result: { type: string }
```

Invalid on a nested object property:

```yaml
output_schema:
  type: object
  properties:
    result:
      type: object
      properties:
        detail:
          type: string
          evidence: primaryLookup
```

Invalid on an array item schema:

```yaml
output_schema:
  type: object
  properties:
    results:
      type: array
      items:
        type: object
        evidence: primaryLookup
        properties:
          id: { type: string }
```

This placement restriction preserves the current contract's top-level-claim semantics. Supporting nested claim paths would require decisions about partial object assertions, array members, path notation, optional ancestors, retry removal, issue reporting, and candidate-presence detection. Those are explicitly outside this migration.

The `evidence` scalar must be a YAML string. Reject null, Boolean, numeric, list, object, and blank values rather than relying on coercion. Reuse or adapt `StrictStringScalarDeserializer` so strict scalar behavior remains covered by tests.

## Expression language remains unchanged

Continue using the effective grammar already implemented by `EvidenceExpressionParser`:

```text
expression     := or_expression EOF
or_expression := and_expression (OR and_expression)*
and_expression := primary (AND primary)*
primary        := SKILL_NAME | "(" expression ")"
```

Preserve all current rules:

- `and` binds more tightly than `or`;
- parentheses override precedence;
- operator tokens are case-insensitive and render canonically in lowercase;
- skill names are case-sensitive and must exactly match a direct `allowed_skills` entry;
- surrounding and inter-token whitespace is insignificant;
- operator substrings in names such as `androidCheck`, `orderLookup`, and `candyParser` remain identifiers;
- `and` and `or` are reserved as whole tokens in every casing;
- `not`, `&&`, `||`, quoted identifiers, implicit operators, predicates, and output-value conditions remain unsupported;
- expressions remain monotonic and name-only.

Do not modify parser or evaluator behavior merely because the expression's manifest location changes.

## Manifest model changes

Update `YamlSkillManifest` as follows:

1. Add a nullable `String evidence` field to `OutputSchemaManifest` with strict string-scalar deserialization and an ordinary getter/setter consistent with the class's copy behavior.
2. Remove the top-level `evidenceContract` field and its `@JsonProperty("evidence_contract")` binding.
3. Remove `EvidenceContractManifest` when no production or test code still requires it.
4. Remove `Field.EVIDENCE_CONTRACT` and its mapped-skill explanation/declared-field bookkeeping.
5. Preserve strict unknown-property handling.

Because `OutputSchemaManifest` is recursive, merely adding a field to the Java type makes the YAML key syntactically deserializable at every schema depth. Catalog validation must therefore enforce the root-property-only placement rule after deserialization.

An old manifest containing `evidence_contract` must not load or silently compile through another path. Because Bifrost has had no releases, this is not a legacy-compatibility scenario: ordinary strict unknown-field rejection is sufficient, and no custom migration diagnostic or compatibility reader is required.

## Catalog compilation and validation

Replace `YamlSkillCatalog.validateEvidenceContract(Resource, YamlSkillManifest)` with logic that discovers evidence annotations while validating the output schema and compiles them into an `EvidenceContract`.

For every immediate root output property with an `evidence` value, startup validation must verify:

1. The root output schema is an object with a valid `properties` map under existing schema rules.
2. The evidence value is a nonblank YAML string scalar.
3. The complete expression parses successfully; trailing tokens are rejected.
4. Every referenced skill exactly matches an entry in the declaring skill's `allowed_skills`.
5. Every reference is therefore a direct allowed child of the declaring LLM-backed YAML skill.
6. Incorrect reference casing remains an error; when exactly one case-insensitive allowed-skill match exists, suggest the correct spelling.
7. Reserved operator-token behavior remains unchanged.

For every schema node outside the immediate root properties, reject a non-null `evidence` annotation. Diagnostics should include the full schema path, such as:

```text
output_schema.evidence
output_schema.properties.result.properties.detail.evidence
output_schema.properties.results.items.evidence
```

Suggested messages:

```text
Invalid YAML skill '<name>' field 'output_schema.properties.likelyCause.evidence': expression must be a nonblank YAML string.
Invalid YAML skill '<name>' field 'output_schema.properties.likelyCause.evidence': invalid evidence expression at column 22: expected a skill name or '('.
Invalid YAML skill '<name>' field 'output_schema.properties.likelyCause.evidence': skill 'investigatenetwork' is not a direct allowed child; did you mean 'investigateNetwork'?
Invalid YAML skill '<name>' field 'output_schema.properties.result.properties.detail.evidence': evidence is currently supported only on immediate root output properties.
```

Use the containing property-map key as the canonical claim name. This eliminates the old case-insensitive join and duplicate-claim-key validation because there is no second claim-name namespace. Preserve the schema property's exact authored casing in the compiled contract and all diagnostics.

If no root property declares `evidence`, compile `EvidenceContract.empty()`. An `output_schema` without evidence continues to work exactly as it does now.

Parse expressions once during catalog construction. Do not reparse the schema annotation during planning or final output validation.

## Runtime behavior that must not change

This ticket changes where requirements are authored, not what they mean.

### Plan coverage

- Build the compiled contract from all root properties containing `evidence`.
- Evaluate every compiled claim during plan validation, including evidence-annotated optional output properties.
- Evaluate expressions against the set of nonblank, case-sensitive `PlanTask.capabilityName()` values.
- Require the proposed plan to include enough tasks to satisfy each expression.
- Keep canonical Boolean expressions in planning prompts and retry feedback; do not flatten OR alternatives into a list that implies every alternative is required.

### Successful execution coverage

- Credit a direct child name only after that child completes successfully in the current YAML mission.
- Planned, started, failed, cancelled, merely visible, wrong-case, or blank capability names do not satisfy final validation.
- Repeated successful calls remain one set member.
- Preserve parent/child mission isolation: child internals do not bubble into the parent, and a successfully completed nested YAML child credits only its public direct-child name at the parent boundary.

### Candidate-output validation and retries

- Run evidence validation only after ordinary output-schema validation has produced schema-valid JSON, as today.
- Evaluate only evidence-annotated top-level fields that are present in the candidate.
- Allow an unsupported optional property to be removed during evidence retry.
- A required property with unsatisfied evidence ultimately fails after retry exhaustion.
- Evidence retry must use already completed work and must not call tools again.
- Preserve structured coverage issues, advisor traces, required canonical expressions, satisfied direct skills, and unsatisfied requirement groups.

### Non-evidence properties

A root output property without `evidence` is governed only by the ordinary schema and other configured validation. Do not infer an evidence requirement from its description, requiredness, name, sibling properties, allowed skills, or prompt.

## Required `bifrost-sample` config migration

Update every current evidence-bearing YAML config under `bifrost-sample/src/main/resources/skills`. The sample application must contain no supported config using the removed `evidence_contract` structure after this ticket is complete.

Move each expression onto the corresponding top-level output property without changing the expression or business semantics.

### Duplicate invoice checker

File: `bifrost-sample/src/main/resources/skills/basics/duplicate_invoice_checker.yml`

Apply these annotations to the existing property definitions:

```yaml
vendorName:
  evidence: invoiceParser
invoiceDate:
  evidence: invoiceParser
totalAmount:
  evidence: invoiceParser
isDuplicate:
  evidence: invoiceParser and expenseLookup
reasoning:
  evidence: invoiceParser and expenseLookup
```

Retain each property's existing `type`, `description`, `format`, `enum`, `nullable`, and other schema attributes; the abbreviated snippet above lists only the annotation being added.

### Incident handling

File: `bifrost-sample/src/main/resources/skills/incidents/handle_incident.yml`

```yaml
severity:
  evidence: classifyIncident
category:
  evidence: classifyIncident
likelyCause:
  evidence: classifyIncident and (investigateNetwork or investigateApp)
evidenceSummary:
  evidence: investigateNetwork or investigateApp
recommendedAction:
  evidence: investigateNetwork or investigateApp
userMessage:
  evidence: draftIncidentResponse
```

### Insurance claim processing

File: `bifrost-sample/src/main/resources/skills/insurance/process_claim.yml`

```yaml
disposition:
  evidence: assessCoverage and fraudScreen and recommendDisposition
payableAmount:
  evidence: assessCoverage
coverageSummary:
  evidence: assessCoverage
fraudRisk:
  evidence: fraudScreen
matchedExclusions:
  evidence: assessCoverage
rationale:
  evidence: extractClaimFacts and assessCoverage and fraudScreen and recommendDisposition
evidenceNotes:
  evidence: extractClaimFacts and assessCoverage and fraudScreen
```

### Support case resolution

File: `bifrost-sample/src/main/resources/skills/support/resolve_support_case.yml`

```yaml
intents:
  evidence: understandIntent
disposition:
  evidence: understandIntent and (handleBilling or handleTechnical or handleHowTo) and composeReply
refundRecommended:
  evidence: handleBilling or handleTechnical or handleHowTo
factsSummary:
  evidence: handleBilling or handleTechnical or handleHowTo
draftReply:
  evidence: composeReply
internalNotes:
  evidence: understandIntent and (handleBilling or handleTechnical or handleHowTo) and composeReply
```

Do not change the separate prompt instruction that all branches required by detected intents should run. Evidence remains minimum claim supportability rather than a general workflow DSL.

### Trip planning

File: `bifrost-sample/src/main/resources/skills/travel/plan_trip.yml`

```yaml
summary:
  evidence: assembleItinerary
transport:
  evidence: planTransport
hotel:
  evidence: planStay
estimatedTotal:
  evidence: assembleItinerary
rationale:
  evidence: understandPreferences and planTransport and planStay and assembleItinerary
openQuestions:
  evidence: understandPreferences and assembleItinerary
```

After migration:

- search all `bifrost-sample/src/main/resources/skills` YAML files for `evidence_contract` and confirm none remain;
- verify every migrated `evidence` expression still references an exact direct `allowed_skills` name;
- load the sample skill catalog or start the relevant sample application context so schema placement and expression compilation are exercised;
- run existing sample tests in addition to the starter tests;
- do not change unrelated sample prompts or business rules while moving the annotations.

## Starter fixtures and test migration

Migrate every valid evidence fixture under `bifrost-spring-boot-starter/src/test/resources/skills` by moving each claim expression into its corresponding root output property.

Replace or rewrite invalid fixtures so they target the new paths. Examples include:

- unknown/non-direct child reference;
- wrong-case child reference and unambiguous casing suggestion;
- ambiguous case-insensitive child spelling;
- blank evidence scalar;
- null evidence scalar;
- Boolean, numeric, list, and object evidence values;
- malformed Boolean expressions;
- reserved operator reference;
- evidence on the root output schema;
- evidence on a nested object property;
- evidence on an array `items` schema;
- ordinary strict unknown-field rejection for the removed top-level `evidence_contract` field.

Old fixtures whose only purpose was duplicate or case-colliding claim-map keys are obsolete because the schema property map is now the single claim namespace. Remove or repurpose them rather than manufacturing an impossible embedded equivalent.

Update Java tests that currently construct `EvidenceContractManifest`. Prefer exercising catalog compilation from `OutputSchemaManifest` properties where the test is about manifest behavior. Tests focused solely on runtime evidence evaluation may continue constructing `EvidenceContract.compiled(...)` directly when that keeps them narrow.

At minimum, inspect and deliberately update tests in these areas found by repository search:

- YAML catalog loading and invalid-manifest diagnostics;
- `YamlSkillDefinition` invariants and defensive copies;
- advisor resolution and evidence trace tests;
- planning prompt and plan-coverage integration tests;
- evidence expression/catalog scalar-shape tests;
- evidence-backed output validation and retry tests;
- step-loop mission execution tests;
- tool callback successful-completion tests;
- nested mission isolation tests;
- architecture/public-surface allowlists if removed manifest types affect them.

## Documentation migration

Update all repository documentation and examples that show or describe the separate section, including at minimum:

- `README.md`;
- `ai/skill-authoring/evidence-contracts.md`;
- any skill-authoring checklists or guidance that mention `evidence_contract` or claim maps;
- `bifrost-sample/README.md` if it describes evidence authoring;
- all comments and error-message assertions found by repository search.

Documentation must explain:

- `evidence` is placed on an immediate root output property;
- its value is the existing Boolean expression over direct allowed child skills;
- it enforces supportability rather than factual truth or data lineage;
- planning evaluates all evidence-annotated properties, while final validation evaluates only annotated properties present in the candidate;
- nested schema use is currently unsupported;
- nested mission execution still credits only successful direct children;
- `evidence` is not a workflow, ordering, dependency, authorization, or output-value condition language;
- the former `evidence_contract` syntax has been removed; because Bifrost has had no releases, no migration guidance or compatibility period applies.

Rewrite examples to show complete property definitions where clarity matters. Avoid documentation snippets that appear to replace existing `type` or other schema attributes with `evidence`.

## Likely implementation locations

The implementing context must search the current checkout before editing. The current feature is centered in:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java`;
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java`;
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillDefinition.java`;
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContract.java`;
- output-schema validation and prompt-rendering code under `internal/outputschema`;
- planning prompt and coverage code under `internal/runtime/planning`;
- evidence advisor, trace, successful-completion, step-loop, and nested-mission tests;
- YAML fixtures under `bifrost-spring-boot-starter/src/test/resources/skills`;
- the five sample configs listed above.

The desired implementation should be localized primarily to manifest parsing, catalog compilation, fixtures, configs, and documentation. Broad rewrites of evidence runtime behavior are a warning sign because runtime semantics are intended to remain stable.

## Non-goals

- Nested output-property evidence or path-based claims.
- Array-element or per-item evidence.
- Inferring evidence requirements from schema descriptions or prompts.
- Proving a child result is factually correct.
- Inspecting tool output values to decide whether an expression is satisfied.
- Field-level provenance or data-flow tracking.
- Workflow ordering, dependencies, branch policy, or multi-intent completeness.
- `not`, predicates, symbolic Boolean operators, or other expression-language expansion.
- Referencing grandchildren or leaking nested mission internals into a parent.
- Changing which optional claims the model chooses to emit.
- Changing output-schema retry limits or evidence retry behavior.
- Supporting both embedded and top-level evidence syntax indefinitely.
- Renaming the internal evidence subsystem solely for cosmetic consistency.
- Changing sample business semantics beyond relocating existing expressions.

## Acceptance criteria

- A top-level output property accepts a nonblank string `evidence` annotation.
- The annotation is compiled once into the existing Boolean evidence runtime model using the property's exact name as the claim.
- An output schema with no `evidence` annotations produces an empty evidence contract and behaves as before.
- Root-schema, nested-property, and array-item uses of `evidence` fail startup with full, actionable field paths.
- Null, blank, Boolean, numeric, list, and object evidence values fail startup rather than being coerced.
- Malformed expressions and invalid direct-child references retain actionable parser columns and casing suggestions.
- The top-level `evidence_contract` syntax is no longer accepted and there is no migration path, compatibility reader, dual-syntax merge, or precedence logic.
- `EvidenceContractManifest`, its manifest field/accessors, and obsolete declared-field bookkeeping are removed when no longer referenced.
- Ordinary output-schema validation ignores the annotation as orchestration metadata and never expects an `evidence` member in candidate JSON.
- Output prompts and any provider schema serialization do not leak `evidence` as a response field or unsupported provider keyword.
- Plan coverage, successful-direct-child tracking, final candidate coverage, retries, diagnostics, traces, and nested mission isolation retain their current behavior.
- All five evidence-bearing `bifrost-sample` configs are migrated to property-level `evidence` with the exact expressions specified in this ticket.
- The sample catalog/application context loads successfully with the migrated configs.
- Repository search finds no active YAML config using `evidence_contract` in `bifrost-sample` or starter valid fixtures.
- Remaining `evidence_contract` text is limited to an ordinary unknown-field rejection fixture, this development-stage replacement ticket, and historical ticket/plan artifacts where rewriting history is inappropriate.
- Focused manifest, catalog, schema, planning, advisor, execution, retry, trace, and nested-boundary tests pass.
- The complete Maven suite passes from the repository root:

  ```powershell
  .\mvnw.cmd test
  ```

## Suggested implementation sequence

1. Search the entire repository for `evidence_contract`, `EvidenceContractManifest`, `getEvidenceContract`, and `setEvidenceContract` to establish the exact current surface.
2. Add strict `evidence` parsing to `OutputSchemaManifest` and focused deserialization tests.
3. Add catalog placement validation and compile root-property annotations into `EvidenceContract`.
4. Update `YamlSkillDefinition` construction paths and remove the obsolete top-level manifest representation.
5. Confirm runtime plan and final-output tests still pass without semantic changes.
6. Rewrite valid and invalid starter YAML fixtures and their diagnostic assertions.
7. Migrate all five `bifrost-sample` configs exactly as listed above and exercise sample catalog loading/startup.
8. Update README and skill-authoring documentation.
9. Search for stale active syntax, obsolete APIs, and misleading terminology.
10. Run focused starter tests, sample tests, and then the full root Maven suite.

Preserve unrelated work in the repository. Avoid broad mechanical rewrites outside this ticket's scope, and do not revise historical research/planning artifacts merely to make repository-wide text search empty; distinguish active configuration and documentation from historical records.
