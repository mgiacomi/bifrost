# ENG-026: Harden ExecutionTrace Contract and Centralize Trace Semantics

## Status

Draft

## Summary

Follow ENG-025 with a dedicated hardening pass that stabilizes `ExecutionTrace` as a core framework subsystem.

This ticket is not about adding more trace features. It is about making the trace contract explicit, reducing semantic drift between runtime layers, centralizing trace write ownership, and proving that active and finalized traces are reliable enough to be trusted as the canonical runtime record.

## Motivation

ENG-025 established the first version of file-backed execution tracing, but repeated review rounds found a pattern of issues that cluster around the same boundaries:

- multiple runtime layers can still shape trace semantics
- some trace records are still emitted at feature seams instead of a canonical recorder boundary
- frame, model, tool, advisor, readback, finalization, and retention invariants are not enforced from one place
- several correctness failures only appear when considering live reads, rehydration, retention, concurrency, or cross-flow consistency together

Because deep tracing is one of the highest-value capabilities in Bifrost, we need a hardening pass that treats trace correctness as a subsystem contract rather than as a set of local instrumentation details.

## Current Problem

The implementation currently works well enough to pass the module test suite, but confidence is limited because the trace contract is distributed across:

- `BifrostSession`
- `DefaultExecutionStateService`
- mission and planning execution paths
- tool callback execution
- advisor mutation paths
- `DefaultExecutionTraceHandle`
- reader and journal projection code

This distribution makes it too easy for one flow to emit slightly different semantics than another flow even when all tests still pass.

## Goals

- define and enforce a single execution-trace contract for runtime writers and readers
- centralize trace-semantic ownership so record shapes and frame linkage do not depend on the calling feature path
- guarantee reconstructable frame graphs and event sequences across mission, planning, tool, and advisor flows
- guarantee that active trace readback, finalization, retention, and rehydration behave consistently
- add contract-oriented tests that fail on semantic drift, not just on implementation details

## Non-Goals

- adding new end-user trace features beyond what ENG-025 already scoped
- implementing replay
- redesigning unrelated framework runtime abstractions outside the trace subsystem
- replacing the NDJSON storage strategy unless the hardening work proves a material blocker

## Decisions Locked In

- `ExecutionTrace` remains the canonical runtime record
- `ExecutionJournal` remains a derived sanitized projection
- the hardening work should prefer centralization and invariant enforcement over incremental patching at individual call sites
- if the current instrumentation seams are too distributed, we should consolidate them behind a narrower recorder boundary instead of documenting the spread as acceptable
- the outcome of this ticket should be a subsystem that is easier to reason about, easier to review, and harder to regress

## Required Outcomes

### 1. Explicit Trace Contract

Document the required invariants for:

- allowed trace writers
- frame lifecycle and parent-child linkage
- model request and response capture
- tool requested, started, completed, and failed sequencing
- advisor request and response mutation linkage
- active-trace reads while writes are still in progress
- finalization as an append barrier
- retention and deletion semantics
- rehydration semantics when trace files are present or absent

### 2. Canonical Trace-Semantic Boundary

Reduce trace-semantic ownership to a small number of approved boundaries so that:

- feature code does not decide record taxonomy ad hoc
- model tracing is not limited to only selected runtime paths
- advisor tracing does not bypass frame-aware trace semantics
- journal projection depends on a stable raw trace contract instead of compensating for writer inconsistencies

### 3. Contract-Level Verification

Add tests that prove:

- the same logical event produces the same trace semantics regardless of call path
- active traces can be read safely during chunked payload writes
- finalized traces do not accept new records
- retained traces cannot be corrupted by session-id collisions or overlapping runs
- JSON round-trip and rehydration preserve the intended post-run behavior
- derived journal projection remains readable without hiding real distinct events

## Acceptance Criteria

- there is a single documented trace contract artifact linked from the implementation plan
- trace writes for model, tool, advisor, and frame lifecycle events flow through the approved canonical boundary or boundaries only
- raw trace data can reconstruct frame identity, frame type, route, and parent-child relationships for representative mission, planning, tool, and advisor flows
- active trace reads behave deterministically and do not fail on legitimate in-flight chunked payloads
- trace completion is a real terminal boundary for later writes
- trace file naming and retention behavior are deterministic and safe under concurrent or repeated runs
- the final implementation is backed by contract-focused tests, not only narrow unit assertions

## Suggested Deliverables

- a research artifact describing the current distributed ownership and failure themes
- an implementation plan for trace hardening
- a dedicated testing plan emphasizing contract and concurrency coverage
- code changes that centralize trace semantics and close the identified correctness gaps

## Related Work

- Original feature ticket: `ai/thoughts/tickets/eng-025-execution-trace.md`
- Recommended research artifact: `ai/thoughts/research/2026-03-25-ENG-026-execution-trace-hardening.md`

