# PR 09 — TargetContext and Selected-Target Lifecycle

## Status

Proposed ticket brief. Depends on PRs 06 and 08.

## Outcome

Create the single transport-neutral authority for selected-target configuration,
credential use, application identity, compatibility, scope rotation,
cancellation, status, and shared domain errors.

## In scope

- Add target URL/trust/timeout validation and an application protocol client.
- Keep the application key in process memory and send it only to the selected
  origin.
- Probe authenticated instance status and require exact
  `consoleCompatibilityVersion`.
- Own immutable scope snapshots and rotate opaque `targetScopeId` on every
  authoritative target or instance change.
- Cancel and suppress stale work and clear application-derived state through
  registered owners.
- Add the side-effect-free `ConsoleStatusSnapshot` and transport-neutral domain
  error contract.
- Add paired browser target entry, replacement, status, retry, and reconnect UI.
- Add the protected no-echo terminal alternative for application-key entry,
  backed by the same process-memory credential provider and `TargetContext`
  lifecycle as browser and future MCP operations.

## Guardrails

- Disable upstream redirects and reject embedded URL credentials.
- Only `TargetContext` commits target identity or rotates scope.
- Status reports independent facts, not aggregate health, and performs no probe.
- Browser handlers do not own target semantics needed by MCP.
- Cached evidence and current target authentication remain separate facts.
- The terminal alternative requires an already selected non-secret target,
  creates no terminal-owned target session, and never places the key in command
  arguments, shell history, ordinary configuration, URLs, or logs.

## Acceptance signals

- Target changes, credential replacement, application restart, compatibility
  mismatch, host/proxy rejection, cancellation races, and late results are tested.
- Browser and protected-terminal key entry populate the same credential provider,
  produce the same scope and compatibility behavior, and retain no second
  credential lifecycle.
- Stale target data cannot be returned under a new scope.
- Shared errors retain stable codes and sanitized safe details.

## Detailed-planning focus

Research Phase 1 DTOs and problems, HTTP/TLS client policy, credential comparison
and logging, no-echo terminal input, scope-owner registration, cancellation,
status consumers, browser state reset, and adapter error mapping.

## Out of scope

Continuous SSE, trace acquisition, MCP routes, and multi-target support.
