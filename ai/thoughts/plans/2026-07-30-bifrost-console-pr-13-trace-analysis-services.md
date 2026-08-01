# PR 13 — Trace Parser, Indexes, and Shared Calculations Implementation Plan

## Overview

Implement one transport-neutral Go trace-analysis service that turns a current-scope acquired NDJSON artifact into a validated, immutable analysis bundle before its handle is exposed. The service will stream-parse current-release Java records, reconstruct large logical payloads to disk, build bounded query indexes, calculate hierarchy/timing/usage/attempt/validation/failure facts once, and serve the same finite, continuable results to the browser work in PR 14 and MCP work in PR 18.

The implementation also corrects a current semantic-fixture/documentation drift: production `UsagePrecision` is `EXACT`, `HEURISTIC`, or `UNAVAILABLE`, while one valid fixture and `ai/skill-authoring/traces-and-debugging.md` currently say `ESTIMATED`.

## Current State Analysis

- The Java writer emits one current-release `TraceRecord` JSON object per line and chunks logical `data` values larger than 4,096 characters into an envelope plus `PAYLOAD_CHUNK_APPENDED` records (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:340-451`).
- The Java reader demonstrates logical reconstruction, but it accumulates the reconstructed payload in a `StringBuilder` and tolerates a partial trailing record/incomplete pending payload (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/NdjsonExecutionTraceReader.java:41-70`, `:128-231`). PR 13 must preserve the current envelope/chunk meaning while applying the ticket's stricter finalized-artifact validation and bounded-memory requirements.
- The Java fixture corpus currently contains 10 valid and 8 invalid trace/expected-result pairs. It protects attempt ordering, terminal completion, validation links, and component-wise usage reconciliation, but it contains no frame records and therefore does not yet prove hierarchy or duration calculations (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ConsoleTraceFixtureCorpusTest.java:41-194`).
- Production `UsagePrecision` defines `HEURISTIC`, but the valid `nested-retry-sequences` fixture is generated with `ESTIMATED`; the skill-authoring trace guide repeats `ESTIMATED` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/usage/UsagePrecision.java:3-8`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ConsoleTraceFixtureCorpusTest.java:265`, `ai/skill-authoring/traces-and-debugging.md:25-32`).
- The current Go artifact service downloads and atomically renames one raw file, publishes its handle after byte-count checks, and explicitly leaves semantic parsing for PR 13 (`bifrost-console/internal/artifact/acquire.go:42-166`, `bifrost-console/internal/artifact/service.go:247-277`, `bifrost-console/internal/artifact/lease.go:11-45`).
- Artifact capacity currently charges only the raw installed file. The settled design requires the same entry, capacity charge, pin, TTL, removal, scope invalidation, and shutdown lifecycle to own the raw artifact and every derived index/payload file (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:484-496`, `:912-922`).
- `internal/live` supplies the nearest query precedent: explicit request/response types, finite page sizes, deterministic order, and cursor/continuity facts (`bifrost-console/internal/live/dto.go:156-184`, `bifrost-console/internal/live/service.go:658-729`). Trace cursors need stronger binding to artifact handle, scope, query fingerprint, ordering, and byte/record progress.
- Shared error codes already include `INVALID_CURSOR`, `ARTIFACT_EXPIRED`, `INVALID_ARTIFACT`, `LIMIT_EXCEEDED`, `LOCAL_STORAGE_UNAVAILABLE`, and `TARGET_CHANGED`; only safe bounded details should be added where analysis needs to identify a limit or invalidity category (`bifrost-console/internal/consolecore/errors.go:5-40`).
- The console composition root currently creates only the artifact service. PR 13 must construct and inject the analysis processor/query service without adding a browser-only or MCP-only implementation (`bifrost-console/internal/console/service.go:150-184`).

## Desired End State

After this plan is implemented:

1. `artifact.Service.Acquire` admits a handle only after the complete raw artifact and all required analysis components have been downloaded, validated, charged, synced, and published as one immutable artifact bundle.
2. Invalid, truncated, oversized, structurally contradictory, or cancellation-affected input leaves no handle, installed bundle, retained semantic state, or capacity charge.
3. A new `internal/traceanalysis` package owns current-release parsing, validation, derived calculations, indexes, query models, range reads, cursors, and domain-error classification below all adapters.
4. Parsing keeps only one bounded physical line and compact working maps in memory. Raw bytes, reconstructed logical payloads, and immutable query indexes live in the artifact bundle and share its lifecycle.
5. Every logical record and physical framework record remains addressable. Large payloads and searches progress through finite, cancellable calls rather than whole-trace or whole-payload materialization.
6. Browser and future MCP adapters can consume the same `TraceSummary`, frame, record, attempt/retry, validation, failure, usage, gap/uncertainty, search, and range results without recomputing semantics.
7. All Java-produced expected results, including new hierarchy/timing cases, are asserted by Go. `HEURISTIC` is the only accepted current precision spelling; fixtures and skill-authoring guidance agree with production.
8. `go run ./internal/buildtool verify`, the focused Java fixture test, all Go tests, and race-focused analysis/artifact tests pass.

### Key Discoveries

- The artifact service already creates the opaque handle before acquisition begins, so it can key one joined staged analysis without introducing a second client-visible identity (`bifrost-console/internal/artifact/service.go:166-194`).
- A per-entry directory is the smallest coherent storage change: it allows the raw artifact, record index, fact indexes, payload store, and manifest to be renamed/published and deleted together while keeping all paths internal.
- The settled design explicitly permits opaque self-contained continuations without signatures because the loopback APIs are independently authenticated (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:498`).
- Deep frame hierarchies must remain completely inspectable and have no product-level depth or node cap. Malicious nesting is handled with iterative algorithms, cycle/relationship validation, cancellation, and artifact-capacity bounds; the depth-128 bound applies to JSON structure, not the frame tree (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:480`, `:704-708`).
- Future MCP requires general frame and record queries plus payload ranges, not scenario-specific diagnostic DTOs (`ai/thoughts/phases/bifrost_console_phase_3_llm_runtime_inspector.md:407-434`).

## What We're NOT Doing

- No Trace Explorer UI, browser analysis endpoints, React state, or visual presentation; those belong to PR 14.
- No MCP tools, MCP schemas, capability advertisement, or adapter DTOs; those belong to PR 18.
- No automatic diagnosis, causal inference, severity, recommendations, cost conversion, or “most important” ranking.
- No historical/cross-version trace readers, trace schema version, legacy fixture preservation, migrations, aliases, or dual `ESTIMATED`/`HEURISTIC` behavior.
- No dependency on `bifrost-cli`, its filesystem discovery, whole-trace types, or TUI presentation.
- No SQLite/CGO, embedded database, external JSON parser, archive reader, decompressor, manifest parser, or durable index adoption across console restarts.
- No hierarchy-specific depth/node cap, intentional record omission, cumulative traversal quota, or full-trace browser payload.
- No raw-artifact MCP operation. PR 13 will retain exact raw-record addressing and an internal bounded raw-range primitive so PR 18 can add that adapter without changing acquisition or storage.

## Skill-Authoring Documentation Impact

**Impact**: Affected

- **Rationale**: PR 13 does not change skill syntax or runtime authoring semantics, but source verification found documentation drift in a topic directly used to diagnose usage. The guide says `ESTIMATED`; production and the settled Phase 1 contract use `HEURISTIC`. Leaving the guide unchanged would instruct an LLM to expect a value the current framework does not emit.
- **Documents to update**: `ai/skill-authoring/traces-and-debugging.md`
- **Supporting evidence**: `UsagePrecision.java`, `ModelUsageExtractorTest`, `SessionUsageServiceTest`, the corrected `ConsoleTraceFixtureCorpusTest`, regenerated `nested-retry-sequences.ndjson`, and the new Go fixture-corpus test.
- **Coverage table update**: Not required. `README.md` already routes retry/usage/terminal-failure diagnosis to `traces-and-debugging.md` and marks the topic source-verified; the topic boundary and confidence do not change.
- **LLM-first usability**: Replace the incorrect enum spelling in the compact precision table and keep the existing distinction between unavailable attempt usage and derived unattributed terminal remainder. Do not add Go index/query implementation details to author guidance.

## Contract and Compatibility Impact

| Surface | Classification and supporting evidence | Planned compatibility treatment |
| --- | --- | --- |
| Application API | No Java application-developer entry point changes. Go types remain under `internal/`. | Preserve; no public API delta. |
| Supported SPI | No Java extension point or Spring bean is added or changed. The Go processor/storage seams are internal construction boundaries. | Preserve; no supported SPI delta. |
| Configuration and manifest contracts | Existing `trace-workspace.max-bytes` and `idle-ttl` keep their syntax/defaults. Derived files now count toward the already-documented aggregate local bytes. No skill manifest behavior changes. | Preserve the existing contract; update tests/README wording only if it currently implies raw bytes rather than aggregate raw-plus-derived bytes. |
| Persisted or serialized contracts | No durable/cross-version contract. The new index bundle is process-local, lives under disposable `transient`, and is never adopted after restart. | No compatibility reader or migration. |
| Ephemeral diagnostic formats | PR 13 begins strict Go consumption of the current `TraceRecord` NDJSON, chunk envelope semantics, enums, and Java fixture results. The invalid `ESTIMATED` fixture is corrected to `HEURISTIC`; hierarchy/timing cases are added. | Update Java fixture producer, committed fixtures, Go consumer, tests, and focused author guidance atomically. Keep one current format. |
| Internal or accidentally exposed implementation | Artifact storage changes from one installed file to one installed directory/bundle; leases gain component access; a processor dependency and `internal/traceanalysis` package are added. | Refactor atomically and update every in-repository caller/test. No compatibility fallback or alternate raw-only acquisition path. |

- **Evidence of supported contracts**: The approved PR 01/13 tickets, framework design lens, Phase 2 design, Java trace source, `ConsoleTraceFixtureCorpusTest`, committed fixture corpus, and the exact `consoleCompatibilityVersion` release gate.
- **Intended breaks**: Remove the erroneous `ESTIMATED` value from the valid fixture/guidance and require semantic validation before exposing a newly acquired analysis handle. Existing raw-only artifact test data will no longer be accepted by production acquisition.
- **In-repository consumers to update**: Java fixture generator/tests, committed trace/expected fixtures, Go artifact unit/integration tests, console composition/integration tests, browser acquisition tests that use fake artifact bytes, storage byte-accounting assertions, `bifrost-console/README.md`, and `ai/skill-authoring/traces-and-debugging.md`.
- **Public-surface delta**: None outside Go `internal/`; no Java public type, constructor, interface, Spring bean, REST route, SSE field, browser endpoint, or configuration key is added.
- **Shim decision**: **No shim.** Traces and index files are current-run ephemeral diagnostics/internal state, the repository is pre-1.0, and all consumers can be changed atomically. Supporting both `ESTIMATED` and `HEURISTIC`, raw-only handles, or old single-file storage would conceal invalid current semantics.
- **Java-to-Go boundary coordination**: **Required.** The Java writer/enums, Java fixture generator, committed NDJSON/expected results, Go parser/calculations, Go fixture tests, and focused trace guidance must land together. The exact Bifrost release string remains the compatibility marker; do not add or independently bump a trace version.

## Implementation Approach

### Resolved Design Decisions

| Research question | Decision |
| --- | --- |
| Package ownership | Add `bifrost-console/internal/traceanalysis`. Keep lifecycle/capacity/path ownership in `internal/artifact`; inject a required artifact processor implemented by `traceanalysis`. |
| Parser/library/bounds | Use `bufio`, `encoding/json`, `json.RawMessage`, and custom numeric/timestamp validation. Maximum physical line: 1 MiB. Maximum JSON object/array nesting: 128. Reject, do not clamp or repair. |
| Index storage | Use an immutable per-handle disk bundle: raw NDJSON, fixed-width record-address index, length-prefixed typed fact indexes, reconstructed payload store, and manifest. Keep only compact build maps and per-query state in memory. Charge every file to the artifact entry. |
| Continuations | URL-safe base64 of a versioned internal cursor containing operation kind, target scope, handle, SHA-256 canonical query fingerprint, ordering, and next sequence/index/byte/search state. Validate scope/handle lifetime first, then token shape/fingerprint. No signature and no server-side cursor registry. |
| Malicious hierarchy | Validate duplicate/missing parents, cycles, lifecycle order, and contradictory complete intervals iteratively. Never recurse by frame depth and impose no frame count/depth cap beyond artifact/capacity and per-operation bounds. Apply the depth-128 bound only to JSON structure. |
| Chunked payloads | Decode one chunk record at a time and append decoded content directly to the staged payload store. Validate envelope/chunk identity, count, index uniqueness/contiguity, content type, and reconstructed JSON/UTF-8 without materializing the full logical value. |
| Shared DTO contract | Define neutral trace context, summaries, typed facts, pages, descriptors, ranges, gaps, and uncertainties in `traceanalysis`; adapters must map from these types and may not recalculate. |

### Fixed Operational Bounds

- Physical NDJSON line: 1 MiB, including newline handling.
- JSON structural depth: 128 objects/arrays.
- Page size: default 100; accepted range 1–1,000.
- Automatically inline logical payload: at most 8 KiB and only when explicitly requested.
- Payload/raw range: default 64 KiB; accepted range 1–1 MiB per call.
- Literal text query: at most 1 KiB UTF-8 and 256 Unicode code points.
- Search work per call: at most 8 MiB of fully processed searchable bytes or 10,000 records, whichever comes first; return completed matches plus continuation.
- No cumulative traversal, record-count, frame-count, payload-size, or frame-depth limit. Artifact bytes and derived bytes remain governed by the configured workspace capacity; `unlimited` remains explicit.

These are internal correctness/response-framing constants, not new configuration. Requests outside an accepted per-call range return `LIMIT_EXCEEDED` with a safe `limitName`/`limitValue`; malformed filters or mutually exclusive cursor/start inputs return `INVALID_ARGUMENT`.

### Validation and Calculation Rules

- Require nonblank stable trace/session identity, positive strictly increasing sequence, a valid timestamp, a known `TraceRecordType`, known consumed `TraceFrameType`/`TraceOutcome`/`UsagePrecision`, and exactly one final `TRACE_COMPLETED`.
- Cross-check terminal trace/session/outcome/finalized time/persistence facts with acquisition metadata where both sides provide the fact.
- Preserve unconsumed `metadata` and `data` as opaque JSON/ranges. Do not require a Go field for an unconsumed key.
- Model logical records separately from physical storage records. The envelope keeps its canonical sequence and reconstructed payload reference; physical chunk records remain queryable through raw-record addressing.
- Require chunk count/index/content type/payload ID agreement; reject missing, duplicate, out-of-order, interleaved, extra, or mismatched chunks as `INCOMPLETE_CHUNKS`/`INVALID_CHUNKS`.
- Frame structure uses recorded IDs only. Duplicate opens, self-parenting, missing parents, cycles, close-before-open, conflicting immutable frame identity, or a complete child interval outside its complete parent are invalid. A legitimately incomplete open/close pair is retained with an explicit lifecycle/duration gap rather than a fabricated duration.
- `inclusiveDuration = closedAt - openedAt` when both timestamps are valid. `selfDuration = inclusiveDuration - sum(immediate complete non-overlapping child durations)`. If a child is incomplete or immediate child intervals overlap, inclusive duration remains available but self duration is marked unavailable with a precise uncertainty; concurrency is not rejected or double-subtracted.
- Attempt/retry membership uses only `attemptId` and `retrySequenceId`. Validate positive consistent attempt numbers and lifecycle ordering for consumed model request/response facts. A provider failure may leave a sent attempt without a response; represent the missing response/usage as a gap.
- Failure identity uses only explicit `failureId` and `terminalFailureId`. Failed/aborted terminal outcomes require a resolvable terminal failure; success forbids one. Earlier errors remain nonterminal facts.
- Sum prompt, completion, and total units independently with checked `int64` arithmetic. Do not require `total = prompt + completion`; require each normalized total to be nonnegative and at least each provided component.
- Direct frame usage is response usage on that exact `frameId`; descendant and inclusive usage use ancestor traversal; attempt/retry usage uses only explicit IDs. A response without a frame remains attributed response usage but is separately reported as unframed rather than forced into the hierarchy.
- Derive terminal unattributed remainder component-by-component as `TRACE_COMPLETED.sessionUsageSnapshot - all normalized physical MODEL_RESPONSE_RECEIVED usage`. Reject any negative component. `UNAVAILABLE` or missing response usage carries numeric facts only when present and marks completeness unknown; it is never presented as known zero.
- Every summary distinguishes recorded fact, mechanical calculation, gap, and uncertainty. No aggregate evidence-completeness score is introduced.

## Phase 1: Correct and Extend the Executable Semantic Corpus

### Overview

Make the current Java fixture producer authoritative for every consumed PR 13 semantic and remove the discovered precision drift before implementing the Go consumer.

### Changes Required

#### 1. Java fixture generator and invariants

**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ConsoleTraceFixtureCorpusTest.java`

**Changes**:

- Replace the valid `ESTIMATED` fixture value with `HEURISTIC`.
- Add valid Java-produced cases for nested frames, repeated skill invocations, direct/descendant usage, inclusive/self duration, unframed attributed usage, incomplete duration gaps, explicit validation/failure references, and chunked JSON plus chunked text.
- Expand expected valid results with root/frame facts, duration availability, usage completeness, failure links, attempt/retry usage, payload descriptors, gaps, and uncertainty where applicable.
- Add minimal invalid mutations for duplicate/mismatched/out-of-order chunks, invalid frame relationships/cycles, invalid terminal failure links, attempt identity contradictions, negative/overflowing usage, oversized physical records, excessive JSON nesting, and truncated final input.
- Preserve the existing canonical invalidity names and add stable PR 13 internal categories only where the old eight cannot describe the failure.
- Assert fixture inventory, current enum spellings, frame invariants, and semantic expectations in Java before Go consumes them.

#### 2. Regenerated cross-language fixtures

**Files**:

- `bifrost-console-fixtures/traces/*.ndjson`
- `bifrost-console-fixtures/expected/*.json`
- `bifrost-console-fixtures/README.md`

**Changes**:

- Regenerate the corpus from Java twice and require the second generation to produce no diff.
- Document the added hierarchy/timing/payload/limit cases and retain the statement that the corpus is current-release, not historical compatibility data.
- Keep expected files semantic and adapter-neutral; do not add browser or MCP presentation models.

#### 3. Skill-authoring precision correction

**File**: `ai/skill-authoring/traces-and-debugging.md`

**Changes**:

- Change `ESTIMATED` to `HEURISTIC` and align its meaning with `UsagePrecision`/`ModelUsageExtractor`.
- Preserve the existing guidance that unavailable usage is not the same as terminal unattributed remainder.

### Success Criteria

#### Automated Verification

- [x] Focused Java corpus tests pass: `mvn -pl bifrost-spring-boot-starter -Dtest=ConsoleTraceFixtureCorpusTest test`
- [x] Intentional regeneration succeeds: `mvn -pl bifrost-spring-boot-starter -Dtest=ConsoleTraceFixtureCorpusTest -Dbifrost.console.fixtures.regenerate=true test`
- [x] A second regeneration produces no fixture diff: `git diff --exit-code -- bifrost-console-fixtures`
- [x] Repository search finds no valid current semantic guidance/fixture using `ESTIMATED`: `rg -n "ESTIMATED" ai/skill-authoring bifrost-console-fixtures bifrost-spring-boot-starter/src`
- [x] New expected files contain no UI or MCP DTO fields.
- [x] Updated trace guidance is supported by the cited enum, focused usage tests, and fixture tests.

#### Manual Verification

- [x] Review one nested-frame expected file and confirm direct, descendant, inclusive, and unframed usage are visibly distinct.
- [x] Review one incomplete/overlapping-duration case and confirm it reports a gap/uncertainty rather than zero or a guessed self duration.

---

## Phase 2: Make Artifact Installation Own One Validated Analysis Bundle

### Overview

Refactor artifact staging and leases so semantic processing happens once during joined acquisition, derived files share raw-artifact lifecycle/capacity, and no invalid handle is exposed.

### Changes Required

#### 1. Required processor and staged bundle abstraction

**New file**: `bifrost-console/internal/artifact/processor.go`

**Files**:

- `bifrost-console/internal/artifact/service.go`
- `bifrost-console/internal/artifact/model.go`
- `bifrost-console/internal/artifact/acquire.go`
- `bifrost-console/README.md`

**Changes**:

- Add a required internal `Processor` dependency invoked after the raw transfer and byte-count checks but before installation publication.
- Give the processor only a cancellable raw reader, immutable `TraceMetadata`, and a staged component sink; never expose an absolute path.
- Require the sink to reserve capacity before each derived write, account short writes/sync/close failures, and report only logical component names.
- Ensure joined callers share one transfer, one processor execution, one staged bundle, one result, and one error.
- Publish `AcquiredArtifact.LocalBytes` as aggregate raw plus derived bytes.
- On parser invalidity, cancellation, scope rotation, capacity rejection, or recoverable storage failure, remove the entire staged bundle, release its full reservation, and publish no handle.
- Make the runtime documentation explicit that `localBytes`/`chargedBytes` include the raw artifact and all required derived files.

#### 2. Per-entry atomic storage bundle

**Files**:

- `bifrost-console/internal/artifact/storage.go`
- `bifrost-console/internal/artifact/capacity.go`
- `bifrost-console/internal/artifact/expiry.go`

**Changes**:

- Replace the single partial/installed file with a protected random staging directory containing `artifact.ndjson` and processor-created components.
- Sync completed files and rename the staging directory to one random installed directory on the same filesystem before publishing.
- Store only the installed directory internally. Removal, eviction, target invalidation, shutdown, and restart cleanup delete the complete bundle without following links/reparse points.
- Extend the filesystem fault seam for directory creation, component creation/open/stat, directory rename, and recursive bundle cleanup with verified in-workspace targets.
- Keep expired-first/LRU eviction and pins unchanged while charging the complete bundle.

#### 3. Lease component access

**File**: `bifrost-console/internal/artifact/lease.go`

**Changes**:

- Replace raw-path-oriented opening with bounded component readers/seekers and size lookup methods used by `traceanalysis`.
- Keep the raw artifact a named internal component and make component names closed/enumerated, never caller-supplied paths.
- Invalidate/close all component readers on scope rotation and shutdown.
- Refresh `lastUsedAt` only when the complete query lease closes successfully.

#### 4. Artifact lifecycle and composition tests

**Files**:

- `bifrost-console/internal/artifact/*_test.go`
- `bifrost-console/internal/console/artifact_integration_test.go`
- `bifrost-console/internal/console/target_integration_test.go`
- `bifrost-console/internal/browserapi/artifacts_test.go`

**Changes**:

- Update raw `"test"` acquisitions to inject an explicit fake processor in artifact-only tests or use valid corpus traces in production-composition tests; do not make the production processor optional.
- Cover joined processing, derived-byte admission/eviction, processor cancellation, invalid-content cleanup, staging sync/rename failure, component open failure, pin/removal behavior, and full bundle deletion.
- Assert that the browser acquisition response never receives an invalid artifact handle and that storage snapshots report aggregate bundle bytes.

### Success Criteria

#### Automated Verification

- [x] Artifact tests pass: `go test ./internal/artifact`
- [x] Console/browser integration tests pass: `go test ./internal/console ./internal/browserapi`
- [ ] Lifecycle concurrency tests pass under race detection: `go test -race ./internal/artifact ./internal/console`
- [x] Fault-injection tests prove no staged file, derived component, capacity charge, or handle survives failed processing.
- [x] Existing cache TTL, LRU, pinning, manual removal, scope rotation, and shutdown tests remain green with bundle storage.

#### Manual Verification

- [ ] Acquire a valid fixture through the existing Console trace action and confirm Trace Storage reports aggregate local bytes and one handle.
- [ ] Attempt acquisition of a malformed fixture and confirm the UI/API reports `INVALID_ARTIFACT` while Trace Storage shows no new entry.

---

## Phase 3: Implement Streaming Parse, Validation, Indexes, and Shared Calculations

### Overview

Build the required `artifact.Processor` in `internal/traceanalysis`, generating an immutable analysis bundle with no full-trace or full-payload memory requirement.

### Changes Required

#### 1. Current-release record and semantic models

**New files**:

- `bifrost-console/internal/traceanalysis/model.go`
- `bifrost-console/internal/traceanalysis/enums.go`
- `bifrost-console/internal/traceanalysis/errors.go`

**Changes**:

- Define only the canonical record fields and consumed metadata needed for identity, hierarchy, timing, attempts/retries, validation, failure, terminal outcome, and usage.
- Retain `metadata` and `data` as `json.RawMessage` until written/indexed; do not model unconsumed fields.
- Add strict custom decoding for Java's numeric `Instant`, checked integral values, nullable normalized identifiers, and the exact current enum sets.
- Keep a stable internal invalidity category for diagnostics/tests while mapping all content invalidity outward to `consolecore.CodeInvalidArtifact` with `rawDownloadAvailable` when known.

#### 2. Bounded NDJSON and chunk processor

**New files**:

- `bifrost-console/internal/traceanalysis/parser.go`
- `bifrost-console/internal/traceanalysis/payload.go`
- `bifrost-console/internal/traceanalysis/limits.go`

**Changes**:

- Read LF or CRLF records with a reusable bounded buffer; accept a complete final line without a newline, but reject a syntactically partial/truncated final record.
- Pre-scan each JSON line outside string literals to enforce depth 128 before `encoding/json` decode.
- Record raw byte offset/length, line terminator length, canonical sequence, and logical/physical representation.
- Stream chunk content to the staged payload store and validate its envelope as described above.
- Write unchunked logical `data` to the payload store only when a descriptor is needed; cap transient decoding at one physical line.
- Check cancellation between reads, structural scans, chunk writes, validation passes, index writes, and large payload validation.

#### 3. Immutable indexes

**New files**:

- `bifrost-console/internal/traceanalysis/index_writer.go`
- `bifrost-console/internal/traceanalysis/index_format.go`
- `bifrost-console/internal/traceanalysis/manifest.go`

**Changes**:

- Write a fixed-width sequence/raw-address index for O(1)/binary-search record lookup without a per-record Go object heap.
- Write length-prefixed typed frame, attempt/retry, validation, failure, usage, gap/uncertainty, and payload descriptor indexes in deterministic canonical order.
- Write a small manifest containing trace identity, counts, root references, terminal facts, component sizes, and index offsets. It is an internal same-process format, not versioned persisted state.
- Validate every index write and final size; never publish a partially written component.

#### 4. Validation and calculations

**New files**:

- `bifrost-console/internal/traceanalysis/validate.go`
- `bifrost-console/internal/traceanalysis/frames.go`
- `bifrost-console/internal/traceanalysis/attempts.go`
- `bifrost-console/internal/traceanalysis/failures.go`
- `bifrost-console/internal/traceanalysis/usage.go`

**Changes**:

- Implement the fixed validation/calculation rules from the Implementation Approach.
- Use iterative parent traversal/topological processing with explicit visitation states to reject cycles and support arbitrarily deep valid frame trees without stack growth.
- Settle frame aggregates bottom-up, with checked duration and usage arithmetic and deterministic canonical tie-breaking.
- Build flat failure and validation indexes during processing.
- Keep terminal remainder, unframed attributed response usage, and frame attribution separate.

#### 5. Fixture-driven processor tests

**New files**:

- `bifrost-console/internal/traceanalysis/fixture_corpus_test.go`
- `bifrost-console/internal/traceanalysis/parser_test.go`
- `bifrost-console/internal/traceanalysis/calculations_test.go`

**Changes**:

- Discover the repository fixture root without copying fixtures into the Go module.
- For every expected file, process the paired NDJSON and compare the relevant neutral semantic result or exact invalidity category.
- Add Go-only adversarial tests for split reads, CRLF, no final newline, one-byte-over-line-limit, depth 128/129, invalid UTF-8, numeric overflow, huge chunk counts, cancellation, and a very deep valid frame chain.
- Verify peak parser allocations do not scale with logical payload size using a generated multi-megabyte chunked payload and allocation-focused test/benchmark.

### Success Criteria

#### Automated Verification

- [x] All traceanalysis tests pass: `go test ./internal/traceanalysis`
- [x] Fixture corpus results match Java expected files for every valid and invalid case.
- [x] The parser accepts 1 MiB lines/depth 128 and rejects the first value above each bound with `INVALID_ARTIFACT`.
- [x] A generated large chunked payload is reconstructed and range-addressable without whole-payload allocation.
- [x] A deeply nested valid frame chain completes without recursion/stack failure and every frame remains indexed.
- [x] Race tests pass: `go test -race ./internal/traceanalysis ./internal/artifact`

#### Manual Verification

- [x] Inspect a generated bundle during a paused test and confirm it contains only the raw artifact plus closed internal analysis components, with no trace/handle-derived filesystem name.
- [x] Compare one nested hierarchy's calculated duration and usage facts manually against its Java expected file.

---

## Phase 4: Add Neutral Bounded Queries, Search, Ranges, and Continuations

### Overview

Expose the immutable bundle through one adapter-neutral query service whose types and errors can be mapped directly by PR 14 and PR 18.

### Changes Required

#### 1. Neutral query/result types

**New file**: `bifrost-console/internal/traceanalysis/dto.go`

**Changes**:

- Define `TraceContext` carrying `TargetScopeID`, artifact handle, trace ID, and session ID on every reusable result.
- Define `TraceSummary`, `FrameSummary`, `RecordSummary`, `AttemptSummary`, `RetrySummary`, `ValidationSummary`, `FailureSummary`, `UsageBreakdown`, `Gap`, `Uncertainty`, `PayloadDescriptor`, `RawRecordDescriptor`, generic/typed pages, and byte/text range results.
- Represent optional/unknown calculations explicitly; never use zero as the absence marker.
- Keep JSON tags/adapter presentation out of the core where they would turn browser formatting into the shared semantic model.

#### 2. Query service and filters

**New files**:

- `bifrost-console/internal/traceanalysis/service.go`
- `bifrost-console/internal/traceanalysis/query_frames.go`
- `bifrost-console/internal/traceanalysis/query_records.go`
- `bifrost-console/internal/traceanalysis/query_facts.go`
- `bifrost-console/internal/traceanalysis/search.go`
- `bifrost-console/internal/traceanalysis/range.go`

**Changes**:

- Add methods for summary lookup, frame queries, record queries, attempt/retry queries, validation queries, failure queries, literal search, reconstructed payload ranges, raw record ranges, and exact raw artifact ranges.
- Frame filters: exact IDs, parent, type, route, skill, outcome, attempt, retry, validation, and failure. Orders: canonical, duration, or usage with stable canonical/ID tie-breakers.
- Record filters: type, frame, route/skill, sequence/time range, attempt/retry, validation/failure/status, representation (logical or physical), literal text, and explicit inline-payload request. Record results remain canonical unless the operation explicitly selects another settled order.
- Acquire one artifact lease per call, open only required components, check context cancellation during scans, and close the lease successfully only after the complete result is materialized.
- Return no partial semantic tree/summary from an invalid bundle; bundle corruption after publication is a storage/console failure, not best-effort evidence.

#### 3. Cursor codec and validation order

**New file**: `bifrost-console/internal/traceanalysis/cursor.go`

**Changes**:

- Canonicalize every filter/order/range request and hash it with SHA-256.
- Encode/decode the fixed cursor fields described above with URL-safe base64 and strict unknown/trailing-field rejection.
- Check current target scope first (`TARGET_CHANGED`), handle existence second (`ARTIFACT_EXPIRED`), and then cursor shape/fingerprint (`INVALID_CURSOR`) so stale evidence is never reinterpreted.
- Store search progress as next record plus optional intra-payload byte/KMP state so an oversized single payload can make bounded progress without missing a boundary-spanning literal.

#### 4. Range encoding

**File**: `bifrost-console/internal/traceanalysis/range.go`

**Changes**:

- Return actual byte start/end, total length, content type, encoding, completion, and next cursor.
- For valid UTF-8 text/JSON, adjust requested boundaries to complete code points and report the actual range.
- Return base64 for arbitrary/exact bytes that cannot be represented as a complete UTF-8 slice; never substitute malformed text.
- Keep logical payload, raw physical record, and raw artifact references distinct.

#### 5. Console composition and shared-service tests

**Files**:

- `bifrost-console/internal/console/service.go`
- `bifrost-console/internal/console/artifact_integration_test.go`
- `bifrost-console/internal/traceanalysis/service_test.go`
- `bifrost-console/internal/traceanalysis/cursor_test.go`
- `bifrost-console/internal/traceanalysis/range_test.go`

**Changes**:

- Construct one `traceanalysis.Service`, inject it as the artifact processor, and retain the same instance as the future adapter-facing query service.
- Test both trace-ID acquisition and existing-handle reuse without another upstream request.
- Exercise cursor tampering, filter/order reuse, scope rotation, expiry/removal, cancellation, page/range extremes, stable sorting, broad traversal, and simultaneous callers.
- Prove every fixture frame/record/payload is reachable through finite pages/ranges while the handle remains valid.

### Success Criteria

#### Automated Verification

- [x] Query/cursor/range tests pass: `go test ./internal/traceanalysis ./internal/console`
- [x] Page/range requests outside fixed bounds return `LIMIT_EXCEEDED`; invalid shapes return `INVALID_ARGUMENT`.
- [x] Cursor reuse with another handle/query/filter/order/range returns `INVALID_CURSOR`.
- [x] Prior-scope cursors return `TARGET_CHANGED`; removed/expired current-scope handles return `ARTIFACT_EXPIRED`.
- [x] Search stops at the byte/record work bound, returns only complete matches, and resumes without duplicates or omissions, including a match spanning an internal search chunk.
- [x] Payload ranges preserve UTF-8 boundaries or return base64 with exact byte offsets.
- [x] Repeated successful queries refresh the one artifact entry's last-use time; canceled/failed queries do not.
- [x] Full Go verification passes from `bifrost-console/`: `go run ./internal/buildtool verify`

#### Manual Verification

- [ ] Traverse a nested fixture from trace summary to roots, children, attempts, failures, records, and complete payload using only returned references/cursors.
- [ ] Change the selected target during a deliberately slowed query and confirm the old result is rejected as `TARGET_CHANGED`.
- [ ] Remove an unused artifact, then confirm a prior payload/record cursor reports `ARTIFACT_EXPIRED` rather than restarting.

---

## Testing Strategy

Create the dedicated PR 13 testing plan with `ai/commands/3_testing_plan.md` before implementation. It should assign requirement IDs to the failing tests below and define exact exit evidence.

### Unit Tests

- Bounded line reader, CRLF/final-line behavior, JSON depth scanner, strict decoder, numeric/timestamp overflow, UTF-8, enum sets, and cancellation.
- Chunk envelope reconstruction for JSON/text, missing/duplicate/out-of-order/mismatched chunks, and payload-store write failures.
- Frame graph validation, deep iterative traversal, incomplete/overlapping duration uncertainty, stable ordering, and checked arithmetic.
- Attempt lifecycle, retry grouping, validation links, failure/terminal links, missing response usage, unframed usage, precision completeness, and component-wise terminal reconciliation.
- Index encoding/decoding, corrupt component detection, cursor canonicalization/fingerprinting, range boundaries, and search continuation.
- Artifact staging, aggregate capacity, joined processor execution, pinning, cleanup, and every injected filesystem failure.

### Integration Tests

- Java fixture generation → committed corpus → Go processor → neutral expected results.
- Upstream catalog/artifact acquisition → staged validation/indexing → one published handle → successful query.
- Malformed/oversized/truncated/contradictory upstream artifact → `INVALID_ARTIFACT`, raw-download availability detail, no installed state.
- Concurrent browser-shaped/future-MCP-shaped callers share acquisition, bundle, pin, last-use time, calculations, domain errors, and continuations.
- Target rotation/shutdown cancels processing and queries, closes readers, removes bundle files, and releases charges.
- Finite workspace admission accounts for raw plus derived files and preserves pinned evidence.

### Manual Testing Steps

1. Run Console against a fixture-serving target and acquire a valid nested trace.
2. Confirm one Trace Storage entry exists and its byte count exceeds/equates to the complete raw-plus-derived bundle.
3. Walk all neutral queries/ranges with a small page size and compare terminal, hierarchy, duration, usage, failure, and payload facts with the expected fixture.
4. Repeat with malformed, oversized, and contradictory fixtures and confirm no analysis handle is retained.
5. Exercise target change, explicit removal, and TTL expiry between continuation calls and confirm the distinct shared errors.

## Performance Considerations

- Parsing and index construction are O(raw artifact bytes + frame/attempt/fact count), use a reusable 1 MiB maximum line buffer, and stream payload bytes directly to disk.
- Frame processing is iterative and O(frames + relationships); it does not consume call-stack depth proportional to hierarchy depth.
- Record lookup uses a fixed-width disk index. Fact queries read purpose-specific immutable indexes rather than decoding the entire raw trace.
- Search is a bounded sequential scan with explicit continuation. Do not add an unbounded in-memory inverted index or background indexing lifecycle.
- Per-call result memory is bounded by page/range constants; returned payloads are opt-in and independently ranged.
- Capacity reservation covers partial raw bytes and every derived write before disk use. Large indexes may evict eligible unused entries under the existing policy but never pinned/in-flight evidence.
- Add focused benchmarks/allocation assertions for large chunked payload processing, deep hierarchy settlement, record pagination, and bounded search. Treat regressions in allocation proportional to raw payload size as release blockers.

## Migration Notes

- No persisted migration is required. Console startup already deletes prior-process `transient` content, and the new analysis bundle is never adopted.
- The internal single-file artifact layout is removed atomically. No fallback reader or old-layout lease remains.
- Existing configured `trace-workspace.max-bytes` may admit fewer traces because it now correctly includes required derived files; this is the already-settled aggregate-byte meaning, not a configuration syntax/default change.
- Existing raw-only unit fixtures must use an explicit fake processor. Production composition always requires the real trace processor.
- Keep `consoleCompatibilityVersion` tied to the exact Bifrost release string. Do not introduce a trace/index version or compatibility alias.

## References

- Original ticket: `ai/thoughts/tickets/bifrost-console-pr-13-trace-analysis-services.md`
- Related research: `ai/thoughts/research/2026-07-30-PR-13-trace-parser-indexes-shared-calculations.md`
- Canonical semantics ticket: `ai/thoughts/tickets/bifrost-console-pr-01-canonical-trace-semantics.md`
- Artifact dependency: `ai/thoughts/tickets/bifrost-console-pr-12-artifact-service.md`
- Browser consumer: `ai/thoughts/tickets/bifrost-console-pr-14-trace-explorer.md`
- MCP consumer: `ai/thoughts/tickets/bifrost-console-pr-18-mcp-trace-inspection.md`
- Roadmap: `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md`
- Settled Phase 2 design: `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:478-506`, `:691-746`, `:912-922`
- Settled workflow calculations: `ai/thoughts/phases/bifrost_console_workflows.md:411-457`
- Future neutral query requirements: `ai/thoughts/phases/bifrost_console_phase_3_llm_runtime_inspector.md:407-434`, `:877-901`
- Framework contract lens: `ai/thoughts/framework-feature-design-lens.md`
- Java writer/reader: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:340-451`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/NdjsonExecutionTraceReader.java:41-231`
- Executable corpus: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ConsoleTraceFixtureCorpusTest.java:41-194`, `bifrost-console-fixtures/README.md`
- Go artifact seam: `bifrost-console/internal/artifact/service.go:133-277`, `bifrost-console/internal/artifact/acquire.go:42-166`, `bifrost-console/internal/artifact/lease.go:11-98`
