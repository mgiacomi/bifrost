---
date: 2026-07-24T23:50:11-07:00
researcher: Unknown
git_commit: 27bb14ef898b9e4ca0f918db0a83c73af18950b2
branch: main
repository: bifrost
topic: "Bifrost Console PR 04 — Spring Adapter Foundation and REST Snapshots"
tags: [research, codebase, spring-mvc, observability, rest, security, pagination]
status: complete
last_updated: 2026-07-24
last_updated_by: Unknown
---

# Research: Bifrost Console PR 04 — Spring Adapter Foundation and REST Snapshots

**Date**: 2026-07-24 23:50:11 PDT
**Researcher**: Unknown
**Model**: GPT-5
**Git Commit**: 27bb14ef898b9e4ca0f918db0a83c73af18950b2
**Branch**: main
**Repository**: bifrost

## Research Question

Research the current codebase for
`ai/thoughts/tickets/bifrost-console-pr-04-spring-rest-adapter.md`, using the
Phase 1 roadmap and later tickets where they clarify how the PR 04 boundary is
consumed.

## Summary

PR 04 is the first HTTP adapter in the starter. The current production starter
contains no Spring MVC controller, servlet filter, handler advice, problem
response, cursor codec, route-collision detector, or observability-specific
security implementation. Its POM includes Spring Boot, Jackson, validation, and
`spring-security-core`, but not Spring MVC or the servlet security modules
(`bifrost-spring-boot-starter/pom.xml:19-75`). The sample supplies
`spring-boot-starter-web` independently (`bifrost-sample/pom.xml:25-29`).

The producer-side services that PR 04 will adapt already exist under
`com.lokiscale.bifrost.internal.runtime.observation`:

- registered-skill lookup and deterministic name-keyed traversal;
- active-execution lookup, stable registry ordinals, high-water traversal, and
  live-monitoring availability;
- current-process finalized-trace lookup, descending ordinal traversal, and
  TTL-based discoverability; and
- bounded internal snapshot types that do not directly carry paths, Spring
  resources, JSON trees, trace records, exceptions, streams, or publishers.

Those services are not currently Spring beans. The single production
auto-configuration always constructs `BifrostSessionRunner` with
`NoOpExecutionObservationHandleFactory.INSTANCE`, so ordinary application
startup does not yet activate the registry, replay buffer, skill inspection
catalog, finalized-trace catalog, completion grace, or instance identity
(`BifrostAutoConfiguration.java:123-133`). Enabled observation composition is
exercised directly in unit tests rather than through auto-configuration.

The repository's supported-surface tests classify these observation types as
internal or accidentally exposed implementation. Their public modifiers exist
for cross-package framework collaboration with the future application adapter,
not as an Application API or Supported SPI
(`BifrostPublicSurfaceArchitectureTest.java:164-193`). The supported Java
Application API remains the seven types under `com.lokiscale.bifrost.api`;
there is no supported SPI, and there are no supported Bifrost bean overrides
(`README.md:142-144`,
`BifrostAutoConfigurationBoundaryTest.java:25-43`).

PR 04's REST and problem meanings will become a protected, release-matched
Java-to-Go protocol boundary even though its implementation types remain
internal. The authoritative Phase 1 design binds REST, future SSE, acquisition,
problem meanings, and consumed NDJSON to the exact complete Bifrost release
string reported as `consoleCompatibilityVersion`
(`bifrost_console_phase_1_observability_foundation.md:75-79`). The repository
does not currently contain code that derives or returns that release string,
nor REST/problem fixtures. The existing `bifrost-console-fixtures` directory
contains Java-produced trace/expected-semantic fixtures for the separate
consumed-NDJSON boundary.

## Detailed Findings

### 1. Repository and dependency boundary

The Maven reactor currently has two build modules:
`bifrost-spring-boot-starter` and `bifrost-sample` (`pom.xml:18-21`). The
repository also contains the non-reactor Go `bifrost-cli` and
`bifrost-console-fixtures`.

The parent fixes Java 21, Spring Boot 3.5.11, and Spring AI 1.1.6
(`pom.xml:24-33`). The starter dependencies relevant to PR 04 are:

- `spring-boot-starter` and `spring-boot-autoconfigure`;
- Jackson databind and YAML;
- `spring-boot-starter-validation`;
- `spring-security-core`; and
- test-only `spring-boot-starter-test`.

There is no starter dependency on `spring-boot-starter-web`,
`spring-webmvc`, `jakarta.servlet`, `spring-security-web`, or
`spring-security-config` in the current POM
(`bifrost-spring-boot-starter/pom.xml:19-79`). The sample application supplies
the web stack itself (`bifrost-sample/pom.xml:25-29`).

The sample has ordinary `@RestController` classes for its business endpoints,
but these controllers are sample-owned and use `ResponseStatusException`.
There is no reusable controller, error-advice, or filter implementation in the
starter (`bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/SampleController.java:10-12`,
`bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/incident/IncidentController.java:13-29`).

### 2. Existing auto-configuration shape

The starter registers exactly one auto-configuration through
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
(`BifrostAutoConfigurationTests.java:67-77`). That class:

- uses `@AutoConfiguration`;
- enables `ExecutionTraceProperties` and `BifrostProperties`;
- marks itself and all bean methods as infrastructure; and
- creates framework-owned beans through package-private methods
  (`BifrostAutoConfiguration.java:79-89`).

The architecture test maintains a closed list of every bean factory, asserts
that each factory method is package-private, and asserts that no production type
or method uses `@ConditionalOnMissingBean`
(`BifrostAutoConfigurationBoundaryTest.java:25-88`). A test also proves that
registering an application-owned internal model resolver causes a collision
rather than backing off (`BifrostAutoConfigurationTests.java:131-141`).

This is direct evidence of the current framework-owned wiring model. It is not
evidence of a supported application replacement seam.

At the current commit, the auto-configuration does not declare:

- a web-application or classpath condition;
- an observability activation property;
- observability registry/catalog beans;
- an observability filter or authority;
- controller or handler beans;
- route-collision inspection;
- a startup `instanceId`; or
- release-version injection.

Its session-runner bean passes the configured trace persistence policy, a UTC
clock, and the no-op observation factory
(`BifrostAutoConfiguration.java:123-133`). This preserves the disabled behavior
today.

### 3. Current configuration contracts

`BifrostProperties` is bound under `bifrost`, is validated, and sets
`ignoreUnknownFields = false` (`BifrostProperties.java:21-28`). It currently
contains `session`, `skills`, `connections`, and `models`; no
`observability` subtree exists (`BifrostProperties.java:32-42`).

`ExecutionTraceProperties` is a separate `execution-trace` configuration
object. It exposes only `persistence`, defaulting null or absent values to
`ONERROR`
(`ExecutionTraceProperties.java:8-25`). The current class does not explicitly
disable unknown-field binding.

The checked-in additional configuration metadata documents named connections
and model aliases, not observability properties
(`additional-spring-configuration-metadata.json:2-102`). The generated metadata
test checks the connection/model surface
(`ConfigurationMetadataTest.java:11-31`).

The Phase 1 design names `bifrost.observability.auth.api-key` as the intended
authentication shape and defines `completion-grace-ttl` (initially `15m`) and a
separate trace-catalog metadata TTL as status/configuration facts
(`bifrost_console_phase_1_observability_foundation.md:315-317`,
`:545-547`). None of these names is implemented in live configuration at this
commit.

### 4. Observation activation and execution integration

`BifrostSessionRunner` already accepts an `ExecutionObservationHandleFactory`
and, in one constructor, a `CompletionGraceRetention`
(`BifrostSessionRunner.java:40-67`). Every new session gets:

- a fresh UUID session ID;
- the configured trace persistence policy;
- the configured clock;
- the observation-handle factory; and
- the trace-handle factory
  (`BifrostSessionRunner.java:89-104`, `:126-141`).

The public convenience constructors default to
`NoOpExecutionObservationHandleFactory.INSTANCE`
(`BifrostSessionRunner.java:25-48`). The production auto-configuration uses
that same no-op path.

`DefaultExecutionObservationHandleFactory` composes a live projector, active
registry, replay buffer, availability latch, and finalized-trace catalog
(`DefaultExecutionObservationHandleFactory.java:12-50`). It exposes accessors
for the registry, replay buffer, availability, and trace catalog specifically
for internal cross-package composition (`DefaultExecutionObservationHandleFactory.java:59-77`).
Its zero- and four-argument constructors use an unavailable trace-catalog
implementation; the five-argument constructor connects a real catalog
(`DefaultExecutionObservationHandleFactory.java:20-50`, `:79-107`).

The framework already has immediate and scheduled completion-retention
implementations. The scheduled implementation accepts a grace `Duration`; the
session runner can inject it into every trace handle
(`ScheduledCompletionGraceRetention.java:23-38`,
`BifrostSessionRunner.java:50-67`). Production wiring does not currently select
either observability-specific grace behavior.

### 5. Registered-skill catalog

`RegisteredSkillCatalog` has two operations:

- exact lookup by registered name; and
- `listAfter(exclusiveName, limit)` traversal
  (`RegisteredSkillCatalog.java:8-13`).

`DefaultRegisteredSkillCatalog` builds an immutable `TreeMap` from the
successfully loaded `YamlSkillCatalog`. For each definition it retains:

- the registered name;
- a normalized, descriptive source path; and
- the original source bytes decoded strictly as UTF-8.

The collection item is `RegisteredSkillFile.Summary`, containing only
`registeredName` and `sourcePath`; detail adds the YAML string
(`RegisteredSkillFile.java:5-31`). Lookup is case-sensitive and traversal uses
natural registered-name order. Tests verify `"Alpha"` before `"beta"`, exact
lookup, unchanged CRLF/YAML content, and failure on malformed UTF-8
(`DefaultRegisteredSkillCatalogTest.java:28-66`).

`SkillSourcePathResolver` derives a path relative to the configured discovery
root and rejects blank, absolute, scheme-bearing, drive-bearing, empty,
`.` or `..` segments
(`SkillSourcePathResolver.java:20-65`, `:90-119`). Caller input is not used to
resolve a path; the detail catalog is keyed by registered name.

The current catalog API validates `limit > 0`, but it does not define the REST
page-size defaults, maximum page size, cursor encoding, response-size ceiling,
link representation, or HTTP DTOs. It also has no count operation.

### 6. Active-execution snapshots and traversal

`ActiveExecutionRegistry` exposes replacement, direct lookup by `sessionId`,
removal, active count, the highest assigned registry ordinal, and newest-first
traversal (`ActiveExecutionRegistry.java:6-19`).

Each `ActiveExecutionSnapshot` contains:

- opaque session and trace IDs;
- stable registry ordinal and last canonical sequence;
- start and update times;
- optional entry skill;
- phase and concise summary;
- bounded active frame path, full depth, and truncation fact;
- current `SessionUsageSnapshot`; and
- optional terminal outcome
  (`ActiveExecutionSnapshot.java:11-28`).

The record enforces nonblank identities, positive canonical sequence, bounded
text, at most 64 retained active-path entries, and consistency between retained
path length and total depth (`ActiveExecutionSnapshot.java:30-62`,
`ExecutionObservationLimits.java:5-14`). A frame-path entry contains only
`frameId`, `TraceFrameType`, and a bounded route
(`ActiveExecutionSnapshot.java:71-82`).

`InMemoryActiveExecutionRegistry` assigns one monotonically increasing ordinal
when a session first appears and retains that ordinal across replacement
(`InMemoryActiveExecutionRegistry.java:28-38`). `newestFirst` filters at or below
a supplied high-water mark and sorts descending by ordinal
(`InMemoryActiveExecutionRegistry.java:65-80`). Passing zero currently means no
filter (`Long.MAX_VALUE`); callers can capture `highestOrdinal()` separately.
Tests cover stable ordinals, direct removal/lookup, descending traversal,
overflow failure, and 128 concurrent independent sessions
(`InMemoryActiveExecutionRegistryTest.java:16-72`).

`LiveMonitoringAvailability` is a process-local first-failure latch. It reports
availability and exposes only the operation name and exception class of the
first failure; it does not expose the exception object
(`LiveMonitoringAvailability.java:7-44`). The Phase 1 design maps this fact to
instance status and requires active snapshot requests to fail with
`LIVE_MONITORING_UNAVAILABLE` when false
(`bifrost_console_phase_1_observability_foundation.md:178`,
`:355`).

### 7. Current-process finalized-trace catalog

`FinalizedTraceCatalog` exposes publish, exact `traceId` lookup, high-water /
before-ordinal traversal, and close
(`FinalizedTraceCatalog.java:7-17`).

`InMemoryFinalizedTraceCatalog`:

- starts empty in memory;
- accepts a positive metadata TTL and clock;
- creates one daemon sweep scheduler;
- validates that a published descriptor points to a readable regular file;
- assigns a strictly increasing catalog ordinal;
- calculates the effective application expiration as the earlier of catalog TTL
  and any core artifact expiration;
- removes expired metadata during lookup/list/sweep;
- lists newest first under a captured high-water ordinal; and
- clears metadata and stops its scheduler on close
  (`InMemoryFinalizedTraceCatalog.java:21-56`, `:58-108`, `:112-158`).

The catalog's `list(0, 0, limit)` captures its current assigned high water and
returns it in `TraceCatalogSlice`; continuation can use the returned high water
and the last returned catalog ordinal as the exclusive `beforeOrdinal`
(`InMemoryFinalizedTraceCatalog.java:128-149`). Tests verify this traversal,
idempotent publication, expiration without artifact deletion, earlier core
expiration, rejection of missing files, and synchronized close
(`InMemoryFinalizedTraceCatalogTest.java:23-124`).

`FinalizedTraceCatalogEntry` deliberately contains internal data that cannot be
serialized as the external REST DTO unchanged: `Path artifactPath` and the
full `FinalizedTraceArtifact` are components
(`FinalizedTraceCatalogEntry.java:11-23`). The entry also contains the adapter
facts needed for a summary: ordinal, trace/session IDs, outcome, finalization and
publication times, size, persistence policy, catalog expiry, and effective
application expiry. The Phase 1 boundary says ordinary DTOs and links use
opaque trace IDs and do not expose the path
(`bifrost_console_phase_1_observability_foundation.md:267-269`).

The catalog API does not currently expose an entry-count operation. It does not
contain REST pagination cursors, response-size accounting, or trace-summary
DTOs.

### 8. Internal DTO bounds and external DTO separation

`ExecutionObservationLimits` already centralizes projection bounds:

- 64 active frame-path entries;
- 256 code points for ordinary text;
- 512 code points for summaries;
- 32 detail fields;
- 8 KiB for details;
- 12 KiB per activity;
- 10,000 replay events; and
- 16 MiB replay-buffer UTF-8 weight
  (`ExecutionObservationLimits.java:5-14`).

The architecture test separately inspects `ActiveExecutionSnapshot` and
`ExecutionActivity` and excludes `Path`, Spring `Resource`, Jackson `JsonNode`,
internal `TraceRecord`, `Throwable`, `Stream`, and `Flow.Publisher` components
(`BifrostPublicSurfaceArchitectureTest.java:330-354`). This is current evidence
that live projection DTOs are intentionally bounded internal collaboration
types.

The finalized-trace catalog entry is different: it carries a core-owned `Path`
and artifact descriptor for internal acquisition. It therefore requires an
external protocol projection rather than direct serialization.

No REST DTO classes exist at the current commit. Consequently, exact JSON field
sets, link fields, `instanceId` response-header name, and endpoint-specific
paths below the reserved namespace are not established by live code. The Phase
1 design does establish observable meanings for:

- instance status;
- registered-skill list/detail;
- active-execution list/detail;
- current-process trace list/detail;
- common collection pages; and
- the stable problem envelope.

### 9. Pagination contract that surrounds the existing services

The authoritative Phase 1 design sets the REST collection contract:

- default `pageSize`: 1,000 complete items;
- maximum requested `pageSize`: 5,000;
- maximum uncompressed serialized JSON response: 16 MiB;
- complete `items`, `hasMore`, opaque `nextCursor`, and `observedAt`;
- centrally supplied `instanceId`; and
- cursor binding to instance, endpoint, ordering, filters, and first-page high
  water
  (`bifrost_console_phase_1_observability_foundation.md:293-305`).

The existing catalogs supply the underlying keyset positions:

| Collection | Current key/order service | First-page state |
|---|---|---|
| Skills | `listAfter(name, limit)`, name ascending | Last registered name |
| Active executions | `newestFirst(highWater, limit)`, ordinal descending | `highestOrdinal()` plus replay-buffer cursor |
| Finalized traces | `list(highWater, before, limit)`, ordinal descending | Returned `TraceCatalogSlice.highWaterOrdinal()` |

The design defines `INVALID_CURSOR` for malformed or mismatched cursors and
`STALE_CURSOR` when a formerly valid cursor cannot continue
(`bifrost_console_phase_1_observability_foundation.md:305`). No cursor
representation or codec exists in the current codebase.

The existing services enforce only positive item limits. They do not enforce
the HTTP page-size range or 16 MiB serialized-response bound. There is no
current serializer-based page budgeting implementation or fixture.

### 10. Authentication, authority, and host security

The starter currently uses Spring Security core authentication for skill
invocation and RBAC. `DefaultSkillTemplate` reads the current
`SecurityContextHolder` authentication for root invocation
(`DefaultSkillTemplate.java:99-107`), and `DefaultAccessGuard` compares current
authorities with skill roles (`DefaultAccessGuard.java:18-59`).

That execution access guard is not an observability HTTP access service. No
`BIFROST_OPERATOR` authority, API-key authentication token, servlet filter, or
route-scoped security context currently exists.

The authoritative PR 04 boundary is:

- opt-in and unauthenticated mode is unsupported;
- one key is supplied only through `X-Bifrost-Api-Key`;
- a valid key establishes the internal `BIFROST_OPERATOR` authority;
- all observability operations map to that authority initially;
- the adapter owns extraction, safe comparison, rejection, and authentication;
- the adapter does not install, replace, reorder, or broaden the host
  `SecurityFilterChain`; and
- host security must pass the reserved namespace through to adapter
  authentication
  (`bifrost_console_phase_1_observability_foundation.md:381-400`,
  `:533-539`).

Only an adapter-produced `401` with `BIFROST_API_KEY_REJECTED` proves the
Bifrost key was rejected. A generic upstream/host `401` or `403` remains a
distinct observable response for the future Go client
(`bifrost_console_phase_1_observability_foundation.md:539`).

The current starter depends only on `spring-security-core`, so it cannot
currently declare or modify a servlet `SecurityFilterChain`. No sample
Spring Security pass-through configuration exists yet.

### 11. Route namespace, servlet context, and collision state

The authoritative namespace is the non-configurable
`/_bifrost/observability/v1/**`, relative to the host servlet context path. An
application under `/orders` therefore exposes the namespace under
`/orders/_bifrost/observability/v1/`
(`bifrost_console_phase_1_observability_foundation.md:535`).

The adapter uses the ordinary application listener, not an Actuator management
listener, and adds no CORS policy
(`bifrost_console_phase_1_observability_foundation.md:541-543`).

The design also gives the namespace exclusive ownership when enabled: a host
handler collision disables the optional adapter for the process with a clear
diagnostic while leaving core Bifrost and unrelated routes available
(`bifrost_console_phase_1_observability_foundation.md:545`).

There is no handler-mapping inspection or collision test in current production
or test code. The current sample controllers use unrelated `/`, `/incidents`,
`/travel`, `/support`, and `/claims` paths.

### 12. Problem and response metadata contracts

The Phase 1 problem envelope has an HTTP status, stable `code`, and sanitized
human-readable `message`. Go branches on status and code, not message text.
Authenticated problems carry the same instance metadata when identity is
available; missing/invalid authentication need not disclose identity
(`bifrost_console_phase_1_observability_foundation.md:512-515`,
`:606-617`).

The stable initial code set is:

| Code | HTTP | Consumer meaning |
|---|---:|---|
| `BIFROST_API_KEY_REJECTED` | 401 | Adapter saw no valid Bifrost key |
| `INVALID_REQUEST` | 400 | Invalid syntax or non-cursor parameter |
| `INVALID_CURSOR` | 400 | Malformed or wrong-scope cursor |
| `STALE_CURSOR` | 410 | Former continuation cannot continue |
| `NOT_FOUND` | 404 | Current-process resource unavailable |
| `LIVE_MONITORING_UNAVAILABLE` | 503 | Live projection is known incomplete |
| `LIMIT_EXCEEDED` | 429 | Fixed admission or request bound prevents operation |
| `APPLICATION_ERROR` | 500 | Sanitized adapter failure |

These meanings come from the authoritative design
(`bifrost_console_phase_1_observability_foundation.md:516-529`). They are not
implemented as Java types or executable JSON fixtures at the current commit.

Every authenticated diagnostic response must use `Cache-Control: no-store`
(`bifrost_console_phase_1_observability_foundation.md:396`). Every successful
authenticated target-specific REST response has centrally applied `instanceId`
metadata (`bifrost_console_phase_1_observability_foundation.md:615`). No
`ResponseBodyAdvice`, interceptor, filter, controller advice, or equivalent
central response component currently exists.

### 13. Instance status and compatibility source

The designed instance-status snapshot reports:

- startup-generated UUIDv4 `instanceId`;
- exact `consoleCompatibilityVersion`;
- `observedAt`;
- `liveMonitoringAvailable`;
- registered-skill, active-execution, and cataloged-trace counts;
- effective trace persistence policy;
- completion-grace TTL; and
- trace-catalog metadata TTL
  (`bifrost_console_phase_1_observability_foundation.md:345-355`).

Current sources already exist for the active count, persistence policy,
availability latch, and configured TTL values once composed. The registered
skill and finalized-trace catalog interfaces currently lack count methods.

The parent project version is `0.1.0-SNAPSHOT` (`pom.xml:10-12`), but no
production class reads Maven/build properties, package implementation version,
or another release source. No current type contains
`consoleCompatibilityVersion`.

The compatibility rule is exact complete-string equality, including qualifiers.
Go initially reads only that stable top-level field and makes no other
observability request on mismatch
(`bifrost_console_phase_1_observability_foundation.md:75-79`,
`:549-553`).

### 14. Testing and fixture landscape

Current relevant executable tests cover:

- starter auto-configuration registration and bean creation;
- strict framework-owned bean boundaries and absence of
  `@ConditionalOnMissingBean`;
- strict configuration metadata for existing model/connection properties;
- active-registry ordering, concurrency, and overflow;
- registered-skill ordering, lookup, YAML preservation, and source safety;
- finalized-trace publication, keyset traversal, TTL, file validation, and
  shutdown; and
- enabled observation lifecycle/failure isolation through directly constructed
  factories.

There are no current tests using `MockMvc`, a random-port web server,
`WebTestClient`, servlet filters, Spring Security filter chains, servlet context
paths, observability route collisions, observability problems, or REST
pagination.

`bifrost-console-fixtures` contains NDJSON traces and expected semantic JSON for
the current Java-to-Go trace agreement. Those fixtures cover success, retries,
usage reconciliation, terminal failure/abort, chunking, malformed JSON,
inconsistent identity, unsupported enums, and other trace meanings
(`bifrost-console-fixtures/README.md:1-116`). There are no REST snapshot,
problem, authentication, pagination, or compatibility fixtures yet.

PR 06 later owns Phase 1 adapter integration and final Java-produced
cross-boundary fixtures. PR 04's ticket itself requires executable
authentication, context-path routing, collision, pagination, identity, problem,
compatibility, and host-security integration signals.

## Contract Classification

The classifications below apply the exact categories from
`ai/thoughts/framework-feature-design-lens.md`.

### Application API

Current supported evidence:

- The ordinary application API is the closed seven-type
  `com.lokiscale.bifrost.api` allowlist.
- README documentation names those seven types and says internal bean
  replacement is unsupported (`README.md:142-144`).
- Architecture tests enforce the exact allowlist
  (`BifrostPublicSurfaceArchitectureTest.java:228-255`).

PR 04 does not currently exist as a Java Application API. Its host-facing
configuration and required Spring Security pass-through documentation are
application-developer integration surfaces, while controller/filter/DTO
implementation types remain unimplemented.

### Supported SPI

Current supported evidence:

- No `.spi` package exists.
- The architecture test explicitly asserts that there is no supported SPI
  (`BifrostPublicSurfaceArchitectureTest.java:276-281`).
- The bean-override allowlist is empty and production code contains no
  `@ConditionalOnMissingBean`
  (`BifrostAutoConfigurationBoundaryTest.java:40-88`).

The technically public observation interfaces and constructors are therefore
not supported SPIs at this commit.

### Configuration and manifest contracts

Current deliberate contracts:

- strict documented `bifrost.*` application properties;
- separate `execution-trace.persistence`; and
- YAML skill syntax, discovery locations, original YAML, and descriptive
  `sourcePath`.

PR 04's `bifrost.observability.*` configuration is described by the ticket and
Phase 1 design but is absent from live properties and metadata. Its eventual
property names, validation, defaults, and activation semantics will be a
configuration contract.

No YAML skill syntax change is described by PR 04. The skill catalog exposes
the unchanged YAML already loaded under the existing manifest contract.

### Persisted or serialized contracts

The new REST pages and problem envelopes will be serialized Java-to-Go protocol
contracts protected by the release umbrella. They do not exist in live code at
this commit.

The configured application API key is a startup configuration secret, not a
returned serialized diagnostic value. `instanceId` is in-memory and restart
scoped, not persisted.

### Ephemeral diagnostic formats

Current trace records and finalized NDJSON artifacts are explicitly
current-release diagnostic formats. Their protected consumer is the future Go
console under an exact `consoleCompatibilityVersion` match, backed by
Java-produced fixtures.

Active snapshots, activity, registered YAML views, and current-process trace
metadata are also current-instance diagnostic data. The REST protocol gives
them stable same-release meanings without making them durable, historical, or
cross-version data.

### Internal or accidentally exposed implementation

The following current types are explicitly classified here by architecture-test
evidence:

- `ActiveExecutionRegistry`, `ActiveExecutionSnapshot`;
- `ActivityReplayBuffer`, `ExecutionActivity`, `ReplayResult`;
- `DefaultExecutionObservationHandleFactory` and its collaborators;
- `RegisteredSkillCatalog`, `RegisteredSkillFile`;
- `FinalizedTraceCatalog`, `FinalizedTraceCatalogEntry`,
  `TraceCatalogSlice`; and
- completion-retention and finalized-artifact collaboration types.

Their public visibility enables framework-owned collaboration across internal
packages. The architecture allowlist reasons specifically name the future
application-adapter use (`BifrostPublicSurfaceArchitectureTest.java:164-193`).
There is no README, API allowlist, SPI allowlist, or verified application use
establishing them as supported contracts.

## Protected Protocol Consumers and Coordinated Boundaries

| Boundary established or used by PR 04 | Protected in-repository consumer |
|---|---|
| Instance status and exact release match | PR 09 `TargetContext` and application protocol client |
| Stable application problems | PR 09 target/authentication/domain-error mapping |
| Skill list/detail pagination and unchanged YAML | PR 10 operational services/UI; PR 17 MCP through shared Go services |
| Active list/detail, high water, `resumeCursor`, and identity | PR 05 SSE baseline; PRs 10–11 UI/live services; PR 17 MCP |
| Trace catalog list/detail and expiry facts | PR 06 artifact streaming/integration; PRs 10 and 12 acquisition/UI |
| Central `instanceId` and no-store response behavior | PR 05 SSE, PR 06 acquisition, PR 09 scope rotation |
| REST/problem JSON fixtures | Future Go protocol tests introduced with Phase 1 integration |
| Native NDJSON meaning reachable from trace metadata | PR 06 acquisition and Java-produced fixtures; later Go parser/services |

PR 05 consumes the PR 04 authentication, problem, instance, active-baseline,
and Spring MVC foundation to add SSE. PR 06 consumes the same foundation for
artifact streaming and completes Phase 1 integration fixtures. PR 09 makes
instance status the only initial compatibility probe and owns target scope
rotation. PR 10 preserves upstream opaque continuations rather than
materializing whole registries. PR 11 combines the first active page's
`resumeCursor` with the later SSE interval. PR 17 reaches the same meanings only
through shared Go services; it does not call the application directly.

Because `consoleCompatibilityVersion` covers REST, SSE, acquisition, problems,
and consumed NDJSON together, a meaning change in any of these surfaces is a
same-release Java/Go/fixture/test/documentation coordination point
(`2026-07-23-bifrost-console-implementation-roadmap.md:120-128`,
`bifrost_console_phase_1_observability_foundation.md:549-553`).

## Architecture Documentation

The current-to-PR-04 data path is:

```text
YamlSkillCatalog
  -> DefaultRegisteredSkillCatalog
  -> internal skill summary/detail projection
  -> authenticated REST DTO

canonical trace append
  -> ExecutionObservationHandle
  -> LiveActivityProjector
  -> InMemoryActiveExecutionRegistry
  -> internal active snapshot projection
  -> authenticated REST DTO

successful core trace finalization
  -> FinalizedTraceArtifact
  -> InMemoryFinalizedTraceCatalog
  -> internal trace summary/detail projection (excluding Path/artifact)
  -> authenticated REST DTO

startup configuration
  -> opt-in activation/authentication/TTLs
  -> startup UUID instance identity
  -> centralized authenticated response metadata and no-store policy
```

The existing code owns the first two or three nodes in each flow. The HTTP
projection, authentication, common response policy, cursor envelope, and route
registration nodes do not yet exist.

The Phase 1 design keeps these responsibilities distinct:

- core owns trace creation, finalization, retention, and file paths;
- observation owns bounded current-process projection and catalog metadata;
- the Spring adapter owns authenticated REST representation and request bounds;
- the host owns listener/TLS/network exposure and any outer security filters;
- future Go owns target compatibility, browser-facing services, and artifact
  parsing/analysis.

## Historical Context (from `ai/thoughts/`)

- `ai/thoughts/tickets/bifrost-console-pr-02-observation-lifecycle.md` scoped
  the live projection, active registry, and replay buffer while explicitly
  excluding HTTP and browser delivery.
- `ai/thoughts/tickets/bifrost-console-pr-03-observability-catalogs.md` scoped
  registered YAML and finalized-trace metadata while explicitly excluding
  Spring routes and trace downloads.
- `ai/thoughts/tickets/bifrost-console-pr-04-spring-rest-adapter.md` combines
  opt-in startup composition, authentication, route ownership, problem mapping,
  compatibility status, and read-only REST snapshots.
- `ai/thoughts/tickets/bifrost-console-pr-05-live-sse-delivery.md` reuses PR
  04's adapter foundation and active baseline for bounded SSE.
- `ai/thoughts/tickets/bifrost-console-pr-06-artifact-streaming-integration.md`
  reuses the authentication/identity/problem boundary for exact artifact
  streaming and Phase 1 fixtures.
- `ai/thoughts/tickets/bifrost-console-pr-09-target-context.md` makes PR 04
  instance status and problems inputs to Go target authentication,
  compatibility, identity, and scope.
- `ai/thoughts/tickets/bifrost-console-pr-10-operational-views.md` consumes the
  skill, active, and trace collection/detail resources through
  transport-neutral Go services.
- `ai/thoughts/tickets/bifrost-console-pr-11-live-execution-experience.md`
  combines PR 04 active snapshots with PR 05 SSE.
- `ai/thoughts/tickets/bifrost-console-pr-17-mcp-runtime-inspection.md` adapts
  the shared Go services rather than calling PR 04 directly.
- `ai/thoughts/phases/bifrost_console_workflows.md` ties the active snapshot
  boundary to `WF-SLOW-EXECUTION` and the skill YAML boundary to
  `WF-UNFAMILIAR-SKILL-PATH`.

## Related Research

No prior files were present in `ai/thoughts/research/` at the time of this
research.

## Open Questions

These items are not established by the live implementation or the ticket brief
and remain areas for detailed implementation planning:

- the exact endpoint paths below `/_bifrost/observability/v1/`;
- the concrete external JSON DTO field sets and link representation;
- the HTTP header name used for centrally supplied `instanceId`;
- the release-version source used to produce
  `consoleCompatibilityVersion` in packaged and test execution;
- the opaque cursor encoding and the exact conditions distinguishable as stale
  versus invalid for each REST collection;
- the component used to measure a prospective Jackson response against the
  16 MiB uncompressed bound while preserving whole items;
- the startup lifecycle and handler-mapping phase used for exclusive route
  collision detection;
- the internal mechanism used to establish and clear `BIFROST_OPERATOR`
  without installing a host `SecurityFilterChain`;
- how cheap registered-skill and finalized-trace counts are obtained for status
  from the current interfaces;
- the exact valid-key constraints beyond the design's high-entropy requirement;
  and
- the division between PR 04 focused web fixtures and PR 06's final
  cross-language/integration fixture set.
