# PR 10 — Read-Only Operational Views

## Status

Proposed ticket brief. Depends on PR 09.

## Outcome

Deliver the first useful browser experience for instance status, registered
skills, active executions, and current-process trace discovery through reusable
transport-neutral services.

## In scope

- Add shared skill, active-execution, and trace-catalog query services.
- Preserve upstream keyset pagination, high-water semantics, opaque
  continuations, identity, observation time, and target scope.
- Add the stable Overview landing view and global target/live context.
- Add Skill Catalog list/detail with unchanged YAML text.
- Add Active Executions list/detail snapshots and trace-catalog browsing.
- Add loading, empty, unavailable, authentication, compatibility, and stale-scope
  presentation states.

## Guardrails

- Go does not continuously materialize complete application registries.
- Browser handlers adapt shared services rather than defining runtime meaning.
- YAML is rendered as untrusted text and `sourcePath` is not a filesystem link.
- Active snapshots are current best-effort facts, not execution history or a
  completed frame tree.
- Recent failure does not label a skill unhealthy.

## Acceptance signals

- Pagination races, refresh, direct lookup, target rotation, unavailable live
  monitoring, and catalog expiry are covered at service and UI levels.
- Navigation remains keyboard-available and scope-bound.
- Browser and shared-service DTOs preserve stable identifiers and direct
  limitations.

## Detailed-planning focus

Research frontend state/routing patterns created by PRs 07–09, API framing,
pagination UI, YAML display, accessible collections, responsive layout, status
language, and Phase 3 reuse requirements.

## Out of scope

Live narrative, trace download, parsing, charts, and MCP.

