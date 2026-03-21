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
- Add configurable session quota settings for the MVP, such as max skill invocations, max tool invocations, max retries, max cumulative model calls, or similar bounded workload signals.
- Track available model-usage data when Spring AI/provider responses expose it, and define a fallback heuristic when exact token counts are unavailable.
- Abort or fail sessions that exceed configured quota boundaries with clear errors.
- Ensure quota checks run in the normal execution path rather than as a best-effort post-processing step.

### Structural
- Runtime usage accounting flows through a dedicated abstraction, not ad hoc counters scattered across the codebase.
- Micrometer integration is added as the observability surface for quota/usage metrics.
- Metrics should be tagged in a way that is useful but not explosive, favoring stable dimensions such as skill name, outcome, and guardrail type.

### Telemetry
- Emit Micrometer meters for at least:
- reasoning/model activity per skill execution
- guardrail trips or quota violations
- tool execution counts
- linter/tool accuracy signals where the existing runtime already has enough data to compute them safely

### Testing
- Tests prove configured quota boundaries trigger failures.
- Tests prove normal sessions remain unaffected below the thresholds.
- Tests prove metrics are recorded when missions execute and when quotas trip.
- Tests document any heuristic behavior used when exact token usage is unavailable.

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
- Micrometer metrics expose mission/tool/linter usage and guardrail violations.
- Quota failures are explicit and test-covered.
- The implementation documents where usage numbers are exact versus heuristic.

---

## Definition of Done
This ticket is done when Bifrost can meter runtime usage, enforce configurable session quotas, and expose the resulting signals through Micrometer metrics.
