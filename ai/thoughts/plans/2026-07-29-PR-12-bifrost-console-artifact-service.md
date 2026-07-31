# PR 12 Central Artifact Acquisition and Trace Storage Implementation Plan

## Overview

Implement one process-local, target-scope-bound Go artifact service that acquires
each selected finalized trace at most once, installs it atomically beneath the
verified Console workspace, and owns its opaque handle, capacity charge, idle
lifetime, active-use pinning, and removal. Expose that shared lifecycle through
the paired browser's trace detail and Trace Storage experiences, while keeping
unchanged raw artifact download as a separate authenticated upstream
pass-through that never creates or uses a local analysis copy.

This plan also atomically corrects the delivered PR 11 SSE availability field to
the settled `applicationTraceAvailability` spelling. The live Java producer and
the Phase 2 design already use that name; only the committed fixture producer,
fixtures, and Go/React consumers still use `artifactAvailability`.

## Current State Analysis

The Java adapter already publishes current-process trace metadata and streams a
finalized NDJSON artifact byte-for-byte. The artifact endpoint requires the
operator key, rejects request-shape variants, provides the application instance
header, `Content-Length`, `application/x-ndjson`, a safe attachment filename,
and `Cache-Control: no-store`
(`ObservabilityRestController.java:251-290,380-424`;
`ObservabilityArtifactIntegrationTest.java:48-68`). No new Java artifact route
is needed for raw download.

The Go Console can list and retrieve trace metadata but has no streaming target
client method, artifact owner, handle, capacity accounting, or artifact browser
route (`bifrost-console/internal/observability/service.go:151-202`;
`bifrost-console/internal/applicationclient/address.go:146-149`). Its current
target client buffers bounded JSON responses in memory
(`bifrost-console/internal/applicationclient/client.go:91-145`), so it cannot be
reused for large artifact acquisition or raw pass-through.

The workspace already establishes the correct security and disposal boundary:
it exclusively owns a verified `transient/` directory, removes prior-process
contents before serving, checks its path/lock/I/O invariants, and classifies
artifact cleanup failures as request-scoped or process-fatal
(`bifrost-console/internal/workspace/workspace.go:1-213`;
`bifrost-console/internal/workspace/artifact_failure.go:1-39`). Shutdown already
cleans the whole transient subtree
(`bifrost-console/internal/console/service.go:190-204`).

The restart-only `trace-workspace.max-bytes` and
`trace-workspace.idle-ttl` configuration contract, including `unlimited` and
`never`, is already parsed, validated, documented, and available through
`profile.Resolved`; it is not yet consumed
(`bifrost-console/internal/config/config.go:5-74`;
`bifrost-console/internal/config/values.go:29-36,169-218`).

The browser API currently admits only POST requests and only list/detail trace
operations (`bifrost-console/internal/browserapi/router.go:51-96`). The React
trace detail is metadata-only, and the live page's Inspect link relies on
`artifactAvailability` (`bifrost-console/web/src/observability/TraceDetail.tsx:12-78`;
`ActiveExecutionDetail.tsx:44-49,141-147`).

## Desired End State

After PR 12:

1. A current target scope has at most one complete installed copy and one opaque
   handle for a trace. Browser calls and future MCP calls use the same service.
2. Concurrent acquisition callers join one transfer. Cancelling one waiter does
   not cancel another; cancelling the final waiter abandons and cleans the
   partial transfer.
3. The service publishes a handle only after the response identity and metadata
   are verified, the complete stream has been written and synced, the exact
   byte count has been checked, and a same-workspace atomic rename succeeds.
4. Complete entries retain their acquisition-time trace metadata and remain
   usable after upstream authentication failure or application expiry, until
   their own target scope, handle, idle, capacity, removal, shutdown, or process
   lifetime ends.
5. Configured aggregate capacity accounts for every partial reservation and
   complete artifact byte. Expired unused entries are removed first, followed
   by least-recently-successfully-used entries. Active leases are never evicted.
6. Idle expiry is based on the last successful handle use. `unlimited` disables
   aggregate-capacity eviction and `never` disables idle expiry, but neither
   changes scope rotation, shutdown, or restart cleanup.
7. Raw download authenticates the paired browser and the current application
   acquisition independently, streams the upstream bytes directly to a
   developer-selected attachment, and never installs, handles, pins, or charges
   those bytes.
8. Trace catalog/detail DTOs distinguish the last observed application
   availability from current local-handle availability. The Trace Storage page
   displays cache facts and removes only unused entries.
9. Java, Go, React, and SSE fixtures consistently use
   `applicationTraceAvailability`; no alias or dual-reader remains.

Verification consists of focused deterministic lifecycle/concurrency tests,
Java-produced boundary fixtures consumed by Go, browser API streaming tests,
React interaction/accessibility tests, Playwright workflow coverage, the
canonical Console verification pipeline, and the focused Java adapter tests
listed below.

### Key Discoveries

- The existing Java artifact route already has the exact byte-preserving,
  authenticated source needed by both acquisition and raw pass-through; adding
  a second Java “raw” route would duplicate one supported behavior
  (`ObservabilityArtifactIntegrationTest.java:48-68,97-118`).
- Target scope owners are synchronously invalidated after the old scope context
  is cancelled, providing the lifecycle hook the artifact service needs
  (`bifrost-console/internal/target/context.go:479-495`).
- The settled Phase 2 cache has active-query pinning only. It explicitly excludes
  permanent pinning and never-evict flags
  (`bifrost_console_phase_2_ui_console.md:905-924`).
- PR 13 consumes an acquired handle to parse/index current-release NDJSON, and
  PR 14 consumes PR 12's distinct raw pass-through. PR 12 must therefore expose
  an internal lease/open seam without parsing NDJSON or building browser trace
  views.
- The live Java producer and design use `applicationTraceAvailability`, while
  fixture and console consumers use `artifactAvailability`. Because activity
  is a current-release ephemeral diagnostic boundary and Java/Go ship
  atomically, the coherent treatment is an atomic rename with no compatibility
  alias.

## What We're NOT Doing

- Parsing NDJSON records, validating trace semantics, reconstructing payloads,
  building indexes, or calculating hierarchy/timing/usage/failure facts; those
  belong to PR 13.
- Building the trace explorer, raw record/payload views, or full raw-download
  confirmation workflow; PR 14 consumes the service and pass-through created
  here.
- Adding MCP routes or authentication. The transport-neutral artifact service
  is deliberately ready for PR 18, but PR 12 exposes browser adapters only.
- Adding permanent user pinning, per-browser ownership, reserved adapter
  capacity, a count limit, a per-trace configured limit, or a never-evict flag.
- Persisting/adopting cache metadata, recovering prior-process files, building
  trace history, or providing durable/cross-version handles.
- Serving raw downloads from the local analysis copy. Raw download always
  performs a new authorized upstream pass-through.
- Changing the Java trace writer, NDJSON record contract, retention policy, or
  existing Java artifact route.
- Adding a second protocol/schema version, OpenAPI, code generation, legacy SSE
  field reader, or migration shim.

## Skill-Authoring Documentation Impact

**Impact**: No impact

- **Rationale**: This work changes Console-local acquisition, storage, and
  browser diagnostics plus one internal current-release activity field spelling.
  It does not change skill manifests, validation, execution/planning semantics,
  evidence contracts, attachments supplied to skills, quotas, or the meaning of
  trace records that skill authors use. The existing author guidance already
  classifies traces as current-run diagnostics.
- **Documents to update**: None under `ai/skill-authoring/`.
- **Supporting evidence**:
  `ai/skill-authoring/traces-and-debugging.md`, the unchanged Java NDJSON fixture
  corpus in `bifrost-console-fixtures/traces/`, and
  `ObservabilityArtifactIntegrationTest`.
- **Coverage table update**: Not required. No topic boundary, author-facing
  behavior, or confidence level changes.
- **LLM-first usability**: Not applicable.

## Contract and Compatibility Impact

| Surface | Classification and supporting evidence | Planned compatibility treatment |
| --- | --- | --- |
| Application API | No Java application-developer API changes. The authenticated observability REST/SSE adapter is a supported application-to-Console boundary evidenced by Phase 1/2 design, executable fixtures, and the Go consumer. | Preserve the artifact endpoint. Atomically correct the consumed SSE field as the intentional pre-1.0 boundary change below. |
| Supported SPI | No supported Java or Go extension point is added or changed. `target.ProbeClient` is an internal test/runtime seam, not a documented SPI. | No compatibility machinery. Update internal implementations and fakes atomically. |
| Configuration and manifest contracts | `trace-workspace.max-bytes`, `trace-workspace.idle-ttl`, defaults `4GiB`/`4h`, and sentinels `unlimited`/`never` are documented and tested configuration contracts. No manifest contract changes. | Preserve spelling, parsing, validation, and defaults; begin enforcing the already documented behavior. |
| Persisted or serialized contracts | No durable artifact store is created. Workspace layout and opaque handles are process-local and disposable; startup deletes rather than adopts them. Browser DTOs are same-executable, same-release serialized contracts. | Do not promise workspace/layout or handle persistence. Update Go/TypeScript DTOs and fixtures atomically. |
| Ephemeral diagnostic formats | NDJSON bytes and semantics remain unchanged. SSE terminal activity changes fixture/consumer spelling from `artifactAvailability` to the live-producer/design spelling `applicationTraceAvailability`. | Intentional atomic current-release break: update Java fixture production, committed SSE fixtures, Go tests/consumers, React code/tests, and Playwright fixtures together. No dual read. |
| Internal or accidentally exposed implementation | Go target streaming, artifact registry, filesystem layout, leases, browser handlers, and service wiring are new internal implementation. `ProbeClient` gains streaming capability. | Keep types under `internal/`, expose no paths, and replace/update all in-repository fakes and callers atomically. |

- **Evidence of supported contracts**: Phase 1/2 design and roadmap decisions;
  `ObservabilityArtifactIntegrationTest`; Java-produced snapshot/problem/SSE/NDJSON
  fixtures; `bifrost-console/README.md`; config tests; and verified Java/Go/React
  consumers.
- **Intended breaks**: Rename the consumed terminal-activity detail field
  `artifactAvailability` to `applicationTraceAvailability`. The protected
  current-release consumers all live in this repository and are updated in one
  change. No external compatibility period is justified by the pre-1.0,
  exact-release agreement.
- **In-repository consumers to update**: `ConsoleSseFixtureCorpusTest`, the two
  application SSE fixtures, Go console/live integration fixtures and tests,
  React activity presentation/reducer/detail code and tests, and Playwright SSE
  fixtures.
- **Public-surface delta**: No public Java type, constructor, Spring bean, or
  extension point changes. New same-release browser routes and DTO fields are
  added; new Go types remain beneath `bifrost-console/internal/`.
- **Shim decision**: **No shim.** The old field is not a separately versioned or
  protected cross-release contract, exact Java/Go release matching is mandatory,
  and all consumers can be changed atomically.
- **Java-to-Go boundary coordination**: **Required.** Preserve the existing Java
  artifact response and verify its headers/bytes through Go streaming contract
  tests. Ship the SSE field correction across Java producer fixtures, committed
  fixtures, Go consumers, React consumers, and semantic tests together. The
  umbrella `consoleCompatibilityVersion` remains the complete product release
  string; no independent marker changes.

## Implementation Approach

Create `bifrost-console/internal/artifact` as the sole owner of analysis
artifact state. A service entry is keyed internally by `(targetScopeId,
traceId)` and contains an opaque random handle, immutable acquisition metadata,
installed file ownership, local byte accounting, last successful use, pin
count, and either acquiring/installed/removing state. Handles are lookup keys,
not encodings of scope, trace ID, or path.

Acquisition uses a leader/waiter record. The first caller starts one
service-owned transfer tied to the target scope and service lifetime. Other
callers wait on the same result. Each waiter can cancel independently; the
leader is cancelled only by scope/service cancellation or when no waiter
remains. A successful already-installed lookup returns the same handle without
an upstream call.

The target client gains a streaming artifact operation separate from bounded
JSON `Get`. It uses the existing no-proxy/no-redirect/identity-encoding
transport, application credential, exact artifact Accept type, response-header
timeout, and current scope cancellation. It validates status/problem mapping,
one valid instance header, media type, encoding, and bounded response headers,
then hands a closeable stream plus declared length to the service. The service
compares the downloaded byte count with both declared length (when present) and
authoritative trace metadata.

Files are created with owner-only permissions beneath a service-owned directory
under `transient/`. A partial name is random and never exposed. Capacity is
reserved before a known-size copy and incrementally for any otherwise valid
unknown-length response. Each admission pass removes expired unpinned entries,
then LRU unpinned entries, before rejecting with `LIMIT_EXCEEDED`. The service
syncs/closes the file, verifies the final size and current target scope, and
renames it atomically to its installed location before publishing the handle.

`Use(handle)` returns an internal lease suitable for PR 13's streaming/parser
work. The lease pins the entry until closed; only a successful close refreshes
`lastUsedAt`. Expiration/removal during a lease marks the entry for deletion
after the final pin rather than deleting in-flight evidence. An earliest-expiry
timer avoids polling and is injectable with the clock for deterministic tests.

Raw download bypasses the artifact service. A narrowly scoped same-origin GET
route is required so browser navigation can stream to the download manager
without buffering the file into a JavaScript Blob. It validates Host, the
strict same-site browser session cookie, exact path shape, and safe fetch
metadata/origin conditions; it needs no CSRF because it is read-only and
creates no Console state. Cross-site navigation lacks the `SameSite=Strict`
cookie. The handler captures the current target scope, opens a fresh upstream
artifact stream, writes fixed safe response headers/filename derived from the
validated trace ID, and propagates disconnect/scope cancellation. It never
forwards upstream `Content-Disposition` verbatim and never exposes a local path.

## Phase 1: Normalize the Boundary and Add Streaming Target Access

### Overview

Make the existing Java artifact endpoint consumable as a stream and remove the
terminal-activity field discrepancy before artifact state depends on it.

### Changes Required

#### 1. Canonical terminal artifact-availability field

**Files**:

- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ConsoleSseFixtureCorpusTest.java`
- `bifrost-console-fixtures/application-sse/activity-trace-completed.sse`
- `bifrost-console-fixtures/application-sse/activity-core-finalization-failed.sse`
- `bifrost-console/internal/console/activity_integration_test.go`
- `bifrost-console/internal/live/service_test.go`
- `bifrost-console/web/src/activity/activityPresentation.ts`
- `bifrost-console/web/src/activity/activityPresentation.test.ts`
- `bifrost-console/web/src/activity/reducer.test.ts`
- `bifrost-console/web/src/observability/ActiveExecutionDetail.tsx`
- `bifrost-console/web/src/observability/ActiveExecutionDetail.test.tsx`
- `bifrost-console/web/e2e/activity-stream.spec.ts`
- `bifrost-console/web/e2e/live-executions.spec.ts`

**Changes**:

- Generate and consume only `applicationTraceAvailability`.
- Preserve the existing values (`AVAILABLE`, `UNAVAILABLE`, and the exceptional
  `CORE_FINALIZATION_FAILED` reason where applicable) and the separate
  `applicationTraceExpiresAt`.
- Search the whole repository and remove all production/test uses of the
  obsolete field; do not add an alias or fallback.

#### 2. Artifact endpoint address and streaming application client

**Files**:

- `bifrost-console/internal/applicationclient/address.go`
- `bifrost-console/internal/applicationclient/address_test.go`
- `bifrost-console/internal/applicationclient/client.go`
- `bifrost-console/internal/applicationclient/client_test.go`
- `bifrost-console/internal/applicationclient/get_test.go`
- `bifrost-console/internal/applicationclient/errors.go`
- `bifrost-console/internal/target/context.go`
- `bifrost-console/internal/target/context_test.go`
- `bifrost-console/internal/target/scope.go`
- affected `ProbeClient` fakes in `bifrost-console/internal/**/_test.go`

**Changes**:

- Add an escaped `ArtifactEndpoint(traceId)` builder.
- Add a streaming response type with body, validated application instance ID,
  media type, optional declared length, and an idempotent close/cancel method.
- Add `Client.OpenArtifact` and `Scope.OpenArtifact`; keep JSON `Get` unchanged.
- Apply the same application credential, direct transport, redirect rejection,
  compression rejection, problem parsing, identity validation, cancellation,
  and target-mismatch revalidation used by other target operations.
- Do not buffer successful artifact bytes. Bound only problem bodies and
  response headers. Require the response to remain compatible with the existing
  Java artifact contract.
- Extend the internal target-client interface and every fake atomically.

#### 3. Cross-boundary fixture coverage

**Files**:

- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityArtifactIntegrationTest.java`
- `bifrost-console/internal/applicationclient/client_test.go`
- `bifrost-console/internal/console/observability_integration_test.go`

**Changes**:

- Retain Java byte-exact header tests for valid and malformed fixture bytes.
- Add Go streaming tests for exact bytes, instance mismatch, missing/duplicate
  length or identity headers, wrong media/encoding, redirect, bounded problem
  body, cancellation, and mid-stream failure.
- Use Java-produced fixture bytes rather than a competing Go artifact format.

### Success Criteria

#### Automated Verification

- [x] Java artifact and fixture tests pass:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=ObservabilityArtifactIntegrationTest,ConsoleSseFixtureCorpusTest,DefaultExecutionObservationHandleTest test`
- [x] Target client and scope tests pass:
  `go test ./internal/applicationclient ./internal/target` from `bifrost-console/`
- [x] Repository search finds no remaining `artifactAvailability` field:
  `rg "artifactAvailability" bifrost-spring-boot-starter bifrost-console bifrost-console-fixtures`
  returns no matches.
- [x] Go tests demonstrate successful artifact bodies are streamed rather than
  read into a single byte slice.

#### Manual Verification

- [ ] A real Java adapter response downloads through the new Go streaming seam
  with the exact fixture byte count and no application key or path in logs.
- [ ] A live completion from the Java runtime enables the existing Inspect trace
  affordance using `applicationTraceAvailability`.

---

## Phase 2: Build the Central Artifact Lifecycle Service

### Overview

Implement the shared immutable installation, handle, joining, lease, capacity,
TTL, eviction, and invalidation owner beneath browser and future MCP adapters.

### Changes Required

#### 1. Artifact domain model and opaque handles

**Files**:

- `bifrost-console/internal/artifact/service.go` (new)
- `bifrost-console/internal/artifact/model.go` (new)
- `bifrost-console/internal/artifact/handle.go` (new)
- `bifrost-console/internal/artifact/service_test.go` (new)

**Changes**:

- Define immutable acquired metadata: scope/trace/session identity, original
  outcome/finalization/size/retention facts, acquisition time, last successful
  use, application expiry/last-observed availability, local bytes, and handle.
- Generate handles from cryptographically random process-local values. Never
  derive or serialize a filesystem path.
- Key uniqueness by scope plus trace, but resolve client calls by handle and
  verify its current scope.
- Define explicit acquiring, installed, deferred-removal, and removed
  transitions. Validate the caller's `targetScopeId` first so an old scope
  returns `TARGET_CHANGED`; within the current scope, return `INVALID_ARGUMENT`
  for a malformed handle and `ARTIFACT_EXPIRED` for any well-formed handle that
  is not installed. This avoids an unbounded removed-handle tombstone set and
  does not reveal whether a random opaque value was previously issued.

#### 2. Joined acquisition and atomic installation

**Files**:

- `bifrost-console/internal/artifact/acquire.go` (new)
- `bifrost-console/internal/artifact/storage.go` (new)
- `bifrost-console/internal/artifact/acquire_test.go` (new)
- `bifrost-console/internal/artifact/storage_test.go` (new)

**Changes**:

- Accept a trusted current-scope trace loader and artifact stream opener rather
  than browser-supplied size/path metadata.
- Join callers for the same `(scope, trace)` into one metadata load, one
  application stream, and one installed file.
- Track waiters independently. Caller cancellation releases only that waiter;
  cancel and clean the leader when the last waiter leaves.
- Create protected random partial files under the verified transient subtree.
  Stream with a fixed-size buffer, update reservations/accounting safely, and
  propagate caller/service/scope cancellation.
- Validate complete EOF, declared and observed size agreement, trace identity,
  current scope, sync, close, and same-filesystem rename before publishing.
- On transport, cancellation, invalidity, disk-full, or rename failure, publish
  no handle, remove partial state, release its reservation, and run
  `Workspace.ClassifyArtifactFailure`. Send a resulting fatal error to the
  lifecycle coordinator; otherwise return the bounded request-scoped error.
- Map complete-but-invalid acquisition to `INVALID_ARTIFACT` with
  `rawDownloadAvailable` based on the last observed application availability.
  Do not parse NDJSON records in this PR. A PR 12 handle proves a complete,
  immutable, current-scope installation, not semantic validity as analysis
  evidence; PR 13 must successfully validate it before exposing trace queries.

#### 3. Capacity, TTL, leases, and removal

**Files**:

- `bifrost-console/internal/artifact/capacity.go` (new)
- `bifrost-console/internal/artifact/lease.go` (new)
- `bifrost-console/internal/artifact/expiry.go` (new)
- `bifrost-console/internal/artifact/capacity_test.go` (new)
- `bifrost-console/internal/artifact/expiry_test.go` (new)

**Changes**:

- Consume resolved `MaxBytes`/`Unlimited` and `IdleTTL`/`NeverExpire` without
  changing their config syntax/defaults.
- Charge partial reservations and complete artifact bytes exactly once. Keep the
  accounting API in this owner so PR 13 can add derived-file charges without a
  second cache policy.
- Before capacity failure, remove expired unpinned entries, then unpinned
  entries ordered by oldest successful use with deterministic acquisition/handle
  tie-breakers. Never evict an acquisition or active lease.
- Return `LIMIT_EXCEEDED` with `limitName: trace-workspace.max-bytes` and the
  configured finite `limitValue`. In unlimited mode, translate disk-full/write
  failure to `LOCAL_STORAGE_UNAVAILABLE`, not configured capacity failure.
- Implement internal handle leases for downstream analysis. A lease increments
  the pin count and exposes read access without exposing a path through any DTO.
  Successful completion refreshes last use; cancellation/error does not.
- Schedule the exact earliest idle deadline using an injectable clock/timer.
  If a deadline occurs while pinned, defer deletion until the last lease closes.
- Implement remove selected unused, clear expired, and clear all unused. Reject
  in-use removal with a new shared `ARTIFACT_IN_USE` domain code and without
  force-cancelling its lease.

#### 4. Target scope and process lifecycle wiring

**Files**:

- `bifrost-console/internal/artifact/target_owner.go` (new)
- `bifrost-console/internal/console/service.go`
- `bifrost-console/internal/console/target_integration_test.go`
- `bifrost-console/internal/lifecycle/coordinator_test.go`

**Changes**:

- Register the artifact service as a target scope owner before
  `TargetContext.StartServing`.
- On scope invalidation, stop new old-scope work, cancel acquisitions/leases,
  wait for their bounded cleanup, invalidate all handles, remove scope content,
  and release all charges before returning.
- Close the service before workspace cleanup at shutdown. Cancel transfers,
  invalidate handles, close timers, wait for workers, and remove owned state.
- Preserve already installed current-scope entries when an upstream credential
  is rejected without scope rotation. Credential replacement and instance
  change continue to rotate and clear the scope.

### Success Criteria

#### Automated Verification

- [ ] Artifact package tests pass with race detection:
  `go test -race ./internal/artifact` from `bifrost-console/`.
  *(Note: race detector requires CGO/GCC, which is not available in the current
  Windows environment. Tests pass without -race.)*
- [x] Tests cover joined acquisition, one-waiter cancellation, final-waiter
  cancellation, exact installation, short/long stream, capacity reservation,
  deterministic LRU, exact TTL, active pin protection, deferred removal,
  explicit removal, scope rotation, authentication rejection after install,
  disk-full cleanup, fatal workspace loss, restart non-adoption, and shutdown.
- [x] Finite-capacity tests assert no transient byte is uncharged; unlimited
  tests assert configured `LIMIT_EXCEEDED` is never returned.
- [x] No handle/DTO/log contains the workspace root or installed path.
- [x] Console lifecycle tests pass:
  `go test ./internal/console ./internal/lifecycle ./internal/workspace`.

#### Manual Verification

- [ ] Two simultaneous browser-facing acquisitions of a large trace produce one
  upstream Java request and one installed file.
- [ ] Cancelling one request leaves another waiter successful; cancelling all
  callers leaves no partial file or capacity charge.
- [ ] Replacing the target or application key removes all prior-scope entries,
  while a simple upstream authentication rejection leaves complete local
  evidence intact.
- [ ] Workspace permissions and cleanup behavior remain correct on Windows,
  Linux, and macOS.

---

## Phase 3: Expose Browser Artifact Operations and Trace Storage

### Overview

Adapt the shared service to the paired browser, provide the separate raw
attachment stream, and make application and local availability visibly
independent.

### Changes Required

#### 1. Browser DTOs and JSON operations

**Files**:

- `bifrost-console/internal/browserapi/artifacts.go` (new)
- `bifrost-console/internal/browserapi/router.go`
- `bifrost-console/internal/browserapi/errors.go`
- `bifrost-console/internal/browserapi/contracts_test.go`
- `bifrost-console/internal/observability/dto.go`
- `bifrost-console/internal/observability/service.go`
- `bifrost-console/web/src/api/contracts.ts`
- `bifrost-console/web/src/api/client.ts`
- corresponding Go/TypeScript client tests

**Changes**:

- Add paired JSON operations for acquire, storage snapshot, remove selected
  unused, clear expired, and clear all unused. State-changing removals require
  the existing session/tab/CSRF protection.
- Enrich trace results with separately named application availability,
  application observation/expiry facts, local availability, and optional opaque
  `artifactHandle`. Never return a path.
- When upstream refresh fails but a valid local entry exists, return its original
  acquisition observation clearly labeled as such; do not claim current
  application reachability or authorization.
- Map shared domain errors consistently, including `ARTIFACT_EXPIRED`,
  `ARTIFACT_IN_USE`, `INVALID_ARTIFACT`, `LIMIT_EXCEEDED`,
  `LOCAL_STORAGE_UNAVAILABLE`, and `TARGET_CHANGED`. Add
  `ARTIFACT_IN_USE` atomically to Go and TypeScript code sets.
- Keep storage snapshot/list operations side-effect-free: viewing Trace Storage
  does not refresh artifact last-use time.

#### 2. Streaming raw attachment route

**Files**:

- `bifrost-console/internal/browserapi/artifact_download.go` (new)
- `bifrost-console/internal/browserapi/router.go`
- `bifrost-console/internal/browserapi/request_policy.go`
- `bifrost-console/internal/browserapi/security_integration_test.go`
- `bifrost-console/internal/browserapi/artifact_download_test.go` (new)

**Changes**:

- Add one exact GET download route with a path-escaped trace ID. Keep all other
  browser API routes POST-only.
- Require the valid `SameSite=Strict` paired session and exact Host. Accept only
  a same-origin navigation/download request shape, rejecting query parameters,
  ranges, conditional requests, cross-site fetch metadata, and ambiguous IDs.
- Capture one current target scope and open a fresh authenticated upstream
  artifact stream. Never consult or mutate the local artifact cache.
- Emit `application/x-ndjson`, `nosniff`, `no-store`, and a fixed safe attachment
  filename generated from a sanitized trace identifier. Do not forward an
  upstream filename/path.
- Stream with a fixed buffer and flush normally; propagate browser disconnect,
  target rotation, upstream cancellation, and short-read failures. Do not create
  a JavaScript Blob or a temporary Go file.
- If failure occurs before response commit, return the shared bounded error. If
  it occurs after commit, terminate the stream without appending an error body.

#### 3. Trace detail and Trace Storage UI

**Files**:

- `bifrost-console/web/src/app/App.tsx`
- `bifrost-console/web/src/app/routes.tsx`
- `bifrost-console/web/src/observability/TraceDetail.tsx`
- `bifrost-console/web/src/observability/TraceStorage.tsx` (new)
- `bifrost-console/web/src/observability/TraceStorage.test.tsx` (new)
- `bifrost-console/web/src/observability/ActiveExecutionDetail.tsx`
- related component tests and styles

**Changes**:

- Add Trace Storage navigation and route.
- Show resolved workspace path, finite maximum or Unlimited, aggregate charged
  bytes, finite idle TTL or Never, acquired count, and per-entry trace ID,
  acquired/last-use/expiry times, local bytes, application availability,
  local-handle availability, and active query pin state.
- Provide accessible selection plus remove selected unused, clear expired, and
  clear all unused confirmations. Disable removal for active pins; do not offer
  force cancellation or permanent pin controls.
- Add deliberate Acquire for analysis/Inspect action to trace detail and the
  completed-execution path. Show progress, joined completion, local handle
  availability, capacity/expiry errors, and the fact that semantic explorer
  views arrive in PR 13/14 rather than rendering raw NDJSON here.
- Add a separate Raw artifact download attachment action that uses normal
  browser navigation to the streaming GET route and explains that it does not
  install or extend a local analysis copy.
- Preserve keyboard access, focus management, text-only untrusted data,
  forced-colors, zoom, and responsive behavior.

#### 4. Console documentation

**Files**:

- `bifrost-console/README.md`

**Changes**:

- Replace the current configuration-only description with executable cache
  behavior: aggregate charging, TTL origin, `unlimited`/`never`, LRU order,
  active-use pinning, removal, scope rotation, shutdown, and restart cleanup.
- Document the raw-download lifecycle and its separation from analysis
  acquisition.
- State that handles are process/scope-local and paths are never protocol IDs.

### Success Criteria

#### Automated Verification

- [ ] Browser API tests cover authentication, CSRF on mutations, Host/origin/fetch
  metadata, exact method/path/query shapes, domain mappings, and no-store
  headers: `go test ./internal/browserapi ./internal/console`.
- [ ] Raw pass-through tests prove exact bytes, bounded error bodies, safe
  attachment headers, no cache/accounting change, cancellation, and target
  rotation.
- [ ] Frontend typecheck and component tests pass:
  `npm run typecheck && npm test` from `bifrost-console/web/`.
- [ ] Component tests cover acquisition states, separate availability facts,
  active-pin removal disablement, confirmations, error recovery, focus, and
  accessible names.
- [ ] Browser DTO contract fixtures contain opaque handles and no local paths.

#### Manual Verification

- [ ] Trace Storage remains usable at 200% zoom and by keyboard in light, dark,
  forced-colors, and reduced-motion modes.
- [ ] Acquiring from a completed execution updates Trace Storage without
  conflating execution outcome, application availability, and local availability.
- [ ] Raw download opens the browser's attachment flow, preserves exact bytes,
  does not change storage totals, and remains available when semantic
  acquisition rejects the content but the application still serves it.
- [ ] Removing an unused trace invalidates its handle but never deletes the
  application's canonical artifact or a browser-downloaded file.

---

## Phase 4: End-to-End Lifecycle, Failure, and Workflow Verification

### Overview

Prove the complete Java-to-browser path and the failure/lifecycle invariants
that make the service safe for PR 13 and future MCP reuse.

### Changes Required

#### 1. Console integration and cross-boundary tests

**Files**:

- `bifrost-console/internal/console/artifact_integration_test.go` (new)
- `bifrost-console/internal/console/target_integration_test.go`
- `bifrost-console/internal/console/security_integration_test.go`
- `bifrost-console-fixtures/` Java-produced artifact/problem/SSE fixtures

**Changes**:

- Run acquisition and raw download against a representative Java-compatible
  server fixture with exact instance/compatibility/authentication behavior.
- Cover target-scope rotation during metadata fetch, streaming, installation,
  lease use, and raw pass-through.
- Assert installed evidence remains available after authentication rejection,
  but a new acquisition/raw pass-through fails until credentials are replaced.
- Assert restart cleanup never adopts a prior handle/file and shutdown waits for
  stream cleanup without leaking goroutines or open files.

#### 2. Browser workflow coverage

**Files**:

- `bifrost-console/web/e2e/artifact-storage.spec.ts` (new)
- `bifrost-console/web/e2e/live-executions.spec.ts`
- `bifrost-console/web/e2e/target-context.spec.ts`

**Changes**:

- Cover the approved failed-completed-execution flow through deliberate
  acquisition and Trace Storage.
- Cover joined clicks/tabs, cancellation, finite capacity, TTL expiry, active
  pin display, removal confirmation, target rotation, raw attachment, malformed
  artifact, and unavailable application.
- Assert stale local handles return `ARTIFACT_EXPIRED` and stale scopes return
  `TARGET_CHANGED`.

#### 3. Production pipeline and race/leak verification

**Files**:

- Existing Console build pipeline tests and CI configuration only if required
  to include new fixtures/specs.

**Changes**:

- Keep all new code in the canonical clean frontend/Go build.
- Run race-sensitive service/lifecycle suites separately where the production
  builder does not enable `-race`.
- Verify no partial files, reserved bytes, timers, response bodies, goroutines,
  or leases remain after each terminal path.

### Success Criteria

#### Automated Verification

- [x] Focused Java suite passes:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=ObservabilityArtifactIntegrationTest,ObservabilityArtifactDeliveryTest,ConsoleSseFixtureCorpusTest,DefaultExecutionObservationHandleTest test`
- [x] All Go tests pass with race coverage for concurrency-sensitive packages:
  `go test ./...` and
  `go test -race ./internal/artifact ./internal/applicationclient ./internal/target ./internal/browserapi ./internal/console`
  from `bifrost-console/`.
- [x] Frontend E2E passes: `npm run test:e2e` from `bifrost-console/web/`.
- [x] Canonical production verification passes:
  `go run ./internal/buildtool verify` from `bifrost-console/`.
- [x] Fixture/contract tests prove Java, Go, and React agree on
  `applicationTraceAvailability` and the existing artifact headers/bytes.
- [x] Every representative workflow/e2e test references the failed-completed
  execution workflow or the most specific PR 12 acceptance requirement.

#### Manual Verification

- [ ] Run Console against the sample Java application and verify acquire,
  repeated acquire, Trace Storage, removal, application expiry, raw download,
  credential rejection/replacement, target change, and shutdown behavior.
- [ ] Verify large traces stream with bounded Go/browser memory and responsive
  cancellation.
- [ ] Force finite-capacity pressure and operating-system disk-full conditions;
  confirm ordinary artifact failure remains request-scoped when cleanup restores
  workspace safety and process-fatal behavior occurs only when the workspace
  invariant cannot be restored.
- [ ] Confirm browser and future transport-neutral callers can share the same
  handle/lease service without adapter-owned copies or policies.

## Testing Strategy

### Unit Tests

- Model state transitions and accounting as deterministic tests with injected
  handle source, clock, timer, filesystem failure hooks, and stream openers.
- Use barriers rather than sleeps for leader/waiter, pin/removal, scope rotation,
  and shutdown races.
- Test every admission/eviction tie-break and both finite/sentinel config modes.
- Test exact status/error detail mapping and ensure error text is bounded and
  contains no credential, upstream body, or local path.

### Integration Tests

- Consume Java-produced artifact and SSE fixtures from Go.
- Exercise real HTTP streaming through the application client and browser
  handler, including disconnects and post-commit failure.
- Exercise target owner invalidation and workspace fatality through the Console
  composition root.
- Exercise the paired browser and Trace Storage workflow with Playwright.

### Manual Testing Steps

1. Acquire one retained trace and confirm a single handle/copy/charge is reused.
2. Start two acquisitions, cancel one, and confirm the other completes.
3. Fill a finite cache, hold a lease, and confirm only eligible LRU entries are
   evicted; release the lease and confirm deferred expiry/removal.
4. Reject the application credential and verify local evidence remains while a
   new raw download/acquisition fails.
5. Download the raw artifact and compare its checksum with the Java source while
   confirming cache totals are unchanged.
6. Rotate target scope and restart Console; confirm old handles and files cannot
   be recovered.

**Note**: Run `ai/commands/3_testing_plan.md` before implementation to turn this
strategy into the focused failing-test order, fixture matrix, platform cases,
and exact exit criteria.

## Performance Considerations

- Never load a successful artifact or raw download wholly into Go or browser
  memory. Use fixed-size copy buffers and direct response streaming.
- Keep registry operations short under one mutex; perform network and filesystem
  I/O outside it using reserved state and revalidate before commit.
- Coalescing prevents duplicate upstream bandwidth, disk writes, and capacity
  charges for the same scope/trace.
- Maintain total charged bytes and eviction order incrementally rather than
  rescanning the workspace. The verified workspace is still checked after
  failures, not on every copied chunk.
- Use one rescheduled earliest-expiry timer instead of a goroutine/timer per
  artifact or a tight polling loop.
- Raw pass-through intentionally consumes upstream bandwidth each time and does
  not benefit from the analysis cache; this preserves its separate
  authorization and retention semantics.

## Migration Notes

There is no durable data migration. Console startup already deletes all prior
`transient/` content, and new handles are process- and scope-local.

The SSE availability spelling is an intentional atomic same-release migration.
Regenerate fixtures and update every in-repository consumer in this PR; do not
accept both names. Exact `consoleCompatibilityVersion` matching prevents a new
Console from partially consuming an older adapter contract.

Existing `trace-workspace` files need no edits. Their currently documented
values begin controlling real artifact storage when this PR ships. Rollback to
the prior Console simply returns to startup cleanup and ignores the settings;
no installed artifact is durable.

## References

- Original ticket:
  `ai/thoughts/tickets/bifrost-console-pr-12-artifact-service.md`
- Research:
  `ai/thoughts/research/2026-07-29-PR-12-bifrost-console-artifact-service.md`
- Phase 2 design:
  `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md`
- Roadmap:
  `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md`
- Framework design lens:
  `ai/thoughts/framework-feature-design-lens.md`
- Downstream parser/index consumer:
  `ai/thoughts/tickets/bifrost-console-pr-13-trace-analysis-services.md`
- Downstream trace explorer/raw-download consumer:
  `ai/thoughts/tickets/bifrost-console-pr-14-trace-explorer.md`
- Existing Java streaming implementation:
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestController.java:251-290,380-424`
- Existing Go lifecycle seams:
  `bifrost-console/internal/target/context.go:479-495`,
  `bifrost-console/internal/workspace/artifact_failure.go:1-39`, and
  `bifrost-console/internal/console/service.go:50-204`
