# PR 11 — Live Activity and Active-Execution Detail Implementation Plan

## Overview

Implement one target-scope-owned Go activity coordinator that consumes the
existing Java SSE stream, maintains one bounded single-continuity recent window
and active-execution baseline, and fans activity out to paired browser tabs
without allowing a tab to own or delay the upstream subscription. Extend the
React operational views with live collections and a selected execution
experience that preserves selection, makes freshness and gaps explicit, and
never presents the bounded projection as a complete trace.

## Current State Analysis

- Java already exposes the authenticated resumable stream at
  `/_bifrost/observability/v1/activity`, with handshake, ordered activity
  frames, bounded replay, stale-cursor signaling, and per-subscriber limits
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityRestController.java:149`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityActivityStream.java:48`).
- Go has bounded one-shot JSON access through `applicationclient.Client.Get`
  and `target.Scope.Upstream`, but neither exposes an SSE response body or
  activity endpoint (`bifrost-console/internal/applicationclient/client.go:91`,
  `bifrost-console/internal/target/scope.go:39`,
  `bifrost-console/internal/applicationclient/address.go:129`).
- `target.Context` owns scope identity, credential replacement, instance
  mismatch handling, cancellation, and `ScopeOwner` invalidation. It currently
  has no activation callback through which a long-lived scope service can start
  after identity is established (`bifrost-console/internal/target/context.go:30`,
  `bifrost-console/internal/target/context.go:95`,
  `bifrost-console/internal/target/context.go:451`).
- `observability.Service` exposes transport-neutral active list/detail methods,
  and the browser API atomically maps those methods into session-authenticated
  JSON routes (`bifrost-console/internal/observability/service.go:98`,
  `bifrost-console/internal/browserapi/observability.go:13`).
- React stores paginated operational snapshots in `ObservabilityProvider`, but
  the detail component performs a separate one-shot fetch. There are no
  activity contracts, continuity state, relay reader, or activity reducer
  actions (`bifrost-console/web/src/observability/ObservabilityProvider.tsx:46`,
  `bifrost-console/web/src/observability/ActiveExecutionDetail.tsx:12`,
  `bifrost-console/web/src/api/contracts.ts:137`).
- Current REST and SSE fixtures are the current-release Java-to-Go contract and
  must be reused rather than copied
  (`bifrost-console-fixtures/README.md`,
  `bifrost-console-fixtures/application-sse/replay.sse`).

## Desired End State

For every established target scope, Console starts exactly one upstream
activity connection independent of browser-tab presence. It establishes an
active-execution baseline, resumes from the baseline cursor, accepts validated
activity in cursor order, ignores exact duplicates, and retains a bounded
suffix from exactly one upstream continuity interval. Scope rotation, instance
change, or upstream `STALE_CURSOR` clears the interval before any new activity
is admitted and records a bounded reset fact.

Browser tabs obtain an authoritative baseline plus a race-free subscription to
Go's relay. Each tab has an independent cursor and bounded pending delivery. A
slow or reconnecting tab either replays the retained suffix or receives a local
gap notice and refreshes its own baseline; it cannot reset the shared interval
or delay other consumers. The transport-neutral recent query returns finite
cursor-ordered results and continuity metadata suitable for later use by
`bifrost_get_execution_activity` without a second upstream connection.

The Live Executions UI keeps active executions and recent completions visibly
separate. Selecting an execution opens a sticky current summary, bounded active
path, and accessible recent narrative. Updates do not reorder rows, steal
selection, or move scroll position while following is paused. Completion stays
selected in place and presents outcome, observation-ended, and application
trace availability as separate facts.

Verification requires focused Go and React tests for replay, duplicates,
upstream reset, tab-local gaps, reconnection, backpressure, missed completion,
finalization failure, scope change, accessibility announcements, reduced
motion, and selection stability, followed by the canonical Console verification
command.

### Key Discoveries

- The settled Phase 2 design requires the one upstream connection to be owned
  by `TargetContext`, not by browser tabs, and requires the same recent window
  below browser and MCP adapters
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:434`,
  `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:454`,
  `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:470`).
- Native `EventSource` cannot carry the existing
  `X-Bifrost-Console-Tab` header. The browser relay will therefore use a
  `fetch` response stream with SSE framing, preserving the paired cookie,
  origin checks, tab header, no-store policy, and POST-only browser API
  convention without placing credentials in URLs.
- The first active page already carries the `resumeCursor` needed to establish
  the snapshot/stream seam; the baseline is best effort rather than an atomic
  registry cut (`bifrost-console/internal/observability/dto.go:97`,
  `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:458`).
- Activity envelopes are sufficient for ordered narrative and prompt terminal
  reaction, but not for reconstructing the full authoritative summary.
  Therefore the coordinator owns periodic active-baseline refresh and the
  relay signals baseline refresh so the browser can replace summary state from
  snapshots instead of deriving usage/path totals solely from retained envelopes
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:462`,
  `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:629`).
- PR 17 explicitly consumes the shared current interval as an on-demand finite
  query and must not own a subscription
  (`ai/thoughts/tickets/bifrost-console-pr-17-mcp-runtime-inspection.md`).

## What We're NOT Doing

- Changing the Java activity projector, replay-buffer semantics, SSE frame
  format, or `consoleCompatibilityVersion`.
- Tailing an active trace file or reconstructing missing activity, projector
  state, a complete frame hierarchy, or a causal explanation.
- Persisting activity, retaining multiple continuity intervals, creating
  durable completion history, or adding configurable retention settings.
- Treating silence, repetition, or elapsed time as evidence of stuckness,
  slowness, outage, deadlock, importance, or cause.
- Acquiring or parsing trace artifacts. The visible Inspect trace transition is
  limited to availability and action affordance; PRs 12–15 implement acquisition
  and the completed diagnostic workflow.
- Adding the MCP adapter. PR 17 will adapt the transport-neutral query service
  created here.
- Adding a native `EventSource` client, credentials/cursors in URLs, a
  browser-owned upstream connection, or adapter-specific activity stores.

## Skill-Authoring Documentation Impact

**Impact**: No impact

- **Rationale**: This PR changes only the internal Console transport,
  current-run activity retention, and browser presentation. It does not change
  skill manifest syntax, validation, mappings, planning or execution semantics,
  evidence contracts, visibility/RBAC, model selection, limits, trace
  production, or testing guidance that a skill author must follow.
- **Documents to update**: None.
- **Supporting evidence**: Existing Java activity projection and active
  snapshots remain unchanged; the Go implementation consumes the current
  `application-sse` and `application-rest` fixtures and adds Console-specific
  tests.
- **Coverage table update**: Not required because no authoring topic or
  confidence boundary changes.
- **LLM-first usability**: Not applicable.

## Contract and Compatibility Impact

| Surface | Classification and supporting evidence | Planned compatibility treatment |
| --- | --- | --- |
| Application API | No impact. No application-developer entry point changes. | Preserve. |
| Supported SPI | No impact. No Spring or documented extension point changes. | Preserve. |
| Configuration and manifest contracts | No `bifrost.*`, Console YAML, or skill-manifest field changes. Bounds and refresh/backoff values are internal constants in this PR. | Preserve; do not add configuration aliases or defaults. |
| Persisted or serialized contracts | No durable format. The Go window and browser state are process-memory-only. | No migration or historical reader. |
| Ephemeral diagnostic formats | Go begins consuming existing `ActivityHandshake`, `ActivityEnvelope`, SSE framing, active snapshots, and problem details. Local browser relay/query DTOs add explicit cursor range, observation time, reset facts, and `bifrost.activity`/`console.*` separation. | Keep the current Java writer, Go reader, shared fixtures, and embedded browser coherent. Reject malformed or contradictory input; never mix intervals. |
| Internal or accidentally exposed implementation | `applicationclient`, `target`, `observability`, `browserapi`, and React contracts/state gain internal lifecycle and relay seams. They are consumed only in-repository. | Update implementations, fakes, tests, routes, and assets atomically; no compatibility wrapper. |

- **Evidence of supported contracts**: The current-release Java-to-Go boundary
  is established by `ObservabilityApiPaths`, `ObservabilityDtos`,
  `consoleCompatibilityVersion`, and
  `bifrost-console-fixtures/application-{rest,sse}`. The browser API is embedded
  with the Go executable and is not independently versioned.
- **Intended breaks**: None to supported contracts. Internal Go test fakes and
  constructors will change atomically to support long-lived streaming and scope
  activation.
- **In-repository consumers to update**: Console construction, target client
  fakes, target lifecycle tests, observability service/DTO tests, browser API
  router/security/contract tests, React API/provider/reducer/components/tests,
  Vite proxy coverage, and embedded asset verification.
- **Public-surface delta**: No Java public type, constructor, Spring bean, or
  extension point changes. Go additions remain under `internal/`; TypeScript
  additions are private embedded-browser contracts.
- **Shim decision**: **No shim.** There is no protected old activity client,
  relay route, query seam, or browser state model. Update internal callers and
  fakes atomically.
- **Java-to-Go boundary coordination**: **Required.** The Java producer is
  unchanged, but the new Go SSE reader must be tested directly against the
  canonical `application-sse` and problem fixtures. Any discrepancy discovered
  during implementation must be fixed coherently in Java, Go, fixtures, and
  tests in the same PR; no tolerant dual parser or compatibility-marker change
  is planned.

## Resolved Design Decisions

1. **Package ownership**: extend `internal/observability` with a live
   coordinator/window rather than creating an adapter-owned package. It already
   owns active-execution DTO validation and is the service shared by browser and
   future MCP adapters.
2. **Scope access**: add a streaming operation to `applicationclient.Client`
   and a scope-aware wrapper to `target.Scope`; do not expose credentials or
   duplicate cancellation, instance-mismatch, and error mapping in
   observability code.
3. **Lifecycle**: extend the internal target owner contract with activation of
   an established `Scope`. Register the observability coordinator before
   `StartServing`; activation starts/replaces the one background worker and
   invalidation synchronously prevents further admission from the old scope.
4. **Bounds**: use code-owned dual limits for the recent ring (2,048 complete
   envelopes and 8 MiB), per-tab pending delivery (256 frames and 1 MiB), and
   recent-query page size (default 100, maximum 256). Count serialized envelope
   bytes, reject a single envelope that exceeds the existing upstream envelope
   maximum, and evict oldest complete envelopes only. These values are internal
   safety policy, not new YAML contracts.
5. **Recovery**: retry ordinary transport closure with capped exponential
   backoff and jitter while replay is possible; retry upstream
   `LIMIT_EXCEEDED` without rotating scope; on upstream `STALE_CURSOR` or
   instance mismatch, clear first, record the boundary, refresh the full
   baseline, and reconnect from the new first-page `resumeCursor`.
6. **Baseline cadence**: refresh active pages on initial activation, after a
   continuity reset, and every 30 seconds while live monitoring is connected.
   Tests use an injected clock/ticker. The coordinator emits a
   `console.baseline_refreshed` signal; each tab then replaces its list/detail
   through the existing authoritative snapshot APIs. The refresh reconciles
   missed starts/completions without claiming an atomic cut.
7. **Browser relay**: use authenticated `fetch` streaming from a POST SSE route,
   not native `EventSource`. A separate POST recent-query route exposes the
   reusable finite service. Subscribe under the same coordinator lock that
   snapshots replay state so no event can fall between replay and live fan-out.
8. **Frontend state**: extend `ObservabilityProvider` and its reducer for shared
   active baseline, recent completions, activity interval, and connection
   facts. Keep row selection, selected narrative item, follow/pause, and scroll
   position local to the live-execution route/component.
9. **Snapshot/activity merge**: activity is appended once by cursor and may
   update directly recorded concise fields and terminal state; authoritative
   path, usage, limits, counts, and complete summary are replaced by baseline or
   detail snapshots. A continuity token on every action prevents cross-boundary
   merging.

## Implementation Approach

Build the relay from the target boundary inward. First establish a strict SSE
transport and scoped stream lifecycle. Then implement the coordinator as a
single serialized owner of baseline, cursor, continuity, ring, and subscribers.
Expose that same owner through a finite recent query and bounded browser relay.
Finally adapt the existing React provider and views, keeping transport facts,
execution activity, and presentation-only follow state separate.

## Phase 1: Scoped Upstream SSE Transport

### Overview

Add a strict, cancellable activity stream reader that uses the current target
credential and identity rules without converting a long-lived response into a
one-shot body.

### Changes Required

#### 1. Activity endpoint and application client

**Files**:

- `bifrost-console/internal/applicationclient/address.go`
- `bifrost-console/internal/applicationclient/client.go`
- `bifrost-console/internal/applicationclient/activity.go` (new)
- `bifrost-console/internal/applicationclient/address_test.go`
- `bifrost-console/internal/applicationclient/activity_test.go` (new)

**Changes**:

- Add `ActivityEndpoint(instanceID, afterCursor)` using the exact
  `/_bifrost/observability/v1/activity` path and encoded required query
  parameters.
- Add `OpenActivity` that sends authenticated `GET`, `Accept:
  text/event-stream`, `Accept-Encoding: identity`, and no `Last-Event-ID`;
  applies connect/header timeouts without applying the finite one-shot request
  deadline to the established body; rejects redirects, compressed content,
  invalid content type, missing/multiple/malformed instance headers, and
  non-success problems using existing failure kinds.
- Return a closeable stream abstraction that parses only `handshake` and
  `activity` frames, enforces bounded line/frame/data sizes, rejects unknown or
  malformed framing, validates exactly one initial handshake, and exposes
  decoded bytes without silently skipping malformed records.
- Exercise the canonical SSE and problem fixtures, split frames/read chunks,
  cancellation, oversized input, duplicate fields, unexpected event names,
  handshake mismatch, and closure.

#### 2. Target-scoped stream wrapper and lifecycle activation

**Files**:

- `bifrost-console/internal/target/context.go`
- `bifrost-console/internal/target/scope.go`
- `bifrost-console/internal/target/context_test.go`
- `bifrost-console/internal/target/scope_test.go`

**Changes**:

- Extend the internal target client interface and test fakes with activity
  streaming.
- Add a `Scope.ActivityStream` wrapper that binds caller and scope
  cancellation, applies the captured credential, checks response/handshake
  instance identity, triggers the existing mismatch revalidation path, maps
  failures to shared domain errors, and requires the scope to remain current.
- Extend the registered-owner lifecycle with an activation callback containing
  the newly established scope. Call it only after successful identity
  commitment, outside target locks, and pair every activation with invalidation
  on replacement/rotation/shutdown. Reject registration after serving begins.
- Prove late activation/stream results cannot publish after rotation and owner
  callbacks cannot deadlock target locks.

### Success Criteria

#### Automated Verification

- [ ] Canonical handshake/replay fixtures parse in order:
  `cd bifrost-console && go test ./internal/applicationclient ./internal/target`
- [ ] Invalid SSE/protocol/problem/cancellation cases fail with the intended
  typed result.
- [ ] Race coverage passes for the new lifecycle:
  `cd bifrost-console && go test -race ./internal/applicationclient ./internal/target`

#### Manual Verification

- [ ] A Console connected to a live application opens one authenticated
  activity request with the expected instance ID and cursor.
- [ ] Replacing the target or credential closes the old request promptly.

---

## Phase 2: Continuous Activity Window and Active Baseline

### Overview

Create the adapter-neutral coordinator that owns the one upstream worker,
authoritative active baseline, single-continuity ring, reset facts, queries, and
bounded subscribers.

### Changes Required

#### 1. Activity and continuity domain contracts

**Files**:

- `bifrost-console/internal/observability/dto.go`
- `bifrost-console/internal/observability/activity.go` (new)
- `bifrost-console/internal/observability/activity_test.go` (new)
- `bifrost-console/internal/observability/dto_test.go`

**Changes**:

- Model and strictly validate the current Java handshake/activity fields,
  including decimal cursors, UUID instance identity, timestamps, execution
  identity, canonical sequence, kind, status, optional frame fields, bounded
  summary, and bounded untrusted details.
- Define `Continuity`, `ResetFact`, `RecentActivityRequest/Result`,
  `ConnectionFact`, and active-baseline snapshot types. Results include target
  scope, interval identity, first/last retained cursor, observed time, requested
  start availability, preceding reset cause/time/cursor, and complete ordered
  envelopes.
- Keep Go connection/reset events outside the application cursor sequence and
  enumerate explicit reset causes (`target_scope_changed`,
  `instance_changed`, `upstream_stale_cursor`, `shutdown`) and tab-local gap
  facts separately.

#### 2. Coordinator, ring, and subscriber behavior

**Files**:

- `bifrost-console/internal/observability/live_service.go` (new)
- `bifrost-console/internal/observability/live_service_test.go` (new)
- `bifrost-console/internal/observability/service.go`

**Changes**:

- Make the service a target lifecycle owner. On activation, load the first
  active page, capture its `resumeCursor`, start the stream from that cursor,
  and traverse the remaining high-water-bound pages while stream ingestion is
  active. Publish the completed best-effort baseline without treating it as an
  atomic registry/stream cut. Refresh the baseline every 30 seconds while
  connected.
- Maintain cursor order, ignore only byte/semantic-identical duplicate cursor
  delivery, reject conflicting duplicates or cursor regression as protocol
  failure, and append only complete validated envelopes.
- Enforce the ring's count and byte bounds with oldest-first eviction. Retain
  only current-interval reset metadata and never return items across an interval
  identity.
- Implement recent queries filtered by optional `sessionId` and `afterCursor`,
  with default/max page size, deterministic delivery-cursor order, a
  continuation cursor, observation time, and explicit `beginningUnavailable`
  / local-gap facts rather than `STALE_CURSOR`.
- Implement atomic replay-plus-subscribe and bounded nonblocking subscriber
  queues. Disconnect a lagging subscriber with a local-gap cause; never block
  the coordinator or upstream reader.
- Publish a bounded `baseline_refreshed` lifecycle signal after initial,
  periodic, and recovery baseline completion so adapters can reload
  authoritative list/detail snapshots without placing a whole catalog in an
  event frame.
- Treat ordinary EOF/transport errors as connection facts and reconnect with
  injected capped backoff. Preserve and retry `LIMIT_EXCEEDED`. Treat
  `LIVE_MONITORING_UNAVAILABLE` as a terminal live-unavailable state for the
  scope. Clear-before-recover on stale cursor or changed instance.
- Reconcile terminal activity into recent completions and remove completed
  sessions from the active baseline without fabricating a completed snapshot.
  Periodic baseline refresh heals missed transitions.

#### 3. Console construction and shutdown

**Files**:

- `bifrost-console/internal/console/service.go`
- `bifrost-console/internal/console/observability_integration_test.go`

**Changes**:

- Construct one observability service before `targetContext.StartServing`,
  register it as the lifecycle owner, inject it into the browser API, and close
  it during coordinated shutdown.
- Verify one upstream subscription regardless of tab count, startup with no
  target/credential, target activation, credential replacement, target
  rotation, and shutdown cleanup.

### Success Criteria

#### Automated Verification

- [ ] Window, query, duplicate, eviction, reset, reconnect, baseline, terminal,
  and subscriber tests pass:
  `cd bifrost-console && go test ./internal/observability ./internal/console`
- [ ] Concurrency ownership is race-free:
  `cd bifrost-console && go test -race ./internal/observability ./internal/console`
- [ ] Tests prove every successful query contains one interval only and one
  slow subscriber cannot block another consumer or upstream ingestion.

#### Manual Verification

- [ ] Starting Console before the application becomes available transitions
  from disconnected to a fresh live interval without stale activity.
- [ ] Restarting the application displays a new continuity boundary and no
  pre-restart narrative after the reset.

---

## Phase 3: Authenticated Browser Relay and Recent Query

### Overview

Expose the shared coordinator through the paired browser realm with a bounded
finite query and a fetch-streamed SSE relay.

### Changes Required

#### 1. Browser API contracts and routes

**Files**:

- `bifrost-console/internal/browserapi/router.go`
- `bifrost-console/internal/browserapi/activity.go` (new)
- `bifrost-console/internal/browserapi/activity_test.go` (new)
- `bifrost-console/internal/browserapi/contracts_test.go`
- `bifrost-console/internal/browserapi/security_integration_test.go`
- `bifrost-console/internal/browserauth/sessions.go`
- `bifrost-console/internal/browserauth/sessions_test.go`

**Changes**:

- Add read-only, session-authenticated POST routes:
  `/api/console/v1/activity/recent` and
  `/api/console/v1/activity/stream`.
- Require the paired cookie, exact Host/Origin, and registered
  `X-Bifrost-Console-Tab` header; do not require CSRF for these read-only
  operations and do not accept cursor, tab, or credentials in URLs.
- Map recent-query results into explicit browser DTOs without changing shared
  continuity semantics.
- For the stream route, validate a small JSON subscribe request, set
  `text/event-stream`, `Cache-Control: no-store`, `X-Content-Type-Options:
  nosniff`, flush a connection/continuity handshake, replay retained
  `bifrost.activity` frames, then deliver `bifrost.activity` and
  `console.connection`, `console.replay_gap`,
  `console.baseline_refreshed`, and
  `console.target_changed` frames as separate namespaces.
- Bound encoded frame size and per-tab pending frames/bytes. Cancel the
  subscription on request cancellation, tab release/expiry, scope mismatch, or
  router shutdown. A tab-local overflow sends a final bounded replay-gap frame
  when writable, then closes.
- Add a tab-lifecycle cancellation registration rather than polling session
  state or allowing an expired/released tab to retain a live response.

#### 2. Local API and proxy contract verification

**Files**:

- `bifrost-console/internal/console/observability_integration_test.go`
- `bifrost-console/web/vite.config.ts`
- `bifrost-console/web/vite.config.test.ts`

**Changes**:

- Verify the full local response stream preserves framing/order, flushes
  promptly, never caches, and remains in the browser security realm.
- Verify the development proxy continues to proxy the event subtree only to the
  configured loopback Go host and never to the Java observability namespace.

### Success Criteria

#### Automated Verification

- [ ] Browser API stream/query/security/backpressure tests pass:
  `cd bifrost-console && go test ./internal/browserapi ./internal/browserauth ./internal/console`
- [ ] Stream tests cover replay/live handoff, cancellation, tab release, stale
  scope, local gap, and namespaced console events.
- [ ] Vite proxy tests pass through the frontend test suite:
  `cd bifrost-console/web && npm test`

#### Manual Verification

- [ ] Two paired tabs receive the same ordered Bifrost activity while retaining
  independent cursors and selections.
- [ ] Pausing/throttling one tab causes only that tab to refresh; the other tab
  and upstream connection continue.

---

## Phase 4: Live React State and Execution Experience

### Overview

Integrate the relay with the existing scope-bound provider, then replace the
static live views with the settled active/recent collections and three-area
selected-execution experience.

### Changes Required

#### 1. TypeScript contracts and fetch-streamed SSE reader

**Files**:

- `bifrost-console/web/src/api/contracts.ts`
- `bifrost-console/web/src/api/client.ts`
- `bifrost-console/web/src/api/activityStream.ts` (new)
- `bifrost-console/web/src/api/activityStream.test.ts` (new)
- `bifrost-console/web/src/api/client.test.ts`

**Changes**:

- Add exact browser activity, continuity, reset, connection, recent-query, and
  relay-frame types. Keep arbitrary activity details as untrusted data and
  narrow only documented keys at presentation time.
- Add recent-query and activity-subscribe calls using the current in-memory tab
  security, `credentials: same-origin`, no-store behavior, abort signals, and a
  streaming `fetch` body.
- Implement an incremental bounded SSE decoder that handles split UTF-8/chunks,
  validates event names and JSON, and never interprets activity text as markup
  or instructions.
- Classify local EOF/abort separately from Bifrost activity and trigger bounded
  reconnect through provider lifecycle.

#### 2. Shared live reducer/provider

**Files**:

- `bifrost-console/web/src/observability/reducer.ts`
- `bifrost-console/web/src/observability/reducer.test.ts`
- `bifrost-console/web/src/observability/ObservabilityProvider.tsx`
- `bifrost-console/web/src/observability/ObservabilityProvider.test.tsx`

**Changes**:

- Extend shared state with interval identity, retained activity, recent
  completions, connection/freshness facts, last applied cursor, and baseline
  observation time.
- Start the relay after a current active baseline is loaded. On reconnect,
  request after the last applied cursor; on local gap, replace only that tab's
  baseline and narrative suffix; on upstream reset/scope change, synchronously
  discard old interval state before admitting the new interval.
- On `console.baseline_refreshed`, coalesce list reloads and refresh the selected
  execution detail if it is still active. Treat returned snapshots as
  authoritative for path, usage, limits, counts, and full summary while
  preserving browser-owned selection/follow state.
- Deduplicate by interval plus delivery cursor. Preserve stable list insertion
  order while updating matching current facts. Move a terminal execution from
  active to recent completions without changing route selection.
- Abort streaming and pending fetches on scope generation, tab/session
  replacement, unmount, or live-monitoring unavailability. Use bounded backoff
  with injectable timers and avoid reconnect loops for explicit unavailable
  state.
- Coalesce short render bursts without dropping, merging, or reordering
  envelopes.

#### 3. Live collections

**Files**:

- `bifrost-console/web/src/observability/ActiveExecutions.tsx`
- `bifrost-console/web/src/observability/ActiveExecutions.test.tsx`
- `bifrost-console/web/src/styles.css`

**Changes**:

- Present Active executions and Recent completions as separate lifecycle
  collections; do not auto-select or reorder active rows on updates.
- Add latest activity, freshness, and bounded active-path context without a
  health/slow/stuck label. Keep developer sorting/filtering as browser-only
  presentation state.
- Keep terminal links scope-bound and recent completions explicitly temporary.

#### 4. Selected live execution detail

**Files**:

- `bifrost-console/web/src/observability/ActiveExecutionDetail.tsx`
- `bifrost-console/web/src/observability/ActiveExecutionDetail.test.tsx`
- `bifrost-console/web/src/observability/ActivityNarrative.tsx` (new)
- `bifrost-console/web/src/observability/CurrentExecutionSummary.tsx` (new)
- `bifrost-console/web/src/observability/ActivePath.tsx` (new)
- `bifrost-console/web/src/observability/activityPresentation.ts` (new)
- `bifrost-console/web/src/observability/activityPresentation.test.ts` (new)
- `bifrost-console/web/src/styles.css`

**Changes**:

- Retain the selected `sessionId` through live updates and terminal transition.
  Combine the current shared baseline/detail snapshot with only same-interval
  activity for that session.
- Render a sticky current summary with phase/status, start/elapsed time,
  snapshot observation time, connection freshness, latest activity, identifiers,
  usage/limits, and counts. Update elapsed time quietly from `startedAt` only
  while visible.
- Render the bounded active skill path as current path, never as a complete
  tree, including truncation and frame identity facts.
- Render every activity envelope as one keyboard-accessible oldest-to-newest
  narrative item. Provide kind-specific concise labels for all 18 kinds,
  collapse optional details until deliberate selection, and render all
  untrusted strings as text.
- Follow newest activity initially. Pause following when the developer scrolls
  backward or selects an earlier item; show Resume live; continue updating the
  summary without changing selection/scroll. Preserve paused state across
  ordinary same-session refreshes.
- Present disconnect, local gap, upstream reset divider, stale observation, and
  live-unavailable states outside the activity list. Announce material
  connection/reset/terminal changes through a restrained live region without
  announcing every counter/timer update.
- On `TRACE_COMPLETED`, show outcome and application artifact availability
  separately. On `EXECUTION_OBSERVATION_ENDED`, show incomplete observation and
  `CORE_FINALIZATION_FAILED` without inventing an outcome. Do not navigate
  automatically. Render Inspect trace only when availability permits it, with
  acquisition deferred to PR 12.
- Respect reduced motion, forced colors, zoom, keyboard focus, and responsive
  layout; use at most a restrained new-row highlight and disable it for reduced
  motion.

### Success Criteria

#### Automated Verification

- [ ] Type checking and component tests pass:
  `cd bifrost-console/web && npm run typecheck && npm test`
- [ ] Reducer/provider tests cover replay, duplicate cursor, local gap, upstream
  reset, reconnect, target reset, missed completion reconciliation,
  finalization failure, and stable selection.
- [ ] Component tests cover follow/pause/resume, scroll and row selection
  preservation, all activity kinds, safe text rendering, live announcements,
  reduced motion, and terminal action visibility.

#### Manual Verification

- [ ] A running execution updates summary, path, and narrative while the route
  and selected context remain stable.
- [ ] Scrolling backward pauses following; new activity does not move the
  viewport or selected row; Resume live returns to the newest item.
- [ ] Disconnect, local gap, upstream reset, target change, unavailable live
  monitoring, completion, and core-finalization failure are visually distinct
  and understandable using keyboard navigation, 200% zoom, forced colors, and
  reduced motion.

---

## Phase 5: Cross-Layer Verification and Documentation

### Overview

Prove the atomic Java-fixture-Go-browser contract and document the implemented
Console behavior without changing skill-authoring guidance.

### Changes Required

#### 1. End-to-end browser scenarios and fixture contract

**Files**:

- `bifrost-console/web/e2e/live-executions.spec.ts` (new)
- `bifrost-console/browser-fixtures/` (extend existing server behavior)
- `bifrost-console/internal/console/observability_integration_test.go`
- `bifrost-console-fixtures/application-sse/*` (reuse; update only if a verified
  producer discrepancy requires an atomic correction)

**Changes**:

- Add deterministic application fixture behavior for baseline, replay,
  duplicate, reconnect, stale cursor, changed instance, gap, slow tab,
  completion, observation-ended, live unavailable, and target rotation.
- Add representative browser scenarios proving stable selection, independent
  tabs, local versus shared gaps, terminal context, and reset navigation.
- Reference the applicable `WF-SE-*` and cross-workflow requirements in test
  names/comments where useful for coverage review.

#### 2. Console runtime documentation

**File**: `bifrost-console/README.md`

**Changes**:

- Document that Console owns one upstream SSE connection for the selected
  scope, retains only one bounded in-memory continuity interval, independently
  bounds tab delivery, and may show incomplete recent activity after gaps or
  restart.
- Document the developer-facing difference between active summaries, temporary
  recent completions, and finalized trace inspection. Do not document internal
  wire DTOs as a supported external API.

### Success Criteria

#### Automated Verification

- [ ] Focused Go race tests pass:
  `cd bifrost-console && go test -race ./internal/applicationclient ./internal/target ./internal/observability ./internal/browserapi ./internal/console`
- [ ] Frontend unit/type tests pass:
  `cd bifrost-console/web && npm run typecheck && npm test`
- [ ] Browser scenarios pass:
  `cd bifrost-console/web && npm run test:e2e`
- [ ] Canonical production verification passes:
  `cd bifrost-console && go run ./internal/buildtool verify`
- [ ] Fixture contract tests consume the canonical `application-rest` and
  `application-sse` files without duplicated semantic fixtures.

#### Manual Verification

- [ ] Run the slow-execution workflow against a real application in two tabs,
  including pause/resume and terminal transition.
- [ ] Restart the application and change the selected target while detail is
  open; confirm no activity or selection crosses the scope/instance boundary.
- [ ] Throttle one tab and temporarily interrupt the target; confirm explicit
  freshness/gap facts and recovery without a false health or cause claim.

---

## Testing Strategy

### Unit Tests

- Strict SSE framing, content type, identity, bounds, cancellation, and problem
  mapping.
- Ring count/byte eviction, cursor ordering, duplicate/conflict handling,
  single-interval queries, filtering, continuations, and reset metadata.
- Activation/invalidation ordering, stale work rejection, reconnect/backoff,
  periodic baseline reconciliation, and unavailable live monitoring.
- Subscriber replay/live atomicity, independent cursors, overflow, release,
  cancellation, and nonblocking fan-out.
- Frontend incremental SSE decoding, interval/cursor reducer invariants,
  snapshot/activity precedence, follow state, accessibility announcements, and
  safe presentation of untrusted detail.

### Integration Tests

- Java fixture to Go reader and query result.
- Target lifecycle through coordinator to browser SSE framing.
- Multiple tab consumers sharing one upstream connection.
- Baseline plus live start/completion races, missed completion healed by
  refresh, stale upstream cursor, instance restart, and target rotation.
- Browser workflow behavior for active list, detail, gap recovery, reconnect,
  terminal transition, and finalization failure.

### Manual Testing Steps

1. Connect Console to an application, start an execution, and open it from Live
   Executions.
2. Verify ordered narrative, current path/usage refresh, freshness, quiet elapsed
   time, and stable selection.
3. Pause following, generate more activity, and confirm summary updates without
   scroll or selection movement.
4. Open a second tab, throttle it, and confirm only it receives a local-gap
   refresh.
5. Interrupt/restart the application and verify a visible reset boundary with
   no combined intervals.
6. Complete normally and with a core-finalization failure; verify the distinct
   in-place terminal states and trace-availability action.
7. Change target scope while detail is open and verify complete application
   state reset and root navigation.

**Note**: Run `ai/commands/3_testing_plan.md` before implementation to turn
these areas into a focused failing-test-first artifact with final command and
exit-criteria ownership.

## Performance Considerations

- Bound all four queues independently: upstream Java delivery, Go recent ring,
  Go per-tab pending delivery, and browser retained/rendered narrative.
- Serialize and size an activity envelope once where practical; avoid copying
  unbounded detail maps per subscriber.
- Never hold coordinator locks while performing network I/O or writing a tab
  response. Snapshot replay/subscription membership under lock, then drain per
  subscriber independently.
- Use one upstream stream and one baseline refresh loop per target scope,
  regardless of route or tab count.
- Batch short frontend bursts into one render pass without semantic
  coalescing. Keep elapsed-time cadence independent of evidence ingestion.
- Measure ring/query/frame bounds in encoded bytes as well as item count so a
  small number of large details cannot defeat memory limits.

## Migration Notes

No persisted data or supported external API requires migration. The browser
assets and Go routes ship in one executable, so their DTO changes are atomic.
On upgrade or restart, the in-memory activity interval begins empty and is
re-established from a fresh active baseline; no prior activity is adopted.

## References

- Original ticket:
  `ai/thoughts/tickets/bifrost-console-pr-11-live-execution-experience.md`
- Research:
  `ai/thoughts/research/2026-07-28-pr-11-live-activity-active-execution-detail.md`
- Phase 2 design:
  `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md`
- Developer workflows:
  `ai/thoughts/phases/bifrost_console_workflows.md`
- Roadmap:
  `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md`
- Future shared-service consumer:
  `ai/thoughts/tickets/bifrost-console-pr-17-mcp-runtime-inspection.md`
- Framework compatibility lens:
  `ai/thoughts/framework-feature-design-lens.md`
