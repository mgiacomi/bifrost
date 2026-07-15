# Sample HTN Skill Tree — IT Incident Commander Implementation Plan

## Overview

Add a three-level HTN incident-response skill tree to `bifrost-sample` that demonstrates nested planning (root planner → mid-level investigate planners → deterministic Java leaves), governed `allowed_skills`, a light root evidence contract, and OpenRouter-backed model routing with planner alias `qwen3-35b` and worker alias `gpt-4o-mini`. No framework changes.

## Current State Analysis

- `skills/incidents/` exists with only `.gitkeep`; README already lists the HTN gallery as planned.
- Existing sample patterns to mirror:
  - Mapped leaf: `skills/basics/expense_lookup.yml` → `ExpenseService` (minimal `name` / `description` / `mapping`).
  - Single-shot LLM: `skills/basics/invoice_parser.yml` (`input_schema` / `output_schema` / retries).
  - Planning + evidence: `skills/basics/duplicate_invoice_checker.yml` (`planning_mode`, `allowed_skills`, `evidence_contract`).
  - Private `prompt`: `skills/vision/feedstock_ticket_parser_by_skill.yml`.
  - HTTP + journal: `SampleController` uses `SkillTemplate.invoke(..., observer)` → `result` / `sessionId` / `executionJournal`.
  - Dummy OpenAI key: `openai-main` uses `${OPENAI_API_KEY:test-openai-api-key}` so CI boots without a real key.
- OpenAI driver already supports OpenRouter-compatible `base-url` and static `headers` (`BifrostProperties`, `model-selection-and-connections.md`).
- Nested YAML missions snapshot/restore parent plan + evidence (`CapabilityExecutionRouter`); leaf evidence does not bubble to the parent ledger.
- Parent POM enables `-parameters` for reflected Java parameter names; `@ToolParam` supplies description/requiredness for leaf contracts.
- Tests today: `SampleApplicationTests` (catalog/context) + `SampleControllerTest` (mocked `SkillTemplate`); no live LLM in CI.
- Ticket decisions are locked (2026-07-14); residual plan choices confirmed during planning:
  - No optional free-text GET.
  - Fixtures only under main resources (`classpath:/fixtures/incidents/`).
  - Dedicated incident catalog test class (not bloating `SampleApplicationTests`).

## Desired End State

A runnable sample path where:

1. All 12 incident YAML skills load from `classpath:/skills/incidents/**/*.yml`.
2. Root `handleIncident` can invoke mid-level `investigateNetwork` / `investigateApp` (planning) which invoke Java-mapped probes.
3. Root evidence contract plan-requires classify + ≥1 investigation specialist + draft.
4. HTTP endpoints under `/incidents/*` return structured report + `sessionId` + `executionJournal`.
5. Sample boots and unit tests pass without a real OpenRouter key; live smoke uses `OPENROUTER_API_KEY`.
6. README documents the tree, evidence rules, scenario plumbing, journal nesting, and model setup.

### Key Discoveries:

- Nested isolation is already enforced: parent `tool_evidence` must name L2 specialists (`classifyIncident`, `investigateNetwork`, `investigateApp`, `draftIncidentResponse`), not L3 probes.
- Multiple tools may produce the same evidence type; one successful producer satisfies a claim — shared `investigation_digest` from either investigate branch is correct.
- Mapped leaves must omit schemas/model/planning/evidence so Java remains the single contract source (same as `expenseLookup`).
- Skill discovery already covers `classpath:/skills/**/*.yml`; subdirectory `skills/incidents/` needs no config change.
- OpenRouter is just another OpenAI-driver connection with `base-url: https://openrouter.ai/api/v1` and a dummy-default api-key.

## What We're NOT Doing

- Framework changes (unless a hard gap is discovered; escalate rather than patch around).
- Real monitoring integrations (Prometheus, PagerDuty, etc.).
- Golden-file exact LLM outputs or live-model CI tests.
- Replacing invoice/feedstock samples.
- Attachments, RBAC, multi-provider routing per skill, or regex linters on incident skills.
- Exhausting `max_steps` / depth limits as a demo scenario (document only).
- Using tiny local models (`granite4-tiny`) for this tree.
- Optional `GET /incidents/handle?text=...`.
- Mirroring fixtures under `src/test/resources`.
- Updating `ai/skill-authoring/` (sample-only work).
- Running `3_testing_plan.md` as a prerequisite (owner will run later if desired).

## Skill-Authoring Documentation Impact

**Impact**: No impact

- **Rationale**: This work only adds sample composition in `bifrost-sample`. It does not change manifest syntax, validation, defaults, planning/evidence semantics, model resolution, or any other author-facing framework behavior. Nested planning, evidence isolation, mapped-leaf contract ownership, and named connections are already documented in `ai/skill-authoring/`.
- **Documents to update**: None
- **Supporting evidence**: Ticket non-goals (no framework changes); existing KB topics (`mental-model.md`, `evidence-contracts.md`, `model-selection-and-connections.md`); framework tests such as `CapabilityExecutionRouterTest` nested evidence restore.
- **Coverage table update**: Not required
- **LLM-first usability**: Not applicable

## Implementation Approach

Bottom-up, sample-only:

1. Wire OpenRouter + planner/worker aliases (`qwen3-35b`, `gpt-4o-mini`) so LLM skill manifests can resolve at catalog load.
2. Implement deterministic leaves + fixtures (testable without models).
3. Author the full YAML tree with locked contracts/prompts from the ticket.
4. Add `IncidentController` for demos.
5. Add catalog / controller / leaf tests that never call the live API.
6. Document in `bifrost-sample/README.md`.
7. Manual smoke with a real OpenRouter key (human).

All locked YAML field values, evidence contract, and I/O schemas are taken verbatim from `ai/thoughts/tickets/eng-sample-htn-incident-commander.md`. Do not invent alternate shapes.

## Phase 1: OpenRouter Connection and Planner/Worker Model Aliases

### Overview

Add the shared OpenRouter connection and two framework model aliases (planner + worker) required by the five LLM-backed incident skills so the sample boots with a dummy key and live demos can use a real key.

### Changes Required:

#### 1. Sample application config
**File**: `bifrost-sample/src/main/resources/application.yml`  
**Changes**: Under `bifrost.connections` and `bifrost.models`, add:

```yaml
bifrost:
  connections:
    # existing ollama-main, ollama-secondary, openai-main ...
    openrouter:
      driver: openai
      base-url: https://openrouter.ai/api/v1
      api-key: ${OPENROUTER_API_KEY:test-openrouter-api-key}
      # optional attribution headers (omit unless needed for demos):
      # headers:
      #   HTTP-Referer: ${OPENROUTER_SITE_URL}
  models:
    # existing models ...
    qwen3-35b:
      connection: openrouter
      provider-model: qwen/qwen3.6-35b-a3b
    gpt-4o-mini:
      connection: openrouter
      provider-model: openai/gpt-4o-mini
```

At implement time, confirm the OpenRouter provider slugs still resolve; keep framework aliases `qwen3-35b` and `gpt-4o-mini` even if a `provider-model` must change.

| Role | Alias | Provider model | Skills |
| --- | --- | --- | --- |
| Planner | `qwen3-35b` | `qwen/qwen3.6-35b-a3b` | `handleIncident`, `investigateNetwork`, `investigateApp` |
| Worker | `gpt-4o-mini` | `openai/gpt-4o-mini` | `classifyIncident`, `draftIncidentResponse` |

### Success Criteria:

#### Automated Verification:
- [x] Sample module compiles: `.\mvnw.cmd -pl bifrost-sample -am test-compile`
- [x] Existing sample tests still pass: `.\mvnw.cmd -pl bifrost-sample test`
- [x] Context loads with dummy OpenRouter key (no env var required)

#### Manual Verification:
- [x] Config keys appear as expected in `application.yml` (connection `openrouter`, models `qwen3-35b` + `gpt-4o-mini`)

---

## Phase 2: Java Leaves, Mapped YAML, and Fixtures

### Overview

Add deterministic telemetry/runbook capabilities and four canned incident ticket fixtures. Leaves accept `scenario` (and optional `category` for runbook) and return stable canned structures; unknown scenarios return neutral valid data (no exceptions).

### Changes Required:

#### 1. Telemetry service
**File**: `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/incident/IncidentTelemetryService.java`  
**Changes**: New `@Service` with seven `@SkillMethod` methods. Use `@ToolParam` for parameter description/requiredness. Distinct method names for target-id uniqueness.

| Method | Params | Behavior (keyed by `scenario`) |
| --- | --- | --- |
| `checkDns` | `scenario` (required) | Resolve status / hostname notes |
| `checkLatency` | `scenario` | p50/p95 + region |
| `checkFirewallRules` | `scenario` | Recent deny hits |
| `getErrorRate` | `scenario` | 5xx rate + window |
| `getRecentDeploys` | `scenario` | Service, version, timestamp |
| `getServiceHealth` | `scenario` | UP / DEGRADED / DOWN |
| `lookupRunbook` | `scenario` (required), `category` (optional) | Short runbook text |

Scenario keys that must have story-supporting data:

| Key | Leaf bias |
| --- | --- |
| `network-dns` | DNS failure signal |
| `app-deploy-regression` | High errors + recent deploy |
| `ambiguous-slow` | Mixed latency/health; mild conflict ok |
| `firewall-block` | Firewall deny hits |

Include at least one intentional mild conflict (e.g. high error rate with empty deploys for a non-deploy story) so mid-level judgment matters. Return `Map` / simple POJO-friendly structures (lists/maps of primitives/strings).

Target IDs (Spring default bean name `incidentTelemetryService`):

- `incidentTelemetryService#checkDns`
- `incidentTelemetryService#checkLatency`
- `incidentTelemetryService#checkFirewallRules`
- `incidentTelemetryService#getErrorRate`
- `incidentTelemetryService#getRecentDeploys`
- `incidentTelemetryService#getServiceHealth`
- `incidentTelemetryService#lookupRunbook`

#### 2. Minimal mapped leaf manifests
**Files** (under `bifrost-sample/src/main/resources/skills/incidents/`):

- `check_dns.yml`
- `check_latency.yml`
- `check_firewall_rules.yml`
- `get_error_rate.yml`
- `get_recent_deploys.yml`
- `get_service_health.yml`
- `lookup_runbook.yml`

Each file shape (example):

```yaml
name: checkDns
description: Checks DNS resolution status for the incident scenario.
mapping:
  target_id: incidentTelemetryService#checkDns
```

Rules:

- Only `name`, `description`, `mapping` (no schemas, model, planning, evidence).
- Public YAML `name` is lowerCamelCase matching the skill inventory.
- Remove `skills/incidents/.gitkeep` once real files exist (or leave; harmless).

#### 3. Ticket fixtures
**Files** under `bifrost-sample/src/main/resources/fixtures/incidents/`:

- `network-dns.txt`
- `app-deploy-regression.txt`
- `ambiguous-slow.txt`
- `firewall-block.txt`

Each is free-text ticket prose matching the ticket gist table (EU DNS, checkout 500s after deploy, intermittent slow, wiki blank after firewall change).

### Success Criteria:

#### Automated Verification:
- [x] Module compiles: `.\mvnw.cmd -pl bifrost-sample -am test-compile`
- [x] Leaf unit tests (added in Phase 5, or early stubs here) pass for known + unknown scenarios
- [x] Targets register as `incidentTelemetryService#...` (verified via catalog/context tests in Phase 5)

#### Manual Verification:
- [x] Fixture text is readable and domain-plausible for demos

---

## Phase 3: LLM and Planning Skill Tree YAML

### Overview

Author the five LLM-backed skills with locked I/O, planning settings, evidence contract (root only), and private prompts. Planners use `model: qwen3-35b`; single-shot workers use `model: gpt-4o-mini`.

### Changes Required:

#### 1. Single-shot skills (workers)
**Files**:

- `bifrost-sample/src/main/resources/skills/incidents/classify_incident.yml`
- `bifrost-sample/src/main/resources/skills/incidents/draft_incident_response.yml`

**Locks** (from ticket):

- `planning_mode: false` (or omit)
- `model: gpt-4o-mini`
- `output_schema_max_retries: 2`
- Full `input_schema` / `output_schema` exactly as ticket sections "Classify" and "Draft"
- Short public `description`; optional private `prompt` for classification/draft guidance (keep concise; no linter)

#### 2. Mid-level planners
**Files**:

- `investigate_network.yml`
- `investigate_app.yml`

**Locks**:

```yaml
model: qwen3-35b
planning_mode: true
max_steps: 6
output_schema_max_retries: 2
# shared input_schema / output_schema from ticket
# no evidence_contract
```

| Skill | `allowed_skills` | `domain` enum value in prompt |
| --- | --- | --- |
| `investigateNetwork` | `checkDns`, `checkLatency`, `checkFirewallRules` | `network` |
| `investigateApp` | `getErrorRate`, `getRecentDeploys`, `getServiceHealth` | `application` |

Private `prompt` MUST instruct:

- Forward `scenario` to every probe.
- Call only needed probes; stop when summary is supportable.
- Do not invent telemetry; base findings on probe results.
- Set `domain` correctly; `findings` / `probesUsed` reflect actual calls.

#### 3. Root planner
**File**: `handle_incident.yml`

**Locks**:

- `model: qwen3-35b`
- `planning_mode: true`
- `max_steps: 10`
- `output_schema_max_retries: 2` (align with other structured skills; ticket requires structured root report)
- `allowed_skills: [classifyIncident, investigateNetwork, investigateApp, draftIncidentResponse, lookupRunbook]`
- Locked root `input_schema` / `output_schema` from ticket
- Locked root `evidence_contract`:

```yaml
evidence_contract:
  claims:
    severity: [incident_classification]
    category: [incident_classification]
    likelyCause: [incident_classification, investigation_digest]
    evidenceSummary: [investigation_digest]
    recommendedAction: [investigation_digest]
    userMessage: [response_draft]
  tool_evidence:
    classifyIncident: [incident_classification]
    investigateNetwork: [investigation_digest]
    investigateApp: [investigation_digest]
    draftIncidentResponse: [response_draft]
```

Private `prompt` MUST instruct:

1. Prefer `classifyIncident` early with `ticketText` (+ `scenario` if present).
2. Choose investigation branch(es); avoid both unless mixed/ambiguous.
3. Forward `scenario` on every tool call that accepts it.
4. Optionally `lookupRunbook`; pass digests into draft.
5. Call `draftIncidentResponse` with ticket + investigation summary (+ optional runbook).
6. Emit full root report fields from classify + investigation + draft.

Do **not** put L3 probe names in root `tool_evidence` or `allowed_skills`.

### Success Criteria:

#### Automated Verification:
- [x] Sample context loads with all 12 incident skills registered: `.\mvnw.cmd -pl bifrost-sample test`
- [x] Catalog tests assert planning flags, allowed_skills, evidence shape, model alias (Phase 5)
- [x] No startup validation errors for manifests

#### Manual Verification:
- [x] Spot-check YAML names match lowerCamelCase inventory and file layout from ticket

---

## Phase 4: HTTP API (`IncidentController`)

### Overview

Expose demo endpoints without growing `SampleController`. Preferred live path loads fixture text and sets `scenario` explicitly.

### Changes Required:

#### 1. Controller
**File**: `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/incident/IncidentController.java`

| Method | Path | Behavior |
| --- | --- | --- |
| `POST` | `/incidents/handle` | Body: `ticketText` + optional `scenario` → `skillTemplate.invoke("handleIncident", inputs, observer)` |
| `GET` | `/incidents/scenarios` | List fixture keys + short descriptions (static table or derived from known keys) |
| `GET` | `/incidents/handle-scenario?name=...` | Load `classpath:/fixtures/incidents/{name}.txt`, set `scenario=name`, invoke `handleIncident` |

Implementation notes:

- Inject `SkillTemplate` + `ResourceLoader` (same pattern as `SampleController`).
- Use observer/`ViewHolder` pattern for `sessionId` + `executionJournal`.
- Response envelope (no `filePath` unless useful):

```json
{
  "result": "{ ... }",
  "sessionId": "...",
  "executionJournal": { }
}
```

- Unknown scenario name → clear 4xx (`IllegalArgumentException` or `ResponseStatusException`).
- Log session id + elapsed ms like existing controller.
- Request DTO for POST can be a simple record/class with `ticketText` and optional `scenario`.

#### 2. Do not modify
**File**: `SampleController.java` — leave invoice/feedstock endpoints unchanged.

### Success Criteria:

#### Automated Verification:
- [x] Controller unit tests pass with mocked `SkillTemplate` (Phase 5)
- [x] Module tests pass: `.\mvnw.cmd -pl bifrost-sample test`

#### Manual Verification:
- [ ] With app running and real `OPENROUTER_API_KEY`, `GET /incidents/scenarios` lists four keys
- [ ] `GET /incidents/handle-scenario?name=network-dns` returns report + journal (Phase 6 smoke)

---

## Phase 5: Automated Tests

### Overview

CI-safe tests only: catalog shape, controller delegation, leaf canned data. No live OpenRouter calls.

### Changes Required:

#### 1. Catalog / context tests
**File**: `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/incident/IncidentSkillCatalogTests.java`  
**Style**: `@SpringBootTest(classes = SampleApplication.class, webEnvironment = NONE)` like `SampleApplicationTests`.

Assert at minimum:

- All public skills registered:  
  `handleIncident`, `classifyIncident`, `investigateNetwork`, `investigateApp`, `draftIncidentResponse`,  
  `checkDns`, `checkLatency`, `checkFirewallRules`, `getErrorRate`, `getRecentDeploys`, `getServiceHealth`, `lookupRunbook`
- Java target IDs registered; raw method names are **not** public capabilities
- Mid-level: `planning_mode` true, `max_steps` 6, expected `allowed_skills`, no evidence contract
- Root: `planning_mode` true, `max_steps` 10, locked `allowed_skills`, evidence claims + tool_evidence shape
- Planning skills resolve model alias `qwen3-35b`; classify/draft resolve `gpt-4o-mini`
- Config: `bifrost.connections.openrouter` driver openai + base-url; both `qwen3-35b` and `gpt-4o-mini` use `connection: openrouter`
- Preferred: required output fields present on root/classify/mid/draft definitions via catalog

#### 2. Controller unit tests
**File**: `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/incident/IncidentControllerTest.java`  
**Style**: mock `SkillTemplate` like `SampleControllerTest`.

Assert:

- `POST /handle` path (method call) invokes `handleIncident` with `ticketText` (+ optional `scenario`)
- `handle-scenario` loads fixture text, sets `scenario` to name, invokes `handleIncident`
- Response includes `result`, `sessionId`, `executionJournal` when observer is used
- `scenarios` returns the four known keys
- Unknown scenario name fails clearly

#### 3. Leaf unit tests
**File**: `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/incident/IncidentTelemetryServiceTest.java`

Assert:

- Known scenarios return stable non-empty story-supporting data for the relevant probes
- Unknown scenario returns neutral valid structures (no throw)
- `lookupRunbook` accepts optional category without requiring live I/O

### Success Criteria:

#### Automated Verification:
- [x] All sample tests pass: `.\mvnw.cmd -pl bifrost-sample test`
- [ ] Full reactor optional: `.\mvnw.cmd test` (if time allows)
- [x] No test depends on network or real API keys

#### Manual Verification:
- [x] Test names and packages are clear for future HTN gallery samples

---

## Phase 6: README Documentation and Live Smoke

### Overview

Document the sample for humans and LLM collaborators; verify nested planning once with a real key.

### Changes Required:

#### 1. README updates
**File**: `bifrost-sample/README.md`

Replace the "HTN gallery (planned)" stub for incidents with a full section covering:

- Tree diagram (3-level skill stack)
- Framework features table (nested planning vs `duplicateInvoiceChecker` 2-level)
- What LLM decides vs fixed by YAML/Java
- Evidence contract rules: AND semantics; shared `investigation_digest`; L2 tool names; plan coverage vs prompt; multiple producers
- Scenario plumbing (`scenario` forwarded; no session bag; prefer `handle-scenario`)
- Model setup: shared OpenRouter connection; planner `qwen3-35b` / worker `gpt-4o-mini`; `OPENROUTER_API_KEY`; why not tiny local models
- Example requests for 2–3 scenarios (PowerShell + curl-friendly)
- How to read journal nesting: MISSION → PLANNING → TOOL → nested MISSION
- Session / `max_steps` limits (document only)
- Update prerequisites, project layout, HTTP API table, configuration model table, tests table, troubleshooting

#### 2. Manual smoke (human, not CI)

With real key:

```powershell
$env:OPENROUTER_API_KEY = "sk-or-..."
.\mvnw.cmd -pl bifrost-sample spring-boot:run
# then:
Invoke-RestMethod http://localhost:8081/incidents/scenarios
Invoke-RestMethod "http://localhost:8081/incidents/handle-scenario?name=network-dns"
Invoke-RestMethod "http://localhost:8081/incidents/handle-scenario?name=app-deploy-regression"
```

Verify:

- Nested frames appear in `executionJournal`
- At least one mid-level planner ran probes
- Report fields present (severity, category, etc.)
- Branch bias roughly matches scenario (not brittle exact JSON)

### Success Criteria:

#### Automated Verification:
- [x] `.\mvnw.cmd -pl bifrost-sample test` still passes after README-only edits
- [x] README references real paths/endpoints that exist in code

#### Manual Verification:
- [ ] Network fixture smoke shows nested planning and sensible network-biased report
- [ ] App fixture smoke shows app-biased investigation
- [x] Boot without `OPENROUTER_API_KEY` still succeeds; only live handle endpoints need the key
- [ ] Acceptance criteria checklist in the ticket can be marked complete

---

## Testing Strategy

### Unit Tests:
- Leaf canned data stability and unknown-scenario neutrality
- Controller input mapping and response envelope with mocked `SkillTemplate`
- Catalog registration, planning flags, allowed_skills, evidence contract shape, model alias wiring

### Integration Tests:
- None required for CI live LLM
- Optional future: test-double chat client for nested planning path (out of v1 scope)

### Manual Testing Steps:
1. Boot sample without OpenRouter key → context loads, unit tests green
2. Set real key; run `network-dns` and `app-deploy-regression` via `handle-scenario`
3. Inspect journal for nested MISSION frames and L2 tool names at root
4. Confirm optional `lookupRunbook` may or may not appear (not evidence-required)
5. Confirm mid-level does not call every probe every time (model judgment; soft check)

**Note**: A dedicated testing plan via `3_testing_plan.md` is optional and deferred to the owner.

## Performance Considerations

- Nested planning with a large model will be slower and costlier than invoice samples; session timeout is already `6000s`.
- Mid-level `max_steps: 6` and selective probe prompts reduce unnecessary tool loops.
- Dummy key ensures CI does not hang on network; live demos should expect multi-minute runs.

## Migration Notes

- Additive only: new package, skills, fixtures, config keys, README sections.
- No data migration; no breaking changes to existing sample endpoints.
- Operators may later retarget `qwen3-35b` or `gpt-4o-mini` to another connection without renaming skills.

## File checklist (final tree)

```
bifrost-sample/src/main/
  java/.../sample/incident/
    IncidentController.java
    IncidentTelemetryService.java
  resources/
    application.yml                          # + openrouter + qwen3-35b + gpt-4o-mini
    fixtures/incidents/
      network-dns.txt
      app-deploy-regression.txt
      ambiguous-slow.txt
      firewall-block.txt
    skills/incidents/
      handle_incident.yml
      classify_incident.yml
      investigate_network.yml
      investigate_app.yml
      draft_incident_response.yml
      check_dns.yml
      check_latency.yml
      check_firewall_rules.yml
      get_error_rate.yml
      get_recent_deploys.yml
      get_service_health.yml
      lookup_runbook.yml
  README.md                                  # incidents section + tables

bifrost-sample/src/test/java/.../sample/incident/
  IncidentSkillCatalogTests.java
  IncidentControllerTest.java
  IncidentTelemetryServiceTest.java
```

## Locked decisions carried into implementation

| Topic | Decision |
| --- | --- |
| Severity | `SEV1`–`SEV4` |
| Classify | Plan-required via root evidence |
| Evidence | Light root only; shared `investigation_digest` |
| Scenario | Explicit field + prompt forward; fixture endpoint |
| Model | Shared OpenRouter; planner `qwen3-35b` (`qwen/qwen3.6-35b-a3b`); worker `gpt-4o-mini` (`openai/gpt-4o-mini`) |
| Controller | New `IncidentController` |
| Draft | Single-shot; optional runbook via root |
| Limits demo | README only |
| Mid I/O | Shared digest schema |
| Mapped leaves | Minimal manifests |
| OpenRouter boot key | Dummy default for CI |
| Mid retries | `output_schema_max_retries: 2` |
| Free-text GET | Not implemented |
| Fixture mirror in test | Not implemented |
| Catalog tests | Dedicated `IncidentSkillCatalogTests` |

## References

- Original ticket: `ai/thoughts/tickets/eng-sample-htn-incident-commander.md`
- Epic index: `ai/thoughts/tickets/eng-sample-htn-skill-tree-gallery.md`
- Patterns: `skills/basics/duplicate_invoice_checker.yml`, `expense_lookup.yml`, `invoice_parser.yml`, `SampleController.java`
- Nested evidence: `CapabilityExecutionRouter`, `CapabilityExecutionRouterTest`
- Model/connections authoring: `ai/skill-authoring/model-selection-and-connections.md`
- Sample config: `bifrost-sample/src/main/resources/application.yml`
