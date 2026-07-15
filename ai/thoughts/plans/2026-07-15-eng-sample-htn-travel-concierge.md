# Sample HTN Skill Tree — Travel Concierge Implementation Plan

## Overview

Add a three-level HTN travel-concierge skill tree to `bifrost-sample` that plans a short trip from natural-language preferences. Gallery role: **approachable training demo** for developers learning Bifrost — nested planning, structured level handoffs, multi-option catalogs (“options in / choice out”), Java rank + LLM pick, light root evidence, and OpenRouter planner/worker models matching incident/support (`qwen3-35b` / `gpt-4o-mini`). No framework changes; no new connection keys.

Teaching priority over product completeness: every design choice should make a framework concept visible in YAML, journals, or tests.

## Current State Analysis

- `skills/travel/` exists as an empty planned gallery slot (README already lists it as planned).
- **Incident**, **insurance**, and **support** are shipped templates for package layout, controller, fixtures, catalog/controller/leaf tests, and README sections.
- OpenRouter connection + planner/worker aliases already exist in `application.yml` — **reuse**, do not re-add.
- Nested YAML missions snapshot/restore parent plan + evidence (`CapabilityExecutionRouter`); leaf evidence does not bubble to the parent ledger.
- Parent `tool_evidence` must name **L2 specialists**, not L3 leaves (proven by incident + support + authoring KB).
- Mapped leaves: minimal `name` / `description` / `mapping` only (Java owns the contract via `@SkillMethod` / `@ToolParam`).
- Skill discovery already covers `classpath:/skills/**/*.yml`; subdirectory needs no config change.
- Tests pattern: dedicated `*SkillCatalogTests`, `*ControllerTest`, leaf service tests; no live LLM in CI.
- Ticket models locked 2026-07-15 (OpenRouter, not Ollama-first). Remaining design questions locked during this planning session for **maximum framework pedagogy** (see Locked Decisions).

## Desired End State

A runnable sample path where:

1. All travel YAML skills load from `classpath:/skills/travel/**/*.yml` (10 skills: 1 root + 2 mid planners + 2 workers + 5 Java leaves including ranker).
2. Root `planTrip` invokes mandatory `understandPreferences`, nested `planTransport` and `planStay`, and `assembleItinerary`.
3. Root evidence contract plan-requires: `trip_preferences` + `transport_digest` + `stay_digest` + `itinerary_draft`.
4. Leaves return **multi-option catalogs** (≥2 options, including dominated options) so journals show real choice.
5. `rankTransportOptions` deterministically ranks options; transport planner still chooses — **“Java ranks, LLM picks.”**
6. HTTP endpoints under `/travel/*` return structured itinerary + `sessionId` + `executionJournal`.
7. Sample boots and unit tests pass without a real OpenRouter key; live smoke uses existing `OPENROUTER_API_KEY`.
8. README documents the tree for non-experts, teaching points, fixtures, model setup, and journal reading tips.

### Key Discoveries:

- Nested isolation is already enforced: parent `tool_evidence` names L2 tools (`understandPreferences`, `planTransport`, `planStay`, `assembleItinerary`), not L3 catalog methods.
- Support’s “understand → specialists → assemble” shape is the closest sibling; travel adds multi-option inventory + ranker as the unique teaching beat.
- Mapped leaves must omit schemas/model/planning/evidence so Java remains the single contract source.
- OpenRouter + `qwen3-35b` / `gpt-4o-mini` already wired; travel skills only reference those aliases.
- Leaves must key off **structured fields** from preferences (`origin`, `destination`, `date`, `scenario`, optional `partySize`) — not re-parse the original essay inside Java.

## Locked Decisions

| Topic | Decision | Teaching point |
| --- | --- | --- |
| Models | Planner `qwen3-35b` → `qwen/qwen3.6-35b-a3b`; worker `gpt-4o-mini` → `openai/gpt-4o-mini`; shared `openrouter` | Reuse named connections; no new keys |
| `understandPreferences` | **Mandatory first step**; evidence-required (`trip_preferences`) | Structured handoff between levels |
| Evidence | **Light root-only**: prefs + transport + stay + itinerary | Evidence isn’t only for ops/compliance |
| Mid-level evidence | **None** on `planTransport` / `planStay` | Root-only pattern (same as incident/support) |
| `rankTransportOptions` | **Include** under `planTransport` allow-list | Java ranks, LLM picks |
| Hard budget | **Soft preference only** (no Java rejector in v1) | Preference tradeoffs without a second validator lesson |
| `assembleItinerary` | **Synthesize only** — not allowed to call leaves | Clean HTN layering: gather → choose → compose |
| Train vs flight | **Planner chooses** which searches to run | Selective tool use under `allowed_skills` |
| Currency / dates | **USD** + **ISO date strings** in catalogs; LLM extracts dates from prose | Keep domain simple; focus on skills |
| Party size | Optional `partySize` on hotel search input; catalog may mildly bias or ignore | Planners pass structured params to mapped leaves |
| Fixtures | Four: `budget-nyc-weekend`, `loyalty-points-max`, `fastest-sfo-sea`, `underspecified` | ≥3 acceptance; underspecified teaches open questions |
| Demo UI | **Out of v1** | HTTP + journal is enough for training |
| Packaging | `skills/travel/` + `sample.travel` package only | Mirror siblings |

## What We're NOT Doing

- Framework changes (unless a hard gap is discovered; escalate rather than patch around).
- Real GDS / Airbnb / airline APIs, payments, or actual bookings.
- Multi-city optimization solvers or map rendering UI.
- Visa/immigration advice accuracy.
- Hard budget Java validator skill (follow-up lesson only).
- `planActivities` / weather / packing leaves (optional extensions, not v1).
- Golden-file exact LLM outputs or live-model CI tests.
- Replacing invoice / feedstock / incident / insurance / support samples.
- Mid-level `evidence_contract` on transport/stay planners.
- Defaulting this tree to `granite4-tiny` / Ollama-only.
- Optional free-text-only GET without scenario fixtures as the preferred path.
- Mirroring fixtures under `src/test/resources`.
- Updating `ai/skill-authoring/` (sample-only work).
- Running `3_testing_plan.md` as a prerequisite (owner may run later).

## Skill-Authoring Documentation Impact

**Impact**: No impact

- **Rationale**: This work only adds sample composition in `bifrost-sample`. It does not change manifest syntax, validation, defaults, planning/evidence semantics, model resolution, or any other author-facing framework behavior. Nested planning, evidence isolation, mapped-leaf contract ownership, and named connections are already documented in `ai/skill-authoring/`.
- **Documents to update**: None
- **Supporting evidence**: Ticket non-goals (no framework changes); existing KB topics (`mental-model.md`, `evidence-contracts.md`, `model-selection-and-connections.md`); framework tests such as `CapabilityExecutionRouterTest` nested evidence restore; incident + support samples as living references.
- **Coverage table update**: Not required
- **LLM-first usability**: Not applicable

## Implementation Approach

Bottom-up, sample-only, mirror support/incident phasing:

1. Implement deterministic catalog leaves + request fixtures (testable without models). No new model config.
2. Author the full YAML tree with locked contracts/prompts from this plan.
3. Add `TravelController` for demos.
4. Add catalog / controller / leaf tests that never call the live API.
5. Document in `bifrost-sample/README.md` with explicit **teaching points**.
6. Manual smoke with a real OpenRouter key (human).

Do not invent alternate evidence tags, model aliases, or root output shapes at implement time.

### Final skill inventory

| Name | Type | Model | Role |
| --- | --- | --- | --- |
| `planTrip` | L1 planning | `qwen3-35b` | Root orchestrator |
| `understandPreferences` | L2 single-shot | `gpt-4o-mini` | Extract structured trip prefs |
| `planTransport` | L2 planning | `qwen3-35b` | Transport specialist |
| `planStay` | L2 planning | `qwen3-35b` | Lodging specialist |
| `assembleItinerary` | L2 single-shot | `gpt-4o-mini` | Final itinerary + narrative |
| `searchFlights` | L3 mapped | — | Multi-option flight catalog |
| `searchTrains` | L3 mapped | — | Multi-option train catalog |
| `searchHotels` | L3 mapped | — | Multi-option hotel catalog |
| `checkLoyaltyPerks` | L3 mapped | — | Loyalty tier / chain perks |
| `rankTransportOptions` | L3 mapped | — | Deterministic rank by price or duration |

Tree:

```
planTrip                                   [L1 planning YAML]
├── understandPreferences                  [L2 LLM single-shot]
├── planTransport                          [L2 planning YAML]
│   ├── searchFlights                      [L3 Java]
│   ├── searchTrains                       [L3 Java]
│   └── rankTransportOptions               [L3 Java — ranks, LLM picks]
├── planStay                               [L2 planning YAML]
│   ├── searchHotels                       [L3 Java]
│   └── checkLoyaltyPerks                  [L3 Java]
└── assembleItinerary                      [L2 LLM single-shot — synthesize only]
```

### What the LLM is allowed to decide (training table)

| Level | Fixed | LLM freedom |
| --- | --- | --- |
| L1 | Specialists only | Order of planning; when to assemble; whether both transport + stay are needed |
| L2 transport | Search + rank tools only | Which searches to run; which option to prefer after rank |
| L2 stay | Hotel + perks tools only | Which hotel/perks to prefer given prefs |
| L2 understand/assemble | Schemas + prompts | Preference extraction; narrative itinerary; open questions |
| L3 | Fake catalogs + deterministic rank | None |

---

## Phase 1: Java Catalog Leaves, Mapped YAML, and Fixtures

### Overview

Add deterministic travel inventory capabilities and four canned trip-request fixtures. Leaves accept structured fields (`scenario` plus origin/destination/date where useful) and return multi-option catalogs; unknown scenarios return neutral valid multi-option data (no exceptions).

**Teaching point**: Java owns leaf contracts; YAML mapping is a thin public identity.

### Changes Required:

#### 1. Catalog service
**File**: `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/travel/TravelCatalogService.java`  
**Changes**: New `@Service` with five `@SkillMethod` methods. Use `@ToolParam` for parameter description/requiredness. Distinct method names for target-id uniqueness.

| Method | Params | Behavior |
| --- | --- | --- |
| `searchFlights` | `scenario` (required), optional `origin`, `destination`, `date` | List of ≥2 flights: airline, depart, arrive, price (USD), stops; include ≥1 dominated option |
| `searchTrains` | `scenario`, optional `origin`, `destination`, `date` | List of ≥2 trains: operator, durationMinutes, price (USD); include dominated option |
| `searchHotels` | `scenario`, optional `destination`, `startDate`, `endDate`, `partySize` | List of ≥2 hotels: name, nightlyRate (USD), rating, neighborhood, optional chain; include dominated option |
| `checkLoyaltyPerks` | `scenario`, optional `loyaltyTier`, `hotelChain` | Perks list for tier/chain; scenario-biased for loyalty fixture |
| `rankTransportOptions` | `optionsJson` or structured list input + `sortBy` (`price` \| `duration`) | Deterministic sort; return ranked list + `sortBy` echo — **no LLM** |

**Param design note**: Prefer simple string/number params that planners can pass from preference fields. For `rankTransportOptions`, accept a stringified JSON array of option maps **or** a simple list of maps if the framework tool binding supports it; implement-time pick the pattern that matches existing `@SkillMethod` list/map usage in the sample (maps/lists of primitives). Document the chosen shape in the method `@ToolParam` description so the transport planner prompt can pass it correctly.

Target IDs (Spring default bean name `travelCatalogService`):

- `travelCatalogService#searchFlights`
- `travelCatalogService#searchTrains`
- `travelCatalogService#searchHotels`
- `travelCatalogService#checkLoyaltyPerks`
- `travelCatalogService#rankTransportOptions`

**Scenario leaf bias** (must support fixtures):

| Key | Catalog bias |
| --- | --- |
| `budget-nyc-weekend` | Cheap trains + budget hotel/hostel; expensive nonstop flight present as dominated-for-budget |
| `loyalty-points-max` | Hotel chain with gold-tier perks even if pricier; `checkLoyaltyPerks` returns strong perks |
| `fastest-sfo-sea` | Nonstop flight cheaper-on-time than multi-stop; slow cheap multi-stop dominated for speed |
| `underspecified` | Generic multi-option catalogs still return ≥2 options; model should surface open questions rather than invent airports |

**Currency / dates**: All prices USD numbers. Times/dates as ISO-like strings (`2026-03-14`, `2026-03-14T08:00:00`). No timezone conversion logic.

**Dominated options**: Every default search for known scenarios includes at least one option that is strictly worse on the scenario’s primary axis (e.g. higher price and longer duration for budget; more stops for speed). Makes journal “choice” obvious in demos.

Unknown scenario: still return ≥2 generic valid options (not empty, not throw).

#### 2. Minimal mapped leaf manifests
**Files** under `bifrost-sample/src/main/resources/skills/travel/`:

- `search_flights.yml`
- `search_trains.yml`
- `search_hotels.yml`
- `check_loyalty_perks.yml`
- `rank_transport_options.yml`

Each file shape:

```yaml
name: searchFlights
description: Searches fake flight inventory and returns multiple priced options for the trip scenario.
mapping:
  target_id: travelCatalogService#searchFlights
```

Rules:

- Only `name`, `description`, `mapping` (no schemas, model, planning, evidence).
- Public YAML `name` is lowerCamelCase matching the inventory.
- Populate empty `skills/travel/` directory (already planned in README).

#### 3. Trip request fixtures
**Files** under `bifrost-sample/src/main/resources/fixtures/travel/`:

- `budget-nyc-weekend.txt`
- `loyalty-points-max.txt`
- `fastest-sfo-sea.txt`
- `underspecified.txt`

Each fixture is free-text trip request prose matching the scenario gist:

| Scenario | Request gist |
| --- | --- |
| `budget-nyc-weekend` | NYC weekend, ~$400 all-in, OK with trains |
| `loyalty-points-max` | Prefer a named hotel chain, gold tier, maximize perks |
| `fastest-sfo-sea` | Morning meeting SFO→SEA, minimize travel time |
| `underspecified` | “Somewhere warm in March” — missing origin/dates/budget |

Prefer `plan-scenario` demos so the model does not invent `scenario`.

### Success Criteria:

#### Automated Verification:
- [x] Module compiles: `.\mvnw.cmd -pl bifrost-sample -am test-compile`
- [x] Leaf unit tests (Phase 4) pass for known + unknown scenarios
- [x] Targets register as `travelCatalogService#...` (catalog tests Phase 4)
- [x] Default searches for known scenarios return ≥2 options including a dominated option

#### Manual Verification:
- [ ] Fixture texts are readable and fun for demos
- [ ] Underspecified fixture clearly lacks origin/destination/dates

---

## Phase 2: LLM and Planning Skill Tree YAML

### Overview

Author workers, mid-level planners, and root with locked I/O, planning settings, root-only evidence contract, and private prompts. Planners use `model: qwen3-35b`; single-shot workers use `model: gpt-4o-mini`.

**Teaching points**: nested `allowed_skills`, mandatory preference extraction, light evidence, synthesize-only assembly, selective transport searches.

### Changes Required:

#### 1. Single-shot workers
**Files**:

- `understand_preferences.yml`
- `assemble_itinerary.yml`

**Common locks**:

- `planning_mode: false` (or omit)
- `model: gpt-4o-mini`
- `output_schema_max_retries: 2`
- Short public `description`; private `prompt` for behavior (no linter)

**`understandPreferences`**

- **Input**: `requestText` (required), optional `scenario`
- **Output** (required fields from ticket, locked):
  - `origin` (string)
  - `destination` (string)
  - `startDate` (string)
  - `endDate` (string)
  - `budgetTotal` (number, nullable)
  - `partySize` (integer)
  - `priorities` (array of string; e.g. cost \| speed \| comfort \| loyalty)
  - `constraints` (array of string)
- **Prompt**: Extract structured prefs from `requestText`. Prefer ISO dates when possible. Do not invent airports/cities when under-specified — leave empty strings or vague values and rely on root `openQuestions`. `scenario` is fixture hint only. Soft budget is a preference, not a hard reject.
- Evidence producer for parent: `trip_preferences`

**`assembleItinerary`**

- **Input**: `requestText`, `preferencesSummary` (string or structured digest), `transportSummary`, `staySummary`, optional `scenario`
- **Output**: Align with root fields the root will emit — at minimum produce pieces the root can map:
  - `summary` (string)
  - `transport` object (`mode`, `outbound`, optional `returnLeg`)
  - `hotel` (object, nullable)
  - `estimatedTotal` (number, USD)
  - `rationale` (string)
  - `openQuestions` (array of string)
- **Prompt**: Synthesize only from provided digests. Do not invent flights/hotels not present in digests. Soft budget: note overruns in rationale / openQuestions rather than failing. If prefs under-specified, populate `openQuestions`.
- Evidence producer for parent: `itinerary_draft`
- **No** `allowed_skills` / planning — cannot re-search leaves.

#### 2. Mid-level planners
**Files**:

- `plan_transport.yml`
- `plan_stay.yml`

**Locks**:

```yaml
model: qwen3-35b
planning_mode: true
output_schema_max_retries: 2
# no evidence_contract
```

| Skill | `max_steps` | `allowed_skills` | Digest role |
| --- | --- | --- | --- |
| `planTransport` | 6 | `searchFlights`, `searchTrains`, `rankTransportOptions` | Transport digest → `transport_digest` for parent |
| `planStay` | 6 | `searchHotels`, `checkLoyaltyPerks` | Stay digest → `stay_digest` |

**`planTransport` output_schema** (suggested):

```yaml
properties:
  domain: { type: string, enum: [transport] }
  summary: { type: string }
  mode: { type: string }   # flight | train | mixed
  selectedOutbound: { type: object }
  selectedReturn: { type: object, nullable: true }
  optionsConsidered:
    type: array
    items: { type: object }
  toolsUsed:
    type: array
    items: { type: string }
  findings:
    type: array
    items:
      type: object
      properties:
        tool: { type: string }
        observation: { type: string }
      required: [tool, observation]
      additionalProperties: false
required: [domain, summary, mode, toolsUsed, findings]
additionalProperties: false
```

**`planStay` output_schema** (suggested):

```yaml
properties:
  domain: { type: string, enum: [stay] }
  summary: { type: string }
  selectedHotel: { type: object, nullable: true }
  loyaltyPerks: { type: object, nullable: true }
  optionsConsidered:
    type: array
    items: { type: object }
  toolsUsed:
    type: array
    items: { type: string }
  findings:
    type: array
    items:
      type: object
      properties:
        tool: { type: string }
        observation: { type: string }
      required: [tool, observation]
      additionalProperties: false
required: [domain, summary, toolsUsed, findings]
additionalProperties: false
```

**Prompts**:

- Forward `scenario` and structured preference fields (`origin`, `destination`, dates, `partySize`, priorities) to every leaf that accepts them.
- **Transport**: Choose which searches to run (do not always call both). Prefer calling `rankTransportOptions` after search when multiple options exist; pick based on priorities (cost vs speed). Do not invent inventory.
- **Stay**: Call `searchHotels`; call `checkLoyaltyPerks` when loyalty/priorities mention chain or tier. Do not invent hotels/perks.
- `toolsUsed` / findings must reflect tools actually invoked.

#### 3. Root planner
**File**: `plan_trip.yml`

```yaml
name: planTrip
description: >
  Root travel concierge. Plans preference understanding, transport and stay
  specialists, and itinerary assembly into a structured trip proposal (demo only).
model: qwen3-35b
planning_mode: true
max_steps: 10
allowed_skills:
  - understandPreferences
  - planTransport
  - planStay
  - assembleItinerary
prompt: |
  You are the root travel concierge (demo only — fake inventory).

  1. Prefer calling understandPreferences early with requestText (and scenario if present).
  2. Call planTransport and planStay with structured fields from preferences + scenario.
     Forward scenario on every tool call that accepts it.
  3. Call assembleItinerary with digests from preferences + transport + stay.
  4. Emit the full root itinerary fields grounded in tool results only.

  Do not invent flights, trains, or hotels. Do not call L3 leaves directly — only the
  specialists listed in allowed_skills. Soft budget: prefer options under budget when
  known; if over, explain in rationale and openQuestions rather than inventing cheaper inventory.
  For underspecified requests, prefer non-empty openQuestions over inventing airports.
evidence_contract:
  claims:
    summary: [itinerary_draft]
    transport: [transport_digest]
    hotel: [stay_digest]
    estimatedTotal: [itinerary_draft]
    rationale: [trip_preferences, transport_digest, stay_digest, itinerary_draft]
    openQuestions: [trip_preferences, itinerary_draft]
  tool_evidence:
    understandPreferences: [trip_preferences]
    planTransport: [transport_digest]
    planStay: [stay_digest]
    assembleItinerary: [itinerary_draft]
input_schema:
  type: object
  properties:
    requestText:
      type: string
      description: Natural language trip request.
    scenario:
      type: string
      description: Optional fixture key for catalog inventory.
  required: [requestText]
  additionalProperties: false
output_schema:
  type: object
  properties:
    summary: { type: string }
    transport:
      type: object
      properties:
        mode: { type: string }
        outbound: { type: object }
        returnLeg: { type: object, nullable: true }
      additionalProperties: true
    hotel: { type: object, nullable: true }
    estimatedTotal: { type: number }
    rationale: { type: string }
    openQuestions:
      type: array
      items: { type: string }
  required: [summary, transport, estimatedTotal, rationale, openQuestions]
  additionalProperties: false
output_schema_max_retries: 2
```

**Evidence notes (locked)**:

- Plan success requires tools that produce `trip_preferences`, `transport_digest`, `stay_digest`, and `itinerary_draft`.
- Parent must **not** list L3 tools (`searchFlights`, etc.) in root `tool_evidence`.
- Mid planners have **no** evidence contracts; digests are parent-facing outputs only.

**Implement-time schema flexibility**: Nested `transport` / `hotel` object property details may be loosened with `additionalProperties: true` on nested objects if strict nested schemas cause flaky retries; keep root **required** fields as locked.

### Success Criteria:

#### Automated Verification:
- [x] Catalog tests assert root allow-list, max_steps, models, evidence contract shape
- [x] Mid planners have correct allow-lists (including `rankTransportOptions` on transport) and no evidence contracts
- [x] Workers use `gpt-4o-mini` without planning
- [x] Sample context loads: `.\mvnw.cmd -pl bifrost-sample test` (after Phase 4 tests land)

#### Manual Verification:
- [ ] YAML validates at catalog load (no boot errors)
- [ ] Root evidence tags only reference L2 tool names

---

## Phase 3: HTTP API (`TravelController`)

### Overview

Add demo endpoints mirroring `IncidentController` / `SupportController`: list scenarios, plan by fixture name, free-form POST.

**Teaching point**: `SkillTemplate.invoke` + observer → `result` / `sessionId` / `executionJournal`.

### Changes Required:

#### 1. Controller
**File**: `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/travel/TravelController.java`

| Method | Path | Behavior |
| --- | --- | --- |
| `POST` | `/travel/plan` | JSON: `requestText` (required), optional `scenario` → invoke `planTrip` |
| `GET` | `/travel/plan-scenario` | `name=` fixture key → load fixture, set `scenario` |
| `GET` | `/travel/scenarios` | List fixture keys + short descriptions |

Response shape (same as siblings):

```json
{
  "result": "<skill output string>",
  "sessionId": "...",
  "executionJournal": { }
}
```

- Use `SkillTemplate.invoke("planTrip", inputs, observer)`.
- Validate known scenario names; 400 on unknown / missing `requestText`.
- Load fixtures from `classpath:/fixtures/travel/{name}.txt`.
- Log sessionId + scenario + elapsedMs (same pattern as support/incident).

Request record:

```java
public record PlanTripRequest(String requestText, String scenario) {}
```

No customerId-style enrichment required for travel v1 (scenario alone keys catalogs).

### Success Criteria:

#### Automated Verification:
- [x] Controller unit tests (Phase 4) pass with mocked `SkillTemplate`
- [x] Module compiles

#### Manual Verification:
- [ ] `GET /travel/scenarios` lists four keys
- [ ] Live smoke (Phase 6) returns journal metadata

---

## Phase 4: Tests (no live LLM)

### Overview

Dedicated travel test classes mirroring support/incident. CI must never call OpenRouter.

### Changes Required:

#### 1. Catalog tests
**File**: `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/travel/TravelSkillCatalogTests.java`

Assert:

- All public travel skills registered; mapping targets internal (not public capabilities).
- Root: `planning_mode`, `max_steps: 10`, allow-list exact membership (`understandPreferences`, `planTransport`, `planStay`, `assembleItinerary`), model `qwen3-35b`, evidence contract tags and `tool_evidence` keys (L2 only; no L3).
- Mid planners: allow-lists (`planTransport` includes `rankTransportOptions`), `max_steps: 6`, no evidence contract, model `qwen3-35b`.
- Workers (`understandPreferences`, `assembleItinerary`): no planning, model `gpt-4o-mini`.
- Required schema fields for root / key workers (mirror support assertions).
- OpenRouter connection + aliases still present (regression guard).

#### 2. Controller tests
**File**: `.../travel/TravelControllerTest.java`

- POST delegates with `requestText` + optional `scenario`; omits null keys.
- plan-scenario loads fixture + sets scenario.
- Unknown scenario → 400.
- Missing `requestText` → 400.
- Response includes `result`, `sessionId`, `executionJournal` (no `filePath`).

#### 3. Leaf tests
**File**: `.../travel/TravelCatalogServiceTest.java`

- Known scenarios return ≥2 options for flights/trains/hotels.
- Dominated option present for at least one primary scenario (budget or speed).
- `rankTransportOptions` ordering is deterministic for a fixed input + `sortBy`.
- `checkLoyaltyPerks` returns strong perks for `loyalty-points-max`.
- Unknown scenario returns neutral valid multi-option data (no throw).

### Success Criteria:

#### Automated Verification:
- [x] `.\mvnw.cmd -pl bifrost-sample test` passes
- [x] No test invokes live HTTP to OpenRouter
- [x] Context loads with dummy `test-openrouter-api-key`

#### Manual Verification:
- [x] N/A

---

## Phase 5: README Documentation

### Overview

Document the travel gallery piece in `bifrost-sample/README.md` as the **approachable HTN training demo**. Emphasize teaching points for developers learning the framework.

### Changes Required:

#### 1. README updates
**File**: `bifrost-sample/README.md`

- Pattern table: add travel nested planners / controller / multi-option catalogs / ranker.
- Prerequisites: OpenRouter used for `/travel/*` live demos too.
- Layout tree: `travel/` package, `fixtures/travel/`, `skills/travel/` (move out of “planned”).
- New section **Travel (`skills/travel/`) — 3-level HTN (training demo)** with:
  - Friendly tree diagram
  - **Teaching points** callout:
    - Nested planners + `allowed_skills`
    - Structured preference handoff (leaves do not re-parse the essay)
    - Options in / choice out (multi-option catalogs + dominated options)
    - Java ranks, LLM picks (`rankTransportOptions`)
    - Light root evidence + nested isolation (L2 names only)
    - Planner vs worker models
  - What LLM decides vs fixed (table)
  - Evidence rules: require understand + transport + stay + assemble
  - Scenario table
  - Example itinerary JSON (illustrative)
  - Limitations (fake data, not real availability)
  - Model setup (reuse OpenRouter aliases)
  - Journal reading tips (look for specialist frames, search → rank → pick)
  - Contrast: vs incident (ops evidence), vs support (multi-intent CRM), vs insurance (strong compliance evidence) — travel is the **gateway / fun** sample
- HTTP API table + PowerShell/curl examples for `/travel/*`
- Fixtures list entry for `fixtures/travel/*.txt`
- Short **2-minute demo script** for talks (e.g. budget-nyc-weekend → show journal nesting)

### Success Criteria:

#### Automated Verification:
- [x] N/A (docs only)

#### Manual Verification:
- [ ] README tree matches YAML inventory
- [ ] Gallery no longer lists travel as planned-only
- [ ] Non-engineer can follow model setup + one GET scenario
- [ ] Copy-paste demo commands work against a running sample (with real key for live plan)

---

## Phase 6: Manual Smoke (human)

### Overview

Live verification with a real OpenRouter key. Capture journal observations for README if useful (optional excerpt).

### Steps:

1. Set `$env:OPENROUTER_API_KEY` and start sample (`.\mvnw.cmd -pl bifrost-sample spring-boot:run` or project-standard run).
2. `GET /travel/scenarios`
3. `GET /travel/plan-scenario?name=budget-nyc-weekend` — expect train-leaning options, multi-option leaves in journal, nested transport/stay frames.
4. `GET /travel/plan-scenario?name=fastest-sfo-sea` — expect flight/speed preference; ranker or multi-option choice visible.
5. `GET /travel/plan-scenario?name=loyalty-points-max` — expect perks path + hotel chain bias.
6. `GET /travel/plan-scenario?name=underspecified` — expect non-empty `openQuestions`; should not invent confident airports.
7. Confirm response includes `sessionId` + `executionJournal`; nested frames show L2 then L3 under transport/stay.

### Success Criteria:

#### Automated Verification:
- [ ] N/A

#### Manual Verification:
- [ ] Journal shows specialist delegation (not one flat mega-plan only)
- [ ] Multi-option catalog calls visible under transport/stay
- [ ] Underspecified path surfaces open questions
- [ ] CI still green without real key

---

## Testing Strategy

### Unit Tests:
- Catalog leaf multi-option stability, dominated options, ranker determinism, unknown-scenario neutrality
- Catalog registration, allow-lists, models, evidence contract shape
- Controller delegation and validation

### Integration Tests:
- Spring context load with full travel catalog (via catalog tests)
- No live LLM integration tests in CI

**Note**: Prefer a dedicated testing plan via `3_testing_plan.md` for full details if desired. This section is a high-level summary.

### Manual Testing Steps:
1. Boot sample without `OPENROUTER_API_KEY` — context starts.
2. Live plan budget + speed + loyalty + underspecified scenarios with real key.
3. Inspect journal nesting and evidence-related plan events at root (L2 names only).
4. Walk a new developer through README teaching points while watching one journal.

## Performance Considerations

- Nested transport + stay planners are slower/costlier than single-shot invoice samples; mission timeout already `6000s`.
- Prefer scenario GET for demos to reduce wasted steps from invented scenario keys.
- Selective transport searches (not always both modes) keeps some paths cheaper.

## Migration Notes

N/A — new sample only. No data migration. No changes to existing skill names. No new `application.yml` connection/model keys.

## References

- Original ticket: `ai/thoughts/tickets/eng-sample-htn-travel-concierge.md`
- Sibling plans: `ai/thoughts/plans/2026-07-14-eng-sample-htn-incident-commander.md`, `ai/thoughts/plans/2026-07-15-eng-sample-htn-support-case-resolver.md`
- Living samples: `bifrost-sample/src/main/resources/skills/incidents/`, `.../skills/support/`
- Controllers: `IncidentController.java`, `SupportController.java`
- Authoring KB: `ai/skill-authoring/evidence-contracts.md`, `mental-model.md`, `model-selection-and-connections.md`
- README gallery: `bifrost-sample/README.md`
- Models already wired: `bifrost-sample/src/main/resources/application.yml` (`openrouter`, `qwen3-35b`, `gpt-4o-mini`)
```
