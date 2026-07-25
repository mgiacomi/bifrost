# Bifrost Console PR 03 — Skill and Finalized-Trace Catalogs Implementation Plan

## Overview

Implement the transport-neutral registered-skill and current-process finalized-trace catalog services required by later Bifrost Console adapters. The work captures the exact YAML bytes loaded at startup, derives safe descriptive skill source paths, adds a core-issued finalized-artifact descriptor with optional completion-grace retention, and coordinates catalog publication with one truthful terminal activity and guaranteed active-entry removal.

PR 03 does not activate application observability in the starter or expose an HTTP contract. PR 04 will own opt-in Spring activation, property binding and defaults, bean composition, opaque cursor encoding, response bounds, and REST DTOs.

## Current State Analysis

The repository is at commit `a35b9c5c63930d40bc873a25ef878648ff2362b3`, with PR 01 trace semantics and PR 02 observation lifecycle already present.

- `YamlSkillCatalog` discovers resources from arbitrary `bifrost.skills.locations` patterns, sorts them by resource description, parses each resource stream, and stores definitions by exact registered name. It does not retain the matched location, original bytes, or a safe relative path (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java:55-143`, `:255-310`).
- `YamlSkillDefinition` retains the Spring `Resource`, copied manifest, execution configuration, and evidence contract, but no immutable source representation (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillDefinition.java:15-25`).
- `ExecutionTraceHandle.finalizeTrace` returns `void`. `DefaultExecutionTraceHandle` appends `TRACE_COMPLETED` and synchronously deletes `NEVER` or successful `ONERROR` files (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionTraceHandle.java:19-27`; `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:240-264`, `:389-424`).
- `BifrostSession.finalizeTrace` performs completed-journal projection and core finalization under the session lock, releases that lock, and then closes the observation handle with a success/failure disposition (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java:703-818`).
- `DefaultExecutionObservationHandle` already holds `TRACE_COMPLETED`, publishes exactly one terminal activity after core disposition is known, and removes active state in `finally`, but it receives no finalized descriptor and has no trace catalog collaborator (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java:42-128`).
- `InMemoryActiveExecutionRegistry` supplies the existing positive, monotonic, exhaustion-safe ordinal and high-water traversal pattern (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/InMemoryActiveExecutionRegistry.java:28-103`).
- Starter auto-configuration still supplies `NoOpExecutionObservationHandleFactory`; no catalog, grace scheduler, or observability property beans are active (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:123-140`).

## Desired End State

After PR 03:

1. An explicitly constructed registered-skill catalog exposes immutable entries keyed by exact registered skill name, sorted by name ascending, with unchanged startup-captured UTF-8 YAML and a normalized descriptive `sourcePath`.
2. Each configured skill location defines its descriptive root as the non-pattern prefix used by Spring resource discovery. Matched resources are relativized against that concrete root; exact-file locations use the filename. Unsafe or unrelativizable paths fail optional observability catalog construction, not ordinary disabled-mode skill loading.
3. Successful core finalization returns an optional immutable descriptor for the exact retained artifact. The descriptor contains opaque identities, outcome, finalized time, exact internal path, byte size, persistence policy, and optional core artifact expiration.
4. A nonzero completion grace delays normal deletion only for `NEVER` and successful `ONERROR`. Zero preserves current synchronous behavior. `ALWAYS` and errored `ONERROR` have no core expiration.
5. The current-process trace catalog accepts only core-issued descriptors, assigns monotonic catalog ordinals, excludes expired entries immediately from lookup and traversal, removes metadata on a bounded schedule, and never deletes artifact bytes.
6. Successful completion publishes a descriptor before releasing one availability-enriched completion activity. Core finalization failure cannot publish a catalog entry and instead releases the existing noncanonical observation-ended activity with unavailable trace facts. Active state is removed after terminal publication is attempted on every path.
7. The services expose transport-neutral lookup and keyset traversal inputs. PR 04 can encode those positions into authenticated opaque cursors without changing catalog semantics.
8. Disabled starter behavior remains unchanged: zero grace, immediate existing persistence-policy cleanup, no enabled catalog consumer, and no observability routes.

### Key Discoveries

- Skill discovery order is resource-description order, while the new inspection catalog must independently traverse by registered name (`YamlSkillCatalogTests.java:331-342`; Phase 1 design: `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:203-209`, `:309-310`).
- The exact resource stream is currently opened only for parsing, so startup capture must feed both parsing and later catalog content to avoid rereading a mutable resource.
- A logical `TRACE_COMPLETED` publication proves its append succeeded but not that the entire core finalization and retention decision returned successfully; descriptor publication must therefore occur through the post-finalization close disposition, not the canonical append callback.
- The session lock already ends before observation close. Descriptor creation and the core retention decision belong in the synchronized trace finalizer; catalog publication and activity enrichment belong after the session lock is released.
- Trace catalog metadata expiration and core artifact expiration are independent. Effective application availability is the earlier instant (`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:285-319`).
- Later PRs depend on this separation: PR 04 adapts the services to REST, PR 06 streams the descriptor’s exact file, PR 10 preserves keyset/high-water semantics, PR 12 creates a separately owned Go copy, and PRs 17-19 reuse the same skill and trace facts without treating `sourcePath` as a filesystem locator.

## Resolved Design Decisions

The research document’s open questions are resolved as follows:

1. **Skill root:** each configured location pattern’s non-pattern prefix is its descriptive root. Use Spring’s resolved concrete root when relativizing file, classpath, or JAR resources. For a location without a pattern, use the matched resource filename.
2. **YAML capture:** read the resource once during startup into an immutable byte copy; parse that same copy. Decode strictly as UTF-8 only when constructing the optional observability catalog, preserving disabled-mode loading for any currently accepted resource while ensuring the exposed representation is valid UTF-8.
3. **Descriptor:** core finalization returns `Optional<FinalizedTraceArtifact>`; absence means the policy and zero grace left no retained file. The descriptor carries `traceId`, `sessionId`, `outcome`, `finalizedAt`, internal `Path`, `sizeBytes`, `persistencePolicy`, and nullable `artifactExpiresAt`.
4. **Duration semantics:** trace-catalog metadata TTL must be positive; completion grace must be nonnegative and explicitly permits zero. PR 04 binds the settled `24h` and `15m` defaults and performs startup property validation.
5. **Scheduling:** core grace uses one lifecycle-owned scheduled executor and one cancellable task per grace-held artifact. The trace catalog owns one periodic metadata sweep, with lookup and traversal also filtering against the injected clock so cleanup lag never extends visible availability.
6. **Lock boundary:** descriptor construction, byte sizing, and the core retention decision occur inside the synchronized trace finalizer. Catalog publication and terminal enrichment occur after `BifrostSession` releases its lock.
7. **Activation:** PR 03 adds composable services and disabled-mode defaults only. PR 04 resolves activation once at startup and constructs either the enabled collaborators or the existing no-op/zero-grace path.
8. **Traversal races:** registered skills are immutable and traverse name ascending after an exclusive name. Traces traverse newest first below a first-page high-water ordinal and an exclusive prior-page ordinal. Expired entries can disappear between pages; no snapshot, offset, tombstone, or server-side pagination session is created.

## What We’re NOT Doing

- Adding `bifrost.observability.*` property classes, defaults, or generated configuration metadata; PR 04 owns them.
- Wiring enabled observation/catalog beans into `BifrostAutoConfiguration`; PR 04 owns activation and composition.
- Adding REST routes, HTTP DTOs, opaque cursor encoding, page-size/JSON response limits, authentication, instance identity, or problem responses.
- Streaming or parsing artifact bytes, opening downloads, or adding download admission; PR 06 owns that boundary.
- Scanning trace storage, adopting files from prior processes, creating durable history, tombstones, deletion journals, startup scavenging, or cross-process cleanup.
- Moving, copying, rewriting, redacting, hashing, packaging, or otherwise taking ownership of canonical trace files.
- Adding an effective-definition DTO, parsing YAML for console consumers, exposing resolved model/Java implementation state, or resolving caller-supplied `sourcePath`.
- Adding catalog cardinality or aggregate-memory caps beyond the settled age-based trace metadata lifecycle.
- Changing canonical NDJSON records, Java/Go consumed semantics, trace schema fixtures, or the release-derived compatibility contract.
- Adding a public observer SPI, replacement beans, compatibility aliases, deprecated paths, or dual old/new finalization behavior.

## Skill-Authoring Documentation Impact

**Impact**: No impact

- **Rationale**: PR 03 adds internal transport-neutral catalog and retention infrastructure but does not activate it, add manifest syntax, alter skill validation, change execution/planning semantics, or expose a supported author-facing configuration. Exact registered skill identity and current-run trace semantics remain unchanged. The user-facing observability configuration and operational guidance become real only when PR 04 activates the module and PR 06 completes artifact streaming.
- **Documents to update**: None.
- **Supporting evidence**: `YamlSkillCatalogTests` continues to protect exact case-sensitive names and existing manifest behavior; `ExecutionTraceHandleTest`, `BifrostSessionRunnerTest`, and `DefaultExecutionObservationHandleTest` protect current trace, finalization, and failure semantics. New PR 03 tests establish internal catalog behavior without presenting it as an author-facing capability.
- **Coverage table update**: Not required. No topic is added and no existing authoring topic’s current-checkout coverage or confidence changes.
- **LLM-first usability**: Not applicable for PR 03. PR 04/PR 06 planning must reassess and document the activated source-path, catalog-lifetime, persistence, and debugging behavior when it becomes author/operator accessible.

## Contract and Compatibility Impact

| Surface | Classification and supporting evidence | Planned compatibility treatment |
| --- | --- | --- |
| Application API | No affected types in `com.lokiscale.bifrost.api`; `SkillTemplate`, `SkillExecutionView`, and invocation behavior remain unchanged. | Preserve. No new application API. |
| Supported SPI | No supported replacement point exists for discovery, trace finalization, observation, retention, catalogs, or scheduling. Architecture tests classify current public internal types as cross-package implementation seams. | No SPI added. Do not add `@ConditionalOnMissingBean` or application replacement hooks. |
| Configuration and manifest contracts | Existing `bifrost.skills.locations`, exact YAML name/validation semantics, and `execution-trace.persistence` remain deliberate contracts. PR 03 adds no bound properties. Optional catalog construction may reject unsafe `sourcePath` derivation, but ordinary disabled-mode skill loading remains unchanged. Zero grace preserves existing persistence behavior. | Preserve existing configuration and manifest behavior. PR 04 will add and document positive metadata-TTL and nonnegative grace-TTL properties atomically. |
| Persisted or serialized contracts | No durable catalog or cross-restart history exists or is added. `FinalizedTraceArtifact` and catalog entries are process-local internal objects. | No impact. Do not add migration, persistence, or compatibility machinery. |
| Ephemeral diagnostic formats | Canonical NDJSON bytes and record semantics do not change. Retention timing can differ only when a future enabled caller supplies nonzero grace; current-run accuracy, ordering, failure visibility, and exact bytes remain intact. | Preserve current writer/reader/projector coherence. No schema or fixture change in PR 03. |
| Internal or accidentally exposed implementation | `ExecutionTraceHandle`, `DefaultExecutionTraceHandle`, `BifrostSession`, observation disposition/handle/factory, `YamlSkillCatalog`, and `YamlSkillDefinition` change atomically. New catalog, descriptor, and scheduler types are internal even if Java visibility is required across packages. | Intentional atomic internal change. Update all repository callers/tests; do not retain obsolete signatures solely for compatibility. |

- **Evidence of supported contracts**: Root README documentation, generated configuration metadata, supported-surface tests, YAML catalog tests, and the explicit architecture allowlist. Public modifiers and constructors on internal classes are not independent compatibility evidence.
- **Intended breaks**: Internal `ExecutionTraceHandle.finalizeTrace(Map<String,Object>)` is replaced by typed finalization returning an optional descriptor. Observation completion disposition gains the optional descriptor. Internal constructors/factories are updated to accept retention/catalog collaborators. No supported Application API, SPI, configuration, manifest, or durable serialized contract breaks.
- **In-repository consumers to update**: `BifrostSession`, `BifrostSessionRunner`, `DefaultExecutionTraceHandle`, direct test doubles and constructor call sites, observation factory/handle tests, YAML catalog tests, architecture allowlist explanations, and any fixture generator that directly finalizes a trace. Canonical NDJSON fixtures do not change.
- **Public-surface delta**: New public-for-internal-collaboration types may include `FinalizedTraceArtifact`, registered-skill catalog interfaces/models, finalized-trace catalog interfaces/models, and lifecycle collaborators. Existing internal public finalization signatures change. No type is added to `com.lokiscale.bifrost.api`, and no Spring extension point or replaceable bean is added.
- **Shim decision**: **No shim.** There is no protected consumer for the current internal finalization signature or constructors. Update all repository callers atomically. Retain only constructors deliberately required for the coherent disabled zero-grace/no-op runtime, not aliases that maintain two finalization models.
- **Java-to-Go boundary coordination**: **Not required.** PR 03 does not add or change REST, SSE, acquisition, problem, or consumed-NDJSON contracts. PR 04 and PR 06 will map these internal services into the coordinated Java-to-Go boundary.

## Implementation Approach

Keep core ownership and optional observability coordination explicit:

```text
configured skill location + matched Resource
    -> capture exact startup bytes and discovery origin
    -> existing manifest parse/validation
    -> optional RegisteredSkillCatalog construction
       -> strict UTF-8 decode
       -> safe sourcePath derivation
       -> name-ascending lookup/traversal

BifrostSession.finalizeTrace
    -> completed-journal projection under session lock
    -> core trace finalization
       -> append TRACE_COMPLETED
       -> immediate delete, delayed core delete, or retain
       -> Optional<FinalizedTraceArtifact>
    -> release session lock
    -> observation close with core disposition + optional descriptor
       -> publish descriptor to current-process trace catalog
       -> enrich and release exactly one terminal activity
       -> remove active entry in finally
```

Catalog and activity failures remain optional and isolated. Core append, journal projection, synchronous zero-grace deletion, and core finalization failures retain their existing propagation/suppression behavior.

## Phase 1: Capture and Expose Registered Skill Sources

### Overview

Extend skill discovery so the same immutable startup bytes used for manifest parsing can later support a safe transport-neutral catalog. Keep existing skill registration identity and ordering behavior intact, while the inspection service provides its own name-ascending keyset traversal.

### Changes Required

#### 1. Preserve discovery origin and exact source bytes

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillDefinition.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/DiscoveredYamlSkillResource.java` (new, package-private)

**Changes**:

- Change discovery results from bare `Resource` values to an immutable pair containing the configured location pattern and matched resource.
- Preserve the current outer location iteration, global resource-description sort, duplicate-name rejection, and exact-name map behavior.
- Read each resource once into a byte array, parse the manifest from those same bytes, and retain a defensive immutable copy plus the discovery origin in `YamlSkillDefinition`.
- Do not decode or normalize the bytes while capturing them. Existing core manifest parsing remains authoritative and ordinary disabled-mode startup must not depend on observability source-path construction.
- Avoid a mutable array record accessor: use defensive copies on construction and access.

```java
final class DiscoveredYamlSkillResource {
    Resource resource();
    String locationPattern();
}

public final class YamlSkillSource {
    Resource resource();
    String locationPattern();
    byte[] bytes(); // defensive copy
}
```

#### 2. Add safe source-path derivation

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/SkillSourcePathResolver.java` (new)

**Changes**:

- Determine the descriptive root from the configured pattern’s non-pattern prefix using the same Spring resource-pattern rules used for discovery.
- Relativize the concrete matched resource URI against a concrete resolved root URI. For `classpath*:` roots, choose the longest matching concrete root so JAR and exploded-classpath resources produce the same relative form.
- For an exact-resource location with no pattern, use only `Resource.getFilename()`.
- Normalize `\` to `/` and reject empty paths, schemes, drive prefixes, leading slashes, empty segments, `.` segments, and `..` segments.
- Never expose the configured root, absolute URI, JAR location, or filesystem path.
- Throw a focused catalog-construction exception when safe relativization is impossible. PR 04 will treat that as optional observability activation failure and fall back to zero grace/no-op behavior; PR 03 must not change ordinary `YamlSkillCatalog` startup because the adapter is not yet activated.

#### 3. Add the registered-skill catalog service

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/RegisteredSkillCatalog.java` (new)
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/RegisteredSkillFile.java` (new)
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/DefaultRegisteredSkillCatalog.java` (new)

**Changes**:

- Build one immutable entry per successfully registered `YamlSkillDefinition`.
- Decode the retained bytes with a strict UTF-8 decoder that reports malformed or unmappable input; retain the resulting string without line-ending, whitespace, key-order, or formatting changes.
- Key lookup only by exact registered skill name. `sourcePath` is output metadata and is never accepted as a lookup key.
- Sort traversal by registered name ascending and resume strictly after an optional exclusive name.
- Allow duplicate `sourcePath` values. Duplicate registered names remain rejected by the existing core catalog.
- Keep list entries bounded to name and `sourcePath`; detail lookup supplies unchanged YAML. Do not construct an effective definition or expose the Spring resource.

```java
public interface RegisteredSkillCatalog {
    Optional<RegisteredSkillFile> find(String registeredName);
    List<RegisteredSkillFile.Summary> listAfter(@Nullable String exclusiveName, int limit);
}
```

### Success Criteria

#### Automated Verification

- [x] Existing skill discovery ordering, duplicate-name, exact-name, validation, and registration tests remain green.
- [x] New tests prove the manifest parser and inspection catalog use the same captured startup bytes even if the backing resource later changes or becomes unreadable.
- [x] New tests preserve BOMs, line endings, comments, whitespace, and key ordering in valid UTF-8 catalog text.
- [x] Invalid UTF-8 or unsafe/unrelativizable source metadata fails catalog construction without changing ordinary skill parsing when the optional catalog is not constructed.
- [x] Classpath, `classpath*:`, JAR-style, filesystem, wildcard, and exact-file locations produce normalized root-relative paths.
- [x] Duplicate `sourcePath` with distinct names remains valid; exact registered name remains identity.
- [x] Name-ascending traversal, exclusive continuation, invalid limits, empty catalogs, and direct lookup are deterministic.
- [x] Focused tests pass:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCatalogTests,DefaultRegisteredSkillCatalogTest,SkillSourcePathResolverTest" test`

#### Manual Verification

- [ ] Inspect representative classpath and filesystem catalog entries and confirm no scheme, drive, configured root, absolute path, `.` segment, or `..` segment is exposed.
- [ ] Compare returned YAML text with the loaded file and confirm formatting and line endings are unchanged.

---

## Phase 2: Return Finalized Artifacts and Apply Core-Owned Completion Grace

### Overview

Replace void internal finalization with one typed successful result and introduce a narrowly scoped core retention scheduler. Preserve current behavior exactly when grace is zero or observability is disabled.

### Changes Required

#### 1. Define the finalized-artifact descriptor

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/FinalizedTraceArtifact.java` (new)
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionTraceHandle.java`

**Changes**:

- Add an immutable descriptor with validated opaque identities, outcome, final completion timestamp, exact internal path, nonnegative byte size, persistence policy, and nullable core artifact expiration.
- Change finalization to accept the typed `TraceCompletion` and return `Optional<FinalizedTraceArtifact>`.
- Return empty only after successful finalization when the normal policy and zero grace leave no retained file.
- Do not expose this descriptor as an application API or serialize it directly.

```java
Optional<FinalizedTraceArtifact> finalizeTrace(TraceCompletion completion) throws IOException;
```

#### 2. Add lifecycle-owned delayed deletion

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/CompletionGraceRetention.java` (new)
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/ScheduledCompletionGraceRetention.java` (new)
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/ImmediateCompletionRetention.java` (new or no-op singleton)

**Changes**:

- Validate grace as nonnegative and ensure `now + grace` is representable.
- For `NEVER` and successful `ONERROR`:
  - grace `0`: perform the existing synchronous exact-path deletion and return no descriptor;
  - grace `> 0`: retain the exact file, schedule one exact-path deletion at expiration, and return a descriptor containing that expiration.
- For `ALWAYS` and errored `ONERROR`, do not schedule deletion and return a descriptor with no core expiration.
- Use one owned single-thread scheduled executor. Tasks capture only the exact core-created path and safe opaque IDs; no directory scans, globs, recursion, or catalog callbacks.
- On later deletion failure, log one sanitized diagnostic without retry and without changing the already returned execution result.
- On scheduler rejection during finalization, fall back to the existing immediate deletion path. If that exact deletion succeeds, return no descriptor; if it fails, preserve the existing core finalization failure behavior.
- `close()` cancels pending work and closes the executor without deleting grace-held files. It does not drain tasks or scan for leftovers.

#### 3. Produce the descriptor within core finalization

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSessionRunner.java`
- Direct internal constructor/factory call sites in production and tests

**Changes**:

- Append `TRACE_COMPLETED` from typed completion metadata and use that record’s timestamp as `finalizedAt`.
- Mark completion, perform or schedule the policy-owned retention decision, and obtain `Files.size` only for a file being described.
- Complete descriptor creation before returning from the synchronized trace handle.
- Retain the optional descriptor through `BifrostSession.finalizeTrace`, release the session lock, and pass it only on a successful core completion disposition.
- Preserve projection failure conversion, finalization failure propagation, suppressed exceptions, finalized-journal assignment, idempotent completion, and observation failure isolation.
- Update the runner’s internal trace-handle factory composition so disabled/default construction uses zero grace and immediate retention. Do not wire a nonzero scheduler bean in PR 03.

### Success Criteria

#### Automated Verification

- [x] `NEVER`, successful `ONERROR`, errored `ONERROR`, and `ALWAYS` behavior remains unchanged at zero grace.
- [x] Nonzero grace returns descriptors only for files that exist and reports the correct outcome, size, finalized time, policy, and optional expiration.
- [x] Grace applies only to `NEVER` and successful `ONERROR`; `ALWAYS` and errored `ONERROR` have no core expiration.
- [x] Delayed deletion targets only the exact descriptor path, runs at expiration, and never traverses sibling or parent paths.
- [x] A later deletion failure is sanitized, not retried, and cannot change an already completed call.
- [x] Scheduler rejection falls back to synchronous deletion and preserves existing deletion failure propagation.
- [x] Shutdown cancels pending tasks without special deletion; a later test process does not adopt the leftover file.
- [x] Finalization failures return no descriptor and retain current propagation/suppression semantics.
- [x] Existing fixture generation produces byte-equivalent canonical NDJSON because no trace record shape changes.
- [x] Focused tests pass:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=ExecutionTraceHandleTest,ScheduledCompletionGraceRetentionTest,BifrostSessionTest,BifrostSessionRunnerTest,ConsoleTraceFixtureCorpusTest" test`

#### Manual Verification

- [ ] With a short nonzero test grace, observe a `NEVER` trace remain present until expiration and disappear afterward.
- [ ] Stop the owning scheduler before expiration and confirm the exact file is left in place and is not adopted on a subsequent process start.

---

## Phase 3: Add the Current-Process Trace Catalog and Terminal Coordination

### Overview

Create the age-bounded trace metadata service and extend the existing observation close seam so it publishes a trustworthy descriptor before releasing exactly one availability-enriched terminal activity.

### Changes Required

#### 1. Define catalog entries and traversal

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/FinalizedTraceCatalog.java` (new)
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/FinalizedTraceCatalogEntry.java` (new)
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/TraceCatalogSlice.java` (new)
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/catalog/InMemoryFinalizedTraceCatalog.java` (new)

**Changes**:

- Require a positive metadata TTL and injected `Clock`.
- On successful publication, verify the descriptor’s exact file is still a regular obtainable file, assign one positive strictly increasing `catalogOrdinal`, and compute:
  - `catalogExpiresAt = publicationTime + metadataTtl`;
  - `applicationTraceExpiresAt = min(catalogExpiresAt, artifactExpiresAt)` when the core expiration exists;
  - otherwise `applicationTraceExpiresAt = catalogExpiresAt`.
- Retain only bounded metadata: trace/session IDs, outcome, finalization/publication times, policy, size, ordinals, expiration facts, and the internal exact artifact reference needed by PR 06. Do not retain records, payloads, indexes, growing history, or a deletion callback.
- Make publication idempotent for the same trace ID and identical descriptor without assigning another ordinal. Reject a conflicting descriptor for an existing trace ID.
- Follow the active-registry exhaustion rule: ordinals are positive, unique, strictly increasing, and fail rather than wrap.
- Provide direct lookup by opaque trace ID and newest-first keyset traversal with:
  - a first-page captured high-water ordinal;
  - an exclusive `beforeOrdinal` for later pages;
  - no entries above the first-page high water;
  - immediate expiration filtering;
  - no offsets, copied snapshots, tombstones, or server-side sessions.
- A missing or expired lookup returns empty and does not reveal whether the trace once existed.

#### 2. Add bounded-lag metadata cleanup

**File**: `InMemoryFinalizedTraceCatalog.java`

**Changes**:

- Own one periodic cleanup task, not one task per entry.
- Derive the sweep interval as the smaller of the metadata TTL and one minute, with a one-second minimum for positive sub-second/test TTLs unless a package-private injected test schedule is supplied.
- Every lookup/list call checks the injected clock before returning, so a sweep delay never extends supported visibility.
- The sweep removes only metadata whose effective application expiration has passed. It never deletes or modifies the core artifact.
- `close()` cancels the sweep, closes its executor, and clears process-local metadata without touching files.

#### 3. Carry descriptors through observation close

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ObservationCompletionDisposition.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandleFactory.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/DefaultExecutionObservationHandle.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/observation/ExecutionActivity.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java`

**Changes**:

- Add an optional finalized descriptor to successful observation disposition; require it to be absent for core-finalization failure.
- Supply a `FinalizedTraceCatalog` collaborator to enabled handles. The no-op factory and disabled runner remain catalog-free.
- On successful close:
  1. require the held canonical completion;
  2. if no descriptor exists, enrich it as `UNAVAILABLE` with reason `NOT_RETAINED`;
  3. if a descriptor exists, publish it synchronously to the in-memory catalog;
  4. only after publication succeeds, enrich as `AVAILABLE` with `applicationTraceExpiresAt`;
  5. if catalog publication fails, log a sanitized diagnostic and enrich as `UNAVAILABLE` with `CATALOG_PUBLICATION_FAILED`;
  6. publish exactly one enriched terminal activity.
- On core-finalization failure, discard the held canonical completion and emit `EXECUTION_OBSERVATION_ENDED` with `applicationTraceAvailability=UNAVAILABLE`, reason `CORE_FINALIZATION_FAILED`, and no descriptor.
- Keep catalog failure independent from `liveMonitoringAvailable`; the terminal activity can still truthfully report unavailability. Failure to project or append the terminal activity continues to fail live monitoring closed.
- Remove active state in `finally` after terminal publication is attempted, even when catalog publication, terminal enrichment, or replay publication fails.
- Add a focused immutable enrichment method that recomputes retained weight and enforces existing bounded details; do not mutate activity details in place or introduce a second availability event.

### Success Criteria

#### Automated Verification

- [x] A fresh catalog is empty and no startup scan or adoption API exists.
- [x] Publication assigns stable increasing ordinals, preserves high-water traversal, rejects exhaustion/wraparound, and remains safe under concurrent independent completions.
- [x] Direct lookup and traversal exclude entries at the exact effective expiration even before the cleanup sweep runs.
- [x] Catalog expiration removes metadata only; core-retained `ALWAYS` and errored `ONERROR` files remain untouched.
- [x] Core expiration earlier than metadata TTL becomes the effective expiration; metadata TTL earlier than core expiration also becomes effective.
- [x] Entries expiring between pages may disappear without shifting in newer entries, snapshots, or tombstones.
- [x] An identical duplicate publication is idempotent; a conflicting duplicate trace ID fails without replacing the original entry.
- [x] An available completion activity is emitted only after catalog lookup can obtain the same trace entry.
- [x] Zero-grace nonretention emits one completion activity with `UNAVAILABLE/NOT_RETAINED` and no catalog entry.
- [x] Catalog publication failure emits one completion activity with `UNAVAILABLE/CATALOG_PUBLICATION_FAILED`, does not fail execution, does not mark live monitoring unavailable, and still removes active state.
- [x] Core finalization failure emits only `EXECUTION_OBSERVATION_ENDED`, publishes no catalog entry, and still removes active state.
- [x] Terminal replay publication failure still fails live monitoring closed and removes active state.
- [x] Concurrent conflicting close calls still produce at most one terminal activity and at most one catalog entry.
- [x] Focused tests pass:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=InMemoryFinalizedTraceCatalogTest,DefaultExecutionObservationHandleTest,ExecutionObservationConcurrencyTest,BifrostSessionRunnerTest" test`

#### Manual Verification

- [ ] Complete representative successful and failed executions under each persistence policy and inspect that outcome and availability remain separate facts.
- [ ] Allow catalog metadata to expire while an `ALWAYS` artifact remains on disk; confirm catalog lookup is unavailable without deleting the file.

---

## Phase 4: Integration, Architecture Boundaries, and Documentation Review

### Overview

Verify the combined lifecycle as one coherent internal feature, update internal boundary documentation, and leave a clean handoff for PR 04 without prematurely documenting inactive configuration or transport.

### Changes Required

#### 1. Add end-to-end internal lifecycle coverage

**Files**:

- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/observation/ObservabilityCatalogLifecycleTest.java` (new)
- Existing focused test classes updated in Phases 1-3

**Changes**:

- Exercise the full path from session finalization through descriptor creation, catalog publication, enriched terminal replay, active removal, TTL expiry, and core grace deletion using deterministic clocks and controllable schedulers.
- Cover success, execution failure with retained `ONERROR`, zero-grace nonretention, projection/finalization failure, catalog failure, terminal publication failure, shutdown-before-grace, and concurrent close.
- Assert absence of filesystem scanning and catalog-owned deletion by using isolated directories with sibling sentinel files.
- Keep Java/Go golden fixture content unchanged and assert no serialized trace schema delta.

#### 2. Update internal public-surface classification

**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java`

**Changes**:

- Add every technically public cross-package descriptor, catalog, and retention type to the internal allowlist with a precise framework-owned collaboration rationale.
- Assert none enters `com.lokiscale.bifrost.api`, becomes a supported SPI, or leaks through a supported Application API signature.
- Preserve the rule that auto-configuration has no new replacement bean or `@ConditionalOnMissingBean` seam.

#### 3. Keep user-facing documentation deferred

**Files**:

- `README.md` — no PR 03 content change expected
- `bifrost-sample/README.md` — no PR 03 content change expected
- `bifrost-sample/src/main/resources/application.yml` — no PR 03 content change expected
- `ai/skill-authoring/` — no PR 03 content change expected

**Changes**:

- Verify no inactive `bifrost.observability.*` setting, route, or supported operator workflow is documented prematurely.
- Record in PR 04/PR 06 handoff notes that activation/configuration and completed artifact-access guidance must reassess the skill-authoring impact and update operational docs when executable.

### Success Criteria

#### Automated Verification

- [x] Focused PR 03 suite passes:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter "-Dtest=YamlSkillCatalogTests,DefaultRegisteredSkillCatalogTest,SkillSourcePathResolverTest,ExecutionTraceHandleTest,ScheduledCompletionGraceRetentionTest,InMemoryFinalizedTraceCatalogTest,DefaultExecutionObservationHandleTest,ExecutionObservationConcurrencyTest,ObservabilityCatalogLifecycleTest,BifrostSessionTest,BifrostSessionRunnerTest,ConsoleTraceFixtureCorpusTest,BifrostPublicSurfaceArchitectureTest" test`
- [x] Full starter module passes: `.\mvnw.cmd -pl bifrost-spring-boot-starter test`
- [x] Full repository verification passes: `.\mvnw.cmd verify`
- [x] Architecture tests confirm no accidental Application API, Supported SPI, or Spring replacement surface.
- [x] Fixture tests confirm canonical NDJSON output remains coherent and byte expectations require no semantic update.
- [x] No `bifrost.observability.*` property metadata or route is introduced by PR 03.

#### Manual Verification

- [ ] Review the new public-for-internal types and confirm each is necessary for cross-package framework composition and absent from supported API documentation.
- [ ] Inspect shutdown behavior to confirm catalog metadata is discarded, pending grace tasks are cancelled, and no file scanning or special deletion occurs.
- [ ] Confirm PR 04 can compose the services using a positive metadata TTL, nonnegative completion grace, and an enabled observation factory without requiring PR 03 API redesign.

## Testing Strategy

### Unit Tests

- Skill resource origin, byte capture, strict UTF-8 conversion, source-path normalization/rejection, duplicate descriptive paths, exact-name lookup, and name keysets.
- Descriptor validation, policy/grace matrix, exact size and completion time, scheduling rejection fallback, delayed failure isolation, and shutdown cancellation.
- Trace catalog publication, duplicate behavior, ordinal exhaustion, concurrency, direct lookup, high-water/before-ordinal traversal, expiration races, cleanup, and non-ownership of files.
- Terminal activity enrichment, ordering, catalog failure, core failure, exact-once close, active removal, retained-weight bounds, and live-monitoring failure boundaries.

### Integration Tests

- Full session completion to descriptor/catalog/activity under all persistence policies.
- Catalog metadata expiry independently from core deletion.
- Shutdown during grace leaving an intentionally unadopted file.
- Existing canonical fixture generation and architecture-boundary checks.

### Manual Testing Steps

1. Load skills from classpath, JAR-like, and filesystem roots and inspect normalized descriptive paths and exact YAML text.
2. Run short-grace traces under each persistence policy and inspect file/catalog/activity timing.
3. Restart the test application or reconstruct services and confirm the trace catalog is empty and leftover files are neither adopted nor deleted.

**Note**: Run `ai/commands/3_testing_plan.md` before implementation to create the dedicated failing-test sequence, concurrency/fake-time strategy, and final exit-criteria artifact.

## Performance Considerations

- Skill YAML is captured once per registered definition and does not grow after startup. The catalog holds one immutable text representation per registered skill; list operations return summaries without YAML.
- Trace catalog memory is proportional to current-process completions within the metadata TTL. Each entry is fixed-size metadata plus one internal path reference and contains no trace records or payloads.
- Trace catalog reads filter by time and sort current eligible entries. PR 03 should keep the implementation simple; no secondary index is required until measured cardinality demonstrates a need.
- The catalog uses one periodic cleanup task. Core completion grace uses one scheduled task only for each file whose normal deletion is delayed.
- Core finalization adds one file-size lookup for a retained descriptor. Catalog publication is synchronous in-memory work after the session lock and performs no file copying or parsing.
- No network work, subscriber callback, unbounded analysis, or artifact content read occurs under the session serialization lock.

## Migration Notes

- No persisted data migration exists. Trace catalog state is intentionally empty after restart.
- No supported API/SPI migration is required.
- Internal callers and tests must move atomically to typed finalization and descriptor-bearing observation disposition; do not retain the void finalization method as a deprecated bridge.
- Disabled starter behavior remains zero grace and no-op observation. PR 04 will later supply enabled collaborators only after complete startup validation.
- Files abandoned because shutdown or crash cancels grace deletion remain core-owned but unsupported and invisible. No rollback or later startup process scans, adopts, or removes them.

## References

- Original ticket: `ai/thoughts/tickets/bifrost-console-pr-03-observability-catalogs.md`
- Research: `ai/thoughts/research/2026-07-24-bifrost-console-pr-03-observability-catalogs.md`
- Framework design lens: `ai/thoughts/framework-feature-design-lens.md`
- Phase 1 design: `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md`
- Implementation roadmap: `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md`
- Prior lifecycle ticket: `ai/thoughts/tickets/bifrost-console-pr-02-observation-lifecycle.md`
- REST consumer: `ai/thoughts/tickets/bifrost-console-pr-04-spring-rest-adapter.md`
- Artifact streaming consumer: `ai/thoughts/tickets/bifrost-console-pr-06-artifact-streaming-integration.md`
- Operational UI consumer: `ai/thoughts/tickets/bifrost-console-pr-10-operational-views.md`
- Go artifact lifecycle consumer: `ai/thoughts/tickets/bifrost-console-pr-12-artifact-service.md`
- MCP consumers: `ai/thoughts/tickets/bifrost-console-pr-17-mcp-runtime-inspection.md`, `ai/thoughts/tickets/bifrost-console-pr-18-mcp-trace-inspection.md`, `ai/thoughts/tickets/bifrost-console-pr-19-debugging-skill.md`
