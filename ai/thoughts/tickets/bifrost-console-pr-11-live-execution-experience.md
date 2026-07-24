# PR 11 — Live Activity and Active-Execution Detail

## Status

Proposed ticket brief. Depends on PR 10.

## Outcome

Deliver one bounded continuous activity interval, resilient browser relay, and
the selected live-execution experience used to investigate a slow execution.

## In scope

- Maintain one upstream SSE connection for the selected target scope.
- Store one bounded recent-activity window with cursor range, observation time,
  and explicit gap/reset facts.
- Clear the window before admitting events after `STALE_CURSOR`, changed
  `instanceId`, or target-scope rotation.
- Relay activity to multiple tabs with bounded per-tab state and reconnect.
- Update active lists and selected execution summaries without losing selection.
- Present sticky current summary, active path, recent narrative, continuity,
  freshness, terminal transition, and trace-availability action.

## Guardrails

- Browser and connection events remain separate from Bifrost activity.
- Do not infer stuck, slow, outage, deadlock, importance, or cause from elapsed
  time, repetition, or silence.
- Do not combine events from opposite sides of a continuity boundary.
- Completion preserves context and never navigates automatically.
- Missing live activity does not become detailed active-trace inspection.

## Acceptance signals

- Replay, duplicate delivery, gap reset, reconnect, tab backpressure, missed
  completion, finalization failure, and target change are covered.
- The selected execution remains stable as current facts update.
- The recent-activity query seam is reusable by MCP without owning another
  upstream subscription.

## Detailed-planning focus

Research SSE client lifecycle, activity reducers, relay framing, browser
reconnect, tab bounds, timing updates, accessibility announcements, reduced
motion, and slow-execution workflow degraded paths.

## Out of scope

Complete hierarchy, full trace payloads, artifact acquisition, and causal claims.

