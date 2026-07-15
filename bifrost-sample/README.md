# Bifrost Sample

A runnable Spring Boot application that demonstrates Bifrost skills end to end: public YAML skill discovery, internal Java `@SkillMethod` targets, named-connection model routing, HTN planning, vision/attachment inputs, and HTTP invocation via `SkillTemplate`.

Use this module as a reference implementation when integrating `bifrost-spring-boot-starter` into your own app.

## What this sample shows

| Pattern | Where |
| --- | --- |
| Mapped YAML skill → Java method | `expenseLookup`, `feedstockTicketParser`, incident probes, insurance leaves, support CRM leaves |
| Pure LLM YAML skill with `input_schema` / `output_schema` / linter | `invoiceParser` (linter); incident/insurance/support workers use schemas + retries only (no linter) |
| Planning skill (`planning_mode: true`) with `allowed_skills` + `evidence_contract` | `duplicateInvoiceChecker` (2-level), `handleIncident` (3-level light evidence), `processClaim` (3-level strong evidence), `resolveSupportCase` (3-level multi-intent + shared `case_facts`) |
| Nested mid-level planners | `investigateNetwork` / `investigateApp`; `assessCoverage` / `fraudScreen`; `handleBilling` / `handleTechnical` / `handleHowTo` |
| Pure YAML vision skill with `attachment` input | `feedstockTicketParserBySkill` |
| Named connections and model aliases (`ollama` + `openai` + OpenRouter) | `application.yml` → `bifrost.connections` / `bifrost.models` |
| HTTP API that invokes skills and returns execution metadata | `SampleController`, `IncidentController`, `ClaimsController`, `SupportController` |
| Execution journal / session id via `SkillTemplate` observer | invoice, feedstock, incident, claims, and support endpoints |

## Prerequisites

- **Java 21+**
- **Maven 3.9+** (or the repo-root wrapper: `mvnw` / `mvnw.cmd`)
- **Ollama** running locally (default `http://localhost:11434`) with at least one of the configured models pulled, e.g.:
  - `ibm/granite4:tiny-h` (default chat + several skills)
  - optionally `gemma4:e2b`, `gemma4:e4b`, `gemma4:26b`
- **OpenAI API key** for feedstock vision demos:
  - set `OPENAI_API_KEY` in the environment (preferred)
  - Bifrost reads it through `bifrost.connections.openai-main.api-key`; `spring.ai.*` is not inherited
- **OpenRouter API key** for nested HTN samples (incident + insurance + support live demos only):
  - set `OPENROUTER_API_KEY` in the environment
  - sample boots without a real key via dummy default `test-openrouter-api-key`
  - Bifrost reads it through `bifrost.connections.openrouter.api-key`

PowerShell:

```powershell
$env:OPENAI_API_KEY = "sk-..."
$env:OPENROUTER_API_KEY = "sk-or-..."   # only needed for live /incidents/*, /claims/*, and /support/* resolve calls
```

## Run

From the repository root:

```powershell
.\mvnw.cmd -pl bifrost-sample spring-boot:run
```

Unix:

```bash
./mvnw -pl bifrost-sample spring-boot:run
```

The app listens on **port 8081** (`server.port` in `src/main/resources/application.yml`).

Build / test only:

```powershell
.\mvnw.cmd -pl bifrost-sample test
```

## Project layout

```
bifrost-sample/
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── java/.../sample/
    │   │   ├── SampleApplication.java              # Spring Boot entrypoint
    │   │   ├── SampleController.java               # Invoice/feedstock HTTP demos
    │   │   ├── ExpenseService.java                 # @SkillMethod (deterministic data)
    │   │   ├── FeedstockFormExtractionService.java # @SkillMethod (OpenAI vision HTTP)
    │   │   ├── incident/
    │   │   │   ├── IncidentController.java         # Incident HTN HTTP demos
    │   │   │   └── IncidentTelemetryService.java   # Deterministic probe leaves
    │   │   ├── insurance/
    │   │   │   ├── ClaimsController.java           # Insurance HTN HTTP demos
    │   │   │   ├── InsurancePolicyService.java     # Policy / exclusion / payout leaves
    │   │   │   └── ClaimsHistoryService.java       # Prior claims / anomaly / address leaves
    │   │   └── support/
    │   │       ├── SupportController.java          # Support HTN HTTP demos
    │   │       └── SupportCrmService.java          # Deterministic CRM leaves
    │   └── resources/
    │       ├── application.yml                     # Named AI connections + Bifrost config
    │       ├── forms/                              # Sample weigh ticket image/PDF
    │       ├── fixtures/incidents/                 # Canned incident tickets
    │       ├── fixtures/insurance/claims/          # Canned FNOL claim texts
    │       ├── fixtures/support/                   # Canned support emails
    │       └── skills/
    │           ├── basics/                         # Mapped leaf, LLM parse, 2-level plan
    │           ├── vision/                         # Feedstock Java + pure YAML vision
    │           ├── incidents/                      # 3-level HTN incident commander
    │           ├── insurance/                      # 3-level HTN claim intake (strong evidence)
    │           ├── support/                        # 3-level HTN support case resolver
    │           └── travel/                         # HTN gallery (planned)
    └── test/
        ├── java/.../sample/                        # Context + controller unit tests
        │   ├── incident/                           # Catalog, controller, leaf tests
        │   ├── insurance/                          # Catalog, controller, leaf tests
        │   └── support/                            # Catalog, controller, leaf tests
        └── resources/fixtures/                     # Sample invoice text
```

Skill YAML `name` values are unchanged; only filesystem folders changed. Discovery still uses `classpath:/skills/**/*.yml`.

## Configuration highlights

Skills are loaded from:

```yaml
bifrost:
  skills:
    locations:
      - classpath:/skills/**/*.yml
      - classpath:/skills/**/*.yaml
```

Named Bifrost connections are `ollama-main`, `ollama-secondary`, `openai-main`, and `openrouter`. The two Ollama connections demonstrate independent endpoints using the same driver; override them with `OLLAMA_BASE_URL` and `OLLAMA_SECONDARY_BASE_URL`. OpenRouter uses the OpenAI driver with `base-url: https://openrouter.ai/api/v1` and `api-key: ${OPENROUTER_API_KEY:test-openrouter-api-key}`.

Named Bifrost models (each LLM-backed YAML skill must set `model` to one of these keys; mapped YAML wrappers omit it):

| Key | Connection | Provider model |
| --- | --- | --- |
| `granite4-tiny` | `ollama-main` | `ibm/granite4:tiny-h` |
| `gemma4-e2b` | `ollama-secondary` | `gemma4:e2b` |
| `gemma4-e4b` | `ollama-secondary` | `gemma4:e4b` |
| `gemma4-26b` | `ollama-main` | `gemma4:26b` |
| `default-model` | `ollama-main` | `ibm/granite4:tiny-h` |
| `openai-gpt-5-mini` | `openai-main` | `gpt-5-mini` |
| `qwen3-35b` | `openrouter` | `qwen/qwen3.6-35b-a3b` (planner) |
| `gpt-4o-mini` | `openrouter` | `openai/gpt-4o-mini` (worker) |

Notes:

- `default-model` is an ordinary named model key; it is **not** auto-selected for LLM-backed skills that omit `model`.
- Session mission timeout is raised to `6000s` for long vision/planning runs.
- `execution-trace.persistence: ALWAYS` keeps full execution traces for inspection (useful with `bifrost-cli`).
- Incident, insurance, and support planners use `qwen3-35b`; workers use `gpt-4o-mini`. Nested planning needs a capable model — these trees do **not** use `granite4-tiny`.

Debug logging is enabled for Bifrost chat, linter, output schema, and planning packages so skill runs are easy to follow in the console.

## Skills catalog

Manifests live under `src/main/resources/skills/<area>/`. Skill `name` values are global and unchanged.

### Basics (`skills/basics/`)

On-ramp patterns: mapped Java, single-shot LLM, shallow planning.

#### 1. `expenseLookup` → Java

- **File:** `skills/basics/expense_lookup.yml`
- **Type:** Mapped skill (`mapping.target_id: expenseService#getLatestExpenses`)
- **Execution:** Model-free deterministic Java; the wrapper inherits the reflected Java input contract.
- **Behavior:** Returns a fixed list of fake expenses from `ExpenseService`.

Demonstrates: exposing an internal Spring `@SkillMethod` target through a public YAML skill. The controller and `allowed_skills` use `expenseLookup`; `expenseService#getLatestExpenses` is mapping-only implementation metadata.

#### 2. `invoiceParser` → LLM

- **File:** `skills/basics/invoice_parser.yml`
- **Type:** LLM skill (no mapping)
- **Model:** `granite4-tiny`
- **Input:** `{ "payload": "<raw invoice text>" }`
- **Output schema:** `vendorName`, `totalAmount`, `invoiceDate`
- **Extras:** regex linter requiring raw JSON; `output_schema_max_retries: 2`

Demonstrates: structured extraction with schema validation and lint retries.

#### 3. `duplicateInvoiceChecker` → planning HTN

- **File:** `skills/basics/duplicate_invoice_checker.yml`
- **Type:** Planning skill (`planning_mode: true`, `max_steps: 10`)
- **Model:** `granite4-tiny`
- **Allowed tools:** `invoiceParser`, `expenseLookup`
- **Evidence contract:** claims such as `isDuplicate` must be backed by tool evidence tags
- **Output schema:** `isDuplicate`, `vendorName`, `totalAmount`, `invoiceDate`, `reasoning`

Demonstrates: multi-step agentic workflow — parse invoice, look up expenses, decide duplicate — with evidence constraints.

### Vision (`skills/vision/`)

Document/image parsing with OpenAI (Java-backed vs pure YAML).

#### 4. `feedstockTicketParser` → Java + OpenAI vision

- **File:** `skills/vision/feedstock_ticket_parser.yml`
- **Type:** Mapped skill → `feedstockFormExtractionService#extractSampleFeedstockTicket`
- **Behavior:** Loads bundled `forms/feedstock-p1.jpg`, calls OpenAI Responses API with a strict JSON schema, returns structured weigh-ticket fields.

Demonstrates: heavy lifting in Java while still registering as a Bifrost skill (useful when you want custom HTTP/vision clients).

**Requires** `OPENAI_API_KEY`.

#### 5. `feedstockTicketParserBySkill` → pure YAML vision

- **File:** `skills/vision/feedstock_ticket_parser_by_skill.yml`
- **Type:** LLM skill on `openai-gpt-5-mini`
- **Input:** `image` attachment (`media_type: image`, `image/jpeg`)
- **Prompt + output schema:** same weigh-ticket domain as the Java extractor
- **Extras:** nullable fields, regex linter, schema retries

Demonstrates: vision/document parsing **without** a custom Java client — Bifrost routes the attachment to the named OpenAI model.

**Requires** `OPENAI_API_KEY`.

### Incidents (`skills/incidents/`) — 3-level HTN

Primary sample for **nested planning**: root planner → mid-level investigate planners → deterministic Java leaves.

```
handleIncident                          [L1 planning YAML, model qwen3-35b]
├── classifyIncident                    [L2 LLM single-shot, model gpt-4o-mini]
├── investigateNetwork                  [L2 planning YAML, model qwen3-35b]
│   ├── checkDns                        [L3 Java leaf]
│   ├── checkLatency                    [L3 Java leaf]
│   └── checkFirewallRules              [L3 Java leaf]
├── investigateApp                      [L2 planning YAML, model qwen3-35b]
│   ├── getErrorRate                    [L3 Java leaf]
│   ├── getRecentDeploys                [L3 Java leaf]
│   └── getServiceHealth                [L3 Java leaf]
├── draftIncidentResponse               [L2 LLM single-shot, model gpt-4o-mini]
└── lookupRunbook                       [L3 Java leaf]  (optional; not evidence-required)
```

| Framework feature | How this sample uses it |
| --- | --- |
| Nested `planning_mode` | Root + `investigateNetwork` / `investigateApp` each run the step-loop engine |
| `allowed_skills` governance | Each planner only sees its specialist / probe set |
| Mapped Java leaves | Minimal YAML (`name` / `description` / `mapping` only); Java owns contracts |
| Light `evidence_contract` | Root only; shared `investigation_digest` so either branch can satisfy investigation claims |
| Nested mission isolation | Parent plan/evidence snapshotted while a child planner runs (visible in journal frames) |
| Planner vs worker models | Shared OpenRouter connection; `qwen3-35b` planners, `gpt-4o-mini` workers |

Contrast with `duplicateInvoiceChecker`: that skill is **2-level** (one planner + leaves). Incident commander is **3-level** (planner that calls other planners).

#### What the LLM decides vs what is fixed

| Level | Fixed (YAML/Java) | LLM freedom |
| --- | --- | --- |
| L1 `handleIncident` | May only call listed specialists; plan must cover root evidence | Order; which investigation branch(es); when to draft; optional runbook |
| L2 `investigate*` | May only call listed probes; structured digest output | Which probes; when evidence is enough |
| L2 classify/draft | Schemas + prompts | Interpretation and wording |
| L3 leaves | Canned data for `scenario` | None |

#### Evidence contract rules (root)

- Claim evidence lists are **AND-all** (every listed evidence type for a present claim must be gathered).
- Nested YAML missions **snapshot/restore** parent evidence; leaf evidence does **not** bubble to the parent ledger.
- Parent `tool_evidence` keys are the **tools the parent invokes** (L2 specialists), not L3 probes inside a child planner.
- Multiple tools may produce the same evidence type; **one successful producer is enough**. Both `investigateNetwork` and `investigateApp` produce `investigation_digest` — either branch satisfies investigation claims (do not require both).
- Successful plans are expected to cover: `classifyIncident` + ≥1 investigation specialist + `draftIncidentResponse`.
- `lookupRunbook` is intentionally **not** in the evidence contract (optional enrichment).

#### Scenario plumbing

Bifrost has no free-form session bag for demo keys. Pass `scenario` on root input and forward it on every tool call that accepts it (prompts instruct this). Prefer `GET /incidents/handle-scenario?name=...` so the model does not invent the key.

| Scenario key | Ticket gist | Expected branch bias |
| --- | --- | --- |
| `network-dns` | EU users cannot resolve `api.example.com` | network → DNS |
| `app-deploy-regression` | Checkout 500s after 14:02 deploy | app → deploys + errors |
| `ambiguous-slow` | Everything intermittent, no deploy today | mixed / model judgment |
| `firewall-block` | Wiki blank after firewall change | network → firewall |

Fixtures live under `src/main/resources/fixtures/incidents/`. Leaves return neutral valid data for unknown scenario keys (no exceptions).

#### Model setup

| Role | Framework alias | OpenRouter provider model | Skills |
| --- | --- | --- | --- |
| Planner | `qwen3-35b` | `qwen/qwen3.6-35b-a3b` | `handleIncident`, `investigateNetwork`, `investigateApp` |
| Worker | `gpt-4o-mini` | `openai/gpt-4o-mini` | `classifyIncident`, `draftIncidentResponse` |

Both aliases share connection `openrouter`. Live demos need a real `OPENROUTER_API_KEY`. CI and local boot use the dummy default so context loads without network.

#### Reading the execution journal

Nested planning frames typically look like:

```
MISSION handleIncident
  PLANNING ...
  TOOL classifyIncident
  TOOL investigateNetwork
    MISSION investigateNetwork          ← nested mission (parent plan/evidence restored after)
      PLANNING ...
      TOOL checkDns
      ...
  TOOL draftIncidentResponse
```

Root `max_steps: 10`; mid-level `max_steps: 6`. Session mission timeout is `6000s`. Nested runs with a large planner can take minutes and cost more than the invoice samples.

### Insurance (`skills/insurance/`) — 3-level HTN (enterprise / evidence)

**Disclaimer:** This sample is a **demo only**. It is **not** real insurance advice, not actuarially correct, and **not** legally binding. Humans remain responsible for final claim decisions.

Enterprise / compliance gallery piece: evidence-backed risk decisioning under HTN structure (stronger root `evidence_contract` than incident).

```
processClaim                               [L1 planning YAML, model qwen3-35b]
├── extractClaimFacts                      [L2 LLM single-shot, model gpt-4o-mini]
├── assessCoverage                         [L2 planning YAML, model qwen3-35b]
│   ├── getPolicy                          [L3 Java]
│   ├── checkExclusions                    [L3 Java]
│   └── estimatePayout                     [L3 Java]
├── fraudScreen                            [L2 planning YAML, model qwen3-35b]
│   ├── priorClaimsLookup                  [L3 Java]
│   ├── anomalyScore                       [L3 Java]
│   └── addressRiskSignals                 [L3 Java optional depth]
└── recommendDisposition                   [L2 LLM single-shot writer, model gpt-4o-mini]
```

| Framework feature | How this sample uses it |
| --- | --- |
| Nested `planning_mode` | Root + `assessCoverage` / `fraudScreen` each run the step-loop engine |
| `allowed_skills` governance | Root sees only four L2 specialists; mid-level desks see only their leaves |
| Mapped Java leaves | Minimal YAML (`name` / `description` / `mapping`); Java owns contracts |
| **Strong** root `evidence_contract` | Plan-requires extract + coverage + fraud + recommend (all four specialists) |
| Nested mission isolation | Parent plan/evidence snapshotted while a child planner runs |
| Planner vs worker models | Same OpenRouter aliases as incident (`qwen3-35b` / `gpt-4o-mini`) |
| Adjudication writer | `recommendDisposition` synthesizes full root-shaped fields; root **copies** them |

#### Side-by-side: invoice vs incident vs insurance

| Sample | Levels | Evidence | Branching story |
| --- | --- | --- | --- |
| `duplicateInvoiceChecker` | 2 | Shallow root contract | Parse + lookup, then decide |
| `handleIncident` | 3 | Light root contract; either investigation branch can satisfy `investigation_digest` | Ops: classify → selective network/app → draft |
| `processClaim` | 3 | **Strong** root contract; **all four** L2 specialists plan-required | Risk: extract → coverage desk **and** SIU-lite → writer |

#### What the LLM decides vs what is fixed

| Level | Fixed (YAML/Java) | LLM freedom |
| --- | --- | --- |
| L1 `processClaim` | May only call four specialists; plan must cover root evidence (all four) | Order; how digests are passed; assemble report by copying recommend |
| L2 coverage/fraud | Listed leaves only; structured digests | Which checks; when enough for a sub-conclusion |
| L2 extract/recommend | Schemas + prompts | Fact extraction; disposition language |
| L3 leaves | Policy/history math and canned scenario data | None |

Fraud specialist is **plan-required** (clean claims still run SIU-lite and return `fraudRisk: low`). Depth *inside* fraud (which leaves) remains free.

#### Evidence contract rules (root)

- Claim evidence lists are **AND-all**.
- Nested YAML missions **snapshot/restore** parent evidence; leaf evidence does **not** bubble to the parent ledger.
- Parent `tool_evidence` keys are **L2 specialists only** (`extractClaimFacts`, `assessCoverage`, `fraudScreen`, `recommendDisposition`) — never L3 leaves (`getPolicy`, `anomalyScore`, …).
- Successful plans must include **all four** specialists. Fraud is not skippable.
- Mid-level planners have **no** `evidence_contract` (structured digests only).

#### Payout formula (deterministic Java)

Implemented exactly in `estimatePayout`:

```
gross = min(claimedAmount, policyLimit)
payable = max(0, gross - deductible)
```

If exclusions match for the loss (scenario-driven), payable is forced to `0`. Unknown scenarios use a neutral policy (limit 5000, deductible 500) and the same formula.

#### claimedAmount precedence

When forwarding amounts into coverage:

1. Root / POST `claimedAmount` if present  
2. Else extract `claimedAmount`  
3. Else scenario enrichment default (process-scenario table)

#### Scenario plumbing

Pass `scenario` on root input and forward it on every leaf that accepts it. Prefer `GET /claims/process-scenario?name=...` — it loads fixture text, sets `scenario`, and injects static `policyId` / `claimedAmount` so over-limit and clear-pay demos do not depend on extract alone. `POST /claims/process` does **not** auto-enrich.

| Scenario key | Claim gist | Expected disposition bias | Enrichment |
| --- | --- | --- | --- |
| `clear-auto-pay` | Minor covered collision, clean history | `pay` or `partial_pay` after deductible | `POL-AUTO-1001` / `2200` |
| `exclusion-flood` | Water damage where flood excluded | `deny` + matched exclusions | `POL-HOME-2002` / `15000` |
| `fraud-velocity` | Third similar claim in 60 days | `refer_siu` | `POL-AUTO-1001` / `4800` |
| `ambiguous-liability` | Unclear fault, missing date | `refer_human` | `POL-AUTO-1001` / (no amount) |
| `over-limit` | Claimed amount above policy limit | `partial_pay` at formula | `POL-AUTO-1001` / `25000` |

Fixtures: `src/main/resources/fixtures/insurance/claims/`. Leaves return neutral valid data for unknown scenario keys (no exceptions).

Disposition enum: `pay | partial_pay | deny | refer_human | refer_siu`.

#### Model setup

Reuses the same OpenRouter connection and aliases as incident — **not** `granite4-tiny` / Ollama-only.

| Role | Framework alias | OpenRouter provider model | Skills |
| --- | --- | --- | --- |
| Planner | `qwen3-35b` | `qwen/qwen3.6-35b-a3b` | `processClaim`, `assessCoverage`, `fraudScreen` |
| Worker | `gpt-4o-mini` | `openai/gpt-4o-mini` | `extractClaimFacts`, `recommendDisposition` |

#### Reading the execution journal

```
MISSION processClaim
  PLANNING ...
  TOOL extractClaimFacts
  TOOL assessCoverage
    MISSION assessCoverage
      PLANNING ...
      TOOL getPolicy
      TOOL checkExclusions
      TOOL estimatePayout
  TOOL fraudScreen
    MISSION fraudScreen
      PLANNING ...
      TOOL priorClaimsLookup
      TOOL anomalyScore
      ...
  TOOL recommendDisposition
```

Root evidence-related plan/tool events show **L2** names at the root frame. Root `max_steps: 10`; mid-level `max_steps: 6`. Nested multi-desk runs can take minutes.

### Support (`skills/support/`) — 3-level HTN

**Disclaimer:** Demo only. Fake CRM data, fake refunds, and draft replies are **not** real customer support actions. Humans remain responsible for any real case disposition.

Gallery role: **ambiguous multi-intent language, policy judgment, and customer-facing synthesis** — complement to ops/incident and insurance compliance samples.

```
resolveSupportCase                         [L1 planning YAML, model qwen3-35b]
├── understandIntent                       [L2 LLM single-shot, model gpt-4o-mini]
├── handleBilling                          [L2 planning YAML, model qwen3-35b]
│   ├── lookupCustomer                     [L3 Java]
│   ├── lookupInvoices                     [L3 Java]
│   ├── lookupRefundPolicy                 [L3 Java facts]
│   └── checkRefundPolicy                  [L2 LLM judgment]
├── handleTechnical                        [L2 planning YAML, model qwen3-35b]
│   ├── lookupAccountStatus                [L3 Java]
│   ├── searchKnownIssues                  [L3 Java]
│   └── createBugTicket                    [L3 Java, stateless]
├── handleHowTo                            [L2 thin planning YAML, model qwen3-35b]
│   └── searchHelpCenter                   [L3 Java]
└── composeReply                           [L2 LLM single-shot, model gpt-4o-mini]
```

| Framework feature | How this sample uses it |
| --- | --- |
| Nested `planning_mode` | Root + billing/technical/how-to specialists each run the step-loop engine |
| Multi-label intent routing | `understandIntent` can return billing + technical together |
| Shared evidence type | Any one `handle*` specialist produces `case_facts` for the parent |
| Dual refund path | Java `lookupRefundPolicy` (facts) + LLM `checkRefundPolicy` (judgment) under billing |
| Mapped Java leaves | Minimal YAML (`name` / `description` / `mapping`); Java owns contracts |
| Planner vs worker models | Same OpenRouter aliases as incident (`qwen3-35b` / `gpt-4o-mini`) |

#### Side-by-side: incident vs insurance vs support

| Sample | Levels | Evidence | Branching story |
| --- | --- | --- | --- |
| `handleIncident` | 3 | Light root; either investigation branch can satisfy `investigation_digest` | Ops: classify → selective network/app → draft |
| `processClaim` | 3 | **Strong** root; **all four** L2 specialists plan-required | Risk: extract → coverage **and** fraud → writer |
| `resolveSupportCase` | 3 | Require understand + ≥1 handle* (`case_facts`) + compose | Support: multi-intent → selective specialists → customer reply |

Vs incident: multi-label intents + customer-facing draft (not just ops status). Vs insurance: selective specialist branches still evidence-require **one** specialist, not all desks.

#### What the LLM decides vs what is fixed

| Level | Fixed (YAML/Java) | LLM freedom |
| --- | --- | --- |
| L1 `resolveSupportCase` | Visible specialists only; evidence requires understand + ≥1 handle* + compose | Which specialist branches; disposition; assembly of root fields |
| L2 billing/technical/how-to | Visible CRM / policy tools only | Which lookups; when enough facts exist |
| L2 understand/compose/checkRefund | Schemas + prompts | Intent labels, sentiment, tone, refund judgment language |
| L3 leaves | Fake CRM data | None |

#### Evidence contract rules (root)

- Claim evidence lists are **AND-all**.
- Nested YAML missions **snapshot/restore** parent evidence; leaf evidence does **not** bubble to the parent ledger.
- Parent `tool_evidence` keys are **L2 tools only** (`understandIntent`, `handleBilling`, `handleTechnical`, `handleHowTo`, `composeReply`) — never L3 CRM methods or `checkRefundPolicy` (billing child owns that call).
- Multiple tools may produce `case_facts`; **one successful producer is enough**. Prompts still require every branch the intents need (mixed emails should run billing **and** technical).
- Successful plans are expected to cover: `understandIntent` + ≥1 of `handleBilling` / `handleTechnical` / `handleHowTo` + `composeReply`.
- Mid-level planners have **no** `evidence_contract` (structured digests only).

#### Dual refund path

| Skill | Kind | Role |
| --- | --- | --- |
| `lookupRefundPolicy` | Java leaf | Deterministic eligibility facts (`maxGoodwillAmount`, `firstComplaintEligible`, `goodwillEligible`, notes) |
| `checkRefundPolicy` | LLM single-shot (billing allow-list only) | Soft judgment language grounded in those facts |

Policy is **not** only in the root `description`. Root defaults `refundRecommended: false` when billing did not run.

#### Scenario plumbing

Pass `scenario` (and optional `customerId`) on root input and forward them on every leaf that accepts them. Prefer `GET /support/resolve-scenario?name=...` so the model does not invent the key. Scenario GET injects a static `customerId`; `POST /support/resolve` does **not** auto-enrich.

| Scenario key | Email gist | Expected bias | Enrichment `customerId` |
| --- | --- | --- | --- |
| `billing-duplicate-charge` | Charged twice for March | billing → invoices; possible refund | `CUST-1001` |
| `tech-crash-on-checkout` | App crashes on pay | technical → known issues / bug ticket | `CUST-1002` |
| `mixed-billing-and-crash` | Charged twice *and* crash | both billing + technical branches | `CUST-1003` |
| `how-to-export` | How do I export CSV? | how-to only; **no** refund / bug ticket | `CUST-1004` |
| `angry-goodwill` | Small overcharge, first complaint, furious tone | policy + tone judgment | `CUST-1005` |

Fixtures: `src/main/resources/fixtures/support/`. Leaves return neutral valid data for unknown scenario keys (no exceptions). `createBugTicket` is **stateless** (deterministic id per scenario).

Disposition enum: `resolved_draft | refund_offered | escalated_bug | needs_human | how_to_answered`.

#### PII / redaction note

All fixture names and ids are **fake demo PII**. Worker prompts instruct models not to invent SSNs, card numbers, or payment instruments; account ids already present in the email may be echoed; prefer generic references (“your March invoice”) when unsure.

#### Model setup

Reuses the same OpenRouter connection and aliases as incident/insurance — **not** `granite4-tiny` / Ollama-only.

| Role | Framework alias | OpenRouter provider model | Skills |
| --- | --- | --- | --- |
| Planner | `qwen3-35b` | `qwen/qwen3.6-35b-a3b` | `resolveSupportCase`, `handleBilling`, `handleTechnical`, `handleHowTo` |
| Worker | `gpt-4o-mini` | `openai/gpt-4o-mini` | `understandIntent`, `composeReply`, `checkRefundPolicy` |

#### Reading the execution journal

Multi-intent runs (especially `mixed-billing-and-crash`) typically show two specialist nested missions:

```
MISSION resolveSupportCase
  PLANNING ...
  TOOL understandIntent
  TOOL handleBilling
    MISSION handleBilling
      PLANNING ...
      TOOL lookupCustomer
      TOOL lookupInvoices
      TOOL lookupRefundPolicy
      TOOL checkRefundPolicy
  TOOL handleTechnical
    MISSION handleTechnical
      PLANNING ...
      TOOL searchKnownIssues
      TOOL createBugTicket
  TOOL composeReply
```

Root evidence-related plan/tool events show **L2** names only. Root `max_steps: 10`; billing/technical `max_steps: 6`; how-to thin planner `max_steps: 4`. Mixed multi-specialist runs are slower/costlier than pure how-to.

### HTN gallery (remaining)

| Folder | Sample (ticket) |
| --- | --- |
| `skills/travel/` | Travel Concierge (planned) |

See `ai/thoughts/tickets/eng-sample-htn-skill-tree-gallery.md`.

## HTTP API

Base URL: `http://localhost:8081`

| Method | Path | Skill invoked | Notes |
| --- | --- | --- | --- |
| `GET` | `/expenses` | `expenseLookup` | Invokes the mapped YAML skill, which delegates to the internal expense target |
| `GET` | `/feedstock/parse-sample` | `feedstockTicketParser` | Uses bundled sample image inside the Java skill |
| `GET` | `/feedstock/parse-sample-by-skill` | `feedstockTicketParserBySkill` | Passes `classpath:/forms/feedstock-p1.jpg` as an attachment |
| `GET` | `/invoice/parse?filePath=...` | `invoiceParser` | Reads invoice text from a local filesystem path |
| `GET` | `/invoice/check-duplicate?filePath=...` | `duplicateInvoiceChecker` | Planning skill; needs Ollama model available |
| `GET` | `/incidents/scenarios` | — | Lists fixture keys + short descriptions |
| `GET` | `/incidents/handle-scenario?name=...` | `handleIncident` | Preferred live demo: loads fixture + sets `scenario` |
| `POST` | `/incidents/handle` | `handleIncident` | JSON body: `ticketText` + optional `scenario` |
| `GET` | `/claims/scenarios` | — | Lists five insurance fixture keys + descriptions |
| `GET` | `/claims/process-scenario?name=...` | `processClaim` | Preferred live demo: fixture + `scenario` + static `policyId`/`claimedAmount` |
| `POST` | `/claims/process` | `processClaim` | JSON: `claimText` + optional `policyId`, `claimedAmount`, `scenario` (no auto-enrich) |
| `GET` | `/support/scenarios` | — | Lists five support fixture keys + descriptions |
| `GET` | `/support/resolve-scenario?name=...` | `resolveSupportCase` | Preferred live demo: fixture + `scenario` + static `customerId` |
| `POST` | `/support/resolve` | `resolveSupportCase` | JSON: `emailText` + optional `customerId`, `scenario` (no auto-enrich) |

`/expenses` calls `skillTemplate.invoke("expenseLookup", ...)`. The Java method name and `expenseService#getLatestExpenses` target ID are not public aliases.

### Example calls

```powershell
# Deterministic Java expenses
Invoke-RestMethod http://localhost:8081/expenses

# Invoice parse (use an absolute path to a text file)
Invoke-RestMethod "http://localhost:8081/invoice/parse?filePath=C:\opendev\code\bifrost\bifrost-sample\src\test\resources\fixtures\duplicate-invoice.txt"

# Duplicate check (planning + Ollama)
Invoke-RestMethod "http://localhost:8081/invoice/check-duplicate?filePath=C:\opendev\code\bifrost\bifrost-sample\src\test\resources\fixtures\duplicate-invoice.txt"

# Vision: Java-backed OpenAI extraction
Invoke-RestMethod http://localhost:8081/feedstock/parse-sample

# Vision: pure YAML skill + attachment
Invoke-RestMethod http://localhost:8081/feedstock/parse-sample-by-skill

# Incident HTN (requires real OPENROUTER_API_KEY for live model calls)
Invoke-RestMethod http://localhost:8081/incidents/scenarios
Invoke-RestMethod "http://localhost:8081/incidents/handle-scenario?name=network-dns"
Invoke-RestMethod "http://localhost:8081/incidents/handle-scenario?name=app-deploy-regression"

# Free-text incident (optional scenario)
$body = @{ ticketText = "EU users cannot resolve api.example.com"; scenario = "network-dns" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8081/incidents/handle -ContentType "application/json" -Body $body

# Insurance claim HTN (requires real OPENROUTER_API_KEY for live model calls)
Invoke-RestMethod http://localhost:8081/claims/scenarios
Invoke-RestMethod "http://localhost:8081/claims/process-scenario?name=clear-auto-pay"
Invoke-RestMethod "http://localhost:8081/claims/process-scenario?name=exclusion-flood"
Invoke-RestMethod "http://localhost:8081/claims/process-scenario?name=fraud-velocity"

# Free-form claim POST (caller supplies optional structured fields; no auto-enrich)
$claim = @{
  claimText = "Rear-ended at stoplight; bumper damage about 2200"
  policyId = "POL-AUTO-1001"
  claimedAmount = 2200
  scenario = "clear-auto-pay"
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8081/claims/process -ContentType "application/json" -Body $claim

# Support case HTN (requires real OPENROUTER_API_KEY for live model calls)
Invoke-RestMethod http://localhost:8081/support/scenarios
Invoke-RestMethod "http://localhost:8081/support/resolve-scenario?name=mixed-billing-and-crash"
Invoke-RestMethod "http://localhost:8081/support/resolve-scenario?name=how-to-export"
Invoke-RestMethod "http://localhost:8081/support/resolve-scenario?name=angry-goodwill"

# Free-form support POST (caller supplies optional fields; no auto-enrich)
$support = @{
  emailText = "I was charged twice for March and the app crashes on pay"
  customerId = "CUST-1003"
  scenario = "mixed-billing-and-crash"
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8081/support/resolve -ContentType "application/json" -Body $support
```

curl-friendly:

```bash
curl -s http://localhost:8081/incidents/scenarios
curl -s "http://localhost:8081/incidents/handle-scenario?name=network-dns"
curl -s -X POST http://localhost:8081/incidents/handle \
  -H "Content-Type: application/json" \
  -d '{"ticketText":"Checkout 500s after deploy","scenario":"app-deploy-regression"}'

curl -s http://localhost:8081/claims/scenarios
curl -s "http://localhost:8081/claims/process-scenario?name=clear-auto-pay"
curl -s "http://localhost:8081/claims/process-scenario?name=exclusion-flood"
curl -s -X POST http://localhost:8081/claims/process \
  -H "Content-Type: application/json" \
  -d '{"claimText":"Rear-ended; bumper ~2200","policyId":"POL-AUTO-1001","claimedAmount":2200,"scenario":"clear-auto-pay"}'

curl -s http://localhost:8081/support/scenarios
curl -s "http://localhost:8081/support/resolve-scenario?name=mixed-billing-and-crash"
curl -s "http://localhost:8081/support/resolve-scenario?name=how-to-export"
curl -s -X POST http://localhost:8081/support/resolve \
  -H "Content-Type: application/json" \
  -d '{"emailText":"Charged twice for March","customerId":"CUST-1001","scenario":"billing-duplicate-charge"}'
```

### Response shape

Endpoints that use the `SkillTemplate` observer return:

```json
{
  "result": "...",
  "filePath": "...",
  "sessionId": "...",
  "executionJournal": { }
}
```

- **`result`** — skill output (often a JSON string when `output_schema` is used)
- **`sessionId`** — Bifrost execution session id
- **`executionJournal`** — step-level execution record for debugging / CLI inspection

`/expenses` returns the skill result object directly (list of expense maps). Incident, claims, and support endpoints omit `filePath` and return `result` / `sessionId` / `executionJournal` only.

## Sample assets

| Asset | Purpose |
| --- | --- |
| `src/main/resources/forms/feedstock-p1.jpg` | Weighmaster certificate image for vision demos |
| `src/main/resources/forms/feedstock.pdf` | Related PDF form (available on classpath; not wired to an endpoint yet) |
| `src/test/resources/fixtures/duplicate-invoice.txt` | Minimal invoice text for parse / duplicate-check demos |
| `src/main/resources/fixtures/incidents/*.txt` | Canned incident tickets for HTN demos (`network-dns`, `app-deploy-regression`, `ambiguous-slow`, `firewall-block`) |
| `src/main/resources/fixtures/insurance/claims/*.txt` | Canned FNOL claim texts (`clear-auto-pay`, `exclusion-flood`, `fraud-velocity`, `ambiguous-liability`, `over-limit`) |
| `src/main/resources/fixtures/support/*.txt` | Canned support emails (`billing-duplicate-charge`, `tech-crash-on-checkout`, `mixed-billing-and-crash`, `how-to-export`, `angry-goodwill`) |

Fixture content:

```text
Vendor: Acme Corp
Invoice Number: INV-123
Amount: 42.50
Date: 03/30/2026
```

## How invocation works (code path)

1. Spring Boot starts `SampleApplication`; Bifrost publishes YAML skills and discovers `@SkillMethod` methods in a separate internal target registry.
2. `SampleController` injects `SkillTemplate`.
3. Controllers call `skillTemplate.invoke(yamlSkillName, inputs)` or the overload with a `Consumer<SkillExecutionView>` observer. Raw Java method names and `beanName#methodName` target IDs are not invocation aliases.
4. Bifrost resolves the skill, then either selects its model alias and named connection for LLM-backed execution or routes a mapped skill directly to its Java target, and returns text (plus optional journal).

Minimal pattern used throughout the sample:

```java
String result = skillTemplate.invoke("invoiceParser", Map.of("payload", invoiceText), view -> {
    // sessionId + executionJournal available on view
});
```

## Tests

| Test class | Coverage |
| --- | --- |
| `SampleApplicationTests` | Context loads, YAML-only public registry, internal expense target, pure-YAML feedstock skill shape |
| `SampleControllerTest` | Controller delegates with public YAML names for expenses, feedstock, and duplicate-invoice paths |
| `IncidentSkillCatalogTests` | 12 incident skills, target isolation, planning graph, root evidence shape, OpenRouter + model aliases, locked schema fields |
| `IncidentControllerTest` | POST handle inputs, handle-scenario fixture+scenario, scenarios list, unknown scenario rejection, journal envelope |
| `IncidentTelemetryServiceTest` | Known scenario story data, unknown neutrality, optional runbook category |
| `InsuranceSkillCatalogTests` | 11 insurance skills, target isolation, root evidence (L2 only), mid-level no contract, model aliases, locked schemas |
| `ClaimsControllerTest` | POST process inputs, process-scenario fixture+enrichment, scenarios list, unknown rejection, journal envelope |
| `InsurancePolicyServiceTest` | Payout formula, exclusion matching, unknown neutrality |
| `ClaimsHistoryServiceTest` | Fraud velocity elevation, clean history, unknown neutrality |
| `SupportSkillCatalogTests` | 14 support skills, target isolation, planning graph, root evidence (L2 only; no `checkRefundPolicy` at root), model aliases, locked schemas |
| `SupportControllerTest` | POST resolve inputs, resolve-scenario fixture+customerId enrichment, scenarios list, unknown/missing rejection, journal envelope |
| `SupportCrmServiceTest` | Scenario bias (duplicates, goodwill, tickets, help articles), unknown neutrality, deterministic bug tickets |

These tests mock or stub model calls where needed; they validate wiring, not live LLM quality. No test calls OpenRouter or Ollama for incident/insurance/support skills. Live nested-planning quality is a manual smoke step with a real `OPENROUTER_API_KEY`.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Connection errors on invoice / planning skills | The selected named Ollama connection is not running, or its model is not pulled |
| Startup rejects `provider` or `spring.ai.*` appears ignored | Replace each model's `provider` with `connection`, define the connection, and move transport credentials/settings there |
| Feedstock endpoints fail with missing API key | `OPENAI_API_KEY` not set in the process environment |
| Incident handle, claims process, or support resolve endpoints fail at runtime | `OPENROUTER_API_KEY` not set (boot still works with dummy default; live calls need a real key) |
| Skill not found | Skill `name` mismatch, or file not under `classpath:/skills/**/*.yml` |
| Long hangs | Vision/planning can take minutes; mission timeout is `6000s` by design; nested incident/insurance/support planning is slower/costlier than invoice samples |
| Schema / linter retries in logs | Expected when the model returns non-JSON or incomplete fields; check DEBUG logs |
| Unknown scenario on `/incidents/handle-scenario` | Use `GET /incidents/scenarios` for valid keys (`network-dns`, `app-deploy-regression`, `ambiguous-slow`, `firewall-block`) |
| Unknown scenario on `/claims/process-scenario` | Use `GET /claims/scenarios` for valid keys (`clear-auto-pay`, `exclusion-flood`, `fraud-velocity`, `ambiguous-liability`, `over-limit`) |
| Unknown scenario on `/support/resolve-scenario` | Use `GET /support/scenarios` for valid keys (`billing-duplicate-charge`, `tech-crash-on-checkout`, `mixed-billing-and-crash`, `how-to-export`, `angry-goodwill`) |

## Related modules

- **`bifrost-spring-boot-starter`** — framework core and auto-configuration
- **`bifrost-cli`** — inspect persisted execution traces from sample runs
- Root **`README.md`** — framework concepts, skill YAML reference, and starter setup
