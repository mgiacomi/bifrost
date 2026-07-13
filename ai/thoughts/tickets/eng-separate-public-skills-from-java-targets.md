# ENG - Separate Public YAML Skills from Java Implementation Targets

**Date:** 2026-07-12

**Status:** Proposed

**Priority:** P1 before expanding the nested HTN sample gallery

**Type:** Pre-release architectural correction

**Delivery order:** Framework prerequisite 1 of 2. Complete this ticket, then complete [`eng-simplify-mapped-yaml-skill-manifests.md`](eng-simplify-mapped-yaml-skill-manifests.md). Begin new HTN gallery sample implementation only after both are verified.

## Summary

Bifrost currently registers Java `@SkillMethod` targets and YAML skills in the same `CapabilityRegistry`. A Java target receives a registry name from `@SkillMethod.name` (or the Java method name), while a mapped YAML skill receives its public name from the YAML manifest. Both entries may represent the same underlying behavior.

This mixed registry leaks implementation identity into the public skill model, makes intuitive same-name Java/YAML mappings collide, and has already produced an invalid sample path: `/expenses` invokes the raw Java capability name through `SkillTemplate`, even though `SkillTemplate` deliberately supports YAML skills only.

Refactor the architecture so:

- a YAML skill has one public Bifrost name;
- a Java `@SkillMethod` is an internal implementation target;
- `mapping.target_id` explicitly connects the two;
- Java targets and public YAML capabilities occupy separate registries/namespaces;
- `SkillMethod.name` is removed;
- public invocation, planning, evidence, and authoring consistently use YAML names.

No production release exists, so no alias, deprecation bridge, or compatibility shim is required.

## Feature-Lens Evaluation

This correction is justified under the [Bifrost Framework Feature Design Lens](../framework-feature-design-lens.md):

- The problem remains with a highly capable model; it is an API and identity problem.
- Entry callers should need only the public YAML skill name.
- Skill developers should be able to distinguish a public capability from its implementation target locally.
- Removing duplicate public identities reduces concepts and ambiguity rather than adding convenience magic.
- Explicit mapping preserves dataflow and governance boundaries.
- Keeping Java targets out of the public skill registry prevents accidental bypass of YAML contracts, RBAC, descriptions, and execution policy.
- The current sample and documentation already contradict enforced `SkillTemplate` behavior, providing concrete evidence rather than a hypothetical concern.

## Current Behavior

### Java target registration

`SkillMethodBeanPostProcessor` currently derives:

```text
target ID:       beanName#methodName
capability name: @SkillMethod.name, or methodName when name is blank
```

It then registers the Java target as a `JAVA_METHOD` in the shared `CapabilityRegistry`, keyed by the capability name.

### YAML capability registration

`YamlSkillCapabilityRegistrar` registers every YAML skill in the same registry, keyed by the manifest `name`.

For a mapped YAML skill, `mapping.target_id` resolves the Java target by its `beanName#methodName` ID and reuses the target invoker while publishing the YAML name, description, RBAC, and effective input contract.

### Public invocation and planning

- `DefaultSkillTemplate` accepts only `YAML_SKILL` capabilities.
- `DefaultSkillVisibilityResolver` resolves `allowed_skills` through `YamlSkillCatalog` before exposing capability metadata.
- Plans and evidence contracts therefore operate on YAML-facing child names in the current supported tree surface.
- Raw Java registry names are implementation artifacts, despite being stored alongside public capabilities.

### Demonstrated inconsistency

The sample declares:

```yaml
name: expenseLookup
mapping:
  target_id: expenseService#getLatestExpenses
```

but `/expenses` calls:

```java
skillTemplate.invoke("getLatestExpenses", Map.of())
```

`SkillTemplate` rejects that `JAVA_METHOD` kind. The sample code and sample README therefore conflict with the framework's tested public API.

### Same-name collision

The most intuitive declaration:

```java
@SkillMethod
List<Result> checkDns(...) { ... }
```

```yaml
name: checkDns
mapping:
  target_id: incidentTelemetryService#checkDns
```

currently attempts to register both the Java target and YAML wrapper under `checkDns`. `InMemoryCapabilityRegistry` correctly rejects the duplicate key, making the intuitive public/implementation naming choice impossible.

## Decision Locks

The implementation plan must preserve these decisions unless a later design review explicitly changes them.

1. **YAML `name` is the only public Bifrost skill name.**
2. **`@SkillMethod` identifies a Java implementation target, not a public skill.**
3. **Remove `SkillMethod.name`.** Do not deprecate or alias it; the project is pre-release.
4. **Java targets and public YAML capabilities must use separate registries/namespaces.**
5. **`mapping.target_id` remains the explicit link from YAML skill to Java target.**
6. **The readable target ID remains `beanName#methodName`.**
7. **Annotated overloads are prohibited.** If one Spring bean has multiple `@SkillMethod` methods with the same Java method name, startup MUST fail with a clear error explaining that `beanName#methodName` is ambiguous and annotated target method names must be unique within the bean.
8. **One Java target may back multiple YAML skills.** Public wrappers may legitimately have different names, descriptions, RBAC, visibility, or authoring roles while sharing deterministic implementation and its reflected input contract.
9. **A YAML public name may equal its backing Java method name.** Separate namespaces must make this collision-free.
10. **`SkillTemplate` remains YAML-only.** It already invokes both LLM-backed and mapped YAML skills.
11. **Do not add raw Java invocation, automatic aliases, or name fallback to `SkillTemplate`.**
12. **`allowed_skills`, plan capability names, evidence `tool_evidence`, and public skill traces use YAML names.**
13. **Mapped input-contract inheritance, exception transformation, ref resolution, and execution-time security must remain intact.** Explicit mapped-schema compatibility is handled by the coordinated manifest-simplification ticket.

## Goals

- Establish one intuitive public identity for every Bifrost skill.
- Make Java implementation identity explicitly internal.
- Remove the shared-name collision between a Java method and its YAML wrapper.
- Prevent accidental public invocation of raw Java targets through Bifrost orchestration APIs.
- Preserve deterministic Java mapping behavior and contract inheritance.
- Produce actionable startup errors for missing or ambiguous Java targets.
- Correct samples and documentation to use YAML names consistently.
- Leave the public catalog clean and enumerable for future SkillBuilder tooling.

## Non-Goals

- Do not add an Actuator endpoint or HTTP catalog dump.
- Do not implement the SkillBuilder application.
- Do not add generated manifests or typed clients.
- Do not broaden `SkillTemplate` to raw Java targets.
- Do not add aliases between old Java capability names and YAML names.
- Do not redesign input schemas, output schemas, evidence contracts, prompts, RBAC, or execution traces beyond changes required to preserve their current semantics under the new identity boundary.
- Do not remove or redesign `SkillMethod.description` or legacy `modelPreference` as part of this ticket unless compilation requires a narrowly scoped adjustment. Their long-term value may be evaluated separately.
- Do not introduce signature-heavy target IDs to support overloaded annotated methods.

## Coordination with Mapped Manifest Simplification

[`eng-simplify-mapped-yaml-skill-manifests.md`](eng-simplify-mapped-yaml-skill-manifests.md) separately removes semantically irrelevant model configuration and duplicate `input_schema` declarations from mapped YAML skills. That behavior is not added to this ticket's scope, but the two implementations must coordinate because both affect `YamlSkillDefinition`, catalog loading, capability metadata, input contracts, and mapped registration.

Implement these tickets sequentially: complete this public-skill/implementation-target separation first, then simplify mapped manifests against the corrected registry architecture. Do not make non-null model execution configuration or explicit mapped-schema compatibility a permanent invariant while implementing this ticket.

The package boundary is deliberate:

- this ticket owns identity, registries, target IDs, public invocation, and removal of `SkillMethod.name`;
- the second ticket owns mapped manifest applicability for `model`, `thinking_level`, and `input_schema`;
- neither ticket auto-generates or auto-registers public YAML skills from Java methods;
- both tickets must land before new gallery trees are implemented.

## Desired Architecture

```text
Spring bean discovery
    @SkillMethod
        |
        v
ImplementationTargetRegistry
    key: beanName#methodName
    value:
      - deterministic invoker
      - reflected input contract/tool schema
      - implementation metadata needed for mapping and diagnostics

YAML discovery
        |
        v
YamlSkillCatalog
    key: YAML name
    value:
      - manifest
      - resolved model/execution configuration when LLM-backed
      - normalized contracts

YAML capability registration
        |
        v
Public CapabilityRegistry
    key: YAML name
    value:
      - LLM-backed YAML capability
      - or mapped YAML capability delegating to an implementation target
```

Mapping remains explicit:

```text
public YAML skill
    name: expenseLookup
        |
        | mapping.target_id
        v
Java target
    expenseService#getLatestExpenses
```

## Proposed Runtime Types

Exact names may be adjusted during implementation, but responsibilities must remain separate.

### Java implementation target

Introduce an immutable descriptor concept such as:

```java
public record SkillImplementationTarget(
        String id,
        CapabilityInvoker invoker,
        CapabilityToolDescriptor tool,
        SkillInputContract inputContract,
        String description)
{
}
```

The descriptor must not have a second public skill name.

### Implementation target registry

Introduce a registry concept such as:

```java
public interface SkillImplementationTargetRegistry
{
    void register(SkillImplementationTarget target);

    SkillImplementationTarget getTarget(String targetId);

    List<SkillImplementationTarget> getAllTargets();
}
```

The implementation must reject duplicate target IDs and ambiguous annotated overloads with actionable messages.

### Public capability registry

`CapabilityRegistry` should contain YAML capabilities only after application initialization. Its name-keyed enumeration becomes an accurate public skill surface rather than a mixture of public and implementation entries.

## SkillBuilder Readiness

This ticket should make the architecture ready for later SkillBuilder introspection without adding a speculative endpoint.

### Public catalog information to preserve

Future tooling must be able to derive immutable public metadata for each YAML skill:

- public YAML name;
- description;
- implementation type: LLM-backed or mapped Java;
- mapping target ID when mapped;
- input contract;
- output contract;
- direct `allowed_skills`;
- evidence contract;
- planning settings when LLM-backed;
- configured model and thinking level when LLM-backed;
- RBAC requirements.

Most of this already exists in `YamlSkillDefinition`, `YamlSkillManifest`, and public capability metadata. The refactor must not discard or hide it behind invoker implementation details.

### Explicit implementation type

Tooling should be able to distinguish:

```text
LLM_BACKED
MAPPED_JAVA
```

without inspecting the concrete invoker. The implementation may use a helper, descriptor projection, or explicit enum if needed. Do not broaden this into an endpoint design.

### Public versus internal projections

Keep public authoring metadata separate from internal target metadata:

```text
Public authoring metadata
    - YAML name
    - description
    - contracts
    - child visibility
    - governance and execution settings

Internal implementation metadata
    - Spring bean name
    - Java method name
    - target ID
    - reflection/binding details
```

This separation should allow a future safe catalog projection without making internal Spring structure part of the public skill identity.

## Suggested Ticket Phases

### Phase 1 - Define identity semantics

- Document YAML `name` as the only public Bifrost skill identity.
- Document `beanName#methodName` as an internal implementation target ID.
- Identify every current path that assumes Java targets are public named capabilities.
- Identify every test, sample, and document that invokes or exposes raw Java capability names.

### Phase 2 - Introduce the Java implementation-target registry

- Add an immutable Java target descriptor.
- Add a dedicated implementation-target registry keyed by `beanName#methodName`.
- Move `@SkillMethod` discovery registration into that registry.
- Reject duplicate target IDs.
- Reject multiple annotated methods with the same method name on one bean.
- The overload error MUST identify the bean, method name, ambiguous target ID, and the unique-name requirement.
- Verify registration lifecycle ordering so all Java targets exist before mapped YAML capabilities resolve them.

### Phase 3 - Remove `SkillMethod.name`

- Delete the annotation attribute.
- Remove name-defaulting logic from `SkillMethodBeanPostProcessor`.
- Update all starter tests, sample services, fixtures, and documentation.
- Do not preserve aliases derived from the former annotation name.
- Keep method descriptions and reflected input contracts available to mapping internals where still required.

### Phase 4 - Make the public capability registry YAML-only

- Stop registering raw Java targets in `CapabilityRegistry`.
- Keep pure LLM-backed and mapped YAML skills registered by YAML name.
- Ensure capability collision checks now describe duplicate public YAML names only.
- Update tests that previously treated a raw Java target as a public capability.
- Preserve a direct, focused test surface for Java target discovery and invocation through the new target registry.

### Phase 5 - Update mapped target resolution

- Resolve `mapping.target_id` through the implementation-target registry.
- Preserve inherited Java input contracts for mapped YAML skills.
- Coordinate removal of explicit mapped `input_schema` with the mapped-manifest simplification ticket; do not create a replacement duplicate-contract path.
- Preserve deterministic invocation, exception transformation, and runtime ref binding.
- Preserve clear startup failure for unknown `mapping.target_id`.
- Permit multiple YAML skills to resolve the same Java target.

### Phase 6 - Preserve current mapped behavior

- Verify mapped YAML root invocation through `SkillTemplate`.
- Verify mapped YAML nested invocation through `allowed_skills`.
- Verify RBAC applies to the YAML wrapper at discovery and execution.
- Verify planners, plans, tool callbacks, evidence contracts, journals, and public traces use the YAML name.
- Verify internal diagnostics can still identify the target ID where appropriate without treating it as a public skill name.

### Phase 7 - Correct samples and documentation

- Change `/expenses` to invoke `expenseLookup`.
- Remove claims that `SkillTemplate` can invoke raw Java capability names.
- State that `allowed_skills` contains YAML skill names in the current public tree model.
- Ensure Incident Commander and every gallery sample use YAML names in controllers, `allowed_skills`, plans, evidence contracts, and public explanations.
- Java method names and `mapping.target_id` remain visible only where implementation mapping is being taught.

### Phase 8 - Verify tooling readiness

- Demonstrate that public catalog enumeration contains YAML skills and no raw Java targets.
- Demonstrate that tooling can distinguish LLM-backed and mapped-Java YAML skills without inspecting invokers.
- Verify public metadata needed for a future SkillBuilder remains obtainable from immutable catalog/descriptor state.
- Do not add an HTTP or Actuator exposure in this phase.

### Phase 9 - Update the AI skill-authoring knowledge base

- Update `ai/skill-authoring/mental-model.md` with the final verified registry and identity semantics.
- Add a focused naming/mapping topic if the final design requires more detail than the mental model should carry.
- Add implementation anchors for the target registry, mapped registrar, public catalog, and focused tests.
- Update the coverage matrix in `ai/skill-authoring/README.md`.
- Ensure guidance remains self-contained while allowing SkillBuilder to inspect source for exact behavior.

## Required Startup Errors

Errors should name the invalid authoring element and the remedy.

### Unknown mapping target

Example shape:

```text
Invalid YAML skill 'expenseLookup' for field 'mapping.target_id':
unknown implementation target 'expenseService#getLatestExpenses'.
```

### Ambiguous annotated overload

Example shape:

```text
Invalid @SkillMethod targets on bean 'expenseService': methods named 'lookup'
produce the ambiguous target ID 'expenseService#lookup'. Annotated target method
names must be unique within a Spring bean; rename one method.
```

Do not silently select an overload or derive signature-based aliases.

### Duplicate implementation target

Example shape:

```text
Duplicate skill implementation target ID 'expenseService#getLatestExpenses'.
Each @SkillMethod target must have a unique beanName#methodName ID.
```

### Wrong public invocation name

`SkillTemplate` should continue to report that a missing or Java implementation identifier is not a known YAML skill. If a later diagnostic can safely identify mapped YAML wrappers for a target, that may improve the error, but it is not required by this ticket and must not enable fallback invocation.

## Test Plan

### Annotation and target discovery

1. `@SkillMethod` compiles without a `name` attribute.
2. An annotated method registers under `beanName#methodName` in the implementation-target registry.
3. The descriptor preserves its invoker, reflected tool schema, input contract, and required implementation metadata.
4. Two differently named annotated methods on one bean register successfully.
5. Two annotated overloads with the same method name fail startup with the required ambiguity message.
6. Duplicate target IDs fail startup clearly.

### Public registry

1. Public registry enumeration contains pure YAML and mapped YAML capabilities.
2. Public registry enumeration contains no raw Java implementation targets.
3. Duplicate YAML names still fail startup.
4. A YAML name equal to its backing Java method name registers without collision.

### Mapping

1. A mapped YAML skill resolves an existing target ID.
2. An unknown target ID fails startup.
3. A mapped skill without `input_schema` inherits the Java-derived contract.
4. Multiple YAML skills can map to the same Java target and retain independent public metadata and RBAC while sharing its reflected input contract.
5. Mapped exceptions retain current AI-readable transformation behavior.
6. Runtime `ref://` binding remains functional for mapped Java arguments.

Rejection of mapped `input_schema` belongs to the subsequent manifest-simplification ticket and is not an independent acceptance condition for this identity refactor.

### Invocation and nesting

1. `SkillTemplate.invoke("expenseLookup", ...)` executes the mapped YAML skill.
2. `SkillTemplate.invoke("getLatestExpenses", ...)` fails as an unknown YAML skill.
3. A planner can call `expenseLookup` through `allowed_skills`.
4. A raw Java target ID or former annotation name cannot be placed in `allowed_skills` as a public child.
5. Plans, tool events, evidence events, and public journal entries use `expenseLookup`.

### Samples and documentation

1. `/expenses` delegates to `SkillTemplate` using `expenseLookup`.
2. Sample controller tests verify the YAML public name.
3. Sample context tests assert YAML skills are public and Java targets are internal.
4. Starter and sample READMEs no longer claim raw Java invocation support.
5. AI authoring guide links resolve and describe final source-verified behavior.

### Tooling readiness

1. Catalog enumeration reports public YAML names only.
2. Pure YAML and mapped YAML implementation types are distinguishable.
3. Mapped descriptors expose their target IDs for trusted development tooling without creating public aliases.
4. Public catalog metadata remains immutable or defensively copied.

## Acceptance Criteria

- [ ] `SkillMethod.name` no longer exists.
- [ ] Java implementation targets are stored outside the public `CapabilityRegistry`.
- [ ] Java targets are keyed by `beanName#methodName`.
- [ ] Annotated overloads with the same bean and method name fail startup with a clear, actionable error.
- [ ] YAML `name` is the only public Bifrost skill name.
- [ ] A YAML name may equal its Java method name without collision.
- [ ] One Java target may back multiple independently governed YAML skills.
- [ ] `SkillTemplate` remains YAML-only and invokes mapped YAML skills normally.
- [ ] Raw Java target names and IDs are not invocable through public skill APIs.
- [ ] `allowed_skills`, plans, evidence contracts, and public traces use YAML names.
- [ ] Mapped input-contract inheritance remains intact, and the coordinated ticket owns removal of duplicate mapped-schema compatibility.
- [ ] Mapped exception transformation and ref binding remain intact.
- [ ] `/expenses` invokes `expenseLookup`.
- [ ] Public catalog enumeration contains YAML skills only.
- [ ] Tooling can distinguish LLM-backed and mapped-Java YAML skills without inspecting invokers.
- [ ] No HTTP or Actuator catalog endpoint is introduced.
- [ ] Starter and sample test suites pass.
- [ ] AI skill-authoring documentation reflects the implemented identity model.

## Risks and Mitigations

### Initialization ordering

Mapped YAML registration depends on Java targets being fully discovered first.

**Mitigation:** Preserve or explicitly establish lifecycle ordering and add an application-context test that exercises discovery plus mapping.

### Accidental policy bypass

Moving Java targets could tempt internal code to execute them without checking the YAML wrapper's RBAC and contracts.

**Mitigation:** Public execution resolves the YAML capability first. Target lookup occurs only after wrapper validation and access checks.

### Trace identity ambiguity

Internal diagnostics may need target IDs while public traces should tell the YAML skill story.

**Mitigation:** Treat YAML name as the capability identity and target ID as optional implementation metadata. Do not substitute the target ID for public route/tool names.

### Over-expanding SkillBuilder scope

Catalog cleanup may invite premature endpoint or descriptor API design.

**Mitigation:** Preserve enumerable immutable metadata and explicit implementation type, but defer transport and tooling APIs until SkillBuilder supplies concrete requirements.

### Hidden dependencies on raw Java registry entries

Tests or internal services may directly resolve Java capabilities by former annotation name.

**Mitigation:** Inventory all registry call sites during Phase 1. Replace implementation needs with target-registry lookup and public needs with YAML skill lookup; do not add fallback aliases.

## Source Anchors

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/annotation/SkillMethod.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityRegistry.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistry.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/DefaultSkillTemplate.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skillapi/SkillTemplateTest.java`
- `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/SampleController.java`
- `bifrost-sample/src/main/resources/skills/basics/expense_lookup.yml`
