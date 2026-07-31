# PR 12 Central Artifact Acquisition and Trace Storage Testing Plan

## Change Summary

- Add a streaming Go application-client operation for the existing authenticated
  Java finalized-artifact endpoint.
- Add one target-scope-bound Go artifact service that joins acquisitions,
  atomically installs one immutable copy, issues one opaque handle, accounts for
  capacity, expires idle entries, pins active leases, and owns removal.
- Enforce the existing `trace-workspace.max-bytes` and
  `trace-workspace.idle-ttl` configuration, including `unlimited` and `never`.
- Add paired browser JSON operations for acquisition and Trace Storage
  management plus a separate direct-streaming raw attachment GET.
- Add Trace Storage and acquisition state to the React UI without parsing
  NDJSON or building the PR 13/14 trace explorer.
- Atomically replace obsolete fixture/consumer use of
  `artifactAvailability` with the live Java and design spelling
  `applicationTraceAvailability`.

## Requirement Traceability

Use these identifiers in new unit/integration test comments or table subtest
names and in representative Playwright test titles.

| ID | Requirement |
| --- | --- |
| PR12-R01 | A current target scope has one immutable installed copy and one opaque handle per trace. |
| PR12-R02 | Simultaneous acquisition joins one metadata load, upstream stream, installation, and capacity charge. |
| PR12-R03 | Waiters cancel independently; final-waiter, scope, and shutdown cancellation remove partial state. |
| PR12-R04 | A handle is published only after complete byte-count, identity, sync/close, current-scope, and atomic-install verification. |
| PR12-R05 | Finite aggregate capacity charges partial and complete bytes and evicts expired then deterministic LRU unpinned entries. |
| PR12-R06 | `unlimited` disables policy capacity rejection; disk-full remains a scoped storage error unless workspace safety cannot be restored. |
| PR12-R07 | Idle TTL begins at last successful use; `never` disables it; active leases defer expiry/removal. |
| PR12-R08 | Explicit removal affects only unused local copies and invalidates their handles; active use returns `ARTIFACT_IN_USE`. |
| PR12-R09 | Scope rotation, restart, and shutdown invalidate/remove all local artifacts; authentication rejection alone does not revoke a complete current-scope copy. |
| PR12-R10 | Application availability, original observation facts, and local-handle availability remain separate. |
| PR12-R11 | Raw attachment download performs a new authorized upstream stream, preserves exact bytes, and creates no local copy, handle, pin, or charge. |
| PR12-R12 | Raw download is bounded, cancellable, current-scope, safely named, no-store, and protected by the paired same-site browser boundary. |
| PR12-R13 | Local paths and credentials never enter handles, browser DTOs, headers, errors, fixtures, or logs. |
| PR12-R14 | Java, fixtures, Go, and React use only `applicationTraceAvailability`; the obsolete spelling has no reader or writer. |
| PR12-R15 | Exact umbrella release compatibility still gates every target artifact operation; no independent protocol version is added. |

## Impacted Areas

- **Java current-release observability boundary**
  - `ConsoleSseFixtureCorpusTest`
  - `ObservabilityArtifactIntegrationTest`
  - `ObservabilityArtifactDeliveryTest`
  - `ObservabilityArtifactStreamTest`
  - committed application SSE and NDJSON fixtures
- **Go upstream client and target authority**
  - `internal/applicationclient/address.go`, `client.go`, and new artifact stream
    tests
  - `internal/target/context.go`, `scope.go`, internal client fakes, and scope
    cancellation/identity tests
- **Go storage and lifecycle**
  - new `internal/artifact/` package
  - existing `internal/workspace/` safety/failure classification
  - `internal/console/service.go`, target-owner invalidation, lifecycle fatal
    propagation, shutdown cleanup
- **Go browser boundary**
  - `internal/browserapi/router.go`, request policy, error mapping, JSON
    operations, direct raw stream
  - same-release browser fixture inventory and security integration tests
- **React/TypeScript**
  - API contracts/client, trace detail, live completion affordance, navigation,
    new Trace Storage view, state reset and error handling
- **Browser workflows**
  - Playwright live-completion, target-context, storage, removal, and download
    flows
- **Documentation/build**
  - executable behavior documented in `bifrost-console/README.md`
  - canonical clean frontend/Go verification pipeline

No `ai/skill-authoring/` document changes are planned, so no authoring claim
requires new evidence.

## Risk Assessment

### Highest-Risk Behaviors

- **Concurrency ownership**: duplicate leaders, lost wakeups, cancellation of
  the wrong waiter, acquisition completing after scope rotation, deadlock
  between target invalidation and service locks, or shutdown leaking a worker.
- **Filesystem publication**: a partial/short/corrupt stream becoming visible,
  rename before close/sync, a stale leader overwriting a new scope entry, unsafe
  path construction, or cleanup escaping `transient/`.
- **Accounting correctness**: double charge, leaked reservation, underflow,
  eviction while pinned, nondeterministic LRU ties, or a failed removal being
  omitted from totals while its bytes remain.
- **Availability semantics**: upstream authentication/expiry incorrectly
  revoking local evidence, local presence masquerading as current application
  availability, or storage listing refreshing idle TTL.
- **Streaming security**: buffering a large response, forwarding unsafe
  `Content-Disposition`, accepting redirect/compression/range/conditional
  shapes, leaking the application key, or returning a JSON error after raw
  response commitment.
- **Cross-boundary drift**: Java/fixture/Go/React disagreement about artifact
  headers, instance identity, exact release compatibility, or
  `applicationTraceAvailability`.

### Important Edge Cases

- Zero-byte, shorter-than-declared, longer-than-declared, missing-length,
  duplicate-length, wrong media type, encoded, redirect, oversized problem, and
  mid-stream failure responses.
- One waiter cancels while another waits; every waiter cancels before response
  headers, during copy, and after bytes are written but before atomic commit.
- Capacity exactly fits; one byte over; an artifact cannot fit in an otherwise
  empty finite cache; pinned bytes prevent admission; expired and LRU ties;
  concurrent acquisition reservations exhaust the remaining ceiling.
- TTL exactly at the boundary; successful vs failed/cancelled use; expiry while
  pinned; `never`; removal racing acquisition/lease/expiry.
- Malformed and random opaque handles, removed handles in the current scope,
  valid handles paired with an old scope, and restarted-process handles.
- Credential rejection after installation, replacement of the credential,
  application instance rotation, target replacement, shutdown during each
  acquisition stage, and restart with abandoned prior-process files.
- Raw client disconnect before headers, after headers, and mid-body; scope
  rotation; application 404/401; unsafe trace identifier; cross-site navigation;
  missing/duplicate session; query/range/conditional headers.
- Windows file-close/rename/delete behavior and junction rejection; Unix
  permission/symlink behavior.

### Protected Compatibility Paths

- The Java artifact endpoint continues to emit exact bytes, one instance ID,
  `application/x-ndjson`, `Content-Length`, safe attachment disposition, and
  `no-store`. Evidence:
  `ObservabilityArtifactIntegrationTest` and Java-produced trace fixtures.
- Exact Java/Go `consoleCompatibilityVersion` rejection remains required before
  artifact use. Evidence: existing `applicationclient` compatibility tests and
  the Phase 2 agreement.
- Existing `trace-workspace` keys, defaults, sentinels, and validation remain
  unchanged. Evidence: `internal/config/config_test.go`,
  `documentation_test.go`, and `bifrost-console/README.md`.
- Workspace marker, lock, permissions, non-adoption, and fatal-invariant
  behavior remain protected. Evidence: `internal/workspace/*_test.go`.
- Browser Host/Origin/session/CSRF separation and no-store/security headers
  remain protected. Evidence: `internal/browserapi/security_integration_test.go`
  and `request_policy_test.go`.

### Intentionally Removed Obsolete Paths

- `artifactAvailability` is removed from the committed SSE fixture producer,
  fixtures, Go tests/consumers, React tests/consumers, and Playwright fixtures.
  Tests must not preserve a dual reader or legacy fixture.
- No second Java raw route, adapter-owned cache, permanent pin, local-path
  identifier, persisted handle, or historical reader is introduced. Absence
  checks protect these scope decisions without treating internal layout as a
  supported contract.

## Existing Test Coverage

### Java

- `ObservabilityArtifactIntegrationTest` protects byte-exact representative
  downloads and required response headers.
- `ObservabilityArtifactDeliveryTest` protects the eight-stream admission bound,
  rejection-before-commit, and shutdown cleanup.
- `ObservabilityArtifactStreamTest` protects bounded copy chunks, short reads,
  and the five-minute Java stream timeout.
- `ConsoleSseFixtureCorpusTest` generates the committed application SSE corpus
  byte-for-byte.
- `DefaultExecutionObservationHandleTest` already asserts the live runtime's
  `applicationTraceAvailability`.

### Go

- `applicationclient/activity_test.go` provides streaming response validation,
  cancellation, redirect, compression, identity, and bounded-frame patterns.
- `applicationclient/get_test.go` provides JSON problem mapping, bounded error
  body, required-header, endpoint-escaping, and identity-header patterns.
- `target/scope_test.go` protects combined caller/scope cancellation and
  `TARGET_CHANGED` vs caller-cancellation mapping.
- `target/context_test.go` protects owner invalidation order, late-result
  rejection, current-scope publication, and authoritative identity rotation.
- `workspace/workspace_test.go`, platform-specific cleanup tests, and
  `artifact_failure_test.go` protect safe ownership, non-adoption, cleanup, and
  scoped-vs-fatal failure classification.
- `browserapi/observability_test.go`, `security_integration_test.go`,
  `request_policy_test.go`, and `contracts_test.go` provide route security,
  response header, DTO, and fixture patterns.
- `console/*_integration_test.go` provides real composition-root, credential
  leakage, listener, and lock-release coverage.

### React and Browser

- API client tests protect exact browser request formation and error decoding.
- Trace detail, active execution detail, provider, reducer, and stale-scope tests
  provide the component/state patterns to extend.
- Playwright specs already tag live execution tests with workflow identifiers
  and exercise a real Console process plus mock target.

### Current Gaps

- No Go target method streams artifact bodies.
- No artifact service/state machine, deterministic filesystem fault seam,
  capacity/TTL accounting, lease, removal, or target owner exists.
- No paired acquisition/storage operations or raw browser stream exists.
- No Trace Storage component or end-to-end artifact lifecycle workflow exists.
- Existing cross-boundary fixtures encode the obsolete availability field.
- Existing tests do not prove that a browser download bypasses local cache
  accounting or that installed evidence survives later upstream auth rejection.

## Bug Reproduction / Failing Test First

The feature itself is new, but PR 12 also contains one reproducible boundary
bug. Start with that low-cost failure before adding service code.

- **Name**:
  `TestCommittedTerminalActivityUsesCanonicalApplicationTraceAvailability`
- **Type**: Go fixture-contract test
- **Location**:
  `bifrost-console/internal/console/activity_integration_test.go`
- **Arrange**:
  Load `bifrost-console-fixtures/application-sse/activity-trace-completed.sse`
  through the existing application activity decoder.
- **Act**:
  Read the terminal activity's details and serialize the relayed activity as the
  Console consumer sees it.
- **Assert**:
  `applicationTraceAvailability == "AVAILABLE"` and the details object has no
  `artifactAvailability` key.
- **Expected failure pre-fix**:
  The committed fixture contains only `artifactAvailability`, so the canonical
  assertion fails reliably without any new production type.
- **Follow-up red test**:
  Update `ConsoleSseFixtureCorpusTest` to generate the canonical key but do not
  regenerate committed fixtures. Its existing byte-for-byte comparison must
  fail until the two committed SSE fixtures/replay and all consumers are
  updated atomically.

For the new feature, the first compile-red test after creating the empty package
should be
`TestAcquireJoinsConcurrentWaitersIntoOneInstalledArtifact`. It defines the
service's central invariant before implementation and should initially fail
because no acquisition can complete or because duplicate opener calls occur.

## Test Infrastructure and Fixture Strategy

- Reuse committed Java trace fixtures, especially success, terminal failure,
  malformed JSON, incomplete chunks, and a generated large byte stream. PR 12
  treats their content as opaque bytes; semantic expectations remain PR 13.
- Use `httptest.Server` for Go application-client and browser-stream tests. Its
  handler must record request count, headers, cancellation, and bytes written.
- Use channel barriers for concurrency stages: metadata entered, headers
  received, first chunk written, pre-sync, pre-rename, and result publication.
  Do not coordinate correctness with `time.Sleep`.
- Inject a clock plus manually fired/reported timer for idle expiry. Assert timer
  schedule values directly.
- Inject deterministic handle bytes in unit tests. Production entropy remains
  cryptographic.
- Put filesystem operations behind the smallest internal seam needed to inject
  short write, sync, close, rename, remove, and `ENOSPC` failures. Continue to
  use the real verified workspace for integration tests.
- Simulate disk-full with injected `ENOSPC`; never fill the developer's normal
  disk. Optional manual disk-full verification must use a disposable,
  size-limited test volume.
- Add a guarded/chunked reader that refuses an oversized `Read` request and can
  block between chunks. This proves bounded streaming and observable
  backpressure more reliably than a brittle heap-allocation threshold.
- Capture logs into a buffer and scan them, DTO JSON, fixture bytes, and headers
  for the test credential, workspace root, partial filename, and installed path.
- Run concurrency-sensitive Go packages with `-race` and a repeat count in the
  focused stress command.

## Tests to Add or Update

### 1. Canonical Terminal Availability Fixture Coherence

- **Names**:
  - `TestCommittedTerminalActivityUsesCanonicalApplicationTraceAvailability`
  - update `generatedSseCorpusMatchesCommittedFixturesByteForByte`
  - update React activity/detail/reducer tests and Playwright terminal fixtures
- **Type**: contract integration + component + E2E fixture update
- **Locations**:
  - Java `ConsoleSseFixtureCorpusTest`
  - Go `internal/console/activity_integration_test.go`,
    `internal/live/service_test.go`
  - `web/src/activity/activityPresentation.test.ts`,
    `web/src/activity/reducer.test.ts`,
    `web/src/observability/ActiveExecutionDetail.test.tsx`
  - `web/e2e/activity-stream.spec.ts`,
    `web/e2e/live-executions.spec.ts`
- **What it proves**:
  Java-produced current-run activity, committed fixtures, Go relay/state, and
  React presentation use exactly `applicationTraceAvailability`; the Inspect
  action and core-finalization-failed state remain coherent.
- **Fixtures/data**:
  Canonical completed and observation-ended SSE fixtures and replay.
- **Mocks**:
  Existing activity fixture loaders and frontend mock SSE server.
- **Contract classification**: Ephemeral diagnostic formats.
- **Compatibility expectation**: Current-run diagnostic coherence and approved
  atomic removal of the obsolete spelling; no legacy reader.

### 2. Artifact Endpoint Addressing and Streaming Client Contract

- **Names**:
  - `TestArtifactEndpointEscapesTraceIdentifierWithoutChangingAuthority`
  - `TestOpenArtifactSendsExactHeadersAndStreamsWithoutBuffering`
  - `TestOpenArtifactValidatesIdentityMediaEncodingAndLength`
  - `TestOpenArtifactMapsBoundedProblemsRedirectAndCancellation`
  - `TestOpenArtifactClosesBodyAndInterruptsBlockedRead`
- **Type**: Go unit/HTTP integration
- **Locations**:
  `internal/applicationclient/address_test.go`,
  `internal/applicationclient/artifact_test.go` (new)
- **What it proves**:
  The operation sends one credential header, `Accept: application/x-ndjson`,
  identity encoding, and no-store; rejects redirect/encoded/wrong-media or
  ambiguous identity/length responses; represents a missing `Content-Length` as
  unknown so service-side metadata/incremental accounting can still verify the
  complete stream; rejects duplicate or invalid length; maps Java problems from
  a bounded body; exposes a closeable stream; and never reads the successful
  body eagerly.
- **Fixtures/data**:
  `httptest.Server`, exact Java-compatible headers, guarded multi-chunk body,
  all response-shape variants.
- **Mocks**:
  Real HTTP client transport against `httptest`; test credential provider.
- **Contract classification**: Application API boundary.
- **Compatibility expectation**: Protected existing Java artifact route and
  exact observable response semantics.

### 3. Target Scope Streaming and Exact Compatibility Gate

- **Names**:
  - `TestScopeOpenArtifactCombinesCallerAndScopeCancellation`
  - `TestScopeOpenArtifactRejectsLateInstanceAndRequestsRevalidation`
  - `TestScopeOpenArtifactMapsApplicationFailuresWithoutLeakingCredential`
  - `TestIncompatibleTargetCannotOpenArtifact`
- **Type**: Go unit/integration
- **Locations**:
  `internal/target/scope_test.go`, `context_test.go`,
  `internal/console/artifact_integration_test.go`
- **What it proves**:
  Scope cancellation returns `TARGET_CHANGED`, caller cancellation is
  request-scoped, late/mismatched instance identity cannot publish, and no
  artifact request is admitted before the exact release probe succeeds.
- **Fixtures/data**:
  Extended internal fake client, blocking stream, incompatible instance
  response.
- **Mocks**:
  Existing target fake style with added `OpenArtifact`.
- **Contract classification**: Application API boundary plus internal target
  authority.
- **Compatibility expectation**: Protected exact-release and target-scope
  gates; internal fakes update atomically without a compatibility adapter.

### 4. Joined Acquisition and Atomic Publication

- **Names**:
  - `TestAcquireJoinsConcurrentWaitersIntoOneInstalledArtifact`
  - `TestAcquireReturnsSameHandleForAlreadyInstalledTrace`
  - `TestAcquireCancelsOneWaiterWithoutCancellingLeader`
  - `TestAcquireCancelsLeaderAndCleansWhenLastWaiterLeaves`
  - `TestAcquirePublishesOnlyAfterSyncCloseSizeAndAtomicRename`
  - `TestAcquireRejectsShortLongFailedOrStaleTransferWithoutHandle`
- **Type**: Go unit with real temporary files where practical
- **Locations**:
  `internal/artifact/acquire_test.go`, `storage_test.go` (new)
- **What it proves**:
  One scope/trace produces one metadata load, opener call, installed file,
  handle, and charge; independent cancellation is correct; every pre-commit
  failure removes partial bytes/reservations; stale scope cannot publish. A
  complete zero-byte or semantically malformed NDJSON body remains opaque at
  this layer and may be installed if its transport/metadata agree; PR 13, not
  PR 12, determines whether it can become analysis evidence.
- **Fixtures/data**:
  Deterministic handles, channel-barrier loader/opener, guarded readers, injected
  sync/close/rename failures, real temp workspace.
- **Mocks**:
  Narrow trusted trace loader, stream opener, clock/entropy, and filesystem
  failure seam. Do not mock service state transitions.
- **Contract classification**: Internal or accidentally exposed implementation.
- **Compatibility expectation**: New internal invariant, built for one coherent
  implementation rather than preserving a prior cache.

### 5. Capacity Accounting and Deterministic Eviction

- **Names**:
  - `TestFiniteCapacityChargesReservationsAndInstalledBytesExactlyOnce`
  - `TestCapacityExactFitSucceedsAndOneByteOverEvictsEligibleEntries`
  - `TestCapacityEvictsExpiredThenLeastRecentlyUsedWithStableTies`
  - `TestCapacityNeverEvictsPinnedOrAcquiringEntries`
  - `TestCapacityRejectsArtifactThatCannotFitEmptyCacheWithBoundedDetails`
  - `TestUnlimitedNeverReturnsConfiguredCapacityLimit`
  - `TestConcurrentReservationsCannotOvercommitFiniteCapacity`
- **Type**: Go unit/race
- **Locations**:
  `internal/artifact/capacity_test.go` (new)
- **What it proves**:
  Accounting never double-counts or leaks, eviction order is deterministic,
  active/in-flight evidence is protected, finite errors identify
  `trace-workspace.max-bytes`, unlimited changes only policy rejection, and
  concurrent admission cannot exceed the ceiling.
- **Fixtures/data**:
  Tiny synthetic byte sizes, deterministic times/handles, pinned/acquiring
  entries, concurrent barrier.
- **Mocks**:
  Injected clock and delete recorder; real mutex/state machine under `-race`.
- **Contract classification**: Configuration and manifest contracts plus
  internal implementation.
- **Compatibility expectation**: Protect existing config semantics while
  establishing their first runtime enforcement.

### 6. Lease, Idle TTL, Handle, and Removal Semantics

- **Names**:
  - `TestSuccessfulLeaseRefreshesIdleDeadlineButFailedUseDoesNot`
  - `TestIdleExpiryAtExactDeadlineRemovesUnpinnedEntry`
  - `TestExpiryWhilePinnedDefersDeletionUntilFinalLeaseCloses`
  - `TestNeverExpireSchedulesNoIdleTimer`
  - `TestRemoveUnusedInvalidatesHandleAndReleasesBytes`
  - `TestRemoveInUseReturnsArtifactInUseWithoutCancellingLease`
  - `TestClearExpiredAndClearAllUnusedPreservePinnedEntries`
  - `TestHandleErrorsDistinguishStaleScopeMalformedAndMissingCurrentHandle`
  - `TestStorageSnapshotDoesNotRefreshLastUse`
- **Type**: Go unit/race
- **Locations**:
  `internal/artifact/lease_test.go`, `expiry_test.go`,
  `service_test.go` (new)
- **What it proves**:
  TTL origin and timer scheduling are exact, only successful use refreshes,
  pinning is lease-only, removal never deletes in-flight evidence, and error
  codes are `TARGET_CHANGED`, `INVALID_ARGUMENT`, `ARTIFACT_EXPIRED`, or
  `ARTIFACT_IN_USE` as designed.
- **Fixtures/data**:
  Manual clock/timer, two simultaneous leases, well-formed random/malformed
  handles, old/current scope IDs.
- **Mocks**:
  Manual timer and delete recorder; real service synchronization.
- **Contract classification**: Internal implementation and same-release browser
  serialized behavior.
- **Compatibility expectation**: New current-process lifecycle; no tombstone
  history, permanent pin, or persisted handle behavior.

### 7. Storage Failure Classification and Lifecycle Invalidation

- **Names**:
  - `TestENOSPCRemovesPartialAndReturnsLocalStorageUnavailableWhenWorkspaceRecovers`
  - `TestCleanupOrWorkspaceProbeFailureTerminatesCoordinator`
  - `TestAuthenticationRejectionPreservesInstalledCurrentScopeArtifact`
  - `TestCredentialReplacementAndInstanceChangeInvalidateArtifacts`
  - `TestScopeRotationCancelsEveryAcquisitionAndLeaseBeforeRemoval`
  - `TestShutdownClosesTimersStreamsWorkersAndCleansTransient`
  - update `TestRestartCleanupNeverAdoptsPriorEntries`
- **Type**: Go unit + composition integration
- **Locations**:
  `internal/artifact/storage_test.go`, `target_owner_test.go`,
  `internal/console/artifact_integration_test.go`,
  `internal/workspace/artifact_failure_test.go`
- **What it proves**:
  Recoverable write exhaustion remains request-scoped, invariant loss becomes
  process-fatal, acquisition-time authorization is preserved, authoritative
  scope changes clear evidence, and restart/shutdown never adopt or leak state.
- **Fixtures/data**:
  Injected `ENOSPC`, cleanup/probe failures, real verified workspace, target
  owner barriers, rejected/replaced credentials, abandoned prior file.
- **Mocks**:
  Filesystem fault seam for unit cases; real workspace and Console coordinator
  for integration.
- **Contract classification**: Configuration contract and internal lifecycle.
- **Compatibility expectation**: Protected workspace fatality/non-adoption and
  target authority behavior.

### 8. Browser Artifact JSON Boundary and Domain Errors

- **Names**:
  - `TestArtifactJSONRoutesRequireSessionAndCSRFByOperation`
  - `TestArtifactJSONRoutesRejectMethodBodyAndScopeVariants`
  - `TestAcquireAndStorageSnapshotReturnOpaquePathFreeDTOs`
  - `TestArtifactDomainErrorsMapToStableHTTPEnvelopes`
  - `TestCachedTraceFallbackPreservesOriginalFactsWithoutClaimingCurrentApplicationState`
  - `TestStorageSnapshotIsSideEffectFree`
  - update `TestBrowserTargetFixtureCorpusMatchesCommittedInventoryByteForByte`
- **Type**: Go handler/contract integration
- **Locations**:
  `internal/browserapi/artifacts_test.go`,
  `contracts_test.go`, `errors_test.go`, `observability_test.go`
- **What it proves**:
  Reads/mutations use the intended session/CSRF policy; strict bodies and scope
  are enforced; DTOs separate application/local facts; all artifact codes,
  including `ARTIFACT_IN_USE`, are bounded and stable; no path leaks.
- **Fixtures/data**:
  Acquired/storage/error browser fixtures, old/current scopes, upstream auth
  failure with installed entry, sentinel workspace settings.
- **Mocks**:
  Transport-neutral artifact service fake at handler boundary only.
- **Contract classification**: Persisted or serialized contracts
  (same-executable browser DTO) and internal browser adapter.
- **Compatibility expectation**: Atomic Go/TypeScript DTO update; no browser API
  version negotiation or old DTO fallback.

### 9. Raw Attachment Pass-Through Security and Streaming

- **Names**:
  - `TestRawDownloadStreamsExactBytesWithoutCacheMutation`
  - `TestRawDownloadUsesFreshApplicationAuthorizationEveryTime`
  - `TestRawDownloadRequiresSameSitePairedNavigation`
  - `TestRawDownloadRejectsQueryRangeConditionalAndAmbiguousTraceID`
  - `TestRawDownloadUsesFixedSafeHeadersAndFilename`
  - `TestRawDownloadCancellationAndScopeRotationCloseUpstream`
  - `TestRawDownloadFailureBeforeCommitReturnsDomainError`
  - `TestRawDownloadFailureAfterCommitAppendsNoErrorEnvelope`
  - `TestRawDownloadBackpressureDoesNotBufferCompleteArtifact`
- **Type**: Go HTTP integration/security
- **Locations**:
  `internal/browserapi/artifact_download_test.go`,
  `security_integration_test.go`, `request_policy_test.go`
- **What it proves**:
  Raw bytes pass directly from a newly authenticated upstream stream to the
  browser; local storage totals/handles/files remain unchanged; request shapes,
  cookie/Host/origin/fetch metadata, cancellation, and pre/post-commit errors
  are safe; filenames never trust upstream paths.
- **Fixtures/data**:
  Java trace fixtures, malicious upstream disposition, cross-site request
  cases, blocked chunk reader, scope cancellation, short read.
- **Mocks**:
  `httptest` upstream and real browser router/session registry; artifact service
  spy used only to assert zero calls.
- **Contract classification**: Application API boundary and browser security
  adapter.
- **Compatibility expectation**: Preserve Java bytes and acquisition-time auth;
  establish the new Go pass-through without a second Java route or local-cache
  fallback.

### 10. React API, Trace Detail, and Trace Storage Components

- **Names/behaviors**:
  - API client forms exact acquire/storage/remove requests and maps all new
    error codes.
  - Trace detail keeps execution outcome, application availability, and local
    availability distinct; acquisition progress is disabled/join-safe.
  - Raw download renders as an ordinary attachment navigation, not `fetch` plus
    `Blob`.
  - Trace Storage shows finite vs Unlimited and TTL vs Never, entries, active
    pins, aggregate bytes, and original application facts.
  - Removal confirmations manage selection/focus; pinned rows cannot be removed;
    target-scope reset discards state.
  - Untrusted identifiers/messages render as text and cannot create executable
    links.
- **Type**: TypeScript unit/component
- **Locations**:
  `web/src/api/client.test.ts`, `contracts.ts`,
  `web/src/observability/TraceDetail.test.tsx`,
  `TraceStorage.test.tsx`, `ActiveExecutionDetail.test.tsx`,
  provider/reducer/route/app tests
- **What it proves**:
  Same-release browser contract correctness, deliberate acquisition/download,
  accessible state transitions, and no browser-owned cache semantics.
- **Fixtures/data**:
  Finite/unlimited snapshots, acquired/unavailable/pinned entries, each domain
  error, malicious text values.
- **Mocks**:
  Mock only API client functions; use React Testing Library and `userEvent`.
- **Contract classification**: Persisted or serialized same-release browser
  contract plus internal presentation.
- **Compatibility expectation**: Atomic Go/TypeScript update and current-scope
  behavior; no old availability field or permanent pin UI.

### 11. End-to-End Failed-Completion and Storage Workflows

- **Names**:
  - `PR12-R02 failed completion joins acquisition and appears once in storage`
  - `PR12-R08 removal invalidates handle without changing application artifact`
  - `PR12-R09 target rotation clears local storage and stale links`
  - `PR12-R11 raw download preserves checksum without installing`
  - `PR12-R10 cached evidence remains distinct after authentication rejection`
- **Type**: Playwright E2E
- **Locations**:
  new `web/e2e/artifact-storage.spec.ts`, updates to
  `live-executions.spec.ts` and `target-context.spec.ts`
- **What it proves**:
  A paired developer can deliberately acquire from a terminal activity/catalog,
  observe one storage entry across tabs, remove it safely, download exact raw
  bytes, and see correct auth/scope/availability transitions.
- **Fixtures/data**:
  Stateful mock Java target recording request counts and serving a known
  checksum; finite tiny cache; controllable auth rejection and instance change.
- **Mocks**:
  Real built Console and browser; HTTP mock only for the observed Java
  application.
- **Contract classification**: Cross-boundary current-release workflow.
- **Compatibility expectation**: Protected paired-browser and application
  boundary with the intentional SSE field removal applied atomically.

### 12. Java Artifact Boundary Regression

- **Names**:
  - retain `downloadsExactFinalizedArtifactWithRequiredHeaders`
  - retain `rejectsUnsupportedShapesAndUnknownArtifactsBeforeCommit`
  - retain representative fixture byte-for-byte parameterized coverage
  - retain delivery admission/shutdown and short-read tests
- **Type**: Java Spring integration/unit regression
- **Locations**:
  Existing observability artifact test classes.
- **What it proves**:
  PR 12's Go work does not alter the protected Java producer or create another
  raw contract; malformed semantic fixtures remain downloadable as exact bytes.
- **Fixtures/data**:
  Existing Java-produced NDJSON corpus.
- **Mocks**:
  Existing Spring test application and delivery test doubles.
- **Contract classification**: Application API and ephemeral diagnostic bytes.
- **Compatibility expectation**: Protected path unchanged.

### 13. Absence, Leakage, Race, and Build Gates

- **Names/checks**:
  - Repository search finds no `artifactAvailability`.
  - No Java route constant/controller method beyond the existing trace artifact
    route is added for raw download.
  - No DTO/fixture/log contains workspace/partial/installed paths or credentials.
  - No production artifact/cache package exists outside the central service.
  - Race/repeat test leaves no files, charges, timers, goroutines, bodies, or
    leases after terminal cases.
  - Canonical production verification rebuilds assets and passes.
- **Type**: static contract check + Go race/stress + build integration
- **Locations**:
  focused tests above, repository search in CI/review checklist, existing build
  tool tests
- **What it proves**:
  Approved obsolete behavior and forbidden parallel ownership are absent, and
  concurrency behavior is stable under repetition.
- **Fixtures/data**:
  Sentinel credential/path strings and repeated barrier schedules.
- **Mocks**:
  None for static searches; fault seams already described for stress tests.
- **Contract classification**: All affected categories.
- **Compatibility expectation**: Protected paths pass; obsolete field and
  unapproved shims/duplicate routes are absent.

## Recommended Test-First Implementation Order

1. Add the canonical availability failing test; update Java generation and
   committed fixtures only after observing the failure.
2. Add `ArtifactEndpoint` and `OpenArtifact` contract tests; implement the
   streaming client and target mapping.
3. Add the joined-acquisition red test; implement the minimal leader/waiter and
   atomic install path.
4. Add failure/cancellation publication tests before adding cleanup branches.
5. Add capacity/accounting tests, then leases/TTL/removal tests with the manual
   clock.
6. Add scope owner, auth rejection, disk-full/fatality, restart, and shutdown
   integration tests before composition wiring is considered complete.
7. Add browser JSON/security tests before handlers, followed by raw streaming
   tests before the GET route.
8. Add React client/component tests before Trace Storage UI and route changes.
9. Add Playwright workflows only after stable Go/browser fixtures exist.
10. Finish with race/repeat, cross-boundary Java tests, static absence scans, and
    the clean production verification pipeline.

## How to Run

### Prerequisites

- Java 21 and Maven 3.9+ through the repository wrapper.
- Exact Console toolchains declared by the repository:
  Go 1.26.5, Node.js 24.18.0, and npm 12.0.2.
- No application key, external service, environment variable, or network access
  is required for automated tests. Tests use local `httptest`/Spring servers and
  committed fixtures.
- Run commands from PowerShell on Windows as shown. On Unix, replace
  `.\mvnw.cmd` with `./mvnw`.

### Fast Red/Green Loops

From the repository root:

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter `
  -Dtest=ConsoleSseFixtureCorpusTest,DefaultExecutionObservationHandleTest test
```

From `bifrost-console/`:

```powershell
go test ./internal/applicationclient ./internal/target
go test ./internal/artifact
go test ./internal/browserapi ./internal/console ./internal/workspace
```

From `bifrost-console/web/`:

```powershell
npm run typecheck
npm test
```

### Boundary and Regression Suites

From the repository root:

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter `
  -Dtest=ObservabilityArtifactIntegrationTest,ObservabilityArtifactDeliveryTest,ObservabilityArtifactStreamTest,ConsoleSseFixtureCorpusTest,DefaultExecutionObservationHandleTest test
```

From `bifrost-console/`:

```powershell
go test ./...
go test -race ./internal/artifact ./internal/applicationclient ./internal/target ./internal/browserapi ./internal/console ./internal/workspace
go test -race -count=25 ./internal/artifact
```

From `bifrost-console/web/`:

```powershell
npm run test:e2e
```

### Static Boundary Checks

From the repository root:

```powershell
rg "artifactAvailability" bifrost-spring-boot-starter bifrost-console bifrost-console-fixtures
rg -n "workspace|transient|partial|execution-trace\\.ndjson" bifrost-console/browser-fixtures
```

The first command must return no matches. The second must return no path-bearing
artifact DTO values; the existing intentionally displayed resolved workspace
path in bootstrap/status fixtures must be reviewed separately and is not an
artifact handle leak.

### Canonical Production Gate

From `bifrost-console/`:

```powershell
go run ./internal/buildtool verify
```

### Platform Verification

- Run the real workspace/integration subset on Windows, Linux, and macOS CI.
- Windows must include junction/reparse and close-before-rename/delete coverage.
- Unix must include symlink and owner-permission coverage.
- Do not perform a real disk-fill test on a normal developer or CI filesystem.
  If desired for release hardening, use a disposable, size-limited mounted test
  volume and record the environment separately.

## Manual Verification

1. Run Console against the sample Spring application with one retained trace.
   Acquire it twice and confirm one handle, one installed copy, one upstream
   acquisition request, and one charge.
2. Start acquisition in two tabs; interrupt one tab and confirm the other
   succeeds. Interrupt both before completion and confirm no partial/charge
   remains.
3. Use a small finite test workspace ceiling. Confirm expired entries are
   removed before LRU entries and an active lease is never evicted.
4. Reject the upstream credential after acquisition. Confirm the local artifact
   remains available with original observation facts while new acquisition and
   raw download report authentication required.
5. Replace the credential/target and then restart Console. Confirm old scope
   references report `TARGET_CHANGED`, current-scope removed handles report
   `ARTIFACT_EXPIRED`, and no prior file is adopted.
6. Download raw bytes through the browser, compare their checksum with the Java
   artifact, and confirm Trace Storage totals/last-use/handles are unchanged.
7. Exercise Trace Storage by keyboard at 200% zoom in light, dark,
   forced-colors, and reduced-motion settings. Verify confirmations, disabled
   pinned removal, focus restoration, and text-only rendering.
8. On each supported OS, confirm workspace permissions, safe cleanup, raw
   filename, target rotation, and shutdown behavior.

## Exit Criteria

- [ ] The canonical availability test is observed failing against the pre-fix
  fixture, then passes after the atomic Java/fixture/Go/React update.
- [ ] The joined-acquisition test is introduced before implementation and proves
  one upstream stream, file, handle, and charge across concurrent callers.
- [ ] All PR12-R01 through PR12-R15 requirements are covered by at least one
  automated test; the five representative Playwright workflows carry their
  applicable requirement IDs.
- [ ] Artifact unit tests cover every state transition and terminal cleanup path
  with deterministic barriers/clocks rather than sleep-based correctness.
- [ ] `go test -race -count=25 ./internal/artifact` passes without races,
  deadlocks, leaked files, reservations, timers, bodies, workers, or leases.
- [ ] Finite, exact-fit, one-byte-over, pinned, concurrent-reservation,
  `unlimited`, finite TTL, exact-deadline, pinned-expiry, and `never` cases pass.
- [ ] Injected `ENOSPC` returns scoped `LOCAL_STORAGE_UNAVAILABLE` only after
  cleanup and a healthy workspace probe; cleanup/probe failure reaches the
  lifecycle fatal path.
- [ ] Scope rotation and shutdown cancel and clean every acquisition/lease;
  restart never adopts prior-process entries; auth rejection alone preserves a
  complete current-scope copy.
- [ ] Raw pass-through tests prove exact bytes, fresh upstream authorization,
  request/security bounds, cancellation, pre/post-commit behavior, bounded
  streaming, safe headers, and zero local-cache mutation.
- [ ] Browser DTOs and UI keep execution outcome, application availability,
  original observation facts, and local-handle availability separate.
- [ ] No handle, DTO, error, header, fixture, or log leaks credentials or an
  artifact/partial/install path.
- [ ] Existing Java artifact, exact-release compatibility, config,
  workspace-safety, target-scope, and browser-security regression suites pass.
- [ ] `artifactAvailability` is absent from production, fixtures, and tests; no
  dual reader, alias, fallback, or historical fixture remains.
- [ ] No second Java raw route, independent browser/MCP cache, permanent pin,
  persisted handle, or independent compatibility marker is introduced.
- [ ] Frontend typecheck, component tests, Playwright tests, all Go tests,
  focused Java tests, and `go run ./internal/buildtool verify` pass.
- [ ] Windows, Linux, and macOS workspace/platform subsets pass.
- [ ] Manual verification steps are completed and any release-hardening
  disposable-volume disk-full result is recorded without risking a normal disk.

## References

- Implementation plan:
  `ai/thoughts/plans/2026-07-29-PR-12-bifrost-console-artifact-service.md`
- Ticket:
  `ai/thoughts/tickets/bifrost-console-pr-12-artifact-service.md`
- Research:
  `ai/thoughts/research/2026-07-29-PR-12-bifrost-console-artifact-service.md`
- Phase 2 design:
  `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md`
- Framework compatibility lens:
  `ai/thoughts/framework-feature-design-lens.md`
- Downstream PR 13:
  `ai/thoughts/tickets/bifrost-console-pr-13-trace-analysis-services.md`
- Downstream PR 14:
  `ai/thoughts/tickets/bifrost-console-pr-14-trace-explorer.md`
