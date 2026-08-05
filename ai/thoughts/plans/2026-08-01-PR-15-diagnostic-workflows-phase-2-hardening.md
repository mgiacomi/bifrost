# PR 15 Diagnostic Workflows and Phase 2 Hardening Implementation Plan

## Overview

Complete the four approved Loomspan Console workflows over the shared live, artifact, and trace-analysis foundations delivered by PRs 10–14, close known lifecycle and presentation defects in their owning layers, and produce the security, accessibility, browser, packaging, and completion evidence required to finish Phase 2. The work preserves one live-execution experience and one hierarchy-first trace explorer; workflow-specific entry states coordinate existing facts rather than creating separate parsers, stores, or diagnostic engines.

This plan incorporates the following planning decisions:

- Snapshot the five configured execution quotas into optional `TRACE_STARTED.configuredLimits` metadata so Spring-created finalized and locally acquired traces remain self-contained for limit comparison. Standalone/internal construction paths may omit the object; when present, all five members are required integers in `0..2147483647`.
- Package Windows as ZIP and Linux/macOS as `tar.gz`; every archive contains the executable, MPL-2.0 license, and a short runtime README, and one `SHA256SUMS` covers all archives.
- Add pull-request GitHub Actions verification and version-tag native release jobs that publish a GitHub release after all target packages pass.
- Remove representative assistive-technology verification from the Phase 2 design as an explicit scope correction. Retain WCAG-oriented semantics, automated accessibility checks, keyboard operation, focus behavior, zoom, forced-colors, reduced-motion, and responsive verification.
- Use `@axe-core/playwright`, after verifying and pinning a compatible version, for representative automated scans that fail configured serious or critical findings.
- Use canonical unfamiliar-skill-path IDs `WF-SP-R1` through `WF-SP-R14`; `WF-US-*` is not an alias.
- Format supported usage proportions from the returned numerator and denominator with at most two decimal places. Zero limits have undefined proportions and absent limits are unavailable; neither produces a percentage.
- Use publishing tags of `v<root-POM-version>`, strip the leading `v` for comparison, reject publishing when the POM version is a `SNAPSHOT`, and keep manual workflow dispatch non-publishing by default.

Before implementation, create the dedicated test plan with `ai/commands/3_testing_plan.md`; it should assign every workflow/degraded-path requirement to a focused failing test or recorded manual check and retain the requirement IDs used below.

## Current State Analysis

PRs 10–14 already provide the application shell, operational views, bounded live activity, deliberate artifact acquisition, transport-neutral trace analysis, and hierarchy-first trace explorer (`loomspan-console/web/src/app/routes.tsx:14-32`, `loomspan-console/internal/browserapi/router.go:20-47`). Existing Playwright scenarios exercise terminal transition and the three finalized workflows, but several scenarios prove generic explorer behavior rather than the complete approved workflow (`loomspan-console/web/e2e/live-executions.spec.ts:248-356`, `loomspan-console/web/e2e/artifact-storage.spec.ts:353-420`).

Known gaps are concrete:

- The browser detects core finalization failure from `applicationTraceAvailability`, while Java emits `UNAVAILABLE` there and places `CORE_FINALIZATION_FAILED` in `applicationTraceUnavailableReason` (`loomspan-console/web/src/observability/ActiveExecutionDetail.tsx:53-58`, `loomspan-spring-boot-starter/src/main/java/com/lokiscale/loomspan/internal/runtime/observation/DefaultExecutionObservationHandle.java:176-187`).
- `TraceExplorer` has hierarchy, timeline, usage, and records views, but no coordinated failure-focused entry summary (`loomspan-console/web/src/observability/traceExplorerState.ts:1-17`, `loomspan-console/web/src/observability/TraceExplorer.tsx:301-315`).
- Go already indexes exact frame `skillNames`, outcomes, attempts, retries, validation statuses, and failure IDs, but `FrameSummary` and the browser frame DTO discard those relationships (`loomspan-console/internal/traceanalysis/frames.go:281-303`, `loomspan-console/internal/traceanalysis/dto.go:47-72`, `loomspan-console/internal/browserapi/trace_analysis.go:84-88`).
- Registered skill YAML is available on the Skills route, but selected trace frames cannot navigate to a matching registered skill (`loomspan-console/web/src/observability/SkillDetail.tsx:64-79`, `loomspan-console/web/src/api/contracts.ts:231-239`).
- Active execution responses carry the five configured quota limits, while finalized trace usage does not (`loomspan-console/web/src/api/contracts.ts:130-155`, `loomspan-console/web/src/observability/TraceUsage.tsx:7-17`).
- Application YAML and evidence are already rendered as inert React text, and Go has fixed body, page, range, search, line, and JSON-depth bounds. Coverage is not yet organized as the complete PR 15 authority-boundary and response-bound matrix (`loomspan-console/internal/traceanalysis/limits.go:8-48`).
- The canonical build verifies frontend, assets, and Go tests and builds only the host executable. It does not run Playwright, create release packages/checksums, or provide repository CI (`loomspan-console/internal/buildtool/pipeline.go:36-56`, `loomspan-console/internal/buildtool/runner.go:31-64`).
- The repository declares MPL 2.0 in `pom.xml` but has no committed license text, package-specific runtime README, release archive layout, or Phase 2 completion-evidence index (`pom.xml:16-22`).

## Desired End State

After this plan is complete:

1. `WF-SLOW-EXECUTION`, `WF-FAILED-EXECUTION`, `WF-EXPENSIVE-EXECUTION`, and `WF-UNFAMILIAR-SKILL-PATH` each have named executable browser scenarios covering their primary transition and representative degraded paths.
2. A watched terminal execution remains in place, accurately distinguishes outcome from observation/finalization and trace availability, and deliberately enters the shared explorer when a finalized artifact is available.
3. Failed traces enter the same explorer in a failure-focused state that relates the terminal failure, frame/path, nearby canonical evidence, attempts/retries/validation, duration/usage, gaps/uncertainties, and stable identifiers without asserting cause.
4. Finalized usage exposes the run-start configured limits and deterministic arithmetic proportions, preserves unavailable/incomplete distinctions, and offers usage-ordered frame navigation without changing hierarchy or labeling usage excessive.
5. Selected frames expose exact recorded skill names and link only matching current-scope registered names to inert YAML. `sourcePath` remains non-navigable text and is never mapped to a developer workspace.
6. Every settled unavailable, expired, malformed, gap, restart, scope-change, authentication, finalization, and incomplete-evidence path has owning-layer automated coverage and honest browser presentation.
7. Untrusted application content remains inert; authority checks retain their established order; request, parse, query, search, and range bounds are executable and documented.
8. Browser verification covers keyboard interaction, focus/navigation, automated accessibility checks, reconnect/reset, forced colors, reduced motion, 200% zoom, and representative narrow/desktop layouts. The authoritative Phase 2 design no longer requires manual assistive-technology verification.
9. GitHub Actions runs Java/Go contract checks, canonical Console verification, and Playwright on pull requests. Version tags build and smoke-test native packages on Windows x86-64, Linux x86-64, and macOS arm64, then publish native archives and `SHA256SUMS` as one coordinated GitHub release.
10. A Phase 2 completion-evidence index maps the authoritative criteria and workflow requirements to automated and manual evidence, with no unrecorded completion claims.

### Key Discoveries

- The approved workflow set explicitly requires coordinated perspectives over one explorer, not four product areas (`ai/thoughts/phases/LOOMSPAN_console_workflows.md:51-76`).
- Configured-limit comparison is a settled arithmetic presentation, while cost judgment and provider pricing remain prohibited (`ai/thoughts/phases/LOOMSPAN_console_workflows.md:352-399`, `:424-495`).
- `sourcePath` is display-only; registered name is the valid coordination key for YAML (`ai/thoughts/phases/LOOMSPAN_console_workflows.md:509-654`).
- Phase 3 PR 18 will adapt the same artifact and trace-query services, so limits and workflow relationships must be transport-neutral rather than browser calculations (`ai/thoughts/tickets/loomspan-console-pr-18-mcp-trace-inspection.md:9-38`).
- PR 19 reuses the same four workflow IDs and uncertainty rules; PR 15's fixtures and completion evidence become the canonical browser-side scenario catalog (`ai/thoughts/tickets/loomspan-console-pr-19-debugging-skill.md:13-24`).
- GitHub Actions supports native OS matrix jobs and job-level least-privilege permissions. Pull-request jobs must use `pull_request`, not a privileged `pull_request_target` checkout of untrusted code.

## What We're NOT Doing

- No separate workflow pages, analysis engine, artifact copy, cache, parser, calculation, or durable history.
- No automatic acquisition, automatic navigation on completion, or automatic loading of raw records/payloads.
- No root-cause, importance, correctness, excess, necessity, expected-cost, monetary-cost, or actionability judgments.
- No provider pricing, historical baselines, trend analytics, cross-version trace support, or trace migration.
- No mapping of application `sourcePath` values to local files, IDE navigation, or provenance claims.
- No installer, package-manager integration, updater, container, remote listener, database, service worker, separate browser deployment, or npm package.
- No new supported target beyond Windows x86-64, Linux x86-64, and macOS arm64.
- No manual screen-reader/assistive-technology acceptance pass. The design document will be amended so this is an explicit scope decision, not missing evidence.
- No MCP, Agent Skill, structured logging, or Phase 3 adapter implementation. PRs 16–19 consume the hardened shared seams later.

## Skill-Authoring Documentation Impact

**Impact**: Affected

- **Rationale**: Skill authors diagnosing one run need to know that Console compares finalized recorded usage against the execution's run-start quota snapshot, that absent limits produce no percentage, and that this arithmetic is not a cost or correctness judgment. The existing trace guidance explains usage reconciliation and finalization but not configured-limit snapshots or Console's registered-name/YAML coordination.
- **Documents to update**: `ai/skill-authoring/traces-and-debugging.md` and the corresponding notes in `ai/skill-authoring/README.md`.
- **Supporting evidence**: `ConsoleTraceFixtureCorpusTest`, generated valid/invalid trace fixtures, Go fixture-corpus and summary tests, `TraceUsage` component tests, and the named `WF-EXPENSIVE-EXECUTION`/`WF-UNFAMILIAR-SKILL-PATH` Playwright scenarios.
- **Coverage table update**: Required. Expand the “Traces and debugging” note to include run-start configured-limit comparison and registered-name trace/YAML navigation. Keep “Execution limits and quotas” at Foundational because PR 15 does not document the complete property/configuration reference.
- **LLM-first usability**: Keep the diagnostic procedure compact: identify finalized terminal usage, inspect limit presence, compute only displayed arithmetic proportions, preserve missing/incomplete facts, and navigate exact registered names. Link to future complete quota configuration guidance instead of duplicating undocumented property syntax.

## Contract and Compatibility Impact

| Surface | Classification and supporting evidence | Planned compatibility treatment |
| --- | --- | --- |
| Application API | No application-facing Java API changes. Existing quota properties and runtime entry points retain their names and behavior. | Preserve. |
| Supported SPI | No supported SPI changes. Go service interfaces and Java trace-handle factories involved here are internal seams. | Preserve supported SPIs; update internal collaborators atomically. |
| Configuration and manifest contracts | Existing five `loomspan.session.quotas` values are read as the run-start snapshot; YAML skill syntax, validation, defaults, and Console YAML schema do not change. | Preserve configuration and manifest behavior; document diagnostic interpretation only. |
| Persisted or serialized contracts | Java application REST/SSE/problem fixtures remain coordinated serialized boundaries. Go browser JSON gains additive current-version fields for limits, frame relationships, and failure focus. These have only in-repository consumers. | Update Java fixtures, Go decoders/adapters, React contracts, and E2E fixtures atomically in the same release. No version bridge. |
| Ephemeral diagnostic formats | `TRACE_STARTED` gains an optional bounded `configuredLimits` metadata object. Spring-created traces emit it. Standalone/internal construction paths may omit it. When present, all five values are required integers in `0..2147483647`. Current Java writer, Java fixture corpus, Go parser/indexes, and debugging docs consume it. | Intentional current-run format update; regenerate valid fixtures and add incomplete-object/overflow/type-invalid cases. Object absence is a current semantic meaning “limit comparison unavailable,” not a legacy reader or cross-version migration. |
| Internal or accidentally exposed implementation | Trace index rows, neutral DTOs, browser query state, buildtool packaging commands, generated archives, and GitHub workflow composition change. | One coherent implementation; no duplicate DTOs, fallback fields, aliases, or compatibility shims. |

- **Evidence of supported contracts**: `LoomspanProperties.Session.Quotas`, approved Phase 2/workflow documents, application fixture corpora, Go application client, browser API/React consumers, and current-run trace corpus.
- **Intended breaks**: Spring-produced current-checkout trace fixtures are regenerated with the run-start quota snapshot. A present but incomplete or invalid object is rejected; complete object absence remains valid only to represent a current standalone/internal producer with unavailable limit comparison. Because traces are current-run ephemeral diagnostics, this is not an old-trace compatibility reader. The Phase 2 manual accessibility evidence requirement is intentionally narrowed by an explicit design-document update.
- **In-repository consumers to update**: Java trace creation/tests and fixture corpus; `loomspan-console-fixtures`; Go parser/manifest/DTO/query/browser tests; React API contracts/components/tests; Playwright fixture server/scenarios; skill-authoring guidance; Phase 2 design/completion evidence.
- **Public-surface delta**: One additive constructor/parameter path may be needed on the technically public but explicitly internal `com.lokiscale.loomspan.internal.core.LoomspanSessionRunner` so Spring wiring can provide an immutable quota snapshot; existing standalone constructors remain and omit limits. Go treats object absence as current “limit comparison unavailable” semantics, while rejecting any present incomplete object. No supported application API, SPI, Spring bean, or extension point changes. Record the exact constructor delta in the implementation review rather than treating its public modifier as a compatibility promise.
- **Shim decision**: **No shim.** No protected cross-version trace contract exists, and all current-checkout producers and consumers can change atomically.
- **Java-to-Go boundary coordination**: **Required.** The consumed NDJSON trace boundary changes. Java writer and fixture generation, Go parsing/indexing, browser DTOs, React contracts, fixtures, tests, and authoring guidance must ship together.

## Implementation Approach

Implement from authoritative evidence outward:

1. Correct and enrich the current-run Java/Go contracts first, including fixtures that fail against the old behavior.
2. Expose existing transport-neutral relationships without browser recomputation.
3. Build failure, usage, and skill-path entry states as coordinated presentation over one `TraceExplorer` and current-scope URL state.
4. Audit degraded/security/bound paths in their owning services before extending Playwright scenarios.
5. Add deterministic native packaging and least-privilege GitHub Actions only after the canonical local verification/build commands remain green.
6. Record completion evidence last, based only on commands and manual checks actually executed.

## Phase 1: Correct Terminal and Trace Evidence Contracts

### Overview

Fix the finalization mismatch and make run-start limits plus already-indexed frame relationships available through the shared trace-analysis layer.

### Changes Required

#### 1. Core-finalization browser interpretation
**Files**:
- `loomspan-console/web/src/observability/ActiveExecutionDetail.tsx`
- `loomspan-console/web/src/observability/ActiveExecutionDetail.test.tsx`
- `loomspan-console/web/e2e/live-executions.spec.ts`

**Changes**:
- Detect `EXECUTION_OBSERVATION_ENDED` with `applicationTraceUnavailableReason === "CORE_FINALIZATION_FAILED"`; retain `applicationTraceAvailability === "UNAVAILABLE"` as the separate availability fact.
- Update component/E2E fixture data to match the committed Java SSE fixture.
- Assert no execution outcome is invented when absent and no Inspect trace action is rendered.

#### 2. Run-start quota snapshot in canonical traces
**Files**:
- `loomspan-spring-boot-starter/src/main/java/com/lokiscale/loomspan/internal/runtime/trace/DefaultExecutionTraceHandle.java`
- `loomspan-spring-boot-starter/src/main/java/com/lokiscale/loomspan/internal/core/LoomspanSessionRunner.java`
- `loomspan-spring-boot-starter/src/main/java/com/lokiscale/loomspan/internal/core/InternalExecutionTraceHandleFactory.java`
- relevant Spring runtime wiring and focused Java tests found through constructor/call-site tracing
- `loomspan-spring-boot-starter/src/test/java/com/lokiscale/loomspan/internal/runtime/trace/ConsoleTraceFixtureCorpusTest.java`
- `loomspan-console-fixtures/traces/*.ndjson`
- `loomspan-console-fixtures/expected/*.json`

**Changes**:
- Capture an immutable value object containing max skill invocations, tool invocations, linter retries, model calls, and usage units when the execution trace is created.
- Write it as optional bounded `configuredLimits` metadata on `TRACE_STARTED`; Spring-created traces always supply it, while existing standalone/internal constructor paths may omit it. Do not read mutable/current application configuration during later catalog or browser queries.
- Require all five members when the object is present, each as an integer in `0..2147483647`. Extend fixture cases to include a non-default snapshot, a valid absent-object standalone case, and malformed missing-member/unknown-member/type/range variants. Preserve LF fixture output.
- Regenerate the corpus atomically; do not add a legacy reader.

#### 3. Go parser, manifest, and neutral DTOs
**Files**:
- `loomspan-console/internal/traceanalysis/parser.go` and validation collaborators
- `loomspan-console/internal/traceanalysis/model.go`
- `loomspan-console/internal/traceanalysis/processor.go`
- `loomspan-console/internal/traceanalysis/dto.go`
- `loomspan-console/internal/traceanalysis/query_facts.go`
- `loomspan-console/internal/traceanalysis/query_frames.go`
- focused parser, calculation, service, continuation, and fixture-corpus tests

**Changes**:
- Accept complete object absence as “limit comparison unavailable.” When the object is present, validate and retain all five values without accepting floats, negatives, values above `2147483647`, missing members, or unknown semantic substitutions.
- Add the immutable snapshot to `TraceSummary` (or the summary-owned neutral value type) so browser and future MCP adapters consume the same fact.
- Preserve existing frame `SkillNames`, outcomes, attempt IDs, retry sequence IDs, validation statuses, and failure IDs when converting `frameResult` to `FrameSummary`.
- Enrich failure index/summary rows with canonical sequence, timestamp, record type, frame ID, route, and directly recorded attempt/retry/validation identifiers needed for navigation. Do not infer relationships from adjacency or time proximity.
- Keep index and response limits unchanged unless a focused boundary test demonstrates an owning-layer defect.

#### 4. Browser adapter and React contracts
**Files**:
- `loomspan-console/internal/browserapi/trace_analysis.go`
- `loomspan-console/internal/browserapi/trace_analysis_test.go`
- `loomspan-console/web/src/api/contracts.ts`
- `loomspan-console/web/src/api/client.ts`

**Changes**:
- Map configured limits and enriched frame/failure relationships directly from transport-neutral DTOs.
- Keep responses target-scoped, bounded, additive, and free of artifact handles/internal paths.
- Update contract tests to prove exact JSON names, null/absence behavior, and no adapter-side calculation.

### Success Criteria

#### Automated Verification
- [x] Java focused tests fail before and pass after the quota snapshot and finalization fixture corrections.
- [x] `ConsoleTraceFixtureCorpusTest` regeneration is idempotent and `git diff -- loomspan-console-fixtures` is empty on the second run.
- [x] Go fixture-corpus tests reject malformed quota metadata as `INVALID_ARTIFACT` and return exact valid values.
- [x] Browser adapter tests prove limits and recorded frame/failure links are mapped without recomputation.
- [x] `ActiveExecutionDetail` tests use the canonical two-field finalization representation.

#### Manual Verification
- [ ] A finalization-failed live execution presents incomplete observation and unavailable trace separately, with no invented outcome or trace action.
- [ ] A locally acquired finalized trace retains its run-start limits after upstream authentication becomes unavailable.

---

## Phase 2: Complete the Coordinated Diagnostic Workflows

### Overview

Add workflow-specific entry states and navigation within the existing live detail and shared hierarchy-first trace explorer.

### Changes Required

#### 1. Failure-focused trace entry (`WF-FE-R1`–`WF-FE-R10`)
**Files**:
- `loomspan-console/web/src/observability/ActiveExecutionDetail.tsx`
- `loomspan-console/web/src/observability/TraceDetail.tsx`
- `loomspan-console/web/src/observability/TraceExplorer.tsx`
- `loomspan-console/web/src/observability/traceExplorerState.ts`
- new focused presentation component beside existing `TraceUsage`, `TraceRecords`, and `TraceEvidenceDetail` components only if separation is needed
- corresponding component tests

**Changes**:
- Carry an explicit current-scope failure focus through deliberate Inspect trace/acquire/open navigation. Use existing `failureId` and selected frame/record coordinates rather than adding a new top-level route or independent store.
- For failed/aborted traces with no explicit incoming focus, select the recorded `terminalFailureId`; never choose the first nonterminal error as a substitute.
- Render a concise failure-focused summary above the coordinated tabs: outcome, terminal failure identity, exact attributed frame/route/skill names, direct attempts/retries/validation facts, frame timing/usage, and explicit gaps/uncertainties.
- Provide links into hierarchy, timeline, usage, records, and explicit raw/payload detail while preserving selected frame/failure context.
- If the terminal failure or related page is beyond the first bounded page, continue through existing cursors until found or exhausted; do not fetch every raw payload.
- Preserve in-place terminal selection and never auto-acquire or auto-navigate.

#### 2. Usage attribution and configured-limit comparison (`WF-UE-R1`–`WF-UE-R13`)
**Files**:
- `loomspan-console/web/src/observability/TraceUsage.tsx`
- `loomspan-console/web/src/observability/TraceExplorer.tsx`
- focused usage component tests

**Changes**:
- Add a trace usage summary with terminal totals, configured limits, and arithmetic consumed proportions for the matching supported counters. Display numerator and denominator alongside a percentage formatted with at most two decimal places. A zero denominator is shown as an undefined proportion and an absent limit as unavailable; neither renders a percentage. Tests assert numeric/accessibility semantics rather than locale-specific punctuation.
- Keep prompt/completion/total, attributed/unattributed/unframed, completeness, and duration facts distinct.
- Add a usage-ordered frame table backed by `getTraceFrames(..., "USAGE_DESC")`; show direct, descendant, and inclusive values separately and retain each row's hierarchy path/frame ID.
- Selecting a contributor updates the shared frame coordinate and makes hierarchy/timeline/records navigation available without labeling the contributor important, excessive, causal, or unnecessary.
- Present attempt/retry/validation usage relationships from shared facts; never sum overlapping parent/child inclusive totals.
- State that monetary cost is not calculated when no canonical monetary fact exists.

#### 3. Registered YAML coordination (`WF-SP-R1`–`WF-SP-R14`)
**Files**:
- `loomspan-console/web/src/observability/TraceExplorer.tsx`
- `loomspan-console/web/src/observability/TraceHierarchy.tsx`
- `loomspan-console/web/src/observability/SkillDetail.tsx`
- API client use of the existing skill catalog/detail routes
- focused component tests

**Changes**:
- Display all exact recorded skill names associated with the selected frame; do not guess a name from route text.
- Match those names against the current-scope registered skill catalog. Render a Skills detail link only for an exact registered-name match; otherwise render the recorded name plus an explicit “not in current registered catalog” fact.
- Preserve repeated invocations as distinct frame IDs even when skill name and route match.
- Keep application YAML inert in `<pre>`, retain `sourcePath` as non-link code text, and preserve target-scope reset semantics.

#### 4. Skill-authoring guidance
**Files**:
- `ai/skill-authoring/traces-and-debugging.md`
- `ai/skill-authoring/README.md`

**Changes**:
- Add compact run-start limit interpretation and exact registered-name/YAML navigation guidance backed by the new fixtures and tests.
- State absence, provisional/finalized distinctions, arithmetic-only comparison, and prohibited cost/cause judgments.
- Update coverage notes without claiming a complete execution-limit configuration reference.

### Success Criteria

#### Automated Verification
- [x] Component tests prove terminal-failure selection, deep continuation, context preservation, and missing/incomplete facts.
- [x] Usage tests cover nonzero, zero, absent, incomplete, unattributed, and usage-sorted cases without double counting.
- [x] Skill-path tests cover exact match, absent current registration, repeated frames, inert YAML, and non-link `sourcePath`.
- [x] URL-state tests reject malformed/stale coordinates and clear workflow focus on target change/artifact expiry.
- [x] Updated skill-authoring claims are supported by cited fixtures and focused tests.

#### Manual Verification
- [ ] The failed, usage, and unfamiliar-skill workflows move among shared explorer views without losing applicable frame/failure context.
- [ ] No presentation describes cause, importance, excess, correctness, necessity, or recommended action.
- [ ] Opening workflow summaries does not automatically load raw payload content.

---

## Phase 3: Degraded Paths, Security, Bounds, and Browser Hardening

### Overview

Turn the settled lifecycle, authority, content, and resource rules into a traceable test matrix and fix any discovered defect in its owning layer.

### Changes Required

#### 1. Degraded-path ownership matrix
**Files**:
- focused tests in `loomspan-console/internal/target`, `internal/live`, `internal/artifact`, `internal/traceanalysis`, and `internal/browserapi`
- React component/provider tests under `loomspan-console/web/src`
- `loomspan-console/web/e2e/*.spec.ts`

**Changes**:
- Map each workflow/requirement ID to unavailable live monitoring, trace not retained, upstream expiry, local artifact expiry, invalid/malformed artifact, gap/uncertainty, replay/subscriber overflow, application restart, target scope change, authentication rejection, core finalization failure, and incomplete timing/usage.
- Assert acquisition-time authorization: complete local handles remain inspectable after later upstream auth rejection, while catalog/new acquisition remains blocked.
- Assert stale scope and expired handle error precedence and browser state clearing.
- Add missing reconnect coverage for a transient disconnect within one instance separately from application instance restart.
- Keep current error codes and lifecycle facts separate; do not add aggregate health labels or browser workarounds.

#### 2. Application-content and authority-boundary tests
**Files**:
- `loomspan-console/internal/browserapi/*_test.go`
- `loomspan-console/web/src/observability/TraceViews.test.tsx`
- `loomspan-console/web/e2e/artifact-storage.spec.ts`
- skill/live/trace fixtures used by those tests

**Changes**:
- Exercise hostile markup, script-like text, links, control characters, oversized labels, and runtime instructions in skill YAML, summaries, routes, failure data, raw records, and reconstructed payload ranges.
- Assert content remains text, cannot create executable/link/form elements, cannot influence routing or API calls, and cannot cross into authority-bearing headers/URLs.
- Preserve middleware order: Host/authority before authentication or body processing; Origin/session/tab/CSRF according to operation sensitivity; raw download through its separate attachment policy.
- Add a production-source guard test that rejects `dangerouslySetInnerHTML` in application-content presentation unless a future reviewed exception is explicit.

#### 3. Response and representative trace bounds
**Files**:
- `loomspan-console/internal/traceanalysis/limits.go` and tests
- `loomspan-console/internal/browserapi/trace_analysis_test.go`
- fixture corpus and E2E synthetic trace server

**Changes**:
- Commit a minimum matrix covering: one primary fixture for each approved workflow; repeated frames; the existing 20,000-deep hierarchy stress case; a page-boundary trace exceeding the 100-item browser page; a deterministically synthesized multi-megabyte payload read in 64-KiB browser ranges; exact maximum and one-over line/body/page/range/search/depth limits; incomplete frame/usage traces; and malformed structural-limit cases.
- Verify every valid frame/record remains reachable through finite continuations and no “representative” fixture introduces an unsupported semantic cap.
- Record fixture size/purpose/requirement IDs in the fixture README rather than embedding large duplicate payloads where deterministic synthesis is sufficient.

#### 4. Accessibility-critical browser behavior
**Files**:
- existing shell, live, trace, hierarchy, and dialog component tests
- `loomspan-console/web/e2e/*.spec.ts`
- `loomspan-console/web/package.json` and lockfile for a compatible pinned `@axe-core/playwright` version verified against the current Playwright package

**Changes**:
- Drive tree Home/End/Arrow navigation and tab Arrow/Home/End behavior in Playwright, not only component tests.
- Verify route heading focus, dialog focus trap/return, no live-update focus stealing, visible/unobscured focus, semantic landmarks/headings/tables/tree/tabs, and restrained status announcements.
- Run `@axe-core/playwright` accessibility scans on representative Overview, Live Detail, Trace Detail/failure, Usage, Records, Skills, pairing, and error states; configured serious or critical findings fail the test.
- Retain narrow viewport, desktop viewport, 200% zoom, forced-colors, reduced-motion, and no whole-page horizontal-scroll checks. Data evidence regions may remain labeled internal scrollers.
- Do not add manual assistive-technology verification.

### Success Criteria

#### Automated Verification
- [x] Every listed degraded path has an owning-layer test and at least one representative browser presentation assertion.
- [x] Host/origin/session/tab/CSRF/raw-download precedence tests remain green with hostile content and oversized bodies.
- [x] Exact-limit and one-over tests return the expected domain code without unbounded allocation/work.
- [x] Automated accessibility scans report no configured serious/critical violations in representative routes.
- [x] Playwright drives tree, tabs, dialogs, reconnect, restart, scope reset, zoom, forced-colors, and reduced-motion behavior.

#### Manual Verification
- [ ] Complete all four workflows by keyboard at desktop, 640px, and 320px widths and at 200% zoom.
- [ ] Confirm focus is visible, updates do not steal it, and evidence tables/raw regions scroll locally rather than forcing application-wide horizontal scrolling.
- [ ] Review hostile-content scenarios and confirm no active link/control appears from application-provided text.

---

## Phase 4: Deterministic Native Packaging and GitHub Release CI

### Overview

Extend the existing canonical buildtool into deterministic native release packaging and add least-privilege GitHub Actions verification/publication.

### Changes Required

#### 1. License and short runtime package document
**Files**:
- `LICENSE` (canonical MPL 2.0 text)
- `loomspan-console/release/README.md` (new short runtime-only document)
- `loomspan-console/README.md`

**Changes**:
- Commit the exact MPL 2.0 license text declared by the root POM.
- Add a concise package README covering runtime prerequisites (none beyond OS), `--version`, startup/pairing, config/workspace locations, target key/trust, cleanup, supported target, and links/reference back to the full repository README. Do not copy build-time instructions into the package.
- Document archive names and checksum verification commands for PowerShell and POSIX shells.

#### 2. Buildtool package mode
**Files**:
- `loomspan-console/internal/buildtool/main.go`
- `pipeline.go`, `runner.go`, `paths.go`, and new focused packaging implementation/tests under the same package

**Changes**:
- Add a native-only `package` mode that runs the full clean build, verifies `--expected-version`, identifies the actual `GOOS/GOARCH`, rejects unsupported release targets, and never accepts arbitrary output content.
- Produce exact names:
  - `loomspan-console-VERSION-windows-x86_64.zip`
  - `loomspan-console-VERSION-linux-x86_64.tar.gz`
  - `loomspan-console-VERSION-macos-arm64.tar.gz`
- Each archive contains one top-level directory with only the native executable (`.exe` on Windows), `LICENSE`, and `README.md`.
- Normalize archive paths, permissions, ordering, timestamps, gzip headers, and ZIP/TAR metadata for deterministic output. Reject symlinks and unexpected files.
- Generate a per-run archive SHA-256 sidecar for CI collection; the release aggregation job writes sorted `SHA256SUMS` entries for all three archives and verifies them before publication.
- Add archive-content, traversal, deterministic-repeat, target-name, version-mismatch, and checksum tests.

#### 3. Native runtime smoke verification
**Files**:
- buildtool tests/commands and GitHub workflow steps

**Changes**:
- On each native runner, extract the archive into a fresh temporary directory and run `loomspan-console --version`.
- Start the packaged executable with an isolated temporary profile/workspace and `--no-open-browser`, verify loopback startup/static bootstrap without Java, Node, database, or application filesystem access, then shut it down cleanly.
- On macOS, build/run arm64 on an arm64 hosted runner; do not treat cross-compilation alone as representative validation.

#### 4. Pull-request CI
**File**: `.github/workflows/console-ci.yml`

**Changes**:
- Trigger on `pull_request` and relevant `push` branches with top-level `contents: read` and no write permission.
- Pin every third-party/official action to a reviewed full commit SHA with a release-tag comment.
- Install exact Java, Go 1.26.5, Node 24.18.0, and npm 12.0.2 toolchains; use locked Maven/npm inputs and safe caches only.
- Run Java fixture/adapter tests, `go run ./internal/buildtool verify`, Playwright browser installation, and `npm run test:e2e` in the same source state.
- Upload failing Playwright traces/test results only; assign finite timeouts and cancellation/concurrency behavior.
- Never use `pull_request_target` to execute contributor code.

#### 5. Version-tag release workflow
**File**: `.github/workflows/console-release.yml`

**Changes**:
- Trigger publishing only on `v*` tags and allow `workflow_dispatch` as a non-publishing validation path by default.
- Give build jobs `contents: read`; give only the final publication job `contents: write`.
- Strip the leading `v` and assert the remaining tag version exactly equals the root POM product version before building. Reject publication when the POM version ends in `-SNAPSHOT`.
- Use a three-entry native matrix: `windows-latest`/x86-64, `ubuntu-latest`/x86-64, and a GitHub-hosted arm64 macOS runner/arm64 whose label is verified against the available runner inventory during implementation. Each job runs package mode and native smoke verification, then uploads only its archive and sidecar.
- In the final Linux aggregation job, download artifacts into separate paths, reject duplicate/unexpected names, verify sidecars, create sorted `SHA256SUMS`, and publish all four files to one GitHub release using the tag. Mark releases prerelease when the version is a prerelease/SNAPSHOT; do not publish if any matrix or verification job fails.
- Preserve Java/Console coordinated release identity; the workflow must not invent a Console-only version.

### Success Criteria

#### Automated Verification
- [x] Packaging twice from identical inputs produces byte-identical archives on each native target.
- [x] Archive tests prove exact names, contents, permissions, safe paths, and checksums.
- [x] Each native runner extracts and executes `--version` and passes the isolated runtime smoke test (`Console Release` run 30735606164, 2026-08-01).
- [x] Pull-request CI runs canonical Console verification plus Playwright with read-only permissions (`Console CI` run 30729350889, 2026-08-01).
- [x] Tag validation fails closed on tag/POM mismatch, missing target, unexpected artifact, or checksum mismatch.
- [x] The final publication job is the only job with `contents: write`.

#### Manual Verification
- [ ] Download a test/prerelease package on each supported target, verify `SHA256SUMS`, read the bundled runtime README, pair a browser, and connect to a sample target.
- [ ] Confirm runtime operation requires no JVM, Node.js, database, separate web server, or shared target filesystem.
- [ ] Inspect release contents and confirm no workspace, credentials, generated test output, source maps, deprecated CLI, or extra binaries are present.

---

## Phase 5: Phase 2 Design and Completion Evidence

### Overview

Align authoritative design wording with the approved scope correction and record verifiable Phase 2 completion without creating a second requirements set.

### Changes Required

#### 1. Accessibility design correction
**File**: `ai/thoughts/phases/LOOMSPAN_console_phase_2_ui_console.md`

**Changes**:
- Remove representative assistive-technology verification from the accessibility acceptance wording.
- Retain WCAG 2.2 AA as the design target and retain keyboard availability, focus, semantics, color independence, reduced motion, forced colors, automated checks, zoom, and responsive requirements.
- State explicitly that Phase 2 does not require a manual screen-reader/assistive-technology acceptance pass so review does not treat it as deferred evidence.

#### 2. Phase 2 completion-evidence index
**File**: `ai/thoughts/phases/LOOMSPAN_console_phase_2_completion_evidence.md` (new)

**Changes**:
- Follow the Phase 1 evidence-index pattern: reference authoritative Phase 2/workflow criteria rather than restating them.
- Map PRs 07–15, all four workflow IDs, security/authority, lifecycle/degraded behavior, accessibility-critical browser behavior, response bounds, and release targets to exact tests, fixtures, workflow run links/identifiers, and manual records.
- Record status only after the named command/workflow passes on the named platform/date. Keep unavailable/manual evidence visibly pending rather than claiming completion.
- Include the representative trace-size/target matrix and release archive/checksum evidence.

#### 3. Runtime/release and fixture documentation
**Files**:
- `loomspan-console/README.md`
- `loomspan-console-fixtures/README.md`
- relevant roadmap/ticket links only where implementation paths now exist

**Changes**:
- Document CI, E2E, package, checksum, and native smoke commands.
- Describe representative fixtures and generated large-payload cases by purpose, bound, and workflow/requirement ID.
- Preserve the distinction between executable current behavior and future MCP/Agent Skill work.

### Success Criteria

#### Automated Verification
- [x] Documentation paths and commands are checked by buildtool/project declaration tests where practical.
- [x] The completion index cites passing executable evidence and contains no unresolved placeholder questions.
- [x] Full repository verification produces no fixture or generated-asset diff.

#### Manual Verification
- [ ] Review Phase 2 architecture invariants, four workflow evidence, degraded paths, security, accessibility-critical checks, bounds, and all three packages together.
- [ ] Confirm the assistive-technology scope correction is explicit and no completion row claims a screen-reader pass.
- [ ] Confirm future PR 16–19 references remain accurate: shared services and workflow IDs are reusable without browser-only calculations.

## Testing Strategy

Create a separate PR 15 testing plan with `3_testing_plan.md`. At a high level:

### Unit and Component Tests

- Java: immutable quota snapshot creation, canonical trace emission, finalization field semantics, fixture generation.
- Go: quota parsing/validation, summary mapping, failure/frame relationship retention, pagination/error precedence, archive determinism and safety.
- React: terminal transition, failure focus, arithmetic proportions, usage ordering, exact skill matching, inert content, URL/scope cleanup, tree/tab/dialog keyboard behavior.

### Integration and End-to-End Tests

- Java-to-Go fixture corpus and browser contract fixtures.
- Named Playwright scenarios for all four workflows and representative unavailable/expired/malformed/gap/restart/scope/auth/finalization/incomplete paths.
- Browser authority/content tests and response-bound continuation tests.
- Native package build, extraction, checksum, `--version`, loopback startup, and release aggregation on all supported targets.

### Canonical Commands

From the repository root or indicated module:

```powershell
.\mvnw.cmd -pl loomspan-spring-boot-starter test
.\mvnw.cmd -pl loomspan-sample -am test
.\mvnw.cmd verify
```

```powershell
cd loomspan-console
npm --prefix web run test:e2e
go test ./...
go run ./internal/buildtool verify
go run ./internal/buildtool package --expected-version VERSION
```

Run the documented race-detector command from `loomspan-console/AGENTS.md`. Run fixture regeneration twice and require no second diff. CI additionally executes the same checks on clean hosted runners.

### Manual Testing Steps

1. Complete each workflow using only the browser and keyboard, including deliberate acquisition and cross-view context preservation.
2. Exercise the target disconnect/reconnect, application restart, auth rejection, trace expiry, local expiry, and target rotation paths.
3. Inspect representative routes at desktop, 640px, 320px, and 200% zoom with forced colors and reduced motion.
4. Verify native package checksums, exact archive contents, `--version`, startup/pairing, sample target connection, and absence of runtime toolchain/database/shared-filesystem requirements.
5. Review the completion-evidence index against actual command and GitHub workflow results.

## Performance Considerations

- Keep trace parsing single-pass and bounded; add quota extraction to existing start-record validation rather than a second artifact scan.
- Preserve lazy usage/records/raw loading. Failure focus may fetch bounded fact pages needed to resolve the terminal failure, but must not fetch payload bytes automatically.
- Use existing continuations for deep frames/failures and the existing `USAGE_DESC` index for contributor ordering; do not sort the full corpus in React.
- Keep Playwright and release jobs finite with explicit process/test timeouts. Parallelize independent native package jobs, then serialize checksum aggregation/publication.
- Deterministic archives may disable variable metadata but need not sacrifice runtime executable compression appropriate to ZIP/gzip.

## Migration Notes

- No user data or durable trace migration is provided. Current-checkout Java writers and Go readers change atomically, and old current-run trace fixtures are regenerated.
- Existing Console profile/config/workspace schemas remain unchanged.
- Existing acquired artifacts from a pre-PR-15 process are not adopted after restart under the current disposable-workspace lifecycle.
- Browser URL coordinates remain process/current-scope conveniences and are cleared when invalid, expired, or stale.
- Release automation must first be validated without publication, then with an explicitly approved prerelease tag; ordinary implementation verification must not create a real release.

## References

- Ticket: `ai/thoughts/tickets/loomspan-console-pr-15-diagnostic-workflows.md`
- Research: `ai/thoughts/research/2026-08-01-diagnostic-workflows-phase-2-hardening.md`
- Roadmap: `ai/thoughts/phases/2026-07-23-loomspan-console-implementation-roadmap.md:108-129`
- Phase 2 design: `ai/thoughts/phases/LOOMSPAN_console_phase_2_ui_console.md`
- Approved workflows: `ai/thoughts/phases/LOOMSPAN_console_workflows.md`
- Phase 1 completion evidence pattern: `ai/thoughts/phases/LOOMSPAN_console_phase_1_completion_evidence.md`
- Future MCP trace consumer: `ai/thoughts/tickets/loomspan-console-pr-18-mcp-trace-inspection.md`
- Future workflow/skill consumer: `ai/thoughts/tickets/loomspan-console-pr-19-debugging-skill.md`
- Framework compatibility policy: `ai/thoughts/framework-feature-design-lens.md`
- Skill-authoring guidance: `ai/skill-authoring/traces-and-debugging.md`
- GitHub Actions documentation consulted through Context7: matrix jobs, hosted OS runners, job-level permissions, and secure pull-request workflow guidance.
