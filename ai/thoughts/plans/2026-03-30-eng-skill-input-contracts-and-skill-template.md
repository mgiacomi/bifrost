# Skill Input Contracts and SkillTemplate Implementation Plan

## Overview

Add a first-class input-contract model for skills and a Spring-native `SkillTemplate` so Bifrost has one reusable, framework-owned input validation story across YAML skills, mapped Java-backed skills, root invocation, nested invocation, and step-loop tool execution.

This plan intentionally treats the ticket as an input-side architecture cleanup, not as a new evidence/output-contract initiative. Output schema and evidence enforcement already exist; the remaining gap is that input contracts are still implicit, generic, or map-first across too many execution paths.

## Current State Analysis

YAML manifests currently support `output_schema` and `evidence_contract`, but there is no `input_schema` field on [`YamlSkillManifest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\YamlSkillManifest.java#L14) and no parallel helper on [`YamlSkillDefinition.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\YamlSkillDefinition.java#L11). Startup validation in [`YamlSkillCatalog.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\YamlSkillCatalog.java#L32) validates `output_schema` and evidence mappings, but nothing establishes a framework-owned runtime input contract.

Capability registration also collapses tool schema and input semantics together. `CapabilityMetadata` defaults missing tools to `CapabilityToolDescriptor.generic(...)` in [`CapabilityMetadata.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\CapabilityMetadata.java#L8), and unmapped YAML skills are explicitly registered with that generic descriptor in [`YamlSkillCapabilityRegistrar.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\YamlSkillCapabilityRegistrar.java:63). Java `@SkillMethod` capabilities do have reflected method-input schemas, but invocation still flows through `CapabilityInvoker.invoke(Map<String, Object>)`.

Execution and prompting reinforce the same gap. Nested unmapped YAML execution serializes arguments into an objective string in [`CapabilityExecutionRouter.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\CapabilityExecutionRouter.java#L45), step-loop validation only checks top-level required fields in [`StepActionValidator.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\runtime\step\StepActionValidator.java#L108), and prompts still describe tool arguments with a generic placeholder in [`StepPromptBuilder.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\runtime\step\StepPromptBuilder.java#L120). The sample app still models public usage as direct `executionRouter.execute(..., Map.of(...))` calls and exposes debug/session endpoints in [`SampleController.java`](C:\opendev\code\bifrost\bifrost-sample\src\main\java\com\lokiscale\bifrost\sample\SampleController.java#L27).

## Desired End State

Bifrost should expose a single normalized `SkillInputContract` runtime model, sourced deterministically from YAML `input_schema` or Java-reflected method signatures depending on capability type. That contract should be available to startup validation, root invocation, nested invocation, and step-loop validation/prompting.

Java developers should invoke YAML skills through `SkillTemplate`, not raw `Map<String, Object>` plumbing or public sample/debug endpoints. `SkillTemplate` should validate and normalize input before any session or mission frame is created, execute the skill inside an internally managed session, and optionally expose a finalized `SkillExecutionView` for tests and debugging.

### Key Discoveries
- [`YamlSkillManifest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\YamlSkillManifest.java#L37) supports `output_schema`, but there is no corresponding `input_schema`.
- [`YamlSkillCatalog.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\YamlSkillCatalog.java#L227) already contains reusable schema validation machinery for the supported manifest subset.
- [`CapabilityExecutionRouter.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\CapabilityExecutionRouter.java#L55) currently turns nested YAML inputs into prompt text instead of carrying a canonical validated input object through execution.
- [`StepActionValidator.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\runtime\step\StepActionValidator.java#L147) only enforces missing top-level required fields today.
- [`SampleController.java`](C:\opendev\code\bifrost\bifrost-sample\src\main\java\com\lokiscale\bifrost\sample\SampleController.java#L52) demonstrates the current public usage pattern the ticket wants to replace.

## What We're NOT Doing

- We are not redesigning `output_schema` or evidence contracts.
- We are not introducing generated typed clients or typed response mapping in v1.
- We are not attempting full JSON Schema parity beyond the supported manifest subset already used for `output_schema`.
- We are not preserving weak map-first seams just because they already exist in sample code.
- We are not making pure YAML skills without `input_schema` fail startup; that path remains intentionally permissive.
- We are not positioning `SkillTemplate` as a generic facade for direct `@SkillMethod` execution; its public boundary is YAML skill invocation.

## Implementation Approach

Reuse the existing manifest schema model for inputs, then separate "tool schema for Spring AI integration" from "normalized Bifrost skill input contract" so the framework can validate, normalize, and render inputs consistently across all call paths. The implementation should move inwards-out: first define contract authority and validation primitives, then thread them through registration and routing, then expose the public `SkillTemplate` API, and finally update step-loop prompting plus sample/public surfaces to use the new path.

The plan favors removal or demotion of transitional APIs when the new path is in place. Since this library is still in development, we should not keep old endpoints and raw invocation examples as co-equal public APIs once `SkillTemplate` exists.

## Phase 1: Add Manifest-Level Input Schema and Effective Contract Resolution

### Overview

Introduce `input_schema` for YAML skills, establish authority/inheritance rules, and make effective contracts available on capability metadata without changing all execution paths yet.

### Changes Required:

#### 1. Extend YAML manifest and definition helpers
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`
**Changes**: Add `@JsonProperty("input_schema")` using the existing schema manifest type currently used by `output_schema`, plus getters/setters and any normalization needed for parity.

```java
@JsonProperty("input_schema")
private OutputSchemaManifest inputSchema;
```

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java`
**Changes**: Add typed helpers for accessing the declared input schema and determining whether a YAML skill is explicit-contract, inherited-contract, or generic-contract.

#### 2. Validate input schema and mapped-skill compatibility at startup
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
**Changes**: Reuse the existing schema-subset validator for `input_schema`, require root object type, apply the same warning heuristics used for `output_schema`, and add mapped-skill compatibility validation against the mapped Java tool schema.

```java
validateSchemaNode(resource, inputSchema, "input_schema", true, 1);
validateMappedInputSchemaCompatibility(resource, manifest, mappedCapability);
```

Compatibility validation should enforce exact structural parity for mapped YAML skills with explicit `input_schema`: field names, requiredness, type, nesting, enums, `items`, `additionalProperties`, and `format` must match; descriptions may differ.

#### 3. Introduce runtime input-contract types and resolution
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputContract.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputContractResolver.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputSchemaNode.java`

**Changes**: Create immutable runtime types that represent the effective input contract, whether it is explicit or generic, and the normalized schema tree used for validation and prompt rendering. Resolver behavior should follow the ticket's authority rules:

1. Java `@SkillMethod`: derive from reflected method schema.
2. mapped YAML without `input_schema`: inherit the mapped target contract.
3. mapped YAML with `input_schema`: validate against Java-derived structure, then publish YAML as authoritative.
4. pure YAML with `input_schema`: publish YAML as authoritative.
5. pure YAML without `input_schema`: publish a generic object contract.

#### 4. Carry tool schema and input contract separately
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolDescriptor.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java`

**Changes**: Preserve the current Spring AI tool schema exposure, but add a parallel field for the Bifrost-owned `SkillInputContract`. Ensure Java capabilities get reflected contracts, mapped YAML skills inherit or override correctly, and unmapped YAML skills no longer rely on "generic tool schema" as the only statement of their input semantics.

### Success Criteria:

#### Automated Verification:
- [x] Starter module compiles: `./mvnw -pl bifrost-spring-boot-starter -DskipTests compile`
- [x] Manifest loading tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*YamlSkillCatalog* test`
- [x] New contract-resolution tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*SkillInputContract* test`

#### Manual Verification:
- [ ] A pure YAML skill with no `input_schema` still loads successfully.
- [ ] A mapped YAML skill without `input_schema` clearly reports the inherited effective contract in debugging/logging or tests.
- [ ] A mapped YAML skill with a mismatched explicit schema fails fast during startup with an actionable error.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Add Shared Input Validation and Normalize Execution Entry Points

### Overview

Create one validator that normalizes and validates input maps for all capability types, then apply it consistently to root execution and nested YAML invocation so execution paths stop depending on raw prompt-serialized mission inputs.

### Changes Required:

#### 1. Add shared validation and issue/result types
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputValidator.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputValidationResult.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputValidationIssue.java`

**Changes**: Implement validation for required fields, null required values, nested objects, enum membership, array/object mismatches, scalar type mismatches, and `additionalProperties: false`. The result should return a normalized/coerced map, not just a boolean.

```java
public record SkillInputValidationResult(
    boolean valid,
    Map<String, Object> normalizedInput,
    List<SkillInputValidationIssue> issues
) {}
```

`SkillInputValidationIssue` should be part of the contract, not an incidental helper. It should include:

```java
public record SkillInputValidationIssue(
    String path,
    String code,
    String message,
    Object rejectedValue
) {}
```

The validator should use stable low-cardinality issue codes so the same data can drive API exceptions and step-loop retry messaging:
- `missing_required`
- `type_mismatch`
- `unknown_field`
- `enum_mismatch`
- `invalid_date_format`
- `coercion_failed`

MVP coercions should be limited and deterministic:
- string to integer/number when parsing succeeds
- string to boolean for `true` / `false`
- documented `format: date` normalization to ISO `YYYY-MM-DD`

`format` handling should match the ticket's narrower rule set:
- startup compatibility checks for mapped YAML skills must require `format` parity with the Java-derived contract
- runtime validation should treat `format` as advisory in MVP except for `format: date`
- `format: date` should accept only `YYYY-MM-DD`, `MM/DD/YYYY`, `M/D/YYYY`, and `MM-DD-YYYY`
- slash- or dash-separated month/day/year values should be interpreted as `MM/DD/YYYY`
- runtime validation should reject unsupported or ambiguous date strings and should not silently truncate datetimes

#### 2. Apply shared validation in router-level execution
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityInvoker.java`

**Changes**: Resolve the effective contract before execution, validate and normalize arguments centrally, and use `normalizedInput` for downstream invocation. Apply that same contract resolution and normalized-input path consistently to root Java invocation, mapped-YAML-to-Java invocation, and nested unmapped YAML execution so the framework no longer has divergent input behavior by entry path. For nested unmapped YAML skills, stop treating input only as objective text; carry validated canonical input into the nested mission execution path and use prompt text only as a rendering of already-normalized mission input.

```java
SkillInputValidationResult result = validator.validate(safeArguments, capability.inputContract());
Map<String, Object> normalizedInput = result.normalizedInput();
```

If the current `ExecutionCoordinator` contract only accepts an objective string, add the minimal supporting seam needed so nested YAML execution can keep canonical inputs available to the prompt/execution state instead of reconstructing meaning from serialized text.

When the effective contract is generic because a pure YAML skill has no `input_schema`, this shared validation path must remain permissive. It should not infer required fields, types, or stricter shape rules from the skill name, description, or prompt text. At most, it may normalize `null` or empty map handling consistently.

#### 3. Add targeted exception shaping for invalid inputs
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/SkillException.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/SkillInputValidationException.java`

**Changes**: Introduce public API exception types now so router/template validation failures have a stable outward-facing shape before `SkillTemplate` lands.

### Success Criteria:

#### Automated Verification:
- [x] Validation unit tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*SkillInputValidator* test`
- [x] Router and nested execution tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*CapabilityExecutionRouter* test`
- [x] Duplicate-invoice integration tests still pass: `./mvnw -pl bifrost-sample -Dtest=*duplicate* test`

#### Manual Verification:
- [ ] A contract-backed YAML skill rejects empty or semantically weak inputs before execution begins.
- [ ] Invalid input does not create partial traces, journals, or mission frames.
- [ ] Nested YAML skill execution receives canonical normalized input rather than depending on prompt-only serialization.
- [ ] A pure YAML skill with no `input_schema` remains permissive and is not rejected based on inferred requirements from descriptions.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Introduce SkillTemplate and SkillExecutionView as the Public Java API

### Overview

Expose the framework-endorsed Java invocation API, make it own session lifecycle internally, and give developers a small post-execution inspection surface that replaces the need for broad sample/debug endpoints.

### Changes Required:

#### 1. Add the public API package and default implementation
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/SkillTemplate.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/DefaultSkillTemplate.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/SkillExecutionView.java`

**Changes**: Add the narrow v1 API surface:

```java
String invoke(String skillName, Object input);
String invoke(String skillName, Map<String, Object> input);
String invoke(String skillName, Object input, Consumer<SkillExecutionView> observer);
String invoke(String skillName, Map<String, Object> input, Consumer<SkillExecutionView> observer);
```

Implementation rules:
- `invoke(String, Object)` converts to canonical map and delegates.
- `invoke(String, Object)` rejects `null`.
- `invoke(String, Map<String, Object>)` normalizes `null` to `Map.of()` only when the resolved contract is generic or explicitly allows empty input.
- input validation happens before session creation.
- capability resolution is YAML-skill-only; direct raw `@SkillMethod` invocation is not the supported public use of this API.
- unknown YAML skill names throw `SkillException`.
- invalid input throws `SkillInputValidationException`.
- observer receives a finalized, read-only `SkillExecutionView` after session completion.
- if observer execution throws, that exception should propagate rather than being swallowed.

`SkillExecutionView` should stay intentionally narrow in v1:

```java
public record SkillExecutionView(
    String sessionId,
    ExecutionJournal executionJournal
) {}
```

Do not include `executionTrace` or broader session internals in the MVP surface.

#### 2. Wire SkillTemplate into auto-configuration
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
**Changes**: Register `SkillTemplate`, its dependencies, and any contract resolver/validator beans needed by both router and public API paths.

#### 3. Demote raw map-first seams from public usage
**Files**:
- `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/SampleController.java`
- any sample-facing docs or example tests that currently demonstrate `executionRouter.execute(..., Map.of(...))`

**Changes**: Update sample execution endpoints to delegate to `SkillTemplate`. Remove or shrink public debug/session endpoints that only exist because the framework lacked a post-execution inspection API. If one sample endpoint remains for demonstration, it should be clearly sample-only and delegate to `SkillTemplate`.

### Success Criteria:

#### Automated Verification:
- [x] SkillTemplate tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*SkillTemplate* test`
- [x] Auto-configuration tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*AutoConfiguration* test`
- [x] Sample app tests pass: `./mvnw -pl bifrost-sample -am "-Dsurefire.failIfNoSpecifiedTests=false" test`

#### Manual Verification:
- [ ] Sample code invokes YAML skills through `SkillTemplate` rather than direct router/session plumbing.
- [ ] Observer callbacks see finalized journal state and a stable `sessionId`.
- [ ] Removing or minimizing public debug/session endpoints does not reduce the ability to inspect post-execution state in tests.
- [ ] Attempts to use `SkillTemplate` against non-YAML capabilities fail clearly rather than quietly broadening the public API.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 4: Upgrade Step-Loop Validation and Prompt Guidance to Use Input Contracts

### Overview

Reuse the same contract model in the planner/executor loop so tool-argument guidance and validation match the new runtime behavior rather than remaining a shallow, tool-schema-only side path.

### Changes Required:

#### 1. Replace ad hoc required-field checks with the shared validator
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepActionValidator.java`
**Changes**: Delegate `CALL_TOOL` argument validation to the shared input validator when a concrete contract exists, while preserving permissive behavior for generic-contract pure YAML skills.

```java
if (!tool.inputContract().isGeneric()) {
    SkillInputValidationResult validation = inputValidator.validate(arguments, tool.inputContract());
}
```

Validation failures should feed stable issue codes and field paths into retry/error messaging instead of continuing to synthesize custom one-off schema messages.

#### 2. Add deterministic compact/verbose argument-shape rendering
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputPromptRenderer.java`

**Changes**: Render concise JSON-shaped examples for concrete tool input contracts and escalate to a more verbose form after validation failures or for more complex schemas. The same renderer should back both detail levels.

```java
renderToolArgumentsExample(contract, DetailLevel.COMPACT)
renderToolArgumentsExample(contract, DetailLevel.VERBOSE)
```

Compact rendering should stay shallow and example-shaped. Verbose rendering should add required-field lists, enum hints, and unknown-field restrictions without dumping raw schema into the prompt.

### Success Criteria:

#### Automated Verification:
- [x] Step-loop validator tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*StepActionValidator* test`
- [x] Step prompt rendering tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*StepPromptBuilder* test`
- [x] Integration tests covering planner retries on invalid tool arguments pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*StepLoop* test`

#### Manual Verification:
- [ ] A ready task bound to a concrete contract shows a concise argument example in the prompt.
- [ ] Invalid nested or typed tool arguments are rejected before tool execution.
- [ ] Generic-contract pure YAML skills do not trigger noisy false positives or over-constrained prompts.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Testing Strategy

### Unit Tests:
- Manifest parsing and startup validation for valid/invalid `input_schema`.
- Mapped YAML inheritance and explicit-schema compatibility checks.
- Shared validator coverage for required fields, nested objects, enums, type mismatches, unknown fields, and deterministic coercions.
- Shared validator coverage for stable issue codes, field paths, and `format: date` normalization behavior.
- `SkillTemplate` success/failure behavior, observer timing, null/empty input handling, and YAML-only capability resolution.
- Step-loop contract-aware validation and compact/verbose prompt rendering.
- Explicit coverage for `SkillTemplate` null-input semantics and observer exception propagation.

### Integration Tests:
- Root Java invocation through `SkillTemplate` with object and map overloads.
- Nested YAML invocation using the same normalized validation path as root execution.
- Contract-backed YAML skill rejects empty input before session creation.
- Existing duplicate-invoice sample flow remains behaviorally unchanged from a user perspective.
- Mapped YAML skill with explicit `input_schema` fails startup when `format` differs from the Java-derived contract.
- `SkillTemplate` rejects attempts to use non-YAML capabilities through its public API.
- Observer exceptions propagate after finalized `SkillExecutionView` delivery rather than being swallowed.

**Note**: Prefer a dedicated testing plan artifact created via `3_testing_plan.md` for full details, especially to lock down impacted modules, failing-test-first sequencing, and exact Maven commands.

### Manual Testing Steps:
1. Start the sample app and invoke a mapped YAML skill through the new `SkillTemplate`-backed endpoint.
2. Invoke a pure YAML skill with valid structured input and confirm the result matches current behavior.
3. Invoke the same skill with missing required input and confirm failure happens before any session/journal is created.
4. Exercise a step-loop skill that binds tool arguments and confirm prompt guidance now includes an example shape.
5. Confirm post-execution inspection is available through `SkillExecutionView` rather than public debug/session inspection endpoints.

## Performance Considerations

Input validation and prompt rendering add runtime work, so contract normalization should happen once and be cached on metadata rather than rebuilt on every call. Validation should also normalize only the supported subset and avoid expensive schema-diff or deep reflection at execution time; startup should absorb the heavier mapped-schema compatibility checks.

## Migration Notes

This change intentionally shifts the public Java integration path from raw router/session plumbing to `SkillTemplate`. Since breaking changes are acceptable, sample-facing APIs and examples should move decisively to the new path instead of maintaining both approaches as equally endorsed options.

Pure YAML skills without `input_schema` remain supported and permissive. Mapped YAML skills without `input_schema` should migrate automatically via inherited Java-derived contracts. Mapped YAML skills with explicit `input_schema` may begin failing startup if they drift structurally from the underlying Java capability; that failure is intentional and should be treated as a configuration correctness check, not a compatibility regression.

## References

- Original ticket/requirements: `ai/thoughts/tickets/eng-skill-input-contracts-and-skill-template.md`
- Related research: `ai/thoughts/research/2026-03-30-skill-input-contracts-and-skill-template.md`
- Manifest schema support today: [`YamlSkillManifest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\YamlSkillManifest.java#L37)
- Startup schema validation today: [`YamlSkillCatalog.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\YamlSkillCatalog.java#L227)
- Generic nested YAML execution today: [`CapabilityExecutionRouter.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\CapabilityExecutionRouter.java#L55)
- Current step-loop argument validation gap: [`StepActionValidator.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\runtime\step\StepActionValidator.java#L108)
- Current generic tool-argument prompt placeholder: [`StepPromptBuilder.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\runtime\step\StepPromptBuilder.java#L120)
- Current sample public invocation surface: [`SampleController.java`](C:\opendev\code\bifrost\bifrost-sample\src\main\java\com\lokiscale\bifrost\sample\SampleController.java#L52)
