# Ticket - Add Skill Input Contracts and a Spring-Native SkillTemplate

Date: 2026-03-30

## Summary
Phase 7's original goal still matters, but the repo is no longer starting from zero.

Recent work already landed several runtime hardening pieces that `phase7.md` treated as future direction:

- evidence contracts now exist in YAML and runtime enforcement
- planning can reject plans with missing evidence coverage
- final responses can be retried or rejected when evidence is missing
- step-loop prompting now binds tasks to specific tools and discourages parent-skill/tool confusion
- step-loop validation already rejects missing required tool arguments when a concrete tool schema is available

That means the remaining Phase 7 work is narrower and more concrete than the original note suggests.

The real unresolved gap is input-side consistency:

- YAML skills still have no first-class `input_schema`
- unmapped YAML skills still expose generic map-shaped tool inputs
- Java callers still invoke skills through raw `Map<String, Object>` plumbing
- nested YAML skill invocation still serializes mission inputs into prompt text instead of moving through a canonical skill-input abstraction
- tool argument validation currently uses only the visible tool schema at step time, not a framework-owned input-contract model reused across all call paths

This ticket turns Phase 7 into an implementation/PR that closes that gap without re-solving output or evidence contracts.

## Project Stance
This library is still in active development.

For this ticket, we should optimize for a cleaner long-term architecture rather than preserving immature APIs, endpoints, or internal seams.

Explicit guidance:

- breaking changes are acceptable
- we do not need to preserve legacy code paths just because they already exist
- compatibility shims should be avoided unless they materially simplify the migration
- reducing technical debt is more important than minimizing churn

The implementation should prefer replacing weak or transitional patterns over layering new abstractions on top of them.

## Review Findings
### 1. Phase 7 is partially implemented already
The motivating duplicate-invoice trace is no longer evidence that supportability is missing.

In the reviewed trace:

- the first plan overused `invoiceParser`
- planning rejected it with both `SINGLE_TOOL_OVERUSE` and `evidence-coverage`
- the accepted execution used both `invoiceParser` and `expenseLookup`
- final output passed both `STRUCTURED_OUTPUT_RECORDED` and `EVIDENCE_VALIDATION_PASSED`

So the repo should not take `phase7.md` literally as a net-new architecture phase. It should treat it as a stale design note whose output/evidence portions have already shipped.

### 2. Input contracts are still missing as a framework concept
`YamlSkillManifest` supports `output_schema` and `evidence_contract`, but there is no corresponding `input_schema`.

Consequences:

- unmapped YAML skills register with `CapabilityToolDescriptor.generic(...)`
- that generic descriptor resolves to JSON schema for `Map<String, Object>`
- the framework cannot distinguish "empty but structurally valid object" from "meaningful skill input" for those skills

### 3. Java invocation ergonomics are still map-first
The current Java entry point is effectively:

```java
executionRouter.execute(metadata, Map.of("payload", invoiceText), session, null);
```

This shows up in the sample app and in the router/invoker seam:

- `CapabilityExecutionRouter.execute(...)`
- `CapabilityInvoker.invoke(Map<String, Object> arguments)`

That is workable, but it is not a framework-endorsed ergonomic API and it does not give Bifrost a central place for contract-aware conversion, validation, defaults, or exception shaping.

### 4. Step-loop argument validation is useful but shallow
`StepActionValidator` currently improves safety, but only at the narrowest layer:

- it inspects the selected tool's JSON schema
- it checks only top-level required fields
- it does not validate types, nested required fields, enums, `additionalProperties`, or schema-derived coercion rules
- it is only consulted for model-proposed `CALL_TOOL` actions

That means the framework still lacks one reusable input validation story for:

- Java callers
- root skill entry
- nested YAML skill invocation
- step-loop tool calls

### 5. Step-loop prompting does not expose input-shape guidance
`StepPromptBuilder` shows:

- ready tasks
- exact `taskId`
- exact `toolName`
- final response schema when finalizing

But it does not show a concrete argument shape per tool beyond the generic `"toolArguments": { ... }` contract. When a visible tool has a useful schema, the framework does not yet render that schema into the prompt in a focused way.

## Problem Statement
Today Bifrost has strong output-side contracts and weaker input-side contracts.

The asymmetry is:

- output contracts are explicit, validated, and reused across planning, execution, and trace
- input contracts are partly implicit, partly tool-schema-derived, and mostly absent for YAML-only skills

This leaves the framework with four related problems:

- YAML-only skills cannot declare meaningful required inputs in the manifest
- unmapped YAML skills expose generic map-shaped tool signatures to planners and executors
- Java callers do not have a first-class invocation API beyond maps
- validation logic is fragmented across method signatures, tool definitions, and step-loop guardrails

Phase 7 should now solve that narrower problem directly.

## Goals
- Introduce an explicit input contract for YAML skills.
- Keep the canonical contract JSON-shaped so the same model works for LLM prompting, trace, and cross-skill calls.
- Define authority and inheritance rules between YAML-declared contracts and Java-derived method signatures.
- Add a Spring-native `SkillTemplate` API so Java callers do not need to assemble raw maps everywhere.
- Reuse one normalized input-contract model across root execution, nested skill execution, and step-loop/tool validation.
- Improve prompt guidance for tool arguments when concrete input contracts exist.
- Remove transitional or weak input paths where doing so materially improves the architecture.

## Non-Goals
- Do not redesign output contracts or evidence contracts again.
- Do not require generated typed clients in v1.
- Do not attempt full JSON Schema feature parity in the first iteration.
- Do not replace Spring AI tool schemas; reuse and align with them.

## Current State in Code
### Already Present
- `output_schema` manifest support and validation
- `evidence_contract` manifest support and validation
- evidence-aware planning validation
- evidence-aware final response validation
- task-to-tool binding in step-loop prompts
- required top-level tool argument checks in `StepActionValidator`
- Java method input schemas generated from `@SkillMethod` signatures

### Still Missing
- `input_schema` on `YamlSkillManifest`
- a runtime `SkillInputContract` model
- YAML contract inheritance from mapped Java targets
- a reusable input validator shared across call paths
- prompt rendering for concrete tool argument shape
- a Java `SkillTemplate`

## Core Design Decision
The canonical skill input contract should be a constrained JSON-schema-compatible object model owned by Bifrost.

Recommendation:

- add `input_schema` to YAML using the same manifest shape already used for `output_schema`
- use a shared schema model rather than introducing a second unrelated input DSL
- keep the initial supported subset aligned with the existing output-schema subset:
  - object
  - array
  - string
  - number
  - integer
  - boolean
  - required
  - properties
  - items
  - enum
  - additionalProperties
  - description
  - format

Why this direction:

- it is already familiar inside the repo
- it is naturally renderable in prompts
- it avoids inventing a Bifrost-only schema language
- it gives the framework enough structure for validation without chasing full JSON Schema complexity

## Authority and Inheritance Rules
The framework needs deterministic contract authority rules.

Recommendation:

1. Java `@SkillMethod` capability:
- source of truth is the method signature-derived schema

2. mapped YAML skill without `input_schema`:
- inherit the mapped target's tool input schema as the effective skill input contract

3. mapped YAML skill with `input_schema`:
- YAML becomes authoritative, but startup validation must reject incompatible divergence from the mapped target contract

4. pure YAML skill with `input_schema`:
- YAML is authoritative

5. pure YAML skill without `input_schema`:
- may continue to use generic object input only as an explicit short-term fallback during migration, not as a design target

For v1, "incompatible divergence" should be conservative and simple:

- YAML cannot remove a required field required by the mapped Java target
- YAML cannot redefine a known property to a conflicting scalar/object/array type

This prevents silent drift without requiring a perfect schema-diff engine.

## Proposed Implementation
### 1. Add `input_schema` to `YamlSkillManifest`
Extend the manifest with:

```java
@JsonProperty("input_schema")
private OutputSchemaManifest inputSchema;
```

Using the same schema object for input and output keeps the MVP small and avoids duplicating schema parsing rules.

### 2. Validate `input_schema` at catalog load
Extend `YamlSkillCatalog` to:

- validate `input_schema` with the same structural rules used for `output_schema`
- require root input schema type to be `object`
- warn on overly deep or broad schemas using the same complexity heuristics
- when `mapping.target_id` is present, validate compatibility with the mapped target's tool schema

### 3. Introduce a runtime `SkillInputContract`
Add an immutable runtime type that wraps the normalized effective input schema for a capability.

Suggested responsibilities:

- expose the canonical schema tree
- expose required top-level fields
- answer whether the contract is generic or explicit
- render simplified prompt guidance

This should live separately from raw YAML manifests so runtime code does not depend directly on mutable manifest classes.

### 4. Carry the effective input contract through capability metadata
Extend `CapabilityMetadata` and/or `CapabilityToolDescriptor` so YAML skills and Java capabilities can expose:

- the raw tool schema for Spring AI integration
- the normalized Bifrost input contract for framework validation and ergonomics

The framework should stop treating tool schema and skill input contract as accidental synonyms.

### 5. Add a shared input validator
Create a reusable validator that accepts:

```java
SkillInputValidationResult validate(Map<String, Object> input, SkillInputContract contract)
```

V1 validation should support:

- missing required fields
- null for required fields
- scalar type mismatches
- array vs object mismatches
- nested object validation
- enum validation
- `additionalProperties: false` at object nodes

This validator should be used by:

- Java-side `SkillTemplate`
- root capability execution before invoking a YAML skill
- nested YAML skill execution in `CapabilityExecutionRouter`
- step-loop `CALL_TOOL` validation when a concrete contract exists

### 6. Introduce `SkillTemplate`
Add a Spring bean with a narrow, boring surface area.

Suggested MVP:

```java
skillTemplate.invoke("duplicateInvoiceChecker", requestObject);
skillTemplate.invoke("duplicateInvoiceChecker", Map.of("payload", payload));
skillTemplate.invoke("duplicateInvoiceChecker", input -> input.put("payload", payload));
```

Recommended behavior:

- resolve capability by name
- convert typed Java input object to canonical map form
- validate against effective input contract
- execute within the supplied or created session
- return raw result initially, with typed response mapping as a follow-up

Recommended non-goals for v1:

- no service-locator behavior
- no magical retries or hidden session scoping
- no mandatory typed-response API

Because breaking changes are acceptable here, `SkillTemplate` should be treated as the new primary Java invocation API rather than as an optional wrapper added beside raw map-first invocation forever.

### 7. Reuse `SkillTemplate` internally where it helps
Once `SkillTemplate` exists, use it as the endorsed Java entry point.

Initial consumers:

- sample app/controller code
- nested YAML-to-YAML invocation paths where practical

This keeps public examples aligned with the framework's intended usage and avoids cementing legacy map-first seams.

### 8. Improve step-loop prompt guidance for tool arguments
Extend `StepPromptBuilder` so that when a ready task's bound tool has a concrete non-generic schema, the prompt includes a compact argument-shape hint.

Example:

```json
"toolArguments": {
  "payload": "<string>"
}
```

or, for a single ready task:

- Required toolArguments fields: `payload`
- Do not omit required fields.

This should be concise and only rendered when it adds signal.

### 9. Upgrade `StepActionValidator` to use the shared validator
Replace the current top-level-required-only check with contract-aware validation.

Benefits:

- one validation implementation instead of ad hoc schema parsing in the step loop
- nested/object/type validation becomes available immediately
- step-loop behavior matches Java/root invocation behavior

## Acceptance Criteria
- YAML skills may optionally declare `input_schema`.
- `input_schema` is validated at startup with the same schema subset rules used by `output_schema`.
- mapped YAML skills inherit input contracts from Java targets when `input_schema` is absent.
- mapped YAML skills with explicit `input_schema` fail startup when the YAML contract conflicts with the mapped Java contract.
- a runtime `SkillInputContract` is available for both Java and YAML capabilities.
- a shared input validator enforces contract rules across root invocation, nested invocation, and step-loop tool calls.
- `SkillTemplate` is available as the framework-endorsed Java API for invoking skills.
- sample code demonstrates `SkillTemplate` instead of raw `executionRouter.execute(..., Map.of(...))` usage.
- step-loop prompts include concise argument-shape guidance when a concrete contract exists.
- obsolete or redundant map-first invocation paths are either removed or clearly demoted so they do not remain the de facto API.

## Definition of Done
- Manifest support and compatibility validation are covered by unit tests.
- Shared input validator is covered by unit tests for required fields, nested objects, enums, additional properties, and type mismatch cases.
- `SkillTemplate` is wired into Spring auto-configuration and covered by tests.
- Nested YAML invocation and root Java invocation both use the same input-contract validation path.
- `StepActionValidator` delegates to the shared validator when a concrete contract exists.
- At least one integration test demonstrates that an empty or semantically weak argument object is rejected for a contract-backed YAML skill.
- The sample `duplicateInvoiceChecker` path still works unchanged from a user point of view after migrating example invocation code to `SkillTemplate`.
- any retained compatibility path is justified explicitly in code or PR notes rather than carried forward by default.

## Test Plan
### Manifest and Contract Loading
1. YAML-only skill with valid `input_schema` loads successfully.
2. YAML-only skill with invalid `input_schema` fails startup.
3. mapped YAML skill without `input_schema` inherits Java-derived contract.
4. mapped YAML skill with incompatible `input_schema` fails startup.

### Shared Input Validation
1. Missing required top-level field is rejected.
2. Missing nested required field is rejected.
3. Wrong scalar type is rejected.
4. Unknown property is rejected when `additionalProperties: false`.
5. Enum violation is rejected.
6. Generic/no-contract path remains permissive.

### SkillTemplate
1. typed request object is converted and validated before invocation.
2. map input is validated before invocation.
3. invalid input fails fast with clear exception messaging.
4. valid input reaches the underlying capability successfully.

### Step-Loop
1. prompt includes argument-shape guidance for a concrete tool schema.
2. `CALL_TOOL` with missing nested/typed input is rejected before execution.
3. generic tool schemas still avoid noisy false positives.

### Backward Compatibility
1. mapped YAML skills continue to inherit tool schemas from Java methods.
2. duplicate-invoice evidence-contract behavior remains unchanged.
3. if generic-input YAML skills are retained temporarily, that behavior is intentional and covered by tests rather than accidental.

## Suggested Files to Touch
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolDescriptor.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityInvoker.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepActionValidator.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- new input-contract and `SkillTemplate` types under `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/...`
- sample updates in `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/SampleController.java`
- corresponding tests under `src/test/java/...`

## Risks
- Reusing the output schema manifest type for inputs is efficient, but it may tempt future coupling between unrelated validation concerns.
- Compatibility checks between YAML and Java-derived schemas can become complex if v1 tries to be too clever.
- A weak `SkillTemplate` that only wraps `Map.of(...)` will not justify its surface area.
- Retaining too many legacy seams in the name of caution would preserve the exact technical debt this ticket is supposed to remove.

## Open Questions
- Should `SkillTemplate` support typed response mapping in v1, or ship with input ergonomics only?
  Recommendation: input ergonomics only in v1.

- Should root skill invocation fail fast on invalid inputs before any mission frame opens?
  Recommendation: yes, unless trace semantics require opening a root frame first and recording validation failure there.

- Should step-loop prompts render full schema examples or only required-field hints?
  Recommendation: start with compact examples or required-field hints only; keep prompts short.

- Should generic-input skills surface a warning in trace or startup logs?
  Recommendation: yes, as a warning only, not a failure.

## Recommendation
Land Phase 7 as an input-contract and invocation-ergonomics phase, not as a reimplementation of evidence contracts.

The repo has already solved much of the output/evidence half. The remaining high-value work is to make skill inputs explicit, validated, and pleasant to use across all entry points:

- `input_schema` for YAML skills
- deterministic authority/inheritance with Java-backed skills
- one shared validator
- one endorsed Java API in `SkillTemplate`

Because the library is still in development, we should take this opportunity to simplify aggressively: remove weak map-first seams where possible, accept breaking changes, and pay down technical debt instead of preserving transitional APIs indefinitely.
