---
researcher: mgiacomi
git_commit: cd8c06d206ca95e9adc7ef7129242f46b5110f2d
branch: main
repository: bifrost
date: 2026-07-29
topic: "PR-12: Central artifact acquisition and trace storage in the Bifrost Console"
tags: [research, artifact-service, trace-storage, pr-12, observability, bifrost-console]
status: completed
last_updated: 2026-07-29
last_updated_by: GLM-5.2
last_updated_note: "Independent verification pass: corrected trace-workspace config claim, documented applicationTraceAvailability vs artifactAvailability wire/fixture discrepancy, added config/errors/workspace detail, expanded file index."
---

# Research: PR-12 — Central Artifact Acquisition and Trace Storage

## Research question

What exists in the Bifrost codebase today for the PR-12 "Central artifact acquisition and trace storage" scope, and what foundational or missing pieces must be understood before implementing that ticket?

Sub-questions covered:

- How are trace artifacts produced, finalized, retained, cataloged, and streamed on the Java side?
- How does the Go console currently list and detail traces, and where does it stop short of acquisition/storage?
- What configuration, limits, and error codes already exist?
- What PR-12 concepts (pinning, eviction, capacity, raw pass-through, joining acquisitions) are absent?
- What historical design context (PR-06, Phase 1, Phase 2, design lens) constrains or informs the work?

## Executive summary

The Java `bifrost-spring-boot-starter` module already produces, catalogs, and streams finalized NDJSON trace artifacts (largely delivered by PR-06). The flow is:

1. `DefaultExecutionTraceHandle` writes and finalizes an NDJSON trace file.
2. A `CompletionGraceRetention` implementation decides whether to retain or delete the file.
3. An `InMemoryFinalizedTraceCatalog` holds current-process metadata.
4. `ObservabilityRestController.artifact` streams the file via `ObservabilityArtifactDelivery` and `ObservabilityArtifactStream`.

The Go `bifrost-console` can list and detail traces but currently does **not**:

- acquire or download trace artifacts from the Java adapter,
- cache, pin, evict, or account for artifact capacity,
- expose an artifact download or raw pass-through browser route, or
- define artifact handles or a central artifact service.

So the PR-12 task is essentially building the Go-side central artifact service (and any supporting Java raw-pass-through endpoint) on top of the existing Java streaming foundation.

## 1. Java artifact lifecycle and streaming (existing)

### 1.1 Trace file creation and persistence policy

`DefaultExecutionTraceHandle` is the canonical trace writer. It appends `TraceRecord` lines to an NDJSON file and finalizes them with a `TRACE_COMPLETED` record.

- `DefaultExecutionTraceHandle.finalizeTrace(...)` computes the trace outcome, writes the final record, and applies `TracePersistencePolicy` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:264-303`).
- `shouldDeleteAfterCompletion(...)` returns `true` for `NEVER` and for successful `ONERROR` traces, `false` for `ALWAYS` and errored `ONERROR` traces (`DefaultExecutionTraceHandle.java:509-517`).
- The default trace path is `${java.io.tmpdir}/<sessionId>.<traceId>.execution-trace.ndjson` (`DefaultExecutionTraceHandle.java:529-532`).
- `TracePersistencePolicy` is the enum `NEVER`, `ONERROR`, `ALWAYS` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/TracePersistencePolicy.java:1-8`).
- `ExecutionTraceProperties` defaults the policy to `ONERROR` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/ExecutionTraceProperties.java:1-25`).
- `BifrostSessionRunner` carries the policy into `BifrostSession` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSessionRunner.java:19-24, 81-82`).

### 1.2 Completion grace retention

A `CompletionGraceRetention` abstraction owns the file after core finalization.

- Interface: `retainOrDelete(...)` returns an optional `RetainedArtifact` with `expiresAt` and `sizeBytes`; `acquire(...)` returns an `ArtifactLease` exposing an `InputStream` and `sizeBytes` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/CompletionGraceRetention.java:1-45`).
- `ScheduledCompletionGraceRetention` holds retained files in a map, schedules deletion after `completionGraceTtl`, reference-counts readers, and deletes when the last reader releases after expiration (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/ScheduledCompletionGraceRetention.java:1-347`).
- `ImmediateCompletionRetention` deletes immediately and cannot be acquired; it is used as the no-op retention when observability is not wired (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/ImmediateCompletionRetention.java:1-37`).

### 1.3 Finalized trace catalog

The current-process trace catalog is in-memory and TTL-bound.

- `FinalizedTraceCatalog` exposes `publish`, `find`, `acquire`, `list`, `catalogedTraceCount`, and close (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/FinalizedTraceCatalog.java:1-46`).
- `InMemoryFinalizedTraceCatalog` stores entries in a `ConcurrentHashMap`, assigns monotonic `catalogOrdinal` values, sweeps expired entries periodically, and computes an `effectiveExpiresAt` as the earlier of artifact expiration and catalog metadata TTL (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/InMemoryFinalizedTraceCatalog.java:1-268`, with `publish` at lines 66-117, `acquire` at 136-155, and `sweep` at 58-62).
- `FinalizedTraceCatalogEntry` record carries the `FinalizedTraceArtifact`, expiration, ordinals, and outcome (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/FinalizedTraceCatalogEntry.java:1-48`).
- `FinalizedTraceArtifact` record (in `internal.core`) is the descriptor published from the trace handle: trace/session IDs, outcome, finalized timestamp, artifact `Path`, size, persistence policy, and optional artifact expiration (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/FinalizedTraceArtifact.java:1-47`).

### 1.4 Publishing the artifact into the catalog

The observation handle publishes the descriptor after core finalization succeeds.

- `DefaultExecutionObservationHandle.close(...)` calls `publishSuccessfulTerminal(...)` when the disposition is `CORE_FINALIZATION_SUCCEEDED` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java:97-138`).
- `publishSuccessfulTerminal(...)` calls `traceCatalog.publish(...)` when a `FinalizedTraceArtifact` is present, then enriches the `TRACE_COMPLETED` activity with `applicationTraceAvailability` (`DefaultExecutionObservationHandle.java:140-174`).
- `LiveActivityProjector` projects `TRACE_COMPLETED` by reading `outcome` and `sessionUsageSnapshot` from the record metadata (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/LiveActivityProjector.java:104-112`).
- `ExecutionActivity.withTraceAvailability(...)` adds `applicationTraceAvailability`, `applicationTraceUnavailableReason`, and `applicationTraceExpiresAt` to the activity details (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionActivity.java:104-136`).

### 1.5 Artifact streaming REST endpoint

The Java adapter exposes a single artifact stream endpoint.

- `ObservabilityApiPaths` defines `/_bifrost/observability/v1/traces/{traceId}/artifact` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiPaths.java:1-15`).
- `ObservabilityRestController.artifact(...)` handles GET requests: validates the Accept header is compatible with `application/x-ndjson`, rejects conditional headers and query strings, looks up the catalog, obtains an admission slot, acquires the artifact lease, and opens the stream (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestController.java:251-290` and `380-424`).
- `ObservabilityArtifactDelivery` controls concurrency and virtual-thread streaming. It admits up to `ObservabilityDeliveryLimits.OPEN_ARTIFACT_DOWNLOADS` (8) simultaneous downloads and uses a 5-minute timeout (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityArtifactDelivery.java:1-159`; limits at `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityDeliveryLimits.java:1-17`).
- `ObservabilityArtifactStream` performs the actual async copy, ensures the exact number of bytes is written, handles timeout/error/complete lifecycles, and closes the response (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityArtifactStream.java:1-197`).
- Authentication uses `ObservabilityApiKeyFilter` (GET only, `X-Bifrost-Api-Key` header, constant-time comparison) and `ObservabilityAccessService` with `Operation.TRACE_ARTIFACT_READ` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiKeyFilter.java:24-68` and `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityAccessService.java:1-23`).

### 1.6 DTOs, runtime, and auto-wiring

- `ObservabilityDtos.Trace` and `ObservabilityDtoMapper.trace(...)` expose catalog metadata to the Go console: trace/session IDs, outcome, finalized timestamp, size, persistence policy, and application trace expiration (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/dto/ObservabilityDtos.java:66-73` and `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityDtoMapper.java:55-60`).
- `ObservabilityRuntime` holds the `artifactDelivery`, `completionRetention`, and `traces` catalog (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/ObservabilityRuntime.java:1-125`).
- `ObservabilityRouteRegistrar.createRuntime(...)` constructs `ScheduledCompletionGraceRetention`, `InMemoryFinalizedTraceCatalog`, `ObservabilityArtifactDelivery`, and the `DefaultExecutionObservationHandleFactory`; it registers the trace and artifact routes (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRouteRegistrar.java:128-162`).
- `BifrostAutoConfiguration` wires `BifrostSessionRunner` with the observation factory and the active coordinator's `completionRetention` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:170-182`).

### 1.7 Tests and fixtures

- `ObservabilityArtifactIntegrationTest` validates byte-exact streaming, 404/400 problem responses, and a representative fixture corpus (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityArtifactIntegrationTest.java:1-150`).
- `ObservabilityArtifactDeliveryTest` verifies the 8-download concurrency cap, shutdown behavior, executor rejection handling, and async setup failure cleanup (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityArtifactDeliveryTest.java:1-109`).

## 2. Go console side (existing)

### 2.1 Browser-facing routes

The Go `browserapi.Router` currently only accepts POST and only exposes JSON list/detail endpoints for traces.

- `router.go` only accepts `http.MethodPost` and routes `/api/console/v1/traces/list` and `/api/console/v1/traces/detail` to `tracesList` and `traceDetail` (`bifrost-console/internal/browserapi/router.go:51-54, 87-90`).
- `observability.go` implements those two JSON handlers by calling `observability.Service.ListTraces` and `GetTrace` (`bifrost-console/internal/browserapi/observability.go:143-195`).
- There is **no** artifact download, raw pass-through, or pin/remove route.

### 2.2 Go observability service

`observability.Service` proxies JSON observability requests to the selected target.

- `ListTraces` and `GetTrace` call `scope.Upstream(...)` against the Java endpoints and validate the response (`bifrost-console/internal/observability/service.go:151-202`).
- Constants include `traceDetailMaxBytes = 1 * 1024 * 1024` but there is **no** artifact-specific size/buffer/timeout handling (`service.go:15-23`).

### 2.3 Target client addresses

`applicationclient.Address` builds the Java observability URLs.

- It has `TracesEndpoint()` and `TraceEndpoint(traceId)` but **no** `ArtifactEndpoint(traceId)` or `RawAttachmentEndpoint(...)` (`bifrost-console/internal/applicationclient/address.go:146-149`).

### 2.4 DTOs and web contracts

- The Go `Trace` DTO contains metadata but no artifact handle or download availability flag (`bifrost-console/internal/observability/dto.go:78-87`).
- `web/src/api/contracts.ts` defines a `Trace` type with the same fields and a `BrowserErrorCode` set that already includes `ARTIFACT_EXPIRED` and `INVALID_ARTIFACT` (`bifrost-console/web/src/api/contracts.ts:1-30, 156-165`).
- `web/src/api/client.ts` exposes `listTraces` and `getTraceDetail` but no artifact download (`bifrost-console/web/src/api/client.ts:148-157`).
- `web/src/observability/Traces.tsx` lists traces and links to trace detail; `TraceDetail.tsx` shows metadata only (`bifrost-console/web/src/observability/Traces.tsx:1-90`; `TraceDetail.tsx:1-78`).
- `web/src/observability/ActiveExecutionDetail.tsx` reads `terminalActivity.details?.artifactAvailability` (the fixture/Go wire field) to decide whether to show an "Inspect trace" link (`bifrost-console/web/src/observability/ActiveExecutionDetail.tsx:44-49, 141-147`). It also reads `artifactAvailability === "CORE_FINALIZATION_FAILED"` on `EXECUTION_OBSERVATION_ENDED` to flag finalization failure. See §9 for the discrepancy between this fixture field name and the live Java runtime's `applicationTraceAvailability`.

### 2.5 Local workspace and storage safety

The console already owns a protected local work directory intended for transient artifacts.

- `workspace.Open(...)` creates or verifies a directory containing `.bifrost-console-work` marker, `.lock`, and a `transient/` child. It validates ownership, symlinks/reparse points, and permissions (`bifrost-console/internal/workspace/workspace.go:1-213`).
- `cleanup.go` removes everything under `transient/` on startup and on explicit `Cleanup()` (`bifrost-console/internal/workspace/cleanup.go:1-89`).
- `artifact_failure.go` classifies an artifact error as fatal if cleanup or workspace probe fails, otherwise returns the original scoped error (`bifrost-console/internal/workspace/artifact_failure.go:1-39` and `artifact_failure_test.go:1-39`).
- This is the intended location for Go-acquired trace copies, but no cache policy, handle, eviction, or pinning implementation exists yet.

## 3. Configuration and limits

- `BifrostProperties.Observability` contains `enabled`, `auth.apiKey`, `completionGraceTtl` (default `15m`), and `traceCatalogMetadataTtl` (default `24h`); `ObservabilityRouteRegistrar.validate(...)` rejects negative, zero, or too-large TTLs (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostProperties.java:328-365`; `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRouteRegistrar.java:214-245`).
- `BifrostProperties.Session.Attachments.maxSize` defaults to 20MB for skill input attachments (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostProperties.java:248-295`).
- `BifrostProperties.Session.Quotas` defines execution invocation limits surfaced in active-execution DTOs (`BifrostProperties.java:222-246`).
- Java artifact download limits: 8 concurrent downloads and a 5-minute stream timeout (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityDeliveryLimits.java:1-17`).
- Go console JSON body limit for observability routes is 4KB (`bifrost-console/internal/browserapi/observability.go:11`).

## 4. PR-12 ticket scope vs. current state

The PR-12 ticket (`ai/thoughts/tickets/bifrost-console-pr-12-artifact-service.md`) calls for:

- a central artifact service for scope-bound trace artifact acquisition and trace storage,
- one immutable installed copy per trace,
- joining simultaneous acquisitions for the same trace,
- artifact handles, metadata, capacity and TTL management,
- pinning and eviction,
- a separate unchanged raw-download pass-through,
- UI to pin, remove, and inspect,
- domain error codes and validation.

Current state mapping:

| PR-12 concept | Current existence |
|---|---|
| Finalized NDJSON trace artifact | Java `DefaultExecutionTraceHandle` writes it; PR-06 streaming is in place. |
| Artifact streaming endpoint | Java `/_bifrost/observability/v1/traces/{traceId}/artifact` exists. |
| Current-process trace catalog | Java `InMemoryFinalizedTraceCatalog` exists; TTL-based, no capacity or pin. |
| File retention with TTL | Java `ScheduledCompletionGraceRetention` exists. |
| Go central artifact service | **Missing**. Only JSON list/detail are proxied. |
| Go artifact cache with capacity / TTL / idle eviction | **Missing**. Workspace cleanup is the only storage management. |
| Artifact handles | **Missing**. No handle abstraction in Go or DTO. |
| Pinning | **Missing** on both sides. |
| Joining simultaneous acquisitions | **Missing**. Java `ObservabilityArtifactDelivery` serializes downloads but does not coalesce multiple requests for the same trace into one file copy. |
| Raw attachment pass-through | **Missing**. The Java endpoint only serves finalized NDJSON trace artifacts; there is no raw attachment pass-through. |
| UI pin / remove / download | **Missing**. Web only lists traces and links to detail. |
| `ARTIFACT_EXPIRED`, `INVALID_ARTIFACT` errors | Codes exist in `consolecore/errors.go` and `contracts.ts` but no acquisition logic produces them. |

## 5. Data flow (as-is)

1. `BifrostSessionRunner` starts a `BifrostSession` with a `TracePersistencePolicy`.
2. `DefaultExecutionTraceHandle` appends records to an NDJSON file in `java.io.tmpdir`.
3. At session completion, `BifrostSessionRunner.finalizeSessionTrace(...)` calls `BifrostSession.finalizeTrace(...)`.
4. `DefaultExecutionTraceHandle.finalizeTrace(...)` writes the `TRACE_COMPLETED` record and applies the retention policy.
5. For retained traces, `ScheduledCompletionGraceRetention.retainOrDelete(...)` returns a `RetainedArtifact` with `expiresAt` and `sizeBytes`.
6. `DefaultExecutionObservationHandle.close(...)` publishes a `FinalizedTraceArtifact` to `InMemoryFinalizedTraceCatalog`.
7. The catalog stores metadata and computes `effectiveExpiresAt`.
8. The browser/Go lists or details traces via `ObservabilityRestController.traces` and `trace`.
9. To download, an authenticated client calls `ObservabilityRestController.artifact(...)`, which admits the request, acquires an `ArtifactLease` from the catalog, and streams the bytes via `ObservabilityArtifactStream`.
10. The Go console currently terminates at step 8 for trace metadata; it does not perform steps 9-10 on behalf of the browser.

## 6. Historical and design context

- `ai/commands/1_research_codebase.md` establishes the research methodology: document the existing system, no recommendations.
- `ai/thoughts/tickets/bifrost-console-pr-12-artifact-service.md` is the PR-12 prompt; it explicitly separates the central artifact service from the unchanged raw pass-through and from UI/MCP consumers.
- `ai/thoughts/tickets/bifrost-console-pr-06-artifact-streaming-integration.md` describes the already-delivered Java streaming endpoint, fixture corpus, and integration tests.
- `ai/thoughts/tickets/bifrost-console-pr-11-live-execution-experience.md` added the trace-availability terminal-activity signal and the active-execution "Inspect trace" link. The protocol field name is **not** uniform across the codebase: the committed SSE fixtures and all Go/React consumers use `artifactAvailability` (see §9 below), while the live Java runtime writes `applicationTraceAvailability` via `ExecutionActivity.withTraceAvailability` and `DefaultExecutionObservationHandle.exceptionalTerminal`.
- `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md` defines the current-process-only trace catalog, the immutable finalized NDJSON artifact, `consoleCompatibilityVersion`, and the rule that the server—not the browser—owns trace format knowledge.
- `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md` assigns Go ownership of a scope-bound transient trace cache with `trace-workspace.max-bytes` and `trace-workspace.idle-ttl` (including `unlimited` and `never` values), parsing, indexing, and proxying trace downloads.
- `ai/thoughts/framework-feature-design-lens.md` requires explicit contract classification, data flow, failure visibility, evidence requirements, and production boundaries; it warns against exposing internal Java types as the protocol.
- `ai/thoughts/tickets/bifrost-console-pr-13-trace-analysis-services.md` is the downstream consumer of PR-12: it turns an acquired current-release artifact into validated, bounded, transport-neutral evidence queries shared by browser and MCP. Its "depends on PR-12" relationship means the handle/acquisition contract PR-12 establishes is the input to PR-13.
- `ai/thoughts/phases/bifrost_console_phase_3_llm_runtime_inspector.md` and `ai/thoughts/phases/bifrost_console_workflows.md` both reference `applicationTraceAvailability` and describe the live-to-failure flow that terminates in trace acquisition through the centralized artifact service, confirming the field name used in the design docs matches the live Java runtime rather than the committed fixtures.
- `ai/thoughts/phases/bifrost_console_phase_1_completion_evidence.md` records Phase 1 completion evidence including `ConsoleArtifactFixtureCorpusTest` and `ObservabilityArtifactIntegrationTest`.

## 7. Gaps and open questions for implementation planning

The following are observed missing pieces, not recommendations:

- **Go artifact acquisition endpoint**: no browser route, `applicationclient` URL, or `observability.Service` method exists for downloading a trace artifact.
- **Go artifact cache model**: no capacity accounting, `max-bytes`/`idle-ttl` policy, pin flag, or eviction algorithm is implemented; the `workspace` package only enforces safety and cleanup.
- **Artifact handle / metadata abstraction**: no Go type represents an acquired, pinned, or evictable artifact handle.
- **Simultaneous acquisition joining**: Java `ObservabilityArtifactDelivery` limits concurrency but does not join overlapping downloads for the same trace into a single stored copy.
- **Raw pass-through endpoint**: nothing in Java or Go serves raw attachments separately from the finalized NDJSON trace artifact.
- **UI for pin/remove/download**: `Traces.tsx` and `TraceDetail.tsx` only show metadata; no artifact action UI.
- **MCP surface**: not yet present; Phase 3 expects trace analysis to be transport-neutral below browser/MCP adapters.
- **Configuration surface**: `bifrost-console` local YAML with `trace-workspace.max-bytes` and `trace-workspace.idle-ttl` **is already present in code** (see §10 below). The `File.TraceWorkspace` struct, `Default()` values (`4GiB`/`4h`), `parseBytes` (with `unlimited`), and `parseDuration` (with `never`) are implemented in `internal/config/`, and the resolved `MaxBytes`/`Unlimited`/`IdleTTL`/`NeverExpire` are carried in `config.Resolved` and surfaced via `profile.Resolved`. What is missing is any **consumer**: no artifact service reads these resolved values yet, so the capacity/TTL policy is parsed and validated but not enforced.

## 9. Wire-format field name discrepancy (verified)

While verifying the PR-11 trace-availability signal, an inconsistency was found between the live Java runtime and the committed wire-format fixtures / Go + React consumers. This is an observation of the current state, not a recommendation.

**Live Java runtime emits `applicationTraceAvailability`:**
- `ExecutionActivity.withTraceAvailability(...)` puts `applicationTraceAvailability`, `applicationTraceUnavailableReason`, and `applicationTraceExpiresAt` into the activity details (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionActivity.java:110-117`).
- `DefaultExecutionObservationHandle.publishSuccessfulTerminal(...)` calls `withTraceAvailability("AVAILABLE"|"UNAVAILABLE", ...)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java:151, 158-159, 168-169`).
- `DefaultExecutionObservationHandle.exceptionalTerminal(...)` also writes `applicationTraceAvailability` and `applicationTraceUnavailableReason` directly (`DefaultExecutionObservationHandle.java:180-181`).
- `DefaultExecutionObservationHandleTest` asserts `applicationTraceAvailability` (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandleTest.java:127, 177`).

**Committed SSE fixtures and all Go/React consumers use `artifactAvailability`:**
- `bifrost-console-fixtures/application-sse/activity-trace-completed.sse:3` — `"details":{"artifactAvailability":"AVAILABLE"}`.
- `bifrost-console-fixtures/application-sse/activity-core-finalization-failed.sse:3` — `"details":{"artifactAvailability":"CORE_FINALIZATION_FAILED"}`.
- `ConsoleSseFixtureCorpusTest` generates the committed fixtures using `Map.of("artifactAvailability", "AVAILABLE")` and `"CORE_FINALIZATION_FAILED"` (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ConsoleSseFixtureCorpusTest.java:34, 38`).
- Go integration/e2e fixtures: `internal/console/activity_integration_test.go:23`, `internal/live/service_test.go:329`, `web/e2e/live-executions.spec.ts:236`, `web/e2e/activity-stream.spec.ts:40`.
- React consumers: `web/src/observability/ActiveExecutionDetail.tsx:46, 49`, `web/src/activity/activityPresentation.ts:44`, plus matching tests `ActiveExecutionDetail.test.tsx:218`, `reducer.test.ts:164`, `activityPresentation.test.ts:84, 89`.

**Implication for PR-12:** the trace-availability signal that PR-12's "application availability vs. local-handle availability" guardrail depends on is currently emitted under two different field names depending on whether the source is the live Java runtime (`applicationTraceAvailability`) or the fixture/Go-consumed contract (`artifactAvailability`). The `applicationTraceExpiresAt` field (used by the Go `Trace` DTO and `contracts.ts`) is consistently named on both sides. This discrepancy is observed state; classifying which name is the supported Application API contract is out of scope for this research pass.

## 10. Go console `trace-workspace` configuration (already present)

The Phase 2 design describes a Go-local `trace-workspace.max-bytes` and `trace-workspace.idle-ttl` policy with `unlimited` and `never` sentinels. This configuration is **already implemented and validated** in the Go console, although no artifact service consumes it yet.

- `bifrost-console/internal/config/config.go:5-13` defines `DefaultMaxBytes = 4 * 1024 * 1024 * 1024` (4 GiB) and `DefaultIdleTTL = 4 * time.Hour`.
- `config.go:18` adds `TraceWorkspace TraceWorkspace` to the `File` struct; `config.go:26-29` defines `TraceWorkspace{ MaxBytes string, IdleTTL string }` with `yaml:"max-bytes"` and `yaml:"idle-ttl"`.
- `config.go:39-46` defines `Resolved{ MaxBytes int64, Unlimited bool, IdleTTL time.Duration, NeverExpire bool }`.
- `config.go:57-66` `Default()` returns `TraceWorkspace{MaxBytes: "4GiB", IdleTTL: "4h"}`; `config.go:68-74` `DefaultYAML` emits the same.
- `bifrost-console/internal/config/values.go:29-36` resolves both fields: `parseBytes` (line 169) accepts `unlimited` → `Unlimited=true`, or a positive integer with `KiB`/`MiB`/`GiB`/`TiB` suffix; `parseDuration` (line 195) accepts `never` → `NeverExpire=true`, or a positive canonical duration using `s`/`m`/`h`. Numeric zero is rejected for both.
- `bifrost-console/internal/config/decode.go` includes `trace-workspace` in the decoded schema.
- `bifrost-console/internal/profile/profile.go:18` stores `Resolved config.Resolved` on the console profile, making the resolved policy available to the rest of the console.
- `bifrost-console/internal/config/config_test.go` exercises defaults and the `unlimited`/`never` sentinels.

So the parsing, validation, defaults, and profile wiring for the PR-12 capacity/TTL policy already exist. The missing piece is the artifact service that reads `profile.Resolved.MaxBytes`/`Unlimited`/`IdleTTL`/`NeverExpire` to admit, evict, and expire acquired copies.

## 11. Additional Go error-code detail (verified)

`bifrost-console/internal/consolecore/errors.go` defines the full `Code` set and a `Details` struct. The artifact-relevant pieces:

- `CodeArtifactExpired = "ARTIFACT_EXPIRED"` (`errors.go:17`) and `CodeInvalidArtifact = "INVALID_ARTIFACT"` (`errors.go:18`) — both already declared, matching `contracts.ts:19-20`. No code path produces them yet.
- `Details.RawDownloadAvailable *bool` (`errors.go:32`, JSON `rawDownloadAvailable,omitempty`) — a pre-existing detail slot suitable for the PR-12 "raw pass-through remains downloadable even when analysis acquisition fails" guardrail. It is not yet populated by any handler.
- `Details.LimitName`/`LimitValue` (`errors.go:30-31`) exist and are the natural carriers for a future `LIMIT_EXCEEDED` capacity rejection, which `contracts.ts:6` already declares.

## 8. Key file index

### Java artifact/catalog/retention

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/TracePersistencePolicy.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/FinalizedTraceArtifact.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/CompletionGraceRetention.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/ScheduledCompletionGraceRetention.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/ImmediateCompletionRetention.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/FinalizedTraceCatalog.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/InMemoryFinalizedTraceCatalog.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/FinalizedTraceCatalogEntry.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionActivity.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/LiveActivityProjector.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/TraceCatalogSlice.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/NdjsonTraceRecordWriter.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/TraceRecordWriter.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/attachment/BifrostAttachment.java`

### Java web/delivery

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/ObservabilityRuntime.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/ObservabilityActivationCoordinator.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRouteRegistrar.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestController.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityArtifactDelivery.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityArtifactStream.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiPaths.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiKeyFilter.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityAccessService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityDtoMapper.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/dto/ObservabilityDtos.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityDeliveryLimits.java`

### Java configuration

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostProperties.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/ExecutionTraceProperties.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSessionRunner.java`

### Go console

- `bifrost-console/internal/browserapi/router.go`
- `bifrost-console/internal/browserapi/observability.go`
- `bifrost-console/internal/observability/service.go`
- `bifrost-console/internal/observability/dto.go`
- `bifrost-console/internal/applicationclient/address.go`
- `bifrost-console/internal/consolecore/errors.go`
- `bifrost-console/internal/workspace/workspace.go`
- `bifrost-console/internal/workspace/cleanup.go`
- `bifrost-console/internal/workspace/artifact_failure.go`
- `bifrost-console/internal/workspace/artifact_failure_test.go`
- `bifrost-console/internal/config/config.go`
- `bifrost-console/internal/config/values.go`
- `bifrost-console/internal/config/decode.go`
- `bifrost-console/internal/config/config_test.go`
- `bifrost-console/internal/profile/profile.go`
- `bifrost-console/web/src/api/client.ts`
- `bifrost-console/web/src/api/contracts.ts`
- `bifrost-console/web/src/observability/Traces.tsx`
- `bifrost-console/web/src/observability/TraceDetail.tsx`
- `bifrost-console/web/src/observability/ActiveExecutionDetail.tsx`
- `bifrost-console/web/src/activity/activityPresentation.ts`

### Shared fixtures and cross-boundary tests

- `bifrost-console-fixtures/application-sse/activity-trace-completed.sse`
- `bifrost-console-fixtures/application-sse/activity-core-finalization-failed.sse`
- `bifrost-console-fixtures/application-sse/handshake.sse`
- `bifrost-console-fixtures/application-sse/replay.sse`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ConsoleSseFixtureCorpusTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandleTest.java`
- `bifrost-console/internal/console/activity_integration_test.go`
- `bifrost-console/internal/live/service_test.go`
- `bifrost-console/web/e2e/live-executions.spec.ts`
- `bifrost-console/web/e2e/activity-stream.spec.ts`

### Design and ticket context

- `ai/commands/1_research_codebase.md`
- `ai/thoughts/tickets/bifrost-console-pr-12-artifact-service.md`
- `ai/thoughts/tickets/bifrost-console-pr-06-artifact-streaming-integration.md`
- `ai/thoughts/tickets/bifrost-console-pr-11-live-execution-experience.md`
- `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md`
- `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md`
- `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md`
- `ai/thoughts/phases/bifrost_console_phase_3_llm_runtime_inspector.md`
- `ai/thoughts/phases/bifrost_console_workflows.md`
- `ai/thoughts/phases/bifrost_console_phase_1_completion_evidence.md`
- `ai/thoughts/tickets/bifrost-console-pr-13-trace-analysis-services.md`
- `ai/thoughts/framework-feature-design-lens.md`
