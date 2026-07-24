# PR 01 — Canonical Trace Semantics and Executable Fixtures

## Status

Proposed ticket brief. Depends on no earlier Bifrost Console PR.

## Outcome

Establish the current-release canonical evidence needed for authoritative Go
analysis: explicit attempt, retry, usage, outcome, and terminal-failure
relationships, backed by Java-produced semantic fixtures.

## In scope

- Remove the obsolete per-record `schemaVersion` surface and all in-repository
  propagation, serialized output, tests, and fixtures.
- Add opaque `retrySequenceId`, opaque `attemptId`, and positive
  `attemptNumber` at the physical model-attempt seam.
- Persist normalized response usage and the terminal session usage snapshot.
- Persist explicit terminal outcome and terminal failure identity.
- Keep trace, quota, metric, and session accounting derived from the same facts.
- Produce successful, failed, aborted, retry, nested-retry, validation,
  unavailable-usage, unattributed-usage, and malformed golden fixtures.

## Guardrails

- Classify trace records as an ephemeral diagnostic format.
- Use no legacy reader, schema migration, compatibility constructor, or dual
  record form.
- Do not infer relationships from adjacency, messages, or attempt number alone.
- Do not introduce a separate trace or container version.

## Acceptance signals

- Current Java writer, reader, tests, and fixtures agree on the new semantics.
- Attributed plus unattributed usage reconciles with terminal recorded totals.
- Non-terminal errors followed by success and linked terminal failure are
  distinguishable.
- Public-surface and Spring-extension-point review finds no accidental additions.

## Detailed-planning focus

Research the canonical append/finalization flow, physical provider-attempt seam,
all `schemaVersion` consumers, usage aggregation, metrics, quotas, validation
records, failure recording, fixture ownership, and skill-authoring impact.

## Out of scope

Live projection, REST/SSE, Go parsing, browser UI, and historical trace support.

