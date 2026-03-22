# Ticket: eng-021-session-quotas-and-micrometer-usage-telemetry.md
## Issue: Add Session-Level Quotas and Metrics for Runaway Spend Detection

### Why This Ticket Exists
Phase 5 expects Bifrost to monitor runaway usage, not just prevent stack overflow. Today the runtime records plans, tool calls, and linter events, but it does not meter model usage, enforce quotas, or expose Micrometer metrics that operators can watch.

Without that layer, a session can remain logically correct while still burning excessive time or tokens.

---

## Goal
Introduce session-level execution quotas and Micrometer-backed usage telemetry so runaway sessions can be detected and stopped before they spiral.

The main outcome should be:

- Bifrost tracks the usage signals needed to reason about cost and load
- sessions can be stopped when they exceed configured guardrails
- operators can observe those guardrails through standard metrics

---

## Non-Goals
This ticket should **not** introduce:

- provider-billing-accurate accounting if the underlying model response does not expose it yet
- journal redaction or thought-extraction APIs
- sample-app walkthrough docs
- a distributed quota store

---

## Required Outcomes

### Functional
- Add configurable session quota settings for the MVP, such as max skill invocations, max tool invocations, max retries, max cumulative model calls, and max cumulative usage units.
- Define quota enablement semantics explicitly:
- existing applications must remain backward compatible when no new quota properties are configured
- each new quota must either have a conservative non-breaking default or an explicit disabled state
- the implementation must document how a quota is disabled and whether the default behavior is opt-in or always-on
- Track available model-usage data when Spring AI/provider responses expose it, and define a deterministic fallback heuristic when exact token counts are unavailable.
- Abort or fail sessions that exceed configured quota boundaries with clear errors.
- Ensure quota checks run in the normal execution path rather than as a best-effort post-processing step.
- Enforce quotas at the session level even when the triggering activity occurs within a mission, skill execution, tool call, or linter retry.

### Structural
- Runtime usage accounting flows through a dedicated abstraction, not ad hoc counters scattered across the codebase.
- Micrometer integration is added as the observability surface for quota/usage metrics.
- Metrics should be tagged in a way that is useful but not explosive, favoring stable dimensions such as skill name, outcome, and guardrail type.
- The implementation must define the runtime scope of each metric clearly:
- session-level counters for cumulative guardrail-relevant usage
- mission- or skill-level activity meters only where the runtime already has a stable boundary for that concept
- no tags or meters may use session ID, raw objective text, or other high-cardinality payload data

### Telemetry
- Emit Micrometer meters for at least:
- reasoning/model activity for each skill execution, while still accumulating quota enforcement against the enclosing session
- guardrail trips or quota violations at the session level, tagged by guardrail type and outcome
- tool execution counts at the tool invocation boundary, tagged by skill and tool name when those values are already stable runtime identifiers
- linter outcomes at the linter boundary, tagged by skill, linter type, and outcome
- a tool-accuracy signal only if it can be computed from existing runtime data without guessing hidden causality
- For this MVP, define tool accuracy as the rate at which a tool invocation is followed by a non-passing final linter outcome for the same skill execution.
- The implementation must document:
- whether retries are counted individually or only the terminal linter outcome is used
- what numerator and denominator are used for the accuracy metric
- any cases where the metric is intentionally omitted because the runtime cannot attribute the linter result safely

### Testing
- Tests prove configured quota boundaries trigger failures.
- Tests prove normal sessions remain unaffected below the thresholds.
- Tests prove metrics are recorded when missions execute and when quotas trip.
- Tests document any heuristic behavior used when exact token usage is unavailable.
- Tests prove the default or disabled quota configuration preserves current behavior for existing applications.
- Tests prove the same usage event both updates the relevant metric and can trip the corresponding session quota in-band.
- Tests cover exact-usage and heuristic-usage accounting separately so operators can see when quota enforcement is based on provider-reported values versus fallback usage units.

### Usage Accounting Rules
- Prefer exact provider-reported usage values when Spring AI exposes them on the model response.
- When exact token usage is unavailable, fall back to a deterministic session-local heuristic usage-unit calculation rather than pretending the value is provider-accurate.
- Quota enforcement must state which boundaries are based on exact token counts, which are based on model-call counts, and which are based on heuristic usage units.
- The implementation must expose whether a recorded usage value is exact or heuristic in code, tests, and operator-facing documentation or metric naming/tagging where practical.
- The heuristic does not need to match provider billing exactly, but it must be stable enough that the same response shape produces the same usage-unit result across runs.

---

## Suggested Files
- `bifrost-spring-boot-starter/pom.xml`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/...`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/...`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/...`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/...`

---

## Acceptance Criteria
- Bifrost enforces at least one meaningful session-level quota boundary beyond stack depth.
- Bifrost documents the default and disabled behavior of every new quota so existing applications do not regress unexpectedly.
- Micrometer metrics expose session guardrail violations plus mission or skill, tool, and linter usage at clearly defined runtime boundaries.
- Quota failures are explicit and test-covered.
- The implementation documents where usage numbers are exact versus heuristic.
- Any shipped tool-accuracy metric defines its numerator, denominator, and retry treatment explicitly.

---

## Definition of Done
This ticket is done when Bifrost can meter runtime usage, enforce configurable session quotas, and expose the resulting signals through Micrometer metrics.
