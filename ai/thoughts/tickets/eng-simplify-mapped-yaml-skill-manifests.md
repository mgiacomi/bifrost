# ENG - Simplify Mapped YAML Skill Manifests

**Date:** 2026-07-13

**Status:** Proposed

**Type:** Pre-release authoring and manifest correction

**Depends on:** [`eng-separate-public-skills-from-java-targets.md`](eng-separate-public-skills-from-java-targets.md)

**Delivery order:** Framework prerequisite 2 of 2. Complete this ticket after public-skill/Java-target separation and before beginning new HTN gallery sample implementation.

## Summary

Bifrost currently requires every YAML skill manifest to declare a catalog-valid `model`, including a mapped YAML skill that delegates directly to a deterministic Java `@SkillMethod`. Mapped execution does not invoke the configured model, so the field is misleading ceremony and forces skill authors to select an irrelevant value.

The framework also lets a mapped YAML skill redeclare `input_schema`, then requires that declaration to remain structurally identical to the Java target's reflected contract. The duplicate schema cannot define a different input shape; it creates a second authoring source that can drift and fail compatibility validation.

Give each execution kind one authoritative authoring source:

- an LLM-backed YAML skill declares its model, and YAML remains the source of any declared input contract;
- a mapped YAML skill omits model configuration and inherits its input contract exclusively from its Java target;
- mapped definitions need neither dummy execution configuration nor a duplicate YAML input schema.

The project has no production release, so manifests, samples, fixtures, and internal types can be corrected without a compatibility bridge.

## Feature-Lens Evaluation

This correction is justified under the [Bifrost Framework Feature Design Lens](../framework-feature-design-lens.md):

- The problem remains with a highly capable model because it is inaccurate manifest semantics, not model weakness.
- A skill developer should understand locally whether a capability is model-driven or deterministic.
- Omitting inapplicable or duplicate fields removes false choices rather than hiding important behavior.
- The model choice cannot affect mapped execution, so requiring it provides no safety, validation, trace, or replay value.
- An explicit mapped `input_schema` cannot currently define a different structure, so the Java target is already the effective implementation constraint.
- One authoritative input contract eliminates drift and makes mapped authoring deterministic.
- Conditional validation produces a clearer failure than silently accepting ignored configuration.
- The correction preserves the explicit YAML public contract and Java mapping boundary.

## Current Behavior

`YamlSkillCatalog.loadDefinition` currently:

1. requires `name`, `description`, and `model` for every manifest;
2. resolves the declared model through `BifrostModelsProperties`;
3. resolves and validates a thinking level;
4. constructs a non-null `EffectiveSkillExecutionConfiguration`;
5. only later allows registration to distinguish a mapped definition through `mapping.target_id`.

`YamlSkillCapabilityRegistrar` delegates a mapped YAML capability to its Java target. The model execution configuration stored on the YAML definition and capability metadata is not used to perform that mapped invocation.

When a mapped skill omits `input_schema`, `SkillInputContractResolver` derives its effective contract from the Java target's reflected tool schema. When the YAML declares `input_schema`, the registrar requires the same types, properties, required fields, enums, formats, collection structure, and `additionalProperties` semantics. The explicit declaration therefore duplicates the Java structure rather than adapting it.

This creates manifests such as:

```yaml
name: expenseLookup
description: Retrieves the most recent expenses.
model: granite4-tiny
mapping:
  target_id: expenseService#getLatestExpenses
```

The declaration falsely suggests that `granite4-tiny` participates in execution.

## Desired Authoring Model

An LLM-backed skill remains explicit about its model:

```yaml
name: summarizeExpenses
description: Summarizes expense patterns.
model: production-reasoning
```

A mapped skill expresses only its public capability and deterministic target:

```yaml
name: expenseLookup
description: Retrieves the most recent expenses.
mapping:
  target_id: expenseService#getLatestExpenses
```

The absence of `model` is meaningful: no LLM executes at that skill boundary.

The absence of `input_schema` is also meaningful: the mapped Java target is the authoritative input-contract source. Bifrost still publishes and validates the reflected effective contract for callers and planners.

## Decision Locks

The implementation plan must preserve these decisions unless a later design review explicitly changes them.

1. **LLM-backed YAML skills still require a non-blank, known `model`.**
2. **Mapped YAML skills omit `model`.** Do not assign a default model to them.
3. **Do not introduce `model: none`.** Absence represents inapplicability without a sentinel value.
4. **A mapped manifest that declares `model` fails startup with a clear error stating that the field is inapplicable because `mapping.target_id` delegates to Java.** Do not silently ignore it.
5. **A mapped manifest that declares `thinking_level` also fails startup as inapplicable.** Thinking level is model execution configuration and cannot be meaningful without a model.
6. **Mapped YAML skills omit `input_schema`.** Their effective input contract always comes from the mapped Java target's reflected contract.
7. **A mapped manifest that declares `input_schema` fails startup with a clear error stating that mapped skills inherit the Java target's input contract.** Do not silently ignore it or maintain compatibility checking between duplicate schemas.
8. **A different public input shape requires a different deterministic Java adapter target.** This ticket does not add YAML argument transformation, narrowing, renaming, or defaulting.
9. **Multiple public YAML skills may map to one Java target, but all inherit that target's same reflected input contract.** They may still differ in public name, capability description, RBAC, visibility, evidence role, or other applicable YAML governance metadata.
10. **Internal mapped definitions and capability metadata must not contain fabricated model or provider values.** Represent the absence of model execution configuration honestly.
11. **Mapped invocation, inherited input validation, RBAC, evidence attribution, exception behavior, runtime ref binding, and tracing remain unchanged.**
12. **Do not auto-register Java targets as public skills.** Public mapped skills remain explicit YAML capabilities connected through `mapping.target_id`.
13. **Update the AI skill guide after implementation so these omissions become current authoring requirements rather than planned corrections.**

## Goals

- Make the manifest accurately communicate whether a skill invokes an LLM.
- Remove irrelevant model selection from deterministic mapped capability authoring.
- Establish the Java target as the single source of truth for mapped input structure.
- Remove duplicate mapped schemas and their compatibility failure mode.
- Produce actionable startup validation for contradictory model and mapping declarations.
- Allow internal catalog and capability types to represent mapped execution without fake configuration.
- Reduce repeated sample boilerplate without weakening public YAML contracts.

## Non-Goals

- Do not add automatic YAML generation or direct public registration for `@SkillMethod` targets.
- Do not add application-wide default models as part of this correction.
- Do not redesign model selection for LLM-backed skills.
- Do not introduce parent-input inheritance.
- Do not add a YAML input-adaptation or contract-narrowing layer for mapped targets.
- Do not add shared schema fragments, `$ref`, `extends`, or multi-skill YAML files.
- Do not redesign output schemas, evidence contracts, linters, RBAC, or traces.
- Do not decide the validity of other mapped execution fields such as `planning_mode` or `max_steps` unless a required implementation invariant makes a narrow validation change unavoidable. Record broader cleanup separately.

The last item is a scope boundary, not an assertion that every other manifest field is meaningful on mapped skills. If implementation inventory reveals additional silently ignored fields, report them for a separate design decision rather than quietly expanding this ticket or preserving them as recommended sample syntax.

## Implementation Considerations

### Classify before resolving execution configuration

Catalog loading should determine whether `mapping.target_id` is present before requiring or resolving model configuration. Validation must then follow the appropriate branch:

```text
LLM-backed YAML
    require and resolve model
    resolve thinking level
    construct model execution configuration

mapped YAML
    reject model, thinking_level, and input_schema when declared
    do not resolve a model
    do not construct dummy model execution configuration
    derive the effective input contract from the Java target
```

### Keep one mapped input-contract source

Remove the explicit-mapped-schema compatibility path from registration. The reflected Java contract must continue to drive public tool-schema publication, root and nested input validation, argument binding, runtime markers, and SkillBuilder/catalog inspection.

Java parameter and DTO metadata must therefore carry the field names, types, requiredness, descriptions, and supported runtime markers intended for the mapped capability. If a developer needs a genuinely different public shape, a separate annotated adapter method makes the transformation explicit and testable.

### Represent execution kind honestly

Review `YamlSkillDefinition`, `EffectiveSkillExecutionConfiguration`, `SkillExecutionDescriptor`, and `CapabilityMetadata` so the type model does not require irrelevant model values for mapped capabilities. Prefer an explicit execution-kind representation or a narrowly optional model configuration whose absence is valid only for deterministic mapped execution.

Do not scatter nullable model assumptions through planner code. Code paths that execute LLM-backed skills should establish the model-backed invariant at their boundary and fail clearly if an impossible definition reaches them.

### Coordinate with Java-target separation

This work overlaps [eng-separate-public-skills-from-java-targets.md](eng-separate-public-skills-from-java-targets.md), which separates public YAML capabilities from internal Java implementation targets. Both tickets touch catalog definitions, registration metadata, and mapped execution.

Complete the identity/registry correction first. Keep acceptance criteria and commits independently reviewable; do not fold this authoring correction invisibly into the registry refactor.

## Required Startup Errors

Errors must identify the contradictory field, the mapped skill, and the inherited Java source of truth. Exact punctuation may vary, but the remedy must remain explicit.

### Model on a mapped skill

```text
Invalid YAML skill 'expenseLookup' for field 'model':
model cannot be declared when mapping.target_id is present because mapped skills delegate to Java.
```

### Thinking level on a mapped skill

```text
Invalid YAML skill 'expenseLookup' for field 'thinking_level':
thinking_level cannot be declared when mapping.target_id is present because no model executes at this boundary.
```

### Input schema on a mapped skill

```text
Invalid YAML skill 'expenseLookup' for field 'input_schema':
input_schema cannot be declared when mapping.target_id is present; mapped skills inherit the Java target's reflected input contract.
```

Do not silently ignore these fields, resolve a default model, or retain compatibility checking between duplicate schemas.

## Suggested Ticket Phases

### Phase 1 - Manifest and type design

- Identify every consumer that currently assumes `YamlSkillDefinition.executionConfiguration()` is non-null.
- Choose an explicit representation for model-backed versus deterministic mapped definitions.
- Define conditional validation messages for missing and inapplicable model, thinking, and input-schema fields.

### Phase 2 - Catalog and registration

- Classify the manifest before resolving model configuration.
- Require and resolve model/thinking configuration only for LLM-backed definitions.
- Register mapped YAML capabilities without fabricated execution configuration.
- Reject `input_schema` on mapped definitions.
- Preserve mapped target resolution and reflected input-contract inheritance.

### Phase 3 - Tests and fixtures

- Add a valid mapped fixture with no `model`.
- Add an invalid mapped fixture that declares `model`.
- Add an invalid mapped fixture that declares `thinking_level`.
- Add an invalid mapped fixture that declares `input_schema`.
- Preserve tests proving an LLM-backed skill fails when `model` is absent or unknown.
- Replace explicit mapped-schema compatibility tests with tests proving reflected contracts remain authoritative for entry and nested invocation.
- Verify mapped entry and nested invocation still avoid model execution.

### Phase 4 - Samples and documentation

- Remove `model` from all mapped sample manifests.
- Remove `input_schema` from all mapped sample manifests and ensure Java target metadata expresses the effective contract.
- Update manifest documentation and examples to show conditional model applicability and Java-owned mapped input contracts.
- Remove the temporary current-checkout warning from the AI skill guide and make omission normative.
- Verify the Incident Commander mapped leaves contain no irrelevant model configuration.

## Acceptance Criteria

- [ ] An LLM-backed YAML skill without `model` fails startup with an actionable required-field error.
- [ ] An LLM-backed YAML skill with an unknown model still fails startup.
- [ ] A mapped YAML skill without `model` loads and invokes its Java target successfully.
- [ ] A mapped YAML skill declaring `model` fails startup with an actionable inapplicable-field error.
- [ ] A mapped YAML skill declaring `thinking_level` fails startup with an actionable inapplicable-field error.
- [ ] A mapped YAML skill without `input_schema` publishes and enforces its Java target's reflected input contract.
- [ ] A mapped YAML skill declaring `input_schema` fails startup with an actionable inheritance explanation.
- [ ] No dummy model, provider, provider-model, or thinking value is stored for mapped execution.
- [ ] Explicit mapped-schema compatibility code and fixtures are removed.
- [ ] Reflected mapped input validation, runtime markers, and argument binding continue to pass.
- [ ] Nested and entry mapped invocation continue to enforce RBAC and execution-time protections.
- [ ] Existing mapped samples omit `model` after the correction.
- [ ] Existing mapped samples omit `input_schema` after the correction and expose equivalent reflected Java contracts.
- [ ] The AI skill guide describes the implemented behavior without a temporary limitation warning.
- [ ] The completed public-skill/Java-target separation remains compatible with the new execution-configuration and input-contract representation.

## Verification Targets

- `YamlSkillCatalogTests`
- `YamlSkillCapabilityRegistrarTests`
- `CapabilityExecutionRouterTest`
- `SkillTemplateTest`
- mapped input-contract inheritance, validation, and runtime-marker tests
- application-context startup with mixed LLM-backed and mapped skills
- sample context and catalog tests

## Implementation Anchors

- [`YamlSkillCatalog.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java)
- [`YamlSkillDefinition.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java)
- [`YamlSkillCapabilityRegistrar.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java)
- [`EffectiveSkillExecutionConfiguration.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/EffectiveSkillExecutionConfiguration.java)
- [`SkillExecutionDescriptor.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillExecutionDescriptor.java)
- [`CapabilityMetadata.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java)
- [`SkillInputContractResolver.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputContractResolver.java)

## Related Work

- [Public YAML skill and Java target separation](eng-separate-public-skills-from-java-targets.md)
- [Incident Commander sample](eng-sample-htn-incident-commander.md)
- [Bifrost Framework Feature Design Lens](../framework-feature-design-lens.md)
