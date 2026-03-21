# Ticket: eng-022-safe-skill-thoughts-api-and-journal-views.md
## Issue: Expose Developer-Friendly Skill Thought Views Without Leaking Raw Internal Payloads

### Why This Ticket Exists
Phase 5 calls for a developer-facing way to inspect “skill thoughts,” but the current runtime only exposes raw `ExecutionJournal` entries and a full journal snapshot. That is a good storage primitive, but it is not yet a safe or convenient API for extracting human-readable traces per skill without leaking unrelated payloads or private variables.

This ticket turns the journal from raw storage into a controlled diagnostics surface.

---

## Goal
Add a safe runtime API for retrieving human-readable thought traces for a skill from `ExecutionJournal` data.

The main outcome should be:

- developers can request a filtered thought view for a skill/session
- the returned view is readable and intentionally shaped for debugging
- sensitive or overly raw payloads are not exposed by default

---

## Non-Goals
This ticket should **not** introduce:

- a full UI
- distributed tracing
- new quota enforcement
- sample application business logic beyond using the new diagnostics API

---

## Required Outcomes

### Functional
- Define a runtime-facing API for retrieving thought/debug traces, such as `getSkillThoughts(skillId)` or an equivalent session/journal query object.
- Filter and format journal entries into a developer-friendly view rather than returning raw event blobs only.
- Make the filtering model explicit: by skill, by frame, or by route, depending on what the current journal data can support reliably.
- Decide and implement a safe default for redaction or omission of private/internal payload details that should not leak directly.

### Structural
- Keep raw journal storage intact as the source of truth.
- Put presentation/query logic behind a dedicated abstraction rather than embedding it into `BifrostSession` as ad hoc list filtering.
- Ensure the API can evolve later into richer diagnostics without breaking callers.

### Testing
- Tests prove thoughts can be retrieved for a specific skill or route.
- Tests prove unrelated journal events are excluded from the filtered view.
- Tests prove sensitive/internal payload handling follows the documented contract.
- Tests prove serialization of the new diagnostics view is stable enough for downstream developer tooling.

---

## Suggested Files
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionJournal.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/JournalEntry.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/...`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionJournalTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionTest.java`

---

## Acceptance Criteria
- Bifrost exposes a supported API for retrieving skill-thought diagnostics from session journal data.
- The returned view is filtered and readable rather than a raw dump of all journal payloads.
- The API has an explicit safety contract around payload exposure and is covered by tests.
- Existing journal recording behavior remains compatible.

---

## Definition of Done
This ticket is done when developers can safely inspect skill thought traces through a supported diagnostics API built on top of the execution journal.
