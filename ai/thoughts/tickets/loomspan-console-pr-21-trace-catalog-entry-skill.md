# PR 21 - Make Entry Skill Required Trace Identity

## Status

Implementation-ready ticket. Planning and codebase verification completed on
2026-08-08 against the Loomspan repository. Depends on PR 03, PR 04, PR 10,
PR 12, and PR 15. No implementation has started.

This ticket intentionally lands before PR 16 through PR 20 so the browser and
future MCP adapters consume one complete shared trace identity contract.

## Outcome

Make the entry skill a required, bounded identity fact for every Loomspan
session and trace, available from the first live snapshot through finalized
catalog listing, trace detail, artifact acquisition, and Go's installed-copy
fallback.

The Trace Catalog leads with the entry skill so a developer can identify a
trace without opening or acquiring its artifact. Trace Detail also states the
entry skill.

## Problem

The active-execution projection learns the first `ROOT_MISSION` route, but that
value is discarded at finalization. Finalized trace REST responses therefore
contain only opaque `traceId` and `sessionId` identifiers. The browser cannot
identify what a cataloged trace was for without opening and acquiring it.

This is not a serialization omission. The finalized Java descriptor, catalog
entry, REST DTO, Go trace DTO, Go acquisition metadata, cached browser fallback,
and TypeScript contract all lack the field.

Recovering the value by acquiring and parsing every NDJSON artifact is rejected.
Catalog listing is metadata-only, and the console must not automatically acquire
artifacts merely to label rows.

## Verified current behavior

- `LiveActivityProjector.updateState()` captures the first `ROOT_MISSION`
  frame's `route` into `ExecutionProjectionState.entrySkill`, first-write-wins.
- `ActiveExecutionSnapshot.entrySkill` is nullable and normalizes blank to null,
  while Go `validateActiveExecution` rejects an empty `EntrySkill`.
- `DefaultExecutionTraceHandle` emits `TRACE_STARTED` during construction,
  before `ExecutionCoordinator` normally opens the first root frame. A client
  can therefore observe an active snapshot with no entry skill and have Go
  reject the whole upstream response.
- `DefaultExecutionTraceHandle.descriptor()` constructs
  `FinalizedTraceArtifact` without entry skill. `FinalizedTraceCatalogEntry`,
  `ObservabilityDtos.Trace`, Go `observability.Trace`, and TypeScript `Trace`
  consequently lack it.
- Go `artifact.TraceMetadata` retains acquisition-time trace facts for installed
  evidence. `browserapi.cachedTrace()` reconstructs trace list/detail results
  from that metadata when the application is unavailable or unauthorized.
  Merely adding `EntrySkill` to `observability.Trace` would lose it on this
  fallback path.
- The canonical root frame route remains in normal NDJSON traces and Go trace
  analysis can expose it after acquisition. That is corroborating evidence, not
  the catalog-label mechanism.

## Required invariant and why first-root capture is insufficient

`entrySkill` is required. It is the normalized registered name of the top-level
YAML skill whose invocation owns the session. It does not change when nested
skills or nested `ROOT_MISSION` frames execute in the same session.

The invariant must be established before session and trace construction, not
discovered later from the first root frame. The repository has real paths that
can finalize without a `ROOT_MISSION`:

- `LoomspanSessionRunner` accepts arbitrary actions and finalizes a session even
  when the action opens no frame.
- A top-level skill can be rejected by `AccessGuard` before
  `ExecutionCoordinator.openMissionFrame()` runs. The runner then records and
  finalizes the failure with no root frame. With retained error traces and
  observability enabled, that trace is eligible for catalog publication.
- Trace construction publishes `TRACE_STARTED` before the first root frame, so
  first-root discovery also cannot satisfy the live Go contract from the first
  visible snapshot.

Automatically opening a synthetic `ROOT_MISSION` for standalone or denied
execution is rejected. It would invent an execution frame and alter canonical
trace/journal semantics. Making the finalized field nullable is also rejected;
it would preserve the cross-layer disagreement and force every browser and MCP
consumer to handle a state Loomspan can prevent at creation time.

## Resolved design

### 1. Establish entry skill at session creation

`DefaultSkillTemplate` already resolves and validates the requested YAML
capability before calling `LoomspanSessionRunner`. Pass `capability.name()` into
the runner as required session identity.

Change the runner APIs in place so every new session requires a nonblank entry
skill. Do not retain entry-skill-free overloads. Update standalone/internal test
callers to provide an explicit stable entry route such as `test.entry`; do not
manufacture a root frame for them.

Carry the same normalized value through:

1. `LoomspanSessionRunner`;
2. `LoomspanSession`;
3. `ExecutionObservationHandleFactory.create(...)` and the initial
   `ExecutionProjectionState`;
4. `InternalExecutionTraceHandleFactory.create(...)` and
   `DefaultExecutionTraceHandle`;
5. `FinalizedTraceArtifact`.

The value must be present before `DefaultExecutionTraceHandle.initialize()`
publishes `TRACE_STARTED`. This removes the pre-root nullable live snapshot.
`DefaultExecutionObservationHandle` must construct its projection state with
that value, and `ActiveExecutionSnapshot.entrySkill` becomes required rather
than nullable. Its constructor must reject null or blank instead of normalizing
them to null.

The session should retain the required identity and expose only the minimum
internal accessor needed for consistency checks. When `ExecutionCoordinator`
begins a top-level invocation, assert that its resolved root capability name
matches the session entry skill. Nested invocation names must not replace it.

### 2. Use one bounded representation

The active contract currently bounds entry skill to
`ExecutionObservationLimits.TEXT_CODE_POINTS` code points. Define the
normalization once at the session-identity boundary and pass that exact value to
both the observation and trace handles. It must:

- reject null or blank input before session construction;
- use the existing 256-code-point contract;
- avoid independently truncating or selecting the value in multiple layers;
- ensure live `ActiveExecution.entrySkill` and finalized `Trace.entrySkill` are
  byte-for-byte equal for the same session.

Prefer a small core-owned value/helper for this required identity over making
core depend on the optional observation package. Do not leave both session-time
normalization and first-root selection as competing definitions.

The existing first-root projector logic becomes a consistency check or is
removed in favor of the pre-seeded state. It must not overwrite the session
identity. A normal first top-level root frame must agree with it; nested roots
must not affect it.

### 3. Carry entry skill through finalization and the application REST contract

Add required `String entrySkill` components to, at minimum:

- `FinalizedTraceArtifact`;
- `FinalizedTraceCatalogEntry`;
- `ObservabilityDtos.Trace`.

`DefaultExecutionTraceHandle.descriptor()` copies its required session identity
into the finalized descriptor. `InMemoryFinalizedTraceCatalog.publish()` copies
it from the core-issued descriptor into the catalog entry.
`ObservabilityDtoMapper.trace()` maps it to the REST `Trace` DTO as JSON field
`entrySkill`.

The catalog remains populated from the core-issued finalized descriptor. Do not
read entry skill from optional live projection state during catalog publication,
scan storage, resolve `artifactPath`, or parse NDJSON.

All record and constructor signatures change in place. Update every construction
site atomically; do not add compatibility constructors or nullable defaults.

### 4. Carry entry skill through Go and installed-copy fallback

Add required `EntrySkill string \`json:"entrySkill"\`` to
`internal/observability.Trace` and make `validateTrace` reject an empty value.

Also add required `EntrySkill` to `artifact.TraceMetadata`, which is the immutable
acquisition-time metadata retained for installed evidence. Copy it in the
`TraceLoader` assembled by `internal/console/service.go`, and restore it in
`browserapi.cachedTrace()`/`cachedTracePage()`.

This is required even though PR 21 does not change acquisition behavior. Without
it, an acquired trace would show entry skill while the application is reachable
and silently lose it when the browser falls back to its installed copy.

`AcquiredArtifact` does not need a duplicate top-level field because callers get
trace identity from `Trace`; the retained internal `TraceMetadata` is the
authoritative installed-copy source. `StoredEntry` and the Trace Storage table
remain unchanged unless implementation reveals they reconstruct `Trace` without
using `Lookup` metadata.

### 5. Browser presentation

Add required `entrySkill: string` to the TypeScript `Trace` contract.

Trace Catalog presentation is settled:

- add `Entry skill` as the first column, before `Trace ID`;
- render it as plain React text;
- retain Trace ID as the detail-navigation link;
- retain the existing scrollable/focusable table region and semantic column
  headers.

Trace Detail presentation is also settled: add `Entry skill` to the identity
facts near Trace ID and Session ID.

Do not link the catalog entry skill to the current registered-skill catalog in
this PR. A finalized trace is a recorded fact, while current registration can
change independently. Existing React rendering supplies the required content
escaping; do not use raw HTML.

## Java implementation map

Production areas expected to change:

- `internal/skillapi/DefaultSkillTemplate`
- `internal/core/LoomspanSessionRunner`
- `internal/core/LoomspanSession`
- `internal/core/InternalExecutionTraceHandleFactory`
- `internal/runtime/observation/ExecutionObservationHandleFactory`
- `internal/runtime/observation/NoOpExecutionObservationHandleFactory`
- `internal/runtime/observation/DefaultExecutionObservationHandleFactory`
- `internal/runtime/observation/DefaultExecutionObservationHandle`
- `internal/observability/ObservabilityActivationCoordinator`
- `internal/runtime/observation/ExecutionProjectionState`
- `internal/runtime/observation/LiveActivityProjector`
- `internal/runtime/observation/ActiveExecutionSnapshot`
- `internal/runtime/trace/DefaultExecutionTraceHandle`
- `internal/core/FinalizedTraceArtifact`
- `internal/runtime/observation/catalog/FinalizedTraceCatalogEntry`
- `internal/runtime/observation/catalog/InMemoryFinalizedTraceCatalog`
- `internal/observability/web/dto/ObservabilityDtos`
- `internal/observability/web/ObservabilityDtoMapper`

Update architecture allow-list explanations only if a new public internal type
is genuinely required for cross-package composition. Prefer package-private
types where possible.

Known direct `FinalizedTraceArtifact` construction sites that must move with the
signature include the trace handle plus tests for scheduled retention,
observation completion, catalog behavior, artifact REST integration, phase-one
integration, and REST integration.

## Go and browser implementation map

Production areas expected to change:

- `loomspan-console/internal/observability/dto.go`
- `loomspan-console/internal/observability/service.go`
- `loomspan-console/internal/artifact/model.go`
- `loomspan-console/internal/console/service.go`
- `loomspan-console/internal/browserapi/observability.go`
- `loomspan-console/web/src/api/contracts.ts`
- `loomspan-console/web/src/observability/Traces.tsx`
- `loomspan-console/web/src/observability/TraceDetail.tsx`

Update JSON metadata builders in browser and end-to-end tests, including the
trace responses in `web/e2e/target-context.spec.ts`,
`web/e2e/live-executions.spec.ts`, and
`web/e2e/artifact-storage.spec.ts`.

Update artifact test helpers so valid `TraceMetadata` defaults include a stable
entry skill. Tests intentionally exercising missing upstream identity should
construct the empty value explicitly.

## Contract and fixture changes

The application REST field is required and named `entrySkill` in list and detail
responses. Update:

- `loomspan-console-fixtures/application-rest/traces-page.json`;
- `loomspan-console-fixtures/application-rest/trace-detail.json`;
- `ConsoleRestFixtureCorpusTest`'s `ObservabilityDtos.Trace` fixture;
- Go REST decoding/validation corpus tests;
- browser contract and component fixtures;
- end-to-end application-response mocks.

Regenerate only the application REST corpus with the intentional DTO change.
This design does not add entry skill to `TRACE_STARTED` or otherwise change
canonical NDJSON bytes, so the trace-analysis input/expected corpus should remain
byte-for-byte unchanged. If implementation finds a necessary canonical-record
change, stop and revise this ticket before regenerating the trace-analysis
corpus; do not silently broaden the change.

## Required semantic tests

### Java lifecycle and invariant tests

- Session/runner creation rejects null or blank entry skill before trace or
  observation construction.
- The observation and trace-handle factories receive the same normalized entry
  skill.
- The first visible active snapshot, including the snapshot projected from
  `TRACE_STARTED`, has a nonblank entry skill.
- A normal top-level `ROOT_MISSION` route agrees with session entry skill.
- Nested `ROOT_MISSION` frames cannot replace entry skill.
- A restricted top-level skill denied before opening a root frame still
  finalizes/catalogs with the requested entry skill when policy allows.
- Standalone runner actions that open no frame retain the explicitly supplied
  entry route and finalize normally.
- The finalized descriptor and catalog entry preserve the required value.
- Repeated catalog publication remains idempotent and treats differing entry
  skill as a conflicting descriptor through ordinary record equality.
- Core finalization failure still cannot publish a trustworthy artifact.
- Optional observation/catalog failures still cannot alter execution outcome or
  canonical finalization semantics.

### REST and Go service tests

- Java trace list and detail JSON contain required `entrySkill`.
- Go list/detail decoding succeeds with it and rejects missing or empty values as
  an invalid upstream response.
- Acquisition copies it into immutable `artifact.TraceMetadata`.
- Reachable trace list/detail and unreachable/unauthorized cached fallback
  return the same entry skill.
- Target-scope rotation and artifact eviction retain their existing behavior;
  no metadata survives beyond the installed entry's normal lifetime.

### Browser tests

- Trace Catalog's first header is `Entry skill`, and the row shows the value
  before the linked Trace ID.
- Trace Detail states entry skill near its identity facts.
- Application-authored characters render as text, not markup.
- Existing keyboard reachability, focus behavior, pagination, date formatting,
  target-scope binding, and cached fallback continue to work.

## Acceptance signals

- Every session is created with a required bounded entry skill before
  `TRACE_STARTED` is published.
- Go never receives a valid active or finalized trace response with empty entry
  skill.
- A trace finalized after the change exposes exactly the same entry skill in
  live state, catalog list, detail, acquisition metadata, and cached fallback.
- Access-denied-before-root and standalone-no-frame paths satisfy the invariant
  without synthetic frames and without failing finalization.
- Nested execution cannot overwrite the session entry skill.
- The Trace Catalog leads with entry skill; Trace ID remains the navigation link;
  Trace Detail includes the field.
- Java and Go application REST fixtures agree byte-for-byte after regeneration.
- Canonical trace bytes, completed-journal projection, retention policies,
  acquisition behavior, and trace-analysis semantics are unchanged.
- No compatibility overload, nullable reader, legacy alias, artifact parser, or
  fallback derivation was introduced.

## Guardrails

- Entry skill is required session identity, not a value opportunistically
  inferred at finalization.
- Do not create a synthetic root frame for denied or standalone execution.
- Do not let optional observability become authoritative for core finalized
  metadata.
- Do not change execution outcome, exception propagation, journal failure
  behavior, trace retention, catalog TTL, acquisition, or cache eviction.
- Do not scan trace storage, resolve caller-provided paths, or automatically
  acquire artifacts to determine entry skill.
- Do not add a trace-format version or independent compatibility signal.
- Treat the name as recorded application content; do not infer intent,
  importance, or current registration from it.
- Preserve user-authored UI changes already present in the working tree and
  avoid unrelated cleanup while implementing this ticket.

## Compatibility position

Loomspan is pre-release and Java, Go, TypeScript, and fixtures move in lockstep.
The console rejects a target whose exact `consoleCompatibilityVersion` differs.
The application trace catalog is current-process-only and empty after restart.
Therefore all changed signatures and required JSON fields change in place, with
no migration or compatibility shim.

## Verification sequence

Run focused tests while implementing, then complete the repository checks:

```powershell
.\mvnw.cmd -pl loomspan-spring-boot-starter test -DfailIfNoTests=false
.\mvnw.cmd -pl loomspan-spring-boot-starter test -Dtest=ConsoleRestFixtureCorpusTest -Dloomspan.console.fixtures.regenerate=true -DfailIfNoTests=false
Set-Location loomspan-console
go test ./...
go run ./internal/buildtool verify
$env:PATH = "C:\msys64\mingw64\bin;" + $env:PATH
$env:CGO_ENABLED = "1"
go test -race ./...
```

After fixture regeneration, inspect the diff and confirm only
`application-rest/traces-page.json` and `application-rest/trace-detail.json`
changed under `loomspan-console-fixtures`; the NDJSON and trace-analysis expected
corpora must remain unchanged.

## Out of scope

- Backfilling metadata for already cataloged traces.
- Parsing artifacts to derive entry skill.
- Cross-restart trace history.
- Adding entry skill to the Trace Storage table or storage snapshot DTO.
- Linking finalized entry skill to the current skill catalog.
- MCP adapter/tool implementation. PR 18 should consume the enriched shared
  trace service contract without inventing another entry-skill derivation.
- Any unrelated UI cleanup.

## Related UI work already present

The current working tree contains user-authored console changes, including Trace
Catalog date formatting through `formatDateTime`. Those changes are independent
of this ticket and must be preserved. PR 21 should build on the current Loomspan
UI rather than reverting or recreating them.
