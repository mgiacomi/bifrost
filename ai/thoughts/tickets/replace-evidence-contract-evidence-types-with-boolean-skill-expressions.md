# Replace evidence-type mappings with Boolean skill expressions

Status: Ready for implementation

## Summary

Replace the current two-part `evidence_contract` representation—claim-to-evidence-type mappings plus `tool_evidence` producer mappings—with Boolean expressions written directly over the declaring skill's allowed direct child skills.

The new authoring model should make requirements read like ordinary developer logic:

```yaml
evidence_contract:
  claims:
    severity: classifyIncident
    category: classifyIncident
    likelyCause: classifyIncident and (investigateNetwork or investigateApp)
    evidenceSummary: investigateNetwork or investigateApp
    recommendedAction: investigateNetwork or investigateApp
    userMessage: draftIncidentResponse
```

This is a replacement for the development-stage syntax, not an additional equivalent syntax. Remove `tool_evidence` and migrate all repository examples, fixtures, tests, and documentation. Do not carry both representations through the runtime unless a concrete external compatibility constraint is discovered. If such a constraint is found, document it and stop for a compatibility decision rather than silently introducing permanent dual syntax.

## Motivation

The current syntax makes authors mentally join two maps:

```yaml
claims:
  likelyCause: [incident_classification, investigation_digest]
tool_evidence:
  classifyIncident: [incident_classification]
  investigateNetwork: [investigation_digest]
  investigateApp: [investigation_digest]
```

Its executable meaning is:

```text
classifyIncident and (investigateNetwork or investigateApp)
```

Developers already understand Boolean logic. Expressing it directly is easier to read, review, and diagnose than encoding it as configuration indirection.

The current planning guidance also flattens all tools associated with a claim into one list and says the plan must use the listed "tool(s)." That wording loses both conjunction and alternatives and can encourage unnecessary execution of every OR branch. The new model must preserve and display the actual expression.

## Decisions already made

- Expressions reference direct child skill names, not author-defined evidence type identifiers.
- Supported operators are `and` and `or`.
- Operators are case-insensitive: `and`, `AND`, `And`, and other casing variants are equivalent. The same applies to `or`.
- Skill names remain case-sensitive and must match their public YAML names exactly.
- Canonical documentation and rendered expressions use lowercase operators.
- `and` has higher precedence than `or`.
- Parentheses override precedence.
- `not` is not supported. Evidence is monotonic: completing more successful work must not make a previously satisfied claim unsupported.
- `and` and `or`, in any casing, are reserved tokens inside evidence expressions. Produce an explicit reserved-word diagnostic when an expression attempts to use one as a skill reference.
- An operator is recognized only as a complete token. Names such as `androidCheck`, `orderLookup`, and `candyParser` remain ordinary case-sensitive skill identifiers.
- The evidence ledger remains local to a YAML mission boundary. A parent expression may reference a direct nested child skill, but not the child's internal tools.
- Planning validation continues to evaluate all contract claims, matching current behavior.
- Final-output validation continues to evaluate only contract-backed claims present in the schema-valid candidate output.

## Expression language

Implement a small parser with this effective grammar:

```text
expression     := or_expression EOF
or_expression := and_expression (OR and_expression)*
and_expression := primary (AND primary)*
primary        := SKILL_NAME | "(" expression ")"
OR             := case-insensitive whole token "or"
AND            := case-insensitive whole token "and"
```

Skill names currently follow Bifrost's public skill-name rules. Do not normalize, trim, repair, truncate, or case-fold a skill reference before matching it to `allowed_skills`. Surrounding expression whitespace is insignificant, but characters inside an identifier are not changed.

Examples:

```text
a or b and c       == a or (b and c)
(a or b) and c     == (a or b) and c
a AND (b Or c)     == a and (b or c)
```

Do not add quoted identifiers or symbolic `&&`/`||` aliases in this ticket. They are not required by the agreed authoring model and would expand the grammar and documentation without adding needed capability.

The AST can be minimal and immutable, with nodes equivalent to:

- a case-sensitive skill reference;
- conjunction of two or more expressions;
- disjunction of two or more expressions.

Flattening adjacent nodes of the same operator is acceptable, but preserve precedence and grouping when rendering diagnostics.

## Manifest and catalog changes

Update `YamlSkillManifest.EvidenceContractManifest` so `claims` maps claim names to expression strings rather than `List<String>`. Remove the `tool_evidence` property and its normalization path.

Keep strict unknown-property handling. A manifest still containing `tool_evidence` must fail startup with a useful message instead of silently ignoring it.

For every `evidence_contract.claims` entry, startup validation must verify:

1. The claim key is nonblank.
2. Claim keys do not collide case-insensitively, preserving current validation.
3. The claim matches a top-level `output_schema` property, preserving current validation and canonical schema casing.
4. The expression value is a YAML string scalar and is nonblank.
5. The complete expression parses successfully; trailing tokens are rejected.
6. Every skill reference exactly matches an entry in the declaring skill's `allowed_skills`.
7. A reference with incorrect casing is rejected. When there is one unambiguous case-insensitive match, include the correctly cased name in the diagnostic.
8. Reserved operator tokens cannot be used as skill references.

Continue requiring `output_schema` when `evidence_contract` is present.

Diagnostics should identify the resource, full field path, claim, source offset or column within the expression, and specific problem where practical. Examples:

```text
Invalid evidence expression for claim 'likelyCause' at column 22: expected a skill name or '('.
Invalid evidence expression for claim 'likelyCause' at column 22: unknown direct child skill 'investigatenetwork'; did you mean 'investigateNetwork'?
Invalid evidence expression for claim 'likelyCause': skill 'checkDns' is not a direct allowed child of 'handleIncident'.
```

Cover at least these malformed forms:

- missing left or right operands;
- adjacent identifiers without an operator;
- unmatched opening or closing parentheses;
- empty parentheses;
- unexpected punctuation;
- unknown/non-allowed skill names;
- incorrectly cased skill names;
- blank or null expressions;
- list/object values where a string scalar is required.

## Runtime model and semantics

Refactor `EvidenceContract` from evidence-type sets into parsed claim expressions. Parsing should occur once during catalog construction, not repeatedly during planning or final validation.

Evaluate each expression against a set/predicate of satisfied direct skill names:

- a skill-reference node is satisfied only when its exact skill name is present;
- an AND node is satisfied only when every child is satisfied;
- an OR node is satisfied when at least one child is satisfied.

### Plan coverage

For plan validation, the satisfied-name set consists of nonblank `PlanTask.capabilityName()` values in the proposed plan. A task's capability name must match case-sensitively. Existing plan-quality validation may independently report unknown tools; evidence evaluation must not normalize names into matches.

Evaluate all claims declared in the contract, as the current implementation does. A plan passes evidence coverage only when every claim expression evaluates true.

### Successful execution coverage

For final-output validation, a reference becomes satisfied only after that direct child tool/skill completes successfully in the current YAML mission. A planned call, started call, failed call, or cancelled call must not satisfy it.

Track successful direct child skill names for the current mission. A set is sufficient; repeated successful calls to the same capability do not change Boolean truth. Refactor the existing produced-evidence state APIs and snapshots as appropriate rather than retaining synthetic evidence IDs merely to emulate the removed configuration.

Preserve mission-boundary isolation:

1. Save the parent's successful-direct-child set when entering a nested YAML mission.
2. Start the child with a fresh set.
3. Restore the parent's set after the child returns.
4. If the nested child completed successfully, credit the nested child's public skill name in the parent set.
5. Never merge the child's internal successful tool names into the parent.

Ensure both planned and unplanned tool execution paths record successful direct child names consistently if the current runtime permits unplanned calls.

### Candidate-output validation

Retain current candidate behavior:

- Identify contract-backed top-level fields present in schema-valid JSON.
- Evaluate only those claims against successfully completed direct children.
- On failure, retry output generation using already completed work only; do not call tools during the output retry.
- Permit removal of an unsupported optional claim.
- Required output fields ultimately fail when their expressions remain unsatisfied.

## Coverage results, diagnostics, and planning prompts

Replace evidence-ID-oriented results such as `missingEvidence` with expression-oriented information. Names should describe unsatisfied requirements or skill expressions rather than evidence types.

An evaluation result should retain enough structure to explain why it failed:

- claim name;
- canonical expression;
- satisfied direct skills;
- unsatisfied clause(s) or references;
- whether an OR group needs any one alternative or an AND group still needs all listed requirements.

Do not flatten an expression into an ambiguous list of tools.

Planning prompt guidance should render the real canonical expression, for example:

```text
Evidence constraints:
- The 'severity' output field requires successful use of: classifyIncident
- The 'likelyCause' output field requires successful use of: classifyIncident and (investigateNetwork or investigateApp)
```

For planning, wording may say that tasks must be included. For final validation, wording must say that skills must have completed successfully. In both cases, make OR alternatives explicit and never imply every alternative must run.

Update trace/event metadata so it no longer publishes misleading `missingEvidence` identifiers. Prefer fields such as `unsatisfiedClaims`, `requiredExpression`, `satisfiedSkills`, and `unsatisfiedRequirements`, aligned with the final result model. Check repository tests before renaming any observable metadata and update them deliberately.

Retain the existing `EVIDENCE_RECORDED` trace record type. The feature still records evidence-supporting mission progress, so the event category remains accurate; only its obsolete evidence-ID payload changes. Replace fields such as `evidenceTypes` and the generic evidence ledger with `successfulSkill` and `successfulDirectSkills`. Do not publish legacy payload aliases or a duplicate compatibility event.

## Required `bifrost-sample` migrations

Migrate every existing evidence contract under `bifrost-sample/src/main/resources/skills`. Preserve the current executable AND/OR semantics; do not use this migration to silently strengthen or weaken a sample's requirements.

### Duplicate invoice checker

File: `bifrost-sample/src/main/resources/skills/basics/duplicate_invoice_checker.yml`

```yaml
evidence_contract:
  claims:
    vendorName: invoiceParser
    invoiceDate: invoiceParser
    totalAmount: invoiceParser
    isDuplicate: invoiceParser and expenseLookup
    reasoning: invoiceParser and expenseLookup
```

### Incident handling

File: `bifrost-sample/src/main/resources/skills/incidents/handle_incident.yml`

```yaml
evidence_contract:
  claims:
    severity: classifyIncident
    category: classifyIncident
    likelyCause: classifyIncident and (investigateNetwork or investigateApp)
    evidenceSummary: investigateNetwork or investigateApp
    recommendedAction: investigateNetwork or investigateApp
    userMessage: draftIncidentResponse
```

### Insurance claim processing

File: `bifrost-sample/src/main/resources/skills/insurance/process_claim.yml`

```yaml
evidence_contract:
  claims:
    disposition: assessCoverage and fraudScreen and recommendDisposition
    payableAmount: assessCoverage
    coverageSummary: assessCoverage
    fraudRisk: fraudScreen
    matchedExclusions: assessCoverage
    rationale: extractClaimFacts and assessCoverage and fraudScreen and recommendDisposition
    evidenceNotes: extractClaimFacts and assessCoverage and fraudScreen
```

### Support case resolution

File: `bifrost-sample/src/main/resources/skills/support/resolve_support_case.yml`

The current shared `case_facts` evidence type means any one handler satisfies that part of the claim. Preserve that behavior:

```yaml
evidence_contract:
  claims:
    intents: understandIntent
    disposition: understandIntent and (handleBilling or handleTechnical or handleHowTo) and composeReply
    refundRecommended: handleBilling or handleTechnical or handleHowTo
    factsSummary: handleBilling or handleTechnical or handleHowTo
    draftReply: composeReply
    internalNotes: understandIntent and (handleBilling or handleTechnical or handleHowTo) and composeReply
```

Do not change the separate prompt instruction that all branches required by the detected intents should run. The evidence expression states minimum claim supportability; it is not a general workflow DSL.

### Trip planning

File: `bifrost-sample/src/main/resources/skills/travel/plan_trip.yml`

```yaml
evidence_contract:
  claims:
    summary: assembleItinerary
    transport: planTransport
    hotel: planStay
    estimatedTotal: assembleItinerary
    rationale: understandPreferences and planTransport and planStay and assembleItinerary
    openQuestions: understandPreferences and assembleItinerary
```

After migration, search all of `bifrost-sample` for `tool_evidence` and old list-valued claim mappings. No legacy evidence-contract syntax should remain.

## Documentation migration

Update every repository document that describes or demonstrates evidence contracts, including at minimum:

- `README.md`;
- `ai/skill-authoring/evidence-contracts.md`;
- `ai/skill-authoring/checklists/evaluate-a-skill-design.md`;
- `bifrost-sample/README.md`.

Remove the evidence-type ontology explanation, shared-producer mapping instructions, and statements that direct authors to `tool_evidence`. Replace them with:

- the expression grammar and precedence;
- case-insensitive operators versus case-sensitive skill names;
- direct-child-only references;
- planning versus successful-execution truth sets;
- nested mission isolation;
- examples of conjunction and alternatives;
- the distinction between evidence supportability and factual truth;
- the warning that an evidence contract is not a general workflow DSL.

Update terminology in tables and prose. For example, replace phrases such as "shared `investigation_digest`" and "L2 `tool_evidence` keys" with direct Boolean requirements over L2 child skills.

## Test migration and additions

Update existing fixtures and tests that construct `EvidenceContractManifest`, call evidence-type APIs, or assert evidence-ID diagnostics. This includes catalog, definition, advisor resolution, planning, tool callback, execution engine, state, and evidence validator tests found by repository search.

Replace obsolete invalid fixtures such as blank evidence IDs and duplicate tool mappings with expression-specific fixtures. Retain coverage for unknown claims and case-colliding claim keys.

Add focused parser tests for:

- a single skill reference;
- left-associative repeated `and` and repeated `or`;
- `and` precedence over `or`;
- parentheses changing precedence;
- arbitrary casing of `and` and `or`;
- canonical rendering with lowercase operators;
- operator substrings inside valid skill names;
- exact case-sensitive skill matching;
- a helpful casing suggestion;
- every malformed form listed in the catalog section;
- rejection of trailing tokens;
- reserved-word handling.

Add evaluator/coverage tests proving:

- `classifyIncident and (investigateNetwork or investigateApp)` passes with classification plus either investigator;
- it fails with classification alone;
- it fails with an investigator alone;
- it does not require both investigators;
- a failed child call does not satisfy final validation;
- a successful unplanned call is credited if that path is supported;
- final validation evaluates only present contract-backed claims;
- plan validation evaluates all declared claims;
- nested child internals do not leak into the parent;
- successful nested child completion credits the child's public name to the parent;
- planning prompts and retry diagnostics retain AND/OR semantics.

Include at least one catalog-loading test for each migrated sample contract, either through existing sample integration coverage or a targeted repository-wide catalog test.

## Likely implementation locations

The implementing context should search before editing, but the current implementation is centered in:

- `YamlSkillManifest.EvidenceContractManifest`;
- `YamlSkillCatalog.validateEvidenceContract` and evidence mapping validation;
- `EvidenceContract`;
- `EvidenceCoverageValidator` and its result/issue records;
- `EvidenceBackedOutputValidator`;
- `EvidenceContractCallAdvisor`;
- `DefaultPlanningService` prompt construction and plan coverage;
- `DefaultToolCallbackFactory` and successful-completion recording;
- execution state/session evidence snapshot and restoration APIs;
- nested mission entry/exit handling;
- associated tests and YAML fixtures under `bifrost-spring-boot-starter/src/test`.

Renaming internal classes from “evidence” to “skill requirement” is optional. Prefer a coherent implementation and bounded diff over cosmetic renaming, but remove public-facing and diagnostic references to evidence IDs that no longer exist.

## Non-goals

- General workflow ordering or dependency expressions.
- Conditional expressions based on tool output values.
- A `not` operator or other non-monotonic rules.
- Symbolic `&&` and `||` aliases.
- Quoted/escaped skill identifiers.
- Referencing grandchildren or merging child execution ledgers into a parent.
- Proving that a successful skill result is factually correct.
- Inferring which optional claims the model will omit during planning.
- Changing sample business semantics beyond translating the current evidence requirements.

## Acceptance criteria

- All evidence-contract claims are authored as Boolean expressions over direct allowed child skill names.
- `tool_evidence` is removed from the manifest model, runtime, sample skills, tests, and documentation.
- Operators are case-insensitive; skill names are case-sensitive.
- `and` precedence and parentheses behave as specified.
- Catalog startup rejects malformed expressions, unknown/non-direct skills, and casing mistakes with actionable diagnostics.
- Plan coverage evaluates expressions against planned direct child names.
- Final coverage evaluates expressions only against successfully completed direct child names.
- Nested mission state remains isolated and credits only the completed direct child at the parent boundary.
- Planning prompts and coverage failures display correct Boolean semantics and never imply all OR alternatives are required.
- All five `bifrost-sample` evidence contracts are migrated exactly as specified above.
- Repository search finds no remaining `tool_evidence` references except, if desired, a deliberate migration-note sentence explaining its removal.
- Repository search finds no old list-valued evidence claim mappings.
- Focused parser, catalog, planning, execution, final validation, and nesting tests pass.
- The full Maven test suite passes from the repository root:

  ```powershell
  .\mvnw.cmd test
  ```

## Implementation guidance

Work from behavior outward:

1. Add the parser, AST, canonical renderer, and focused unit tests.
2. Change the manifest and catalog validation, including direct `allowed_skills` resolution.
3. Refactor the contract and coverage evaluator to use expressions.
4. Refactor successful-completion state while preserving nested mission isolation.
5. Correct planning prompts, retry feedback, traces, and final-output diagnostics.
6. Migrate starter fixtures/tests.
7. Migrate all five sample skills and their tests.
8. Rewrite repository documentation and examples.
9. Search for legacy terminology/syntax and run focused tests followed by the full suite.

Preserve unrelated work in the repository and avoid broad mechanical rewrites outside this ticket's scope.
