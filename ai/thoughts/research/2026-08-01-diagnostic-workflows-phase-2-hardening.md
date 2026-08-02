---
date: 2026-08-01T16:29:34-07:00
researcher: Devin
git_commit: 9195db379c7efc98383c2a99738938f68390ead4
branch: main
repository: bifrost
topic: "Bifrost Console PR 15 diagnostic workflows and Phase 2 hardening"
tags: [research, codebase, bifrost-console, diagnostic-workflows, browser, trace-analysis, release]
status: complete
last_updated: 2026-08-01
last_updated_by: Devin
---

# Research: Bifrost Console PR 15 Diagnostic Workflows and Phase 2 Hardening

**Date**: 2026-08-01 16:29:34 PDT
**Researcher**: Devin (GPT-5.6)
**Git Commit**: 9195db379c7efc98383c2a99738938f68390ead4
**Branch**: main
**Repository**: bifrost

## Research Question

Research the current codebase for the work described by `ai/thoughts/tickets/bifrost-console-pr-15-diagnostic-workflows.md`, including workflow coverage inherited from PRs 10–14, degraded paths, application-content security and response bounds, browser verification, and release packaging evidence.

## Summary

The current Console is an independent Go module with an embedded React/TypeScript application. Its runtime is organized around a process-local target scope, a bounded live-activity service, a centralized artifact service, transport-neutral trace-analysis services, and a browser adapter. The browser already exposes operational pages, live execution detail, deliberate trace acquisition, Trace Storage, and a hierarchy-first trace explorer with Timeline, Usage, and Records views (`bifrost-console/README.md:3-9`, `bifrost-console/web/src/app/routes.tsx:14-32`).

The four approved workflow IDs have executable browser scenarios for slow/live execution, usage exploration, repeated nested skill invocations, and failure evidence. The three PR 15 trace scenarios are named directly in `artifact-storage.spec.ts`; live terminal transition and deliberate acquisition are covered in `live-executions.spec.ts` (`bifrost-console/web/e2e/artifact-storage.spec.ts:353-396`, `bifrost-console/web/e2e/live-executions.spec.ts:248-272`, `bifrost-console/web/e2e/live-executions.spec.ts:307-356`).

The existing browser workflow pieces are coordinated but remain generic explorer behavior rather than separate workflow-specific pages. Failure evidence is available in Records and URL selection state; trace usage includes trace and frame attribution; repeated invocations remain distinct by frame ID; registered skill YAML is available through the Skills route. The current browser code does not connect a selected trace frame directly to registered YAML, does not implement a dedicated failure-first explorer mode, and does not compare finalized trace usage arithmetically with configured execution limits. Configured limits are currently displayed on active execution detail (`bifrost-console/web/src/observability/ActiveExecutionDetail.tsx:248-255`), while finalized usage is displayed separately by `TraceUsage` (`bifrost-console/web/src/observability/TraceUsage.tsx:3-17`).

Lifecycle and degraded semantics are distributed to their owning layers. Go owns target-scope rotation, authentication and compatibility status, live continuity and replay bounds, artifact acquisition/expiry/capacity, validation, query continuation, and domain errors. React clears or redirects scope-bound presentation state and renders returned facts. The shared error vocabulary includes `TARGET_CHANGED`, `ARTIFACT_EXPIRED`, `INVALID_ARTIFACT`, `LIVE_MONITORING_UNAVAILABLE`, and `LIMIT_EXCEEDED` (`bifrost-console/internal/consolecore/errors.go:7-24`).

Application-provided YAML, raw records, and reconstructed payload ranges are rendered as React text children in `<pre>` elements. Production code contains no `dangerouslySetInnerHTML`. Component and Playwright tests assert that markup-like content does not become a link or executable element (`bifrost-console/web/src/observability/SkillDetail.tsx:64-79`, `bifrost-console/web/src/observability/TraceEvidenceDetail.tsx:3-5`, `bifrost-console/web/src/observability/TraceViews.test.tsx:55-63`, `bifrost-console/web/e2e/artifact-storage.spec.ts:333-351`). Go additionally bounds JSON request bodies, query pages, ranges, literal search, search work, NDJSON lines, and JSON depth (`bifrost-console/internal/browserapi/trace_analysis.go:12-18`, `bifrost-console/internal/traceanalysis/limits.go:8-48`).

The repository has a reproducible current-platform build pipeline and runtime README, but no repository CI workflow or release-packaging implementation was found for the three supported targets. The build tool emits one executable for the host platform and verifies embedded-asset hashes; executable archives, executable checksums, per-target runtime README bundles, and release license bundles are not present in the live codebase (`bifrost-console/internal/buildtool/pipeline.go:36-56`, `bifrost-console/internal/buildtool/runner.go:48-64`).

## Detailed Findings

### 1. Workflow and navigation foundation from PRs 10–14

The browser router defines Overview, Target, Skills, Active Executions, Traces, Trace Storage, and detail routes beneath one application shell (`bifrost-console/web/src/app/routes.tsx:14-32`). This corresponds to the operational-view foundation described by PR 10 and the hierarchy-first explorer described by PR 14 (`ai/thoughts/tickets/bifrost-console-pr-10-operational-views.md:13-22`, `ai/thoughts/tickets/bifrost-console-pr-14-trace-explorer.md:12-24`).

Current-scope navigation uses `targetScopeId` in query state. Explorer state admits `hierarchy`, `timeline`, `usage`, and `records`, plus frame, failure, and record coordinates; malformed view and record-sequence coordinates are dropped (`bifrost-console/web/src/observability/traceExplorerState.ts:1-17`). Each returned trace-analysis response is checked against the current target scope before browser state is accepted (`bifrost-console/web/src/observability/TraceExplorer.tsx:82-109`).

The Go browser router is an adapter over interfaces for artifacts and trace analysis rather than the owner of calculations. Its trace-analysis interface exposes summary, hierarchy, records, attempts, retries, validation, failures, payloads, gaps, uncertainties, usage, search, and range reads (`bifrost-console/internal/browserapi/router.go:20-47`). Browser analysis routes resolve a trace ID to the current scope's internal artifact handle; the handle is not included in analysis responses (`bifrost-console/internal/browserapi/trace_analysis.go:20-45`).

### 2. `WF-FAILED-EXECUTION`

Live detail recognizes `TRACE_COMPLETED` and `EXECUTION_OBSERVATION_ENDED`, retains the selected route, and distinguishes artifact availability and core finalization failure (`bifrost-console/web/src/observability/ActiveExecutionDetail.tsx:47-63`). Terminal presentation states that context is preserved, reports an observation ending without a trustworthy outcome when applicable, and shows **Inspect trace** only when the application reported `AVAILABLE` (`bifrost-console/web/src/observability/ActiveExecutionDetail.tsx:148-177`). Acquisition is a separate button action and uses the shared artifact API (`bifrost-console/web/src/observability/ActiveExecutionDetail.tsx:110-129`, `bifrost-console/web/src/observability/ActiveExecutionDetail.tsx:178-201`).

Trace detail keeps application availability and local artifact availability as separate fields and presents acquisition separately from unchanged raw attachment download (`bifrost-console/web/src/observability/TraceDetail.tsx:140-171`). Once locally available, one shared `TraceExplorer` is rendered (`bifrost-console/web/src/observability/TraceDetail.tsx:180-193`).

The explorer summary includes the returned outcome and evidence counts. Failure facts are loaded with Records, selected by `failureId`, and continuable when the selected failure is not in the first page (`bifrost-console/web/src/observability/TraceExplorer.tsx:141-163`, `bifrost-console/web/src/observability/TraceExplorer.tsx:279-285`). The current live code does not define a distinct failure-focused route or mode; `TraceExplorerView` contains only hierarchy, timeline, usage, and records (`bifrost-console/web/src/observability/traceExplorerState.ts:1-3`).

Playwright covers terminal in-place transition, deliberate acquisition, and a failure evidence scenario whose raw content remains inert (`bifrost-console/web/e2e/live-executions.spec.ts:248-272`, `bifrost-console/web/e2e/live-executions.spec.ts:307-356`, `bifrost-console/web/e2e/artifact-storage.spec.ts:385-396`).

### 3. `WF-EXPENSIVE-EXECUTION`

Trace-analysis DTOs carry attributed, unattributed, unframed-attributed, and terminal usage. Frame DTOs carry direct, descendant, and inclusive usage with independent completeness flags (`bifrost-console/internal/traceanalysis/dto.go:22-45`, `bifrost-console/internal/traceanalysis/dto.go:47-72`, `bifrost-console/internal/traceanalysis/dto.go:149-156`). The browser adapter maps these values without recomputing them (`bifrost-console/internal/browserapi/trace_analysis.go:58-63`, `bifrost-console/internal/browserapi/trace_analysis.go:79-88`, `bifrost-console/internal/browserapi/trace_analysis.go:115-130`).

The browser loads usage only when the Usage view is opened. At trace level it displays attributed, unattributed, unframed-attributed, and terminal values; with a selected frame it displays direct, descendant, and inclusive values and marks incomplete rows (`bifrost-console/web/src/observability/TraceExplorer.tsx:164-174`, `bifrost-console/web/src/observability/TraceUsage.tsx:3-17`). Attempt and retry usage are returned and shown with Records rather than in the Usage table (`bifrost-console/web/src/api/contracts.ts:247-252`).

Active execution contracts include usage counters and five configured limits (`bifrost-console/web/src/api/contracts.ts:120-155`). Active detail renders configured limits as a separate definition list (`bifrost-console/web/src/observability/ActiveExecutionDetail.tsx:248-255`). The finalized trace contracts do not carry configured limits, and `TraceUsage` performs no limit proportion calculation (`bifrost-console/web/src/api/contracts.ts:221-255`, `bifrost-console/web/src/observability/TraceUsage.tsx:7-17`).

The named Playwright workflow selects a nested frame, retains that selection across Timeline and Usage, checks returned frame usage, then exercises a narrow viewport, forced colors, reduced motion, 200% zoom, and keyboard focus (`bifrost-console/web/e2e/artifact-storage.spec.ts:353-370`).

### 4. `WF-UNFAMILIAR-SKILL-PATH`

The finalized hierarchy is returned by Go with stable frame IDs, parent IDs, child IDs, type, route, timing, and usage (`bifrost-console/internal/traceanalysis/dto.go:47-72`). React renders it as an ARIA tree with canonical nested traversal, expand/collapse state, and Home, End, Arrow Up/Down, Arrow Left/Right behavior (`bifrost-console/web/src/observability/TraceHierarchy.tsx:4-28`). The explorer reconstructs selected-frame ancestry from returned parent relationships and presents breadcrumbs (`bifrost-console/web/src/observability/TraceExplorer.tsx:176-235`, `bifrost-console/web/src/observability/TraceExplorer.tsx:301-308`).

Registered skill detail is a separate current-scope page. It displays the application-provided registered name, `sourcePath` as code text, and unchanged YAML in a `<pre>` element; `sourcePath` is not rendered as a link (`bifrost-console/web/src/observability/SkillDetail.tsx:64-79`). The Java adapter produces these three fields directly from `RegisteredSkillFile` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityDtoMapper.java:16-24`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/RegisteredSkillFile.java:5-19`).

The current `TraceFrame` browser contract has frame type and route but no registered-skill name or Skills-route reference, so trace hierarchy and skill YAML remain separately navigable product areas in the live implementation (`bifrost-console/web/src/api/contracts.ts:231-239`).

Playwright proves that two invocations with the same route remain distinct by frame ID and that selection survives view changes and reload (`bifrost-console/web/e2e/artifact-storage.spec.ts:372-383`).

### 5. Degraded, lifecycle, and uncertainty paths

The shared Go domain codes separate authentication, access blocking, target availability, incompatibility, scope change, cursor state, artifact expiry/validity, live availability, limits, local storage, and console errors (`bifrost-console/internal/consolecore/errors.go:7-24`). Error details may include current scope, transport category, limit name/value, and raw-download availability (`bifrost-console/internal/consolecore/errors.go:26-34`).

Target scope is captured for operations and published atomically. Old-scope artifact operations return `TARGET_CHANGED`; expired handles return `ARTIFACT_EXPIRED` (`bifrost-console/internal/artifact/service.go:140-169`, `bifrost-console/internal/artifact/service.go:260-281`). Scope rotation cancels acquisitions and removes old-scope artifact entries through the artifact service's target-owner integration.

The browser clears selected frame/record/failure coordinates on `TARGET_CHANGED`. It clears explorer evidence and returns Trace Detail to a not-installed state on `ARTIFACT_EXPIRED` or a missing artifact lookup (`bifrost-console/web/src/observability/TraceExplorer.tsx:86-109`, `bifrost-console/web/src/observability/TraceDetail.tsx:72-78`). Scope-bound routes redirect stale target references to the root through the shared scope hook.

Gap and uncertainty counts are part of the summary, while individual gap and uncertainty facts are loaded in Records (`bifrost-console/web/src/api/contracts.ts:221-255`, `bifrost-console/web/src/observability/TraceExplorer.tsx:141-163`). Missing timing and incomplete usage remain explicit in the browser (`bifrost-console/web/src/observability/TraceViews.test.tsx:38-45`, `bifrost-console/web/e2e/artifact-storage.spec.ts:398-408`).

Live activity uses one bounded interval. The fixture documentation records a 2,048-entry/8-MiB Go ring and the browser REST/SSE routes (`bifrost-console-fixtures/README.md:25-38`). Browser reducers and presentation distinguish continuity reset, replay/subscriber overflow, connection state, and recent reload behavior. Target restart/instance change is exercised by replacing the fixture application's instance ID and asserting prior live state disappears (`bifrost-console/web/e2e/live-executions.spec.ts:274-305`).

The Java producer represents core finalization failure as `EXECUTION_OBSERVATION_ENDED` details with unavailable trace facts (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java:177-183`). React currently checks `applicationTraceAvailability === "CORE_FINALIZATION_FAILED"` for its `finalizationFailed` branch, while the Java source shown above puts `"UNAVAILABLE"` in `applicationTraceAvailability` and `"CORE_FINALIZATION_FAILED"` in `applicationTraceUnavailableReason` (`bifrost-console/web/src/observability/ActiveExecutionDetail.tsx:56-58`).

### 6. Application content, authority boundaries, and response bounds

The browser API validates Host before request bodies, validates Origin for JSON operations, uses session checks for reads, and adds tab/CSRF checks for sensitive operations (`bifrost-console/internal/browserapi/router.go:75-98`, `bifrost-console/internal/browserapi/router.go:99-175`). Raw downloads use a separate same-site navigation policy and safe attachment response path (`bifrost-console/internal/browserapi/router.go:82-88`).

Application-provided YAML and evidence bytes are presented as text children. Tests use script-like YAML and anchor-like payloads and assert no executable script or link is created (`bifrost-console/web/src/observability/SkillDetail.tsx:77-78`, `bifrost-console/web/src/observability/TraceEvidenceDetail.tsx:3-5`, `bifrost-console/web/src/observability/TraceViews.test.tsx:55-63`). Playwright repeats the no-link assertion against real browser rendering (`bifrost-console/web/e2e/artifact-storage.spec.ts:333-351`, `bifrost-console/web/e2e/artifact-storage.spec.ts:385-396`).

Trace-analysis JSON request bodies are capped at 8 KiB (`bifrost-console/internal/browserapi/trace_analysis.go:12-18`). The parser/query layer caps physical NDJSON lines at 1 MiB, JSON nesting at 128, pages at 1,000 items, payload/raw ranges at 1 MiB, literal search at 1 KiB/256 code points, and search work at 8 MiB/10,000 records (`bifrost-console/internal/traceanalysis/limits.go:8-48`). Browser evidence reads are explicit and continuable; the E2E large-payload scenario verifies 64-KiB ranges (`bifrost-console/web/e2e/artifact-storage.spec.ts:410-420`).

### 7. Browser verification currently present

`web/package.json` defines Vitest, coverage, Playwright, typecheck, and production browser build scripts (`bifrost-console/web/package.json:11-17`). Playwright is configured for Chromium and retains traces on failure (`bifrost-console/web/playwright.config.ts:3-9`). Its process fixture starts the built executable with an isolated temporary profile/workspace and removes that temporary root after shutdown (`bifrost-console/web/e2e/fixtures/consoleProcess.ts:38-65`).

Current Playwright files cover pairing, session lifecycle, shell/assets, target context, activity stream, live executions, artifact storage, and trace workflows. Workflow-name linkage exists for `WF-EXPENSIVE-EXECUTION`, `WF-UNFAMILIAR-SKILL-PATH`, and `WF-FAILED-EXECUTION` (`bifrost-console/web/e2e/artifact-storage.spec.ts:353-396`). The slow/live workflow uses `WF-SE` names in `live-executions.spec.ts` (`bifrost-console/web/e2e/live-executions.spec.ts:248-313`).

Complex keyboard semantics are component-tested for the hierarchy and implemented for explorer tabs and the raw-download dialog (`bifrost-console/web/src/observability/TraceViews.test.tsx:10-37`, `bifrost-console/web/src/observability/TraceExplorer.tsx:287-299`, `bifrost-console/web/src/observability/TraceDetail.tsx:43-70`). The workflow E2E scenario checks focus under forced colors, reduced motion, zoom, and a narrow viewport, but it does not drive the hierarchy or tab arrow-key algorithms (`bifrost-console/web/e2e/artifact-storage.spec.ts:365-370`).

The build tool's `verify` pipeline runs browser typecheck and Vitest coverage, builds and verifies embedded assets, and runs Go tests. It does not invoke `npm run test:e2e` (`bifrost-console/internal/buildtool/pipeline.go:36-50`, `bifrost-console/internal/buildtool/runner.go:31-54`).

### 8. Packaging, runtime, and release evidence

Exact build prerequisites are Go 1.26.5, Node.js 24.18.0, and npm 12.0.2 (`bifrost-console/README.md:11-20`). `verify` and `build` use the locked frontend graph, typecheck, coverage, clean asset generation, SHA-256 asset manifest verification, and Go tests; `build` then compiles the current platform executable (`bifrost-console/README.md:22-45`, `bifrost-console/internal/buildtool/pipeline.go:36-56`). Node and npm are build-time dependencies only (`bifrost-console/README.md:17-20`).

The runtime README documents all command-line flags, strict YAML configuration, profile/workspace locations for Windows, macOS, and Linux, pairing, target trust, and workspace lifecycle (`bifrost-console/README.md:47-176`). The root POM declares MPL 2.0 (`pom.xml:16-22`).

The Phase 2 design names Windows x86-64, Linux x86-64, and macOS Apple Silicon as initial published targets and specifies one executable with license, short runtime README, and checksums (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:170-188`). Live build code uses `runtime.GOOS` only to choose `.exe` naming and builds the host target (`bifrost-console/internal/buildtool/runner.go:48-64`). No GitHub Actions, GitLab CI, Azure Pipelines, release scripts, executable checksum generation, release archives, or per-target package manifests were found in the repository.

The fixture corpus currently contains sixteen valid and twenty invalid traces and covers nested/repeated frames, incomplete/overlapping duration, chunked payloads, usage variants, failures, attempts, and structural-limit mutations (`bifrost-console-fixtures/README.md:1-19`). The browser fixture server additionally synthesizes a multi-megabyte payload for bounded range verification (`bifrost-console/web/e2e/artifact-storage.spec.ts:410-420`).

## Framework and Protocol Surface Classification

The canonical framework design lens classifies the relevant surfaces as follows:

- **Application API:** no PR 15 diagnostic workflow browser code is an application-facing Java API. The configured application observability key and fixed HTTP namespace are runtime adapter concerns.
- **Supported SPI:** no new PR 15 SPI is present. The Go `ArtifactService` and `TraceAnalysisService` interfaces are internal adapter seams (`bifrost-console/internal/browserapi/router.go:20-47`).
- **Configuration and manifest contracts:** Console schema-version 1 YAML is strict and restart-only; the generated embedded-asset manifest contains path, length, and SHA-256 data (`bifrost-console/README.md:70-105`).
- **Persisted or serialized contracts:** browser REST/SSE DTOs, Java application REST/SSE/problem responses, artifact headers, and Go browser responses are serialized process boundaries. Current protected consumers are the Go `applicationclient`, Go `browserapi`, React API contracts/client, and executable Java/Go/browser fixtures.
- **Ephemeral diagnostic formats:** current-release NDJSON traces and their Java-to-Go corpus are explicitly ephemeral and do not promise cross-version readability (`bifrost-console-fixtures/README.md:1-5`).
- **Internal or accidentally exposed implementation:** artifact handles, local paths, Go derived index files, browser reducer state, and explorer URL selection details are internal/current-scope mechanisms. Browser analysis resolves trace IDs to handles without exposing handles in analysis responses (`bifrost-console/internal/browserapi/router.go:29-31`).

The Java protocol producer is `ObservabilityRestController` plus the activity/finalization runtime. Java fixture producers are `ConsoleRestFixtureCorpusTest`, `ConsoleSseFixtureCorpusTest`, `ConsoleArtifactFixtureCorpusTest`, and `ConsoleTraceFixtureCorpusTest`. The Go protected consumers are `internal/applicationclient`, `internal/live`, `internal/artifact`, and `internal/traceanalysis`; React consumes the Go browser DTOs in `web/src/api/contracts.ts`. Observable changes to configured limits, terminal trace availability, skill YAML/source path, trace metadata/artifact transport, activity details, problem codes, or consumed NDJSON semantics therefore cross Java fixtures and Go/browser consumers in the same repository release.

## Architecture Documentation

The current end-to-end flow is:

1. Java records canonical execution and trace evidence and exposes authenticated instance, skill, active-execution, trace, artifact, and activity boundaries.
2. Go `target.Context` owns one selected target authority, credential lifecycle, instance identity, and opaque target scope.
3. Go `live.Service` owns one bounded activity interval and fans it out to browser SSE/recent queries.
4. Go `artifact.Service` deliberately acquires, validates, atomically installs, capacity-charges, leases, expires, removes, and invalidates one current-scope copy per trace.
5. Go `traceanalysis.Service` reads the installed components and returns bounded transport-neutral hierarchy, timing, usage, attempt, retry, validation, failure, gap, uncertainty, record, search, and byte-range facts.
6. `browserapi.Router` applies loopback browser security and adapts those shared services into scoped JSON/SSE responses.
7. React owns navigation, selection, sorting/presentation, focus, progressive disclosure, and current-scope URL state. It does not recompute authoritative trace relationships or usage.

## Code References

- `bifrost-console/web/src/observability/ActiveExecutionDetail.tsx:47-63` — terminal activity and availability classification.
- `bifrost-console/web/src/observability/ActiveExecutionDetail.tsx:148-201` — preserved terminal presentation, Inspect trace, and deliberate acquisition.
- `bifrost-console/web/src/observability/TraceExplorer.tsx:82-174` — scope checks and lazy evidence loading.
- `bifrost-console/web/src/observability/TraceExplorer.tsx:176-299` — deep-link lookup, ancestry, evidence selection, continuations, and tab keyboard behavior.
- `bifrost-console/web/src/observability/TraceUsage.tsx:3-17` — current usage table.
- `bifrost-console/web/src/observability/TraceHierarchy.tsx:4-28` — ARIA tree and keyboard behavior.
- `bifrost-console/web/src/observability/SkillDetail.tsx:64-79` — source path and inert YAML rendering.
- `bifrost-console/internal/browserapi/router.go:20-47` — shared adapter-facing service interfaces.
- `bifrost-console/internal/browserapi/router.go:75-175` — browser security order and route map.
- `bifrost-console/internal/browserapi/trace_analysis.go:20-63` — scope/handle resolution and summary adaptation.
- `bifrost-console/internal/traceanalysis/dto.go:22-72` — neutral summary/frame contracts.
- `bifrost-console/internal/traceanalysis/limits.go:8-48` — fixed parser/query bounds.
- `bifrost-console/internal/consolecore/errors.go:7-34` — shared domain errors and details.
- `bifrost-console/web/e2e/artifact-storage.spec.ts:353-420` — three named workflows, incomplete timing, and large payload continuation.
- `bifrost-console/web/e2e/live-executions.spec.ts:248-356` — terminal transition, restart reset, and deliberate acquisition.
- `bifrost-console/internal/buildtool/pipeline.go:36-56` — canonical verification/build phases.
- `bifrost-console/internal/buildtool/runner.go:31-64` — concrete commands and current-platform executable build.

## Historical Context (from ai/thoughts/)

- `ai/thoughts/phases/bifrost_console_workflows.md:32-92` defines the four approved workflows and shared product requirements.
- `ai/thoughts/phases/bifrost_console_workflows.md:101-220` defines the failed-execution live-to-trace workflow and degraded paths.
- `ai/thoughts/phases/bifrost_console_workflows.md:352-507` defines usage attribution, configured-limit comparison, and interpretation boundaries.
- `ai/thoughts/phases/bifrost_console_workflows.md:509-666` defines nested skill path and registered YAML coordination.
- `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:145-188` defines release coupling, toolchains, CI composition, supported targets, and runtime packaging.
- `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:730-780` defines Timeline, Usage, Records, progressive disclosure, complete inspectability, and browser-owned explorer state.
- `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:108-120` places PR 15 after PRs 10–14 as Phase 2 workflow and hardening completion.
- `ai/thoughts/phases/bifrost_console_phase_1_completion_evidence.md:8-40` records Phase 1 automated evidence as passing on Windows on 2026-07-26 and lists its wrapper commands.
- `ai/thoughts/tickets/bifrost-console-pr-10-operational-views.md` through `bifrost-console-pr-14-trace-explorer.md` describe the operational, live, artifact, analysis, and explorer layers that PR 15 audits.

## Related Research

No pre-existing documents were present in `ai/thoughts/research/` at the time of this research.

## Open Questions

These are areas for which no current implementation artifact or recorded verification result was found:

- No Phase 2 completion-evidence index equivalent to the Phase 1 completion-evidence document is present.
- No repository CI/release workflow records execution of Playwright or packaging on Windows x86-64, Linux x86-64, and macOS arm64.
- No release archive layout, executable checksum file, bundled license, or package-specific runtime README is present.
- No committed representative target/trace-size matrix or manual accessibility verification record is present.
- The live browser contract check for core finalization failure and the current Java-produced activity detail use different fields, as documented in the degraded-path section.
- No current trace/frame DTO field connects a runtime frame directly to a registered skill name or Skills detail route.
- No finalized trace DTO carries configured limits for the designed arithmetic limit-comparison presentation.
