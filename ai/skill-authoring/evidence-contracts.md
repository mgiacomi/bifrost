---
audience: bifrost-skill-builder
status: development
applies_to: current-repository-checkout
coverage: initial-source-verified
---

# Authoring Evidence Contracts

## Purpose

Use an `evidence_contract` when a skill's structured output contains claims that must be supported by successful work performed during that skill's mission.

Evidence contracts enforce supportability, not real-world truth. They let Bifrost determine that the declared work capable of supporting a claim was planned and completed; they do not prove that a tool result is factually correct.

Evidence contracts are optional. Do not add one unless deterministic evidence coverage provides meaningful protection for the skill's output.

## Contract Shape

An evidence contract requires an `output_schema` so claim names can be validated.

```yaml
evidence_contract:
  claims:
    vendorName: [parsed_invoice]
    isDuplicate: [parsed_invoice, expense_match_search]
  tool_evidence:
    invoiceParser: [parsed_invoice]
    expenseLookup: [expense_match_search]
```

- `claims` maps a top-level output field to the evidence types required to support that field when it is present in the final output.
- `tool_evidence` maps a direct child capability visible to this skill to the evidence types credited when that tool completes successfully.
- Evidence type identifiers are skill-author-defined semantic labels.

## Semantic Rules

### Every evidence type listed for a claim is required

Claim lists have AND semantics.

```yaml
claims:
  isDuplicate: [parsed_invoice, expense_match_search]
```

`isDuplicate` is supported only after both `parsed_invoice` and `expense_match_search` have been gathered.

Do not list evidence from alternative branches together unless the claim genuinely requires all branches.

### Multiple tools may produce the same evidence type

One successful producer is sufficient to establish a shared evidence type.

```yaml
claims:
  recommendedAction: [investigation_digest]

tool_evidence:
  investigateNetwork: [investigation_digest]
  investigateApp: [investigation_digest]
```

A plan using either `investigateNetwork` or `investigateApp` can cover `investigation_digest`. The claim does not require both tools because the claim requires an evidence type, not every tool mapped to that type.

Treat the shared type as a semantic guarantee: both tools must produce results that legitimately satisfy the meaning of `investigation_digest`.

### A producer may expose common and specific evidence

Use a common type for substitutable support while retaining branch-specific types for more specific claims:

```yaml
claims:
  recommendedAction: [investigation_digest]
  dnsAssessment: [network_digest]

tool_evidence:
  investigateNetwork: [investigation_digest, network_digest]
  investigateApp: [investigation_digest, app_digest]
```

The execution trace still identifies which tool ran. Evidence types do not need to encode the complete provenance trail in their names.

### Evidence is credited only after successful tool completion

Declaring a tool in the contract does not gather evidence. Planning coverage checks whether the plan can produce the required types; the mission ledger records a type only when the corresponding tool completes successfully.

A failed tool call MUST NOT satisfy its evidence mapping.

### Final validation applies to claims present in the candidate output

Before accepting schema-valid JSON, Bifrost finds contract-backed top-level fields present in the candidate and checks their required evidence against the current mission ledger.

If evidence is missing, the evidence advisor requests a corrected response using only evidence already gathered. It does not call tools during that output retry. The model may remove an unsupported optional claim; a required output field cannot be repaired that way and will ultimately fail if its evidence is absent.

### Planning coverage currently evaluates every contract claim

Before accepting a plan, Bifrost evaluates all claims listed in the contract and requires the planned tool bindings to collectively cover their evidence types. It does not currently infer that an optional claim will be omitted later.

Only place a field in `evidence_contract.claims` when the plan should be capable of supporting it for the mission.

## Nested Skill Semantics

Evidence ledgers are local to each YAML mission boundary.

When a parent invokes a nested YAML skill:

1. Bifrost saves the parent's plan and evidence ledger.
2. The child starts with a fresh evidence ledger and applies its own contract.
3. The child completes or fails as one capability from the parent's perspective.
4. Bifrost restores the parent's plan and evidence ledger.
5. If the child completed successfully, the parent credits evidence declared for the child capability in the parent's `tool_evidence`.

Example:

```yaml
# Parent contract
tool_evidence:
  investigateNetwork: [investigation_digest]
```

```yaml
# Child contract
tool_evidence:
  checkDns: [dns_observation]
  inspectRoutes: [route_observation]
```

The parent SHOULD name `investigateNetwork`, not `checkDns` or `inspectRoutes`. The child's leaf evidence is an internal implementation detail of the child mission.

Do not export or merge child evidence tags into the parent ledger. If a parent needs a guarantee from a child, declare that guarantee as evidence produced by the child capability at the parent boundary.

## Authoring Decision Procedure

For each structured output field:

1. Is the field a claim whose support depends on work performed during the mission?
   - If no, omit it from `evidence_contract.claims`.
   - If yes, identify the minimum independent semantic evidence guarantees required.
2. Does the claim require every guarantee?
   - If yes, list every required type; the list is AND-all.
   - If no, define a shared semantic type that each acceptable alternative producer can provide.
3. Which direct child capabilities can establish each evidence type?
   - Map those direct child names in the current skill's `tool_evidence`.
4. Are all mapped producers actually visible to this skill through `allowed_skills` and runtime access?
   - Verify this explicitly; current catalog validation does not fully enforce the relationship.
5. Does successful completion of each mapped tool really justify crediting the evidence type?
   - If not, strengthen the child contract or choose a narrower evidence label.
6. Can a plan cover every contract claim without destroying intended selective branching?
   - If not, reconsider the evidence ontology before increasing plan breadth.

## Valid Patterns

### Independent facts are conjunctive

```yaml
claims:
  duplicateDecision: [invoice_facts, prior_expense_search]
tool_evidence:
  invoiceParser: [invoice_facts]
  expenseLookup: [prior_expense_search]
```

Both sources are independently necessary, so both types are required.

### Alternative specialists share a guarantee

```yaml
claims:
  evidenceSummary: [investigation_digest]
tool_evidence:
  investigateNetwork: [investigation_digest]
  investigateApp: [investigation_digest]
```

Either specialist can satisfy the parent-level guarantee.

### Parent credits a nested child boundary

```yaml
allowed_skills: [classifyIncident, investigateNetwork, investigateApp]
evidence_contract:
  claims:
    likelyCause: [incident_classification, investigation_digest]
  tool_evidence:
    classifyIncident: [incident_classification]
    investigateNetwork: [investigation_digest]
    investigateApp: [investigation_digest]
```

The parent does not name leaves hidden inside either investigation skill.

## Invalid or Misleading Patterns

### Encoding alternatives as conjunction

```yaml
claims:
  recommendedAction: [network_digest, app_digest]
```

If network and application investigation are alternatives, this requires both and will force plan coverage across both branches. Use a shared semantic type instead.

### Naming hidden grandchildren in the parent contract

```yaml
tool_evidence:
  checkDns: [network_digest]
```

This is invalid as a parent model when the parent invokes `investigateNetwork` and only the child can see `checkDns`. Credit the direct child capability at the parent boundary.

### Treating tool success as stronger than it is

```yaml
tool_evidence:
  fetchUnverifiedText: [policy_verified]
```

A successful call only earns the evidence types declared for it. The author is responsible for making the label truthful about the guarantee provided by successful completion.

### Adding a contract only to force tool usage

Do not use evidence contracts as a general workflow DSL. They protect final claim supportability. Use skill responsibility, descriptions, prompts, planning, and capability design for ordinary orchestration.

## Startup Validation Currently Enforced

The current catalog requires:

- an `output_schema` when `evidence_contract` is present;
- every claim name to match a top-level `output_schema` property, case-insensitively;
- nonblank claim, tool, and evidence identifiers;
- no duplicate evidence identifiers within one mapping;
- no claim or tool keys that collide case-insensitively.

## Known Limitations and Open Design Signals

### Planning guidance overstates multiple producers

The deterministic coverage validator correctly accepts any producer of a shared evidence type. The current planning prompt and retry wording flatten all possible producer tools into one list and may sound as though every listed producer must be invoked.

Treat the validator semantics in this document as the current executable rule. When diagnosing an unnecessary call-every-branch plan, inspect the generated planning prompt. This discrepancy should be considered a framework clarity issue, not evidence that the contract itself has AND semantics across producers.

### Producer reachability is not fully validated at startup

The catalog validates the shape of `tool_evidence` but does not currently prove that each producer is present in the declaring skill's `allowed_skills`, registered, and reachable for a particular authenticated invocation.

Before proposing a plan, verify that declared producers are direct, visible child capabilities. If all producers for required evidence are unavailable, the contract is unsatisfiable for that run.

### No explicit Boolean evidence expression language

The current compact contract supports conjunction of evidence types and substitution through a shared semantic type. It does not expose `anyOf`, nested AND/OR groups, or child-ledger export.

Do not invent unsupported syntax. Record a recurring use case when a truthful shared semantic type cannot express the requirement.

## Diagnostics

When evidence coverage fails, inspect:

1. Which output claims the contract evaluates.
2. Which evidence types each claim requires.
3. Which direct tools the plan binds.
4. Which evidence types those tools are declared to produce.
5. Whether the tools actually completed successfully.
6. Whether the failure occurred in the child or parent mission ledger.
7. Whether runtime access filtering removed the only producer.
8. Whether planning guidance caused all alternative producers to be selected unnecessarily.

Do not repair a failure by weakening evidence labels until they no longer describe meaningful support.

## Implementation Anchors

### Manifest and startup validation

- [`YamlSkillManifest.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillManifest.java), especially `EvidenceContractManifest`, defines the YAML object shape.
- [`YamlSkillCatalog.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java), especially `validateEvidenceContract`, defines startup validation.
- [`evidence-contract-skill.yaml`](../../bifrost-spring-boot-starter/src/test/resources/skills/valid/evidence-contract-skill.yaml) is a valid focused fixture.
- The `evidence-contract-*` files under [`skills/invalid`](../../bifrost-spring-boot-starter/src/test/resources/skills/invalid/) demonstrate rejected shapes.

### Runtime semantics

- [`EvidenceContract.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContract.java) normalizes claim and producer mappings.
- [`EvidenceCoverageValidator.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceCoverageValidator.java) implements planning and gathered-evidence coverage.
- [`EvidenceBackedOutputValidator.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceBackedOutputValidator.java) determines which present claims require final support.
- [`EvidenceContractCallAdvisor.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContractCallAdvisor.java) implements final-response retry and failure behavior.
- [`DefaultPlanningService.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/planning/DefaultPlanningService.java) applies plan coverage and constructs current planning guidance.
- [`DefaultToolCallbackFactory.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/tool/DefaultToolCallbackFactory.java) credits evidence after successful direct tool execution.
- [`CapabilityExecutionRouter.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/CapabilityExecutionRouter.java) snapshots and restores evidence around nested YAML missions.

### Behavioral tests and samples

- [`EvidenceContractTests.java`](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContractTests.java) covers normalized lookup and final evidence retry behavior.
- [`PlanningServiceTest.java`](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/planning/PlanningServiceTest.java) covers planning constraints and evidence coverage acceptance and rejection.
- [`ToolCallbackFactoryTest.java`](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/tool/ToolCallbackFactoryTest.java) covers evidence recording on tool completion.
- [`CapabilityExecutionRouterTest.java`](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/core/CapabilityExecutionRouterTest.java), especially `nestedYamlDelegationStartsWithFreshEvidenceAndRestoresParentEvidenceAfterward`, protects nested isolation.
- [`duplicate_invoice_checker.yml`](../../bifrost-sample/src/main/resources/skills/basics/duplicate_invoice_checker.yml) demonstrates independent conjunctive evidence.
- [`handle_incident.yml`](../../bifrost-sample/src/main/resources/skills/incidents/handle_incident.yml) demonstrates the implemented selective-branching incident workflow and its evidence boundary.
