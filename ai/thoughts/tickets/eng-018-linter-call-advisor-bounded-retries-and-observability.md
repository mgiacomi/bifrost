# Ticket: eng-018-linter-call-advisor-bounded-retries-and-observability.md
## Issue: Implement Linter Self-Correction With Bounded Retries and Observable Outcomes

### Why This Ticket Exists
With validated linter manifests and advisor wiring in place, the remaining Phase 4 behavior is the actual self-correction loop: inspect model output, decide pass/fail, feed back a hint, retry safely, and record what happened. This is where the highest token-burn and debuggability risks live, so it deserves its own focused PR.

---

## Goal
Implement a `LinterCallAdvisor` that validates YAML skill generations, appends corrective hints on failure, enforces retry limits, and records outcomes for debugging and tests.

The main outcome should be:

- linter failures trigger bounded model retries
- retry exhaustion is visible and deterministic
- linter pass/fail data is observable from Bifrost runtime state

---

## Non-Goals
This ticket should **not** introduce:

- new linter schema design
- new storage backends
- broad telemetry platform work beyond the minimum hooks needed to observe linter behavior
- unbounded automatic retries

---

## Required Outcomes

### Functional
- Implement a `LinterCallAdvisor` that evaluates model output against the skill's configured linter.
- On linter failure, append a concise corrective hint and trigger another generation attempt through the advisor path.
- Stop retrying once the output passes or `max_retries` is reached.
- Ensure stubborn failures surface clearly rather than looping forever.
- Make linter behavior opt-in per YAML skill based on validated manifest config.

### Structural
- Retry behavior lives in advisor-based self-correction rather than a second orchestration loop inside mission execution.
- Outcome recording is explicit enough for execution-journal logging, metrics, or future diagnostics APIs.
- Runtime code can inspect retry count and final linter status without scraping prompt text.

### Testing
- Tests prove a passing response returns without retry.
- Tests prove failing output causes retries with appended hints.
- Tests prove retries stop at `max_retries`.
- Tests prove linter outcomes and retry counts are observable in a stable runtime-facing form.
- Add an integration-style test that exercises a YAML skill using the advisor-backed retry loop.

---

## Suggested Files
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/...`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/...`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionJournal.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/...`

---

## Acceptance Criteria
- Linter-enabled YAML skills retry through a Spring AI advisor when validation fails.
- Retry count is bounded by validated manifest configuration.
- Final linter outcome and retry count are observable and test-covered.
- No infinite retry path exists for a permanently failing linter.

---

## Definition of Done
This ticket is done when linter-enabled YAML skills can self-correct through a bounded Spring AI advisor loop and the resulting pass/fail behavior is observable in tests and runtime state.
