# Bifrost Console PR 03 — Skill and Finalized-Trace Catalogs Testing Plan

## Change Summary

- Capture each registered YAML skill’s discovery origin and exact startup bytes without changing existing manifest parsing, validation, identity, or disabled-mode startup behavior.
- Build an explicitly constructed registered-skill inspection catalog with strict UTF-8 text, safe root-relative `sourcePath`, exact-name lookup, and name-ascending keyset traversal.
- Replace internal void trace finalization with a typed optional finalized-artifact descriptor.
- Add core-owned completion-grace retention for `NEVER` and successful `ONERROR` traces while preserving existing zero-grace behavior.
- Add a TTL-governed, current-process-only finalized-trace metadata catalog with ordinals, direct lookup, and newest-first high-water traversal.
- Publish a finalized descriptor before releasing one availability-enriched terminal activity, while preserving core failure propagation, optional-observability isolation, and guaranteed active-entry removal.
- Preserve the PR 03/PR 04 boundary: no Spring observability activation, bound `bifrost.observability.*` properties, routes, HTTP DTOs, opaque transport cursors, or Java-to-Go protocol changes are introduced.

## Impacted Areas

- Skill discovery and immutable source capture:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillDefinition.java`
  - New discovery/source metadata helpers.
- Registered-skill observability catalog:
  - New types under `com.lokiscale.bifrost.internal.runtime.observation.catalog`.
- Core trace finalization and retention:
  - `ExecutionTraceHandle`
  - `DefaultExecutionTraceHandle`
  - `BifrostSession`
  - `BifrostSessionRunner`
  - New finalized-artifact and completion-grace types.
- Trace metadata catalog and expiration:
  - New catalog entry, slice, interface, implementation, clock, and scheduling behavior.
- Observation terminal coordination:
  - `ObservationCompletionDisposition`
  - `DefaultExecutionObservationHandle`
  - `DefaultExecutionObservationHandleFactory`
  - `ExecutionActivity`
- Internal constructor and test-double callers of the changed finalization and observation contracts.
- Architecture classification and auto-configuration boundary tests.
- Current-release canonical NDJSON fixture generation and semantic-coherence regression coverage.

## Risk Assessment

### High-Risk Behaviors

- **False artifact availability:** terminal activity could say `AVAILABLE` before catalog publication completes, after a failed finalization, or after the file has already been removed.
- **Core behavior regression:** adding grace or catalog collaborators could change execution results, completed-journal behavior, suppressed exceptions, or zero-grace `NEVER`/`ONERROR` deletion.
- **Ownership inversion:** catalog expiry or shutdown could delete a core-owned artifact, or the catalog could scan/adopt files rather than accepting only core-issued descriptors.
- **Concurrency and exact-once behavior:** conflicting close calls or concurrent completions could duplicate terminal activities, descriptors, catalog entries, or ordinals.
- **Lock expansion:** catalog publication, cleanup scheduling, or other optional work could accidentally execute while the session lock is held.
- **Unsafe path disclosure:** a `sourcePath` could expose an absolute path, URI scheme, drive, JAR location, configured root, or traversal segment.
- **Mutable/reloaded YAML:** catalog detail could reread a changed resource or reserialize the parsed manifest instead of returning the exact startup-captured UTF-8 representation.
- **Expiration races:** lookup or later pages could return an entry at/after effective expiration, or newly inserted traces could shift an in-progress traversal.
- **Scheduler lifecycle:** shutdown might run pending deletion, leak worker threads, drain work contrary to design, or delete sibling files.

### Medium-Risk Edge Cases

- A classpath root resolves to several concrete roots under `classpath*:`; the wrong root could produce an unstable or host-specific `sourcePath`.
- Exact-file locations have no pattern suffix and must use only the filename.
- Multiple discovery roots may intentionally produce the same descriptive `sourcePath`.
- Strict UTF-8 catalog construction must not silently replace malformed bytes, while ordinary disabled-mode loading remains as compatible as the existing parser permits.
- Metadata TTL must reject zero/negative values; completion grace must accept zero and reject negative values.
- Duration addition may overflow `Instant`.
- Descriptor sizing or grace scheduling may fail after `TRACE_COMPLETED` is appended.
- Duplicate trace publication must distinguish an identical idempotent repeat from conflicting reuse of an opaque trace ID.
- Ordinals must fail instead of wrapping and must not be consumed repeatedly by idempotent publication.
- Cleanup sweep lag must not extend visible availability because lookup/list filtering is authoritative.
- Enriching terminal details must retain bounded immutable activity constraints.

### Protected Compatibility Paths

- **Application API:** `SkillTemplate` invocation and the seven approved `com.lokiscale.bifrost.api` types remain unchanged. Evidence: `SupportedSurfaceIntegrationTest` and `BifrostPublicSurfaceArchitectureTest`.
- **Configuration and manifest contracts:** `bifrost.skills.locations`, exact case-sensitive YAML names, validation order, arbitrary supported Spring resource patterns, and `execution-trace.persistence` remain supported. Evidence: `YamlSkillCatalogTests`, `BifrostAutoConfigurationTests`, generated configuration metadata, and root documentation.
- **Disabled runtime behavior:** starter auto-configuration continues to create the ordinary runner with no enabled observation catalog and zero completion grace. Existing synchronous persistence behavior remains the default.
- **Ephemeral diagnostic coherence:** canonical trace records, ordering, terminal facts, chunk storage, reader behavior, and committed Java-produced fixtures remain current-release coherent. Evidence: `ExecutionTraceHandleTest` and `ConsoleTraceFixtureCorpusTest`.
- **Core failure semantics:** completed-journal projection and canonical finalization failures preserve current propagation and suppression. Evidence: `BifrostSessionRunnerTest`.

### Intentionally Removed Obsolete Paths

- `ExecutionTraceHandle.finalizeTrace(Map<String,Object>)` returning `void`.
- Observation success disposition without a typed optional finalized descriptor.
- Internal trace-handle/factory call sites that can only express immediate retention.

These are internal or accidentally exposed implementation surfaces. Tests must update callers atomically to the typed model and must not require old and new methods, overloads, bridges, deprecated aliases, or dual finalization behavior to coexist.

### Java-to-Go Boundary

No REST, SSE, acquisition, problem, or consumed-NDJSON contract changes in PR 03. No Go tests, release-string mismatch tests, or new cross-language fixtures are required. Existing Java fixture byte-for-byte and semantic tests are retained to prove that the internal finalization changes do not alter current-release NDJSON.

### Skill-Authoring Documentation Evidence

The implementation plan classifies skill-authoring documentation impact as **No impact**. No tests are added merely to support new author-facing prose. Existing exact-name, manifest, and trace tests remain the evidence that PR 03 did not change author-facing behavior.

## Existing Test Coverage

- `YamlSkillCatalogTests`
  - Protects exact case-sensitive public names, deterministic current discovery ordering, duplicate registered-name rejection, missing-resource behavior, manifest parsing/validation, and defensive manifest copies.
  - Gap: no retained discovery origin, exact byte capture, source-path derivation, strict UTF-8 inspection view, or name-ascending inspection traversal.
- `ExecutionTraceHandleTest`
  - Protects the `NEVER`/`ONERROR`/`ALWAYS` matrix at immediate deletion, distinct file identity, append-after-completion rejection, logical publication after complete chunk append, and write-failure behavior.
  - Gap: no typed descriptor, completion grace, scheduler lifecycle, size/expiration facts, or delayed deletion failures.
- `BifrostSessionRunnerTest`
  - Protects runner defaults, terminal records, failure outcomes, observation attachment, optional failure isolation, core-failure dispositions, and finalization deletion failures.
  - Gap: no descriptor carriage, post-lock catalog publication, enriched terminal availability, or grace-enabled runner composition.
- `DefaultExecutionObservationHandleTest`
  - Protects held completion release, exceptional observation-ended activity, exact-once concurrent close, fail-closed live projection, sanitized diagnostics, terminal publication failure, and active removal.
  - Gap: no trace catalog collaborator, publication-before-availability ordering, unavailable reasons, application expiration, or catalog-failure isolation.
- `InMemoryActiveExecutionRegistryTest`
  - Supplies the intended stable ordinal, newest-first high-water, exhaustion, and concurrent update pattern.
  - Gap: trace catalog additionally needs expiration, exclusive `beforeOrdinal`, idempotent publish, and immutable finalized entries.
- `ExecutionObservationConcurrencyTest`
  - Exercises 128 concurrent authoritative sessions repeatedly and verifies no sampling, unique cursor ordering, and cleanup.
  - Gap: no corresponding concurrent finalized catalog publication or exact-once terminal/catalog pairing.
- `ConsoleTraceFixtureCorpusTest`
  - Proves committed Java-produced fixtures match the writer byte-for-byte and satisfy current consumed semantics.
  - Gap: the direct finalization calls must move to the typed signature without changing fixture bytes.
- `BifrostPublicSurfaceArchitectureTest` and `BifrostAutoConfigurationBoundaryTest`
  - Protect the narrow Application API, absence of Supported SPI, classification of public internal types, safe API signatures, and absence of `@ConditionalOnMissingBean`.
  - Gap: new public-for-internal descriptor/catalog/lifecycle types must be classified and kept out of supported signatures.
- `BifrostAutoConfigurationTests`
  - Protects current starter bean creation and framework-owned composition.
  - Gap: explicitly assert PR 03 does not activate catalog/grace infrastructure or add observability configuration beans.

## Bug Reproduction / Failing Test First

This is a new feature and internal contract replacement, not a correction of one existing incorrect behavior. There is no single bug reproduction test.

Implementation should still proceed test-first in the following lowest-cost sequence:

1. Add `YamlSkillCatalogTests#capturesStartupBytesOnceForObservability` and the first `DefaultRegisteredSkillCatalogTest` contract. They initially fail because definitions do not retain source bytes/origin and the inspection catalog does not exist.
2. Add the parameterized persistence/grace matrix in `ExecutionTraceHandleTest`. It initially fails because finalization returns no descriptor and supports no grace.
3. Add `InMemoryFinalizedTraceCatalogTest#publishesDescriptorWithOrdinalAndEffectiveExpiration`. It initially fails because the catalog does not exist.
4. Add `DefaultExecutionObservationHandleTest#publishesCatalogEntryBeforeAvailableTerminalActivity`. It initially fails because the close disposition carries no descriptor and the handle has no catalog collaborator.
5. Add the full lifecycle integration test only after the unit seams exist.

The first new test may fail to compile while the intended internal type/signature is absent. Do not avoid that failure by using reflection or preserving the obsolete void API.

### First Failing Test Outline

- **Type:** Unit
- **Location:** `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalogTests.java`
- **Name:** `capturesStartupBytesOnceForObservability`
- **Arrange:** Create a counting mutable Spring `Resource` containing a valid mapped-skill YAML with comments and CRLF line endings. Configure a `YamlSkillCatalog` with a resolver returning that resource.
- **Act:** Initialize the core catalog, mutate or disable the backing resource, and access the retained source bytes from the resulting definition.
- **Assert:** The resource stream was opened exactly once and the retained defensive bytes equal the original content byte-for-byte.
- **Expected failure before implementation:** `YamlSkillDefinition` has no retained source/origin contract, so the test cannot compile against the intended accessor or cannot retrieve the bytes without reopening the resource.

## Test Infrastructure and Determinism

- Use JUnit 5, AssertJ, `@TempDir`, `ApplicationContextRunner`, and `OutputCaptureExtension`, matching current repository conventions.
- Add a small test-only `MutableClock` under the nearest test package or as a nested helper. Time-based catalog tests must advance the clock directly and invoke a package-private purge hook; they must not sleep.
- Inject a package-private `ScheduledExecutorService`/scheduler adapter into retention and catalog implementations for deterministic unit tests. Use a recording executor that captures delay, task, cancellation, rejection, and shutdown calls.
- Inject a package-private exact-path deletion function where needed to simulate later `IOException` without platform-specific file-permission tricks. This remains a test seam, not a public SPI or Spring bean.
- Use one bounded real-executor test with a latch and generous timeout only to prove the production scheduled executor executes and closes. Do not make the main suite depend on wall-clock timing.
- Use temporary directories with sibling sentinel files to prove exact-path-only deletion and absence of recursive cleanup.
- Use `CountDownLatch` and virtual-thread executors for repeatable concurrent publication/close tests, following `ExecutionObservationConcurrencyTest`.
- Build any realistic JAR resource fixture inside the test using `ZipOutputStream` and an isolated `URLClassLoader`; do not add a committed binary JAR or network dependency.
- Test source strings that require exact preservation by comparing original UTF-8 bytes with `catalog.yaml().getBytes(StandardCharsets.UTF_8)`, including UTF-8 BOM, CRLF, comments, blank lines, and trailing newline.
- For the disabled-mode UTF-8 boundary, use a valid YAML resource encoded with a UTF-16 BOM that the existing Jackson parser accepts. Assert ordinary skill loading still succeeds while optional registered-skill catalog construction rejects it as not strict UTF-8.

## Tests to Add or Update

### 1. Capture Immutable Startup Skill Source

- **Type:** Unit/integration-style component test
- **Location:** `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalogTests.java`
- **Tests:**
  - `capturesStartupBytesOnceForObservability`
  - `returnsDefensiveCopiesOfCapturedSourceBytes`
  - `retainsConfiguredLocationForEachDiscoveredResource`
  - `preservesExistingResourceOrderingAndDuplicateNameFailure`
- **What it proves:**
  - Parsing and later inspection share one startup byte capture.
  - Mutating returned bytes cannot modify catalog state.
  - The matched configuration origin survives discovery without becoming public metadata.
  - Existing registered-name identity, resource ordering, and validation behavior remain protected.
- **Fixtures/data:** Counting mutable `Resource`; current classpath pattern fixtures; duplicate-name fixtures.
- **Mocks:** A minimal `ResourcePatternResolver` test double returning explicit resources in a chosen order.
- **Contract classification:** Configuration and manifest contracts plus Internal or accidentally exposed implementation.
- **Compatibility expectation:** Protect existing loading/validation behavior; add source capture without a second read.

### 2. Derive and Reject Skill Source Paths

- **Type:** Unit
- **Location:** `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/SkillSourcePathResolverTest.java`
- **Tests:**
  - `relativizesClasspathPatternAgainstNonPatternRoot`
  - `relativizesFilesystemPatternAndNormalizesSeparators`
  - `choosesLongestConcreteRootForClasspathStar`
  - `usesFilenameForExactResourceLocation`
  - `producesSameRelativePathForExplodedAndJarResources`
  - `allowsDuplicateRelativePathsFromDifferentRoots`
  - `rejectsSchemeDriveAbsoluteEmptyDotAndDotDotResults`
  - `doesNotIncludeConfiguredRootOrJarLocation`
- **What it proves:**
  - The approved root rule is deterministic across supported Spring resource forms.
  - `sourcePath` is safe descriptive metadata, not a filesystem or URI locator.
  - `WF-SP-R9` is enforced at the Java source-metadata boundary.
- **Fixtures/data:** Temporary directory tree, temporary JAR with `/skills/incidents/check_dns.yml`, custom malformed URI resources, Windows-style separator strings exercised independently of host OS.
- **Mocks:** Custom `Resource`/resolved-root values only for malformed cases; use real Spring resources for normal paths.
- **Contract classification:** Configuration and manifest contracts.
- **Compatibility expectation:** Existing arbitrary location patterns continue loading while unsafe optional catalog construction fails clearly.

### 3. Build the Registered-Skill Catalog

- **Type:** Unit
- **Location:** `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/DefaultRegisteredSkillCatalogTest.java`
- **Tests:**
  - `returnsUnchangedUtf8YamlCapturedAtStartup`
  - `rejectsNonUtf8SourceWithoutChangingCoreCatalogLoad`
  - `looksUpOnlyByExactRegisteredName`
  - `listsSummariesByRegisteredNameAscendingAfterExclusiveName`
  - `allowsDuplicateSourcePathsWithDistinctRegisteredNames`
  - `rejectsNonPositiveLimits`
  - `returnsEmptyForMissingNameAndPastEnd`
  - `listSummaryDoesNotContainYamlResourceOrEffectiveDefinition`
- **What it proves:**
  - YAML is authoritative unchanged text and is not reread or reserialized (`WF-SP-R7`, `WF-SP-R8`).
  - Exact registered name remains identity; `sourcePath` is never an input key.
  - Pagination semantics are deterministic and transport-neutral.
- **Fixtures/data:** UTF-8 BOM/CRLF/comment YAML, UTF-16 BOM YAML for strict-decoder rejection, definitions with names intentionally out of discovery order, two roots with the same relative filename.
- **Mocks:** Construct from real `YamlSkillDefinition` objects or the smallest factory produced by `YamlSkillCatalog`; no mocking of text transformation.
- **Contract classification:** Configuration and manifest contracts.
- **Compatibility expectation:** Preserve exact-name and manifest behavior; add inspection-only service semantics.

### 4. Preserve Disabled Starter Configuration

- **Type:** Integration
- **Location:** `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
- **Tests:**
  - `pr03DoesNotActivateObservabilityCatalogsOrCompletionGrace`
  - `defaultRunnerStillUsesImmediateOnErrorPersistenceBehavior`
  - `pr03AddsNoObservabilityConfigurationProperties`
- **What it proves:**
  - PR 03 does not silently absorb PR 04 activation/property work.
  - Existing default startup and `execution-trace.persistence` behavior remain intact.
  - No partial routes, catalog beans, scheduler beans, or `bifrost.observability.*` metadata are exposed.
- **Fixtures/data:** Existing `ApplicationContextRunner`; generated Spring configuration metadata resource.
- **Mocks:** None.
- **Contract classification:** Configuration and manifest contracts.
- **Compatibility expectation:** Protected path.

### 5. Validate Finalized Artifact Descriptor

- **Type:** Unit
- **Location:** `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/core/FinalizedTraceArtifactTest.java`
- **Tests:**
  - `acceptsCompleteRetainedArtifactFacts`
  - `rejectsBlankIdentitiesNegativeSizeNullRequiredFactsAndInvalidExpiration`
  - `retainsExactInternalPathWithoutSerializingOrNormalizingIt`
- **What it proves:**
  - The core-issued descriptor is immutable, internally complete, and cannot represent invalid availability facts.
  - The path remains an internal exact reference, not an ordinary catalog identifier.
- **Fixtures/data:** `@TempDir` file and fixed instants.
- **Mocks:** None.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** New coherent internal contract; no serialization compatibility promise.

### 6. Cover the Persistence Policy and Grace Matrix

- **Type:** Parameterized unit
- **Location:** `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionTraceHandleTest.java`
- **Tests:**
  - `returnsDescriptorAccordingToPolicyErrorStateAndCompletionGrace`
  - `usesCompletedRecordTimestampAndExactFinalSizeInDescriptor`
  - `zeroGracePreservesImmediateDeletionAndEmptyDescriptor`
  - `alwaysAndErroredOnErrorHaveNoCoreExpiration`
  - `rejectsNegativeOrOverflowingCompletionGrace`
  - `repeatedFinalizationDoesNotAppendRescheduleOrCreateAnotherDescriptor`
- **What it proves:**
  - Full matrix:
    - `NEVER`, grace `0` → delete synchronously, no descriptor.
    - `NEVER`, grace `>0` → descriptor with expiration, delayed deletion.
    - successful `ONERROR`, grace `0` → delete synchronously, no descriptor.
    - successful `ONERROR`, grace `>0` → descriptor with expiration, delayed deletion.
    - errored `ONERROR` → descriptor, no core expiration.
    - `ALWAYS` → descriptor, no core expiration.
  - Outcome and retention remain separate facts (`WF-X-R5`, `WF-FE-R3`).
  - Typed finalization does not change canonical terminal records or idempotence.
- **Fixtures/data:** Fixed clock, typed `TraceCompletion` values for success/failure/abort, recording grace scheduler, temp files.
- **Mocks:** Recording scheduler; real NDJSON writer.
- **Contract classification:** Ephemeral diagnostic formats and Internal or accidentally exposed implementation.
- **Compatibility expectation:** Current-run diagnostic coherence plus protected zero-grace behavior.

### 7. Test Completion-Grace Scheduling and Shutdown

- **Type:** Unit with one bounded executor integration test
- **Location:** `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ScheduledCompletionGraceRetentionTest.java`
- **Tests:**
  - `schedulesOneDeletionForExactArtifactAtExpiration`
  - `deletesOnlyExactPathAndLeavesSiblingSentinels`
  - `logsLaterDeletionFailureOnceWithoutRetryOrSensitiveMessage`
  - `rejectionFallsBackToImmediateDeletion`
  - `rejectionAndFallbackDeletionFailurePreserveCoreFailure`
  - `closeCancelsPendingTasksWithoutDeletingGraceHeldFiles`
  - `closeIsIdempotentAndRejectsNewScheduling`
  - `productionExecutorRunsDueDeletionAndTerminatesOnClose`
- **What it proves:**
  - Core alone owns delayed deletion.
  - Failure after successful finalization cannot retroactively change execution.
  - Scheduler startup/shutdown semantics match the accepted process-local abandoned-file tradeoff.
- **Fixtures/data:** Temp directory containing target plus parent/sibling sentinel files; recording/rejecting executor; injected throwing deleter; `CapturedOutput`.
- **Mocks:** Test scheduler/deleter injection. One test uses the real production executor and a latch rather than arbitrary sleep.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** Preserve zero-grace synchronous failure behavior; new nonzero grace is best-effort and process-local.

### 8. Carry Descriptors Through Core Completion Without Extending the Lock

- **Type:** Unit/integration
- **Location:** `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/core/BifrostSessionRunnerTest.java`
- **Tests:**
  - `passesDescriptorOnlyOnSuccessfulCoreFinalization`
  - `coreFailureDispositionNeverCarriesDescriptor`
  - `journalProjectionFailurePublishesNoDescriptor`
  - `completionAppendOrRetentionFailurePublishesNoDescriptor`
  - `catalogWorkBeginsAfterSessionLockIsReleased`
  - `optionalCatalogOrObservationFailureDoesNotChangeSuccessfulResult`
  - `existingFailureStillReceivesCleanupFailureAsSuppressed`
- **What it proves:**
  - A descriptor cannot escape a failed core completion.
  - Existing journal/finalization failure semantics remain unchanged.
  - Optional catalog work is outside the session serialization boundary.
- **Fixtures/data:** Existing injecting trace handle/projector helpers updated to typed finalization; blocking observation/catalog collaborator with latches; fixed clock.
- **Mocks:** Purpose-built internal test doubles, not mocking `BifrostSession` itself.
- **Contract classification:** Internal or accidentally exposed implementation and Ephemeral diagnostic formats.
- **Compatibility expectation:** Preserve core failure path; atomically remove the obsolete void finalizer.

### 9. Validate Trace Catalog Publication and Expiration

- **Type:** Unit
- **Location:** `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/InMemoryFinalizedTraceCatalogTest.java`
- **Tests:**
  - `startsEmptyAndPublishesOnlyExplicitDescriptor`
  - `publishesDescriptorWithOrdinalAndEffectiveExpiration`
  - `usesEarlierCatalogExpirationWhenArtifactHasNoEarlierExpiration`
  - `usesEarlierCoreExpirationWhenPresent`
  - `rejectsZeroNegativeAndOverflowingMetadataTtl`
  - `rejectsMissingOrNonRegularArtifactAtPublication`
  - `identicalDuplicatePublicationIsIdempotentWithoutConsumingOrdinal`
  - `conflictingDuplicateTraceIdDoesNotReplaceOriginal`
  - `expiredLookupIsIndistinguishableFromMissing`
  - `lookupAndListExcludeExactBoundaryBeforeSweep`
  - `purgeRemovesMetadataWithoutDeletingArtifact`
  - `closeClearsMetadataAndNeverDeletesArtifacts`
- **What it proves:**
  - The catalog is descriptor-fed, current-process-only, and not a filesystem scanner.
  - Catalog TTL and core retention are independent.
  - Expiration never extends availability and never transfers deletion ownership.
  - Missing/expired facts remain appropriately limited (`WF-X-R10`, `WF-FE-R8`).
- **Fixtures/data:** Mutable clock, temp files, descriptors with/without core expiration, sibling sentinels, recording sweep executor.
- **Mocks:** Injected clock and deterministic scheduler only.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** New process-local service; no persisted or cross-restart contract.

### 10. Validate Trace Keyset Traversal and Concurrency

- **Type:** Unit/concurrency
- **Location:** `InMemoryFinalizedTraceCatalogTest.java`
- **Tests:**
  - `firstPageCapturesHighWaterAndSortsNewestFirst`
  - `laterPageUsesSameHighWaterAndExclusiveBeforeOrdinal`
  - `newPublicationDoesNotEnterExistingTraversal`
  - `expirationBetweenPagesMayRemoveEntryWithoutOffsetShift`
  - `failsInsteadOfWrappingCatalogOrdinal`
  - `concurrentIndependentPublicationsReceiveUniqueOrdinals`
  - `concurrentIdempotentPublicationCreatesOneEntryAndOneOrdinal`
- **What it proves:**
  - PR 04 can create opaque cursors over stable keyset facts without needing offsets or server-side page sessions.
  - Concurrent completion does not duplicate or wrap ordinals.
- **Fixtures/data:** 128 unique descriptors, mutable clock, barrier/latch-controlled executor.
- **Mocks:** None beyond deterministic clock.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** New transport-neutral traversal contract for later internal adapter use.

### 11. Enrich Terminal Activity Only After Catalog Publication

- **Type:** Unit
- **Location:** `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandleTest.java`
- **Tests:**
  - `publishesCatalogEntryBeforeAvailableTerminalActivity`
  - `reportsUnavailableNotRetainedWhenDescriptorIsAbsent`
  - `reportsCatalogPublicationFailureWithoutFailingExecutionOrLiveMonitoring`
  - `coreFailureDiscardsHeldCompletionAndReportsUnavailable`
  - `availableTerminalCarriesEffectiveExpirationAndNoInternalPath`
  - `terminalEnrichmentRemainsImmutableAndWithinRetainedWeightLimit`
  - `terminalReplayFailureStillFailsClosedAndRemovesActiveEntry`
  - `catalogFailureStillRemovesActiveEntry`
  - `concurrentConflictingClosePublishesAtMostOneTerminalAndCatalogEntry`
- **What it proves:**
  - `AVAILABLE` is truthful at publication time.
  - At most one outward terminal activity exists; there is no early completion plus later availability event.
  - Catalog failure is isolated from execution and live-monitoring availability when the truthful unavailable terminal can still be published.
  - Core failure remains a distinct noncanonical observation-ended event.
- **Fixtures/data:** Recording catalog and replay buffer sharing an ordered operation log; throwing catalog; descriptors for retained and nonretained cases; existing trace records.
- **Mocks:** Small in-memory fakes implementing the catalog/replay interfaces.
- **Contract classification:** Internal or accidentally exposed implementation and Ephemeral diagnostic formats.
- **Compatibility expectation:** Extend the approved PR 02 lifecycle while preserving exact-once close and fail-closed live publication.

### 12. Exercise the Complete Catalog Lifecycle

- **Type:** Integration
- **Location:** `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/ObservabilityCatalogLifecycleTest.java`
- **Tests:**
  - `successfulAlwaysExecutionCatalogsBeforeAvailableTerminalAndRemovesActiveEntry`
  - `failedOnErrorExecutionCatalogsRetainedArtifactAndKeepsOutcomeSeparate`
  - `successfulNeverWithZeroGraceReportsUnavailableAndCreatesNoEntry`
  - `successfulNeverWithGraceExpiresFromCatalogAndDeletesOnlyAtCoreExpiration`
  - `catalogMetadataCanExpireBeforeAlwaysArtifactWithoutDeletingIt`
  - `coreFinalizationFailureCreatesNoCatalogEntryAndRemovesActiveState`
  - `shutdownCancelsGraceAndNewCatalogDoesNotAdoptLeftoverFile`
  - `concurrentCompletionsPairOneTerminalWithOneCatalogEntryPerTrace`
- **What it proves:**
  - The complete procedural ordering and ownership model works with real trace writer, session runner, observation handle, replay buffer, registry, and catalogs.
  - Restart begins with an empty supported catalog.
  - `NEVER`, `ONERROR`, `ALWAYS`, grace, metadata TTL, outcome, and application availability remain distinct.
  - The tests support `WF-FE-R3`, `WF-FE-R8`, `WF-X-R5`, and the finalized-trace portion of `WF-FAILED-EXECUTION`.
- **Fixtures/data:** Fixed/mutable clock; isolated temp trace paths; deterministic retention and cleanup executors; real `BifrostSessionRunner` composition.
- **Mocks:** Only scheduling/time controls and targeted failure injectors; use real core/observation/catalog implementations for the main path.
- **Contract classification:** Ephemeral diagnostic formats and Internal or accidentally exposed implementation.
- **Compatibility expectation:** Current-run coherence and protected execution/finalization behavior.

### 13. Preserve High-Concurrency Observation Guarantees

- **Type:** Repeated concurrency integration
- **Location:** `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionObservationConcurrencyTest.java`
- **Tests:**
  - Extend `representsEveryBlockedLiveSessionWithoutSamplingAndRemovesAllAfterRelease`
  - Add `catalogsEveryRetainedConcurrentCompletionWithoutDuplicateTerminalActivity`
- **What it proves:**
  - PR 03 does not add observability admission, sampling, omission, or hidden concurrency limits.
  - Catalog ordinals, activity cursors, trace IDs, and session IDs remain unique and correlated under concurrent completion.
- **Fixtures/data:** Existing 128 virtual-thread scenario, using `ALWAYS` or nonzero grace so each execution produces a descriptor.
- **Mocks:** Real in-memory registry, replay buffer, and trace catalog; deterministic cleanup scheduler.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** Preserve PR 02 authoritative concurrency and cleanup behavior.

### 14. Preserve Canonical Trace Fixtures

- **Type:** Regression/integration
- **Location:** `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ConsoleTraceFixtureCorpusTest.java`
- **Tests:**
  - Update direct calls to typed `TraceCompletion`.
  - Retain `generatedCorpusMatchesCommittedFixturesByteForByte`.
  - Retain semantic validity/invalidity and usage tests unchanged.
- **What it proves:**
  - Removing the obsolete finalization signature does not change canonical NDJSON bytes, terminal ordering, consumed enums, chunks, failure links, or usage semantics.
- **Fixtures/data:** Existing committed `bifrost-console-fixtures` corpus.
- **Mocks:** None.
- **Contract classification:** Ephemeral diagnostic formats.
- **Compatibility expectation:** Current-release writer/fixture coherence, not historical compatibility.

### 15. Protect Architecture and SPI Boundaries

- **Type:** Architecture
- **Locations:**
  - `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java`
  - `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostAutoConfigurationBoundaryTest.java`
- **Tests:**
  - Update `everyExternallyAccessibleTopLevelTypeIsClassified`.
  - Update `technicallyPublicInternalTypesHaveNonblankReasons`.
  - Extend bounded immutable DTO checks to catalog summary/activity types where applicable.
  - Retain `noSupportedSpiPackageOrTypeExists`.
  - Retain `apiSignaturesRecursivelyExcludeInternalAndAutoconfigureTypes`.
  - Retain `supportedBifrostBeanOverrideAllowlistIsEmpty`.
  - Retain `productionTypesDoNotUseConditionalOnMissingBean`.
- **What it proves:**
  - New public-for-Java-collaboration types remain classified internal.
  - No descriptor, `Path`, catalog, scheduler, or observation type leaks through supported Application API signatures.
  - PR 03 adds no Supported SPI or replaceable Spring bean.
- **Fixtures/data:** Reflection/ArchUnit inspection of compiled production classes.
- **Mocks:** None.
- **Contract classification:** Application API, Supported SPI, and Internal or accidentally exposed implementation.
- **Compatibility expectation:** Protect the narrow supported API/SPI posture while permitting the approved internal atomic change.

### 16. Prove Obsolete Internal Finalization Is Gone

- **Type:** Compile-time/architecture
- **Location:** `BifrostPublicSurfaceArchitectureTest.java` or a focused `ExecutionTraceHandleContractTest.java`
- **Tests:**
  - `executionTraceHandleExposesOnlyTypedFinalization`
  - `observationSuccessDispositionCarriesTypedOptionalDescriptor`
- **What it proves:**
  - The old `void finalizeTrace(Map<String,Object>)` path was removed rather than retained behind an overload or bridge.
  - Observation close has one coherent descriptor-aware contract.
- **Fixtures/data:** Reflection over exact declared methods and return/parameter types.
- **Mocks:** None.
- **Contract classification:** Internal or accidentally exposed implementation.
- **Compatibility expectation:** Approved removal.

## Test Execution Order

Run in this order during implementation so failures remain local:

1. Skill capture and path/catalog unit tests.
2. Descriptor and completion-grace unit tests.
3. Trace catalog unit/concurrency tests.
4. Observation handle and session-runner tests.
5. Full observability lifecycle test.
6. Architecture, auto-configuration, and canonical fixture regression tests.
7. Entire starter module.
8. Entire repository.

## How to Run

### Focused Skill Catalog Tests

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCatalogTests,SkillSourcePathResolverTest,DefaultRegisteredSkillCatalogTest" test
```

### Focused Finalization and Retention Tests

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=FinalizedTraceArtifactTest,ExecutionTraceHandleTest,ScheduledCompletionGraceRetentionTest,BifrostSessionTest,BifrostSessionRunnerTest" test
```

### Focused Catalog and Terminal Lifecycle Tests

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=InMemoryFinalizedTraceCatalogTest,DefaultExecutionObservationHandleTest,ExecutionObservationConcurrencyTest,ObservabilityCatalogLifecycleTest" test
```

### Compatibility and Architecture Regression Tests

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCatalogTests,BifrostAutoConfigurationTests,BifrostAutoConfigurationBoundaryTest,BifrostPublicSurfaceArchitectureTest,SupportedSurfaceIntegrationTest,ConsoleTraceFixtureCorpusTest" test
```

### Full Starter Module

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter test
```

### Full Repository

```powershell
.\mvnw.cmd verify
```

## Required Profiles, Environment, and Test Data

- Java 21 or newer and Maven 3.9+ through the checked-in wrapper.
- No external model provider, database, network service, Spring profile, API key, or environment variable is required.
- Use existing test YAML resources and the committed `bifrost-console-fixtures` corpus.
- Create temporary YAML files, JAR resources, trace files, and sentinel files only under JUnit `@TempDir`.
- Tests must close all executors, URL class loaders, streams, and catalogs in `finally`/try-with-resources so the suite does not leak threads or lock temporary files on Windows.
- Concurrency tests use bounded timeouts only as deadlock protection, not as correctness timing.

## Manual Verification

1. Load representative skills from an exploded classpath root, a temporary JAR root, and a filesystem root. Compare returned YAML bytes and verify every `sourcePath` is relative, normalized, and non-locating.
2. Run a short nonzero-grace `NEVER` execution. Confirm the terminal activity reports available only after catalog lookup succeeds, then confirm catalog expiry and core file deletion occur at their independently configured boundaries.
3. Run `ALWAYS`, allow catalog metadata to expire, and confirm the file remains while lookup becomes unavailable.
4. Close the grace scheduler before a pending deletion, reconstruct a new catalog, and confirm the leftover file is neither deleted nor adopted.
5. Inspect generated configuration metadata and the application context to confirm PR 03 introduced no `bifrost.observability.*` property or enabled adapter bean.

## Exit Criteria

- [ ] Each staged failing contract test is observed failing before its corresponding implementation, without preserving the obsolete void finalization API to make tests compile.
- [ ] All new and updated tests pass after implementation.
- [ ] Exact startup YAML bytes are captured once, remain immutable, decode strictly for inspection, and are never reread or reserialized.
- [ ] All approved source-path forms and rejection cases pass, including real exploded/JAR resources and `WF-SP-R9`.
- [ ] Existing YAML identity, validation, discovery, and disabled-mode configuration tests pass unchanged in meaning.
- [ ] The complete persistence-policy/grace matrix passes, including zero-grace compatibility, scheduler rejection fallback, delayed deletion failure, and shutdown cancellation.
- [ ] No descriptor or catalog entry is produced for failed core finalization.
- [ ] Trace catalog direct lookup, expiration, idempotence, ordinal exhaustion, concurrency, high-water, and between-page removal behavior are covered.
- [ ] Catalog expiration and shutdown never delete or mutate a core-owned artifact.
- [ ] An `AVAILABLE` terminal activity is emitted only after the same trace is obtainable from the catalog.
- [ ] Catalog publication failure remains isolated, produces one truthful unavailable terminal activity, preserves live monitoring when replay succeeds, and removes active state.
- [ ] Core finalization failure produces only the noncanonical observation-ended terminal path and removes active state.
- [ ] Concurrent completion produces at most one terminal activity and one catalog entry per trace without sampling or omission.
- [ ] Optional catalog publication is proven to occur after the session lock is released.
- [ ] Canonical fixture generation remains byte-for-byte stable and all current semantic fixture tests pass.
- [ ] Supported Application API and configuration/manifest paths pass; no Supported SPI, `@ConditionalOnMissingBean`, active observability bean, or `bifrost.observability.*` property is introduced.
- [ ] Reflection/architecture coverage proves the obsolete void finalization signature and descriptor-less success path are absent, not retained as compatibility overloads.
- [ ] No changed skill-authoring guidance requires evidence because no authoring documents changed.
- [ ] Manual verification steps are complete.
- [ ] `.\mvnw.cmd -pl bifrost-spring-boot-starter test` passes.
- [ ] `.\mvnw.cmd verify` passes.

## References

- Implementation plan: `ai/thoughts/plans/2026-07-24-bifrost-console-pr-03-observability-catalogs.md`
- Ticket: `ai/thoughts/tickets/bifrost-console-pr-03-observability-catalogs.md`
- Research: `ai/thoughts/research/2026-07-24-bifrost-console-pr-03-observability-catalogs.md`
- Phase design: `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md`
- Workflow requirements: `ai/thoughts/phases/bifrost_console_workflows.md`
- Framework design lens: `ai/thoughts/framework-feature-design-lens.md`
