# PR 14 — Trace Explorer Foundation

## Status

Proposed ticket brief. Depends on PR 13.

## Outcome

Present shared trace evidence as a navigable browser explorer without moving
authoritative calculations into frontend state.

## In scope

- Add acquired-trace summary and browser-owned explorer route/state.
- Present hierarchy-first navigation and selected-frame breadcrumbs/detail.
- Add coordinated timeline, usage, records, attempts, validation, failure, and
  raw-record views.
- Retrieve payloads only on deliberate request and support bounded complete
  inspection through continuations.
- Add deliberate unchanged raw-artifact download through PR 12's separate
  pass-through path without implicitly acquiring or retaining an analysis copy.
- Add current-scope links to trace, frame, record, failure, and related evidence.
- Handle stale scope, expired handles, removed artifacts, malformed evidence,
  and unavailable payloads.

## Guardrails

- Browser formats, groups, sorts, filters, and navigates shared facts but does
  not recalculate authoritative hierarchy, duration, usage, or failure.
- Raw content renders as text, not HTML or Markdown, and cannot form executable
  links.
- Raw-artifact download is an explicit attachment action and remains distinct
  from raw-record and reconstructed-payload inspection.
- Deep links are current-scope navigation conveniences, not durable bookmarks.
- Progressive disclosure does not prevent deliberate raw or complete inspection.

## Acceptance signals

- Hierarchy, repeated invocation, timeline, usage, record, payload, failure, and
  cross-view navigation have component and browser coverage.
- Raw-artifact download requires deliberate action and clearly preserves the
  separate application-availability and local-analysis-copy lifecycles.
- Keyboard, focus, forced-colors, zoom, reduced-motion, and responsive behavior
  meet the Phase 2 baseline.
- Large lists and payloads remain bounded and continuable.

## Detailed-planning focus

Research accessible tree/table/tab primitives, URL state, view coordination,
virtualization need, timeline rendering, payload confirmation, stale-link reset,
raw-download confirmation and availability, focus management, and evidence
terminology.

## Out of scope

Automatic root-cause analysis, repository lookup, MCP, and final workflow polish.
