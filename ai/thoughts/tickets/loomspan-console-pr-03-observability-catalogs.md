# PR 03 — Skill and Finalized-Trace Catalogs

## Status

Proposed ticket brief. Depends on PR 02.

## Outcome

Provide application services with bounded-size entries for registered skill YAML
and TTL-governed finalized current-process trace metadata without transferring
trace ownership to observability.

## In scope

- Catalog registered skills by unique name with normalized skills-root-relative
  `sourcePath` and unchanged UTF-8 YAML.
- Add the core-issued finalized-artifact descriptor and completion-grace
  integration.
- Catalog only artifacts finalized successfully by the current process.
- Track catalog ordinals, metadata TTL, size, effective expiration, and
  availability facts.
- Coordinate terminal activity enrichment, catalog publication, and guaranteed
  active-entry removal.
- Add deterministic keyset traversal services for both catalogs.

## Guardrails

- Never scan trace storage or expose paths as resource identifiers.
- Core alone creates, writes, finalizes, retains, and deletes canonical files.
- Catalog expiry removes discoverability only and never deletes the artifact.
- Catalog cardinality follows current-process completions inside the metadata
  TTL and has no independent entry-count or aggregate-metadata-memory cap.
- YAML remains authoritative text; do not invent an effective-definition DTO.
- `sourcePath` is descriptive metadata and is never resolved from caller input.

## Acceptance signals

- Restart begins with an empty trace catalog.
- `NEVER`, `ONERROR`, `ALWAYS`, grace, and catalog TTL remain distinct facts.
- Failed finalization cannot publish a trustworthy available artifact.
- Skill ordering and lookup are deterministic, duplicate `sourcePath` values
  remain allowed and independently addressable by unique registered skill name,
  and YAML bytes remain unchanged.

## Detailed-planning focus

Research skill discovery/resource ownership, persistence-policy cleanup,
finalization descriptors, shutdown behavior, TTL scheduling, pagination races,
and configuration/skill-authoring documentation impact.

## Out of scope

Spring routes, trace downloads, Go acquisition, YAML parsing, and history.
