# Sample HTN Skill Tree — Customer Support Case Resolver Implementation Plan

## Overview

Add a three-level HTN customer-support skill tree to `bifrost-sample` that turns free-text support emails into disposition + draft reply. Demonstrates multi-intent routing (billing / technical / how-to / mixed), nested planning (root → domain specialists → Java CRM leaves), dual refund path (Java policy facts + LLM judgment), light-but-stronger root evidence (`intent_classification` + shared `case_facts` + `response_draft`), and OpenRouter planner/worker models matching incident/insurance (`qwen3-35b` / `gpt-4o-mini`). No framework changes; no new connection keys.

Gallery role: **ambiguous multi-intent language, policy judgment, and customer-facing synthesis** — complement to ops/incident and insurance compliance samples.

## Current State Analysis

- `skills/support/` is README-planned only; no package, fixtures, or YAML yet.
- **Incident commander** and **insurance claim intake** are shipped templates for package layout, controller, fixtures, catalog/controller/leaf tests, and README sections.
- OpenRouter connection + planner/worker aliases already exist in `application.yml` — **reuse**, do not re-add.
- Nested YAML missions snapshot/restore parent plan + evidence (`CapabilityExecutionRouter`); leaf evidence does not bubble to the parent ledger.
- Parent `tool_evidence` must name **L2 specialists**, not L3 leaves (proven by incident + insurance + authoring KB).
- Multiple tools may produce the same evidence type; **one successful producer satisfies a claim** — use shared `case_facts` from any of `handleBilling` / `handleTechnical` / `handleHowTo`.
- Mapped leaves: minimal `name` / `description` / `mapping` only (Java owns the contract via `@SkillMethod` / `@ToolParam`).
- Skill discovery already covers `classpath:/skills/**/*.yml`; subdirectory needs no config change.
- Tests pattern: dedicated `*SkillCatalogTests`, `*ControllerTest`, leaf service tests; no live LLM in CI.
- Ticket models locked 2026-07-15 (OpenRouter, not Ollama-first). Remaining design questions locked during this planning session (see Locked Decisions).

## Desired End State

A runnable sample path where:

1. All support YAML skills load from `classpath:/skills/support/**/*.yml` (14 skills: 1 root + 3 mid planners + 3 workers + 7 Java leaves).
2. Root `resolveSupportCase` invokes mandatory `understandIntent`, at least one `handle*` specialist (billing / technical / how-to), and `composeReply`.
3. Root evidence contract plan-requires: `intent_classification` + `case_facts` + `response_draft`.
4. HTTP endpoints under `/support/*` return structured case outcome + `sessionId` + `executionJournal`.
5. Sample boots and unit tests pass without a real OpenRouter key; live smoke uses existing `OPENROUTER_API_KEY`.
6. README documents the tree, multi-intent routing, dual refund path, evidence rules (vs incident + insurance), PII guidance, fixtures, and model setup.

### Key Discoveries:

- Nested isolation is already enforced: parent `tool_evidence` names L2 tools (`understandIntent`, `handleBilling`, `handleTechnical`, `handleHowTo`, `composeReply`), not L3 CRM methods.
- Shared evidence type `case_facts` from any specialist branch mirrors incident's shared `investigation_digest` — multi-intent demos need only **one** branch for plan success, while prompts encourage both when the email is mixed.
- Dual refund: Java `lookupRefundPolicy` returns deterministic eligibility facts; LLM `checkRefundPolicy` (worker under billing allow-list) produces judgment language. Policy is not only in root `description`.
- Mapped leaves must omit schemas/model/planning/evidence so Java remains the single contract source.
- OpenRouter + `qwen3-35b` / `gpt-4o-mini` already wired; support skills only reference those aliases.

## Locked Decisions

| Topic | Decision |
| --- | --- |
| Models | Planner `qwen3-35b` → `qwen/qwen3.6-35b-a3b`; worker `gpt-4o-mini` → `openai/gpt-4o-mini`; shared `openrouter` connection |
| `understandIntent` | **Mandatory first step**; evidence-required (`intent_classification`) |
| Refund policy | **Both**: Java `lookupRefundPolicy` (facts) + LLM `checkRefundPolicy` (judgment) |
| Disposition enum | `resolved_draft` \| `refund_offered` \| `escalated_bug` \| `needs_human` \| `how_to_answered` |
| `createBugTicket` | **Stateless canned** ticket id by scenario (no in-memory list) |
| Sentiment | On `understandIntent` output only; **not** a root field |
| Invoice reuse | **Option B isolate** — no `invoiceParser` / `expenseLookup` on billing branch |
| PII | Fake fixtures + **stronger redaction guidance** in `understandIntent` / `composeReply` prompts |
| Packaging | `skills/support/` + `sample.support` package only; no gallery-index ticket work |
| `handleHowTo` | Thin **planner** (`max_steps: 4`, only `searchHelpCenter`) |
| Root evidence | Require `intent_classification` + shared `case_facts` (≥1 of handle*) + `response_draft` |
| Dual refund names | Java `lookupRefundPolicy`; LLM `checkRefundPolicy` (no name clash) |

## What We're NOT Doing

- Framework changes (unless a hard gap is discovered; escalate rather than patch around).
- Real email/CRM integrations or contact-center UI.
- Perfect tone control or multi-language support in v1.
- Human-in-the-loop approval workflow.
- Reusing invoice skills on the billing branch (follow-up only).
- Golden-file exact LLM outputs or live-model CI tests.
- Replacing invoice / feedstock / incident / insurance samples.
- Mid-level `evidence_contract` on handle* planners (root-only for v1).
- Defaulting this tree to `granite4-tiny` / Ollama-only.
- Optional free-text-only GET without scenario fixtures as the preferred path.
- Mirroring fixtures under `src/test/resources`.
- Side-effectful bug-ticket session state.
- Updating `ai/skill-authoring/` (sample-only work).
- Running `3_testing_plan.md` as a prerequisite (owner may run later).

## Skill-Authoring Documentation Impact

**Impact**: No impact

- **Rationale**: This work only adds sample composition in `bifrost-sample`. It does not change manifest syntax, validation, defaults, planning/evidence semantics, model resolution, or any other author-facing framework behavior. Nested planning, evidence isolation (including multiple producers of one evidence type), mapped-leaf contract ownership, and named connections are already documented in `ai/skill-authoring/`.
- **Documents to update**: None
- **Supporting evidence**: Ticket non-goals (no framework changes); existing KB topics (`mental-model.md`, `evidence-contracts.md`, `model-selection-and-connections.md`); framework tests such as `CapabilityExecutionRouterTest` nested evidence restore; incident + insurance samples as living references.
- **Coverage table update**: Not required
- **LLM-first usability**: Not applicable

## Implementation Approach

Bottom-up, sample-only, mirror incident/insurance phasing:

1. Implement deterministic CRM leaves + email fixtures (testable without models). No new model config.
2. Author the full YAML tree with locked contracts/prompts from this plan.
3. Add `SupportController` for demos.
4. Add catalog / controller / leaf tests that never call the live API.
5. Document in `bifrost-sample/README.md`.
6. Manual smoke with a real OpenRouter key (human).

Do not invent alternate disposition enums, evidence tags, or model aliases at implement time.

### Final skill inventory

| Name | Type | Model | Role |
| --- | --- | --- | --- |
| `resolveSupportCase` | L1 planning | `qwen3-35b` | Root orchestrator |
| `understandIntent` | L2 single-shot | `gpt-4o-mini` | Intents, sentiment, entities |
| `handleBilling` | L2 planning | `qwen3-35b` | Billing specialist |
| `handleTechnical` | L2 planning | `qwen3-35b` | Technical specialist |
| `handleHowTo` | L2 thin planning | `qwen3-35b` | How-to specialist |
| `composeReply` | L2 single-shot | `gpt-4o-mini` | Customer-facing draft |
| `checkRefundPolicy` | L2 single-shot (billing allow-list) | `gpt-4o-mini` | Soft policy judgment |
| `lookupCustomer` | L3 mapped | — | CRM customer |
| `lookupInvoices` | L3 mapped | — | Recent charges |
| `lookupRefundPolicy` | L3 mapped | — | Structured policy facts |
| `lookupAccountStatus` | L3 mapped | — | Account / feature flags |
| `searchKnownIssues` | L3 mapped | — | Known issues / KB |
| `createBugTicket` | L3 mapped | — | Fake ticket id |
| `searchHelpCenter` | L3 mapped | — | Help articles |

Tree:

```
resolveSupportCase                         [L1 planning YAML]
├── understandIntent                       [L2 LLM single-shot]
├── handleBilling                          [L2 planning YAML]
│   ├── lookupCustomer                     [L3 Java]
│   ├── lookupInvoices                     [L3 Java]
│   ├── lookupRefundPolicy                 [L3 Java facts]
│   └── checkRefundPolicy                  [L2 LLM judgment]
├── handleTechnical                        [L2 planning YAML]
│   ├── lookupAccountStatus                [L3 Java]
│   ├── searchKnownIssues                  [L3 Java]
│   └── createBugTicket                    [L3 Java]
├── handleHowTo                            [L2 thin planning YAML]
│   └── searchHelpCenter                   [L3 Java]
└── composeReply                           [L2 LLM single-shot]
```

---

## Phase 1: Java CRM Leaves, Mapped YAML, and Fixtures

### Overview

Add deterministic support CRM capabilities and five canned email fixtures. Leaves accept `scenario` (and optional `customerId` where useful) and return stable canned structures; unknown scenarios return neutral valid data (no exceptions).

### Changes Required:

#### 1. CRM service
**File**: `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/support/SupportCrmService.java`  
**Changes**: New `@Service` with seven `@SkillMethod` methods. Use `@ToolParam` for parameter description/requiredness. Distinct method names for target-id uniqueness.

| Method | Params | Behavior (keyed by `scenario`) |
| --- | --- | --- |
| `lookupCustomer` | `scenario` (required), `customerId` (optional) | name, plan, tenureMonths, priorComplaintCount, notes |
| `lookupInvoices` | `scenario`, optional `customerId` | recent charges list; duplicates possible by scenario |
| `lookupRefundPolicy` | `scenario` | maxGoodwillAmount, firstComplaintEligible, goodwillEligible, notes (facts only — no prose judgment) |
| `lookupAccountStatus` | `scenario` | status (active/suspended), featureFlags, notes |
| `searchKnownIssues` | `scenario` | matching issue ids / titles / severity hints |
| `createBugTicket` | `scenario`, optional `summary` | **stateless** fake ticket id + echo of summary (deterministic per scenario) |
| `searchHelpCenter` | `scenario`, optional `query` | top help article titles + urls (fake) |

Target IDs (Spring default bean name `supportCrmService`):

- `supportCrmService#lookupCustomer`
- `supportCrmService#lookupInvoices`
- `supportCrmService#lookupRefundPolicy`
- `supportCrmService#lookupAccountStatus`
- `supportCrmService#searchKnownIssues`
- `supportCrmService#createBugTicket`
- `supportCrmService#searchHelpCenter`

**Scenario leaf bias** (must support fixtures):

| Key | Leaf bias |
| --- | --- |
| `billing-duplicate-charge` | Customer with tenure; two identical March charges; goodwill eligible if first complaint; no tech issues |
| `tech-crash-on-checkout` | Active account; known issue or empty known issues + ticket id `BUG-TECH-…`; no refund path needed |
| `mixed-billing-and-crash` | Duplicate charge **and** crash signals; both billing + tech data populated |
| `how-to-export` | Help center articles for CSV export; no refund; no bug ticket needed |
| `angry-goodwill` | Small overcharge; `priorComplaintCount: 0`; `maxGoodwillAmount: 50`; goodwill eligible; furious tone lives in email fixture only |

**Refund policy facts (Java)** — example fields (stable map keys):

```
maxGoodwillAmount: number          # e.g. 50
firstComplaintEligible: boolean    # priorComplaintCount == 0 for scenario
goodwillEligible: boolean          # scenario-driven
notes: string                      # factual eligibility notes only
```

Unknown scenario: neutral customer, empty invoices, non-eligible goodwill, healthy account, empty known issues, generic ticket id, empty help results (still valid maps/lists).

#### 2. Minimal mapped leaf manifests
**Files** under `bifrost-sample/src/main/resources/skills/support/`:

- `lookup_customer.yml`
- `lookup_invoices.yml`
- `lookup_refund_policy.yml`
- `lookup_account_status.yml`
- `search_known_issues.yml`
- `create_bug_ticket.yml`
- `search_help_center.yml`

Each file shape:

```yaml
name: lookupCustomer
description: Looks up customer plan, tenure, and prior complaint count for the support scenario.
mapping:
  target_id: supportCrmService#lookupCustomer
```

Rules:

- Only `name`, `description`, `mapping` (no schemas, model, planning, evidence).
- Public YAML `name` is lowerCamelCase matching the inventory.
- Create `skills/support/` directory as needed (README already lists it as planned).

#### 3. Email fixtures
**Files** under `bifrost-sample/src/main/resources/fixtures/support/`:

- `billing-duplicate-charge.txt`
- `tech-crash-on-checkout.txt`
- `mixed-billing-and-crash.txt`
- `how-to-export.txt`
- `angry-goodwill.txt`

Each fixture is free-text customer email prose matching the scenario gist. Use clearly fake names/ids (e.g. `cust-demo-1001`, `Jane Demo`). Prefer `resolve-scenario` so the model does not invent `scenario`.

Optional controller enrichment (Phase 3): static per-scenario `customerId` for demos (e.g. `CUST-1001`).

### Success Criteria:

#### Automated Verification:
- [x] Module compiles: `.\mvnw.cmd -pl bifrost-sample -am test-compile`
- [x] Leaf unit tests (Phase 4) pass for known + unknown scenarios
- [x] Targets register as `supportCrmService#...` (catalog tests Phase 4)

#### Manual Verification:
- [ ] Fixture emails are readable and domain-plausible for demos
- [ ] Mixed fixture clearly contains both billing and crash language

---

## Phase 2: LLM and Planning Skill Tree YAML

### Overview

Author workers, mid-level planners, and root with locked I/O, planning settings, root-only evidence contract, and private prompts. Planners use `model: qwen3-35b`; single-shot workers use `model: gpt-4o-mini`.

### Changes Required:

#### 1. Single-shot workers
**Files**:

- `understand_intent.yml`
- `compose_reply.yml`
- `check_refund_policy.yml`

**Common locks**:

- `planning_mode: false` (or omit)
- `model: gpt-4o-mini`
- `output_schema_max_retries: 2`
- Short public `description`; private `prompt` for behavior (no linter)

**`understandIntent`**

- **Input**: `emailText` (required), optional `scenario`, optional `customerId`
- **Output** (required):
  - `intents`: array of enum `billing | technical | how_to | other`
  - `sentiment`: enum `angry | frustrated | neutral | positive` (or similar closed set — keep small)
  - `entities`: object or string map for order id / amount / product (keep schema simple; prefer object with optional string/number fields)
  - `summary`: short string
- **Prompt**: Extract multi-label intents; do not invent facts; scenario is fixture hint only. **PII**: treat fixture data as fake demo PII; do not invent real personal data; when summarizing entities, prefer ids already present in the email.
- Evidence producer for parent: `intent_classification`

**`composeReply`**

- **Input**: `emailText`, `factsSummary` (required string digest), optional `intentSummary`, optional `dispositionHint`, optional `scenario`
- **Output** (required): `draftReply` (customer-facing email body), `internalNotes` (agent-facing)
- **Prompt**: Ground every claim in `factsSummary`; do not invent refunds, ticket ids, or policy. Match tone to customer without escalating hostility. **PII redaction guidance**: do not invent SSNs/card numbers; if email contains account ids, you may echo them; never invent payment instrument numbers; prefer generic references (“your March invoice”) when unsure.
- Evidence producer for parent: `response_draft`

**`checkRefundPolicy`** (LLM judgment; called only from `handleBilling`)

- **Input**: `emailText` or billing context string, `policyFactsSummary` (from Java `lookupRefundPolicy` + customer/invoice digests), optional `scenario`
- **Output**: `refundRecommended` (boolean), `refundAmount` (number, nullable/not required), `rationale` (string), `policyNotes` (string)
- **Prompt**: Soft policy — e.g. goodwill under max amount if first complaint and small overcharge; do not exceed `maxGoodwillAmount` from facts; if not eligible, recommend no refund and explain. Ground in `policyFactsSummary` only.

#### 2. Mid-level planners
**Files**:

- `handle_billing.yml`
- `handle_technical.yml`
- `handle_how_to.yml`

**Locks**:

```yaml
model: qwen3-35b
planning_mode: true
output_schema_max_retries: 2
# no evidence_contract
```

| Skill | `max_steps` | `allowed_skills` | Digest role |
| --- | --- | --- | --- |
| `handleBilling` | 6 | `lookupCustomer`, `lookupInvoices`, `lookupRefundPolicy`, `checkRefundPolicy` | Billing digest → `case_facts` for parent |
| `handleTechnical` | 6 | `lookupAccountStatus`, `searchKnownIssues`, `createBugTicket` | Tech digest → `case_facts` |
| `handleHowTo` | 4 | `searchHelpCenter` | How-to digest → `case_facts` |

**Mid-level output_schema** (shared shape; domain enum differs):

```yaml
properties:
  domain: { type: string, enum: [billing, technical, how_to] }
  summary: { type: string }
  findings:
    type: array
    items:
      type: object
      properties:
        tool: { type: string }
        observation: { type: string }
      required: [tool, observation]
      additionalProperties: false
  toolsUsed:
    type: array
    items: { type: string }
  # billing-only optional fields may appear on handleBilling:
  refundRecommended: { type: boolean }
  refundAmount: { type: number }
  # technical-only optional:
  bugTicketId: { type: string }
required: [domain, summary, findings, toolsUsed]
additionalProperties: false
```

Implementation note: keep schemas valid JSON Schema. Prefer **per-skill** optional fields rather than one union schema if easier — billing may require `refundRecommended` boolean; technical may include optional `bugTicketId`; how-to needs only the shared core.

**Prompts**:

- Forward `scenario` (and `customerId` when present) to every leaf that accepts them.
- Call only tools needed; stop when digest is supportable.
- Do not invent CRM data; base findings on tool results.
- `toolsUsed` / findings must reflect tools actually invoked.
- **Billing**: prefer customer + invoices; call `lookupRefundPolicy` before `checkRefundPolicy`; pass policy facts into the LLM skill.
- **Technical**: prefer known issues before creating a bug ticket; create ticket when no known issue covers the crash / escalate path.
- **How-to**: call `searchHelpCenter`; do not invent article content beyond tool results.

#### 3. Root planner
**File**: `resolve_support_case.yml`

```yaml
name: resolveSupportCase
description: >
  Root customer support case resolver. Plans intent understanding, selective
  billing/technical/how-to specialist branches, and customer reply composition
  into a structured case disposition.
model: qwen3-35b
planning_mode: true
max_steps: 10
allowed_skills:
  - understandIntent
  - handleBilling
  - handleTechnical
  - handleHowTo
  - composeReply
prompt: |
  You are the root customer support case resolver (demo only).

  1. Prefer calling understandIntent early with emailText (and scenario / customerId if present).
  2. Choose specialist branch(es) from intents + email. Call handleBilling and/or handleTechnical
     and/or handleHowTo as needed. For mixed billing+technical emails, call both specialists.
     For pure how-to, call handleHowTo only — do not create refunds or bug tickets.
  3. Forward scenario (and customerId when present) on every tool call that accepts them.
  4. Call composeReply with emailText plus a factsSummary built from specialist digest(s)
     (and optional intent summary). Do not invent facts not present in tool results.
  5. Emit the full root report: intents, disposition, refund fields, optional bugTicketId,
     factsSummary, draftReply, internalNotes — grounded in understandIntent + specialists + compose.

  Do not invent CRM data, refunds, or ticket ids. Do not call L3 leaves directly — only the
  specialists listed in allowed_skills. Any one handle* specialist can satisfy case_facts evidence;
  still run every branch the intents require.
evidence_contract:
  claims:
    intents: [intent_classification]
    disposition: [intent_classification, case_facts, response_draft]
    refundRecommended: [case_facts]
    factsSummary: [case_facts]
    draftReply: [response_draft]
    internalNotes: [intent_classification, case_facts, response_draft]
  tool_evidence:
    understandIntent: [intent_classification]
    handleBilling: [case_facts]
    handleTechnical: [case_facts]
    handleHowTo: [case_facts]
    composeReply: [response_draft]
input_schema:
  type: object
  properties:
    emailText:
      type: string
      description: Free-text customer support email.
    customerId:
      type: string
      description: Optional customer id.
    scenario:
      type: string
      description: Optional fixture key for deterministic leaf data.
  required: [emailText]
  additionalProperties: false
output_schema:
  type: object
  properties:
    intents:
      type: array
      items:
        type: string
        enum: [billing, technical, how_to, other]
    disposition:
      type: string
      enum: [resolved_draft, refund_offered, escalated_bug, needs_human, how_to_answered]
    refundRecommended: { type: boolean }
    refundAmount: { type: number }
    bugTicketId: { type: string }
    factsSummary: { type: string }
    draftReply: { type: string }
    internalNotes: { type: string }
  required:
    - intents
    - disposition
    - refundRecommended
    - factsSummary
    - draftReply
    - internalNotes
  additionalProperties: false
output_schema_max_retries: 2
```

**Evidence notes (locked)**:

- Plan success requires tools that produce `intent_classification`, `case_facts`, and `response_draft`.
- `refundRecommended` claim is tied to `case_facts` so pure how-to / tech paths must still set a boolean (typically `false`) from whatever specialist ran — root prompt: default `refundRecommended: false` when billing did not run.
- `refundAmount` and `bugTicketId` are optional output fields (not required); omit or null-equivalent only if schema allows omission (prefer omit when not applicable; do not invent).
- Parent must **not** list L3 tools or `checkRefundPolicy` in root `tool_evidence` (billing child owns that call).

**Disposition guidance (prompt-level, not hard rules)**:

| Bias | When |
| --- | --- |
| `refund_offered` | Billing path recommends goodwill/refund |
| `escalated_bug` | Tech path created / needs bug ticket |
| `how_to_answered` | How-to only, help articles sufficient |
| `needs_human` | Ambiguous policy, angry + high amount, or insufficient facts |
| `resolved_draft` | Default when a draft resolves without refund/escalation |

### Success Criteria:

#### Automated Verification:
- [x] Catalog tests assert root allow-list, max_steps, models, evidence contract shape
- [x] Mid planners have correct allow-lists and no evidence contracts
- [x] Workers use `gpt-4o-mini` without planning
- [x] Sample context loads: `.\mvnw.cmd -pl bifrost-sample test` (after Phase 4 tests land)

#### Manual Verification:
- [ ] YAML validates at catalog load (no boot errors)
- [ ] Root evidence tags only reference L2 tool names

---

## Phase 3: HTTP API (`SupportController`)

### Overview

Add demo endpoints mirroring `IncidentController` / `ClaimsController`: list scenarios, resolve by fixture name, free-form POST.

### Changes Required:

#### 1. Controller
**File**: `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/support/SupportController.java`

| Method | Path | Behavior |
| --- | --- | --- |
| `POST` | `/support/resolve` | JSON: `emailText` (required), optional `customerId`, `scenario` → invoke `resolveSupportCase` |
| `GET` | `/support/resolve-scenario` | `name=` fixture key → load fixture, set `scenario`, inject optional static `customerId` |
| `GET` | `/support/scenarios` | List fixture keys + short descriptions |

Response shape (same as incident/claims):

```json
{
  "result": "<skill output string>",
  "sessionId": "...",
  "executionJournal": { }
}
```

- Use `SkillTemplate.invoke("resolveSupportCase", inputs, observer)`.
- Validate known scenario names; 400 on unknown / missing emailText.
- Load fixtures from `classpath:/fixtures/support/{name}.txt`.
- Log sessionId + scenario + elapsedMs (same pattern as incident).

**Scenario enrichment table** (controller-owned; optional `customerId` only — no amounts required):

| Scenario | `customerId` |
| --- | --- |
| `billing-duplicate-charge` | `CUST-1001` |
| `tech-crash-on-checkout` | `CUST-1002` |
| `mixed-billing-and-crash` | `CUST-1003` |
| `how-to-export` | `CUST-1004` |
| `angry-goodwill` | `CUST-1005` |

POST does **not** auto-enrich; caller supplies optional fields.

Request record example:

```java
public record ResolveSupportRequest(String emailText, String customerId, String scenario) {}
```

### Success Criteria:

#### Automated Verification:
- [x] Controller unit tests (Phase 4) pass with mocked `SkillTemplate`
- [x] Module compiles

#### Manual Verification:
- [ ] `GET /support/scenarios` lists five keys
- [ ] Live smoke (Phase 6) returns journal metadata

---

## Phase 4: Tests (no live LLM)

### Overview

Dedicated support test classes mirroring incident/insurance. CI must never call OpenRouter.

### Changes Required:

#### 1. Catalog tests
**File**: `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/support/SupportSkillCatalogTests.java`

Assert:

- All public support skills registered; mapping targets internal (not public capabilities).
- Root: `planning_mode`, `max_steps: 10`, allow-list exact order/membership, `frameworkModel` / manifest model `qwen3-35b`, evidence contract tags and tool_evidence keys (L2 only; no L3 / no `checkRefundPolicy` at root).
- Mid planners: allow-lists, max_steps (6 / 6 / 4), no evidence contract, model `qwen3-35b`.
- Workers (`understandIntent`, `composeReply`, `checkRefundPolicy`): no planning, model `gpt-4o-mini`.
- Required schema fields for root / key workers (mirror incident assertions).
- OpenRouter connection + aliases still present (same assertions as incident — regression guard).

#### 2. Controller tests
**File**: `.../support/SupportControllerTest.java`

- POST delegates with emailText + optional scenario/customerId; omits null keys.
- resolve-scenario loads fixture + sets scenario + enrichment customerId.
- Unknown scenario → 400.
- Missing emailText → 400.
- Response includes `result`, `sessionId`, `executionJournal` (no `filePath`).

#### 3. Leaf tests
**File**: `.../support/SupportCrmServiceTest.java`

- Known scenarios return expected bias (duplicate invoices, goodwill flags, ticket id stability, help articles).
- Unknown scenario returns neutral valid data (no throw).
- `createBugTicket` is deterministic for a given scenario (stateless).
- `lookupRefundPolicy` facts stable for `angry-goodwill` / `billing-duplicate-charge`.

### Success Criteria:

#### Automated Verification:
- [x] `.\mvnw.cmd -pl bifrost-sample test` passes
- [x] No test invokes live HTTP to OpenRouter
- [x] Context loads with dummy `test-openrouter-api-key`

#### Manual Verification:
- [ ] N/A

---

## Phase 5: README Documentation

### Overview

Document the support gallery piece in `bifrost-sample/README.md` and wire it into the existing HTN gallery table / HTTP / layout sections.

### Changes Required:

#### 1. README updates
**File**: `bifrost-sample/README.md`

- Pattern table: add support nested planners / controller.
- Prerequisites: OpenRouter used for `/support/*` live demos too.
- Layout tree: `support/` package, `fixtures/support/`, `skills/support/`.
- New section **Support (`skills/support/`) — 3-level HTN** with:
  - Tree diagram
  - What LLM decides vs fixed (multi-intent table)
  - Evidence rules: require understand + ≥1 handle* + compose; shared `case_facts`
  - Dual refund path explanation (Java facts vs LLM judgment)
  - Scenario table + enrichment customerIds
  - Stronger PII / redaction note (demo data; prompts instruct no invented payment instruments)
  - Model setup (reuse OpenRouter aliases)
  - Journal reading tips for multi-intent runs
  - Contrast: vs incident (multi-label intents + customer reply), vs insurance (selective branches still evidence-require one specialist, not all desks)
- Move support out of “HTN gallery (remaining)” into documented samples; leave travel planned.
- HTTP API table + PowerShell/curl examples for `/support/*`.
- Fixtures list entry for `fixtures/support/*.txt`.

### Success Criteria:

#### Automated Verification:
- [x] N/A (docs only)

#### Manual Verification:
- [ ] README tree matches YAML inventory
- [ ] Gallery remaining table no longer lists support as planned
- [ ] Copy-paste demo commands work against a running sample (with real key for live resolve)

---

## Phase 6: Manual Smoke (human)

### Overview

Live multi-intent verification with a real OpenRouter key. Capture journal observations for README if useful (optional excerpt).

### Steps:

1. Set `$env:OPENROUTER_API_KEY` and start sample (`.\mvnw.cmd -pl bifrost-sample spring-boot:run` or project-standard run).
2. `GET /support/scenarios`
3. `GET /support/resolve-scenario?name=mixed-billing-and-crash` — expect both specialist branches in journal; disposition refund and/or escalated as model judges; draft reply grounded.
4. `GET /support/resolve-scenario?name=how-to-export` — expect how-to path; **no** refund; **no** bug ticket; disposition `how_to_answered` or `resolved_draft`.
5. `GET /support/resolve-scenario?name=angry-goodwill` — policy judgment path; goodwill under $50 when facts allow.
6. Confirm response includes `sessionId` + `executionJournal`; nested frames show L2 then L3 under billing/technical.

### Success Criteria:

#### Automated Verification:
- [ ] N/A

#### Manual Verification:
- [ ] Multi-intent fixture exercises ≥2 specialist branches in journal
- [ ] How-to path does not create refund or bug ticket
- [ ] CI still green without real key

---

## Testing Strategy

### Unit Tests:
- CRM leaf scenario stability and unknown-scenario neutrality
- Catalog registration, allow-lists, models, evidence contract shape
- Controller delegation and validation

### Integration Tests:
- Spring context load with full support catalog (via catalog tests)
- No live LLM integration tests in CI

**Note**: Prefer a dedicated testing plan via `3_testing_plan.md` for full details if desired. This section is a high-level summary.

### Manual Testing Steps:
1. Boot sample without `OPENROUTER_API_KEY` — context starts.
2. Live resolve mixed + how-to + angry-goodwill scenarios with real key.
3. Inspect journal nesting and evidence-related plan events at root (L2 names only).

## Performance Considerations

- Nested multi-specialist runs (especially mixed billing+technical) are slower/costlier than invoice samples; mission timeout already `6000s`.
- Prefer scenario GET for demos to reduce wasted steps from invented scenario keys.
- `handleHowTo` thin planner keeps pure how-to paths cheaper than full billing/tech.

## Migration Notes

N/A — new sample only. No data migration. No changes to existing skill names.

## References

- Original ticket: `ai/thoughts/tickets/eng-sample-htn-support-case-resolver.md`
- Sibling plans: `ai/thoughts/plans/2026-07-14-eng-sample-htn-incident-commander.md`, `ai/thoughts/plans/2026-07-15-eng-sample-htn-insurance-claim-intake.md`
- Living samples: `bifrost-sample/src/main/resources/skills/incidents/`, `.../skills/insurance/`
- Controllers: `IncidentController.java`, `ClaimsController.java`
- Authoring KB: `ai/skill-authoring/evidence-contracts.md`, `mental-model.md`, `model-selection-and-connections.md`
- README gallery: `bifrost-sample/README.md`
