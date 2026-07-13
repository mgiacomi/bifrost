# Ticket: Sample HTN Skill Tree — Insurance Claim Intake

**Status:** Design  
**Priority:** P2 (enterprise narrative; slightly heavier than incident/support)  
**Module:** `bifrost-sample`  
**Related tickets:**  
- `eng-sample-htn-incident-commander.md`  
- `eng-sample-htn-support-case-resolver.md`  
- `eng-sample-htn-travel-concierge.md`  
- Existing sample: `duplicate_invoice_checker.yml` (evidence_contract precedent)  
**Depends on:** Nested planning (existing); strong use of `output_schema` + `evidence_contract`; attachments optional for later (photos/PDFs)  

---

## Summary

Add a **three-level HTN skill tree** for fake insurance claim intake: extract claim facts, assess coverage against policy data, run a lightweight fraud screen, and return a structured adjudication recommendation with **evidence-backed claims**.

This is the **enterprise / compliance-oriented** showcase: Bifrost is not only “agent picks tools,” but “agent must ground decisions in tool evidence under an HTN structure.”

## Motivation

Insurance (even fake) is a strong Bifrost story:

- Unstructured FNOL-style text needs extraction.  
- Coverage decisions need policy + exclusion tools (deterministic leaves).  
- Fraud signals need separate investigation branch.  
- Regulated-flavored outputs benefit from `evidence_contract` (already used by `duplicateInvoiceChecker`).  
- Nested planners mirror real org structure: intake → coverage desk → SIU-lite.

Complements:

- Invoice sample → AP automation + evidence  
- Incident sample → ops branching  
- **Claim sample → risk decisioning + evidence**

## Goals

- Three-level skill stack with nested planners for coverage and fraud.  
- First-class **evidence_contract** on root (and likely mid-level).  
- Deterministic policy/claims history leaves (Java).  
- Structured recommendation: pay / partial / deny / refer_to_human / refer_to_siu.  
- Ollama-first for v1 (text-only claim intake).  
- Fixtures for clear-pay, exclusion-deny, fraud-refer, ambiguous.  
- README that explains evidence tags and how to read them in the journal.  

## Non-Goals

- Real insurance regulations or actuarial correctness.  
- Computer vision damage estimation (optional future with attachments).  
- Payment execution.  
- Full SIU case management.  
- Guaranteeing model legal accuracy (explicitly a demo).

## Domain story

**Mission:** Customer submits a free-text claim description (and optional structured fields). Bifrost returns:

- extracted claim facts  
- coverage assessment  
- fraud risk signals  
- recommended disposition  
- rationale with evidence  

Humans remain responsible for final legal decisions; the sample models **intake + recommendation**.

## Skill tree (3 levels)

```
processClaim                               [L1 planning YAML]
├── extractClaimFacts                      [L2 LLM single-shot YAML]
├── assessCoverage                         [L2 planning YAML]
│   ├── getPolicy                          [L3 Java]
│   ├── checkExclusions                    [L3 Java]
│   └── estimatePayout                     [L3 Java]
├── fraudScreen                            [L2 planning YAML]
│   ├── priorClaimsLookup                  [L3 Java]
│   ├── anomalyScore                       [L3 Java]
│   └── addressRiskSignals                 [L3 Java optional]
└── recommendDisposition                   [L2 LLM single-shot YAML]
```

### Depth notes

- L1 orchestrates extract → coverage → fraud → recommend (order flexible if model has reasons).  
- L2 coverage planner decides which policy tools to call (always get policy? skip estimate if excluded?).  
- L2 fraud planner decides depth of screening.  
- L3 systems of record are fake but stable per `scenario` / `policyId`.

## What the LLM is allowed to decide

| Level | Fixed | LLM freedom |
| --- | --- | --- |
| L1 | Specialists only | Order; whether fraud screen is needed; when to recommend |
| L2 coverage/fraud | Listed tools only | Which checks; when enough for a sub-conclusion |
| L2 extract/recommend | Schemas + prompts | Fact extraction; disposition language |
| L3 | Policy/history math | None |

## Skill inventory (proposed)

### Planning skills

| Name | `allowed_skills` | Role |
| --- | --- | --- |
| `processClaim` | `extractClaimFacts`, `assessCoverage`, `fraudScreen`, `recommendDisposition` | Root |
| `assessCoverage` | `getPolicy`, `checkExclusions`, `estimatePayout` | Coverage desk |
| `fraudScreen` | `priorClaimsLookup`, `anomalyScore`, `addressRiskSignals` | SIU-lite |

### LLM single-shot skills

| Name | Purpose |
| --- | --- |
| `extractClaimFacts` | loss date, type, amount claimed, location, narrative entities |
| `recommendDisposition` | final structured recommendation from branch digests |

### Java leaves

Suggested service: `InsurancePolicyService` (+ maybe `ClaimsHistoryService`).

| Capability | Behavior |
| --- | --- |
| `getPolicy` | coverage limits, deductibles, active dates, product type |
| `checkExclusions` | list matched exclusions for loss type / keywords |
| `estimatePayout` | simple deterministic calc from limit/deductible/claimed amount |
| `priorClaimsLookup` | prior claims count, recency, similar loss types |
| `anomalyScore` | 0–1 score from scenario rules |
| `addressRiskSignals` | optional address/velocity fake flags |

## Mission input / output

### Root input

```yaml
properties:
  claimText: { type: string }
  policyId: { type: string, nullable: true }
  claimedAmount: { type: number, nullable: true }
  scenario: { type: string, nullable: true }
required: [claimText]
```

### Extract skill output (intermediate)

```yaml
properties:
  lossDate: { type: string, nullable: true }
  lossType: { type: string }              # auto | property | theft | liability | other
  claimedAmount: { type: number, nullable: true }
  location: { type: string, nullable: true }
  description: { type: string }
  parties: { type: array, items: { type: string } }
```

### Root output (proposed)

```yaml
properties:
  disposition:
    type: string
    description: pay | partial_pay | deny | refer_human | refer_siu
  payableAmount: { type: number, nullable: true }
  coverageSummary: { type: string }
  fraudRisk: { type: string }             # low | medium | high
  matchedExclusions: { type: array, items: { type: string } }
  rationale: { type: string }
  evidenceNotes: { type: string }
required: [disposition, coverageSummary, fraudRisk, matchedExclusions, rationale, evidenceNotes]
```

## Evidence contract (proposed direction)

Build on the invoice sample pattern:

```yaml
evidence_contract:
  claims:
    disposition: [coverage_assessment, fraud_assessment]
    payableAmount: [coverage_assessment]
    fraudRisk: [fraud_assessment]
    matchedExclusions: [coverage_assessment]
    rationale: [coverage_assessment, fraud_assessment, claim_facts]
  tool_evidence:
    extractClaimFacts: [claim_facts]
    assessCoverage: [coverage_assessment]
    fraudScreen: [fraud_assessment]
    # or map leaf tools if mid-level results are not tagged that way
```

Exact tag wiring must match how Bifrost attributes tool evidence today for nested skills — **verify during design** against `duplicateInvoiceChecker` and runtime behavior for nested planners.

## Canned scenarios / fixtures

| Scenario | Claim gist | Expected bias |
| --- | --- | --- |
| `clear-auto-pay` | Minor covered collision, clean history | pay / partial after deductible |
| `exclusion-flood` | Water damage where flood excluded | deny + matched exclusions |
| `fraud-velocity` | Third similar claim in 60 days, high anomaly | refer_siu |
| `ambiguous-liability` | Unclear fault, missing date | refer_human |
| `over-limit` | Claimed amount above policy limit | partial_pay at limit |

Policy fixtures can live as JSON under `resources/fixtures/insurance/policies/*.json` loaded by Java leaves.

## HTTP API (proposed)

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/claims/process` | JSON: claimText, optional policyId, claimedAmount, scenario |
| `GET` | `/claims/process-scenario` | `name=` fixture |
| `GET` | `/claims/scenarios` | list fixtures |

Response: `result`, `sessionId`, `executionJournal`.

## Package / file layout (proposed)

```
bifrost-sample/src/main/
  java/.../sample/insurance/
    InsurancePolicyService.java
    ClaimsHistoryService.java
    ClaimsController.java
  resources/skills/insurance/
    process_claim.yml
    extract_claim_facts.yml
    assess_coverage.yml
    fraud_screen.yml
    recommend_disposition.yml
    get_policy.yml
    check_exclusions.yml
    estimate_payout.yml
    prior_claims_lookup.yml
    anomaly_score.yml
  resources/fixtures/insurance/
    claims/clear-auto-pay.txt
    claims/exclusion-flood.txt
    policies/pol-1001.json
    ...
```

## Documentation requirements

- Tree diagram with evidence story.  
- Side-by-side with `duplicateInvoiceChecker` evidence pattern.  
- Explicit disclaimer: demo only, not real insurance advice.  
- How to inspect evidence-related journal entries.  
- Scenario table with expected dispositions (bias, not guarantees).

## Tests

- Leaf math: `estimatePayout` deterministic for known policy + amount.  
- Exclusion matching deterministic for fixture keywords.  
- Catalog registration; planners’ `allowed_skills`.  
- Evidence contract present on root manifest (shape test).  
- Controller delegation.  
- No live LLM in CI.

## Acceptance criteria

- [ ] Nested coverage and fraud planners call Java leaves.  
- [ ] Root defines `output_schema` + `evidence_contract`.  
- [ ] At least four fixtures covering pay, deny, SIU refer, human refer.  
- [ ] Payout estimation is deterministic Java (not LLM arithmetic).  
- [ ] HTTP returns journal metadata.  
- [ ] Ollama-only runnable for v1.  
- [ ] README includes compliance-oriented teaching points + disclaimer.  
- [ ] Nested evidence attribution verified manually once with a live run.

## Open design questions

1. **Evidence attribution across nested planners:** do mid-level planning skills emit tool evidence tags the root contract can cite, or must root only allow leaf tools directly? (Critical design spike.)  
2. **Disposition enum** final set and naming.  
3. **Should `recommendDisposition` be planning** with no tools (synthesis only) or pure single-shot?  
4. **Policy id required?** or extract from text?  
5. **Attachments later:** damage photos via `type: attachment` — separate follow-up ticket?  
6. **Partial pay formula** — document exact fake formula in README.  
7. **Model strength** for multi-step claim reasoning vs tiny local models.  
8. **RBAC demo:** optional `rbac_roles` on fraud tools for a future security sample?  
9. **Shared “gallery index”** in README vs separate walkthrough doc.

## Implementation sketch (after design lock)

1. Spike: nested planner + evidence_contract behavior (document findings in this ticket).  
2. Policy/history Java services + fixtures.  
3. Mapped leaves.  
4. Extract + recommend LLM skills.  
5. Coverage + fraud planners.  
6. Root planner with evidence contract.  
7. HTTP + tests + README.  
8. Capture one full journal for docs.

## Risks

- **Evidence contracts + nesting** may not compose the way the sample wants; may force flatter tool visibility at root (still a valid HTN if mid-level skills return structured digests as “tools”).  
- Small local models may struggle with long claim reasoning; may need stronger `bifrost.models` entry.  
- Users may over-trust demo dispositions — docs must disclaimer clearly.

## Design discussion notes

_(Use this section during ticket review.)_

- Owner: TBD  
- Reviewers: TBD  
- Decisions log:  
  - (pending)  
- Spike results (evidence + nesting):  
  - (pending)
