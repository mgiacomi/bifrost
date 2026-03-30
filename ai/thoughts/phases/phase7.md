# Phase 7 - Skill Input Contracts and Invocation Ergonomics

Date: 2026-03-30

## Goal
Define a durable, explicit input-contract model for Bifrost skills and pair it with a developer-friendly invocation API so skill inputs can move cleanly across LLM planning, Java callers, nested skill calls, and future validation layers without relying on ad hoc maps or fragile prompt conventions.

## Why This Phase Exists
Phase 6 clarified an important boundary:
- the runtime can enforce contracts that already exist
- but many YAML skills still expose generic inputs
- and generic inputs sharply limit how much meaningful validation the framework can perform

That limitation showed up during manual testing as semantically weak tool calls such as empty argument objects that were structurally acceptable but not meaningfully grounded in a task contract.

The framework now needs a more intentional answer to a bigger question:

How should inputs be represented, validated, and invoked across different call paths?

This is not just about adding an `input_schema` field to YAML. The harder design problem is making skill inputs usable and consistent across:
- skill-to-skill LLM orchestration
- Java-to-skill invocation
- mapped YAML skills backed by Java methods
- future tooling such as plan validation, editor support, and trace inspection

## Problem Statement
Today the framework has an asymmetry:
- Java `@SkillMethod` capabilities can expose meaningful input structure from method signatures.
- Mapped YAML skills can often inherit that structure from their Java target.
- Unmapped YAML skills fall back to generic map-shaped inputs.

This creates several problems:
- the runtime cannot reliably distinguish meaningful inputs from empty or weak inputs
- planning and execution prompts cannot present strong input expectations for all skills
- Java callers do not have a clear, first-class abstraction for constructing skill inputs
- nested skill invocation risks drifting toward loosely typed maps even when the use case is stable and repeatable

Phase 7 should solve this as an architecture problem, not as a patch for one trace pattern.

## Core Questions
Phase 7 needs to answer these design questions explicitly:

1. What is the canonical input contract for a skill?
   Is it JSON Schema, a simpler Bifrost-owned schema model, or a hybrid?

2. What should LLM-facing skill-to-skill calls use?
   Should nested skill calls operate on JSON-shaped objects because that is the most natural LLM-native contract?

3. What should Java callers use?
   Should Java callers pass raw `Map<String, Object>`, typed request objects, a builder, or a framework helper abstraction?

4. How do we avoid duplication?
   If YAML declares an input contract and Java also declares a method signature, which one is authoritative and how do they stay aligned?

5. How do we keep the API pleasant?
   A technically pure schema system that is painful for developers to use would be a bad fit for a Spring-native framework.

## Design Principles
- The framework should prefer explicit contracts over generic maps.
- The LLM-facing contract should remain JSON-native and easy to render in prompts.
- Java integration should feel Spring-like and ergonomic rather than schema-heavy.
- The framework should avoid forcing developers to hand-maintain the same contract in multiple places.
- Validation should be composable and gradual, not all-or-nothing.
- The design should support future traceability, editor tooling, and runtime diagnostics.

## Recommendation
The recommended direction for Phase 7 is:

1. introduce explicit skill input contracts
2. keep the canonical transport shape JSON-like
3. add a Spring-inspired `SkillTemplate` API for Java callers

The key idea is that the framework should not force Java developers to manually assemble untyped maps for every invocation, even if the underlying wire contract remains JSON-shaped.

Instead, Bifrost should expose a higher-level helper similar in spirit to Spring's `JdbcTemplate`, `RestTemplate`, or other template-style APIs: something that makes common skill-invocation paths simple, repeatable, and hard to misuse.

## Why a SkillTemplate Direction Looks Strong
Of the candidate ideas, `SkillTemplate` appears to offer the best balance.

### Raw Map Inputs
Pros:
- easy to implement
- flexible

Cons:
- weak discoverability
- weak validation ergonomics
- easy to misuse
- encourages technical debt in Java callers

### Raw JSON Everywhere
Pros:
- natural fit for LLM-facing contracts
- good for prompt rendering and trace inspection

Cons:
- awkward as the primary Java developer API
- pushes callers toward stringly typed construction
- can make ordinary business code clumsy

### Spring-Inspired SkillTemplate
Pros:
- provides a clean Java entry point
- can accept multiple input styles while standardizing invocation behavior
- gives the framework a central place for conversion, validation, defaults, and error shaping
- feels natural in a Spring-native library

Cons:
- requires more up-front design
- can become too magical if responsibilities are not kept clear

The likely best architecture is:
- JSON-shaped contract at the skill boundary
- `SkillTemplate` as the Java-side convenience and orchestration layer

## Proposed Phase 7 Scope
### 1. Define Skill Input Contract Semantics
Decide how a skill declares its expected inputs.

This likely includes:
- field names
- required vs optional fields
- basic type information
- object and array support
- descriptive metadata for prompting and tooling

This phase should decide whether the contract is:
- full JSON Schema
- a constrained JSON-Schema-compatible subset
- or a Bifrost-specific schema model that can still be rendered as JSON Schema when needed

### 2. Define Contract Inheritance and Authority Rules
The framework needs clear rules for where input contracts come from.

Candidate cases:
- pure YAML skill with explicit declared input contract
- mapped YAML skill inheriting contract from Java target
- mapped YAML skill overriding or refining inherited contract
- direct Java `@SkillMethod` capability exposing a generated contract

This phase should define which source is authoritative in each case.

### 3. Introduce SkillTemplate
Design and implement a Java API that makes skill invocation ergonomic.

The template should likely support patterns such as:
- invoke a skill by name with a typed object
- invoke a skill by name with a map
- invoke a skill by name with a builder DSL
- return structured output in a predictable form

Possible responsibilities:
- input conversion to canonical JSON/object form
- contract-aware validation before execution
- default session/context bridging
- consistent exception shaping

Possible non-responsibilities:
- it should not become a giant service locator
- it should not hide all execution details so deeply that debugging becomes opaque

### 4. Unify Nested Skill Invocation Semantics
Nested skill calls should use the same contract model as top-level skill calls.

That means the framework should avoid one contract style for:
- LLM-visible skill-to-skill calls

and a completely different model for:
- Java-driven invocation

Different developer ergonomics are fine. Different underlying contract semantics are not.

### 5. Enable Better Runtime Validation
Once input contracts are explicit, the framework can improve:
- tool input validation
- plan validation
- prompt guidance
- nested skill safety
- trace diagnostics

Phase 7 should lay the groundwork for those improvements rather than implementing every downstream enhancement immediately.

## Proposed SkillTemplate Shape
The exact API should be designed carefully, but the rough direction could look like this:

```java
skillTemplate.invoke("duplicateInvoiceChecker", requestObject);
skillTemplate.invoke("duplicateInvoiceChecker", Map.of("payload", payload));
skillTemplate.invoke("duplicateInvoiceChecker", input -> input
    .put("payload", payload)
    .put("invoiceId", invoiceId));
```

Potential future variants:
- typed response mapping
- session-aware invocation
- fluent options for model overrides or execution hints

The important idea is not the exact method names yet. It is that Java callers should have one obvious, framework-endorsed path for invoking skills safely and ergonomically.

## Tradeoffs
## Benefits
- Stronger and more uniform input validation
- Better prompts because the framework can describe inputs more clearly
- Better Java developer experience
- Reduced reliance on raw maps
- Better long-term foundation for editor tooling and diagnostics

## Costs
- More design work than a narrow patch
- Potential migration work for existing skill manifests
- Risk of overlapping concerns between YAML contracts and Java signatures if authority rules are weak
- Risk of overdesign if the template becomes too abstract

## Explicitly Out of Scope for the First Iteration
- Solving every output-contract problem at the same time
- Full code generation for typed skill clients
- Rich UI/editor tooling
- Automatic inference of perfect contracts for every dynamic skill
- Supporting every advanced JSON Schema feature on day one

## Key Design Risks
### Contract Duplication
If both YAML and Java define the same fields independently, the framework will drift unless authority and inheritance rules are precise.

### Ergonomics vs Purity
A fully schema-centric design may be elegant internally but frustrating for ordinary Spring developers.

### Overly Generic Template
If `SkillTemplate` only wraps a map and adds little value, it will not justify its existence.

### Overly Magical Template
If `SkillTemplate` does too much hidden conversion or inference, developers may not trust it.

## Open Questions
- Should the canonical internal contract model be JSON Schema or a constrained Bifrost schema abstraction?
- How should mapped YAML skills inherit and refine Java-derived contracts?
- Should `SkillTemplate` support strongly typed responses in the first iteration, or start with input ergonomics only?
- How much builder DSL is actually helpful before the API becomes noisy?
- Should validation errors be fail-fast by default for Java callers?

## Suggested Deliverables
- A written contract model decision
- A written authority/inheritance decision
- Initial manifest support for explicit input contracts
- Initial `SkillTemplate` API and implementation
- Validation and trace integration for explicit input contracts
- Tests covering YAML-only skills, mapped skills, and Java-driven invocation

## Recommendation
Phase 7 should be the input-contract and invocation-ergonomics phase.

It should treat explicit skill input contracts as the architectural foundation, but it should deliver that foundation through a Spring-native developer experience rather than exposing raw schema mechanics everywhere.

The best current direction is:
- JSON-shaped contracts at the skill boundary
- a carefully designed `SkillTemplate` for Java callers
- clear inheritance rules between YAML skills and Java-backed capabilities

That approach gives Bifrost a better long-term answer than either "just use maps" or "just add input_schema and let everyone deal with raw JSON."
