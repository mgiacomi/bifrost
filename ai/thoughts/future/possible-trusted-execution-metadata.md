# Possible Future Feature: Trusted Execution Metadata

## Status

**Observation only. No framework change is currently planned or recommended.**

This document preserves a possible future framework concern so that later reports can be compared with it. It is not an approved feature, implementation plan, backlog commitment, or proposed API.

Revisit the concern only when additional, independent use cases show that Bifrost lacks a coherent way to carry trusted execution metadata through a mission.

## Potential Concern

Some information belongs to the execution environment rather than to the model's business reasoning. Examples may include:

- authenticated identity and authorization claims;
- trusted tenant identity;
- correlation and trace identifiers;
- request provenance;
- deadlines and cancellation state;
- other runtime-owned invariants established by an authoritative source.

If nested skills or deterministic Java capabilities require this information, asking the model to copy it through ordinary tool arguments may give the model control over values it should not choose or modify. Independently reading it from unrelated global or thread-local application state can instead create hidden dependencies and unclear lifecycle behavior.

The possible gap is therefore not general argument forwarding. It is whether Bifrost eventually needs a narrow, production-grade mechanism for making authoritative execution metadata available across a run without making that metadata model-controlled or globally mutable.

## What This Does Not Cover

This observation does not currently justify:

- a free-form session or context map;
- automatic inheritance or merging of parent skill inputs;
- a mutable global or session-scoped property bag;
- silently filling in ordinary tool arguments omitted by a planner;
- optimizing orchestration behavior for small local models;
- treating ordinary business input as trusted metadata.

Values such as scenario names, ticket identifiers, hostnames, requested dates, and similar mission data should normally remain explicit skill or tool inputs. Repetition of those values may be an ergonomic signal, but it is a separate question from trusted execution metadata.

## Why This May Be a Framework Responsibility

This concern would remain even with a perfectly capable planner. Model capability does not make the model an authoritative source for tenant identity, authorization, deadlines, or correlation state.

A framework-level facility may eventually be appropriate when Bifrost itself owns the execution lifecycle and nested routing, and therefore is the layer capable of preserving:

- authoritative sourcing;
- immutability for the duration of the run;
- consistent propagation across nested execution;
- capability and tenant isolation;
- trace and redaction semantics;
- deterministic behavior across supported execution modes.

Those properties are more difficult for each application or skill author to reproduce safely.

## Constraints on Any Future Direction

If recurring evidence supports a feature, evaluate it through the [Bifrost Framework Feature Design Lens](../framework-feature-design-lens.md). At minimum, a future design should satisfy or explicitly address the following constraints.

### Developer model

- The normal `SkillTemplate` invocation path should remain obvious for developers running entry skills.
- Skill developers should be able to understand locally whether and why a capability receives runtime metadata.
- Ordinary callers should not need to understand nested propagation mechanics.
- The mechanism should add fewer concepts than the friction it removes.

### Ownership and trust

- Every value should have a defined authoritative source.
- The model must not be able to override protected values.
- Caller-supplied business data must not silently acquire the trust of runtime metadata.
- Capabilities should receive only metadata they are entitled to use.
- Tenant and authorization information should remain integrated with their security authority rather than duplicated into an arbitrary map.

### State and lifecycle

- Metadata should be scoped to one execution, never application-global.
- Values should be deeply immutable for that execution unless a specific item has deliberately designed mutation semantics.
- Nested, concurrent, asynchronous, cancellation, and timeout behavior must be defined.
- The design should not depend on accidental thread affinity.

### Contracts and observability

- A capability's dependency on metadata should be explicit and inspectable.
- Missing required metadata should fail deterministically with an actionable error.
- Traces should distinguish caller-, model-, and framework-supplied information.
- Sensitive values need explicit visibility and redaction policies.
- Replay and diagnostic behavior must be considered before the API is accepted.

### API shape

- Prefer narrow, named, typed concepts over `Map<String, Object>` or stringly typed keys.
- Do not assume that all metadata has identical trust, visibility, propagation, or redaction rules.
- Avoid automatic name-based binding and ambiguous override precedence.
- Do not expose the full execution session as a general service locator for Java leaves.

These are evaluation constraints, not a commitment to any particular syntax, annotation, manifest field, parameter-injection mechanism, or session representation.

## Signals That Should Trigger Reconsideration

Compare future requests with this document when one or more of the following occurs:

- multiple unrelated skill trees need the same trusted value at several nesting levels;
- application developers independently create similar propagation infrastructure around Bifrost;
- a customer must place tenant, identity, correlation, deadline, or provenance values in model-generated arguments;
- nested execution loses or inconsistently observes runtime-owned metadata;
- current behavior creates a concrete security, isolation, audit, cancellation, or operability problem;
- framework integrations need a consistent way to contribute authoritative per-run metadata;
- an existing Bifrost runtime concept cannot represent the requirement without misuse.

Repeated ordinary business-argument forwarding should be recorded separately unless the value is demonstrably runtime-owned and trusted.

## Questions for a Future Investigation

If the concern recurs, research should answer these questions before proposing an implementation:

1. What exact metadata is needed, and which component is authoritative for each value?
2. Which capabilities need access, and how is that access declared or constrained?
3. Does existing Spring Security, tracing, observation, locale, or request-context infrastructure already provide the correct source?
4. Must the model see the value, or should only deterministic runtime code receive it?
5. How will nested YAML skills, mapped YAML skills, and Java capabilities observe it consistently?
6. What must appear in traces, and what must be redacted?
7. How will the mechanism behave across virtual threads, asynchronous execution, cancellation, and replay?
8. Can the requirement be met by extending an existing typed Bifrost concept rather than adding a general context abstraction?
9. Is the evidence broad enough to justify a public framework API?

## Occurrence Log

### 1. Incident Commander sample review - 2026-07-12

While reviewing scenario forwarding for the planned Incident Commander HTN sample, a broader question arose about values such as correlation identifiers and tenant or locale information that may need to survive nested execution.

The immediate sample value, `scenario`, is ordinary business input and does not establish a trusted-execution-metadata requirement. The review nevertheless identified a distinct production concern: authoritative runtime values should not have to become model-controlled tool arguments merely to remain available during nested execution.

**Evidence strength:** design observation arising from one sample review; no demonstrated customer requirement and no implementation action warranted.

Future occurrences should be appended here with the date, motivating use case, exact metadata involved, current workaround, consequences, and whether the evidence is independent of earlier reports.

