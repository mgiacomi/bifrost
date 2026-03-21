# Ticket: eng-019-runtime-state-standardization-for-linter-outcomes.md
## Issue: Standardize Linter Outcome Recording Through ExecutionStateService

### Why This Ticket Exists
ENG-018 added bounded linter retries and observable linter outcomes, but the current implementation records those outcomes directly on `BifrostSession` from inside the advisor. That works, but it bypasses the runtime-state abstraction used by the rest of Bifrost execution for plans, tool calls, tool results, and errors.

This ticket closes that gap by making linter outcome recording flow through `ExecutionStateService` in the same way as the rest of execution observability. The goal is consistency of ownership and future extensibility, not a behavior change to the linter itself.

---

## Goal
Route linter outcome recording through `ExecutionStateService` so advisor-driven linter telemetry follows the same state-management boundary as the rest of Bifrost runtime execution.

The main outcome should be:

- linter observability is recorded through the same abstraction as plans, tool calls, results, and errors
- advisors no longer write runtime journal state directly to `BifrostSession`
- future telemetry/export/state implementations have one consistent runtime recording seam

---

## Non-Goals
This ticket should **not** introduce:

- changes to linter retry semantics
- changes to prompt construction or advisor ordering
- new telemetry platforms, metrics backends, or tracing integrations
- a redesign of `BifrostSession` journal payload structure
- changes to what counts as linter pass, retry, or exhaustion

---

## Required Outcomes

### Functional
- `LinterCallAdvisor` records linter outcomes through a runtime-state abstraction rather than mutating `BifrostSession` journal state directly.
- Current observable behavior remains intact:
  - last linter outcome remains accessible from runtime state
  - journal entries still record linter pass/retry/exhaustion in stable structured form
- Linter-enabled YAML skill execution still produces identical retry behavior and observable results after the refactor.

### Structural
- `ExecutionStateService` becomes the standard write boundary for linter runtime events.
- `DefaultExecutionStateService` owns the concrete logic for storing latest linter outcome and journaling linter events.
- `LinterCallAdvisor` depends on a narrow bridge or recorder abstraction rather than directly reaching into session internals.
- The resulting design should match the repo’s existing pattern for:
  - `logPlanCreated`
  - `logPlanUpdated`
  - `logToolCall`
  - `logToolResult`
  - `logError`

### Testing
- Tests prove linter outcomes are still visible in runtime-facing state after the refactor.
- Tests prove linter journal entries are still recorded through the standardized path.
- Tests prove advisor behavior is unchanged for:
  - pass without retry
  - retry with corrective hint
  - retry exhaustion
- Tests prove end-to-end YAML skill execution still records linter outcomes correctly after the abstraction change.

---

## Suggested Files
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/ExecutionStateService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/...`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/linter/...`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/state/...`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/...`

---

## Acceptance Criteria
- Linter outcome recording flows through `ExecutionStateService`.
- `LinterCallAdvisor` no longer appends journal entries directly to `BifrostSession`.
- Existing linter outcome observability remains available and test-covered.
- No behavior regression is introduced in bounded retries, retry exhaustion, or final recorded outcome.

---

## Definition of Done
This ticket is done when linter outcome recording uses the same runtime-state abstraction as the rest of Bifrost execution and all existing linter retry/observability behavior continues to work unchanged.
