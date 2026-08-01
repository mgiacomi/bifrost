---
date: 2026-07-31T23:43:16-07:00
researcher: Unknown
git_commit: 7cb3cff1cd04686d441b31dd08bf16d4579b6318
branch: main
repository: bifrost
topic: "Research for PR 14 — Trace Explorer Foundation"
tags: [research, codebase, bifrost-console, trace-analysis, browser-api, react]
status: complete
last_updated: 2026-07-31
last_updated_by: Unknown
---

# Research: PR 14 — Trace Explorer Foundation

**Date**: 2026-07-31T23:43:16-07:00  
**Researcher**: Unknown (`humanlayer thoughts status` is unavailable in this environment)  
**Git Commit**: 7cb3cff1cd04686d441b31dd08bf16d4579b6318  
**Branch**: main  
**Repository**: bifrost

## Research Question

Document the existing codebase and historical context relevant to [PR 14 — Trace Explorer Foundation](../tickets/bifrost-console-pr-14-trace-explorer.md): acquired-trace exploration, hierarchy-first navigation, coordinated evidence views, deliberate payload and raw inspection, raw-artifact download, scope invalidation, and accessible browser behavior.

## Summary

The repository already has the two PR 14 dependencies in place. PR 12 supplies a trace catalog/detail screen, acquisition of one local analysis copy, Trace Storage lifecycle controls, and a separate authenticated raw-artifact pass-through download. PR 13 supplies the transport-neutral `traceanalysis.Service`, which owns processed hierarchy, timing, usage, failure, record, payload, raw-range, and literal-search results over acquired artifacts.

The current browser route is `/traces/:traceId`; it presents catalog metadata plus acquisition and raw-download actions. It explicitly identifies semantic explorer views as future work. The browser API has catalog, detail, and artifact endpoints only; it does not yet inject `traceanalysis.Service` or expose trace-analysis query endpoints. The composition root does construct and wire that service to the shared artifact service.

Current frontend patterns already provide target-scope-bound links, stale deep-link reset to the overview route, request-result scope verification, focus placement on route headings, table regions, loading/error states, and continuation controls in catalog-style views. Existing trace-analysis query results carry the target scope, opaque artifact handle, trace ID, and session ID, and their APIs page lists and ranges with continuation cursors.

## Detailed Findings

### Browser routes and current trace experience

- `routes.tsx` registers `/traces`, `/traces/:traceId`, and `/trace-storage`; the trace detail route currently owns the per-trace browser view (`bifrost-console/web/src/app/routes.tsx:26-28`).
- `TraceDetailView` loads trace catalog metadata from `getTraceDetail`, renders it as a definition list, and places focus on its `h2` when it mounts (`bifrost-console/web/src/observability/TraceDetail.tsx:18-65`, `:92-105`).
- The view exposes **Acquire for analysis**, which calls the CSRF-protected JSON artifact-acquisition endpoint, and **Raw artifact download**, a browser attachment link. Its own text distinguishes an installed analysis copy from the direct download and states that semantic explorer views are future work (`TraceDetail.tsx:80-147`).
- `Traces` renders the catalog in a semantic table inside a labelled, focusable table region. It loads the first page automatically, provides refresh/retry/loading/empty states, and appends catalog pages through `Load more` (`bifrost-console/web/src/observability/Traces.tsx:8-80`).
- The current global navigation exposes both Trace Catalog and Trace Storage (`bifrost-console/web/src/app/App.tsx:60-68`).

### Target scope and current-scope navigation

- Catalog trace links use `scopeBoundPath`, adding `targetScopeId` as a query parameter (`Traces.tsx:66`; `bifrost-console/web/src/observability/scope.ts:6-10`).
- `useScopeBoundRoute` compares that route scope with the current target scope. A missing or mismatched scope replaces the route with `/` and records `staleTargetScope` in navigation state (`bifrost-console/web/src/observability/useScopeBoundRoute.ts:6-25`). `TraceDetailView` uses this hook before it requests a trace identifier (`TraceDetail.tsx:31-47`).
- After a detail response arrives, `requireCurrentTargetScope` independently compares the response scope with the selected scope, refreshes target state when they differ, and raises `TARGET_CHANGED`; `recoverObservabilityError` also refreshes on an unhandled target-changed error (`scope.ts:12-38`).
- Component coverage verifies that an old trace deep link resets before `getTraceDetail` is called (`bifrost-console/web/src/observability/TraceDetail.test.tsx:71-76`). Browser E2E coverage for acquisition also includes target rotation, application authorization failure with locally installed evidence, and unavailable application artifacts (`bifrost-console/web/e2e/artifact-storage.spec.ts:337-429`).

### Acquisition and analysis-service wiring

- The console composition root creates one `traceanalysis.Service`, passes it to `artifact.New` as the artifact processor, then wires the created artifact service back through `SetArtifactService`. The comments state that the query service acquires artifact leases by opaque handle (`bifrost-console/internal/console/service.go:154-190`; `bifrost-console/internal/traceanalysis/service.go:17-58`).
- The artifact service is registered as a target owner, so its local state participates in target-scope lifecycle management (`console/service.go:187-193`).
- `traceanalysis.Service` documents itself as adapter-facing for PR 14 browser work and PR 18 MCP work. Each query takes a scope and handle, acquires a lease, opens only required bundle components, obeys context cancellation, and materializes a complete result before closing a successful lease (`traceanalysis/service.go:17-25`).
- Its `TraceContext` is included on reusable query results and has target scope ID, opaque handle, trace ID, and session ID (`bifrost-console/internal/traceanalysis/dto.go:9-23`).

### Go-owned trace evidence and continuations

- `GetSummary` returns trace identity, outcome, root frame IDs, counts, aggregate and terminal usage, and completeness from the processed manifest and usage index (`bifrost-console/internal/traceanalysis/query_facts.go:20-88`; `dto.go:25-51`).
- `QueryFrames` returns hierarchy-ready frame summaries. Each summary includes parent and child frame IDs, route/type, optional inclusive/self duration, and direct/descendant/inclusive usage plus completeness flags (`bifrost-console/internal/traceanalysis/query_frames.go:44-64`; `dto.go:53-75`).
- `QueryRecords` produces physical/logical record summaries, including canonical sequence, frame/route references, raw byte addresses, representation, chunk/envelope state, and optional explicitly requested bounded inline payloads (`bifrost-console/internal/traceanalysis/query_records.go:44-65`; `dto.go:77-111`).
- Fact queries exist for attempts, retry sequences, validation links, failures, payload descriptors, gaps, uncertainties, and usage breakdown (`query_facts.go:186-619`; `dto.go:113-194`). Literal text search has a paged `Search` query (`bifrost-console/internal/traceanalysis/search.go:21-33`).
- `ReadPayloadRange`, `ReadRawRecordRange`, and `ReadRawArtifactRange` return a bounded byte result with actual offsets, total length, content type, text/base64 representation, and continuation information (`bifrost-console/internal/traceanalysis/query_ranges.go:12-243`; `dto.go:196-236`).
- Generic pages contain non-nil items, `hasMore`, and an opaque `nextCursor` (`dto.go:184-193`). The current bounds set a maximum page size of 1,000 and maximum range size of 1 MiB (`bifrost-console/internal/traceanalysis/limits.go:18-33`).

### Existing browser API and frontend contracts

- The browser API router presently registers `/traces/list`, `/traces/detail`, artifact acquisition/storage/removal endpoints, and the special raw-download path; no trace-analysis service is part of `browserapi.Options` and no trace-analysis query routes are registered (`bifrost-console/internal/browserapi/router.go:18-37`, `:60-122`).
- Frontend contracts and the `client.ts` wrapper currently describe trace catalog metadata, acquired-artifact metadata, storage snapshots, generic catalog pages, and raw-download URLs; they do not yet define trace-analysis summary, frame, record, range, or search DTOs (`bifrost-console/web/src/api/contracts.ts:78-132`; `bifrost-console/web/src/api/client.ts:153-192`).
- Trace catalog/detail browser handlers enrich upstream trace metadata with artifact availability. If the upstream returns several target/application failures, the handlers can return facts from an installed local artifact without claiming current application availability (`bifrost-console/internal/browserapi/observability.go:104-213`).

### Separate raw-artifact download path

- The raw-download route is intercepted before the normal POST/origin flow and uses an authenticated paired-session GET wrapper (`bifrost-console/internal/browserapi/router.go:60-66`).
- `artifactRawDownload` rejects query, range, and conditional requests; captures the current target scope; opens the application artifact directly; publishes fixed attachment headers only while that scope remains current; then streams with a 32 KiB buffer (`bifrost-console/internal/browserapi/artifact_download.go:15-83`). It neither consults nor mutates the local artifact cache.
- The browser client forms the URL as `/api/console/v1/artifacts/{encoded traceId}/raw` (`bifrost-console/web/src/api/client.ts:191-193`). The detail component uses a plain `<a download>` link (`TraceDetail.tsx:121-127`).
- Browser API tests cover exact-byte streaming without cache mutation, fresh application authorization for every download, rejected query/range/conditional/ambiguous requests, safe attachment headers, backpressure, cancellation/scope rotation, and error behavior before/after the response commits (`bifrost-console/internal/browserapi/artifact_download_test.go:192-735`).

### Tests, fixtures, and historical context

- Component tests cover the trace table and its catalog continuation states (`bifrost-console/web/src/observability/Traces.test.tsx:47-108`) and trace detail metadata, acquire success/failure, raw-download link, availability status, and stale scope (`TraceDetail.test.tsx:71-191`).
- Trace-analysis tests are organized around parsing, fixture corpus parity, index/bundle processing, calculations, query paging, cursors, ranges, search, payloads, and service composition under `bifrost-console/internal/traceanalysis/*_test.go`.
- The fixture corpus contains successful, repeated-invocation, nested-retry, chunked payload, incomplete, invalid relationship, timing, usage, failure, and malformed evidence cases in `bifrost-console-fixtures/traces/`, with expected projections in `bifrost-console-fixtures/expected/`.
- PR 12’s ticket defines the separate local-analysis-copy and application-pass-through lifecycles. PR 13’s ticket establishes shared, adapter-neutral analysis queries. The roadmap lists PR 14 immediately after those dependencies and identifies hierarchy, timeline, usage, records, payloads, raw download, and evidence links as its delivered experience (`ai/thoughts/tickets/bifrost-console-pr-12-artifact-service.md`; `ai/thoughts/tickets/bifrost-console-pr-13-trace-analysis-services.md`; `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:115-126`).

## Architecture Documentation

The present execution path is:

`React trace catalog/detail → browser JSON or download route → target scope/artifact service → acquired artifact lease → traceanalysis.Service → processed trace bundle components`.

The raw-download branch instead follows:

`React attachment link → authenticated browser GET → current target scope → application artifact stream → browser`, without a local artifact-cache lookup or mutation.

Application adapter REST, artifact-streaming, problem, and consumed NDJSON boundaries have executable fixtures under `bifrost-console-fixtures/`, browser API tests, console integration tests, and browser E2E tests. The framework lens classifies traces as **Ephemeral diagnostic formats**; Go trace-analysis DTOs, browser routes, and current internal wiring are technically exposed implementation surfaces whose deliberately supported contract status is not established by these declarations alone. The existing browser REST and frontend DTOs constitute observed, consumed browser contracts, while the repository supplies no separate supported-contract allowlist in the reviewed sources.

## Related Research

No prior documents were present in `ai/thoughts/research/` when this research was run.

## Open Questions

- The current source contains no PR 14 browser endpoint or frontend DTO for the existing trace-analysis query service; their exact adapter mapping is not yet present in the codebase.
- No trace-explorer component, URL-state schema, tree/table/tab primitive, timeline renderer, or explorer-specific virtualization component is present in the reviewed frontend source.
- The repository currently has raw download as an immediate attachment link. An explorer-specific confirmation interaction is not present in the existing trace detail component.
