# Ticket - Add Optional Evidence Contracts for Supported Final Claims

Date: 2026-03-30

## Summary
Recent planning work improved plan structure and added weak-plan validation, but a remaining reliability gap is visible in duplicate-checking traces: a mission can still produce a schema-valid final answer that is not actually supported by the work performed.

The motivating example is a `duplicateInvoiceChecker` run where the planner used `invoiceParser`, never used `expenseLookup`, and still returned `isDuplicate: false`. The answer was structurally valid and operationally clean, but semantically under-evidenced.

This ticket proposes an optional skill-level `evidence_contract` that lets the framework enforce a stronger rule:

- every accepted final claim must be backed by required evidence
- every accepted plan must be capable of producing that evidence

The framework should own claim sufficiency through deterministic coverage checks rather than relying on a small model to infer, remember, and self-police what evidence is required.

## Problem Statement
Today the runtime has two strong boundaries:

- plan-shape and plan-quality validation during planning
- `output_schema` validation for final mission responses

What it does not have is a way to say:

- this output field is a claim
- this claim requires specific evidence
- only certain tools can produce that evidence
- therefore a plan or final answer is unsupported if the required evidence is absent

Without that layer, the runtime can accept a final response that is:

- valid JSON
- schema compliant
- trace clean
- but semantically unsupported by completed work

This is especially risky for smaller planning/execution models, which tend to optimize toward the shortest valid output rather than the most defensible one.

## Goals
- Introduce an optional, skill-level evidence contract that defines which claims require which evidence.
- Keep the framework generic; do not hardcode production logic for `duplicateInvoiceChecker` or any single skill.
- Validate plans against required evidence coverage before execution begins.
- Validate final mission responses against evidence gathered during execution.
- Reuse existing runtime seams where possible: YAML skill manifests, session state, planning validation, tool-result logging, output validation, and trace recording.
- Preserve backward compatibility for skills that do not define an evidence contract.

## Non-Goals
- Do not attempt general natural-language understanding of every mission objective.
- Do not try to prove that a claim is true in the real world.
- Do not require all tools to return a brand-new rich evidence payload format in the first version.
- Do not replace `output_schema`; evidence contracts complement schema validation rather than replacing it.
- Do not block existing skills that lack an evidence contract.
- Do not add domain-specific string-matching rules for `duplicate`, `compare`, `verify`, etc. as the primary enforcement path.

## Core Idea
The framework should enforce supportability, not truth.

For any final response, the runtime should be able to answer:

1. Which claims is this skill allowed to make?
2. Which evidence types are required for each claim?
3. Which tools can produce those evidence types?
4. Did the accepted plan include tasks that can produce the needed evidence?
5. Did execution actually gather the needed evidence before the final response was accepted?

If the answer to 4 is no, the plan is insufficient.
If the answer to 5 is no, the final response is unsupported.

## Proposed Contract Shape
Add an optional `evidence_contract` block to YAML skills.

Example for `duplicateInvoiceChecker`:

```yaml
evidence_contract:
  claims:
    vendorName:
      requires: [parsed_invoice]
    invoiceDate:
      requires: [parsed_invoice]
    totalAmount:
      requires: [parsed_invoice]
    isDuplicate:
      requires: [parsed_invoice, expense_match_search]
    reasoning:
      requires: [parsed_invoice, expense_match_search]

  tool_evidence:
    invoiceParser:
      produces: [parsed_invoice]
    expenseLookup:
      produces: [expense_match_search]
```

Notes:
- `claims` keys should map to top-level output field names for the skill.
- `requires` is a list of evidence-type identifiers.
- `tool_evidence` maps visible sub-skill names to the evidence types they can produce.
- Evidence-type identifiers are just stable strings in v1.

## How the Contract Would Be Used
### Planning Time
Before accepting a parsed plan:

- compute the set of claims that the skill output can make
- compute the required evidence types for those claims
- compute the evidence types that the plan can produce based on bound task `capabilityName` values and `tool_evidence`
- reject or retry the plan if the required evidence is not covered

Example:
- output contains `isDuplicate`
- `isDuplicate` requires `expense_match_search`
- proposed plan only uses `invoiceParser`
- `expense_match_search` is uncovered
- plan is insufficient and must be retried or warned, depending on policy

### Execution Time
When a tool completes successfully:

- look up the evidence types that tool can produce
- add those evidence types to a session-scoped evidence ledger

Before accepting the final response:

- inspect the response fields actually present
- compute required evidence for those claims
- confirm the evidence ledger contains that evidence
- reject/retry if required evidence is missing

Example:
- final response includes `isDuplicate`
- ledger only contains `parsed_invoice`
- `expense_match_search` is missing
- final response is unsupported

## Why This Is Better Than Objective Heuristics
This avoids building a brittle English-understanding layer into the framework.

Instead of asking the runtime to infer:
- “Does this mission sound like comparison?”
- “Does duplicate detection probably require lookup?”

the skill author states the support requirements explicitly once, and the framework applies the same generic rules to every run.

That keeps specificity where it belongs:
- skill authors define claim/evidence semantics
- framework enforces coverage

## Proposed Implementation Shape
### 1. Extend YAML Skill Manifest
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`

Add:
- `EvidenceContractManifest`
- `ClaimRequirementManifest`
- `ToolEvidenceManifest`

Suggested shape:

```java
@JsonProperty("evidence_contract")
private EvidenceContractManifest evidenceContract;

public static class EvidenceContractManifest {
    private Map<String, ClaimRequirementManifest> claims = Map.of();
    @JsonProperty("tool_evidence")
    private Map<String, ToolEvidenceManifest> toolEvidence = Map.of();
}

public static class ClaimRequirementManifest {
    private List<String> requires = List.of();
}

public static class ToolEvidenceManifest {
    private List<String> produces = List.of();
}
```

Validation expectations:
- claim names must be non-blank
- evidence-type names must be non-blank
- tool names in `tool_evidence` must be non-blank
- empty contract should normalize to empty maps/lists

### 2. Validate Contract During Skill Catalog Load
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`

Add manifest validation similar in spirit to existing `output_schema` validation:

- if `evidence_contract.claims` references blank or duplicate evidence ids, reject skill load
- if `tool_evidence` entries are malformed, reject skill load
- if `claims` references fields not present in `output_schema`, either:
  - fail fast, or
  - warn and document why

Recommendation for v1:
- require claim names to exist in the root `output_schema.properties`
- keep validation limited to top-level fields only

This keeps the initial version simple and deterministic.

### 3. Create Runtime Evidence Contract Model
Add a runtime-friendly model separate from the manifest if helpful.

Suggested new types:
- `EvidenceContract`
- `ClaimRequirement`
- `ToolEvidenceDescriptor`
- `EvidenceCoverageResult`
- `EvidenceCoverageIssue`

The runtime model should be immutable and normalized for fast lookups:
- `claim -> required evidence set`
- `tool -> produced evidence set`

### 4. Add Session-Scoped Evidence Ledger
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`

Add something lightweight first:

```java
private Set<String> producedEvidenceTypes;
```

with accessors/mutators like:
- `getProducedEvidenceTypes()`
- `addProducedEvidenceTypes(Set<String> evidenceTypes)`
- `clearProducedEvidenceTypes()`

Notes:
- initialize empty by default
- keep thread-safe under the existing session lock
- include in JSON/session snapshot only if needed for current session persistence semantics

Version 1 does not need full evidence payload storage. Presence/absence of evidence type is enough to enforce support coverage.

### 5. Extend Execution State Service for Evidence Recording
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/ExecutionStateService.java`
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java`

Add methods like:
- `recordEvidenceProduced(BifrostSession session, Collection<String> evidenceTypes)`
- `currentEvidenceTypes(BifrostSession session)`

Keep this centralized so feature code does not manipulate session evidence state ad hoc.

### 6. Record Evidence on Successful Tool Completion
There are two reasonable implementation strategies.

#### V1 Recommendation: derive evidence from tool name
When a tool completes successfully:
- read the current skill’s evidence contract
- map the completed tool name to `tool_evidence.produces`
- add those evidence types to the session ledger

This is the lowest-risk starting point because it requires no tool return-type changes.

Likely integration points:
- where `markToolCompleted(...)` and `logToolResult(...)` are called
- step-loop tool completion path after a successful tool result is accepted

This ticket should identify the exact tool-completion integration point during implementation and keep evidence recording adjacent to successful completion, not merely tool invocation.

#### Deferred future option: explicit tool-emitted evidence
Later, tools could return richer metadata like:

```json
{
  "evidenceType": "parsed_invoice",
  "payload": { ... }
}
```

Do not require that in this ticket.

### 7. Add Planning-Time Evidence Coverage Validation
Add a validator that runs after plan parsing and before plan storage.

Suggested new type:
- `EvidenceCoverageValidator`

Suggested method:

```java
EvidenceCoverageResult validatePlanCoverage(
    ExecutionPlan plan,
    EvidenceContract contract,
    OutputSchemaManifest outputSchema)
```

Responsibilities:
- determine which claims require support
- determine required evidence types
- determine which evidence types the plan can produce from task `capabilityName`
- return missing-evidence issues

V1 policy recommendation:
- if no `evidence_contract` is present, skip validation
- if `evidence_contract` is present and required evidence is uncovered, classify as a planning error
- reuse the existing planning retry/warning trace path if possible

Important design choice:
- for v1, treat all root-level output claims defined in the contract as support-relevant
- do not try to infer optional claim omission from the mission objective

### 8. Add Final-Response Evidence Validation
Add a validation step after `output_schema` success and before the response is accepted as final.

This should parallel the role of `OutputSchemaCallAdvisor`, but for support coverage.

Suggested new component:
- `EvidenceContractCallAdvisor`
or
- `EvidenceBackedOutputValidator`

Responsibilities:
- parse the candidate final JSON
- identify which top-level claims are present in the response
- map those claims to required evidence
- compare required evidence against the session ledger
- retry or fail if required evidence is missing

Suggested retry hint:

- “The response includes `isDuplicate`, but evidence `expense_match_search` has not been gathered.”
- “Do not add unsupported claims. Use only information established by completed tool calls.”

Recommendation:
- keep this as a separate validator/advisor rather than folding it into `OutputSchemaValidator`
- `output_schema` should remain concerned with structure and types
- evidence validation should remain concerned with supportability

### 9. Add Trace Visibility
The runtime should record evidence-validation outcomes in trace, similar to how planning quality and structured-output outcomes are already visible.

Potential new trace record types:
- `EVIDENCE_RECORDED`
- `EVIDENCE_VALIDATION_FAILED`
- `EVIDENCE_VALIDATION_PASSED`

If adding new record types feels heavy for v1, at minimum:
- record evidence-production events on tool completion
- record final validation failure details through existing output validation/error pathways

Preferred direction:
- add explicit trace events so under-supported answers are easy to debug in traces

## Suggested Phase Breakdown
### Phase 1 - Manifest and Runtime Model
- extend `YamlSkillManifest`
- validate `evidence_contract` in `YamlSkillCatalog`
- add immutable runtime model
- no runtime enforcement yet

### Phase 2 - Session Evidence Ledger
- add evidence ledger to `BifrostSession`
- add `ExecutionStateService` helpers
- record tool-produced evidence on successful completion
- add trace visibility for evidence production

### Phase 3 - Planning Coverage Enforcement
- add `EvidenceCoverageValidator`
- wire into `DefaultPlanningService`
- reuse bounded retry flow for uncovered evidence
- add planning tests

### Phase 4 - Final Claim Support Enforcement
- add final-response validator/advisor
- wire after `output_schema`
- retry unsupported answers with precise feedback
- add output-validation tests

## Backward Compatibility and Rollout
This feature should be fully opt-in.

Rules:
- if a skill has no `evidence_contract`, behavior is unchanged
- if a skill has an `evidence_contract`, planning and final-response checks are enabled
- existing skills should continue to load and run unchanged

Recommendation:
- do not add a global hard requirement for contracts in this ticket
- land framework support first
- adopt contracts on high-value skills incrementally

## Acceptance Criteria
- Skills may optionally declare an `evidence_contract` in YAML.
- Contract loading is validated at skill-catalog time.
- Successful tool completion records declared evidence types in session state.
- Planning rejects or retries plans that cannot produce the evidence required by contract-backed claims.
- Final mission responses are rejected or retried when they include claims unsupported by gathered evidence.
- Skills without `evidence_contract` continue to behave exactly as they do today.
- Trace or equivalent runtime diagnostics make evidence production and evidence-validation failures visible.

## Definition of Done
- Manifest support is implemented and validated by automated tests.
- Session evidence state is recorded and covered by unit tests.
- Plan-coverage validation is wired into planning and covered by regression tests.
- Final-response evidence validation is wired into output acceptance and covered by regression tests.
- At least one end-to-end test demonstrates that a schema-valid but unsupported final claim is rejected when a contract is present.
- The motivating duplicate-invoice case is documented in test names, fixtures, or PR notes as a covered scenario, without hardcoding production logic for that skill alone.

## Test Plan
### Manifest Validation
1. Valid contract loads successfully.
2. Blank evidence ids are rejected.
3. Claim names not found in root `output_schema` are rejected.
4. Empty `tool_evidence` list normalizes correctly.

### Session Evidence Ledger
1. Successful tool completion records declared evidence types.
2. Failed tool completion does not record evidence.
3. Repeated tool completion does not corrupt the evidence set.

### Planning Coverage
1. Contract-backed skill with required evidence from two tools:
- plan only uses tool A
- planner retry or error is triggered

2. Contract-backed skill with full coverage:
- plan uses tools A and B appropriately
- planning accepts the plan

3. Skill without contract:
- same weak plan shape
- no evidence-coverage validation fires

### Final Response Coverage
1. Final response includes claim requiring missing evidence:
- response is schema-valid
- evidence validator rejects/retries it

2. Final response includes only claims supported by gathered evidence:
- response is accepted

3. Skill without contract:
- schema-valid response proceeds as before

### Trace Coverage
1. Evidence production is visible after tool completion.
2. Evidence-validation failure is visible when final response is unsupported.
3. Planning retry due to uncovered evidence is visible in planning trace.

## Suggested Files to Touch
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/ExecutionStateService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/TraceRecordType.java`
- new runtime evidence validator/model classes under `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/...`
- corresponding tests under `src/test/java/...`

## Review Focus
- Keep the framework generic; do not sneak in skill-specific production rules.
- Keep `output_schema` and evidence validation as separate responsibilities.
- Keep v1 evidence recording simple; do not force tool-return redesign prematurely.
- Preserve backward compatibility for all skills without contracts.
- Ensure planning and final-response enforcement are both covered; doing only one half leaves the system leaky.

## Risks
- Overdesigning the first version with nested evidence payloads or deep claim semantics may slow delivery and add unnecessary complexity.
- Trying to infer support requirements from objective text instead of the contract would reintroduce brittleness.
- If contract validation is too strict, skill authors may find adoption cumbersome.
- If contract validation is too loose, the feature will not materially improve answer supportability.

## Open Questions
- Should contract-backed planning coverage treat all contract claims as always in scope, or only `output_schema.required` claims?
  Recommendation: for v1, treat all claims listed in `evidence_contract.claims` as support-relevant. Keep this simple and explicit.

- Should `reasoning` itself require evidence?
  Recommendation: yes, if present in the contract. Reasoning is still a claim about why the answer is justified.

- Should evidence be recorded purely from tool success, or should tools eventually emit typed evidence objects?
  Recommendation: derive from tool name in v1; explicit typed evidence can be a follow-up ticket.

- Should missing evidence cause hard failure after retry exhaustion, or degrade to warning?
  Recommendation: hard failure for final-response evidence validation when a contract is present. A contract-backed unsupported claim should not be silently accepted.

## Recommendation
Proceed with an opt-in evidence-contract system as the next framework-level step after planning hardening.

This is the cleanest path to making “is this answer supported by the work performed?” a deterministic runtime concern instead of a hope. It gives skill authors a precise way to declare support requirements, keeps the framework generic, and directly addresses the failure mode where small models return plausible but unsupported conclusions.
