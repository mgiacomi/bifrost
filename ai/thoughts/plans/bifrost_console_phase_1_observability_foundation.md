# Bifrost Console — Phase 1 Observability Foundation

## Status

Initial design direction. This document records the decisions and constraints established during early product planning. It is not yet an implementation plan.

## Feature context

Bifrost needs a standalone web console that helps developers understand:

- which skills a running Bifrost instance provides;
- which executions are currently in flight and what Bifrost is doing at a summarized level;
- how a completed execution flowed through skills, planning, models, tools, validation, and guardrails;
- where time and usage accumulated; and
- where and why an execution failed.

The console replaces the legacy `bifrost-cli` trace viewer with a broader supported product. Detailed trace debugging remains an important part of the product, but it sits alongside live monitoring and skill discovery. The old CLI is deprecated, receives no compatibility commitment from this design, and will be removed after the Bifrost Console implementation is complete.

This phase establishes the engine and server-side observability foundation. A later phase will design and build the standalone console UI.

## Meaning of developer audit

In this feature, **developer audit** means reconstructing an execution well enough to understand what Bifrost did, why it followed that path, where time and usage accumulated, and where or why it failed.

It does not initially mean:

- business or regulatory auditing;
- compliance-grade retention;
- immutable or tamper-evident records;
- historical analytics;
- cross-version trace compatibility; or
- long-term archival guarantees.

Those concerns may be explored as separate future capabilities if real requirements emerge.

## Phase 1 objective

Create one supported observability seam around the canonical trace flow so that Bifrost can publish safe, ordered live activity without changing its execution model or duplicating instrumentation throughout the engine.

The phase should provide the backend capabilities required by the future console:

1. skill catalog inspection;
2. active-execution discovery and snapshots;
3. a summarized live activity stream;
4. current-process trace discovery and loading; and
5. a secured server boundary that exposes those capabilities to a standalone application.

## Architectural direction

The existing trace remains the canonical detailed execution record. The journal and live activity feed are separate projections over the same trace vocabulary.

```text
engine activity
    -> canonical trace record
        -> trace persistence
        -> completed journal projection
        -> live activity projection
```

The journal answers which developer-facing diagnostic entries belong in a completed execution view. The activity projector answers what concise update an operator should see now.

The activity feed must not become a second execution record or a separate instrumentation system.

Execution activity is normally projected only from successfully appended canonical records. There is one narrow lifecycle exception: if the existing core completion operation fails before a trustworthy canonical completion activity can be released, the optional observability consumer emits one bounded `EXECUTION_OBSERVATION_ENDED` activity from the guaranteed execution-cleanup hook. That activity reports only that observation ended incompletely because core diagnostic finalization failed. It is not appended to the trace, does not invent an execution outcome, and does not create a second general instrumentation vocabulary.

## Contract boundaries

Phase 1 introduces two intentionally different external representations:

1. **Live activity contract:** A small, current-version console protocol for snapshots and incremental activity. It should be understandable without knowledge of Bifrost implementation classes.
2. **Diagnostic trace representation:** The detailed current-release execution record used for developer debugging. Its schema may evolve with Bifrost and should not acquire historical or compliance compatibility guarantees merely because the console can inspect it.

The server module translates internal skill, session, activity, and trace state into external DTOs. Existing internal classes should not become the HTTP contract by accidental serialization.

The server reports one version for this integration: `consoleCompatibilityVersion`. Its value is exactly the complete Bifrost product release string shared by the Java framework and Go console, including any qualifier. For example, release `1.42` reports `1.42`, and release `1.42-SNAPSHOT` reports `1.42-SNAPSHOT`. The Go console and application adapter must report the same exact string; a mismatch is rejected clearly before snapshots, live activity, or trace acquisition are used. Engine, observability-adapter, Go-console-release, and trace-schema versions are deliberately not additional reported facts or compatibility gates.

`consoleCompatibilityVersion` is the release-derived umbrella for every application-to-console contract, including REST snapshots, SSE activity, acquisition behavior, problem meanings, and the finalized trace artifact consumed by Go. It is not separately configurable, manually incremented, or maintained as an independent protocol counter. Every distinct Bifrost release string produces that same distinct compatibility value in Java and Go, whether or not that release changes this boundary.

The Java adapter and Go console are separate runtime components but one coordinated Bifrost release unit. They always receive the same product release string and are published together, even when a release changes only one component's implementation. That release string is the `consoleCompatibilityVersion`; there is no second compatibility number. The authenticated instance-status request is the compatibility probe: Go initially reads only its stable top-level `consoleCompatibilityVersion`, interprets the remaining status only after an exact match, and makes no other observability request on mismatch.

## Existing functionality to reuse

Phase 1 should build on the existing:

- session and trace identifiers;
- ordered trace sequence numbers and timestamps;
- frame identifiers, parent relationships, frame types, and routes;
- trace record vocabulary for model, plan, step, tool, validation, evidence, error, and completion activity;
- NDJSON trace persistence;
- trace reading and chunk reconstruction;
- journal summarization rules;
- YAML skill catalog and capability registry; and
- selected legacy CLI trace-tree behavior as a temporary reference for retrospective trace inspection, without preserving its filesystem discovery, types, architecture, or compatibility.

The existing completed `SkillTemplate` observer is not a real-time interface and should not be stretched into one.

## Current implementation facts and consequences

The following facts about the current codebase motivated this design and should be reverified before implementation if the code has changed:

- Every live session owns a canonical execution trace handle.
- Normal engine trace activity converges on the session trace-append methods, even when it originates outside `ExecutionTraceRecorder`, such as execution-state recording. This is why one central publication seam appears feasible.
- Trace records already carry a trace ID, session ID, monotonic sequence within the trace, timestamp, frame and parent-frame identity, frame type, route, metadata, and optional data.
- The NDJSON writer stores records incrementally while an execution runs. Large logical payloads may be represented by an envelope plus storage chunks.
- The reader reconstructs chunked payloads for retrospective inspection.
- The journal is a replay-derived projection over the canonical trace, not a mutable runtime append target.
- Requesting a journal during execution currently rereads and projects the trace. A finalized journal is retained in the session at completion.
- Canonical trace finalization and completed-journal projection are existing core execution-cleanup behavior. If either fails after an otherwise successful execution, the cleanup failure currently propagates to the caller. If execution has already failed, the cleanup failure is attached to the original failure as a suppressed exception. Phase 1 preserves this behavior.
- The journal selects only a developer-facing subset of trace records. It does not currently project frame lifecycle, most model lifecycle, step lifecycle, evidence, or trace lifecycle records.
- Trace files currently use temporary filesystem storage. `NEVER`, `ONERROR`, and `ALWAYS` determine whether the file survives completion; a file may exist during execution and then be deleted.
- The public `SkillTemplate` observer receives a completed execution view rather than a continuous stream.
- The YAML skill catalog and capability registry can describe successfully registered skills. Invalid startup configuration usually prevents the application from becoming a running instance that the console could inspect.
- Bifrost's supported public API is intentionally narrow before 1.0. The observability boundary should be designed deliberately rather than exposing existing internal registries or trace classes directly.

These facts have several consequences:

1. Live activity must work independently of whether a completed trace is retained.
2. The console cannot assume every successful execution will have a trace available through the current application process under the configured persistence policy.
3. The activity projector should consume logical canonical records, not storage chunks.
4. The server should own filesystem and trace-format knowledge; the browser should not.
5. The activity feed needs a push path rather than repeatedly replaying the growing trace file.
6. Skill status should report facts available from a successfully started instance, not promise to diagnose configurations that prevented startup.

## Decisions and rationale to preserve

### Do not tail trace files for live monitoring

File tailing would couple the console to local filesystem access, temporary paths, NDJSON layout, partial writes, payload chunking, and retention-driven deletion. It would also fail for a separately deployed console without shared storage. Live publication should therefore happen in process at the canonical append seam.

### Do not make the journal the live event bus

The journal remains valuable as a completed developer-facing projection and as a source of shared summarization logic. It is not the live transport because it is replay-derived, omits important lifecycle events, and would require repeatedly rereading a growing trace.

### Do not instrument every engine subsystem again

The existing trace vocabulary already captures the useful execution activity. Phase 1 should add publication at the convergence point rather than build a parallel set of monitoring calls that can drift from trace behavior.

### Do not expose raw trace records as the default live experience

Raw records are too detailed for a useful live experience and are coupled to current engine internals. A small activity projection provides a comprehensible live narrative while a finalized trace remains available through the producing process's current-process catalog for detailed inspection.

### Use REST and Server-Sent Events initially

REST fits catalog and snapshot retrieval. Server-Sent Events fit ordered one-way monitoring, browser reconnection, and cursor delivery. WebSockets add protocol and lifecycle complexity without an initial bidirectional control requirement.

### Use one operator authority initially

`BIFROST_OPERATOR` grants the complete developer observability experience, and the initial application observability access key establishes that sole authority. Separate permissions were considered because trace payloads may contain business data, but no current requirement justifies a permission matrix. Centralized authorization preserves a future extension point without exposing premature configuration.

### Keep the console separate from the observed application

The web console is intended to run as its own project. A small optional Bifrost server module exposes supported observability contracts from the observed application. The console must not depend on application internals, classpath access, or a shared local filesystem.

### Preserve canonical trace and journal failure semantics

Canonical trace append, canonical trace finalization, and completed-journal projection remain core runtime responsibilities with their existing failure and suppressed-exception behavior. Phase 1 does not reclassify those failures as optional console-observability failures and does not make an otherwise failing core finalization appear successful.

The isolation rule applies to failures introduced by the new optional observability capabilities: live projection, active-registry and replay-buffer publication, trace-catalog registration, execution-completion activity enrichment, REST/SSE exposure, and client delivery. Those failures must not replace, suppress, or otherwise change the result produced by the existing execution and canonical-finalization path.

## Required observability components

### Canonical record publisher

Add a framework-owned publication mechanism at the central trace append path. It publishes from the logical trace record after the complete canonical append succeeds, including any required payload-chunk writes, without requiring instrumentation changes across individual engine components. The append path must retain the logical record long enough to project it; the payload-less storage envelope returned for a chunked record is not by itself a sufficient publication input.

The initial publication transaction is deliberately a small synchronous in-memory operation inside the same per-session serialization boundary that orders canonical appends:

1. construct the logical trace record once;
2. append its canonical storage representation completely;
3. after append success, deterministically project the logical record into a bounded active-execution state update and zero or one bounded activity envelope;
4. apply the state update to the active registry and append the activity envelope, when present, to the bounded process-local replay buffer; and
5. signal independently running delivery work that new activity is available.

Only the bounded in-memory projection and state publication belong in the synchronous execution path. That code must perform no filesystem or network I/O, invoke no subscriber or application callback, wait for no subscriber capacity, retain no logical payload after the call, and perform no unbounded analysis or arbitrary payload copying. Network delivery, subscriber fan-out, write deadlines, and slow-client handling occur asynchronously by reading the already-published bounded state. A full replay buffer overwrites its oldest entries according to the cursor-expiration contract; it does not block execution or reject the newest current activity.

This design intentionally avoids a general asynchronous publisher queue, durable outbox, projection retry loop, or transactional commit layer. Such machinery would add ordering, overflow, shutdown-drain, and reconciliation behavior disproportionate to a process-local developer tool. Synchronous bounded projection preserves per-execution canonical ordering and makes the active registry and replay buffer agree as closely as their documented non-atomic snapshot semantics require.

An unexpected activity-projection, active-registry update, or live-delivery-buffer publication failure is contained at this boundary and never propagates into execution. The adapter records a sanitized diagnostic and changes the process-local `liveMonitoringAvailable` value from `true` to `false`. Instance status reports this boolean. When it is `false`, active-execution snapshots and new live-activity requests fail with `LIVE_MONITORING_UNAVAILABLE` rather than serving state known to be incomplete. If an SSE response is already open when the failure occurs, the adapter closes that stream; it cannot replace an already-started response with an HTTP problem, and it does not fabricate a Bifrost activity event for the adapter failure. The next status or subscription attempt exposes the unavailable condition. Skill-catalog, current-process finalized-trace-catalog, and trace-download operations remain available because they do not depend on the live in-memory projection. The initial release does not retry, reconstruct, or automatically recover that projection; application restart may be required to make live monitoring available again. This is a fail-closed accuracy guard, not a subsystem health model, recovery state machine, execution status, or troubleshooting protocol.

The publisher is internal infrastructure, not a general-purpose application event SPI.

Each execution owns one optional internal observation handle. Successful canonical appends update that handle, and the guaranteed core completion boundary closes it exactly once. The handle alone coordinates terminal activity, current-process catalog publication, and active-entry removal. When observability is disabled, the handle is a no-op.

This handle is a small framework-owned lifecycle coordinator, not a public observer SPI, general event bus, durable queue, retry or reconciliation mechanism, or independently persisted state machine. The active registry, bounded replay buffer, and current-process trace catalog remain separate owned components; they do not independently decide that an execution has started or ended.

### Activity projector

Project canonical trace activity into small developer-facing updates such as:

- execution started or completed;
- planning started or plan created;
- skill started or completed;
- model interaction started or completed;
- tool activity started, completed, or failed;
- validation passed, retried, or failed;
- quota, timeout, or guardrail termination; and
- execution failure.

Activity language should describe observable execution behavior. It must not claim to expose model thinking or hidden chain-of-thought.

Shared journal functionality such as JSON handling, error summarization, and tool identification should be reused or extracted where appropriate. The activity projection remains distinct because the current journal intentionally omits several lifecycle and phase records required for live monitoring.

### Registered skill YAML catalog

Expose each successfully registered YAML skill as its registered skill name, a normalized skills-root-relative `sourcePath`, and the unchanged YAML file content. For example, a resource discovered as `classpath:/skills/incidents/check_dns.yml` reports `incidents/check_dns.yml`, never its absolute filesystem path, classpath URL, JAR location, or configured discovery root. `sourcePath` uses `/` separators, has no leading slash, drive, scheme, `.` segment, or `..` segment, and is descriptive metadata rather than a supported filesystem locator.

The collection endpoint returns concise entries in deterministic registered-skill-name order under the common pagination contract below. Each entry contains the unique registered skill name, `sourcePath`, and a server-generated link using the registered skill name for lookup. The detail response returns that `sourcePath` and unchanged UTF-8 YAML content. The adapter never resolves a caller-supplied `sourcePath` against a filesystem; it retrieves only the resource already associated with the registered skill name. Multiple configured discovery roots may produce the same descriptive `sourcePath` without making it an identity collision because registered skill names are already unique.

The YAML content is the skill representation. The adapter does not create an external effective-definition DTO or add parsed defaults, resolved model connections, provider identifiers, compiled evidence contracts, Java objects, or registration machinery. Go, browser, and MCP may display, syntax-highlight, search, or transmit the YAML as text, but they do not normalize, reserialize, or treat a separately parsed model as the authoritative skill definition. Adding a YAML field does not independently create a compatibility-version decision; `consoleCompatibilityVersion` changes with the containing Bifrost release regardless. Only a change to the skill-listing or file-retrieval protocol creates coordinated Java-to-Go contract work within that release.

### Active-execution registry

Maintain an on-heap, concurrent registry containing one bounded-size current snapshot per live execution. A snapshot should be sufficient to render an active-execution list and recover browser state after connecting or reconnecting.

The initial bounded summary fields are:

- session and trace identifiers;
- entry skill;
- start time and elapsed time;
- current phase;
- active skill/frame path;
- execution status;
- model, tool, and skill invocation counts;
- current usage and configured limits; and
- latest concise activity summary.

The registry is operational state, not durable history. The initial release does not impose an independent cardinality limit: registry size follows actual engine concurrency and entries must be removed on every execution-ending and cleanup path. Each entry remains small and bounded; it must not accumulate event history, trace payloads, or other data whose size grows with execution duration. Total registry memory is therefore lifecycle-proportional to authoritative live engine executions rather than globally fixed. Bifrost currently has no global execution-concurrency ceiling for this design to inherit. An arbitrary observability cap would silently hide executions precisely under high concurrency, so partial or omitted registry entries are not initial behavior. Off-heap or temporary-file registry storage is not part of the initial design. A cardinality ceiling and explicitly partial snapshots may be reconsidered only if measured concurrency and registry overhead demonstrate a need.

Each entry receives an instance-local monotonically increasing `registryOrdinal` when it enters the registry. The ordinal is pagination metadata, not an execution identifier or public cross-instance sequence. It supports newest-first keyset traversal under the common pagination contract below without copying the registry into a transactional snapshot.

The active-execution surface also supports direct lookup by `sessionId`. It returns the same bounded current registry snapshot used by the paginated collection, not event history, an active trace, or a frame tree. The snapshot contains the session and trace IDs, entry skill, start time, current phase, bounded active skill path, execution status, model/tool/skill counts, current usage and configured limits when available, and latest concise activity summary. After the execution leaves the active registry, lookup returns `NOT_FOUND`; retrospective inspection uses `traceId` and the current-instance trace catalog when available.

### Live delivery buffer

Maintain a bounded cursor-based buffer for short reconnect recovery. The canonical trace remains the source for full detail; the reconnect buffer is not another trace store.

The initial recovery contract is deliberately small:

- each application startup generates one `instanceId` that changes on every restart and identifies that running application instance;
- the multiplexed activity stream uses one instance-local monotonically increasing cursor;
- the first active-execution baseline page reports the `instanceId`, a `resumeCursor` observed near first-page collection, an observation time, and the registry high-water mark carried by its opaque continuation;
- a client subscribes after that `resumeCursor`; and
- an expired cursor or changed `instanceId` returns `STALE_CURSOR` and requires a fresh paginated baseline.

Canonical trace sequence numbers remain per-trace facts and are not replaced by the delivery cursor.

The live registry and activity stream are instance-local, best-effort, eventually consistent developer-monitoring views, not a transactional or durable streaming service. Baseline pages may contain entries observed at different moments and are not an atomic cut of registry state and stream position. The server reads the current `instanceId`, delivery cursor, and highest registry ordinal near first-page collection and returns the public facts as `instanceId`, `resumeCursor`, and `observedAt`, with the high-water position retained inside the opaque pagination continuation. Later pages traverse current entries at or below that high-water mark without copying the concurrent registry. The stream cursor is a practical reconnect position, not a claim that every returned entry is complete through that cursor.

The console subscribes after the first page's `resumeCursor`, applies later activity in cursor order while traversing remaining baseline pages, tolerates duplicate delivery, and refreshes current active state after a disconnect, expired cursor, changed `instanceId`, or low-frequency refresh interval. These refreshes intentionally heal ordinary live-view and pagination races. An execution may therefore appear slightly late, remain visible briefly after completion, or be corrected by a later event or refresh. Recovery restores current operational state; it does not reconstruct intermediate narrative events that were not delivered.

An expired upstream cursor or changed `instanceId` is a continuity boundary, not merely a reconnect hint. A console consumer must not combine activity retained before that boundary with activity received after baseline recovery as though the delivery were continuous. Phase 2 defines the deliberately simple Go behavior: clear the current recent-activity window, obtain a fresh active baseline, and admit only activity from the new continuous interval.

Delivery pressure is handled by disconnect and refresh rather than silent unbounded growth. Each subscriber has a bounded pending-delivery allowance. If a subscriber cannot keep up or a write deadline expires, the server closes that subscriber without affecting the engine or other clients. The client may use buffered replay when available and otherwise obtains a fresh paginated baseline. Exact event-count, byte, write-deadline, and refresh-interval values are implementation-planning decisions.

The application adapter also applies one small process-wide admission guard to long-lived activity delivery. The initial process accepts only a finite fixed number of simultaneously open authenticated SSE subscriptions. Admission occurs after successful application-observability authentication and before the stream begins. A request beyond that capacity is rejected immediately with the application problem code `LIMIT_EXCEEDED` and HTTP `429`; it is not queued, and existing subscribers are unaffected. A slot is released whenever its stream closes. This fixed limit prevents an accidental reconnect loop or authenticated client from creating an unbounded number of adapter-owned streaming connections. The exact initial constant is an implementation-planning decision validated against representative console use; it is not dynamically configurable in the initial release. The initial release does not add per-client identity, fairness, rate calculations, or configurable admission policies.

Phase 1 does not require an atomic snapshot boundary shared with cursor advancement, a transactional projection or commit abstraction, a durable event log, an internal message broker, exactly-once delivery, automatic projection reconstruction, or a projector reconciliation state machine. The single fail-closed `liveMonitoringAvailable` value defined by the canonical publisher prevents silently stale live results without introducing a recovery protocol. Unexpected projection failures remain isolated from execution and are logged without sensitive diagnostic content. The finalized canonical trace remains the detailed debugging fallback when it is retained.

### Current-process trace catalog

Provide a supported way to list and stream finalized traces made available by the current running application process. The initial catalog is populated from core-issued finalized-artifact descriptors and may obtain cheap ordinary metadata such as current file size from the referenced artifact, but it does not discover traces by scanning storage. Catalog identifiers and ordinary metadata must not use filesystem paths. The browser does not understand NDJSON or trace storage layout; the Go console consumes the current trace artifact covered by `consoleCompatibilityVersion` and owns console-side trace analysis.

The catalog is deliberately **current-process-only**. It is not a history of files retained by the core trace subsystem and it is not reconstructed after application restart. A new process starts with an empty catalog and publishes only descriptors finalized by that process. A trace file that remains physically present because of `ALWAYS`, errored `ONERROR`, shutdown, a crash, or failed deletion is not a supported console resource unless the current process itself finalized and cataloged it. Core filesystem retention and console discoverability are separate facts: `ALWAYS` means only that normal core retention does not delete the file; it does not mean that a later application process or console can discover or inspect it.

The core execution-trace subsystem exclusively creates, writes, finalizes, retains, and deletes the canonical trace artifact. Enabling application observability does not relocate, copy, rewrite, adopt, or assume ownership of that file. The optional adapter receives a bounded finalized-artifact descriptor after successful core completion and maintains only current-process catalog metadata that refers internally to the exact core-owned file. It never receives an arbitrary deletion operation and never applies the persistence policy itself.

The trace artifact is deliberately the finalized UTF-8 NDJSON trace file exactly as written by Bifrost. Phase 1 removes the existing `TraceRecord.schemaVersion` component, `CURRENT_SCHEMA_VERSION` constant, constructor/reader/writer propagation, and serialized NDJSON property from the framework before establishing the Java/Go fixture agreement. The server streams the resulting bytes without repackaging records, reconstructing payloads, or creating a new manifest, archive, envelope, digest, completeness marker, version field, or container format. Each nonblank line is one serialized `TraceRecord`, and trace compatibility is covered exclusively by `consoleCompatibilityVersion`.

The exact stored artifact may contain a filesystem path recorded by the trace implementation, and that path may therefore appear in an authenticated raw-record view or raw artifact download. This is an intentional exception to the rule against using filesystem paths in console contracts. The path is diagnostic content inside the canonical trace, not a resource identifier or a supported lookup mechanism. The application adapter and Go console do not add code to remove, redact, normalize, or rewrite it. Summaries, catalogs, links, and ordinary DTOs continue to use opaque trace identifiers and must not depend on or separately expose the path.

The artifact includes payload envelopes and `PAYLOAD_CHUNK_APPENDED` records exactly as stored. Chunking is part of the current trace-record schema. Java remains responsible for writing the canonical file; Go is responsible for reconstructing logical payloads for analysis while retaining raw envelope and chunk records for framework-level inspection. The application server must not create a second rewritten “logical trace” download.

The download uses ordinary HTTP metadata rather than an artifact manifest: `Content-Type: application/x-ndjson; charset=utf-8`, a safe attachment filename derived from the opaque trace ID rather than a filesystem path, and `Content-Length` when known. The catalog or response may expose `sizeBytes` so clients can enforce limits; it does not expose a separate trace-schema version. Bifrost does not require application-level compression or an integrity digest initially; ordinary HTTP content encoding, if used by the host server, remains a transport concern.

The application adapter accepts only a finite fixed number of concurrent authenticated trace-artifact downloads across the process. Admission occurs after authentication and resource lookup but before the response body begins. An excess request is rejected immediately with `LIMIT_EXCEEDED` and HTTP `429`; it is not queued and does not interrupt a download already in progress. A slot is released when streaming completes, fails, or is cancelled. The exact initial constant is an implementation-planning decision validated against representative trace use; it is not dynamically configurable in the initial release. This is a fixed guard for the adapter-owned file and connection lifetime, not a generalized request scheduler or bandwidth quota.

Only traces finalized by the existing trace lifecycle are added to the catalog. The server need not reread and certify every file before download. A consumer must validate record JSON and required fields, trace and session identity consistency, Java-defined ordering, vocabulary, and chunk completeness while streaming. Malformed or unknown record content, inconsistent identity or ordering, or incomplete/mismatched chunks produce the Go `INVALID_ARTIFACT` service error rather than a silently partial trace view. Raw attachment download may remain available for framework debugging even when Go analysis rejects the artifact.

Cross-language contract fixtures are ordinary representative NDJSON files produced by the Java writer, not a new interchange package. They should cover a normal finalized trace, chunked text and JSON payloads, malformed or truncated input, unexpected record content, and missing or mismatched chunks. Java tests establish writer semantics and Go tests establish equivalent parsing and reconstruction for the exactly matched `consoleCompatibilityVersion`. The fixture suite associates fixtures with that umbrella version externally; individual records do not carry a version property.

Detailed frame, record, and payload inspection is available only after a trace is finalized and while it remains in the catalog. Active executions are inspected through active state and the activity stream.

Phase 1 owns only current-process catalog availability and streaming of the immutable finalized artifact. It does not create or understand Go acquired-artifact handles, acquired-copy retention, browser or MCP continuation tokens, payload ranges, query pinning, or console-cache eviction. A download opened before application expiration may finish according to the server rule below; all multi-request inspection continuity after acquisition is a Go console responsibility.

Each catalog entry receives a process-local monotonically increasing `catalogOrdinal` when the core-issued finalized descriptor is successfully published to the catalog. The catalog retains one small bounded metadata record per currently cataloged artifact and never duplicates trace contents merely to support listing. Catalog metadata contains only the opaque identities, completion and availability facts, ordinals, and cheap ordinary artifact metadata required for discovery and acquisition; it does not contain trace records, payloads, reconstructed data, indexes, or growing per-trace history.

Application-side catalog metadata is bounded by age rather than by an independent entry-count or aggregate-memory ceiling. The application observability YAML/property setting `bifrost.observability.trace-catalog-metadata-ttl` controls that age and defaults to `24h` when omitted. The value is resolved and validated at application startup with the other observability settings. Each successfully published entry receives a catalog expiration derived from its publication time and this TTL. At or after that expiration, the adapter immediately excludes the entry from lookup, listing, and new acquisition and reclaims its metadata within a bounded cleanup lag. The exact sweep or scheduling mechanism and cleanup cadence are implementation-planning decisions, but cleanup may not depend indefinitely on a caller requesting that particular entry. The adapter does not retain a tombstone.

The initial release deliberately imposes no hard catalog-entry-count or aggregate catalog-metadata byte limit. A high completion burst can therefore create many simultaneously live metadata entries within the configured TTL, and total catalog memory remains proportional to the number of current-process completions still inside that window. This is an accepted operational tradeoff based on each entry remaining small and bounded. The adapter does not reject a completed execution, hide a nonexpired entry, evict an entry early, or fail live execution merely to enforce an undisclosed metadata cap. Application owners who require a smaller catalog-memory window may configure a shorter TTL and restart the application. A future cardinality or aggregate-byte limit requires an explicit contract for early eviction and discoverability rather than being introduced as an implementation detail.

Catalog-metadata retention does not own, extend, shorten, or otherwise change canonical trace-file retention. Canonical artifact bytes remain governed exclusively by the core-owned `NEVER`, `ONERROR`, and `ALWAYS` behavior plus any core-calculated completion-grace expiration. Removing catalog metadata never deletes the trace file. Conversely, physical retention of an `ALWAYS` or errored `ONERROR` file after catalog expiry does not preserve console discoverability. HTTP pagination bounds individual responses but does not impose a catalog cardinality limit.

### Application collection pagination

Current-process trace summaries, active-execution summaries, and registered-skill-file entries use one bounded keyset-pagination contract. The initial values are:

| Limit | Value |
|---|---:|
| Default page size when omitted | 1,000 complete items |
| Maximum requested page size | 5,000 complete items |
| Maximum uncompressed serialized JSON response | 16 MiB |

A caller may request `pageSize` from `1` through `5,000`; larger, zero, or negative values are rejected clearly rather than silently clamped. A response stops before adding an item that would exceed the 16 MiB uncompressed JSON limit and may therefore contain fewer items than requested. Summary DTOs must remain bounded-size; the server never cuts an item or JSON value in half. Skill YAML content, individual execution details, raw trace streams, trace-record pagination, and reconstructed-payload ranges use their separate detail or streaming contracts.

Every collection page returns complete `items`, `hasMore`, an opaque `nextCursor` when more traversal is possible, and `observedAt`, in addition to the centrally supplied `instanceId`. Cursors use keyset position rather than numeric offset and bind to the application instance, endpoint, ordering, filters, and first-page high-water mark. A malformed cursor or reuse against another endpoint, ordering, filter, or incompatible query produces `INVALID_CURSOR`; a previously valid cursor that can no longer continue produces `STALE_CURSOR`. Cursor representation is an implementation detail and need not create server-side pagination sessions, durable state, or cryptographic attestation beyond the authenticated protocol.

Collection traversal is best-effort, not a database snapshot:

- **Skills:** definitions are immutable for the application instance and summaries sort by skill name ascending. Continuation resumes after the last returned name.
- **Current-process traces:** the first page captures the current highest `catalogOrdinal`; entries sort newest first and later pages include only ordinals at or below that high-water mark. Newly cataloged traces appear on a later refresh rather than midway through traversal. A trace removed by retention between pages may be absent; Phase 1 creates no page snapshot or tombstone.
- **Active executions:** the first page captures the current highest `registryOrdinal` and reports the activity-stream `resumeCursor`. Entries sort newest first and continuation pages include only registry ordinals at or below that high-water mark. The client begins or resumes SSE after that `resumeCursor` while retrieving remaining pages. Executions started later arrive through SSE rather than appearing midway through the baseline traversal. An execution completed before its page is read may have left the registry, but its post-cursor completion activity supplies the transition when still replayable; ordinary periodic refresh continues to heal best-effort races.

These high-water marks prevent newly inserted entries from shifting later keyset pages without copying either collection. They do not freeze item contents, prevent retention deletion, guarantee delivery after a replay gap, or create transactional consistency between pages and activity.

When observability initializes successfully, it supplies the core trace subsystem with a process-startup minimum-retention duration named `completion-grace-ttl`, initially defaulting to `15m`. A finalized trace that the existing `NEVER` or successful `ONERROR` policy would otherwise delete remains core-owned and available during that grace period. The core calculates the exact expiration as part of successful finalization and owns the delayed deletion. `ALWAYS` and errored `ONERROR` traces have no core grace expiration because their normal persistence policy already retains them. A grace TTL of `0` preserves the existing synchronous deletion behavior.

Application acquisition remains available only until the earlier of the core artifact expiration, when one exists, and the independent catalog-metadata expiration. The adapter reports that effective boundary as `applicationTraceExpiresAt`. At or after it, the adapter refuses new downloads, excludes the entry from catalog results, and reclaims the metadata within the catalog's bounded cleanup lag. A download opened before the effective expiration may finish. Expiring catalog metadata does not delete or modify a core-retained file, and physical deletion remains the core trace subsystem's responsibility.

The policies describe normal filesystem retention by the producing process, not durable archival, supported console history, cross-process discovery, or secure erasure. `ALWAYS` means only that normal core retention does not delete the finalized trace; it does not promise that a later application process will catalog or serve it. Grace deletion is deliberately best-effort and process-local. Shutdown cancels pending grace-deletion work and closes current resources but does not perform special shutdown deletion, search for trace files, or recursively clean storage. A shutdown or crash before the grace timer runs therefore abandons the exact grace-held `NEVER` or successful `ONERROR` file on disk. This is an accepted initial developer-tool lifecycle tradeoff, not an unfinished recovery requirement. Later processes do not scan, re-catalog, adopt, or delete those files. Physical presence after process termination does not imply supported console availability. No durable deletion queue, startup scavenger, tombstone, or historical non-retention index is required.

Execution outcome and application trace availability are independent facts. Phase 1 must not create combined statuses such as `COMPLETED_WITH_ARTIFACT`, `FAILED_WITHOUT_ARTIFACT`, or a public `FINALIZING` execution state. Existing execution outcome vocabulary continues to describe whether the execution succeeded, failed, timed out, reached quota, or ended for another execution reason. Application trace availability separately describes only whether the finalized diagnostic artifact can currently be acquired from the current-instance catalog. It says nothing about core filesystem retention, discoverability after restart, or whether Go has already acquired a temporary local copy.

The initial completion ordering is deliberately procedural rather than a new state machine:

1. invoke the existing core completion path, including completed-journal projection, canonical trace finalization with its canonical execution-completion record, and the core-owned retention decision; a successful result includes the exact finalized-artifact descriptor and any core-calculated expiration;
2. if and only if that core completion path returns successfully and its descriptor names a currently retained file, publish the descriptor's metadata in the optional adapter catalog without moving, copying, rewriting, or taking deletion ownership of the artifact;
3. when core completion succeeds, release one execution-completion activity with the execution outcome and application-trace-availability facts known at that time;
4. when core completion throws, discard the pending canonical completion activity and instead attempt to release one observability-only `EXECUTION_OBSERVATION_ENDED` activity with reason `CORE_FINALIZATION_FAILED` and `applicationTraceAvailability: UNAVAILABLE`; and
5. remove the execution from the active registry on the guaranteed observability cleanup path after the applicable terminal activity has been attempted, including when core finalization, catalog publication, or terminal-activity publication fails.

The execution-completion activity reports `applicationTraceAvailability` as `AVAILABLE` or `UNAVAILABLE`, an optional `applicationTraceExpiresAt`, and, when useful and safely known, a concise unavailability reason such as `NOT_RETAINED` or `CATALOG_PUBLICATION_FAILED`. If it reports `AVAILABLE`, the artifact must already be obtainable from the current-instance catalog. `applicationTraceExpiresAt` is the earlier of the catalog-metadata expiration and any core artifact expiration, so it remains finite even for `ALWAYS` and errored `ONERROR` artifacts. There is no later asynchronous `TRACE_AVAILABLE` transition in the initial design. Catalog publication or completion-activity publication failure is isolated from execution and does not alter the result of the already successful core completion path. A zero grace period combined with a policy that does not retain the trace simply produces `UNAVAILABLE`.

The canonical execution-completion record follows the central record-publication seam after append, but its outward execution-completion activity is held as a bounded local completion value until the existing core completion operation returns successfully and the optional catalog work supplies the application-trace-availability facts above. It is then enriched and released once through the ordinary bounded replay buffer.

If the core completion operation throws, its current propagation or suppression behavior is preserved, the pending canonical completion activity is discarded, and no artifact is adopted into the console catalog. The same guaranteed internal cleanup hook that removes the active entry conveys only a small completion disposition to the optional observability consumer: normal completion or core finalization failure. When observability is disabled this hook has no enabled consumer; it is not a public SPI. For core finalization failure, the consumer attempts to publish `EXECUTION_OBSERVATION_ENDED` immediately before removal. The event contains the session and trace identifiers, reason `CORE_FINALIZATION_FAILED`, `applicationTraceAvailability: UNAVAILABLE`, observation time, and the last safely known canonical sequence when available. It may include an execution outcome only when that outcome was independently established before cleanup; absence means the outcome is not trustworthy. It contains no exception object, stack trace, or application diagnostic payload. Detailed cause remains in ordinary application diagnostics under the existing logging and exception behavior.

Successful publication of this truthful per-execution terminal event means the core failure does not by itself set `liveMonitoringAvailable` to `false`. Failure to project or publish the event follows the existing fail-closed live-publication rule: the active entry is still removed and `liveMonitoringAvailable` becomes `false`. The adapter does not retry the event, retain a terminal tombstone, or create a recovery workflow.

The adapter must not emit an early un-enriched completion activity followed by a second availability event. A core completion attempt produces at most one outward terminal activity: the enriched canonical execution-completion activity after successful core completion, or the observability-only `EXECUTION_OBSERVATION_ENDED` activity after core finalization failure. This is a narrow terminal-release rule, not a public execution state or general asynchronous workflow.

A nonzero completion grace necessarily defers a core retention deletion that would otherwise occur at completion. The core may use one small lifecycle-owned scheduler with one bounded task per grace-held trace; it does not create a durable deletion queue, scan the filesystem, delegate deletion to the adapter, or drain pending grace deletions during shutdown. A failure in that later TTL-driven deletion is logged without retry and cannot retroactively change an execution result that has already returned. Process shutdown or crash before the task runs likewise abandons the file without retry or later-process cleanup. The adapter still treats the descriptor as expired or unavailable after the producing process ends and refuses new downloads, so an abandoned file does not extend supported availability. This is the accepted narrow consequence of delayed cleanup, not a general change to canonical trace or journal failure semantics. With a grace TTL of `0`, the existing synchronous retention behavior and failure propagation remain unchanged.

These completion facts are ephemeral. After the activity has left the replay buffer, the application is not required to distinguish an expired trace from an unknown identifier because it maintains no durable tombstones. Phase 1 does not add finalization jobs, polling, background retry, or reconciliation for diagnostic artifacts. A visible finalization state should be reconsidered only if measured finalization latency later creates meaningful developer confusion.

### Observability server boundary

Provide an optional server-side module supporting:

- REST snapshots for instance information, skills, active executions, and current-process traces;
- Server-Sent Events for one-way live activity delivery;
- sequence or cursor-based reconnection;
- streaming download of finalized current trace artifacts covered by `consoleCompatibilityVersion`; and
- `consoleCompatibilityVersion` so the standalone console can reject an incompatible application contract.

Instance information reports the startup-generated `instanceId`, release-derived `consoleCompatibilityVersion`, `observedAt`, process-local `liveMonitoringAvailable`, registered-skill count, active-execution count, current-instance cataloged-trace count, effective trace persistence policy, completion-grace TTL, and trace-catalog metadata TTL. These are cheap point-in-time facts, not a transactional snapshot or aggregate health model. It does not add catalog-health, catalog-reachability, readiness, historical-count, or capacity fields. When `liveMonitoringAvailable` is `false`, active-execution snapshots and SSE activity return `LIVE_MONITORING_UNAVAILABLE`. Skill-catalog, current-instance finalized-trace-catalog, and trace-download operations remain available because they do not depend on the live in-memory projection.

WebSockets are not required for the initial observational product. They should be reconsidered only if a future phase adds bidirectional execution control.

## Design requirements for the central seam

The following are mandatory design constraints and must remain visible in later implementation and testing plans:

1. **Execution isolation:** A failure introduced by the new optional live projection, registry, replay buffer, catalog, server, or client-delivery capabilities must never fail, cancel, delay materially, or alter a Bifrost execution. This isolation rule does not change the existing core failure semantics of canonical trace append, canonical trace finalization, or completed-journal projection.
2. **Lock isolation:** The agreed bounded in-memory projection may run inside the per-session serialization boundary to preserve append ordering. Network delivery, subscriber callbacks, per-subscriber fan-out, write deadlines, and any potentially blocking work must not occur while holding the session lock.
3. **Proportional resources:** Observability resources are either explicitly bounded or lifecycle-proportional to authoritative engine and retention state. Delivery buffers, subscriber queues, payloads, per-execution registry entries, and HTTP responses are explicitly bounded. Active-registry cardinality follows actual live engine executions. Trace-catalog cardinality follows current-process completions still inside the configured catalog-metadata TTL and has no independent entry-count or aggregate-byte ceiling. Every catalog entry remains small and bounded and contains no trace contents or growing history. The catalog TTL bounds ordinary lifetime growth without coupling metadata removal to canonical file deletion.
4. **Canonical ordering:** Activities projected from canonical records must preserve canonical trace sequence ordering within an execution. The sole observability-owned `EXECUTION_OBSERVATION_ENDED` exception is released only after core completion has failed and immediately before active-entry removal; it does not participate in canonical sequence ordering.
5. **Append integrity:** State and canonically projected activity publish only after the complete corresponding canonical trace append succeeds, including required chunk writes. Projection consumes the logical record rather than a payload-less chunk envelope. `EXECUTION_OBSERVATION_ENDED` is permitted only when core finalization prevents release of a trustworthy canonical completion projection and must remain explicitly noncanonical.
6. **Logical payload projection:** Live projection must operate on the logical record before trace-storage chunking hides or splits large payloads.
7. **Explicit lifecycle:** One optional internal observation handle belongs to each execution. It receives only successfully appended logical canonical records and is closed exactly once by the guaranteed core completion boundary. That handle alone coordinates active-entry establishment and removal, terminal-activity release, and current-process catalog publication, including exceptional completion paths. Disabled observability supplies a no-op handle.
8. **Reconnect behavior:** Live delivery must support cursors and bounded replay, with defined behavior when a requested cursor has expired.
9. **Projection consistency:** Live summaries should use the same interpretation vocabulary as completed trace/journal analysis, while accepting transient live-view races and missing intermediate activity under the documented best-effort model.
10. **Explicit data policy:** Console authentication secrets are not included in observability results. These are the application observability key and `X-Bifrost-Api-Key` request header, downstream console or MCP credentials, browser pairing/session/CSRF values, and other authentication headers handled by the observability path. Bifrost does not detect or redact a secret that the observed application itself embeds in a skill YAML file, prompt, model data, activity, trace, payload, tool input or output, error, or metadata. All such recorded application content is available to authenticated consumers unchanged. The initial release does not add secret scanning, sanitization, content classification, disclosure tiers, or field exclusion.
11. **Observational scope:** Phase 1 must not introduce start, cancel, retry, mutation, or other execution control operations.
12. **Concurrency correctness:** Multiple simultaneous executions and nested frames within each execution must not leak state or activity into one another.
13. **Completion correctness:** Completion, failure, timeout, quota, and cleanup paths must close the execution's observation handle exactly once and remove the active entry even when core finalization or optional observability publication fails. Existing canonical-finalization failure propagation is preserved. A core completion attempt produces at most one outward terminal activity: successful core completion may release the enriched canonical execution-completion activity after application trace availability is known; failed core completion discards that pending activity and attempts one observability-only `EXECUTION_OBSERVATION_ENDED` activity before active-entry removal. The exceptional activity reports `CORE_FINALIZATION_FAILED` and unavailable diagnostic finalization without inventing an execution outcome. If its publication fails, live monitoring fails closed. Within any released activity, execution outcome and application trace availability remain distinct facts, and an artifact reported as available is already obtainable from the current-instance catalog.
14. **Independent clients:** One client disconnecting or falling behind must not affect other clients or the engine.
15. **Proportional guarantees:** Live monitoring is process-local, best-effort, and eventually consistent. It requires ordered published activity, bounded replay, explicit replay gaps, periodic or reconnect-driven refresh, and failure isolation, but not atomic snapshot watermarks, durable or exactly-once event delivery, transactional infrastructure, or automatic projection reconstruction.

The fixed SSE-subscription and trace-download admission limits are the initial release's only application-wide observability admission controls. The catalog-metadata TTL is a lifecycle limit, not an admission control or hard memory ceiling. These controls protect specific adapter-owned lifetimes; they do not claim comprehensive protection against aggregate resource exhaustion across all authenticated requests or host-server resources. General request-rate limiting, authentication-attempt throttling, per-source quotas, catalog cardinality or aggregate-byte admission, fairness, adaptive admission, bandwidth governance, a shared byte or memory budget, and defense against a malicious authenticated operator are outside the initial release. The application owner remains responsible for ordinary listener, reverse-proxy, network, operating-system, and process-level capacity controls.

## Security model

The initial authentication and authorization model is intentionally simple:

- the observability server module is opt-in;
- enabling it requires one configured high-entropy application observability access key;
- the key is presented on every observability request as `X-Bifrost-Api-Key: <key>` and a valid key establishes the sole `BIFROST_OPERATOR` authority;
- a Bifrost operator may inspect the full skill catalog, all active executions, current-process cataloged trace details, and trace downloads;
- requests with a missing or invalid key receive no observability access; and
- trace and activity data are fully available to operators in the initial developer-debugging release.

The access key is a static application secret, not a user account, OAuth token, or console-owned identity. The adapter must fail closed if observability is enabled without a valid configured key, compare credentials safely, never accept the key in a URL, and never log it. A literal configuration value may be used deliberately for local development, but documentation and samples should prefer Spring externalized configuration such as an environment variable or mounted secret. Initial rotation may require application restart.

Application observability uses **acquisition-time authorization**. The adapter authenticates every new REST request and SSE connection before admitting it, but it does not attempt to revoke diagnostic bytes after they have been successfully returned to an authorized consumer. An admitted streaming response is not reauthenticated partway through its body; ordinary connection closure, process restart, or transport failure may still end it. Rejection or rotation of the application key governs later application requests and connections. It cannot invalidate a complete trace copy already acquired by Go, remove data already rendered by a browser, recall an MCP result, or control a consumer's own retention. Downstream console copies remain governed by their separately authenticated and bounded local lifecycle.

All authenticated application-observability HTTP responses carrying diagnostic data use `Cache-Control: no-store`, including snapshots, catalogs, errors containing diagnostic context, and trace downloads. SSE responses use equivalent no-store response policy while remaining compatible with required streaming behavior. This prevents ordinary intermediary or browser HTTP caching; it is not a promise that an authorized client will not retain content after receiving it.

Authorization logic should be centralized behind an observability access service with internally named operations such as runtime inspection, catalog inspection, trace reading, and trace download. Every operation maps to `BIFROST_OPERATOR` initially. The operation names do not create a configurable permission matrix or require operation-level logging; they preserve a future authorization seam.

Existing skill RBAC does not filter the diagnostic catalog for a Bifrost operator. Possession of the application observability key is an explicit trust decision for the whole running Bifrost instance.

Successful authentication is logged at all three boundaries: application observability authentication in Phase 1, browser pairing/session authentication in Phase 2, and MCP access-key authentication in Phase 3. Operation-level access auditing is not initially required and may be added in a future feature.

## Skill status semantics

The console must not treat recent execution failure as proof that a skill is unhealthy.

Initial status terms need precise definitions. Likely candidates are:

- `READY`: registered and executable within the successfully started application;
- `RESTRICTED`: reserved for a future caller-specific catalog, not normally applicable to the all-instance operator view.

The backend exposes registration/readiness facts and recent activity separately rather than inventing a skill-health classification.

## Explicit non-goals

Phase 1 does not include:

- the standalone console UI;
- editing skills or application configuration;
- starting, cancelling, retrying, or mutating executions;
- arbitrary model-provider health probes;
- multi-instance aggregation;
- historical trend dashboards;
- durable or exactly-once live activity delivery;
- a combined execution-outcome and trace-artifact state machine, public `FINALIZING` status, or asynchronous trace-availability workflow;
- compliance-grade audit capabilities;
- durable retention guarantees or cleanup of trace files abandoned when a producing process stops before completion-grace deletion runs;
- comprehensive aggregate resource-exhaustion protection beyond the fixed SSE-subscription and trace-download admission limits and the age-based catalog-metadata lifecycle;
- retroactive revocation or recall of diagnostic data already returned to an authenticated consumer;
- secret detection, automatic redaction, disclosure tiers, data-loss prevention, or a generalized content-classification system;
- backward, forward, cross-release, archival, or third-party trace schema compatibility; or
- exposure of hidden model reasoning.

## Phase 1 completion criteria

Phase 1 is complete when a separately running client can, through the secured server boundary:

1. identify and inspect one running Bifrost instance;
2. traverse its paginated registered-skill catalog and retrieve each skill's relative `sourcePath` and unchanged YAML content;
3. traverse a best-effort current baseline of active executions through bounded pages with an `instanceId`, resume cursor, and observation time, and look up one current active execution by `sessionId`;
4. subscribe to ordered, summarized live execution activity;
5. reconnect using a cursor with documented replay-gap behavior;
6. traverse the paginated current-process trace catalog and load traces currently available from that process;
7. correlate a live execution with its eventual trace when retained; and
8. distinguish stable application problem codes without parsing message text; and
9. distinguish a normal canonical completion activity from the observability-only terminal activity produced when core finalization fails; and
10. do all of the above without observability failures affecting engine execution.

Concurrency, failure isolation, explicit-bound and lifecycle-proportional resource behavior, and authorization must be verified before considering the foundation complete.

## Boundary with Phase 2

Phase 2 will design and build the standalone monitor console. It should consume the Phase 1 contracts rather than depend on Bifrost internals or trace filesystem layout.

Expected Phase 2 product areas are:

- instance overview;
- registered skill YAML inspection;
- active execution list;
- summarized live activity experience; and
- detailed current-process trace explorer.

The Phase 2 design conversation should determine information hierarchy, navigation, interaction patterns, visual language, failure-focused workflows, responsive behavior, and the minimum useful trace-debugging experience.

## Minimal current-release Java/Go agreement

The following settles the smallest activity, reconnect, identity, and native-NDJSON agreement needed before implementation tasks are cut. Additional fields and live kinds remain deferred until the working developer experience demonstrates a need.

### Activity-event contract

The activity envelope is deliberately small. It contains the delivery cursor, startup-generated `instanceId`, session and trace IDs, nullable canonical sequence, timestamp, activity kind, optional execution status, optional frame ID/parent-frame ID/frame type/route, a bounded short summary, and optional bounded details. `consoleCompatibilityVersion` is established by instance status before subscription and is not repeated in every event. Go may branch only on detail fields explicitly documented for that activity kind; other details are bounded display-only diagnostic values rather than a second semantic data model. The observability-only `EXECUTION_OBSERVATION_ENDED` event may have no canonical sequence or execution status; it carries reason `CORE_FINALIZATION_FAILED` and must not masquerade as a canonical trace projection.

### Trace-record classification

Activity kinds reuse the existing `TraceRecordType` names rather than creating a parallel vocabulary. The initial visible set is `TRACE_STARTED`; `FRAME_OPENED` and `FRAME_CLOSED` only for `SKILL_EXECUTION` frames; `MODEL_REQUEST_SENT`; `MODEL_RESPONSE_RECEIVED`; `PLAN_CREATED`; `PLAN_UPDATED`; `PLAN_VALIDATION_FAILED`; `PLAN_RETRY_REQUESTED`; `TOOL_CALL_STARTED`; `TOOL_CALL_COMPLETED`; `TOOL_CALL_FAILED`; `STEP_STARTED`; `STEP_ACTION_REJECTED`; `STEP_COMPLETED`; `ERROR_RECORDED`; and `TRACE_COMPLETED`. `EXECUTION_OBSERVATION_ENDED` is the sole additional observability-owned kind.

Frame opening and closing maintain the bounded active snapshot even when they do not create a visible event. All other unlisted trace records are trace-only in the initial release. Evidence activity, plan-quality warnings, linter and structured-output activity, proposed or accepted step actions, model preparation, advisor mutations, captured model thoughts, payload chunks, and other low-level records may be promoted later after real console use demonstrates that their live value justifies the additional activity. Their omission from live activity does not remove them from the finalized trace.

### Concise summary content

A live summary should remain concise rather than copying arbitrary large trace metadata or payloads into every event. It should normally identify the phase, skill or tool, status, timing, counts, and failure classification. Full recorded detail remains available from a finalized trace only while it is obtainable from the producing process's current-process catalog or from a separately acquired Go copy under its own lifecycle.

### Disclosure boundary and deferred controls

The initial rule is deliberately simple: **console authentication secrets are never returned as diagnostic data; Bifrost does not detect or redact secrets embedded by the observed application in recorded content.** At the application boundary, console authentication secrets are the application observability access key and `X-Bifrost-Api-Key` request header used to enter that boundary. The rule does not protect a string inside a skill YAML file, prompt, model request or response, application-provided model configuration, activity event, trace record, payload, tool input or output, error, or metadata merely because it looks like a key, token, credential, personal data, or another sensitive value. The adapter returns that recorded application content unchanged to the authenticated operator.

Redaction, sanitization, disclosure tiers, secret detection, data-loss prevention, and application-defined content classification are not initial release capabilities. Diagnostic text supplied by the observed application is untrusted data. Consumers may parse it or calculate deterministic results from it, but must not execute it or treat instructions found in it as commands. The initial protocol does not add a universal provenance wrapper or content-classification model. Any future feature in this area must define its own compatibility and raw-artifact behavior rather than being implied by the current contracts.

“Available” does not require every response to contain every field automatically and is not an indefinite retention guarantee. Summaries, detail endpoints, pagination, and explicit raw-payload requests may organize and bound delivery. While the relevant application artifact or valid Go-acquired copy remains available under its documented limits, an authenticated operator may request any underlying application observability content; the system does not intentionally filter evidence according to guessed relevance or sensitivity.

Authorization is evaluated at the boundary that acquires each copy. A successful application response authorizes that transfer; later loss or rejection of the upstream application key does not retroactively revoke the transferred evidence. Subsequent application acquisitions still require a currently valid application key, while access to a complete Go-acquired copy requires the applicable paired-browser or MCP authentication and remains bounded by target scope, artifact-handle retention, and console-process lifetime.

### Buffer bounds and expired cursors

Choose explicit limits by event count and/or memory, not an unbounded time history. Per-client overflow closes only that client; it does not silently grow the pending queue. Go supplies `afterCursor` and `instanceId` when opening the application activity stream. A valid position returns `200` with `text/event-stream`; each activity uses its delivery cursor as the SSE `id`. An expired cursor or changed `instanceId` returns the ordinary JSON problem response `STALE_CURSOR` with HTTP `410`. Go clears its recent-activity window, obtains a fresh paginated active-execution baseline, and reconnects after the first page's `resumeCursor`. The initial release does not support `Last-Event-ID`; Go is the only direct application SSE client, and a second cursor-input mechanism adds no product value yet. A baseline refresh restores best-effort current operational state but does not claim to reconstruct intermediate activity that has left the buffer.

### Trace storage, retention, and catalog ownership

Application observability does not introduce an observability-owned trace or metadata directory. Canonical traces continue to use the core execution-trace subsystem's existing location and naming behavior whether observability is enabled or disabled. The initial adapter does not add a trace-root setting, relocate a live trace, copy a finalized artifact, scan the core trace location, or adopt a file from another process. Because `instanceId` is generated in memory on every startup, it requires no persisted identity file, directory ownership convention, or cross-process locking.

Every production session and trace identifier used in a core-generated filename is opaque and framework-generated. The core retains the exact path it created and may delete only that exact per-trace file according to its persistence policy and optional grace expiration. Delayed retention performs no directory recursion, wildcard deletion, sibling traversal, or catalog-supplied path lookup. The adapter can open a file only through the finalized descriptor received from that same current instance; callers cannot supply a path, and catalog metadata never exposes it as an identifier. Owner-restricted filesystem permissions should be used for canonical trace files where the platform supports them without special administration.

Temporary-directory cleanup is not guaranteed to be prompt or to occur at all on every supported platform. Frequent restarts, early shutdown during completion grace, `ALWAYS` retention, a crash, or failed delayed deletion may therefore leave core-owned trace files behind. The initial release accepts that developer-tool tradeoff rather than adding filesystem scanning or age-based abandoned-file cleanup. Later application processes do not adopt or remove leftovers. Conservative cleanup may be added only after its core ownership and safety rules are designed explicitly.

The browser never receives filesystem paths as identifiers. An authenticated raw-record view or raw artifact download may contain a path already recorded inside the canonical trace under the explicit raw-artifact exception above, but neither Go nor the browser treats it as a supported identifier or lookup mechanism. The application exposes opaque trace IDs and only finalized artifacts whose core-issued descriptors remain available in the current process catalog under the completion grace TTL and persistence policy. It does not expose crash leftovers and does not maintain durable tombstones or historical non-retention records.

Observability activation is resolved once during application startup before the session runner can create a session. Successful activation generates the in-memory `instanceId`, validates authentication and the current-process catalog, supplies the core trace subsystem with the configured completion-grace duration, and enables the internal finalized-descriptor consumer. Failure disables the optional adapter, supplies zero grace and a no-op consumer, and leaves the existing canonical trace location, immediate persistence behavior, and core failure semantics unchanged. Initialization must fail closed: it must not expose partial routes or switch trace storage locations. The failure is reported clearly in application diagnostics so the developer can correct the configuration.

When application observability is disabled, its `instanceId`, current-process catalog, and completion-grace behavior are not activated. Existing canonical trace location, persistence-policy, synchronous deletion, and failure behavior remain unchanged, and the central observability publication hook has no enabled consumer. After successful activation, a later catalog or server failure does not change the core trace location or transfer retention ownership; the already selected core grace behavior continues for that process while catalog publication fails independently under the existing isolation rules.

### Application problem response contract

Application observability failures use a small JSON problem response with an HTTP status, stable machine-readable `code`, and safe human-readable `message`. Go branches only on the status and stable code, never on message text. The response must not echo the application observability key, authentication header, raw diagnostic payloads, or sensitive internal exception details. Authenticated problem responses carry the same `instanceId` response metadata as other authenticated target-specific responses when that identity is available.

Only distinctions that change Go behavior require dedicated initial codes:

| Code | HTTP status | Meaning |
|---|---:|---|
| `BIFROST_API_KEY_REJECTED` | `401` | The adapter received no valid application observability key. |
| `INVALID_REQUEST` | `400` | Request syntax or a non-cursor parameter is invalid. |
| `INVALID_CURSOR` | `400` | The cursor is malformed or does not belong to the requested endpoint, ordering, or filters. |
| `STALE_CURSOR` | `410` | The cursor was once meaningful but can no longer continue the requested traversal or activity subscription, including because `instanceId` changed; the caller must begin a fresh baseline or collection query. |
| `NOT_FOUND` | `404` | The requested current-process resource is not available. This does not prove that a trace expired or previously existed. |
| `LIVE_MONITORING_UNAVAILABLE` | `503` | The process-local live projection is known to be incomplete, so active snapshots and activity are unavailable. |
| `LIMIT_EXCEEDED` | `429` | A fixed application-observability admission or request bound prevents the operation. The caller may try again only after the bounded resource becomes available or with a request inside the documented bound. |
| `APPLICATION_ERROR` | `500` | The adapter failed without a more specific stable distinction. |

This is not a taxonomy of Java exceptions. Internal exceptions map to the smallest applicable problem code and a sanitized message. New dedicated codes are added only when Go must behave differently, and a change to the stable code meaning follows the application-to-Go compatibility policy.

An expired activity cursor or changed `instanceId` during SSE connection returns the ordinary `STALE_CURSOR` problem above. Go translates that recoverable application response into its local replay-gap/reset behavior; no special application SSE control event is required. A compatibility mismatch is not an application problem response: authenticated instance status succeeds, and Go compares its top-level `consoleCompatibilityVersion` before interpreting anything else. Trace validity is determined by Go after download, so `INVALID_ARTIFACT` is a Go service error rather than a Phase 1 HTTP problem code.

### Application observability authentication

The module is opt-in and reserves `/_bifrost/observability/v1/**` as its exclusive, initially non-configurable route namespace when enabled. The namespace is relative to the application's servlet context path; for example, an application mounted at `/orders` exposes it beneath `/orders/_bifrost/observability/v1/`. It protects only those routes with the configured application observability access key supplied in `X-Bifrost-Api-Key`. A valid key produces the internal `BIFROST_OPERATOR` authority used by the centralized observability access service. The header is deliberately not `Authorization: Bearer`: a host OAuth2 resource-server filter could otherwise consume the Bifrost key as a host bearer token before adapter authentication. Unauthenticated observability is not a supported mode.

The adapter owns route-scoped API-key extraction, safe comparison, rejection, and establishment of `BIFROST_OPERATOR`. It does not install, replace, reorder, or broaden the host application's `SecurityFilterChain`, authentication providers, login scheme, session policy, or authorization rules. An application with Spring Security or another servlet filter that would otherwise reject the reserved namespace must configure that namespace as pass-through so the request can reach adapter authentication. In a typical Spring Security application this is a namespace-specific `permitAll` rule; `permitAll` at the host layer does not make the observability route public because the adapter still requires `X-Bifrost-Api-Key`. Documentation must include current Spring Security examples.

A missing or invalid Bifrost key receives `401` with `BIFROST_API_KEY_REJECTED`. Go treats only that recognizable response as an invalid or missing application observability key. A generic `401` or `403` without that code indicates that host security or an upstream proxy probably rejected the request before it reached the adapter; Go reports that distinction rather than incorrectly asking the developer to replace a potentially valid Bifrost key. Documentation and diagnostics must explain this distinction.

The initial adapter exposes the namespace only on the ordinary application listener. It does not register an Actuator endpoint or automatically attach to a separately configured Spring Boot management listener. The application owner continues to control listener binding, HTTP/TLS, firewalling, and network exposure. A reverse proxy or other upstream control may add restrictions, but it must forward the servlet context and reserved routes and preserve `X-Bifrost-Api-Key`; the console does not acquire credentials for an unrelated upstream login scheme. Go calls the application server-to-server, so the adapter does not add CORS policy for these routes and the browser never sends the application key directly to the observed application.

The initial application-observability surface is read-only and uses safe retrieval/streaming operations, so it does not require host CSRF tokens. This does not weaken CSRF handling for unrelated application routes. Any future state-changing application-observability operation requires a new security decision rather than inheriting this exception implicitly.

Enabling the module reserves the namespace; it must never replace, wrap, or silently coexist with a host handler using the same route. A detected handler or route collision disables observability for that process with a clear diagnostic, consistent with optional-adapter initialization failure, while leaving the core Bifrost engine and unrelated application routes available.

The recommended configuration shape is `bifrost.observability.auth.api-key`, normally supplied through an externalized secret rather than committed literally to application YAML. Choosing HTTP means the application owner accepts that the key and diagnostic data cross that network path without transport confidentiality or integrity.

### Console/server release compatibility

The Java adapter and Go console ship as a coordinated pair with the same complete Bifrost product release string. Both expose that exact string as `consoleCompatibilityVersion`, including qualifiers such as `-SNAPSHOT`. The authenticated instance-status request is the compatibility probe. Go reads only its stable top-level `consoleCompatibilityVersion` until an exact match is established; a mismatch prevents all other observability use without negotiation or fallback parsing. This rejects components bearing different release strings and covers REST, SSE, trace acquisition, problem meanings, and the native Java NDJSON consumed by Go. Engine, adapter, Go release, and trace-schema versions are not separately reported, negotiated, or checked, and there is no container-format version.

Two independently built components bearing the same release string, including two different builds both labeled `1.42-SNAPSHOT`, are considered matched by this runtime check. The version cannot distinguish different source revisions carrying the same release identity. Coordinated build inputs, Java-produced fixtures, cross-language tests, and release discipline must therefore keep same-release Java and Go behavior aligned. The runtime does not add a build hash or second protocol counter to compensate for inconsistent snapshot builds. Changes to REST, SSE, acquisition, problem meanings, or consumed NDJSON semantics require coordinated Java, Go, fixture, test, and documentation work in the same release.

### Current-release Java/Go trace agreement

Java writes its native finalized UTF-8 NDJSON trace and Go consumes those records directly. This is a current-release implementation boundary between the coordinated Java framework and Go console, not a durable public interchange format, archival format, third-party extension API, or promise that one console release reads another release's traces.

The authoritative agreement is the current Java writer and enums plus Java-produced golden fixtures exercised by Go semantic tests. Concise written guidance records only the invariants Go must rely upon: one JSON record per nonblank line; the outer fields and semantic enums Go consumes; consistent trace/session identities; canonical sequence ordering; frame-parent rules; payload-envelope and `PAYLOAD_CHUNK_APPENDED` reconstruction; a final `TRACE_COMPLETED`; and the malformed or inconsistent conditions that invalidate semantic analysis. Per-record metadata or data fields need documentation only when Go interprets them for hierarchy, timing, usage, failure, validation, completion, or another shared calculation. All other metadata and data remain opaque diagnostic JSON available for raw inspection rather than an exhaustively specified cross-language model.

A finalized artifact is eligible for Go analysis only when the structural and consumed semantic facts above are valid. Go rejects malformed JSON, unknown record or consumed semantic enum values, inconsistent identity or ordering, invalid consumed frame relationships, incomplete or inconsistent chunks, and a missing or non-final `TRACE_COMPLETED` rather than silently presenting an incorrect analysis. The unchanged raw artifact may remain available while the application retains it.

Every Bifrost release receives its own release-derived `consoleCompatibilityVersion`; trace changes do not trigger a separate increment decision. Within a release, a Java change to a field or meaning Go consumes updates Java, Go, fixtures, and tests together before publication. Unconsumed metadata additions require no Go model or exhaustive contract update. Go-only improvements likewise ship under the containing release string. No trace-record, trace-schema, artifact, container, or independent protocol version is maintained alongside it.

Java-produced golden fixtures are the executable cross-language agreement. They cover representative successful and failed traces, nested frames, chunked payloads, validation and retry behavior, and malformed artifacts, with expected Go results for the calculations the console actually provides rather than parse success alone. Go owns those derived console calculations, and browser and MCP consume the same results. There is no schema registry, generated cross-language model, migration system, historical reader, or exhaustive public specification.

### Required identity facts

Phase 1 exposes:

- one `instanceId`, generated as a new UUIDv4 in memory at application startup and changed on every restart; it identifies only that running application instance and also scopes its activity cursor, active registry, and current-instance trace catalog;
- the release-derived `consoleCompatibilityVersion`; no additional engine, adapter, Go-release, or trace-schema versions are required or exposed for this integration;
- session, trace, frame, and parent-frame identifiers; and
- the registered name, relative `sourcePath`, and unchanged YAML content for each skill.

Every successful authenticated target-specific REST response carries `instanceId` through centrally applied response metadata; a DTO may repeat it when useful domain evidence. Trace catalog and artifact-download responses carry the same identity metadata in their HTTP response headers before content begins. The SSE handshake and every activity envelope identify the same `instanceId`. Missing or invalid authentication responses need not disclose identity.

This identity is a correlation and lifecycle guard, not a cryptographic attestation or a durable application identity. Phase 1 does not sign responses or artifacts solely to bind them to an instance. HTTPS supplies transport authenticity and integrity when the application owner requires those properties; an intentionally configured HTTP target retains the transport risks already documented.

Production `sessionId` and `traceId` values are opaque UUIDs generated independently for one execution. `sessionId` is the lookup key while that execution is active. Active baseline entries and activity carry both identifiers so clients can correlate the live execution with its trace. Once execution ends, the active session entry disappears; if the finalized artifact is retained, retrospective lookup uses `traceId`. Frame IDs and record sequences are interpreted only within their containing trace.

All application observability resources are lifetime-bound to the reported `instanceId`. A changed `instanceId` tells downstream consumers that the application restarted and that they must discard state derived from the prior instance. Phase 1 does not promise resource lookup, history, or deep-link continuity across that boundary.

One execution maps to one trace. Nested skills, plans, model calls, tools, and validations are frames within that same trace and use frame and parent-frame identifiers for hierarchy. Separate executions have separate traces and no implicit parent/root execution relationship. Cross-trace child-execution correlation is outside the initial design and would require a future explicit contract if Bifrost later adds that execution model.

## Handoff to future planning

A future Phase 1 planning session should begin by revalidating the current implementation facts and the minimal Java-to-Go agreement above. Additional live kinds, detail fields, or trace semantics are added only when the initial developer experience or a shared Go calculation requires them; they are not prerequisites for broadening the contract speculatively.

Implementation planning should keep engine publication, the per-execution observation handle, projection, operational registry/buffering, server transport, security, and trace catalog responsibilities separable even if they ship in one phase. The handle is the sole lifecycle coordinator but not the owner of every stored representation. This preserves testability and prevents the optional HTTP module from becoming part of core execution behavior.
