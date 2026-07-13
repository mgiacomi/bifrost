# Possible Future Improvement: Skill Execution Projection

## Status

**Research observation only. No framework change is currently planned or recommended.**

This document preserves a possible developer-experience and operability concern so that later sample and production evidence can be compared with it. It is not an approved feature, implementation plan, backlog commitment, or proposed API.

Revisit the concern after representative nested skill trees have produced successful and failed execution traces, especially if developers or samples must independently reconstruct a narrative of which skills ran and why.

## Potential Concern

Bifrost records a detailed hierarchical execution trace, projects a smaller linear journal, and provides a CLI that reconstructs a technical frame tree from persisted trace records. Each surface is useful, but they do not currently provide one canonical, developer-facing explanation of a composed skill execution.

A skill or run developer may want to answer a narrower set of questions than a forensic trace is designed to answer:

- which public skill was invoked;
- which child skills or Java capabilities were selected;
- what parent path led to each invocation;
- where planning, evidence, validation, retry, and failure affected that path;
- which parts are safe and useful to show in a sample, support response, or development tool.

The current CLI tree is primarily a technical frame tree. It may contain mission, planning, model-call, step, and tool-invocation frames. That is not necessarily the same structure as the public skill tree a developer authored or the concise HTN path they want to understand.

The possible gap is therefore not absence of trace data or absence of a tree renderer. It is the absence of a stable, sanitized, skill-level projection that framework and tooling surfaces can share.

## Current Behavior to Preserve

- The canonical execution trace remains the authoritative, immutable record of what occurred.
- Trace records retain technical detail needed for debugging, auditing, and future analysis.
- The developer journal remains smaller and safer than exposing the complete trace by default.
- Nested execution uses hierarchical frames rather than a second mutable call tree.
- Trace persistence remains configurable and is not required merely to execute a skill.
- The CLI can continue to provide a detailed forensic view in addition to any concise skill view.

Any future projection should be derived from authoritative execution records rather than become a parallel execution state that can drift from them.

## Distinct Structures

A future investigation must keep at least three structures conceptually separate:

1. **Skill-execution hierarchy**: public skill or capability invocations and their semantic parent relationships.
2. **Technical frame hierarchy**: missions, planning, model calls, steps, tools, retries, and other runtime frames.
3. **Plan dependency structure**: planned tasks and prerequisite relationships, which may not form the same tree as runtime invocation.

A generic `depth` value is ambiguous across these structures. Prefer deriving and naming the relevant relationship rather than storing redundant depth on every journal or trace event.

## Candidate Direction for Research

Investigate a layered model with clear ownership:

### Canonical execution trace

The complete, versioned forensic record. It should remain suitable for detailed debugging and audit needs, subject to existing capture and redaction rules.

### Canonical developer projection

An immutable, sanitized view derived centrally from the trace. Without prescribing an API, useful concepts may include:

- one node per skill or capability invocation;
- stable invocation identity and semantic parent identity;
- public skill or capability name;
- execution mode, outcome, and duration where deterministically available;
- ordered highlights such as plan creation, capability selection, child entry and exit, evidence recording and validation, retry, final response, and failure;
- references back to authoritative frames or trace records for deeper inspection.

The projection should normalize existing low-level events when possible rather than add duplicate trace events solely for presentation wording.

### Renderers and consumers

The CLI, sample documentation, JSON endpoints, support tooling, and a future SkillBuilder could render the same semantic projection differently. They should not each acquire independent knowledge of raw event payload fields and frame interpretation.

This layering would allow both a concise "show me the HTN path" view and the existing detailed forensic tree without treating either as a replacement for the other.

## Trace Taxonomy Questions

Current source inspection shows that mission frames are opened as `ROOT_MISSION`, including nested mission execution, while `SKILL_EXECUTION` also exists as a frame type. A nested mission's immediate technical parent may be a tool-invocation or step frame rather than another mission frame.

Future research should determine whether:

- current frame types accurately describe root and nested mission semantics;
- the semantic parent skill should be derived by walking technical ancestors;
- an explicit semantic relationship is needed in the canonical trace;
- `SKILL_EXECUTION` has a distinct intended purpose or represents unused taxonomy;
- a projection can be correct for incomplete or exceptionally terminated traces.

These questions should be resolved before publishing a stable skill-depth or skill-stack contract.

## Sanitization and Access

Raw execution traces may contain mission inputs, prompts, model requests, tool arguments, tool results, and provider details. A teaching or developer projection must not assume that raw trace content is safe to display or return over a public application boundary.

A future design should define:

- which projected fields are stable and safe by default;
- how existing trace redaction and access-control rules apply;
- whether callers can obtain the projection after exceptional completion;
- how persisted and in-memory projections remain semantically consistent;
- how schema versions are handled by non-Java consumers such as the CLI.

## Directions Not Currently Justified

This observation does not currently justify:

- adding a stored `depth` field to every journal or trace entry;
- exposing the raw execution trace as the normal developer API;
- turning the journal into an unbounded duplicate of the trace;
- adding duplicate runtime events only to support preferred CLI wording;
- committing to a public projection class or endpoint before representative traces are studied;
- creating sample-specific narrative logic that becomes a second trace interpreter;
- weakening trace fidelity, redaction, validation, evidence, or failure behavior for a cleaner demonstration.

## Relationship to Nested Execution Observability

This concern is related to [Possible Future Improvement: Nested Execution Observability](possible-nested-execution-observability.md), but it has a different primary question.

Nested execution observability asks whether a run developer can diagnose failure location, execution phase, and resource use across a composed run. This note asks whether Bifrost should expose one stable semantic explanation of the skill path for development, teaching, and tooling. A future canonical projection might support both concerns, but the evidence should be preserved separately until research establishes the appropriate implementation boundary.

## Signals That Should Trigger Reconsideration

Append a new occurrence when one or more of these appears independently:

- multiple samples require custom code or prose to reconstruct the same skill path;
- run developers cannot distinguish public skill nesting from technical frame nesting;
- framework consumers independently build similar trace-to-skill-tree projectors;
- Java and CLI interpretations of trace semantics drift or disagree;
- a future SkillBuilder needs a stable execution explanation rather than raw trace internals;
- support or production tooling repeatedly needs a sanitized skill-level summary;
- exceptional executions contain useful trace data that supported invocation surfaces cannot expose safely.

## Questions for a Future Investigation

1. What skill hierarchy and highlights can be derived correctly from existing trace records?
2. What does `SKILL_EXECUTION` mean relative to root and nested mission frames?
3. How should semantic skill parents relate to intervening step and tool frames?
4. Which current journal omissions are intentional safety or usability choices?
5. Should the journal remain linear while a separate hierarchical view is introduced?
6. What stable vocabulary covers direct, planning, mapped, nested, retry, evidence, and failure behavior?
7. Which projection fields can be safely displayed by default?
8. How can callers receive the projection on both success and exceptional completion?
9. Should the CLI consume a framework-produced projection, a versioned interchange format, or a shared projection specification?
10. Which golden traces are needed to test success, failure, retry, mapped execution, evidence validation, and incomplete persistence?

## Suggested Research Sequence

1. Complete the approved public-skill identity and mapped-manifest simplification work.
2. Build and run a representative nested sample such as Incident Commander.
3. Preserve successful and failed golden traces from that sample.
4. Compare the raw trace, projected journal, and CLI interpretations.
5. Define the smallest coherent semantic projection and its redaction contract.
6. Create bounded framework and tooling tickets only after their ownership and dependencies are clear.

The sample should become operational before the projection is designed, but the projection should be considered before the sample's trace narrative is treated as polished public teaching material.

## Implementation Anchors for Research

- [`TraceRecord.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/TraceRecord.java) defines the canonical trace record relationships and payloads.
- [`TraceRecordType.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/TraceRecordType.java) defines the recorded event vocabulary.
- [`TraceFrameType.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/TraceFrameType.java) defines the technical frame taxonomy.
- [`DefaultExecutionStateService.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java) opens mission frames and records execution state events.
- [`ExecutionJournalProjector.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/ExecutionJournalProjector.java) defines the current linear developer journal projection.
- [`DefaultSkillTemplate.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/DefaultSkillTemplate.java) defines the supported invocation observer surface.
- [`main.go`](../../../bifrost-cli/main.go) reconstructs and renders the current CLI technical frame tree.

These anchors identify current research starting points; they are not proposed change locations.

## Occurrence Log

### 1. Incident Commander sample review - 2026-07-13

While reviewing the planned three-level Incident Commander sample, another LLM observed that the raw journal would be too dense for teaching and proposed a first-class skill stack, stable highlight events, and a one-call HTN path view.

Source inspection found that the canonical trace already contains hierarchical frames and relevant event types, and that the CLI already reconstructs a technical frame tree. It also found that the journal deliberately omits frame lifecycle and evidence events, while the CLI independently interprets raw trace semantics. The remaining plausible gap is a canonical, sanitized skill-level projection shared by framework and tooling consumers.

**Evidence strength:** one representative sample review, supported by source inspection and the project owner's independent expectation that CLI traces need broader review. Sufficient to preserve for research, but insufficient to approve a public projection API or implementation ticket.

Future occurrences should record the date, tree shape, consumer, question the developer could not answer, available trace and journal data, workaround, redaction considerations, and whether the evidence is independent of this sample.
