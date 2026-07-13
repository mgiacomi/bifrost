# Ticket: Sample HTN Skill Tree Gallery (Epic Index)

**Status:** Design  
**Priority:** P1 (organizational)  
**Module:** `bifrost-sample`  
**Framework prerequisites:** Complete `eng-separate-public-skills-from-java-targets.md`, then `eng-simplify-mapped-yaml-skill-manifests.md`, before implementing new gallery trees.  
**Child tickets:**  
1. `eng-sample-htn-incident-commander.md` — **build first**  
2. `eng-sample-htn-support-case-resolver.md`  
3. `eng-sample-htn-insurance-claim-intake.md`  
4. `eng-sample-htn-travel-concierge.md`  

---

## Summary

Track the set of **nested HTN skill-tree samples** we will add to `bifrost-sample` to show Bifrost as an LLM-in-the-loop hierarchical task network—not only single-level tool calling.

This epic does not implement features itself. It links the four domain tickets, shared conventions, and a suggested build order so design discussions stay organized.

## Why tickets-per-domain is the right approach

Agreed working model:

1. **Design in tickets** — full idea, tree, open questions, acceptance criteria.  
2. **Discuss one ticket at a time** — lock decisions in that ticket’s “Design discussion notes.”  
3. **Implement one ticket at a time** — keep PRs reviewable.  
4. **Update sample README gallery** as each lands.

Alternatives considered:

| Approach | Pros | Cons |
| --- | --- | --- |
| One mega-ticket for all four | Single place | Unreviewable; blocks parallel design |
| **One ticket per domain + epic index (this)** | Clear ownership; staged delivery | Slight cross-link overhead |
| Code first, docs later | Fast spike | Loses the teaching narrative |

**Recommendation:** keep this epic + four children. No better structure needed unless a fifth tree appears.

## Shared goals across all trees

Every skill-tree sample should:

- Be at least **three skill-stack levels** (root planner → mid specialist → leaf).  
- Use **`allowed_skills`** to show governed tool visibility.  
- Prefer **Ollama-first** (OpenAI only if a domain truly needs vision later).  
- Return **`sessionId` + `executionJournal`** from HTTP demos.  
- Ship **fixtures/scenarios** with documented *branch bias*, not brittle golden LLM output.  
- Live under `resources/skills/<domain>/` and `.../sample/<domain>/` Java packages.  
- Get a **README section** (and eventually a gallery table in `bifrost-sample/README.md`).

## Shared non-goals

- Framework rewrites (file framework gaps as separate eng tickets).  
- Live third-party systems.  
- CI tests that call real LLMs.  
- Perfect determinism of model prose.

## Suggested build order

Begin this sample order only after both framework prerequisite tickets above are complete and verified.

| Order | Ticket | Rationale |
| --- | --- | --- |
| 1 | Incident Commander | Clearest nested branching; ops narrative; no paid APIs |
| 2 | Support Case Resolver | Multi-intent + customer copy; intermediate complexity |
| 3 | Insurance Claim Intake | Evidence-contract stress test; may need nesting spike |
| 4 | Travel Concierge | Approachable “fun” gallery piece for talks |

Order can change after design reviews; incident remains the default first implementation.

## Shared conventions (propose once, reuse)

Decide during the first implementation (Incident) and copy forward:

1. **Scenario parameter** — optional `scenario` string on root input for deterministic leaves.  
2. **HTTP style** — `POST /<domain>/...` for free text; `GET .../scenarios` list; optional `.../scenario?name=`.  
3. **Response envelope** — `result`, `sessionId`, `executionJournal` (match existing feedstock/invoice pattern).  
4. **Mapped leaves** — every `@SkillMethod` has a YAML manifest with `mapping.target_id`.  
5. **Naming** — camelCase skill names consistent with existing sample (`duplicateInvoiceChecker`).  
6. **Models** — named keys under `bifrost.models`; document which key each tree uses.  
7. **Logging** — rely on existing DEBUG packages; don’t invent a parallel trace format.

## Sample README gallery (target end state)

```markdown
## HTN skill tree gallery
| Sample | Levels | Highlights | Endpoint |
| Incident Commander | 3 | Nested investigate* planners | /incidents/... |
| Support Case Resolver | 3 | Multi-intent routing | /support/... |
| Insurance Claim Intake | 3 | Evidence contracts | /claims/... |
| Travel Concierge | 3 | Option ranking / preferences | /travel/... |
```

## Cross-cutting risks

- **Nested planning quality** on small local models.  
- **Evidence contracts** with nested planners (especially claims).  
- **Sample module bloat** — mitigate with domain packages and clear README TOC.  
- **Skill name collisions** — prefix by domain in descriptions; keep global names unique.

## Acceptance criteria (epic)

- [ ] All four child tickets exist with full design detail (done when children written).  
- [ ] Build order agreed.  
- [ ] Shared conventions locked during Incident implementation.  
- [ ] Each child reaches its own acceptance criteria before the next starts (unless explicitly parallelized).  
- [ ] `bifrost-sample/README.md` has a gallery section once ≥1 tree ships.

## Design discussion notes

_(Epic-level decisions only; domain decisions go in child tickets.)_

- Organization approach: **epic + 4 tickets** (confirmed with stakeholders).  
- Build first: **Incident Commander** (proposed).  
- Decisions log:  
  - **2026-07-12:** Thin sample reorg before implementing trees. Keepers moved under `bifrost-sample/src/main/resources/skills/{basics,vision}/`; empty `incidents|support|insurance|travel` folders reserved. Skill YAML `name` values unchanged. Discovery still `classpath:/skills/**/*.yml`.
  - (pending further)
