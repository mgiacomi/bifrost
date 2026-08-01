# PR 14 — Trace Explorer Foundation Implementation Plan

## Overview

Deliver the finalized-trace browser explorer on top of the acquired-artifact and
transport-neutral analysis services completed in PRs 12–13. The browser will
navigate Go-owned hierarchy, timing, usage, record, attempt, validation, and
failure facts without recalculating them or treating raw application content as
trusted presentation content.

## Current State Analysis

`TraceDetailView` currently displays catalog metadata and separate artifact
actions only. The console composition root already wires `traceanalysis.Service`
to the shared artifact service, but `browserapi.Options`, the router, browser
contracts, and client expose no analysis operations. The analysis service
already provides finite pages, range continuations, scope/handle-bound cursors,
and bounded results.

The settled Phase 2 design makes the explorer hierarchy-first. Timeline, Usage,
and Records are coordinated alternate views over one selected context, rather
than independently calculated reports. Raw artifact download is deliberately a
different application pass-through operation from acquired-artifact inspection.

## Desired End State

From a current-scope trace detail route, a developer can deliberately acquire
an artifact and enter an explorer that presents its validated summary and frame
hierarchy. They can select a frame, record, or failure; follow current-scope
links among related evidence; inspect timeline, usage, records, attempts,
validation, gaps, and uncertainties; and deliberately reveal reconstructed
payload or raw-record text in bounded, continuable ranges. A deliberate raw
attachment action remains a fresh application download and never installs or
extends the local analysis copy.

The trace, selection, and view state are URL-addressable only while the
`targetScopeId` remains current. A stale scope, expired or removed local
artifact, malformed analysis evidence, or unavailable requested content yields
the existing precise domain error and clears explorer-only selection instead of
showing stale facts.

### Key Discoveries

- `traceanalysis.Service` is intentionally adapter-facing for this browser work
  and acquires leases from an opaque handle (`bifrost-console/internal/traceanalysis/service.go:17-78`).
- The service already owns frame, record, fact, literal-search, and byte-range
  semantics, including cursor fingerprinting and a 1,000-item / 1 MiB maximum
  response bound (`bifrost-console/internal/traceanalysis/query_*.go`,
  `limits.go:18-33`).
- The browser's existing `useScopeBoundRoute` and response-level scope check
  provide the required two-stage stale-navigation protection
  (`bifrost-console/web/src/observability/useScopeBoundRoute.ts:6-25`,
  `scope.ts:12-38`).
- Current raw download is a special authenticated GET that opens the application
  artifact directly and bypasses the local cache
  (`bifrost-console/internal/browserapi/artifact_download.go:15-83`).
- The Phase 2 design explicitly chooses semantic tree behavior, HTML/SVG
  timeline rendering, and no general charting dependency
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:75-99`).

## What We're NOT Doing

- Automatic acquisition, root-cause analysis, repository/file lookup, execution
  control, durable history, MCP, or workflow polish reserved for PR 15.
- A browser raw-artifact-range API. That bounded acquired-copy primitive remains
  reserved for PR 18's MCP raw-artifact-inspection capability; PR 14 offers raw
  records plus the existing separate attachment download.
- A second parser, hierarchy builder, duration calculator, usage aggregator,
  retry/failure inference, or browser-owned evidence cache.
- Rendering raw content as HTML/Markdown, interpreting strings as links, or
  placing payload bytes or credentials in URLs, browser storage, logs, or error
  details.
- A general visualization, virtual-list, or component-library dependency. Use
  pagination first; introduce browser virtualization only if the planned
  representative large-list tests demonstrate an interaction or rendering need.

## Skill-Authoring Documentation Impact

**Impact**: No impact

- **Rationale**: This work exposes already-recorded current-run diagnostics in a
  personal browser console. It changes no skill manifest syntax, runtime,
  evidence contract, trace emission, limits, author-facing debugging semantics,
  or author workflow promised by `ai/skill-authoring/`.
- **Documents to update**: None.
- **Supporting evidence**: `traceanalysis.Service` and the browser adapter
  consume PR 12's acquired bundle and PR 13's existing validated facts; the
  authoring knowledge base already routes author trace semantics to
  `traces-and-debugging.md` and does not document console UI operations.
- **Coverage table update**: Not required; no authoring topic or confidence
  changes.
- **LLM-first usability**: Not applicable.

## Contract and Compatibility Impact

| Surface | Classification and supporting evidence | Planned compatibility treatment |
| --- | --- | --- |
| Application API | No application REST/SSE or artifact-stream protocol change. The explorer consumes an already acquired PR 12 bundle. | Preserve; no Java, fixture, or adapter-protocol change. |
| Supported SPI | No supported extension point changes. `traceanalysis.Service` is internal console wiring, not an established SPI. | No change. |
| Configuration and manifest contracts | No configuration, manifest, or author-facing behavior changes. | Preserve. |
| Persisted or serialized contracts | No durable format change. Browser analysis DTOs are current-process browser contracts and must be fixture-tested with their TypeScript consumers. | Add browser-only DTOs atomically with Go handlers, fixtures, client, and UI. |
| Ephemeral diagnostic formats | Acquired current-release trace evidence is an ephemeral diagnostic format. The browser exposes Go-owned projections and raw text only on request. | Current-version coherence; preserve Go calculations, scope/handle invalidation, bounded continuation, and text-only rendering. |
| Internal or accidentally exposed implementation | Browser routes, console wiring, frontend state, and Go analysis DTOs are technically exposed internal console surfaces with no supported-contract allowlist. | Add the adapter surface atomically; no legacy routes, aliases, fallback calculations, or dual behavior. |

- **Evidence of supported contracts**: The settled Phase 2 design and PR 12/13
  tickets protect the application artifact lifecycle and shared calculation
  semantics. No evidence establishes the new browser analysis routes or DTOs as
  a separately versioned public API.
- **Intended breaks**: The trace-detail screen changes from an acquisition-only
  destination to the explorer entry point after acquisition. No protected API
  is removed.
- **In-repository consumers to update**: Browser API router/options and
  composition root; browser fixtures and API tests; TypeScript contracts/client;
  trace detail/route/state/components/styles; Vitest and Playwright coverage.
- **Public-surface delta**: Internal authenticated POST routes under
  `/api/console/v1/traces/analysis/`, internal Go browser DTOs, and browser
  TypeScript types/client functions. No Java API, SPI, constructor, or Spring
  bean change.
- **Shim decision**: **No shim.** There is no protected predecessor to adapt;
  all producer and in-repository consumers will ship atomically.
- **Java-to-Go boundary coordination**: **Not required.** PR 14 does not alter
  application-adapter REST/SSE/acquisition/problem/NDJSON meanings; its new
  browser boundary is wholly inside the Go console and React application.

## Implementation Approach

Keep one explorer state model keyed by `traceId`, `targetScopeId`, selected
frame ID, record sequence, failure ID, and active view. URL state carries the
current-scope navigation conveniences and selection identifiers, but never an
artifact handle, raw bytes, or reconstructed payload. Browser API handlers
resolve `traceId` to the current locally available handle through the artifact
service, capture and publish the target scope, then call the existing analysis
service. They project the result into browser DTOs without exposing local paths
or requiring React to manage handles.

Use explicit endpoint families for summary, frames, records, attempts/retries,
validation links, failures, payload descriptors, gaps/uncertainties, usage,
literal search, payload ranges, and raw-record ranges. Each POST body is
strictly bounded; list/search/range continuation cursor values pass unchanged to
the owning service and never get reinterpreted by the browser. The browser
requests summary and the first hierarchy page on entry, then loads alternate
views and raw data only in response to a developer action.

## Phase 1: Add the Internal Browser Analysis Adapter

### Overview

Expose read-only, scoped projections of existing trace-analysis queries without
changing their calculations, acquisition lifecycle, or application protocol.

### Changes Required

#### 1. Wire and define the adapter

**Files**: `bifrost-console/internal/console/service.go`,
`bifrost-console/internal/browserapi/router.go`,
`bifrost-console/internal/browserapi/trace_analysis.go` (new)

**Changes**:

- Add a narrow `TraceAnalysisService` interface to `browserapi.Options` and
  pass the composed `traceanalysis.Service` from the console root.
- Register session-authenticated, read-only POST operations below
  `/api/console/v1/traces/analysis/` for summary, frames, records, facts,
  search, payload range, and raw-record range. Do not require CSRF for these
  side-effect-free operations.
- Capture the target scope before resolving the trace's installed artifact;
  reject local-unavailable/expired/removed entries with existing shared errors;
  call `writeScopedJSON` so a scope rotation cannot commit old facts.
- Enforce small JSON bodies, required trace/selection values, page/range input
  shape, and operation-specific source constraints before delegating. Preserve
  Go's error code, target scope, bounded details, cursor and continuation
  precedence.
- Deliberately omit `ReadRawArtifactRange`; keep `artifactRawDownload` unchanged.

#### 2. Define fixture-backed browser DTOs

**Files**: `bifrost-console/internal/browserapi/trace_analysis.go` (new),
`bifrost-console/internal/browserapi/contracts_test.go`,
`bifrost-console/browser-fixtures/trace-analysis/` (new)

**Changes**:

- Define browser response DTOs for trace context (without an artifact handle),
  summary, frames, records, facts, pages, byte ranges, and explicit content
  encoding. Maintain `items: []`, `hasMore`, and nullable `nextCursor` in the
  browser wire format.
- Serialize all user-controlled/raw string values as ordinary JSON strings;
  never construct HTML, URLs, filesystem locations, or response headers from
  them.
- Add exact fixture inventory tests for representative summary, hierarchy,
  repeated invocation, usage/failure/validation, record/payload, range, and
  continuation shapes.

### Success Criteria

#### Automated Verification:

- [x] `go test ./internal/browserapi ./internal/traceanalysis` passes from
  `bifrost-console/`.
- [x] Browser API tests prove scope capture/publish, missing or expired local
  artifact behavior, malformed request rejection, pagination/range continuation
  forwarding, and no raw-download cache mutation.
- [x] The trace-analysis browser fixture inventory matches emitted JSON
  byte-for-byte.

#### Manual Verification:

- [x] A locally acquired trace can be queried after later upstream
  authentication rejection without suggesting the application is currently
  authorized.
- [x] A removed or expired artifact produces a precise error and no stale
  response is displayed.

---

## Phase 2: Establish Explorer Route, Acquisition Handoff, and Shared State

### Overview

Turn the trace detail route into the scope-bound explorer entry point and add
one URL-backed, resettable coordinator for view and evidence selection.

### Changes Required

#### 1. Extend browser contracts and client

**Files**: `bifrost-console/web/src/api/contracts.ts`,
`bifrost-console/web/src/api/client.ts`

**Changes**:

- Add TypeScript mirrors of every Phase 1 DTO plus client calls whose request
  arguments are trace ID, filters, page/range limit, and opaque continuation.
- Keep `artifactHandle` out of explorer request, URL, and persisted UI state;
  the Go adapter resolves the local entry for the trace.
- Extend `BrowserErrorCode` only where PR 13 already returns a missing shared
  code; otherwise preserve existing error meanings and use
  `recoverObservabilityError` / `requireCurrentTargetScope` for all responses.

#### 2. Add route state and the explorer shell

**Files**: `bifrost-console/web/src/app/routes.tsx`,
`bifrost-console/web/src/observability/TraceDetail.tsx`,
`bifrost-console/web/src/observability/TraceExplorer.tsx` (new),
`bifrost-console/web/src/observability/traceExplorerState.ts` (new)

**Changes**:

- Retain `/traces/:traceId` as the durable route shape, with URL query fields
  for `targetScopeId`, `view`, `frameId`, `recordSequence`, and `failureId`.
  Validate selection values; remove invalid or stale explorer selection rather
  than applying it to another trace.
- Preserve existing catalog metadata and explicit **Acquire for analysis**.
  On successful acquisition, navigate deliberately to the same trace route's
  explorer state; if a local artifact already exists, show **Open explorer**.
- Use the existing scope-bound route hook before any query, independently verify
  every response scope, abort in-flight effects on navigation/scope generation,
  and replace a stale deep link with `/` and its existing notice.
- Focus the route heading on entry and use a labelled status region for loading,
  acquisition, target changes, expiration/removal, malformed evidence, and
  unavailable payload errors without moving focus during normal selection.
- Render summary outcome, identity, lifecycle facts, root hierarchy entries,
  selected-frame breadcrumbs, and a semantic tablist for Hierarchy, Timeline,
  Usage, and Records. The coordinator owns only selection/presentation state;
  it never derives authoritative facts.

#### 3. Make raw artifact download deliberately distinct

**Files**: `bifrost-console/web/src/observability/TraceDetail.tsx`,
`bifrost-console/web/src/observability/TraceExplorer.tsx`,
`bifrost-console/web/src/styles/index.css`

**Changes**:

- Replace the immediate attachment link with a browser confirmation dialog that
  explains it performs a fresh application download, may be unavailable, and
  neither installs nor retains a local analysis copy. Only the confirmed action
  emits the unchanged `download` link/navigation to the existing raw endpoint.
- Keep raw-record and reconstructed-payload inspection inside the explorer and
  visually/name-wise separate from the attachment action.

### Success Criteria

#### Automated Verification:

- [x] `npm run typecheck` and `npm test` pass from `bifrost-console/web/`.
- [x] Component tests cover initial acquisition, existing-local-artifact entry,
  URL state validation, stale-scope reset before requests, response-scope race,
  focus placement, and raw-download confirmation.
- [x] Tests prove explorer URLs contain no handle, payload, credential, or local
  filesystem path.

#### Manual Verification:

- [x] A trace/failure/frame/record current-scope link restores the intended
  view and selected evidence after a refresh while the local artifact remains
  valid.
- [x] Cancelling raw-download confirmation leaves both application availability
  and local-cache lifecycle unchanged.

---

## Phase 3: Implement Coordinated Evidence Views and Deliberate Detail

### Overview

Complete the hierarchy-first explorer with bounded alternate views and
cross-view selection, preserving the service as the only authority for facts.

### Changes Required

#### 1. Hierarchy, timeline, and usage

**Files**: `bifrost-console/web/src/observability/TraceHierarchy.tsx` (new),
`TraceTimeline.tsx` (new), `TraceUsage.tsx` (new), `TraceExplorer.tsx`

**Changes**:

- Request and append frame pages using the hierarchy-capable order/filter; use
  semantic tree buttons with `aria-expanded`, keyboard arrow/Home/End behavior,
  visible indentation, and no frontend parent/child/duration/usage inference.
- Present selected-frame breadcrumbs from returned parent relationships and
  select related frame IDs from hierarchy, timeline, usage, failure, attempt,
  validation, and record views.
- Render timeline bars with HTML/SVG only from returned timestamps and optional
  inclusive/self durations. Show unavailable/incomplete timing explicitly; do
  not create a bar or zero duration for unknown values.
- Render usage as tables/simple visual comparisons of returned direct,
  descendant, inclusive, terminal, aggregate, and unattributed components.
  Preserve completeness flags and never sum overlapping inclusive values in
  React.

#### 2. Records, facts, search, and raw/payload readers

**Files**: `bifrost-console/web/src/observability/TraceRecords.tsx` (new),
`TraceEvidenceDetail.tsx` (new), `TraceExplorer.tsx`

**Changes**:

- Provide paged/filterable records plus attempts, retries, validation links,
  failures, payload descriptors, gaps, uncertainties, and bounded literal
  search. Every **Load more**, search continuation, and range continuation uses
  the returned cursor exactly once and disables itself while pending.
- Provide explicit controls to open a raw physical record or reconstructed
  payload. Fetch no payload bytes until activation; render returned text/base64
  in a `<pre>`/text-only component with wrapping and no linkification.
- Offer **Read next range** until `hasMore` is false, preserving actual offsets,
  content type, encoding, and total length. Do not concatenate an unbounded
  payload in memory; retain and display finite chunks with an explicit clear or
  replacement selection.
- Route record/failure/attempt/validation selection through the shared
  coordinator, retaining trace scope and selection across alternate views.

#### 3. Responsive and accessibility styling

**Files**: `bifrost-console/web/src/styles/index.css`,
`bifrost-console/web/src/styles/tokens.css` as needed

**Changes**:

- Add explorer layout, responsive single-column fallback, scrollable labelled
  table regions, high-zoom-safe controls, non-color selection/status cues, and
  focused selected rows.
- Extend existing forced-colors and reduced-motion rules to tree controls,
  timeline geometry, dialogs, tab selection, and raw text regions. Do not add
  motion-dependent timeline or selection behavior.

### Success Criteria

#### Automated Verification:

- [x] Vitest covers hierarchy expansion/keyboard navigation, repeated frame
  invocation selection, breadcrumbs, timeline/usage fidelity, cross-view links,
  records/failure/attempt/validation states, literal search, and continuation.
- [x] Tests prove raw text is rendered as text and payload fetching occurs only
  after an explicit action.
- [x] Tests cover invalid artifact, unavailable payload, expired/removed
  handle, target rotation, stale cursor, malformed selection, and a fixture
  with an over-one-page collection or range.

#### Manual Verification:

- [x] Keyboard-only traversal reaches tree, tabs, records, confirmation, raw
  reader, and continuation controls with an obvious focus indicator.
- [x] At 200% zoom, narrow viewport, forced-colors, and reduced-motion, the
  hierarchy and evidence relationships remain usable and distinguishable.
- [x] A multi-megabyte payload can be deliberately inspected through bounded
  chunks without a long browser stall or automatic full-content load.

---

## Phase 4: Prove Browser Workflow Integration

### Overview

Exercise the end-to-end behavior against Java-produced fixture artifacts and
the existing paired-console test harness, leaving PR 15 to broaden final
workflow/release hardening.

### Changes Required

#### 1. Extend browser fixtures and Playwright scenarios

**Files**: `bifrost-console/web/e2e/artifact-storage.spec.ts` or focused new
`trace-explorer.spec.ts`, `bifrost-console/web/e2e/fixtures/` as needed,
`bifrost-console-fixtures/traces/` and `expected/` only when an existing
fixture lacks a required representative shape

**Changes**:

- Add browser flows tagged with `WF-FAILED-EXECUTION`,
  `WF-EXPENSIVE-EXECUTION`, and `WF-UNFAMILIAR-SKILL-PATH` as applicable:
  acquire/open hierarchy, inspect repeated/nested/failure evidence, switch
  timeline/usage/records while retaining selection, explicitly inspect a
  payload/raw record, and follow an evidence link.
- Verify target rotation resets deep links and in-flight evidence; expiration or
  removal produces shared errors; malformed acquisition never enters explorer;
  and valid local evidence remains inspectable after later upstream
  authentication rejection.
- Verify confirmed raw download streams exact Java-produced bytes and does not
  mutate Trace Storage; cancellation performs no download.

#### 2. Run the canonical build verification

**Files**: no production file change

**Changes**:

- Run the repository-standard Console verifier after focused Go, frontend, and
  Playwright tests. Investigate asset or integration failures in their owning
  Go/browser layer rather than masking them in explorer state.

### Success Criteria

#### Automated Verification:

- [x] `npm run test:e2e` passes from `bifrost-console/web/`.
- [x] `go run ./internal/buildtool verify` passes from `bifrost-console/`.
- [x] Playwright scenarios cover hierarchy, repeated invocation, timeline,
  usage, records, payload, failure, cross-view navigation, scope reset, and
  separate raw-download lifecycle semantics.

#### Manual Verification:

- [x] Review a successful, nested-retry, chunked-payload, terminal-failure,
  incomplete, and malformed fixture experience; each presents facts and direct
  uncertainty without a root-cause or importance claim.
- [x] Confirm the attachment choice, local analysis-copy status, and current
  application availability remain visibly distinct throughout the explorer.

---

## Testing Strategy

### Unit Tests

- Browser API request validation, trace-to-local-handle resolution, scoped
  response publication, DTO fixture serialization, and domain-error mapping.
- Explorer reducer/URL parser, selection reset, focus and status behavior,
  tree keyboard semantics, timeline/usage presentation, text-only raw content,
  and continuation state.

### Integration Tests

- Existing trace-analysis tests remain the authority for calculations and
  continuations. Browser API tests prove exact adaptation; Playwright verifies
  the coordinated experience against the fixture corpus and paired session.

**Note**: Run `ai/commands/3_testing_plan.md` before implementation for the
dedicated failing-test sequence, exact fixture selection, commands, and exit
criteria.

### Manual Testing Steps

1. Acquire a failed nested/retry trace, enter the explorer, select a failure,
   and verify frame breadcrumbs, related attempts/validation, timeline, usage,
   and records retain the same selection.
2. Open a chunked payload and a raw record only by deliberate controls; page
   through their ranges and verify displayed content is plain text.
3. Rotate the target or remove the artifact during use, then confirm selection
   is cleared and no old evidence remains reachable.
4. Confirm/cancel raw attachment download and compare the downloaded bytes with
   the fixture while checking Trace Storage is unchanged.

## Performance Considerations

- Start with existing finite pages (default 100, maximum 1,000) and 1 MiB range
  cap; render only loaded pages and do not prefetch payloads or full evidence.
- SVG timelines derive only from the selected/loaded frame set. Use paged
  loading and measured rendering before considering virtualization; any later
  virtualization must retain keyboard semantics and addressability.
- Abort superseded requests and avoid retaining concatenated raw chunks to keep
  browser memory bounded. Go leases remain per existing query lifecycle.

## Migration Notes

No data migration or compatibility shim is required. The explorer only reads
current-process, current-scope acquired bundles. Existing trace-detail URLs
continue to identify the trace; they gain optional current-scope explorer query
state and reset safely when stale.

## References

- Original ticket: `ai/thoughts/tickets/bifrost-console-pr-14-trace-explorer.md`
- Related research: `ai/thoughts/research/2026-07-31-PR-14-trace-explorer.md`
- Roadmap: `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md`
- Workflow requirements: `ai/thoughts/phases/bifrost_console_workflows.md`
- Prior foundations: `ai/thoughts/tickets/bifrost-console-pr-12-artifact-service.md`,
  `ai/thoughts/tickets/bifrost-console-pr-13-trace-analysis-services.md`
- Follow-on consumers: `ai/thoughts/tickets/bifrost-console-pr-15-diagnostic-workflows.md`,
  `ai/thoughts/tickets/bifrost-console-pr-18-mcp-trace-inspection.md`
