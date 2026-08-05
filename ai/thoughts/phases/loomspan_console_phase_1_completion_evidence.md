# Loomspan Console Phase 1 Completion Evidence

This is the evidence index for the ten authoritative completion criteria in
`LOOMSPAN_console_phase_1_observability_foundation.md`. It does not define a
second requirement set. Status is recorded only after the named repository
wrapper command passes.

## Automated evidence

| Criterion | Evidence | Status |
| --- | --- | --- |
| 1. Instance identity and inspection | `ObservabilityRestIntegrationTest.authenticatesAndReturnsExactReleaseIdentityAndNoStore`; `application-rest/instance-status.json` | Passing on Windows, 2026-07-26 |
| 2. Paginated registered-skill catalog and unchanged YAML | `ObservabilityRestIntegrationTest.collectionAndNamespaceFallbackAreStable`; `ConsoleRestFixtureCorpusTest` | Passing on Windows, 2026-07-26 |
| 3. Bounded active baseline, identity, resume cursor, and detail | `ObservabilityRestIntegrationTest.activeBaselineCarriesInstanceObservationAndResumeCursor`; `activeContinuationRetainsFirstPageHighWaterAndRejectsAnotherInstance` | Passing on Windows, 2026-07-26 |
| 4. Ordered summarized activity | `ObservabilitySseIntegrationTest.opensAuthenticatedStreamWithHandshakeAndReplaysActivityAfterCursor`; `ConsoleSseFixtureCorpusTest` | Passing on Windows, 2026-07-26 |
| 5. Cursor reconnect and replay-gap behavior | `ObservabilitySseIntegrationTest`; `ObservabilityRestIntegrationTest.activityRequestFailuresRemainJsonBeforeAsyncOwnership`; `application-sse/replay.sse` | Passing on Windows, 2026-07-26 |
| 6. Current-process trace catalog, detail, and exact artifact | `ObservabilityRestIntegrationTest.listsAndGetsCurrentActiveExecutionAndFinalizedTrace`; `ObservabilityArtifactIntegrationTest.downloadsExactFinalizedArtifactWithRequiredHeaders`; `ConsoleArtifactFixtureCorpusTest` | Passing on Windows, 2026-07-26 |
| 7. Live-to-final trace correlation | Existing observation/catalog tests plus `ObservabilityArtifactIntegrationTest`; the failed-execution workflow `WF-FAILED-EXECUTION` uses the same opaque `traceId` | Passing on Windows, 2026-07-26 |
| 8. Stable application problem codes | `ObservabilityApiKeyFilterTest`; `ObservabilityRestIntegrationTest`; `application-rest/problem-*.json` | Passing on Windows, 2026-07-26 |
| 9. Canonical completion versus core-finalization failure | `DefaultExecutionObservationHandleTest`; `ConsoleSseFixtureCorpusTest` fixtures `activity-trace-completed.sse` and `activity-core-finalization-failed.sse` | Passing on Windows, 2026-07-26 |
| 10. Engine isolation from observability failure | `DefaultExecutionObservationHandleTest`; `ObservabilitySseIntegrationTest`; `ExecutionTraceHandleTest` | Passing on Windows, 2026-07-26 |
| Concurrency and explicit bounds | `ObservabilityActivityDeliveryTest`; `ObservabilityArtifactDeliveryTest.rejectsNinthDownloadWithoutQueuingAndReclaimsSlot`; `ObservabilityArtifactStreamTest` | Passing on Windows, 2026-07-26 |
| Lifecycle-proportional resources and expiration | `ScheduledCompletionGraceRetentionTest.leaseOpenedBeforeDeadlineDefersDueDeletionUntilClose`; `InMemoryFinalizedTraceCatalogTest.acquiresPathFreeMetadataAndCoreLeaseBeforeEffectiveExpiry`; runtime shutdown tests | Passing on Windows, 2026-07-26 |
| Authorization | `ObservabilityApiKeyFilterTest`; `ObservabilityHostSecurityIntegrationTest`; artifact real-server authentication assertions | Passing on Windows, 2026-07-26 |

Run the evidence with:

```powershell
.\mvnw.cmd -pl loomspan-spring-boot-starter test
.\mvnw.cmd -pl loomspan-sample -am test
.\mvnw.cmd verify
```

Passing means every command exits 0 and ordinary tests produce no fixture diff.
Fixture regeneration is separately checked twice:

```powershell
.\mvnw.cmd -pl loomspan-spring-boot-starter '-Dtest=ConsoleTraceFixtureCorpusTest,ConsoleRestFixtureCorpusTest,ConsoleSseFixtureCorpusTest,ConsoleArtifactFixtureCorpusTest' '-Dloomspan.console.fixtures.regenerate=true' test
git diff -- loomspan-console-fixtures
```

## Manual evidence still required

- Run the sample with an externalized 32+ character key, invoke the mapped
  `/expenses` endpoint, list traces, and byte-compare the downloaded NDJSON.
- Repeat under a servlet context path and confirm every route remains relative
  to that context.
- Hold and cancel a throttled transfer; confirm a later download is admitted,
  a pre-expiry transfer finishes, and new post-expiry acquisition is
  `404/NOT_FOUND`.
- Observe memory with a large artifact and confirm it remains bounded by the
  fixed copy buffer; open eight downloads and confirm immediate ninth-request
  rejection without affecting SSE.
- Review cancellation, timeout, open/read/write, and deletion-failure logs for
  absence of API keys, header values, payloads, and internal paths.

## Downstream exact-version gate

PR 09 owns the Go test that an inexact `consoleCompatibilityVersion` prevents
every request after instance status: no snapshots, SSE, catalog, or artifact
acquisition. Java fixtures associate artifact metadata with the exact same
complete release string, but do not claim that future Go behavior is already
tested here.
