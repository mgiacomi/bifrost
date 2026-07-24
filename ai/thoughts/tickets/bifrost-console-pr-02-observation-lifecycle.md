# PR 02 — Observation Lifecycle and Live Projection Core

## Status

Proposed ticket brief. Depends on PR 01.

## Outcome

Create one internal per-execution observation lifecycle that projects bounded
live state only after successful canonical append while leaving execution and
canonical finalization semantics unchanged.

## In scope

- Add the optional no-op/enabled observation handle and exact-once close path.
- Publish logical records after complete append, including chunk writes.
- Add the deterministic activity projector, an active registry containing
  exactly one bounded-size snapshot for every authoritative live execution, and
  a bounded cursor replay buffer.
- Track registry ordinals, stream cursors, current summaries, and invocation and
  usage counts.
- Fail live monitoring closed after unexpected projection/publication failure
  while isolating the execution.
- Remove active state on every completion, failure, timeout, quota, and cleanup
  path, including core finalization failure.

## Guardrails

- No filesystem, network, callback, fan-out, or blocking work under the session
  serialization boundary.
- No public observer SPI, general event bus, durable queue, retry loop, or
  projection reconstruction.
- Preserve existing canonical append, finalization, and completed-journal
  failure behavior.
- Registry cardinality follows authoritative engine concurrency and has no
  independent observability cap, admission limit, sampling, or omission
  behavior.
- Keep every snapshot and event bounded; retain no logical payload after
  projection.

## Acceptance signals

- Concurrent executions and nested frames do not leak state or ordering.
- Every authoritative live execution is represented without observability
  limiting, rejecting, sampling, or hiding concurrent executions.
- Chunked logical records project correctly after complete storage append.
- Optional observability failures cannot replace or suppress execution results.
- Terminal activity and active-entry removal follow the settled exceptional
  finalization rules.

## Detailed-planning focus

Locate the central append seam, session lock, cleanup boundary, journal
summarization helpers, concurrency tests, error diagnostics, and lifecycle
extension exposure.

## Out of scope

HTTP endpoints, SSE sockets, artifact catalogs, and browser delivery.
