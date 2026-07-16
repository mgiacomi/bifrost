# Reduce the Bifrost Spring Boot Starter Public Surface Testing Plan

## Change Summary

- Replace the starter's accidentally broad Java surface with exactly seven public Application API types under `com.lokiscale.bifrost.api`.
- Keep only four separately classified Spring integration types under `com.lokiscale.bifrost.autoconfigure`; classify every other production type as internal and move it under `com.lokiscale.bifrost.internal...`.
- Remove all Bifrost-specific SPIs, bean-replacement promises, and `@ConditionalOnMissingBean` backoff behavior.
- Replace the leaked `ExecutionJournal`/`JournalEntry`/`JsonNode` graph with immutable `SkillExecutionEvent` values and replace internal validation issues with the three-field public `SkillInputValidationIssue`.
- Capture the current Spring Security authentication at the `SkillTemplate` boundary, preserve validation and authorization exceptions, and normalize other internal runtime failures to a safe `SkillException`.
- Preserve documented configuration, YAML manifests, mapped targets, attachments, provider selection, metrics integration, current trace storage/CLI behavior, and success-only observer semantics.
- Update repository consumers atomically. Old packages, bean overrides, and public implementation shapes are approved removals and must not receive compatibility tests or shims.

## Impacted Areas

- Public facade and values: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/api/`.
- Facade implementation and translation: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skillapi/`.
- Session authentication and authorization: internal `BifrostSessionRunner`, `AccessGuard`, capability routing, and visibility resolution.
- Current diagnostic projection: internal `ExecutionJournalProjector`, journal DTOs, trace records, and public `SkillExecutionEvent` mapping.
- Spring Boot integration: `BifrostAutoConfiguration`, configuration binding/metadata, named AI connections, metrics, advisors, executors, and framework-owned runtime beans.
- Package and visibility boundaries: every production and test Java package in `bifrost-spring-boot-starter`.
- Repository consumer boundary: `bifrost-sample` production code, controller tests, catalog-oriented tests, and response DTO/assertions.
- Current trace tooling: starter NDJSON writer/reader/projector tests and `bifrost-cli` Go tests.
- Documentation evidence: README public-surface descriptions and source anchors in `ai/skill-authoring/mental-model.md`.

## Risk Assessment

### High risk

- **Incomplete classification:** a public implementation type, constructor, generic argument, record component, exception, or annotation can silently retain an unintended contract.
- **False SPI retention:** a remaining `@ConditionalOnMissingBean` or backoff test could continue advertising `AccessGuard`, `SkillChatModelResolver`, advisors, registries, metrics recorders, or another runtime seam as application-replaceable.
- **Facade behavior drift:** moving the facade could alter object/map overload behavior, null-input handling, YAML-only lookup, observer timing, attachment inputs, or returned string conversion.
- **Authorization regression:** `SecurityContextHolder` authentication could be read too late, omitted, caller-controlled, lost before nested/asynchronous work, or accidentally converted into a public parameter.
- **Exception leakage:** internal exception types/messages could escape; conversely, the implementation could incorrectly wrap `SkillInputValidationException`, `AccessDeniedException`, an existing `SkillException`, an application observer exception, or an `Error`.
- **Diagnostic regression:** event selection, ordering, repeated events, failure visibility, redaction, frame/route context, or detail values could change while replacing the leaked DTO graph.
- **Shallow immutability:** copying only the outer diagnostic map/list could still allow nested application-facing values to mutate.
- **Spring graph regression:** removing 39 backoff conditions and narrowing visibility could prevent auto-configuration, create ambiguous dependencies, duplicate executors, lose lifecycle cleanup, or disable optional Micrometer/ObjectMapper integration.

### Medium risk

- Configuration prefixes, defaults, validation paths, metadata, provider driver behavior, or named connection reuse could change during package moves.
- Sample tests may continue to depend on internal catalogs and registries, defeating the consumer-boundary goal even if production sample code is clean.
- Test-only constructor pressure could keep internal declarations public.
- The trace CLI could become incoherent if the implementation accidentally changes the persisted NDJSON shape while changing only the application projection.
- Documentation could claim RBAC or diagnostic behavior without focused test evidence, or retain dead source links after relocation.

### Edge cases

- Null object input versus null map input for generic, empty, and contract-backed skills.
- Empty and nested diagnostic detail maps/lists, scalar or textual payloads, null frame/route values, and repeated legitimate events.
- Validation issues with blank/root paths and rejected values that must not reach the public issue.
- Present, absent, anonymous, matching-role, and mismatched-role authentication.
- Runtime failure before a frame opens, authorization failure, existing `SkillException`, observer failure after successful execution, and `Error` propagation.
- Application contexts with and without `MeterRegistry`, with an application `ObjectMapper`, and with unrelated `ExecutorService` beans.
- Technically public internal types needed across internal subpackages: each must be explicitly allowlisted with a nonblank mechanical reason.

### Contract and compatibility scope

| Canonical category | Test obligation |
| --- | --- |
| Application API | Protect the exact seven-type package, public signatures, invocation overloads, mapped `@SkillMethod` behavior, validation/authorization behavior, observer semantics, attachment values, and safe exceptions. |
| Supported SPI | The allowlist is empty. Prove there is no `.spi` package, no supported replacement bean, and no Bifrost `@ConditionalOnMissingBean`. Remove tests asserting custom-bean backoff. |
| Configuration and manifest contracts | Preserve current `bifrost.*`, `execution-trace.*`, YAML syntax/defaults/validation, model connections, attachments, mappings, and `rbac_roles` behavior with existing focused suites. |
| Persisted or serialized contracts | No deliberately durable format changes. Keep current writer/reader/CLI tests green, but do not add historical schema fixtures or adapters. |
| Ephemeral diagnostic formats | Protect current-run event usefulness, selection, ordering, failures, frame/route context, existing redaction, and writer/projector coherence. Do not require the old public journal DTO graph. |
| Internal or accidentally exposed implementation | Move and narrow atomically. Update same-package unit tests; delete obsolete public-shape and override expectations instead of testing both old and new behavior. |

## Existing Test Coverage

### Coverage to retain and relocate as needed

- `skillapi/SkillTemplateTest` covers YAML-only lookup, object/map normalization, null inputs, observer timing, validation rejection, and observer exception propagation.
- `core/BifrostSessionRunnerTest.seedsAuthenticationIntoNewSessionWhenProvided` proves the session runner's authenticated overload stores identity; it does not prove the facade calls that overload.
- `security/DefaultAccessGuardTest`, `skill/SkillVisibilityResolverTest`, and `core/CapabilityExecutionRouterTest` cover root/nested authorization and session-authentication fallback.
- `runtime/trace/ExecutionJournalProjectorTest` covers sanitization, failure records, nested failure summaries, ordering/repetition, and rejection of legacy message inference.
- `runtime/trace/ExecutionJournalProjectionContractTest` covers representative projected event selection and omission of raw-only trace records.
- `runtime/trace/ExecutionTraceContractTest`, writer/reader/handle tests, and boundary-cleanup tests cover current trace accuracy, failure redaction, and current-version coherence.
- `autoconfigure/BifrostAutoConfigurationTests` covers discovery, configuration, mapped invocation, model connections, advisors, registries, and the runtime graph.
- `autoconfigure/BifrostPropertiesTest`, `ConfigurationMetadataTest`, connection protocol/factory tests, and sensitive-data tests protect documented configuration and provider behavior.
- `autoconfigure/ConnectionImplementationVisibilityTest` is a useful small visibility precedent that should be subsumed by the complete classification test.
- `runtime/usage/MicrometerUsageMetricsRecorderTest` protects emitted meter behavior.
- `annotation/SkillMethodTest`, target-discovery tests, YAML catalog/registrar tests, and manifest fixtures protect mapped target and manifest behavior.
- `bifrost-sample` context, controller, deterministic service, and catalog tests cover representative repository usage, but several currently inspect internal registries/catalogs/properties directly.

### Gaps

- No complete exact allowlist for public API, Spring integration types, or technically public internal types.
- No recursive public-signature check covering generic arguments, records, declared exceptions, and annotations.
- No general prohibition on `.spi` packages or Bifrost `@ConditionalOnMissingBean`.
- No test proving `SkillTemplate` captures `SecurityContextHolder` authentication.
- No complete facade exception-normalization matrix.
- No public DTO tests for deep defensive immutability or absence of Jackson/internal values.
- Existing projection tests assert internal journal DTOs rather than the new public event representation.
- Existing auto-configuration tests explicitly preserve obsolete resolver, advisor, and implementation-registry backoff behavior.
- No rule preventing sample production code from importing internal or auto-configuration Bifrost types.

## Bug Reproduction / Failing Test First

### 1. `capturesCurrentSecurityContextAuthenticationForRootInvocation`

- **Type:** Unit.
- **Location:** Initially add to the existing `skillapi/SkillTemplateTest`; relocate to `internal/skillapi/DefaultSkillTemplateTest` with the implementation package move.
- **Arrange:** Build the current `DefaultSkillTemplate` with a real `BifrostSessionRunner`, valid YAML capability metadata, and a mocked execution router whose answer reads `session.getAuthentication()`. Put an authenticated token with `ROLE_ALLOWED` in `SecurityContextHolder`. Clear the context in `finally`/`@AfterEach`.
- **Act:** Invoke the map overload through the `SkillTemplate` interface.
- **Assert:** The router observes the same authentication principal and authority from the session.
- **Expected failure pre-fix:** The session has no authentication because current `DefaultSkillTemplate` calls `callWithNewSession(session -> ...)` rather than the authenticated overload.
- **Mocking:** Mock only registry/router/input-validator collaborators; use the real session runner so the test exercises the actual handoff.
- **Contract classification:** Application API and trusted runtime authorization behavior.
- **Compatibility expectation:** Protected documented `rbac_roles` path.

### 2. First boundary red test

- **Type:** Architecture/contract.
- **Location:** `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java`.
- **Arrange:** Add the ArchUnit test dependency, scan production `com.lokiscale.bifrost` classes, and define the exact seven API plus four Spring-integration allowlists.
- **Act:** Run `exposesOnlyClassifiedTopLevelTypes()` and `applicationApiSignaturesDoNotLeakImplementationTypes()` against the current tree.
- **Assert:** Only classified types are externally accessible and API signatures recursively contain only approved API/JDK types.
- **Expected failure pre-fix:** The current tree contains approximately 165 public types and `SkillExecutionView`/`SkillInputValidationException` leak internal DTOs. Failure output must name representative offenders.
- **Contract classification:** Application API plus Internal or accidentally exposed implementation.
- **Compatibility expectation:** Approved removal; the old surface must not be preserved.

Record both failures before production changes. Do not require every later test to compile against the pre-change package names; the authentication test is the minimal behavioral red test, and the architecture test is the minimal boundary red test.

## Tests to Add or Update

### 1. Facade invocation, authentication, and exception matrix

- **Names:**
  - `capturesCurrentSecurityContextAuthenticationForRootInvocation`
  - `invokesWithoutAuthenticationWhenSecurityContextIsEmpty`
  - `preservesSkillInputValidationExceptionAndPublicIssues`
  - `preservesAccessDeniedExceptionInstance`
  - `preservesExistingSkillExceptionInstance`
  - `wrapsOtherRuntimeFailureWithSafeSkillExceptionAndCause`
  - `doesNotCatchError`
  - `callsObserverOnlyAfterSuccessfulExecution`
  - `doesNotCallObserverAfterValidationAuthorizationOrRuntimeFailure`
  - `propagatesApplicationObserverFailureAfterSuccessfulExecution`
  - existing YAML-only lookup, object/map overload, null-input, and result-string tests.
- **Type:** Unit, with focused facade integration for root/nested RBAC.
- **Locations:**
  - Internal constructor-level tests: `src/test/java/com/lokiscale/bifrost/internal/skillapi/DefaultSkillTemplateTest.java`.
  - Public facade/context tests: `src/test/java/com/lokiscale/bifrost/api/SkillTemplateContractTest.java`.
- **What it proves:** Authentication is captured once at root invocation and stored in the session; no public authentication parameter exists; structured input and authorization failures cross unchanged; internal runtime messages do not leak; the safe message is exactly `Skill '<name>' execution failed.`; the cause remains available for diagnostics; observers remain success-only and application callback failures are not mistaken for internal framework failures.
- **Fixtures/data:** Valid YAML capability metadata, generic and contract-backed inputs, authenticated tokens with matching/mismatched authorities, a protected root plus nested protected child, and deterministic router results/failures.
- **Mocks:** Mockito for registry, router, validator, and observer in unit tests. Use the real session runner and real access guard/router path for RBAC integration.
- **Contract classification:** Application API; Configuration and manifest contracts for `rbac_roles`.
- **Compatibility expectation:** Protected path, except internal exception shapes are deliberately removed.

### 2. Public value shape and deep immutability

- **Names:**
  - `skillExecutionViewDefensivelyCopiesEvents`
  - `skillExecutionEventDeeplyCopiesDetails`
  - `skillExecutionEventSupportsNullFrameAndRoute`
  - `skillInputValidationExceptionDefensivelyCopiesIssues`
  - `skillInputValidationIssueExposesOnlyPathCodeAndMessage`
  - `skillExceptionHasOnlyMessageAndMessageCauseConstructors`
- **Type:** Unit/reflection contract.
- **Location:** `src/test/java/com/lokiscale/bifrost/api/ApplicationApiValueTest.java`.
- **What it proves:** Public values cannot be mutated through original or returned nested maps/lists; only standard Java scalar/list/map values cross the boundary; validation rejected values are absent; public exception constructors do not introduce internal types or a premature failure taxonomy.
- **Fixtures/data:** Mutable outer and nested `ArrayList`/`LinkedHashMap` inputs containing strings, numbers, booleans, null where supported, nested lists, and nested maps.
- **Mocks:** None.
- **Contract classification:** Application API and Ephemeral diagnostic formats.
- **Compatibility expectation:** New protected public shape; approved removal of journal/Jackson/rejected-value exposure.

### 3. Diagnostic mapping and current-run coherence

- **Names:** Update existing projector/contract cases and add:
  - `mapsSelectedJournalEntriesToPublicEventsInOrder`
  - `mapsEnumsToStringsAndCopiesFrameAndRoute`
  - `convertsObjectArrayAndScalarPayloadsWithoutJsonNode`
  - `preservesExistingSensitiveFieldRedaction`
  - `publicEventDetailsAreDeeplyImmutable`
  - retain failure summary, repeated event, ignored raw record, and no-legacy-message-inference cases.
- **Type:** Unit plus current-version projection contract.
- **Locations:** Relocated internal `ExecutionJournalProjectorTest`, `ExecutionJournalProjectionContractTest`, and `internal/skillapi/SkillExecutionViewMapperTest` if mapping is separated.
- **What it proves:** The new public events preserve the current selected information, order, duplicates, failure visibility, level/type meaning, timestamp, frame, route, and existing redaction without returning raw trace records or internal/Jackson objects. Scalar/text details use the chosen stable `message` or `value` key.
- **Fixtures/data:** The representative trace stream already used by `ExecutionJournalProjectionContractTest`, sensitive keys covered by existing redaction tests, nested payloads, null payloads, and ignored record types.
- **Mocks:** None; use real projector/mapper and `ObjectMapper` conversion.
- **Contract classification:** Ephemeral diagnostic formats.
- **Compatibility expectation:** Current-run diagnostic coherence. Do not assert old `ExecutionJournal`, `JournalEntry`, enum, or `JsonNode` API compatibility and do not add historical fixtures.

### 4. Exact public surface and recursive signature enforcement

- **Names:**
  - `apiPackageContainsExactlySevenApprovedPublicTypes`
  - `autoconfigurePackageContainsExactlyFourIntegrationTypes`
  - `everyExternallyAccessibleTopLevelTypeIsClassified`
  - `technicallyPublicInternalTypesHaveNonblankReasons`
  - `apiSignaturesRecursivelyExcludeInternalAndAutoconfigureTypes`
  - `noSupportedSpiPackageOrTypeExists`
- **Type:** Architecture/reflection.
- **Location:** `src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java`.
- **What it proves:** The closed allowlists are exact; generic arguments, arrays, record components, public/protected fields, constructors, methods, parameters, returns, declared exceptions, and annotations cannot leak a Bifrost type outside the API package; internal technical-public exceptions are deliberate and explained.
- **Fixtures/data:** Explicit sets for the seven API and four integration class names plus `Map<String,String>` for technically public internals.
- **Mocks:** None.
- **Contract classification:** Application API; Supported SPI (empty); Internal or accidentally exposed implementation.
- **Compatibility expectation:** Protect the new boundary and require complete removal of old exposure.

### 5. Empty Spring replacement surface

- **Names:**
  - `bifrostBeanFactoriesDoNotUseConditionalOnMissingBean`
  - `supportedBifrostBeanOverrideAllowlistIsEmpty`
  - `beanMethodsArePackagePrivate`
  - `accessGuardAndChatModelResolverAreInternalFrameworkOwnedTypes`
  - `autoConfigurationBuildsOneCompleteFrameworkGraph`
- **Type:** Architecture plus Spring context integration.
- **Locations:**
  - `src/test/java/com/lokiscale/bifrost/architecture/BifrostAutoConfigurationBoundaryTest.java`.
  - Updated `src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`.
- **What it proves:** No configuration class or bean method advertises Bifrost backoff; all framework-owned dependencies are present in an ordinary context; narrowed bean-method visibility remains discoverable; `AccessGuard` and `SkillChatModelResolver` are not supported application extension points.
- **Fixtures/data:** `ApplicationContextRunner` configurations for minimal mapped-skill context and full configured model context.
- **Mocks:** Existing fake/mock chat model and factory fixtures only where provider I/O would otherwise occur.
- **Contract classification:** Supported SPI (explicitly empty) and Internal or accidentally exposed implementation.
- **Compatibility expectation:** Approved removal of every old replacement path.

### 6. Standard ecosystem integration remains optional and deterministic

- **Names:**
  - `usesApplicationObjectMapperWithoutCreatingBifrostOverridePoint`
  - `usesMicrometerRecorderWhenMeterRegistryExists`
  - `usesNoopUsageRecorderWhenMeterRegistryIsAbsent`
  - `unrelatedExecutorServiceDoesNotReplaceMissionExecutor`
  - `missionExecutorIsNamedAndClosedWithContext`
  - existing provider registration and one-model-per-named-connection tests.
- **Type:** Spring context integration.
- **Location:** Updated `BifrostAutoConfigurationTests`, `NamedAiConnectionRegistryTests`, and `MicrometerUsageMetricsRecorderTest` in their relocated packages.
- **What it proves:** Removing Bifrost replacement seams does not remove ordinary Spring/Jackson/Micrometer integration, create ambiguous executor injection, leak threads, or duplicate named `ChatModel` construction.
- **Fixtures/data:** Contexts with/without `SimpleMeterRegistry`, a distinct application `ObjectMapper`, an unrelated executor bean, and existing multi-provider test properties.
- **Mocks:** Provider factory mocks; no live network calls.
- **Contract classification:** Configuration and manifest contracts plus internal wiring.
- **Compatibility expectation:** Protected standard integration behavior, not a Bifrost SPI.

### 7. Representative application consumption

- **Names:**
  - `sampleProductionUsesOnlySupportedBifrostApi`
  - `contextLoadsThroughStarterAutoConfiguration`
  - `mappedSkillInvokesThroughSkillTemplate`
  - `resourceAttachmentInvokesThroughSkillTemplate`
  - `successfulResponseContainsSessionIdAndExecutionEvents`
  - existing controller and deterministic service behavior cases.
- **Type:** Architecture, compile-time consumer, and Spring Boot integration.
- **Locations:**
  - New `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SupportedApiUsageArchitectureTest.java`.
  - Updated `SampleApplicationTests` and controller tests.
- **What it proves:** Sample production code imports Bifrost types only from `com.lokiscale.bifrost.api`; ordinary injection, mapped invocation, attachment values, and diagnostic responses work without direct catalog/registry/property/journal access.
- **Fixtures/data:** Existing sample YAML, deterministic mapped targets, controller request fixtures, and a classpath `Resource` attachment.
- **Mocks:** Existing mocked application services/provider clients. Do not inspect internal catalogs to prove public behavior.
- **Contract classification:** Application API and Configuration and manifest contracts.
- **Compatibility expectation:** Protected ordinary developer experience; internal sample-test dependencies are approved removals.

### 8. Configuration, manifests, mapping, and providers remain coherent

- **Names:** Retain existing `BifrostPropertiesTest`, `ConfigurationMetadataTest`, `ConnectionProtocolTest`, `SensitiveConnectionDataRedactionTest`, YAML catalog/definition/registrar tests, `SkillMethodTest`, and target-discovery integration tests under relocated packages.
- **Type:** Unit and integration regression.
- **Location:** Existing tests moved with their production packages; configuration tests remain under `com.lokiscale.bifrost.autoconfigure`.
- **What it proves:** Package/public-surface changes do not alter property keys/defaults/validation paths, metadata, YAML accepted shapes, mapped target discovery, public YAML identity, model selection, provider protocols, or sensitive connection redaction.
- **Fixtures/data:** Existing `application-test.yml` and valid/invalid skill fixture trees; update imports only unless a test encoded an approved internal surface.
- **Mocks:** Existing provider mocks and deterministic Spring beans.
- **Contract classification:** Configuration and manifest contracts.
- **Compatibility expectation:** Protected paths.

### 9. Internal behavior survives package and visibility narrowing

- **Names:** Retain the internal core, runtime, planner, quota, evidence, linter, output-schema, attachment, VFS, security, and trace behavioral tests after moving them into matching `.internal...` packages.
- **Type:** Unit and internal integration.
- **Location:** Corresponding `src/test/java/com/lokiscale/bifrost/internal...` packages.
- **What it proves:** Repackaging and constructor narrowing do not alter runtime behavior; tests can use package-private access without forcing production declarations public.
- **Fixtures/data:** Existing fixtures and helpers moved atomically.
- **Mocks:** Preserve existing mocking style, but do not keep an interface/public constructor solely for Mockito.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** Behavior regression coverage only; no source-compatibility promise for internal shapes.

### 10. Tests and expectations to remove or rewrite

- **Remove:** `customChatModelResolverBacksOffDefaultConnectionConstruction`.
- **Remove/rewrite:** `allowsCustomSkillAdvisorResolverOverride`, `backsOffWhenApplicationProvidesImplementationTargetRegistry`, and every equivalent custom Bifrost bean backoff assertion.
- **Replace:** `ConnectionImplementationVisibilityTest` with the comprehensive public-surface classification, retaining any unique provider visibility assertion in the new allowlist test.
- **Rewrite:** Sample catalog/registry/property assertions as public invocation, response, or configuration-focused tests; do not preserve internal imports for test convenience.
- **Remove:** Assertions requiring `SkillExecutionView.executionJournal()`, public `ExecutionJournal`/`JournalEntry`, public runtime validation issues, rejected values, old packages, legacy constructors, bean aliases, or bridge types.
- **Contract classification:** Supported SPI (empty) and Internal or accidentally exposed implementation.
- **Compatibility expectation:** Approved removal. The suite must not require simultaneous old and new behavior.

### 11. Trace writer/reader/projector/CLI coherence

- **Names:** Retain `NdjsonTraceRecordWriterTest`, `NdjsonExecutionTraceReaderTest`, `ExecutionTraceHandleTest`, `ExecutionTraceContractTest`, `ExecutionTraceBoundaryCleanupTest`, updated projector tests, and all `bifrost-cli` Go tests.
- **Type:** Current-version integration and CLI tests.
- **Location:** Relocated starter trace tests and existing `bifrost-cli` tests.
- **What it proves:** The API projection change does not accidentally change current NDJSON writing/reading, error visibility, redaction, or CLI inspection.
- **Fixtures/data:** Current repository trace fixtures only. If the current format changes accidentally, fix the implementation; if it changes deliberately in scope, update current fixtures atomically rather than adding a legacy reader.
- **Mocks:** Existing model/provider fakes.
- **Contract classification:** Ephemeral diagnostic formats; no durable cross-version contract is introduced.
- **Compatibility expectation:** Current-run diagnostic coherence.

### 12. Evidence for skill-authoring source-anchor updates

- **Tests used as evidence:** Public `SkillTemplateContractTest`, `SkillMethodTest`, YAML registrar/catalog tests, `DefaultAccessGuardTest`, `CapabilityExecutionRouterTest`, and the new root authentication test.
- **Type:** Existing behavior/contract evidence; no prose-only test.
- **What it proves:** The updated `mental-model.md` anchors point to code whose tests still establish YAML-only invocation, mapped Java target behavior, local visibility, and session-authentication fallback.
- **Authoring boundary:** No new authoring topic or manifest behavior is claimed. Only source links/terminology change, plus evidence that the already-documented current-authentication behavior works through the facade.
- **Contract classification:** Application API and Configuration and manifest contracts.
- **Compatibility expectation:** Protected author-facing semantics.

## Mutation Checks for the Guards

After the architecture tests pass, locally make and then discard each temporary mutation:

1. Add an unallowlisted public type beneath `.internal`; the public-surface test must name it and fail.
2. Add an internal type to an API generic return signature; recursive signature validation must fail.
3. Add a type beneath `.spi`; the empty-SPI test must fail.
4. Add `@ConditionalOnMissingBean` to a Bifrost bean method; the auto-configuration boundary test must fail.
5. Add a public internal type to the allowlist with a blank reason; the classification test must fail.

Do not commit mutation-only files or compatibility paths.

## How to Run

No live provider credentials, network services, database, special Maven profile, or environment variables are required. Tests must use existing fakes/mocks and repository fixtures. Clear `SecurityContextHolder` after every test that sets authentication.

### Red tests before implementation

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=DefaultSkillTemplateTest#capturesCurrentSecurityContextAuthenticationForRootInvocation test
.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostPublicSurfaceArchitectureTest test
```

Capture the assertion failures showing missing facade authentication and current unclassified/leaked types.

### Focused post-change suites

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=DefaultSkillTemplateTest,ApplicationApiValueTest,SkillMethodTest,SupportedSurfaceIntegrationTest test
.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostPublicSurfaceArchitectureTest,BifrostAutoConfigurationBoundaryTest test
.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostAutoConfigurationTests,BifrostPropertiesTest,ConfigurationMetadataTest,NamedAiConnectionRegistryTests,MicrometerUsageMetricsRecorderTest test
.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=DefaultAccessGuardTest,BifrostSessionRunnerTest,CapabilityExecutionRouterTest,SkillVisibilityResolverTest test
.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=ExecutionJournalProjectorTest,ExecutionJournalProjectionContractTest,NdjsonTraceRecordWriterTest,NdjsonExecutionTraceReaderTest,ExecutionTraceHandleTest,ExecutionTraceContractTest,ExecutionTraceBoundaryCleanupTest test
.\mvnw.cmd -pl bifrost-sample -am -Dtest=SupportedApiUsageArchitectureTest,SampleApplicationTests test
```

The ordinary suites require no network or provider credentials. An explicit post-change operational smoke can be run with `OPENAI_API_KEY` set:

```powershell
.\mvnw.cmd -pl bifrost-sample -am '-Dbifrost.live-provider-test=true' '-Dtest=LiveProviderSmokeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

If Surefire's method selector or class naming changes during relocation, use the final simple class names; do not retain duplicate old-package tests merely to keep these commands unchanged.

### Full verification

```powershell
.\mvnw.cmd test
Push-Location bifrost-cli
go test ./...
Pop-Location
rg -n "ConditionalOnMissingBean" bifrost-spring-boot-starter/src/main/java
rg -n "com\.lokiscale\.bifrost\.(skillapi|annotation)" --glob "*.java" .
git diff --check
```

The two `rg` commands must return no matches. Also inspect `git status --short` to ensure no temporary mutation, obsolete forwarding type, generated trace, or provider artifact remains.

## Manual Verification

1. Start the sample with its normal local configuration and invoke a deterministic mapped-skill endpoint that requires no live model. Confirm the public response and no internal-type serialization.
2. Observe one successful invocation and confirm the response contains `sessionId` and `executionEvents` with the expected current event categories, ordering, frame/route context, and redacted sensitive fields.
3. Invoke a protected test skill through a Spring test context with matching and missing authorities. Confirm success versus `AccessDeniedException` without an authentication input argument.
4. Trigger invalid input and inspect `SkillInputValidationException`: every issue has only path/code/message and no rejected value.
5. Trigger an internal runtime failure and confirm the caller sees only `Skill '<name>' execution failed.` while the exception cause/log retains diagnostic detail.
6. Review the generated architecture classification output: exactly seven API types, four Spring integration types, zero SPIs, zero replacement points, and a reason for every technically public internal type.
7. Review README and `ai/skill-authoring/mental-model.md` links against the relocated sources and confirm the prose does not promise extension points, durable diagnostics, or a broader exception taxonomy.

A live external-provider planning smoke test is optional and non-blocking for this surface refactor; existing mocked provider, nested-routing, and full Java/CLI suites are the required evidence.

## Exit Criteria

- [x] The authentication handoff test and initial boundary architecture test are recorded failing before production changes.
- [x] The exact seven Application API types and four framework-integration types are mechanically allowlisted.
- [x] Every other externally accessible type is classified, and every technically public internal type has a nonblank reason.
- [x] Recursive signature checks cover constructors, methods, fields, generics, arrays, records, exceptions, and annotations without leaking internal/autoconfiguration types.
- [x] Supported Bifrost SPI and bean-replacement allowlists are empty; no production `@ConditionalOnMissingBean` remains.
- [x] Obsolete resolver/advisor/registry override tests and old-package/public-DTO expectations are removed rather than preserved beside new behavior.
- [x] `SkillTemplate` captures trusted authentication, preserves validation/authorization/existing public exceptions, safely wraps other internal runtime failures, does not catch `Error`, and invokes observers only after success.
- [x] Public execution and validation values are defensively and deeply immutable and expose no Jackson, trace, journal, runtime validation, or rejected-value type/data.
- [x] Current diagnostic selection, order, repetitions, failure visibility, frame/route context, and existing redaction remain coherent through `SkillExecutionEvent`.
- [x] Current NDJSON writer/reader/projector and CLI tests pass without historical adapters or fixtures.
- [x] Documented configuration, metadata, YAML manifests, mappings, attachments, providers, metrics integration, and RBAC semantics remain green.
- [x] Sample production code depends only on `com.lokiscale.bifrost.api`, and sample tests verify public behavior rather than internal registries/catalogs.
- [x] A supported-surface integration test exercises an LLM-backed YAML skill through `SkillTemplate` and standard named-connection configuration without replacing internal Bifrost beans.
- [x] Tests cited as evidence for changed skill-authoring source anchors establish the documented invocation, mapping, visibility, and authentication behavior.
- [x] All focused starter and sample suites pass.
- [x] Full Maven and Go CLI suites pass.
- [x] The opt-in live OpenAI smoke passes through the supported `SkillTemplate` facade and normal named-connection configuration.
- [x] All architecture mutation checks fail for the intended reason and are discarded afterward.
- [x] Search checks find no old public packages, conditional bean backoff, compatibility shims, aliases, or dual old/new paths.
- [x] Manual review confirms the ticket, implementation plan, tests, and documentation describe the same boundary.

## References

- Ticket: `ai/thoughts/tickets/eng-reduce-spring-boot-starter-public-surface.md`
- Implementation plan: `ai/thoughts/plans/2026-07-15-reduce-spring-boot-starter-public-surface.md`
- Canonical compatibility lens: `ai/thoughts/framework-feature-design-lens.md`
- Testing-plan command: `ai/commands/3_testing_plan.md`
- Skill-authoring evidence target: `ai/skill-authoring/mental-model.md`
- Planning baseline: branch `main`, commit `2a690417cdec638031b33518f4224e21cb28dbe5`, 2026-07-15
