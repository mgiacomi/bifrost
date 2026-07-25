---
date: 2026-07-24 20:41:17 PDT
researcher: mgiacomi
git_commit: a35b9c5c63930d40bc873a25ef878648ff2362b3
branch: main
repository: bifrost
topic: "Bifrost Console PR 03 — Skill and Finalized-Trace Catalogs"
tags: [research, codebase, observability, skill-catalog, trace-catalog, retention]
status: complete
last_updated: 2026-07-24
last_updated_by: mgiacomi
---

# Research: Bifrost Console PR 03 — Skill and Finalized-Trace Catalogs

**Date**: 2026-07-24 20:41:17 PDT  
**Researcher**: mgiacomi  
**Model**: GPT-5  
**Git Commit**: a35b9c5c63930d40bc873a25ef878648ff2362b3  
**Branch**: main  
**Repository**: bifrost

## Research Question

Research the current codebase for the work described by
`ai/thoughts/tickets/bifrost-console-pr-03-observability-catalogs.md`, using the
phase roadmap and future tickets to document how the PR's services will be
consumed.

The ticket asks for two application-service catalogs:

- registered skill YAML keyed by unique skill name, with normalized
  skills-root-relative `sourcePath` and unchanged UTF-8 YAML; and
- TTL-governed metadata for successfully finalized, current-process trace
  artifacts, without transferring canonical artifact ownership from core
  (`ai/thoughts/tickets/bifrost-console-pr-03-observability-catalogs.md:9-24`).

It also calls for catalog ordinals, deterministic keyset traversal, completion
grace, availability facts, terminal activity coordination, and guaranteed
active-entry removal, while keeping Spring routes, downloads, Go acquisition,
YAML parsing, and history outside PR 03
(`ai/thoughts/tickets/bifrost-console-pr-03-observability-catalogs.md:26-53`).

## Summary

The repository contains the PR 01 trace contract and the PR 02 observation
lifecycle, but it does not yet contain a registered-skill observability catalog,
a finalized-trace catalog, a finalized-artifact descriptor, completion-grace
retention, trace-catalog TTL configuration, or catalog cleanup scheduling.

The current skill discovery boundary is `YamlSkillCatalog`. At startup it
resolves each configured resource pattern, filters existing resources, sorts
resources by their URI/description, parses each resource stream into a
`YamlSkillManifest`, validates it, and stores a `YamlSkillDefinition` in a
`LinkedHashMap` keyed by the exact registered skill name
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:55-113`,
`:115-143`, `:255-310`). A definition retains the Spring `Resource`, a defensive
parsed manifest, execution configuration, and compiled evidence contract; it
does not retain original YAML text or a normalized source path
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillDefinition.java:12-25`,
`:121-164`). The existing `getSkills()` order is deterministic resource order,
not registered-name order.

The current trace handle creates a unique temporary NDJSON file during session
construction, writes canonical records synchronously, publishes an observation
only after a complete append, appends `TRACE_COMPLETED` during finalization, and
then immediately deletes the file when `NEVER` or successful `ONERROR` requires
deletion
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:57-72`,
`:147-187`, `:240-277`, `:389-414`). `ExecutionTraceHandle.finalizeTrace`
returns `void`; no successful-finalization descriptor is returned
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionTraceHandle.java:8-27`).

`BifrostSession.finalizeTrace` is the core coordination point. It projects the
completed journal before finalization, converts journal projection failure into
a failed trace completion, calls the handle's finalizer under the session lock,
records the finalized journal only when both projection and finalization
succeed, unlocks, and then closes the optional observation handle with a
success/failure disposition
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java:703-805`).
The PR 02 observation handle already holds canonical completion activity until
that close, publishes one terminal activity, and removes the active registry
entry in a `finally` block
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java:42-128`).
It has no artifact descriptor or catalog collaborator, so successful completion
activity currently has no application-trace availability enrichment.

The application auto-configuration currently constructs `BifrostSessionRunner`
with `NoOpExecutionObservationHandleFactory`; PR 02's enabled factory and
in-memory registries exist as internal classes but are not activated in the
starter
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:123-140`).
The only Spring-managed executor is the virtual-thread mission executor, whose
bean destroy method is `close`; no scheduler or catalog lifecycle bean exists
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:322-327`).

## Detailed Findings

### 1. Repository and roadmap position

The repository is a Maven multi-module Java 21 project. The relevant production
work is in `bifrost-spring-boot-starter`; `bifrost-console-fixtures` contains
the Java/Go semantic trace corpus, while the future standalone Go console does
not yet exist (`pom.xml:40-45`, `pom.xml:49-50`).

The roadmap orders Phase 1 as `01 -> 02 -> 03 -> 04 -> 05 -> 06`.
PR 03 establishes transport-neutral catalogs before PR 04 exposes them through
REST, PR 05 adds SSE, and PR 06 streams artifact bytes
(`ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:98-106`).

PR 03 depends directly on the PR 02 lifecycle. The live code matches that
dependency:

1. canonical append publishes to one per-execution observation handle only
   after the writer succeeds;
2. `TRACE_COMPLETED` is held rather than immediately appended to replay;
3. core finalization closes the handle with an explicit success/failure
   disposition; and
4. the handle removes the active entry after terminal publication is attempted.

These behaviors are present in
`DefaultExecutionTraceHandle.appendInternal` and
`DefaultExecutionObservationHandle`
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:279-337`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java:42-128`).

### 2. Current skill discovery and resource ownership

`BifrostProperties.Skills.locations` is a list of Spring resource patterns. Its
default is only `classpath:/skills/**/*.yaml`; null or empty assignment restores
that default
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostProperties.java:304-313`).
The root README and sample add separate `.yml` and `.yaml` patterns
(`README.md:69-72`, `README.md:116`,
`bifrost-sample/src/main/resources/application.yml:34-37`).

`YamlSkillCatalog` owns discovery and loading:

- `afterPropertiesSet()` clears both maps, discovers resources, loads each
  definition, and rejects a duplicate registered name
  (`YamlSkillCatalog.java:93-108`);
- `discoverResources()` iterates configured locations in declaration order,
  calls `ResourcePatternResolver.getResources`, accepts every existing
  resource, then globally sorts all results by `describe(resource)`
  (`YamlSkillCatalog.java:115-143`);
- `describe(resource)` returns `resource.getURI().toString()` when possible and
  otherwise the Spring resource description
  (`YamlSkillCatalog.java:987-996`);
- `readManifest()` opens the resource stream once and parses it through Jackson
  YAML into a tree and then a typed manifest (`YamlSkillCatalog.java:255-310`);
- `skillsByName` is a `LinkedHashMap`, so `getSkills()` returns insertion order
  and `getSkill(name)` is exact, case-sensitive name lookup
  (`YamlSkillCatalog.java:55-57`, `:110-113`).

The resource sort is executable behavior. The pattern test expects
`patternTwoSkill` before `patternOneSkill`, reflecting sorted resource
descriptions rather than alphabetic registered names
(`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalogTests.java:331-342`).
The public-name tests show exact lookup and no case-folding
(`YamlSkillCatalogTests.java:49-75`).

Duplicate registered names stop application startup. The existing diagnostic
identifies the second sorted resource and reports the duplicated name
(`YamlSkillCatalogTests.java:312-328`). There is no current concept of duplicate
`sourcePath`, because no source path is derived or stored.

`YamlSkillDefinition.resource()` exposes the originally discovered Spring
`Resource` through the record accessor. The definition's canonical constructor
defensively copies the parsed manifest and validates the relationship between
the manifest, implementation type, execution configuration, and evidence
contract (`YamlSkillDefinition.java:15-56`). The record does not copy or cache
the resource stream. Consequently, the current runtime definition represents
the parsed manifest and a resource reference; it does not contain the exact
UTF-8 bytes that were parsed.

The live discovery path does not calculate a skills-root-relative path. Its
diagnostic description can include a `file:`, `jar:`, or other resource URI
because it is based on `Resource.getURI()` (`YamlSkillCatalog.java:987-996`).
No code in `YamlSkillCatalog` calls `getFilename`, computes a path relative to
the configured pattern, strips a configured root, or normalizes separators.

The configured location contract accepts multiple arbitrary Spring patterns,
not a single stored "skills root." The phase design defines the intended
catalog metadata independently: a path such as
`classpath:/skills/incidents/check_dns.yml` is represented as
`incidents/check_dns.yml`, and duplicate descriptive paths from different roots
remain valid because registered name is identity
(`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:203-209`).

### 3. Current trace file creation and identity

Every `DefaultExecutionTraceHandle` independently generates a trace UUID. Its
default path is:

```text
${java.io.tmpdir}/${sessionId}.${traceId}.execution-trace.ndjson
```

(`DefaultExecutionTraceHandle.java:416-424`).

The constructor resolves the exact `Path`, creates an
`NdjsonTraceRecordWriter`, deletes any file at that exact path, and writes
`TRACE_STARTED` plus `TRACE_CAPTURE_POLICY_RECORDED`
(`DefaultExecutionTraceHandle.java:147-187`, `:189-216`, `:269-277`).
Tests verify that two handles with the same session ID receive distinct paths
and that production filenames include the session prefix and generated trace
component (`ExecutionTraceHandleTest.java:64-88`).

`ExecutionTrace.snapshot()` currently contains `traceId`, `sessionId`, a
nullable string `filePath`, persistence policy, `errored`, and `completed`.
It can convert the string back to `Path`
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionTrace.java:10-38`).
This is an internal diagnostic/runtime snapshot; it is not an opaque catalog
entry or server DTO.

### 4. Canonical append and observation publication

`appendInternal` assigns the next sequence, serializes data, and writes one
record or an envelope plus all chunks. For a chunked payload it publishes one
logical record only after the envelope and every chunk have been written
(`DefaultExecutionTraceHandle.java:279-337`). For an ordinary payload it writes
the physical record and then publishes it (`DefaultExecutionTraceHandle.java:339-342`).

`publish()` catches runtime failures from the optional observation handle so
they do not change canonical trace behavior
(`DefaultExecutionTraceHandle.java:345-355`). The handle test covers success
after all chunk writes and no logical publication when the envelope, middle
chunk, or final chunk write fails
(`ExecutionTraceHandleTest.java:121-190`).

This establishes the current trust boundary used by PR 03: a catalog cannot
infer successful finalization merely from observing a `TRACE_COMPLETED`
logical record, because that record is published after its append but before
the remainder of `finalizeTrace`, including retention deletion, returns.

### 5. Current finalization and persistence-policy cleanup

`TracePersistencePolicy` contains exactly `NEVER`, `ONERROR`, and `ALWAYS`
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/TracePersistencePolicy.java:3-8`).
`ExecutionTraceProperties` binds `execution-trace.persistence`, defaults to
`ONERROR`, and restores that default on null assignment
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/ExecutionTraceProperties.java:8-23`).

`DefaultExecutionTraceHandle.finalizeTrace` currently:

1. returns immediately when already completed;
2. adds `errored` and the persistence policy to completion metadata;
3. appends `TRACE_COMPLETED`;
4. sets `completed = true`; and
5. immediately calls `Files.deleteIfExists(tracePath)` when the policy says not
   to retain the file
   (`DefaultExecutionTraceHandle.java:240-264`).

Deletion is selected as follows:

- `NEVER`: delete after every completion;
- `ONERROR`: delete when `errored` is false;
- `ALWAYS`: do not delete
  (`DefaultExecutionTraceHandle.java:389-397`).

The handle test asserts exactly those three results
(`ExecutionTraceHandleTest.java:28-45`). There is no duration parameter,
expiration instant, delayed deletion task, scheduler handle, or shutdown
coordination in this class.

If deletion succeeds, `snapshot().filePath` becomes null for a completed trace
whose policy requires deletion (`DefaultExecutionTraceHandle.java:399-407`).
If the deletion call throws, the `completed` flag has already been set. The
session-runner tests explicitly exercise a finalization failure after that
point and require a core-failure observation disposition
(`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/core/BifrostSessionRunnerTest.java:384-437`).

`ExecutionTraceHandle.finalizeTrace` has no return value and exposes no
finalized-artifact record (`ExecutionTraceHandle.java:19-27`). The only direct
path accessor is `tracePath()`. No current production type combines finalized
trace/session identity, exact path, size, retention policy, artifact expiration,
or completion time.

### 6. Session completion, failure propagation, and cleanup

`BifrostSessionRunner` constructs a new session for each call, runs the action,
marks the trace errored when the action throws, and always calls
`completeSession` in `finally`
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSessionRunner.java:69-140`).

The runner creates a terminal `TraceCompletion` with:

- `SUCCEEDED` when the action returns;
- `ABORTED` when the failing thread is interrupted; or
- `FAILED` for other failures
  (`BifrostSessionRunner.java:175-195`).

Cleanup failure is suppressed onto an existing execution failure, or propagated
when there was no prior failure (`BifrostSessionRunner.java:197-221`).

Inside `BifrostSession.finalizeTrace`, completed-journal projection occurs
before the handle finalizer. Projection failure:

- creates or reuses a terminal failure ID;
- changes the effective completion to failed;
- marks the handle errored; and
- attempts to append an `ERROR_RECORDED` record
  (`BifrostSession.java:721-751`).

The handle finalizer is then invoked. Only when projection, finalization, and
the projected journal all succeed is the finalized journal stored
(`BifrostSession.java:752-763`). Runtime exceptions, errors, finalization I/O
failure, and projection I/O failure are separately retained through unlock,
observation close, and rethrow (`BifrostSession.java:765-805`).

The observation disposition is `CORE_FINALIZATION_SUCCEEDED` only when all four
failure variables are null; otherwise it is `CORE_FINALIZATION_FAILED`
(`BifrostSession.java:777-784`). Observation close occurs after the session
lock is released. Runtime failures from the optional observer are swallowed so
they do not change core finalization (`BifrostSession.java:807-818`).

### 7. Existing terminal activity and active-entry removal seam

`LiveActivityProjector` recognizes `TRACE_COMPLETED`, updates final outcome and
terminal usage, creates a completion activity, and returns that activity as
`heldTerminal` rather than ordinary immediately publishable activity
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/LiveActivityProjector.java:64-69`,
`:100-113`, `:423-428`).

`DefaultExecutionObservationHandle` stores the held completion. On successful
core close it requires and publishes that held completion. On failed core close
it discards the held completion and emits
`EXECUTION_OBSERVATION_ENDED` with reason `CORE_FINALIZATION_FAILED`
(`DefaultExecutionObservationHandle.java:69-83`, `:101-110`, `:130-169`).

In all close paths, active-entry removal is attempted in `finally`, after
terminal publication, and `heldCompletion` is cleared
(`DefaultExecutionObservationHandle.java:116-127`). Tests cover:

- held completion released on core success;
- held completion discarded on core failure;
- exactly-once close under concurrent conflicting calls;
- terminal publication failure still removing active state; and
- missing held completion failing live monitoring closed while still removing
  active state
  (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandleTest.java:25-78`,
  `:118-158`, `:233-295`).

The current `ObservationCompletionDisposition` contains only status, optional
trace outcome, and close time
(`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ObservationCompletionDisposition.java:9-24`).
It carries neither a finalized descriptor nor application availability facts.
The current completion activity therefore cannot report catalog publication,
catalog expiry, artifact expiry, or effective availability.

### 8. Existing registry ordinals and traversal pattern

The active-execution registry supplies the closest live implementation pattern
for PR 03's trace ordinals:

- it stores snapshots in a `ConcurrentHashMap` keyed by session ID;
- it assigns one strictly increasing positive ordinal when a session first
  enters;
- replacement preserves the existing ordinal;
- it records the assigned high-water value; and
- `newestFirst(highWaterMark, limit)` filters by the high water, sorts by
  ordinal descending, and limits the result
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActiveExecutionRegistry.java:11-37`,
  `:57-103`).

Tests cover stable ordinals, newest-first traversal, exhaustion rather than
wraparound, and concurrent independent updates
(`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActiveExecutionRegistryTest.java:16-64`).

No equivalent trace catalog exists. The phase design specifies name-ascending
skill traversal and newest-first trace traversal bounded by the first page's
highest `catalogOrdinal`; entries that expire between pages may be absent and no
snapshot or tombstone is created
(`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:293-313`).

### 9. TTL, expiration, scheduling, and shutdown state

No production code currently references `catalogOrdinal`,
`applicationTraceExpiresAt`, `trace-catalog-metadata-ttl`,
`completion-grace-ttl`, a finalized artifact descriptor, `TaskScheduler`,
`ScheduledExecutorService`, `ScheduledFuture`, `DelayQueue`, or `@Scheduled`.

The only existing `Duration` configuration in `BifrostProperties` is session
mission timeout (`BifrostProperties.java:238-256`). The generated configuration
metadata currently contains `bifrost.skills.locations`,
`bifrost.session.mission-timeout`, and `execution-trace.persistence`; it contains
no observability TTL properties
(`bifrost-spring-boot-starter/target/classes/META-INF/spring-configuration-metadata.json:148-151`,
`:184-193`).

The only explicitly destroyed executor in auto-configuration is
`bifrostMissionExecutor`, an `Executors.newVirtualThreadPerTaskExecutor()` bean
closed by Spring (`BifrostAutoConfiguration.java:322-327`). Trace handles are
not Spring beans and own no executor or close method. Observation registries and
replay buffers are plain in-memory objects and expose no shutdown method.

The phase design distinguishes:

- trace catalog metadata TTL, default `24h`, which removes discoverability and
  metadata but never artifact bytes;
- core completion grace, default `15m`, which delays normal deletion only for
  `NEVER` and successful `ONERROR`;
- no core grace expiration for `ALWAYS` or errored `ONERROR`;
- effective application availability at the earlier of catalog metadata expiry
  and core artifact expiry; and
- shutdown cancellation of pending grace deletion without special trace-file
  deletion or later-process adoption
  (`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:285-291`,
  `:315-319`).

Those distinctions are design inputs; none are current executable behavior.

### 10. Auto-configuration and activation state

`BifrostAutoConfiguration` enables `ExecutionTraceProperties` and
`BifrostProperties` and declares the runtime infrastructure beans directly
(`BifrostAutoConfiguration.java:79-85`). The class contains no
`@ConditionalOnMissingBean` methods. The architecture tests classify the
auto-configuration/property types as framework-integration types and explain
that internal public types such as `BifrostSessionRunner`,
`ExecutionTraceHandle`, `TracePersistencePolicy`,
`DefaultExecutionTraceHandle`, the observation classes, and `YamlSkillCatalog`
are public for cross-package internal collaboration, not ordinary application
API
(`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:38-42`,
`:61-79`, `:107-107`, `:163-199`).

The current session runner bean receives:

```java
new BifrostSessionRunner(
    maxDepth,
    executionTraceProperties.getPersistence(),
    Clock.systemUTC(),
    NoOpExecutionObservationHandleFactory.INSTANCE)
```

(`BifrostAutoConfiguration.java:123-133`).

Thus the PR 02 enabled observation classes exist and are tested, but ordinary
starter startup currently uses the no-op factory. The future PR 04 ticket owns
opt-in module activation and strict observability configuration
(`ai/thoughts/tickets/bifrost-console-pr-04-spring-rest-adapter.md:12-20`).
The phase design further states that activation is resolved before the runner
can create a session and supplies either enabled catalog/grace collaborators or
zero grace/no-op behavior
(`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:508-510`).

### 11. Configuration and documentation surfaces

Current deliberate configuration/manifest contracts affected by this area are:

- `bifrost.skills.locations`, including its default and arbitrary list of Spring
  resource patterns (`BifrostProperties.java:304-313`);
- YAML skill syntax, exact public skill name, validation, and registration
  semantics (`YamlSkillCatalog.java:93-108`, `:255-310`);
- `execution-trace.persistence`, including default `ONERROR`
  (`ExecutionTraceProperties.java:8-23`).

The root README documents skill locations and persistence configuration
(`README.md:49-82`, `:116`, `:268-272`). The sample uses both `.yml` and `.yaml`
patterns and `ALWAYS` persistence
(`bifrost-sample/src/main/resources/application.yml:34-37`, `:64-65`).
The sample README currently explains `ALWAYS` in terms of manual inspection by
the deprecated CLI (`bifrost-sample/README.md:143-149`).

The skill-authoring knowledge base states that exact YAML name is shared across
catalog/registry lookup, invocation, planning, evidence, metrics, journals, and
traces
(`ai/skill-authoring/mental-model.md:34-39`). Its trace guide classifies traces
as current-checkout/current-run diagnostics and documents that failed
finalization must not be inferred as a completed outcome
(`ai/skill-authoring/traces-and-debugging.md:8-18`, `:34-38`).
It does not currently document `sourcePath`, current-process catalog
discoverability, catalog TTL, or completion grace.

### 12. Contract classification

Using the repository's six-category framework design lens:

| Surface | Current classification evidence |
|---|---|
| `SkillTemplate`, `SkillExecutionView`, and application-facing skill invocation | **Application API**. These live in `com.lokiscale.bifrost.api`, are documented in the root README, and are protected by supported-surface tests. PR 03 does not change their behavior directly. |
| `BifrostSessionRunner`, `ExecutionTraceHandle`, `DefaultExecutionTraceHandle`, observation factories/handles, active registry, `YamlSkillCatalog`, and their constructors/beans | **Internal or accidentally exposed implementation**. Architecture-test allowlist explanations explicitly identify cross-package internal collaboration; public modifiers and Spring beans do not independently establish a supported API/SPI. |
| `bifrost.skills.locations`, YAML syntax/name semantics, and `execution-trace.persistence` | **Configuration and manifest contracts**. They are configuration-property bound, generated into metadata, documented, sampled, and covered by startup tests. |
| Canonical NDJSON `TraceRecord` files and terminal semantics | **Ephemeral diagnostic formats**. The framework lens and skill-authoring guide state that traces are current-run diagnostic representations, not durable cross-version history. |
| `ExecutionTrace` JSON annotations and internal file path | Technically serialized/internal diagnostic state, with no evidence that it is a deliberately supported durable or application protocol contract. |
| PR 03 catalog service models and finalized descriptor | Not present in the live codebase, so they have no current technical exposure, behavior, or existing consumers. The ticket and phase design classify their intended use as internal application-service infrastructure feeding later adapters. |

There is no evidence of a current **Supported SPI** for replacing skill
discovery, trace finalization, retention, observation, catalog storage, or
scheduling. `BifrostAutoConfiguration` does not back off these beans, and the
architecture tests describe the relevant public types as internal collaboration
surfaces.

No current **Persisted or serialized contract** promises cross-restart trace
catalog history. The phase design explicitly makes the catalog process-local
and empty after restart
(`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:259-267`).

### 13. Future PR consumers

PR 03's planned application services are reused by several later tickets:

- **PR 04** adapts skill, active execution, and trace catalog services to
  authenticated REST snapshots with opaque continuations. It owns routes,
  identity metadata, authentication, HTTP bounds, and protocol DTOs, all of
  which remain outside PR 03
  (`ai/thoughts/tickets/bifrost-console-pr-04-spring-rest-adapter.md:9-20`,
  `:23-30`).
- **PR 05** uses the terminal activity behavior and must leave skill and
  finalized-trace operations available if live monitoring fails
  (`ai/thoughts/tickets/bifrost-console-pr-05-live-sse-delivery.md:13-22`,
  `:32-37`).
- **PR 06** streams exact finalized NDJSON by opaque trace ID and preserves core
  retention ownership. Its acceptance condition requires an artifact reported
  available by terminal activity to already be obtainable
  (`ai/thoughts/tickets/bifrost-console-pr-06-artifact-streaming-integration.md:12-21`,
  `:24-38`).
- **PR 10** creates browser-facing transport-neutral services and UI for skill
  YAML, active executions, and trace browsing while preserving upstream keyset
  and high-water semantics. It treats YAML as untrusted text and `sourcePath`
  as non-filesystem metadata
  (`ai/thoughts/tickets/bifrost-console-pr-10-operational-views.md:9-20`,
  `:24-38`).
- **PR 12** acquires one Go-owned immutable copy with a separate handle,
  capacity, TTL, and removal lifecycle. It explicitly keeps application
  availability distinct from local-copy availability and does not adopt
  cross-process history
  (`ai/thoughts/tickets/bifrost-console-pr-12-artifact-service.md:9-25`,
  `:28-52`, `:61-63`).
- **PR 17** adapts the same shared skill/execution/activity services to MCP and
  does not maintain another skill catalog. Returned YAML is untrusted data
  (`ai/thoughts/tickets/bifrost-console-pr-17-mcp-runtime-inspection.md:9-27`,
  `:30-38`).
- **PR 18** exposes trace listing and progressive evidence queries through the
  same Go acquisition/analysis services used by the browser, with no MCP-owned
  catalog, cache, or lifecycle
  (`ai/thoughts/tickets/bifrost-console-pr-18-mcp-trace-inspection.md:9-28`,
  `:30-38`).
- **PR 19** uses skill and trace evidence in an agent debugging workflow, while
  explicitly treating `sourcePath` as neither a local locator nor a provenance
  claim
  (`ai/thoughts/tickets/bifrost-console-pr-19-debugging-skill.md:13-24`,
  `:27-38`).

The roadmap identifies "understand an unfamiliar nested skill path" as a
workflow spanning PR 03, PR 10, PRs 13-15, and PRs 17-19
(`ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:187-192`).

## Architecture Documentation

### Current skill flow

```text
bifrost.skills.locations
    -> ResourcePatternResolver.getResources(pattern)
    -> existing resources sorted by Resource URI/description
    -> resource stream parsed and validated
    -> YamlSkillDefinition(Resource, copied manifest, execution config, evidence)
    -> LinkedHashMap keyed by exact registered skill name
    -> capability registration and execution consumers
```

The exact resource remains reachable from the definition, but original YAML
text and a catalog-safe relative path are not materialized.

### Current trace and observation flow

```text
BifrostSessionRunner creates session
    -> observationHandleFactory.create(sessionId)
    -> traceHandleFactory.create(sessionId, policy, clock, observationHandle)
    -> core appends canonical NDJSON
       -> after complete append: observationHandle.recordAppended(logicalRecord)
    -> BifrostSession.finalizeTrace(completion)
       -> completed-journal projection
       -> append TRACE_COMPLETED
       -> synchronous persistence-policy deletion
       -> unlock session
       -> observationHandle.close(success/failure disposition)
          -> release held terminal activity or observation-ended activity
          -> remove active registry entry in finally
```

There is currently no descriptor/catalog step between successful core
finalization and observation close.

### Designed PR 03 ownership boundaries recorded in the roadmap

The Phase 1 design assigns canonical bytes to core and only bounded discovery
metadata to observability. The catalog is populated from core-issued
descriptors rather than filesystem discovery; restart starts empty; catalog
expiry affects discoverability only
(`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:259-291`).

It also specifies procedural completion ordering: successful core finalization,
conditional descriptor publication, one availability-enriched completion
activity, exceptional observation-ended activity on core failure, and guaranteed
active removal after terminal publication attempt
(`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:323-339`).

## Code References

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:55-57` — current skill and diagnostic maps.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:93-143` — startup registration, exact-name uniqueness, discovery, and resource ordering.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:255-310` — resource stream parsing.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:987-996` — URI/description resource diagnostics.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillDefinition.java:15-25` — retained definition state.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostProperties.java:304-313` — skill location configuration.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionTraceHandle.java:8-27` — current trace handle operations and void finalization.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:240-264` — completion append and synchronous cleanup.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:389-424` — retention decision, visible path, and file naming.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java:703-818` — finalization, failure handling, unlock, and observation close.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java:42-128` — held terminal publication and guaranteed active removal.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActiveExecutionRegistry.java:28-103` — current ordinal/high-water traversal pattern.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:123-140` — no-op observation wiring and skill catalog bean.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:322-327` — current executor shutdown ownership.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionTraceHandleTest.java:28-45` — current policy deletion behavior.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/core/BifrostSessionRunnerTest.java:279-437` — observation attachment and exceptional finalization dispositions.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandleTest.java:25-295` — terminal and cleanup lifecycle coverage.

## Historical Context (from `ai/thoughts/`)

- `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:203-209`
  defines exact skill catalog identity, relative source metadata, unchanged YAML,
  and the absence of an effective-definition DTO.
- `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:259-291`
  separates current-process catalog metadata from core-owned trace retention and
  defines catalog ordinals and age-bounded metadata.
- `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:293-319`
  defines shared keyset/high-water behavior, completion grace, effective
  expiration, and shutdown semantics.
- `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:323-339`
  defines the procedural terminal coordination rule reused by PR 03.
- `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:498-510`
  records exact-file ownership, lack of scanning/adoption, and enabled/disabled
  activation behavior.
- `ai/thoughts/framework-feature-design-lens.md` classifies configuration,
  manifests, ephemeral traces, and internal technical exposure and states that
  public modifiers, constructors, beans, or tests alone do not establish a
  supported contract.
- No earlier research documents were present in `ai/thoughts/research/` at the
  time of this research.

## Related Research

No existing related research artifacts were present. The directly related
planning inputs are:

- `ai/thoughts/tickets/bifrost-console-pr-02-observation-lifecycle.md`
- `ai/thoughts/tickets/bifrost-console-pr-03-observability-catalogs.md`
- `ai/thoughts/tickets/bifrost-console-pr-04-spring-rest-adapter.md`
- `ai/thoughts/tickets/bifrost-console-pr-06-artifact-streaming-integration.md`
- `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md`
- `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md`

## Verification

The focused existing test suite was run against the researched commit:

```text
.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCatalogTests,ExecutionTraceHandleTest,BifrostSessionRunnerTest,DefaultExecutionObservationHandleTest,InMemoryActiveExecutionRegistryTest" test
```

Result: 127 tests run, 0 failures, 0 errors, 0 skipped; Maven reported
`BUILD SUCCESS`. These tests cover the existing skill discovery/validation
behavior, persistence-policy cleanup, append publication boundary, core
finalization dispositions, exact-once terminal close, guaranteed active-entry
removal, and active-registry ordinal traversal documented above.

## Open Questions

These points are not answered by current executable code:

1. Configured skill discovery locations are arbitrary Spring resource patterns,
   while the designed `sourcePath` is relative to a "skills root." The current
   configuration model does not identify or store that root separately.
2. A `YamlSkillDefinition` currently retains only a reloadable `Resource` plus
   parsed state. Current code does not establish whether unchanged catalog YAML
   is captured during startup or reread later.
3. `ExecutionTraceHandle.finalizeTrace` returns no descriptor, and the current
   session finalizer retains only success/failure variables. No live type
   establishes the exact descriptor fields or how it is conveyed to the
   observation/catalog coordinator.
4. The phase design defines `24h` catalog metadata TTL and `15m` completion
   grace defaults, but no current property class establishes their validation
   ranges or accepted zero semantics.
5. No scheduler exists, so current code does not establish cleanup cadence,
   bounded catalog cleanup lag, delayed-deletion task ownership, or cancellation
   behavior.
6. Current finalization runs under the session lock and synchronous deletion is
   part of that operation. No executable behavior yet establishes where
   descriptor sizing, catalog publication, or delayed-retention scheduling
   occurs relative to the lock.
7. The enabled PR 02 observation factory is not wired by current
   auto-configuration. The exact PR 03/PR 04 bean composition and activation
   boundary does not exist yet.
8. No catalog query service exists, so method-level behavior for expiry races,
   direct lookup, high-water traversal after removal, and ordinal exhaustion is
   not yet executable.
