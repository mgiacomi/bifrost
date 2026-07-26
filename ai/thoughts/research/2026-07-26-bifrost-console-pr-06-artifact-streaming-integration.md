---
date: 2026-07-26T11:07:09-07:00
researcher: mgiacomi
git_commit: a0137b073a0a7fc384f4070c6876daef8cc93776
branch: main
repository: bifrost
topic: "PR 06 finalized artifact streaming and Phase 1 integration"
tags: [research, codebase, bifrost-console, observability, artifact-streaming, ndjson, spring-mvc]
status: complete
last_updated: 2026-07-26
last_updated_by: mgiacomi
---

# Research: PR 06 Finalized Artifact Streaming and Phase 1 Integration

**Date**: 2026-07-26 11:07:09 PDT  
**Researcher**: mgiacomi  
**Git Commit**: `a0137b073a0a7fc384f4070c6876daef8cc93776`  
**Branch**: `main`  
**Repository**: `bifrost`

## Research Question

Research the current codebase for
`ai/thoughts/tickets/bifrost-console-pr-06-artifact-streaming-integration.md`,
using the Phase 1 design and implementation roadmap and relevant future tickets
to document the implementation surface, existing behavior, cross-boundary
contracts, tests, fixtures, configuration, and downstream consumers for PR 06.

## Summary

PRs 01 through 05 are present in the live tree and git history. The current
implementation already produces deterministic finalized NDJSON artifacts,
retains them according to core persistence and completion-grace rules, publishes
bounded metadata into a current-process catalog, exposes secured REST snapshots,
and delivers authenticated SSE. PR 06 is the Phase 1 integration endpoint in the
roadmap: it adds the still-absent artifact byte stream and completes
cross-boundary fixtures, sample wiring, documentation, and end-to-end evidence
before target-facing Phase 2 behavior begins
(`ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:70-106`,
`:197-204`).

The exact artifact already exists. `DefaultExecutionTraceHandle` writes the
canonical records and payload chunks to one NDJSON path and returns a
`FinalizedTraceArtifact` descriptor only after `TRACE_COMPLETED` and the
core-owned retention decision
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:264-319`,
`:393-449`). The current catalog keeps the internal path, size, and effective
expiration but exposes only path-free trace DTOs over HTTP
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/InMemoryFinalizedTraceCatalog.java:58-106`;
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityDtoMapper.java:58-64`).

No artifact-download route, download operation, download admission component,
attachment filename logic, NDJSON response body, or download integration test
exists in the current tree. The registered routes stop at trace list/detail and
SSE (`ObservabilityRouteRegistrar.java:99-113`). The controller returns buffered
`ResponseEntity<byte[]>` JSON for REST and uses direct Servlet async output only
for SSE (`ObservabilityRestController.java:53-245`;
`ObservabilityActivityStream.java:21-80`). The codebase contains no use of
Spring MVC `StreamingResponseBody`, `ResponseBodyEmitter`, or `SseEmitter`.

The central lifecycle fact for PR 06 is that catalog expiration and physical
file expiration are already separate, but neither current interface represents
an in-flight download. Catalog `find` removes or rejects an entry at its
effective expiration, while `ScheduledCompletionGraceRetention` schedules
`Files.deleteIfExists` directly at the core grace deadline
(`InMemoryFinalizedTraceCatalog.java:112-120`, `:177-179`;
`ScheduledCompletionGraceRetention.java:49-113`). Its interface has only
`retainOrDelete(...)` and `close()` (`CompletionGraceRetention.java:8-14`).
Therefore the present implementation has no lease/reference/token through which
a download admitted before expiration can defer the core-owned deletion. This
is the concrete current seam behind the ticket's file-expiry-race focus.

The Java-to-Go semantic fixture corpus is already substantial and reproducible:
10 valid and 8 deliberately invalid NDJSON artifacts, paired with expected
semantic results. Valid traces are created through
`DefaultExecutionTraceHandle`; invalid traces are named mutations. Tests
byte-compare generated and committed output and support explicit regeneration
(`ConsoleTraceFixtureCorpusTest.java:41-97`, `:196-220`, `:479-539`).
The fixture README explicitly assigns PR 06 ownership of streaming the same
corpus and PR 13 ownership of consuming the expected semantics without copying
it (`bifrost-console-fixtures/README.md:1-23`).

The focused existing baseline passed on this checkout: 25 tests, 0 failures,
covering REST, SSE, host-security pass-through, catalog expiry, grace retention,
and both fixture generators.

## Detailed Findings

### 1. Roadmap Position and Phase Boundary

- The dependency chain is `01 -> 02 -> 03 -> 04 -> 05 -> 06`. PR 06 completes
  Phase 1 and is a direct prerequisite of PR 09
  (`ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:70-106`).
- The Phase 1 gate requires executable authentication, compatibility,
  pagination, SSE, artifact streaming, and consumed-trace fixtures before
  target-facing Phase 2 behavior begins
  (`ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:197-204`).
- Phase 1 completion includes loading currently available traces through the
  secured server boundary, correlating live executions to retained traces,
  distinguishing stable application problem codes and core-finalization
  termination, and proving execution isolation
  (`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:435-450`).
- The workflow roadmap places PR 06 on the failed-completed-execution workflow
  together with PRs 01, 03, 12-15, and 18-19
  (`ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:188-195`).

### 2. Canonical Artifact Production

- `FinalizedTraceArtifact` contains opaque trace and session IDs, outcome,
  finalization time, normalized internal path, exact byte size, persistence
  policy, and optional core expiration
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/FinalizedTraceArtifact.java:9-42`).
- `DefaultExecutionTraceHandle` writes one physical record per NDJSON append.
  When a logical payload exceeds 4,096 Java characters, it writes an envelope
  followed by `PAYLOAD_CHUNK_APPENDED` records; live projection receives the
  reconstructed logical record only after all physical writes succeed
  (`DefaultExecutionTraceHandle.java:35-38`, `:371-404`, `:419-451`).
- Finalization appends `TRACE_COMPLETED`, marks the handle complete, and then
  applies `NEVER`, `ONERROR`, or `ALWAYS`. A file otherwise due for deletion is
  offered to completion-grace retention; an empty result means no finalized
  artifact descriptor, while a retained result supplies expiration and size
  (`DefaultExecutionTraceHandle.java:264-303`, `:509-517`).
- The design classifies the downloaded object as the exact finalized UTF-8
  NDJSON file. It explicitly excludes repackaging, reconstructed logical
  records, manifests, archives, digests, completeness markers, or an independent
  version (`bifrost_console_phase_1_observability_foundation.md:261-279`).
- A recorded trace path can remain inside the raw canonical diagnostic content,
  but catalog DTOs and resource lookup use the opaque trace ID
  (`bifrost_console_phase_1_observability_foundation.md:269-271`).

### 3. Core Retention and Expiration

- `ImmediateCompletionRetention` synchronously deletes an otherwise
  non-retained artifact and returns no descriptor
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/ImmediateCompletionRetention.java:14-24`).
- `ScheduledCompletionGraceRetention` calculates `finalizedAt + grace`, obtains
  the file size, and schedules a direct best-effort deletion. A zero grace,
  closed service, or rejected schedule performs synchronous deletion
  (`ScheduledCompletionGraceRetention.java:49-116`).
- Closing grace retention cancels pending deletion tasks and shuts down the
  owned scheduler; it does not delete those pending files during shutdown
  (`ScheduledCompletionGraceRetention.java:119-130`).
- The catalog independently calculates
  `applicationTraceExpiresAt = min(core artifact expiration, catalog metadata
  expiration)` and rejects publication when that effective time is not after
  publication (`InMemoryFinalizedTraceCatalog.java:75-106`).
- Catalog lookup and listing exclude entries at or after the effective
  expiration. A periodic sweep removes metadata, but no catalog action deletes
  the artifact (`InMemoryFinalizedTraceCatalog.java:112-179`).
- Current tests verify delayed deletion, close behavior, effective expiration,
  missing-file rejection, and metadata expiration without artifact deletion
  (`ScheduledCompletionGraceRetentionTest.java:22-109`;
  `InMemoryFinalizedTraceCatalogTest.java:25-132`).
- The current catalog/retention interfaces expose no acquisition-start or
  acquisition-release operation. The scheduled delete task also holds no
  in-flight reader count. This is current implementation state, independent of
  operating-system behavior for deleting an open file.

### 4. Current Spring MVC Boundary

- The web adapter is a servlet-only auto-configuration, conditional on servlet
  web application and required classes. Its infrastructure beans are declared
  with package-private factory methods and `ROLE_INFRASTRUCTURE`
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostObservabilityWebAutoConfiguration.java:35-124`).
- It registers routes programmatically only after opt-in configuration,
  validation, MVC availability, and reserved-namespace collision checks
  (`ObservabilityRouteRegistrar.java:69-121`).
- Existing exact routes are instance, skills list/detail, active list/detail,
  activity SSE, and traces list/detail. A reserved-namespace fallback handles
  all remaining paths. There is no artifact route
  (`ObservabilityRouteRegistrar.java:99-113`, `:170-202`).
- JSON snapshot handlers serialize complete bounded byte arrays into
  `ResponseEntity<byte[]>`. SSE alone owns an async servlet context and writes
  through `ServletOutputStream`/`WriteListener`
  (`ObservabilityRestController.java:53-245`;
  `ObservabilityActivityStream.java:21-80`).
- Spring Boot 3.5.11, Java 21, optional Spring MVC, optional Servlet API, and
  optional Spring Security web support are the current module baseline
  (`pom.xml:45-50`;
  `bifrost-spring-boot-starter/pom.xml:53-87`).
- Existing response/request bounds are REST page size 1-5,000, 16 MiB
  uncompressed JSON pages, a 4,096-character encoded cursor, 16 open SSE
  subscriptions, 256 pending activity frames, 1 MiB pending SSE bytes, and a
  five-second SSE head-frame write-readiness deadline
  (`BoundedJsonPageWriter.java:10-11`, `:19-69`;
  `ObservabilityCursorCodec.java:9-31`;
  `ObservabilityDeliveryLimits.java:7-12`).
- No download concurrency constant or artifact-stream byte/request limit exists
  in the current code.

### 5. Authentication, Headers, and Problems

- The route-scoped filter requires exactly one `X-Bifrost-Api-Key`, validates
  its shape, compares it with `MessageDigest.isEqual`, installs the internal
  operator authentication, and supplies `X-Bifrost-Instance-Id` on
  authenticated responses (`ObservabilityApiKeyFilter.java:49-96`, `:105-145`).
- The same filter applies `Cache-Control: no-store` before dispatch and when it
  writes a problem. It does not expose the instance header for failed Bifrost
  authentication (`ObservabilityApiKeyFilter.java:53-68`, `:133-145`).
- `ObservabilityAccessService` currently names instance, skill, active,
  activity-subscribe, and trace-read operations. There is no distinct download
  operation (`ObservabilityAccessService.java:5-18`).
- Stable problem codes already include `NOT_FOUND`, `LIMIT_EXCEEDED`, and
  `APPLICATION_ERROR`; there is no artifact-expired application problem
  (`ObservabilityProblem.java:3-16`).
- The Phase 1 design says an expired artifact and an unknown trace both become
  `NOT_FOUND` once direct evidence is gone; the adapter keeps no tombstone
  (`bifrost_console_phase_1_observability_foundation.md:343`,
  `:517-531`).
- The settled download metadata is
  `application/x-ndjson; charset=utf-8`, attachment disposition with a filename
  derived from opaque trace ID, known `Content-Length`, no-store caching, and
  the instance identity header
  (`bifrost_console_phase_1_observability_foundation.md:273-275`, `:615-617`).
  None of the download-specific metadata is emitted by current production code
  because the download handler is absent.

### 6. Download Admission and Cancellation Baseline

- SSE provides the existing local admission pattern: an `Admission` object is
  allocated after request validation, the seventeenth open stream is rejected
  with HTTP 429/`LIMIT_EXCEEDED`, and the slot is released exactly once on
  normal close, failure, timeout, cancellation callback, or shutdown
  (`ObservabilityActivityDelivery.java:85-107`, `:281-340`;
  `ObservabilityActivityStream.java:59-91`, `:180-252`).
- SSE admission is tied to live-monitoring availability and its own process-wide
  constant. It does not cover artifact downloads
  (`ObservabilityDeliveryLimits.java:7-12`).
- The Phase 1 contract separately assigns artifact downloads a finite fixed
  process-wide authenticated admission limit. Admission occurs after resource
  lookup and before body commitment; excess requests are not queued
  (`bifrost_console_phase_1_observability_foundation.md:273-275`).
- The current controller's pre-stream error path is centralized through
  `ObservabilityApiKeyFilter` and `ObservabilityProblemMapper`. Once a servlet
  response is committed, the filter rethrows transport failures rather than
  attempting to replace the body with JSON
  (`ObservabilityApiKeyFilter.java:70-99`).

### 7. Cross-Boundary Fixtures

- `bifrost-console-fixtures/traces/` contains 10 valid and 8 invalid artifacts;
  `expected/` contains the future Go-consumed semantic results
  (`bifrost-console-fixtures/README.md:1-5`).
- Valid cases cover single-attempt success, terminal failure and abort, advisor
  retry, nested retry sequences, validation exhaustion, unavailable and
  unattributed usage, recovered nonterminal error, and chunked payload
  (`ConsoleTraceFixtureCorpusTest.java:41-51`).
- Invalid cases cover malformed JSON, inconsistent identity, duplicate
  sequence, incomplete chunks, missing or non-final completion, unsupported
  enum, and contradictory usage reconciliation
  (`ConsoleTraceFixtureCorpusTest.java:52-60`, `:479-530`).
- Fixture generation is deterministic: fixed clock, stable IDs, fixed thread
  name and trace-path metadata, Java writer generation for valid cases, named
  mutations for invalid cases, byte-for-byte comparison, and opt-in
  regeneration (`ConsoleTraceFixtureCorpusTest.java:39-84`, `:207-220`,
  `:479-539`).
- Expected results assert consumed relationships and calculations, including
  attempts, validation links, terminal identity/outcome/failure, attributed
  usage, terminal usage, and component-wise unattributed usage
  (`ConsoleTraceFixtureCorpusTest.java:100-194`, `:400-477`).
- `application-rest/` is a second Java-produced corpus for PR 04 DTO/problem
  bodies. Its generator also byte-compares committed output and uses the same
  regeneration switch
  (`ConsoleRestFixtureCorpusTest.java:28-59`, `:64-119`).
- There is currently no committed application-SSE fixture and no artifact
  transport fixture that proves HTTP response headers plus exact body bytes.
  The fixture README assigns those transport extensions to PR 06
  (`bifrost-console-fixtures/README.md:19-23`).

### 8. Release Version and Compatibility

- Maven filters the complete project version into
  `META-INF/bifrost-release.properties` as
  `consoleCompatibilityVersion=${project.version}`
  (`bifrost-spring-boot-starter/src/main/resources-filtered/META-INF/bifrost-release.properties:1`;
  `bifrost-spring-boot-starter/pom.xml:120-130`).
- `BifrostReleaseVersion.load()` requires exactly one metadata resource and
  exactly one nonblank, resolved declaration
  (`BifrostReleaseVersion.java:11-49`).
- The instance-status handler loads this value once and returns it in the
  status DTO (`ObservabilityRestController.java:35-64`).
- The live artifact, REST, SSE, problem meanings, and Go-consumed NDJSON share
  that exact release-string umbrella. There is no independently reported trace
  schema, artifact, container, engine, or adapter version
  (`bifrost_console_phase_1_observability_foundation.md:75-79`,
  `:549-565`).
- The checked-in fixture status uses `0.1.0-SNAPSHOT`, matching the current root
  Maven version (`pom.xml:8-9`;
  `bifrost-console-fixtures/application-rest/instance-status.json:1`).

### 9. Sample and Documentation State

- Root documentation already describes opt-in properties, externalized API-key
  configuration, no-store and instance headers, existing routes, data exposure,
  context-path behavior, and a host Spring Security `permitAll` pass-through
  example (`README.md:274-326`).
- Spring configuration metadata documents the four current observability
  properties and defaults
  (`bifrost-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json:2-26`).
- The sample currently configures `execution-trace.persistence: ALWAYS` but does
  not enable `bifrost.observability`, reference
  `BIFROST_OBSERVABILITY_API_KEY`, include a production `SecurityFilterChain`,
  or document artifact download
  (`bifrost-sample/src/main/resources/application.yml:64-65`;
  `bifrost-sample/README.md:142-149`).
- The sample depends on `spring-boot-starter-web` and the Bifrost starter; it
  does not directly depend on `spring-boot-starter-security`
  (`bifrost-sample/pom.xml:17-34`).
- The existing host-security behavior is executable in the starter integration
  test, including namespace pass-through while retaining host rules elsewhere
  (`ObservabilityHostSecurityIntegrationTest.java:50-101`).

### 10. Current Verification Topology

- `ObservabilityRestIntegrationTest` verifies authentication, exact release
  identity, no-store and instance headers, route fallback, Accept/query/method
  bounds, baseline metadata, SSE pre-commit failures, trace catalog list/detail,
  and path non-exposure
  (`ObservabilityRestIntegrationTest.java:61-239`).
- `ObservabilitySseIntegrationTest` uses a real embedded servlet server and
  verifies authenticated handshake/replay, response headers, fixed admission,
  slot reclamation, and live-failure closure
  (`ObservabilitySseIntegrationTest.java:59-178`).
- Focused research verification command:

  ```text
  .\mvnw.cmd -pl bifrost-spring-boot-starter '-Dtest=ConsoleTraceFixtureCorpusTest,ConsoleRestFixtureCorpusTest,InMemoryFinalizedTraceCatalogTest,ScheduledCompletionGraceRetentionTest,ObservabilityRestIntegrationTest,ObservabilitySseIntegrationTest,ObservabilityHostSecurityIntegrationTest' test
  ```

  Result: `BUILD SUCCESS`; 25 tests, 0 failures, 0 errors, 0 skipped.
- No current test sends an HTTP artifact download, measures exact streamed bytes
  against a fixture, exercises download admission/cancellation, or holds a
  download across core expiration.

## Contract Classification

The categories below follow
`ai/thoughts/framework-feature-design-lens.md:13-31`.

| Surface | Classification | Technical exposure and behavior | Deliberate-support evidence / protected consumers |
|---|---|---|---|
| Seven `com.lokiscale.bifrost.api` types | Application API | Public declarations used by applications | Root README explicitly closes the supported API to these types (`README.md:142-146`) |
| Bifrost-specific SPIs and bean replacement | No Supported SPI currently declared | Numerous internal public interfaces/classes and Spring infrastructure beans are technically visible | README explicitly says there are no supported Bifrost SPIs or bean overrides (`README.md:142-144`) |
| `bifrost.observability.*` and `execution-trace.persistence` | Configuration and manifest contracts | Bound properties, defaults, validation, and generated metadata | Root documentation, configuration metadata, integration tests, and Phase 1 design establish deliberate configuration behavior |
| REST/SSE/artifact acquisition/problem boundary | Current-release coordinated application-to-console protocol; implementation DTOs remain internal | REST and SSE behavior exists; artifact byte transport does not yet exist | Phase designs, roadmap gate, Java fixtures, and future Go PRs 09/11/12 establish protected Go/browser/MCP consumers |
| Canonical NDJSON trace and fixtures | Ephemeral diagnostic format | Java writer/reader, enums, fixture corpus, expected consumed semantics | Explicit Phase 1 and fixture README classification; PR 13 is the verified future Go semantic consumer |
| `FinalizedTraceArtifact`, catalog, retention, observation, DTO, controller, route, filter, and delivery types | Internal or accidentally exposed implementation | Many are `public` Java declarations under `com.lokiscale.bifrost.internal`; Spring creates infrastructure beans without `@ConditionalOnMissingBean` | Package placement, README supported-surface statement, non-public bean factory methods, and absence of documented replacement points show technical exposure without Supported SPI evidence |
| Exact complete release string | Compatibility marker for the coordinated protocol | Filtered Maven resource loaded by Java and returned in status | Phase 1 explicitly makes it the umbrella for REST, SSE, acquisition, problems, and consumed NDJSON |
| Catalog DTOs and response bodies | Current-release serialized protocol contract, not durable persisted history | Java-produced JSON fixtures and integration tests capture current behavior | Future Go target client consumes the boundary after exact version match; application metadata is process-lifetime-bound |

### Public Declarations, Constructors, and Interfaces

- PRs 01-05 introduced public Java declarations in internal packages, including
  `FinalizedTraceArtifact`, `CompletionGraceRetention`,
  `FinalizedTraceCatalog`, `FinalizedTraceCatalogEntry`,
  `ObservabilityRuntime`, controller/filter/DTO classes, and activity delivery.
- Their public modifiers and constructors establish technical exposure only.
  The live documentation does not classify them as Application API or Supported
  SPI.
- The application-facing API remains the seven explicitly allowlisted
  `com.lokiscale.bifrost.api` types. Sample production code is separately
  checked against depending on internal or auto-configuration packages
  (`bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SupportedApiUsageArchitectureTest.java:11-19`).

### Spring Beans and Extension Points

- `BifrostObservabilityWebAutoConfiguration` creates all adapter collaborators
  as infrastructure beans (`BifrostObservabilityWebAutoConfiguration.java:41-124`).
- No observability bean is guarded by `@ConditionalOnMissingBean`; a repository
  search finds no such annotation in starter production code.
- Route activation constructs the runtime-owned catalog, scheduler, delivery
  service, and observation factory directly rather than retrieving host
  replacements (`ObservabilityRouteRegistrar.java:125-155`).
- Current evidence therefore classifies these beans and constructors as
  internal decomposition, not supported replacement points.

### Serialized Formats and Protocol Consumers

- REST fixtures protect Java-to-future-Go body meanings.
- SSE integration tests protect handshake/event framing and transport behavior,
  but there is not yet a committed SSE fixture corpus.
- Trace fixtures protect the subset of NDJSON semantics that Go PR 13 will
  interpret. Unconsumed metadata remains opaque diagnostic JSON.
- PR 09 consumes status, identity, compatibility, authentication, and problem
  meanings. PR 11 consumes SSE. PR 12 consumes artifact transport into one
  scope-bound immutable Go copy and also supplies unchanged raw pass-through.
  PR 13 consumes the expected semantic fixture results. PRs 18 and 19 later use
  the same trace service through MCP and the debugging skill.
- A meaning change to REST, SSE, acquisition, problems, or consumed NDJSON is
  therefore a coordinated Java/Go/fixture/test/documentation release change
  under the exact shared release string
  (`bifrost_console_phase_1_observability_foundation.md:549-565`).

## Architecture Documentation

### Existing End-to-End Flow

```text
execution
  -> DefaultExecutionTraceHandle
       -> exact physical NDJSON file
       -> core persistence/completion-grace decision
       -> FinalizedTraceArtifact descriptor
  -> DefaultExecutionObservationHandle
       -> InMemoryFinalizedTraceCatalog metadata
       -> enriched terminal activity
  -> Observability REST/SSE adapter
       -> trace list/detail JSON (present)
       -> activity stream (present)
       -> exact artifact byte stream (not present)
  -> future Go console
       -> target/compatibility boundary (PR 09)
       -> acquired immutable copy / raw pass-through (PR 12)
       -> semantic parser and shared calculations (PR 13)
```

The observation handle already guarantees that an `AVAILABLE` terminal activity
is released only after successful catalog publication. It maps catalog
publication failure to unavailable without changing core execution outcome
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java:87-181`).

### Ownership Boundaries

- Core owns trace creation, append, finalization, persistence policy, grace
  retention, and deletion.
- The application observability catalog owns only bounded process-local metadata
  and discoverability.
- The HTTP adapter owns authentication, request validation, response metadata,
  admission, and delivery.
- Future Go owns complete acquired copies, artifact handles, cache retention,
  semantic validation, indexing, and shared calculations.
- Browser and MCP consume Go services rather than Java filesystem paths or
  separate artifact copies.

### Availability Semantics

- A terminal activity reporting `AVAILABLE` already means catalog lookup can
  obtain the entry.
- New lookup is denied at the earlier of catalog and core expiration.
- Metadata expiration does not delete a core-retained file.
- Physical presence after metadata expiry or restart does not make the trace a
  supported resource.
- There are no tombstones, so `NOT_FOUND` does not prove prior existence or
  expiration.
- Once Go has completed an authorized acquisition, later application expiration
  or authentication loss does not retroactively revoke that copy; Go's own
  scope and retention rules apply
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:970-978`).

## Historical Context (from `ai/thoughts/`)

- `ai/thoughts/tickets/bifrost-console-pr-01-canonical-trace-semantics.md`:
  established retry, attempt, usage, outcome, and failure relationships and the
  Java-produced semantic fixture corpus.
- `ai/thoughts/tickets/bifrost-console-pr-02-observation-lifecycle.md`:
  established post-append projection, bounded live state, and execution
  isolation.
- `ai/thoughts/tickets/bifrost-console-pr-03-observability-catalogs.md`:
  established the core-issued descriptor, current-process-only trace catalog,
  metadata TTL, core ownership, and completion grace.
- `ai/thoughts/tickets/bifrost-console-pr-04-spring-rest-adapter.md`:
  established opt-in activation, authentication, compatibility, stable
  problems, pagination, context paths, and host-security pass-through.
- `ai/thoughts/tickets/bifrost-console-pr-05-live-sse-delivery.md`:
  established bounded multiplexed streaming, fixed admission, write deadlines,
  cancellation, and replay behavior.
- `ai/thoughts/tickets/bifrost-console-pr-09-target-context.md`: first direct
  downstream protocol consumer; it owns exact compatibility matching, target
  identity, credentials, scope rotation, cancellation, and application problem
  mapping.
- `ai/thoughts/tickets/bifrost-console-pr-12-artifact-service.md`: downstream
  artifact acquisition and unchanged raw pass-through consumer. It separates
  installed analysis copies from raw downloads and does not expose paths.
- `ai/thoughts/tickets/bifrost-console-pr-13-trace-analysis-services.md`:
  semantic consumer of the PR 01/06 corpus; it validates and indexes the exact
  acquired NDJSON and produces shared browser/MCP calculations.
- `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md`:
  authoritative settled semantics for exact artifact bytes, HTTP metadata,
  admission, expiration, acquisition-time authorization, compatibility, and
  Phase 1 completion.

Git history confirms the implementation sequence:
`98799ce`/`1d2810e` (PR 01), `70d9af2`/`a35b9c5` (PR 02),
`189d59e`/`27bb14e` (PR 03), `b3c9f6a`/`97feaf9` (PR 04), and
`95b53d4`/`a0137b0` (PR 05).

## Related Research

No prior documents were present in `ai/thoughts/research/` on this checkout.

## Open Questions

These are facts not determined by the present implementation or ticket brief
and remain detailed-planning inputs:

1. The exact artifact-download URL shape is not named in the Phase 1 design,
   ticket brief, `ObservabilityApiPaths`, or current route registrar.
2. The initial fixed concurrent-download admission constant is intentionally
   left to implementation planning. Only the independent SSE value of 16 exists
   today.
3. The concrete streaming primitive and executor/thread ownership for finite
   artifact responses are not selected. Current finite REST uses buffered byte
   arrays; current long-lived SSE uses direct Servlet async nonblocking I/O.
4. The core-owned representation for a download admitted before core expiration
   is not present. Current completion retention has no acquisition/lease method,
   and its scheduled task deletes the exact path when due.
5. The request-shape rules specific to artifact download are not stated beyond
   authenticated GET, finite fixed admission, resource lookup, safe headers,
   cancellation, and existing namespace behavior.
6. The safe filename's exact suffix and escaping convention are not stated
   beyond derivation from the opaque trace ID rather than the filesystem path.
7. The exact transport-fixture filenames and whether SSE fixture generation
   shares or separates the REST corpus generator are not yet represented in the
   fixture tree.
8. The Phase 1 completion-evidence matrix mapping each criterion to automated
   or manual evidence does not yet exist in the repository.
9. The sample's final opt-in and host-security shape is not present. The current
   sample lacks both observability settings and a Spring Security dependency,
   while the root documentation and starter integration test already contain
   the general host-security example.

