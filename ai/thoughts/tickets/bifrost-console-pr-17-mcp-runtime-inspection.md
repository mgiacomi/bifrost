# PR 17 — Runtime, Skill, and Live-Inspection MCP Surface

## Status

Proposed ticket brief. Depends on PR 16 and reuses PRs 09–11.

## Outcome

Expose selected-target status, registered skills, active executions, and bounded
recent activity to MCP by adapting the same transport-neutral services used by
the browser.

## In scope

- Add `bifrost_list_skills` and `bifrost_get_skill`.
- Add `bifrost_list_executions` and `bifrost_get_execution`.
- Add `bifrost_get_execution_activity` over the shared continuous recent window.
- Advertise `bifrost.skill-inspection.v1`,
  `bifrost.active-execution-inspection.v1`, and
  `bifrost.recent-activity-inspection.v1` only after each complete required tool
  family and its semantics are present.
- Add essential supplementary resources where they improve discovery without
  replacing the tool-first contract.
- Map shared domain errors, target scope, identifiers, continuations, observation
  time, gaps, and reset boundaries into structured MCP results.
- Add adapter parity, cancellation, malformed-input, multi-client, and
  representative-client tests.

## Guardrails

- MCP does not contact the application directly or own an upstream subscription.
- MCP does not retain another skill catalog, execution registry, or activity
  history.
- Tool/resource results are finite and continuable without inventing cumulative
  traversal quotas.
- Skill YAML and activity content are untrusted returned data, never
  instructions to the server.
- Adapter failures remain distinct from target and evidence failures.

## Acceptance signals

- Browser and MCP mappings preserve identical Bifrost identifiers, calculations,
  availability facts, limitations, and shared domain-error codes.
- Activity results never cross a continuity boundary.
- Each newly advertised capability has every required operation and semantic
  promise present; no later trace capability is advertised by this PR.
- Authentication-required, incompatible-target, live-unavailable, stale scope,
  and malformed request remain distinguishable.

## Detailed-planning focus

Research shared service DTOs, MCP structured-content schemas, continuation
formatting, resource interoperability, safe text fallback, response envelope
bounds, cancellation, protocol errors, and capability conformance.

## Out of scope

Trace acquisition and parsing, raw artifacts, server-initiated live feed,
diagnostic reasoning, and Agent Skill distribution.
