# Ticket: Sample HTN Skill Tree — IT Incident Commander

**Status:** Design (decisions locked 2026-07-12; blocked on two framework prerequisite tickets)  
**Priority:** P1 (recommended first nested-planning sample)  
**Module:** `bifrost-sample`  
**Related tickets:**  
- `eng-sample-htn-skill-tree-gallery.md` (epic index)  
- `eng-sample-htn-support-case-resolver.md`  
- `eng-sample-htn-travel-concierge.md`  
- `eng-sample-htn-insurance-claim-intake.md`  
**Depends on:** Nested `planning_mode` skills + `allowed_skills` (already supported), plus completion of `eng-separate-public-skills-from-java-targets.md` and `eng-simplify-mapped-yaml-skill-manifests.md`  

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
- Telemetry probes can be pure fake Java (no OpenAI required).
- Execution journals read like a real ops story.

## Goals

- Demonstrate **skill-stack depth ≥ 3**: root planner → mid planner → leaf.
- Keep the demo **runnable with Ollama only** (no OpenAI dependency).
- Make the tree **inspectable** via `sessionId` + `executionJournal` (and later `bifrost-cli`).
- Provide **canned scenarios** with documented expected *branches* (not brittle exact JSON).
- Document the sample in `bifrost-sample/README.md` (or a dedicated walkthrough section).
- Prefer **clear naming**: HTTP path, YAML skill `name`, and Java capability names tell one story.
- Explicitly showcase core framework features (see [Framework features this sample showcases](#framework-features-this-sample-showcases)).

## Non-Goals

- Real monitoring integrations (Prometheus, PagerDuty, etc.).
- Perfect LLM determinism or golden-file exact outputs.
- Framework changes (unless a gap is discovered during implementation).
- Replacing existing invoice/feedstock samples.
- Production-grade runbooks or security scanning.
- Attachments, RBAC, multi-provider routing, or regex linters (already covered by invoice/feedstock samples).
- Exhausting `max_steps` / depth limits as a first-class demo scenario (document limits in README only for v1).

## Framework features this sample showcases

| Feature | How this sample uses it |
| --- | --- |
| Nested `planning_mode` | Root + `investigateNetwork` / `investigateApp` each run the step-loop engine |
| `allowed_skills` governance | Each planner only sees its specialist / probe set |
| YAML skill names as tools | Leaves are mapped YAML skills so they appear in `allowed_skills` |
| `@SkillMethod` + `mapping.target_id` | Deterministic telemetry / runbook leaves |
| `input_schema` | LLM-backed root and specialists declare contracts; mapped leaves inherit reflected Java contracts |
| `output_schema` (+ retries) | Structured classify / draft / root report |
| Light `evidence_contract` | Root only; must **not** force both investigation branches (see evidence rules) |
| Named Ollama models | Tree uses a stronger local model key than invoice's tiny default |
| `SkillTemplate` observer | HTTP returns `sessionId` + `executionJournal` |
| Nested mission isolation | Parent plan/evidence snapshotted while child planner runs (visible in journal frames) |
| `prompt` vs `description` | Planners get private runbook-style prompts; public descriptions stay short |
| `max_steps` | Tighter mid-level budgets teach step limits without a failure fixture |

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
| L1 `handleIncident` | May only call listed specialists | Order; skip network and/or app; when to draft; optional runbook |
| L2 `investigateNetwork` / `investigateApp` | May only call listed probes | Which probes; when evidence is enough |
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

- **`max_steps`:** root `10`; mid-level `6` (tight enough to show budgets; default engine default is 10).
- **`model`:** prefer a stronger Ollama named key already in sample config (e.g. `gemma4-e4b` or `gemma4-26b` if available). Do **not** default the whole tree to `granite4-tiny` without a successful nested-planning smoke. Document the chosen key in README; invoice sample can keep tiny.
- **`prompt`:** private planning instructions on root + mid-level (forward `scenario`, prefer classify first, don't call every branch, stop when enough evidence).
- **`evidence_contract`:** light on root only; none (or very light) on mid-level — see [Evidence contract rules](#evidence-contract-rules).

### LLM single-shot skills

| Name | Input | Output (schema) |
| --- | --- | --- |
| `classifyIncident` | `ticketText`, optional `scenario` | `severity` (`SEV1`–`SEV4`), `category`, `rationale` |
| `draftIncidentResponse` | ticket + investigation digests (+ optional runbook text) | `summary`, `likelyCause`, `recommendedAction`, `userMessage` |

### Java leaves (`@SkillMethod` + mapped YAML)

Implement in `com.lokiscale.bifrost.sample.incident.IncidentTelemetryService` (single service is fine for v1).

| Capability (YAML skill name) | Fake behavior |
| --- | --- |
| `checkDns` | Resolve status keyed by `scenario` |
| `checkLatency` | p50/p95 latency + region |
| `checkFirewallRules` | Recent deny hits |
| `getErrorRate` | 5xx rate + window |
| `getRecentDeploys` | Service, version, timestamp |
| `getServiceHealth` | UP/DEGRADED/DOWN |
| `lookupRunbook` | Short text runbook by category / scenario |

Every leaf YAML must:

- Use `mapping.target_id` to the Java method.
- Omit `input_schema` and inherit the effective contract from the mapped Java method. Each Java leaf signature and parameter metadata must expose **`scenario`** with the intended name, type, description, and requiredness so demos remain reproducible.
- Not perform external network calls.

Implement this sample after, or coordinate it with, [`eng-simplify-mapped-yaml-skill-manifests.md`](eng-simplify-mapped-yaml-skill-manifests.md) so finished mapped leaves omit both irrelevant model configuration and duplicate input schemas rather than institutionalizing temporary ceremony.

**Scenario plumbing (locked):** Bifrost has no free-form session bag for demo keys. Pass `scenario` explicitly on root input and instruct planners (via `prompt`) to forward it on every tool call. Prefer `GET /incidents/handle-scenario?name=...` for live demos so the model does not invent the key.

## Evidence contract rules

Framework behavior that must drive the sample design:

1. Claim evidence lists are **AND-all** (every listed evidence type for a present claim must be gathered).
2. Nested YAML missions **snapshot/restore** parent evidence; **leaf evidence does not bubble** to the parent ledger.
3. Parent `tool_evidence` keys are the **tools the parent actually invokes** (L2 specialists / runbook), not L3 probes inside a child planner.

**v1 lock — light root contract only:**

- Root keeps `output_schema` for the incident report.
- If root uses `evidence_contract`, map only soft/always-safe tools, e.g.:
  - claims that need classification → `classifyIncident` evidence tag
  - claims that need synthesis → `draftIncidentResponse` and/or investigation specialist tags **without requiring both network and app**
- Do **not** require both `investigateNetwork` and `investigateApp` evidence for the same claim set — that would force "call every branch" and kill the selective-HTN story.
- Mid-level: **no** `evidence_contract` in v1 (probe selection stays free). Optional later if we want specialist-level evidence demos.
- Document nested isolation in README so readers understand why root `tool_evidence` names L2 skills.

Example shape (illustrative; exact tags finalized at implement time):

```yaml
# handleIncident — light, branch-safe
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

Note: `investigateNetwork` and `investigateApp` both produce the same `investigation_digest` tag so **either** branch satisfies investigation-backed claims.

## Mission input / output

### Root input (locked)

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
```

### Root output (locked)

```yaml
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

### Classify behavior (locked)

- Soft-required via root `prompt` ("prefer calling `classifyIncident` early").
- Not hard-coded orchestration in Java.
- Light evidence can encourage classify without forbidding skip paths if the model misbehaves (demos use fixtures + prompt quality).

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

Skill location globs already cover `classpath:/skills/**/*.yml`, so a subdirectory is fine.

## Documentation requirements

Update `bifrost-sample/README.md` with:

- Tree diagram
- Framework features table (nested planning vs invoice 2-level)
- What LLM decides vs what is fixed
- Evidence contract rules (AND semantics; L2 tool names; no forced dual-branch)
- Scenario plumbing (`scenario` must be forwarded)
- Chosen model key + Ollama pull prerequisites
- Example requests for 2–3 scenarios (prefer `handle-scenario`)
- How to read the journal for nested planning (MISSION → PLANNING → TOOL → nested MISSION)
- Contrast with `duplicateInvoiceChecker` (2-level vs 3-level)

Optional later: `WALKTHROUGH.md` tour order including this sample.

## Tests

Minimum:

1. **Context / catalog tests** — all incident skills registered; mid-level skills have `planning_mode: true` and expected `allowed_skills`.
2. **Controller unit tests** — delegates to `SkillTemplate` with correct skill name and inputs (including scenario fixture path).
3. **Leaf unit tests** — scenario keys return stable fake data.
4. **Optional integration** — only if we add a test double chat client; do not require live Ollama in CI.

## Acceptance criteria

- [ ] Nested planning path exists: root planner can invoke at least one mid-level planner that invokes leaves.
- [ ] All leaves are Java-mapped skills with no external network calls.
- [ ] At least three fixtures documented with expected branch bias.
- [ ] HTTP endpoints return result + sessionId + executionJournal.
- [ ] `GET /incidents/scenarios` and `GET /incidents/handle-scenario` work for demos.
- [ ] Root evidence contract (if present) does not require both investigation branches.
- [ ] Leaves accept `scenario`; fixture endpoint sets it explicitly.
- [ ] README section explains the tree, evidence rules, journal nesting, and how to run it.
- [ ] Sample runs with Ollama-only config (OpenAI not required).
- [ ] Naming is consistent across YAML, Java, and HTTP.
- [ ] Nested planning uses a documented model key appropriate for multi-level quality (not silently stuck on tiny without smoke).

## Design decisions (locked 2026-07-12)

| # | Topic | Decision |
| --- | --- | --- |
| 1 | Severity scale | `SEV1`–`SEV4` |
| 2 | Classify required? | Soft-required via root prompt (+ light evidence); not Java-hardcoded |
| 3 | Evidence strength | Light on root only; none on mid-level; never require both branches for the same claims |
| 4 | Scenario plumbing | Explicit `scenario` on root + leaves; prompts forward it; fixture endpoint for demos |
| 5 | Model choice | Prefer stronger Ollama named model for this tree; document; smoke before shipping tiny |
| 6 | Controller home | New `IncidentController` |
| 7 | Draft skill | Single-shot; optional `lookupRunbook` via root (not a third planner) |
| 8 | Failure / limits demo | Defer; document session/`max_steps` limits in README only |

## Implementation sketch (after design lock)

1. Add Java leaf service + mapped YAML manifests that inherit the reflected Java `scenario` contract and omit mapped `input_schema`.
2. Add classify + draft single-shot skills (`output_schema`).
3. Add mid-level planning skills (`planning_mode`, tight `max_steps`, prompts, no evidence contract).
4. Add root planning skill + output schema + **light** evidence contract + prompts.
5. Wire `IncidentController` + fixtures + scenario list/run endpoints.
6. Tests + README (including journal nesting + evidence rules).
7. Manual smoke with Ollama on stronger model; capture one journal snippet for docs.

## Why build this first among A–D

- Strongest illustration of **nested HTN method selection**.
- No paid API dependency.
- Journal narrative is easy to explain in talks and docs.
- Complements invoice sample (business evidence) with ops branching.

## Design discussion notes

- Owner: TBD  
- Reviewers: TBD  
- Decisions log:  
  - 2026-07-12: Reviewed against `bifrost-spring-boot-starter` capabilities; locked evidence, scenario, model, controller, draft, and HTTP decisions above. Implementation deferred pending separate discussion.
