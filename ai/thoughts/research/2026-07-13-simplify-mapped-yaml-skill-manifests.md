---
date: 2026-07-13T18:47:03-07:00
researcher: Codex
model: GPT-5
git_commit: e8cb09e34095c5ae1a7e75cd109b2b7247e2cadc
branch: main
repository: bifrost
topic: "Simplify mapped YAML skill manifests"
tags: [research, codebase, yaml-skills, mapped-java, model-configuration, input-contracts]
status: complete
last_updated: 2026-07-13
last_updated_by: Codex
last_updated_note: "Removed the legacy ModelPreference API and recorded verification"
---

# Research: Simplify Mapped YAML Skill Manifests

**Date**: 2026-07-13T18:47:03-07:00  
**Researcher**: Codex  
**Git Commit**: e8cb09e34095c5ae1a7e75cd109b2b7247e2cadc  
**Branch**: main  
**Repository**: bifrost

## Research Question

Research the current Bifrost codebase in support of `ai/thoughts/tickets/eng-simplify-mapped-yaml-skill-manifests.md`, documenting how mapped YAML manifests are classified, how model/thinking configuration and input schemas are loaded and represented, how mapped execution bypasses the model runtime, which consumers assume execution configuration is present, and which tests, fixtures, samples, and documentation describe the current behavior.

## Summary

The public-skill/Java-target separation prerequisite is present at the researched commit. YAML skills are the only public capabilities, Java `@SkillMethod` methods are stored in a separate `SkillImplementationTargetRegistry`, and `mapping.target_id` connects the two. `YamlSkillDefinition` and `CapabilityMetadata` expose `LLM_BACKED` versus `MAPPED_JAVA` by testing whether a normalized mapped target ID is present (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:85-102`; `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:55-60`).

Catalog loading does not use that classification before model processing. `YamlSkillCatalog.loadDefinition` requires `model` on every manifest, resolves it through `BifrostModelsProperties`, resolves/defaults and validates `thinking_level`, and constructs a non-null `EffectiveSkillExecutionConfiguration` before returning the definition (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:137-179`). The catalog also returns without discovering any YAML resources when the application model catalog is empty (`YamlSkillCatalog.java:70-88`).

Mapped registration resolves the separate Java target, creates an effective input contract, and always projects the definition's execution configuration into public `CapabilityMetadata` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:49-70`). If mapped YAML omits `input_schema`, the Java target schema becomes a `YAML_INHERITED` contract. If mapped YAML declares `input_schema`, registration validates structural compatibility with the reflected Java schema, merges Java runtime-ref markers into the declared schema, and publishes a `YAML_EXPLICIT` contract (`YamlSkillCapabilityRegistrar.java:99-135`; `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputContractResolver.java:41-64`, `SkillInputContractResolver.java:131-206`, `SkillInputContractResolver.java:346-376`).

Mapped execution does not consume the stored model configuration. `CapabilityExecutionRouter` checks RBAC, validates the effective input contract, and then routes only capabilities without `mappedTargetId` into `ExecutionCoordinator`. A mapped capability instead resolves runtime refs and invokes its deterministic target-backed invoker directly (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:50-85`). Chat-client construction and mission/planning engines are therefore model-backed-only runtime paths, although their APIs currently assume a non-null execution configuration.

The checkout contains 17 YAML resources with `mapping.target_id`: 2 sample manifests and 15 starter test fixtures. All 17 declare `model`; none declares `thinking_level`; three declare `input_schema` (one valid compatibility fixture and two invalid mismatch fixtures); and the two sample manifests declare `planning_mode: false`. Current documentation explicitly calls the mapped-model requirement and compatible mapped schema a temporary current-checkout limitation (`ai/skill-authoring/mental-model.md:63-93`).

## Detailed Findings

### Manifest Shape and Catalog Loading

`YamlSkillManifest` represents `model`, `thinking_level`, `planning_mode`, `max_steps`, `input_schema`, `output_schema`, `linter`, `evidence_contract`, and `mapping` as independent fields. The mapping object is initialized even when no mapping block is declared, and its `target_id` is nullable (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:16-50`, `YamlSkillManifest.java:152-180`, `YamlSkillManifest.java:271-286`). Jackson is configured to reject unknown fields (`YamlSkillCatalog.java:191-208`, `YamlSkillCatalog.java:807-811`).

Catalog initialization has two model-wide behaviors:

1. If `bifrost.models` is empty, `afterPropertiesSet` returns before resource discovery, leaving the YAML catalog empty (`YamlSkillCatalog.java:70-88`).
2. For every discovered resource, `loadDefinition` validates `name`, `description`, and `model` before examining model catalog data. It validates prompt and schemas, resolves the model, derives a default thinking level of `medium` for thinking-capable models, validates supported thinking levels, and constructs `EffectiveSkillExecutionConfiguration` (`YamlSkillCatalog.java:137-189`).

The one existing execution-kind-specific catalog check is for `prompt`: a nonblank prompt is rejected when `mapping.target_id` is present (`YamlSkillCatalog.java:219-228`). Input-schema validation is execution-kind-neutral and validates any declared schema before mapped target resolution (`YamlSkillCatalog.java:288-296`). Model and thinking validation are also execution-kind-neutral.

`YamlSkillDefinition.mappingTargetId()` trims a nonblank target ID and normalizes blank values to null. Its `implementationType()` derives `LLM_BACKED` or `MAPPED_JAVA` from that value (`YamlSkillDefinition.java:85-102`). Input-contract helpers separately report declared, inherited, or generic shape from `input_schema` and mapping presence (`YamlSkillDefinition.java:60-78`).

### Execution-Configuration Representation and Consumers

`EffectiveSkillExecutionConfiguration` is a four-field record. `frameworkModel`, `provider`, and `providerModel` are not annotated nullable; only `thinkingLevel` is nullable (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/EffectiveSkillExecutionConfiguration.java:1-11`). `YamlSkillDefinition` stores that record directly and its compact constructor only normalizes the manifest and evidence contract, so it establishes no explicit nullable/non-null validation for execution configuration (`YamlSkillDefinition.java:14-33`). In production construction, `YamlSkillCatalog` always supplies a populated record.

The production consumers of `YamlSkillDefinition.executionConfiguration()` are:

- `YamlSkillCapabilityRegistrar`, which always calls `SkillExecutionDescriptor.from(...)` while registering both LLM-backed and mapped public capabilities (`YamlSkillCapabilityRegistrar.java:57-68`).
- `SpringAiSkillChatClientFactory`, which selects a provider adapter, resolves the provider's `ChatModel`, and creates provider-specific options (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:63-101`).
- `DefaultMissionExecutionEngine`, which records provider/model trace metadata and sends model requests (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:110-181`).
- `StepLoopMissionExecutionEngine`, which passes the configuration into planning and step-model execution (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java:211-258`).
- `DefaultPlanningService`, which uses provider/model data for planning traces and model calls (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:124-148`, `DefaultPlanningService.java:292-308`, `DefaultPlanningService.java:396-430`).
- `SpringAiMissionUserMessageSender`, which uses provider/model information while sending attachment-aware mission messages (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/attachment/SpringAiMissionUserMessageSender.java:48-57`).

The last five consumers sit behind `ExecutionCoordinator`. `CapabilityExecutionRouter` reaches that coordinator only when `mappedTargetId` is null (`CapabilityExecutionRouter.java:69-85`). `ExecutionCoordinator` then creates a chat client and chooses the model execution engine (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:96-121`). A mapped invocation does not cross that boundary.

`SkillExecutionDescriptor` already represents absent execution configuration: all four fields are nullable, `none()` creates an all-null descriptor, and `configured()` tests `frameworkModel != null` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillExecutionDescriptor.java:9-33`). `CapabilityMetadata` normalizes a null descriptor to `none()` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:22-39`). However, `SkillExecutionDescriptor.from(...)` rejects null, and the YAML registrar currently always uses `from(definition.executionConfiguration())` (`SkillExecutionDescriptor.java:20-28`; `YamlSkillCapabilityRegistrar.java:57-68`). No production code outside registration reads `CapabilityMetadata.skillExecution`; it is currently a metadata projection exercised by tests.

### Java Target Contract Production

`SkillMethodBeanPostProcessor` assigns each Java target the ID `beanName#methodName`, builds its reflected input schema, resolves that schema into a `JAVA_REFLECTED` input contract, and stores both on `SkillImplementationTarget` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:147-164`). The target descriptor requires nonblank ID, description, and input schema and a non-null input contract (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillImplementationTarget.java:7-33`).

Mapped registration resolves this descriptor through the separate `SkillImplementationTargetRegistry`. It can initialize a referenced lazy/prototype bean by extracting the bean name from `target_id`, and it verifies that a custom registry returns a target whose ID exactly matches the request (`YamlSkillCapabilityRegistrar.java:137-179`). This target registry and identity verification are the current implementation of the prerequisite ticket.

### Mapped Input-Contract Flow

The mapped contract flow has two branches:

```text
mapping.target_id
    -> resolve SkillImplementationTarget
    -> input_schema absent
         -> copy target.inputContract.schema
         -> kind YAML_INHERITED
         -> publish target.inputSchema as provider-facing tool schema
    -> input_schema present
         -> convert YAML schema
         -> compare it recursively with reflected Java schema
         -> merge Java runtime-ref markers into YAML nodes
         -> kind YAML_EXPLICIT
         -> serialize the effective YAML contract as tool schema
```

`validateStructuralCompatibility` recursively compares type, required fields, semantic additional-properties behavior, additional-properties schemas, enums, formats, attachment metadata, property names/subtrees, and array items (`SkillInputContractResolver.java:131-206`). Descriptions are carried by schema nodes but are not among the compared fields. Runtime-ref markers are copied from corresponding Java nodes into an explicit YAML schema after compatibility validation (`SkillInputContractResolver.java:41-53`, `SkillInputContractResolver.java:346-376`).

For an inherited mapped contract, the registrar publishes the Java target's reflected JSON schema directly while substituting the public YAML name and description in `CapabilityToolDescriptor` (`YamlSkillCapabilityRegistrar.java:99-114`). Entry invocation through `DefaultSkillTemplate` and nested invocation through `CapabilityExecutionRouter` validate `CapabilityMetadata.inputContract` before execution (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/DefaultSkillTemplate.java:67-95`; `CapabilityExecutionRouter.java:50-67`). The router resolves `ref://` values only after validation and immediately before deterministic invocation (`CapabilityExecutionRouter.java:67-85`).

### Mapped Runtime Behavior Preserved by the Current Split

The YAML registrar creates the mapped invoker by delegating to `SkillImplementationTarget.invoker`. It wraps unexpected runtime failures with the public YAML skill name (`YamlSkillCapabilityRegistrar.java:74-97`). The target invoker itself resolves the final Spring bean/proxy at invocation time, retaining the proxy-aware behavior established by the prerequisite ticket (`SkillMethodBeanPostProcessor.java:147-164`).

Before calling this invoker, `CapabilityExecutionRouter`:

- checks the YAML wrapper's RBAC through `AccessGuard`;
- validates and normalizes against the wrapper's effective input contract;
- resolves runtime references;
- bypasses model-backed coordination because `mappedTargetId` is present.

These operations are located together at `CapabilityExecutionRouter.java:50-85`. The public wrapper retains the manifest name, description, RBAC, public tool descriptor, effective contract, and mapped target ID in `CapabilityMetadata` (`YamlSkillCapabilityRegistrar.java:57-70`). Multiple YAML wrappers can store the same target ID while retaining independent descriptions and RBAC (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java:175-212`).

### Other Fields Currently Accepted on Mapped Manifests

The manifest loader accepts fields other than `model`, `thinking_level`, and `input_schema` without a general mapped-field applicability branch. `prompt` is the exception and is explicitly rejected on mapped manifests (`YamlSkillCatalog.java:219-228`).

Mapped routing means fields consumed only after entry into `ExecutionCoordinator` do not participate in the direct deterministic call. In the current code, that includes mapped definitions' own `planning_mode`, `max_steps`, `allowed_skills`, linter configuration, model-output schema retry behavior, and evidence contract. The catalog can still parse, normalize, or validate those fields, and the complete manifest remains inspectable through `YamlSkillDefinition`; the direct mapped branch is selected before chat-client creation, planning, model output validation, and advisor execution (`CapabilityExecutionRouter.java:69-85`; `ExecutionCoordinator.java:96-121`).

The two production sample mappings both contain `planning_mode: false` (`bifrost-sample/src/main/resources/skills/basics/expense_lookup.yml:1-6`; `bifrost-sample/src/main/resources/skills/vision/feedstock_ticket_parser.yml:1-8`). No mapped fixture declares `thinking_level`, and no mapped fixture sets planning mode to true.

Fields that remain active on mapped wrappers include public `name`, public `description`, `rbac_roles`, `mapping.target_id`, and the effective input contract. A parent skill's evidence contract can credit evidence for completion of a mapped child by the child's public YAML name; that is the parent's contract, not the mapped child's own model-execution path.

### Tests and Fixtures

`YamlSkillCatalogTests` currently covers:

- default thinking level and non-thinking models (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:40-68`);
- unknown model and unsupported thinking failures (`YamlSkillCatalogTests.java:71-95`);
- `LLM_BACKED` versus `MAPPED_JAVA` classification (`YamlSkillCatalogTests.java:204-218`);
- prompt rejection on mapped YAML (`YamlSkillCatalogTests.java:246-256`);
- declared input-schema parsing and validation (`YamlSkillCatalogTests.java:326-402`);
- mapped-schema structural and format mismatch failures at registration/startup (`YamlSkillCatalogTests.java:404-425`).

There is no current fixture for a YAML skill with a missing `model`, mapped or unmapped. The current unknown-model fixture establishes catalog lookup failure but not missing-model behavior.

`YamlSkillCapabilityRegistrarTests` currently proves that a mapped definition:

- carries `gpt-5` into `CapabilityMetadata.skillExecution` even while inheriting and invoking the Java target contract (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java:130-150`);
- can share a public name with its Java method because the registries are separate (`YamlSkillCapabilityRegistrarTests.java:153-173`);
- can share one target across multiple public YAML wrappers with independent RBAC and descriptions (`YamlSkillCapabilityRegistrarTests.java:175-212`);
- resolves final advised, lazy, and prototype beans (`YamlSkillCapabilityRegistrarTests.java:222-257`);
- inherits a reflected Java contract when YAML omits `input_schema` (`YamlSkillCapabilityRegistrarTests.java:260-270`);
- retains public-boundary exception behavior and clear unknown-target failure (`YamlSkillCapabilityRegistrarTests.java:303-346`).

`CapabilityExecutionRouterTest` covers nested RBAC/evidence/plan preservation and root mapped runtime-ref binding; its root mapped test uses `SkillExecutionDescriptor.none()`, showing that direct mapped routing itself does not require configured model metadata (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/CapabilityExecutionRouterTest.java:28-307`). `SkillTemplateTest` covers YAML-only public invocation and validates through the capability contract (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skillapi/SkillTemplateTest.java:38-177`).

### Samples and Documentation

Both mapped sample manifests declare `model: granite4-tiny` even though they route to no-argument Java targets (`bifrost-sample/src/main/resources/skills/basics/expense_lookup.yml:1-6`; `bifrost-sample/src/main/resources/skills/vision/feedstock_ticket_parser.yml:1-8`). `ExpenseService#getLatestExpenses` is a deterministic no-argument target (`bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/ExpenseService.java:9-18`). `FeedstockFormExtractionService#extractSampleFeedstockTicket` is also a no-argument Java target; it makes its own explicit OpenAI Responses API call using service configuration, independently of the YAML wrapper's `granite4-tiny` execution configuration (`bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/FeedstockFormExtractionService.java:82-99`, `FeedstockFormExtractionService.java:127-145`).

The root README states that every YAML skill must name a catalog model and presents a mapped example containing `model: granite4-tiny` (`README.md:90`, `README.md:122-190`). The authoring mental model gives the more detailed current-checkout description: mapped skills invoke Java rather than an LLM, inherit the reflected contract when YAML omits `input_schema`, may currently declare only a structurally compatible duplicate schema, and still must declare a valid model because of temporary catalog behavior (`ai/skill-authoring/mental-model.md:63-93`). Its checklist already asks authors to omit mapped `input_schema` so the Java target remains the contract source (`ai/skill-authoring/checklists/evaluate-a-skill-design.md:48-49`).

## Code References

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:70-88` - Empty model catalog prevents YAML discovery.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:137-189` - Universal model/thinking resolution and definition construction.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:14-33` - Definition record and current execution-configuration field.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:60-102` - Input-source and implementation-kind projections.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:49-135` - Target resolution, metadata projection, tool schema, and duplicate-schema compatibility path.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputContractResolver.java:41-64` - Explicit, inherited, and generic YAML contract selection.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputContractResolver.java:131-206` - Structural compatibility validation.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillExecutionDescriptor.java:9-33` - Existing all-null `none()` execution projection.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:50-85` - Security, validation, model-backed versus mapped routing, ref binding, and invocation.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:96-121` - Model-backed boundary that creates chat clients and runs mission engines.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:147-164` - Reflected Java schema and implementation-target creation.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java:130-150` - Current mapped metadata includes a fabricated model descriptor while execution is deterministic.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java:260-270` - Reflected contract inheritance.
- `bifrost-spring-boot-starter/src/test/resources/skills/valid/mapped-method-skill-with-input-schema.yaml:1-13` - Current valid duplicate mapped-schema fixture.

## Architecture Documentation

The current architecture separates public and implementation identity but not model configuration at the catalog type boundary:

```text
YAML resource
  -> YamlSkillCatalog
       always resolves model + thinking
       stores EffectiveSkillExecutionConfiguration
       classifies implementation from mapping.target_id
  -> YamlSkillCapabilityRegistrar
       unmapped: generic/declared YAML contract
       mapped: resolve separate SkillImplementationTarget
               inherit Java contract OR validate duplicate YAML contract
       always project SkillExecutionDescriptor.from(configuration)
  -> public CapabilityRegistry
       mappedTargetId null      -> CapabilityExecutionRouter -> ExecutionCoordinator -> model
       mappedTargetId non-null  -> CapabilityExecutionRouter -> target invoker -> Java
```

`CapabilityMetadata` is already capable of describing no model configuration through `SkillExecutionDescriptor.none()`. The live YAML registration path does not use that representation for mapped skills. The Java target registry contains no framework model/provider/provider-model/thinking fields; those values enter mapped public metadata only through the YAML definition and registrar.

Input contracts are distinct from implementation identity. Java discovery creates `JAVA_REFLECTED`; mapped YAML registration projects either `YAML_INHERITED` or `YAML_EXPLICIT`; pure YAML can project `YAML_EXPLICIT` or `GENERIC` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputContract.java:7-40`). Validation and publication operate on the effective public capability contract regardless of which source produced it.

## Historical Context (from ai/thoughts/)

- `ai/thoughts/research/2026-07-13-separate-public-skills-from-java-targets.md` documented the pre-separation shared registry at commit `abc9885`. At that point mapped target resolution scanned public `CapabilityMetadata` by ID; the current commit replaces that flow with `SkillImplementationTargetRegistry`.
- `ai/thoughts/plans/2026-07-13-separate-public-skills-from-java-targets.md:361` explicitly retained mapped sample `model` fields for this follow-up ticket.
- `ai/thoughts/plans/2026-07-13-separate-public-skills-from-java-targets.md:389` explicitly retained the authoring guide's temporary mapped-model and compatible-schema warning until this follow-up is implemented.
- Commit `e8cb09e` (`Separate Public YAML Skills from Java Implementation Targets`) is the current HEAD and implements the prerequisite identity/registry boundary used by this research.
- At the initial research commit, `ai/thoughts/tickets/eng-simplify-mapped-yaml-skill-manifests.md` owned only model/thinking/input-schema applicability. The follow-up decisions below intentionally broaden that ticket to every mapped-only field whose runtime path is bypassed.

## Related Research

- [`2026-07-13-separate-public-skills-from-java-targets.md`](2026-07-13-separate-public-skills-from-java-targets.md)
- [`eng-separate-public-skills-from-java-targets.md`](../tickets/eng-separate-public-skills-from-java-targets.md)
- [`eng-simplify-mapped-yaml-skill-manifests.md`](../tickets/eng-simplify-mapped-yaml-skill-manifests.md)
- [`mental-model.md`](../../skill-authoring/mental-model.md)

## Initial Open Questions (Resolved Below)

These were the open questions at the end of the first code pass. The dated follow-ups below resolve all of them; they are retained here to preserve the research trail.

- The current source contains two available absence representations at different layers: a potentially absent `YamlSkillDefinition.executionConfiguration` (not explicitly annotated or guarded) and the explicit all-null `SkillExecutionDescriptor.none()`. The checkout contains no implemented invariant selecting how mapped YAML definitions should connect those layers.
- The empty-model-catalog early return currently suppresses all YAML discovery, including mapped YAML. No focused test establishes the intended catalog behavior for an application containing mapped skills but no configured framework models.
- The ticket intentionally leaves broader mapped-field applicability outside this correction. The live direct-routing boundary shows that `planning_mode`, `max_steps`, a mapped skill's own `allowed_skills`, linter, model-output retry path, and its own evidence contract do not participate in deterministic invocation; the current checkout contains no separate design decision for those fields.
- No mapped fixture currently declares `thinking_level`, and no mapped fixture sets `planning_mode: true`; current behavior for those declarations follows the generic catalog parser plus direct mapped routing rather than focused test coverage.

## Follow-up Research 2026-07-13T19:01:54-07:00

The user requested recommendations for the open questions and clarified that Bifrost is still in development, so breaking internal and manifest corrections are acceptable.

### Recommended Execution-Configuration Invariant

Use one conditional invariant in `YamlSkillDefinition`:

- `LLM_BACKED` definitions have a non-null `EffectiveSkillExecutionConfiguration`.
- `MAPPED_JAVA` definitions have no `EffectiveSkillExecutionConfiguration`.
- `CapabilityMetadata` projects the first case with `SkillExecutionDescriptor.from(...)` and the second with `SkillExecutionDescriptor.none()`.

The record component should be explicitly nullable, and the `YamlSkillDefinition` compact constructor should reject both invalid combinations: a mapped definition with model execution configuration and an unmapped definition without it. Model-backed runtime code should obtain the value through a boundary method such as `requireExecutionConfiguration()`, which fails with an invariant-oriented message if an invalid definition reaches model execution. This keeps the nullable state localized rather than adding null checks throughout chat, planning, and mission code.

This recommendation uses the execution-kind signal that already exists (`mappingTargetId`/`implementationType`) and the absent metadata representation that already exists (`SkillExecutionDescriptor.none()`). It avoids introducing a second execution-kind hierarchy beside `PublicSkillImplementationType`.

An alternative is a sealed definition hierarchy such as `ModelBackedYamlSkillDefinition` and `MappedJavaYamlSkillDefinition`. That gives stronger compile-time exhaustiveness but changes every catalog and runtime consumer and duplicates distinctions already represented by mapping and `PublicSkillImplementationType`. It is appropriate only if the framework is about to add more execution kinds with materially different state; the current two-kind model does not require that additional type structure.

### Recommended Empty-Model-Catalog Behavior

Remove the model-catalog-empty early return and always discover YAML manifests.

After discovery and classification:

- mapped-only applications can load without any `bifrost.models` entries;
- an LLM-backed manifest still fails because its required model is missing or unknown;
- mixed applications load mapped definitions and validate every LLM-backed definition against the model catalog.

The current early return silently converts configured YAML resources into an empty catalog. Removing it makes resource presence authoritative and lets conditional validation report the actual manifest error. The behavior change is that an application which accidentally packages LLM-backed YAML while configuring no models will fail startup instead of silently publishing no skills; for an in-development framework, that failure is the more explicit contract.

### Recommended Treatment of Other Mapped Fields

Keep this ticket limited to `model`, `thinking_level`, and `input_schema`, as its decision locks specify. Record a separate immediate follow-up ticket for a complete mapped-field applicability matrix rather than adding incidental checks while editing catalog loading.

For that follow-up, the fields with clear model-runtime-only semantics should be rejected when declared on a mapped skill:

- `planning_mode` and `max_steps`, because mapped execution never enters a planning or mission loop;
- a mapped skill's own `allowed_skills`, because the mapped boundary does not construct a child tool surface;
- `linter`, because the mapped branch does not run model-response advisors or retries;
- `output_schema_max_retries`, because there is no model response to retry;
- a mapped skill's own `evidence_contract`, because the mapped boundary does not plan or validate model-produced claims.

Rejecting explicit false, zero, or empty declarations as well as active values gives field presence a consistent meaning: authors do not configure execution mechanisms that cannot run at that boundary. Some manifest fields currently normalize absence to empty/default values, so enforcing declaration-level rejection may require retaining whether the YAML field was present rather than inspecting only the normalized value.

`output_schema` itself needs a separate choice. The current mapped route neither validates Java return values against it nor publishes an output contract through `CapabilityMetadata`. There are two coherent options:

1. Reject mapped `output_schema` until the framework defines a Java-owned reflected output contract or deterministic return validation. This is the smallest honest current contract and is the recommended near-term choice.
2. Retain mapped `output_schema` as a public deterministic output contract, then define when Java results are serialized, validated, traced, and failed. This preserves YAML control over public output shape but creates a second Java/YAML compatibility and enforcement design analogous to the input-schema path being removed.

Because the framework is pre-release and duplicate input contracts are being removed for single-source ownership, rejecting mapped `output_schema` until it has defined deterministic semantics is consistent with the current correction. That decision still belongs in the separate applicability ticket because the present ticket explicitly excludes output-schema redesign.

RBAC, public name, description, and `mapping.target_id` remain applicable mapped fields. Evidence attribution from a parent skill's `tool_evidence` mapping also remains applicable because it describes the parent's use of the mapped child, not an evidence contract executed by the child.

### Recommended Fixture Coverage

Add a mapped fixture declaring `thinking_level`; startup must reject it with the ticket's required inapplicable-field error. This is not an unresolved design question because the ticket already locks and requires that behavior.

Do not add a `planning_mode: true` expectation to this ticket. Its outcome depends on the separate mapped-field applicability decision. When that decision is implemented, add focused invalid fixtures for `planning_mode` declarations, including `false`, so tests establish that field presence rather than truthiness is invalid. The two sample mappings' current `planning_mode: false` declarations should be handled by that same follow-up rather than being removed incidentally under the model/input-schema ticket.

## Follow-up Research 2026-07-13T19:12:10-07:00

The recommendation to separate other mapped-field validation was based primarily on the ticket's existing scope lock, not on a need to preserve compatibility. The ticket explicitly says not to decide `planning_mode`, `max_steps`, and other fields unless required by an implementation invariant. Its acceptance criteria and verification list are correspondingly centered on model configuration and input-contract ownership.

Separation has three practical benefits:

1. The current ticket has one reviewable semantic claim: mapped execution has neither model configuration nor a YAML-owned input contract. The associated internal type change, conditional catalog loading, registrar projection, and schema compatibility removal can be reviewed together.
2. The remaining fields are not one uniform problem. Planning, child visibility, linting, evidence, retry configuration, and output contracts have different ownership and runtime meanings. In particular, `output_schema` could either be rejected or become a deterministic public return contract, which requires a separate enforcement design.
3. The ticket is prerequisite 2 of 3 in an existing delivery sequence. Broadening it changes its acceptance surface and delays the naming-validation prerequisite and gallery work.

Those are process and semantic-cohesion reasons, not objections to breaking changes. If the design review explicitly changes the scope now, the catalog classification branch introduced by this ticket is also the natural place to enforce all fields that are unambiguously model-runtime-only. Given the clarification that destructive pre-release cleanup is welcome, a coherent alternative—and now the preferred option if the ticket is intentionally amended—is:

- include rejection of `planning_mode`, `max_steps`, `allowed_skills`, `linter`, `output_schema_max_retries`, and the mapped skill's own `evidence_contract` in this ticket;
- treat field presence as invalid even when the value is `false`, zero, or empty;
- update the ticket's decision locks, non-goals, startup errors, phases, fixtures, samples, documentation, and acceptance criteria before implementation;
- keep `output_schema` itself in a separate design decision because it is potentially a legitimate public deterministic return contract rather than merely unused model execution configuration.

This broadened version remains a single architectural rule: classify the execution kind first, then accept only fields whose semantics exist for that kind. It is preferable to quietly adding checks under the current ticket text; the important boundary is explicit design coverage, not the number of tickets.

## Follow-up Research 2026-07-13T19:20:25-07:00

The user selected the broadened ticket scope and proposed rejecting `output_schema` because mapped output is Java-owned.

Rejecting mapped `output_schema` is consistent with the ticket's single-source contract rule and is the recommended decision. A mapped capability delegates its entire implementation boundary to one Java target. Its input structure comes from that target's reflected parameter contract, and its returned value comes from that target's Java return behavior. Allowing YAML to redeclare either side creates a second public contract source without transformation semantics.

The current runtime provides additional support for rejection:

- mapped invocation calls the Java-backed invoker directly and does not enter model output-schema advisors or retry handling (`CapabilityExecutionRouter.java:69-85`);
- `CapabilityMetadata` stores an input contract but no output-contract projection (`CapabilityMetadata.java:9-20`);
- the current `output_schema` machinery is designed around validating and retrying model responses, not validating deterministic Java return objects;
- none of the 17 mapped YAML resources in the current checkout declares `output_schema`, so the decision does not require migrating an existing mapped output declaration.

Retaining mapped `output_schema` would require new semantics for Java result serialization, validation timing, failure transformation, trace payloads, nested tool returns, and compatibility between the Java return type and YAML. Those are not present in the current runtime. Rejecting the field avoids presenting descriptive YAML as an enforced contract.

The tradeoff is that the public catalog cannot currently publish a structured mapped output contract. The authoritative source is the Java target's actual return behavior, but Bifrost does not yet reflect that behavior into an output-schema type. If structured mapped output introspection is added later, the coherent extension is a Java-reflected output contract, optionally using Java annotations for descriptions and constraints. If one public mapped capability needs a different returned shape, it should map to a different Java adapter target that performs that transformation explicitly.

With this decision, the broadened ticket can apply one mapped-field rule. A mapped manifest may declare public identity and governance fields whose runtime semantics exist at that boundary, including `name`, `description`, `mapping.target_id`, and `rbac_roles`. It must reject `model`, `thinking_level`, `prompt`, `input_schema`, `output_schema`, `planning_mode`, `max_steps`, `allowed_skills`, `linter`, `output_schema_max_retries`, and its own `evidence_contract` when those fields are declared. Parent skills may still refer to the mapped skill in their own `allowed_skills` and `evidence_contract.tool_evidence`; those declarations belong to the parent model-backed boundary.

This remains a tractable catalog/manifest correction rather than a runtime redesign. Most work is centralized in classification-before-validation, field-presence detection, startup diagnostics, fixtures, sample cleanup, and documentation. The mapped invocation, Java target registry, input validation, RBAC, ref binding, exception behavior, and tracing paths remain unchanged.

## Follow-up Research 2026-07-13T21:06:00-07:00

A final gap pass found five remaining decisions or verification boundaries that should be resolved before implementation planning.

### Declared-Field Presence Semantics

The broadened rule rejects fields when they are declared, not only when their normalized value is active. This should explicitly include YAML nulls, blanks, false values, zero values, and empty collections. For example, all of the following are declarations and should fail on a mapped skill:

```yaml
model: null
planning_mode: false
max_steps: 0
allowed_skills: []
```

This matters because `YamlSkillManifest` currently initializes `allowedSkills` and `rbacRoles` to empty lists and normalizes null setters to empty lists (`YamlSkillManifest.java:24-28`, `YamlSkillManifest.java:102-120`). After deserialization, an omitted `allowed_skills` field and an explicitly empty one are indistinguishable. The implementation therefore needs declaration-presence tracking for conditionally applicable fields, either through setter flags/a declared-field set or by retaining raw YAML field presence during binding. Inspecting only normalized values would not enforce the selected authoring rule.

Recommendation: treat any explicit YAML occurrence as declared and retain presence separately from normalized values. Keep `rbac_roles` exempt because it remains applicable to mapped wrappers.

### Blank or Incomplete Mapping Declarations

Current `YamlSkillDefinition.mappingTargetId()` normalizes a blank target ID to null, and `YamlSkillCapabilityRegistrarTests` explicitly treats a programmatically constructed blank target as LLM-backed (`YamlSkillDefinition.java:85-102`; `YamlSkillCapabilityRegistrarTests.java:84-101`). That behavior makes these two manifests equivalent:

```yaml
# LLM-backed
name: example
model: configured-model
```

```yaml
# Currently normalized as LLM-backed
name: example
model: configured-model
mapping:
  target_id: " "
```

Recommendation: absence of the `mapping` block means LLM-backed; declaring `mapping` requires a nonblank `mapping.target_id`. A blank, null, or missing target inside an explicit mapping block should fail at `mapping.target_id`. This makes classification local and removes an ambiguous declaration. The current blank-normalization registrar test should be replaced with catalog fixtures covering omitted mapping versus invalid blank/incomplete mapping.

### Validation Ordering and Diagnostic Identity

Once `name` and `description` are valid and execution kind is classified, mapped field-applicability validation should run before model resolution, schema/linter/evidence content validation, and Java target lookup. That ordering guarantees that a mapped manifest containing `model` reports the inapplicable `model` declaration even when the value is unknown, and that a mapped `output_schema` reports inapplicability rather than a nested schema-shape error.

The existing catalog is fail-fast, so aggregation is not required. Recommendation: use a stable field order matching the documented applicability matrix and test each field independently. For diagnostics, include both the validated public skill name and resource description when available. Current catalog errors identify the resource, while the ticket's required examples identify the skill name (`YamlSkillCatalog.java:745-748`). Including both preserves fixture/file diagnostics and satisfies the public-skill identity requirement.

### Truly Model-Free Mapped Application Verification

Removing the empty-model-catalog return is necessary and the current auto-configuration structure supports mapped-only execution without a usable model. `SkillTemplate` and `CapabilityExecutionRouter` are unconditional infrastructure beans; the router receives `ExecutionCoordinator` through `ObjectProvider` and requests it only for unmapped capabilities (`BifrostAutoConfiguration.java:239-270`; `CapabilityExecutionRouter.java:69-85`). The chat-model resolver/factory may exist with no registered provider model, but mapped execution never asks it to resolve one (`BifrostAutoConfiguration.java:399-464`).

Recommendation: add an application-context integration test with no `bifrost.models` entries and no provider `ChatModel` bean. It should load a mapped manifest, publish it with `SkillExecutionDescriptor.none()`, invoke it through `SkillTemplate`, and assert that no execution coordinator/chat-model resolution is required. Also retain a mixed/LLM test proving an LLM-backed manifest fails clearly when no model catalog entry exists.

### Legacy `ModelPreference` Scope

**Resolution (superseded by the 21:17 follow-up below):** The initial recommendation to track this cleanup separately was rejected. The ticket now includes destructive removal, and the removal has already been applied and verified in the working tree.

`CapabilityMetadata` and `SkillImplementationTarget` still require `ModelPreference`; the YAML registrar hard-codes `ModelPreference.LIGHT`, and production code does not read `modelPreference()` (`CapabilityMetadata.java:9-28`; `SkillImplementationTarget.java:7-22`; `YamlSkillCapabilityRegistrar.java:57-68`). `@SkillMethod.modelPreference` is legacy Java-target metadata retained by the prerequisite ticket (`SkillMethod.java:12-19`).

This field is adjacent to, but distinct from, framework model/provider execution configuration. Recommendation: explicitly state that this ticket's “no fabricated model metadata” criterion covers `EffectiveSkillExecutionConfiguration` and `SkillExecutionDescriptor`, not legacy `ModelPreference`. Removing `ModelPreference` is a valid pre-release cleanup, but it affects the Java annotation, target descriptor, public capability constructor, and many unrelated tests; doing so here would broaden the work beyond manifest applicability without changing mapped execution. Track its removal separately unless the implementation plan intentionally expands into legacy metadata cleanup.

With these decisions recorded, no further architectural questions are apparent for the mapped-manifest correction. Remaining work is implementation planning: enumerate startup messages/fixtures for every rejected field, update the ticket text to reflect the broadened applicability matrix, and map the established invariant into catalog, definition, registrar, sample, and documentation changes.

## Resolved Decision Summary

The research and ticket now agree on the complete implementation boundary:

- Omitted `mapping` means LLM-backed; an explicit mapping requires a non-blank `target_id`.
- Mapped wrappers allow only `name`, `description`, `mapping.target_id`, and `rbac_roles`.
- Mapped declarations of `model`, `thinking_level`, `prompt`, `input_schema`, `output_schema`, `planning_mode`, `max_steps`, `allowed_skills`, `linter`, `output_schema_max_retries`, or their own `evidence_contract` fail startup.
- Explicit null, blank, false, zero, and empty values still count as declarations, requiring parser-level presence tracking.
- Java owns mapped input and output. A different public shape requires a Java adapter; mapped `output_schema` is rejected rather than treated as unenforced documentation.
- Mapped definitions carry no execution configuration and project `SkillExecutionDescriptor.none()`; model-backed consumers require configuration at their boundary.
- YAML discovery proceeds with an empty model catalog, and a mapped-only context is verified without framework models or a `ChatModel` bean.
- Applicability validation is fail-fast in a stable field order before model/content/target validation, and errors include both public skill name and YAML resource.
- Parent LLM skills may still use their own `allowed_skills` and evidence contracts to govern mapped children.
- Legacy `ModelPreference` is removed destructively from the enum, annotation, metadata, construction paths, and tests; no compatibility API remains.

## Follow-up Research 2026-07-13T21:17:13-07:00

The user decided that legacy metadata should be removed rather than tracked separately. `ModelPreference` has therefore been deleted from the current working tree:

- the `ModelPreference` enum source is deleted;
- `@SkillMethod` now exposes only `description`;
- `SkillImplementationTarget` no longer stores a model preference;
- `CapabilityMetadata` no longer stores or defaults a model preference;
- `SkillMethodBeanPostProcessor` and `YamlSkillCapabilityRegistrar` no longer project preference values;
- all constructor call sites and preference-specific tests have been updated.

No compatibility constructor or deprecated annotation attribute remains. Historical research and completed prerequisite plans still describe `ModelPreference` because it existed at their researched commits; those documents remain historical records rather than live API documentation.

Verification after removal:

- `mvnw.cmd -pl bifrost-spring-boot-starter clean test` — 461 tests passed.
- `mvnw.cmd test` — full parent/starter/sample reactor passed; the sample module ran 8 tests.
- A live-source search under starter main and test sources returns no `ModelPreference` or `modelPreference` references.

The mapped-manifest ticket can now state “no fabricated model metadata” without a legacy preference exception. The remaining relevant representations are `EffectiveSkillExecutionConfiguration` on model-backed definitions and `SkillExecutionDescriptor`, whose `none()` value represents mapped public metadata.
