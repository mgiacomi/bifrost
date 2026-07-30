---
date: 2026-07-28T08:32:52-07:00
researcher: Cascade
git_commit: 348ab1a35c4a8b5a950c823ce48023cb78aae47a
branch: main
repository: bifrost
topic: "PR 11 — Live Activity and Active-Execution Detail"
tags: [research, codebase, bifrost-console, live-execution, sse, active-execution, observability, replay-buffer, delivery-limits]
status: complete
last_updated: 2026-07-28
last_updated_by: Cascade
last_updated_note: "Replaced restated-gap open questions with forward-looking design questions; removed references to the superseded kimi_research.md"
---

# Research: PR 11 — Live Activity and Active-Execution Detail

**Date**: 2026-07-28T08:32:52-07:00
**Researcher**: Cascade
**Git Commit**: `348ab1a35c4a8b5a950c823ce48023cb78aae47a`
**Branch**: `main`
**Repository**: bifrost

## Research Question

What existing Bifrost Console components, Java observability endpoints, Go services, React state, and fixtures are available as the starting point for PR 11 — "Live Activity and Active-Execution Detail"? In particular:

- Where is the upstream SSE live-activity endpoint defined and how does it frame events?
- How are active executions currently listed, paged, and detailed in the Go console and React frontend?
- What target-scope, tab, CSRF, and route infrastructure already exists?
- How will future PRs (12–15, 17) consume the live-activity query seam and shared services that PR 11 must establish?

## Summary

The Java application exposes a bounded, resumable SSE activity stream at `/_bifrost/observability/v1/activity` (`ObservabilityApiPaths.ACTIVITY`), together with a bounded replay buffer (`InMemoryActivityReplayBuffer`), per-subscriber dispatch (`ObservabilityActivityDelivery`), and activity framing (`ObservabilityActivityStream`). The live-activity DTO (`ActivityEnvelope`) and the active-execution snapshot (`ActiveExecutionSnapshot`) are produced by `LiveActivityProjector` from canonical trace records.

The Go `bifrost-console` currently has no SSE/activity client. It does have:

- A transport-neutral `internal/observability` service that proxies JSON list/detail calls to the upstream observability REST endpoints.
- A `internal/browserapi` router that exposes those list/detail calls as `/api/console/v1/active-executions/list` and `/api/console/v1/active-executions/detail`.
- React components `ActiveExecutions.tsx` and `ActiveExecutionDetail.tsx`, plus `ObservabilityProvider.tsx` and `reducer.ts`, which manage list/detail state with pagination, scope generation, and `TARGET_CHANGED`/`STALE_CURSOR` recovery.
- A target-scope lifecycle in `internal/target` and `web/src/target` that resets state on scope rotation.
- A tab/CSRF browser session layer in `web/src/security/BrowserSessionProvider.tsx` and `internal/browserauth`.

The Go `Address` type has endpoint methods for instance, skills, active-executions, and traces, but does not have an `ActivityEndpoint()` method. No SSE client, recent-activity window, activity relay, or activity-related browser API endpoint exists in the Go console. No `ActivityEnvelope` TypeScript type or live-activity reducer state exists in the React frontend.

## Detailed Findings

### 1. Java upstream live-activity endpoint and framing

The Java application observability namespace is rooted at `/_bifrost/observability/v1` and is registered dynamically by `ObservabilityRouteRegistrar`:

- `ObservabilityApiPaths.ACTIVITY` = `/_bifrost/observability/v1/activity` (`ObservabilityApiPaths.java:9`).
- `ObservabilityRestController.activity(HttpServletRequest, HttpServletResponse)` handles the endpoint (`ObservabilityRestController.java:149-203`).
- The request must be `GET`, must contain exactly `instanceId` and `afterCursor` query parameters, must accept `text/event-stream`, and must not carry a `Last-Event-ID` header (`ObservabilityRestController.java:349-378`).
- The controller validates the cursor via `replayBuffer().replayAfter(afterCursor, 1)`, admits a subscription via `activityDelivery().admit(afterCursor)`, sends a handshake frame, and then calls `ObservabilityActivityStream.open(...)` (`ObservabilityRestController.java:176-196`).

`ObservabilityActivityStream` (`ObservabilityActivityStream.java`):

- Sets `Content-Type: text/event-stream; charset=UTF-8` and uses Jakarta `AsyncContext` with timeout `0` (`ObservabilityActivityStream.java:55-78`).
- Frames events as `id: <cursor>\nevent: activity\ndata: <json>\n\n`; the handshake uses `event: handshake` with no `id` (`ObservabilityActivityStream.java:100-123`).
- The `frame` method constructs SSE byte frames with prefix `id: <cursor>\nevent: activity\ndata: ` followed by JSON payload and `\n\n` terminator (`ObservabilityActivityStream.java:112-123`).
- Enqueues frames with per-subscriber bounds: `PENDING_ACTIVITY_FRAMES = 256` and `PENDING_BYTES = 1 MiB` (`ObservabilityDeliveryLimits.java:9-10`).
- Closes if the head of the pending queue is not written within `WRITE_READINESS_DEADLINE = 5s` (`ObservabilityDeliveryLimits.java:12`; `ObservabilityActivityStream.java:231-246`).

`ObservabilityActivityDelivery` (`ObservabilityActivityDelivery.java`):

- `admit(afterCursor)` enforces `OPEN_SUBSCRIPTIONS = 16` concurrent subscribers (`ObservabilityDeliveryLimits.java:7`).
- `dispatchOneBatchPerCursor` groups subscribers by their current cursor, replays activities after that cursor in batches of `REPLAY_BATCH = 256`, serializes each activity frame once, and offers the byte frame to all matching subscribers (`ObservabilityActivityDelivery.java:196-268`).
- Subscribes to `LiveActivitySignal` for `activityAvailable()` / `liveUnavailable()` notifications.
- Closes subscribers on `TOO_OLD` or `FUTURE` replay status or on frame encoding failure (`ObservabilityActivityDelivery.java:206-230`).

`InMemoryActivityReplayBuffer` (`InMemoryActivityReplayBuffer.java`):

- Retains up to `REPLAY_EVENTS = 10,000` events or `REPLAY_UTF8_BYTES = 16 MiB` (`ExecutionObservationLimits.java:13-14`).
- `append(ExecutionActivity)` assigns a strictly increasing positive `long` cursor via a `cursorSupplier`, stores the activity in a `Deque`, and evicts oldest-first when count or byte limits are exceeded (`InMemoryActivityReplayBuffer.java:42-66`).
- `replayAfter(cursor, limit)` returns `AVAILABLE`, `EMPTY`, `TOO_OLD`, or `FUTURE` (`ReplayResult.java:1-20`; `InMemoryActivityReplayBuffer.java:75-115`).

`ObservabilityDeliveryLimits` defines all delivery constants (`ObservabilityDeliveryLimits.java:5-16`):

| Constant | Value |
|---|---|
| `OPEN_SUBSCRIPTIONS` | 16 |
| `OPEN_ARTIFACT_DOWNLOADS` | 8 |
| `PENDING_ACTIVITY_FRAMES` | 256 |
| `PENDING_BYTES` | 1,048,576 (1 MiB) |
| `REPLAY_BATCH` | 256 |
| `WRITE_READINESS_DEADLINE` | 5 seconds |
| `ARTIFACT_DOWNLOAD_TIMEOUT` | 5 minutes |

The SSE fixture `bifrost-console-fixtures/application-sse/replay.sse` shows the expected handshake + two activity frames; `activity-core-finalization-failed.sse` and `activity-trace-completed.sse` show the `EXECUTION_OBSERVATION_ENDED` and `TRACE_COMPLETED` kinds and `details.artifactAvailability`.

### 2. Java activity content and active-execution snapshot

`LiveActivityProjector` (`LiveActivityProjector.java`):

- Projects an `ExecutionActivity` for a subset of visible `TraceRecordType`s defined in the `VISIBLE` set (`LiveActivityProjector.java:21-36`): `TRACE_STARTED`, `MODEL_REQUEST_SENT`, `MODEL_RESPONSE_RECEIVED`, `PLAN_CREATED`, `PLAN_UPDATED`, `PLAN_VALIDATION_FAILED`, `PLAN_RETRY_REQUESTED`, `TOOL_CALL_STARTED`, `TOOL_CALL_COMPLETED`, `TOOL_CALL_FAILED`, `STEP_STARTED`, `STEP_ACTION_REJECTED`, `STEP_COMPLETED`, `ERROR_RECORDED`, `TRACE_COMPLETED`.
- Also processes `FRAME_OPENED` and `FRAME_CLOSED` records for `SKILL_EXECUTION` frames in `updateState` to maintain the active frame path, though these are not in the `VISIBLE` set.
- Updates `state.phase`, `state.summary`, `state.usage`, and the active frame path (`LiveActivityProjector.java:72-116`).
- Returns a `Projection` containing the latest `ActiveExecutionSnapshot`, the non-terminal `ExecutionActivity`, and a held terminal `ExecutionActivity` (`LiveActivityProjector.java:68-70`).
- `DETAIL_KEYS` lists 20 recognized detail field names (`LiveActivityProjector.java:38-42`).

`ExecutionObservationLimits` (`ExecutionObservationLimits.java:5-14`):

| Constant | Value |
|---|---|
| `ACTIVE_FRAME_PATH_ENTRIES` | 64 |
| `TEXT_CODE_POINTS` | 256 |
| `SUMMARY_CODE_POINTS` | 512 |
| `DETAIL_FIELDS` | 32 |
| `DETAIL_UTF8_BYTES` | 8,192 (8 KiB) |
| `ACTIVITY_UTF8_BYTES` | 12,288 (12 KiB) |
| `REPLAY_EVENTS` | 10,000 |
| `REPLAY_UTF8_BYTES` | 16,777,216 (16 MiB) |

`ActiveExecutionSnapshot` (`ActiveExecutionSnapshot.java:12-56`):

- Contains `sessionId`, `traceId`, `registryOrdinal`, `lastCanonicalSequence`, `startedAt`, `updatedAt`, `entrySkill` (nullable), `phase`, `summary`, `activePath`, `totalFrameDepth`, `activePathTruncated`, `usage`, and `outcome` (nullable `TraceOutcome`).
- Enforces `activePath` ≤ 64 entries and `totalFrameDepth ≥ activePath.size()`.
- `FramePathEntry` subrecord contains `frameId`, `frameType` (`TraceFrameType`), and `route`.

`ObservabilityDtoMapper.activity(String instanceId, ExecutionActivity)` maps the runtime activity into the wire `ActivityEnvelope` (`ObservabilityDtoMapper.java:62-79`). The envelope contains:

- `instanceId` (String)
- `cursor` (String, decimal representation of the `long` delivery cursor)
- `sessionId` (String)
- `traceId` (String)
- `canonicalSequence` (Long)
- `timestamp` (Instant)
- `kind` (`ExecutionActivityKind`)
- `executionStatus` (String)
- `frameId` (String)
- `parentFrameId` (String)
- `frameType` (`TraceFrameType`)
- `route` (String)
- `summary` (String)
- `details` (Map<String, Object>)

(`ObservabilityDtos.java:91-106`)

`ActivityHandshake` DTO contains `instanceId`, `observedAt`, and `afterCursor` (`ObservabilityDtos.java:86-89`).

`ExecutionActivityKind` (`ExecutionActivityKind.java:3-23`) lists 18 activity kinds:

`TRACE_STARTED`, `FRAME_OPENED`, `FRAME_CLOSED`, `MODEL_REQUEST_SENT`, `MODEL_RESPONSE_RECEIVED`, `PLAN_CREATED`, `PLAN_UPDATED`, `PLAN_VALIDATION_FAILED`, `PLAN_RETRY_REQUESTED`, `TOOL_CALL_STARTED`, `TOOL_CALL_COMPLETED`, `TOOL_CALL_FAILED`, `STEP_STARTED`, `STEP_ACTION_REJECTED`, `STEP_COMPLETED`, `ERROR_RECORDED`, `TRACE_COMPLETED`, `EXECUTION_OBSERVATION_ENDED`.

`LiveMonitoringAvailability` (`LiveMonitoringAvailability.java:7-46`):

- Uses an `AtomicReference<Failure>` to track the first failure. Once any failure is recorded via `fail(operation, throwable)`, `isAvailable()` returns `false` permanently for that process.
- `firstFailure()` returns an `Optional<Failure>` containing the operation name and exception class.

`DefaultExecutionObservationHandle` (`DefaultExecutionObservationHandle.java:82-93`):

- When a projection produces a non-null `activity`, the handle appends it to `replayBuffer` and calls `signalActivity()` to notify delivery.
- On `REPLAY_PUBLICATION_FAILED`, calls `failClosed` with code `REPLAY_PUBLICATION_FAILED`.

### 3. Go console target-scope, application client, and REST bridge

`internal/applicationclient/address.go` builds upstream URLs:

- `ObservabilityRoot()` = `<scheme>://<authority>/_bifrost/observability/v1` (`address.go:86`).
- `ActiveExecutionsEndpoint()` = root + `/active-executions` (`address.go:134-136`).
- `ActiveExecutionEndpoint(sessionId)` = root + `/active-executions/<sessionId>` (`address.go:137-139`).
- `InstanceEndpoint()`, `SkillsEndpoint()`, `SkillEndpoint(name)`, `TracesEndpoint()`, `TraceEndpoint(traceId)` are also defined (`address.go:129-143`).
- No `ActivityEndpoint()` method exists on `Address`.

`internal/applicationclient/client.go`:

- `Client.Get(...)` performs `GET` with `Accept: application/json` and `Accept-Encoding: identity`, returns body, instance ID, and a typed `Failure` (`client.go:91-152`).
- `Client.Probe(...)` validates `consoleCompatibilityVersion` and `liveMonitoringAvailable` and returns `applicationclient.Instance` (`client.go:154-231`).
- `classifyTransport` maps errors to categories: `dns`, `connection`, `timeout`, `tls_untrusted_issuer`, `tls_hostname_mismatch`, `tls_expired`, `tls_not_yet_valid`, `tls_handshake`, `redirect`, `namespace_not_found`, `upstream_server`, `upstream_protocol` (`client.go:282-326`).

`internal/applicationclient/errors.go`:

- 11 `FailureKind` constants: `FailureAuthentication`, `FailureAccess`, `FailureIncompatible`, `FailureUnavailable`, `FailureProtocol`, `FailureInvalidArgument`, `FailureInvalidCursor`, `FailureStaleCursor`, `FailureNotFound`, `FailureLimitExceeded`, `FailureLiveMonitoringUnavailable` (`errors.go:11-23`).
- `Failure.ConsoleError(scopeID)` maps each kind to a `consolecore.Error` with the appropriate error code and details (`errors.go:78-104`).

`internal/target/context.go`:

- `Context` manages the selected target scope, credentials, retry schedule, and `ScopeOwner` invalidation (`context.go:55-72`).
- `Capture()` returns a `Scope` containing the current `ID`, `Context`, `Target`, `InstanceID`, `client`, `credential`, and `authority` (`context.go:239-253`).
- `PublishCurrent(scope, publish)` runs `publish` only if the scope is still current (`context.go:282-292`).
- `RegisterOwner(name, owner)` registers a `ScopeOwner` that receives `InvalidateTargetScope` calls on rotation; registration is closed once serving begins (`context.go:95-111`).
- `rotateLocked` cancels the old scope context, closes the old client, notifies all registered `ScopeOwner`s, and creates a fresh scope with a new `ScopeID` (`context.go:451-486`).
- `probe` performs the authenticated instance-status check, establishes `LiveMonitoring` status from `instance.LiveMonitoringAvailable`, and sets `RuntimeIdentity` to `RuntimeEstablished` (`context.go:308-404`).

`internal/target/scope.go`:

- `Scope` holds `ID`, `Context`, `Target`, `InstanceID`, `client`, `credential`, and `authority` (`scope.go:16-24`).
- `Scope.Upstream(ctx, endpoint, maxBytes)` performs a scoped `GET` via `client.Get`, handles instance-ID mismatch by triggering `revalidateAfterMismatch`, maps `context.Canceled` to either `TARGET_UNAVAILABLE` or `TARGET_CHANGED` depending on whether the parent was canceled, and maps `applicationclient.Failure` to `consolecore.Error` (`scope.go:39-81`).
- `Scope.Probe(ctx)` performs a scoped probe via `client.Probe` (`scope.go:26-37`).
- `Scope.RequireCurrent()` delegates to `authority.RequireCurrent(scope.ID)` (`scope.go:83-88`).

`internal/observability/service.go`:

- `ListActiveExecutions` and `GetActiveExecution` call upstream via `scope.Upstream`, unmarshal JSON, validate the response, set `TargetScopeID`, and call `scope.RequireCurrent()` (`service.go:98-149`).
- Default page size is 1000, max page size is 5000, `collectionMaxBytes` is 16 MiB, `activeExecutionDetailMaxBytes` is 1 MiB (`service.go:15-23`).
- Validators for `ActiveExecution`, `Page`, `InstanceStatus`, `SkillSummary`, `SkillDetail`, and `Trace` are defined (`service.go:204-323`).

`internal/observability/dto.go` defines Go DTOs matching the Java wire format:

- `InstanceStatus` includes `LiveMonitoringAvailable` (`dto.go:5-17`).
- `ActiveExecution` includes `SessionID`, `TraceID`, `LastCanonicalSequence`, `StartedAt`, `UpdatedAt`, `ElapsedMillis`, `EntrySkill`, `Status`, `Phase`, `Summary`, `ActivePath`, `TotalFrameDepth`, `ActivePathTruncated`, `Usage`, `ConfiguredLimits` (`dto.go:59-76`).
- `ActivePage` embeds `Page[ActiveExecution]` and adds `ResumeCursor *string` (`dto.go:97-100`).
- `FramePathEntry`, `Usage`, `ConfiguredLimits`, `Trace`, `SkillSummary`, `SkillDetail` are also defined (`dto.go:19-106`).

`internal/browserapi/router.go` wires the browser-facing REST routes:

- `POST /api/console/v1/observability/instance` → `observabilityInstance` (`router.go:75-76`).
- `POST /api/console/v1/skills/list` → `skillsList` (`router.go:77-78`).
- `POST /api/console/v1/skills/detail` → `skillDetail` (`router.go:79-80`).
- `POST /api/console/v1/active-executions/list` → `activeExecutionsList` (`router.go:81-82`).
- `POST /api/console/v1/active-executions/detail` → `activeExecutionDetail` (`router.go:83-84`).
- `POST /api/console/v1/traces/list` → `tracesList` (`router.go:85-86`).
- `POST /api/console/v1/traces/detail` → `traceDetail` (`router.go:87-88`).
- All routes go through `withSession`, with CSRF required only for state-changing operations (`router.go:54-91`). Active-execution list/detail use `withSession(..., false, ...)` (no CSRF).

`internal/browserapi/observability.go` translates browser JSON requests to `observability.Service` calls:

- Each handler decodes a JSON body (limited to `maxObservabilityJSONBody = 4 KiB`), captures the target scope, calls the service method, and writes scoped JSON through `PublishCurrent` (`observability.go:13-195`).
- `writeScopedJSON` marshals the response, appends a newline, and publishes through `Target.PublishCurrent` to ensure the scope is still current before writing (`observability.go:197-209`).

`internal/consolecore/status.go`:

- `LiveMonitoring` type has four values: `NOT_APPLICABLE`, `UNKNOWN`, `AVAILABLE`, `UNAVAILABLE` (`status.go:39-42`).
- `StatusSnapshot` includes `LiveMonitoring` alongside `TargetConnection`, `TargetAuthentication`, `JavaGoCompatibility`, `RuntimeIdentity`, and `InstanceID` (`status.go:45-55`).
- `Validate()` enforces that `RuntimeIdentity == ESTABLISHED` iff `InstanceID != ""` (`status.go:85-87`).

### 4. React frontend state and active-execution views

`web/src/api/contracts.ts` defines the TypeScript contract types:

- `BrowserErrorCode` includes `LIVE_MONITORING_UNAVAILABLE`, `STALE_CURSOR`, `INVALID_CURSOR`, `TARGET_CHANGED`, `LIMIT_EXCEEDED` (`contracts.ts:1-23`).
- `TargetStatus.liveMonitoring` is `"NOT_APPLICABLE" | "UNKNOWN" | "AVAILABLE" | "UNAVAILABLE"` (`contracts.ts:43`).
- `InstanceStatus.liveMonitoringAvailable` is `boolean` (`contracts.ts:88`).
- `ActiveExecution` includes `sessionId`, `traceId`, `lastCanonicalSequence`, `startedAt`, `updatedAt`, `elapsedMillis`, `entrySkill`, `status`, `phase`, `summary`, `activePath`, `totalFrameDepth`, `activePathTruncated`, `usage`, `configuredLimits` (`contracts.ts:137-154`).
- `ActivePage` extends `Page<ActiveExecution>` with `resumeCursor: string | null` (`contracts.ts:175-177`).
- No `ActivityEnvelope` or `ActivityHandshake` type exists.

`web/src/api/client.ts`:

- `listActiveExecutions(cursor?, pageSize?)` POSTs to `/api/console/v1/active-executions/list` (`client.ts:130-135`).
- `getActiveExecutionDetail(sessionId)` POSTs to `/api/console/v1/active-executions/detail` (`client.ts:137-139`).
- `BrowserAPIError` carries `code`, `message`, `status`, `targetScopeId`, and `details` (`client.ts:17-27`).
- All API calls use the `post<T>` helper which sets `Content-Type: application/json`, `X-Bifrost-Console-Tab`, `X-Bifrost-Console-CSRF` headers, and uses `credentials: "same-origin"`, `cache: "no-store"`, `redirect: "error"` (`client.ts:34-66`).

`web/src/observability/ObservabilityProvider.tsx`:

- Holds `instance`, `skills`, `activeExecutions`, `traces` state and load functions (`ObservabilityProvider.tsx:46-54`).
- Resets state when `scopeGeneration` changes by incrementing `generationRef` and dispatching `reset` (`ObservabilityProvider.tsx:61-68`).
- `loadActiveExecutions` retries without cursor on `STALE_CURSOR`, then calls `requireCurrentScope` and dispatches `active-success` with `resumeCursor` preserved (`ObservabilityProvider.tsx:116-150`).
- Each load function uses a generation ref and request ID ref to handle stale responses (`ObservabilityProvider.tsx:70-177`).

`web/src/observability/reducer.ts`:

- `ObservabilityState` includes `activeExecutions` with `targetScopeId`, `items`, `hasMore`, `nextCursor`, `resumeCursor`, `observedAt`, `loading`, `loaded`, and `error` (`reducer.ts:3-8`).
- `observabilityReducer` handles `reset`, `active-loading`, `active-success`, and `active-error` (`reducer.ts:36-98`).
- `active-success` uses the `append` flag to either replace or concatenate items and keeps `resumeCursor` stable across appends (`reducer.ts:65-78`).

`web/src/observability/ActiveExecutions.tsx`:

- Renders a paginated table of active executions with links to `/active-executions/:sessionId?targetScopeId=<scope>` (`ActiveExecutions.tsx:7-89`).
- Triggers `loadActiveExecutions()` on first load if `!loaded && !loading && !error` (`ActiveExecutions.tsx:15-19`).
- Uses `scopeBoundPath` from `web/src/observability/scope.ts` to attach the scope query parameter (`ActiveExecutions.tsx:65`).
- "Load more" button appears when `hasMore && nextCursor` (`ActiveExecutions.tsx:80-84`).

`web/src/observability/ActiveExecutionDetail.tsx`:

- Reads `:sessionId` from React Router params, calls `getActiveExecutionDetail`, validates scope with `requireCurrentTargetScope`, and renders a status grid, usage table, configured limits, and active-path table (`ActiveExecutionDetail.tsx:12-131`).
- Uses `useScopeBoundRoute()` to navigate to `/` if the URL scope no longer matches the current target scope (`ActiveExecutionDetail.tsx:21-45`).
- The detail view is a static snapshot — it does not subscribe to live activity updates.

`web/src/observability/scope.ts`:

- `scopeBoundPath(path, targetScopeID)` appends `?targetScopeId=...` (`scope.ts:6-10`).
- `requireCurrentTargetScope` refreshes target on mismatch and throws `BrowserAPIError` with `TARGET_CHANGED` (`scope.ts:12-26`).
- `recoverObservabilityError` refreshes once for `TARGET_CHANGED` and normalizes unknown errors to `CONSOLE_ERROR` (`scope.ts:28-43`).

`web/src/target/TargetProvider.tsx` and `targetReducer.ts`:

- Manage `target`, `error`, and `scopeGeneration`; a new `targetScopeId` triggers a navigation to `/` with `staleTargetScope: true` (`TargetProvider.tsx:41-56`).
- `scopeGeneration` is incremented on scope changes and on `TARGET_CHANGED` errors (`targetReducer.ts:27-36`).

`web/src/app/routes.tsx` defines the browser routes:

- `/active-executions` → `<ActiveExecutions />` (`routes.tsx:23`).
- `/active-executions/:sessionId` → `<ActiveExecutionDetailView />` (`routes.tsx:24`).
- Also: `/` (ObservabilityOverview), `/target` (Overview), `/skills`, `/skills/:registeredName`, `/traces`, `/traces/:traceId` (`routes.tsx:14-31`).

### 5. Browser session, tab, and CSRF infrastructure

`web/src/security/BrowserSessionProvider.tsx`:

- `bootstrap()` returns `processId`, `workspacePath`, `tabId`, `csrfToken`, and `target`. Current security (`tabId`/`csrfToken`) is kept in a ref (`BrowserSessionProvider.tsx:29-155`).
- Heartbeats every 60 seconds (`tabHeartbeatIntervalMilliseconds = 60_000`) and on `visibilitychange`/`pageshow`; on session security errors it re-pairs (`BrowserSessionProvider.tsx:29-155`).
- `pagehide` releases the tab (`BrowserSessionProvider.tsx:158-168`).

`internal/browserapi/router.go` validates `X-Bifrost-Console-Tab` and `X-Bifrost-Console-CSRF` for CSRF-protected operations (`router.go:143-151`). Active-execution list/detail are not CSRF-protected (they use `withSession(..., false, ...)`), matching the read-only operational views from PR 10.

### 6. Fixtures and expected semantics

`bifrost-console-fixtures/application-rest/`:

- `instance-status.json` — `instanceId`, `consoleCompatibilityVersion: "0.1.0-SNAPSHOT"`, `liveMonitoringAvailable: true`, skill/execution/trace counts, persistence policy, TTLs.
- `active-executions-page.json` — one active execution (`session-1`, `CheckDns`, `RUNNING`), `resumeCursor: "9"`, `hasMore: false`, `nextCursor: null`.
- `active-execution-detail.json` — full `ActiveExecution` payload for `session-1` with usage and configured limits.
- `continuation-page.json`, `empty-page.json` — pagination fixtures.
- Problem fixtures: `problem-live-monitoring-unavailable.json` (503, `LIVE_MONITORING_UNAVAILABLE`), `problem-stale-cursor.json` (410, `STALE_CURSOR`), `problem-invalid-cursor.json`, `problem-limit-exceeded.json`, `problem-not-found.json`, `problem-application-error.json`, `problem-bifrost-api-key-rejected.json`, `problem-invalid-request.json`.

`bifrost-console-fixtures/application-sse/`:

- `handshake.sse` — `event: handshake` with `{"instanceId":"11111111-1111-4111-8111-111111111111","observedAt":"2026-07-25T12:00:00Z","afterCursor":"0"}`.
- `replay.sse` — handshake followed by two `activity` events: cursor 7 (`TRACE_COMPLETED`, `artifactAvailability: "AVAILABLE"`) and cursor 8 (`EXECUTION_OBSERVATION_ENDED`, `artifactAvailability: "CORE_FINALIZATION_FAILED"`).
- `activity-trace-completed.sse` — single `activity` event with `id: 7`, `TRACE_COMPLETED`.
- `activity-core-finalization-failed.sse` — single `activity` event with `id: 8`, `EXECUTION_OBSERVATION_ENDED`.

`bifrost-console-fixtures/expected/` contains the current-release semantic trace corpus for future trace analysis. `bifrost-console-fixtures/README.md` states that `application-rest/`, `application-sse/`, and the semantic trace corpus are the Java-to-Go contract and must not be duplicated.

### 7. Dependency and future-PR context

The Bifrost Console Implementation Roadmap (`ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md`) places PR 11 in Phase 2 after PR 10 and before PR 12:

- Phase 2 dependency chain: `07 → 08 → 09 → 10 → 11 → 12 → 13 → 14 → 15`.
- PR 10 adds read-only operational views; PR 11 adds the live activity/active-execution experience.
- Cross-phase: `11 → 17` (MCP runtime inspection reuses PRs 09–11).

Future PR briefs that reference PR 11 output:

- **PR 12 — Central Artifact Acquisition and Trace Storage**: depends on PR 11; reuses the same transport-neutral services and shared live-activity boundaries; adds artifact streaming and trace storage.
- **PR 13 — Trace Parser, Indexes, and Shared Calculations**: consumes the artifact service and the current-release trace fixtures; needs the activity continuity boundary from PR 11.
- **PR 14 — Trace Explorer Foundation**: navigates traces; uses the raw-artifact pass-through from PR 12 and the scope/lifecycle mechanisms from PRs 09–11.
- **PR 15 — Diagnostic Workflows and Phase 2 Hardening**: completes workflows including the slow-execution workflow started by PR 11; adds terminal live-to-trace transition, reconnect, and target-reset coverage.
- **PR 17 — Runtime, Skill, and Live-Inspection MCP Surface**: reuses PRs 09–11; exposes `bifrost_get_execution_activity` over the same shared continuous recent-activity window without owning a separate upstream subscription.

## Code References

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiPaths.java:9` — activity route constant `ACTIVITY`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestController.java:149-203` — activity SSE endpoint handler.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestController.java:349-378` — activity request validation.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityActivityStream.java:48-123` — SSE open, handshake, and framing.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityActivityStream.java:136-256` — enqueue, drain, pending-frame and byte limits, write readiness deadline.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityActivityDelivery.java:1-130` — admission control, subscriber registration, signal handling.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityActivityDelivery.java:196-268` — `dispatchOneBatchPerCursor` replay and frame offering.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityDeliveryLimits.java:5-16` — per-subscriber and buffer bounds.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActivityReplayBuffer.java:42-66` — append with cursor assignment and eviction.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActivityReplayBuffer.java:75-115` — replay semantics (TOO_OLD / FUTURE / EMPTY / AVAILABLE).
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ReplayResult.java:1-20` — replay result record and status enum.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/LiveActivityProjector.java:21-36` — visible record type set.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/LiveActivityProjector.java:44-70` — projection model and return type.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ActiveExecutionSnapshot.java:12-56` — active-execution snapshot fields and bounds.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionActivityKind.java:3-23` — 18 activity kind enum values.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionObservationLimits.java:5-14` — text, summary, detail, activity, and replay limits.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/LiveMonitoringAvailability.java:7-46` — atomic first-failure tracking.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java:82-93` — replay buffer append and activity signal.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityDtoMapper.java:62-79` — activity DTO mapping.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/dto/ObservabilityDtos.java:86-106` — `ActivityHandshake` and `ActivityEnvelope` wire DTOs.
- `bifrost-console/internal/applicationclient/address.go:86-143` — upstream observability URL construction (no `ActivityEndpoint`).
- `bifrost-console/internal/applicationclient/client.go:91-152` — upstream `GET` with error categorization.
- `bifrost-console/internal/applicationclient/errors.go:9-104` — `FailureKind` constants and `ConsoleError` mapping.
- `bifrost-console/internal/target/context.go:55-72` — `Context` fields and lifecycle.
- `bifrost-console/internal/target/context.go:95-111` — `RegisterOwner` for `ScopeOwner` invalidation.
- `bifrost-console/internal/target/context.go:239-292` — `Capture` and `PublishCurrent`.
- `bifrost-console/internal/target/context.go:451-486` — `rotateLocked` scope rotation with owner notification.
- `bifrost-console/internal/target/scope.go:16-24` — `Scope` struct fields.
- `bifrost-console/internal/target/scope.go:39-81` — `Scope.Upstream` and failure mapping.
- `bifrost-console/internal/observability/service.go:15-23` — page size and byte limits.
- `bifrost-console/internal/observability/service.go:98-149` — `ListActiveExecutions` / `GetActiveExecution`.
- `bifrost-console/internal/observability/dto.go:5-100` — Go observability DTOs.
- `bifrost-console/internal/browserapi/router.go:54-91` — browser API route table.
- `bifrost-console/internal/browserapi/observability.go:13-209` — browser API observability handlers and `writeScopedJSON`.
- `bifrost-console/internal/consolecore/status.go:39-55` — `LiveMonitoring` status values and `StatusSnapshot`.
- `bifrost-console/web/src/api/contracts.ts:1-177` — TypeScript contracts including error codes, `ActiveExecution`, `ActivePage`.
- `bifrost-console/web/src/api/client.ts:17-27` — `BrowserAPIError` class.
- `bifrost-console/web/src/api/client.ts:34-66` — `post` helper with security headers.
- `bifrost-console/web/src/api/client.ts:130-139` — active-execution client calls.
- `bifrost-console/web/src/observability/ObservabilityProvider.tsx:46-207` — state management, reset, and load functions.
- `bifrost-console/web/src/observability/reducer.ts:3-98` — reducer state and actions.
- `bifrost-console/web/src/observability/ActiveExecutions.tsx:7-89` — active-execution list UI.
- `bifrost-console/web/src/observability/ActiveExecutionDetail.tsx:12-131` — active-execution detail UI.
- `bifrost-console/web/src/observability/scope.ts:1-44` — scope-bound paths and error recovery.
- `bifrost-console/web/src/app/routes.tsx:14-31` — browser route definitions.
- `bifrost-console/web/src/security/BrowserSessionProvider.tsx:29-168` — tab lifecycle, heartbeat, release.
- `bifrost-console-fixtures/application-sse/handshake.sse` — handshake frame example.
- `bifrost-console-fixtures/application-sse/replay.sse` — full handshake + activity frame example.
- `bifrost-console-fixtures/application-sse/activity-trace-completed.sse` — `TRACE_COMPLETED` activity.
- `bifrost-console-fixtures/application-sse/activity-core-finalization-failed.sse` — `EXECUTION_OBSERVATION_ENDED` activity.
- `bifrost-console-fixtures/application-rest/active-executions-page.json` — page with `resumeCursor`.
- `bifrost-console-fixtures/application-rest/active-execution-detail.json` — full active execution payload.
- `bifrost-console-fixtures/application-rest/instance-status.json` — `liveMonitoringAvailable` flag.
- `bifrost-console-fixtures/application-rest/problem-live-monitoring-unavailable.json` — 503 error fixture.
- `bifrost-console-fixtures/application-rest/problem-stale-cursor.json` — 410 error fixture.
- `ai/thoughts/tickets/bifrost-console-pr-11-live-execution-experience.md` — PR 11 brief.
- `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:442-470` — live activity relay and recent-activity queries design.
- `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:597-689` — live execution presentation design.

## Architecture Documentation

### Current layers

1. **Java application runtime**: owns canonical trace records, live projection (`LiveActivityProjector`), activity replay (`InMemoryActivityReplayBuffer`), per-subscriber delivery (`ObservabilityActivityDelivery`), SSE framing (`ObservabilityActivityStream`), and REST/SSE observability endpoints (`ObservabilityRestController`). It is the authoritative source for activity, active-execution snapshots, and trace catalogs.
2. **Go console services**: own target selection (`target.Context`), scope lifecycle (`target.Scope`), application client transport (`applicationclient.Client`), and the browser API (`browserapi.Router`). They currently bridge REST calls but do not consume the SSE stream.
3. **React frontend**: owns browser state (`ObservabilityProvider`, `reducer`), routing (`routes.tsx`), and presentation (`ActiveExecutions`, `ActiveExecutionDetail`). It currently consumes the read-only operational views through POST-based browser API calls.
4. **Fixtures**: capture the current-release wire contracts for REST (`application-rest/`), SSE (`application-sse/`), and trace semantics (`expected/`). They must not be duplicated.

### Design patterns

- **Transport-neutral services**: the Java side publishes REST and SSE; the Go side has a service layer in `internal/observability` that the browser API consumes. The `Scope.Upstream` method provides scoped GET requests with instance-ID mismatch detection.
- **Scope-bound state**: every observability response carries `targetScopeId`; the frontend and Go services reset state on `scopeGeneration` change. `Target.PublishCurrent` guards response writing to ensure scope currency.
- **POST-all browser API**: `internal/browserapi/router.go` uses `POST` for all browser operations, even reads, to avoid CSRF leakage. CSRF is required only for state-changing operations.
- **Bounded resources**: the Java activity delivery has explicit pending-frame (256), byte (1 MiB), subscription (16), replay-batch (256), and write-deadline (5s) limits. The Go service has max response sizes (`collectionMaxBytes` = 16 MiB, `activeExecutionDetailMaxBytes` = 1 MiB). The Java replay buffer retains up to 10,000 events or 16 MiB.
- **Cursor continuity**: active executions use a page cursor and a `resumeCursor` for live activity; the SSE stream uses an increasing `deliveryCursor` with `handshake.afterCursor` and `id` on each frame.
- **Target/activity separation**: `LiveMonitoringAvailability` tracks first failure atomically; `consolecore.StatusSnapshot` keeps `LiveMonitoring` as a separate fact from connection, authentication, and compatibility.
- **ScopeOwner registration**: `target.Context.RegisterOwner` allows services to register for scope invalidation notifications; registration is closed once serving begins.
- **Stale-cursor recovery**: `ObservabilityProvider.loadActiveExecutions` catches `STALE_CURSOR` and retries without cursor; `recoverObservabilityError` refreshes target on `TARGET_CHANGED`.

## Contract and Compatibility Classification

Using the categories from `ai/thoughts/framework-feature-design-lens.md`:

- **Application API**: not directly affected; the console is an internal developer tool.
- **Supported SPI**: no new Spring SPI for this PR.
- **Configuration and manifest contracts**: no `bifrost.*` properties are changed. Any new console profile settings for live-activity bounds would need classification.
- **Persisted or serialized contracts**: not applicable; traces and activity are ephemeral diagnostic formats.
- **Ephemeral diagnostic formats**: the `ActivityEnvelope`, `ActivityHandshake`, `ActiveExecutionSnapshot`, and SSE frames are current-run diagnostic formats. They may evolve to improve current-run usefulness and are not historical/cross-version contracts. The SSE fixtures in `application-sse/` capture the current-release wire format.
- **Internal or accidentally exposed implementation**: the Java `ObservabilityRestController` and SSE framing, the Go `internal/observability` and `browserapi` packages, and the React contracts are implementation surfaces consumed by in-repository consumers (console, fixtures, future MCP adapter). They are not supported external APIs.

Evidence status: the `ObservabilityApiPaths`, `ObservabilityDtos`, SSE fixture files, and `consoleCompatibilityVersion` field constitute the current-release Java-to-Go contract. No independently versioned compatibility marker exists for the SSE stream itself; the `consoleCompatibilityVersion` in `InstanceStatus` is the shared compatibility gate.

## Historical Context

- **PR 05 — Live SSE delivery** and **PR 06 — Artifact streaming** established the Java side of the live activity stream and artifact boundary. The current codebase reflects those completed surfaces.
- **PR 07–09** created the Go console build, browser security, and `TargetContext` lifecycle. The existing `TargetProvider`, `TargetContext`, `Scope`, and session infrastructure are their output.
- **PR 10 — Read-only operational views** added the current `ActiveExecutions`, `ActiveExecutionDetail`, `ObservabilityProvider`, `reducer.ts`, `client.ts`, and `contracts.ts` for skills, active executions, and traces. PR 11 is the next PR in the Phase 2 dependency chain.
- A prior research document on the same PR 11 topic (`kimi_research.md`) previously existed in `ai/thoughts/research/`. It was removed after verification showed it recorded `ObservabilityRoot()` without the `/_bifrost` prefix and listed `FRAME_OPENED`/`FRAME_CLOSED` as members of the `LiveActivityProjector.VISIBLE` set. This document supersedes it.

## Related Research

- `ai/thoughts/tickets/bifrost-console-pr-10-operational-views.md` — parent dependency (read-only operational views).
- `ai/thoughts/tickets/bifrost-console-pr-11-live-execution-experience.md` — the ticket being researched.
- `ai/thoughts/tickets/bifrost-console-pr-12-artifact-service.md` through `pr-17-mcp-runtime-inspection.md` — future consumers of the live-activity seam.
- `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md` — Phase 2 design document (live activity relay, recent-activity queries, live execution presentation).
- `ai/thoughts/phases/bifrost_console_workflows.md` — developer workflow design companion.
- `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md` — roadmap dependency chain.
- `ai/thoughts/framework-feature-design-lens.md` — contract classification guidance.

## Open Questions

1. Where in `bifrost-console` will the upstream SSE client live (`internal/observability` or a new package), and how will it share the `target.Scope` credential/scope context without duplicating the `Scope.Upstream` pattern? `applicationclient.Client.Get` performs one-shot GETs with `Accept: application/json`; no `text/event-stream` consumer exists, and the `Address` type has no `ActivityEndpoint()` method.
2. How will the Go side maintain one bounded recent-activity window, expose it as a query seam to the browser and (later) MCP, and clear it on `STALE_CURSOR`, changed `instanceId`, or target-scope rotation? The `target.Context.RegisterOwner` / `ScopeOwner` mechanism exists but no live-activity service is currently registered to receive `InvalidateTargetScope` calls.
3. What is the browser-side model for the live activity stream — a new provider/context alongside `ObservabilityProvider`, or an extension of the existing reducer? Where will `EventSource` lifecycle, reconnect, and tab backpressure be managed? No `ActivityEnvelope`/`ActivityHandshake` TypeScript type, activity reducer state, or SSE consumer exists today; `client.ts` exposes only the POST-and-JSON `post<T>` helper.
4. How will the active-execution detail view merge the initial snapshot (`getActiveExecutionDetail`) with subsequent live activity for the same `sessionId` without losing selection or combining events from opposite sides of a continuity boundary?
5. Which new browser API endpoints will be needed (for example a subscribe seam and a recent-activity query seam), and how will they fit into the existing `browserapi` router and session/CSRF policy? `browserapi.Router` currently has no `/api/console/v1/activity/*` routes.
6. The Phase 2 design document (lines 434–436, 442–470) describes a bounded recent-activity window with single-continuity-interval semantics, replay-gap signals, and periodic baseline refresh. No Go implementation of this window exists in the current codebase.
7. The Phase 2 design document (lines 597–689) describes a live execution presentation with current summary, active skill path, recent narrative, following/pause behavior, continuity notices, and terminal transition. No React implementation of these UI components exists beyond the static snapshot detail view in `ActiveExecutionDetail.tsx`.
