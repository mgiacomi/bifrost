# PR 06 Finalized Artifact Streaming and Phase 1 Integration Implementation Plan

## Overview

Complete the Phase 1 application boundary by serving the exact core-owned
finalized NDJSON artifact through the authenticated observability namespace,
while preserving expiration, deletion ownership, bounded resource use, and
cancellation semantics. Finish the Java-produced transport fixtures, sample
wiring, operational documentation, and completion-evidence matrix that future
Go PRs 09, 12, and 13 consume.

This is a framework-owned diagnostic boundary. The model, skill author, and
ordinary application caller do not choose artifact paths, retention, or
authorization. The runtime has the authoritative catalog entry, core expiration
state, and application credential, so acquisition and deletion coordination
belong below the HTTP adapter.

## Current State Analysis

PRs 01 through 05 are implemented. The framework already writes deterministic
finalized NDJSON, publishes path-free metadata to a current-process trace
catalog, exposes secured REST snapshots, and provides bounded authenticated
SSE. The exact file path remains internal to
`FinalizedTraceCatalogEntry`; no download handler exists
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/FinalizedTraceCatalogEntry.java:9-20`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRouteRegistrar.java:99-113`).

The remaining correctness gap is the expiration race. Catalog lookup rejects
new use at `applicationTraceExpiresAt`, but
`ScheduledCompletionGraceRetention` independently calls
`Files.deleteIfExists` at the core deadline and has no in-flight acquisition
state
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/InMemoryFinalizedTraceCatalog.java:112-120`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/ScheduledCompletionGraceRetention.java:49-113`).
Merely opening the file and relying on platform delete behavior is insufficient:
Windows can reject deletion of an open file, while Unix can unlink it, and the
current best-effort deletion has no retry. The core therefore needs an explicit
lease that defers its own grace deletion until an already-admitted reader
releases the file.

Finite REST currently buffers JSON into `ResponseEntity<byte[]>`; only SSE uses
Servlet async ownership and exact-once cleanup
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestController.java:53-247`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityActivityStream.java:59-91`).
Artifact delivery must not buffer trace contents or run blocking file/network
work under execution locks.

The fixture corpus already contains ten valid and eight invalid artifacts plus
the expected Go-consumed semantic results. PR 06 must reuse those exact files,
not create a second artifact corpus
(`bifrost-console-fixtures/README.md:1-23`). The sample retains traces with
`execution-trace.persistence: ALWAYS`, but observability is not enabled and the
sample does not yet demonstrate host-security pass-through
(`bifrost-sample/src/main/resources/application.yml:64-65`,
`bifrost-sample/pom.xml:17-34`).

## Desired End State

An authenticated client can request:

```text
GET /_bifrost/observability/v1/traces/{traceId}/artifact
X-Bifrost-Api-Key: <application observability key>
Accept: application/x-ndjson
```

For a cataloged, unexpired trace, the adapter responds with the exact bytes of
the core-owned finalized file and:

```text
Content-Type: application/x-ndjson; charset=utf-8
Content-Disposition: attachment; filename="bifrost-trace-<traceId>.ndjson"
Content-Length: <cataloged exact byte size>
Cache-Control: no-store
X-Bifrost-Instance-Id: <current instance UUID>
```

The route has a fixed process-wide capacity of eight admitted downloads.
Authentication and resource lookup happen before admission; admission and a
core file lease happen before response commitment. A ninth concurrent request
receives `429/LIMIT_EXCEEDED` without queuing. Missing, expired, or no-longer
obtainable resources receive `404/NOT_FOUND` while the response is still
uncommitted. An admitted download that began before the effective expiration
may finish; the core performs an otherwise-due grace deletion after its last
lease closes. Each transfer has a fixed five-minute async timeout. Completion,
failure, client cancellation, timeout, and application shutdown release the
admission and lease exactly once.

The body is never rewritten, reconstructed, normalized, redacted, compressed by
Bifrost, parsed, or buffered in full. The route accepts only GET with no query,
range, or conditional request headers and an absent, wildcard, or compatible
NDJSON `Accept` header. Unsupported request shapes fail before streaming with
`400/INVALID_REQUEST`. Host-provided transport encoding remains outside the
Bifrost artifact contract.

Phase 1 is verified by focused unit/integration tests, deterministic REST/SSE
and artifact transport fixtures, the existing semantic trace corpus, a runnable
sample configuration, and a completion-evidence matrix mapping every criterion
in the Phase 1 design to automated or manual evidence.

### Key Discoveries

- Core finalization already records exact size and optional core expiry in
  `FinalizedTraceArtifact`; the adapter must not stat an arbitrary caller path
  or invent another descriptor
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/FinalizedTraceArtifact.java:9-42`).
- The effective acquisition deadline is already the earlier of core expiration
  and catalog metadata expiration
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/InMemoryFinalizedTraceCatalog.java:75-106`).
- The existing SSE admission and async cleanup pattern demonstrates immediate
  rejection and exact-once slot release, but artifact downloads require an
  independent counter and lifecycle
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityActivityDelivery.java:85-107`,
  `:281-340`).
- The API-key filter already applies `no-store` and authenticated instance
  identity before dispatch; download-specific media, disposition, and length
  headers belong to the artifact handler
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiKeyFilter.java:49-96`,
  `:133-145`).
- PR 12 will consume this route twice: once into a complete scope-bound
  immutable analysis copy and once as an unchanged raw browser pass-through.
  PR 13 consumes the existing NDJSON and expected-result corpus; neither future
  PR should depend on Java filesystem paths or a second artifact format
  (`ai/thoughts/tickets/bifrost-console-pr-12-artifact-service.md:11-44`,
  `ai/thoughts/tickets/bifrost-console-pr-13-trace-analysis-services.md:11-45`).

## What We're NOT Doing

- No Go client, target context, artifact cache, parser, browser UI, or MCP work.
- No filesystem discovery, restart adoption, history, archive, or durable
  deletion queue.
- No artifact copy, manifest, envelope, digest, completeness marker, range
  protocol, compression policy, or independent artifact/schema version.
- No record rewriting, logical payload reconstruction, redaction, or semantic
  validation in Java.
- No configurable download capacity, fairness, bandwidth quota, per-client
  identity, or generalized request scheduler.
- No tombstone or new problem code distinguishing unknown from expired.
- No public Application API or Supported SPI for artifact access, retention
  leases, download delivery, or Spring bean replacement.
- No compatibility overload, legacy path, alias route, or dual streaming
  implementation.

## Skill-Authoring Documentation Impact

**Impact**: No impact

- **Rationale**: PR 06 changes authenticated operator acquisition of an existing
  current-run diagnostic trace. It does not change manifest syntax, skill
  validation, execution/planning semantics, retry or usage meanings, trace
  record vocabulary, author-facing inputs/outputs, capability visibility,
  attachments, models, limits, or testing guidance. The existing
  `ai/skill-authoring/traces-and-debugging.md` already says traces are
  current-checkout diagnostics rather than an application contract; serving the
  exact same bytes does not change that authoring rule.
- **Documents to update**: None.
- **Supporting evidence**:
  `bifrost-console-fixtures/traces/`,
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ConsoleTraceFixtureCorpusTest.java`,
  `ai/skill-authoring/traces-and-debugging.md`.
- **Coverage table update**: Not required. The “Traces and debugging” topic
  coverage and confidence do not change because PR 06 adds transport without
  changing trace interpretation.
- **LLM-first usability**: Not applicable. Operational application-adapter
  instructions will remain in the root and sample READMEs rather than being
  duplicated into the skill-authoring routing tree.

## Contract and Compatibility Impact

| Surface | Classification and supporting evidence | Planned compatibility treatment |
| --- | --- | --- |
| Application API | No changes to the seven supported `com.lokiscale.bifrost.api` types; the root README closes the allowlist (`README.md:142-146`). | Preserve. Sample production code continues to pass `SupportedApiUsageArchitectureTest`. |
| Supported SPI | No Bifrost SPI or supported bean replacement point exists; observability beans and core lease types remain internal infrastructure (`README.md:142-144`, `BifrostObservabilityWebAutoConfiguration.java:35-124`). | No SPI added. Update internal collaborators atomically. |
| Configuration and manifest contracts | Existing `bifrost.observability.*` properties and defaults are unchanged. The sample begins demonstrating the already-supported opt-in and externalized key; no manifest behavior changes. | Preserve existing property names/defaults. No configuration migration. |
| Persisted or serialized contracts | The new artifact HTTP response and transport fixtures are a current-release coordinated Java-to-Go acquisition contract. The body is not a durable/cross-version interchange format. | Add the route, request rules, headers, and exact body behavior atomically with fixtures/tests/docs under the existing release string. |
| Ephemeral diagnostic formats | Exact finalized NDJSON and the 10-valid/8-invalid semantic corpus are unchanged. | Stream bytes unchanged; byte-compare the HTTP body to the committed source fixture. No legacy reader or schema version. |
| Internal or accidentally exposed implementation | `CompletionGraceRetention`, catalog entries, runtime, route/controller, access operation, downloader, lease, and Spring infrastructure are technically public/internal-package types, not supported contracts. | Replace the current direct scheduled-delete decomposition with one coherent lease-aware internal design and update all repository callers/tests atomically. |

- **Evidence of supported contracts**: Phase 1 design
  (`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:259-291`,
  `:315-343`, `:549-565`), roadmap Phase 1 gate
  (`ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:197-204`),
  Java fixtures, and future Go consumers in PRs 09, 12, and 13.
- **Intended breaks**: None to a supported contract. Internal
  `CompletionGraceRetention` implementations and constructors may change
  atomically to introduce lease-aware acquisition.
- **In-repository consumers to update**:
  `DefaultExecutionTraceHandle`, `ObservabilityActivationCoordinator`,
  `ObservabilityRuntime`, `ObservabilityRouteRegistrar`,
  `BifrostObservabilityWebAutoConfiguration`, controller/routes/access service,
  retention/catalog tests, architecture allowlist if a new public internal type
  is unavoidable, fixture generators, integration tests, sample configuration,
  sample security wiring/tests, root/sample documentation, roadmap/Phase 1
  completion evidence.
- **Public-surface delta**: No supported API/SPI delta. Add the lease as the
  nested internal collaboration type
  `CompletionGraceRetention.ArtifactLease`; keep artifact delivery
  implementations package-private. Do not add a top-level public type or expose
  a replacement bean through auto-configuration.
- **Shim decision**: **No shim.** The affected retention and adapter types are
  internal decomposition with no protected external consumers. Change them and
  all repository callers atomically.
- **Java-to-Go boundary coordination**: **Required.** PR 06 establishes the Java
  acquisition route, headers, request bounds, problems, exact byte behavior,
  SSE/artifact transport fixtures, tests, and documentation. PRs 09 and 12 must
  consume those committed fixtures and exact release match without inventing a
  second route or header convention; PR 13 continues to consume the unchanged
  NDJSON/expected semantic corpus.
- **Compatibility marker**: Keep the exact complete Maven product release
  string as `consoleCompatibilityVersion`. Do not add or increment an
  independent artifact, trace, adapter, or container version.

## Implementation Approach

Use one two-level acquisition transaction:

1. The adapter-owned trace catalog validates current-process identity and the
   effective application expiration.
2. The core-owned completion-retention service opens an exact-file lease while
   atomically coordinating any scheduled grace deletion.

`FinalizedTraceCatalog#acquire` captures one acquisition-start instant, rejects
an expired entry, and asks the core retention service to lease the
`FinalizedTraceArtifact`. For `ALWAYS` and errored `ONERROR` descriptors with no
core expiry, the lease opens the exact descriptor path without taking deletion
ownership. For grace-held descriptors, the retention service uses per-artifact
state guarded by a lock: acquiring before the core deadline increments the
reader count before opening; expiry marks the entry unavailable to new readers;
deletion runs immediately only with zero readers, otherwise on the last release.
The catalog reclaims only metadata and never performs deletion.

The web adapter uses a dedicated `ObservabilityArtifactDelivery` with an
eight-slot semaphore/counter and an adapter-owned virtual-thread-per-download
executor. The handler validates authentication and request shape, resolves
current catalog metadata, acquires a download slot, and then atomically leases
the artifact before committing headers. If lease acquisition loses an expiry or
deletion race, it releases the slot and returns `404/NOT_FOUND`. Once ownership
transfers, a Servlet `AsyncContext` with a fixed five-minute transfer timeout
streams fixed-size chunks from the lease to `ServletOutputStream`. Async
complete, error, timeout, disconnect/write failure, synchronous setup failure,
and runtime shutdown converge on one idempotent close path that closes the file,
releases the lease and admission, interrupts owned work when needed, and
completes the async context. After commitment, transport failure terminates the
response; it is never replaced with a JSON problem.

This direct Servlet async approach follows the existing SSE lifecycle without
installing a host-wide Spring MVC `TaskExecutor`. Virtual threads are suitable
for finite blocking file/socket copies on the Java 21 baseline, while the
eight-download admission limit bounds simultaneous files, connections, and
tasks. No work occurs under the execution trace append lock.

## Phase 1: Add Core-Owned Artifact Acquisition Leases

### Overview

Make “opened before expiration may finish” a platform-independent core
invariant. Preserve core deletion ownership and existing shutdown behavior.

### Changes Required

#### 1. Lease-aware completion retention contract

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/CompletionGraceRetention.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/ScheduledCompletionGraceRetention.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/ImmediateCompletionRetention.java`
- New narrow lease type under
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/`

**Changes**:

- Extend the internal retention collaboration with acquisition of the exact
  `FinalizedTraceArtifact`; add nested
  `CompletionGraceRetention.ArtifactLease`, an `AutoCloseable` exposing a
  readable channel/stream and the descriptor’s exact size.
- Replace the scheduled implementation’s set of futures with per-exact-path
  state containing descriptor identity, expiration, scheduled future, active
  lease count, expired/delete-pending state, and closed state.
- Make acquisition and timer expiry atomic with respect to that state. Refuse a
  grace artifact at/after its deadline or when it is no longer registered.
- For a non-expiring descriptor, open only its normalized exact path and do not
  register or delete it.
- If opening fails after reserving a reader, roll back the reader exactly once
  and run any now-unblocked due deletion.
- At the deadline, prevent new leases. Delete immediately with zero readers or
  defer until the final reader closes. Preserve the existing sanitized,
  no-retry warning on delete failure.
- Preserve zero-grace synchronous deletion and shutdown semantics: closing
  cancels pending timers and does not introduce special shutdown deletion or
  filesystem scanning.
- Update the activation gate so disabled observability retains immediate
  deletion and exposes no acquisition.

#### 2. Atomic current-process catalog acquisition

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/FinalizedTraceCatalog.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/InMemoryFinalizedTraceCatalog.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/FinalizedTraceCatalogEntry.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandleFactory.java`

**Changes**:

- Add a narrow acquisition result that carries the path-free public metadata
  needed by HTTP plus the core lease; do not expose the path to the controller.
- Inject the core retention/acquisition collaborator into the catalog.
- Under one catalog acquisition operation, capture the start time, reject an
  entry at/after `applicationTraceExpiresAt`, acquire the core lease, and return
  the acquisition. A concurrent metadata sweep may remove discoverability after
  acquisition without cancelling the returned lease.
- Map a missing file, expired core lease, or raced deletion to no acquisition.
  Keep unexpected I/O available to the adapter for safe
  `APPLICATION_ERROR` mapping before commitment.
- Retain `find` for metadata detail and keep `list`/count behavior unchanged.

#### 3. Focused lifecycle tests

**Files**:

- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ScheduledCompletionGraceRetentionTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/InMemoryFinalizedTraceCatalogTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionTraceHandleTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/ObservabilityActivationCoordinatorTest.java`

**Changes**:

- Start with failing tests for a lease held across core expiration, last-reader
  deletion, rejection after expiry, multiple readers, open failure rollback,
  and exactly-once close.
- Test metadata expiry during an active lease: new catalog acquisition fails,
  the admitted lease remains readable, and metadata removal does not delete an
  `ALWAYS` artifact.
- Preserve tests for zero grace, rejected scheduling, delayed-delete failure,
  close cancellation without deletion, exact descriptor size, and disabled
  activation.
- Avoid OS-specific assertions about unlinking open files; assert the explicit
  lease state and eventual exact-path deletion.

### Success Criteria

#### Automated Verification

- [x] Focused retention/catalog tests pass:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter '-Dtest=ScheduledCompletionGraceRetentionTest,InMemoryFinalizedTraceCatalogTest,ExecutionTraceHandleTest,ObservabilityActivationCoordinatorTest' test`
- [x] The test suite proves a pre-expiry reader finishes and the exact grace
  file is deleted only after its last lease closes.
- [x] Zero-grace and disabled-observability behavior remain unchanged.
- [x] No recursive, wildcard, caller-supplied, or catalog-owned deletion is
  introduced.

#### Manual Verification

- [x] Review the acquisition/expiry interleavings for both timer-wins and
  acquire-wins races.
- [x] Review shutdown ordering to confirm active delivery is stopped before
  retention and catalog resources close.

---

## Phase 2: Add Bounded Authenticated Artifact Streaming

### Overview

Expose the exact leased bytes through a fixed, documented HTTP subresource with
bounded admission and exact-once async cleanup.

### Changes Required

#### 1. Route, authorization operation, and request contract

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiPaths.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityAccessService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestController.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRouteRegistrar.java`

**Changes**:

- Add the exact nested route
  `GET /_bifrost/observability/v1/traces/{traceId}/artifact`; keep
  `/traces/{traceId}` as metadata detail.
- Add `TRACE_ARTIFACT_READ` to the centralized internal access operation enum;
  it initially requires the same sole `BIFROST_OPERATOR` authority.
- Add a dedicated artifact request validator:
  - method must be GET;
  - no query parameters;
  - no `Range`, `If-Range`, `If-Match`, `If-None-Match`,
    `If-Modified-Since`, or `If-Unmodified-Since`;
  - `Accept` may be absent, wildcard, or compatible with
    `application/x-ndjson`;
  - malformed or unsupported shapes map to `400/INVALID_REQUEST`.
- Resolve current catalog metadata before admission. After admission, acquire
  the atomic catalog/core lease before body commitment; release the slot and
  return `404/NOT_FOUND` if that second step loses an expiration or deletion
  race. Preserve the same problem for unknown, expired, deleted, or otherwise
  unobtainable resources without a tombstone distinction.
- Construct the filename only from the cataloged opaque trace ID:
  `bifrost-trace-<traceId>.ndjson`. Use Spring’s `ContentDisposition` builder
  to quote/encode the value safely rather than concatenating raw header text.
  Do not use the filesystem filename or path.

#### 2. Independent download admission and async delivery

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityDeliveryLimits.java`
- New
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityArtifactDelivery.java`
- New
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityArtifactStream.java`

**Changes**:

- Add `OPEN_ARTIFACT_DOWNLOADS = 8`, separate from the 16 SSE subscriptions.
  Add `ARTIFACT_DOWNLOAD_TIMEOUT = Duration.ofMinutes(5)`. Keep both fixed and
  package-private.
- Implement immediate, non-queued admission with an idempotent `Admission`
  close operation. `admit` throws `429/LIMIT_EXCEEDED` when all eight slots are
  owned.
- Own a named virtual-thread-per-task executor for finite blocking copies.
  Submit work only after admission and lease success; never retain a queued
  request beyond the fixed admission bound.
- Transfer the servlet request to `AsyncContext`, set the five-minute async
  transfer timeout, and install an `AsyncListener` before work begins. Timeout
  is a delivery cancellation, not a new problem code after commitment.
- Stream a fixed-size buffer from the leased input to
  `ServletOutputStream`; do not allocate by artifact length or read the whole
  file. Detect early EOF/size mismatch as a transport failure and never pad or
  reconstruct bytes.
- Converge normal completion, read/write failure, disconnect, async timeout,
  listener error, executor rejection, synchronous setup failure, and shutdown
  on one atomic cleanup that interrupts/cancels work where applicable, closes
  the lease, releases admission, and completes the async context at most once.
- Before async ownership transfers, close admission/lease and rethrow so the
  existing filter/problem mapper can return JSON. After commitment, terminate
  the response without trying to write a JSON problem.
- On delivery service shutdown, reject new admissions, cancel active transfers,
  release their leases, and stop owned virtual threads before core retention is
  closed.

#### 3. Response headers and runtime wiring

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/ObservabilityRuntime.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostObservabilityWebAutoConfiguration.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRouteRegistrar.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiKeyFilter.java`

**Changes**:

- Wire one artifact delivery service per enabled runtime; do not make it
  replaceable with `@ConditionalOnMissingBean`.
- Close runtime resources in dependency order: activity delivery, artifact
  delivery, completion retention, then catalog metadata.
- Set status and artifact headers before starting async work:
  `application/x-ndjson; charset=utf-8`, safe attachment disposition, and the
  descriptor’s known `Content-Length`.
- Continue relying on the filter for authenticated
  `X-Bifrost-Instance-Id` and `Cache-Control: no-store`; add tests proving
  these remain present on the download and authenticated pre-commit problems.
- Ensure all filter dispatch types remain async-supported and that redispatch,
  if any, cannot authenticate or start the artifact twice.

#### 4. Unit and embedded-server integration tests

**Files**:

- New `ObservabilityArtifactDeliveryTest.java`
- New `ObservabilityArtifactStreamTest.java`
- New `ObservabilityArtifactIntegrationTest.java`
- Update `ObservabilityRestIntegrationTest.java`
- Update `ObservabilityHostSecurityIntegrationTest.java`
- Update `ObservabilityRouteRegistrarTest.java`
- `BifrostPublicSurfaceArchitectureTest.java`

**Changes**:

- Assert exact bytes for normal, failed, chunked, usage, retry, and malformed
  fixtures; malformed semantic content still downloads unchanged.
- Assert media type/charset, safe disposition, exact length, no-store,
  instance identity, context-path behavior, path non-exposure, and no
  application-level content encoding.
- Assert missing/duplicate/invalid keys, unknown/expired trace, incompatible
  `Accept`, query/range/conditional headers, HEAD/POST, and reserved fallback
  problems before commitment.
- Hold eight real embedded-server downloads open, assert the ninth receives
  `429/LIMIT_EXCEEDED`, then prove normal completion, cancellation, timeout,
  and server shutdown reclaim every slot.
- Hold a real download across core grace expiry and assert the body completes
  byte-for-byte before last-lease deletion.
- Cancel a client mid-body and assert only that transfer closes, its slot and
  lease are reclaimed, and subsequent download succeeds.
- Keep host namespace pass-through behavior: host `permitAll` lets the request
  reach Bifrost authentication while unrelated business routes remain governed
  by the host.

### Success Criteria

#### Automated Verification

- [x] Artifact unit/integration tests pass:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter '-Dtest=ObservabilityArtifactDeliveryTest,ObservabilityArtifactStreamTest,ObservabilityArtifactIntegrationTest,ObservabilityRestIntegrationTest,ObservabilityHostSecurityIntegrationTest,ObservabilityRouteRegistrarTest' test`
- [ ] The ninth concurrent authenticated download receives
  `429/LIMIT_EXCEEDED` and every terminal path reclaims the slot.
- [x] Each representative HTTP body is byte-identical to its committed
  `bifrost-console-fixtures/traces/*.ndjson` source.
- [ ] A download opened before expiration completes across expiry; a new one
  receives `404/NOT_FOUND`.
- [x] Existing REST and SSE tests remain green:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter '-Dtest=ObservabilityRestIntegrationTest,ObservabilitySseIntegrationTest,ObservabilityHostSecurityIntegrationTest' test`

#### Manual Verification

- [ ] Inspect a browser/curl download and confirm the displayed filename is
  derived from the opaque trace ID, never the stored path.
- [ ] Throttle a local client, cancel mid-transfer, and confirm the application
  remains responsive and a later download is admitted.
- [ ] Review logs from cancellation and deletion failure to confirm they expose
  no API key, header value, trace payload, or internal path.

---

## Phase 3: Finalize Cross-Boundary Fixtures and Phase 1 Evidence

### Overview

Commit deterministic transport evidence without duplicating the canonical trace
corpus, and map every Phase 1 completion criterion to executable or deliberate
manual verification.

### Changes Required

#### 1. Deterministic SSE transport fixtures

**Files**:

- New `bifrost-console-fixtures/application-sse/handshake.sse`
- New representative event/replay fixtures under
  `bifrost-console-fixtures/application-sse/`
- New `ConsoleSseFixtureCorpusTest.java`
- `bifrost-console-fixtures/README.md`

**Changes**:

- Generate frames through the production `ObservabilityActivityStream`
  framing methods using fixed instance IDs, cursors, timestamps, and activity
  envelopes.
- Include handshake and representative normal completion,
  `CORE_FINALIZATION_FAILED`, and replay framing needed by future Go PR 11.
- Byte-compare the complete committed inventory and use the existing explicit
  `bifrost.console.fixtures.regenerate=true` workflow.
- Keep SSE fixtures separate from `application-rest/`; share production codecs
  and test helpers only where doing so does not merge distinct wire contracts.

#### 2. Artifact transport fixture metadata

**Files**:

- New `bifrost-console-fixtures/application-artifact/download-response.json`
- New `ConsoleArtifactFixtureCorpusTest.java`
- `bifrost-console-fixtures/README.md`

**Changes**:

- Commit deterministic consumed metadata for the exact route, method, status,
  content type, disposition, length, no-store, instance header, and relative
  body fixture reference.
- Point the body reference at
  `../traces/single-attempt-success.ndjson`; do not copy its bytes into the
  transport directory.
- Generate metadata with the same production header builder used by the
  handler, byte-compare inventory, and assert the referenced body length and
  exact streamed response bytes.
- Document that future Go PRs consume the metadata plus the existing trace
  corpus under the exact `consoleCompatibilityVersion`, and that the fixture
  JSON is test metadata rather than another runtime manifest.

#### 3. Phase 1 completion-evidence matrix

**Files**:

- New
  `ai/thoughts/phases/bifrost_console_phase_1_completion_evidence.md`
- `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md`
- `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md`

**Changes**:

- Map all ten criteria at Phase 1 design lines 435-450 plus concurrency,
  isolation, bounds/lifecycle, and authorization to named automated tests,
  fixture corpora, and any remaining manual evidence.
- Include exact repository wrapper commands and expected exit conditions.
- Mark evidence status as implemented/passing only after the cited command has
  run; do not use a plan checkbox as proof.
- Link the matrix from the Phase 1 design and roadmap gate. Do not copy the
  design rationale or create a second requirement namespace.
- Reference the “Diagnose a failed completed execution” workflow where the
  artifact fixtures and end-to-end test cover it.

#### 4. Integrated Phase 1 scenario

**Files**:

- New
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityPhaseOneIntegrationTest.java`

**Changes**:

- Exercise one lifecycle from authenticated instance status and active
  baseline through SSE activity, trace catalog availability, metadata detail,
  exact artifact download, and post-expiration `NOT_FOUND`.
- Assert terminal activity reports `AVAILABLE` only after catalog publication
  and the artifact can already be leased.
- Include a core-finalization-failure scenario that produces the noncanonical
  terminal activity and no artifact without changing the execution failure
  semantics.
- Verify independent skill/catalog/download operations remain available after
  live projection is marked unavailable.

### Success Criteria

#### Automated Verification

- [x] Fixture generators pass and inventories match:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter '-Dtest=ConsoleTraceFixtureCorpusTest,ConsoleRestFixtureCorpusTest,ConsoleSseFixtureCorpusTest,ConsoleArtifactFixtureCorpusTest' test`
- [x] Running fixture regeneration twice produces no second-run diff.
- [ ] The integrated Phase 1 test covers authentication, compatibility,
  snapshots, SSE, catalogs, availability, artifact bytes, problems,
  expiration, and execution isolation.
- [x] Every Phase 1 criterion has a named implemented automated test or a
  concrete manual step in the completion-evidence matrix.

#### Manual Verification

- [x] Review fixture directories and confirm no NDJSON body is duplicated
  outside `bifrost-console-fixtures/traces/`.
- [x] Review the evidence matrix against the authoritative Phase 1 criteria and
  confirm no criterion is inferred solely from prose.

---

## Phase 4: Wire the Sample and Operational Documentation

### Overview

Make the completed boundary reproducible from the sample without committing a
secret or weakening host security.

### Changes Required

#### 1. Opt-in sample configuration

**Files**:

- `bifrost-sample/src/main/resources/application.yml`
- `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleApplicationTests.java`

**Changes**:

- Add:

  ```yaml
  bifrost:
    observability:
      enabled: ${BIFROST_OBSERVABILITY_ENABLED:false}
      auth:
        api-key: ${BIFROST_OBSERVABILITY_API_KEY:}
  ```

- Keep observability disabled by default so ordinary sample startup needs no
  secret. Document that enabling requires a printable 32-512 character key.
- Add an opt-in test profile/property set that enables the adapter with a test
  key and verifies instance status plus artifact acquisition after a sample
  execution.

#### 2. Executable host-security pass-through example

**Files**:

- `bifrost-sample/pom.xml`
- New sample `SecurityFilterChain` configuration under
  `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/`
- Sample security/context tests

**Changes**:

- Add the Spring Security web starter explicitly.
- Configure the reserved namespace as host-layer `permitAll` so requests reach
  adapter authentication, while preserving the sample’s existing public
  business-route behavior with an explicit rule.
- Do not import Bifrost internal or auto-configuration types from sample
  production code; use the documented literal reserved namespace.
- Keep CSRF/host rules explicit and scoped. Do not install a second API-key
  filter or expose the Bifrost key to application code.
- Prove a missing Bifrost key still receives
  `BIFROST_API_KEY_REJECTED`, a valid key succeeds, and sample business routes
  retain their intended host behavior.

#### 3. Root and sample operational documentation

**Files**:

- `README.md`
- `bifrost-sample/README.md`
- `bifrost-console-fixtures/README.md`

**Changes**:

- Add the artifact route, request example, exact response headers, eight-slot
  admission behavior, expiration semantics, cancellation behavior, safe
  filename, and unknown/expired `NOT_FOUND` rule.
- State that authenticated raw traces may contain application business data and
  a path already recorded inside canonical diagnostic content; ordinary DTOs
  and lookup never expose or use that path.
- Document opt-in environment variables and a PowerShell/curl flow:
  start sample, invoke a skill, list traces, download by trace ID.
- Explain host/proxy rejection versus Bifrost
  `BIFROST_API_KEY_REJECTED`, ordinary-listener/context-path behavior, TLS
  ownership, no CORS requirement for the server-to-server Go client, and no
  retroactive revocation of a completed authorized transfer.
- Keep configuration metadata unchanged because no new property is added.

### Success Criteria

#### Automated Verification

- [x] Sample context, architecture, security, and opt-in observability tests
  pass: `.\mvnw.cmd -pl bifrost-sample -am test`
- [x] `SupportedApiUsageArchitectureTest` confirms sample production code still
  uses only the supported Bifrost Application API.
- [x] Starter and sample compile together with the explicit security
  dependency: `.\mvnw.cmd -pl bifrost-sample -am verify`
- [x] Documentation examples use the exact implemented route, headers,
  filename, capacity, and environment variable names.

#### Manual Verification

- [ ] Start the sample with an externalized 32+ character key, execute a skill,
  list its trace, and download byte-identical NDJSON using PowerShell and curl.
- [ ] Repeat beneath a servlet context path and confirm all observability
  resources remain relative to that context.
- [ ] Confirm the API key is absent from committed YAML, command arguments,
  URLs, response bodies, trace content introduced by the adapter, and logs.

---

## Testing Strategy

Create the dedicated testing plan with `ai/commands/3_testing_plan.md` before
implementation. It should make the lease-expiry race and async cancellation the
first failing tests, enumerate all terminal cleanup paths, and use the most
specific Phase 1 completion criterion or approved workflow identifier for the
representative end-to-end scenario.

### Unit Tests

- Core lease acquisition versus deadline, reader counts, deferred deletion,
  open/delete failures, multiple readers, close, and shutdown.
- Catalog metadata expiry versus core expiry and active leases.
- Eight-slot artifact admission, non-queued rejection, exact-once release, and
  shutdown.
- Request validation, safe content disposition, exact length, fixed-size copy,
  short read, setup failure, timeout, disconnect, and overlapping callbacks.
- Deterministic SSE/artifact fixture inventory and byte comparison.

### Integration Tests

- Real embedded servlet server for exact bytes and headers.
- Authentication, access operation, context path, host security, fallback,
  method/query/Accept/range/conditional bounds, and stable problems.
- Eight held downloads plus ninth-request rejection and slot reclamation.
- Download across core and catalog expiration, and cancellation mid-body.
- Full Phase 1 lifecycle from live execution through retained artifact.
- Sample opt-in, host pass-through, skill execution, catalog, and download.

### Manual Testing Steps

1. Run the sample with observability enabled and a noncommitted external key.
2. Invoke one successful and one failing sample skill.
3. Authenticate to instance, active/SSE, and trace resources.
4. Download each retained artifact and compare its hash/bytes with the
   core-written file during local development only; do not expose that path as
   a protocol identifier.
5. Throttle and cancel a transfer, then confirm another request is admitted.
6. Wait through a short test completion grace and confirm an already-open
   transfer finishes while new acquisition becomes `NOT_FOUND`.

## Performance Considerations

- File and response memory remain O(buffer size), not O(artifact size).
- At most eight artifact file handles, servlet responses, and virtual download
  tasks are admitted process-wide; SSE retains its independent limit of 16.
- Virtual threads isolate finite blocking file/socket copies without installing
  a host-wide executor or consuming execution locks.
- Per-grace-artifact state remains lifecycle-proportional to files already held
  by the configured core completion grace. It is removed after deletion,
  scheduling rejection, or runtime close.
- No queue, retry loop, digest pass, parse pass, copy, or bandwidth accounting
  is added to the application adapter.
- Exact `Content-Length` uses finalization metadata and the stream verifies it
  does not silently return a short artifact.

## Migration Notes

No user data or configuration migration is required. The route and lease
behavior are additive to the current pre-1.0 observability boundary. Existing
property names, defaults, REST/SSE resources, trace bytes, and problem meanings
remain unchanged.

Internal retention/catalog constructors and interfaces change atomically with
all repository callers. No overload or deprecated bridge is retained. Future Go
code must use the new documented artifact route only after exact
`consoleCompatibilityVersion` match; there is no fallback to filesystem paths,
trace detail JSON, or another URL.

Rollback consists of reverting PR 06 as one coherent boundary change. Do not
roll back only the core lease while leaving the download route, because that
would reintroduce platform-dependent expiration races.

## Resolved Research Decisions

| Research question | Decision |
| --- | --- |
| Download URL | `GET /_bifrost/observability/v1/traces/{traceId}/artifact` |
| Concurrent admission | Fixed process-wide 8, independent of 16 SSE subscriptions |
| Streaming primitive | Direct Servlet async ownership with adapter-owned virtual-thread finite copies; no Spring MVC global task executor |
| Pre-expiry completion | Core-owned exact-file lease; grace deletion waits for last admitted reader |
| Request shape | GET only; no query, range, or conditional headers; absent/wildcard/NDJSON-compatible `Accept` |
| Filename | `bifrost-trace-<opaque-traceId>.ndjson`, emitted through safe `ContentDisposition` encoding |
| Fixture placement | Separate `application-sse/` and `application-artifact/`; artifact body references the single existing `traces/` corpus |
| Completion evidence | New Phase 1 evidence matrix linked from the phase design and roadmap |
| Sample shape | Disabled-by-default env opt-in plus executable Spring Security namespace pass-through that preserves business routes |

## References

- Original ticket:
  `ai/thoughts/tickets/bifrost-console-pr-06-artifact-streaming-integration.md`
- Related research:
  `ai/thoughts/research/2026-07-26-bifrost-console-pr-06-artifact-streaming-integration.md`
- Framework design lens:
  `ai/thoughts/framework-feature-design-lens.md`
- Phase 1 design:
  `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md`
- Implementation roadmap:
  `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md`
- Downstream target consumer:
  `ai/thoughts/tickets/bifrost-console-pr-09-target-context.md`
- Downstream acquisition consumer:
  `ai/thoughts/tickets/bifrost-console-pr-12-artifact-service.md`
- Downstream semantic consumer:
  `ai/thoughts/tickets/bifrost-console-pr-13-trace-analysis-services.md`
- Similar admission/cancellation implementation:
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityActivityDelivery.java:85-107`
- Similar Servlet async lifecycle:
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityActivityStream.java:59-91`
