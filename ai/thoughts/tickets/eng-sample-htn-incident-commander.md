# Ticket: Sample HTN Skill Tree — IT Incident Commander

**Status:** Design (decisions locked 2026-07-14; model selection locked 2026-07-14)  
**Priority:** P1 (recommended first nested-planning sample)  
**Module:** `bifrost-sample`  
**Related tickets:**  
- `eng-sample-htn-skill-tree-gallery.md` (epic index)  
- `eng-sample-htn-support-case-resolver.md`  
- `eng-sample-htn-travel-concierge.md`  
- `eng-sample-htn-insurance-claim-intake.md`  
**Depends on:** Nested `planning_mode` skills + `allowed_skills` (already supported). Mapped YAML leaves already use minimal manifests (`name` / `description` / `mapping` only) as in `expenseLookup`. Named AI connections are already in the framework.  

---

## Summary

Add a **three-level HTN skill tree** to `bifrost-sample` that models a fake IT incident response workflow. The root skill plans and delegates; mid-level skills also plan (network vs app investigation); leaves are deterministic Java `@SkillMethod`s returning canned telemetry.

This is the primary sample for showing Bifrost as an **LLM-in-the-loop HTN**: structure and tool visibility are fixed in YAML; branch selection, probe ordering, and "enough evidence" judgments are left to the model.

## Motivation

Today's sample planning depth is effectively **two levels**:

```
duplicateInvoiceChecker (planning)
├── invoiceParser (LLM leaf)
└── expenseLookup (Java leaf)
```

That proves planning + tools, but not **nested planners** (a mid-level skill that itself runs `planning_mode: true`). Nested planning is the clearest demo of Bifrost's product claim: HTN trees without brittle rule DSLs.

Incident response is a natural domain:

- Free-text tickets are ambiguous (LLM judgment).
- Investigation branches are discrete (HTN methods).
- Telemetry probes can be pure fake Java (no paid API required for leaves).
- Execution journals read like a real ops story.

## Goals

- Demonstrate **skill-stack depth ≥ 3**: root planner → mid planner → leaf.
- Run nested planning against a **capable** model (not a tiny local model); default sample path uses OpenRouter.
- Make the tree **inspectable** via `sessionId` + `executionJournal` (and later `bifrost-cli`).
- Provide **canned scenarios** with documented expected *branches* (not brittle exact JSON).
- Document the sample in `bifrost-sample/README.md` (or a dedicated walkthrough section).
- Prefer **clear naming**: HTTP path, YAML skill `name`, and Java capability names tell one story.
- Explicitly showcase core framework features (see [Framework features this sample showcases](#framework-features-this-sample-showcases)).
- Lock **input/output contracts** for every LLM-backed skill so nested handoffs are explicit.

## Non-Goals

- Real monitoring integrations (Prometheus, PagerDuty, etc.).
- Perfect LLM determinism or golden-file exact outputs.
- Framework changes (unless a gap is discovered during implementation).
- Replacing existing invoice/feedstock samples.
- Production-grade runbooks or security scanning.
- Attachments, RBAC, multi-provider routing, or regex linters (already covered by invoice/feedstock samples).
- Exhausting `max_steps` / depth limits as a first-class demo scenario (document limits in README only for v1).
- Proving that tiny local models can execute multi-level planning trees (they remain useful for shallow samples like invoice parse).

## Framework features this sample showcases

| Feature | How this sample uses it |
| --- | --- |
| Nested `planning_mode` | Root + `investigateNetwork` / `investigateApp` each run the step-loop engine |
| `allowed_skills` governance | Each planner only sees its specialist / probe set |
| YAML skill names as tools | Leaves are mapped YAML skills so they appear in `allowed_skills` |
| `@SkillMethod` + `mapping.target_id` | Deterministic telemetry / runbook leaves |
| `input_schema` | Every LLM-backed skill declares an explicit contract; mapped leaves inherit reflected Java contracts |
| `output_schema` (+ retries) | Structured classify / investigate digests / draft / root report |
| Light `evidence_contract` | Root only; shared `investigation_digest` so either branch can satisfy investigation claims |
| `SkillTemplate` observer | HTTP returns `sessionId` + `executionJournal` |
| Nested mission isolation | Parent plan/evidence snapshotted while child planner runs (visible in journal frames) |
| `prompt` vs `description` | Planners get private runbook-style prompts; public descriptions stay short |
| `max_steps` | Tighter mid-level budgets teach step limits without a failure fixture |
| Named AI connections | Shared OpenRouter connection; planner alias `qwen3-35b` + worker alias `gpt-4o-mini` |

## Domain story

**Mission:** Given a free-text incident ticket, produce a structured incident report:

- severity (`SEV1`–`SEV4`)
- category (`network` / `application` / `mixed` / `unknown`)
- likely cause
- evidence summary
- recommended next action
- user-facing status blurb

The agent should **not** run every probe every time. It should classify, choose investigation branch(es), gather just enough evidence, then synthesize.

## Skill tree (3 levels)

```
handleIncident                          [L1 planning YAML]
├── classifyIncident                    [L2 LLM single-shot YAML]
├── investigateNetwork                  [L2 planning YAML]
│   ├── checkDns                        [L3 Java leaf]
│   ├── checkLatency                    [L3 Java leaf]
│   └── checkFirewallRules              [L3 Java leaf]
├── investigateApp                      [L2 planning YAML]
│   ├── getErrorRate                    [L3 Java leaf]
│   ├── getRecentDeploys                [L3 Java leaf]
│   └── getServiceHealth                [L3 Java leaf]
├── draftIncidentResponse               [L2 LLM single-shot YAML]
└── lookupRunbook                       [L3 Java leaf]  (optional; root may call before/with draft)
```

**v1 lock:** `draftIncidentResponse` is **single-shot** (not a third planner). Nested planning is concentrated on `investigate*`. Root may call `lookupRunbook` itself and pass digests into draft, or omit runbook when investigation text is enough.

### Depth definition (for docs)

- **Level 1:** Root mission skill (`handleIncident`) — HTN method selection across specialists.
- **Level 2:** Specialist skills — either planning (`investigate*`) or structured LLM (`classify*`, `draft*`).
- **Level 3:** Deterministic capabilities — fake systems of record.

This is **skill-stack depth**, not merely "three tasks in one plan."

## What the LLM is allowed to decide

| Level | Fixed by humans (YAML/Java) | LLM freedom |
| --- | --- | --- |
| L1 `handleIncident` | May only call listed specialists; plan must cover root evidence (see below) | Order; which investigation branch(es); when to draft; optional runbook |
| L2 `investigateNetwork` / `investigateApp` | May only call listed probes; structured digest output | Which probes; when evidence is enough |
| L2 `classifyIncident` / `draftIncidentResponse` | Schemas + prompts | Interpretation and wording |
| L3 leaves | Return canned data for `scenario` | None |

## Skill inventory

### Planning skills (`planning_mode: true`)

| Name | `allowed_skills` | Role |
| --- | --- | --- |
| `handleIncident` | `classifyIncident`, `investigateNetwork`, `investigateApp`, `draftIncidentResponse`, `lookupRunbook` | Root mission |
| `investigateNetwork` | `checkDns`, `checkLatency`, `checkFirewallRules` | Network specialist |
| `investigateApp` | `getErrorRate`, `getRecentDeploys`, `getServiceHealth` | App specialist |

Locked settings:

- **`max_steps`:** root `10`; mid-level `6` (tight enough to show budgets; engine default is 10).
- **`model`:** planners use framework alias `qwen3-35b`; single-shot workers use `gpt-4o-mini` (both OpenRouter-backed). See [Model selection](#model-selection).
- **`prompt`:** private planning instructions on root + mid-level (forward `scenario` on every tool call; prefer classify early; do not call every investigation branch; stop when enough evidence; pass structured digests into draft).
- **`evidence_contract`:** locked light contract on root only; **none** on mid-level — see [Evidence contract rules](#evidence-contract-rules).
- **`input_schema` / `output_schema`:** locked for all three planners — see [Mission input / output](#mission-input--output).

### LLM single-shot skills

| Name | Role | Contracts |
| --- | --- | --- |
| `classifyIncident` | Interpret ticket into severity + category | Full I/O locked below |
| `draftIncidentResponse` | Synthesize user-facing report pieces from digests | Full I/O locked below |

Both use direct mission execution (`planning_mode` omitted or `false`). Both declare `output_schema` with `output_schema_max_retries: 2`.

### Java leaves (`@SkillMethod` + mapped YAML)

Implement in `com.lokiscale.bifrost.sample.incident.IncidentTelemetryService` (single service is fine for v1).

| Capability (YAML skill name) | Java target (illustrative) | Fake behavior |
| --- | --- | --- |
| `checkDns` | `incidentTelemetryService#checkDns` | Resolve status keyed by `scenario` |
| `checkLatency` | `incidentTelemetryService#checkLatency` | p50/p95 latency + region |
| `checkFirewallRules` | `incidentTelemetryService#checkFirewallRules` | Recent deny hits |
| `getErrorRate` | `incidentTelemetryService#getErrorRate` | 5xx rate + window |
| `getRecentDeploys` | `incidentTelemetryService#getRecentDeploys` | Service, version, timestamp |
| `getServiceHealth` | `incidentTelemetryService#getServiceHealth` | UP/DEGRADED/DOWN |
| `lookupRunbook` | `incidentTelemetryService#lookupRunbook` | Short text runbook by category / scenario |

Every leaf YAML must:

- Use `mapping.target_id` to the Java method.
- Contain only `name`, `description`, and `mapping` (optional `rbac_roles` only if needed later).
- Omit `input_schema` / `output_schema` / model / planning / evidence fields so the Java target remains the single contract source.
- Not perform external network calls.

Every Java leaf method must:

- Accept **`scenario`** (and for `lookupRunbook`, also optional `category`) with reflected parameter name, type, description, and requiredness that match the intended public contract.
- Use distinct method names within the bean (target id uniqueness).
- Return stable canned structures keyed by `scenario` (unknown key → neutral/empty-but-valid data, not exceptions, so demos degrade gracefully).

**Scenario plumbing (locked):** Bifrost has no free-form session bag for demo keys. Pass `scenario` explicitly on root input and instruct planners (via `prompt`) to forward it on every tool call. Prefer `GET /incidents/handle-scenario?name=...` for live demos so the model does not invent the key.

## Evidence contract rules

Framework behavior that must drive the sample design:

1. Claim evidence lists are **AND-all** (every listed evidence type for a present claim must be gathered).
2. Nested YAML missions **snapshot/restore** parent evidence; **leaf evidence does not bubble** to the parent ledger.
3. Parent `tool_evidence` keys are the **tools the parent actually invokes** (L2 specialists), not L3 probes inside a child planner.
4. Plan coverage evaluates **every** claim in the contract before accepting a plan (it does not infer that a required output field might be omitted later).
5. Multiple tools may produce the same evidence type; **one successful producer is enough**. The planning prompt may list all producers and sound like "call every branch" — the validator still accepts either; document this in README.

**v1 lock — light root contract (required, not optional):**

Because root `output_schema` marks all report fields `required`, the evidence contract below makes a successful mission **plan-require**:

| Evidence type | Produced by | Effect on plan |
| --- | --- | --- |
| `incident_classification` | `classifyIncident` | Plan must include classify |
| `investigation_digest` | `investigateNetwork` **or** `investigateApp` | Plan must include **at least one** investigation specialist |
| `response_draft` | `draftIncidentResponse` | Plan must include draft |

This is **not** soft orchestration via prompt alone. Prompt still steers order ("classify early") and selective branching; evidence enforces supportability of required claims. Orchestration remains model-driven (no Java hardcoding of call order).

- Mid-level: **no** `evidence_contract` in v1 (probe selection stays free).
- `lookupRunbook` is intentionally **not** in the contract (optional enrichment).
- Do **not** require both investigation branches for the same claim set.
- Document nested isolation in README so readers understand why root `tool_evidence` names L2 skills.

**Locked root evidence contract:**

```yaml
# handleIncident
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

## Mission input / output

### Root — `handleIncident` (locked)

```yaml
input_schema:
  type: object
  properties:
    ticketText:
      type: string
      description: Free-text incident report from a human or alert system.
    scenario:
      type: string
      description: Optional fixture key for deterministic leaf data (e.g. network-dns, app-deploy-regression, ambiguous-slow).
  required: [ticketText]
  additionalProperties: false

output_schema:
  type: object
  properties:
    severity:
      type: string
      description: SEV1 (highest) through SEV4 (lowest)
      enum: [SEV1, SEV2, SEV3, SEV4]
    category:
      type: string
      enum: [network, application, mixed, unknown]
    likelyCause: { type: string }
    evidenceSummary: { type: string }
    recommendedAction: { type: string }
    userMessage: { type: string }
  required: [severity, category, likelyCause, evidenceSummary, recommendedAction, userMessage]
  additionalProperties: false
```

Root prompt guidance (private `prompt`, not public `description`):

1. Prefer calling `classifyIncident` early with `ticketText` (and `scenario` if present).
2. Choose investigation branch(es) from classification + ticket; do not call both specialists unless the ticket is genuinely mixed/ambiguous.
3. Forward `scenario` on every tool call that accepts it.
4. Optionally call `lookupRunbook` with category/scenario; pass any runbook text into draft.
5. Call `draftIncidentResponse` with ticket text plus investigation digest(s) (and optional runbook text).
6. Emit the root report fields from classification + investigation + draft results.

### Classify — `classifyIncident` (locked)

```yaml
# planning_mode: false (or omit)
input_schema:
  type: object
  properties:
    ticketText:
      type: string
      description: Free-text incident report to classify.
    scenario:
      type: string
      description: Optional fixture key; may hint expected domain but classification must still read the ticket.
  required: [ticketText]
  additionalProperties: false

output_schema:
  type: object
  properties:
    severity:
      type: string
      enum: [SEV1, SEV2, SEV3, SEV4]
    category:
      type: string
      enum: [network, application, mixed, unknown]
    rationale:
      type: string
      description: Short explanation of severity and category choice.
  required: [severity, category, rationale]
  additionalProperties: false
```

### Mid-level investigation planners (locked)

Both `investigateNetwork` and `investigateApp` share the same I/O shape. They differ only in `description`, `prompt`, `allowed_skills`, and which leaf data they can gather.

```yaml
planning_mode: true
max_steps: 6
output_schema_max_retries: 2

input_schema:
  type: object
  properties:
    ticketText:
      type: string
      description: Original incident ticket text.
    scenario:
      type: string
      description: Fixture key to forward to every probe call.
    classificationSummary:
      type: string
      description: Optional short digest from classifyIncident (severity/category/rationale).
  required: [ticketText]
  additionalProperties: false

output_schema:
  type: object
  properties:
    domain:
      type: string
      enum: [network, application]
      description: Which specialist produced this digest.
    summary:
      type: string
      description: One-paragraph investigation summary for the parent / draft skill.
    findings:
      type: array
      items:
        type: object
        properties:
          probe:
            type: string
            description: Probe skill name that produced the finding (e.g. checkDns).
          observation:
            type: string
            description: Short factual observation from the probe result.
        required: [probe, observation]
        additionalProperties: false
    probesUsed:
      type: array
      items: { type: string }
      description: Ordered list of probe skill names actually invoked.
    confidence:
      type: string
      enum: [low, medium, high]
  required: [domain, summary, findings, probesUsed, confidence]
  additionalProperties: false
```

Mid-level prompt guidance:

- Forward `scenario` to every probe.
- Call only the probes needed for this ticket; stop when `summary` can be supported.
- Do not invent telemetry; base findings on probe results.
- Set `domain` to `network` or `application` to match the skill.
- `findings` must reflect probes actually run (do not invent empty high-confidence digests).

### Draft — `draftIncidentResponse` (locked)

```yaml
# planning_mode: false (or omit)
input_schema:
  type: object
  properties:
    ticketText:
      type: string
      description: Original incident ticket text.
    classificationSummary:
      type: string
      description: Short classify digest (severity, category, rationale).
    investigationSummary:
      type: string
      description: Concatenated or single investigation specialist summary/findings.
    runbookText:
      type: string
      description: Optional runbook excerpt from lookupRunbook.
  required: [ticketText, investigationSummary]
  additionalProperties: false

output_schema:
  type: object
  properties:
    summary:
      type: string
      description: Internal ops summary of the incident state.
    likelyCause:
      type: string
    recommendedAction:
      type: string
    userMessage:
      type: string
      description: Short status blurb suitable for an end user or ticket comment.
  required: [summary, likelyCause, recommendedAction, userMessage]
  additionalProperties: false
```

Root may compose `investigationSummary` from one or both specialist digests (e.g. JSON stringified or plain-text concatenation of `summary` + findings). Exact composition is prompt-guided, not schema-enforced.

## Model selection

Nested planning needs reliable tool use, plan coverage, and selective branching. Single-shot workers need solid schema adherence at lower cost. Tiny local models are fine for shallow extract samples; they are **not** the target for this tree.

**Sample convention (locked):** one shared OpenRouter connection; **planner** vs **worker** framework aliases.

| Role | Framework alias | OpenRouter provider model | Used by |
| --- | --- | --- | --- |
| **Planner** | `qwen3-35b` | `qwen/qwen3.6-35b-a3b` | `handleIncident`, `investigateNetwork`, `investigateApp` |
| **Worker** | `gpt-4o-mini` | `openai/gpt-4o-mini` | `classifyIncident`, `draftIncidentResponse` |

| Layer | Value |
| --- | --- |
| Connection | `openrouter` (`driver: openai`) — **both** aliases share this connection |
| Credential | `${OPENROUTER_API_KEY:test-openrouter-api-key}` — dummy default so sample boot and CI work without a real key; live demos require a real key |
| Mapped leaves | Omit `model` |

Sample `application.yml` additions (illustrative):

```yaml
bifrost:
  connections:
    openrouter:
      driver: openai
      base-url: https://openrouter.ai/api/v1
      api-key: ${OPENROUTER_API_KEY:test-openrouter-api-key}
      # optional OpenRouter attribution headers if desired:
      # headers:
      #   HTTP-Referer: ${OPENROUTER_SITE_URL}
  models:
    qwen3-35b:
      connection: openrouter
      provider-model: qwen/qwen3.6-35b-a3b
    gpt-4o-mini:
      connection: openrouter
      provider-model: openai/gpt-4o-mini
```

Planning YAML skills set `model: qwen3-35b`. Single-shot classify/draft set `model: gpt-4o-mini`. Mapped leaves omit `model`.

**Why this split:** Qwen 3.6 35B is the sample's main planner for multi-step tool calling and HTN branch selection; GPT-4o-mini is the cheaper worker for structured single-shot classify/draft. Both route through OpenRouter so demos need one API key. Confirm OpenRouter slugs at implement time; keep the framework aliases stable if a slug changes.

**CI / tests:** OpenAI-driver connections require a nonblank api-key at startup. The dummy default keeps `@SpringBootTest` and local boot working without OpenRouter. Catalog, controller, and leaf tests must not call the live API. Live smoke remains a manual/local demo step with a real `OPENROUTER_API_KEY`.

**Local override:** operators MAY retarget either alias to another connection later; the sample default is OpenRouter for both. Do not default this tree to `granite4-tiny`.

## Canned scenarios / fixtures

Place under `src/main/resources/fixtures/incidents/` for live demos and mirror under `src/test/resources/fixtures/incidents/` if tests need them.

| Scenario key | Ticket gist | Expected branch bias |
| --- | --- | --- |
| `network-dns` | EU users cannot resolve `api.example.com` | network → DNS |
| `app-deploy-regression` | Checkout 500s after 14:02 deploy | app → deploys + errors |
| `ambiguous-slow` | Everything intermittent, no deploy today | mixed or latency + health; model judgment |
| `firewall-block` | VPN works; internal wiki blank after rule change | network → firewall |

Leaf data should **support** the story (and occasionally conflict slightly so mid-level judgment matters).

Example conflict: high error rate but empty deploys → "not a deploy regression."

## HTTP API (locked)

New controller: `com.lokiscale.bifrost.sample.incident.IncidentController` (do **not** grow `SampleController`).

| Method | Path | Behavior |
| --- | --- | --- |
| `POST` | `/incidents/handle` | JSON body: `ticketText` + optional `scenario` |
| `GET` | `/incidents/scenarios` | List fixture keys + short descriptions |
| `GET` | `/incidents/handle-scenario?name=...` | Load fixture text + set `scenario`; preferred live demo path |

Optional: `GET /incidents/handle?text=...&scenario=...` only if useful for curl one-liners; POST remains the primary free-text API.

Response envelope (match existing observer pattern; no `filePath` unless a fixture path is useful):

```json
{
  "result": "{ ... structured report ... }",
  "sessionId": "...",
  "executionJournal": { }
}
```

## Package / file layout (locked)

```
bifrost-sample/src/main/
  java/.../sample/incident/
    IncidentController.java
    IncidentTelemetryService.java
  resources/skills/incidents/
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
  resources/fixtures/incidents/
    network-dns.txt
    app-deploy-regression.txt
    ambiguous-slow.txt
    firewall-block.txt
```

Skill location globs already cover `classpath:/skills/**/*.yml`, so a subdirectory is fine (`skills/incidents/` already exists with `.gitkeep`).

## Documentation requirements

Update `bifrost-sample/README.md` with:

- Tree diagram
- Framework features table (nested planning vs invoice 2-level)
- What LLM decides vs what is fixed
- Evidence contract rules (AND semantics; shared `investigation_digest`; L2 tool names; plan coverage vs prompt guidance; known "list all producers" prompt wording)
- Scenario plumbing (`scenario` must be forwarded; no session bag)
- Example requests for 2–3 scenarios (prefer `handle-scenario`)
- How to read the journal for nested planning (MISSION → PLANNING → TOOL → nested MISSION)
- Contrast with `duplicateInvoiceChecker` (2-level vs 3-level)
- Session / `max_steps` limits (document only; no failure fixture)
- Model setup: shared OpenRouter connection; planner alias `qwen3-35b` (`qwen/qwen3.6-35b-a3b`); worker alias `gpt-4o-mini` (`openai/gpt-4o-mini`); `OPENROUTER_API_KEY`; why this sample does not use tiny local models

Optional later: `WALKTHROUGH.md` tour order including this sample.

## Tests

Minimum:

1. **Context / catalog tests** — all incident skills registered; mid-level skills have `planning_mode: true` and expected `allowed_skills`; root has the locked evidence contract shape.
2. **Controller unit tests** — delegates to `SkillTemplate` with skill name `handleIncident` and correct inputs (including scenario fixture path).
3. **Leaf unit tests** — scenario keys return stable fake data; unknown scenario returns neutral valid data.
4. **Contract shape tests (optional but preferred)** — root/classify/mid/draft manifests expose the locked required fields (parse YAML or catalog definitions).
5. **Optional integration** — only if we add a test double chat client; do not require a live local model in CI.

## Acceptance criteria

- [ ] Nested planning path exists: root planner can invoke at least one mid-level planner that invokes leaves.
- [ ] All leaves are Java-mapped skills with no external network calls and minimal mapped manifests.
- [ ] All LLM-backed skills use the locked `input_schema` / `output_schema` shapes above.
- [ ] Root uses the locked evidence contract; it does not require both investigation branches.
- [ ] Successful root plans are expected to cover classify + ≥1 investigation specialist + draft (via evidence + required output fields).
- [ ] At least three fixtures documented with expected branch bias.
- [ ] HTTP endpoints return result + sessionId + executionJournal.
- [ ] `GET /incidents/scenarios` and `GET /incidents/handle-scenario` work for demos.
- [ ] Leaves accept `scenario`; fixture endpoint sets it explicitly; prompts instruct forwarding.
- [ ] README section explains the tree, evidence rules, journal nesting, scenario plumbing, model setup, and how to invoke HTTP endpoints.
- [ ] Sample config adds OpenRouter connection with dummy-default api-key + planner alias `qwen3-35b` and worker alias `gpt-4o-mini`; live demo uses real `OPENROUTER_API_KEY`.
- [ ] Planning skills (`handleIncident`, `investigateNetwork`, `investigateApp`) declare `model: qwen3-35b`.
- [ ] Single-shot skills (`classifyIncident`, `draftIncidentResponse`) declare `model: gpt-4o-mini`.
- [ ] Mid-level investigate skills declare `output_schema_max_retries: 2`.
- [ ] CI / sample boot succeeds without a real OpenRouter key; tests do not call the live API.
- [ ] Naming is consistent across YAML, Java, and HTTP.

## Design decisions

| # | Topic | Decision | Locked |
| --- | --- | --- | --- |
| 1 | Severity scale | `SEV1`–`SEV4` | 2026-07-12 |
| 2 | Classify required? | **Plan-required** via root evidence + required output fields (`incident_classification`). Prompt steers early ordering. Not Java-hardcoded. | 2026-07-14 (revised) |
| 3 | Evidence strength | Light on root only; none on mid-level; shared `investigation_digest` for either branch; locked contract shape | 2026-07-14 |
| 4 | Scenario plumbing | Explicit `scenario` on root + leaves; prompts forward it; fixture endpoint for demos | 2026-07-12 |
| 5 | Model choice | Shared OpenRouter connection; planner `qwen3-35b` → `qwen/qwen3.6-35b-a3b`; worker `gpt-4o-mini` → `openai/gpt-4o-mini`; not tiny local models | 2026-07-15 |
| 6 | Controller home | New `IncidentController` | 2026-07-12 |
| 7 | Draft skill | Single-shot; optional `lookupRunbook` via root (not a third planner) | 2026-07-12 |
| 8 | Failure / limits demo | Defer; document session/`max_steps` limits in README only | 2026-07-12 |
| 9 | Mid-level I/O | Shared structured digest schema (`domain`, `summary`, `findings`, `probesUsed`, `confidence`) | 2026-07-14 |
| 10 | Classify / draft I/O | Full schemas locked in this ticket | 2026-07-14 |
| 11 | Mapped leaf manifests | Minimal only (`name`, `description`, `mapping`); Java owns contracts | 2026-07-14 |
| 12 | OpenRouter boot key | Dummy default `${OPENROUTER_API_KEY:test-openrouter-api-key}` so sample boots/CI without a real key; live demos need a real key | 2026-07-14 |
| 13 | Mid-level schema retries | `output_schema_max_retries: 2` on both investigate planners (same as classify/draft) | 2026-07-14 |
| 14 | Scope freeze | No additional demo scenarios or framework features for v1; expand later if needed | 2026-07-14 |

## Implementation sketch

1. Add `IncidentTelemetryService` + seven mapped YAML leaves (minimal manifests; reflected `scenario` contract).
2. Add `classifyIncident` and `draftIncidentResponse` single-shot skills with locked schemas.
3. Add mid-level planning skills with locked I/O, `planning_mode`, `max_steps: 6`, `output_schema_max_retries: 2`, prompts, no evidence contract.
4. Add root planning skill with locked I/O, evidence contract, `max_steps: 10`, prompts.
5. Wire `IncidentController` + fixtures + scenario list/run endpoints.
6. Add OpenRouter connection (`api-key: ${OPENROUTER_API_KEY:test-openrouter-api-key}`) + planner alias `qwen3-35b` and worker alias `gpt-4o-mini` in sample `application.yml`; set planner model on the three planning skills and worker model on classify/draft.
7. Catalog / controller / leaf tests + README (journal nesting + evidence rules + model/prereq notes).
8. Manual smoke: nested planning on one network and one app fixture with a real OpenRouter key.

## Why build this first among A–D

- Strongest illustration of **nested HTN method selection**.
- Leaves need no paid API.
- Journal narrative is easy to explain in talks and docs.
- Complements invoice sample (business evidence) with ops branching.

## Design discussion notes

- Owner: TBD  
- Reviewers: TBD  
- Decisions log:  
  - 2026-07-12: Reviewed against `bifrost-spring-boot-starter` capabilities; locked evidence direction, scenario, controller, draft, and HTTP decisions. Implementation deferred.
  - 2026-07-14: Design review against `ai/skill-authoring`. Locked mid-level/classify/draft I/O schemas; revised classify from "soft-required" to plan-required via evidence; dropped obsolete mapped-manifest dependency tickets (behavior already in checkout).
  - 2026-07-14: Named AI connections already in framework. Locked model to OpenRouter via named aliases; removed all named-connection deferral language from this ticket.
  - 2026-07-14: Final pre-impl locks — dummy OpenRouter key default for boot/CI; mid-level `output_schema_max_retries: 2`; no further v1 scope.
  - 2026-07-15: Sample model convention — shared OpenRouter connection; planner `qwen3-35b` → `qwen/qwen3.6-35b-a3b` for planning skills; worker `gpt-4o-mini` → `openai/gpt-4o-mini` for classify/draft (replaces single `qwen3-235b` alias for all five).
