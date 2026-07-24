# PR 06 — Finalized Artifact Streaming and Phase 1 Integration

## Status

Proposed ticket brief. Depends on PR 05.

## Outcome

Complete the Phase 1 application boundary with safe finalized-artifact streaming,
cross-boundary fixtures, sample wiring, and end-to-end verification.

## In scope

- Stream the exact finalized UTF-8 NDJSON artifact by opaque trace ID.
- Enforce expiration, download admission, request bounds, cancellation, and
  safe response headers and filenames.
- Preserve core retention ownership when downloads start before expiration.
- Finalize Java-produced golden fixtures and expected consumed semantics for the
  future Go tests.
- Add adapter integration tests spanning status, snapshots, SSE, catalog,
  availability, download, authentication, and problems.
- Update sample configuration, security examples, and operational documentation.

## Guardrails

- Do not repackage, rewrite, normalize, redact, or reconstruct the download.
- Do not expose the internal artifact path as an identifier.
- No manifest, archive, digest, completeness marker, or independent artifact
  version.
- Java/Go compatibility remains the exact release-string umbrella contract.

## Acceptance signals

- An available artifact is already obtainable when terminal activity says so.
- Expired and missing resources remain semantically distinct only where the
  protocol has direct evidence.
- Representative success, failure, chunk, usage, retry, and malformed fixtures
  are produced reproducibly.
- Phase 1 completion criteria are mapped to automated or manual evidence.

## Detailed-planning focus

Research streaming response primitives, file-expiry races, admission constants,
fixture placement and generation, sample security configuration, release-version
injection, and boundary documentation ownership.

## Out of scope

Go parsing, browser UI, artifact caching, and historical file discovery.

