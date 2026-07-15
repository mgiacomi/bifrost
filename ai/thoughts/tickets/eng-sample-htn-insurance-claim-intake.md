# Ticket: Sample HTN Skill Tree — Insurance Claim Intake

**Status:** Design locked (ready for implementation)  
**Priority:** P2 (enterprise narrative; slightly heavier than incident/support)  
**Module:** `bifrost-sample`  
**Implementation plan:** `ai/thoughts/plans/2026-07-15-eng-sample-htn-insurance-claim-intake.md`  
**Related tickets:**  
- `eng-sample-htn-incident-commander.md` (pattern source; already implemented)  
- `eng-sample-htn-support-case-resolver.md`  
- `eng-sample-htn-travel-concierge.md`  
- Existing sample: `duplicate_invoice_checker.yml` (shallow evidence_contract precedent)  
**Depends on:** Nested planning (existing); strong use of `output_schema` + `evidence_contract`; incident sample conventions  

---

## Summary

Add a **three-level HTN skill tree** for fake insurance claim intake: extract claim facts, assess coverage against policy data, run a lightweight fraud screen, and return a structured adjudication recommendation with **evidence-backed claims**.

This is the **enterprise / compliance-oriented** showcase: Bifrost is not only "agent picks tools," but "agent must ground decisions in tool evidence under an HTN structure."

## Motivation

Insurance (even fake) is a strong Bifrost story:

- Unstructured FNOL-style text needs extraction.  
- Coverage decisions need policy + exclusion tools (deterministic leaves).  
- Fraud signals need separate investigation branch.  
- Regulated-flavored outputs benefit from `evidence_contract` (already used by `duplicateInvoiceChecker` and root `handleIncident`).  
- Nested planners mirror real org structure: intake → coverage desk → SIU-lite.

Complements:

- Invoice sample → AP automation + evidence  
- Incident sample → ops branching  
- **Claim sample → risk decisioning + evidence**

## Goals

- Three-level skill stack with nested planners for coverage and fraud.  
- First-class **`evidence_contract` on root only** (mid-level digests structured, not contract-enforced).  
- Deterministic policy/claims history leaves (Java).  
- Structured recommendation disposition enum: `pay | partial_pay | deny | refer_human | refer_siu`.  
- **OpenRouter planner/worker models** matching incident (`qwen3-35b` / `gpt-4o-mini`) — not Ollama-first.  
- Five fixtures: clear-pay, exclusion-deny, fraud-refer, ambiguous, over-limit.  
- README that explains evidence tags, payout formula, disclaimer, and how to read the journal.  

## Non-Goals

- Real insurance regulations or actuarial correctness.  
- Computer vision damage estimation / attachments (separate follow-up).  
- Payment execution.  
- Full SIU case management.  
- Guaranteeing model legal accuracy (explicitly a demo).  
- Mid-level evidence contracts on `assessCoverage` / `fraudScreen`.  
- RBAC on fraud tools (future security sample).  
- Framework changes.  
- Live LLM in CI.  

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
processClaim                               [L1 planning YAML, model qwen3-35b]
├── extractClaimFacts                      [L2 LLM single-shot YAML, model gpt-4o-mini]
├── assessCoverage                         [L2 planning YAML, model qwen3-35b]
│   ├── getPolicy                          [L3 Java]
│   ├── checkExclusions                    [L3 Java]
│   └── estimatePayout                     [L3 Java]
├── fraudScreen                            [L2 planning YAML, model qwen3-35b]
│   ├── priorClaimsLookup                  [L3 Java]
│   ├── anomalyScore                       [L3 Java]
│   └── addressRiskSignals                 [L3 Java optional depth]
└── recommendDisposition                   [L2 LLM single-shot YAML, model gpt-4o-mini]
```

### Depth notes

- L1 orchestrates extract → coverage → fraud → recommend. **Order flexible; all four specialists are plan-required by root evidence.**  
- L2 coverage planner decides which policy tools to call (always get policy? skip estimate if hard-excluded?).  
- L2 fraud planner decides depth of screening (which fraud leaves). Fraud specialist itself is **not** optional.  
- L3 systems of record are fake but stable per `scenario` / optional `policyId`.

## What the LLM is allowed to decide

| Level | Fixed | LLM freedom |
| --- | --- | --- |
| L1 | Specialists only; plan must cover root evidence (all four) | Order; how digests are passed; when to recommend after desks complete |
| L2 coverage/fraud | Listed tools only | Which checks; when enough for a sub-conclusion |
| L2 extract/recommend | Schemas + prompts | Fact extraction; disposition language |
| L3 | Policy/history math | None |

## Skill inventory (locked)

### Planning skills

| Name | `allowed_skills` | Role | Model |
| --- | --- | --- | --- |
| `processClaim` | `extractClaimFacts`, `assessCoverage`, `fraudScreen`, `recommendDisposition` | Root | `qwen3-35b` |
| `assessCoverage` | `getPolicy`, `checkExclusions`, `estimatePayout` | Coverage desk | `qwen3-35b` |
| `fraudScreen` | `priorClaimsLookup`, `anomalyScore`, `addressRiskSignals` | SIU-lite | `qwen3-35b` |

### LLM single-shot skills

| Name | Purpose | Model |
| --- | --- | --- |
| `extractClaimFacts` | loss date, type, amount claimed, location, narrative entities | `gpt-4o-mini` |
| `recommendDisposition` | final structured recommendation from branch digests | `gpt-4o-mini` |

### Java leaves

Services: `InsurancePolicyService` + `ClaimsHistoryService`.

| Capability | Service method | Behavior |
| --- | --- | --- |
| `getPolicy` | `insurancePolicyService#getPolicy` | coverage limits, deductibles, active dates, product type |
| `checkExclusions` | `insurancePolicyService#checkExclusions` | list matched exclusions for loss type / keywords |
| `estimatePayout` | `insurancePolicyService#estimatePayout` | deterministic calc (see formula) |
| `priorClaimsLookup` | `claimsHistoryService#priorClaimsLookup` | prior claims count, recency, similar loss types |
| `anomalyScore` | `claimsHistoryService#anomalyScore` | 0–1 score from scenario rules |
| `addressRiskSignals` | `claimsHistoryService#addressRiskSignals` | optional address/velocity fake flags |

Mapped leaf YAML: only `name` / `description` / `mapping` (no schemas, model, planning, evidence).

## Models (locked — match incident commander)

| Role | Framework alias | OpenRouter provider model | Skills |
| --- | --- | --- | --- |
| **Planner** | `qwen3-35b` | `qwen/qwen3.6-35b-a3b` | `processClaim`, `assessCoverage`, `fraudScreen` |
| **Worker** | `gpt-4o-mini` | `openai/gpt-4o-mini` | `extractClaimFacts`, `recommendDisposition` |

| Layer | Value |
| --- | --- |
| Connection | Existing `openrouter` (`driver: openai`) — **reuse** incident wiring; no new connection keys |
| Credential | `${OPENROUTER_API_KEY:test-openrouter-api-key}` — dummy default for boot/CI; live demos need real key |
| Mapped leaves | Omit `model` |

**Why not Ollama-first / `granite4-tiny`:** Nested multi-desk claim reasoning needs a capable planner; same rationale as incident. Ticket originally proposed Ollama-first; **superseded 2026-07-15**.

**CI / tests:** Catalog, controller, and leaf tests must not call the live API. Live smoke is manual with a real `OPENROUTER_API_KEY`.

## Mission input / output (locked)

### Root input

```yaml
properties:
  claimText: { type: string }
  policyId: { type: string }       # optional
  claimedAmount: { type: number }  # optional
  scenario: { type: string }       # optional fixture key
required: [claimText]
additionalProperties: false
```

`policyId` is **optional**. Fixtures and `scenario` drive deterministic leaves; free-text POST may omit it.

### Extract skill output

```yaml
properties:
  lossDate: { type: string }              # nullable
  lossType:
    type: string
    enum: [auto, property, theft, liability, other]
  claimedAmount: { type: number }         # nullable
  location: { type: string }              # nullable
  description: { type: string }
  parties: { type: array, items: { type: string } }
required: [lossType, description, parties]
```

### recommendDisposition (locked — adjudication writer)

Teaching pattern (mirror incident `draftIncidentResponse`): recommend is a **single-shot writer**, not a second planner. Root orchestrates and **copies** recommend fields into the root report.

**Input:**

```yaml
properties:
  claimText: { type: string }
  extractedFactsSummary: { type: string }
  coverageSummary: { type: string }
  fraudSummary: { type: string }
  scenario: { type: string }   # optional
required: [claimText, extractedFactsSummary, coverageSummary, fraudSummary]
additionalProperties: false
```

**Output:** same field set as root report (`disposition`, optional `payableAmount`, `coverageSummary`, `fraudRisk`, `matchedExclusions`, `rationale`, `evidenceNotes`).

**Prompt biases:** ground in digests only; high fraud → `refer_siu`; hard exclusions → `deny`; missing facts → `refer_human`; prefer coverage `estimatedPayable` when paying/partial.

### claimedAmount precedence (locked)

1. Root/POST `claimedAmount` if present
2. Else extract `claimedAmount`
3. Else scenario enrichment default (if any)

### process-scenario enrichment (locked)

Preferred demo path sets `claimText` + `scenario` **and** injects static `policyId` / `claimedAmount` from a controller table consistent with Java leaf data. POST `/claims/process` does not auto-enrich.

| Scenario | `policyId` (example) | `claimedAmount` (example) |
| --- | --- | --- |
| `clear-auto-pay` | `POL-AUTO-1001` | `2200` |
| `exclusion-flood` | `POL-HOME-2002` | `15000` |
| `fraud-velocity` | `POL-AUTO-1001` | `4800` |
| `ambiguous-liability` | `POL-AUTO-1001` | omit or low placeholder |
| `over-limit` | `POL-AUTO-1001` | `25000` |

### Mid-level digests

**assessCoverage** required: `summary`, `covered`, `matchedExclusions`, `estimatedPayable`, `policyLimit`, `deductible`, `toolsUsed`, `confidence` (`low|medium|high`).

**fraudScreen** required: `summary`, `fraudRisk` (`low|medium|high`), `anomalyScore`, `priorClaimsCount`, `riskSignals`, `toolsUsed`, `confidence`.

### Root output

```yaml
properties:
  disposition:
    type: string
    enum: [pay, partial_pay, deny, refer_human, refer_siu]
  payableAmount: { type: number }         # not required (deny/refer may omit)
  coverageSummary: { type: string }
  fraudRisk:
    type: string
    enum: [low, medium, high]
  matchedExclusions:
    type: array
    items: { type: string }
  rationale: { type: string }
  evidenceNotes: { type: string }
required: [disposition, coverageSummary, fraudRisk, matchedExclusions, rationale, evidenceNotes]
additionalProperties: false
```

### Payout formula (locked)

Document in README and implement exactly in `estimatePayout`:

```
gross = min(claimedAmount, policyLimit)
payable = max(0, gross - deductible)
```

## Evidence contract (locked)

**Root only.** Mid-level planners have **no** `evidence_contract`.

Nested semantics (verified against framework + incident sample — **no spike required**):

- Evidence ledgers are local to each YAML mission boundary.  
- Parent snapshots/restores plan + evidence when invoking a nested YAML skill.  
- Leaf evidence does **not** bubble to the parent ledger.  
- Parent `tool_evidence` names **direct L2 children**, not L3 leaves.

```yaml
evidence_contract:
  claims:
    disposition: [coverage_assessment, fraud_assessment, disposition_recommendation]
    payableAmount: [coverage_assessment]
    coverageSummary: [coverage_assessment]
    fraudRisk: [fraud_assessment]
    matchedExclusions: [coverage_assessment]
    rationale: [claim_facts, coverage_assessment, fraud_assessment, disposition_recommendation]
    evidenceNotes: [claim_facts, coverage_assessment, fraud_assessment]
  tool_evidence:
    extractClaimFacts: [claim_facts]
    assessCoverage: [coverage_assessment]
    fraudScreen: [fraud_assessment]
    recommendDisposition: [disposition_recommendation]
```

**Plan coverage implication:** successful plans must include extract + coverage + fraud + recommend. Fraud is **not** skippable; clean claims still run SIU-lite and return `fraudRisk: low`.

Do **not** put L3 names (`getPolicy`, `anomalyScore`, …) in root `tool_evidence`.

## Canned scenarios / fixtures (locked)

Place under `src/main/resources/fixtures/insurance/claims/` (and optional `policies/*.json`).

| Scenario key | Claim gist | Expected disposition bias |
| --- | --- | --- |
| `clear-auto-pay` | Minor covered collision, clean history | `pay` or `partial_pay` after deductible |
| `exclusion-flood` | Water damage where flood excluded | `deny` + matched exclusions |
| `fraud-velocity` | Third similar claim in 60 days, high anomaly | `refer_siu` |
| `ambiguous-liability` | Unclear fault, missing date | `refer_human` |
| `over-limit` | Claimed amount above policy limit | `partial_pay` at formula |

Leaf data supports the story; unknown `scenario` returns neutral valid data (no exceptions).

## HTTP API (locked)

New controller: `com.lokiscale.bifrost.sample.insurance.ClaimsController` (do **not** grow `SampleController` / `IncidentController`).

| Method | Path | Behavior |
| --- | --- | --- |
| `POST` | `/claims/process` | JSON: `claimText` + optional `policyId`, `claimedAmount`, `scenario` |
| `GET` | `/claims/scenarios` | List fixture keys + short descriptions |
| `GET` | `/claims/process-scenario?name=...` | Load fixture text + set `scenario` + inject static `policyId`/`claimedAmount`; preferred live demo path |

Response: `result`, `sessionId`, `executionJournal`.

## Package / file layout (locked)

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
    address_risk_signals.yml
  resources/fixtures/insurance/
    claims/clear-auto-pay.txt
    claims/exclusion-flood.txt
    claims/fraud-velocity.txt
    claims/ambiguous-liability.txt
    claims/over-limit.txt
    policies/pol-1001.json   # optional if data inlined in Java
```

## Documentation requirements

- Tree diagram with evidence story.  
- Side-by-side with `duplicateInvoiceChecker` (2-level) and `handleIncident` (3-level light evidence).  
- Explicit disclaimer: demo only, not real insurance advice.  
- Exact payout formula.  
- How to inspect evidence-related journal entries (L2 names at root).  
- Scenario table with expected dispositions (bias, not guarantees).  
- Model setup: OpenRouter planner/worker reuse.

## Tests

- Leaf math: `estimatePayout` deterministic for known policy + amount.  
- Exclusion matching deterministic for fixture keywords.  
- Catalog registration; planners' `allowed_skills`; root evidence shape (no L3 keys).  
- Mid-level evidence contracts empty.  
- Model aliases: planners `qwen3-35b`, workers `gpt-4o-mini`.  
- Controller delegation + scenario list + unknown scenario rejection.  
- No live LLM in CI.

## Acceptance criteria

- [ ] Nested coverage and fraud planners call Java leaves.  
- [ ] Root defines `output_schema` + locked `evidence_contract` (L2 tool names only).  
- [ ] Mid-level planners have **no** evidence contract.  
- [ ] Five fixtures covering pay/partial, deny, SIU refer, human refer, over-limit.  
- [ ] Payout estimation is deterministic Java using the locked formula.  
- [ ] HTTP returns journal metadata under `/claims/*`.  
- [ ] Planners use `qwen3-35b`; extract/recommend use `gpt-4o-mini` (OpenRouter).  
- [ ] README includes compliance-oriented teaching points, formula, and disclaimer.  
- [ ] Nested evidence attribution verified manually once with a live run.  
- [ ] CI tests pass without network / real API keys.

## Open design questions

**All resolved 2026-07-15.** See Design discussion notes.

~~1. Evidence attribution across nested planners~~ → Parent credits L2 specialists only; leaf tags do not bubble.  
~~2. Disposition enum~~ → `pay | partial_pay | deny | refer_human | refer_siu`.  
~~3. recommendDisposition planning vs single-shot~~ → Single-shot worker.  
~~4. Policy id required~~ → Optional; scenario/fixtures drive leaves.  
~~5. Attachments~~ → Out of scope (follow-up).  
~~6. Partial pay formula~~ → Locked above.  
~~7. Model strength~~ → OpenRouter planner/worker like incident.  
~~8. RBAC demo~~ → Out of scope.  
~~9. Gallery index~~ → Section in `bifrost-sample/README.md` (same as incident).
~~10. recommendDisposition I/O~~ → Full root-shaped writer output; string digest inputs.
~~11. process-scenario payload~~ → claimText + scenario + static policyId/claimedAmount enrichment.
~~12. claimedAmount precedence~~ → root/POST → extract → scenario default.

## Implementation sketch

1. Policy/history Java services + fixtures.  
2. Mapped leaves.  
3. Extract + recommend LLM skills.  
4. Coverage + fraud planners.  
5. Root planner with evidence contract.  
6. HTTP + tests + README.  
7. Capture one full journal for docs (manual smoke).

(No nesting/evidence spike — already verified via incident + framework tests.)

## Risks

- Small local models are **not** used for this tree; if OpenRouter is unavailable, live demos fail (boot still works with dummy key).  
- Requiring all four specialists increases minimum plan breadth and cost vs optional-fraud designs — intentional for teaching.  
- Users may over-trust demo dispositions — docs must disclaimer clearly.  
- Mid-level selective branching (skip estimate when excluded) is soft/model-dependent; do not golden-file it.

## Design discussion notes

- Owner: TBD  
- Reviewers: TBD  
- Decisions log (2026-07-15):  
  - **Models:** OpenRouter planner `qwen3-35b` / worker `gpt-4o-mini` (match incident); supersedes original Ollama-first goal.  
  - **Disposition enum:** `pay | partial_pay | deny | refer_human | refer_siu`.  
  - **Evidence:** Root-only contract; mid-level digests without contracts.  
  - **Fraud:** Plan-required via root evidence (always run; clean → low).  
  - **recommendDisposition:** Single-shot adjudication writer; full root-shaped output; root copies fields.
  - **Recommend input:** claimText + three string digests (extract/coverage/fraud).
  - **policyId:** Optional.
  - **claimedAmount precedence:** root/POST → extract → scenario enrichment default.
  - **process-scenario:** injects policyId + claimedAmount from static table.
  - **Payout formula:** `max(0, min(claimed, limit) - deductible)`.
  - **Attachments / RBAC:** Out of scope.  
  - **Testing plan:** Owner runs `3_testing_plan.md` after this plan.  
  - **Skill-authoring KB:** No impact (sample-only).  
- Spike results (evidence + nesting):  
  - **Not required as new work.** Framework isolates nested evidence ledgers; parent credits child capability names from parent `tool_evidence`. Confirmed by `ai/skill-authoring/evidence-contracts.md`, `CapabilityExecutionRouterTest`, and shipped `handleIncident` sample.  
- Implementation plan: `ai/thoughts/plans/2026-07-15-eng-sample-htn-insurance-claim-intake.md`
