# Ticket: Sample HTN Skill Tree — Customer Support Case Resolver

**Status:** Implemented (2026-07-15) — Phase 6 manual smoke remaining for human with real `OPENROUTER_API_KEY`  
**Priority:** P2  
**Module:** `bifrost-sample`  
**Implementation plan:** `ai/thoughts/plans/2026-07-15-eng-sample-htn-support-case-resolver.md`  
**Related tickets:**  
- `eng-sample-htn-incident-commander.md` (sibling sample; recommended first)  
- `eng-sample-htn-travel-concierge.md`  
- `eng-sample-htn-insurance-claim-intake.md`  
**Depends on:** Nested planning support (existing). Invoice skill reuse deferred (v1 isolate).  

---

## Summary

Add a **three-level HTN skill tree** that turns a free-text customer support email into a disposition + draft reply. The root skill classifies intent and routes to billing and/or technical mid-level planners; leaves provide fake CRM, invoice, policy, and ticketing data via Java.

This sample emphasizes **ambiguous multi-intent language**, **policy judgment**, and **customer-facing synthesis** — different narrative from ops/incident, still pure Bifrost HTN.

## Motivation

Support workflows are familiar to almost every product audience. They showcase:

- LLM intent understanding that hard rules get wrong (“charged twice *and* the app crashes”).  
- Parallel or sequential specialist branches (billing + technical).  
- Soft policy (“goodwill refund under $50 if first complaint”) that belongs in prompts/evidence, not a brittle DSL.  
- Final composition skill that must ground claims in tool results.

Pairs well with existing invoice skills if we choose to **reuse** `invoiceParser` / `expenseLookup` on the billing branch (optional design choice).

## Goals

- Three-level skill stack: root planner → domain planners → Java leaves.  
- Multi-intent routing (billing, technical, how-to, or mixed).  
- Structured case outcome + draft customer reply.  
- **OpenRouter planner/worker models** matching incident (`qwen3-35b` / `gpt-4o-mini`) — not Ollama-first.  
- Fixtures covering single-intent and multi-intent emails.  
- README section with tree, examples, and journal reading tips.  
- Clear separation of `description` (tool selection) vs `prompt` (policy/behavior).

## Non-Goals

- Real email/CRM integrations.  
- Full contact-center UI.  
- Perfect tone control or multi-language support in v1.  
- Replacing the invoice duplicate-checker sample (complement, don’t subsume).  
- Human-in-the-loop approval workflow (can be a future extension).

## Domain story

**Mission:** Given a customer email (and optional customer id), produce:

- detected intents  
- disposition (`resolved_draft` | `needs_human` | `escalated_bug` | `refund_offered` | …)  
- facts gathered (account, invoices, known issues)  
- draft reply  
- internal notes / escalation payload if needed  

The agent should gather only the data needed for the intents present.

## Skill tree (3 levels)

```
resolveSupportCase                         [L1 planning YAML]
├── understandIntent                       [L2 LLM single-shot YAML]  ← evidence-required
├── handleBilling                          [L2 planning YAML]
│   ├── lookupCustomer                     [L3 Java]
│   ├── lookupInvoices                     [L3 Java]
│   ├── lookupRefundPolicy                 [L3 Java facts]
│   └── checkRefundPolicy                  [L2 LLM judgment]
├── handleTechnical                        [L2 planning YAML]
│   ├── lookupAccountStatus                [L3 Java]
│   ├── searchKnownIssues                  [L3 Java]
│   └── createBugTicket                    [L3 Java, stateless]
├── handleHowTo                            [L2 thin planning YAML]
│   └── searchHelpCenter                   [L3 Java]
└── composeReply                           [L2 LLM single-shot YAML]  ← evidence-required
```

### Depth notes

- L1 chooses which specialist branches to run (and order); must call `understandIntent` early and `composeReply` before finish.  
- L2 billing/technical/how-to planners choose which CRM tools to call.  
- L3 never “decides policy”; they return data. Soft policy judgment is the LLM skill `checkRefundPolicy`, grounded in Java `lookupRefundPolicy` facts.  
- Any one `handle*` specialist produces shared root evidence type `case_facts` (multi-intent still runs every branch the intents require).

## What the LLM is allowed to decide

| Level | Fixed | LLM freedom |
| --- | --- | --- |
| L1 | Visible specialists only; evidence requires understand + ≥1 handle* + compose | Which specialist branches; disposition; assembly of root fields |
| L2 billing/technical/how-to | Visible CRM / policy tools only | Which lookups; when enough facts exist |
| L2 understand/compose/checkRefund | Schemas + prompts | Intent labels, sentiment, tone, refund judgment language |
| L3 | Fake CRM data | None |

## Skill inventory (locked)

### Planning skills

| Name | `allowed_skills` | Role | Model |
| --- | --- | --- | --- |
| `resolveSupportCase` | `understandIntent`, `handleBilling`, `handleTechnical`, `handleHowTo`, `composeReply` | Root | `qwen3-35b` |
| `handleBilling` | `lookupCustomer`, `lookupInvoices`, `lookupRefundPolicy`, `checkRefundPolicy` | Billing specialist | `qwen3-35b` |
| `handleTechnical` | `lookupAccountStatus`, `searchKnownIssues`, `createBugTicket` | Tech specialist | `qwen3-35b` |
| `handleHowTo` | `searchHelpCenter` | Thin how-to planner (`max_steps: 4`) | `qwen3-35b` |

### LLM single-shot skills

| Name | Purpose | Model |
| --- | --- | --- |
| `understandIntent` | Extract multi-label intents, sentiment, entities | `gpt-4o-mini` |
| `composeReply` | Customer-facing email draft grounded in gathered facts | `gpt-4o-mini` |
| `checkRefundPolicy` | Soft policy judgment grounded in Java policy facts | `gpt-4o-mini` |

### Java leaves

Service: `SupportCrmService`.

| Capability | Fake data |
| --- | --- |
| `lookupCustomer` | name, plan, tenure, priorComplaintCount |
| `lookupInvoices` | recent charges, duplicates possible by scenario |
| `lookupRefundPolicy` | maxGoodwillAmount, eligibility flags (facts only) |
| `lookupAccountStatus` | active/suspended, feature flags |
| `searchKnownIssues` | matching KB / incident ids |
| `createBugTicket` | deterministic fake ticket id (stateless) |
| `searchHelpCenter` | top help articles |

## Models (locked — match incident commander)

| Role | Framework alias | OpenRouter provider model | Skills |
| --- | --- | --- | --- |
| **Planner** | `qwen3-35b` | `qwen/qwen3.6-35b-a3b` | `resolveSupportCase`, `handleBilling`, `handleTechnical`, `handleHowTo` |
| **Worker** | `gpt-4o-mini` | `openai/gpt-4o-mini` | `understandIntent`, `composeReply`, `checkRefundPolicy` |

| Layer | Value |
| --- | --- |
| Connection | Existing `openrouter` (`driver: openai`) — **reuse** incident wiring; no new connection keys |
| Credential | `${OPENROUTER_API_KEY:test-openrouter-api-key}` — dummy default for boot/CI; live demos need real key |
| Mapped leaves | Omit `model` |

**Why not Ollama-first / `granite4-tiny`:** Nested multi-intent support routing needs a capable planner; same rationale as incident. Ticket originally proposed Ollama-first; **superseded 2026-07-15**.

**CI / tests:** Catalog, controller, and leaf tests must not call the live API. Live smoke is manual with a real `OPENROUTER_API_KEY`.

## Mission input / output

### Root input

```yaml
properties:
  emailText: { type: string }
  customerId: { type: string }          # optional
  scenario: { type: string }            # optional fixture key
required: [emailText]
```

### Root output (locked)

```yaml
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
  refundAmount: { type: number }          # optional (omit when N/A)
  bugTicketId: { type: string }           # optional (omit when N/A)
  factsSummary: { type: string }
  draftReply: { type: string }
  internalNotes: { type: string }
required: [intents, disposition, refundRecommended, factsSummary, draftReply, internalNotes]
additionalProperties: false
```

Sentiment is **not** a root field; it lives on `understandIntent` output only.

## Canned scenarios / fixtures

| Scenario | Email gist | Expected bias |
| --- | --- | --- |
| `billing-duplicate-charge` | Charged twice for March | billing → invoices; possible refund |
| `tech-crash-on-checkout` | App crashes on pay | technical → known issues / bug ticket |
| `mixed-billing-and-crash` | Charged twice *and* crash | both branches |
| `how-to-export` | How do I export CSV? | how-to → help center; no refund |
| `angry-goodwill` | Small overcharge, first complaint, furious tone | policy + tone judgment |

Fixture files: `src/main/resources/fixtures/support/*.txt` (or test resources).

## HTTP API (proposed)

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/support/resolve` | JSON: `emailText`, optional `customerId`, `scenario` |
| `GET` | `/support/resolve-scenario` | `name=` fixture key |
| `GET` | `/support/scenarios` | list fixtures |

Response: `result`, `sessionId`, `executionJournal` (same pattern as other samples).

## Package / file layout (proposed)

```
bifrost-sample/src/main/
  java/.../sample/support/
    SupportCrmService.java
    SupportController.java   # or SampleController methods
  resources/skills/support/
    resolve_support_case.yml
    understand_intent.yml
    handle_billing.yml
    handle_technical.yml
    handle_how_to.yml
    compose_reply.yml
    lookup_customer.yml
    ...
  resources/fixtures/support/
    billing-duplicate-charge.txt
    mixed-billing-and-crash.txt
    ...
```

## Relationship to existing invoice sample

**Locked: Option B (isolate) for v1.** Support tree uses only CRM fakes; invoice sample stays independent. Cross-link trees later if useful.

## Evidence contract (locked)

Root-only `evidence_contract` (mid-level planners have none). Parent credits **L2** tools only; leaf evidence does not bubble.

```yaml
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
```

- Plan success requires: `understandIntent` + **≥1** of `handleBilling` / `handleTechnical` / `handleHowTo` + `composeReply`.  
- Shared `case_facts` mirrors incident’s shared `investigation_digest` (one producer is enough for the claim; prompts still require every branch the intents need).  
- Root `tool_evidence` must **not** list L3 leaves or `checkRefundPolicy` (billing child owns that call).

## Documentation requirements

- Tree diagram and intent-routing explanation.  
- Multi-intent fixture walkthrough.  
- Note that draft reply quality varies by model; journal is the teaching artifact.  
- Cross-link from sample README “skill tree gallery.”

## Tests

- Catalog registration + `allowed_skills` shape for planners.  
- CRM leaf scenario stability tests.  
- Controller delegation tests.  
- No live LLM required in CI.

## Acceptance criteria

- [x] Root can invoke mid-level planners that invoke Java leaves.  
- [x] At least one multi-intent fixture documented.  
- [x] At least one how-to path that does **not** create a bug ticket or refund.  
- [x] HTTP endpoint returns journal metadata.  
- [x] Planners use `qwen3-35b`; single-shot workers use `gpt-4o-mini` (OpenRouter; reuse incident connection).  
- [x] CI / sample boot succeeds without a real OpenRouter key; tests do not call the live API.  
- [x] README section complete (includes model setup).  
- [x] Policy guidance lives in skill `prompt` / structured policy tool, not only in root description.

## Open design questions

**All resolved 2026-07-15.** See Design discussion notes and plan `ai/thoughts/plans/2026-07-15-eng-sample-htn-support-case-resolver.md`.

~~1. Is `understandIntent` mandatory?~~ → Yes; evidence-required (`intent_classification`).  
~~2. Refund policy Java vs LLM?~~ → Both: Java `lookupRefundPolicy` (facts) + LLM `checkRefundPolicy` (judgment).  
~~3. Disposition enum~~ → `resolved_draft | refund_offered | escalated_bug | needs_human | how_to_answered`.  
~~4. `createBugTicket` side effects?~~ → Stateless canned ticket id by scenario.  
~~5. Sentiment field?~~ → On `understandIntent` only; not root.  
~~6. PII~~ → Fake fixtures + stronger redaction guidance in understand/compose prompts.  
~~7. Reuse invoice skills?~~ → Option B isolate for v1.  
~~8. Shared packaging?~~ → Per-domain `skills/support/` only; README gallery section (no separate index ticket work).  
~~9. `handleHowTo` planner vs single-shot?~~ → Thin planner (`max_steps: 4`).  
~~10. Root evidence strength?~~ → Require understand + shared `case_facts` (≥1 handle*) + compose (stronger than “intents+draft only”).  
~~11. Dual refund skill names?~~ → `lookupRefundPolicy` (Java) + `checkRefundPolicy` (LLM).  
~~12. HTTP shape?~~ → Mirror incident: `POST /support/resolve`, `GET /support/resolve-scenario`, `GET /support/scenarios`; optional static `customerId` enrichment on scenario GET.

## Implementation sketch

1. CRM Java service + mapped leaves + fixtures.  
2. Intent + compose + checkRefundPolicy LLM skills.  
3. Mid-level planners (`handleBilling` / `handleTechnical` / `handleHowTo`).  
4. Root planner + schemas + evidence.  
5. HTTP (`SupportController`).  
6. Catalog / controller / leaf tests + README.  
7. Manual multi-intent smoke run; capture journal excerpt if useful for docs.

(No nesting/evidence spike — already verified via incident + insurance + framework tests.)

## Risks

- Small local models are **not** used for this tree; if OpenRouter is unavailable, live demos fail (boot still works with dummy key).  
- Multi-intent mixed runs cost more (two specialist planners); intentional for teaching.  
- Draft reply quality varies by model — journal is the teaching artifact, not golden prose.  
- Soft policy judgment is model-dependent; do not golden-file refund wording.  
- Users may over-trust demo refunds/dispositions — README should label demo-only.

## Design discussion notes

- Owner: TBD  
- Reviewers: TBD  
- Implementation plan: `ai/thoughts/plans/2026-07-15-eng-sample-htn-support-case-resolver.md`  
- Decisions log (2026-07-15):  
  - **Models:** OpenRouter planner `qwen3-35b` / worker `gpt-4o-mini` (match incident); supersedes original Ollama-first goal. Mapped leaves omit `model`.  
  - **`understandIntent`:** Mandatory first step; root evidence requires `intent_classification`.  
  - **Refund policy:** Dual path — Java `lookupRefundPolicy` facts + LLM `checkRefundPolicy` judgment under `handleBilling`.  
  - **Disposition enum:** `resolved_draft | refund_offered | escalated_bug | needs_human | how_to_answered`.  
  - **`createBugTicket`:** Stateless canned id (no in-memory session list).  
  - **Sentiment:** `understandIntent` output only; not a root field.  
  - **PII:** Fake fixtures + stronger redaction guidance in understand/compose prompts.  
  - **Invoice reuse:** Option B isolate for v1.  
  - **Packaging:** `skills/support/` + `sample.support` package; README gallery (no separate index ticket).  
  - **`handleHowTo`:** Thin planner (`max_steps: 4`, only `searchHelpCenter`).  
  - **Evidence:** Root-only contract; require understand + ≥1 handle* (`case_facts`) + compose; mid-level digests without contracts.  
  - **HTTP:** `/support/resolve`, `/support/resolve-scenario`, `/support/scenarios`; scenario GET may inject static `customerId`.  
  - **Skill-authoring KB:** No impact (sample-only composition).
