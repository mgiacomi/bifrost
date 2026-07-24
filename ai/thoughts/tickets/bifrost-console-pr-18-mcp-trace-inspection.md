# PR 18 — Trace-Inspection MCP Surface

## Status

Proposed ticket brief. Depends on PR 17 and reuses PRs 12–13.

## Outcome

Expose progressive, caller-directed, continuable trace evidence to MCP through
the same artifact handles, acquisition lifecycle, calculations, and query
services used by the browser.

## In scope

- Add `bifrost_list_traces` and `bifrost_get_trace`.
- Add frame and record query tools, payload-range reading, and optional raw
  artifact-range reading.
- Advertise `bifrost.trace-inspection.v1` only with its complete required tool
  family, and advertise `bifrost.raw-artifact-inspection.v1` only when the
  optional raw-artifact operation is present.
- Map failure, hierarchy, timing, usage, attempt, validation, gap, availability,
  and uncertainty facts without recomputation.
- Bind continuations to artifact handle, target scope, query, filters, ordering,
  and installed-copy lifetime.
- Add supplementary resource templates while keeping essential workflows
  tool-complete.
- Test joined browser/MCP acquisition, shared pin/TTL/removal behavior,
  cancellation, expiration, malformed data, broad traversal, and multiple clients.

## Guardrails

- No MCP-specific acquisition, copy, cache, index, parser, calculation, last-use
  time, capacity policy, or error meaning.
- Default results are concise, but deliberate raw and complete inspection remains
  possible through finite calls.
- Returned runtime content cannot trigger another operation or any server-side
  authority.
- Missing evidence and unavailable evidence are not rewritten as conclusions.

## Acceptance signals

- All required `bifrost.trace-inspection.v1` operations and the optional raw
  capability conform to their advertised semantics.
- Capability conformance rejects either trace capability when any operation or
  semantic promise required by that capability is absent.
- Browser and MCP observe the same handle invalidation and calculated facts.
- Oversized, truncated, expired, removed, incompatible, and unsafe artifacts fail
  with precise shared meanings.

## Detailed-planning focus

Research query schemas, resource URI design, capability generations, continuation
signing/opacity, response framing limits, raw-range semantics, shared query
pinning, client cancellation, and representative broad-inspection behavior.

## Out of scope

Automatic diagnosis, full-runtime dump, server-driven context injection, Agent
Skill instructions, and historical trace migration.
