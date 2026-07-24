# PR 05 — Live SSE Delivery

## Status

Proposed ticket brief. Depends on PR 04.

## Outcome

Deliver one multiplexed, bounded, resumable application activity stream without
allowing clients or network work to affect Bifrost execution.

## In scope

- Add authenticated SSE subscription after a requested resume cursor.
- Preserve instance-local cursor ordering and include `instanceId` in handshake
  and every envelope.
- Return `STALE_CURSOR` before streaming when replay is unavailable or instance
  identity changed.
- Add bounded per-subscriber pending delivery, write deadlines, and disconnect
  behavior.
- Add the fixed process-wide authenticated subscription admission limit.
- Close existing streams when live monitoring becomes unavailable.
- Test baseline-plus-stream races and reconnect behavior.

## Guardrails

- Never perform network delivery or subscriber callbacks under execution locks.
- Buffer overwrite never blocks execution or rejects newest current activity.
- Do not create exactly-once, durable, transactional, or gap-reconstruction
  semantics.
- One slow or disconnected subscriber cannot affect execution or other clients.

## Acceptance signals

- Cursor ordering, duplicate tolerance, stale replay, changed instance, capacity,
  write timeout, cancellation, and shutdown are covered.
- Active snapshots fail closed when live projection is known incomplete.
- Skill and finalized-trace operations remain usable after live failure.

## Detailed-planning focus

Research Spring MVC SSE lifecycle, async executor ownership, cancellation,
response-commit behavior, timeout handling, subscriber accounting, and safe
diagnostics.

## Out of scope

Browser relay, periodic browser refresh, trace download, and durable history.

