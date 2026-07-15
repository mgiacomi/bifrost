# Sample HTN Skill Tree — Insurance Claim Intake Implementation Plan

## Overview

Add a three-level HTN insurance claim-intake skill tree to `bifrost-sample` that demonstrates nested planning (root planner → coverage/fraud mid-level planners → deterministic Java leaves), governed `allowed_skills`, a **root-only** `evidence_contract` that plan-requires extract + coverage + fraud + recommend, and OpenRouter-backed model routing with planner alias `qwen3-35b` and worker alias `gpt-4o-mini` (same as incident commander). No framework changes.

This is the **enterprise / compliance** gallery piece: evidence-backed risk decisioning under HTN structure.

## Current State Analysis

- `skills/insurance/` exists with only `.gitkeep`; README lists insurance under HTN gallery (planned).
- **Incident commander is the primary template** (already shipped): package layout, controller, fixtures, catalog tests, README section, nested evidence rules.
- Existing sample patterns to mirror:
  - Mapped leaf: `skills/basics/expense_lookup.yml` / incident probes (minimal `name` / `description` / `mapping`).
  - Single-shot LLM: `skills/incidents/classify_incident.yml`, `draft_incident_response.yml`.
  - Nested planning + root evidence: `skills/incidents/handle_incident.yml`.
  - Shallow evidence: `skills/basics/duplicate_invoice_checker.yml` (2-level contrast).
  - HTTP + journal: `IncidentController` uses `SkillTemplate.invoke(..., observer)` → `result` / `sessionId` / `executionJournal`.
- OpenRouter connection + planner/worker aliases already exist in `application.yml` from the incident sample — **reuse**, do not re-add.
- Nested YAML missions snapshot/restore parent plan + evidence (`CapabilityExecutionRouter`); leaf evidence does **not** bubble to the parent ledger.
- Parent `tool_evidence` must name **L2 specialists**, not L3 leaves (documented in `ai/skill-authoring/evidence-contracts.md`; proven by incident + `CapabilityExecutionRouterTest`).
- Tests today: sample + incident catalog/controller/leaf tests; no live LLM in CI.
- Ticket design locked 2026-07-15 (see ticket Design discussion notes + Locked decisions).

## Desired End State

A runnable sample path where:

1. All 11 insurance YAML skills load from `classpath:/skills/insurance/**/*.yml`.
2. Root `processClaim` invokes mid-level `assessCoverage` / `fraudScreen` (planning) which invoke Java-mapped leaves; also invokes single-shot `extractClaimFacts` and `recommendDisposition`.
3. Root evidence contract plan-requires: extract + coverage + fraud + recommend (all four specialists).
4. HTTP endpoints under `/claims/*` return structured recommendation + `sessionId` + `executionJournal`.
5. Sample boots and unit tests pass without a real OpenRouter key; live smoke uses existing `OPENROUTER_API_KEY`.
6. README documents the tree, evidence rules (vs invoice + incident), payout formula, scenario bias, disclaimer, journal nesting.

### Key Discoveries:

- Nested isolation is already enforced: parent `tool_evidence` names L2 specialists (`extractClaimFacts`, `assessCoverage`, `fraudScreen`, `recommendDisposition`), not L3 leaves (`getPolicy`, etc.).
- No design spike needed for nesting + evidence composition — incident sample + authoring KB already settle this.
- Mapped leaves must omit schemas/model/planning/evidence so Java remains the single contract source.
- Skill discovery already covers `classpath:/skills/**/*.yml`; subdirectory `skills/insurance/` needs no config change.
- OpenRouter + `qwen3-35b` / `gpt-4o-mini` already wired; insurance skills only reference those aliases.

## What We're NOT Doing

- Framework changes (unless a hard gap is discovered; escalate rather than patch around).
- Real insurance regulations, actuarial correctness, or payment execution.
- Computer vision / damage photos (`type: attachment`) — separate follow-up.
- Full SIU case management.
- Golden-file exact LLM outputs or live-model CI tests.
- Replacing invoice/feedstock/incident samples.
- RBAC on fraud tools (future security sample).
- Mid-level `evidence_contract` on `assessCoverage` / `fraudScreen` (root-only for v1).
- Defaulting this tree to `granite4-tiny` / Ollama-only (ticket originally said Ollama-first; **superseded** by locked OpenRouter planner/worker split matching incident).
- Optional free-text-only GET without scenario fixtures as the preferred path.
- Mirroring fixtures under `src/test/resources`.
- Updating `ai/skill-authoring/` (sample-only work).
- Running `3_testing_plan.md` as a prerequisite (owner will run later).

## Skill-Authoring Documentation Impact

**Impact**: No impact

- **Rationale**: This work only adds sample composition in `bifrost-sample`. It does not change manifest syntax, validation, defaults, planning/evidence semantics, model resolution, or any other author-facing framework behavior. Nested planning, evidence isolation, mapped-leaf contract ownership, and named connections are already documented in `ai/skill-authoring/`.
- **Documents to update**: None
- **Supporting evidence**: Ticket non-goals (no framework changes); existing KB topics (`mental-model.md`, `evidence-contracts.md`, `model-selection-and-connections.md`); framework tests such as `CapabilityExecutionRouterTest` nested evidence restore; incident sample as living reference.
- **Coverage table update**: Not required
- **LLM-first usability**: Not applicable

## Implementation Approach

Bottom-up, sample-only, mirror incident phasing:

1. Implement deterministic leaves + policy/claim fixtures (testable without models). No new model config.
2. Author the full YAML tree with locked contracts/prompts from the ticket.
3. Add `ClaimsController` for demos.
4. Add catalog / controller / leaf tests that never call the live API.
5. Document in `bifrost-sample/README.md`.
6. Manual smoke with a real OpenRouter key (human).

All locked YAML field values, evidence contract, I/O schemas, disposition enum, and formulas are taken from the locked ticket sections. Do not invent alternate shapes at implement time.

## Phase 1: Java Leaves, Mapped YAML, and Fixtures

### Overview

Add deterministic policy and claims-history capabilities plus five canned claim fixtures. Leaves accept `scenario` (and optional `policyId` / claim fields) and return stable canned structures; unknown scenarios return neutral valid data (no exceptions).

### Changes Required:

#### 1. Policy service
**File**: `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/insurance/InsurancePolicyService.java`  
**Changes**: New `@Service` with three `@SkillMethod` methods. Use `@ToolParam` for parameter description/requiredness.

| Method | Params | Behavior |
| --- | --- | --- |
| `getPolicy` | `scenario` (required), `policyId` (optional) | limits, deductible, active dates, product type |
| `checkExclusions` | `scenario`, optional `lossType` / keywords | list matched exclusion codes/strings |
| `estimatePayout` | `scenario`, `claimedAmount` (required), optional `policyId` | deterministic formula (below) |

**Locked partial-pay / payout formula** (document in README + implement exactly):

```
gross = min(claimedAmount, policyLimit)
payable = max(0, gross - deductible)
```

- If exclusions match for the loss (scenario-driven): return `payableAmount: 0` and note exclusion (coverage desk still returns estimate metadata; disposition is model-side).
- Unknown scenario: use a neutral policy (e.g. limit 5000, deductible 500) and compute the same formula.

Target IDs (Spring default bean name `insurancePolicyService`):

- `insurancePolicyService#getPolicy`
- `insurancePolicyService#checkExclusions`
- `insurancePolicyService#estimatePayout`

#### 2. Claims history service
**File**: `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/insurance/ClaimsHistoryService.java`  
**Changes**: New `@Service` with three `@SkillMethod` methods.

| Method | Params | Behavior |
| --- | --- | --- |
| `priorClaimsLookup` | `scenario` (required) | prior claim count, recency, similar loss types |
| `anomalyScore` | `scenario` | 0.0–1.0 score + short reason |
| `addressRiskSignals` | `scenario` | optional address/velocity flags (may be empty list for clean scenarios) |

Target IDs:

- `claimsHistoryService#priorClaimsLookup`
- `claimsHistoryService#anomalyScore`
- `claimsHistoryService#addressRiskSignals`

Scenario keys that must have story-supporting data:

| Key | Leaf bias |
| --- | --- |
| `clear-auto-pay` | Covered auto; clean history; low anomaly; estimate after deductible |
| `exclusion-flood` | Flood/water exclusion match; deny-supporting exclusions |
| `fraud-velocity` | Multiple similar claims in 60 days; high anomaly; address risk flags |
| `ambiguous-liability` | Incomplete facts; mild/neutral coverage; low–medium fraud |
| `over-limit` | Claimed amount above policy limit; clean fraud; partial at formula |

#### 3. Minimal mapped leaf manifests
**Files** under `bifrost-sample/src/main/resources/skills/insurance/`:

- `get_policy.yml`
- `check_exclusions.yml`
- `estimate_payout.yml`
- `prior_claims_lookup.yml`
- `anomaly_score.yml`
- `address_risk_signals.yml`

Each file shape (example):

```yaml
name: getPolicy
description: Retrieves policy limits, deductible, and product metadata for the claim scenario.
mapping:
  target_id: insurancePolicyService#getPolicy
```

Rules:

- Only `name`, `description`, `mapping` (no schemas, model, planning, evidence).
- Public YAML `name` is lowerCamelCase matching the skill inventory.
- Remove `skills/insurance/.gitkeep` once real files exist (or leave; harmless).

#### 4. Claim text + optional policy JSON fixtures
**Files** under `bifrost-sample/src/main/resources/fixtures/insurance/`:

Claims (required):

- `claims/clear-auto-pay.txt`
- `claims/exclusion-flood.txt`
- `claims/fraud-velocity.txt`
- `claims/ambiguous-liability.txt`
- `claims/over-limit.txt`

Policies (optional but preferred for realism; may be inlined in Java if simpler — prefer JSON if loading is straightforward):

- `policies/pol-1001.json` (and additional as needed per scenario)

Each claim fixture is free-text FNOL-style prose matching the scenario gist table. Prefer `process-scenario` so the model does not invent `scenario`.

### Success Criteria:

#### Automated Verification:
- [x] Module compiles: `.\mvnw.cmd -pl bifrost-sample -am test-compile`
- [x] Leaf unit tests (Phase 4) pass for known + unknown scenarios and payout formula
- [x] Targets register as `insurancePolicyService#...` / `claimsHistoryService#...` (catalog tests Phase 4)

#### Manual Verification:
- [ ] Fixture text is readable and domain-plausible for demos

---

## Phase 2: LLM and Planning Skill Tree YAML

### Overview

Author the five LLM-backed skills with locked I/O, planning settings, root-only evidence contract, and private prompts. Planners use `model: qwen3-35b`; single-shot workers use `model: gpt-4o-mini`.

### Changes Required:

#### 1. Single-shot skills (workers)
**Files**:

- `bifrost-sample/src/main/resources/skills/insurance/extract_claim_facts.yml`
- `bifrost-sample/src/main/resources/skills/insurance/recommend_disposition.yml`

**Locks**:

- `planning_mode: false` (or omit)
- `model: gpt-4o-mini`
- `output_schema_max_retries: 2`
- Full `input_schema` / `output_schema` exactly as ticket locked sections
- Short public `description`; private `prompt` for extraction/recommendation guidance (concise; no linter)

**extractClaimFacts** output (locked):

- `lossDate` (nullable string)
- `lossType` enum: `auto | property | theft | liability | other`
- `claimedAmount` (nullable number)
- `location` (nullable string)
- `description` (string)
- `parties` (array of string)

**recommendDisposition** (locked — teaching-oriented, mirror `draftIncidentResponse`):

- **Role:** single-shot **adjudication writer**, not a second planner. Root remains the HTN orchestrator; recommend is the final structured synthesis step forced by evidence (`disposition_recommendation`).
- **Input** (string digests + original claim — easy for root to pass tool results without inventing nested JSON schemas):

```yaml
properties:
  claimText: { type: string }
  extractedFactsSummary: { type: string }   # compact extract result (JSON string or prose)
  coverageSummary: { type: string }         # assessCoverage digest summary / JSON string
  fraudSummary: { type: string }            # fraudScreen digest summary / JSON string
  scenario: { type: string }                # optional
required: [claimText, extractedFactsSummary, coverageSummary, fraudSummary]
additionalProperties: false
```

- **Output** (same field set as root report so root **copies** rather than re-authors numbers/disposition):

```yaml
properties:
  disposition: { type: string, enum: [pay, partial_pay, deny, refer_human, refer_siu] }
  payableAmount: { type: number }           # not required
  coverageSummary: { type: string }
  fraudRisk: { type: string, enum: [low, medium, high] }
  matchedExclusions: { type: array, items: { type: string } }
  rationale: { type: string }
  evidenceNotes: { type: string }
required: [disposition, coverageSummary, fraudRisk, matchedExclusions, rationale, evidenceNotes]
additionalProperties: false
```

- **Prompt rules:** Do not invent policy limits, payouts, exclusions, or fraud scores; ground disposition in the three digests. Prefer coverage desk `estimatedPayable` for `payableAmount` when paying/partial. High fraud → bias `refer_siu`. Hard exclusions → bias `deny`. Missing/ambiguous facts → bias `refer_human`.
- **Why this shape teaches best:** Journal shows a clear pipeline extract → desks → **writer**; evidence tags map 1:1 to specialists; root prompt is “orchestrate + assemble,” not “re-judge the claim.” A thinner recommend (only disposition + rationale) would force the root model to re-synthesize structured fields and blur who owns adjudication language.  
- **claimedAmount precedence (locked):** when forwarding amounts into coverage leaves / digests: **(1)** root/POST `claimedAmount` if present, else **(2)** extract `claimedAmount`, else **(3)** scenario default from the fixture table (if any). Document in root + coverage prompts.  
- **process-scenario enrichment (locked):** preferred demo path sets `claimText` + `scenario` **and** injects optional `policyId` / `claimedAmount` from a static per-scenario table in `ClaimsController` (not only free text). That makes over-limit / clear-pay demos deterministic without relying on the extract worker. POST `/claims/process` remains free-form (caller supplies optional fields).  
- **Scenario enrichment table** (controller-owned; values must match leaf story data):

| Scenario | `policyId` | `claimedAmount` |
| --- | --- | --- |
| `clear-auto-pay` | e.g. `POL-AUTO-1001` | e.g. `2200` (under limit, after deductible → pay/partial) |
| `exclusion-flood` | e.g. `POL-HOME-2002` | e.g. `15000` |
| `fraud-velocity` | e.g. `POL-AUTO-1001` | e.g. `4800` |
| `ambiguous-liability` | e.g. `POL-AUTO-1001` | omit or low placeholder |
| `over-limit` | e.g. `POL-AUTO-1001` | e.g. `25000` (above policy limit) |

Exact numeric limits/deductibles live in Java leaf data and must be consistent with this table.
| --- | --- | --- | |  
Wait I made a markdown error - let me fix that. The last part of the table got corrupted. Let me do a clean edit. 

#### 2. Mid-level planners
**Files**:

- `assess_coverage.yml`
- `fraud_screen.yml`

**Locks**:

```yaml
model: qwen3-35b
planning_mode: true
max_steps: 6
output_schema_max_retries: 2
# no evidence_contract
```

| Skill | `allowed_skills` | Digest role |
| --- | --- | --- |
| `assessCoverage` | `getPolicy`, `checkExclusions`, `estimatePayout` | Coverage desk digest |
| `fraudScreen` | `priorClaimsLookup`, `anomalyScore`, `addressRiskSignals` | SIU-lite digest |

Mid-level `output_schema` (locked shape — structured digests for parent/recommend):

**assessCoverage** required fields:

- `summary` (string)
- `covered` (boolean) — whether loss appears covered absent exclusions
- `matchedExclusions` (array of string)
- `estimatedPayable` (number, nullable)
- `policyLimit` (number, nullable)
- `deductible` (number, nullable)
- `toolsUsed` (array of string)
- `confidence` enum: `low | medium | high`

**fraudScreen** required fields:

- `summary` (string)
- `fraudRisk` enum: `low | medium | high`
- `anomalyScore` (number, nullable)
- `priorClaimsCount` (integer, nullable)
- `riskSignals` (array of string)
- `toolsUsed` (array of string)
- `confidence` enum: `low | medium | high`

Private `prompt` MUST instruct:

- Forward `scenario` (and `policyId` / claim fields when present) to every leaf that accepts them.
- Call only needed leaves; stop when digest is supportable.
- Do not invent policy/history numbers; base digests on actual leaf results.
- `toolsUsed` reflects actual invocations.
- Coverage: prefer `getPolicy` early; skip `estimatePayout` if hard exclusion deny is clear (optional judgment).
- Fraud: prefer `priorClaimsLookup` + `anomalyScore`; use `addressRiskSignals` when velocity/address is in play.

#### 3. Root planner
**File**: `process_claim.yml`

**Locks**:

- `model: qwen3-35b`
- `planning_mode: true`
- `max_steps: 10`
- `output_schema_max_retries: 2`
- `allowed_skills: [extractClaimFacts, assessCoverage, fraudScreen, recommendDisposition]`
- Locked root `input_schema` / `output_schema` from ticket
- Locked root `evidence_contract` (root-only; all four specialists plan-required):

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

**Why fraud is required:** the sample teaches coverage **and** SIU-lite. Clean claims still run fraud and get `fraudRisk: low` — screening ran and was clean, not skipped.

**Why recommend is in the contract:** disposition language is synthesized by the worker; crediting it forces the plan to include the synthesis step (parallel to incident’s `draftIncidentResponse` / `response_draft`).

Private `prompt` MUST instruct:

1. Call `extractClaimFacts` early with `claimText` (+ `scenario` / optional structured fields).
2. Call `assessCoverage` with claim facts + `scenario` / `policyId` / effective claimed amount.
3. Call `fraudScreen` with claim facts + `scenario` (always — required by evidence).
4. Call `recommendDisposition` with `claimText` + string digests from extract, coverage, and fraud.
5. **Copy** recommendDisposition fields into the root report (do not re-author disposition / payable math).
6. Do not invent policy/history math; do not call L3 leaves directly.
7. Amount precedence when forwarding to coverage: root `claimedAmount` → else extract → else scenario default.

Do **not** put L3 leaf names in root `tool_evidence` or `allowed_skills`.

**Cross-cutting locks (tests/docs):** catalog asserts recommendDisposition locked I/O; README documents recommend-as-writer, enrichment table, and amount precedence.

**Root input** (locked):

```yaml
properties:
  claimText: { type: string }
  policyId: { type: string }      # optional
  claimedAmount: { type: number } # optional
  scenario: { type: string }      # optional
required: [claimText]
additionalProperties: false
```

**Root output** (locked):

```yaml
properties:
  disposition:
    type: string
    enum: [pay, partial_pay, deny, refer_human, refer_siu]
  payableAmount: { type: number }   # nullable in schema if framework supports; else number with null via optional required list
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

Note: `payableAmount` is present in properties but **not** required (deny/refer paths may omit or null). If nullable schema support matches incident patterns, mark nullable; otherwise allow omission and keep it out of `required`.

### Success Criteria:

#### Automated Verification:
- [x] Sample context loads with all 11 insurance skills registered: `.\mvnw.cmd -pl bifrost-sample test`
- [x] Catalog tests assert planning flags, allowed_skills, evidence shape, model aliases (Phase 4)
- [x] No startup validation errors for manifests

#### Manual Verification:
- [ ] Spot-check YAML names match lowerCamelCase inventory and file layout from ticket

---

## Phase 3: HTTP API (`ClaimsController`)

### Overview

Expose demo endpoints without growing `SampleController` or `IncidentController`. Preferred live path loads fixture text and sets `scenario` explicitly.

### Changes Required:

#### 1. Controller
**File**: `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/insurance/ClaimsController.java`

| Method | Path | Behavior |
| --- | --- | --- |
| `POST` | `/claims/process` | Body: `claimText` + optional `policyId`, `claimedAmount`, `scenario` → `skillTemplate.invoke("processClaim", inputs, observer)` |
| `GET` | `/claims/scenarios` | List five fixture keys + short descriptions |
| `GET` | `/claims/process-scenario?name=...` | Load fixture text, set `scenario=name`, inject static `policyId`/`claimedAmount` from enrichment table, invoke `processClaim` |

Implementation notes:

- Inject `SkillTemplate` + `ResourceLoader` (same pattern as `IncidentController`).
- Use observer/`ViewHolder` pattern for `sessionId` + `executionJournal`.
- Response envelope:

```json
{
  "result": "{ ... }",
  "sessionId": "...",
  "executionJournal": { }
}
```

- Unknown scenario name → clear 4xx (`ResponseStatusException`).
- Log session id + elapsed ms like existing controllers.
- Request DTO: record with `claimText`, optional `policyId`, `claimedAmount`, `scenario`.
- **process-scenario** applies the Phase 2 scenario enrichment table (`claimText` + `scenario` + static `policyId`/`claimedAmount`). Do not rely on extract alone for over-limit / clear-pay demos.
- POST `/claims/process` does **not** auto-enrich; the caller supplies optional structured fields.
- Prefer concrete policy IDs/amounts in the enrichment table at implement time (same values as Java leaves).
- Controller unit tests assert enrichment fields are present on invoke for at least `over-limit` and `clear-auto-pay`.

#### 2. Do not modify
**Files**: `SampleController.java`, `IncidentController.java` — leave existing endpoints unchanged.

### Success Criteria:

#### Automated Verification:
- [x] Controller unit tests pass with mocked `SkillTemplate` (Phase 4)
- [x] Module tests pass: `.\mvnw.cmd -pl bifrost-sample test`

#### Manual Verification:
- [ ] With app running and real `OPENROUTER_API_KEY`, `GET /claims/scenarios` lists five keys
- [ ] `GET /claims/process-scenario?name=clear-auto-pay` returns recommendation + journal (Phase 5 smoke)

---

## Phase 4: Automated Tests

### Overview

CI-safe tests only: catalog shape, controller delegation, leaf canned data and math. No live OpenRouter calls.

### Changes Required:

#### 1. Catalog / context tests
**File**: `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/insurance/InsuranceSkillCatalogTests.java`  
**Style**: `@SpringBootTest(classes = SampleApplication.class, webEnvironment = NONE)` like `IncidentSkillCatalogTests`.

Assert at minimum:

- All public skills registered:  
  `processClaim`, `extractClaimFacts`, `assessCoverage`, `fraudScreen`, `recommendDisposition`,  
  `getPolicy`, `checkExclusions`, `estimatePayout`, `priorClaimsLookup`, `anomalyScore`, `addressRiskSignals`
- Java target IDs registered; raw method names / target IDs are **not** public capabilities
- Mid-level: `planning_mode` true, `max_steps` 6, expected `allowed_skills`, **empty** evidence contract
- Root: `planning_mode` true, `max_steps` 10, locked `allowed_skills`, evidence claims + tool_evidence shape (L2 only; no L3 keys)
- Planning skills resolve model alias `qwen3-35b`; extract/recommend resolve `gpt-4o-mini`
- Config: existing `openrouter` + aliases still wired (assert same as incident if useful; do not require new connection keys)
- Required output fields present on root / extract / mid / recommend via catalog

#### 2. Controller unit tests
**File**: `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/insurance/ClaimsControllerTest.java`  
**Style**: mock `SkillTemplate` like `IncidentControllerTest`.

Assert:

- POST process invokes `processClaim` with `claimText` (+ optional fields)
- `process-scenario` loads fixture text, sets `scenario` to name, invokes `processClaim`
- Response includes `result`, `sessionId`, `executionJournal` when observer is used
- `scenarios` returns the five known keys
- Unknown scenario name fails clearly

#### 3. Leaf unit tests
**Files**:

- `InsurancePolicyServiceTest.java`
- `ClaimsHistoryServiceTest.java`

Assert:

- Known scenarios return stable story-supporting data
- Unknown scenario returns neutral valid structures (no throw)
- `estimatePayout` formula: `max(0, min(claimed, limit) - deductible)` for known policies
- Exclusion matching deterministic for flood scenario keywords/loss type
- Fraud scenario returns elevated anomaly / prior claims / risk signals

### Success Criteria:

#### Automated Verification:
- [x] All sample tests pass: `.\mvnw.cmd -pl bifrost-sample test`
- [x] Full reactor optional: `.\mvnw.cmd test` (if time allows)
- [x] No test depends on network or real API keys

#### Manual Verification:
- [ ] Test names and packages are clear for future HTN gallery samples

---

## Phase 5: README Documentation and Live Smoke

### Overview

Document the sample for humans and LLM collaborators; verify nested planning + evidence once with a real key.

### Changes Required:

#### 1. README updates
**File**: `bifrost-sample/README.md`

Replace the insurance row in "HTN gallery (remaining)" with a full **Insurance (`skills/insurance/`)** section covering:

- Tree diagram (3-level skill stack)
- Framework features table (nested planning + **stronger** root evidence vs incident light contract vs invoice 2-level)
- What LLM decides vs fixed by YAML/Java (fraud specialist is plan-required; depth inside fraud is free)
- Evidence contract rules: AND semantics; L2 tool names only; plan coverage requires extract + coverage + fraud + recommend; leaf evidence does not bubble
- Side-by-side with `duplicateInvoiceChecker` and `handleIncident`
- Scenario plumbing (`scenario` forwarded; prefer `process-scenario`)
- **Payout formula** documented exactly
- Model setup: reuse OpenRouter; planner `qwen3-35b` / worker `gpt-4o-mini`; not `granite4-tiny`
- Example requests for 2–3 scenarios (PowerShell + curl)
- How to read journal nesting: MISSION → PLANNING → TOOL → nested MISSION
- **Explicit disclaimer**: demo only, not real insurance advice / not legally binding
- Update prerequisites (OpenRouter already listed), project layout, HTTP API table, tests table, troubleshooting

#### 2. Manual smoke (human, not CI)

With real key:

```powershell
$env:OPENROUTER_API_KEY = "sk-or-..."
.\mvnw.cmd -pl bifrost-sample spring-boot:run
# then:
Invoke-RestMethod http://localhost:8081/claims/scenarios
Invoke-RestMethod "http://localhost:8081/claims/process-scenario?name=clear-auto-pay"
Invoke-RestMethod "http://localhost:8081/claims/process-scenario?name=exclusion-flood"
Invoke-RestMethod "http://localhost:8081/claims/process-scenario?name=fraud-velocity"
```

Verify:

- Nested frames appear in `executionJournal`
- Coverage and fraud mid-level planners ran leaves
- Report fields present; disposition bias roughly matches scenario (not brittle exact JSON)
- Evidence-related plan/tool events show L2 specialist names at root

### Success Criteria:

#### Automated Verification:
- [x] `.\mvnw.cmd -pl bifrost-sample test` still passes after README-only edits
- [x] README references real paths/endpoints that exist in code

#### Manual Verification:
- [ ] Clear-pay and exclusion-flood smokes show nested planning and sensible disposition bias
- [ ] Fraud-velocity biases toward `refer_siu`
- [ ] Boot without `OPENROUTER_API_KEY` still succeeds; only live process endpoints need the key
- [ ] Acceptance criteria checklist in the ticket can be marked complete

---

## Testing Strategy

### Unit Tests:
- Leaf canned data stability, unknown-scenario neutrality, payout formula, exclusion matching
- Controller input mapping and response envelope with mocked `SkillTemplate`
- Catalog registration, planning flags, allowed_skills, root evidence contract shape (no L3 keys), model aliases

### Integration Tests:
- None required for CI live LLM
- Optional future: test-double chat client for nested planning path (out of v1 scope)

### Manual Testing Steps:
1. Boot sample without OpenRouter key → context loads, unit tests green
2. Set real key; run `clear-auto-pay`, `exclusion-flood`, `fraud-velocity` via `process-scenario`
3. Inspect journal for nested MISSION frames and L2 tool names at root
4. Confirm fraud specialist always appears in successful plans (evidence-required)
5. Confirm mid-level does not always call every leaf (model judgment; soft check)

**Note**: A dedicated testing plan via `3_testing_plan.md` is optional and deferred to the owner.

## Performance Considerations

- Nested planning with a large model will be slower and costlier than invoice samples; session timeout is already `6000s`.
- Mid-level `max_steps: 6` and selective leaf prompts reduce unnecessary tool loops.
- Requiring fraud + coverage + extract + recommend increases minimum plan breadth vs optional-fraud designs — intentional for the teaching sample.
- Dummy key ensures CI does not hang on network; live demos should expect multi-minute runs.

## Migration Notes

- Additive only: new package, skills, fixtures, README sections.
- No new `application.yml` connection/model keys required (reuse incident OpenRouter wiring).
- No data migration; no breaking changes to existing sample endpoints.

## File checklist (final tree)

```
bifrost-sample/src/main/
  java/.../sample/insurance/
    ClaimsController.java
    InsurancePolicyService.java
    ClaimsHistoryService.java
  resources/
    fixtures/insurance/
      claims/
        clear-auto-pay.txt
        exclusion-flood.txt
        fraud-velocity.txt
        ambiguous-liability.txt
        over-limit.txt
      policies/                    # optional JSON if not inlined
        pol-1001.json
        ...
    skills/insurance/
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
  README.md                                  # insurance section + tables

bifrost-sample/src/test/java/.../sample/insurance/
  InsuranceSkillCatalogTests.java
  ClaimsControllerTest.java
  InsurancePolicyServiceTest.java
  ClaimsHistoryServiceTest.java
```

## Locked decisions carried into implementation

| Topic | Decision |
| --- | --- |
| Models | Shared OpenRouter (existing); planner `qwen3-35b`; worker `gpt-4o-mini`; leaves omit `model` |
| Disposition enum | `pay \| partial_pay \| deny \| refer_human \| refer_siu` |
| Evidence | Root-only; L2 specialist names; no mid-level contracts |
| Fraud screen | Plan-required via root evidence (always run; clean → low risk) |
| Recommend skill | Single-shot **adjudication writer** (`gpt-4o-mini`); full root-shaped output; root **copies** fields |
| Recommend input | `claimText` + `extractedFactsSummary` + `coverageSummary` + `fraudSummary` (+ optional `scenario`) |
| Extract skill | Single-shot worker (`gpt-4o-mini`) |
| `policyId` | Optional on root; fixtures/`scenario` drive leaves |
| `claimedAmount` precedence | Root/POST → extract → scenario enrichment default |
| process-scenario | Injects `policyId` + `claimedAmount` from static table (not text-only) |
| Partial pay formula | `max(0, min(claimed, limit) - deductible)` |
| Controller | New `ClaimsController` under `/claims/*` |
| Attachments / RBAC | Out of scope |
| Skill-authoring KB | No impact |
| Testing plan | Owner runs `3_testing_plan.md` later |
| Ollama-first | **Superseded** — do not default this tree to tiny local models |

## References

- Original ticket: `ai/thoughts/tickets/eng-sample-htn-insurance-claim-intake.md`
- Epic index: `ai/thoughts/tickets/eng-sample-htn-skill-tree-gallery.md`
- Sibling plan (pattern source): `ai/thoughts/plans/2026-07-14-eng-sample-htn-incident-commander.md`
- Patterns: `skills/incidents/*`, `skills/basics/duplicate_invoice_checker.yml`, `expense_lookup.yml`, `IncidentController.java`
- Nested evidence: `CapabilityExecutionRouter`, `CapabilityExecutionRouterTest`, `ai/skill-authoring/evidence-contracts.md`
- Sample config: `bifrost-sample/src/main/resources/application.yml`
