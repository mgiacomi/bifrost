# Ticket - Add Skill Input Contracts and a Spring-Native SkillTemplate

Date: 2026-03-30

## Summary
The repo is not starting from zero.

Recent work already landed several runtime hardening pieces around planning, evidence, and final-response validation:

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

This ticket focuses on the remaining input-side gap without re-solving output or evidence contracts.

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

This ticket should solve that narrower problem directly.

## Goals
- Introduce an explicit input contract for YAML skills.
- Keep the canonical contract JSON-shaped so the same model works for LLM prompting, trace, and cross-skill calls.
- Define which input contract is the source of truth for Java skills, YAML skills, and mapped YAML-to-Java skills, including when mapped skills derive their contract automatically from reflected Java method signatures.
- Add a Spring-native `SkillTemplate` API so Java callers do not need to assemble raw maps everywhere.
- Keep `SkillTemplate` focused on invoking YAML skills as the public developer abstraction, not raw `@SkillMethod` capabilities directly.
- Make `SkillTemplate` plus `SkillExecutionView` the single supported public execution/debug path for developers.
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
The canonical skill input contract should use the same manifest shape and supported schema subset as `output_schema`.

Concrete decision:

- add `input_schema` to YAML using the same manifest shape already used for `output_schema`
- use a shared schema model rather than introducing a second unrelated input DSL
- keep the initial supported subset the same as the existing `output_schema` subset:
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

Implementation intent:

- skill authors should learn one schema shape, not separate input and output schema languages
- manifest parsing and validation should reuse the same schema model already used for `output_schema`
- prompt rendering helpers should reuse the same schema representation where practical
- `additionalProperties` remains part of the input schema language; do not drop it for inputs just because some skills may not need it

Why this direction:

- it is already familiar inside the repo
- it is naturally renderable in prompts
- it avoids inventing a Bifrost-only schema language
- it gives the framework enough structure for validation without chasing full JSON Schema complexity

## Authority and Inheritance Rules
The framework needs deterministic contract authority rules.

For mapped YAML skills, the default behavior should be direct and automatic:

- if a YAML skill has `mapping.target_id` and does not declare `input_schema`, the framework should derive the effective input contract from the mapped Java target using the same reflected method-signature metadata already used to build the target tool schema

This should be the default path, not a special case.

Recommendation:

1. Java `@SkillMethod` capability:
- source of truth is the method signature-derived schema

2. mapped YAML skill without `input_schema`:
- inherit the mapped target's input contract automatically
- in practice, this means resolving the mapped capability and using its reflection-derived method-signature schema as the YAML skill's effective input contract

3. mapped YAML skill with `input_schema`:
- YAML is the effective published schema for that skill
- startup validation must compare the YAML schema to the reflected Java-derived schema and fail fast on any structural mismatch
- the framework should still use YAML if it is present, but only after startup validation confirms it matches the Java shape
- descriptions are allowed to differ freely, even if the developer chooses poor descriptions
- `format` must not differ from the Java-derived schema

4. pure YAML skill with `input_schema`:
- YAML is authoritative

5. pure YAML skill without `input_schema`:
- is allowed intentionally
- the framework should treat the skill as having a generic object-shaped input contract
- startup should not fail merely because `input_schema` is absent
- this path should remain permissive rather than inventing pseudo-validation from the skill description

For mapped YAML skills with explicit `input_schema`, startup validation should require exact structural parity with the mapped Java contract.

At minimum, the following must match:

- field names
- required vs optional status
- scalar types
- object vs array structure
- nested properties
- enum sets
- `additionalProperties`
- `items`
- `format`

Descriptions may differ and do not need to match.

This prevents silent drift without requiring a perfect schema-diff engine.

Implementation intent:

- do not require developers to hand-maintain the same input contract in both Java and YAML for mapped skills
- the framework should use reflection-derived Java metadata as the default source whenever a YAML skill points at a Java target
- explicit YAML `input_schema` on mapped skills should primarily exist for description/prompt refinement, not structural reshaping
- pure YAML skills may rely on descriptions alone when that is sufficient for the use case
- do not force explicit input contracts unless they provide real value
- do not emit startup or trace warnings merely because a pure YAML skill omits `input_schema`

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

`SkillInputValidationResult` should carry the normalized/coerced input map, not just pass/fail state.

Suggested shape:

```java
public record SkillInputValidationResult(
    boolean valid,
    Map<String, Object> normalizedInput,
    List<SkillInputValidationIssue> issues
) {}
```

Implementation intent:

- if coercion succeeds, downstream execution should use `normalizedInput`
- validation should not force callers to rerun conversion/coercion logic after a successful result
- one validator pass should produce both the decision and the execution-ready input payload

Define validation issues explicitly as well:

```java
public record SkillInputValidationIssue(
    String path,
    String code,
    String message,
    Object rejectedValue
) {}
```

Field guidance:

- `path`: canonical schema path such as `payload`, `options.includeHistory`, or `items[0].amount`
- `code`: stable machine-readable reason
- `message`: human-readable explanation
- `rejectedValue`: the offending value when available; `null` is acceptable for missing-field cases

Suggested issue codes:

- `missing_required`
- `type_mismatch`
- `unknown_field`
- `enum_mismatch`
- `invalid_date_format`
- `coercion_failed`

Implementation intent:

- keep `code` stable and low-cardinality
- use canonical field names in `path`
- derive exception messages and retry guidance from these issues rather than inventing a second error format

V1 validation should support:

- missing required fields
- null for required fields
- scalar type mismatches
- array vs object mismatches
- nested object validation
- enum validation
- `additionalProperties: false` at object nodes

Coercion policy:

- allow limited, deterministic coercion before final validation failure
- apply the same coercion rules everywhere after input has been normalized into the canonical `Map<String, Object>`
- this is intentional and aligns with Spring-style developer expectations around template/binding APIs
- do not create different coercion rules based on the original caller path

Allowed MVP coercions:

- string -> integer/number when the value parses cleanly
- string -> boolean for `true` / `false`
- documented `format: date` normalization

Guardrails:

- keep coercion narrow, deterministic, and testable
- avoid fuzzy guessing outside the explicitly documented date normalization rules
- use the normalized/coerced map for downstream execution

`format` policy:

- `format` must match when comparing mapped YAML `input_schema` against reflected Java-derived schema at startup
- `format` is advisory only for MVP runtime input validation
- runtime validation should enforce shape, requiredness, and basic types, but should not reject inputs solely on `format` semantics in this ticket
- one explicit exception is `format: date`, where limited deterministic normalization is allowed because LLMs commonly confuse date formatting

`format: date` normalization policy:

- allow a small set of documented input patterns
- normalize accepted values to ISO `YYYY-MM-DD`
- keep the parsing deterministic rather than fuzzy
- reject unsupported or ambiguous date strings outside the documented patterns

Allowed MVP patterns:

- `YYYY-MM-DD`
- `MM/DD/YYYY`
- `M/D/YYYY`
- `MM-DD-YYYY`

Interpretation rule:

- slash- or dash-separated month/day/year values should be interpreted as `MM/DD/YYYY`
- do not support natural-language dates
- do not infer dates from arbitrary text blobs
- do not silently truncate datetimes in this ticket

`additionalProperties` policy:

- keep `additionalProperties` as a supported part of `input_schema`
- enforce it for explicit input contracts
- require it to match when comparing mapped YAML `input_schema` against reflected Java-derived schema
- nested `additionalProperties` behavior should mirror `output_schema` semantics rather than inventing special input-only rules
- do not invent equivalent strictness for pure YAML skills that omit `input_schema`

This validator should be used by:

- Java-side `SkillTemplate`
- root capability execution before invoking a YAML skill
- nested YAML skill execution in `CapabilityExecutionRouter`
- step-loop `CALL_TOOL` validation when a concrete contract exists

Behavior when no explicit input contract exists:

- if the effective contract is generic because a pure YAML skill has no `input_schema`, validation should remain permissive
- do not attempt to infer required fields or types from the skill name or description
- generic-contract validation may still normalize `null`/empty maps consistently, but it should not reject input based on guessed structure

### 6. Introduce `SkillTemplate`
Add a Spring bean with a narrow, boring surface area.

Suggested MVP:

```java
String invoke(String skillName, Object input);
String invoke(String skillName, Map<String, Object> input);
String invoke(String skillName, Object input, Consumer<SkillExecutionView> observer);
String invoke(String skillName, Map<String, Object> input, Consumer<SkillExecutionView> observer);
```

Recommended behavior:

- resolve a YAML skill by `skillName`
- treat `invoke(String, Map<String, Object>)` as the primary internal execution path
- implement `invoke(String, Object)` as a convenience overload that converts the object into canonical map form and then delegates to the map overload
- validate against effective input contract
- perform limited Spring-style coercion after normalization and before rejecting input
- execute within an internally managed session in v1
- finalize the session/trace lifecycle before notifying any observer
- return the raw serialized result as `String` in v1

Recommended implementation detail:

- `invoke(String, Object)` should reject `null`
- `invoke(String, Map<String, Object>)` should normalize `null` to `Map.of()` only if the resolved contract is generic or explicitly allows empty input
- object-to-map conversion should use the framework `ObjectMapper` and preserve JSON-shaped structures
- the map overload should be the single place where capability lookup, validation, session execution, and observer notification are orchestrated
- observer overloads should delegate to the same internal execution method as the non-observer overloads
- input validation should happen before session creation and before any mission/root execution frame is opened
- invalid input should fail fast without producing a session, execution journal, or observer callback

Recommended non-goals for v1:

- no service-locator behavior
- no magical retries or hidden session scoping
- no mandatory typed-response API

Because breaking changes are acceptable here, `SkillTemplate` should be treated as the new primary Java invocation API rather than as an optional wrapper added beside raw map-first invocation forever.

Concrete decision for v1 return type:

- `SkillTemplate` returns `String`
- do not add `Object` or typed-response returns in this ticket
- if a strong use case emerges later, typed/object return support can be added in a follow-up

Public invocation boundary:

- `SkillTemplate` is for invoking YAML skills
- it should not be positioned as a generic façade for directly invoking every registered capability kind
- raw `@SkillMethod` capabilities remain internal building blocks and mapped targets behind YAML skills
- reflection-derived `@SkillMethod` metadata is still used internally for contract derivation and execution routing

#### Observer Contract
The observer overloads are the MVP escape hatch for developers who need execution diagnostics without forcing every caller onto a heavy return type.

Recommendation:

- the observer should run after execution completes and after the session trace has been finalized
- the observer should receive a read-only `SkillExecutionView`, not the mutable `BifrostSession`
- `SkillExecutionView` should expose only post-execution diagnostic state that is useful for debugging and tests

Suggested `SkillExecutionView` contents:

- `sessionId`
- `executionJournal`

For the MVP, keep this intentionally small:

- `sessionId`
- `executionJournal`

Suggested API shape:

```java
public record SkillExecutionView(
    String sessionId,
    ExecutionJournal executionJournal
) {}
```

Implementation note:

- the observer should be invoked after `BifrostSessionRunner` completes, not immediately after `executionRouter.execute(...)`
- this ensures the journal reflects finalized post-execution state
- if observer execution throws, the template should let that exception propagate rather than silently swallowing it

Why this shape:

- it gives developers direct access to the most useful execution summary for testing and debugging
- it avoids leaking session lifecycle internals into the public API
- it replaces the need for broad public debug/service endpoints
- it keeps the MVP small and easy to reason about

Deliberate omission for MVP:

- do not include `executionTrace` yet
- if developers later need trace details that are not represented in the journal, add them intentionally in a follow-up change rather than widening the first version preemptively

The observer is preferred over returning a wrapper like `SkillExecutionResult` in v1 because:

- most callers only want the result
- diagnostics are valuable but secondary
- a callback keeps the happy path minimal while still enabling rich inspection when needed

#### Session Ownership
For the MVP, `SkillTemplate` should own session creation and completion internally.

Ordinary callers should not need to run inside a `sessionRunner` paradigm.

If explicit caller-owned sessions are needed later, add separate overloads in a follow-up change rather than complicating the first release of `SkillTemplate`.

#### Concrete Execution Flow
Recommended control flow for the primary overload:

1. resolve the capability by `skillName`
2. convert input to canonical `Map<String, Object>` if needed
3. resolve the effective `SkillInputContract` for the capability
4. validate the input map against that contract
5. fail fast with a clear exception if validation fails
6. only after successful validation, execute the capability inside `BifrostSessionRunner.callWithNewSession(...)`
7. capture the returned `String` result
8. after session completion/finalization, build `SkillExecutionView`
9. invoke the observer if one was supplied
10. return the `String` result

Validation boundary decision:

- `SkillTemplate` is the public API boundary for input validation
- invalid input should be rejected before the runtime enters mission execution
- do not open root mission frames for requests that fail input validation
- do not create partial traces or journals for validation failures

Suggested failure behavior:

- unknown YAML `skillName` should throw `SkillException`
- invalid input should throw `SkillInputValidationException`
- execution failures should propagate unchanged unless the framework already transforms them lower in the stack

MVP exception model:

```java
public class SkillException extends RuntimeException { ... }
public class SkillInputValidationException extends SkillException { ... }
```

Exception policy:

- use `SkillException` for framework-owned top-level API failures such as unknown skill resolution
- use `SkillInputValidationException` for input-contract validation failures
- do not wrap every lower-level runtime failure in `SkillException` just for uniformity
- preserve original lower-layer exceptions unless the framework is intentionally defining a clearer public API boundary
- invalid-input failures should happen before `ExecutionCoordinator.execute(...)` is called

Suggested class split:

- public API package: `com.lokiscale.bifrost.skillapi`
- `com.lokiscale.bifrost.skillapi.SkillTemplate`
- `com.lokiscale.bifrost.skillapi.DefaultSkillTemplate`
- `com.lokiscale.bifrost.skillapi.SkillExecutionView`
- `com.lokiscale.bifrost.skillapi.SkillException`
- `com.lokiscale.bifrost.skillapi.SkillInputValidationException`

Suggested internal placement:

- keep YAML manifest/catalog work under `com.lokiscale.bifrost.skill`
- place shared input-contract/validation internals under `com.lokiscale.bifrost.runtime.input`

Intent:

- `skillapi` is the public developer-facing invocation boundary
- `runtime` remains internal execution machinery
- `skill` remains manifest/catalog territory

Suggested dependencies for `DefaultSkillTemplate`:

- `CapabilityRegistry`
- `CapabilityExecutionRouter`
- `BifrostSessionRunner`
- `ObjectMapper`
- shared input validator / contract resolver

This should be enough to implement the class without inventing additional orchestration layers.

#### Public API Direction
After this ticket lands, the intended public developer API should be:

- `SkillTemplate` for execution
- `SkillExecutionView` for post-execution inspection

The following should no longer be treated as supported public-facing developer APIs:

- public HTTP execution endpoints that exist only to invoke skills from sample code
- public HTTP debug/session inspection endpoints that exist only to expose trace, journal, or session internals
- direct use of lower-level routing/session classes as the recommended integration path

Recommendation:

- remove redundant sample/debug endpoints where possible
- if a tiny demo endpoint must remain for sample-app demonstration, keep it clearly sample-only and ensure it delegates to `SkillTemplate`
- do not preserve endpoint-based debugging once `SkillExecutionView` exists

### 7. Reuse `SkillTemplate` internally where it helps
Once `SkillTemplate` exists, use it as the endorsed Java entry point.

Initial consumers:

- sample app/controller code
- nested YAML-to-YAML invocation paths where practical

This keeps public examples aligned with the framework's intended usage and avoids cementing legacy map-first seams.

Recommended migration steps:

1. add `SkillTemplate` and wire it in auto-configuration
2. migrate sample controller endpoints to use `SkillTemplate`
3. replace public debug/session inspection endpoints with `SkillExecutionView`-based examples and tests
4. remove public execution/debug endpoints that existed mainly to compensate for missing programmatic execution visibility
5. only then remove or demote redundant raw execution entry points from public-facing examples

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

Rendering policy should be deterministic and framework-owned, not model-chosen.

Recommendation:

- first render a compact schema example block
- if the model fails argument validation, retry with a more verbose rendering
- both compact and verbose forms should come from the same schema renderer with different detail levels

#### Compact Rendering
Use on the first attempt when a concrete contract exists.

Rules:

- render a JSON-shaped example only
- include top-level fields
- include nested objects/arrays up to a shallow depth
- omit descriptions
- omit most prose
- include required fields naturally through the example shape

Example:

```json
{
  "payload": "<string>"
}
```

#### More Verbose Rendering
Use after argument validation failure, or immediately for more complex schemas if needed.

Rules:

- render the same JSON-shaped example
- add a short required-field list
- add concise field rules
- include enum choices when present
- state when unknown fields are not allowed
- include nested field guidance only to a limited depth
- do not dump full raw JSON Schema into the prompt

Example:

```json
{
  "invoiceId": "<string>",
  "options": {
    "includeHistory": <boolean>
  }
}
```

Rules:

- Required fields: `invoiceId`
- `invoiceId` must be a string
- `options.includeHistory` must be a boolean if present
- Do not add fields not shown above

#### Escalation Policy
- first attempt: compact rendering
- retry after invalid arguments: more verbose rendering plus the validation error
- avoid full raw-schema dumping in the MVP

#### Suggested Complexity Heuristics
- prefer compact rendering when:
  - max depth <= 2
  - total property count <= 6
  - enum sets are short
- prefer more verbose rendering when:
  - validation already failed
  - nesting is deeper
  - property count is larger
  - enums or nested structures would otherwise be ambiguous

#### Shared Renderer
Use one renderer with explicit detail levels, for example:

```java
renderToolArgumentsExample(schema, DetailLevel.COMPACT)
renderToolArgumentsExample(schema, DetailLevel.VERBOSE)
```

Value placeholders should be deterministic:

- string -> `"<string>"`
- number/integer -> `<number>`
- boolean -> `<boolean>`
- enum string -> `"<one of: A, B, C>"`
- object -> nested object
- array -> `[ <value> ]` or `[ { ... } ]`

### 9. Upgrade `StepActionValidator` to use the shared validator
Replace the current top-level-required-only check with contract-aware validation.

Benefits:

- one validation implementation instead of ad hoc schema parsing in the step loop
- nested/object/type validation becomes available immediately
- step-loop behavior matches Java/root invocation behavior

Specific behavior when no `input_schema` exists for a pure YAML skill:

- `StepActionValidator` should remain permissive
- it should not invent required arguments from descriptions or prompt text
- it should only enforce argument shape when the tool/capability exposes a concrete contract

## Acceptance Criteria
- YAML skills may optionally declare `input_schema`.
- `input_schema` is validated at startup with the same schema subset rules used by `output_schema`.
- mapped YAML skills inherit input contracts from Java targets when `input_schema` is absent.
- mapped YAML skills with explicit `input_schema` fail startup when the YAML contract conflicts with the mapped Java contract.
- a runtime `SkillInputContract` is available for both Java and YAML capabilities.
- a shared input validator enforces contract rules across root invocation, nested invocation, and step-loop tool calls.
- pure YAML skills without `input_schema` continue to load and execute with permissive generic-object input behavior.
- `SkillTemplate` is available as the framework-endorsed Java API for invoking skills.
- `invoke(String, Map<String, Object>)` is the primary implementation path, and the `Object` overload delegates to it.
- `SkillTemplate` validates input before creating a session or opening any mission frame.
- sample code demonstrates `SkillTemplate` instead of raw `executionRouter.execute(..., Map.of(...))` usage.
- `SkillExecutionView` is the supported post-execution inspection API for `sessionId` and `executionJournal`.
- step-loop prompts include concise argument-shape guidance when a concrete contract exists.
- obsolete or redundant map-first invocation paths are either removed or clearly demoted so they do not remain the de facto API.
- public execution/debug endpoints that existed only to compensate for missing programmatic APIs are removed or reduced to minimal sample-only wrappers over `SkillTemplate`.

## Definition of Done
- Manifest support and compatibility validation are covered by unit tests.
- Shared input validator is covered by unit tests for required fields, nested objects, enums, additional properties, and type mismatch cases.
- `SkillTemplate` is wired into Spring auto-configuration and covered by tests.
- Nested YAML invocation and root Java invocation both use the same input-contract validation path.
- `StepActionValidator` delegates to the shared validator when a concrete contract exists.
- At least one integration test demonstrates that an empty or semantically weak argument object is rejected for a contract-backed YAML skill.
- The sample `duplicateInvoiceChecker` path still works unchanged from a user point of view after migrating example invocation code to `SkillTemplate`.
- any retained compatibility path is justified explicitly in code or PR notes rather than carried forward by default.
- observer overloads are covered by tests and receive finalized trace/journal state.
- public debug/session endpoints made redundant by `SkillExecutionView` are removed, or any retained sample-only endpoint is explicitly justified.

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
5. observer overload receives a finalized read-only execution view after execution completes.
6. `invoke(String, Object)` delegates to `invoke(String, Map<String, Object>)`.
7. observer receives finalized `executionJournal`, not partially populated state.
8. invalid input does not create a session, journal, or observer callback.

### Auto-Configuration and Wiring
1. `SkillTemplate` is exposed as a Spring bean.
2. sample application code compiles and runs using the bean.
3. implementation does not require callers to manage `BifrostSessionRunner` directly for ordinary use.

### Public Surface Cleanup
1. public execution endpoints that exist only as a substitute for programmatic invocation are removed or minimized.
2. public debug/session inspection endpoints are removed in favor of `SkillExecutionView`.
3. any retained sample endpoint delegates to `SkillTemplate` rather than reproducing execution logic directly.

### Step-Loop
1. prompt includes argument-shape guidance for a concrete tool schema.
2. `CALL_TOOL` with missing nested/typed input is rejected before execution.
3. generic tool schemas still avoid noisy false positives.

### Backward Compatibility
1. mapped YAML skills continue to inherit tool schemas from Java methods.
2. duplicate-invoice evidence-contract behavior remains unchanged.
3. pure YAML skills without `input_schema` are supported intentionally and covered by tests rather than treated as temporary accidental behavior.

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
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/SkillTemplate.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/DefaultSkillTemplate.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/SkillExecutionView.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/SkillException.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/SkillInputValidationException.java`
- new shared input-contract/validation internals under `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepActionValidator.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- sample updates in `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/SampleController.java`
- corresponding tests under `src/test/java/...`

## Risks
- Reusing the output schema manifest type for inputs is efficient, but it may tempt future coupling between unrelated validation concerns.
- Compatibility checks between YAML and Java-derived schemas can become complex if v1 tries to be too clever.
- A weak `SkillTemplate` that only wraps `Map.of(...)` will not justify its surface area.
- Retaining too many legacy seams in the name of caution would preserve the exact technical debt this ticket is supposed to remove.
- Leaving endpoint-based debug and execution paths in place after adding `SkillExecutionView` would keep the public API confusing and undermine the cleanup goal.

## Open Questions
- Should `SkillTemplate` support typed response mapping in v1, or ship with input ergonomics only?
  Recommendation: input ergonomics only in v1.

## Recommendation
Land this work as an input-contract and invocation-ergonomics change set, not as a reimplementation of evidence contracts.

The repo has already solved much of the output/evidence half. The remaining high-value work is to make skill inputs explicit, validated, and pleasant to use across all entry points:

- `input_schema` for YAML skills
- deterministic authority/inheritance with Java-backed skills
- one shared validator
- one endorsed Java API in `SkillTemplate`
- one endorsed inspection API in `SkillExecutionView`

Because the library is still in development, we should take this opportunity to simplify aggressively: remove weak map-first seams where possible, accept breaking changes, and pay down technical debt instead of preserving transitional APIs indefinitely.
