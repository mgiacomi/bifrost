# PR 12 — Central Artifact Acquisition and Trace Storage

## Status

Proposed ticket brief. Depends on PR 11.

## Outcome

Create one centralized, scope-bound artifact service shared by browser and future
MCP operations, with one immutable installed copy and lifecycle per trace.

## In scope

- Stream application artifacts into verified temporary installation and publish
  only complete admitted copies.
- Join simultaneous acquisition for the same scope-bound trace.
- Issue opaque artifact handles and maintain shared metadata, last-use, pinning,
  application availability, and original observation facts.
- Enforce aggregate byte capacity and idle TTL, including explicit `unlimited`
  and `never`.
- Add admission, eviction, cleanup, deliberate removal, target-scope
  invalidation, restart cleanup, and shutdown behavior.
- Add authenticated raw attachment pass-through streaming to a
  developer-selected browser download without creating an analysis copy,
  artifact handle, index, or workspace capacity charge.
- Add Trace Storage UI and artifact-specific shared domain errors.

## Guardrails

- Never expose a partial or invalid acquisition as evidence.
- Browser and MCP cannot create separate copies, handles, capacity charges, or
  cleanup policies.
- Raw attachment pass-through is separate from analysis acquisition and streams
  the unchanged application artifact with safe attachment headers and no
  implicit installation.
- Raw pass-through requires a complete transport but not successful Go semantic
  validation; a malformed artifact may remain explicitly downloadable without
  becoming valid analysis evidence.
- Do not delete in-flight evidence merely to admit new work.
- Upstream authentication failure blocks new acquisition but does not
  retroactively revoke a complete current-scope copy.
- Workspace-wide unsafe failure terminates service; recoverable artifact failure
  remains request-scoped.

## Acceptance signals

- Joined acquisition, waiter cancellation, capacity, TTL, pinning, removal,
  expiration, scope rotation, disk-full, restart, and shutdown are covered.
- Application availability and local-handle availability remain separate.
- Raw pass-through preserves exact bytes, request bounds, cancellation, target
  scope, and acquisition-time authorization without changing cache accounting.
- Local paths never become browser or protocol identifiers.

## Detailed-planning focus

Research streaming limits, atomic installation, safe filenames, cache accounting,
raw attachment headers and cancellation, locking, eviction ordering, last-use
refresh, query pinning, cleanup recovery, workspace fatality criteria, and UI
confirmation behavior.

## Out of scope

NDJSON semantic parsing, durable archive, cross-process adoption, and history.
