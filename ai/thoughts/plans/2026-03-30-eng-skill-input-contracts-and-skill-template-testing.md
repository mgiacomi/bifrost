# Skill Input Contracts and SkillTemplate Testing Plan

## Change Summary
- Add optional YAML `input_schema` support using the existing schema manifest subset.
- Introduce a runtime `SkillInputContract` plus shared input validation reused across startup, router execution, nested YAML execution, `SkillTemplate`, and step-loop validation.
- Add public `SkillTemplate` / `SkillExecutionView` APIs and migrate sample usage away from raw `executionRouter.execute(..., Map.of(...))`.
- Upgrade step-loop validation and prompt rendering to use concrete input contracts when available.

## Impacted Areas
- [`YamlSkillCatalogTests.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\skill\YamlSkillCatalogTests.java)
- [`YamlSkillCapabilityRegistrarTests.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\skill\YamlSkillCapabilityRegistrarTests.java)
- [`SkillMethodBeanPostProcessorTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\core\SkillMethodBeanPostProcessorTest.java)
- [`CapabilityExecutionRouterTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\core\CapabilityExecutionRouterTest.java)
- [`ExecutionCoordinatorIntegrationTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\core\ExecutionCoordinatorIntegrationTest.java)
- [`StepActionValidatorTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\runtime\step\StepActionValidatorTest.java)
- [`StepPromptBuilderTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\runtime\step\StepPromptBuilderTest.java)
- [`BifrostAutoConfigurationTests.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\autoconfigure\BifrostAutoConfigurationTests.java)
- [`SampleControllerTest.java`](C:\opendev\code\bifrost\bifrost-sample\src\test\java\com\lokiscale\bifrost\sample\SampleControllerTest.java)
- Test fixtures under [`src/test/resources/skills`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\resources\skills)

## Risk Assessment
- High risk: startup validation drift for mapped YAML skills, especially structural parity and `format` mismatch rules.
- High risk: inconsistent validation behavior between root Java invocation, mapped YAML invocation, nested YAML invocation, and step-loop `CALL_TOOL`.
- High risk: accidental tightening of pure YAML skills with no `input_schema`, which must remain permissive.
- High risk: `SkillTemplate` broadening into a generic capability facade instead of remaining YAML-only.
- Medium risk: observer/session lifecycle behavior causing partial journals, swallowed exceptions, or callbacks before finalization.
- Medium risk: prompt rendering becoming too noisy or diverging from validator behavior after tool-argument failures.

## Existing Test Coverage
- [`YamlSkillCatalogTests.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\skill\YamlSkillCatalogTests.java) already covers manifest startup failures, output schema validation, evidence contract validation, warning heuristics, and classpath fixture loading via `ApplicationContextRunner`.
- [`CapabilityExecutionRouterTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\core\CapabilityExecutionRouterTest.java) already covers nested YAML routing, state restoration, and authorization checks.
- [`StepActionValidatorTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\runtime\step\StepActionValidatorTest.java) already covers current top-level required-argument checks and permissive generic object behavior.
- [`StepPromptBuilderTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\runtime\step\StepPromptBuilderTest.java) is the natural place for compact/verbose argument-shape prompt assertions.
- [`BifrostAutoConfigurationTests.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\autoconfigure\BifrostAutoConfigurationTests.java) is the existing wiring anchor for new public beans.
- [`SampleControllerTest.java`](C:\opendev\code\bifrost\bifrost-sample\src\test\java\com\lokiscale\bifrost\sample\SampleControllerTest.java) is the current sample-facing regression harness.

### Gaps
- No current tests assert `input_schema` parsing, startup validation, or mapped inheritance/compatibility for input contracts.
- No current tests exercise a framework-owned `SkillInputContract` or shared validator.
- No current tests cover `SkillTemplate`, `SkillExecutionView`, observer timing, null-input semantics, or YAML-only resolution.
- No current tests cover nested required fields, enum mismatch, unknown-field rejection, or deterministic date coercion in step-loop validation.
- No current tests assert prompt escalation from compact to more verbose tool-argument guidance.

## Bug Reproduction / Failing Test First
- Type: integration
- Location: [`CapabilityExecutionRouterTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\core\CapabilityExecutionRouterTest.java) or a new `SkillTemplateTest.java` under `com.lokiscale.bifrost.skillapi`
- Arrange/Act/Assert outline:
- Arrange a contract-backed YAML skill whose effective input contract requires a top-level `payload` string.
- Invoke the root execution path with `{}` or `null`.
- Assert that validation fails before any mission frame/session work begins and that no observer callback fires.
- Expected failure (pre-fix):
- Current code accepts or normalizes generic map input too permissively because there is no reusable framework-owned input-contract validator on the public/router path.

## Tests to Add/Update
### 1) `loadsYamlSkillInputSchemaWhenPresent`
- Type: unit
- Location: [`YamlSkillCatalogTests.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\skill\YamlSkillCatalogTests.java)
- What it proves: YAML-only skills can declare `input_schema`, it is parsed with the existing manifest model, and the root schema must be `object`.
- Fixtures/data: new valid fixture under [`skills/valid`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\resources\skills\valid)
- Mocks: none beyond existing `ApplicationContextRunner`

### 2) `failsStartupWhenInputSchemaUsesUnsupportedKeywordOrNonObjectRoot`
- Type: unit
- Location: [`YamlSkillCatalogTests.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\skill\YamlSkillCatalogTests.java)
- What it proves: `input_schema` reuses the same subset rules as `output_schema`, including unsupported-keyword rejection and root-object enforcement.
- Fixtures/data: new invalid YAML fixtures under [`skills/invalid`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\resources\skills\invalid)
- Mocks: none

### 3) `mappedYamlSkillWithoutInputSchemaInheritsJavaDerivedContract`
- Type: unit
- Location: [`YamlSkillCapabilityRegistrarTests.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\skill\YamlSkillCapabilityRegistrarTests.java)
- What it proves: mapped YAML skills inherit the target's reflected contract automatically when `input_schema` is absent.
- Fixtures/data: mapped YAML fixture and existing `@SkillMethod` test target
- Mocks: minimal registry/context scaffolding already used by registrar tests

### 4) `mappedYamlSkillWithMismatchedInputSchemaFailsStartup`
- Type: unit
- Location: [`YamlSkillCatalogTests.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\skill\YamlSkillCatalogTests.java)
- What it proves: explicit mapped YAML `input_schema` must match the reflected Java contract structurally, including `required`, nesting, enums, `additionalProperties`, `items`, and `format`.
- Fixtures/data: invalid mapped YAML fixtures, including a dedicated `format` mismatch case
- Mocks: `TargetBeanConfiguration` pattern already used in `YamlSkillCatalogTests`

### 5) `validatesAndNormalizesInputContractCases`
- Type: unit
- Location: new [`SkillInputValidatorTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\runtime\input\SkillInputValidatorTest.java)
- What it proves: the shared validator rejects missing required fields, null required values, nested required-field gaps, enum mismatches, type mismatches, array-vs-object mismatches, and unknown fields when `additionalProperties: false`.
- Fixtures/data: contract builders or inline schema objects; no Spring context required
- Mocks: none

### 6) `normalizesSupportedDateFormatsAndRejectsUnsupportedDates`
- Type: unit
- Location: new [`SkillInputValidatorTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\runtime\input\SkillInputValidatorTest.java)
- What it proves: `format: date` accepts only `YYYY-MM-DD`, `MM/DD/YYYY`, `M/D/YYYY`, and `MM-DD-YYYY`, normalizes to ISO, and rejects ambiguous/unsupported values without truncating datetimes.
- Fixtures/data: inline examples for accepted and rejected dates
- Mocks: none

### 7) `genericContractRemainsPermissive`
- Type: unit
- Location: new [`SkillInputValidatorTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\runtime\input\SkillInputValidatorTest.java)
- What it proves: pure YAML skills without `input_schema` are not rejected based on guessed structure; validator may normalize empties but does not invent required fields.
- Fixtures/data: generic-object contract plus empty/null map inputs
- Mocks: none

### 8) `routerUsesNormalizedInputAcrossRootAndNestedExecution`
- Type: integration
- Location: [`CapabilityExecutionRouterTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\core\CapabilityExecutionRouterTest.java)
- What it proves: router-level execution uses the shared contract/validation path for root Java invocation, mapped YAML invocation, and nested YAML execution.
- Fixtures/data: existing session setup plus contract-backed capabilities
- Mocks: `ExecutionCoordinator`, `ExecutionStateService`, and invokers as appropriate

### 9) `nestedYamlValidationFailsBeforeMissionExecutionBegins`
- Type: integration
- Location: [`CapabilityExecutionRouterTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\core\CapabilityExecutionRouterTest.java)
- What it proves: invalid nested YAML input is rejected before `ExecutionCoordinator.execute(...)` or mission frame opening.
- Fixtures/data: contract-backed child YAML capability requiring `payload`
- Mocks: verify coordinator is never called

### 10) `stepActionValidatorDelegatesToSharedValidatorForConcreteContracts`
- Type: unit
- Location: [`StepActionValidatorTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\runtime\step\StepActionValidatorTest.java)
- What it proves: step-loop validation now rejects nested required-field omissions, enum mismatches, type mismatches, and unknown fields for concrete contracts while staying permissive for generic contracts.
- Fixtures/data: JSON schemas/contracts with nested objects and enums
- Mocks: existing `ToolCallback` helpers

### 11) `stepPromptBuilderRendersCompactAndVerboseArgumentGuidance`
- Type: unit
- Location: [`StepPromptBuilderTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\runtime\step\StepPromptBuilderTest.java)
- What it proves: first-pass prompt guidance is compact, retry guidance is more verbose, and both are deterministic for the same schema.
- Fixtures/data: representative shallow and nested contracts
- Mocks: existing step prompt test harness

### 12) `skillTemplateInvokesYamlSkillsOnly`
- Type: unit
- Location: new [`SkillTemplateTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\skillapi\SkillTemplateTest.java)
- What it proves: `SkillTemplate` resolves YAML skills only and rejects direct use with non-YAML capabilities.
- Fixtures/data: mocked `CapabilityRegistry` entries for YAML and non-YAML capabilities
- Mocks: `CapabilityRegistry`, `CapabilityExecutionRouter`, `BifrostSessionRunner`, `ObjectMapper`, validator/resolver

### 13) `skillTemplateNullInputAndObserverLifecycle`
- Type: unit
- Location: new [`SkillTemplateTest.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\skillapi\SkillTemplateTest.java)
- What it proves: object overload rejects `null`, map overload only normalizes `null` when allowed, invalid input creates no session or observer callback, observer sees finalized `sessionId` + `executionJournal`, and observer exceptions propagate.
- Fixtures/data: generic and explicit contracts, observer spy, fake execution journal
- Mocks: same as above

### 14) `autoConfigurationExposesSkillTemplateBean`
- Type: unit
- Location: [`BifrostAutoConfigurationTests.java`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\test\java\com\lokiscale\bifrost\autoconfigure\BifrostAutoConfigurationTests.java)
- What it proves: the starter exposes `SkillTemplate` and any required validator/resolver beans without extra manual wiring.
- Fixtures/data: existing auto-config test context
- Mocks: none beyond existing context runner pattern

### 15) `sampleControllerDelegatesToSkillTemplate`
- Type: integration
- Location: [`SampleControllerTest.java`](C:\opendev\code\bifrost\bifrost-sample\src\test\java\com\lokiscale\bifrost\sample\SampleControllerTest.java)
- What it proves: sample execution endpoints use `SkillTemplate`, duplicate-invoice behavior remains stable, and any retained sample-only endpoint no longer reimplements routing/session logic directly.
- Fixtures/data: sample app context and existing sample skills
- Mocks: `SkillTemplate` bean or slice-level spy depending on current test style

## How to Run
- Compile starter first: `./mvnw -pl bifrost-spring-boot-starter -DskipTests compile`
- Run manifest/catalog tests: `./mvnw -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests,YamlSkillCapabilityRegistrarTests,SkillMethodBeanPostProcessorTest test`
- Run validation/router tests: `./mvnw -pl bifrost-spring-boot-starter -Dtest=SkillInputValidatorTest,CapabilityExecutionRouterTest test`
- Run step-loop tests: `./mvnw -pl bifrost-spring-boot-starter -Dtest=StepActionValidatorTest,StepPromptBuilderTest,StepLoopMissionExecutionEngineTest test`
- Run public API/wiring tests: `./mvnw -pl bifrost-spring-boot-starter -Dtest=SkillTemplateTest,BifrostAutoConfigurationTests test`
- Run sample regression tests: `./mvnw -pl bifrost-sample -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=SampleControllerTest,SampleApplicationTests" test`
- Run broader integration suite before merge: `./mvnw test`

## Exit Criteria
- [ ] A failing test exists and fails pre-fix for at least one contract-backed invalid-input path.
- [ ] Startup validation covers valid `input_schema`, invalid `input_schema`, mapped inheritance, and explicit mapped-schema mismatch including `format`.
- [ ] Shared validator tests cover required fields, nested objects, enums, type mismatches, unknown fields, generic permissiveness, stable issue codes, and deterministic date normalization.
- [ ] Router/integration tests prove root Java invocation, mapped YAML invocation, and nested YAML invocation all use the same normalized input-contract path.
- [ ] `SkillTemplate` tests cover YAML-only resolution, null-input semantics, pre-session validation failure, finalized observer delivery, and observer exception propagation.
- [ ] Step-loop tests prove concrete contract guidance/validation improved while generic contracts remain permissive.
- [ ] Sample tests show the public example path uses `SkillTemplate` and duplicate-invoice behavior remains unchanged from a user point of view.
- [ ] All targeted tests pass post-fix.
- [ ] Manual verification of the sample path and prompt behavior is complete if any prompt-shape assertions remain hard to express purely in automated tests.
