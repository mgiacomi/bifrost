# Bifrost Sample

A runnable Spring Boot application that demonstrates Bifrost skills end to end: public YAML skill discovery, internal Java `@SkillMethod` targets, named-connection model routing, HTN planning, vision/attachment inputs, and HTTP invocation via `SkillTemplate`.

Use this module as a reference implementation when integrating `bifrost-spring-boot-starter` into your own app.

## What this sample shows

| Pattern | Where |
| --- | --- |
| Mapped YAML skill → Java method | `expenseLookup`, `feedstockTicketParser`, incident probes |
| Pure LLM YAML skill with `input_schema` / `output_schema` / linter | `invoiceParser` (linter); incident `classifyIncident` / `draftIncidentResponse` use schemas + retries only (no linter) |
| Planning skill (`planning_mode: true`) with `allowed_skills` + `evidence_contract` | `duplicateInvoiceChecker` (2-level), `handleIncident` (3-level HTN) |
| Nested mid-level planners | `investigateNetwork`, `investigateApp` |
| Pure YAML vision skill with `attachment` input | `feedstockTicketParserBySkill` |
| Named connections and model aliases (`ollama` + `openai` + OpenRouter) | `application.yml` → `bifrost.connections` / `bifrost.models` |
| HTTP API that invokes skills and returns execution metadata | `SampleController`, `IncidentController` |
| Execution journal / session id via `SkillTemplate` observer | invoice, feedstock, and incident endpoints |

## Prerequisites

- **Java 21+**
- **Maven 3.9+** (or the repo-root wrapper: `mvnw` / `mvnw.cmd`)
- **Ollama** running locally (default `http://localhost:11434`) with at least one of the configured models pulled, e.g.:
  - `ibm/granite4:tiny-h` (default chat + several skills)
  - optionally `gemma4:e2b`, `gemma4:e4b`, `gemma4:26b`
- **OpenAI API key** for feedstock vision demos:
  - set `OPENAI_API_KEY` in the environment (preferred)
  - Bifrost reads it through `bifrost.connections.openai-main.api-key`; `spring.ai.*` is not inherited
- **OpenRouter API key** for the nested incident HTN sample (live demos only):
  - set `OPENROUTER_API_KEY` in the environment
  - sample boots without a real key via dummy default `test-openrouter-api-key`
  - Bifrost reads it through `bifrost.connections.openrouter.api-key`

PowerShell:

```powershell
$env:OPENAI_API_KEY = "sk-..."
$env:OPENROUTER_API_KEY = "sk-or-..."   # only needed for live /incidents/* handle calls
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
    │   │   └── incident/
    │   │       ├── IncidentController.java         # Incident HTN HTTP demos
    │   │       └── IncidentTelemetryService.java   # Deterministic probe leaves
    │   └── resources/
    │       ├── application.yml                     # Named AI connections + Bifrost config
    │       ├── forms/                              # Sample weigh ticket image/PDF
    │       ├── fixtures/incidents/                 # Canned incident tickets
    │       └── skills/
    │           ├── basics/                         # Mapped leaf, LLM parse, 2-level plan
    │           ├── vision/                         # Feedstock Java + pure YAML vision
    │           ├── incidents/                      # 3-level HTN incident commander
    │           ├── support/                        # HTN gallery (planned)
    │           ├── insurance/                      # HTN gallery (planned)
    │           └── travel/                         # HTN gallery (planned)
    └── test/
        ├── java/.../sample/                        # Context + controller unit tests
        │   └── incident/                           # Catalog, controller, leaf tests
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
- Incident planners use `qwen3-35b`; classify/draft workers use `gpt-4o-mini`. Nested planning needs a capable model — this sample does **not** use `granite4-tiny` for the incident tree.

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

### HTN gallery (remaining)

| Folder | Sample (ticket) |
| --- | --- |
| `skills/support/` | Support Case Resolver (planned) |
| `skills/insurance/` | Insurance Claim Intake (planned) |
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
```

curl-friendly:

```bash
curl -s http://localhost:8081/incidents/scenarios
curl -s "http://localhost:8081/incidents/handle-scenario?name=network-dns"
curl -s -X POST http://localhost:8081/incidents/handle \
  -H "Content-Type: application/json" \
  -d '{"ticketText":"Checkout 500s after deploy","scenario":"app-deploy-regression"}'
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

`/expenses` returns the skill result object directly (list of expense maps). Incident endpoints omit `filePath` and return `result` / `sessionId` / `executionJournal` only.

## Sample assets

| Asset | Purpose |
| --- | --- |
| `src/main/resources/forms/feedstock-p1.jpg` | Weighmaster certificate image for vision demos |
| `src/main/resources/forms/feedstock.pdf` | Related PDF form (available on classpath; not wired to an endpoint yet) |
| `src/test/resources/fixtures/duplicate-invoice.txt` | Minimal invoice text for parse / duplicate-check demos |
| `src/main/resources/fixtures/incidents/*.txt` | Canned incident tickets for HTN demos (`network-dns`, `app-deploy-regression`, `ambiguous-slow`, `firewall-block`) |

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

These tests mock or stub model calls where needed; they validate wiring, not live LLM quality. No test calls OpenRouter or Ollama for incident skills. Live nested-planning quality is a manual smoke step with a real `OPENROUTER_API_KEY`.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Connection errors on invoice / planning skills | The selected named Ollama connection is not running, or its model is not pulled |
| Startup rejects `provider` or `spring.ai.*` appears ignored | Replace each model's `provider` with `connection`, define the connection, and move transport credentials/settings there |
| Feedstock endpoints fail with missing API key | `OPENAI_API_KEY` not set in the process environment |
| Incident handle endpoints fail at runtime | `OPENROUTER_API_KEY` not set (boot still works with dummy default; live calls need a real key) |
| Skill not found | Skill `name` mismatch, or file not under `classpath:/skills/**/*.yml` |
| Long hangs | Vision/planning can take minutes; mission timeout is `6000s` by design; nested incident planning is slower/costlier than invoice samples |
| Schema / linter retries in logs | Expected when the model returns non-JSON or incomplete fields; check DEBUG logs |
| Unknown scenario on `/incidents/handle-scenario` | Use `GET /incidents/scenarios` for valid keys (`network-dns`, `app-deploy-regression`, `ambiguous-slow`, `firewall-block`) |

## Related modules

- **`bifrost-spring-boot-starter`** — framework core and auto-configuration
- **`bifrost-cli`** — inspect persisted execution traces from sample runs
- Root **`README.md`** — framework concepts, skill YAML reference, and starter setup
