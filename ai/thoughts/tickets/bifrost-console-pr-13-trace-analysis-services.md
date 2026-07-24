# PR 13 — Trace Parser, Indexes, and Shared Calculations

## Status

Proposed ticket brief. Depends on PR 12.

## Outcome

Turn an acquired current-release artifact into validated, bounded,
transport-neutral evidence queries shared by browser and MCP.

## In scope

- Stream-parse bounded NDJSON and retain raw-record addressing.
- Validate identity, ordering, enums, frame relationships, terminal completion,
  and consumed semantic fields.
- Reconstruct logical payloads from envelopes and chunk records while preserving
  raw framework records.
- Build disk/memory-conscious indexes for hierarchy, records, attempts,
  validation, failures, timing, and usage.
- Calculate inclusive/self duration, direct/descendant/inclusive usage, retry and
  attempt attribution, terminal outcome, failure links, gaps, and uncertainty.
- Add bounded query/search/range primitives and handle-bound continuations.
- Exercise Java-produced fixtures with semantic expected results.

## Guardrails

- No full-trace requirement in Go or browser memory.
- Reject contradictory consumed semantics rather than guessing.
- Missing usage is unknown, not zero; overlapping inclusive totals are not
  summed.
- Do not infer hierarchy, retries, failure, or causality from adjacency or text.
- Unconsumed metadata remains opaque diagnostic JSON.

## Acceptance signals

- Successful, failed, aborted, nested, chunked, retried, unavailable, malformed,
  oversized, truncated, and contradictory artifacts are covered.
- Browser and future MCP can obtain identical calculations and domain errors.
- Queries are finite, cancellable, continuable, and scoped to a valid handle.

## Detailed-planning focus

Research the Phase 1 fixture corpus, legacy CLI algorithms worth independently
salvaging, streaming parser libraries, index storage, continuation integrity,
calculation definitions, query bounds, cancellation, and malicious nesting.

## Out of scope

Browser presentation, speculative diagnosis, historical formats, and MCP DTOs.

