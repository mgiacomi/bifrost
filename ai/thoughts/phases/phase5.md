# Phase 5 - Hardening Guardrails & Release Prep

## Goal
Bulletproof the execution logic with performance safety nets, ensuring sub-agents do not infinitely loop or cause runaway spend, and prepare the library for consumption.

## Primary Outcomes
- Telemetry and Quotas are actively monitored.
- Circuit breakers prevent recursive LLM doom-loops.
- Telemetry hooks exist for observing "skill thoughts".

## Scope
- Runaway spend safety and session quotas.
- Recursion limits.
- Project sample finalization.

## Detailed Tasks
### 1. Guardrails (Runaway Spend & Recursion)
- **Stack Depth Guard:** Ensure `ExecutionFrame` strictly tracks recursion so `callSkill` cannot endlessly loop.
- **Token Quotas / Timeouts:** Ensure virtual thread submissions use `.get(timeout)` to prevent indefinite blocking inside sub-agents.
- Enable basic token counting heuristics or Micrometer meters to kill `BifrostSession` instances generating unconstrained iterative loads.

### 2. Telemetry (getSkillThoughts & Metrics)
- Finalize the `ExecutionJournal` serialization so the developer can invoke `getSkillThoughts(skill_id)` to extract human-readable debug trajectories from the sub-agent traces without leaking private variables.
- Configure advanced Micrometer telemetry to track **Reasoning Density** (tokens consumed per skill call) and **Tool Accuracy** (how often a tool call resulted in a Linter error).

### 3. Documentation & Sample App
- Finish building the `bifrost-sample` application.
- Provide clear `README.md` instructions on how to use `@SkillMethod` vs YAML manifests.

## Deliverables
- Resiliency timeouts / circuit breakers.
- Telemetry exporters.
- Full End-to-End Sample application documentation.

## Exit Criteria
- Circular or infinite `callSkill` loops are successfully caught and terminated by stack limits.
- A developer can run the demo app and trace an LLM's full thought process in the application logs.
