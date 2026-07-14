# Separate Public YAML Skills from Java Implementation Targets Implementation Plan

## Overview

Refactor Bifrost so YAML manifest names are the only public skill identities and Java `@SkillMethod` methods are internal deterministic implementation targets addressed only by `beanName#methodName`. The work introduces a dedicated implementation-target descriptor and registry, moves Java discovery out of `CapabilityRegistry`, resolves YAML mappings through the new registry, preserves all mapped execution behavior, and corrects samples and authoring guidance that currently expose raw Java names.

This is framework prerequisite 1 of 3. It deliberately preserves the current mapped-manifest rules so `eng-simplify-mapped-yaml-skill-manifests.md` can remove mapped `model`, `thinking_level`, and duplicate `input_schema` declarations as a separate reviewable change; portable YAML public-name validation follows in `eng-validate-public-yaml-skill-names.md` after shared fixtures have settled.

## Current State Analysis

`SkillMethodBeanPostProcessor` currently creates a public-shaped `CapabilityMetadata` for every annotated Java method. It derives a registry name from `@SkillMethod.name` or the Java method name, derives a separate ID from `beanName#methodName`, and registers the result as `JAVA_METHOD` in the shared name-keyed `CapabilityRegistry` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:39-117`).

`YamlSkillCapabilityRegistrar` later registers YAML definitions into the same registry. Mapped definitions locate Java targets by scanning all capabilities for a matching metadata ID, then copy the Java invoker and reflected contract into YAML-facing metadata (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:39-138`). This produces a mixed registry, makes same-named Java and YAML entries collide, and provides no target-ID uniqueness or annotated-overload validation.

The supported public paths are already YAML-oriented. `DefaultSkillTemplate` accepts only `YAML_SKILL` metadata (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/DefaultSkillTemplate.java:67-109`), and `DefaultSkillVisibilityResolver` resolves every `allowed_skills` entry through `YamlSkillCatalog` before consulting the registry (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:27-58`). Tool usage, plans, evidence, and trace events use the selected YAML capability's `name` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:101-188`).

The sample contradicts that boundary: `expense_lookup.yml` publishes `expenseLookup` mapped to `expenseService#getLatestExpenses`, while `/expenses` invokes `getLatestExpenses` through `SkillTemplate` (`bifrost-sample/src/main/resources/skills/basics/expense_lookup.yml:1-6`; `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/SampleController.java:34-36`).

## Desired End State

After this plan is implemented:

- `CapabilityRegistry` contains only public YAML capabilities, keyed by YAML manifest `name`.
- The `CapabilityRegistry` contract is YAML-only, and the default `InMemoryCapabilityRegistry` rejects any attempted non-`YAML_SKILL` registration, making the public boundary an enforced invariant.
- `SkillImplementationTargetRegistry` contains only internal Java targets, keyed by the readable `beanName#methodName` ID.
- `@SkillMethod` has no `name` attribute and cannot create a public alias.
- `YamlSkillCapabilityRegistrar` performs one direct target-registry lookup for each mapped definition and builds a YAML-facing wrapper that retains its own name, description, RBAC, contracts, and mapping target ID.
- A Java method name may equal its YAML public name without collision, and multiple independently governed YAML skills may share one Java target.
- Annotated overloads on one bean fail startup before partial registration, while duplicate target IDs fail in the implementation-target registry with actionable errors.
- Spring proxies and compiler bridge/synthetic methods resolve to canonical user methods so one annotated method registers once, while genuine overloads remain prohibited.
- Target invokers resolve the final Spring bean/proxy by name at execution time, preserving AOP advice without depending on bean-post-processor order.
- `SkillTemplate`, `allowed_skills`, plans, evidence, usage, and public traces continue to use YAML names only; raw Java method names and target IDs are not public invocation aliases.
- Public catalog consumers can explicitly distinguish `LLM_BACKED` from `MAPPED_JAVA` without examining invoker implementations.
- Existing mapped input inheritance, explicit mapped-schema compatibility, exception transformation, runtime `ref://` binding, and execution-time RBAC remain intact until the coordinated follow-up ticket changes only the mapped-manifest rules.

The end state is verified by focused registry, processor, registrar, invocation, visibility, integration, sample, and documentation tests plus the full Maven reactor test suite.

### Key Discoveries

- `InMemoryCapabilityRegistry` is a single concurrent name map whose `putIfAbsent` collision check cannot distinguish public YAML names from Java implementation names (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistry.java:8-43`).
- `CapabilityMetadata` currently carries both public metadata and Java execution details, while no dedicated implementation-target type exists (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:9-47`).
- `SkillInputContractResolver.resolveYamlCapability` currently accepts Java-flavored `CapabilityMetadata`; it must consume the new target descriptor without changing explicit-schema compatibility or inherited-contract semantics (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputContractResolver.java:34-63`).
- `CapabilityToolDescriptor` requires a name and is consumed by provider-facing tool callback construction (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolDescriptor.java:9-35`; `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:81-89`). Because `beanName#methodName` is internal syntax and not a portable provider tool name, internal targets should store reflected schema text directly.
- `SkillMethodBeanPostProcessor` currently closes each invoker over the bean instance received during post-processing (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:102-117`). Since AOP proxying is also bean-post-processing, correctness must not depend on which processor observes the raw or final instance.
- `CapabilityMetadata` currently converts a null kind to `JAVA_METHOD` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:23-32`), which conflicts with an explicitly YAML-only public metadata/registry model.
- Java discovery runs during bean post-processing and YAML registration runs as a `SmartInitializingSingleton`, which already gives mappings the required target-before-wrapper lifecycle (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:88-150`).
- Public runtime attribution already uses YAML wrapper names; the refactor should protect this behavior with integration assertions rather than reroute those paths (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:101-188`).
- The checkout is at the same `abc9885` commit researched in `ai/thoughts/research/2026-07-13-separate-public-skills-from-java-targets.md`; the only existing untracked work is the supplied research directory and must be preserved.

## What We're NOT Doing

- Do not implement the follow-up mapped-manifest simplification: mapped YAML still requires its current model configuration and may still declare only a structurally compatible `input_schema` in this change.
- Do not validate or migrate provider-portable YAML public names in this change; that work is isolated in `eng-validate-public-yaml-skill-names.md`.
- Do not add Java aliases, fallback lookup, auto-generated YAML wrappers, raw-Java `SkillTemplate` invocation, or raw Java entries in `allowed_skills`.
- Do not change the readable target-ID format or support annotated overloads through signature-based IDs.
- Do not redesign prompts, RBAC, input/output schemas, evidence contracts, planning, execution traces, or exception/ref behavior beyond adapting them to the separate target descriptor.
- Do not remove `SkillMethod.description` or legacy `modelPreference`; retain them as internal target metadata for now.
- Do not add an Actuator/HTTP catalog endpoint, SkillBuilder application, generated clients, or mutable catalog projection.
- Do not rewrite historical phase documents; update current framework/sample documentation and the active AI authoring knowledge base.
- Do not implement source changes as part of this planning task.

## Implementation Approach

Use two explicit storage boundaries and keep wrapper construction as the only bridge between them:

```text
Spring @SkillMethod discovery
  -> SkillImplementationTarget(id = beanName#methodName, invoker, reflected contract)
  -> SkillImplementationTargetRegistry

YAML discovery
  -> YamlSkillDefinition(name = public YAML name, mapping.target_id = optional internal ID)
  -> YamlSkillCapabilityRegistrar
       -> direct target-registry lookup when mapped
       -> CapabilityMetadata(name = public YAML name)
  -> CapabilityRegistry
```

Choose the concrete names `SkillImplementationTarget`, `SkillImplementationTargetRegistry`, `InMemorySkillImplementationTargetRegistry`, and `SkillImplementationTargetCollisionException` in the existing `core` package. The immutable target record will contain `id`, `description`, legacy `modelPreference`, `CapabilityInvoker`, reflected `inputSchema`, and `SkillInputContract`; it will not contain a second public skill name or any `CapabilityToolDescriptor`. YAML registration is the only place that constructs a provider-facing tool descriptor, using the YAML name/description plus the target's reflected schema.

Retain `CapabilityKind` as an explicit public-metadata marker with only `YAML_SKILL`; remove the obsolete `JAVA_METHOD` value because Java targets now have a distinct descriptor and no pre-release compatibility contract requires the legacy classification. Add a small explicit `PublicSkillImplementationType` enum (`LLM_BACKED`, `MAPPED_JAVA`) in `core` and an `implementationType()` projection on YAML definition/public metadata derived from mapping presence. This satisfies catalog-tooling introspection without forcing the next ticket's execution-configuration representation into this refactor.

Validate overload ambiguity before registering any method from a bean. Resolve the user class with `AopUtils.getTargetClass(bean)`, discover merged `@SkillMethod` metadata across the user class and implemented interfaces, canonicalize methods with `BridgeMethodResolver.findBridgedMethod`, discard synthetic/bridge duplicates, and group the distinct canonical annotated methods by Java method name. Reject any genuine same-name group larger than one with the bean name, method name, ambiguous target ID, and rename requirement. Retain the canonical method after validation and defer `AopUtils.selectInvocableMethod` until invocation against the final bean/proxy. The registry independently rejects any duplicate target ID so repeated bean-name/target registration cannot silently replace an invoker.

Do not close target invokers over the post-processor callback instance. Make the processor `BeanFactoryAware`, capture the bean name and canonical method, resolve `beanFactory.getBean(beanName)` when the target is invoked, and then use `AopUtils.selectInvocableMethod` against that runtime bean class. This guarantees the invocation traverses the final proxy and its advice regardless of bean-post-processor order.

Make the public boundary executable rather than conventional: `InMemoryCapabilityRegistry.register` must reject metadata whose kind is not `YAML_SKILL` before inserting it. Retain the `DefaultSkillTemplate` kind check as defense for custom registry implementations, but production code and the default registry must make raw Java metadata impossible to publish.

## Phase 1: Introduce Internal Java Target Identity and Discovery

### Overview

Create the immutable implementation-target model and registry, remove the annotation-level public name, and redirect Java discovery and auto-configuration to the internal registry while preserving reflected schemas, invocation, exception transformation, and ref-aware binding.

### Changes Required

#### 1. Add the target descriptor and registry boundary

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillImplementationTarget.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillImplementationTargetRegistry.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/InMemorySkillImplementationTargetRegistry.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillImplementationTargetCollisionException.java`

**Changes**:

- Add an immutable record with validated nonblank `id`/`description`/`inputSchema`, non-null invoker/input contract, and retained legacy model preference.
- Store reflected input schema text directly. Do not construct or retain `CapabilityToolDescriptor` or Spring AI `ToolDefinition` solely to give an internal target a name.
- Expose direct `register(target)`, `getTarget(targetId)`, and defensively copied `getAllTargets()` operations.
- Key the concurrent implementation by exact `beanName#methodName`; return `null` for blank/missing lookups, matching current registry lookup conventions.
- Reject duplicate IDs with the required message that every annotated target must have a unique `beanName#methodName` ID.
- Add focused tests for lookup, enumeration immutability, duplicate rejection, and concurrent registration/read behavior in `InMemorySkillImplementationTargetRegistryTest`.

#### 2. Remove the annotation public name and register internal targets

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/annotation/SkillMethod.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/annotation/SkillMethodTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`

**Changes**:

- Delete `SkillMethod.name` outright and update annotation tests to cover only `description` and `modelPreference`.
- Replace the processor's `CapabilityRegistry` dependency with `SkillImplementationTargetRegistry` and derive exactly one identity: `beanName#methodName`.
- Build reflected schemas and input contracts exactly as today and store the schema text directly on `SkillImplementationTarget`; use the target ID only for internal registration and diagnostics, never as a tool name.
- Preserve argument binding, optional parameters, nested resource materialization, stream cleanup, result serialization, and `BifrostExceptionTransformer` behavior.
- Resolve the underlying target class with `AopUtils.getTargetClass`, discover merged annotations on target and interface declarations, collapse bridge methods with `BridgeMethodResolver.findBridgedMethod`, ignore synthetic/bridge duplicates, and deduplicate canonical methods before validation.
- Collect the canonical annotated methods for a bean before registration, reject genuine same-name annotated overloads before any target from that bean is added, and include bean, method, ambiguous ID, and the unique-method-name remedy in the error.
- Retain the canonical user method after validation and defer runtime invocable-method selection until the final bean/proxy has been resolved.
- Implement `BeanFactoryAware`; target invokers capture bean name/canonical method, resolve the current bean from the factory at invocation time, and select the runtime-invocable method rather than retaining the callback bean instance.
- Rewrite processor tests to resolve targets by ID and add cases for two differently named annotated methods, prohibited annotated overloads, JDK/CGLIB proxy discovery, interface-declared annotations, generic bridge methods, target descriptor contents, invocation, schemas, transformed exceptions, and ref-capable parameters.

#### 3. Auto-configure the second registry with correct lifecycle wiring

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`

**Changes**:

- Add a conditional infrastructure bean for `SkillImplementationTargetRegistry` backed by `InMemorySkillImplementationTargetRegistry`.
- Inject it into the static `SkillMethodBeanPostProcessor`; leave the public `CapabilityRegistry` bean dedicated to YAML registration.
- Assert both registries exist as distinct beans and that the shared input-contract resolver is still used by target discovery and YAML registration.
- Replace the current “registered alongside” context assertion with target-in-internal-registry and mapped-YAML-in-public-registry assertions.

### Success Criteria

#### Automated Verification

- [x] The annotation no longer exposes `name`, and all Java/test call sites compile without `@SkillMethod(name = ...)`.
- [x] Target registry, duplicate-ID, overload ambiguity, reflected contract, invocation, exception, and ref tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=SkillMethodTest,InMemorySkillImplementationTargetRegistryTest,SkillMethodBeanPostProcessorTest,BifrostAutoConfigurationTests test`.
- [x] Proxy and bridge fixtures register each canonical annotated user method exactly once, remain invocable through the actual bean/proxy, and do not suppress genuine overload failures.
- [x] An application-context fixture with real method advice proves mapped target invocation traverses the final proxy; the invoker does not retain or execute a raw pre-proxy instance.
- [x] A repository search finds no annotation-name declaration or use: `rg "SkillMethod\(name|annotation\.name\(\)|String name\(\) default"` returns no matches.

#### Manual Verification

- [x] Review startup error text for an overloaded fixture and confirm it identifies the bean, method, `beanName#methodName` ID, and rename remedy.
- [x] Inspect one registered target in a context test and confirm it has no independent public skill name.

**Implementation Note**: After this phase passes, pause for confirmation that target identity and diagnostics match the ticket before changing mapped YAML registration.

---

## Phase 2: Make YAML Registration the Only Public Capability Path

### Overview

Resolve mapped definitions through the internal target registry, keep only YAML wrappers in `CapabilityRegistry`, and expose an explicit public YAML implementation type without changing mapped execution semantics.

### Changes Required

#### 1. Adapt input-contract resolution to target descriptors

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputContractResolver.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/input/SkillInputContractResolverTest.java`

**Changes**:

- Change `resolveYamlCapability` to accept `SkillImplementationTarget` for mapped definitions rather than Java-flavored `CapabilityMetadata`.
- Preserve `YAML_INHERITED` construction, runtime-ref markers, and the existing explicit mapped-schema merge/compatibility behavior.
- Do not remove compatibility validation; that belongs to the subsequent manifest-simplification ticket.

#### 2. Enforce the YAML-only public registry invariant

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityRegistry.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistry.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistryTest.java`

**Changes**:

- Document on the interface that it is the public YAML capability registry and implementations must reject non-YAML metadata.
- Require a non-null `CapabilityMetadata.kind` and remove the implicit `JAVA_METHOD` default; update construction sites to state their public kind explicitly.
- Reject registration when `metadata.kind()` is not `CapabilityKind.YAML_SKILL`, before mutating the registry.
- Report that `CapabilityRegistry` is the public YAML registry and Java methods must be registered through `SkillImplementationTargetRegistry`.
- Convert existing registry fixtures from `JAVA_METHOD` to YAML metadata and assert `CapabilityKind` exposes only `YAML_SKILL`.
- Keep public-name matching, duplicate YAML-name collision, lookup, defensive enumeration, and concurrency behavior unchanged.

#### 3. Resolve each mapping once through the target registry

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java`
- new/updated mapped YAML fixtures under `bifrost-spring-boot-starter/src/test/resources/skills/`

**Changes**:

- Inject both `CapabilityRegistry` and `SkillImplementationTargetRegistry`.
- Remove the obsolete two-argument registrar constructor that creates a private empty target registry; require every construction path to supply the shared implementation-target registry.
- Replace both mixed-registry scans with a single `resolveMappedTarget` call per definition; reuse that result for invoker, inherited contract, tool schema, and compatibility checks.
- Build only YAML `CapabilityMetadata`, retaining the YAML name/description/RBAC/execution settings, mapped target ID, and target invoker; construct its `CapabilityToolDescriptor` from the YAML name/description and the target's direct `inputSchema` value.
- Improve unknown-target startup text to say “unknown implementation target” and include the YAML skill/resource plus `mapping.target_id` field.
- Add fixtures/tests showing a YAML name equal to its Java method name registers without collision, two YAML names can share one target while retaining distinct descriptions/RBAC/mapped IDs, and unknown mappings fail clearly.
- Assert public registry enumeration contains pure and mapped YAML entries but no target IDs or raw Java metadata.

#### 4. Add explicit YAML implementation-type introspection

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/PublicSkillImplementationType.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java`
- corresponding catalog/registrar tests

**Changes**:

- Add `LLM_BACKED` and `MAPPED_JAVA` values.
- Expose a direct projection on immutable YAML definition/public capability state based on the normalized presence of `mapping.target_id`; do not inspect invoker concrete types.
- Keep `CapabilityKind` as the explicit YAML-only public marker and keep existing execution descriptors intact so the follow-up ticket can independently represent absent mapped model configuration.
- Test both types through catalog and public registry enumeration and verify returned collections remain immutable/defensively copied.

#### 5. Simplify public invocation defenses around the YAML-only registry

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/DefaultSkillTemplate.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skillapi/SkillTemplateTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java`

**Changes**:

- Keep `SkillTemplate` YAML-only. A raw target ID or former Java name absent from the public registry must produce `Unknown YAML skill`; no lookup against the target registry and no fallback are allowed.
- Retain the non-YAML kind guard as defensive validation for a custom/misconfigured public registry. Test it with a test-only stub/custom registry because the default `InMemoryCapabilityRegistry` must now reject the invalid setup before template invocation.
- Rework visibility tests to register Java targets in the internal registry and assert only catalog-backed YAML wrappers become visible.

### Success Criteria

#### Automated Verification

- [x] Registrar, catalog projection, visibility, resolver, and template tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=YamlSkillCapabilityRegistrarTests,YamlSkillCatalogTests,SkillVisibilityResolverTest,SkillTemplateTest,SkillInputContractResolverTest test`.
- [x] A same-named method/YAML fixture starts successfully and the public registry contains exactly the YAML entry for that name.
- [x] A shared-target fixture publishes two independent YAML wrappers with the same target ID and inherited contract.
- [x] `YamlSkillCapabilityRegistrarTests#exposesOnlySharedRegistryConstructor` proves the obsolete private-registry fallback constructor is absent.
- [x] `rg "getAllCapabilities\(\)\.stream" bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java` returns no matches.
- [x] `CapabilityRegistry.getAllCapabilities()` contains no `JAVA_METHOD` entry in application-context tests.
- [x] `CapabilityKind` exposes only `YAML_SKILL`; Java targets cannot be represented as public capability metadata.
- [x] Constructing `CapabilityMetadata` with a null kind fails immediately, and all valid construction paths provide `YAML_SKILL` explicitly.

#### Manual Verification

- [x] Review public catalog metadata and confirm an author/tool can distinguish `LLM_BACKED` from `MAPPED_JAVA` without inspecting invokers.
- [x] Confirm trusted diagnostics retain `mapping.target_id` while public names remain the YAML names.

**Implementation Note**: After this phase passes, pause for confirmation that the two registries and catalog projection expose the intended public/internal boundary.

---

## Phase 3: Prove Mapped Execution, Security, and Attribution Are Preserved

### Overview

Update integration fixtures and assertions so the separated registries preserve mapped root/nested execution, wrapper governance, contracts, exception/ref behavior, and YAML-only public attribution.

### Changes Required

#### 1. Update integration fixtures to internal target lookup

**Files**:

- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/CapabilityExecutionRouterTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/tool/ToolCallbackFactoryTest.java`
- any other tests found by `rg "@SkillMethod\(name" bifrost-spring-boot-starter/src/test`

**Changes**:

- Remove annotation-name fixtures and any setup that inserts Java implementation metadata into the public registry.
- Preserve mapped wrapper invocation through `CapabilityExecutionRouter`, including access checks and input validation before deterministic invocation.
- Keep LLM-backed routing distinct using YAML implementation type/mapping state; do not add a direct target execution route to public APIs.
- Assert nested planning/tool callbacks receive the YAML wrapper metadata and public name.

#### 2. Add acceptance coverage for identity and behavior boundaries

**Files**:

- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skillapi/SkillTemplateTest.java`
- focused fixtures under `bifrost-spring-boot-starter/src/test/resources/skills/valid/` and `invalid/`

**Changes**:

- Add a mapped root invocation test proving `SkillTemplate.invoke(publicYamlName, ...)` executes the target and validates the inherited contract.
- Add negative tests proving the former annotation name and `beanName#methodName` are unknown YAML skills and invalid public children.
- Preserve transformed Java exception output and runtime `ref://` argument binding through the YAML wrapper.
- Verify different wrappers around one target enforce their own RBAC and public metadata independently.
- Assert plans, task/tool events, evidence producers, usage metrics, journals, and public trace routes use the YAML name; the internal target ID may appear only as mapping/diagnostic metadata.
- Preserve the current catalog behavior for explicit compatible mapped `input_schema`; rejection is not part of this ticket.

### Success Criteria

#### Automated Verification

- [x] Targeted end-to-end behavior passes: `./mvnw -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorIntegrationTest,CapabilityExecutionRouterTest,ToolCallbackFactoryTest,SkillTemplateTest,YamlSkillCapabilityRegistrarTests test`.
- [x] Starter module tests pass: `./mvnw -pl bifrost-spring-boot-starter test`.
- [x] No source code references the removed `JAVA_METHOD` classification: `rg "CapabilityKind\.JAVA_METHOD" bifrost-spring-boot-starter/src` returns no matches.
- [x] Public event assertions contain YAML names and never substitute target IDs.

#### Manual Verification

- [x] Inspect a mapped execution journal/trace fixture and confirm the public narrative names the YAML skill while the target ID remains internal metadata.
- [x] Review RBAC tests for two wrappers sharing a target and confirm no direct target invocation can bypass either wrapper's policy.

**Implementation Note**: After this phase passes, pause for confirmation that execution and governance behavior did not regress before updating examples and guidance.

---

## Phase 4: Correct Samples and Authoring Documentation

### Overview

Make the sample and current documentation teach the implemented identity boundary: application code and skill trees use YAML names; Java methods expose internal mapping targets only.

### Changes Required

#### 1. Update sample services, controller, and tests

**Files**:

- `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/ExpenseService.java`
- `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/FeedstockFormExtractionService.java`
- `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/SampleController.java`
- `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleApplicationTests.java`
- `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleControllerTest.java`

**Changes**:

- Remove every annotation `name` argument while leaving Java method names and YAML `mapping.target_id` values unchanged.
- Change `/expenses` to invoke `expenseLookup`.
- Add controller delegation coverage for `/expenses` and update context tests to assert `expenseLookup` is public, `expenseService#getLatestExpenses` is internal, and `getLatestExpenses` is not independently registered.
- Keep mapped sample manifests' current `model` fields until the follow-up ticket; do not fold manifest simplification into this work.

#### 2. Update framework and sample READMEs

**Files**:

- `README.md`
- `bifrost-sample/README.md`

**Changes**:

- State that YAML `name` is the only public skill name and `allowed_skills` accepts YAML names only.
- Rewrite Java examples as internal `@SkillMethod(description = ...)` targets connected by explicit `mapping.target_id`.
- Correct the `/expenses` table/narrative to show `expenseLookup`, remove the claim that direct Java capability invocation works, and explain `beanName#methodName` only in mapping-oriented sections.
- Describe public enumeration as YAML-only and avoid promising a SkillBuilder endpoint.

#### 3. Update the AI skill-authoring knowledge base

**Files**:

- `ai/skill-authoring/mental-model.md`
- `ai/skill-authoring/README.md`

**Changes**:

- Replace “Java capability registration” language with internal implementation-target language and document the separate registries, explicit target ID, overload prohibition, shared-target support, and YAML-only entry/visibility names.
- Add final source anchors for the implementation-target descriptor/registry, processor, YAML registrar, and focused tests.
- Update the coverage matrix to mark public identity and Java mapping semantics as source-verified.
- Keep the current-checkout warning about mapped `model` and explicit compatible `input_schema`; the follow-up ticket removes that warning after its own implementation.
- Keep the naming/mapping guidance in `mental-model.md`; do not add a separate topic for this focused identity correction.

### Success Criteria

#### Automated Verification

- [x] Sample tests pass: `./mvnw -pl bifrost-sample -am -Dtest=SampleApplicationTests,SampleControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`.
- [x] Full reactor tests pass: `./mvnw test`.
- [x] `rg "@SkillMethod\(name|invoke\(\"getLatestExpenses\"|registered YAML skill or Java capability name|direct capability name works" README.md bifrost-sample ai/skill-authoring` returns no matches.
- [x] All repository-relative links added to the AI authoring guide resolve.

#### Manual Verification

- [ ] Run the sample with its configured provider and call `GET /expenses`; confirm it returns the deterministic result through `expenseLookup`.
- [x] Review the framework README, sample README, and mental model as an author and confirm public YAML name versus internal target ID is locally understandable.

**Implementation Note**: After all automated checks pass, pause for final human confirmation of the sample behavior and documentation wording. Do not begin `eng-simplify-mapped-yaml-skill-manifests.md` or `eng-validate-public-yaml-skill-names.md` in the same implementation task unless separately requested.

---

## Testing Strategy

Before implementation, create a dedicated testing artifact with `ai/commands/3_testing_plan.md`. It should order the work around failing acceptance tests first and retain the commands below as exit criteria.

### Unit Tests

- Target descriptor validation, target registry lookup/enumeration, duplicate IDs, and concurrency.
- Annotation surface without `name`.
- Processor target-ID derivation, canonical proxy/interface/bridge discovery, unique method registration, genuine overload ambiguity, reflected schemas, invocation, exception transformation, and ref materialization.
- Runtime bean lookup through a real advised proxy, proving mapped invocation preserves Spring AOP behavior independently of post-processor ordering.
- Public registry rejection of non-YAML metadata in addition to YAML lookup/collision/enumeration behavior.
- Capability metadata rejection of null/implicit kinds.
- YAML implementation-type projection for both `LLM_BACKED` and `MAPPED_JAVA`.
- Input-contract resolver behavior when supplied a target descriptor.
- Public registry collision behavior remains name-based for YAML capabilities.

### Integration Tests

- Spring context creates both registries and discovers Java targets before YAML mapping registration.
- JDK/CGLIB proxy, interface-annotation, and generic bridge fixtures resolve to one canonical, invocable target without masking genuine overloads.
- Same-name Java method/YAML wrapper starts without collision.
- Multiple YAML wrappers share one target but retain independent public metadata and RBAC.
- Unknown mappings and annotated overloads fail startup with actionable errors.
- Public registry enumeration is YAML-only and target registry enumeration is internal-only.
- Mapped root and nested calls preserve inherited validation, exception transformation, ref binding, execution-time authorization, and YAML-name attribution.
- Raw method names/target IDs fail public invocation and child visibility.
- Sample `/expenses` delegates through `expenseLookup`.

### Manual Testing Steps

1. Start the sample with `./mvnw -pl bifrost-sample spring-boot:run` and call `GET /expenses`.
2. Inspect startup/debug output to confirm the mapped wrapper resolves `expenseService#getLatestExpenses` without registering that ID or method name publicly.
3. Inspect a mapped execution journal/trace and verify routes, tool events, plan entries, and evidence producers use `expenseLookup`.
4. Start an overload-invalid test application and review the complete diagnostic for authoring clarity.

## Performance Considerations

- Mapped registration changes from repeated linear scans of all capabilities to constant-time target-ID lookup.
- Both registries should retain concurrent maps and defensive copied enumeration; no new mutable catalog surface is introduced.
- Runtime mapped invocation remains a direct stored invoker call after wrapper access/input/ref processing, so no model call or additional registry lookup is added to the hot path.
- Overload grouping occurs once per processed bean at startup and should favor diagnostic correctness over micro-optimization.

## Migration Notes

This is a pre-release breaking correction with no compatibility layer:

- Remove all `name = ...` arguments from `@SkillMethod` usages.
- Ensure every Java target method name is unique among annotated methods in its Spring bean.
- Use `beanName#methodName` only in YAML `mapping.target_id` and trusted diagnostics.
- Invoke and compose only YAML manifest names through `SkillTemplate`, `allowed_skills`, plans, evidence contracts, and public traces.
- If multiple public policies/descriptions need the same deterministic behavior, create multiple YAML wrappers mapped to the same target.
- Do not remove mapped `model` or compatible mapped `input_schema` declarations until the follow-up ticket is implemented.
- Rollback is a code rollback only; there is no persistent data migration.

## Post-Review Hardening Addendum

The second validation pass identified and closed additional identity-boundary edge cases:

- [x] Normalize blank `mapping.target_id` values to unmapped/LLM-backed metadata.
- [x] Preserve interface-declared `@ToolParam` metadata while binding and invoking with canonical implementation types.
- [x] Rewrite renamed interface parameters consistently in schema properties/required entries and reject incompatible multi-interface contracts.
- [x] Select JDK-proxy interface methods through the exact canonical/bridge relationship instead of broad assignability.
- [x] Initialize only referenced lazy/prototype target beans before mapped resolution and avoid duplicate prototype registration.
- [x] Reject mismatched public YAML/provider tool names and inconsistent custom-registry identities.
- [x] Return defensive manifest copies from the public catalog.
- [x] Return defensive copies from nested linter/input/output schema accessors as well as the aggregate manifest accessor.
- [x] Keep target IDs in internal causes/logs while public mapped failures identify the YAML wrapper.
- [x] Avoid inferring implementation identity from `#` in `evidence_contract.tool_evidence` until portable public-name syntax or evidence reachability validation supplies an unambiguous boundary.

## References

- Original ticket: `ai/thoughts/tickets/eng-separate-public-skills-from-java-targets.md`
- Related research: `ai/thoughts/research/2026-07-13-separate-public-skills-from-java-targets.md`
- Follow-up ticket: `ai/thoughts/tickets/eng-simplify-mapped-yaml-skill-manifests.md`
- Subsequent public-name validation ticket: `ai/thoughts/tickets/eng-validate-public-yaml-skill-names.md`
- Current Java discovery: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:82-117`
- Current mixed registry: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistry.java:8-43`
- Current mapped registration: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:39-138`
- Current public invocation: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/DefaultSkillTemplate.java:67-109`
- Current YAML-only visibility: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:27-58`
- Current public attribution: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:101-188`
- Sample inconsistency: `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/SampleController.java:34-36`
