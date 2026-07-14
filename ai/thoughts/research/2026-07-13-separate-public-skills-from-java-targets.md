---
date: 2026-07-13T11:35:48-07:00
researcher: Codex
model: GPT-5
git_commit: abc98856810eb3514e4f2a656e54dbbd4ffc3932
branch: main
repository: bifrost
topic: "Current codebase architecture for separating public YAML skills from Java implementation targets"
tags: [research, codebase, skills, yaml, java-targets, capability-registry]
status: complete
last_updated: 2026-07-13
last_updated_by: Codex
---

# Research: Separate Public YAML Skills from Java Implementation Targets

**Date**: 2026-07-13T11:35:48-07:00  
**Researcher**: Codex  
**Model**: GPT-5  
**Git Commit**: `abc98856810eb3514e4f2a656e54dbbd4ffc3932`  
**Branch**: `main`  
**Repository**: `bifrost`

## Research Question

Research the current Bifrost codebase in support of `ai/thoughts/tickets/eng-separate-public-skills-from-java-targets.md`, documenting the existing identity model, registry behavior, mapping flow, public invocation paths, tests, samples, documentation, lifecycle, and affected code surfaces.

## Summary

The ticket describes the live code at commit `abc9885`; the public/internal registry separation has not yet been implemented. Spring discovers `@SkillMethod` methods and registers each one as `CapabilityKind.JAVA_METHOD` in the single `CapabilityRegistry`. Its registry key is `@SkillMethod.name` when set, otherwise the Java method name, while its metadata ID is always `beanName#methodName`. Later in startup, `YamlSkillCapabilityRegistrar` registers both LLM-backed and mapped manifests as `CapabilityKind.YAML_SKILL` entries in that same registry under the YAML manifest name.

Mapped YAML registration searches the shared registry by metadata ID, copies the Java target invoker, inherits or validates the Java-derived input contract, and publishes YAML-facing name, description, RBAC, execution metadata, tool descriptor, and `mappedTargetId`. Registry keys are globally unique, so a Java registry name and YAML manifest name cannot be equal even when the YAML maps to that Java method.

The supported entry and child surfaces are already YAML-oriented. `DefaultSkillTemplate` rejects any resolved capability whose kind is not `YAML_SKILL`. `DefaultSkillVisibilityResolver` first resolves every `allowed_skills` entry through `YamlSkillCatalog`, which excludes raw Java-only entries from the visible tool set. Planning, tool callbacks, evidence credit, usage, and trace events consume the selected capability's `name`; for a mapped wrapper, that is the YAML manifest name.

The sample demonstrates both sides of the current mismatch. `expense_lookup.yml` publishes `expenseLookup` and maps it to `expenseService#getLatestExpenses`, but `SampleController.getExpenses()` calls `SkillTemplate.invoke("getLatestExpenses", ...)`. The raw entry is a `JAVA_METHOD`, so the template rejects it. The sample README explicitly claims this direct call works, while the root README and AI authoring mental model correctly state that `SkillTemplate` is YAML-only.

No dedicated Java implementation-target descriptor or registry exists. There is also no startup validation dedicated to annotated overload ambiguity or duplicate `beanName#methodName` IDs. Current collision enforcement is only the shared registry's name-keyed `putIfAbsent`, and current mapped lookup scans all registered capabilities for a matching metadata ID.

## Detailed Findings

### 1. Java annotation and discovery identity

`SkillMethod` currently declares three attributes: optional `name`, required `description`, and legacy `modelPreference` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/annotation/SkillMethod.java:12-19`).

`SkillMethodBeanPostProcessor` is a Spring `BeanPostProcessor`. After each bean initializes, it visits every annotated method and calls its registration path (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:82-86`). Registration derives two identities:

- registry/capability name: `annotation.name()` when nonblank, otherwise `method.getName()` (`SkillMethodBeanPostProcessor.java:91-93`);
- metadata ID: `beanName + "#" + method.getName()` (`SkillMethodBeanPostProcessor.java:104-106`).

The reflected Spring AI method-input schema becomes both the Java tool descriptor schema and the source for a `SkillInputContract` (`SkillMethodBeanPostProcessor.java:94-115`). The invoker binds Java arguments, resolves ref-capable values during binding, invokes the reflected method, serializes the result, and transforms runtime failures through `BifrostExceptionTransformer` (`SkillMethodBeanPostProcessor.java:102`, `SkillMethodBeanPostProcessor.java:225-252`). The completed metadata is registered in the shared registry under the derived capability name with kind `JAVA_METHOD` (`SkillMethodBeanPostProcessor.java:104-117`).

There is no separate implementation-target type in the current source tree. Java implementation data is represented by the same `CapabilityMetadata` record used for YAML capabilities. That record contains public-facing name and description, invoker, kind, tool schema, input contract, and optional mapped target ID (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:9-22`).

### 2. Shared registry semantics and collision boundary

`CapabilityRegistry` exposes `register(name, metadata)`, name lookup, and full enumeration (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityRegistry.java:5-12`). The default `InMemoryCapabilityRegistry` stores all entries in one `ConcurrentMap<String, CapabilityMetadata>` (`InMemoryCapabilityRegistry.java:8-10`).

Registration requires the map key to equal `metadata.name()` and uses `putIfAbsent`. Any existing entry with that name produces `CapabilityCollisionException`, without distinguishing Java from YAML kinds (`InMemoryCapabilityRegistry.java:13-27`). `getAllCapabilities()` returns a copied list containing both kinds (`InMemoryCapabilityRegistry.java:40-43`).

Consequences visible from the current mechanics:

- Java annotation names and YAML manifest names share one global name namespace.
- A YAML public name equal to its backing Java registry name reaches the existing name collision check.
- Registry enumeration is a mixed projection of raw Java methods and YAML skills.
- Metadata IDs are not registry keys. No registry operation enforces ID uniqueness.
- Two annotated overloads have the same constructed `beanName#methodName` ID. Current discovery contains no overload-specific validation; each method is registered according to its derived annotation/method capability name.

### 3. YAML catalog and capability registration

`YamlSkillCatalog` is the YAML discovery boundary. It loads resources during `afterPropertiesSet()`, keys definitions by manifest `name`, and rejects duplicate YAML names inside the catalog (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:71-87`). It exposes a copied definition list and name lookup (`YamlSkillCatalog.java:90-103`).

`YamlSkillDefinition` retains the resource, complete manifest, resolved execution configuration, and evidence contract. Its projections expose `allowedSkills`, RBAC roles, prompt, input/output contracts, planning settings, and `mapping.target_id` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:9-95`). This is the current immutable/typed source for public authoring metadata.

`YamlSkillCapabilityRegistrar` is a `SmartInitializingSingleton`, so it registers YAML capabilities after singleton creation, after Java bean post-processing has discovered ordinary application beans (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:19-42`). For every catalog definition, it builds `CapabilityMetadata` with:

- a resource-derived internal ID;
- the YAML `name` and `description`;
- resolved YAML execution settings;
- YAML RBAC roles;
- kind `YAML_SKILL`;
- the effective tool/input contract;
- the manifest's `mapping.target_id` (`YamlSkillCapabilityRegistrar.java:44-60`).

Both pure and mapped YAML entries are registered in the same `CapabilityRegistry` under their YAML name (`YamlSkillCapabilityRegistrar.java:60`). Pure YAML entries receive a placeholder invoker because the execution router sends them to the model-backed coordinator instead (`YamlSkillCapabilityRegistrar.java:79-83`; `CapabilityExecutionRouter.java:69-85`).

### 4. Mapped Java target resolution and inherited behavior

For a mapped definition, the registrar resolves `mapping.target_id` by scanning `capabilityRegistry.getAllCapabilities()` and comparing the requested ID to `CapabilityMetadata.id()` (`YamlSkillCapabilityRegistrar.java:124-137`). `resolveInvoker` repeats the same lookup and returns the Java target's invoker (`YamlSkillCapabilityRegistrar.java:64-76`). Unknown targets fail startup with the YAML resource description, field name, and unknown ID (`YamlSkillCapabilityRegistrar.java:73-74`, `YamlSkillCapabilityRegistrar.java:136-137`).

When the YAML omits `input_schema`, the mapped wrapper receives the target's reflected tool schema and an inherited YAML input contract (`YamlSkillCapabilityRegistrar.java:86-121`). When YAML declares an input schema, the registrar validates structural compatibility with the Java target before publishing it (`YamlSkillCapabilityRegistrar.java:103-119`). This is coordinated with, but currently separate from, the mapped-manifest simplification ticket.

The mapped wrapper stores the Java `target_id` but executes with its own YAML `CapabilityMetadata`. `CapabilityExecutionRouter` first enforces access against that wrapper and validates its effective contract. It sends only unmapped YAML capabilities into nested model execution; mapped YAML and Java kinds invoke their stored invoker after runtime `ref://` argument resolution (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:50-85`). Thus current mapped behavior preserves wrapper RBAC, contract validation, ref resolution, deterministic invocation, and the Java invoker's exception transformation.

The current registrar does not consume or remove the Java registry entry after constructing a wrapper. Multiple differently named YAML definitions can resolve the same Java metadata ID because resolution is read-only and each YAML wrapper is registered separately.

### 5. Public invocation and YAML-only child visibility

`DefaultSkillTemplate.invoke` looks up the requested string in the shared registry, validates the capability input, creates a new session, and executes the resolved metadata (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/DefaultSkillTemplate.java:67-94`). Its lookup explicitly reports an unknown YAML skill when no name exists and rejects a found non-YAML kind with `SkillTemplate only supports YAML skills` (`DefaultSkillTemplate.java:97-109`). A Java annotation name therefore exists in the registry but is not a supported template entry.

`DefaultSkillVisibilityResolver` begins from the current YAML catalog definition. For each `allowed_skills` name, it first requires a matching `YamlSkillDefinition`, then looks up same-named capability metadata and applies `AccessGuard` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:27-58`). A registry-only Java name does not pass the catalog lookup and is not exposed to the parent model.

The integration and visibility tests encode this boundary. `SkillVisibilityResolverTest` registers a raw `internal.only.target` but asserts that only the allowed YAML wrapper is visible (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java:30-71`). `ExecutionCoordinatorIntegrationTest` uses `allowed.visible.skill` in the plan and journal while its implementation target remains `targetBean#deterministicTarget` (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java:93-101`, `ExecutionCoordinatorIntegrationTest.java:301-310`).

### 6. Plans, evidence, usage, and traces use capability names

After visibility resolves a YAML child, downstream runtime surfaces operate on the child `CapabilityMetadata.name()`:

- tool usage records receive the selected capability name (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:101-105`);
- linked tool-call events and plan completion use the capability name (`DefaultToolCallbackFactory.java:133-147`);
- evidence producers are credited by the capability name (`DefaultToolCallbackFactory.java:143-147`);
- success/failure metrics, tool results, tool failures, and error payloads use the same name (`DefaultToolCallbackFactory.java:150-188`).

For a mapped YAML wrapper, `CapabilityMetadata.name()` is the manifest name created by the YAML registrar. The Java target ID remains separately available in `mappedTargetId`, and its annotation-derived registry name is not substituted into these runtime paths.

### 7. Auto-configuration and initialization order

`BifrostAutoConfiguration` currently supplies one `InMemoryCapabilityRegistry` bean (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:88-94`). The static bean post-processor is built with that registry and performs Java discovery during bean initialization (`BifrostAutoConfiguration.java:104-118`). The YAML catalog is a separate bean (`BifrostAutoConfiguration.java:133-141`), but the YAML registrar receives the same shared registry (`BifrostAutoConfiguration.java:143-150`).

The working lifecycle is:

```text
Spring bean initialization
  -> SkillMethodBeanPostProcessor
  -> JAVA_METHOD metadata in shared CapabilityRegistry

YamlSkillCatalog initialization
  -> YAML resources loaded and keyed by manifest name

afterSingletonsInstantiated
  -> YamlSkillCapabilityRegistrar
  -> mapped target scan by metadata ID
  -> YAML_SKILL metadata in shared CapabilityRegistry
```

This ordering is exercised by application-context registrar tests and the sample context test.

### 8. Sample inconsistency

`ExpenseService.getLatestExpenses()` is annotated with the explicit Java capability name `getLatestExpenses` (`bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/ExpenseService.java:9-17`). The YAML manifest publishes `expenseLookup` and maps it to `expenseService#getLatestExpenses` (`bifrost-sample/src/main/resources/skills/basics/expense_lookup.yml:1-6`).

`SampleController.getExpenses()` invokes `getLatestExpenses` through `SkillTemplate` (`bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/SampleController.java:34-36`). Because that lookup resolves the Java registry entry, it reaches the template's non-YAML rejection. `SampleApplicationTests` currently asserts that the raw Java name is present in the shared registry, not that the mapped YAML entry is the public entry (`bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleApplicationTests.java:42-46`). `SampleControllerTest` has no `/expenses` delegation case; its three cases cover the feedstock and duplicate-invoice paths (`bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleControllerTest.java:23-90`).

The sample README lists `/expenses` as a direct Java capability invocation and states that it works as a top-level capability (`bifrost-sample/README.md:201-209`). This differs from the tested `SkillTemplate` behavior.

### 9. Documentation and fixture inventory

The root README has both aligned and legacy wording:

- it says `SkillTemplate` invokes YAML skills and Java behavior needs a mapped YAML manifest (`README.md:94-118`, `README.md:195-210`);
- it says `allowed_skills` may contain a registered YAML skill or Java capability name (`README.md:170-172`), which does not match the catalog-first visibility resolver;
- its Java example still uses `@SkillMethod(name = ...)` (`README.md:195-210`).

The AI authoring mental model already documents YAML-only entry invocation, mapped wrappers, and catalog-first child visibility (`ai/skill-authoring/mental-model.md:33-42`, `ai/skill-authoring/mental-model.md:61-72`, `ai/skill-authoring/mental-model.md:95-100`). Its Java section still describes `@SkillMethod` as registering a Java capability because that is the executable implementation today.

Explicit `@SkillMethod(name = ...)` usage appears in production sample services and across annotation, auto-configuration, processor, registrar, catalog, coordinator integration, and ref-binding tests. The main affected test files are:

- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/annotation/SkillMethodTest.java`;
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`;
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`;
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java`;
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`;
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java`;
- `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleApplicationTests.java`.

Current tests cover Java discovery/invocation, mapped target resolution, inherited contracts, exception transformation, ref binding, YAML-only template rejection, and YAML-only child visibility. No current focused test covers annotated overload ambiguity, duplicate target IDs independent of capability-name collision, one target backing multiple YAML wrappers, or same-named Java/YAML entries.

## Code References

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/annotation/SkillMethod.java:12-19` - Current annotation attributes, including `name`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:82-117` - Java discovery, dual identity derivation, metadata creation, and shared registration.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistry.java:8-43` - Single name-keyed map, collision behavior, and mixed enumeration.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:9-35` - Shared Java/YAML metadata record and kind default.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:71-103` - YAML loading, duplicate-name validation, and catalog lookup.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:39-138` - YAML registration, target scanning, invoker delegation, and contract inheritance.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/DefaultSkillTemplate.java:67-109` - YAML-only public entry enforcement.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java:27-58` - Catalog-first `allowed_skills` resolution and RBAC filtering.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:50-85` - Wrapper access/input enforcement and mapped/direct invocation split.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:101-188` - YAML capability name propagation into plans, evidence, metrics, and traces.
- `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/SampleController.java:34-36` - Raw Java name passed to `SkillTemplate`.
- `bifrost-sample/src/main/resources/skills/basics/expense_lookup.yml:1-6` - YAML public name and explicit Java target ID.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java:41-131` - Existing mapped registration, inherited contract, exception, and unknown-target coverage.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skillapi/SkillTemplateTest.java:42-68` - Existing YAML-only entry behavior.

## Architecture Documentation

The current system has three logical projections but only two physical storage boundaries:

1. `YamlSkillCatalog` is a YAML-only, name-keyed authoring catalog.
2. `CapabilityRegistry` is a mixed, name-keyed runtime registry containing Java and YAML metadata.
3. `CapabilityMetadata.kind` and `mappedTargetId` distinguish execution cases inside the mixed registry.

Java mapping is explicit at the manifest boundary (`mapping.target_id`) but indirect at runtime: the registrar scans mixed metadata by `id`, then embeds the target invoker into new YAML metadata. Public root and child APIs independently impose YAML-only rules even though their backing registry remains mixed. This produces a YAML-facing execution tree on top of a registry that still exposes Java names and metadata through lookup/enumeration.

The ticket's desired `ImplementationTargetRegistry` and immutable implementation-target descriptor do not exist in the checkout. The closest existing descriptor is Java-flavored `CapabilityMetadata`; the closest existing target lookup is the registrar's repeated `getAllCapabilities().stream().filter(candidate -> targetId.equals(candidate.id()))` scan.

## Historical Context (from ai/thoughts/)

- `ai/thoughts/phases/phase2.md` records the original Phase 2 architecture in which `CapabilityRegistry` is populated by `@SkillMethod` scanning and those entries serve as YAML mapping targets.
- `ai/thoughts/phases/phase3.md` adds YAML definitions to the same `CapabilityRegistry` while preserving YAML-facing names and descriptions for mapped skills.
- `ai/skill-authoring/mental-model.md` documents the newer author-facing model: entry and nested skills use YAML names, while Java supplies deterministic implementation behavior behind mapped YAML leaves.
- `ai/thoughts/tickets/eng-separate-public-skills-from-java-targets.md` locks the future identity boundary: YAML name as public identity, `beanName#methodName` as internal target ID, separate registries, removal of `SkillMethod.name`, and continued YAML-only template/visibility semantics.
- `ai/thoughts/tickets/eng-simplify-mapped-yaml-skill-manifests.md` explicitly depends on this registry separation and owns the subsequent removal of mapped-only model/thinking configuration and duplicate mapped `input_schema` declarations. The current registrar still supports inherited contracts and structurally compatible declared mapped schemas.

Git history shows the ticket and current source state at `abc9885` (`Get ready for new tickets and samples`). Earlier implementation anchors in the file history include `f20eec2` (Phase 2 ticket 007), `073072d` (skill input contracts), and `2bed345` (code reformat).

## Related Research

There were no existing documents in `ai/thoughts/research/` at the time of this research. Related design and ticket material:

- [`eng-separate-public-skills-from-java-targets.md`](../tickets/eng-separate-public-skills-from-java-targets.md)
- [`eng-simplify-mapped-yaml-skill-manifests.md`](../tickets/eng-simplify-mapped-yaml-skill-manifests.md)
- [`mental-model.md`](../../skill-authoring/mental-model.md)
- [`phase2.md`](../phases/phase2.md)
- [`phase3.md`](../phases/phase3.md)

## Verification

Fresh targeted tests were run against the researched checkout:

- `mvn -pl bifrost-spring-boot-starter '-Dtest=SkillMethodBeanPostProcessorTest,YamlSkillCapabilityRegistrarTests,SkillTemplateTest' test` - 25 tests passed.
- `mvn -pl bifrost-sample -am '-Dtest=SampleApplicationTests,SampleControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` - 7 tests passed.

These tests verify the current implemented behavior; they do not exercise the ticket's not-yet-implemented separate registry or new overload/target-identity acceptance cases.

## Open Questions

No open questions remain about the current shared-registry flow covered by this research. The ticket intentionally leaves exact future runtime type names adjustable. The current checkout contains no implementation evidence resolving those future naming choices, nor an existing explicit enum that distinguishes `LLM_BACKED` from `MAPPED_JAVA` at the public catalog level; today that distinction is inferred from `mappingTargetId` on a `YAML_SKILL` capability.
