# Bifrost Console PR 04 — Spring Adapter Foundation and REST Snapshots Testing Plan

## Change Summary

- Add opt-in `bifrost.observability.*` configuration and a servlet/MVC
  auto-configuration without changing non-web or disabled applications.
- Commit observation and completion-grace behavior only after the application
  proves exclusive ownership of `/_bifrost/observability/v1/**`.
- Authenticate one bounded `X-Bifrost-Api-Key`, establish the internal
  `BIFROST_OPERATOR` authority for one request, and leave the host
  `SecurityFilterChain` under application control.
- Add centralized no-store policy, startup-scoped instance identity, stable
  problems, exact release compatibility, explicit REST DTOs, opaque keyset
  continuations, and a 16 MiB whole-item collection response ceiling.
- Expose instance, registered-skill, active-execution, and current-process trace
  list/detail resources.
- Correct active-registry traversal so a continuation can resume below the last
  emitted ordinal, and add cheap skill/trace count operations for status.
- Establish deterministic Java-produced REST/problem fixtures for future Go
  contract tests.

## Test Objectives

1. Prove disabled behavior remains the current no-op behavior.
2. Prove no partial observability state or routes exist before successful
   collision-free activation.
3. Prove authentication and identity cannot be bypassed, confused with host
   authentication, leaked, or retained across requests.
4. Prove exact Java-to-Go wire meanings independently of internal Java types.
5. Prove pagination remains bounded, stable under concurrent mutation, and
   recoverable under the approved invalid/stale distinctions.
6. Prove a live-projection failure blocks only active resources.
7. Prove all protected API/configuration behavior remains intact while obsolete
   internal traversal is removed rather than retained behind an overload.

## Impacted Areas

- `bifrost-spring-boot-starter/pom.xml`
  - optional servlet/MVC production dependencies;
  - test-only web and Spring Security dependencies;
  - filtered release resource.
- `com.lokiscale.bifrost.autoconfigure`
  - strict observability properties;
  - core/session-runner composition;
  - servlet web auto-configuration;
  - auto-configuration imports and metadata.
- `com.lokiscale.bifrost.internal.observability`
  - activation state, lifecycle, route collision inspection, registration,
    authentication, authorization, common response policy, DTOs, cursors,
    bounded JSON writing, queries, and handlers.
- `com.lokiscale.bifrost.internal.runtime.observation`
  - active traversal signature;
  - gated observation;
  - replay cursor use;
  - live availability.
- `com.lokiscale.bifrost.internal.runtime.observation.catalog`
  - skill and finalized-trace counts;
  - expiry-aware status behavior.
- `bifrost-console-fixtures/application-rest/`
  - reviewed Java-produced response and problem bodies.
- Architecture boundaries
  - exact public API;
  - framework integration types;
  - no SPI;
  - no bean override seams;
  - no leaked internal DTO components.
- `README.md`
  - application configuration and host-security integration claims.

## Risk Assessment

### Critical Risks

- **Partial activation:** observation, catalog schedulers, or completion grace
  could become enabled before a route collision is discovered.
- **Host security confusion:** filter ordering or context handling could allow a
  host identity to bypass the Bifrost key, allow the Bifrost identity to escape
  the namespace, or misreport host/proxy rejection as an invalid Bifrost key.
- **Namespace shadowing:** exact, variable, functional, wildcard, or catch-all
  host routes could be silently replaced or coexist with Bifrost routes.
- **Secret disclosure:** configured or presented keys could appear in problems,
  logs, bean/property strings, fixtures, URLs, or identity metadata.
- **Protocol drift:** Jackson getters or internal records could add fields,
  serialize paths/artifacts, change enum/time formats, or move
  `consoleCompatibilityVersion` out of the stable top level.
- **Pagination corruption:** active page two could repeat page one; concurrent
  insertions could shift later pages; mutation could create duplicates; a byte
  cutoff could lose the continuation or cut JSON/items.
- **Identity mixing:** a cursor or response from an earlier application
  instance could be interpreted in the new runtime scope.

### High Risks

- Disabled or non-web applications may accidentally gain web dependencies,
  filters, scheduler threads, trace grace, or changed execution behavior.
- A catalog count may include expired metadata or perform artifact I/O.
- Authentication errors may disclose instance identity.
- An unexpected exception may use the host error body instead of the stable
  application problem.
- Context-path handling may double-prefix or omit the servlet context.
- API-root-relative skill links may accept a scheme, authority, absolute path,
  query, fragment, or path escape.
- A live availability failure may incorrectly block skills or finalized traces.
- Filter/registrar shutdown may leak mappings, contexts, or scheduler threads.

### Medium Risks

- `pageSize` may be clamped, accepted twice, overflow numeric parsing, or become
  bound into a cursor even though callers may change it.
- Empty/final pages may disagree between `hasMore` and nullable `nextCursor`.
- `elapsedMillis` may become negative under clock skew.
- Zero quota values may be misreported instead of preserving their existing
  unlimited meaning.
- Release filtering may drop `-SNAPSHOT`, leave a Maven placeholder, or return
  null outside a packaged JAR.
- Success logging may be noisy but must remain free of credentials and
  diagnostic content.

## Contract and Compatibility Test Scope

| Classification | Protected or changed path | Required test treatment |
| --- | --- | --- |
| Application API | Existing exact seven-type API and ordinary disabled skill execution | Run existing API/architecture and mapped-skill tests. Assert no observability DTO/filter/service enters API signatures. |
| Supported SPI | No supported SPI and no bean replacement allowlist | Assert no `.spi`, `@ConditionalOnMissingBean`, public customization interface, or application override seam is added. |
| Configuration and manifest contracts | New strict `bifrost.observability.*`; unchanged YAML syntax/content | Test all defaults, valid/invalid values, unknown fields, secret-safe strings, generated metadata, and exact unchanged YAML. |
| Persisted or serialized contracts | New REST/problem/header/link/cursor meanings and release marker | Protect exact JSON fixtures, headers, status/code mapping, cursor behavior, and complete release string. Cursors are not cross-restart persisted data. |
| Ephemeral diagnostic formats | Current-instance skill, active, and trace metadata | Test current-run accuracy, bounds, ordering, fail-closed live behavior, path exclusion, and no-store/security. Do not test historical trace compatibility. |
| Internal or accidentally exposed implementation | Active traversal replacement, catalog count additions, activation/filter/registrar internals | Replace callers atomically, assert the old two-argument traversal is absent, and test the coherent new behavior without a compatibility overload. |

### Java-to-Go Boundary Coordination

- PR 04 Java tests must produce reviewed exact JSON bodies for every REST
  resource and stable problem code used by this PR.
- Integration tests must protect observable HTTP status, content type,
  no-store, instance header, and authentication distinctions that are not
  represented by body-only fixtures.
- The instance fixture must keep `consoleCompatibilityVersion` top-level and
  equal to the complete Maven release string, including qualifiers.
- Cursor tests must cover the semantics the future Go client maps to
  `INVALID_CURSOR` and `STALE_CURSOR`.
- PR 06 may extend but must not duplicate this corpus for SSE/acquisition.
  PR 09’s Go client will consume the same fixtures.

### Intentionally Removed Obsolete Path

- Remove `ActiveExecutionRegistry.newestFirst(long highWaterMark, int limit)`.
- Add only
  `newestFirst(long highWaterMark, long beforeOrdinal, int limit)`.
- A reflection/architecture assertion should prove the old overload is absent;
  tests must not require simultaneous old/new traversal behavior.

## Existing Test Coverage

### Tests to Extend

- `BifrostAutoConfigurationTests`
  - auto-configuration import and framework-owned composition;
  - disabled/non-model application behavior.
- `BifrostAutoConfigurationBoundaryTest`
  - exact bean factories, package-private factory methods, no
    `@ConditionalOnMissingBean`.
- `BifrostPublicSurfaceArchitectureTest`
  - exact Application API, framework integration types, no SPI, classified
    technically public internals, bounded observation DTOs.
- `BifrostPropertiesTest`
  - strict binding, exact validation paths, sensitive-value-safe strings.
- `ConfigurationMetadataTest`
  - generated property names, types, defaults, and hints.
- `InMemoryActiveExecutionRegistryTest`
  - stable ordinals, high water, concurrency, overflow.
- `InMemoryActivityReplayBufferTest`
  - current cursor, expiry, future cursor, count/byte bounds, concurrency.
- `DefaultRegisteredSkillCatalogTest`
  - deterministic name ordering, exact lookup, unchanged YAML, invalid UTF-8.
- `InMemoryFinalizedTraceCatalogTest`
  - keyset traversal, earlier effective expiration, expiry, close.
- `ConsoleTraceFixtureCorpusTest`
  - fixture-root and deterministic regeneration convention to reuse, not to
    alter.

### Current Gaps

- No test starts the observability adapter through auto-configuration.
- No MockMvc or random-port application tests exist.
- No servlet filter, host `SecurityFilterChain`, context-path, or route
  collision test exists.
- No REST DTO, problem, cursor, page-size, or JSON-byte-budget test exists.
- No release resource or REST/problem fixture corpus exists.
- Current active traversal has no page-two position.
- Skill/trace catalogs expose no count.

## Bug Reproduction / Failing Test First

The PR is primarily new behavior, but it contains one concrete current
implementation gap that should be driven by the first red test.

- **Name**:
  `traversesCapturedHighWaterBelowExclusiveBeforeOrdinal`
- **Type**: unit
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActiveExecutionRegistryTest.java`
- **Arrange**:
  - insert four sessions and record the captured `highestOrdinal()`;
  - request the first two newest items;
  - retain the last item’s ordinal;
  - insert a fifth session after the captured high water.
- **Act**:
  - request the next page with the original high water and the first page’s
    exclusive before ordinal.
- **Assert**:
  - page two contains the two older original sessions exactly once;
  - the later fifth session is absent;
  - concatenated pages contain no duplicates.
- **Expected failure before implementation**:
  the desired three-argument traversal does not compile because the registry has
  no exclusive position. Calling the existing method twice returns the same
  first page, demonstrating that the current API cannot implement a REST
  continuation.
- **Minimal fix boundary**:
  replace the internal interface/implementation method and update repository
  callers. Do not add a compatibility overload.

## Incremental Red/Green Sequence

Each implementation slice should add or update its named test before production
code:

1. Active exclusive-keyset traversal and catalog counts.
2. Observability property binding/defaults and filtered release loading.
3. Pending/disabled/enabled activation and session-runner delegates.
4. Route collision classification and all-or-none registration.
5. API-key filter, request-local authority, context restoration, and problems.
6. Exact DTO projection and JSON serialization.
7. Cursor codec and collection query semantics.
8. Exact 16 MiB whole-item page writing.
9. Seven handlers and namespace fallback.
10. Full servlet/context-path/host-security/lifecycle integration.
11. Deterministic fixture corpus and documentation-backed smoke checks.

At each step, the new focused test should fail for the intended missing behavior
before production changes and pass afterward. A compilation failure caused only
by a deliberately new internal signature is acceptable for step 1; later steps
should prefer behavioral assertion failures over broad context-startup errors.

## Tests to Add or Update

### 1. Active Registry Keyset and Catalog Count Tests

- **Type**: unit
- **Locations**:
  - `InMemoryActiveExecutionRegistryTest.java`
  - `DefaultRegisteredSkillCatalogTest.java`
  - `InMemoryFinalizedTraceCatalogTest.java`
  - `DefaultExecutionObservationHandleFactoryTest.java` or the nearest existing
    factory test
- **Names / what they prove**:
  - `traversesCapturedHighWaterBelowExclusiveBeforeOrdinal`
    - page two works without duplicates or post-high-water insertions.
  - `skipsRemovedEntriesBetweenActivePagesWithoutStalingPosition`
    - removal creates an allowed gap, not repetition or failure.
  - `rejectsNegativeOrImpossibleActiveKeysetArguments`
    - high water and before invariants are explicit.
  - `reportsExactRegisteredSkillCount`
    - immutable catalog count equals unique registered names.
  - `excludesExpiredEntriesFromCatalogedTraceCountBeforeSweep`
    - status count respects the clock even before scheduled reclamation.
  - `unavailableCatalogReportsZeroCount`
    - disabled fallback remains coherent.
  - `oldTwoArgumentActiveTraversalIsAbsent`
    - no accidental compatibility overload remains.
- **Fixtures/data**: fixed snapshots and mutable clocks already used by adjacent
  tests.
- **Mocks**: none; inject deterministic clocks and ordinal suppliers.
- **Contract classification**: Internal or accidentally exposed implementation.
- **Compatibility expectation**: approved atomic removal and coherent new
  current-run traversal.

### 2. Observability Configuration and Release Tests

- **Type**: unit / application-context
- **Locations**:
  - update `BifrostPropertiesTest.java`
  - update `ConfigurationMetadataTest.java`
  - new `BifrostReleaseVersionTest.java`
- **Names / what they prove**:
  - `bindsDisabledObservabilityDefaults`
    - false, `PT15M`, and `PT24H` are exact defaults.
  - `bindsValidExternalizedObservabilityConfiguration`
    - all approved property names bind.
  - `allowsConfiguredKeyWhileDisabled`
    - activation control remains independent.
  - `rejectsUnknownObservabilityProperty`
    - strict `bifrost.*` behavior remains protected.
  - `syntacticallyInvalidDurationFailsBinding`
    - malformed values remain ordinary Spring configuration failures.
  - `semanticInvalidityDisablesOnlyEnabledAdapter`
    - missing/short/long/non-ASCII/whitespace key, negative grace, and
      zero/negative metadata TTL produce safe adapter disablement without
      partial routes.
  - `observabilityPropertyStringsNeverExposeApiKey`
    - sentinel secret is absent from `toString` and validation messages.
  - `metadataDocumentsEveryObservabilityPropertyAndDefault`
    - generated metadata is complete.
  - `loadsCompleteFilteredMavenReleaseIncludingQualifier`
    - exact `0.1.0-SNAPSHOT`.
  - `rejectsMissingBlankDuplicateOrUnfilteredReleaseValue`
    - compatibility cannot silently become null or a placeholder.
- **Fixtures/data**: property values and isolated classloaders/resources for
  invalid release cases.
- **Mocks**: none.
- **Contract classification**: Configuration and manifest contracts;
  Persisted or serialized contracts for release identity.
- **Compatibility expectation**: additive protected configuration and exact
  release-string wire behavior.

### 3. Activation and Session-Runner Composition Tests

- **Type**: unit / application-context
- **Locations**:
  - new `ObservabilityActivationCoordinatorTest.java`
  - new `GatedObservabilityRuntimeTest.java`
  - update `BifrostAutoConfigurationTests.java`
- **Names / what they prove**:
  - `pendingActivationUsesNoOpObservationAndImmediateRetention`
  - `successfulCommitEnablesOneIdentityAndOwnedCollaborators`
  - `collisionOrInvalidConfigurationPermanentlyDisablesActivation`
  - `activationCannotCommitTwiceOrReenableAfterDisable`
  - `failedCommitClosesPartiallyCreatedCatalogAndSchedulers`
  - `contextCloseClosesGraceAndCatalogExactlyOnce`
  - `disabledServletAndNonWebApplicationsCreateNoObservabilityResources`
  - `mappedSkillExecutionRemainsUnchangedWhenObservabilityIsDisabled`
  - `enabledSessionAppearsActiveThenIsRemovedAndCataloged`
- **What they prove**:
  activation is atomic, one-way, leak-free, and does not change existing
  disabled execution behavior. The lifecycle integration test should hold a
  `BifrostSessionRunner` action behind latches, observe the active entry, release
  it, and verify removal/finalized publication.
- **Fixtures/data**: injected fake closables, fixed clock/UUID supplier, a
  model-free mapped action, and temporary trace files.
- **Mocks**: small lifecycle fakes are preferable to mocking internal records.
- **Contract classification**: Internal or accidentally exposed implementation;
  Application API regression coverage for disabled `SkillTemplate` behavior.
- **Compatibility expectation**: protected disabled Application API behavior
  and current-run observation coherence.

### 4. Route Collision Detector Tests

- **Type**: unit / web application-context
- **Locations**:
  - new `ObservabilityRouteCollisionDetectorTest.java`
  - new `ObservabilityRouteRegistrarTest.java`
  - new `ObservabilityCollisionIntegrationTest.java`
- **Names / what they prove**:
  - `detectsExactHostRouteInsideReservedNamespace`
  - `detectsPathVariableWildcardAndCatchAllOverlap`
  - `detectsFunctionalRouterAndExplicitUrlHandlerOverlap`
  - `reservesFutureActivityAndArtifactChildren`
  - `ignoresUnrelatedApplicationRoutesAndFrameworkFallbackMappings`
  - `unclassifiableApplicationMappingFailsClosed`
  - `registersAllExactRoutesAndFallbackBeforeCommitting`
  - `registrationFailureRollsBackEveryBifrostMapping`
  - `collisionLeavesHostAndUnrelatedRoutesUsable`
  - `destroyUnregistersOnlyBifrostMappings`
- **Fixtures/data**: small nested controller, `RouterFunction`, and explicit URL
  mapping configurations with exact, variable, wildcard, catch-all, unrelated,
  static-resource, and future-route patterns.
- **Mocks**: unit-test pattern analysis directly; use real Spring handler
  mappings for registration/integration behavior.
- **Contract classification**: Configuration and manifest contracts for
  activation semantics; Internal implementation for registrar mechanics.
- **Compatibility expectation**: protected fail-closed configuration behavior;
  no partial old/new route ownership.

### 5. API-Key Filter and Access-Service Tests

- **Type**: unit / servlet integration
- **Locations**:
  - new `ObservabilityApiKeyFilterTest.java`
  - new `ObservabilityAccessServiceTest.java`
  - new `ObservabilitySecurityIntegrationTest.java`
- **Names / what they prove**:
  - `pendingOrDisabledFilterPassesThroughWithoutClaimingNamespace`
  - `rejectsMissingBlankInvalidDuplicateOversizedAndUnicodeKeys`
  - `rejectsKeyInQueryWhenHeaderIsAbsent`
  - `acceptsExactlyOneCompleteMatchingKey`
  - `matchingKeyEstablishesOnlyBifrostOperator`
  - `restoresEmptyAnonymousAndHostSecurityContextsOnEveryExitPath`
  - `doesNotLeakAuthenticationAcrossConcurrentRequests`
  - `authorizesEveryInitialOperationOnlyForBifrostOperator`
  - `successfulAuthenticationLogExcludesKeyAndDiagnosticContent`
  - `rejectionLogAndProblemExcludeConfiguredAndPresentedSentinels`
  - `hostPermitAllReachesBifrostAuthentication`
  - `hostRejectionBeforeFilterHasNoBifrostProblemCode`
- **Fixtures/data**:
  - boundary key lengths 31/32/512/513;
  - spaces, tabs, CR/LF, DEL, non-ASCII, multiple header lines;
  - distinct sentinel values for configured key, presented key, YAML, path, and
    exception message;
  - host chain configurations with namespace pass-through and deny/authenticate
    rules.
- **Mocks**:
  - unit tests use mock servlet request/response/filter chain;
  - integration uses the real filter and Spring Security chain.
- **Contract classification**: Configuration and manifest contracts;
  Persisted or serialized problem contract; ephemeral diagnostic security.
- **Compatibility expectation**: protected authentication distinction and
  security boundary.

### 6. Common Problem and Response-Policy Tests

- **Type**: unit / MockMvc integration
- **Locations**:
  - new `ObservabilityProblemMapperTest.java`
  - response assertions in `ObservabilityRestIntegrationTest.java`
- **Names / what they prove**:
  - one parameterized test for each approved status/code pair:
    `BIFROST_API_KEY_REJECTED/401`, `INVALID_REQUEST/400`,
    `INVALID_CURSOR/400`, `STALE_CURSOR/410`, `NOT_FOUND/404`,
    `LIVE_MONITORING_UNAVAILABLE/503`, `LIMIT_EXCEEDED/429`,
    `APPLICATION_ERROR/500`.
  - `problemBodyContainsExactlyStatusCodeAndSafeMessage`
  - `authenticatedSuccessAndProblemCarrySameInstanceHeaderAndNoStore`
  - `authenticationRejectionUsesNoStoreButOmitsInstanceIdentity`
  - `unexpectedExceptionIsSanitizedBeforeResponseCommit`
  - `problemAndLogsExcludeSensitiveSentinels`
- **Fixtures/data**: parameterized approved table and sentinel exception/path/
  header values.
- **Mocks**: mapper tests use direct exceptions; MockMvc uses a handler seam that
  deliberately throws before response commitment.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: protected Java-to-Go code/status meanings.

### 7. DTO Projection and Exact Wire-Shape Tests

- **Type**: unit / JSON contract
- **Locations**:
  - new `ObservabilityDtoMapperTest.java`
  - new `ObservabilityWireJsonTest.java`
- **Names / what they prove**:
  - `instanceStatusKeepsCompatibilityVersionTopLevelAndHeaderIdentityEqual`
  - `skillSummaryUsesSafeApiRootRelativeHref`
  - `skillDetailPreservesYamlBytesAsDecodedUtf8Text`
  - `activeListAndDetailUseTheSameBoundedSnapshotFields`
  - `activeElapsedIsDerivedAtObservationTimeAndNeverNegative`
  - `activeUsageAndConfiguredLimitsRemainSeparateAndPreserveZeroUnlimited`
  - `traceDtoExcludesOrdinalArtifactAndFilesystemPath`
  - `wireEnumsTimesDurationsAndNullableFieldsUseExactApprovedEncoding`
  - `wireRecordsContainNoUnexpectedJacksonProperties`
- **Fixtures/data**:
  - fixed instants, UUIDs, maximum active path/summary, all usage values,
    zero/nonzero limits, CRLF YAML, path-bearing internal trace entry.
- **Mocks**: none; use real `ObjectMapper` configuration.
- **Contract classification**: Persisted or serialized contracts; ephemeral
  diagnostic projection.
- **Compatibility expectation**: exact new wire contract and current-run
  security/accuracy.

### 8. Cursor Codec and Collection Query Tests

- **Type**: unit / property-style parameterized
- **Locations**:
  - new `ObservabilityCursorCodecTest.java`
  - new `ObservabilityCollectionQueryServiceTest.java`
- **Names / what they prove**:
  - `roundTripsEachVersionOneCollectionCursor`
  - `rejectsMalformedPaddingGarbageOversizedAndUnknownVersionCursors`
  - `rejectsWrongEndpointOrderFilterAndImpossiblePositionAsInvalid`
  - `mapsDifferentWellFormedInstanceToStale`
  - `allowsPageSizeChangeAcrossContinuation`
  - `skillContinuationIsCaseSensitiveAfterLastRegisteredName`
  - `activeAndTraceContinuationRetainFirstPageHighWater`
  - `deletionBetweenPagesSkipsWithoutStaling`
  - `newInsertionsAppearOnlyOnRefresh`
  - `initialActivePageCapturesResumeCursorOnce`
  - `emptyAndFinalPagesHaveConsistentHasMoreAndNullCursor`
- **Fixtures/data**:
  deterministic instance IDs, ordering/filter fingerprints, mutable catalogs,
  seeded malformed-token corpus, and boundary-long cursor strings.
- **Mocks**: fake catalog interfaces are acceptable for query-service edge
  cases; codec uses the real `ObjectMapper`.
- **Contract classification**: Persisted or serialized contracts for cursor
  meanings; Internal implementation for keyset mechanics.
- **Compatibility expectation**: protected Java-to-Go continuation behavior;
  cursors themselves have no restart/cross-version preservation promise.

### 9. Bounded JSON Page Writer Tests

- **Type**: unit
- **Location**: new `BoundedJsonPageWriterTest.java`
- **Names / what they prove**:
  - `acceptsDefaultMinimumAndMaximumPageSizes`
  - `rejectsZeroNegativeOverflowDuplicateAndAboveMaximumPageSize`
  - `rejectsUnknownQueryParameters`
  - `fetchesAtMostRequestedSizePlusOne`
  - `writesExactMeasuredBytesWithoutMvcReserialization`
  - `removesOnlyWholeTrailingItemsUntilAtOrBelowSixteenMiB`
  - `recomputesCursorFromLastActuallyEmittedItem`
  - `keepsHasMoreWhenItemOrByteLimitStopsTraversal`
  - `neverCutsUtf8JsonOrOneItem`
  - `returnsLimitExceededWhenOneSyntheticItemOrEnvelopeCannotFit`
- **Fixtures/data**:
  - empty/single/multi-item pages;
  - maximum bounded active summaries sufficient to cross 16 MiB;
  - multibyte text;
  - a synthetic oversized item only for the defensive unreachable branch.
- **Mocks**: counting catalog/query fake to assert the 5,001 maximum; real
  `ObjectMapper` and JSON parsing for body integrity.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: protected page framing and resource bound.

### 10. REST Resource Integration Tests

- **Type**: MockMvc integration
- **Location**: new `ObservabilityRestIntegrationTest.java`
- **Names / what they prove**:
  - `returnsAuthenticatedInstanceStatusWithExactReleaseAndCounts`
  - `listsAndGetsCaseSensitiveSkillWithUnchangedYaml`
  - `listsAndGetsCurrentActiveExecution`
  - `listsAndGetsCurrentProcessTraceWithoutPath`
  - `unknownCurrentResourceReturnsNotFoundWithoutExpiryClaim`
  - `unknownReservedGetReturnsNotFound`
  - `unsupportedReservedMethodReturnsInvalidRequest`
  - `liveFailureBlocksOnlyActiveListAndDetail`
  - `allResourcesAreReadOnlyAndNoCorsPolicyIsAdded`
- **Fixtures/data**: committed activation with fixed clock/identity, two skills,
  multiple active snapshots, retained and expired trace entries.
- **Mocks**: real in-memory registries/catalogs; no network or model provider.
- **Contract classification**: Persisted or serialized contracts and ephemeral
  diagnostic coherence.
- **Compatibility expectation**: exact new external behavior.

### 11. Context Path, Host Security, and Full Lifecycle Tests

- **Type**: random-port integration / end-to-end within the Java module
- **Locations**:
  - new `ObservabilityContextPathIT.java`
  - new `ObservabilityHostSecurityIT.java`
  - new `ObservabilityLifecycleIT.java`
- **Names / what they prove**:
  - `servesOnlyBeneathOrdersServletContext`
  - `rootRelativeRequestWithoutContextDoesNotReachAdapter`
  - `hostPermitAllStillRequiresBifrostKey`
  - `hostAuthenticationDefaultCanBlockBeforeBifrost`
  - `hostBusinessRoutesKeepOriginalAuthentication`
  - `disabledAndInvalidActivationExposeNoAdapterRoute`
  - `routeCollisionKeepsNoOpObservationAndZeroGrace`
  - `enabledExecutionIsVisibleThenRemovedAndCataloged`
  - `applicationRestartChangesIdentityAndStalesPriorCursor`
  - `shutdownRemovesMappingsAndStopsOwnedSchedulers`
- **Fixtures/data**:
  nested minimal `@SpringBootApplication` classes, random ports, `/orders`
  context, model-free session runner actions held by latches, temporary traces,
  and generated in-memory test keys.
- **Mocks**: no mocked servlet/security chain; use real embedded container and
  JDK HTTP client or `TestRestTemplate`.
- **Contract classification**: Application API regression, Configuration and
  manifest contracts, Persisted or serialized contracts, and ephemeral
  diagnostic lifecycle.
- **Compatibility expectation**: protected disabled application behavior and
  exact new integration boundary.

### 12. Public Surface and Auto-Configuration Boundary Tests

- **Type**: architecture / reflection
- **Locations**:
  - update `BifrostPublicSurfaceArchitectureTest.java`
  - update `BifrostAutoConfigurationBoundaryTest.java`
  - update `BifrostAutoConfigurationTests.java`
- **Names / what they prove**:
  - existing exact-seven Application API test remains unchanged;
  - framework integration allowlist adds only
    `BifrostObservabilityWebAutoConfiguration`;
  - every new technically public internal type has a nonblank internal reason;
  - no observability DTO is public outside `internal`;
  - DTO components recursively exclude paths, artifacts, resources,
    exceptions, streams, publishers, and canonical trace records;
  - both auto-configurations’ bean factories are exact, framework-owned, and
    package-private;
  - production code still has no `@ConditionalOnMissingBean`;
  - no supported SPI or bean override appears;
  - auto-configuration imports contain the exact expected two classes;
  - old active traversal overload is absent.
- **Fixtures/data**: production class import and reflection.
- **Mocks**: none.
- **Contract classification**: Application API, Supported SPI, and Internal or
  accidentally exposed implementation.
- **Compatibility expectation**: preserve API/no-SPI boundaries; approved
  internal removal.

### 13. Deterministic REST and Problem Fixture Corpus

- **Type**: golden-fixture contract
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ConsoleRestFixtureCorpusTest.java`
- **Fixture location**: `bifrost-console-fixtures/application-rest/`
- **Inventory**:
  - `instance-status.json`
  - `skills-page.json`, `skill-detail.json`
  - `active-executions-page.json`, `active-execution-detail.json`
  - `traces-page.json`, `trace-detail.json`
  - `empty-page.json`, representative continuation page;
  - one body for each of the eight approved problem codes.
- **What it proves**:
  deterministic Java production of the exact bodies future Go tests consume;
  top-level compatibility, field spelling, enum/time/duration formatting,
  nullable continuation, safe links, and sanitized problem framing.
- **Fixtures/data**: fixed clocks, IDs, key-safe configuration, YAML, active
  state, trace metadata, and deterministic messages.
- **Mocks**: direct committed runtime fixture; no model/network.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: protected release-matched Java-to-Go producer
  contract.

### 14. Sensitive Diagnostic and Logging Tests

- **Type**: unit / integration security regression
- **Locations**:
  - filter/problem/activation tests above;
  - dedicated `ObservabilitySensitiveDataTest.java` if a single sentinel matrix
    is clearer.
- **What it proves**:
  configured/presented API key, authentication header, host authorization
  header, raw exception message, filesystem path, YAML, and diagnostic payload
  sentinels never appear in authentication problems, adapter errors, activation
  diagnostics, successful-authentication logs, or fixture metadata.
- **Fixtures/data**: one unique sentinel per sensitive category and a captured
  Logback appender.
- **Mocks**: captured logger plus deliberate safe failures.
- **Contract classification**: Ephemeral diagnostic formats and serialized
  problem security.
- **Compatibility expectation**: current-run security boundary.

## Workflow-Linked Coverage

Representative tests should include the requirement ID in `@DisplayName` or
equivalent test metadata:

| Requirement | Test evidence in PR 04 |
| --- | --- |
| `WF-SE-R1` | Active DTO exposes mechanically derived `elapsedMillis` and no slow/stuck/health classification. |
| `WF-SE-R2` | Active list/detail expose the same bounded snapshot and active path, not event history or a trace tree. |
| `WF-SE-R3` | Active baseline includes `observedAt`, instance header, high-water continuation, and first-page `resumeCursor`. |
| `WF-SE-R6` | Active DTO exposes bounded `activePath` plus truncation/depth facts and no complete hierarchy claim. |
| `WF-SE-R7` | Long elapsed time and absent recent activity do not create a stuck/health field. |
| `WF-SE-R9` | Java fixture fixes the active snapshot/cursor meaning later shared by browser and MCP through Go. |
| `WF-SP-R2` | Active execution exposes only the bounded current path. |
| `WF-SP-R7` | Skill summary link resolves by registered name to application-provided YAML. |
| `WF-SP-R8` | CRLF/comments/YAML text survives detail serialization unchanged. |
| `WF-SP-R9` | `sourcePath` is returned as descriptive text and never accepted as a lookup/path input. |

Requirements owned by later trace analysis, SSE, browser, or MCP PRs should not
be claimed by PR 04 tests.

## Mocking and Test-Data Policy

- Prefer real in-memory registries, catalogs, clocks, Jackson configuration,
  Spring handler mappings, filters, and security chains.
- Use fakes only for lifecycle close counters, query fetch counters, UUID/clock
  determinism, and deliberately injected failures.
- Do not mock internal DTOs or `ObjectMapper`; doing so would miss the boundary
  being protected.
- No external model provider, network service, persistent database, or user
  credential is required.
- Random-port tests use generated process-local keys and temporary directories.
  Keys must never be committed to fixtures or logged.
- Time/concurrency tests use latches, injected clocks, and bounded joins rather
  than sleeps.

## Manual Verification

1. Start the existing sample with observability absent and verify its current
   business routes and Bifrost execution still work.
2. Supply a generated key through `BIFROST_OBSERVABILITY_API_KEY`, enable the
   adapter, and inspect all seven routes with `curl`.
3. Verify correct, missing, invalid, duplicated, and host-blocked credentials.
4. Run under `server.servlet.context-path=/orders` and follow the API-root-
   relative skill link against the validated API base.
5. Configure a small page size, traverse each collection, restart the
   application, and verify the prior cursor returns `STALE_CURSOR`.
6. Add an exact and then broad host route collision; verify the host route
   remains usable and the entire optional adapter, observation, and completion
   grace remain disabled.
7. Enable a host Spring Security chain that authenticates all other requests
   but permits the namespace through; verify Bifrost still requires its own key.
8. Review startup and request logs for the sentinel key, YAML, paths, and
   exception text.
9. Review every new fixture as a future Go input, not merely valid JSON.

## How to Run

Commands below use the Windows wrapper for this workspace. On POSIX, replace
`.\mvnw.cmd` with `./mvnw`.

### Baseline Before Implementation

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter test
```

Record that the existing suite passes before adding the first red test.

### First Red Test

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter `
  -Dtest=InMemoryActiveExecutionRegistryTest test
```

The first run should fail to compile until the new exclusive-before traversal
signature exists. After the minimal internal change, it must pass.

### Focused Unit and Architecture Tests

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter `
  -Dtest=InMemoryActiveExecutionRegistryTest,DefaultRegisteredSkillCatalogTest,InMemoryFinalizedTraceCatalogTest,BifrostPropertiesTest,ConfigurationMetadataTest,BifrostReleaseVersionTest,ObservabilityActivationCoordinatorTest,GatedObservabilityRuntimeTest,ObservabilityRouteCollisionDetectorTest,ObservabilityRouteRegistrarTest,ObservabilityApiKeyFilterTest,ObservabilityAccessServiceTest,ObservabilityProblemMapperTest,ObservabilityDtoMapperTest,ObservabilityWireJsonTest,ObservabilityCursorCodecTest,ObservabilityCollectionQueryServiceTest,BoundedJsonPageWriterTest,BifrostAutoConfigurationTests,BifrostAutoConfigurationBoundaryTest,BifrostPublicSurfaceArchitectureTest test
```

### Servlet Integration Tests

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter `
  -Dtest=ObservabilityCollisionIntegrationTest,ObservabilitySecurityIntegrationTest,ObservabilityRestIntegrationTest,ObservabilityContextPathIT,ObservabilityHostSecurityIT,ObservabilityLifecycleIT test
```

If the project configures `*IT` through Maven Failsafe during implementation,
run these in `verify` instead and keep unit/integration ownership explicit.

### Fixture Verification

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter `
  -Dtest=ConsoleRestFixtureCorpusTest test
```

Intentional regeneration:

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter `
  -Dtest=ConsoleRestFixtureCorpusTest `
  -Dbifrost.console.fixtures.regenerate=true test
```

Run the regeneration command twice. The second run must produce no change under
`bifrost-console-fixtures/application-rest/`.

### Full Exit Commands

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter -am verify
.\mvnw.cmd verify
```

No external environment variable is required by automated tests. Manual smoke
testing uses an externally supplied `BIFROST_OBSERVABILITY_API_KEY`.

## Exit Criteria

- [x] The pre-change starter suite passes before test-first work begins.
- [ ] The active page-two test exists and fails before the internal traversal
  correction.
- [ ] Each later implementation slice has a focused red test before production
  behavior is added.
- [ ] All unit, architecture, MockMvc, random-port, fixture, starter, and reactor
  tests pass after implementation.
- [ ] Disabled and non-web applications create no routes, instance identity,
  catalog/grace scheduler, or enabled observation and retain ordinary mapped
  skill behavior.
- [ ] Invalid configuration and every collision produce no partial route or
  partial observation/grace activation.
- [ ] Host and Bifrost authentication remain distinguishable; security contexts
  are restored and cannot leak across sequential or concurrent requests.
- [ ] API keys, authentication headers, YAML, paths, raw exception details, and
  diagnostic payloads are absent from problems, response metadata, logs, and
  fixtures where prohibited.
- [ ] Every authenticated response has consistent no-store and instance
  metadata; authentication rejection omits identity.
- [ ] All seven routes and the namespace fallback work under root and non-root
  servlet contexts.
- [x] REST/problem bodies match the reviewed fixture corpus byte-for-byte.
- [x] `consoleCompatibilityVersion` equals the complete Maven version including
  qualifiers and remains a stable top-level status field.
- [ ] Default/min/max/invalid/continued/empty/mutated/byte-limited collection
  cases pass; bodies never exceed 16 MiB or cut an item.
- [ ] Changed-instance cursors are stale; malformed/wrong-scope cursors are
  invalid; deletion gaps do not become stale.
- [ ] Active live failure blocks active list/detail only.
- [x] Internal paths, artifacts, ordinals, exceptions, and framework objects do
  not enter wire DTOs.
- [x] The exact seven-type Application API and empty Supported SPI/bean-override
  allowlists remain protected.
- [x] The obsolete two-argument active traversal is absent, with no shim,
  fallback, or dual behavior.
- [x] Skill YAML syntax and authoring semantics remain unchanged; no
  `ai/skill-authoring/` update or evidence test is required.
- [x] Fixture regeneration is deterministic: a second regeneration creates no
  diff.
- [x] `WF-SE-R1`, `WF-SE-R2`, `WF-SE-R3`, `WF-SE-R6`, `WF-SE-R7`,
  `WF-SE-R9`, `WF-SP-R2`, `WF-SP-R7`, `WF-SP-R8`, and `WF-SP-R9` have
  named representative evidence without claiming later-PR requirements.
- [ ] Manual servlet, context-path, collision, host-security, cursor-restart,
  and log-review steps are complete.

## References

- Implementation plan:
  `ai/thoughts/plans/2026-07-25-bifrost-console-pr-04-spring-rest-adapter.md`
- Ticket:
  `ai/thoughts/tickets/bifrost-console-pr-04-spring-rest-adapter.md`
- Research:
  `ai/thoughts/research/2026-07-24-spring-rest-adapter.md`
- Phase 1:
  `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md`
- Phase 2:
  `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md`
- Phase 3:
  `ai/thoughts/phases/bifrost_console_phase_3_llm_runtime_inspector.md`
- Workflows:
  `ai/thoughts/phases/bifrost_console_workflows.md`
- Framework compatibility policy:
  `ai/thoughts/framework-feature-design-lens.md`
