---
date: 2026-08-08T01:09:07-07:00
researcher: Devin
git_commit: 581393b829727261f68ee8a73e094105f1265940
branch: main
repository: loomspan
topic: "PR 21 - Make Entry Skill Required Trace Identity"
tags: [research, codebase, session-identity, observability, trace-catalog, console]
status: complete
last_updated: 2026-08-08
last_updated_by: Devin
---

# Research: PR 21 - Make Entry Skill Required Trace Identity

**Date**: 2026-08-08T01:09:07-07:00
**Researcher**: Devin (GPT-5)
**Git Commit**: 581393b829727261f68ee8a73e094105f1265940
**Branch**: main
**Repository**: loomspan

## Research Question

Research the live Loomspan codebase for the implementation-ready ticket
`ai/thoughts/tickets/loomspan-console-pr-21-trace-catalog-entry-skill.md`: document where entry-skill identity currently originates, how live and finalized trace data flows through Java, Go, browser fallback, TypeScript, fixtures, and tests, and classify the affected framework and protocol surfaces.

## Summary

The current application invocation path resolves a YAML capability before creating a session. `DefaultSkillTemplate` obtains `CapabilityMetadata`, verifies that the requested name equals `capability.name()`, and then calls `LoomspanSessionRunner` without passing that name (`loomspan-spring-boot-starter/src/main/java/com/lokiscale/loomspan/internal/skillapi/DefaultSkillTemplate.java:91`, `:105`, `:134`). The runner creates `LoomspanSession` with session, authentication, persistence, clock, observation, and trace-factory inputs only (`loomspan-spring-boot-starter/src/main/java/com/lokiscale/loomspan/internal/core/LoomspanSessionRunner.java:117`, `:120`, `:154`, `:157`).

During session construction, Java creates the observation handle first and the trace handle second. Both factories receive the session ID, but neither receives entry-skill identity. Trace-handle construction synchronously appends `TRACE_STARTED`, so observation can publish a first snapshot before a root frame exists (`loomspan-spring-boot-starter/src/main/java/com/lokiscale/loomspan/internal/core/LoomspanSession.java:192-205`, `loomspan-spring-boot-starter/src/main/java/com/lokiscale/loomspan/internal/runtime/trace/DefaultExecutionTraceHandle.java:243-257`). `LiveActivityProjector` currently assigns `entrySkill` from the first opened `ROOT_MISSION`, and `ActiveExecutionSnapshot` accepts a nullable value. The first `TRACE_STARTED` projection therefore has no entry skill; Go's active-execution validator rejects an empty value (`loomspan-spring-boot-starter/src/main/java/com/lokiscale/loomspan/internal/runtime/observation/LiveActivityProjector.java:72-85`, `loomspan-spring-boot-starter/src/main/java/com/lokiscale/loomspan/internal/runtime/observation/ActiveExecutionSnapshot.java:19`, `loomspan-console/internal/observability/service.go:297-300`).

Finalization is a separate metadata flow. `DefaultExecutionTraceHandle` builds `FinalizedTraceArtifact` without entry skill; the in-memory catalog copies that descriptor into `FinalizedTraceCatalogEntry`; `ObservabilityDtoMapper` maps the catalog entry to the application REST `Trace` record. None of those three records contains entry skill (`loomspan-spring-boot-starter/src/main/java/com/lokiscale/loomspan/internal/runtime/trace/DefaultExecutionTraceHandle.java:351-365`, `loomspan-spring-boot-starter/src/main/java/com/lokiscale/loomspan/internal/core/FinalizedTraceArtifact.java:9-17`, `loomspan-spring-boot-starter/src/main/java/com/lokiscale/loomspan/internal/runtime/observation/catalog/InMemoryFinalizedTraceCatalog.java:102`, `loomspan-spring-boot-starter/src/main/java/com/lokiscale/loomspan/internal/observability/web/dto/ObservabilityDtos.java:66`).

The same omission continues through the console. Go `observability.Trace`, `validateTrace`, acquisition-time `artifact.TraceMetadata`, the production `TraceLoader`, cached trace reconstruction, TypeScript `Trace`, Trace Catalog, Trace Detail, and the two application REST fixtures all omit entry skill. The cached path matters because list/detail handlers reconstruct `observability.Trace` from installed metadata when the upstream is unavailable, unauthorized, missing, blocked, or invalid (`loomspan-console/internal/browserapi/observability.go:168-177`, `:200-209`, `:213-251`).

The live code also confirms two root-free completion paths described by the ticket. `LoomspanSessionRunner` finalizes arbitrary actions in `finally`, including actions that open no frame (`loomspan-spring-boot-starter/src/main/java/com/lokiscale/loomspan/internal/core/LoomspanSessionRunner.java:112-181`, `:186-240`). For normal skill execution, `CapabilityExecutionRouter` checks access before calling the YAML execution coordinator, and `ExecutionCoordinator` performs another access check before opening the root mission frame (`loomspan-spring-boot-starter/src/main/java/com/lokiscale/loomspan/internal/core/CapabilityExecutionRouter.java:51-81`, `loomspan-spring-boot-starter/src/main/java/com/lokiscale/loomspan/internal/core/ExecutionCoordinator.java:65-96`).

## Detailed Findings

### 1. Entry-skill source and current session boundary

- `DefaultSkillTemplate.invoke` is the ordinary application-facing YAML skill path. It resolves `CapabilityMetadata`, validates the input contract, captures Spring Security authentication, and starts a new session (`DefaultSkillTemplate.java:89-107`).
- `requireYamlSkill` rejects an unknown capability, a non-YAML capability, or a registry result whose normalized name differs from the requested name (`DefaultSkillTemplate.java:134-152`). The returned `capability.name()` is therefore already available before session construction.
- `LoomspanSessionRunner` exposes `runWithNewSession` and `callWithNewSession` variants with optional authentication, but no entry-skill parameter (`LoomspanSessionRunner.java:112-181`). Its completion boundary always invokes trace finalization and records a session-level completion even when the action opened no frame (`LoomspanSessionRunner.java:186-246`).
- `LoomspanSession` has multiple public and package-private constructors. Its deepest constructor validates `sessionId`, creates observation with `create(sessionId)`, then creates tracing with `(sessionId, persistencePolicy, clock, observationHandle)` (`LoomspanSession.java:165-205`). There is no run-owned skill identity field or accessor.
- Spring auto-configuration declares infrastructure beans for `ObservabilityActivationCoordinator` and `LoomspanSessionRunner`; the runner is assembled with the coordinator's observation factory and completion retention (`loomspan-spring-boot-starter/src/main/java/com/lokiscale/loomspan/autoconfigure/LoomspanAutoConfiguration.java:129-143`, `:170-182`). These beans are not declared with `@ConditionalOnMissingBean`.

### 2. Live observation projection

- `ExecutionObservationHandleFactory.create` currently accepts only `sessionId` (`loomspan-spring-boot-starter/src/main/java/com/lokiscale/loomspan/internal/runtime/observation/ExecutionObservationHandleFactory.java:5`). The no-op, default, and activation-coordinator paths share that signature (`NoOpExecutionObservationHandleFactory.java:8`, `DefaultExecutionObservationHandleFactory.java:48-50`, `ObservabilityActivationCoordinator.java:22`, `:57-58`).
- `DefaultExecutionObservationHandle` creates `ExecutionProjectionState(sessionId)` with no entry skill (`DefaultExecutionObservationHandle.java:47`). `ExecutionProjectionState` stores a mutable nullable `String entrySkill` (`ExecutionProjectionState.java:18`).
- Each successful canonical append is projected into activity plus an active snapshot. `LiveActivityProjector.updateState` records the first `ROOT_MISSION` route only when `state.entrySkill == null`; later root frames do not replace it (`LiveActivityProjector.java:65-85`). The route is bounded through `ExecutionObservationLimits.truncate` (`LiveActivityProjector.java:428`).
- The text bound is 256 Unicode code points (`ExecutionObservationLimits.java:8`, `:20-31`). `ActiveExecutionSnapshot` currently declares `@Nullable String entrySkill` and normalizes blank to null, while phase and frame routes are required and bounded (`ActiveExecutionSnapshot.java:12-45`, `:66-92`).
- `DefaultExecutionTraceHandle.initialize` emits `TRACE_STARTED` and `TRACE_CAPTURE_POLICY_RECORDED` during construction (`DefaultExecutionTraceHandle.java:243-257`). The snapshot constructor receives `state.entrySkill` for every projected record (`LiveActivityProjector.java:179-211`), so a snapshot projected before the first `FRAME_OPENED/ROOT_MISSION` carries null.
- Java's REST mapper passes that nullable live field unchanged into `ObservabilityDtos.ActiveExecution` (`ObservabilityDtoMapper.java:24-52`). Go's DTO declares required-looking `EntrySkill string` and `validateActiveExecution` treats an empty value as missing identity (`loomspan-console/internal/observability/dto.go:59-76`, `loomspan-console/internal/observability/service.go:297-310`).

### 3. Root-frame ordering and root-free completion

- `CapabilityExecutionRouter.execute` calls `accessGuard.checkAccess` before dispatching a YAML skill to `ExecutionCoordinator` (`CapabilityExecutionRouter.java:51-81`).
- `ExecutionCoordinator.execute` resolves and checks the root capability before calling `openMissionFrame`; the canonical route used for that root frame is `rootCapability.name()` (`ExecutionCoordinator.java:65-96`). An access denial can therefore occur before a `ROOT_MISSION` record.
- Standalone runner actions are not required to call the coordinator. The runner finalizes success or failure in its `finally` block, records remaining-frame metadata, and only treats still-open frames as a finalization error (`LoomspanSessionRunner.java:120-144`, `:157-181`, `:186-238`). Zero-frame actions finalize through the ordinary path.
- Nested invocations can create additional root mission frames, but projector first-write-wins behavior means only the first root route currently becomes the live entry skill (`LiveActivityProjector.java:78-85`).

### 4. Core finalization and catalog publication

- `LoomspanSession.finalizeTrace` projects the journal, asks the core trace handle to finalize, and closes observation with the finalized descriptor only when core finalization succeeded. On core failure it supplies an empty artifact to observation (`LoomspanSession.java:703-786`, `:809-825`).
- `DefaultExecutionTraceHandle.finalizeTrace` appends `TRACE_COMPLETED`, applies persistence/grace retention, and creates its descriptor from trace ID, session ID, outcome, finalization time, path, size, persistence policy, and optional artifact expiry (`DefaultExecutionTraceHandle.java:310-365`).
- `FinalizedTraceArtifact` validates nonblank trace/session IDs, nonnegative size, normalized absolute path, required outcome/time/policy, and expiry ordering. It has no entry-skill component (`FinalizedTraceArtifact.java:9-45`).
- `InMemoryFinalizedTraceCatalog.publish` accepts only the core descriptor. Re-publication is idempotent when record equality matches and conflicting when the descriptor differs; new publication validates the file and copies descriptor fields into `FinalizedTraceCatalogEntry` (`InMemoryFinalizedTraceCatalog.java:66-119`). The catalog does not read the artifact contents.
- `FinalizedTraceCatalogEntry` retains the original descriptor as well as catalog ordinal, publication time, catalog expiry, and effective application-trace expiry. It currently has no direct entry-skill component (`FinalizedTraceCatalogEntry.java:11-45`).
- `ObservabilityDtoMapper.trace` maps catalog fields to `ObservabilityDtos.Trace`; that REST record has seven fields and no entry skill (`ObservabilityDtoMapper.java:55-60`, `ObservabilityDtos.java:66-73`).

### 5. Go application adapter and validation

- `observability.Trace` represents application trace metadata plus console-local availability facts. It currently includes target scope, trace/session IDs, outcome, time/size/persistence/expiry, local availability, optional handle, and acquisition-time application availability (`loomspan-console/internal/observability/dto.go:78-91`).
- `ListTraces` and `GetTrace` decode application JSON, call `validateTrace`, attach target scope, and recheck that the captured scope is current (`loomspan-console/internal/observability/service.go:151-202`).
- `validateTrace` requires the existing identifiers/state, timestamps, expiry ordering, and nonnegative size; it does not validate an entry-skill field because none exists (`service.go:313-323`).
- The committed application REST corpus is decoded in Go DTO and service tests (`loomspan-console/internal/observability/dto_test.go:127-148`, `loomspan-console/internal/observability/service_test.go:144-166`, `:286-303`). Java constructs the same trace object and writes both trace fixture files in `ConsoleRestFixtureCorpusTest` (`loomspan-spring-boot-starter/src/test/java/com/lokiscale/loomspan/internal/observability/web/ConsoleRestFixtureCorpusTest.java:75-89`).

### 6. Acquisition metadata and installed-copy fallback

- `artifact.TraceMetadata` is documented as immutable acquisition-time metadata copied from the authoritative observability response and retained for the installed entry's lifetime. Its current fields mirror the application trace metadata except entry skill (`loomspan-console/internal/artifact/model.go:26-39`).
- Production wiring calls `observabilityService.GetTrace` in the artifact service's `TraceLoader` and explicitly copies each current trace field into `artifact.TraceMetadata` (`loomspan-console/internal/console/service.go:163-178`).
- `artifact.Service.Lookup` returns the entry's retained metadata with local handle and availability state (`loomspan-console/internal/artifact/service.go:417-447`). `StorageSnapshot` provides cache facts for list reconstruction, while detailed identity is recovered through `Lookup` (`artifact/service.go:360-397`, `artifact/model.go:127-158`).
- Browser trace list/detail first call the live observability service. For authentication, access-blocked, unavailable, not-found, and console errors, handlers attempt installed-copy fallback (`loomspan-console/internal/browserapi/observability.go:151-210`, `:213-227`).
- `cachedTrace` reconstructs a complete `observability.Trace` from `Lookup.Metadata` and local state. `cachedTracePage` iterates storage entries, calls `cachedTrace`, and returns a trace page sorted by trace ID (`observability.go:229-281`). Any finalized identity fact absent from `TraceMetadata` is absent on this path.
- `AcquiredArtifact` already exposes its embedded `TraceMetadata` internally, while browser artifact response mapping exposes selected acquisition facts. `StoredEntry` is a cache/storage view and does not reconstruct the trace DTO (`artifact/model.go:42-50`, `:127-158`).

### 7. TypeScript and browser presentation

- TypeScript `Trace` mirrors the Go browser JSON contract and currently has no `entrySkill` member (`loomspan-console/web/src/api/contracts.ts:157-170`). `AcquiredArtifact` and `StoredEntry` are separate artifact/storage contracts (`contracts.ts:172-204`).
- Trace Catalog renders a focusable, labeled table region with semantic column headers. Its first column is linked Trace ID, followed by Session, Outcome, formatted finalization time, size, persistence, and formatted expiry (`loomspan-console/web/src/observability/Traces.tsx:48-79`).
- Trace Detail renders trace and session IDs at the start of a definition-list identity/status grid, followed by outcome, retention, acquisition availability, and local artifact state (`loomspan-console/web/src/observability/TraceDetail.tsx:142-152`). React interpolates values as text nodes.
- Unit fixtures for both views construct a `Trace` without entry skill (`Traces.test.tsx:28-38`, `TraceDetail.test.tsx:57-67`). Catalog tests cover table facts, scope-bound Trace ID navigation, formatted dates, empty/loading/error states, retry, and pagination (`Traces.test.tsx:46-105`). Detail tests cover identity rendering, scope reset, errors, acquisition, raw-download confirmation/focus, cached availability, and explorer expiry (`TraceDetail.test.tsx:76-203`).
- End-to-end upstream trace metadata builders also omit entry skill in `target-context.spec.ts:24`, `live-executions.spec.ts:9`, and `artifact-storage.spec.ts:27-52`.

### 8. Fixtures and canonical trace formats

- `loomspan-console-fixtures/application-rest/traces-page.json:1` and `trace-detail.json:1` encode the current Java application REST shape without entry skill. Java generates them and Go treats them as executable decoding fixtures.
- Canonical NDJSON already records the route on ordinary `FRAME_OPENED` root mission records. `TRACE_STARTED` carries session ID and trace metadata, not entry skill (`DefaultExecutionTraceHandle.java:249-257`; committed files under `loomspan-console-fixtures/traces/`).
- The current Go trace-analysis contracts derive frame routes after acquisition from NDJSON. This is a distinct installed analysis path from the metadata-only trace catalog and cached trace DTO.

## Contract Classification

The categories below use `ai/thoughts/framework-feature-design-lens.md`.

| Surface | Current classification and evidence | Current consumers |
|---|---|---|
| `SkillTemplate.invoke` and YAML registered-name resolution | **Application API** for ordinary application invocation; YAML skill names and their normalized registered-name semantics are also **Configuration and manifest contracts**. Evidence is the public `SkillTemplate` API and `DefaultSkillTemplate`'s YAML-only validation. | Application callers, `DefaultSkillTemplate`, capability registry/router. |
| `LoomspanSessionRunner`, `LoomspanSession`, observation/trace factories and handles | **Internal or accidentally exposed implementation**. Several types are public for cross-package Java composition, and the architecture allow-list explicitly describes them as internal collaboration rather than application extension points (`LoomspanPublicSurfaceArchitectureTest.java:82-100`, `:184-210`). | Spring auto-configuration and numerous in-repository tests/internal services. |
| Infrastructure Spring beans | **Internal or accidentally exposed implementation**. Technical bean exposure exists for the activation coordinator and runner; there is no `@ConditionalOnMissingBean` declaration for these seams. | Loomspan auto-configuration and internal runtime assembly. |
| Application observability REST trace JSON | A current Java-to-Go **serialized cross-component contract**. The exact-release compatibility check, Java generator, committed corpus, Go decoders, and service tests are evidence of protected in-repository consumers. Its lifecycle posture is lockstep/current release rather than durable cross-version storage. | Go observability service, browser adapter, future shared adapter consumers, application REST fixtures. |
| Browser API Go DTO and TypeScript `Trace` | A serialized internal application-adapter/browser contract with executable Go/TypeScript/browser consumers. | React provider/views, browser handlers, unit and E2E tests. |
| `artifact.TraceMetadata` | Internal immutable installed-copy state, not an externally persisted cross-restart format. Evidence is the package documentation and workspace lifecycle. | Artifact service, browser cached trace list/detail, trace-analysis services. |
| Canonical NDJSON and trace-analysis fixtures | **Ephemeral diagnostic formats** under the framework lens. They are current-run debugging inputs with executable Java/Go fixtures, not a durable cross-version interchange promise. | Java writer/projector, Go parser/analysis, trace fixture corpus. |
| Trace catalog entries | Current-process internal metadata. The catalog is in-memory, TTL-bound, empty after restart, and populated only from a core-issued finalized descriptor. | Java REST adapter and acquisition endpoint. |

Technical exposure is broader than supported status: public modifiers, constructors, records, interfaces, beans, and tests demonstrate current behavior and in-repository coupling. The architecture allow-list supplies explicit evidence that the Java types in this change area are public for internal cross-package composition, not supported Application API or Supported SPI. No affected `@ConditionalOnMissingBean` customization point was found.

## Tests and Construction-Site Inventory

- Direct Java `FinalizedTraceArtifact` construction occurs in the trace handle plus scheduled retention, observation completion, catalog, artifact REST, phase-one, and REST integration tests (`ScheduledCompletionGraceRetentionTest.java:79`, `:106`; `DefaultExecutionObservationHandleTest.java:115`, `:159`; `InMemoryFinalizedTraceCatalogTest.java:182`; `ObservabilityArtifactIntegrationTest.java:121`; `ObservabilityPhaseOneIntegrationTest.java:89`; `ObservabilityRestIntegrationTest.java:218`).
- `LoomspanSessionRunner.callWithNewSession` is used across core runner tests and standalone linter, output-schema, evidence, observation-concurrency, and advisor tests. These calls currently supply only an action and optional authentication.
- Live entry-skill behavior is covered by `LiveActivityProjectorTest`, `DefaultExecutionObservationHandleTest`, active REST integration, Go DTO/service tests, and active browser view fixtures.
- Finalized REST corpus ownership is split intentionally: Java generates/compares the corpus; Go decodes and validates it. The two trace JSON files are the executable application-adapter fixtures for this field.
- Go `TraceMetadata` literals appear in artifact helpers, browser tests, console integration tests, and trace-analysis tests. `internal/artifact/helpers_test.go:133-147` is the common valid metadata helper used by most artifact lifecycle tests.
- Browser trace response mocks occur in the two React view tests and the three E2E specifications listed above.

## Architecture Documentation

The current flow has two separate identity pipelines:

1. Live observation: canonical trace record append -> `DefaultExecutionObservationHandle` -> `LiveActivityProjector` -> active registry -> Java ActiveExecution REST -> Go active service -> browser live views.
2. Finalized trace: `DefaultExecutionTraceHandle.descriptor` -> `FinalizedTraceArtifact` -> observation completion -> in-memory catalog -> Java Trace REST -> Go trace service -> optional artifact acquisition metadata -> live-enriched or installed-copy browser trace response -> TypeScript views.

The first pipeline derives entry skill from trace activity after construction. The second pipeline never receives that derived value. The only common data established before both handles are created today are session ID, persistence policy, clock, and factory references.

The catalog preserves core ownership: only successful core finalization can supply a descriptor, observation failure is isolated from core execution outcome, catalog publication does not parse artifacts, and acquisition leases the already-cataloged artifact. Go similarly centralizes acquisition and cache lifetime in the artifact service; browser fallback reads installed metadata instead of parsing NDJSON.

## Historical Context (from `ai/thoughts/`)

- `ai/thoughts/tickets/loomspan-console-pr-03-observability-catalogs.md:9-32` established core-issued finalized descriptors, TTL-governed current-process metadata, no trace-storage scans, and catalog expiry as discoverability-only.
- `ai/thoughts/tickets/loomspan-console-pr-04-spring-rest-adapter.md:14-21` established the authenticated, read-only application observability REST boundary for active and trace catalog resources.
- `ai/thoughts/tickets/loomspan-console-pr-10-operational-views.md` established the browser's shared trace-catalog query service, scope-bound navigation, and read-only trace discovery.
- `ai/thoughts/tickets/loomspan-console-pr-12-artifact-service.md:7-12` established one centralized scope-bound installed copy with immutable acquisition-time metadata.
- `ai/thoughts/tickets/loomspan-console-pr-15-diagnostic-workflows.md` preserved application content as untrusted presentation data and prohibited automatic trace acquisition.
- `ai/thoughts/phases/loomspan_console_phase_1_observability_foundation.md:181-231` describes the observation handle as framework-owned rather than an observer SPI and lists entry skill as part of active summary identity.
- `ai/thoughts/phases/loomspan_console_phase_2_ui_console.md:561-629` describes persistent execution/trace identity context including entry skill and session/trace identifiers.
- `ai/thoughts/tickets/loomspan-console-pr-21-trace-catalog-entry-skill.md` is the implementation-ready design that connects the currently separate live and finalized identity pipelines.

## Related Research

- `ai/thoughts/research/2026-08-01-diagnostic-workflows-phase-2-hardening.md`
- `ai/thoughts/framework-feature-design-lens.md`

## Open Questions

No unresolved code-location question was found for the ticket's named Java, Go, fixture, or browser surfaces. The live repository confirms the described current behavior and construction sites. The ticket's implementation and semantic verification have not started, and this research did not modify production code or execute the implementation verification suite.
