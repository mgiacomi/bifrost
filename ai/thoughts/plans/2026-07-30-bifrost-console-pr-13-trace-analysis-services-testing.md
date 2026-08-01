# PR 13 — Trace Parser, Indexes, and Shared Calculations Testing Plan

## Change Summary

- Require semantic validation and complete derived-index construction before `artifact.Service.Acquire` exposes an artifact handle.
- Replace the internal single installed file with one lifecycle/capacity-owned bundle containing the unchanged raw NDJSON, record/fact indexes, reconstructed payload storage, and a manifest.
- Add `bifrost-console/internal/traceanalysis` for bounded current-release parsing, chunk reconstruction, validation, hierarchy/timing/usage calculations, and neutral queries.
- Add finite pages, search work windows, payload/raw ranges, and scope/handle/query-bound continuations for PR 14 and PR 18 to consume without adapter-specific recalculation.
- Extend the Java-produced cross-language fixture corpus to cover hierarchy, duration, attribution, failure, chunking, gaps, uncertainty, structural limits, and contradictions.
- Correct the current `ESTIMATED` fixture/authoring-guide drift to the production enum value `HEURISTIC`; do not preserve both spellings.

## Requirements and Workflow Coverage

| Requirement | Primary evidence |
| --- | --- |
| Validate a complete current-release artifact before handle publication | `WF-X-R4`, `WF-UE-R1`, `WF-FE-R8` |
| Browser and future MCP use identical calculations/errors | `WF-X-R7`, `WF-UE-R3`, `WF-SP-R10` |
| Raw records/payloads are opt-in but completely addressable | `WF-X-R9`, `WF-X-R11`, `WF-SP-R6`, `WF-SP-R11` |
| Missing, invalid, expired, unattributed, and unknown facts remain distinct | `WF-X-R10`, `WF-FE-R8`, `WF-UE-R7`, `WF-UE-R11` |
| Acquisition-time authorization and one shared local lifecycle remain intact | `WF-X-R12`, `WF-FE-R9`, existing `PR12-R01`–`PR12-R13` tests |
| Direct/descendant/inclusive/attempt/retry usage is mechanical and non-overlapping | `WF-UE-R3`, `WF-UE-R4`, `WF-UE-R6`, `WF-UE-R9` |
| Duration remains separate from usage and uncertainty is explicit | `WF-UE-R10`, `WF-SP-R5` |
| Hierarchy uses recorded identity and has no product depth/node cap | `WF-SP-R1`, `WF-SP-R3`, `WF-SP-R4`, `WF-SP-R11` |
| Current Java/Go release agreement remains exact | Framework design lens Category 5, `TestScopeOpenArtifactRejectsIncompatibleTarget`, Java fixture corpus |

## Impacted Areas

- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ConsoleTraceFixtureCorpusTest.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/usage/UsagePrecision.java` and focused usage tests as evidence; production behavior is not changed.
- `bifrost-console-fixtures/traces/`, `bifrost-console-fixtures/expected/`, and `bifrost-console-fixtures/README.md`
- New `bifrost-console/internal/traceanalysis/` parser, models, calculations, indexes, cursor, range, search, and query service.
- `bifrost-console/internal/artifact/` acquisition, storage, capacity, lease, expiry, cleanup, target-owner, and test helpers.
- `bifrost-console/internal/console/service.go` plus console artifact/target/observability integration tests.
- `bifrost-console/internal/browserapi/` acquisition/storage contract tests whose fake artifacts or byte accounting change.
- `bifrost-console/internal/applicationclient/` and `internal/target/` exact-version rejection regression coverage.
- `bifrost-console/README.md` aggregate raw-plus-derived byte semantics.
- `ai/skill-authoring/traces-and-debugging.md` precision table.

## Risk Assessment

### High-Risk Behaviors

- Malformed or contradictory bytes could receive a valid handle and be presented as trusted analysis.
- Joined callers could run duplicate processors, receive different handles/results, or double-charge derived bytes.
- A staged raw file or sidecar could survive cancellation, invalidation, storage failure, or semantic rejection.
- Chunk reconstruction could reorder, duplicate, omit, or combine payloads, or allocate the whole logical payload.
- Frame validation/calculation could recurse by hierarchy depth, accept cycles/missing parents, or invent durations for incomplete/overlapping intervals.
- Usage could be double-counted across ancestors/retries, treat missing usage as zero, misplace unframed response usage, or hide a negative terminal reconciliation.
- Cursor reuse could cross scope, handle, filter, order, representation, payload, or byte range.
- Search/range behavior could omit evidence at work-window or UTF-8 boundaries, duplicate results after continuation, or exceed fixed per-call bounds.
- Index corruption or component-open failures could become partial best-effort evidence instead of a precise storage/console failure.
- Refactoring single-file storage could regress TTL, LRU, pinning, manual removal, shutdown, restart cleanup, workspace safety, or acquisition-time authorization.
- Error/log/DTO changes could leak local paths, credentials, payloads, or cursor internals.

### Edge Cases

- LF, CRLF, complete final line without newline, blank lines, truncated final JSON, invalid UTF-8, one-byte-over-limit lines, depth 128/129, numeric overflow, and decimal-second timestamps.
- Missing, duplicate, out-of-order, interleaved, mismatched, extra, invalid-content-type, and very large chunk sequences.
- No frames, repeated skill invocations, multiple roots when permitted by the current trace, deep valid chains, incomplete closes, overlapping children, negative durations, child intervals outside parents, and cycles.
- Provider attempts with no response, separate nested retry sequences, inconsistent attempt identity, validation retry/exhaustion, recovered nonterminal errors, terminal failure/abort, and invalid terminal failure references.
- `EXACT`, `HEURISTIC`, and `UNAVAILABLE`; absent usage; unframed attributed usage; independently reconciled prompt/completion/total components; overflow and negative remainder.
- Empty result pages, exact page/range limits, one-over-limit inputs, inline payload at 8 KiB/8 KiB+1, search stopping on both the 8 MiB and 10,000-record bounds, and matches spanning internal chunks.
- Caller cancellation versus scope rotation versus shutdown; expiry/removal between continuation calls; authentication rejection after acquisition.

### Compatibility Scope

- **Application API**: No changed public Java API; retain existing signature/extension-point tests and add no new public surface.
- **Supported SPI**: No affected SPI; no compatibility test is required for the internal Go processor interface.
- **Configuration and manifest contracts**: Preserve `trace-workspace.max-bytes`, `idle-ttl`, `unlimited`, and `never` parsing/default tests. Assert only the already-documented aggregate byte meaning changes operational accounting.
- **Persisted or serialized contracts**: No durable index compatibility tests. Restart must delete rather than adopt prior bundle layouts.
- **Ephemeral diagnostic formats**: Highest compatibility focus. Test current Java writer/enums/fixtures and Go parser/calculations as one release, including accuracy, ordering, failure visibility, opaque unconsumed JSON, and exact-version gating.
- **Internal or accidentally exposed implementation**: Remove old single-file/raw-only production behavior atomically. Update tests to the bundle/required-processor design; do not retain tests or fallbacks requiring both layouts.

### Authoring Claims Requiring Evidence

- `HEURISTIC` is the current precision spelling and describes framework-derived estimates.
- `UNAVAILABLE` attempt usage is not the same fact as terminal unattributed usage.
- Terminal unattributed usage is derived component-by-component from terminal totals minus normalized physical response usage.

Evidence must come from `UsagePrecision.java`, `ModelUsageExtractorTest`, `SessionUsageServiceTest`, the corrected Java fixture producer, and the Go fixture-corpus/calculation tests. No test should merely assert the prose file contains a word.

## Existing Test Coverage

### Java and Cross-Language Fixtures

- `ConsoleTraceFixtureCorpusTest#generatedCorpusMatchesCommittedFixturesByteForByte` proves deterministic Java fixture generation.
- `#corpusInventoryContainsEveryRequiredSemanticCase` protects the current 10-valid/8-invalid inventory.
- `#validFixturesSatisfyAttemptTerminalAndUsageInvariants` protects monotonic sequence, one final completion, attempt lifecycle, and validation links.
- `#invalidFixturesHaveOneNamedExpectedClassification` protects the existing eight invalidity categories.
- `#unattributedUsageExpectedResultIsTerminalMinusAttributedResponses` protects part of the component-wise arithmetic.
- `ModelUsageExtractorTest` and `SessionUsageServiceTest` already protect production `UsagePrecision.HEURISTIC`.

### Go Artifact Lifecycle

- `internal/artifact/acquire_test.go` covers joined acquisition, independent waiter cancellation, final-waiter cleanup, sync/close/size/rename publication, short/long transfers, capacity, ENOSPC, shutdown, and backpressure.
- `capacity_test.go`, `expiry_test.go`, `lease_test.go`, `service_test.go`, `storage_test.go`, and `target_owner_test.go` cover capacity arithmetic/LRU, TTL, pins, path hiding, cleanup/fatal boundaries, authentication preservation, target rotation, restart non-adoption, and races/deadlocks.
- `internal/console/artifact_integration_test.go` covers one installed copy, raw-download separation/checksum, authentication rejection, scope rotation, restart, shutdown, and path/credential non-disclosure.
- `internal/console/observability_integration_test.go#TestScopeOpenArtifactRejectsIncompatibleTarget` proves an inexact release string blocks artifact access.
- Browser API contract/security tests cover stable error envelopes, acquisition/storage DTOs, cache controls, same-origin policy, and raw-download security.

### Gaps

- No Go trace parser, semantic validator, fixture consumer, hierarchy/timing/usage calculator, analysis index, or query service exists.
- Current acquisition accepts byte-count-correct malformed NDJSON and publishes a handle.
- Current fixtures contain no `FRAME_OPENED`/`FRAME_CLOSED`, so hierarchy and duration are unproved.
- Current artifact capacity excludes derived files and storage publishes one file, not one bundle.
- No tests cover logical-versus-physical records, streamed reconstruction, payload descriptors/ranges, search work bounds, query filters, or handle/query-bound cursors.
- No test proves all frames/records/payload bytes remain reachable without a hierarchy cap or cumulative traversal limit.
- The current fixture/guidance incorrectly accepts `ESTIMATED` as a valid precision.

## Bug Reproduction / Failing Test First

### 1. Malformed Artifact Is Published

- **Name**: `TestAcquireRejectsMalformedNDJSONBeforePublishingHandle`
- **Type**: integration-level artifact unit test
- **Location**: `bifrost-console/internal/artifact/acquire_test.go`
- **Arrange**: Use the existing real workspace, fake metadata loader, and fake stream opener with byte-count-correct `"{not-json\n"` content. Activate a valid scope.
- **Act**: Call `Service.Acquire`, then read `StorageSnapshot`.
- **Assert**: Acquisition returns `INVALID_ARTIFACT` with no artifact handle; acquired count and charged bytes are zero; the staging directory is empty.
- **Expected failure (pre-fix)**: Current `installStream` checks transfer length but not NDJSON semantics, so acquisition succeeds and publishes one installed entry.
- **Contract classification**: Ephemeral diagnostic format plus internal artifact lifecycle.
- **Compatibility expectation**: Current-run diagnostic coherence; approved removal of raw-only production acquisition.

### 2. Valid Fixture Uses a Nonexistent Precision

- **Name**: `validFixtureUsagePrecisionValuesAreCurrentEnums`
- **Type**: Java unit/fixture contract test
- **Location**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ConsoleTraceFixtureCorpusTest.java`
- **Arrange**: Generate/read every valid fixture and collect `/metadata/usage/precision` from physical `MODEL_RESPONSE_RECEIVED` records.
- **Act**: Convert each spelling through `UsagePrecision.valueOf`.
- **Assert**: Every consumed precision is one of `EXACT`, `HEURISTIC`, or `UNAVAILABLE`.
- **Expected failure (pre-fix)**: `nested-retry-sequences.ndjson` contains `ESTIMATED`, which is not a `UsagePrecision`.
- **Contract classification**: Ephemeral diagnostic format and authoring-documentation evidence.
- **Compatibility expectation**: Approved removal; do not add an alias or legacy reader for `ESTIMATED`.

The first test proves the main feature gap in current Go behavior. The second is the smallest reliable red test for the discovered documentation/fixture defect. Commit both red tests before their corresponding fixes when implementation workflow permits.

## Tests to Add/Update

### 1. Java Fixture Producer and Semantic Inventory

- **Names**:
  - `generatedCorpusMatchesCommittedFixturesByteForByte` (update)
  - `corpusInventoryContainsEveryRequiredSemanticCase` (update)
  - `validFixturesSatisfyCurrentTraceAnalysisInvariants` (replace/expand)
  - `invalidFixturesHaveOneNamedExpectedClassification` (update)
  - `validFixtureUsagePrecisionValuesAreCurrentEnums` (new)
  - `expectedUsageReconcilesEveryComponentIndependently` (replace/expand)
- **Type**: Java unit/fixture contract
- **Location**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ConsoleTraceFixtureCorpusTest.java`
- **What it proves**: The Java producer deterministically covers every current consumed semantic, uses only production enums, keeps exactly one final completion, and publishes adapter-neutral expected facts.
- **Fixtures/data**: Add valid nested/repeated/incomplete/overlap/chunked-JSON cases and minimal invalid chunk/frame/attempt/failure/usage/limit/truncation mutations.
- **Mocks**: Fixed clock, deterministic ID supplier, and real `DefaultExecutionTraceHandle`; no mocked serialized records for valid cases.
- **Contract classification**: Ephemeral diagnostic format.
- **Compatibility expectation**: Current-run diagnostic coherence; obsolete fixture values removed atomically.

### 2. Go Consumes Every Java Expected Result

- **Name**: `TestFixtureCorpusMatchesJavaExpectedSemantics`
- **Type**: Go integration/contract
- **Location**: `bifrost-console/internal/traceanalysis/fixture_corpus_test.go`
- **What it proves**: Every valid Java fixture produces the exact neutral identity, terminal, hierarchy, timing, usage, attempt/retry, validation, failure, payload, gap, and uncertainty facts; every invalid fixture returns its expected category and no partial result.
- **Fixtures/data**: Use `bifrost-console-fixtures` in place; do not copy or translate it.
- **Mocks**: Real processor with a test artifact staging sink and real temporary files.
- **Contract classification**: Ephemeral diagnostic format / Java-to-Go boundary.
- **Compatibility expectation**: Current-run diagnostic coherence only; no historical fixture reader.

### 3. NDJSON Framing, Structural Limits, and Strict Values

- **Names**:
  - `TestParserAcceptsLFCRLFAndCompleteFinalLine`
  - `TestParserRejectsBlankMalformedTruncatedAndInvalidUTF8Records`
  - `TestParserEnforcesPhysicalLineLimitAtExactBoundary`
  - `TestParserEnforcesJSONDepthAt128`
  - `TestParserRejectsIdentitySequenceTimestampEnumAndIntegerContradictions`
  - `TestParserPreservesUnconsumedMetadataAndDataAsOpaqueJSON`
  - `TestParserStopsPromptlyWhenContextIsCanceled`
- **Type**: Go unit
- **Location**: `bifrost-console/internal/traceanalysis/parser_test.go`
- **What it proves**: Framing and fixed parser bounds are exact; consumed values are strict; unknown unconsumed JSON is retained/addressable rather than rejected or interpreted.
- **Fixtures/data**: Table-driven inline lines plus generated 1 MiB/1 MiB+1 and depth-128/129 records.
- **Mocks**: Fragmenting/cancelable readers and a recording component sink.
- **Contract classification**: Ephemeral diagnostic format.
- **Compatibility expectation**: Current writer/reader/debugging-tool coherence.

### 4. Streamed Logical Payload Reconstruction

- **Names**:
  - `TestProcessorReconstructsChunkedTextAndJSONWhilePreservingPhysicalRecords`
  - `TestProcessorRejectsMissingDuplicateOutOfOrderInterleavedAndMismatchedChunks`
  - `TestProcessorRejectsInvalidReconstructedJSONAndContentType`
  - `TestProcessorStreamsLargePayloadWithoutWholeValueReadOrWrite`
- **Type**: Go unit/integration
- **Location**: `bifrost-console/internal/traceanalysis/payload_test.go`
- **What it proves**: The envelope keeps the logical record sequence/payload descriptor, every chunk remains physically addressable, invalid chunk contracts fail, and a large logical payload is copied incrementally.
- **Fixtures/data**: Java chunked text/JSON fixtures plus an on-demand 64 MiB generated chunk stream.
- **Mocks**: Reader generating chunks without a 64 MiB backing slice; sink records maximum individual write/request and fails if it exceeds the parser buffer bound.
- **Contract classification**: Ephemeral diagnostic format.
- **Compatibility expectation**: Current chunk meaning; no Java reader's partial-payload tolerance.

### 5. Frame Graph and Timing Calculations

- **Names**:
  - `TestFramesBuildDeterministicHierarchyFromRecordedIDs`
  - `TestFramesRejectDuplicateMissingSelfParentCycleAndConflictingIdentity`
  - `TestFramesCalculateInclusiveAndSelfDuration`
  - `TestFramesMarkIncompleteOrOverlappingSelfDurationUnavailable`
  - `TestFramesRejectNegativeOrOutsideParentCompleteIntervals`
  - `TestFramesProcessTwentyThousandDeepChainIteratively`
- **Type**: Go unit/fixture contract
- **Location**: `bifrost-console/internal/traceanalysis/frames_test.go`
- **What it proves**: `WF-SP-R1/R3/R4/R5/R11`; hierarchy is recorded/mechanical, duration uncertainty is explicit, and valid depth does not cause recursion failure or truncation.
- **Fixtures/data**: Java nested/repeated/incomplete/overlap cases plus generated deep/invalid graphs.
- **Mocks**: None for calculations; use a recording index writer where isolation is useful.
- **Contract classification**: Ephemeral diagnostic format.
- **Compatibility expectation**: Current-run hierarchy accuracy with no depth/node compatibility cap.

### 6. Attempts, Validation, Failure, and Terminal Semantics

- **Names**:
  - `TestAttemptsUseOnlyExplicitAttemptAndRetryIdentity`
  - `TestAttemptsAllowSentWithoutResponseAndReportUsageGap`
  - `TestAttemptsRejectInconsistentIdentityNumberAndLifecycleOrder`
  - `TestValidationsLinkOnlyToRecordedAttemptIdentity`
  - `TestFailuresKeepRecoveredErrorsSeparateFromTerminalFailure`
  - `TestTerminalOutcomeRequiresExactlyOneFinalConsistentCompletion`
- **Type**: Go unit/fixture contract
- **Location**: `bifrost-console/internal/traceanalysis/attempts_test.go`, `failures_test.go`
- **What it proves**: Attempt/retry/failure relationships never come from adjacency or text; missing response facts remain gaps; success/failure/abort terminal linkage is exact.
- **Fixtures/data**: Existing advisor retry, nested retry, validation exhaustion, terminal failure/abort, nonterminal-error-then-success, missing/non-final completion fixtures and added contradictions.
- **Mocks**: None.
- **Contract classification**: Ephemeral diagnostic format.
- **Compatibility expectation**: Current-run failure visibility and authoring-guide evidence.

### 7. Usage Attribution and Reconciliation

- **Names**:
  - `TestUsageCalculatesDirectDescendantInclusiveAttemptAndRetryWithoutDoubleCounting`
  - `TestUsageKeepsUnframedAttributedAndTerminalRemainderSeparate`
  - `TestUsageRepresentsUnavailableOrMissingAsUnknownNotZero`
  - `TestUsageAcceptsExactHeuristicAndUnavailableOnly`
  - `TestUsageReconcilesPromptCompletionAndTotalIndependently`
  - `TestUsageRejectsNegativeRemainderInvalidComponentsAndOverflow`
- **Type**: Go unit/fixture contract
- **Location**: `bifrost-console/internal/traceanalysis/usage_test.go`
- **What it proves**: `WF-UE-R1/R3/R4/R6/R7/R9/R11`; each physical response is counted once, hierarchy/retry totals do not overlap, and uncertainty/remainder are not rewritten.
- **Fixtures/data**: Existing usage fixtures plus new framed/unframed/overflow/component-divergence cases.
- **Mocks**: None.
- **Contract classification**: Ephemeral diagnostic format and authoring-documentation evidence.
- **Compatibility expectation**: Current semantic accuracy; `ESTIMATED` rejected.

### 8. Index Format, Corruption, and Bounded Working State

- **Names**:
  - `TestIndexesRoundTripEveryRecordAndFactInCanonicalOrder`
  - `TestRecordAddressIndexFindsFirstMiddleAndLastPhysicalAndLogicalRecords`
  - `TestIndexWriterRejectsShortWriteSyncCloseAndSizeMismatch`
  - `TestQueryRejectsCorruptOrMissingInstalledComponentWithoutPartialEvidence`
  - `TestProcessorWorkingMemoryDoesNotMaterializeLargePayload`
- **Type**: Go unit/integration
- **Location**: `bifrost-console/internal/traceanalysis/index_test.go`, `processor_test.go`
- **What it proves**: Private indexes are complete and deterministic, exact raw addressing works, corruption is visible, and payload-size growth is disk/streaming rather than whole-value memory.
- **Fixtures/data**: Small deterministic index rows, fault-injected files, generated large payload.
- **Mocks**: Faulty sink/component opener; maximum-buffer/write recorder. Add a non-gating `BenchmarkProcessChunkedPayload` with `-benchmem` for review evidence.
- **Contract classification**: Internal implementation supporting an ephemeral diagnostic format.
- **Compatibility expectation**: No persisted index compatibility; corruption must fail current-run analysis.

### 9. Validated Bundle Publication and Joined Processing

- **Names**:
  - `TestAcquireRejectsMalformedNDJSONBeforePublishingHandle` (red test, retain)
  - `TestAcquireJoinsConcurrentWaitersIntoOneProcessedBundle`
  - `TestAcquirePublishesOnlyAfterRawAndDerivedComponentsSyncAndAtomicRename`
  - `TestAcquireProcessorFailureRemovesBundleAndReleasesAllCapacity`
  - `TestAcquireFinalWaiterCancellationStopsProcessorAndCleansBundle`
  - `TestAcquireReturnsSameProcessedHandleWithoutReprocessing`
- **Type**: Go artifact unit/integration
- **Location**: `bifrost-console/internal/artifact/acquire_test.go`
- **What it proves**: One transfer/processor/bundle/handle/result is shared, and publication is all-or-nothing.
- **Fixtures/data**: Valid/malformed corpus bytes; processor fake with call counters, barriers, component writes, cancellation, and failure injection.
- **Mocks**: Fake loader/opener/processor and expanded faulty filesystem. Production-composition tests use the real processor.
- **Contract classification**: Internal artifact lifecycle plus ephemeral diagnostic admission.
- **Compatibility expectation**: Approved removal of raw-only handle publication.

### 10. Aggregate Capacity, Lease, Cleanup, and Authorization Regressions

- **Names**:
  - `TestCapacityChargesRawAndEveryDerivedComponentExactlyOnce`
  - `TestCapacityEvictsEligibleBundleButNeverPinnedQuery`
  - `TestLeaseOpensOnlyEnumeratedComponentsWithoutExposingPaths`
  - `TestRemovalExpiryScopeRotationShutdownDeleteCompleteBundle`
  - `TestRestartCleanupNeverAdoptsOldSingleFileOrBundleLayout`
  - `TestAuthenticationRejectionPreservesValidCurrentScopeProcessedBundle`
  - existing `PR12-R01`–`PR12-R13` tests (update helpers/expectations)
- **Type**: Go unit/integration/race
- **Location**: `bifrost-console/internal/artifact/capacity_test.go`, `lease_test.go`, `storage_test.go`, `target_owner_test.go`, `service_test.go`
- **What it proves**: The storage refactor preserves the protected PR 12 lifecycle and configuration semantics while counting derived bytes.
- **Fixtures/data**: Deterministic component sizes, manual clock/timers, current-scope credentials, old-layout sentinel files.
- **Mocks**: Existing real temporary workspace, fake clock/timer/entropy, expanded fault filesystem.
- **Contract classification**: Configuration contract and internal implementation.
- **Compatibility expectation**: Protect cache policy; deliberately remove old layout adoption/reader behavior.

### 11. Neutral Query Results and Filters

- **Names**:
  - `TestSummaryCarriesScopeHandleIdentityTerminalRootsUsageGapsAndUncertainty`
  - `TestFrameQueryFiltersAndOrdersWithStableCanonicalTieBreakers`
  - `TestRecordQuerySeparatesLogicalAndPhysicalRepresentations`
  - `TestAttemptValidationFailureQueriesReturnRecordedCrossReferences`
  - `TestAllFixtureFramesRecordsAndPayloadsAreReachableThroughFiniteCalls`
  - `TestOptionalAndUnknownFactsNeverSerializeInternallyAsKnownZero`
- **Type**: Go unit/integration
- **Location**: `bifrost-console/internal/traceanalysis/service_test.go`, `query_frames_test.go`, `query_records_test.go`, `query_facts_test.go`
- **What it proves**: Neutral DTOs contain shared mechanical facts, filters/orders are deterministic, and complete traversal is possible without whole-trace responses.
- **Fixtures/data**: Expanded Java corpus and generated page-boundary traces.
- **Mocks**: Real artifact service/processor for integration; fake lease provider only for narrow error tests.
- **Contract classification**: Internal transport-neutral service over ephemeral diagnostics.
- **Compatibility expectation**: Shared browser/MCP meaning (`WF-X-R7`), not an adapter wire contract.

### 12. Page Bounds and Continuation Integrity

- **Names**:
  - `TestQueryAppliesDefaultAndAcceptsExactMaximumPageSize`
  - `TestQueryRejectsZeroNegativeAndOneOverMaximumPageSize`
  - `TestCursorBindsOperationScopeHandleFingerprintOrderingAndProgress`
  - `TestCursorRejectsMalformedUnknownTrailingAndTamperedFields`
  - `TestCursorErrorPrecedenceIsTargetChangedThenArtifactExpiredThenInvalidCursor`
  - `TestPaginationHasNoDuplicatesOrOmissionsAcrossEverySupportedOrder`
- **Type**: Go unit/integration
- **Location**: `bifrost-console/internal/traceanalysis/cursor_test.go`, `service_test.go`
- **What it proves**: Finite traversal cannot be silently restarted or applied to other evidence/query meanings.
- **Fixtures/data**: Deterministic multi-page frames/records plus multiple scopes/handles.
- **Mocks**: Deterministic handle/scope factory and controllable artifact removal/expiry.
- **Contract classification**: Internal service and target-scope lifecycle.
- **Compatibility expectation**: Current-scope continuation integrity; no durable cursor support.

### 13. Search Work Bounds and Range Correctness

- **Names**:
  - `TestSearchStopsAtEightMiBOrTenThousandRecordsAndContinues`
  - `TestSearchContinuationFindsBoundarySpanningLiteralWithoutDuplicates`
  - `TestSearchRejectsTextOverByteOrCodePointLimit`
  - `TestPayloadRangeAppliesDefaultExactMaximumAndOneOverLimit`
  - `TestTextRangeAdjustsToUTF8BoundariesAndReportsActualOffsets`
  - `TestBinaryRangeReturnsBase64WithExactOffsets`
  - `TestPayloadRawRecordAndRawArtifactReferencesCannotBeInterchanged`
- **Type**: Go unit/integration
- **Location**: `bifrost-console/internal/traceanalysis/search_test.go`, `range_test.go`
- **What it proves**: Search/ranges are finite, continuable, byte-accurate, encoding-safe, and reference-bound.
- **Fixtures/data**: Generated ASCII/Unicode/binary payloads, 10,001 records, >8 MiB searchable data, chunk-boundary literals.
- **Mocks**: Recording/cancelable readers; real component files for range integration.
- **Contract classification**: Internal service over ephemeral diagnostic content.
- **Compatibility expectation**: Complete current-run inspectability through bounded calls.

### 14. Console Composition, Exact Release Gate, and Security

- **Names**:
  - `TestArtifactAcquisitionInstallsValidatedBundleAndQueriesSharedFacts` (update integration)
  - `TestInvalidArtifactReturnsSharedErrorAndLeavesNoStorageEntry`
  - `TestScopeRotationCancelsProcessingAndRejectsLateQueryPublication`
  - `TestArtifactEvidenceRemainsAfterAuthenticationRejection` (retain/update)
  - `TestScopeOpenArtifactRejectsIncompatibleTarget` (retain)
  - `TestAnalysisErrorsLogsAndDTOsDoNotLeakPathsCredentialsPayloadsOrCursorInternals`
  - existing browser artifact-storage E2E flow (regression only)
- **Type**: Go integration/security and existing browser E2E
- **Location**: `bifrost-console/internal/console/artifact_integration_test.go`, `observability_integration_test.go`, `security_integration_test.go`; existing `bifrost-console/web/e2e/artifact-storage.spec.ts`
- **What it proves**: Production wiring uses the real processor before publication, exact release mismatch prevents artifact/analysis access, lifecycle errors remain shared, and new internal state does not leak.
- **Fixtures/data**: Java fixture server responses and an intentionally inexact `consoleCompatibilityVersion`.
- **Mocks**: Existing `httptest` application server, real target/artifact/traceanalysis services, captured logs/JSON.
- **Contract classification**: Java-to-Go boundary, target lifecycle, and security boundary.
- **Compatibility expectation**: Protect exact release-string rejection and PR 12 authorization/security; no browser/MCP analysis DTO commitment in PR 13.

### 15. Authoring Guidance Evidence

- **Names**:
  - `validFixtureUsagePrecisionValuesAreCurrentEnums`
  - existing `ModelUsageExtractorTest` HEURISTIC case
  - existing `SessionUsageServiceTest` HEURISTIC aggregation cases
  - `TestUsageRepresentsUnavailableOrMissingAsUnknownNotZero`
  - `TestUsageReconcilesPromptCompletionAndTotalIndependently`
- **Type**: Java and Go focused behavioral tests
- **Location**: Existing Java usage/fixture tests and new Go usage tests.
- **What it proves**: Every changed claim in `traces-and-debugging.md` is executable behavior.
- **Fixtures/data**: Current fixture corpus.
- **Mocks**: Existing provider-usage test doubles only.
- **Contract classification**: Authoring guidance backed by an ephemeral diagnostic format.
- **Compatibility expectation**: Correct current checkout guidance; no `ESTIMATED` alias.

## Tests to Remove or Rewrite

- Rewrite artifact tests that use arbitrary raw `"test"` bytes in production-composition paths; use valid Java fixture bytes. Artifact-only lifecycle tests may inject a named fake processor.
- Replace assertions that installed/charged bytes equal raw transfer length with raw-plus-derived bundle accounting where the real processor runs.
- Replace single-file storage/path fault helpers with bundle/component equivalents. Do not retain an old-layout test mode.
- Remove the valid `ESTIMATED` fixture expectation and any test accepting it.
- Keep raw pass-through download tests unchanged: malformed raw download remains separate and does not create an analysis handle.
- Do not add historical fixture, legacy cursor, prior bundle format, or Java-reader partial-payload compatibility tests.

## Mocking and Fixture Strategy

- Valid cross-language semantics must use files generated by real `DefaultExecutionTraceHandle`; do not hand-author valid NDJSON.
- Invalid corpus artifacts should remain minimal deterministic mutations made by the Java fixture generator.
- Parser boundary tests may generate isolated records directly because they target Go framing/resource defenses rather than Java writer behavior.
- Artifact lifecycle tests should use an explicit fake `artifact.Processor` with call counts, barriers, deterministic component sizes, cancellation, and failure injection.
- Console integration tests must use the real `traceanalysis.Service` and existing `httptest` application endpoints.
- Filesystem tests should extend the current `fileSystem` seam and real verified temporary workspace. Do not expose or assert production absolute paths.
- Use manual clock/timers and deterministic entropy already established in `internal/artifact/helpers_test.go`.
- Generate very large/deep inputs on demand and stream them; do not commit multi-megabyte stress fixtures.

## How to Run

### Prerequisites

- Repository root: `C:\opendev\code\bifrost`
- Go 1.26.5, Node.js 24.18.0, and npm 12.0.2 for the canonical Console verifier.
- The repository's Maven/JDK toolchain for `bifrost-spring-boot-starter`.
- No external target, credential, profile, environment variable, or network access is required for automated tests.
- Fixture regeneration is intentional-only through `-Dbifrost.console.fixtures.regenerate=true`.

### Red-Test Evidence Before Implementation

From the repository root:

```text
mvn -pl bifrost-spring-boot-starter -Dtest=ConsoleTraceFixtureCorpusTest#validFixtureUsagePrecisionValuesAreCurrentEnums test
```

From `bifrost-console/`:

```text
go test ./internal/artifact -run '^TestAcquireRejectsMalformedNDJSONBeforePublishingHandle$' -count=1
```

Record that each fails for the expected assertion, not compilation, fixture discovery, toolchain, or unrelated reasons.

### Focused Java Verification

From the repository root:

```text
mvn -pl bifrost-spring-boot-starter -Dtest=ConsoleTraceFixtureCorpusTest,ModelUsageExtractorTest,SessionUsageServiceTest test
```

Intentional fixture regeneration:

```text
mvn -pl bifrost-spring-boot-starter -Dtest=ConsoleTraceFixtureCorpusTest -Dbifrost.console.fixtures.regenerate=true test
git diff -- bifrost-console-fixtures
```

Run regeneration a second time and require no additional diff.

### Focused Go Verification

From `bifrost-console/`:

```text
go test ./internal/traceanalysis ./internal/artifact ./internal/console ./internal/browserapi ./internal/applicationclient ./internal/target
go test -race ./internal/traceanalysis ./internal/artifact ./internal/console
go test ./internal/console -run '^TestScopeOpenArtifactRejectsIncompatibleTarget$' -count=1
go test ./internal/traceanalysis -run '^TestFixtureCorpusMatchesJavaExpectedSemantics$' -count=1
go test ./internal/traceanalysis -run '^$' -bench 'ProcessChunkedPayload|DeepHierarchy|RecordQuery|Search' -benchmem
```

### Canonical Full Verification

From `bifrost-console/`:

```text
go run ./internal/buildtool verify
```

From the repository root, run the repository-standard broader Maven verification required by the implementation/review workflow in addition to the focused module command.

### Static Consistency Checks

From the repository root:

```text
rg -n "ESTIMATED" ai/skill-authoring bifrost-console-fixtures bifrost-spring-boot-starter/src
rg -n "legacy|fallback|compatibility" bifrost-console/internal/traceanalysis bifrost-console/internal/artifact
git diff --check
```

The first search must return no current semantic use. Review hits from the second search individually; no old trace/index/storage reader or raw-only production fallback may remain.

## Manual Verification

1. Start Console against the existing fixture-serving test application or equivalent local fixture target with an exactly matching release string.
2. Acquire the valid nested-frame fixture through the existing trace action.
3. Confirm Trace Storage shows one handle and aggregate raw-plus-derived local bytes.
4. Traverse summary, frame, record, attempt, validation, failure, usage, and payload queries with deliberately small pages/ranges until all expected evidence is reached.
5. Acquire malformed, oversized, truncated, and contradictory artifacts and confirm `INVALID_ARTIFACT`, no installed entry, and separate raw-download availability.
6. Start a long search/query, change the selected target, and confirm the old operation/cursor yields `TARGET_CHANGED`.
7. Remove or expire an unpinned artifact between continuation calls and confirm `ARTIFACT_EXPIRED`.
8. Reject the current application credential after successful acquisition and confirm the valid local handle remains queryable while new target access requires authentication.
9. Inspect logs, JSON errors, browser state, and Trace Storage output for absence of local paths, credentials, automatic payload disclosure, and cursor internals.

## Exit Criteria

- [ ] Both failing tests were observed failing on pre-fix behavior for the expected reasons.
- [ ] Both failing tests pass after implementation and remain in the suite.
- [ ] Java fixture regeneration is deterministic; a second regeneration produces no additional diff.
- [ ] Every committed Java trace has one paired expected file, and the Go corpus test consumes the entire inventory.
- [ ] Valid fixtures cover success, failure, abort, nested/repeated frames, retry/validation, incomplete/overlapping timing, chunked text/JSON, unavailable/unframed/unattributed usage, and recovered errors.
- [ ] Invalid coverage includes malformed, oversized, truncated, structural-depth, identity/order/enum, frame, attempt, terminal failure, chunk, negative/overflow, and usage-reconciliation contradictions.
- [ ] Parser boundary tests prove the exact 1 MiB line and depth-128 behavior.
- [ ] Large-payload tests prove bounded read/write operations with no whole-logical-payload materialization; benchmark evidence is captured for review.
- [ ] A 20,000-frame valid chain is completely indexed/queryable without recursion failure or hierarchy truncation.
- [ ] Direct, descendant, inclusive, attempt, retry, unframed, unavailable, and terminal-remainder usage facts match Java expected results without double counting.
- [ ] Every frame, logical record, physical record, and payload byte in representative fixtures is reachable through finite pages/ranges while its handle remains valid.
- [ ] Page, search, literal, inline-payload, and range limits pass at the boundary and reject the first value above it.
- [ ] Continuations reject cross-operation/scope/handle/filter/order/representation/range reuse and resume without duplicates or omissions.
- [ ] Cursor error precedence is `TARGET_CHANGED`, then `ARTIFACT_EXPIRED`, then `INVALID_CURSOR`.
- [ ] Invalid processing publishes no handle, installed bundle, retained index/payload component, or capacity charge.
- [ ] Joined acquisition performs one transfer, one processor run, one bundle publication, and one capacity charge.
- [ ] Aggregate capacity, TTL, LRU, pinning, removal, scope rotation, authentication rejection, shutdown, restart cleanup, and workspace-fatal behavior remain green under normal and race tests.
- [ ] `TestScopeOpenArtifactRejectsIncompatibleTarget` proves an inexact release string prevents artifact/analysis access before partial rendering or parsing.
- [ ] Raw pass-through download remains byte-exact and lifecycle-independent, including when semantic analysis rejects the artifact.
- [ ] Logs, errors, DTOs, handles, references, and cursors expose no local paths or credentials and do not automatically inline large payloads.
- [ ] Tests cited for `traces-and-debugging.md` prove `HEURISTIC`, unavailable-versus-unattributed distinction, and component-wise reconciliation.
- [ ] `ESTIMATED` has no current semantic use in production source, fixtures, or skill-authoring guidance; no dual reader/alias exists.
- [ ] Old single-file analysis installation and raw-only production acquisition are absent rather than retained behind fallbacks.
- [ ] Focused Java tests, focused Go tests, race tests, exact-version regression, and `go run ./internal/buildtool verify` all pass.
- [ ] Manual verification steps are complete, or review records why a fully automated equivalent supersedes a specific manual step.

## References

- Implementation plan: `ai/thoughts/plans/2026-07-30-bifrost-console-pr-13-trace-analysis-services.md`
- Ticket: `ai/thoughts/tickets/bifrost-console-pr-13-trace-analysis-services.md`
- Research: `ai/thoughts/research/2026-07-30-PR-13-trace-parser-indexes-shared-calculations.md`
- Workflow requirements: `ai/thoughts/phases/bifrost_console_workflows.md`
- Current-release console design: `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md`
- Future neutral query consumers: `ai/thoughts/tickets/bifrost-console-pr-14-trace-explorer.md`, `ai/thoughts/tickets/bifrost-console-pr-18-mcp-trace-inspection.md`
- Contract policy: `ai/thoughts/framework-feature-design-lens.md`
- Authoring guidance: `ai/skill-authoring/traces-and-debugging.md`, `ai/skill-authoring/source-verification.md`
- Java fixture producer: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ConsoleTraceFixtureCorpusTest.java`
- Existing Go artifact tests: `bifrost-console/internal/artifact/`, `bifrost-console/internal/console/artifact_integration_test.go`
