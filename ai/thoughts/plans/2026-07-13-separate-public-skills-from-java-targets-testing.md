# Testing Plan: Separate Public YAML Skills from Java Implementation Targets

**Date:** 2026-07-13

**Implementation plan:** [`2026-07-13-separate-public-skills-from-java-targets.md`](2026-07-13-separate-public-skills-from-java-targets.md)

**Governing ticket:** [`eng-separate-public-skills-from-java-targets.md`](../tickets/eng-separate-public-skills-from-java-targets.md)

**Research:** [`2026-07-13-separate-public-skills-from-java-targets.md`](../research/2026-07-13-separate-public-skills-from-java-targets.md)

## Change Summary

This iteration separates public YAML capabilities from internal Java `@SkillMethod` implementation targets. `CapabilityRegistry` becomes a YAML-only public namespace; a new `SkillImplementationTargetRegistry` owns internal `beanName#methodName` targets. Mapped YAML registration resolves the internal target and creates the provider-facing tool descriptor from the YAML name and description.

The tests must prove more than successful registration. They must lock the identity boundary, deterministic target discovery, final-proxy invocation, lifecycle ordering, public attribution, and the existing mapped-input/security/error/ref behavior. This plan intentionally leaves mapped-manifest simplification and portable YAML-name validation to their subsequent tickets.

## Impacted Areas

- `@SkillMethod` annotation shape and all annotated fixtures.
- `SkillImplementationTarget`, its registry contract/default implementation, and duplicate handling.
- `SkillMethodBeanPostProcessor` discovery across user classes, interfaces, proxies, bridge methods, and overloads.
- Invocation through the final Spring bean/proxy rather than a captured post-processor instance.
- `CapabilityRegistry`, `InMemoryCapabilityRegistry`, and explicit non-null `CapabilityMetadata.kind`.
- YAML mapped-target lookup, public tool descriptor construction, inherited input contracts, target reuse, errors, and runtime refs.
- Auto-configuration bean wiring and target-before-YAML lifecycle ordering.
- `SkillTemplate`, visibility, routing, planning, evidence, journal, and tool callback paths that must use public YAML names only.
- Sample `/expenses`, application-context assertions, README text, and authoring documentation.

Not impacted in this iteration:

- Rejection/removal of mapped `model`, `thinking_level`, or `input_schema`; that belongs to `eng-simplify-mapped-yaml-skill-manifests.md`.
- Portable YAML name syntax and fixture renames; that belongs to `eng-validate-public-yaml-skill-names.md`.
- Provider calls, live model behavior, or a public catalog endpoint.

## Risk Assessment

| Risk | Level | Required proof |
|---|---:|---|
| Same Java/YAML name still collides or leaks into public enumeration | High | Context starts with equal names; public registry contains only YAML; target registry contains the Java target. |
| A mapped invoker captures a raw bean and bypasses Spring advice | High | A real Spring proxy/advisor surrounds mapped invocation and observes exactly one call. |
| Proxy/interface/bridge discovery misses a target or registers it twice | High | JDK proxy/interface annotation and generic bridge fixtures each register one canonical target; genuine overloads still fail. |
| Ambiguous overload failure occurs after partial bean registration | High | Startup error has the required diagnostics and the target registry has no targets from that bean. |
| Public registry enforcement relies only on caller convention | High | `CapabilityKind` exposes only `YAML_SKILL`, and null metadata kind is rejected immediately. |
| Registrar compatibility constructor creates an isolated target namespace | High | The registrar exposes only the constructor that requires the shared implementation-target registry. |
| Target lifecycle ordering breaks mapped YAML startup | High | Full `ApplicationContextRunner` registration resolves a discovered target before the YAML registrar publishes the wrapper. |
| Provider-facing tool identity uses internal `bean#method` syntax | High | Internal descriptor has schema/contract but no tool descriptor; mapped public tool name/description come from YAML. |
| Existing mapped contracts, refs, errors, or RBAC regress | High | Existing focused tests are retained/moved to the new boundary and an end-to-end mapped path covers each behavior. |
| Raw Java identifiers remain invocable or visible as children | Medium | `SkillTemplate` and visibility treat method names/target IDs as unknown public YAML skills. |
| Public attribution changes to target IDs | Medium | Plans, tool callbacks, evidence, journal, and traces assert YAML capability names. |
| Public tooling cannot distinguish mapped from LLM-backed YAML | Medium | Catalog/metadata projection exposes explicit `MAPPED_JAVA` versus `LLM_BACKED` without inspecting invokers. |
| Sample continues teaching the invalid raw invocation path | Medium | Controller and context tests assert `expenseLookup`; repository text checks find no public `getLatestExpenses` invocation claim. |

## Existing Test Coverage

The research baseline at commit `abc9885` passed the focused starter suite (25 tests) and the sample suite (7 tests). That is a useful pre-change baseline, but it does not prove the new separation.

Existing coverage to preserve or adapt:

- `SkillMethodTest` verifies annotation target, retention, and current attributes.
- `SkillMethodBeanPostProcessorTest` thoroughly covers reflected input schemas, binding, optional arguments, exception transformation, and nested/ref materialization, but currently treats Java methods as public capabilities.
- `InMemoryCapabilityRegistryTest` covers lookup, collision, concurrency, and enumeration, but its fixtures are `JAVA_METHOD` metadata and must become YAML public metadata.
- `YamlSkillCapabilityRegistrarTests` already uses `ApplicationContextRunner` to cover mapped resolution, inherited contracts, YAML RBAC, transformed errors, and unknown targets.
- `BifrostAutoConfigurationTests` covers shared resolver wiring and currently asserts Java and YAML entries coexist in one registry; that assertion must be inverted.
- `SkillVisibilityResolverTest` covers YAML catalog/RBAC filtering but currently injects a Java capability into the public registry.
- `SkillTemplateTest` covers the YAML-only API, but currently establishes the negative case by inserting `JAVA_METHOD` metadata into the public registry, which the new registry must prohibit.
- `CapabilityExecutionRouterTest` and `ExecutionCoordinatorIntegrationTest` cover delegation, security, refs, plans, tools, and evidence. Raw-Java public fixtures must be removed or redirected through mapped YAML wrappers.
- `ToolCallbackFactoryTest`, planning tests, and evidence tests already exercise public capability names and should remain YAML-named characterization coverage.
- `SampleApplicationTests` currently expects `getLatestExpenses` in the public registry, and `SampleControllerTest` has no `/expenses` assertion.

Coverage gaps this plan closes:

- No separate target-registry contract tests.
- No same-name Java/YAML success case.
- No canonical interface/proxy/bridge discovery cases.
- No genuine-overload/no-partial-registration assertion.
- No real-advice mapped invocation test.
- No direct public-registry kind enforcement or null-kind test.
- No assertion that an internal descriptor cannot expose provider-facing tool identity.
- No target reuse test with independent public descriptions/RBAC.
- No explicit public implementation-type projection test.
- No sample controller assertion for the corrected public expense skill.

## Bug Reproduction / Failing Test First

Add and run one minimal integration test before production changes:

### `sameNamedYamlSkillAndJavaMethodRegisterInSeparateNamespaces`

- **Type:** Application-context integration test.
- **Location:** `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java`.
- **Fixture:** Add `src/test/resources/skills/valid/same-name-mapped-method-skill.yaml` with `name: deterministicTarget` and `mapping.target_id: targetBean#deterministicTarget`; use the existing `TargetBeanConfiguration` and Java method `deterministicTarget` after removing the annotation `name` argument.
- **Assertion:** The context starts; `CapabilityRegistry.getCapability("deterministicTarget")` is one `YAML_SKILL`; `SkillImplementationTargetRegistry.getTarget("targetBean#deterministicTarget")` is present; public enumeration has one `deterministicTarget` entry and no target ID.
- **Expected pre-fix failure:** Startup fails with `CapabilityCollisionException` because the Java method and YAML wrapper are registered under the same shared-registry key.
- **Mocks:** None. Use the existing `ApplicationContextRunner` and real auto-configuration.
- **Why first:** It is the smallest stable reproduction of the ticket's root defect and simultaneously exercises registry separation and initialization ordering.

Run only this test first:

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCapabilityRegistrarTests#sameNamedYamlSkillAndJavaMethodRegisterInSeparateNamespaces" test
```

Record the collision failure, implement the smallest registry separation needed to turn it green, then proceed through the focused tests below. Do not weaken the fixture by choosing different Java and YAML names.

## Tests to Add/Update

### 1. Annotation and internal target descriptor

#### Update `SkillMethodTest`

- **Tests:** Rename `exposesDefaultsAndConfiguredValues` to `exposesDescriptionAndModelPreferenceWithoutPublicName`; add `doesNotDeclarePublicNameAttribute` if the reflection assertion is not clear in the renamed test.
- **Type/location:** Unit; existing `annotation/SkillMethodTest.java`.
- **Proves:** `name` is removed while `description` and legacy `modelPreference` remain available.
- **Fixtures/mocks:** Nested annotated methods; no mocks.

#### Add `SkillImplementationTargetTest`

- **Tests:** `storesInternalIdentityInvokerSchemaAndContract`; `doesNotExposeProviderFacingToolDescriptor`.
- **Type/location:** Unit; new `core/SkillImplementationTargetTest.java`.
- **Proves:** The internal record carries `id`, description, legacy preference if retained, invoker, raw reflected schema, and input contract. Reflect over record components/fields to ensure neither `CapabilityToolDescriptor` nor Spring AI `ToolDefinition` is stored.
- **Fixtures/mocks:** A no-op invoker and concrete Java-reflected input contract; no Spring context or mocks.

### 2. Internal target registry contract

#### Add `InMemorySkillImplementationTargetRegistryTest`

- **Tests:** `returnsNullWhenTargetIsMissing`; `registersAndRetrievesTargetById`; `enumeratesTargetsWithoutMutableBackingState`; `rejectsDuplicateTargetIdBeforeMutation`; `supportsConcurrentRegistrationAndReads`.
- **Type/location:** Unit; new `core/InMemorySkillImplementationTargetRegistryTest.java`.
- **Proves:** Lookup/enumeration parity with the public registry where useful, immutable/defensive enumeration, thread safety, and the required actionable duplicate-ID error.
- **Fixtures/mocks:** Small target factory using distinct `bean#method` IDs; no mocks.

### 3. Canonical target discovery and invocation

#### Refactor `SkillMethodBeanPostProcessorTest`

- **Tests:** Rename `registersAnnotatedMethodAsCapability` to `registersAnnotatedMethodAsInternalTarget`; retain binding/schema/ref/error tests against the target descriptor's invoker and schema; add `registersDifferentlyNamedAnnotatedMethodsOnOneBean`; add `rejectsAnnotatedOverloadsBeforeRegisteringAnyTargetFromBean`.
- **Type/location:** Focused unit/component; existing `core/SkillMethodBeanPostProcessorTest.java`.
- **Proves:** Java discovery publishes only internal target IDs, preserves all deterministic binding behavior, permits distinct method names, and rejects genuine overloads atomically.
- **Fixtures/mocks:** Real fixture beans and in-memory target registry. The overload fixture has `lookup(String)` and `lookup(long)` plus another uniquely named annotated method so the test can verify no partial registration. No Mockito is required.
- **Required error assertions:** Bean name, method name `lookup`, target ID `beanName#lookup`, and the instruction that annotated method names must be unique.

#### Add `SkillMethodTargetDiscoveryIntegrationTests`

- **Tests:** `discoversInterfaceDeclaredAnnotationOnJdkProxyExactlyOnce`; `canonicalizesGenericBridgeMethodWithoutFalseOverload`; `discoversCglibProxiedImplementationExactlyOnce`; `stillRejectsGenuineOverloadsAfterCanonicalization`.
- **Type/location:** Spring application-context integration; new `core/SkillMethodTargetDiscoveryIntegrationTests.java`.
- **Proves:** Merged interface annotations, user-class resolution, bridge canonicalization, synthetic/bridge filtering, and genuine ambiguity detection.
- **Fixtures/mocks:** Real Spring `ProxyFactory`/auto-proxy fixtures: an annotated interface with an unannotated implementation, a generic annotated interface producing a bridge method, and a class-proxied bean. Use counters or registry enumeration, not Mockito, to assert one canonical registration.

#### Add real-advice coverage to `YamlSkillCapabilityRegistrarTests`

- **Test:** `mappedInvocationResolvesFinalAdvisedBean`.
- **Type/location:** Application-context integration; existing `skill/YamlSkillCapabilityRegistrarTests.java`.
- **Proves:** The stored invoker resolves `BeanFactory.getBean(beanName)` at call time, selects the invocable method on the final proxy, and does not bypass advice captured after the BPP callback.
- **Fixtures/mocks:** A deliberately late test `BeanPostProcessor` creates a real Spring `ProxyFactory`/`MethodInterceptor` proxy around `TargetBean.deterministicTarget`, with registration order controlled so `SkillMethodBeanPostProcessor` observes the pre-proxy instance. Invoke the public mapped YAML metadata and assert the result plus exactly one advice entry. This arrangement must fail with the old captured-bean invoker; do not mock the target or invoker.

### 4. Public registry invariant and explicit metadata kind

#### Update `InMemoryCapabilityRegistryTest`

- **Tests:** Convert successful, duplicate, concurrent, and enumeration fixtures to `YAML_SKILL`.
- **Type/location:** Unit; existing `core/InMemoryCapabilityRegistryTest.java`.
- **Proves:** The public registry keeps its existing semantics for YAML entries.
- **Fixtures/mocks:** Metadata factory always supplies the explicit YAML kind; no mocks.

#### Add `CapabilityMetadataTest`

- **Tests:** `exposesOnlyYamlSkillCapabilityKind`; `rejectsNullCapabilityKind`.
- **Type/location:** Unit; new `core/CapabilityMetadataTest.java`.
- **Proves:** Java targets cannot be represented as public capability metadata, and construction fails immediately for a null kind.
- **Fixtures/mocks:** Minimal valid metadata with a null kind; no mocks.

### 5. Auto-configuration and namespace separation

#### Update `BifrostAutoConfigurationTests`

- **Tests:** Add `autoConfiguresSeparatePublicAndImplementationTargetRegistries`; rename `registersYamlSkillAlongsideDiscoveredSkillMethodTargets` to `publishesYamlSkillAndKeepsDiscoveredTargetInternal`; add `allowsCustomSkillImplementationTargetRegistryOverride` if the auto-config bean follows the project's normal back-off convention.
- **Type/location:** `ApplicationContextRunner`; existing `autoconfigure/BifrostAutoConfigurationTests.java`.
- **Proves:** Exactly one registry of each role exists, shared input-contract resolver wiring remains intact, the public registry lacks `deterministicTarget` when the YAML fixture is named `mapped.method.skill`, and the target registry contains `targetBean#deterministicTarget`.
- **Fixtures/mocks:** Existing mapped target configuration and application-test properties; a small custom registry only for the back-off test.

### 6. YAML mapping and public descriptors

#### Expand `YamlSkillCapabilityRegistrarTests`

- **Tests:** Keep/update `mapsDeterministicYamlSkillToDiscoveredSkillMethodTarget`, `mappedYamlSkillWithoutInputSchemaInheritsJavaDerivedContract`, `mappedDeterministicYamlSkillReturnsTransformedErrorWhenTargetThrows`, and `failsStartupWhenMappedYamlSkillReferencesUnknownTargetId`; add `buildsProviderToolIdentityFromYamlNotTargetId`, `multipleYamlSkillsCanShareOneTargetWithIndependentPublicMetadata`, and `exposesOnlySharedRegistryConstructor`; include the failing-first same-name and AOP tests above.
- **Type/location:** Application-context integration; existing `skill/YamlSkillCapabilityRegistrarTests.java`.
- **Proves:** Target lookup uses the new registry; public name/description/schema come from the YAML wrapper plus reflected target schema; unknown-target diagnostics remain actionable; two wrappers can share one invoker/contract while retaining distinct descriptions and RBAC; no compatibility constructor can silently substitute a private empty target registry.
- **Fixtures/mocks:** Add `same-name-mapped-method-skill.yaml` and two valid wrapper fixtures pointing to `targetBean#deterministicTarget` with distinct names/descriptions/roles. No provider/model calls and no target mocks.
- **Important boundary:** Keep the existing explicit mapped-schema compatibility tests green for this ticket; their removal/rejection is deferred to the next ticket.

#### Update `SkillInputContractResolverTest`

- **Tests:** Preserve existing schema compatibility tests; add or rename one focused test to `adaptsJavaReflectedContractForMappedYamlWithoutProviderTargetDescriptor` if this logic moves into a new helper.
- **Type/location:** Unit; existing `runtime/input/SkillInputContractResolverTest.java`.
- **Proves:** Contract inheritance consumes raw target schema/contract data and does not require an internal `CapabilityToolDescriptor`.
- **Fixtures/mocks:** Reflected method schema and YAML definition; no mocks.

### 7. Public implementation-type projection

#### Update `YamlSkillCatalogTests`

- **Test:** `distinguishesLlmBackedAndMappedJavaImplementationTypes`.
- **Type/location:** Catalog unit/integration; existing `skill/YamlSkillCatalogTests.java`.
- **Proves:** Trusted authoring/catalog metadata exposes `LLM_BACKED` and `MAPPED_JAVA` directly; the mapped projection retains `target_id`; callers do not inspect invoker classes.
- **Fixtures/mocks:** One existing pure YAML manifest and one mapped manifest; no model invocation.

### 8. Public invocation, visibility, security, refs, and attribution

#### Update `SkillTemplateTest`

- **Tests:** Replace `skillTemplateInvokesYamlSkillsOnly`'s illegal Java registry fixture with `rejectsImplementationTargetIdsAsUnknownYamlSkills`; keep the positive YAML invocation assertions.
- **Type/location:** Unit; existing `skillapi/SkillTemplateTest.java`.
- **Proves:** A method name and `bean#method` target ID cannot be invoked through `SkillTemplate`; the template never falls back to the internal registry.
- **Fixtures/mocks:** Public registry containing only YAML metadata; mocked execution router remains appropriate to isolate template validation.

#### Update `SkillVisibilityResolverTest`

- **Tests:** Replace `excludesNonYamlCapabilitiesEvenIfListedInAllowedSkills` with `doesNotExposeImplementationTargetIdsAsAllowedChildren`; retain RBAC tests.
- **Type/location:** Unit; existing `skill/SkillVisibilityResolverTest.java`.
- **Proves:** `allowed_skills` resolves catalog-backed YAML names only, with no internal-registry or target-ID fallback.
- **Fixtures/mocks:** Programmatic catalog/root definition containing an internal-looking ID and a valid YAML child; public registry has YAML entries only; existing mocked authentication/session patterns remain.

#### Update `CapabilityExecutionRouterTest`

- **Tests:** Remove/replace `javaCapabilityStillAcceptsDirectRefBackedObjectsOnRootInvocationPath` with `mappedYamlCapabilityAcceptsDirectRefBackedObjectsOnRootInvocationPath`; retain YAML RBAC and nested-delegation tests.
- **Type/location:** Unit; existing `core/CapabilityExecutionRouterTest.java`.
- **Proves:** Ref materialization remains reachable through the supported public mapped-YAML path, not a raw Java public capability.
- **Fixtures/mocks:** YAML metadata whose invoker delegates to a target-shaped fixture; existing router collaborators may remain mocked.

#### Update `ExecutionCoordinatorIntegrationTest`

- **Tests:** Retain end-to-end plan/tool/ref tests after removing `@SkillMethod.name`; add assertions to the main coordinator flow that public registry/tool definitions/plan tasks/journal or evidence events contain the YAML wrapper name and never `targetBean#deterministicTarget` or a former annotation alias.
- **Type/location:** Application-context integration; existing `core/ExecutionCoordinatorIntegrationTest.java`.
- **Proves:** Planning, nested invocation, binary refs, evidence, usage, and trace attribution preserve public YAML identity end to end.
- **Fixtures/mocks:** Existing fake chat clients and YAML fixtures. Do not introduce a live provider.

#### Update `ToolCallbackFactoryTest` and retain planning/evidence characterization tests

- **Tests:** Add `usesPublicYamlNameForToolDefinitionAndExecutionAttribution` if existing assertions do not cover both; update any metadata construction to explicit `YAML_SKILL`.
- **Type/location:** Unit; existing `runtime/tool/ToolCallbackFactoryTest.java`, with existing planning/evidence suites retained.
- **Proves:** Provider tool names, execution routing, evidence keys, and usage attribution remain YAML-named.
- **Fixtures/mocks:** Existing mocked coordinator/router/evidence services are appropriate; metadata must be public YAML only.

### 9. Sample behavior and public catalog

#### Update `SampleControllerTest`

- **Test:** `sampleControllerDelegatesExpensesToPublicYamlSkill`.
- **Type/location:** Unit; existing `bifrost-sample/.../SampleControllerTest.java`.
- **Proves:** `/expenses` calls `SkillTemplate.invoke("expenseLookup", Map.of())` and returns the result.
- **Fixtures/mocks:** Mock `SkillTemplate`; no application context or provider.

#### Update `SampleApplicationTests`

- **Test:** Rename `registersSkillsTocapabilityRegistry` to `publishesYamlSkillsAndKeepsExpenseTargetInternal`.
- **Type/location:** `@SpringBootTest`; existing `bifrost-sample/.../SampleApplicationTests.java`.
- **Proves:** Public registry contains `expenseLookup` and `invoiceParser`, does not contain `getLatestExpenses` or `expenseService#getLatestExpenses`, and the target registry contains `expenseService#getLatestExpenses`.
- **Fixtures/mocks:** Existing test API key and sample manifests; no live request is made.

### 10. Documentation and repository consistency checks

- Remove all `@SkillMethod(name = ...)` uses from source/tests/docs.
- Verify public invocation examples use YAML names and only mapping examples contain `beanName#methodName`.
- Verify `getLatestExpenses` remains only as the Java method/mapping target or internal diagnostic, never as a public `SkillTemplate` name.
- Verify authoring documentation states that `CapabilityRegistry` is YAML-only and implementation targets use the separate registry.
- Do not rename otherwise valid public YAML fixtures for portability in this iteration; the third prerequisite ticket owns that work.

## How to Run

### 1. Red test before implementation

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCapabilityRegistrarTests#sameNamedYamlSkillAndJavaMethodRegisterInSeparateNamespaces" test
```

Expected before the fix: non-zero exit caused by the shared-name collision. Expected after the first implementation slice: zero exit.

### 2. Core identity and discovery loop

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=SkillMethodTest,SkillImplementationTargetTest,InMemorySkillImplementationTargetRegistryTest,SkillMethodBeanPostProcessorTest,SkillMethodTargetDiscoveryIntegrationTests,InMemoryCapabilityRegistryTest,CapabilityMetadataTest" test
```

Run after each change to annotation, descriptor, registries, discovery, canonicalization, or invocation.

### 3. Auto-configuration and YAML mapping loop

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=BifrostAutoConfigurationTests,YamlSkillCapabilityRegistrarTests,YamlSkillCatalogTests,SkillInputContractResolverTest" test
```

No API keys or live models are required; these tests use context runners and local fixtures.

### 4. Public execution regression loop

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=SkillTemplateTest,SkillVisibilityResolverTest,CapabilityExecutionRouterTest,ExecutionCoordinatorIntegrationTest,ToolCallbackFactoryTest,PlanningServiceTest,EvidenceContractTests" test
```

### 5. Sample suite

```powershell
.\mvnw.cmd -pl bifrost-sample -am "-Dtest=SampleApplicationTests,SampleControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

### 6. Repository consistency checks

```powershell
rg -n '@SkillMethod\s*\([^)]*name\s*=' --glob '*.java' bifrost-spring-boot-starter bifrost-sample
rg -n -F 'skillTemplate.invoke("getLatestExpenses"' bifrost-sample/src/main bifrost-spring-boot-starter/src/main README.md bifrost-sample/README.md ai/skill-authoring
rg -n -F 'invoke("targetBean#' bifrost-sample/src/main bifrost-spring-boot-starter/src/main
rg -n 'CapabilityKind\.JAVA_METHOD' bifrost-spring-boot-starter/src/main bifrost-spring-boot-starter/src/test
```

All four commands must return no matches. `JAVA_METHOD` is obsolete and has no compatibility role in this pre-release framework.

### 7. Full verification

```powershell
.\mvnw.cmd test
```

No live model/provider validation is required for this architectural iteration. A manual `/expenses` smoke test is optional after the automated sample suite; if run, it must exercise `expenseLookup` and must not require a model call because the skill is mapped Java.

## Exit Criteria

- The failing-first same-name test is observed red on the old architecture and green on the separated registries.
- All new/updated unit, context, and sample tests pass with no flaky ordering dependency.
- Public registry tests prove YAML-only enforcement before mutation and explicit non-null metadata kind.
- Internal descriptor/registry tests prove `beanName#methodName` identity without provider-facing tool descriptors.
- Interface, JDK proxy, class proxy, bridge, synthetic, and overload cases behave exactly as specified; overload failure is atomic and actionable.
- Real Spring advice executes exactly once around mapped invocation through the final proxy.
- Equal Java/YAML names and multiple YAML wrappers per target work without collision.
- The registrar exposes only its shared-registry constructor; the constructor-surface test was observed red before the obsolete fallback was removed and green afterward.
- Mapped contract inheritance, explicit-schema compatibility for this iteration, exception transformation, runtime refs, and RBAC remain green.
- `SkillTemplate`, allowed children, plans, tool definitions, evidence, journals, traces, and usage expose YAML names only.
- Public metadata distinguishes `LLM_BACKED` from `MAPPED_JAVA` and preserves mapped target ID for trusted tooling.
- `/expenses` delegates to `expenseLookup`; the sample public registry contains no raw Java method name or target ID.
- Focused starter, sample, and full Maven suites pass.
- Repository consistency searches show no remaining `@SkillMethod.name` use in Java sources/fixtures or raw target invocation examples in product documentation and runtime source. Historical ticket/research examples may continue to describe the pre-fix defect.
- No mapped-manifest simplification, portable YAML-name validation, aliasing, raw Java invocation, or catalog endpoint has leaked into this iteration.

## Post-Review Regression Coverage

- Blank/whitespace mapping IDs normalize to LLM-backed metadata and route through the coordinator path.
- Interface-only `@ToolParam` descriptions and optionality survive JDK proxy discovery.
- A competing broader interface overload cannot capture invocation of the annotated target method.
- Interface and implementation parameter renames rewrite both schema properties and required entries.
- Incompatible annotated interface contracts fail startup, while equivalent duplicate contracts remain supported.
- Referenced lazy singleton and prototype targets register before YAML mapping resolution and remain repeatedly invocable.
- Public metadata rejects provider tool names that differ from the YAML name.
- Custom target/public registry mismatches fail before execution.
- Catalog callers cannot mutate stored name, RBAC, or mapping state through returned manifests.
- Catalog callers cannot mutate stored linter, input-schema, or output-schema state through convenience accessors.
- Serialization failures expose the YAML wrapper name publicly while retaining the internal target ID only in the cause.
- Evidence validation does not infer implementation identity from `#` while portable public-name syntax and evidence reachability remain deferred.
