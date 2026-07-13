# Ticket: Sample HTN Skill Tree — Customer Support Case Resolver

**Status:** Design  
**Priority:** P2  
**Module:** `bifrost-sample`  
**Related tickets:**  
- `eng-sample-htn-incident-commander.md` (sibling sample; recommended first)  
- `eng-sample-htn-travel-concierge.md`  
- `eng-sample-htn-insurance-claim-intake.md`  
**Depends on:** Nested planning support (existing); optional reuse of invoice/expense skills for billing paths  

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
- Ollama-first; no OpenAI required.  
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
├── understandIntent                       [L2 LLM single-shot YAML]
├── handleBilling                          [L2 planning YAML]
│   ├── lookupCustomer                     [L3 Java]
│   ├── lookupInvoices                     [L3 Java]
│   ├── checkRefundPolicy                  [L3 Java or LLM]
│   └── (optional) invoiceParser           [existing LLM skill]
├── handleTechnical                        [L2 planning YAML]
│   ├── lookupAccountStatus                [L3 Java]
│   ├── searchKnownIssues                  [L3 Java]
│   └── createBugTicket                    [L3 Java]
├── handleHowTo                            [L2 planning or single-shot]
│   └── searchHelpCenter                   [L3 Java]
└── composeReply                           [L2 LLM single-shot YAML]
```

### Depth notes

- L1 chooses which specialist branches to run (and order).  
- L2 billing/technical planners choose which CRM tools to call.  
- L3 never “decides policy”; they return data. Policy judgment lives in L2 prompts or a dedicated `checkRefundPolicy` skill.

## What the LLM is allowed to decide

| Level | Fixed | LLM freedom |
| --- | --- | --- |
| L1 | Visible specialists only | Which intents to pursue; whether to escalate vs resolve |
| L2 billing/technical | Visible CRM tools only | Which lookups; when enough facts exist |
| L2 understand/compose | Schemas + prompts | Intent labels, tone, refund recommendation language |
| L3 | Fake CRM data | None |

## Skill inventory (proposed)

### Planning skills

| Name | `allowed_skills` | Role |
| --- | --- | --- |
| `resolveSupportCase` | `understandIntent`, `handleBilling`, `handleTechnical`, `handleHowTo`, `composeReply` | Root |
| `handleBilling` | `lookupCustomer`, `lookupInvoices`, `checkRefundPolicy` (+ optional `invoiceParser`) | Billing specialist |
| `handleTechnical` | `lookupAccountStatus`, `searchKnownIssues`, `createBugTicket` | Tech specialist |
| `handleHowTo` | `searchHelpCenter` | Optional thin planner or single-shot |

### LLM single-shot skills

| Name | Purpose |
| --- | --- |
| `understandIntent` | Extract intents, sentiment, entities (order id, amount, product) |
| `composeReply` | Customer-facing email draft grounded in gathered facts |
| `checkRefundPolicy` | Optional LLM skill if policy is fuzzy; else Java returns structured policy rules |

### Java leaves

Suggested service: `SupportCrmService`.

| Capability | Fake data |
| --- | --- |
| `lookupCustomer` | name, plan, tenure, priorComplaintCount |
| `lookupInvoices` | recent charges, duplicates possible by scenario |
| `checkRefundPolicy` | max goodwill amount, eligibility flags (if Java) |
| `lookupAccountStatus` | active/suspended, feature flags |
| `searchKnownIssues` | matching KB / incident ids |
| `createBugTicket` | returns fake ticket id |
| `searchHelpCenter` | top help articles |

## Mission input / output

### Root input

```yaml
properties:
  emailText: { type: string }
  customerId: { type: string }          # optional
  scenario: { type: string }            # optional fixture key
required: [emailText]
```

### Root output (proposed)

```yaml
properties:
  intents:
    type: array
    items: { type: string }             # billing | technical | how_to | other
  disposition: { type: string }
  refundRecommended: { type: boolean }
  refundAmount: { type: number, nullable: true }
  bugTicketId: { type: string, nullable: true }
  factsSummary: { type: string }
  draftReply: { type: string }
  internalNotes: { type: string }
required: [intents, disposition, refundRecommended, factsSummary, draftReply, internalNotes]
```

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

**Option A — reuse:** `handleBilling` may call `invoiceParser` when the email includes raw invoice text.  
**Option B — isolate:** support tree uses only CRM fakes; invoice sample stays independent.

Recommendation: **Option B for v1** (simpler story), with a follow-up to cross-link trees once both are stable.

## Evidence contract (proposed direction)

Root claims such as:

- `refundRecommended` ← billing tools / policy  
- `bugTicketId` ← `createBugTicket` when disposition is escalate  
- `draftReply` ← facts from whatever branches ran  

Exact tag names TBD in design review.

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

- [ ] Root can invoke mid-level planners that invoke Java leaves.  
- [ ] At least one multi-intent fixture documented.  
- [ ] At least one how-to path that does **not** create a bug ticket or refund.  
- [ ] HTTP endpoint returns journal metadata.  
- [ ] Ollama-only runnable.  
- [ ] README section complete.  
- [ ] Policy guidance lives in skill `prompt` / structured policy tool, not only in root description.

## Open design questions

1. **Is `understandIntent` mandatory** as a first step, or can root infer intents while planning?  
2. **Refund policy:** Java rules vs LLM `checkRefundPolicy` skill?  
3. **Disposition enum** — finalize closed set for schema.  
4. **Should `createBugTicket` be side-effectful in-memory** (session list) so a second call is visible?  
5. **Sentiment:** first-class output field or only inside `internalNotes`?  
6. **PII:** keep fixtures clearly fake; any redaction guidance in prompts?  
7. **Reuse invoice skills** now or later?  
8. **Shared packaging:** one `skills/` tree gallery index ticket vs per-domain folders only?

## Implementation sketch (after design lock)

1. CRM Java service + mapped leaves.  
2. Intent + compose LLM skills.  
3. Mid-level planners.  
4. Root planner + schemas + evidence.  
5. Fixtures + HTTP.  
6. Tests + README.  
7. Manual multi-intent smoke run; capture journal excerpt.

## Design discussion notes

_(Use this section during ticket review.)_

- Owner: TBD  
- Reviewers: TBD  
- Decisions log:  
  - (pending)
