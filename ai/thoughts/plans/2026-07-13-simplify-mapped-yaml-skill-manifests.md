# Simplify Mapped YAML Skill Manifests Implementation Plan

## Overview

Make YAML execution kind a first-class startup boundary. LLM-backed manifests continue to require a configured model and may use model-runtime fields; mapped manifests may contain only public identity, RBAC, and `mapping.target_id`, inherit their input/output behavior from Java, and carry no model execution configuration. YAML discovery must still run when no framework models or `ChatModel` bean exist.

The related destructive `ModelPreference` removal is already present and verified in the working tree. It is completed work within this ticket and must remain visible in the same pull request for coherent review; this plan records and verifies it without repeating the implementation.

The dedicated testing artifact is `ai/thoughts/plans/2026-07-13-simplify-mapped-yaml-skill-manifests-testing.md`; it fixes the invalid-manifest matrix, failing-first test, commands, and exit criteria before production changes begin.

## Current State Analysis

- `YamlSkillCatalog.afterPropertiesSet()` returns before resource discovery when `bifrost.models` is empty, and `loadDefinition()` validates and resolves `model` before it distinguishes mapped from LLM-backed definitions (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:70-88`, `YamlSkillCatalog.java:137-169`).
- `YamlSkillManifest` normalizes several absent/null values and initializes `mapping` and collection fields, so the parsed object cannot currently distinguish omission from explicit `null`, `false`, `0`, or `[]` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:16-50`, `YamlSkillManifest.java:102-120`, `YamlSkillManifest.java:152-180`).
- `YamlSkillDefinition` always stores an `EffectiveSkillExecutionConfiguration`; it derives implementation type from a normalized nonblank target rather than from declaration of the `mapping` block (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:14-33`, `YamlSkillDefinition.java:85-102`).
- The registrar always projects `SkillExecutionDescriptor.from(...)` and still supports structurally compatible duplicate mapped `input_schema` declarations (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:48-68`, `YamlSkillCapabilityRegistrar.java:97-132`).
- Direct mapped routing already bypasses `ExecutionCoordinator` while retaining wrapper RBAC, effective-input validation, runtime-ref resolution, and Java invocation (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:50-85`).
- Existing mapped fixtures and samples declare fake models; the two sample manifests additionally declare `planning_mode: false`. The README and authoring mental model still document those as temporary requirements (`bifrost-sample/src/main/resources/skills/basics/expense_lookup.yml:1-7`, `bifrost-sample/src/main/resources/skills/vision/feedstock_ticket_parser.yml:1-9`, `README.md:90-185`, `ai/skill-authoring/mental-model.md:63-93`).
- `ModelPreference` has already been removed from the enum, annotation, target/metadata records, construction paths, and tests. The current worktree has no live `ModelPreference`/`modelPreference` source references, and the research records successful starter and full-reactor test runs.

## Desired End State

After this plan is implemented:

- Omitted `mapping` means LLM-backed. An explicitly declared `mapping` with a non-object shape or a missing, null, non-string, or blank `target_id` fails startup as malformed mapping syntax.
- A mapped manifest accepts only `name`, `description`, `rbac_roles`, and `mapping.target_id`.
- A mapped declaration of `model`, `thinking_level`, `prompt`, `input_schema`, `output_schema`, `planning_mode`, `max_steps`, `allowed_skills`, `linter`, `output_schema_max_retries`, or `evidence_contract` fails even when its value is null, blank, false, zero, or empty.
- Mapped applicability failures occur in the stable order above, before model lookup, nested content validation, retry validation, or Java-target resolution. Diagnostics include public skill name, resource, field, reason, and remedy.
- LLM-backed definitions have a required execution configuration; mapped definitions have none and publish `SkillExecutionDescriptor.none()`.
- Mapped capabilities always publish and enforce the Java target's reflected input contract. Mapped `output_schema` is rejected; no YAML return contract or Java return-schema reflection is added.
- A mapped-only application with no framework models and no provider `ChatModel` starts, discovers the YAML wrapper, and invokes it through `SkillTemplate`.
- Parent LLM skills can continue to name mapped children in parent-owned `allowed_skills` and `evidence_contract.tool_evidence`.

### Key Discoveries

- `SkillExecutionDescriptor.none()` already provides the public metadata representation needed for mapped execution (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillExecutionDescriptor.java:9-33`).
- All production model-configuration consumers are downstream of the LLM-only `ExecutionCoordinator` branch, so a narrow `requireExecutionConfiguration()` boundary can localize the nullable definition state without adding scattered null checks (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:69-85`).
- Setter-based declaration tracking must survive `YamlSkillDefinition.copyManifest()`. Jackson `convertValue` invokes setters for serialized defaults, so the copy operation must explicitly restore the source declaration set rather than infer it from copied values (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:119-130`).
- The Java target already owns the mapped tool schema and runtime input contract; the duplicate YAML branch exists only in registrar/resolver compatibility code (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:97-132`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputContractResolver.java:41-64`).
- Auto-configuration already makes `SkillTemplate` and the router available independently of a usable chat model, providing a natural application-context seam for the model-free integration test (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java:49-104`).

## What We're NOT Doing

- Auto-registering Java targets as public skills or adding public aliases for target IDs.
- Adding a default model, a `model: none` sentinel, or changing LLM-backed model selection.
- Adding YAML argument transformation, schema narrowing, parent-input inheritance, shared schema fragments, `$ref`, `extends`, or multi-skill YAML resources.
- Reflecting Java output schemas, validating deterministic return values against YAML, or adding a mapped return adapter.
- Redesigning LLM-backed planning, linting, retry, evidence, tool visibility, RBAC, or tracing semantics.
- Reworking mapped invocation, bean/proxy lookup, exception transformation, runtime refs, or public/target registry separation beyond regression coverage.
- Restoring any `ModelPreference` compatibility type, annotation member, constructor, or overload.

## Implementation Approach

Keep one `YamlSkillManifest` representation and add explicit root-field declaration metadata rather than introducing a parallel raw-manifest hierarchy. Inspect the raw YAML tree for identity, mapping syntax, and root declaration presence before typed binding so an unbindable value on an inapplicable mapped field cannot win validation precedence. Classify the manifest immediately after `name` and `description` validation. For mapped definitions, reject contradictory fields from declaration metadata and skip every model/content validator. For LLM-backed definitions, preserve the existing validation/resolution path.

Represent the resulting invariant in `YamlSkillDefinition`: mapped definitions carry a nullable execution configuration that must be absent, while LLM-backed definitions require one. Expose `requireExecutionConfiguration()` for model consumers and use `SkillExecutionDescriptor.none()` only at mapped public registration. This keeps nullable state at the execution-kind seam.

## Phase 1: Preserve Manifest Syntax and Encode the Definition Invariant

### Overview

Retain which root YAML fields were written, classify explicit mapping syntax correctly, and make invalid execution-kind/configuration combinations unrepresentable after definition construction.

### Changes Required

#### 1. Track root-field declarations independently from normalized values

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`

**Changes**:

- Add an internal enum or equivalent stable field identifier and a private declaration set for the conditionally applicable root fields plus `mapping`.
- Mark a field as declared from each root setter before normalization, including when Jackson supplies `null`, `false`, `0`, an empty list, or a blank string.
- Expose package-private/read-only helpers such as `isDeclared(field)` and `declaredFields()`; keep declaration metadata out of YAML serialization with Jackson ignore annotations.
- Preserve the current normalized getters so LLM-backed behavior and existing consumers do not change.
- Keep `rbac_roles` allowed for mapped wrappers; it may be tracked for completeness but must not enter the mapped rejection list.
- Treat `mapping: null`, `mapping: {}`, and `mapping: { target_id: null }` as declared mappings. Normalizing a null mapping object to an empty `MappingManifest` remains acceptable because declaration metadata retains the syntax distinction.

#### 2. Preserve declaration metadata across defensive copies

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java`

**Changes**:

- After the Jackson-based defensive copy, explicitly replace the copy's declaration set with the source set. Do not allow `convertValue` to turn serialized null/default properties into declarations.
- Ensure `manifest()` and nested defensive accessors still return isolated values while retaining the same declaration facts.
- Add focused tests for omitted versus explicit-null/blank/false/zero/empty fields before catalog validation is layered on top.

#### 3. Encode configured LLM versus unconfigured mapped definitions

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java`
- model-backed consumers found by `rg "executionConfiguration\(\)" bifrost-spring-boot-starter/src/main/java`
- corresponding unit tests

**Changes**:

- Annotate the record's `executionConfiguration` component as nullable.
- Derive implementation type from explicit mapping declaration after catalog validation, not merely from a normalized target value.
- In the compact constructor, reject a mapped definition without a nonblank target, a mapped definition with execution configuration, and an LLM-backed definition without execution configuration.
- Add `requireExecutionConfiguration()` with an invariant-oriented error and migrate chat-client, mission, planning, step-loop, attachment, and other model-backed consumers to it.
- Keep `mappingTargetId()` normalized for registry lookup, but do not use blank normalization to reinterpret an explicitly declared mapping as LLM-backed.

### Success Criteria

#### Automated Verification

- [x] Manifest/definition tests distinguish omission from explicit `null`, blank, `false`, `0`, and empty collections.
- [x] Defensive copies preserve declaration metadata without exposing mutable internal state.
- [x] Definition construction rejects malformed mapped targets and both impossible execution-kind/configuration combinations.
- [x] Every model-backed production consumer uses `requireExecutionConfiguration()` or an equivalent explicit boundary: `rg "\.executionConfiguration\(\)" bifrost-spring-boot-starter/src/main/java` returns only intentional invariant code.
- [x] Focused tests pass: `mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCatalogTests,YamlSkillCapabilityRegistrarTests" test`.

#### Manual Verification

- [x] Review the manifest API and confirm normalized values and declaration presence cannot be confused by callers.
- [x] Review invariant errors and confirm they diagnose framework/programming faults rather than presenting them as authoring errors.

**Implementation Note**: Pause after this phase for confirmation that declaration-presence copying and the two execution-kind invariants are correct before changing catalog validation.

---

## Phase 2: Classify and Validate Before Model or Content Resolution

### Overview

Restructure catalog loading so syntax determines execution kind first, mapped applicability errors are deterministic, and resource discovery no longer depends on model configuration.

### Changes Required

#### 1. Always discover configured YAML resources

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`

**Changes**:

- Remove the empty-model-catalog early return from `afterPropertiesSet()`.
- Preserve deterministic resource ordering, missing-root behavior, duplicate-name detection, and defensive catalog access.
- Add empty-model-catalog coverage proving an LLM-backed resource fails for missing/unknown `model` instead of silently producing an empty catalog.

#### 2. Add mapping classification and malformed-mapping validation

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`

**Changes**:

- After parsing and validating `name` and `description`, inspect declaration presence for `mapping`.
- If mapping is omitted, enter the existing LLM-backed branch and require a nonblank known model.
- If mapping is declared, require an object containing a nonblank string `mapping.target_id`; diagnose null, empty, non-object, null-target, non-string-target, and blank-target forms at `mapping.target_id`.
- Replace the registrar test that currently normalizes a blank mapping to LLM-backed metadata with catalog-level invalid fixtures.

#### 3. Reject mapped-only fields in one stable applicability pass

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`

**Changes**:

- Validate declarations in this exact order: `model`, `thinking_level`, `prompt`, `input_schema`, `output_schema`, `planning_mode`, `max_steps`, `allowed_skills`, `linter`, `output_schema_max_retries`, `evidence_contract`.
- Fail on declaration presence from the raw YAML tree regardless of whether the value can bind to the typed manifest, then retain the same facts in manifest declaration metadata for defensive copies.
- Run this pass before model lookup, schema/linter/evidence content validation, retry defaulting/range validation, and Java-target lookup.
- Replace the prompt truthiness check with the general presence-aware applicability pass so `prompt: ""` and `prompt: null` are also rejected.
- Add an error helper that includes the validated public skill name and resource description/URI. Preserve field paths for generic Jackson mapping and unknown-field failures where a valid name may not yet be available.
- Add a multi-error fixture to lock the first-error order and individual fixtures for all eleven rejected fields.

#### 4. Preserve the LLM-backed validation path

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`

**Changes**:

- Move existing required-model, catalog lookup, thinking default/support, prompt, input/output schema, linter, retry, and evidence validation into the LLM-backed branch without changing their semantics.
- Construct `EffectiveSkillExecutionConfiguration` and `EvidenceContract` only for LLM-backed definitions.
- Construct mapped definitions with no execution configuration and an empty evidence contract after applicability validation succeeds.

### Success Criteria

#### Automated Verification

- [x] Omitted mapping loads as LLM-backed; missing/unknown LLM model failures remain actionable.
- [x] Missing, null, blank, non-string, and non-object mapping fixtures fail at `mapping.target_id` and are not reclassified as LLM-backed.
- [x] Each rejected mapped field has an independent startup test; fixtures collectively cover explicit null, blank, false, zero, and empty-list declarations.
- [x] A multi-error mapped fixture fails first on the documented stable field order, and a focused assertion locks the complete eleven-field order.
- [x] An unknown mapped model reports inapplicable `model` before model lookup, malformed mapped schemas report field inapplicability before schema shape, and invalid retry/linter/evidence contents never win over applicability.
- [x] All applicable startup errors contain public skill name, resource, field, reason, and remedy.
- [x] Catalog tests pass: `mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests test`.

#### Manual Verification

- [x] Read one diagnostic from each explanation category (model runtime, input ownership, output ownership, nested tools, retry/linter, evidence) and confirm an author can correct the manifest locally.
- [x] Confirm the validation table in tests matches the ticket's exact allowed/rejected field matrix.

**Implementation Note**: Pause after this phase for confirmation of validation order and diagnostic wording before simplifying registration.

---

## Phase 3: Publish Honest Mapped Metadata and Java-Owned Contracts

### Overview

Remove fabricated mapped execution metadata and duplicate YAML/Java schema compatibility while preserving the existing direct Java route.

### Changes Required

#### 1. Project execution metadata by execution kind

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java`

**Changes**:

- For `LLM_BACKED`, construct metadata from `requireExecutionConfiguration()`.
- For `MAPPED_JAVA`, publish `SkillExecutionDescriptor.none()` and never manufacture framework model, provider, provider-model, or thinking values.
- Keep public name/description/RBAC, target ID, target invoker, tool descriptor, and input contract unchanged.
- Assert mapped metadata is unconfigured while LLM-backed metadata remains configured.

#### 2. Make mapped input/output ownership exclusively Java-backed

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputContractResolver.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/input/SkillInputContractResolverTest.java`

**Changes**:

- Delete the registrar's explicit mapped-schema compatibility branch. A mapped definition always copies the resolved Java target contract as `YAML_INHERITED` and publishes the target's reflected JSON schema with the public YAML name/description.
- Remove compatibility/marker-merge methods and tests from `SkillInputContractResolver` if no LLM-backed or Java-reflection path still calls them.
- Delete or repurpose the currently valid duplicate-schema and mismatch fixtures as presence-aware `input_schema` rejection cases.
- Retain pure LLM YAML `input_schema` resolution and validation unchanged.
- Reject mapped `output_schema` solely in catalog validation; do not add a return schema to `CapabilityMetadata` or validate Java return values.

#### 3. Add a truly model-free mapped application integration test

**Files**:

- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
- a minimal valid mapped fixture under `bifrost-spring-boot-starter/src/test/resources/skills/valid/`
- mapped target test configuration in the same test class or a focused integration test

**Changes**:

- Use an `ApplicationContextRunner` that does not import `application-test.yml` model entries and does not register any provider `ChatModel`.
- Load a mapped manifest containing only `name`, `description`, optional `rbac_roles`, and a valid target ID.
- Assert the context starts, the public capability has `SkillExecutionDescriptor.none()`, no chat model is present/resolved, and `SkillTemplate.invoke(...)` reaches the Java target with reflected input validation.
- Add a sibling LLM-backed/no-model test proving that the same empty catalog fails clearly rather than skipping discovery.

#### 4. Preserve mapped runtime behavior with regression coverage

**Files**:

- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/CapabilityExecutionRouterTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skillapi/SkillTemplateTest.java`
- existing registrar, target, RBAC, evidence, and trace tests

**Changes**:

- Retain root and nested reflected-input validation, argument binding, runtime `ref://` materialization, wrapper RBAC, parent evidence attribution, transformed exceptions, proxy/lazy/prototype target behavior, and public-name tracing.
- Keep parent-owned `allowed_skills` and `evidence_contract.tool_evidence` fixtures that refer to mapped children; only declarations on the mapped child are invalid.
- Preserve multiple public wrappers targeting one Java method with independent descriptions/RBAC and the same inherited Java contract.

### Success Criteria

#### Automated Verification

- [x] Mapped registrar tests assert `SkillExecutionDescriptor.none()`; LLM-backed tests assert configured metadata.
- [x] No mapped compatibility call remains: `rg "validateStructuralCompatibility" bifrost-spring-boot-starter/src/main/java` returns no matches unless another non-mapped use is explicitly justified.
- [x] No valid mapped fixture contains a rejected field.
- [x] A context with no `bifrost.models` and no `ChatModel` discovers and invokes a mapped skill through `SkillTemplate`.
- [x] A no-model context containing an LLM-backed manifest fails at `model`.
- [x] Focused integration tests pass: `mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=BifrostAutoConfigurationTests,YamlSkillCapabilityRegistrarTests,CapabilityExecutionRouterTest,SkillTemplateTest,SkillInputContractResolverTest" test`.
- [x] Repository search still finds no live legacy preference API: `rg "ModelPreference|modelPreference" bifrost-spring-boot-starter/src/main bifrost-spring-boot-starter/src/test` returns no matches.

#### Manual Verification

- [x] Inspect mapped public metadata and confirm no model/provider/thinking fields are populated.
- [x] Inspect a mapped tool descriptor and confirm its schema comes from the Java target while its name and description come from YAML.

**Implementation Note**: Pause after this phase for confirmation that model-free invocation and Java-owned contract behavior are correct before updating every fixture/sample/document.

---

## Phase 4: Migrate Fixtures, Samples, and Authoring Guidance

### Overview

Make all executable examples and documentation express the final strict mapped-wrapper contract.

### Changes Required

#### 1. Normalize mapped test fixtures

**Files**: mapped YAML resources under `bifrost-spring-boot-starter/src/test/resources/skills/valid/` and `skills/invalid/`

**Changes**:

- Remove `model` and every other rejected field from fixtures whose purpose is valid mapping, shared targets, RBAC, lazy/prototype targets, unknown targets, or unrelated mapping diagnostics.
- Keep rejected fields only in focused invalid applicability fixtures, with one contradictory field per fixture except the validation-order fixture.
- Replace schema mismatch expectations with direct `input_schema` inapplicability expectations.
- Add valid mapped fixtures with and without `rbac_roles` to prove the complete allowed field set.

#### 2. Simplify sample manifests and retain Java-owned behavior

**Files**:

- `bifrost-sample/src/main/resources/skills/basics/expense_lookup.yml`
- `bifrost-sample/src/main/resources/skills/vision/feedstock_ticket_parser.yml`
- Incident Commander mapped leaf manifests added by dependent work, if present when implementation starts
- sample catalog/context tests

**Changes**:

- Remove `model` and `planning_mode: false` from both current mapped samples and remove all rejected fields from later mapped leaves.
- Keep YAML public identity, target IDs, and any applicable RBAC intact.
- Confirm target annotations/DTO parameters remain sufficient to produce the intended mapped input contracts.
- Preserve the feedstock service's own explicit external API behavior; it is Java implementation behavior, not YAML model execution.

#### 3. Update public and AI authoring documentation

**Files**:

- `README.md`
- `bifrost-sample/README.md`
- `ai/skill-authoring/mental-model.md`
- `ai/skill-authoring/checklists/evaluate-a-skill-design.md`
- `ai/skill-authoring/README.md` if its coverage/index text references the old rule

**Changes**:

- State that `model` is required only for LLM-backed YAML.
- Publish the mapped allowed-field list and representative minimal manifest.
- Explain syntactic mapping classification, invalid blank/incomplete mapping, and declaration-presence semantics.
- Replace temporary mapped-model/compatible-input-schema warnings with the final Java-owned input/output contract rule and Java-adapter guidance.
- Explain that mapped children remain valid entries in parent-owned allowlists/evidence mappings, while those fields cannot be declared on the child wrapper itself.
- Remove examples that show `planning_mode: false`, fake model configuration, or duplicate schemas on mapped manifests.

### Success Criteria

#### Automated Verification

- [x] All mapped resources satisfy the allowed-field contract except focused invalid fixtures; enforce with a repository scan/test rather than relying only on review.
- [x] Sample context/catalog tests pass: `mvnw.cmd -pl bifrost-sample -am -DskipTests=false test`.
- [x] Starter tests pass: `mvnw.cmd -pl bifrost-spring-boot-starter test`.
- [x] Full reactor passes: `mvnw.cmd test`.
- [x] Documentation contains no temporary claim that mapped YAML requires a model or may duplicate a compatible input schema: `rg "mapped.*must.*model|mapped.*compatible.*input_schema|current-checkout limitations" README.md bifrost-sample/README.md ai/skill-authoring` returns no stale matches.
- [x] All repository-relative documentation links resolve.

#### Manual Verification

- [ ] Start the sample with its normal configuration and invoke `/expenses`; confirm `expenseLookup` still reaches the deterministic target.
- [ ] Exercise the mapped feedstock sample and confirm its Java-owned service behavior is unchanged.
- [x] Read the README and mental model as a new author and confirm LLM-backed versus mapped allowed fields are understandable without source inspection.

**Implementation Note**: After automated verification passes, pause for final human confirmation of sample behavior and documentation wording before beginning the dependent YAML-name validation ticket.

---

## Testing Strategy

### Unit Tests

- Declaration tracking for omission and explicit null/blank/false/zero/empty values, including preservation through defensive copying.
- Mapping classification for omitted, null, empty, blank-target, and valid-target blocks.
- Definition invariants and `requireExecutionConfiguration()` behavior.
- Stable mapped-field rejection order and field-specific diagnostic contents.
- Unchanged LLM model resolution, thinking defaults/support, schema validation, linter, retry, and evidence behavior.
- Registrar projection to configured versus none execution descriptors.
- Java-reflected mapped input inheritance and pure-LLM explicit/generic input contracts.

### Integration Tests

- Model-free application context discovers, registers, validates, and invokes a mapped YAML skill without `bifrost.models`, a provider `ChatModel`, or model coordination.
- Empty-model LLM and mixed-resource contexts fail on the LLM manifest rather than suppressing discovery.
- Root/nested mapped invocation preserves RBAC, input validation, refs, binding, exceptions, parent evidence, and tracing.
- Shared Java targets retain independent public wrappers/governance.
- Sample application contexts load after mapped manifest cleanup.

### Manual Testing Steps

1. Run the sample and invoke the deterministic expense endpoint through its YAML public name.
2. Inspect registered metadata for one LLM-backed and one mapped skill; verify configured versus none execution descriptors.
3. Start with a malformed mapping and one contradictory mapped field and review the complete startup diagnostics.
4. Inspect a mapped execution trace and verify the YAML public name remains the attributed capability.

## Performance Considerations

- Declaration tracking adds a small fixed-size set per parsed manifest and only affects startup.
- Removing duplicate schema compatibility eliminates recursive mapped-schema comparison during registration.
- Always discovering YAML with an empty model catalog adds resource scanning only when YAML locations are configured; it replaces silent suppression with required validation.
- Runtime mapped routing remains unchanged and does not add model resolution or registry lookup to the invocation hot path.

## Migration Notes

This is a pre-release breaking authoring correction with no compatibility bridge:

- Remove every model/runtime field from mapped manifests, including explicit false, zero, empty, blank, and null declarations.
- Keep only `name`, `description`, optional `rbac_roles`, and nonblank `mapping.target_id` on mapped wrappers.
- Move any public input/output transformation into a distinct Java adapter target; do not duplicate Java contracts in YAML.
- Keep parent composition declarations on the LLM parent, not on the mapped child.
- Applications that package LLM-backed YAML without configuring models will now fail startup instead of publishing an empty catalog.
- Rollback is a code/manifest rollback only; no persistent data migration is involved.

## Completed Workstream in This PR: Legacy ModelPreference Removal

- [x] Enum source deleted.
- [x] `@SkillMethod` member removed.
- [x] `SkillImplementationTarget` and `CapabilityMetadata` components/constructors updated.
- [x] Processor, registrar, callers, and tests updated without deprecated overloads.
- [x] Starter tests passed (461 tests in the recorded verification).
- [x] Full starter/sample reactor passed in the recorded verification.
- [x] Live starter source/test search contains no `ModelPreference` or `modelPreference` references.

Review this completed workstream as an intentional part of the ticket. Do not split it into a separate delivery, restore compatibility APIs, or redo the removal while implementing the remaining phases.

## References

- Original ticket: `ai/thoughts/tickets/eng-simplify-mapped-yaml-skill-manifests.md`
- Research and resolved follow-ups: `ai/thoughts/research/2026-07-13-simplify-mapped-yaml-skill-manifests.md`
- Prerequisite plan: `ai/thoughts/plans/2026-07-13-separate-public-skills-from-java-targets.md`
- Dedicated test-plan command: `ai/commands/3_testing_plan.md`
- Dedicated testing plan: `ai/thoughts/plans/2026-07-13-simplify-mapped-yaml-skill-manifests-testing.md`
- Catalog/classification anchor: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:70-179`
- Manifest presence anchor: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:16-180`
- Definition invariant anchor: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:14-102`
- Registration/contract anchor: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:48-162`
- Direct mapped route: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:50-85`
- Model-free context seam: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java:49-119`
