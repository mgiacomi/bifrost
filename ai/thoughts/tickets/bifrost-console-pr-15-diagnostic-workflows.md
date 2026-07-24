# PR 15 — Diagnostic Workflows and Phase 2 Hardening

## Status

Proposed ticket brief. Depends on PR 14.

## Outcome

Complete the settled developer workflows and prove the browser console is safe,
accessible, lifecycle-correct, and releasable on supported targets.

## In scope

- Add failure-focused trace entry and terminal live-to-trace transition.
- Add usage-focused attribution and limit-comparison workflow.
- Add unfamiliar nested-skill-path workflow with registered YAML coordination.
- Cover all settled unavailable, expired, malformed, gap, restart, scope-change,
  authentication, finalization, and incomplete-evidence paths.
- Complete application-content rendering security tests and response bounds.
- Add Playwright workflow, keyboard, accessibility-critical, reconnect, and
  target-reset coverage.
- Verify clean packaging for Windows x86-64, Linux x86-64, and macOS Apple
  Silicon with checksums and runtime documentation.

## Guardrails

- Present evidence and uncertainty without labeling cause, importance,
  correctness, excess, necessity, or actionability.
- Do not map `sourcePath` to the developer workspace.
- No automatic trace acquisition, automatic updater, installer, container,
  remote listener, database, or durable history.
- Hardening must fix discovered correctness gaps in their owning layer rather
  than add UI workarounds.

## Acceptance signals

- All four approved workflows map to executable browser scenarios and degraded
  paths, with representative coverage referencing the applicable workflow or
  most specific requirement IDs.
- Untrusted content cannot cross presentation or authority boundaries.
- Phase 2 architecture invariants and completion evidence are reviewed together.
- A packaged executable runs without JVM, Node.js, database, or shared target
  filesystem access.

## Detailed-planning focus

Audit workflow coverage against PRs 10–14, define representative trace sizes and
targets, accessibility/manual verification, release CI, licenses, checksums,
runtime README, and remaining skill-authoring guidance.

## Out of scope

MCP, Agent Skills, remote access, and cross-version traces.
