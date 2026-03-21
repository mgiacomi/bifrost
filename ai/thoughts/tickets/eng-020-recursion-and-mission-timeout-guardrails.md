# Ticket: eng-020-recursion-and-mission-timeout-guardrails.md
## Issue: Turn Existing Depth Limits Into a Complete Mission Guardrail Boundary

### Why This Ticket Exists
Phase 5 calls for circuit breakers that prevent recursive LLM doom-loops and sub-agent stalls. The current codebase already has one important piece in place: `BifrostSession` enforces a maximum frame depth and throws `BifrostStackOverflowException` when the stack grows too deep.

What is still missing is the rest of the guardrail boundary:

- proof that recursive `callSkill` flows fail predictably end to end
- bounded execution time for LLM-backed missions
- configuration that makes these limits explicit rather than incidental

This ticket builds on the existing stack guard instead of re-implementing it.

---

## Goal
Harden mission execution so recursive skill chains and stalled model calls fail fast through explicit, configurable runtime guardrails.

The main outcome should be:

- recursive YAML skill execution is covered by end-to-end guardrail tests
- LLM-backed mission execution has a timeout boundary
- failure modes are explicit, observable, and safe for callers

---

## Non-Goals
This ticket should **not** introduce:

- token or cost quota enforcement
- Micrometer metrics export
- journal summarization APIs
- sample-app documentation work

---

## Required Outcomes

### Functional
- Preserve the existing session stack-depth limit as the recursion guardrail for nested `callSkill` execution.
- Add integration coverage proving recursive YAML skill chains terminate with a clear overflow failure instead of hanging or silently corrupting session state.
- Introduce a configurable mission execution timeout for LLM-backed skill execution.
- Ensure timeout failures surface clearly and close out mission frame state safely.
- Define where timeout configuration lives, likely in session/runtime properties, so application teams can tune it.

### Structural
- Guardrail policy is configured through first-class properties rather than scattered constants.
- Timeout handling belongs in the mission/coordinator execution path, not in provider-specific chat adapters.
- Stack-overflow and timeout failures both leave `BifrostSession` in a consistent state for later inspection.

### Testing
- Tests prove recursive child-skill execution fails at the configured max depth.
- Tests prove timed-out model execution surfaces a clear exception.
- Tests prove mission frames are cleaned up after timeout and overflow failures.
- Tests prove existing happy-path mission execution behavior does not regress when guardrails are enabled.

---

## Suggested Files
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/MissionExecutionEngine.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionTest.java`

---

## Acceptance Criteria
- Recursive YAML skill loops terminate via the configured depth guard and are covered by automated tests.
- LLM-backed mission execution cannot block forever; timeout behavior is configurable and test-covered.
- Overflow and timeout failures leave session frames and plan state in a coherent, inspectable state.
- Existing successful mission execution behavior remains intact.

---

## Definition of Done
This ticket is done when Bifrost has explicit recursion and mission-time guardrails that fail fast, clean up correctly, and are covered by end-to-end tests.
