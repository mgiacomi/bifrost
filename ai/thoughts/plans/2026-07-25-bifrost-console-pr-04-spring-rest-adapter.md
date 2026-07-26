# Bifrost Console PR 04 — Spring Adapter Foundation and REST Snapshots Implementation Plan

## Overview

Implement the first opt-in application-to-console HTTP boundary in
`bifrost-spring-boot-starter`. The adapter will activate the existing
observation registries and catalogs only after successful servlet startup,
reserve and authenticate `/_bifrost/observability/v1/**`, and expose
release-matched, bounded REST snapshots for instance status, registered skills,
active executions, and current-process finalized traces.

The implementation establishes the Java producer side of the Phase 1
Java-to-Go protocol. It deliberately keeps internal runtime types, filesystem
paths, host Spring Security configuration, and future SSE/artifact behavior out
of the REST contract.

## Current State Analysis

- The starter has no Spring MVC controller, servlet filter, route registrar,
  cursor codec, REST DTO, response advice, or observability problem mapping.
  It depends on Spring Security core but not the servlet web or security-web
  stacks (`bifrost-spring-boot-starter/pom.xml:19-79`).
- The sample supplies `spring-boot-starter-web`; the starter does not currently
  force applications to become servlet applications
  (`bifrost-sample/pom.xml:25-29`).
- `BifrostAutoConfiguration` is the only registered auto-configuration and
  always wires `BifrostSessionRunner` with
  `NoOpExecutionObservationHandleFactory.INSTANCE`
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:79-85`,
  `:123-133`).
- `BifrostProperties` is strict under `bifrost` and has no observability
  subtree (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostProperties.java:21-42`).
- The producer-side services already provide immutable skill traversal,
  active-execution snapshots and high water, the activity replay cursor,
  live-monitoring availability, and finalized-trace traversal
  (`RegisteredSkillCatalog.java:8-13`,
  `ActiveExecutionRegistry.java:6-19`,
  `ActivityReplayBuffer.java:3-10`,
  `FinalizedTraceCatalog.java:7-17`).
- `ActiveExecutionRegistry.newestFirst(highWaterMark, limit)` cannot resume
  after the last item in a page. Unlike the trace catalog, it has no exclusive
  before-ordinal input, so a real second active page requires an atomic internal
  API correction (`InMemoryActiveExecutionRegistry.java:65-80`).
- Registered-skill and finalized-trace catalogs have no count operation.
  Instance status therefore cannot obtain all three designed counts from the
  current interfaces.
- Internal active snapshots are already bounded and contain no `Path`,
  `Resource`, `JsonNode`, trace record, exception, stream, or publisher
  components. Finalized trace entries deliberately do contain an internal
  `Path` and artifact descriptor and must never be serialized directly
  (`ActiveExecutionSnapshot.java:12-26`,
  `FinalizedTraceCatalogEntry.java:11-23`,
  `BifrostPublicSurfaceArchitectureTest.java:330-354`).
- The current supported Java API remains the closed seven-type
  `com.lokiscale.bifrost.api` allowlist. There is no supported SPI or
  application-owned bean replacement seam
  (`BifrostPublicSurfaceArchitectureTest.java:228-281`,
  `BifrostAutoConfigurationBoundaryTest.java:25-88`).
- Phase 2 consumes this boundary through a hand-authored Go target client and
  transport-neutral services. Phase 3 consumes those services rather than
  calling Java directly. Exact route and serialized-field spelling are
  intentionally PR 04 implementation decisions, protected afterward by
  Java-produced fixtures
  (`bifrost_console_phase_2_ui_console.md:301-326`, `:1111-1117`,
  `bifrost_console_phase_3_llm_runtime_inspector.md:55-100`).

## Desired End State

When observability is disabled, absent, invalid, non-servlet, or rejected due
to a route collision, the application retains the existing no-op observation
and immediate trace-retention behavior and exposes no Bifrost observability
route.

When valid observability configuration reaches a collision-free servlet
application:

- startup commits one UUIDv4 `instanceId`;
- the session runner uses the enabled observation factory and configured core
  completion grace;
- the reserved namespace is owned exclusively by the adapter;
- every request is authenticated through exactly one
  `X-Bifrost-Api-Key` header;
- valid authentication establishes only the internal `BIFROST_OPERATOR`
  authority for the duration of that request;
- authenticated responses use `Cache-Control: no-store` and
  `X-Bifrost-Instance-Id`;
- stable REST/problem JSON is produced from hand-authored boundary DTOs;
- collections use bounded keyset pagination and never exceed 16 MiB of
  uncompressed serialized JSON;
- active resources fail closed when live projection is known incomplete; and
- the host application continues to own listener, TLS, proxy, and outer
  security-filter policy.

The boundary is verified through unit tests, servlet integration tests, host
Spring Security tests, deterministic Java-produced JSON fixtures, configuration
metadata tests, architecture tests, and the full Maven build.

### Key Discoveries

- Phase 1 fixes the namespace, page limits, compatibility semantics, problem
  codes, no-store requirement, and exact-release match, but deliberately leaves
  route and field spelling to implementation
  (`ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md:293-305`,
  `:512-553`).
- Phase 2 initially reads only the top-level
  `consoleCompatibilityVersion`, commits identity through `TargetContext`, and
  treats a changed `instanceId` on any later authenticated response as a scope
  boundary (`bifrost_console_phase_2_ui_console.md:301-354`).
- Phase 2 follows the server-generated skill link and treats `sourcePath` only
  as descriptive data; Phase 3 carries the same skill meaning through shared Go
  services (`bifrost_console_phase_2_ui_console.md:941-945`,
  `bifrost_console_phase_3_llm_runtime_inspector.md:414-418`).
- API-root-relative links are safer than origin- or servlet-context-qualified
  links: Go can resolve them against its validated target and externally visible
  context path without trusting a server-provided scheme or authority.
- A route collision must be resolved before observation becomes usable.
  Therefore activation needs an internal pending/committed gate: session-runner
  collaborators behave as no-op/zero-grace while pending and become enabled
  only after MVC mappings have been inspected and Bifrost routes registered.
- The Maven project version is the complete compatibility value. A filtered
  classpath build resource is deterministic in packaged and test execution,
  unlike relying on nullable package manifest metadata.

## Settled External Contract

### Configuration

```yaml
bifrost:
  observability:
    enabled: false
    auth:
      api-key: ${BIFROST_OBSERVABILITY_API_KEY}
    completion-grace-ttl: 15m
    trace-catalog-metadata-ttl: 24h
```

- `enabled` defaults to `false`.
- `completion-grace-ttl` defaults to `15m` and accepts zero but not a negative
  value.
- `trace-catalog-metadata-ttl` defaults to `24h` and must be greater than zero.
- When enabled, `auth.api-key` must contain 32–512 printable ASCII characters,
  with no whitespace or control characters. Documentation recommends at least
  32 cryptographically random bytes encoded as unpadded base64url.
- Supplying a key while disabled is allowed so activation can be controlled
  independently in externalized configuration.
- Unknown or syntactically unbindable `bifrost.*` properties retain the current
  strict Spring binding failure. A parsed but unusable enabled observability
  configuration disables only the optional adapter with a safe, actionable
  diagnostic and registers no partial route.

### Routes

All paths are relative to the host servlet context:

| Method | Path | Result |
| --- | --- | --- |
| `GET` | `/_bifrost/observability/v1/instance` | Authenticated compatibility and runtime status |
| `GET` | `/_bifrost/observability/v1/skills` | Registered-skill summaries |
| `GET` | `/_bifrost/observability/v1/skills/{registeredName}` | Registered name, source path, unchanged YAML |
| `GET` | `/_bifrost/observability/v1/active-executions` | Bounded active baseline |
| `GET` | `/_bifrost/observability/v1/active-executions/{sessionId}` | Current bounded active snapshot |
| `GET` | `/_bifrost/observability/v1/traces` | Current-instance finalized-trace summaries |
| `GET` | `/_bifrost/observability/v1/traces/{traceId}` | Current finalized-trace metadata |

An authenticated unknown `GET` under the namespace returns `NOT_FOUND`. An
unsupported method or invalid request shape returns `INVALID_REQUEST`. The
adapter adds no CORS or Actuator mapping.

### Common Wire Rules

- Successful authenticated and authenticated problem responses carry
  `X-Bifrost-Instance-Id: <uuid>` and `Cache-Control: no-store`.
- Missing or rejected Bifrost credentials return `401` and
  `BIFROST_API_KEY_REJECTED` without disclosing instance identity.
- The problem body is exactly:

  ```json
  {
    "status": 400,
    "code": "INVALID_REQUEST",
    "message": "Sanitized stable-context message"
  }
  ```

- Collection bodies contain `items`, `hasMore`, nullable `nextCursor`, and
  `observedAt`. The active collection additionally contains a decimal-string
  `resumeCursor` on its first page; later pages omit it.
- `pageSize` defaults to `1000`, accepts `1..5000`, and is not silently clamped.
- Summary DTOs remain bounded. Collection serialization stops before a whole
  item that would make the final JSON body exceed 16 MiB. A single item that
  cannot fit produces `LIMIT_EXCEEDED`; no JSON value or item is truncated.
- Continuation cursors are unpadded-base64url encodings of a versioned internal
  JSON envelope. They bind to `instanceId`, collection, ordering, filters,
  high-water mark, and exclusive keyset position. Page size may change between
  requests because it is not an ordering or filter.
- Malformed, unsupported-version, wrong-route/order/filter, or internally
  impossible cursors return `INVALID_CURSOR`. A structurally valid cursor for a
  different `instanceId` returns `STALE_CURSOR`. Deletion of active or trace
  entries between pages does not by itself make a cursor stale; traversal skips
  missing entries under the retained keyset position.
- Skill summary `href` values use an API-root-relative form such as
  `skills/CheckDns`. They contain no scheme, authority, leading slash, query,
  fragment, or path escape. Go resolves them only against the already validated
  observability API base.

### DTO Field Sets

- **Instance status:** `instanceId`, top-level
  `consoleCompatibilityVersion`, `observedAt`,
  `liveMonitoringAvailable`, `registeredSkillCount`,
  `activeExecutionCount`, `catalogedTraceCount`,
  `tracePersistencePolicy`, `completionGraceTtl`, and
  `traceCatalogMetadataTtl`.
- **Skill summary:** `registeredName`, `sourcePath`, and `href`.
- **Skill detail:** `registeredName`, `sourcePath`, and unchanged `yaml`.
- **Active summary/detail:** `sessionId`, `traceId`,
  `lastCanonicalSequence`, `startedAt`, `updatedAt`, `elapsedMillis`,
  nullable `entrySkill`, literal `status: "ACTIVE"`, `phase`, `summary`,
  bounded `activePath` entries (`frameId`, `frameType`, `route`),
  `totalFrameDepth`, `activePathTruncated`, the complete current
  `SessionUsageSnapshot` values, and configured quota limits. Internal
  `registryOrdinal` is not serialized.
- **Trace summary/detail:** `traceId`, `sessionId`, `outcome`, `finalizedAt`,
  `sizeBytes`, `persistencePolicy`, and `applicationTraceExpiresAt`.
  Internal `catalogOrdinal`, `artifactPath`, and `FinalizedTraceArtifact` are
  not serialized. PR 06 will add the artifact acquisition link without
  changing these meanings.

All enum values use their exact Java constant spelling, timestamps use UTC
RFC 3339/ISO-8601 strings, durations use ISO-8601 strings, and identifiers are
opaque strings.

## What We’re NOT Doing

- SSE subscription, subscriber admission, or async delivery (PR 05).
- Trace artifact bytes, download admission, attachment headers, or streaming
  expiration races (PR 06).
- A Go client, browser API, browser UI, or MCP adapter.
- OpenAPI, JSON Schema, generated protocol models, or a second compatibility
  version.
- CORS, Actuator/management-listener exposure, listener/TLS configuration, or
  host `SecurityFilterChain` creation or mutation.
- API-key rotation without restart, multiple keys, users, scopes, operation
  auditing, throttling, or unauthenticated mode.
- Offset pagination, server-side pagination sessions, cursor signatures,
  durable cursors, collection snapshots, or tombstones.
- Direct serialization of internal observation or trace-catalog types.
- Filesystem discovery, path-based trace lookup, artifact rewriting, or
  historical trace discovery.
- Sample-application observability enablement and final Phase 1 end-to-end
  wiring, which remain in PR 06. PR 04 documents and tests host pass-through
  behavior.

## Skill-Authoring Documentation Impact

**Impact**: No impact

- **Rationale**: PR 04 adds an application-operator transport and application
  configuration. It does not change YAML syntax, validation, skill identity,
  mappings, planning/execution semantics, RBAC for skill invocation, evidence,
  inputs/outputs, attachments, models, quotas, or trace meaning. It transports
  the already registered unchanged YAML and existing bounded runtime facts.
- **Documents to update**: None under `ai/skill-authoring/`.
- **Supporting evidence**:
  `DefaultRegisteredSkillCatalogTest` protects exact YAML preservation and
  source-path safety; `ActiveExecutionSnapshot` and
  `BifrostPublicSurfaceArchitectureTest` protect the bounded diagnostic
  projection; new REST fixture tests will prove transport without changing the
  source semantics.
- **Coverage table update**: Not required. No authoring topic is added and no
  coverage or confidence classification changes.
- **LLM-first usability**: Not applicable. Application-operator setup belongs
  in the main README, not in skill-authoring routing.

## Contract and Compatibility Impact

| Surface | Classification and supporting evidence | Planned compatibility treatment |
| --- | --- | --- |
| Application API | No change to the seven-type `com.lokiscale.bifrost.api` allowlist. Host developers gain documented opt-in properties and a Spring Security pass-through requirement, not a Java invocation API. | Preserve the existing API exactly. |
| Supported SPI | No supported SPI exists. Filters, access services, route registrars, DTOs, activation gates, and catalog changes remain framework-owned. | No SPI added; no override/back-off seam or `@ConditionalOnMissingBean`. |
| Configuration and manifest contracts | Add strict `bifrost.observability.*` application configuration. YAML manifests and registered YAML content are unchanged. | Add one coherent configuration contract, metadata, validation tests, and README guidance. Unknown fields remain rejected. |
| Persisted or serialized contracts | Add the release-matched REST pages, detail DTOs, headers, links, cursor meanings, and problem envelope consumed by future Go. Cursors are transient tokens, not persisted data. | Establish the contract through deterministic Java-produced fixtures and exact serialization tests. Coordinate later Java/Go changes under the product release string. |
| Ephemeral diagnostic formats | Project existing current-instance skill, active, and trace-catalog facts. No canonical trace or NDJSON meaning changes. | Preserve current-run accuracy, bounds, no-store behavior, sensitive authentication exclusion, and live fail-closed semantics. |
| Internal or accidentally exposed implementation | Change internal active traversal to include an exclusive before ordinal; add skill/trace count operations; wire existing public-internal observation collaborators into the adapter. | Update all implementations, anonymous fallbacks, tests, and architecture reasons atomically. Do not retain the incomplete traversal overload. |

- **Evidence of supported contracts**: the approved PR 04 ticket; Phase 1
  application boundary; Phase 2 `TargetContext`, Go protocol, pagination, and
  skill-service consumers; Phase 3 shared-service/MCP consumers; future
  Java-produced fixtures.
- **Intended breaks**: replace the internal
  `ActiveExecutionRegistry.newestFirst(highWaterMark, limit)` signature with
  `newestFirst(highWaterMark, beforeOrdinal, limit)`. This is an approved
  internal correction with no protected application consumer. No supported
  break is introduced.
- **In-repository consumers to update**: the active registry implementation and
  tests; observation-factory unavailable catalog; auto-configuration tests and
  closed bean-factory list; public-surface architecture classifications;
  configuration metadata tests; fixture README/corpus test; main README.
- **Public-surface delta**: add
  `BifrostObservabilityWebAutoConfiguration` as a public Spring Boot
  integration type and public nested observability property accessors under
  `BifrostProperties`. Add no Application API or Supported SPI. Keep all
  controllers, DTOs, filters, access services, tokens, cursor types, activation
  types, and registrars under `com.lokiscale.bifrost.internal`.
- **Shim decision**: **No shim.** The only replaced signature is internal and
  has no protected consumer; all repository callers change atomically.
- **Java-to-Go boundary coordination**: **Required.** PR 04 establishes the Java
  producer, exact release marker, REST/problem fixtures, and tests before a Go
  consumer exists. PR 06 extends the same corpus with SSE/acquisition
  integration, and PR 09 implements the Go target client against it. After a Go
  consumer exists, any changed REST, problem, SSE, acquisition, or consumed
  NDJSON meaning must update Java, Go, fixtures, tests, and documentation in the
  same release.

## Implementation Approach

Use two auto-configuration layers:

1. existing core auto-configuration consumes an optional internal activation
   gate when constructing `BifrostSessionRunner`; and
2. a servlet/MVC-conditional web auto-configuration owns activation validation,
   collision inspection, route registration, authentication, and REST beans.

The activation gate begins pending and exposes delegating
`ExecutionObservationHandleFactory` and `CompletionGraceRetention`
collaborators. Pending or disabled delegates behave exactly like today’s no-op
factory and immediate retention. After every singleton and host MVC mapping is
known, the route registrar either:

- constructs the enabled registries/catalogs, generates identity, registers the
  complete route set, and atomically commits the gate; or
- records one sanitized activation diagnostic, leaves the gate permanently
  disabled, registers no Bifrost handler, and makes the namespace filter
  transparent.

This sequencing prevents a route collision from enabling completion grace or
observation before the adapter knows it owns the namespace.

Use Spring MVC programmatic `RequestMappingInfo` registration rather than
annotated controllers. It permits collision inspection before any Bifrost
mapping exists and lets the complete route set appear atomically. Register a
namespace fallback alongside the exact GET mappings so unsupported or unknown
reserved paths cannot fall through to host handlers after successful
activation.

Use a servlet `Filter` registered only for the reserved namespace and ordered
immediately after Spring Security’s conventional filter registration. The host
must permit the namespace through its own authorization layer. The filter
validates one bounded header, compares UTF-8 bytes with
`MessageDigest.isEqual`, installs a new request-local Spring Security context
containing only `BIFROST_OPERATOR`, invokes the chain, and restores the original
context in `finally`. It does not define a security chain or persist
authentication.

Keep serialization explicit. Controllers map internal values to boundary
records. A page writer uses the application `ObjectMapper` to serialize the
exact final bytes, removes trailing complete items until the body fits, and
writes those same bytes unchanged. This prevents measurement with one JSON
shape followed by MVC reserialization with another.

## Phase 1: Configuration, Activation, and Runtime Composition

### Overview

Add the opt-in configuration and optional web dependencies, provide a reliable
release source, correct internal traversal/count gaps, and make production
session creation switch atomically between disabled and enabled observation.

### Changes Required

#### 1. Optional servlet build surface and release resource

**Files**:

- `bifrost-spring-boot-starter/pom.xml`
- `bifrost-spring-boot-starter/src/main/resources-filtered/META-INF/bifrost-release.properties`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/BifrostReleaseVersionTest.java`

**Changes**:

- Add optional compile dependencies needed to implement Spring MVC and the
  Jakarta servlet filter without forcing the host to install a web server.
- Add test dependencies for servlet web, MockMvc/random-port tests, and a host
  Spring Security chain.
- Configure only the dedicated filtered resource directory and write
  `consoleCompatibilityVersion=${project.version}` into the classpath resource.
- Add an internal loader that rejects a missing, blank, duplicated, or
  placeholder value and returns the complete string unchanged.
- Prove test/class-directory and packaged-JAR loading report
  `0.1.0-SNAPSHOT`, including the qualifier.

#### 2. Strict observability properties and metadata

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostProperties.java`
- `bifrost-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostPropertiesTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/ConfigurationMetadataTest.java`

**Changes**:

- Add nested `Observability` and `Auth` properties with the settled names and
  defaults.
- Keep syntactic Spring binding strict. Perform enabled-mode semantic
  validation inside activation so a missing/weak key or out-of-range parsed TTL
  disables only this optional adapter.
- Ensure property/string representations never include the key.
- Extend generated metadata assertions to cover all four settings, their
  defaults, secret guidance, and duration types.

#### 3. Complete internal catalog traversal and counts

**Files**:

- `.../runtime/observation/ActiveExecutionRegistry.java`
- `.../runtime/observation/InMemoryActiveExecutionRegistry.java`
- `.../runtime/observation/catalog/RegisteredSkillCatalog.java`
- `.../runtime/observation/catalog/DefaultRegisteredSkillCatalog.java`
- `.../runtime/observation/catalog/FinalizedTraceCatalog.java`
- `.../runtime/observation/catalog/InMemoryFinalizedTraceCatalog.java`
- `.../runtime/observation/DefaultExecutionObservationHandleFactory.java`
- corresponding active, skill-catalog, finalized-catalog, and factory tests

**Changes**:

- Replace active traversal with
  `newestFirst(highWaterMark, beforeOrdinal, limit)`; zero retains the existing
  first-page sentinel meaning, and later pages filter below the exclusive
  ordinal.
- Add `registeredSkillCount()` and `catalogedTraceCount()` operations. Trace
  count must exclude entries already expired at the injected clock even if the
  scheduled sweep has not reclaimed them.
- Update the unavailable anonymous catalog, implementations, concurrency
  behavior, overflow tests, deletion-between-page tests, and count/expiry tests.
- Do not expose ordinals through REST.

#### 4. Pending-to-committed observability activation

**Files**:

- new internal activation/runtime types under
  `com.lokiscale.bifrost.internal.observability`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- new `BifrostObservabilityWebAutoConfiguration.java`
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `BifrostAutoConfigurationTests.java`
- `BifrostAutoConfigurationBoundaryTest.java`
- `BifrostPublicSurfaceArchitectureTest.java`

**Changes**:

- Add a one-way `PENDING -> ENABLED|DISABLED` activation coordinator with
  sanitized reason categories, not exception payloads or credentials.
- Supply gated observation and completion-retention delegates to the production
  `BifrostSessionRunner`. Pending/disabled behavior is no-op plus immediate
  retention; enabled behavior uses `DefaultExecutionObservationHandleFactory`
  and `ScheduledCompletionGraceRetention`.
- On commit, compose one clock, instance UUID, active registry, replay buffer,
  availability latch, registered-skill catalog, finalized-trace catalog, and
  configured grace scheduler. Close all owned schedulers/catalogs at context
  shutdown.
- In a non-servlet/non-MVC application, keep the gate disabled and log one
  actionable message if the developer requested enablement.
- Register the servlet auto-configuration only when the application is a
  servlet web application and MVC classes are present.
- Extend the exact auto-configuration import, bean factory, integration-type,
  and technically-public-internal allowlists. Do not add
  `@ConditionalOnMissingBean`.

### Success Criteria

#### Automated Verification

- [x] Focused configuration, catalog, activation, and auto-configuration tests
  pass:
  `./mvnw -pl bifrost-spring-boot-starter -Dtest=BifrostPropertiesTest,ConfigurationMetadataTest,InMemoryActiveExecutionRegistryTest,DefaultRegisteredSkillCatalogTest,InMemoryFinalizedTraceCatalogTest,BifrostAutoConfigurationTests,BifrostAutoConfigurationBoundaryTest,BifrostPublicSurfaceArchitectureTest test`
- [ ] A disabled or non-web application creates no instance identity, catalog
  scheduler, grace scheduler, or enabled observation handle.
- [ ] A valid pending activation exposes no observation or grace behavior
  before route ownership commits.
- [x] Active pagination traverses multiple pages without duplicates or newly
  inserted ordinals, while removals may create documented gaps.
- [x] Version tests prove the exact complete Maven release string.

#### Manual Verification

- [ ] Inspect generated Spring configuration metadata in an IDE and confirm the
  properties, defaults, and secret descriptions are discoverable.
- [ ] Start the existing sample without observability configuration and confirm
  startup and execution behavior are unchanged.

---

## Phase 2: Namespace Ownership, Authentication, and Common HTTP Policy

### Overview

Commit activation only after proving exclusive namespace ownership, then
authenticate the route without altering host security and apply shared response
security/identity behavior.

### Changes Required

#### 1. Route collision inspection and atomic registration

**Files**:

- new `ObservabilityRouteCollisionDetector.java`
- new `ObservabilityRouteRegistrar.java`
- new unannotated `ObservabilityRestController.java`
- new `ObservabilityApiPaths.java`
- servlet auto-configuration and focused registrar/collision tests

**Changes**:

- After singleton initialization, inspect host
  `RequestMappingHandlerMapping`, functional router mappings, and explicit URL
  handler mappings for paths that live inside or broadly overlap the reserved
  namespace. Ignore framework fallback/static-resource mappings that cannot
  outrank an explicit observability route.
- Treat exact, variable, wildcard, and catch-all host mappings that can claim
  the namespace as collisions. Include future Phase 1 reserved children such as
  activity and trace artifact paths in namespace ownership, not only the seven
  current GET routes.
- If inspection cannot safely classify an application-defined mapping that may
  claim the namespace, disable observability rather than silently shadow it.
- On success, register all exact routes plus an authenticated namespace
  fallback in one registrar operation, then commit activation.
- On failure, register none, disable the gate, make the filter transparent, and
  leave the host handler and unrelated routes usable.
- Record registered mappings and unregister them during context destruction.

#### 2. Route-scoped authentication and authorization

**Files**:

- new `ObservabilityApiKeyFilter.java`
- new internal authentication token/authority types
- new `ObservabilityAccessService.java` and operation enum
- web auto-configuration and focused filter/access tests

**Changes**:

- Register one servlet filter only for the reserved namespace, with request and
  future async dispatch support and an order immediately after the conventional
  Spring Security filter.
- While activation is pending/disabled, pass through without reading a key or
  claiming the namespace.
- Require exactly one `X-Bifrost-Api-Key` header and reject blank, multiple,
  oversized, whitespace/control-bearing, query-parameter, or otherwise invalid
  presentations.
- Compare bounded UTF-8 byte arrays safely and never log the presented or
  configured value.
- For a valid key, install a fresh request-local context containing the sole
  `BIFROST_OPERATOR` authority, log successful adapter authentication without
  sensitive data, and restore the complete prior context in `finally`.
- Centralize operation authorization behind internal runtime/catalog/trace
  operation names, all initially mapped to the sole authority.

#### 3. Common response metadata and problem boundary

**Files**:

- new `ObservabilityProblem.java`
- new problem code/exception mapper
- filter/controller response writer tests

**Changes**:

- Apply `Cache-Control: no-store` to every namespace response, including
  authentication rejection.
- Add `X-Bifrost-Instance-Id` after valid authentication and keep it identical
  to the status-body value.
- Map only the approved code/status pairs. Sanitize messages and never include
  exception messages, class names, stack traces, paths, YAML, API keys, request
  headers, or diagnostic payloads.
- Preserve the critical distinction between adapter-produced
  `BIFROST_API_KEY_REJECTED` and a host/proxy `401` or `403` that occurs before
  this filter.
- Ensure unexpected adapter failures become `APPLICATION_ERROR` only before
  response commitment and remain logged with safe structured context.

### Success Criteria

#### Automated Verification

- [ ] Focused security and routing tests pass:
  `./mvnw -pl bifrost-spring-boot-starter -Dtest='*Observability*FilterTest,*Observability*Route*Test,*Observability*ProblemTest' test`
- [x] Exact, variable, and catch-all route collisions disable every Bifrost
  observability route but leave the host collision route and ordinary business
  routes usable.
- [ ] A `/orders` servlet context exposes the adapter only under
  `/orders/_bifrost/observability/v1/**`.
- [ ] Missing, invalid, duplicate, or oversized keys return the exact rejection
  problem without `X-Bifrost-Instance-Id`.
- [ ] Valid authentication restores an existing host `SecurityContext` after
  success, controller failure, and exception paths.
- [ ] A host security chain with namespace `permitAll` reaches Bifrost
  authentication; without pass-through, the host’s generic rejection remains
  distinguishable and contains no forged Bifrost problem code.
- [x] No Bifrost bean defines or mutates a `SecurityFilterChain`.

#### Manual Verification

- [ ] Run an enabled application behind a host authenticated-by-default
  security chain, add only the documented namespace pass-through, and confirm
  the Bifrost key remains required.
- [ ] Confirm unrelated authenticated application endpoints retain their
  original host identity and access rules.

---

## Phase 3: REST DTOs, Pagination, and Snapshot Resources

### Overview

Create the explicit wire model, bounded continuation machinery, and seven
read-only resources over the committed activation context.

### Changes Required

#### 1. Hand-authored DTO projections

**Files**:

- new DTO records under
  `com.lokiscale.bifrost.internal.observability.web.dto`
- new `ObservabilityDtoMapper.java`
- mapper and JSON serialization tests

**Changes**:

- Implement exactly the settled fields and Jackson inclusion rules.
- Derive `elapsedMillis` from the page/detail `observedAt`, clamping only clock
  skew below zero to zero; never mutate the internal snapshot.
- Project configured quota values separately from observed usage, preserving
  zero as the framework’s existing unlimited value.
- Build only API-root-relative skill links from validated registered names.
- Never expose internal ordinals, artifact objects, paths, exceptions, Spring
  resources, or raw trace content.
- Assert exact property names, enum spelling, time/duration encoding, null
  behavior, and absence of accidental getters.

#### 2. Opaque cursor codec and keyset services

**Files**:

- new `ObservabilityCursorCodec.java`
- per-collection cursor payload records
- new collection query services and cursor tests

**Changes**:

- Encode a versioned JSON envelope with the application `ObjectMapper`, then
  unpadded base64url; reject input above a small fixed encoded length before
  decoding.
- Bind instance, endpoint, order, filter fingerprint, high water, and exclusive
  position without embedding secrets or accepting caller-controlled type names.
- Capture trace/active high water on first page. Resume skills after the last
  registered name and active/traces below the last emitted ordinal.
- Capture the current replay cursor near first active-page observation and emit
  it as a decimal string only on the initial active page.
- Fetch at most requested size plus one to determine `hasMore`; do not
  materialize an entire registry/catalog.
- Apply the settled invalid-versus-stale mapping and deletion-between-pages
  behavior.

#### 3. Exact bounded collection serialization

**Files**:

- new `BoundedJsonPageWriter.java`
- response-size and serialization tests

**Changes**:

- Validate `pageSize` and reject unknown/duplicated query parameters.
- Map at most 5,001 bounded summaries.
- Serialize the complete prospective page, including metadata and cursor, using
  the injected application `ObjectMapper`.
- If it exceeds 16 MiB, remove trailing complete items, recompute the
  continuation, and serialize again until it fits. Write those exact bytes as
  the HTTP body so MVC cannot reserialize them differently.
- Return `LIMIT_EXCEEDED` if fixed envelope metadata or one summary cannot fit.
- Preserve `hasMore=true` whenever the item-count or byte boundary stops a
  traversal that can continue.

#### 4. Instance, skill, active, and trace handlers

**Files**:

- `ObservabilityRestController.java`
- focused handler/service tests

**Changes**:

- Implement all seven GET routes and the namespace fallback.
- Assemble status from one observation time and committed runtime context.
  Read `consoleCompatibilityVersion` from the filtered release resource.
- Use exact, case-sensitive registered-skill lookup and return unchanged YAML.
- Reject active list/detail with `LIVE_MONITORING_UNAVAILABLE` when the latch is
  false; leave status, skills, and traces usable.
- Map absent current resources to `NOT_FOUND` without claiming they expired or
  previously existed.
- Treat all resources as current-instance and read-only.

### Success Criteria

#### Automated Verification

- [ ] Focused protocol/resource tests pass:
  `./mvnw -pl bifrost-spring-boot-starter -Dtest='*Observability*DtoTest,*Observability*CursorTest,*BoundedJsonPageWriterTest,*Observability*ControllerTest' test`
- [ ] Every response matches its exact approved field set and excludes internal
  types and filesystem paths.
- [ ] Default, minimum, maximum, invalid, byte-limited, empty, single-item, and
  multi-page collections are covered.
- [ ] Cross-endpoint, changed-instance, malformed, oversized, unsupported
  version, impossible-position, and changed-query cursor cases return the
  approved problems.
- [ ] Concurrent insertions do not shift later active/trace pages; removals may
  create gaps without duplicates or cursor failure.
- [ ] The first active page includes a usable replay cursor captured near its
  high water; subsequent active pages do not replace it.
- [ ] Live failure blocks only active resources.
- [x] Serialized collection bodies are at most exactly 16 MiB and contain only
  complete JSON items.

#### Manual Verification

- [ ] Use `curl` with the configured key to inspect all seven routes and follow
  a returned skill `href` against the fixed API base.
- [ ] Restart the application and confirm status reports a new instance UUID
  and an old continuation returns `STALE_CURSOR`.
- [ ] Trigger or fixture live-monitoring unavailability and confirm skills and
  traces remain readable while active resources return the expected `503`.

---

## Phase 4: Executable Contract Fixtures and Developer Integration

### Overview

Protect the new boundary as the future Go client’s input and document the
application-owner responsibilities without moving PR 05/06 behavior into this
change.

### Changes Required

#### 1. Java-produced REST/problem fixture corpus

**Files**:

- new `bifrost-console-fixtures/application-rest/` fixture tree
- `bifrost-console-fixtures/README.md`
- new
  `bifrost-spring-boot-starter/src/test/java/.../ConsoleRestFixtureCorpusTest.java`

**Changes**:

- Generate deterministic representative bodies for instance, skill page/detail,
  active page/detail, trace page/detail, empty and continued pages, and every
  stable problem code applicable to PR 04.
- Use fixed clocks, UUIDs, snapshots, configuration, and catalog entries.
- Byte-compare the full inventory in normal tests and support an explicit
  regeneration property matching the existing trace-fixture workflow.
- Document that PR 06 extends this corpus with SSE, artifact streaming, final
  application integration, and transport metadata while PR 09’s Go tests
  consume it.
- Require two regeneration runs with no second diff.

#### 2. Servlet and host-security integration suite

**Files**:

- new MockMvc and random-port test applications under starter tests
- existing auto-configuration and architecture test suites

**Changes**:

- Cover disabled, invalid, valid, non-web, collision, context-path, unknown
  route, unsupported method, and clean shutdown behavior.
- Exercise both no-host-security and host-security-pass-through applications.
- Prove adapter-produced and upstream/host authentication failures remain
  distinguishable.
- Exercise identity/header consistency, compatibility string, no-store,
  pagination, problem sanitation, response limits, and route exclusivity at the
  servlet boundary rather than only through directly constructed services.
- Verify enabled observation actually creates/removes active entries and
  publishes finalized metadata, while disabled behavior remains current no-op.

#### 3. Application-developer documentation

**Files**:

- `README.md`

**Changes**:

- Document opt-in properties, defaults, key generation/externalization,
  restart-only rotation, ordinary listener/context-path behavior, HTTP versus
  HTTPS risk, and no CORS/Actuator behavior.
- Provide a current Spring Security example that permits only the reserved
  namespace through the host layer while keeping Bifrost API-key authentication
  mandatory.
- Explain that generic upstream `401/403` differs from
  `BIFROST_API_KEY_REJECTED`.
- State that operator access exposes all registered YAML and current diagnostic
  data, that application-provided content is not secret-scanned/redacted, and
  that authentication secrets are never returned.
- Document disabled/collision diagnostics and the read-only PR 04 route set.
- Do not update skill-authoring guidance or enable the sample; PR 06 owns final
  sample and full Phase 1 operational wiring.

### Success Criteria

#### Automated Verification

- [x] Deterministic fixture generation passes:
  `./mvnw -pl bifrost-spring-boot-starter -Dtest=ConsoleRestFixtureCorpusTest test`
- [x] Full starter tests pass:
  `./mvnw -pl bifrost-spring-boot-starter test`
- [x] Full reactor tests pass:
  `./mvnw test`
- [x] A deliberate fixture regeneration followed by a second regeneration
  produces no second Git diff.
- [x] Architecture tests show no new Application API or SPI and classify the
  web auto-configuration as a Spring integration surface.
- [x] README examples contain no literal usable credential.

#### Manual Verification

- [ ] Follow the README in a servlet sample with and without Spring Security and
  confirm the documented responses.
- [ ] Verify startup diagnostics are actionable but contain no API key, raw
  exception detail, filesystem path, or YAML.
- [x] Review fixture JSON as the contract that PRs 06 and 09 will consume.

## Testing Strategy

### Unit Tests

- Property defaults, validation, secret-safe representations, and metadata.
- Release-string loading from classpath and packaged artifacts.
- Activation state transitions, idempotence, cleanup, and disabled delegates.
- Active exclusive-keyset traversal and catalog counts/expiry.
- Header parsing, constant-time bounded comparison, context restoration, and
  access-operation mapping.
- Collision pattern classification and registrar rollback.
- DTO mapping and exact Jackson shapes.
- Cursor round trips, binding, invalidity, staleness, and size bounds.
- Whole-item JSON page budgeting at boundary sizes.
- Problem classification and sanitization.

### Integration Tests

- Servlet context paths and actual route registration.
- Host Spring Security pass-through versus upstream rejection.
- Disabled, invalid, collision, and enabled auto-configuration.
- Authentication, instance metadata, no-store, compatibility, every resource,
  pagination, live failure, and clean shutdown.
- Representative execution lifecycle from session creation through active
  removal and finalized-catalog publication.
- Java-produced REST/problem fixture corpus.

Create the dedicated testing-plan artifact with `ai/commands/3_testing_plan.md`
before implementation. It should identify the failing test introduced first
for each behavior, map representative coverage to `WF-SLOW-EXECUTION` and
`WF-UNFAMILIAR-SKILL-PATH`, and give exact exit commands and fixture
regeneration checks.

### Manual Testing Steps

1. Start a servlet application with observability absent; verify no namespace
   route and unchanged Bifrost execution.
2. Enable observability with an externalized generated key; query instance
   status and verify body/header identity plus exact release.
3. Exercise correct, missing, invalid, and host-blocked credentials.
4. Traverse every collection with a small page size and follow the skill link.
5. Repeat under a non-root servlet context and representative reverse-proxy
   external path.
6. Add a colliding host handler and verify the host route remains usable while
   the optional adapter is fully disabled.
7. Restart and verify identity/cursor invalidation.

## Performance Considerations

- Authentication work is bounded to at most 512 ASCII bytes and performs no
  password hashing, network access, or session lookup.
- REST list queries fetch at most 5,001 bounded summaries and never copy an
  entire registry or catalog.
- JSON budgeting may serialize more than once only when the byte ceiling removes
  trailing items. It never performs per-item whole-page serialization.
- Status counts perform no filesystem I/O. Finalized-trace count may scan
  bounded metadata to exclude entries whose TTL has passed but does not inspect
  artifact bytes.
- DTO projection never copies trace artifacts or arbitrary trace payloads.
- Route and collision inspection occurs once during startup, not per request.
- Filters and response policy do not run on unrelated application paths.

## Migration Notes

- Existing applications are unchanged because observability defaults off and
  servlet dependencies remain optional.
- Enabling observability is a new application configuration and network
  disclosure decision. Applications with outer Spring Security or proxy
  authentication must add namespace pass-through.
- There is no cursor migration or cross-version fallback. Every restart changes
  `instanceId`; every product release string changes compatibility.
- No deprecated path, legacy endpoint, dual JSON model, or compatibility
  overload is introduced.
- The internal active-registry method and catalog count additions are updated
  atomically across the repository.

## References

- Original ticket:
  `ai/thoughts/tickets/bifrost-console-pr-04-spring-rest-adapter.md`
- Related research:
  `ai/thoughts/research/2026-07-24-spring-rest-adapter.md`
- Phase 1 design:
  `ai/thoughts/phases/bifrost_console_phase_1_observability_foundation.md`
- Phase 2 consumer:
  `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md`
- Phase 3 consumer:
  `ai/thoughts/phases/bifrost_console_phase_3_llm_runtime_inspector.md`
- Roadmap:
  `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md`
- Workflows:
  `ai/thoughts/phases/bifrost_console_workflows.md`
- Future adapter tickets:
  `ai/thoughts/tickets/bifrost-console-pr-05-live-sse-delivery.md`,
  `ai/thoughts/tickets/bifrost-console-pr-06-artifact-streaming-integration.md`
- Future consumers:
  `ai/thoughts/tickets/bifrost-console-pr-09-target-context.md`,
  `ai/thoughts/tickets/bifrost-console-pr-10-operational-views.md`,
  `ai/thoughts/tickets/bifrost-console-pr-11-live-execution-experience.md`,
  `ai/thoughts/tickets/bifrost-console-pr-17-mcp-runtime-inspection.md`
- Framework policy:
  `ai/thoughts/framework-feature-design-lens.md`
