# Ticket: Sample HTN Skill Tree — Travel Concierge

**Status:** Design  
**Priority:** P3  
**Module:** `bifrost-sample`  
**Related tickets:**  
- `eng-sample-htn-incident-commander.md`  
- `eng-sample-htn-support-case-resolver.md`  
- `eng-sample-htn-insurance-claim-intake.md`  
**Depends on:** Nested planning (existing); optional attachment inputs if we later accept screenshots of confirmations (not required for v1)  

---

## Summary

Add a **fun, approachable three-level HTN skill tree** that plans a short trip from natural-language preferences. Root planning skill decomposes into transport and lodging specialists; leaves return fake flights, trains, hotels, and loyalty perks; a final skill assembles an itinerary.

This sample prioritizes **explainability and delight** for demos/talks. It is intentionally less “enterprise evidence” and more “LLM chooses among ranked options under constraints.”

## Motivation

Not every Bifrost audience lives in ops or insurance. A travel concierge:

- Makes HTN decomposition intuitive (trip → transport + stay → bookable options).  
- Shows preference tradeoffs (cheap vs fast vs loyalty) that rules engines handle poorly.  
- Produces a tangible artifact (day-by-day itinerary) people enjoy reading.  
- Still exercises nested planning, `allowed_skills`, schemas, and journals.

It is a **gateway sample**: lower cognitive load than claims/incidents, still a real tree.

## Goals

- Three-level skill stack with nested planners for transport and stay.  
- Fake inventory leaves with multiple options so the LLM must choose.  
- Structured itinerary output (legs, hotel, rough cost, rationale).  
- **OpenRouter planner/worker models** matching incident (`qwen3-35b` / `gpt-4o-mini`) — not Ollama-first.  
- Fixtures: budget trip, loyalty-maximizing trip, ambiguous “romantic weekend,” family constraints.  
- README section that non-engineers can follow.  

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
├── understandPreferences                  [L2 LLM single-shot YAML]
├── planTransport                          [L2 planning YAML]
│   ├── searchFlights                      [L3 Java]
│   ├── searchTrains                       [L3 Java]
│   └── rankTransportOptions               [L3 Java optional helper]
├── planStay                               [L2 planning YAML]
│   ├── searchHotels                       [L3 Java]
│   └── checkLoyaltyPerks                  [L3 Java]
└── assembleItinerary                      [L2 LLM single-shot YAML]
```

### Optional extensions (not v1 required)

- `planActivities` mid-level planner + `searchEvents` leaf  
- Weather leaf for packing notes  
- Budget hard-constraint validator Java skill  

## What the LLM is allowed to decide

| Level | Fixed | LLM freedom |
| --- | --- | --- |
| L1 | Specialists only | Whether trains vs flights matter; order of planning; when to assemble |
| L2 transport/stay | Search tools only | Which searches to run; which option to prefer given prefs |
| L2 understand/assemble | Schemas + prompts | Preference extraction; narrative itinerary |
| L3 | Fake catalogs | None — return option lists with attributes |

## Skill inventory (proposed)

### Planning skills

| Name | `allowed_skills` | Role | Model |
| --- | --- | --- | --- |
| `planTrip` | `understandPreferences`, `planTransport`, `planStay`, `assembleItinerary` | Root | `qwen3-35b` |
| `planTransport` | `searchFlights`, `searchTrains` (+ optional ranker) | Transport specialist | `qwen3-35b` |
| `planStay` | `searchHotels`, `checkLoyaltyPerks` | Lodging specialist | `qwen3-35b` |

### LLM single-shot skills

| Name | Purpose | Model |
| --- | --- | --- |
| `understandPreferences` | origin, destination, dates, budget, party size, priorities, constraints | `gpt-4o-mini` |
| `assembleItinerary` | Final structured itinerary + human-readable summary | `gpt-4o-mini` |

### Java leaves

Suggested service: `TravelCatalogService`.

| Capability | Returns |
| --- | --- |
| `searchFlights` | list of flights: airline, depart, arrive, price, stops |
| `searchTrains` | list of trains: operator, duration, price |
| `searchHotels` | list of hotels: name, nightly rate, rating, neighborhood |
| `checkLoyaltyPerks` | perks for a loyalty tier / hotel chain |
| `rankTransportOptions` | optional deterministic sort by price or duration for teaching “Java ranks, LLM picks” |

Leaves should key off structured fields (`origin`, `destination`, `date`, `scenario`) passed by planners—not re-parse the original essay inside Java.

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

### Root output (proposed)

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

## HTTP API (proposed)

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/travel/plan` | JSON body with `requestText`, optional `scenario` |
| `GET` | `/travel/plan-scenario` | `name=` fixture |
| `GET` | `/travel/scenarios` | list fixtures |

Response: `result`, `sessionId`, `executionJournal`.

## Package / file layout (proposed)

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
  resources/fixtures/travel/
    budget-nyc-weekend.txt
    loyalty-points-max.txt
    ...
```

## Documentation requirements

- Friendly tree diagram.  
- Emphasize “options in / choice out” teaching point.  
- Show example itinerary JSON.  
- Position this sample as the **approachable HTN demo** in the gallery.  
- Note limitations (fake data, not real availability).

## Tests

- Catalog leaves return ≥2 options per search for default scenarios.  
- Skill registration + planner `allowed_skills`.  
- Controller delegation.  
- Optional: assert `rankTransportOptions` ordering determinism.

## Acceptance criteria

- [ ] Nested planners for transport and stay exist.  
- [ ] Leaves return multi-option catalogs (not a single forced answer).  
- [ ] Root output includes rationale and openQuestions.  
- [ ] At least three fixtures documented.  
- [ ] Planners use `qwen3-35b`; single-shot workers use `gpt-4o-mini` (OpenRouter; reuse incident connection).  
- [ ] CI / sample boot succeeds without a real OpenRouter key; tests do not call the live API.  
- [ ] README section written for a non-expert reader (includes model setup).  
- [ ] Journal shows specialist delegation (not one flat mega-plan only).

## Open design questions

1. **Currency and timezone** handling in fake data?  
2. **Hard budget violations:** should a Java validator skill reject over-budget plans, or only LLM soft preference?  
3. **Date parsing:** rely on LLM preference skill vs require ISO dates in fixtures?  
4. **Train vs flight:** always search both, or let transport planner choose searches? (Prefer let planner choose.)  
5. **Party size / rooms:** model in hotel search inputs?  
6. **Evidence contract:** light-touch or skip for this “fun” sample?  
7. **Should assembleItinerary be allowed to call leaves** if data missing, or only synthesize?  
8. **Demo UI:** worth a minimal static form later?

## Implementation sketch (after design lock)

1. Catalog service with scenario-keyed inventories.  
2. Mapped search/perk leaves.  
3. Preference + assemble LLM skills.  
4. Transport/stay planners.  
5. Root planner.  
6. HTTP + fixtures + README.  
7. Manual demo script for talks (2-minute path).

## Design discussion notes

_(Use this section during ticket review.)_

- Owner: TBD  
- Reviewers: TBD  
- Decisions log:  
  - **2026-07-15:** Models locked to match incident commander — shared OpenRouter connection; planner `qwen3-35b` → `qwen/qwen3.6-35b-a3b`; worker `gpt-4o-mini` → `openai/gpt-4o-mini`. Supersedes original Ollama-first goal. Mapped leaves omit `model`. Other design questions still open.
