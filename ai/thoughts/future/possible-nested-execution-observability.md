# Possible Future Improvement: Nested Execution Observability

## Status

**Research observation only. No framework change is currently planned or recommended.**

This document preserves a possible operability concern so later reports and runtime evidence can be compared with it. It is not an approved feature, implementation plan, backlog commitment, or proposed API.

Revisit the concern when representative skill trees show that developers cannot readily identify where composed execution spent its model budget or why a nested mission failed.

## Potential Concern

A planning-enabled YAML skill can require model interactions to create a plan, select and validate steps, retry rejected output, and synthesize a final response. A selected step may invoke another YAML skill, whose own manifest independently chooses direct or planning execution. Consequently, model calls, latency, retries, and failure locations can grow across a composed tree.

Bifrost currently has session quotas, aggregate session usage, skill-tagged usage metrics, hierarchical trace frames, and several skill-specific terminal messages. These provide important safeguards and diagnostic evidence. The possible gap is whether the developer-facing result makes that evidence sufficiently easy to interpret for one failed or unexpectedly expensive run.

A run developer should ideally be able to answer:

- which skill and execution phase failed;
- the parent skill path that led to it;
- whether the failure was a deterministic guardrail, planning failure, model-output failure, tool failure, or contract-validation failure;
- which limits were configured and what values were observed;
- where model calls and usage accumulated across the selected execution path.

The concern is observability of existing execution semantics, not a request to hide or automatically repair failures.

## Current Behavior to Preserve

- `planning_mode: true` explicitly selects step-based planning for a YAML skill.
- An LLM-backed YAML skill without that setting uses direct mission execution, including when nested. A separate single-shot specialist mode is not presently needed.
- Each nested YAML mission opens a hierarchical execution frame.
- Session quotas remain authoritative across the composed run.
- Session usage is tracked in aggregate, and runtime metrics can attribute some usage to a skill name.
- Step-loop terminal messages already distinguish several failures and commonly include the skill name and step number.
- Traces and journals preserve route and frame identifiers for recorded events.

Any future work should build on these concepts rather than introduce a parallel execution or accounting model without evidence.

## Candidate Research Areas

### Structured hierarchical failures

Investigate whether failures should expose a stable structured description containing concepts such as:

- session and execution frame identity;
- failing skill and parent skill path;
- execution phase;
- stable failure category or code;
- configured limit and observed value when relevant;
- a safe human-readable explanation;
- a reference to the authoritative trace or journal.

This should not require every exception layer to construct and concatenate a fragile stack string. It must also avoid leaking prompts, sensitive inputs, tool arguments, or provider details that trace redaction rules protect.

### Per-run usage attribution

Investigate whether existing usage records and hierarchical frames can produce a developer-facing breakdown by skill execution and parent path. Useful dimensions may include:

- direct versus planning execution;
- model-call count and available usage units;
- tool calls and validation retries;
- depth and parent frame for interpretation, not necessarily as quota boundaries;
- session totals, configured limits, and remaining capacity.

Prefer a projection derived from authoritative, immutable execution records. Avoid a second mutable accounting tree that can drift from quota enforcement.

### Failure-path access

Verify whether a caller can consistently obtain the relevant trace or journal when an entry or nested mission terminates exceptionally. A rich trace does not solve the operability problem if the run developer cannot locate or receive it from the supported invocation surface.

### Related execution projection research

A canonical skill-level projection may eventually provide some of the parent-path, failure, and usage context described here. The separate [Possible Future Improvement: Skill Execution Projection](possible-skill-execution-projection.md) note preserves the broader teaching and tooling concern without expanding this note beyond nested failure and resource observability. Future research should determine whether the concerns share one implementation mechanism; the notes do not presume one ticket boundary.

## Directions Not Currently Justified

This observation does not currently justify:

- per-depth quotas or budget allocation;
- automatic budget changes based only on nesting depth;
- model-visible "calls remaining" instructions;
- a new single-shot nested-skill execution mode;
- silently simplifying, skipping, or repairing a plan as budget runs low;
- weakening evidence, authorization, timeout, retry, or validation safeguards;
- redesigning Bifrost around the limitations of very small local models;
- declaring a universal minimum model size from one sample.

Depth is not a reliable cost boundary. A direct skill deep in a tree may use less model capacity than a retry-heavy root planner, and model/provider usage units may differ. Any enforceable subtree budget would need a demonstrated production ownership or isolation requirement beyond convenient accounting.

## Model Capability and Prompt Weight

Small-model failure in a deep sample does not by itself establish a framework defect. Sample guidance should name the configurations actually tested and should not imply that success on a shallow skill generalizes to a deeper planning tree.

Prompt and tool-schema weight is still worth measuring because unnecessary context increases cost and latency for capable production models as well. Research should use trace data and representative benchmarks to distinguish:

- insufficient model planning or tool-use capability;
- excessive prompt or schema overhead;
- deterministic quota or timeout exhaustion;
- contract or validation failure;
- a framework defect.

The goal is diagnosis and production efficiency, not accommodation of tiny models at the expense of coherent semantics.

## Constraints on Any Future Direction

Evaluate a future proposal through the [Bifrost Framework Feature Design Lens](../framework-feature-design-lens.md). In particular:

- keep the normal `SkillTemplate` call understandable without exposing internal planner mechanics;
- make failure categories and usage attribution locally comprehensible to skill and run developers;
- derive diagnostic information from authoritative run-scoped state;
- preserve immutability, trace fidelity, redaction, authorization, and replay behavior;
- avoid turning diagnostic values into model-controlled inputs;
- distinguish observation from enforcement;
- introduce new quota concepts only when evidence demonstrates a real ownership or isolation need;
- retain visible, deterministic failure rather than hiding a bad plan or exceeded limit.

## Signals That Should Trigger Reconsideration

Append a new occurrence when one or more of these appears independently:

- run developers cannot identify which nested skill or phase caused a production failure;
- support investigations repeatedly require manual reconstruction of frame relationships;
- aggregate session usage cannot explain material cost or latency differences between runs;
- operators need per-run attribution across reused skills or independently owned subtrees;
- exceptions lose actionable planning, evidence, quota, or child-mission context at the entry boundary;
- sample or integration testing reveals substantial prompt/schema overhead with capable production models;
- multiple applications independently build similar trace post-processing to obtain skill-path cost or failure summaries.

Small-model unreliability alone is not a trigger unless the same investigation reveals a model-independent observability or efficiency problem.

## Questions for a Future Investigation

1. What information can already be derived from trace frames, records, session usage, and metrics without changing execution?
2. Which failure details are lost or obscured as nested capability exceptions cross tool and mission boundaries?
3. Can callers consistently obtain a trace or journal after exceptional completion?
4. What stable phase and failure-category vocabulary would cover direct, planning, tool, quota, timeout, output, and evidence failures?
5. Which usage measurements are exact, heuristic, or unavailable for each supported provider?
6. Should attribution identify a skill definition, one invocation frame, a parent path, or all three?
7. What belongs in an exception, returned failure object, journal projection, metrics, or actuator-style diagnostic surface?
8. How will redaction and access control prevent diagnostic improvements from disclosing sensitive mission data?
9. What representative capable-model benchmarks show that prompt or schema weight is material?
10. Is observation sufficient, or is there independent evidence for enforceable per-skill or subtree budgets?

## Implementation Anchors for Research

- [`ExecutionCoordinator.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionCoordinator.java) selects direct or step-based execution and creates mission frames.
- [`DefaultMissionExecutionEngine.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/DefaultMissionExecutionEngine.java) implements direct mission execution.
- [`StepLoopMissionExecutionEngine.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/step/StepLoopMissionExecutionEngine.java) implements planning-step execution and records several terminal failures.
- [`DefaultPlanningService.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/planning/DefaultPlanningService.java) performs plan generation and quality checks.
- [`SessionUsageSnapshot.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/usage/SessionUsageSnapshot.java) defines current aggregate session usage.
- [`DefaultSessionUsageService.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/usage/DefaultSessionUsageService.java) records usage and enforces session quotas.
- [`ExecutionJournalProjector.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionJournalProjector.java) defines the current developer-facing journal projection.

These anchors identify current research starting points; they are not proposed change locations.

## Occurrence Log

### 1. Incident Commander sample review - 2026-07-12

While reviewing the planned three-level Incident Commander sample, another LLM observed that nested planning can multiply model calls and that failures may appear to developers as undifferentiated model flakiness. It proposed budget hints, stronger terminal errors, a single-shot nested specialist, and cost observability by depth.

Review found that direct nested specialist execution already exists through the absence of `planning_mode: true`. Existing quotas, usage accounting, hierarchical traces, and skill-specific terminal messages address part of the concern. The remaining plausible gap is a convenient per-run projection of hierarchical cost and structured failure context.

**Evidence strength:** one representative sample review supported by source inspection; sufficient to preserve as a research observation, but insufficient to approve a public feature or new quota semantics.

Future occurrences should record the date, tree shape, tested model/provider, failure or cost symptom, available trace evidence, developer workaround, and whether the evidence is independent of this sample.
