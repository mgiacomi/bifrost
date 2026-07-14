# Bifrost Sample

A runnable Spring Boot application that demonstrates Bifrost skills end to end: public YAML skill discovery, internal Java `@SkillMethod` targets, multi-provider model routing, HTN planning, vision/attachment inputs, and HTTP invocation via `SkillTemplate`.

Use this module as a reference implementation when integrating `bifrost-spring-boot-starter` into your own app.

## What this sample shows

| Pattern | Where |
| --- | --- |
| Mapped YAML skill → Java method | `expenseLookup`, `feedstockTicketParser` |
| Pure LLM YAML skill with `input_schema` / `output_schema` / linter | `invoiceParser` |
| Planning skill (`planning_mode: true`) with `allowed_skills` + `evidence_contract` | `duplicateInvoiceChecker` |
| Pure YAML vision skill with `attachment` input | `feedstockTicketParserBySkill` |
| Named multi-provider models (`ollama` + `openai`) | `application.yml` → `bifrost.models` |
| HTTP API that invokes skills and returns execution metadata | `SampleController` |
| Execution journal / session id via `SkillTemplate` observer | invoice and feedstock endpoints |

## Prerequisites

- **Java 21+**
- **Maven 3.9+** (or the repo-root wrapper: `mvnw` / `mvnw.cmd`)
- **Ollama** running locally (default `http://localhost:11434`) with at least one of the configured models pulled, e.g.:
  - `ibm/granite4:tiny-h` (default chat + several skills)
  - optionally `gemma4:e2b`, `gemma4:e4b`, `gemma4:26b`
- **OpenAI API key** for feedstock vision demos:
  - set `OPENAI_API_KEY` in the environment (preferred)
  - or `openai.api.key` / Spring AI `spring.ai.openai.api-key`

PowerShell:

```powershell
$env:OPENAI_API_KEY = "sk-..."
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
    │   │   ├── SampleController.java               # HTTP demos → SkillTemplate
    │   │   ├── ExpenseService.java                 # @SkillMethod (deterministic data)
    │   │   └── FeedstockFormExtractionService.java # @SkillMethod (OpenAI vision HTTP)
    │   └── resources/
    │       ├── application.yml                     # Spring AI + bifrost config
    │       ├── forms/                              # Sample weigh ticket image/PDF
    │       └── skills/
    │           ├── basics/                         # Mapped leaf, LLM parse, 2-level plan
    │           ├── vision/                         # Feedstock Java + pure YAML vision
    │           ├── incidents/                      # HTN gallery (planned)
    │           ├── support/                        # HTN gallery (planned)
    │           ├── insurance/                      # HTN gallery (planned)
    │           └── travel/                         # HTN gallery (planned)
    └── test/
        ├── java/.../sample/                        # Context + controller unit tests
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

Named Bifrost models (each LLM-backed YAML skill must set `model` to one of these keys; mapped YAML wrappers omit it):

| Key | Provider | Provider model |
| --- | --- | --- |
| `granite4-tiny` | ollama | `ibm/granite4:tiny-h` |
| `gemma4-e2b` | ollama | `gemma4:e2b` |
| `gemma4-e4b` | ollama | `gemma4:e4b` |
| `gemma4-26b` | ollama | `gemma4:26b` |
| `default-model` | ollama | `ibm/granite4:tiny-h` |
| `openai-gpt-5-mini` | openai | `gpt-5-mini` |

Notes:

- `default-model` is an ordinary named model key; it is **not** auto-selected for LLM-backed skills that omit `model`.
- Session mission timeout is raised to `6000s` for long vision/planning runs.
- `execution-trace.persistence: ALWAYS` keeps full execution traces for inspection (useful with `bifrost-cli`).

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

### HTN gallery (planned)

Empty folders reserved for nested skill-tree samples:

| Folder | Sample (ticket) |
| --- | --- |
| `skills/incidents/` | IT Incident Commander |
| `skills/support/` | Support Case Resolver |
| `skills/insurance/` | Insurance Claim Intake |
| `skills/travel/` | Travel Concierge |

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

`/expenses` returns the skill result object directly (list of expense maps).

## Sample assets

| Asset | Purpose |
| --- | --- |
| `src/main/resources/forms/feedstock-p1.jpg` | Weighmaster certificate image for vision demos |
| `src/main/resources/forms/feedstock.pdf` | Related PDF form (available on classpath; not wired to an endpoint yet) |
| `src/test/resources/fixtures/duplicate-invoice.txt` | Minimal invoice text for parse / duplicate-check demos |

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
4. Bifrost resolves the skill, then either selects the named model provider for LLM-backed execution or routes a mapped skill directly to its Java target, and returns text (plus optional journal).

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

These tests mock or stub model calls where needed; they validate wiring, not live LLM quality.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Connection errors on invoice / planning skills | Ollama not running, or model not pulled |
| Feedstock endpoints fail with missing API key | `OPENAI_API_KEY` not set in the process environment |
| Skill not found | Skill `name` mismatch, or file not under `classpath:/skills/**/*.yml` |
| Long hangs | Vision/planning can take minutes; mission timeout is `6000s` by design |
| Schema / linter retries in logs | Expected when the model returns non-JSON or incomplete fields; check DEBUG logs |

## Related modules

- **`bifrost-spring-boot-starter`** — framework core and auto-configuration
- **`bifrost-cli`** — inspect persisted execution traces from sample runs
- Root **`README.md`** — framework concepts, skill YAML reference, and starter setup
