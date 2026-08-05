# PR 15 — Diagnostic Workflows and Phase 2 Hardening

## Status

Proposed ticket brief. Depends on PR 14.

## Outcome

Complete the settled developer workflows and prove the browser console is safe,
accessible, lifecycle-correct, and releasable on supported targets.

## Settled review contracts

- Use the authoritative unfamiliar-skill-path requirement IDs
  `WF-SP-R1` through `WF-SP-R14`; `WF-US-*` is not a valid alias.
- Spring-created traces record the five run-start configured quotas in optional
  `TRACE_STARTED.configuredLimits`. Standalone/internal construction paths may
  omit the object. When present, all five members are required integers in
  `0..2147483647`; absence means limit comparison is unavailable and is not a
  legacy-reader promise.
- Usage comparison shows numerator and denominator and formats a percentage
  deterministically with at most two decimal places. A zero limit has an
  undefined proportion; an absent limit is unavailable. Neither case produces
  a percentage.
- Automated browser accessibility checks use `@axe-core/playwright` after
  verifying a compatible pinned version, and fail configured serious or
  critical findings. Manual screen-reader/assistive-technology acceptance is
  not part of Phase 2.
- Publishing tags use `v<root-POM-version>` and require a non-SNAPSHOT POM
  version after stripping the leading `v`. Manual workflow dispatch validates
  without publishing by default. Native runner labels are verified against the
  available GitHub-hosted runner inventory during implementation.
- The representative evidence matrix includes ordinary workflow traces,
  repeated frames, the existing 20,000-deep hierarchy stress case, more than
  100 browser rows, a multi-megabyte payload read in 64-KiB ranges, incomplete
  evidence, and exact/one-over structural and request bounds.

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
- Code review and completion evidence use only canonical `WF-SP-*` unfamiliar-
  skill-path IDs and apply the configured-limit absence/presence rules above.

## Detailed-planning focus

Audit workflow coverage against PRs 10–14, define representative trace sizes and
targets, accessibility/manual verification, release CI, licenses, checksums,
runtime README, and remaining skill-authoring guidance.

## Out of scope

MCP, Agent Skills, remote access, and cross-version traces.
