# PR 15 Diagnostic Workflows and Phase 2 Hardening Testing Plan

## Change Summary

- Complete the four approved Console workflows over the existing live, artifact, and hierarchy-first trace explorer foundations.
- Fix core-finalization presentation, add a run-start snapshot of the five configured quotas to current-release traces, and carry recorded frame/failure relationships through Go and browser DTOs.
- Add failure-focused, usage-focused, and registered-skill-YAML coordination without automatic acquisition, browser-side diagnostic calculations, or workspace source mapping.
- Prove degraded lifecycle behavior, hostile-content isolation, authority ordering, bounded responses, keyboard/accessibility behavior, deterministic native packaging, and release CI.
- Update skill-authoring guidance only where focused fixtures and tests establish the described run-start-limit and registered-name semantics.

The authoritative unfamiliar-skill-path IDs are `WF-SP-R1` through `WF-SP-R14` in `ai/thoughts/phases/LOOMSPAN_console_workflows.md`. Tests and completion evidence must use only those canonical IDs; `WF-US-*` is not an alias.

## Impacted Areas

- Java trace production and fixtures: `DefaultExecutionTraceHandle`, session-runner/factory wiring, `ConsoleTraceFixtureCorpusTest`, and `loomspan-console-fixtures`.
- Go trace analysis: parser/model/processor, frame/failure projection, neutral DTOs, continuations, limits, and fixture-corpus tests under `loomspan-console/internal/traceanalysis`.
- Go browser and lifecycle boundaries: `internal/browserapi`, `internal/target`, `internal/live`, `internal/artifact`, and stable domain-error mapping.
- React contracts and presentation: active execution detail, trace detail/explorer/state, hierarchy, usage, records/evidence, skill detail, scope reset, and API contracts/client.
- Browser E2E: live execution, artifact/trace workflows, pairing/error states, reconnect/restart, accessibility-critical interaction, and hostile content.
- Build and release: `internal/buildtool`, `LICENSE`, package README, Console README, fixture README, and GitHub Actions workflows.
- Documentation evidence: skill-authoring trace guidance, Phase 2 design correction, and Phase 2 completion-evidence index.

## Risk Assessment

- **High:** Java writer and Go reader could disagree about optional `configuredLimits`, complete-object validation, the `0..2147483647` integer domain, field names, or fixture line endings. Spring producers and current-checkout readers must move atomically; absent metadata remains valid only as current “limit comparison unavailable” semantics for standalone/internal producers, not as a historical compatibility fallback.
- **High:** Failure focus could select a recovered/nonterminal error, infer relationships from adjacency, eagerly fetch payloads, or invent an outcome after core finalization failure.
- **High:** Usage percentages or attribution could be calculated in React, divide by zero, collapse unknown into zero, double-count inclusive totals, or imply cost/importance/cause.
- **High:** Application-provided text could create active DOM, routing, authority-bearing URLs, headers, or API requests; middleware precedence could parse an untrusted body before rejecting Host/Origin/session/tab/CSRF.
- **High:** stale scope, expiry, restart, and later auth rejection could either leak prior target state or incorrectly destroy a complete locally acquired artifact.
- **High:** packaging could admit path traversal/symlinks/unexpected files, produce nondeterministic archives, publish a partial target set, or grant write permission to untrusted pull-request jobs.
- **Medium:** deep failure/frame lookup or usage sorting could omit evidence at page boundaries or add an accidental semantic node/depth cap.
- **Medium:** live updates, route changes, dialogs, tree/tabs, reconnects, responsive layouts, or status announcements could break keyboard/focus/accessibility behavior.
- **Medium:** authoring prose could overstate configuration syntax, cross-version trace compatibility, monetary cost, causal conclusions, or deployment provenance.

### Contract and Compatibility Scope

| Surface | Test treatment |
| --- | --- |
| Application API | Preserve existing quota property names and runtime behavior; add no application-facing API test for Console-only DTOs. Protect existing standalone constructor behavior if an internal public constructor overload changes, and assert no internal quota type leaks into supported signatures. |
| Supported SPI | No new SPI. Existing supported SPI suites remain green; internal Go/Java collaborators change atomically. |
| Configuration and manifest contracts | Preserve strict Console YAML, skill YAML, quota configuration, and asset-manifest behavior. Test that the trace records a creation-time value snapshot, not that configuration syntax changed. |
| Persisted or serialized contracts | Update Java fixture producers, Go consumers/browser adapter, React contracts, and executable fixtures together. Assert exact current JSON/NDJSON names, optionality, and path/handle exclusion. No version bridge. |
| Ephemeral diagnostic formats | Accept absent `TRACE_STARTED.configuredLimits` as current “limit comparison unavailable” semantics. Spring fixtures require it; when present, require all five members as integers in `0..2147483647` and reject incomplete/malformed/type/range-invalid objects. Test current writer/reader/projector/debugging-doc coherence, not historical readability. |
| Internal or accidentally exposed implementation | Update index rows, DTOs, URL coordinates, package internals, and workflow composition without compatibility aliases or tests that preserve obsolete shapes. |

Protected Java-to-Go behavior includes exact application release rejection, application REST/SSE/problem fixtures, artifact transport, activity finalization fields, current trace semantics, and observable target/auth/scope behavior. Browser analysis responses must remain handle-free and path-free. Intentionally removed behavior includes quota-less Spring-produced fixtures, the incorrect browser check for `applicationTraceAvailability === "CORE_FINALIZATION_FAILED"`, and the Phase 2 manual assistive-technology acceptance requirement. Absent quota metadata remains a valid current semantic only for standalone/internal producers.

## Existing Test Coverage

- `ActiveExecutionDetail.test.tsx` already covers selected terminal context, missed terminal reconciliation, the finalization-failure branch, and deliberate acquisition. Its finalization fixture currently encodes the browser-side field mismatch and must become the first focused regression.
- `live-executions.spec.ts` covers selection during activity, terminal/observation-ended transitions, target-change reset, and deliberate acquisition. Scenario names use partial `WF-SE` labels and need exact requirement references.
- `calculations_test.go`, `continuation_test.go`, and `fixture_corpus_test.go` cover hierarchy validity, 20,000-deep traversal, direct/descendant/inclusive usage, retry/attempt identity, terminal failure, unknown usage, continuation reachability, bounds, and Java fixture agreement.
- `browserapi/contracts_test.go`, `trace_analysis_test.go`, router/observability/artifact tests cover serialized fixtures, security headers, stable errors, scope behavior, and several authority checks.
- `TraceViews.test.tsx` and `artifact-storage.spec.ts` cover tree keyboard behavior at component level, inert evidence/YAML, hierarchy/timeline/usage selection, incomplete facts, bounded payload reads, auth-after-acquisition, and target rotation.
- `artifact-storage.spec.ts` has named failed, expensive, and unfamiliar-skill scenarios, but they prove generic explorer behavior rather than all corresponding workflow requirements.
- Buildtool tests cover pipeline ordering, asset determinism, cleanup/path containment, pinned toolchains, and product-version parsing. There is no native archive, checksum, extraction, smoke, or release-aggregation coverage.
- There is no committed automated accessibility scan, transient same-instance reconnect scenario, representative trace/target matrix, GitHub workflow verification, native package evidence, or Phase 2 completion index.

## Bug Reproduction / Failing Test First

### 1. Canonical core-finalization fields

- Type: component unit, then E2E regression.
- Location: `loomspan-console/web/src/observability/ActiveExecutionDetail.test.tsx`; `loomspan-console/web/e2e/live-executions.spec.ts`.
- Arrange/Act/Assert outline: provide `EXECUTION_OBSERVATION_ENDED` with `applicationTraceAvailability: "UNAVAILABLE"`, `applicationTraceUnavailableReason: "CORE_FINALIZATION_FAILED"`, and no outcome; render the selected execution; assert incomplete observation and unavailable trace are separate, while outcome and Inspect trace are absent.
- Expected failure (pre-fix): the browser does not enter the finalization-failed branch because it checks the availability field for the reason code.
- Requirements: `WF-FE-R1`, `WF-FE-R3`, `WF-FE-R8`, `WF-SE-R8`.

### 2. Required run-start quota snapshot

- Type: Java unit/fixture integration followed by Go corpus test.
- Location: focused trace-handle/session-runner tests; `ConsoleTraceFixtureCorpusTest.java`; `internal/traceanalysis/fixture_corpus_test.go`.
- Arrange/Act/Assert outline: create a Spring trace with non-default limits, mutate/replace configuration after trace creation, finalize it, and assert `TRACE_STARTED.configuredLimits` contains the original five integer values; then require Go summary values to match byte-for-byte. Add a valid absent-object standalone trace plus missing-member, unknown-member, float, negative, and above-`2147483647` invalid fixtures.
- Expected failure (pre-fix): Java emits no snapshot and Go has no optional parser/model projection or complete-object validation.
- Requirements: `WF-UE-R1`, `WF-UE-R3`–`WF-UE-R5`; skill-authoring limit-snapshot claim.

### 3. Recorded relationships survive neutral projection

- Type: Go unit/adapter contract.
- Location: `internal/traceanalysis/calculations_test.go` or focused DTO test; `internal/browserapi/trace_analysis_test.go`.
- Arrange/Act/Assert outline: build a frame/failure with exact skill names, attempt IDs, retry sequence IDs, validation statuses, failure ID, sequence, timestamp, record type, frame, and route; query neutral and browser DTOs; assert exact preservation and no inferred adjacent relationship.
- Expected failure (pre-fix): `FrameSummary` and browser DTOs discard these relationships.
- Requirements: `WF-FE-R5`–`WF-FE-R7`, `WF-UE-R3`, `WF-UE-R9`, `WF-SP-R4`, `WF-SP-R6`, `WF-SP-R7`, `WF-SP-R10`.

These are the first red tests. The remaining workflow, security, packaging, and accessibility cases are behavior additions; add each focused test before its implementation slice and record the pre-fix failure in the PR evidence.

## Tests to Add/Update

### 1. Trace quota contract and fixture corpus

- Type: Java unit/integration and Go integration.
- Location: trace creation/wiring tests; `ConsoleTraceFixtureCorpusTest.java`; `loomspan-console-fixtures`; `internal/traceanalysis/{parser,fixture_corpus,continuation}_test.go`.
- What it proves: immutable five-value creation-time snapshot for Spring-created traces; valid absent-object semantics for standalone/internal traces; exact current NDJSON names and `0..2147483647` integer domain; LF/idempotent fixture generation; valid summary projection; rejection as `INVALID_ARTIFACT` for a present object with a missing/unknown member, float/string/null, negative, above-max, duplicate, or structurally invalid metadata; no historical compatibility reader.
- Fixtures/data: one non-default valid trace plus generated invalid mutations at exact max/one-over boundaries.
- Mocks: none across the corpus; use mutable test configuration only to prove snapshot isolation.
- Contract classification: Configuration and manifest contracts; Ephemeral diagnostic formats.
- Compatibility expectation: protected quota configuration behavior; intentional current-run format replacement.
- Requirements: `WF-UE-R1`, `WF-UE-R3`–`WF-UE-R5`, `WF-UE-R11`.

### 2. Finalization and live lifecycle semantics

- Type: React component, Go live/target integration, Playwright E2E.
- Location: `ActiveExecutionDetail.test.tsx`, live reducer/provider tests, `internal/live`, `internal/target`, `web/e2e/live-executions.spec.ts`.
- What it proves: selected context remains in place; active and recent-terminal sections are distinct; outcome and availability remain independent; finalization failure invents neither outcome nor trace; Inspect trace is deliberate; bounded/replay-gap state never appears durable; active path/freshness/provisional facts remain explicit.
- Fixtures/data: canonical Java SSE finalization fixture, retained-window start, replay overflow/reset, disappearing baseline execution, unavailable monitoring, trace not retained, no retained evidence.
- Mocks: deterministic relay/target fixture; real packaged Console process for representative E2E.
- Contract classification: Persisted or serialized contracts.
- Compatibility expectation: protected Java/Go/browser finalization and continuity semantics; remove incorrect field interpretation.
- Requirements: all `WF-FE-R1`–`WF-FE-R4`, `WF-FE-R8`, `WF-FE-R10`; all `WF-SE-R1`–`WF-SE-R10`.

### 3. Failure-focused trace entry and deep resolution

- Type: Go unit/adapter, React component, Playwright E2E.
- Location: trace calculation/query and browser adapter tests; new focused failure component tests beside `TraceExplorer`; `artifact-storage.spec.ts`.
- What it proves: failed/aborted trace defaults only to recorded `terminalFailureId`; recovered errors never substitute; incoming failure focus survives deliberate acquire/open and view changes; terminal failure beyond page 1 is found through finite continuation; exact IDs and direct recorded relationships navigate hierarchy/timeline/usage/records; gaps/uncertainties are explicit; payload/raw bytes are not loaded until requested; cause is never asserted.
- Fixtures/data: terminal failure after more than 100 failure/record rows, recovered earlier error, missing/incomplete timing/usage, gap and uncertainty, malformed/stale focus.
- Mocks: component API spies for lazy-load assertions; real fixture server for E2E.
- Contract classification: Ephemeral diagnostic formats; Internal presentation state.
- Compatibility expectation: current-run writer/reader/projector coherence; malformed/stale internal coordinates are rejected, not preserved.
- Requirements: `WF-FE-R5`–`WF-FE-R8`; `WF-SP-R4`–`WF-SP-R6`.

### 4. Usage definitions, limits, and contributor navigation

- Type: Go unit/adapter, React component, Playwright E2E.
- Location: `calculations_test.go`, query/adapter tests, `TraceUsage.test.tsx` or `TraceViews.test.tsx`, `artifact-storage.spec.ts`.
- What it proves: browser consumes the same direct/descendant/inclusive/attempt/retry/unattributed facts as neutral Go DTOs; units are preserved; numerator and denominator produce a percentage with at most two decimal places for nonzero limits; zero is labeled undefined and absence unavailable with no percentage; tests assert numeric/accessibility semantics without locale-specific punctuation; missing is unknown; duration stays separate; `USAGE_DESC` is deterministic and retains path/frame ID; overlapping inclusive totals are not summed; raw payloads stay lazy; no currency or judgment labels appear.
- Fixtures/data: nonzero/zero/absent limits, exact/heuristic/unavailable usage, unattributed and unframed-attributed usage, incomplete hierarchy, tied contributor totals, retry/validation relationships.
- Mocks: spy API only for presentation/lazy loading; use corpus values for arithmetic semantics.
- Contract classification: Ephemeral diagnostic formats.
- Compatibility expectation: current-run diagnostic coherence shared by browser and future adapters.
- Requirements: all `WF-UE-R1`–`WF-UE-R13`; `WF-SP-R5`, `WF-SP-R6`.

### 5. Hierarchy and registered YAML coordination

- Type: Go query/adapter, React component, Playwright E2E.
- Location: frame query/continuation tests, browser adapter tests, `TraceHierarchy`/`TraceExplorer`/`SkillDetail` tests, `artifact-storage.spec.ts`.
- What it proves: complete finalized hierarchy and bounded active path remain distinct; every repeated invocation has a unique frame ID; breadcrumbs use recorded identifiers/routes/names; exact recorded names alone match current-scope catalog entries; absent registration is explicit; matching skill opens unchanged inert YAML; `sourcePath` remains non-link text and never becomes a Go/browser filesystem lookup; scope/instance changes clear trace and skill state.
- Fixtures/data: repeated and nested frames, same route with different names, exact/case-mismatched/unregistered names, hostile YAML/source paths, more than one frame page, 20,000-deep calculation fixture.
- Mocks: catalog API spy proving calls use registered name and current scope, never `sourcePath`; real fixture server for representative E2E.
- Contract classification: Persisted or serialized contracts; Ephemeral diagnostic formats.
- Compatibility expectation: protect skill DTO/YAML contract and shared hierarchy calculation; no hierarchy-specific semantic cap or source mapping.
- Requirements: `WF-SP-R1`–`WF-SP-R11`, `WF-SP-R14`. `WF-SP-R12` is Phase 3 IDE behavior and `WF-SP-R13` is a negative scope invariant; both are covered in the requirement matrix below rather than by adding Console functionality.

### 6. Degraded lifecycle and error precedence

- Type: Go unit/integration, React provider/component, representative Playwright E2E.
- Location: `internal/{target,live,artifact,traceanalysis,browserapi}` tests; provider/scope/explorer tests; live/artifact E2E specs.
- What it proves: unavailable monitoring, retained-window loss, upstream expiry, local expiry, invalid artifact, gaps, replay/subscriber overflow, transient reconnect, instance restart, target rotation, auth rejection, finalization failure, and incomplete evidence remain distinct; `TARGET_CHANGED` precedes `ARTIFACT_EXPIRED`, which precedes malformed cursor; stale browser coordinates clear; complete acquired evidence remains locally inspectable after later auth rejection while new upstream work is blocked.
- Fixtures/data: domain error table and separate same-instance disconnect versus new-instance restart fixtures.
- Mocks: controllable target/application fixture and clocks; real browser only for representative observable paths.
- Contract classification: Persisted or serialized contracts; Internal lifecycle state.
- Compatibility expectation: protected observable domain codes and acquisition-time authorization; stale internal state removed without fallback.
- Requirements: `WF-FE-R8`–`WF-FE-R10`; `WF-SE-R3`, `WF-SE-R8`, `WF-SE-R9`; `WF-UE-R1`, `WF-UE-R2`, `WF-UE-R11`; `WF-SP-R1`, `WF-SP-R2`, `WF-SP-R7`, `WF-SP-R10`, `WF-SP-R11`.

### 7. Hostile application content and authority order

- Type: Go router integration, React component, production-source guard, Playwright E2E.
- Location: `internal/browserapi/*_test.go`; `TraceViews.test.tsx` plus live/skill/failure tests; a focused source guard; `artifact-storage.spec.ts`.
- What it proves: hostile markup, scripts, anchors, forms, control characters, oversized labels, route/failure instructions, raw records, and reconstructed payloads stay text; they cannot create active DOM, route navigation, headers, URLs, or API calls. Host rejects before authentication/body processing; Origin/session/tab/CSRF retain per-operation order; raw download retains attachment policy. Application-content presenters contain no unreviewed `dangerouslySetInnerHTML`.
- Fixtures/data: hostile strings in every listed application-provided field and an oversized/invalid body whose downstream decoder would fail if reached.
- Mocks: request counters prove rejected input never reaches auth/body/service; browser API spies prove content never triggers calls.
- Contract classification: Persisted or serialized contracts.
- Compatibility expectation: protected browser authority/security boundary.
- Requirements: `WF-FE-R6`, `WF-UE-R13`, `WF-SP-R8`, `WF-SP-R9`, `WF-SP-R12`, `WF-SP-R14` plus ticket security acceptance.

### 8. Bounds, finite continuation, and representative corpus

- Type: Go unit/integration and Playwright E2E.
- Location: `limits.go` tests, browser adapter tests, fixture corpus/README, E2E synthetic trace server.
- What it proves: exact maximum succeeds and one-over fails for 8-KiB body, 1-MiB line, depth 128, page 1,000, 1-MiB range, 1-KiB/256-code-point search, 8-MiB/10,000-record work; every valid frame/record/payload is reachable through finite continuations; one primary fixture per workflow, repeated frames, the existing 20,000-deep stress case, a browser page boundary over 100 rows, and a deterministically synthesized multi-megabyte payload continuable in 64-KiB reads establish the representative matrix without a semantic hierarchy cap.
- Fixtures/data: committed matrix of the four primary workflows, deep, repeated, page-boundary, incomplete, and malformed structural cases; synthesize large payload bytes rather than committing duplicates.
- Mocks: bounded deterministic readers that report bytes/work performed.
- Contract classification: Ephemeral diagnostic formats; Persisted browser request/response contracts.
- Compatibility expectation: current-run diagnostic usefulness and boundedness; no historical fixture obligation.
- Requirements: `WF-FE-R7`, `WF-FE-R8`, `WF-UE-R1`, `WF-SP-R1`, `WF-SP-R3`, `WF-SP-R11`.

### 9. Keyboard, focus, responsive, and automated accessibility

- Type: component and Playwright E2E with pinned `@axe-core/playwright` automated accessibility scans.
- Location: shell/live/trace/hierarchy/dialog component tests; representative E2E specs; `web/package.json` only if an approved scanner is added.
- What it proves: tree and tab Arrow/Home/End algorithms; route-heading focus; dialog trap and return; live updates do not steal focus/selection/scroll; Resume live works; visible focus and restrained announcements; semantic landmarks/headings/tables/tree/tabs; `@axe-core/playwright` reports no configured serious/critical violations; usable desktop/640px/320px layouts, 200% zoom, forced colors, reduced motion, and no whole-page horizontal scroll.
- Fixtures/data: Overview, Live Detail, failure, Usage, Records, Skills, pairing, and error states.
- Mocks: component fake timers for live updates; real Chromium for E2E and scans.
- Contract classification: Internal presentation behavior.
- Compatibility expectation: current browser accessibility behavior; no manual screen-reader acceptance requirement.
- Requirements: `WF-FE-R1`, `WF-FE-R2`, `WF-SE-R2`–`WF-SE-R5`, `WF-SE-R8`, `WF-UE-R8`, `WF-SP-R1`, `WF-SP-R4`, `WF-SP-R6` plus ticket accessibility acceptance.

### 10. Deterministic package construction and native smoke

- Type: Go unit/integration and native CI smoke.
- Location: new `internal/buildtool` package tests; `.github/workflows/console-release.yml` native jobs.
- What it proves: supported target/version validation; exact archive names; one safe top-level directory containing only executable, `LICENSE`, and `README.md`; normalized order/timestamps/permissions/gzip/ZIP/TAR metadata; symlink/traversal/unexpected-file rejection; byte-identical repeat; correct sidecars and sorted `SHA256SUMS`; extracted `--version` and isolated loopback startup without Java, Node, database, or target filesystem.
- Fixtures/data: small fake executable for archive unit tests and actual native binary for CI; corrupted/duplicate/missing sidecars.
- Mocks: fake runner/clock for unit determinism; no mock for native execution.
- Contract classification: Internal build implementation; packaged serialized artifact.
- Compatibility expectation: exact new release package contract; fail closed rather than accepting alternate layouts.

### 11. Pull-request and release workflow policy

- Type: static workflow contract test plus clean-run CI integration.
- Location: buildtool project-declaration tests; `.github/workflows/console-ci.yml`; `.github/workflows/console-release.yml`.
- What it proves: PR workflow uses `pull_request`, read-only permissions, pinned action SHAs, exact toolchains/locks, Java fixtures, canonical Console verify, and Playwright; release build jobs are read-only and only final publication has write; `v<version>` is stripped and compared exactly to a non-SNAPSHOT root POM version; manual dispatch does not publish by default; tag/POM mismatch, SNAPSHOT publication, missing target, duplicate/unexpected artifact, or checksum mismatch blocks publication; all three native jobs precede one coordinated release.
- Fixtures/data: parsed workflow YAML, tag cases (`v0.1.0`, mismatched, malformed, and SNAPSHOT), and release artifact-name matrix. Verify the GitHub-hosted macOS arm64 runner label during implementation rather than hard-coding an unverified planning assumption.
- Mocks: static parser/fake aggregation directory; publication validated first through non-publishing manual mode.
- Contract classification: Configuration and manifest contracts.
- Compatibility expectation: new fail-closed release policy; no `pull_request_target` compatibility path.

### 12. Documentation and completion evidence integrity

- Type: source/document contract checks plus recorded manual review.
- Location: buildtool/project declaration tests where practical; `ai/skill-authoring`; Phase 2 design/evidence index; Console and fixture READMEs.
- What it proves: runnable commands/paths exist; skill guidance states finalized versus provisional, missing limits, arithmetic-only proportions, exact registered-name navigation, inert YAML, and no cost/cause/provenance claims; coverage table remains honest; completion rows cite an actually passing command/workflow/platform/date and leave unavailable manual evidence pending; manual assistive-technology acceptance is explicitly out of scope.
- Fixtures/data: links to focused tests/corpus and workflow run IDs, not copied behavioral examples.
- Mocks: none.
- Contract classification: authoring guidance over Configuration contracts and Ephemeral diagnostic formats.
- Compatibility expectation: guidance describes current checkout only and does not promise cross-version traces.

## Requirement-to-Evidence Matrix

Every surfaced workflow requirement is assigned below. `A#` refers to the automated test group above; `M#` refers to the manual checks under **Manual Verification**.

| Requirements | Primary evidence |
| --- | --- |
| `WF-FE-R1`, `WF-FE-R2`, `WF-FE-R3`, `WF-FE-R4` | A2, A9; M1 |
| `WF-FE-R5`, `WF-FE-R6`, `WF-FE-R7` | A3, A8; M1 |
| `WF-FE-R8`, `WF-FE-R9`, `WF-FE-R10` | A2, A6; M2 |
| `WF-SE-R1`, `WF-SE-R2`, `WF-SE-R3` | A2, A9; M1 |
| `WF-SE-R4`, `WF-SE-R5` | A2, A9; M1 |
| `WF-SE-R6`, `WF-SE-R7`, `WF-SE-R8`, `WF-SE-R9`, `WF-SE-R10` | A2, A6; M1–M2 |
| `WF-UE-R1`, `WF-UE-R2`, `WF-UE-R3`, `WF-UE-R4` | A1, A4, A6; M1 |
| `WF-UE-R5`, `WF-UE-R6`, `WF-UE-R7` | A1, A4; M1 |
| `WF-UE-R8`, `WF-UE-R9`, `WF-UE-R10` | A4, A9; M1 |
| `WF-UE-R11`, `WF-UE-R12`, `WF-UE-R13` | A4, A7; M1, M5 |
| `WF-SP-R1`, `WF-SP-R2`, `WF-SP-R3`, `WF-SP-R4` | A2, A5, A8; M1 |
| `WF-SP-R5`, `WF-SP-R6`, `WF-SP-R7` | A3–A5; M1–M2 |
| `WF-SP-R8`, `WF-SP-R9`, `WF-SP-R10` | A5, A7; M3, M5 |
| `WF-SP-R11` | A5, A8; M1 |
| `WF-SP-R12` | A7 negative Console boundary; M5 verifies IDE-only/provenance wording. No PR 15 IDE feature is added. |
| `WF-SP-R13` | A5 asserts absence of copy/export/extra retention controls and API calls; M5 reviews scope. |
| `WF-SP-R14` | A5, A7 prohibited-language assertions; M5 |

### Degraded-Path Assignment

| Path | Focused automated evidence | Recorded manual evidence |
| --- | --- | --- |
| Trace not retained / no retained evidence | A2 component/E2E terminal summary and bounded-history assertion | M2 |
| Upstream trace expiry before acquisition | A6 artifact/browser domain test and representative E2E | M2 |
| Local artifact expiry during inspection | A6 precedence and explorer-reset tests | M2 |
| Invalid/malformed artifact or unsupported consumed semantic value | A1 corpus rejection, A6 presentation, A8 exact bounds | M2 |
| Gap/uncertainty/incomplete timing or usage | A3–A4 component/E2E and A6 domain facts | M1 |
| Browser replay gap / subscriber overflow | A2 live reducer/relay/E2E | M2 |
| Upstream interval reset | A2/A6 continuity reset with no cross-interval merge | M2 |
| Same-instance transient disconnect/reconnect | A6 separate E2E fixture; retained facts marked stale until refreshed | M2 |
| Application instance restart | A6 scope rotation/E2E clears prior application-derived state | M2 |
| Target scope change / stale deep link | A6 error precedence and browser-coordinate clearing | M2 |
| Later authentication rejection | A6 local-handle inspection remains available; new catalog/acquisition blocked | M2 |
| Core finalization failure | failing test 1 and A2 canonical two-field E2E | M2 |
| Active execution / provisional usage/path | A2/A4 explicit provisional and bounded labels | M1 |
| Registered YAML unavailable or exact name absent | A5 component/E2E; no local-file substitute | M1, M3 |
| Page boundary / deep hierarchy / large payload | A3/A5/A8 finite continuation and deliberate 64-KiB reads | M1 |
| Hostile content / oversized body | A7/A8 DOM and middleware precedence | M3 |
| Missing monetary value | A4 explicit no-calculation presentation | M5 |

## How to Run

From the repository root unless noted:

```powershell
.\mvnw.cmd -pl loomspan-spring-boot-starter test -Dtest=ConsoleTraceFixtureCorpusTest -DfailIfNoTests=false
.\mvnw.cmd -pl loomspan-spring-boot-starter test
.\mvnw.cmd -pl loomspan-sample -am test
.\mvnw.cmd verify
```

Regenerate the intentional current-release corpus once, then rerun generation and require no second diff:

```powershell
.\mvnw.cmd -pl loomspan-spring-boot-starter test -Dtest=ConsoleTraceFixtureCorpusTest -Dloomspan.console.fixtures.regenerate=true -DfailIfNoTests=false
git diff --exit-code -- loomspan-console-fixtures
```

From `loomspan-console`:

```powershell
go test ./...
go run ./internal/buildtool verify
npm --prefix web run test:e2e
go run ./internal/buildtool package --expected-version VERSION
```

Race detector on the documented Windows development environment:

```powershell
$env:PATH = "C:\msys64\mingw64\bin;" + $env:PATH
$env:CGO_ENABLED = "1"
go test -race ./...
```

CI must repeat clean Java/fixture, canonical verify, and Playwright checks. Native package and smoke checks run on Windows x86-64, Linux x86-64, and macOS arm64; do not use cross-compilation as the macOS runtime result. Package publication remains disabled until an explicitly approved prerelease validation.

## Manual Verification

1. Complete all four workflows using only the keyboard at desktop, 640px, and 320px widths and at 200% zoom. Verify shared frame/failure context, visible focus, local evidence-region scrolling, no focus/scroll theft, deliberate raw loading, and no semantic judgment labels.
2. Exercise retained-window loss, same-instance disconnect/reconnect, restart, auth rejection, trace expiry, local expiry, target rotation, invalid artifact, replay gap, and core-finalization failure. Record displayed facts and expected domain codes without treating recent activity as history.
3. Inspect hostile application content in live, failure, route, skill YAML/source path, record, and payload views under forced colors and reduced motion. Confirm it produces no active link/control, navigation, form, or request.
4. On each supported native target, verify `SHA256SUMS`, exact archive contents, `--version`, loopback startup/pairing, and sample-target connection with an isolated profile/workspace and without JVM, Node.js, database, web server, or shared target filesystem.
5. Review the Phase 2 architecture, workflow evidence, skill-authoring claims, release contents, and completion index together. Confirm there is no cost/cause/importance/correctness/action recommendation, source provenance claim, screen-reader pass claim, or completed row without platform/date/run evidence.

## Exit Criteria

- [x] Each first-red test fails for the documented pre-fix reason and passes after its owning implementation slice.
- [x] All `WF-FE-*`, `WF-SE-*`, `WF-UE-*`, and canonical `WF-SP-*` requirements map to passing automated evidence or an explicitly recorded manual check; no `WF-US-*` identifiers appear in completion evidence.
- [x] All listed degraded paths have an owning-layer automated test and at least one representative browser assertion or recorded manual check.
- [x] Java fixture regeneration is idempotent, committed fixture files remain LF, and the full Java-to-Go corpus plus exact release-string rejection pass.
- [x] Current writer, Go reader/calculator, browser adapter, React projector, fixtures, and debugging guidance agree on limits, relationships, ordering, failure visibility, unknown/incomplete facts, security, and redaction.
- [x] Absent quota metadata is handled only as current standalone/internal “limit comparison unavailable” semantics; no historical quota-less compatibility reader, fallback DTO field, duplicate workflow store, browser-side authoritative diagnostic calculation, or simultaneous old/new behavior remains.
- [x] Protected Application API, Supported SPI, configuration/manifest, and serialized boundaries pass; internal public signatures expose no new supported extension point or leaked internal quota type.
- [x] Host/Origin/session/tab/CSRF/raw-download precedence, hostile-content isolation, and exact/one-over bounds pass without unbounded work.
- [ ] Automated accessibility scans have no configured serious/critical violations, Playwright drives keyboard/focus/reconnect/reset/zoom/forced-colors/reduced-motion behavior, and the five manual checks are recorded.
- [x] Packaging is byte-deterministic on each native target, archives/checksums/smoke tests pass, and only the coordinated final release job can publish after all targets succeed (`Console Release` run 30735606164, 2026-08-01).
- [x] Updated skill-authoring claims are supported by the cited fixtures and focused tests and do not claim complete quota configuration or cross-version trace compatibility.
- [x] Canonical Maven, Go, buildtool, race-detector, and Playwright commands pass on the required environments with no generated fixture or frontend-asset diff.
