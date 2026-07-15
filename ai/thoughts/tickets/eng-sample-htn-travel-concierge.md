# Ticket: Sample HTN Skill Tree — Travel Concierge

**Status:** Implemented (2026-07-15) — manual smoke remaining (Phase 6)  
**Priority:** P3  
**Module:** `bifrost-sample`  
**Implementation plan:** `ai/thoughts/plans/2026-07-15-eng-sample-htn-travel-concierge.md`  
**Related tickets:**  
- `eng-sample-htn-incident-commander.md`  
- `eng-sample-htn-support-case-resolver.md`  
- `eng-sample-htn-insurance-claim-intake.md`  
**Depends on:** Nested planning (existing); optional attachment inputs if we later accept screenshots of confirmations (not required for v1)  

---

## Summary

Add a **fun, approachable three-level HTN skill tree** that plans a short trip from natural-language preferences. Root planning skill decomposes into transport and lodging specialists; leaves return fake flights, trains, hotels, and loyalty perks; a final skill assembles an itinerary.

**Primary purpose:** training demo for developers learning Bifrost. Prefer options that teach and show off the framework (nested planning, structured handoffs, multi-option catalogs, Java rank + LLM pick, light evidence, journals). Product-perfect travel outcomes are secondary.

This sample prioritizes **explainability and delight** for demos/talks. It is intentionally less “enterprise evidence” than insurance, but still uses a **light root evidence contract** so learners see evidence outside ops/compliance samples.

## Motivation

Not every Bifrost audience lives in ops or insurance. A travel concierge:

- Makes HTN decomposition intuitive (trip → transport + stay → bookable options).  
- Shows preference tradeoffs (cheap vs fast vs loyalty) that rules engines handle poorly.  
- Produces a tangible artifact (day-by-day itinerary) people enjoy reading.  
- Still exercises nested planning, `allowed_skills`, schemas, and journals.

It is a **gateway sample**: lower cognitive load than claims/incidents, still a real tree.

## Goals

- Three-level skill stack with nested planners for transport and stay.  
- Fake inventory leaves with multiple options (including dominated options) so the LLM must choose.  
- Deterministic `rankTransportOptions` leaf so journals teach **“Java ranks, LLM picks.”**  
- Structured itinerary output (legs, hotel, rough cost, rationale, openQuestions).  
- Mandatory `understandPreferences` with structured handoff to leaves (no essay re-parse in Java).  
- Light root-only evidence contract (`trip_preferences` + `transport_digest` + `stay_digest` + `itinerary_draft`).  
- **OpenRouter planner/worker models** matching incident (`qwen3-35b` / `gpt-4o-mini`) — not Ollama-first.  
- Four fixtures: `budget-nyc-weekend`, `loyalty-points-max`, `fastest-sfo-sea`, `underspecified`.  
- README section for non-experts **and** developer teaching points.  

## Non-Goals

- Real GDS / Airbnb / airline APIs.  
- Payments or actual bookings.  
- Multi-city optimization solvers.  
- Map rendering UI (optional future static page).  
- Visa/immigration advice accuracy.

## Domain story

**Mission:** User describes a trip in plain language. Bifrost returns a proposed itinerary:

- outbound/return transport choices  
- hotel recommendation  
- estimated total  
- why these options fit preferences  
- open questions if under-specified  

## Skill tree (3 levels)

```
planTrip                                   [L1 planning YAML]
├── understandPreferences                  [L2 LLM single-shot]  ← evidence-required
├── planTransport                          [L2 planning YAML]
│   ├── searchFlights                      [L3 Java]
│   ├── searchTrains                       [L3 Java]
│   └── rankTransportOptions               [L3 Java — ranks; LLM picks]
├── planStay                               [L2 planning YAML]
│   ├── searchHotels                       [L3 Java]
│   └── checkLoyaltyPerks                  [L3 Java]
└── assembleItinerary                      [L2 LLM single-shot]  ← synthesize only; evidence-required
```

### Depth notes

- L1 must call `understandPreferences` early; must call transport + stay specialists + `assembleItinerary` (root evidence requires all four).  
- L2 transport **chooses** which searches to run (not always both); should use `rankTransportOptions` when multiple options exist.  
- L2 stay chooses hotel search + optional loyalty perks.  
- L3 never decides preference tradeoffs; they return multi-option catalogs or deterministic ranks.  
- `assembleItinerary` has **no** leaf access — synthesize from digests only.

### Optional extensions (not v1 required)

- `planActivities` mid-level planner + `searchEvents` leaf  
- Weather leaf for packing notes  
- Budget hard-constraint validator Java skill (deferred — soft preference only in v1)  
- Minimal static demo form UI  

## What the LLM is allowed to decide

| Level | Fixed | LLM freedom |
| --- | --- | --- |
| L1 | Specialists only; evidence requires prefs + transport + stay + assemble | Order of planning; when to assemble |
| L2 transport | Search + rank tools only | Which searches to run; which option to prefer after rank |
| L2 stay | Hotel + perks tools only | Which hotel/perks to prefer given prefs |
| L2 understand/assemble | Schemas + prompts | Preference extraction; narrative itinerary; open questions |
| L3 | Fake catalogs + deterministic rank | None |

## Skill inventory (locked)

### Planning skills

| Name | `allowed_skills` | Role | Model |
| --- | --- | --- | --- |
| `planTrip` | `understandPreferences`, `planTransport`, `planStay`, `assembleItinerary` | Root | `qwen3-35b` |
| `planTransport` | `searchFlights`, `searchTrains`, `rankTransportOptions` | Transport specialist | `qwen3-35b` |
| `planStay` | `searchHotels`, `checkLoyaltyPerks` | Lodging specialist | `qwen3-35b` |

### LLM single-shot skills

| Name | Purpose | Model |
| --- | --- | --- |
| `understandPreferences` | origin, destination, dates, budget, party size, priorities, constraints (**mandatory** / evidence-required) | `gpt-4o-mini` |
| `assembleItinerary` | Final structured itinerary + summary (**synthesize only**, no leaf re-search) | `gpt-4o-mini` |

### Java leaves

Service: `TravelCatalogService`.

| Capability | Returns |
| --- | --- |
| `searchFlights` | ≥2 flights: airline, depart, arrive, price (USD), stops; include dominated option |
| `searchTrains` | ≥2 trains: operator, duration, price (USD); include dominated option |
| `searchHotels` | ≥2 hotels: name, nightly rate (USD), rating, neighborhood; optional `partySize` input |
| `checkLoyaltyPerks` | perks for a loyalty tier / hotel chain |
| `rankTransportOptions` | **required** deterministic sort by price or duration (“Java ranks, LLM picks”) |

Leaves key off structured fields (`origin`, `destination`, `date`, `scenario`, optional `partySize`) passed by planners — **not** re-parse the original essay inside Java.

## Models (locked — match incident commander)

| Role | Framework alias | OpenRouter provider model | Skills |
| --- | --- | --- | --- |
| **Planner** | `qwen3-35b` | `qwen/qwen3.6-35b-a3b` | `planTrip`, `planTransport`, `planStay` |
| **Worker** | `gpt-4o-mini` | `openai/gpt-4o-mini` | `understandPreferences`, `assembleItinerary` |

| Layer | Value |
| --- | --- |
| Connection | Existing `openrouter` (`driver: openai`) — **reuse** incident wiring; no new connection keys |
| Credential | `${OPENROUTER_API_KEY:test-openrouter-api-key}` — dummy default for boot/CI; live demos need real key |
| Mapped leaves | Omit `model` |

**Why not Ollama-first / `granite4-tiny`:** Nested transport/stay planning needs a capable planner; same rationale as incident. Ticket originally proposed Ollama-first; **superseded 2026-07-15**.

**CI / tests:** Catalog, controller, and leaf tests must not call the live API. Live smoke is manual with a real `OPENROUTER_API_KEY`.

## Mission input / output

### Root input

```yaml
properties:
  requestText:
    type: string
    description: Natural language trip request.
  scenario:
    type: string
    description: Optional fixture key for catalog inventory.
required: [requestText]
```

### Preference skill output (intermediate)

```yaml
properties:
  origin: { type: string }
  destination: { type: string }
  startDate: { type: string }
  endDate: { type: string }
  budgetTotal: { type: number, nullable: true }
  partySize: { type: integer }
  priorities: { type: array, items: { type: string } }  # cost | speed | comfort | loyalty
  constraints: { type: array, items: { type: string } }
```

### Root output (locked)

```yaml
properties:
  summary: { type: string }
  transport:
    type: object
    properties:
      mode: { type: string }            # flight | train | mixed
      outbound: { type: object }
      returnLeg: { type: object, nullable: true }
  hotel: { type: object, nullable: true }
  estimatedTotal: { type: number }
  rationale: { type: string }
  openQuestions: { type: array, items: { type: string } }
required: [summary, transport, estimatedTotal, rationale, openQuestions]
```

## Canned scenarios / fixtures

| Scenario | Request gist | Catalog bias |
| --- | --- | --- |
| `budget-nyc-weekend` | NYC, $400 all-in, OK with trains | cheap train + hostel/budget hotel |
| `loyalty-points-max` | Prefer hotel chain X, gold tier | perks-heavy hotel even if pricier |
| `fastest-sfo-sea` | Morning meeting, minimize travel time | nonstop flight over cheap multi-stop |
| `underspecified` | “Somewhere warm in March” | model should ask open questions, not invent airports |

Fake catalogs should include **dominated options** (strictly worse) so good choices are visible in the journal.

## Evidence contract (locked — light root-only)

Root `planTrip` requires:

| Evidence type | Produced by |
| --- | --- |
| `trip_preferences` | `understandPreferences` |
| `transport_digest` | `planTransport` |
| `stay_digest` | `planStay` |
| `itinerary_draft` | `assembleItinerary` |

- Mid-level planners have **no** evidence contracts.  
- Root `tool_evidence` must **not** list L3 leaves.  
- Teaching point: evidence is useful even on a “fun” sample — not only ops/compliance.

## HTTP API (locked)

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/travel/plan` | JSON body with `requestText`, optional `scenario` |
| `GET` | `/travel/plan-scenario` | `name=` fixture |
| `GET` | `/travel/scenarios` | list fixtures |

Response: `result`, `sessionId`, `executionJournal`.

## Package / file layout (locked)

```
bifrost-sample/src/main/
  java/.../sample/travel/
    TravelCatalogService.java
    TravelController.java
  resources/skills/travel/
    plan_trip.yml
    understand_preferences.yml
    plan_transport.yml
    plan_stay.yml
    assemble_itinerary.yml
    search_flights.yml
    search_trains.yml
    search_hotels.yml
    check_loyalty_perks.yml
    rank_transport_options.yml
  resources/fixtures/travel/
    budget-nyc-weekend.txt
    loyalty-points-max.txt
    fastest-sfo-sea.txt
    underspecified.txt
```

## Documentation requirements

- Friendly tree diagram.  
- Emphasize “options in / choice out” and **“Java ranks, LLM picks.”**  
- Explicit developer **teaching points** (nested planners, structured handoff, multi-option catalogs, light evidence, planner vs worker models).  
- Show example itinerary JSON.  
- Position this sample as the **approachable HTN training demo** in the gallery.  
- Note limitations (fake data, not real availability).  
- Short 2-minute demo script for talks.

## Tests

- Catalog leaves return ≥2 options per search for default scenarios (including dominated options).  
- Skill registration + planner `allowed_skills` (including `rankTransportOptions` on transport).  
- Root evidence contract shape (L2 only).  
- Controller delegation.  
- Assert `rankTransportOptions` ordering determinism.  
- No live LLM in CI.

## Acceptance criteria

- [ ] Nested planners for transport and stay exist.  
- [ ] Leaves return multi-option catalogs (not a single forced answer); dominated options present.  
- [ ] `rankTransportOptions` is on the transport allow-list and is deterministic.  
- [ ] `understandPreferences` is mandatory / evidence-required; leaves use structured fields.  
- [ ] Light root evidence requires prefs + transport + stay + itinerary digests.  
- [ ] `assembleItinerary` synthesizes only (no leaf re-search).  
- [ ] Root output includes rationale and openQuestions.  
- [ ] Four fixtures documented (`budget-nyc-weekend`, `loyalty-points-max`, `fastest-sfo-sea`, `underspecified`).  
- [ ] Planners use `qwen3-35b`; single-shot workers use `gpt-4o-mini` (OpenRouter; reuse incident connection).  
- [ ] CI / sample boot succeeds without a real OpenRouter key; tests do not call the live API.  
- [ ] README section written for non-experts **and** developers (includes model setup + teaching points).  
- [ ] Journal shows specialist delegation (not one flat mega-plan only).

## Open design questions

**All resolved 2026-07-15.** See Design discussion notes and plan `ai/thoughts/plans/2026-07-15-eng-sample-htn-travel-concierge.md`.

~~1. Currency and timezone?~~ → **USD** prices; ISO-like date/time strings; no timezone conversion logic.  
~~2. Hard budget violations?~~ → **Soft preference only** in v1 (no Java rejector). Hard validator is a follow-up lesson.  
~~3. Date parsing?~~ → LLM `understandPreferences` extracts dates from prose; catalogs use ISO strings.  
~~4. Train vs flight?~~ → **Transport planner chooses** which searches to run (not always both).  
~~5. Party size / rooms?~~ → Optional `partySize` on hotel search; catalog may mildly bias or ignore (teaches structured param pass-through).  
~~6. Evidence contract?~~ → **Light root-only** (prefs + transport + stay + itinerary). Not skip — training value.  
~~7. assembleItinerary leaf access?~~ → **Synthesize only**; no leaf re-search.  
~~8. Demo UI?~~ → **Out of v1**; HTTP + journal is enough.  
~~9. rankTransportOptions?~~ → **Include in v1** (required teaching beat).  
~~10. understandPreferences mandatory?~~ → **Yes**; evidence-required (`trip_preferences`).  
~~11. Primary audience?~~ → **Developer training demo** first; fun domain second.

## Implementation sketch

1. Catalog service with scenario-keyed multi-option inventories + ranker.  
2. Mapped search/perk/rank leaves.  
3. Preference + assemble LLM skills.  
4. Transport/stay planners (transport allow-list includes ranker).  
5. Root planner + light evidence.  
6. HTTP + fixtures + README (teaching points).  
7. Catalog / controller / leaf tests (no live API).  
8. Manual demo script for talks (2-minute path).

## Design discussion notes

- Owner: TBD  
- Reviewers: TBD  
- Implementation plan: `ai/thoughts/plans/2026-07-15-eng-sample-htn-travel-concierge.md`  
- Decisions log (2026-07-15):  
  - **Models:** OpenRouter planner `qwen3-35b` / worker `gpt-4o-mini` (match incident); supersedes original Ollama-first goal. Mapped leaves omit `model`. Reuse existing `openrouter` connection — no new keys.  
  - **Audience:** Primary purpose is **developer training demo** for learning Bifrost; pick options that maximize framework pedagogy over product-perfect travel outcomes.  
  - **`understandPreferences`:** Mandatory first step; root evidence requires `trip_preferences`. Structured fields hand off to leaves (no essay re-parse in Java).  
  - **Evidence:** Light root-only contract requiring prefs + transport + stay + itinerary digests. Mid-level planners have no evidence contracts. Do **not** skip evidence just because the sample is “fun.”  
  - **`rankTransportOptions`:** **Include in v1** under `planTransport` — teaches “Java ranks, LLM picks.”  
  - **Hard budget:** Soft preference only; no Java validator in v1.  
  - **`assembleItinerary`:** Synthesize only; not allowed to call leaves.  
  - **Train vs flight:** Transport planner chooses which searches to run.  
  - **Currency / dates:** USD + ISO-like strings; LLM extracts dates from prose.  
  - **Party size:** Optional on hotel search for structured-param teaching.  
  - **Fixtures:** Four locked keys (`budget-nyc-weekend`, `loyalty-points-max`, `fastest-sfo-sea`, `underspecified`). Dominated options required in catalogs.  
  - **Demo UI:** Out of v1.  
  - **Packaging:** `skills/travel/` + `sample.travel` package; README gallery.  
  - **HTTP:** `/travel/plan`, `/travel/plan-scenario`, `/travel/scenarios`.  
  - **Skill-authoring KB:** No impact (sample-only composition).
