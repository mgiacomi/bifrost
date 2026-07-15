# Sample HTN Incident Commander Testing Plan

## Change Summary

- Add a three-level HTN incident skill tree to `bifrost-sample` (12 YAML skills, Java telemetry leaves, fixtures, OpenRouter planner/worker models, `IncidentController`, README).
- **No framework changes** and **no live LLM calls in CI**.
- Implementation plan: `ai/thoughts/plans/2026-07-14-eng-sample-htn-incident-commander.md`
- Ticket: `ai/thoughts/tickets/eng-sample-htn-incident-commander.md`
- Skill-authoring documentation impact: **No impact** (no authoring-guidance evidence tests required)

## Impacted Areas

| Area | Paths |
| --- | --- |
| Config | `bifrost-sample/src/main/resources/application.yml` (`openrouter`, `qwen3-35b`, `gpt-4o-mini`) |
| Java leaves | `.../sample/incident/IncidentTelemetryService.java` |
| Mapped YAML | `skills/incidents/check_*.yml`, `get_*.yml`, `lookup_runbook.yml` |
| LLM / planning YAML | `handle_incident.yml`, `classify_incident.yml`, `investigate_*.yml`, `draft_incident_response.yml` |
| Fixtures | `classpath:/fixtures/incidents/*.txt` |
| HTTP | `.../sample/incident/IncidentController.java` |
| Docs | `bifrost-sample/README.md` |
| Tests (new) | `.../sample/incident/IncidentSkillCatalogTests.java`, `IncidentControllerTest.java`, `IncidentTelemetryServiceTest.java` |
| Regression surface | Existing sample context boot, invoice/feedstock catalog + controller tests |

**Out of automated test scope (by design):** live nested planning quality, exact report JSON, OpenRouter network success, `max_steps` exhaustion demos.

## Testing Philosophy

This is a sample application, so tests protect only the sample's executable contracts:

- Skill manifests load and the application context starts.
- Controllers invoke the intended public skill names with correct inputs.
- Deterministic Java leaves return stable, neutral data for unknown scenarios.

Tests do not validate LLM quality, exact generated reports, live provider behavior, or framework internals already covered by starter-module tests. Manual OpenRouter smoke testing remains the verification path for nested planning quality.

## Risk Assessment

| Risk | Severity | Mitigation via tests |
| --- | --- | --- |
| Manifests fail catalog load (invalid fields, missing model alias, mapped leaf with schemas) | High | `@SpringBootTest` catalog tests — context must load |
| Wrong public skill names / target IDs (tree cannot nest) | High | Capability + target registry assertions |
| Root evidence contract wrong (L3 probes in `tool_evidence`, both branches required, missing classify/draft) | High | Assert claims + tool_evidence maps on `handleIncident` |
| Mid-level not planning or wrong `allowed_skills` | High | `planningModeExplicitlyEnabled`, `maxSteps`, `allowedSkills` |
| Scenario plumbing broken (fixture not loaded / `scenario` not set) | High | Controller unit tests with input captor |
| Optional `scenario` / nulls blow up `Map.of` | Medium | Controller tests for ticket-only POST body |
| Unknown scenario throws from leaves (demo fragility) | Medium | Leaf unit tests for unknown key |
| Dummy OpenRouter key missing → CI boot fails | High | Context load without env var; assert connection/model wiring |
| Live API accidentally invoked in unit tests | High | Mock `SkillTemplate` for controller; pure unit for leaves; catalog does not invoke skills |
| Regression on existing basics/vision samples | Medium | Keep running full `bifrost-sample` test suite |

### Edge cases to cover

- Unknown scenario name on `handle-scenario` → clear client error (not 500 from missing resource).
- Unknown `scenario` on leaves → neutral valid data, no exception.
- `lookupRunbook` with and without optional `category`.
- Mapped leaves are **not** public under raw method names / only via YAML names.
- Mid-level `evidenceContract` empty; root non-empty with shared `investigation_digest` producers.

## Existing Test Coverage

### Relevant patterns

| Existing test | Pattern to reuse |
| --- | --- |
| `SampleApplicationTests` | `@SpringBootTest(SampleApplication)`, `CapabilityRegistry`, `YamlSkillCatalog`, `BifrostProperties`, internal target vs public capability |
| `SampleControllerTest` | Mock `SkillTemplate`, observer → `SkillExecutionView`, `ArgumentCaptor` for inputs, assert `result` / `sessionId` / `executionJournal` |

### Gaps (pre-PR)

- No incident skills, controller, or telemetry service exist.
- No assertions for nested planning skill graphs, multi-level `allowed_skills`, or OpenRouter connection.
- No leaf scenario-keyed canned data tests.

### Catalog API notes (for implementers)

Use `YamlSkillDefinition` accessors already on the catalog entry:

- `planningModeExplicitlyEnabled()`, `maxSteps(default)`, `allowedSkills()`, `outputSchemaMaxRetries()`
- `evidenceContract().evidenceByClaim()`, `evidenceContract().evidenceByTool()`, `evidenceContract().isEmpty()`
- `requireExecutionConfiguration()` / model alias via execution config or `manifest().getModel()`
- `outputSchema().getRequired()` / properties for locked required fields
- `mappingTargetId()` for mapped leaves; `capabilityRegistry.getCapability(name)` for public identity

## Bug Reproduction / Failing Test First

This is a **feature addition**, not a bugfix. There is no production defect to reproduce.

**Pre-implementation failing tests (TDD-style):** write the three test classes below against the planned public API. Before implementation they fail with missing types, missing skills, or context startup errors. After implementation they pass.

| Order | Write first | Expected pre-impl failure |
| --- | --- | --- |
| 1 | `IncidentTelemetryServiceTest` | Class/methods missing (compile) or wrong canned data |
| 2 | `IncidentControllerTest` | Class missing (compile) |
| 3 | `IncidentSkillCatalogTests` | Context fails or skills null until YAML + config + service land |

Recommended red→green order during implementation:

1. Leaves + leaf tests green  
2. Config + YAML + catalog tests green  
3. Controller + controller tests green  
4. Full module suite + manual smoke  

## Tests to Add/Update

### 1) `IncidentTelemetryServiceTest` — known scenario story data

- **Type:** unit  
- **Location:** `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/incident/IncidentTelemetryServiceTest.java`  
- **What it proves:** For each scenario key, the probes that should carry the story return non-empty, stable, domain-plausible fields (e.g. `network-dns` → DNS failure signal; `app-deploy-regression` → elevated errors + recent deploy; `firewall-block` → deny hits).  
- **Fixtures/data:** none (in-method scenario strings)  
- **Mocks:** none — construct `new IncidentTelemetryService()`  
- **Suggested methods:**
  - `networkDnsScenarioSupportsDnsFailureSignal`
  - `appDeployRegressionSupportsErrorsAndDeploy`
  - `firewallBlockSupportsDenyHits`
  - `ambiguousSlowReturnsMixedSignalsWithoutThrowing`

### 2) `IncidentTelemetryServiceTest` — unknown scenario neutrality

- **Type:** unit  
- **Location:** same class  
- **What it proves:** `scenario=unknown-key-xyz` returns valid neutral structures for all seven methods; no exception.  
- **Fixtures/data:** fixed unknown key  
- **Mocks:** none  
- **Suggested method:** `unknownScenarioReturnsNeutralValidDataForAllProbes`

### 3) `IncidentTelemetryServiceTest` — runbook optional category

- **Type:** unit  
- **Location:** same class  
- **What it proves:** `lookupRunbook(scenario, null)` and `lookupRunbook(scenario, "network")` both return non-blank text without I/O.  
- **Mocks:** none  
- **Suggested method:** `lookupRunbookAcceptsOptionalCategory`

### 4) `IncidentControllerTest` — POST handle delegates with inputs

- **Type:** unit  
- **Location:** `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/incident/IncidentControllerTest.java`  
- **What it proves:** `handle` invokes `skillTemplate.invoke(eq("handleIncident"), inputs, observer)` with `ticketText` and optional `scenario`; response has `result`, `sessionId`, `executionJournal` (no required `filePath`).  
- **Fixtures/data:** inline ticket string  
- **Mocks:** `SkillTemplate` with `doAnswer` observer pattern from `SampleControllerTest`  
- **Suggested methods:**
  - `handleDelegatesToHandleIncidentWithTicketAndScenario`
  - `handleOmitsScenarioWhenNotProvided` (assert captors do not contain null values if using `Map.of`-style builders)

### 5) `IncidentControllerTest` — handle-scenario loads fixture + sets scenario

- **Type:** unit  
- **Location:** same class  
- **What it proves:** `handleScenario("network-dns")` loads `classpath:/fixtures/incidents/network-dns.txt` content into `ticketText`, sets `scenario=network-dns`, invokes `handleIncident`, returns journal envelope.  
- **Fixtures/data:** main-resource fixture (must exist on classpath when test runs)  
- **Mocks:** `SkillTemplate`; real `DefaultResourceLoader`  
- **Suggested method:** `handleScenarioLoadsFixtureAndSetsScenarioKey`

### 6) `IncidentControllerTest` — scenarios list + unknown name

- **Type:** unit  
- **Location:** same class  
- **What it proves:** `scenarios` includes `network-dns`, `app-deploy-regression`, `ambiguous-slow`, `firewall-block` (with short descriptions if exposed). Unknown name fails with a clear exception / 4xx-style error (match controller design).  
- **Mocks:** `SkillTemplate` unused for list; optional for unknown  
- **Suggested methods:**
  - `scenariosListsFourKnownKeys`
  - `handleScenarioRejectsUnknownName`

### 7) `IncidentSkillCatalogTests` — all skills + targets registered

- **Type:** integration (Spring context, no web, no LLM invoke)  
- **Location:** `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/incident/IncidentSkillCatalogTests.java`  
- **What it proves:** All 12 public YAML names exist on `CapabilityRegistry`; seven `incidentTelemetryService#...` targets exist; raw method names / target IDs are **not** public capabilities; mapped leaves have `skillExecution().configured() == false`.  
- **Fixtures/data:** full sample `application.yml` + skills on classpath  
- **Mocks:** none  
- **Suggested method:** `registersIncidentPublicSkillsAndKeepsTargetsInternal`

### 8) `IncidentSkillCatalogTests` — nested planning graph shape

- **Type:** integration  
- **Location:** same class  
- **What it proves:**
  - `handleIncident`: planning true, `max_steps` 10, `allowed_skills` exactly the five specialists (incl. `lookupRunbook`), model `qwen3-35b`, `output_schema_max_retries` 2
  - `investigateNetwork` / `investigateApp`: planning true, `max_steps` 6, correct probe `allowed_skills`, **empty** evidence contract, model `qwen3-35b`, retries 2
  - `classifyIncident` / `draftIncidentResponse`: not planning (or not explicitly true), retries 2, model `gpt-4o-mini`
- **Suggested methods:**
  - `rootPlannerHasLockedAllowedSkillsAndMaxSteps`
  - `midLevelPlannersHaveProbeAllowListsAndNoEvidenceContract`
  - `singleShotSkillsUseWorkerAliasWithoutPlanning`

### 9) `IncidentSkillCatalogTests` — root evidence contract shape

- **Type:** integration  
- **Location:** same class  
- **What it proves:** Root `evidenceContract` matches locked ticket shape:
  - Claims: `severity`/`category` → `incident_classification`; `likelyCause` → both classification + digest; `evidenceSummary`/`recommendedAction` → digest; `userMessage` → `response_draft`
  - Tool evidence: `classifyIncident`, `investigateNetwork`, `investigateApp`, `draftIncidentResponse` only (no L3 probes; no `lookupRunbook`)
  - Both investigate tools produce `investigation_digest` (shared type; either branch sufficient)
- **Suggested method:** `rootEvidenceContractMatchesLockedShape`

### 10) `IncidentSkillCatalogTests` — locked I/O required fields

- **Type:** integration  
- **Location:** same class  
- **What it proves:** Catalog definitions expose required output fields for root / classify / mid / draft per ticket (and root/classify/mid/draft input required fields where declared).  
- **Suggested method:** `llmBackedIncidentSkillsExposeLockedRequiredSchemaFields`

### 11) `IncidentSkillCatalogTests` — OpenRouter + model alias config

- **Type:** integration  
- **Location:** same class  
- **What it proves:** `bifrost.connections.openrouter` is OpenAI driver with OpenRouter base URL; both `bifrost.models.qwen3-35b` and `bifrost.models.gpt-4o-mini` use `connection: openrouter` with provider models `qwen/qwen3.6-35b-a3b` and `openai/gpt-4o-mini`; context loads **without** `OPENROUTER_API_KEY` env (dummy default).  
- **Suggested method:** `openRouterConnectionAndPlannerWorkerAliasesAreWired`

### 12) Existing suite — regression

- **Type:** existing unit/integration  
- **Location:** `SampleApplicationTests`, `SampleControllerTest` (unchanged unless a conflict appears)  
- **What it proves:** Invoice/feedstock/expense wiring still works after new skills and config.  
- **Action:** run full module suite; do not weaken existing assertions.

### Explicitly not adding (v1)

| Test | Reason |
| --- | --- |
| Live OpenRouter e2e | Cost, flakiness, key requirement; manual smoke only |
| Golden-file report JSON | Non-deterministic LLM |
| Nested evidence isolation framework test | Already in starter (`CapabilityExecutionRouterTest`); sample does not re-prove framework |
| `@WebMvcTest` HTTP layer | Controller unit tests sufficient; optional later |
| Skill-authoring KB tests | Impact = No impact |

## How to Run

### Automated (CI / local, no API key)

```powershell
# From repo root — sample module only (preferred during implementation)
.\mvnw.cmd -pl bifrost-sample -am test

# Or sample tests only if starter already installed
.\mvnw.cmd -pl bifrost-sample test

# Focused classes
.\mvnw.cmd -pl bifrost-sample -Dtest=IncidentTelemetryServiceTest,IncidentControllerTest,IncidentSkillCatalogTests test
```

**Env:** none required. Do **not** set a fake network-dependent key that points at a real endpoint for unit tests.

### Manual smoke (optional for PR merge if key available; required for ticket acceptance)

```powershell
$env:OPENROUTER_API_KEY = "sk-or-..."   # real key
.\mvnw.cmd -pl bifrost-sample spring-boot:run
# separate shell:
Invoke-RestMethod http://localhost:8081/incidents/scenarios
Invoke-RestMethod "http://localhost:8081/incidents/handle-scenario?name=network-dns"
Invoke-RestMethod "http://localhost:8081/incidents/handle-scenario?name=app-deploy-regression"
```

**Soft checks (not automated):** nested MISSION frames in journal; ≥1 mid-level planner; report fields present; branch bias roughly matches scenario.

### Compile-only checkpoint

```powershell
.\mvnw.cmd -pl bifrost-sample -am test-compile
```

## Exit Criteria

### Automated

- [ ] New tests exist under `.../sample/incident/` for catalog, controller, and leaves
- [ ] Pre-impl: new tests fail (compile or assertion) until corresponding code lands; post-impl: all pass
- [ ] `.\mvnw.cmd -pl bifrost-sample test` passes with **no** `OPENROUTER_API_KEY` set
- [ ] No test performs live HTTP to OpenRouter or Ollama for incident skills
- [ ] Catalog tests cover: 12 skills, target isolation, planning graph, root evidence shape, model alias, OpenRouter wiring, locked required schema fields
- [ ] Controller tests cover: POST handle inputs, handle-scenario fixture+scenario, scenarios list, unknown scenario rejection, journal envelope
- [ ] Leaf tests cover: known scenario story data, unknown neutrality, optional runbook category
- [ ] Existing `SampleApplicationTests` + `SampleControllerTest` still pass
- [ ] Skill-authoring evidence tests: **N/A** (documentation impact = No impact)

### Manual (ticket / demo acceptance)

- [ ] Boot without real OpenRouter key succeeds
- [ ] With real key: `network-dns` and `app-deploy-regression` smoke complete
- [ ] Journal shows nested planning frames; report fields populated
- [ ] README examples match real endpoints and config keys

### PR readiness bar

Merge-ready for CI when **automated** exit criteria pass. Manual smoke may lag if no key is available, but ticket acceptance remains incomplete until smoke is done.

## Traceability

| Ticket acceptance item | Covered by |
| --- | --- |
| Nested planning path exists (structure) | Catalog mid-level + root planning flags / allowed_skills |
| Leaves Java-mapped, minimal manifests | Catalog targets + mapped capability execution not configured |
| Locked I/O schemas | Catalog required field assertions |
| Root evidence contract; not both branches required | Catalog evidence shape (shared digest producers) |
| Fixtures + branch bias documented | Fixtures on classpath; bias is manual/README (not golden tests) |
| HTTP result + sessionId + journal | Controller unit tests |
| scenarios + handle-scenario | Controller unit tests |
| scenario on leaves / fixture sets it | Leaf + controller tests |
| OpenRouter dummy key + planner/worker aliases | Catalog config tests + full suite without env |
| Planners use `qwen3-35b`; workers use `gpt-4o-mini` | Catalog model assertions |
| Mid-level retries = 2 | Catalog assertions |
| CI without live API | All automated tests |
| Naming consistency | Catalog public names + controller skill name |

## References

- Implementation plan: `ai/thoughts/plans/2026-07-14-eng-sample-htn-incident-commander.md`
- Ticket: `ai/thoughts/tickets/eng-sample-htn-incident-commander.md`
- Existing tests: `SampleApplicationTests.java`, `SampleControllerTest.java`
- Catalog API: `YamlSkillDefinition`, `EvidenceContract`
- Framework nested isolation (do not re-test in sample): `CapabilityExecutionRouterTest`
