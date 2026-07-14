# ENG - Simplify Mapped YAML Skill Manifests

**Date:** 2026-07-13

**Status:** Proposed

**Type:** Pre-release execution-kind authoring and manifest correction

**Depends on:** [`eng-separate-public-skills-from-java-targets.md`](eng-separate-public-skills-from-java-targets.md)

**Delivery order:** Framework prerequisite 2 of 3. Complete this ticket after public-skill/Java-target separation, then complete [`eng-validate-public-yaml-skill-names.md`](eng-validate-public-yaml-skill-names.md) before beginning new HTN gallery sample implementation.

## Summary

Bifrost currently parses every YAML skill as though it were model-backed, even when `mapping.target_id` delegates execution directly to a deterministic Java `@SkillMethod`. That makes mapped manifests accept or require configuration that cannot participate in their execution. It also duplicates Java-owned input contracts in YAML, permits YAML output declarations that Java does not enforce, skips all YAML discovery when the configured model catalog is empty, and carries the unused legacy `ModelPreference` value through Java annotation and metadata types.

Make execution kind an explicit validation boundary:

- an LLM-backed YAML skill declares its model and may use model-execution fields;
- a mapped YAML skill declares only public identity/governance plus `mapping.target_id`;
- mapped input and output contracts are owned by the Java target;
- mapped definitions contain no fabricated model execution configuration;
- YAML discovery works for a mapped-only application with no model configuration or `ChatModel` bean;
- the dead `ModelPreference` API and metadata are removed without a compatibility bridge.

The project has no production release, so manifests, samples, fixtures, internal types, and obsolete APIs can be corrected destructively.

Destructive cleanup is explicitly preferred over compatibility scaffolding for this ticket. Reviewers should not request deprecated aliases, compatibility constructors, manifest fallbacks, or preservation of ignored mapped fields unless a new design decision changes the authoring contract.

## Feature-Lens Evaluation

This correction is justified under the [Bifrost Framework Feature Design Lens](../framework-feature-design-lens.md):

- The problem remains with a highly capable model because it is inaccurate manifest semantics, not model weakness.
- A skill developer should understand locally whether a capability is model-driven or deterministic.
- Omitting inapplicable fields removes false choices rather than hiding behavior.
- Java already determines mapped invocation, input binding, and returned values.
- One authoritative contract source eliminates drift and makes mapped authoring deterministic.
- Conditional validation is safer than silently accepting ignored configuration.
- Declared-field presence must be honored: `false`, `0`, `[]`, blank, and explicit `null` are still declarations, not omissions.

## Current Behavior

`YamlSkillCatalog.loadDefinition` currently resolves model configuration before registration knows whether a manifest is mapped. An empty configured model catalog returns before YAML discovery, so even model-free mapped skills are suppressed. A blank or incomplete `mapping` declaration can collapse into the LLM-backed path instead of failing as malformed mapping syntax.

Mapped routing invokes the Java target directly. The mapped skill's `model`, `thinking_level`, `prompt`, `planning_mode`, `max_steps`, `allowed_skills`, `linter`, `output_schema_max_retries`, and evidence contract do not participate in that invocation. Explicit mapped `input_schema` only duplicates the Java-reflected schema; mapped `output_schema` is not an authoritative Java return contract.

`ModelPreference` is also carried by `@SkillMethod`, `SkillImplementationTarget`, and `CapabilityMetadata`, but no production selection or routing behavior consumes it. Keeping the value would imply a supported preference mechanism that does not exist.

## Desired Authoring Model

An LLM-backed skill remains explicit about model execution:

```yaml
name: summarizeExpenses
description: Summarizes expense patterns.
model: production-reasoning
planning_mode: true
max_steps: 8
```

A mapped skill contains only its public identity, applicable governance, and deterministic target:

```yaml
name: expenseLookup
description: Retrieves the most recent expenses.
rbac_roles: [finance-reader]
mapping:
  target_id: expenseService#getLatestExpenses
```

For mapped skills, Java is authoritative for both input and output. Bifrost publishes and validates the reflected Java input contract. If a different public input or output shape is needed, the developer supplies a distinct Java adapter target.

A parent LLM skill may still reference a mapped child through its own `allowed_skills` or `evidence_contract.tool_evidence`. Those fields govern the parent and are not declarations on the mapped child.

## Decision Locks

The implementation must preserve these decisions unless a later design review explicitly changes them.

1. **Manifest classification is syntactic and precedes model resolution.** An omitted `mapping` block means LLM-backed. An explicitly declared `mapping` must contain a non-blank `target_id`; blank, null, or incomplete mapping fails startup.
2. **LLM-backed YAML skills require a non-blank, known `model`.** Existing LLM semantics remain unchanged.
3. **Mapped YAML skills omit every model/runtime field.** The only mapped-wrapper fields are `name`, `description`, `mapping.target_id`, and `rbac_roles`.
4. **Mapped declarations of `model`, `thinking_level`, `prompt`, `input_schema`, `output_schema`, `planning_mode`, `max_steps`, `allowed_skills`, `linter`, `output_schema_max_retries`, or `evidence_contract` fail startup.** Do not silently ignore them.
5. **Declaration presence, not the parsed value, controls applicability validation.** Explicit `null`, blank strings, `false`, `0`, and empty collections still fail when the field is inapplicable. Parser defaults must not erase declaration presence.
6. **Do not introduce sentinels or defaults such as `model: none`.** Absence represents inapplicability.
7. **Java owns mapped input and output contracts.** Mapped YAML cannot redeclare `input_schema` or `output_schema`; a different shape requires a separate Java adapter target.
8. **Multiple public mapped skills may target the same Java method, but they inherit the same Java input/output contracts.** They may differ only in public identity and applicable governance such as RBAC.
9. **A YAML definition has exactly one execution invariant:** an LLM-backed definition has an execution configuration; a mapped definition has none. `CapabilityMetadata` projects the mapped case as `SkillExecutionDescriptor.none()`. Model-backed consumers establish the required configuration at their boundary instead of scattering nullable assumptions.
10. **YAML resources are discovered even when the configured model catalog is empty.** A mapped-only application can start and invoke mapped skills without `bifrost.models` or a `ChatModel` bean; an LLM-backed manifest still fails for its missing model.
11. **Applicability validation is fail-fast and precedes model lookup, schema-content validation, retry validation, and Java-target lookup.** Use one stable field order so a manifest has a deterministic first error.
12. **Startup diagnostics identify both the public skill name and YAML resource, plus the contradictory field and remedy.**
13. **Remove `ModelPreference` completely.** Delete the enum, the `@SkillMethod` member, metadata/target record components, constructor plumbing, imports, and tests. Do not deprecate it or retain compatibility overloads.
14. **Mapped invocation behavior remains intact:** reflected input validation, argument binding, runtime ref binding, RBAC, evidence attribution from the invoking parent, exception behavior, and tracing.
15. **Do not auto-register Java targets as public skills.** Public mapped skills remain explicit YAML capabilities joined through `mapping.target_id`.
16. **Update samples, fixtures, and the AI skill guide to express the final contract.**

## Goals

- Make mapped manifests contain only fields meaningful at their execution boundary.
- Establish Java as the single source of truth for mapped input and output.
- Produce deterministic, presence-aware startup validation for malformed or contradictory declarations.
- Represent mapped execution without fake model/provider/thinking values.
- Support truly model-free mapped-only application contexts.
- Remove the dead `ModelPreference` API and data flow.
- Preserve public YAML capability identity and deterministic Java mapping.

## Non-Goals

- Do not auto-generate YAML or directly expose `@SkillMethod` targets as public skills.
- Do not add application-wide default models or redesign LLM-backed model selection.
- Do not add parent-input inheritance, YAML argument transformation, contract narrowing, shared schema fragments, `$ref`, `extends`, or multi-skill YAML files.
- Do not add Java-reflected output-schema publication or return-value schema validation in this ticket. Reject mapped `output_schema`; Java owns the returned value until a separate output-contract design is approved.
- Do not redesign the meaning of planning, retry, linter, evidence, or tool-allowlist fields for LLM-backed skills. This ticket only makes them inapplicable to mapped wrappers.
- Do not redesign RBAC or tracing.

## Implementation Considerations

### Preserve field declaration presence

Generic YAML binding and defaults cannot distinguish omission from declarations such as `planning_mode: false`, `max_steps: 0`, `allowed_skills: []`, or `field: null`. Add explicit presence tracking during manifest binding (for example, setters that record occurrence or a raw declared-field set) and use it for mapped-field applicability checks. `rbac_roles` remains allowed on mapped wrappers.

### Classify and validate before resolving execution configuration

Use this conceptual order:

```text
parse resource and retain declared-field presence
require name and description
classify mapping:
    mapping omitted -> LLM-backed
    mapping declared -> require non-blank mapping.target_id

if mapped:
    reject in stable order:
        model, thinking_level, prompt, input_schema, output_schema,
        planning_mode, max_steps, allowed_skills, linter,
        output_schema_max_retries, evidence_contract
    do not resolve a model or validate ignored field contents
    resolve Java target
    derive the effective Java input contract

if LLM-backed:
    require and resolve model
    apply existing model/runtime validation
```

This ordering ensures a contradictory mapped field wins over an unknown model, malformed schema, invalid retry count, or missing target. Publish a stable first-error field order and cover it with a multi-error fixture.

### Keep Java authoritative for mapped contracts

Remove explicit mapped-input-schema compatibility logic. Java parameter and DTO metadata continues to drive tool-schema publication, root and nested input validation, argument binding, and runtime markers. Mapped YAML `output_schema` is rejected because deterministic return behavior is Java-owned and the current direct route does not enforce a YAML output contract.

### Represent execution kind honestly

Allow `YamlSkillDefinition.executionConfiguration()` to be absent only for a definition already classified as mapped. Provide a narrow `requireExecutionConfiguration()` boundary (or equivalent sealed execution-kind API) for model-backed consumers. Registration translates the mapped absence to `SkillExecutionDescriptor.none()`; it does not manufacture a provider, provider-model, model name, or thinking level.

### Discover mapped YAML without models

Remove the empty-model-catalog early return that suppresses resource discovery. Model resolution belongs only in the LLM-backed branch. Add an application-context test with no `bifrost.models` and no `ChatModel` bean that discovers and invokes a mapped YAML skill through `SkillTemplate`.

### Remove `ModelPreference`

Delete `ModelPreference.java` and remove `modelPreference` from `@SkillMethod`, `SkillImplementationTarget`, `CapabilityMetadata`, registration construction, tests, and all call sites. Because the framework is unreleased and the value has no consumer, there is no deprecation period or migration shim.

### Coordinate with Java-target separation

Complete [eng-separate-public-skills-from-java-targets.md](eng-separate-public-skills-from-java-targets.md) first. Keep the identity/registry refactor and this authoring correction independently reviewable even though both touch catalog and registration metadata.

### Implementation and code-review guardrails

- Treat the already-applied `ModelPreference` removal as completed work within this ticket and the same pull request. Keep it visible in the diff and acceptance criteria so reviewers understand that it is intentional, but do not reimplement, revert, or split it into a separate delivery.
- Verify that declaration-presence metadata survives every `YamlSkillDefinition` defensive copy. Jackson value copying must not turn omitted null/default properties into declared fields.
- Verify classification and mapped-field applicability before model lookup, schema/linter/evidence validation, retry validation/defaulting, and Java-target lookup. Multi-error fixtures must lock the deterministic first-error order.
- Build the model-free integration test from a context that does not import the existing `application-test.yml` model catalog and does not register a provider `ChatModel`; otherwise the test does not prove the acceptance criterion.
- Remove mapped input-schema compatibility without weakening pure LLM YAML `input_schema` validation or Java-reflected runtime-marker behavior.
- Prefer direct removal and fixture/sample migration over compatibility branches because the framework is still in development and has no production-release contract to preserve.

## Required Startup Errors

Exact punctuation may vary, but errors must include the public skill name, YAML resource, field, reason, and remedy. Representative shape:

```text
Invalid YAML skill 'expenseLookup' in 'classpath:skills/expense-lookup.yaml' for field 'model':
model cannot be declared when mapping.target_id is present because mapped skills delegate to Java.
```

Use field-specific reasons:

| Field | Required explanation |
| --- | --- |
| `model`, `thinking_level`, `prompt`, `planning_mode`, `max_steps` | No model executes at the mapped boundary. |
| `input_schema` | Mapped skills inherit the Java target's reflected input contract. |
| `output_schema` | The deterministic Java target owns the returned value. |
| `allowed_skills` | A mapped wrapper does not perform nested model tool selection; declare the mapped child on its LLM parent instead. |
| `linter`, `output_schema_max_retries` | Model-output validation/retry does not run on direct Java routing. |
| `evidence_contract` | The mapped child's own model evidence contract is not evaluated; an invoking parent may declare tool evidence. |

Malformed mapping must be diagnosed separately:

```text
Invalid YAML skill 'expenseLookup' in 'classpath:skills/expense-lookup.yaml' for field 'mapping.target_id':
mapping was declared, so target_id must be non-blank.
```

## Suggested Ticket Phases

### Phase 1 - Manifest and type invariants

- Add declared-field presence tracking and explicit mapping-block presence tracking.
- Encode the LLM-backed/configured versus mapped/unconfigured definition invariant.
- Add a model-backed configuration-requirement boundary and mapped `SkillExecutionDescriptor.none()` projection.
- Remove `ModelPreference` and all compatibility/plumbing code.

### Phase 2 - Catalog and registration

- Always discover YAML resources, including with an empty model catalog.
- Classify and validate mapping before resolving model configuration.
- Reject all inapplicable mapped fields in stable order.
- Remove mapped input-schema compatibility and reject mapped output schemas.
- Preserve Java target resolution, reflected input inheritance, and direct routing.

### Phase 3 - Tests and fixtures

- Add a valid mapped fixture containing only identity, optional RBAC, and a non-blank target.
- Add invalid fixtures for every rejected field.
- Include explicit null/blank, `planning_mode: false`, `max_steps: 0`, and empty-list declarations to prove presence-aware rejection.
- Add missing, null, and blank `mapping.target_id` coverage plus a multi-error validation-order fixture.
- Preserve LLM missing/unknown-model coverage.
- Replace explicit mapped-schema compatibility tests with Java-authoritative input tests and mapped-output-schema rejection.
- Add the model-free application-context/`SkillTemplate` invocation test.
- Verify entry and nested mapped invocation, RBAC, runtime markers, binding, evidence attribution, exceptions, and tracing remain intact.
- Verify the removed `ModelPreference` type and members have no source or binary/API assertions remaining.

### Phase 4 - Samples and documentation

- Remove every inapplicable field from mapped sample manifests, including explicit false/zero/empty declarations.
- Ensure Java target metadata expresses mapped input contracts.
- Update manifest documentation, examples, and the AI skill guide with the strict allowed-field set and Java-owned input/output rule.
- Verify Incident Commander mapped leaves contain only the allowed mapped-wrapper fields.

## Acceptance Criteria

- [ ] An omitted `mapping` block selects LLM-backed validation; an explicit mapping with missing, null, or blank `target_id` fails as malformed mapping.
- [ ] An LLM-backed YAML skill without `model` or with an unknown model fails startup actionably.
- [ ] A mapped-only application with no configured framework models and no `ChatModel` bean discovers and invokes its Java target through `SkillTemplate`.
- [ ] A mapped YAML skill may declare only `name`, `description`, `mapping.target_id`, and `rbac_roles`.
- [ ] A mapped declaration of any of `model`, `thinking_level`, `prompt`, `input_schema`, `output_schema`, `planning_mode`, `max_steps`, `allowed_skills`, `linter`, `output_schema_max_retries`, or `evidence_contract` fails startup with an actionable field-specific error.
- [ ] Applicability checks treat explicit null, blank, false, zero, and empty collections as declarations.
- [ ] Applicability validation runs before model lookup, field-content validation, retry validation, and Java-target lookup, with deterministic first-error ordering.
- [ ] Every manifest error identifies the public skill name and YAML resource.
- [ ] A mapped skill publishes and enforces its Java target's reflected input contract; explicit mapped-schema compatibility code and fixtures are removed.
- [ ] Mapped `output_schema` is rejected because output is Java-owned; this ticket adds no YAML return adapter or reflected output-schema feature.
- [ ] No dummy model, provider, provider-model, or thinking value is stored for mapped definitions; mapped capability metadata uses `SkillExecutionDescriptor.none()`.
- [ ] Model-backed consumers fail clearly if an impossible unconfigured LLM definition reaches them.
- [ ] Parent LLM skills may continue to list mapped children in parent-owned `allowed_skills` and evidence contracts.
- [x] `ModelPreference.java`, the `@SkillMethod` member, record components, constructor arguments, imports, compatibility overloads, and tests are removed; starter and full-reactor tests pass.
- [ ] Reflected input validation, runtime markers, binding, direct invocation, RBAC, parent evidence attribution, exceptions, and tracing continue to pass for mapped skills.
- [ ] All mapped samples and fixtures use only the allowed mapped-wrapper fields.
- [ ] Documentation describes the implemented behavior without a temporary limitation warning.
- [ ] The completed public-skill/Java-target separation remains compatible with the execution-kind representation.

## Verification Targets

- `YamlSkillCatalogTests`
- `YamlSkillCapabilityRegistrarTests`
- `BifrostAutoConfigurationTests` or an equivalent model-free application-context test
- `CapabilityExecutionRouterTest`
- `SkillTemplateTest`
- `SkillMethodTest`
- `SkillImplementationTargetTest`
- `CapabilityMetadataTest`
- mapped input-contract inheritance, validation, binding, runtime-marker, RBAC, evidence, and tracing tests
- sample context and catalog tests
- full starter and reactor test suites
- repository search proving no live `ModelPreference` reference remains

## Implementation Anchors

- [`YamlSkillCatalog.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java)
- [`YamlSkillManifest.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java)
- [`YamlSkillDefinition.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java)
- [`YamlSkillCapabilityRegistrar.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java)
- [`EffectiveSkillExecutionConfiguration.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/EffectiveSkillExecutionConfiguration.java)
- [`SkillExecutionDescriptor.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillExecutionDescriptor.java)
- [`CapabilityMetadata.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java)
- [`SkillImplementationTarget.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillImplementationTarget.java)
- [`SkillMethod.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/annotation/SkillMethod.java)
- [`SkillMethodBeanPostProcessor.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java)
- [`SkillInputContractResolver.java`](../../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputContractResolver.java)

## Related Work

- [Codebase research and gap analysis](../research/2026-07-13-simplify-mapped-yaml-skill-manifests.md)
- [Public YAML skill and Java target separation](eng-separate-public-skills-from-java-targets.md)
- [Public YAML skill-name validation](eng-validate-public-yaml-skill-names.md)
- [Incident Commander sample](eng-sample-htn-incident-commander.md)
- [Bifrost Framework Feature Design Lens](../framework-feature-design-lens.md)
