# PR 06 Finalized Artifact Streaming and Phase 1 Integration Testing Plan

## Change Summary

- Add authenticated
  `GET /_bifrost/observability/v1/traces/{traceId}/artifact`.
- Stream the exact finalized NDJSON bytes with fixed response metadata and no
  whole-artifact buffering.
- Add a core-owned acquisition lease so a download admitted before expiration
  finishes before an otherwise-due grace deletion.
- Limit artifact delivery to eight concurrent process-wide downloads with no
  queue and a five-minute transfer timeout.
- Release the lease, admission, task, and Servlet async context exactly once on
  success, failure, disconnect, timeout, setup rejection, and shutdown.
- Commit deterministic SSE and artifact transport fixtures while reusing the
  existing trace corpus.
- Wire disabled-by-default sample configuration and executable Spring Security
  pass-through.
- Provide an evidence matrix for all Phase 1 completion criteria.

## Impacted Areas

- **Core artifact lifecycle**
  - `CompletionGraceRetention`
  - `ScheduledCompletionGraceRetention`
  - `ImmediateCompletionRetention`
  - `ObservabilityActivationCoordinator`
- **Current-process trace acquisition**
  - `FinalizedTraceCatalog`
  - `InMemoryFinalizedTraceCatalog`
  - `FinalizedTraceCatalogEntry`
  - `DefaultExecutionObservationHandleFactory`
- **Servlet boundary**
  - `ObservabilityApiPaths`
  - `ObservabilityAccessService`
  - `ObservabilityRestController`
  - `ObservabilityArtifactDelivery`
  - `ObservabilityArtifactStream`
  - `ObservabilityDeliveryLimits`
  - `ObservabilityRouteRegistrar`
  - `ObservabilityRuntime`
  - `ObservabilityApiKeyFilter`
  - `BifrostObservabilityWebAutoConfiguration`
- **Cross-boundary fixtures**
  - Existing `bifrost-console-fixtures/traces/` and `application-rest/`
  - New `application-sse/` and `application-artifact/`
- **Sample and security**
  - `bifrost-sample/pom.xml`
  - sample `application.yml`
  - sample `SecurityFilterChain`
  - sample context/security/architecture tests
- **Documentation and completion evidence**
  - root and sample READMEs
  - fixture README
  - Phase 1 design, roadmap, and completion-evidence matrix

## Behaviors Changing

### User-visible

- A cataloged trace gains an authenticated attachment endpoint.
- Success returns exact NDJSON bytes with:
  - `application/x-ndjson; charset=utf-8`;
  - `Content-Disposition:
    attachment; filename="bifrost-trace-<traceId>.ndjson"`;
  - the known `Content-Length`;
  - `Cache-Control: no-store`; and
  - `X-Bifrost-Instance-Id`.
- Unsupported method, query, range, conditional, or `Accept` shapes fail with
  `400/INVALID_REQUEST` before body commitment.
- Unknown, expired, raced-deleted, and otherwise unobtainable artifacts share
  `404/NOT_FOUND`; no tombstone distinction is added.
- The ninth simultaneous authenticated artifact download fails immediately with
  `429/LIMIT_EXCEEDED`.
- A pre-expiry download may finish after the effective deadline, while a new
  request at/after the deadline fails.
- A malformed semantic artifact still downloads unchanged; semantic rejection
  remains future Go PR 13 behavior.

### Internal

- Core grace deletion becomes lease-aware and platform-independent.
- Catalog metadata lookup and exact-file lease acquisition become one
  coordinated current-process operation after HTTP admission.
- Artifact delivery owns finite async work and cleanup independently from SSE.
- Runtime shutdown closes artifact delivery before completion retention and
  catalog metadata.
- Existing internal constructors/interfaces are changed atomically with no
  compatibility overload or legacy behavior.

## Risk Assessment

| Risk | Severity | Why it matters | Primary evidence |
| --- | --- | --- | --- |
| Core deletes a grace-held file while a pre-expiry download is active | Critical | Can truncate evidence or leave platform-specific undeleted files | Deterministic lease/expiry unit tests plus embedded-server expiry test |
| Lease or admission leaks on overlapping Servlet callbacks | Critical | Permanently consumes capacity and can indefinitely defer core deletion | Controlled callback unit tests and post-cancellation replacement download |
| A pre-commit failure becomes a committed/partial response | High | Go cannot safely distinguish an application problem from corrupt transport | Controller/stream unit tests and real-server problem assertions |
| A post-commit failure attempts to write JSON over NDJSON | High | Produces a mixed invalid artifact and can leak internal diagnostics | Stream failure test and client-observed termination |
| Download capacity accidentally shares SSE state | High | Artifact load could evict live monitoring or vice versa | Independent-limit unit/integration tests |
| Admission occurs before authentication or unknown-resource lookup | High | Leaks capacity behavior and violates settled error precedence | Authentication/not-found tests while all eight slots are occupied |
| Catalog expiry and core expiry disagree | High | New downloads may start after one authoritative deadline | Mutable-clock catalog tests plus controlled retention deadline |
| Header injection or filesystem filename/path exposure | High | Security and boundary violation | Adversarial opaque-ID disposition test and DTO/header/path assertions |
| Whole artifact is buffered | High | Unbounded memory growth for large diagnostic files | Tracking stream unit test and large-file integration smoke |
| Body differs from the canonical writer output | High | Breaks PR 12 acquisition and PR 13 semantic consumption | Byte comparison against committed trace fixtures |
| Transfer timeout/cancellation leaves a task or file open | High | Resource leak and delayed deletion | Injected timeout/cancellation unit tests; no five-minute wall-clock wait |
| Shutdown closes retention before active downloads | High | Reintroduces expiry/deletion races or hangs shutdown | Runtime close-order test |
| Existing REST/SSE/authentication behavior regresses | High | Protected current-release Java-to-Go boundary | Existing REST, SSE, filter, context-path, and host-security suites |
| Release identity or fixture association drifts | High | Future Go must reject mismatched Java before acquisition | Release metadata/status fixture tests and artifact fixture association |
| Sample Spring Security unexpectedly protects public sample routes | Medium | Changes representative application behavior | Sample real-server security test |
| Test depends on Unix unlink or Windows sharing behavior | High | Race test becomes nonportable | Tests drive explicit lease/timer state, never raw OS open-file semantics |
| Async/concurrency test is timing-flaky | High | False confidence and unstable CI | Latches, captured tasks, mutable clocks, bounded polling; no arbitrary sleeps |
| Sensitive key, payload, or path reaches logs/problems | High | Diagnostic boundary can expose secrets/content | Captured-output failure tests and manual log review |

## Contract and Compatibility Test Scope

| Surface | Classification | Testing obligation |
| --- | --- | --- |
| Seven `com.lokiscale.bifrost.api` types | Application API | Run starter public-surface and sample API-usage architecture tests; assert no signature delta. |
| Observability beans and internal lease/delivery types | Supported SPI: none | Assert no new replaceable bean or top-level public internal type; do not preserve old internal constructors through overloads. |
| Existing `bifrost.observability.*` properties | Configuration and manifest contracts | Run default/opt-in/invalid activation tests; assert no new property and no changed default. |
| Artifact route, headers, problems, REST/SSE fixture bodies | Persisted or serialized cross-component boundary for the exact current release, not durable history | Test exact observable bytes, headers, status/code meanings, and deterministic fixtures. |
| Canonical NDJSON trace corpus | Ephemeral diagnostic format | Preserve current writer/reader/projector coherence and exact current-checkout fixtures; do not add old-schema or cross-version tests. |
| Retention, catalog, runtime, controller, delivery, async stream | Internal or accidentally exposed implementation | Replace tests that encode direct scheduled deletion with lease-aware behavior; require one coherent implementation and absence of legacy route/constructor/fallback. |

### Protected Compatibility Paths

- Existing instance, skill, active-execution, trace metadata, and SSE resources.
- Exact `consoleCompatibilityVersion` emitted from filtered Maven release
  metadata.
- Stable application problem status/code meanings.
- Existing four observability properties, defaults, and opt-in validation.
- Current trace writer and the committed 10-valid/8-invalid fixture corpus.
- `Cache-Control: no-store` and authenticated instance identity.
- Servlet context-path behavior and host-security namespace pass-through.
- The seven supported Application API types.

### Approved Internal Replacements

- Direct timer-owned `Files.deleteIfExists` without reader state is replaced by
  lease-aware due deletion.
- Existing internal retention/catalog constructors are updated atomically.
- No raw-file-open alternative, old artifact URL, alias, overload, or fallback
  is retained.
- Tests must not require the former implementation and new lease behavior
  simultaneously.

### Java-to-Go Coordination

PR 06 can verify the Java producer side now:

- exact status compatibility string;
- exact acquisition route and metadata;
- stable application problem meanings;
- exact artifact bytes;
- deterministic REST/SSE/artifact fixture inventories; and
- unchanged consumed-NDJSON corpus.

The Go mismatch-rejection behavior itself belongs to PR 09 because no Go target
client exists in this checkout. The PR 06 artifact fixture metadata must
associate the response with the same complete release string found in
`application-rest/instance-status.json`, and the completion-evidence matrix must
name the downstream PR 09 test obligation: on a non-exact match, Go makes no
snapshot, SSE, catalog, or artifact request. PR 06 must not add Java-side version
negotiation or an artifact-specific version to simulate that future consumer.

## Existing Test Coverage

### Core lifecycle

- `ScheduledCompletionGraceRetentionTest` covers zero-grace synchronous
  deletion, nonzero retention, close cancellation, and negative grace.
- `ExecutionTraceHandleTest` covers persistence policies, finalized descriptor
  size, and finalization behavior.
- `InMemoryFinalizedTraceCatalogTest` covers publication, listing, effective
  earlier expiry, missing files, metadata expiry without deletion, and close
  serialization.
- `ObservabilityActivationCoordinatorTest` covers enabled/disabled delegation.

**Gap**: no test represents a reader, lease, acquisition-start time, timer/read
race, multiple readers, or last-reader deletion.

### Servlet delivery and security

- `ObservabilityActivityDeliveryTest` covers fixed SSE admission, immediate
  rejection, exact-once release, dispatcher behavior, and shutdown.
- `ObservabilityActivityStreamTest` covers Servlet async ownership,
  pre-/post-ownership failures, write readiness, timeout, and overlapping
  callbacks.
- `ObservabilityRestIntegrationTest` covers authentication, release identity,
  request bounds, problems, catalogs, and path-free DTOs.
- `ObservabilitySseIntegrationTest` uses a real server for handshake/replay,
  16-stream admission, cancellation/closure, and live-failure isolation.
- `ObservabilityApiKeyFilterTest` covers duplicate/oversized keys, context
  restoration, stable problems, and sanitized unexpected failures.
- `ObservabilityHostSecurityIntegrationTest` covers host `permitAll`,
  independent Bifrost authentication, forwards, and host-owned business routes.
- `ObservabilityContextPathIntegrationTest` covers servlet context scoping.

**Gap**: no finite file stream, artifact media/disposition/length headers,
download admission, short-read handling, socket cancellation, or artifact route
exists.

### Fixtures and release identity

- `ConsoleTraceFixtureCorpusTest` deterministically generates and validates the
  semantic trace corpus.
- `ConsoleRestFixtureCorpusTest` deterministically generates REST/problem bodies.
- `BifrostReleaseVersionTest`, `ObservabilityRestIntegrationTest`, and the
  committed instance-status fixture assert `0.1.0-SNAPSHOT`.

**Gap**: no committed SSE framing or artifact transport metadata fixture exists.

### Sample and public surface

- `SampleApplicationTests` loads the sample and invokes a mapped YAML skill
  through `SkillTemplate` without a provider call.
- `SupportedApiUsageArchitectureTest` rejects sample dependencies on Bifrost
  internal/auto-configuration packages.
- `BifrostPublicSurfaceArchitectureTest` allowlists technically public internal
  top-level types.

**Gap**: the sample has no enabled-observability real-server test and no
executable host-security pass-through.

## Bug Reproduction / Failing Test First

### Primary red test

- **Name**:
  `downloadsExactFinalizedArtifactWithRequiredHeaders`
- **Type**: integration, real embedded servlet server
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityArtifactIntegrationTest.java`
- **Arrange**:
  - start the existing opt-in test application on a random port;
  - write a fixed small NDJSON file under `@TempDir`;
  - publish a matching `FinalizedTraceArtifact` into the enabled runtime catalog;
  - send authenticated GET to
    `ObservabilityApiPaths.TRACES + "/trace-1/artifact"` with
    `Accept: application/x-ndjson`.
- **Act**: receive the response with `HttpClient.BodyHandlers.ofByteArray()`.
- **Assert**:
  - status 200;
  - exact content type, disposition, length, no-store, and instance header;
  - body byte-for-byte equals the file;
  - body and headers do not expose the internal path.
- **Expected failure pre-fix**: current route registration falls through the
  reserved namespace handler and returns `404/NOT_FOUND` JSON.
- **Why first**: this is the cheapest stable black-box proof that the requested
  product behavior is absent; it does not depend on the eventual internal lease
  shape.

### First lifecycle red test after the minimal lease signature compiles

- **Name**:
  `leaseOpenedBeforeDeadlineDefersDueDeletionUntilClose`
- **Type**: unit
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ScheduledCompletionGraceRetentionTest.java`
- **Arrange**:
  - inject a mutable clock and manually controlled scheduled executor through a
    package-private constructor;
  - retain a file with a known deadline;
  - acquire one `CompletionGraceRetention.ArtifactLease` immediately before the
    deadline.
- **Act**:
  - advance the clock to the deadline;
  - execute the captured due-deletion task;
  - read the complete lease;
  - close it.
- **Assert**:
  - the exact path exists and remains readable while leased;
  - no new lease is admitted at the deadline;
  - closing the final lease triggers one exact-path deletion;
  - reader/deletion state is reclaimed.
- **Expected failure before lease behavior**: the current scheduled task either
  unlinks the file while it is open or fails deletion without a last-reader
  retry, depending on platform.
- **Portability rule**: the final test uses only the retention-owned lease and
  controlled task; it must not assert raw OS open-file behavior.

### First cleanup red test after async streaming compiles

- **Name**:
  `overlappingTimeoutErrorAndCompletionReleaseResourcesExactlyOnce`
- **Type**: unit
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityArtifactStreamTest.java`
- **Arrange**: mocked `AsyncContext`, controlled executor task, mocked lease and
  admission, and captured `AsyncListener`.
- **Act**: invoke timeout, error, and task-completion callbacks concurrently
  behind a latch.
- **Assert**: lease close, admission close, task cancellation, and async
  completion occur at most once; no JSON problem write occurs after ownership.
- **Expected failure before cleanup coordination**: independent callbacks can
  double-close/release or leave one resource owned.

## Test Design Rules

- Use JUnit 5, AssertJ, Mockito, `@TempDir`, `@Timeout`, and the existing Spring
  Boot test conventions.
- Drive concurrency with `CountDownLatch`, barriers, captured executor tasks,
  and bounded polling. Do not use arbitrary `Thread.sleep`.
- Inject `Clock`, scheduler/executor, and timeout through package-private
  constructors. Do not add a public test seam or replaceable Spring bean.
- Use a real embedded server only for behavior mocks cannot establish:
  actual committed headers/body, socket disconnect, host filter order, context
  path, and process-wide concurrent connections.
- Unit-test the five-minute timeout with an injected/captured timeout value; do
  not wait five minutes in CI.
- Reuse `bifrost-console-fixtures/traces/`; do not copy fixture bodies.
- Normal automated tests use fixed non-secret keys and make no model-provider
  or external-network calls.
- Give representative tests `@DisplayName` metadata using
  `WF-FAILED-EXECUTION`, `WF-FE-R3`, `WF-FE-R4`, `WF-FE-R8`,
  `WF-FE-R9`, or the exact Phase 1 criterion text where appropriate.
- Do not create a new requirement identifier namespace for Phase 1 criteria.

## Tests to Add or Update

### 1. Lease-aware grace retention

- **Type**: unit
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ScheduledCompletionGraceRetentionTest.java`
- **Tests**:
  - `leaseOpenedBeforeDeadlineDefersDueDeletionUntilClose`
  - `expiryWinsAndRejectsNewLeaseBeforeDeleting`
  - `multipleLeasesDeleteOnlyAfterFinalClose`
  - `leaseCloseIsIdempotent`
  - `openFailureRollsBackReaderAndRunsDueDeletion`
  - `nonExpiringArtifactLeaseNeverTransfersDeletionOwnership`
  - `closeCancelsPendingDeletionWithoutDeletingAndRejectsNewLease`
  - preserve
    `zeroGraceDeletesSynchronouslyAndNonzeroCloseCancelsWithoutDeleting`
- **What it proves**:
  - acquire-wins and expiry-wins interleavings;
  - no new reader at/after core deadline;
  - last-reader deletion occurs exactly once;
  - `ALWAYS`/errored `ONERROR` files are opened but never deleted by the lease
    service;
  - open failure and idempotent close cannot leak state;
  - shutdown retains the accepted no-special-deletion behavior.
- **Fixtures/data**: small exact files under `@TempDir`; fixed descriptor IDs and
  instants.
- **Mocks**: manually controlled `ScheduledExecutorService` and mutable `Clock`;
  use a real file/channel.
- **Contract classification**: Internal or accidentally exposed implementation.
- **Compatibility expectation**: approved internal replacement; preserve core
  retention outcomes, not the former direct-delete decomposition.

### 2. Trace-handle and activation regression

- **Type**: unit
- **Locations**:
  - `ExecutionTraceHandleTest.java`
  - `ObservabilityActivationCoordinatorTest.java`
- **Tests**:
  - keep persistence-policy and exact-size descriptor cases;
  - add
    `disabledObservabilityDeletesImmediatelyAndCannotAcquireArtifact`;
  - add
    `enabledActivationDelegatesRetentionAndAcquisitionToOneRuntimeOwner`;
  - add
    `closingActivationStopsAcquisitionBeforeRetentionClose`.
- **What it proves**: opt-in behavior does not alter disabled execution
  retention; the same runtime collaborator owns retain/acquire; close order is
  safe.
- **Fixtures/data**: small finalized traces and fake runtime collaborators.
- **Mocks**: mocks are appropriate for delegation/order; filesystem behavior
  remains covered by test 1.
- **Contract classification**: Configuration and manifest contracts plus
  internal implementation.
- **Compatibility expectation**: protected disabled/default behavior; approved
  internal constructor update.

### 3. Atomic catalog acquisition

- **Type**: unit
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/InMemoryFinalizedTraceCatalogTest.java`
- **Tests**:
  - `acquiresPathFreeMetadataAndCoreLeaseBeforeEffectiveExpiry`
  - `catalogExpiryRejectsNewAcquisitionWithoutCancellingExistingLease`
  - `coreExpiryRejectsNewAcquisitionEvenWhenMetadataRemains`
  - `leaseFailureAfterMetadataLookupReturnsNoAcquisitionAndKeepsCatalogCoherent`
  - `sweepCanRemoveMetadataWhileAcquiredLeaseFinishes`
  - `closeRejectsAcquisitionAndClearsOnlyMetadata`
  - preserve listing, idempotent publication, missing file, conflict, and
    earlier-deadline tests.
- **What it proves**: effective expiration, metadata/deletion ownership
  separation, current-process scope, and no path exposure in acquisition output.
- **Fixtures/data**: `@TempDir`, mutable clock, fake/real lease collaborator.
- **Mocks**: mock retention only for exact call/order/failure cases; use the real
  scheduled retention in one composition test.
- **Contract classification**: Internal implementation supporting the
  current-release serialized acquisition boundary.
- **Compatibility expectation**: current-run acquisition coherence; no
  tombstone or historical lookup.

### 4. Artifact delivery admission

- **Type**: unit
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityArtifactDeliveryTest.java`
- **Tests**:
  - `rejectsNinthDownloadWithoutQueuingAndReclaimsSlot`
  - `artifactAndSseAdmissionsAreIndependent`
  - `setupFailureReleasesAdmissionBeforeTaskOwnership`
  - `executorRejectionReleasesAdmissionAndLease`
  - `shutdownRejectsNewAdmissionCancelsTransfersAndStopsOwnedTasks`
  - `repeatedAdmissionCloseDoesNotUnderflowCount`
- **What it proves**: fixed limit 8, no queue, independent SSE limit 16,
  exact-once accounting, and finite shutdown.
- **Fixtures/data**: eight admissions, controlled executor, fake leases.
- **Mocks**: controlled/mock executor; no servlet mocks here.
- **Contract classification**: Persisted or serialized acquisition behavior at
  the boundary; implementation remains internal.
- **Compatibility expectation**: new protected current-release limit; no
  configurable or legacy capacity path.

### 5. Servlet async artifact stream lifecycle

- **Type**: unit
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityArtifactStreamTest.java`
- **Tests**:
  - `streamsExactBytesInBoundedChunksAndCompletes`
  - `shortReadTerminatesCommittedResponseWithoutPaddingOrProblemBody`
  - `writeFailureClosesOnlyOwnedTransfer`
  - `failureBeforeAsyncOwnershipPropagatesForJsonProblemMapping`
  - `failureAfterAsyncOwnershipNeverWritesJsonProblem`
  - `timeoutInterruptsTaskAndReleasesLeaseAndAdmission`
  - `clientErrorReleasesLeaseAndAdmission`
  - `overlappingTimeoutErrorAndCompletionReleaseResourcesExactlyOnce`
  - `configuredServletTimeoutIsExactlyFiveMinutes`
- **What it proves**:
  - O(buffer) copying and exact byte order;
  - no full read, rewrite, reconstruction, padding, or mixed JSON/NDJSON;
  - clear pre-/post-commit failure boundary;
  - timeout/callback cancellation and exact-once cleanup.
- **Fixtures/data**:
  - tracking input stream that records maximum requested read size;
  - premature-EOF stream;
  - output stream that fails after a chosen byte;
  - small representative NDJSON bytes.
- **Mocks**: mocked Servlet request/response, `AsyncContext`,
  `AsyncListener`, lease, admission, and controlled executor.
- **Contract classification**: Persisted or serialized acquisition boundary
  plus internal implementation.
- **Compatibility expectation**: exact current-release transport semantics.

### 6. Request-shape, problem, and header helpers

- **Type**: unit/MockMvc integration
- **Locations**:
  - `ObservabilityRestIntegrationTest.java`
  - focused header/request helper test if extracted
  - `ObservabilityApiKeyFilterTest.java`
- **Tests**:
  - parameterized accepted `Accept`: absent, `*/*`,
    `application/x-ndjson`, compatible media range with positive quality;
  - parameterized rejected `Accept`: JSON-only, zero-quality NDJSON, malformed;
  - reject query parameters, duplicate query values, Range and all six
    conditional variants;
  - reject HEAD, POST, and fallback suffixes;
  - `safeDispositionEncodesOpaqueTraceIdWithoutHeaderInjection`;
  - authenticated pre-commit problems carry no-store and instance ID;
  - missing/duplicate/invalid keys do not disclose instance identity;
  - host lookalike authority cannot call `TRACE_ARTIFACT_READ`.
- **What it proves**: exact request contract, stable safe problems, safe
  filename, authentication precedence, and header centralization.
- **Fixtures/data**: normal UUID-like ID plus an internal test descriptor with
  quotes, Unicode, and header-delimiter characters that remain valid as an
  opaque catalog string; URL-encode the path segment.
- **Mocks**: use MockMvc for pre-async validation; direct helper test for
  disposition encoding.
- **Contract classification**: Persisted or serialized application-to-console
  boundary.
- **Compatibility expectation**: new protected route behavior; preserve
  existing problem codes and authentication semantics.

### 7. Exact artifact response over a real server

- **Type**: integration
- **Location**:
  `ObservabilityArtifactIntegrationTest.java`
- **Tests**:
  - `downloadsExactFinalizedArtifactWithRequiredHeaders`
  - parameterized
    `streamsRepresentativeFixtureByteForByte(String fixture)` covering:
    - `single-attempt-success.ndjson`
    - `terminal-failure.ndjson`
    - `terminal-abort.ndjson`
    - `advisor-retry.ndjson`
    - `chunked-payload.ndjson`
    - `unattributed-usage.ndjson`
    - `malformed-json.ndjson`
    - `incomplete-chunks.ndjson`
  - `malformedArtifactStillDownloadsAsRawEvidence`
  - `unknownExpiredAndRacedDeletedArtifactsReturnNotFoundBeforeCommit`
  - `downloadHeadersNeverExposeArtifactPath`
- **What it proves**: real filter/controller/async/HTTP interaction, exact
  bytes, headers, current-process availability, and semantic-opacity of Java
  transport.
- **Fixtures/data**: reference committed corpus directly using the fixture-root
  convention already used by corpus tests.
- **Mocks**: none; publish descriptors into the real enabled runtime.
- **Contract classification**: Persisted or serialized current-release
  Java-to-Go boundary and Ephemeral diagnostic format.
- **Compatibility expectation**: exact byte and metadata contract; no
  cross-version promise.

### 8. Real-server admission, cancellation, and expiration

- **Type**: integration
- **Location**:
  `ObservabilityArtifactIntegrationTest.java`
- **Tests**:
  - `rejectsNinthAuthenticatedDownloadAndReclaimsCancelledSlots`
  - `unknownResourceReturnsNotFoundEvenWhenAllDownloadSlotsAreOccupied`
  - `invalidAuthenticationDoesNotConsumeOrRevealDownloadCapacity`
  - `preExpiryDownloadFinishesAndNewAcquisitionAfterExpiryIsNotFound`
  - `clientDisconnectReleasesOnlyItsAdmissionAndLease`
- **What it proves**: socket-level admission/error precedence, cancellation,
  expiry continuity, and isolation between transfers.
- **Fixtures/data**:
  - deterministic generated multi-megabyte file larger than servlet/socket
    buffering for held connections;
  - committed small trace for replacement request;
  - short test grace and explicit latches/polling exposed by package-private
    runtime counters.
- **Mocks**: none.
- **Flake controls**:
  - use `@Timeout(30)`;
  - wait for `admittedCount()` with a monotonic deadline;
  - never infer server ownership merely from request submission;
  - close every response stream in `finally`;
  - do not wait for the production five-minute timeout.
- **Contract classification**: Persisted or serialized acquisition behavior and
  internal lifecycle.
- **Compatibility expectation**: fixed limit/error precedence and
  acquisition-time authorization are protected current-release semantics.
- **Workflow evidence**: `WF-FE-R4`, `WF-FE-R8`, `WF-FE-R9`, `WF-X-R12`.

### 9. Runtime shutdown ordering

- **Type**: unit/integration
- **Locations**:
  - new `ObservabilityRuntimeTest.java`
  - `ObservabilityArtifactDeliveryTest.java`
- **Tests**:
  - `closesActivityThenArtifactDeliveryBeforeRetentionAndCatalog`
  - `shutdownInterruptsActiveTransferBeforeRetentionClose`
  - `shutdownFailurePreservesFirstFailureAndSuppressesLaterFailures`
- **What it proves**: dependency-safe order, finite cancellation, and existing
  close failure aggregation.
- **Fixtures/data**: ordered mock `AutoCloseable` collaborators and one blocked
  transfer.
- **Mocks**: Mockito `InOrder` for order; controlled executor/latch for active
  transfer.
- **Contract classification**: Internal implementation.
- **Compatibility expectation**: approved internal update; preserve shutdown
  error propagation.

### 10. Route registration, context path, and host security

- **Type**: integration
- **Locations**:
  - `ObservabilityRouteRegistrarTest.java`
  - `ObservabilityContextPathIntegrationTest.java`
  - `ObservabilityHostSecurityIntegrationTest.java`
- **Tests**:
  - exact artifact mapping is registered under the reserved namespace;
  - mapping rollback removes artifact route with every other route;
  - route is available only beneath configured servlet context;
  - host `permitAll` reaches Bifrost key authentication;
  - business routes remain host-owned;
  - forwards/async dispatch cannot start the same download twice.
- **What it proves**: namespace ownership, context-path behavior, filter order,
  and no authentication bypass.
- **Fixtures/data**: existing test applications plus one published artifact.
- **Mocks**: registration unit test may mock handler mapping; security/context
  tests use a real server.
- **Contract classification**: Configuration and manifest contract plus
  serialized HTTP boundary.
- **Compatibility expectation**: preserve existing namespace and host-security
  behavior while adding one exact child resource.

### 11. Public surface and Spring extension boundary

- **Type**: architecture
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java`
- **Tests/assertions**:
  - public Application API allowlist remains unchanged;
  - no new top-level public internal type is added for the lease;
  - `CompletionGraceRetention.ArtifactLease` remains nested internal
    collaboration;
  - no observability artifact bean uses `@ConditionalOnMissingBean`;
  - sample production code imports no Bifrost internal or auto-configuration
    package.
- **What it proves**: the change does not accidentally create an API/SPI or
  extension promise.
- **Fixtures/data**: compiled classes/annotations.
- **Mocks**: none.
- **Contract classification**: Application API, Supported SPI, and accidental
  public exposure.
- **Compatibility expectation**: protected API unchanged; no new SPI; approved
  internal signature replacement without shim.

### 12. Deterministic SSE fixtures

- **Type**: fixture contract test
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ConsoleSseFixtureCorpusTest.java`
- **Fixture inventory**:
  - `application-sse/handshake.sse`
  - `application-sse/activity-trace-completed.sse`
  - `application-sse/activity-core-finalization-failed.sse`
  - `application-sse/replay.sse`
- **What it proves**: production framing, IDs, event names, JSON data, terminal
  availability, and noncanonical finalization-failure semantics are
  deterministic for future Go PR 11.
- **Fixtures/data**: fixed instance UUID, timestamps, cursors, activity
  envelopes; generation calls production framing methods.
- **Mocks**: none.
- **Contract classification**: Persisted or serialized current-release SSE
  boundary.
- **Compatibility expectation**: exact current-release fixture; no historical
  framing reader.

### 13. Deterministic artifact transport fixture

- **Type**: fixture contract test
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ConsoleArtifactFixtureCorpusTest.java`
- **Fixture inventory**:
  - `application-artifact/download-response.json`
  - body reference to `../traces/single-attempt-success.ndjson`
- **What it proves**:
  - exact method/path/status/header metadata;
  - exact safe filename and content length;
  - same `consoleCompatibilityVersion` association as instance status;
  - referenced body exists and is byte-identical to real streamed output;
  - no duplicate NDJSON body exists in the transport directory.
- **Fixtures/data**: production header builder and committed trace/status
  fixtures.
- **Mocks**: none.
- **Contract classification**: Persisted or serialized acquisition boundary
  plus Ephemeral diagnostic body.
- **Compatibility expectation**: executable Java producer agreement consumed
  later by PRs 09/12; no manifest/version added to runtime.

### 14. Preserve trace semantic corpus coherence

- **Type**: fixture/unit regression
- **Location**:
  `ConsoleTraceFixtureCorpusTest.java`
- **Tests**:
  - retain complete 10-valid/8-invalid inventory;
  - retain writer byte comparison and semantic expected results;
  - assert artifact transport tests reference, rather than copy, this corpus;
  - keep schema/version absence.
- **What it proves**: transport addition does not change current trace
  semantics or introduce historical compatibility machinery.
- **Fixtures/data**: existing traces and expected results.
- **Mocks**: none.
- **Contract classification**: Ephemeral diagnostic format.
- **Compatibility expectation**: current writer/fixture/consumer coherence;
  obsolete schema behavior remains absent.

### 15. Integrated Phase 1 lifecycle

- **Type**: integration, real embedded server
- **Location**:
  `ObservabilityPhaseOneIntegrationTest.java`
- **Tests**:
  - `availableTerminalActivityAlreadyHasDownloadableArtifact`
  - `coreFinalizationFailurePublishesIncompleteObservationWithoutArtifact`
  - `liveFailureLeavesSkillTraceCatalogAndArtifactOperationsUsable`
- **Scenario outline**:
  1. authenticate and read exact instance compatibility status;
  2. establish active baseline and SSE handshake;
  3. append representative execution activity;
  4. complete observation with a retained artifact;
  5. observe one terminal activity with separate outcome and availability;
  6. list/detail/download the artifact;
  7. expire it and receive `NOT_FOUND`;
  8. separately exercise `CORE_FINALIZATION_FAILED`;
  9. mark live monitoring unavailable and prove independent artifact operations.
- **What it proves**: Phase 1 completion criteria 1, 3-10 relevant to this PR,
  plus concurrency/isolation/authorization. Existing REST tests remain evidence
  for skill catalog criterion 2.
- **Fixtures/data**: one committed valid trace, one finalization-failure
  observation, fixed key.
- **Mocks**: use production runtime components; inject failure only at the
  documented core-finalization seam.
- **Contract classification**: Coordinated serialized REST/SSE/acquisition
  boundary plus Ephemeral diagnostic artifact.
- **Compatibility expectation**: protected exact-release Phase 1 behavior.
- **Workflow evidence**: `WF-FAILED-EXECUTION`, especially `WF-FE-R3`,
  `WF-FE-R4`, `WF-FE-R8`, and `WF-X-R10`.

### 16. Sample opt-in and host-security behavior

- **Type**: integration and architecture
- **Locations**:
  - new
    `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/ObservabilitySampleIntegrationTest.java`
  - `SampleApplicationTests.java`
  - `SupportedApiUsageArchitectureTest.java`
- **Tests**:
  - `defaultSampleStartsWithObservabilityDisabledAndNoKey`
  - `enabledSampleRequiresBifrostKeyThroughHostPermitAll`
  - `mappedSkillProducesCatalogedDownloadableArtifact`
  - `sampleBusinessRoutesRemainPublicUnderExplicitHostRules`
  - architecture test remains green.
- **What it proves**: disabled default, externalized opt-in, no provider call,
  real sample trace/catalog/download path, and non-regression of business
  routes.
- **Fixtures/data**: test property key, mapped `expenseLookup`, random port.
- **Mocks**: none; no external provider call.
- **Contract classification**: Configuration contract and Application API.
- **Compatibility expectation**: existing sample invocation/public routes
  preserved; observability remains opt-in.

### 17. Phase 1 completion-evidence matrix validation

- **Type**: documentation/evidence review backed by automated command results
- **Location**:
  `ai/thoughts/phases/bifrost_console_phase_1_completion_evidence.md`
- **Checks**:
  - all ten numbered Phase 1 criteria have named evidence;
  - concurrency, failure isolation, bounds/lifecycle, and authorization have
    named evidence;
  - automated entries name exact test methods and commands;
  - manual entries specify environment, action, and observable pass condition;
  - statuses become “passing” only after recorded commands succeed;
  - `WF-FAILED-EXECUTION` links use existing IDs;
  - PR 09 exact-version mismatch rejection remains an explicit downstream gate,
    not falsely marked as Java-tested.
- **Contract classification**: All affected categories as an evidence index.
- **Compatibility expectation**: no second requirement namespace or unsupported
  claim.

## Mocking and Test-Seam Strategy

| Concern | Strategy |
| --- | --- |
| Core time | Inject mutable `Clock`; never sleep until expiry. |
| Scheduled deletion | Capture scheduled runnable with a controlled executor and run it deterministically. |
| File semantics | Use real `@TempDir` files and lease API; never depend on platform unlink behavior. |
| Admission | Real counter/semaphore with controlled admissions; mock only downstream task start. |
| Servlet callbacks | Mockito Servlet API plus captured listener/executor tasks for all callback permutations. |
| Fixed-size streaming | Tracking input/output streams that record read sizes and exact bytes. |
| Socket cancellation | Real embedded server and Java `HttpClient` response stream close. |
| Held real downloads | Generated body larger than response buffers, wait for runtime admitted count, always clean up in `finally`. |
| Host security/context path | Real embedded server; no mocked filter chain. |
| Fixture generation | Production codecs/framing/header builders with fixed identities and timestamps. |
| Full lifecycle failure | Inject only at the existing core finalization seam; keep adapter/runtime production-real. |
| Sample execution | Use mapped `expenseLookup`; no model/provider stub is needed. |

No new public test-only hooks, replaceable framework beans, environment-specific
filesystem assumptions, or network calls are allowed.

## Manual Verification

### Sample acquisition

1. Set a noncommitted key of at least 32 printable ASCII characters and enable
   observability through environment variables.
2. Start `bifrost-sample`.
3. Invoke a mapped sample skill that needs no provider.
4. Authenticate to instance status and confirm the complete release string.
5. List trace catalog metadata, select the opaque trace ID, and download the
   artifact.
6. Confirm filename, content type, length, no-store, instance header, and
   parseable line-delimited bytes.
7. Confirm no ordinary response exposes the internal path.

### Cancellation and expiration

1. Run with a short completion-grace TTL in a disposable local test
   configuration.
2. Start a throttled artifact download before expiry.
3. Cross the effective deadline and confirm a new request receives
   `404/NOT_FOUND`.
4. Confirm the first download completes, then confirm core deletion occurs
   after release.
5. Repeat with client cancellation and confirm a replacement request can use
   the released slot.

### Host and security behavior

1. Confirm a missing/invalid Bifrost key receives the stable Bifrost 401 only
   when the request reaches the adapter.
2. Confirm host/proxy rejection remains distinguishable and does not include the
   Bifrost problem code.
3. Confirm sample business routes retain their documented host behavior.
4. Repeat under a non-root servlet context.
5. Review logs for cancellation, timeout, open failure, short read, and delete
   failure; confirm no key/header value, raw payload, or internal path appears.

### Resource behavior

1. Observe process memory while downloading an artifact substantially larger
   than the stream buffer; memory must not scale with full artifact size.
2. Open eight downloads and confirm the ninth fails immediately without
   affecting the existing eight or SSE.
3. Shut down with active downloads and confirm finite termination with no
   non-daemon artifact task left running.

## How to Run

### Prerequisites

- Java 21 or newer.
- Maven 3.9 or newer, using the repository wrapper.
- No provider credentials or external services for automated tests.
- Fixture regeneration is opt-in only.

### Red-test sequence

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter '-Dtest=ObservabilityArtifactIntegrationTest#downloadsExactFinalizedArtifactWithRequiredHeaders' test
```

Expected before implementation: test failure because the route returns the
reserved-namespace `404/NOT_FOUND`.

After the minimal lease signature/test seam compiles:

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter '-Dtest=ScheduledCompletionGraceRetentionTest#leaseOpenedBeforeDeadlineDefersDueDeletionUntilClose' test
```

Expected before lease behavior: the due deletion is not safely deferred and
completed on last release.

### Core lifecycle tests

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter '-Dtest=ScheduledCompletionGraceRetentionTest,ExecutionTraceHandleTest,InMemoryFinalizedTraceCatalogTest,ObservabilityActivationCoordinatorTest,ObservabilityRuntimeTest' test
```

### Artifact unit and integration tests

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter '-Dtest=ObservabilityArtifactDeliveryTest,ObservabilityArtifactStreamTest,ObservabilityArtifactIntegrationTest,ObservabilityRestIntegrationTest,ObservabilityContextPathIntegrationTest,ObservabilityHostSecurityIntegrationTest,ObservabilityRouteRegistrarTest' test
```

### Fixture and release tests

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter '-Dtest=BifrostReleaseVersionTest,ConsoleTraceFixtureCorpusTest,ConsoleRestFixtureCorpusTest,ConsoleSseFixtureCorpusTest,ConsoleArtifactFixtureCorpusTest' test
```

### Integrated Phase 1 and existing boundary regressions

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter '-Dtest=ObservabilityPhaseOneIntegrationTest,ObservabilityRestIntegrationTest,ObservabilitySseIntegrationTest,ObservabilityHostSecurityIntegrationTest,ObservabilityApiKeyFilterTest,BifrostPublicSurfaceArchitectureTest' test
```

### Full starter and sample verification

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter test
.\mvnw.cmd -pl bifrost-sample -am test
.\mvnw.cmd verify
```

### Intentional fixture regeneration

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter '-Dtest=ConsoleTraceFixtureCorpusTest,ConsoleRestFixtureCorpusTest,ConsoleSseFixtureCorpusTest,ConsoleArtifactFixtureCorpusTest' '-Dbifrost.console.fixtures.regenerate=true' test
git diff -- bifrost-console-fixtures
```

Run the regeneration command twice. The second run must produce no additional
diff. Review generated fixture changes; do not regenerate as an incidental step
in ordinary tests.

### Cross-platform verification

Run the focused retention and artifact integration suites on Windows and Linux.
Both must pass without conditional assertions or platform-specific expectations.
The explicit lease, not native open-file deletion behavior, is the contract.

## Exit Criteria

- [ ] The primary artifact endpoint test is committed red before production
  route implementation and fails with the expected current 404 behavior.
- [ ] The lease-expiry test is red before lease behavior and uses controlled
  time/scheduling rather than sleeps or OS unlink assumptions.
- [x] All new tests pass after implementation.
- [x] A pre-expiry lease remains completely readable across core expiration and
  the exact grace-held file is deleted once after the last lease closes.
- [x] New acquisitions fail at the earlier of core and catalog expiration.
- [x] Unknown/expired/raced resources share `404/NOT_FOUND` without tombstones.
- [x] Eight downloads are admitted, the ninth receives immediate
  `429/LIMIT_EXCEEDED`, and artifact admission is independent from SSE.
- [ ] Success, failure, short read, write failure, disconnect, timeout,
  executor rejection, overlapping callbacks, and shutdown release admission and
  lease exactly once.
- [x] Pre-commit failures remain JSON problems; post-commit failures terminate
  without mixed JSON/NDJSON.
- [x] Exact representative fixture bytes and all required response headers are
  verified over a real HTTP server.
- [x] Malformed and incomplete semantic fixtures remain downloadable unchanged.
- [x] No artifact path is used as an HTTP identifier or exposed in ordinary
  DTOs, headers, problems, or logs.
- [x] Streaming memory is bounded by the copy buffer rather than artifact size.
- [x] The fixed five-minute timeout is asserted without a five-minute test.
- [x] REST, SSE, authentication, problem, context-path, host-security, release,
  and existing fixture suites remain green.
- [x] The complete release string remains the only compatibility marker; the
  artifact fixture associates with the exact status release and adds no
  independent version.
- [x] The future PR 09 exact-mismatch rejection test obligation is recorded:
  mismatch prevents every request after status.
- [x] The current trace fixture corpus stays coherent and is referenced rather
  than copied by artifact transport fixtures.
- [x] Fixture regeneration is deterministic and the second run is diff-free.
- [x] Existing `bifrost.observability.*` properties/defaults are unchanged and
  the sample remains disabled by default without a key.
- [x] The enabled sample demonstrates host pass-through, Bifrost
  authentication, mapped skill completion, catalog publication, and download
  without a provider call.
- [x] Application API allowlist remains unchanged, no Supported SPI or
  replaceable artifact bean is added, and no compatibility shim/legacy route
  remains.
- [x] No tests preserve approved obsolete internal constructors or direct
  deletion behavior alongside the new design.
- [x] No skill-authoring documentation evidence is required because the
  implementation plan classified authoring impact as “No impact.”
- [x] Every Phase 1 completion criterion and the concurrency, isolation,
  lifecycle/bounds, and authorization gates have named passing automated or
  completed manual evidence.
- [ ] Focused tests pass on Windows and Linux.
- [x] Full repository `.\mvnw.cmd verify` passes.
- [ ] Manual sample, cancellation/expiration, security, resource, and sanitized
  logging checks are complete.

## References

- Implementation plan:
  `ai/thoughts/plans/2026-07-26-bifrost-console-pr-06-artifact-streaming-integration.md`
- Ticket:
  `ai/thoughts/tickets/bifrost-console-pr-06-artifact-streaming-integration.md`
- Research:
  `ai/thoughts/research/2026-07-26-bifrost-console-pr-06-artifact-streaming-integration.md`
- Phase 1 completion criteria:
  `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:435-450`
- Approved failed-execution workflow:
  `ai/thoughts/phases/bifrost_console_workflows.md:101-210`
- Existing lease-adjacent test:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ScheduledCompletionGraceRetentionTest.java`
- Existing async cleanup pattern:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityActivityStreamTest.java`
- Existing real-server admission pattern:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ObservabilitySseIntegrationTest.java`
- Existing cross-boundary corpus:
  `bifrost-console-fixtures/README.md`
