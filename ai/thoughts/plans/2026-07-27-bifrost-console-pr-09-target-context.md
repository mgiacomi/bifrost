# Bifrost Console PR 09 — TargetContext and Selected-Target Lifecycle Implementation Plan

## Overview

Implement the Console's single transport-neutral selected-target authority. PR 09
adds strict non-secret target configuration, a bounded Java application protocol
client, process-memory application credentials, exact release compatibility,
immutable target scopes, cancellation and late-result suppression, independent
status facts, shared domain errors, paired-browser target workflows, and the
protected no-echo terminal credential path.

This is the lifecycle and trust boundary reused by PRs 10–19. It must therefore
land as shared Go services below browser handlers rather than as browser-specific
connection state.

## Current State Analysis

- `console.Run` assembles profile ownership, workspace ownership, process
  lifecycle, browser pairing/sessions, and the loopback host, but it has no
  application client or selected-target authority
  (`bifrost-console/internal/console/service.go:33-145`).
- Version-1 Console YAML currently permits only `listener` and
  `trace-workspace`; its explicit allowlist rejects target configuration
  (`bifrost-console/internal/config/config.go:5-52`,
  `bifrost-console/internal/config/decode.go:42-63`).
- Browser routes already enforce Host, Origin, paired-session, and CSRF controls
  in the correct order and bound request JSON to 1 KiB
  (`bifrost-console/internal/browserapi/router.go:34-127`,
  `bifrost-console/internal/browserapi/errors.go:10-49`).
- The browser has only pairing/session state and a foundation route. There is no
  target status store, target form, reconnect operation, scope-reset boundary,
  or target error vocabulary
  (`bifrost-console/web/src/security/sessionReducer.ts:1-24`,
  `bifrost-console/web/src/app/App.tsx:6-53`).
- The Java application boundary already exposes authenticated
  `GET /_bifrost/observability/v1/instance`, requires exactly one
  `X-Bifrost-Api-Key`, and returns `X-Bifrost-Instance-Id`
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiPaths.java:3-13`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/web/ObservabilityApiKeyFilter.java:22-68`).
- Java-produced instance and problem fixtures exist, but no Go consumer
  currently guards that boundary
  (`bifrost-console-fixtures/README.md:21-34`).

## Desired End State

After PR 09:

1. An optional static YAML target default is validated before the listener
   opens. Trust and timeout settings are restart-only and contain no credential.
2. One `TargetContext` owns the normalized target, credential generation,
   current scope, identity, compatibility, status, retry lifecycle, and
   registered scope-owner invalidation.
3. Every target operation captures an immutable scope capability and must pass a
   current-scope check before returning or publishing a result.
4. Target or connection-authority replacement, replacement credentials, and a
   changed established application `instanceId` rotate the opaque scope, cancel
   prior work, and clear registered owner state. Initial credentials for an
   already selected credential-less target do not cause a second rotation.
5. The application client sends a credential only to the normalized selected
   origin, follows no redirects, uses normal TLS validation plus an optional
   custom CA bundle, applies concrete resource bounds, and requires the exact
   complete Bifrost release string before consuming the full instance DTO.
6. Browser and terminal credential entry populate the same in-memory provider
   and invoke the same `TargetContext` operations.
7. Paired browser bootstrap/status exposes independent target facts, and the
   Overview supports selection, credential replacement, explicit recheck, and
   clear sanitized failure presentation without exposing the credential.
8. A target-scope change replaces browser navigation with Overview and remounts
   the target-scoped UI boundary so future PRs cannot retain stale application
   state accidentally.

Verification is complete when fixture-backed protocol tests, target race and
lifecycle tests, browser API/UI tests, terminal tests, Go race tests, frontend
verification, and the canonical Console build all pass.

### Key Discoveries

- The exact complete `consoleCompatibilityVersion`, including qualifiers, is
  the only Java/Go compatibility gate; no secondary protocol or trace version
  may be introduced
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:308-321`).
- Status is a side-effect-free projection. It performs no target request,
  credential mutation, reconnect, workspace repair, or catalog query
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:351-379`).
- Authentication rejection does not itself rotate scope or invalidate complete
  current-scope acquired evidence. Credential replacement does rotate scope
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:337-347`).
- A generic upstream/proxy 401 or 403 is not evidence that the Bifrost key was
  invalid. Only the recognized Java `BIFROST_API_KEY_REJECTED` problem maps to
  `TARGET_AUTHENTICATION_REQUIRED`
  (`ai/thoughts/research/2026-07-26-bifrost-console-pr-09-target-context.md:331-359`).
- PRs 10–18 consume this scope, status, cancellation, and error seam. Browser
  handlers cannot own semantics that later MCP handlers need
  (`ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:108-129`).

## What We're NOT Doing

- Continuous upstream SSE, recent-activity storage, replay, or browser live
  relay; those belong to PR 11.
- Skill, active-execution, or trace-catalog queries and their views; those
  belong to PR 10.
- Artifact acquisition, trace parsing, analysis, handles, continuations, or
  local trace-storage UI; those belong to PRs 12–14.
- MCP routes, MCP authentication, SDK types, or MCP credential changes; those
  begin in PR 16.
- Multiple selected targets, target discovery, redirect discovery, environment
  proxy support, client certificates, OAuth, Basic authentication, refresh
  tokens, or alternative application authentication headers.
- Browser editing or persistence of static YAML. Browser-selected targets remain
  process-local.
- HTTPS verification bypasses, `insecure-skip-verify`, certificate pinning, or a
  browser-managed CA upload.
- A health/readiness/degraded aggregate, historical status log, target polling
  after success, or a general troubleshooting state machine.
- An OpenAPI/JSON-Schema/code-generation system or an independent browser API
  compatibility version.

## Settled Implementation Decisions

### Version-1 Target YAML

The existing version number remains `1`; this is an intentional additive
pre-release extension to the strict current schema:

```yaml
target:
  address: https://application.example/context
  connect-timeout: 5s
  response-header-timeout: 10s
  request-timeout: 30s
  ca-bundle: ./private-ca.pem
```

- The entire `target` section is optional. Omission means no default selected
  target.
- `address` is required and nonblank when `target` is present.
- All three timeouts are optional inside `target` and resolve to the values
  shown above.
- Durations use the existing positive canonical integer `s`, `m`, or `h`
  syntax. There is no `never` value for network timeouts.
- `ca-bundle` is optional. A relative path resolves against the resolved
  configuration file's parent directory; an absolute path remains absolute.
- The CA file is loaded at startup, must resolve to a regular file no larger
  than 1 MiB, must contain at least one PEM certificate, and augments the
  operating-system root pool. It never replaces system roots and never enables
  insecure verification.
- Invalid target or CA configuration fails startup before any listener opens,
  even when the target will not be contacted immediately.
- Browser-selected target addresses reuse the statically resolved trust and
  timeout policy and are not written back to YAML.

### URL and Authority Normalization

Validation occurs before selection or rotation:

- accept only hierarchical `http` and `https` URLs;
- require a nonempty, unambiguous ASCII DNS name or IP literal and a valid
  optional numeric port;
- lowercase scheme and DNS host, canonicalize IP literals, and omit an explicit
  default port (`80` for HTTP or `443` for HTTPS);
- reject user information, query, fragment, opaque URLs, IPv6 zone identifiers,
  non-ASCII authority, percent-encoded authority, backslashes, encoded path
  separators, repeated separators, dot segments, and malformed escapes;
- accept either no path or one clean absolute externally visible context path;
- preserve the clean context path and remove its trailing slash before appending
  `/_bifrost/observability/v1`; and
- expose only the normalized safe address to status and browser consumers.

The client does not use `HTTP_PROXY`, `HTTPS_PROXY`, or `NO_PROXY`. Initial
requests are direct so application credentials traverse only the explicitly
selected target authority. Explicit proxy support requires a later
credential-forwarding and authority design.

### Concrete HTTP, TLS, and Retry Bounds

- Dial/connect timeout: configured value, default 5 seconds.
- TLS handshake timeout: the configured connect timeout.
- Response-header timeout: configured value, default 10 seconds.
- Overall bounded REST request timeout: configured value, default 30 seconds.
- Maximum upstream response headers: 64 KiB.
- Maximum instance or problem body: 64 KiB.
- Maximum submitted target address: 2 KiB; surrounding whitespace is rejected
  rather than trimmed.
- Maximum browser target-operation JSON body: 4 KiB, sufficient for the 2 KiB
  address, maximum 512-byte printable key, JSON escaping, and fixed field
  overhead.
- Automatic decompression: disabled; the instance probe requests identity
  encoding and rejects an unsupported content encoding.
- Maximum idle connections: 16 total and 4 per selected host.
- Idle connection timeout: 90 seconds.
- Expect-continue timeout: 1 second.
- Redirect policy: return the redirect response without issuing another request;
  map it to the safe `redirect` transport category.
- Internal application-client requests are never automatically replayed.

Only the target probe/reconnect coordinator retries transient DNS, connection,
timeout, and upstream 5xx failures. It uses one current-scope timer and one
serialized probe with delays of 1, 2, 4, 8, 16, then 30 seconds, retaining a
30-second cap with ±20 percent injected/testable jitter. It continues while the
scope remains current and the failure remains retryable. A manual recheck
cancels the pending delay and requests an immediate serialized probe.

Success stops retrying. Missing/rejected credentials, access blocking, TLS
validation, redirects, namespace-not-found, invalid upstream protocol, and
incompatibility wait for explicit recheck or an authoritative
target/credential/configuration change. There is no polling after success.

### Target Mutation and Rotation Rules

| Event | Scope behavior | Status/probe behavior |
| --- | --- | --- |
| No target at startup | No scope ID | All target facts are `NOT_APPLICABLE`. |
| YAML default selected | Create one fresh process-local scope | Authentication is `REQUIRED`; compatibility is `NOT_CHECKED`. |
| Browser selects address and supplies key atomically | Rotate once from any prior selection, or create the first scope | Store key in the new generation and probe. |
| First credential supplied to a selected credential-less target | Preserve that target's existing scope | Establish the first credential generation and probe. |
| Credential replaced when one already exists | Rotate immediately without comparing secret bytes | Store the new generation and probe. |
| Connection-authority settings change | Rotate | Build a new scoped client and probe when a credential exists. |
| First compatible authenticated instance response | Preserve scope | Commit identity and live-monitoring fact. |
| Later serialized probe sees the same instance | Preserve scope | Refresh independent status facts. |
| Later serialized probe sees a changed established instance | Rotate before publishing new-runtime facts | Preserve target and credential in the new scope; commit new identity only in that scope. |
| Authentication/access/compatibility/temporary transport failure | Preserve scope | Update independent status facts; retain prior complete evidence under future owner policies. |
| Console shutdown | Cancel current scope and invalidate owners | Publish no later results. |

Rotation ordering is:

1. serialize the authoritative mutation;
2. cancel the prior scope and stop its retry/probe work;
3. make prior-scope current checks fail;
4. synchronously ask registered owners to detach and invalidate visible
   prior-scope state in deterministic registration order;
5. install/publish the new immutable scope; and
6. allow new-scope operations to capture it.

Owner callbacks are internal, bounded, synchronous invalidation hooks. They
return no recoverable error and must not call back into target mutation. An
owner may perform physical cleanup after detaching state, using the cancelled
old-scope context, but it must make stale data unreachable before returning.
Registration is completed during Console assembly before the listener serves.

### Shared Status and Error Shapes

`ConsoleStatusSnapshot` contains the exact conceptual facts settled by Phase 2:

- `observedAt`
- optional `targetScopeId`
- `targetSelection`
- `targetConnection`
- `targetAuthentication`
- `javaGoCompatibility`
- `runtimeIdentity`
- optional `instanceId`
- `liveMonitoring`

It additionally exposes the normalized selected target address and an
`unencrypted` Boolean in the browser adapter's surrounding target block. Those
are target-configuration presentation facts, not new health states.

The shared `consolecore.Error` has a stable code, bounded safe message, optional
operation scope ID, and a typed bounded details structure. PR 09 implements all
settled codes so later shared services extend behavior without redefining the
contract, while PR 09 itself produces only target/configuration-relevant codes.
Initial detail fields cover expected/observed compatibility version, current
scope, safe transport category, limit name/value, and raw-download availability.
Arbitrary maps, raw errors, paths, URLs containing user information, response
bodies, stack traces, or credential-derived values are prohibited.

Safe transport categories are:

- `dns`
- `connection`
- `timeout`
- `tls_untrusted_issuer`
- `tls_hostname_mismatch`
- `tls_expired`
- `tls_not_yet_valid`
- `tls_handshake`
- `redirect`
- `namespace_not_found`
- `upstream_server`
- `upstream_protocol`

The browser error adapter preserves the shared code and typed details. Coarse
HTTP mappings are:

| Shared code | HTTP status |
| --- | ---: |
| `INVALID_ARGUMENT`, `INVALID_CURSOR` | 400 |
| `TARGET_AUTHENTICATION_REQUIRED` | 401 |
| `TARGET_ACCESS_BLOCKED` | 403 |
| `NOT_FOUND` | 404 |
| `INCOMPATIBLE_TARGET`, `TARGET_CHANGED`, `STALE_CURSOR`, `ARTIFACT_EXPIRED`, `LIVE_MONITORING_UNAVAILABLE` | 409 |
| `INVALID_ARTIFACT` | 422 |
| `LIMIT_EXCEEDED` | 429 |
| `TARGET_UNAVAILABLE`, `LOCAL_STORAGE_UNAVAILABLE` | 503 |
| `CONSOLE_ERROR` | 500 |

Browser session rejection remains the existing browser-local 401
`SESSION_REQUIRED`; callers distinguish the stable code rather than treating
every 401 as a browser-session failure.

### Browser Routes

All routes remain POST-only under the existing browser realm:

| Route | Controls | Request | Result |
| --- | --- | --- | --- |
| `/api/console/v1/target/status` | paired session | `{}` | Side-effect-free current target/status projection. |
| `/api/console/v1/target/connect` | paired session + tab CSRF | `{targetAddress, applicationKey}` | Atomic target/key replacement followed by one probe. |
| `/api/console/v1/target/credential` | paired session + tab CSRF | `{applicationKey}` | Initial credential install or authoritative replacement followed by one probe. |
| `/api/console/v1/target/recheck` | paired session + tab CSRF | `{}` | Immediate serialized probe for the current scope. |

Bootstrap includes the same target/status projection so refresh does not need a
probe. Mutating operations may commit the new selection/credential and still
return a shared target error when the probe fails; the committed status remains
available through bootstrap/status. No route returns a credential, key suffix,
credential generation, CA path, or internal transport object.

## Skill-Authoring Documentation Impact

**Impact**: No impact

- **Rationale**: PR 09 changes the separately running developer Console's local
  target selection, authentication, status, and lifecycle. It does not change
  YAML skill syntax, validation, mappings, execution/planning semantics,
  evidence contracts, model selection, capability visibility, inputs/outputs,
  attachments, limits, traces, or skill testing guidance.
- **Documents to update**: None under `ai/skill-authoring/`.
- **Supporting evidence**: The ticket is limited to Console target authority
  (`ai/thoughts/tickets/bifrost-console-pr-09-target-context.md`); the Java
  application API package is not used by this path, and the consumed
  observability adapter is framework-owned internal web infrastructure
  (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:58-60`).
- **Coverage table update**: Not required. No authoring topic is added and no
  coverage/confidence classification changes.
- **LLM-first usability**: Not applicable.

## Contract and Compatibility Impact

| Surface | Classification and supporting evidence | Planned compatibility treatment |
| --- | --- | --- |
| Application API | No ordinary `com.lokiscale.bifrost.api` entry point changes or is consumed. The Console calls only the internal observability web adapter. | Preserve; no application API delta. |
| Supported SPI | No supported Java or Go target SPI exists. Scope owners and clients live under the Go `internal` tree. The Java web beans are framework infrastructure rather than supported replacement points. | No supported SPI added. Keep all new Go seams internal and update them atomically in future Console PRs. |
| Configuration and manifest contracts | Console schema-version 1 gains the documented optional `target` mapping and exact fields/defaults above. Existing fields retain their current meaning. Application `bifrost.observability.*` properties and skill manifests are unchanged. | Intentional additive pre-release Console configuration change. Update decoder allowlists, defaults, validation, README example, and focused tests atomically. No aliases or legacy spellings. |
| Persisted or serialized contracts | The existing authenticated Java REST/problem boundary becomes a live Go consumer. The local browser JSON API gains target/status/error DTOs, but Go and embedded browser ship atomically. | Preserve Java fixtures and exact complete release gate. Add Go semantic consumption tests. Update Go/browser DTOs and fixtures atomically; no browser compatibility version. |
| Ephemeral diagnostic formats | PR 09 does not parse or change NDJSON. It enforces the exact compatibility prerequisite later acquisition and parsing require. | No format change. Do not add trace versions, legacy readers, or compatibility adapters. |
| Internal or accidentally exposed implementation | New package layout, constructors, owner hooks, retry scheduler, terminal adapter, and browser reducers are internal. Existing browser API and Go internal types may change atomically. | One coherent implementation with no compatibility machinery. Update all repository callers/tests together. |

- **Evidence of supported contracts**: The strict Console YAML documented in
  `bifrost-console/README.md`; the Java-generated fixture corpus and its
  current-release contract in `bifrost-console-fixtures/README.md`; the
  approved PR 09 ticket and Phase 2 design.
- **Intended breaks**: None. Previously unknown target YAML fields were
  rejected; adding the approved fields does not reinterpret a previously valid
  document.
- **In-repository consumers to update**: Console config/defaults/README/tests,
  process assembly and CLI tests, browser router and tests, TypeScript client
  and components/tests, browser fixtures, and Go fixture consumers.
- **Public-surface delta**: No supported Java type, constructor, method,
  annotation, bean, or Go external package is added or removed. Browser JSON
  DTOs and new Go types are internal current-release surfaces.
- **Shim decision**: **No shim.** There is no protected prior target
  configuration, Go API, or browser target DTO requiring compatibility
  machinery. Browser and Go change atomically.
- **Java-to-Go boundary coordination**: **Required.** PR 09 starts consuming the
  existing `/instance`, problem, authentication-header, instance-header, and
  exact compatibility semantics. Java production behavior and committed
  fixtures are not planned to change; Go tests must consume them. If
  implementation discovers a producer mismatch, update Java, Go, fixtures,
  focused tests, and protocol documentation in the same PR rather than adding
  permissive parsing.

## Implementation Approach

Use four internal layers:

```text
browserapi / credentialprompt
              |
              v
       internal/target
   authoritative mutation, scope,
   credential generation, probe/retry
        |             |
        v             v
internal/consolecore  internal/applicationclient
status + errors       bounded authenticated wire client
```

`internal/console` remains composition and shutdown ownership. Browser code
adapts `consolecore` DTOs and invokes `target` operations. The application
client does not mutate target state; it returns authenticated wire facts or
sanitized typed failures. Only `TargetContext` decides whether those facts can
be committed and whether identity changes rotate scope.

## Phase 1: Configuration, Target Validation, and Application Protocol Client

### Overview

Extend the strict profile schema and build the bounded, direct, authenticated
application client independently of browser concerns.

### Changes Required

#### 1. Version-1 target configuration

**Files**:

- `bifrost-console/internal/config/config.go`
- `bifrost-console/internal/config/decode.go`
- `bifrost-console/internal/config/values.go`
- `bifrost-console/internal/config/config_test.go`
- `bifrost-console/internal/config/documentation_test.go`
- `bifrost-console/README.md`

**Changes**:

- Add `Target` file/resolved models and the exact YAML fields/defaults from
  Settled Implementation Decisions.
- Preserve omission distinctly from a present invalid/empty mapping.
- Reuse the canonical positive-duration parser but reject `never` for network
  fields.
- Resolve `ca-bundle` against the profile configuration directory rather than
  the process working directory.
- Validate/load the CA bundle during profile startup with bounded reads and
  safe, non-content-bearing errors.
- Keep `DefaultYAML` target-free so a new profile performs no upstream network
  activity and contains no application key.
- Extend README parsing/runtime-flag coverage tests.

#### 2. Target address model and validator

**New files**:

- `bifrost-console/internal/applicationclient/address.go`
- `bifrost-console/internal/applicationclient/address_test.go`

**Changes**:

- Introduce an immutable normalized `Address` with safe display value, scheme,
  authority, context path, derived observability root, and unencrypted fact.
- Apply all approved authority/path rejection and canonicalization rules.
- Compose the fixed namespace structurally rather than through string
  concatenation.
- Add table-driven equivalence, normalization, Unicode/escape, default-port,
  IPv4/IPv6, context-path, user-info, query/fragment, dot-segment, backslash,
  and encoded-separator tests.

#### 3. Custom trust and bounded HTTP transport

**New files**:

- `bifrost-console/internal/applicationclient/transport.go`
- `bifrost-console/internal/applicationclient/transport_test.go`
- `bifrost-console/internal/applicationclient/testcert_test.go`

**Changes**:

- Build a dedicated `http.Transport` with no environment proxy, ordinary
  hostname/certificate verification, optional system-root augmentation, fixed
  connection/header bounds, and redirects disabled at the client.
- Give each scoped client its own transport lifecycle so rotation/shutdown can
  close idle connections.
- Inject dial/TLS seams only where needed for deterministic classification
  tests; do not expose them outside the internal package.
- Test system roots plus custom CA, wrong CA, hostname mismatch, expired and
  not-yet-valid certificates, HTTP targets, redirect non-following, header/body
  limits, unsupported compression, timeout, cancellation, and connection reuse.

#### 4. Instance wire client and problem mapping

**New files**:

- `bifrost-console/internal/applicationclient/client.go`
- `bifrost-console/internal/applicationclient/instance.go`
- `bifrost-console/internal/applicationclient/problem.go`
- `bifrost-console/internal/applicationclient/errors.go`
- `bifrost-console/internal/applicationclient/client_test.go`
- `bifrost-console/internal/applicationclient/contract_test.go`

**Changes**:

- Send exactly one `X-Bifrost-Api-Key`, accept only a 32–512 byte printable
  non-space ASCII key shape, and never add the key to URLs or errors.
- Bound/read the `/instance` body, inspect only the stable top-level
  `consoleCompatibilityVersion` before compatibility is established, and reject
  any non-exact complete release match.
- After a match, decode and validate the full instance response needed by PR 09:
  body/header `instanceId` agreement, `observedAt`, and
  `liveMonitoringAvailable`. Counts and retention facts may be represented in
  the wire DTO for fixture fidelity but do not become PR 09 status fields.
- Recognize the exact Java problem shape and distinguish
  `BIFROST_API_KEY_REJECTED` from generic 401/403.
- Return typed internal failures with safe transport categories and retained
  internal causes; do not format browser/domain errors here.
- Consume the committed `instance-status.json` and all application problem
  fixtures as semantic contract tests, including an exact compatibility
  mismatch mutation.

### Success Criteria

#### Automated Verification

- [x] Config unit/documentation tests pass:
  `go test ./internal/config`
- [x] Application client tests and Java fixture consumption pass:
  `go test ./internal/applicationclient`
- [x] Config tests prove no credential/key field exists and default YAML selects
  no target.
- [x] Redirect, proxy, TLS, body/header, timeout, and credential-leak tests pass.
- [x] Java REST fixture inventory remains unchanged and valid:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter '-Dtest=ConsoleRestFixtureCorpusTest' test`

#### Manual Verification

- [ ] A default HTTPS target works with ordinary public trust.
- [ ] A private test target works only when its CA bundle is configured.
- [ ] An HTTP target is accepted and identified as unencrypted.
- [ ] Invalid target and CA configuration fail before the listener opens with
  safe actionable messages.

---

## Phase 2: Shared Domain Contract and Authoritative TargetContext

### Overview

Create the reusable status/error vocabulary, credential provider, immutable
scope capability, mutation/identity authority, owner invalidation, retry
coordinator, and final stale-result barrier.

### Changes Required

#### 1. Shared status and service errors

**New files**:

- `bifrost-console/internal/consolecore/errors.go`
- `bifrost-console/internal/consolecore/errors_test.go`
- `bifrost-console/internal/consolecore/status.go`
- `bifrost-console/internal/consolecore/status_test.go`

**Changes**:

- Define closed typed codes/enums and typed bounded error details.
- Ensure `Error()` returns only the safe message while `Unwrap()` remains
  internal-diagnostic-only and never reaches an adapter automatically.
- Define immutable status values and constructor validation for allowed
  combinations, including paired absence/presence of scope/identity fields.
- Test every allowed no-target, selected/no-key, probing, reachable,
  authentication-required, access-blocked, incompatible, unavailable, and
  established combination.

#### 2. Process-memory credential provider

**New files**:

- `bifrost-console/internal/target/credentials.go`
- `bifrost-console/internal/target/credentials_test.go`

**Changes**:

- Store credential bytes only in process memory behind an opaque generation.
- Distinguish initial installation from authoritative replacement.
- Never compare old and new replacement values.
- Cancel prior-scope use before clearing superseded bytes; minimize temporary
  copies and clear owned byte slices on replacement/close.
- Expose only a scoped request capability to the application client. Snapshots,
  status, browser DTOs, logs, and test failure output never contain the key.
- Add sentinel-secret tests that inspect errors, formatted values, browser
  fixtures, and captured logs/output.

#### 3. Immutable scope and current-result barrier

**New files**:

- `bifrost-console/internal/target/scope.go`
- `bifrost-console/internal/target/scope_test.go`
- `bifrost-console/internal/target/owners.go`
- `bifrost-console/internal/target/owners_test.go`

**Changes**:

- Generate cryptographically random UUID scope IDs and treat them only as
  opaque equality values.
- Let an operation capture immutable scope ID, target/client capability,
  credential generation, established identity/compatibility facts, and a
  scope-cancellation context without exposing secret bytes.
- Provide `IsCurrent`/`RequireCurrent` as the mandatory final result barrier.
- Register named scope owners before serving; reject duplicate or late
  registration.
- Implement the approved cancellation/invalidation/publication ordering and
  deterministic owner invocation.
- Test advisory cancellation plus an intentionally noncooperative late result,
  concurrent capture/rotation, owner order, reentrant/late registration
  rejection, and shutdown.

#### 4. TargetContext state machine and identity authority

**New files**:

- `bifrost-console/internal/target/context.go`
- `bifrost-console/internal/target/context_test.go`
- `bifrost-console/internal/target/probe.go`
- `bifrost-console/internal/target/probe_test.go`
- `bifrost-console/internal/target/retry.go`
- `bifrost-console/internal/target/retry_test.go`

**Changes**:

- Implement `SelectAndConnect`, `SupplyCredential`, `Recheck`, `Snapshot`,
  `Capture`, `RegisterOwner`, and `Close` around one serialized mutation
  authority.
- Commit connection/authentication/compatibility/identity/live facts as
  successive immutable snapshots; status-only changes preserve scope.
- Establish the first identity without rotation. Commit a later changed
  identity only by rotating before publishing its facts.
- Coalesce or serialize concurrent probes so completion order cannot decide
  identity.
- Revalidate the captured scope before every probe result is committed or
  returned.
- Implement the exact retry eligibility/backoff policy with injected clock,
  timer, and jitter sources. Keep at most one pending timer and one probe for the
  current scope.
- Map application-client failures into shared domain errors/status without
  exposing internal causes.
- Close scoped transports and retry work on rotation and shutdown.

Illustrative ownership shape:

```go
type ScopeOwner interface {
    InvalidateTargetScope(previous ScopeID, cancelled context.Context)
}

type Scope struct {
    ID         ScopeID
    Context    context.Context
    Target     applicationclient.Address
    InstanceID string
    // Credential access remains an unexported scoped capability.
}
```

The exact field visibility may change during implementation, but the ownership,
secret exclusion, and lifecycle semantics may not.

### Success Criteria

#### Automated Verification

- [x] Shared contracts pass: `go test ./internal/consolecore`
- [ ] Target lifecycle tests pass with race detection:
  `go test -race ./internal/target`
- [ ] Tests cover every rotation/non-rotation row in the settled table.
- [ ] Cancellation-race tests prove an uncancellable old operation cannot
  return, install, or publish under a new scope.
- [ ] Concurrent identity tests prove only `TargetContext` commits identity.
- [ ] Retry tests prove one timer/probe, exact capped sequence, jitter bounds,
  manual bypass, eligibility, reset, rotation cancellation, and shutdown.
- [ ] Sentinel-secret tests prove status/errors/snapshots/logs contain no key.

#### Manual Verification

- [ ] Replacing a target or existing credential changes scope once.
- [ ] Supplying the first key to a YAML-selected target preserves its scope.
- [ ] A temporary failure and same-instance reconnect preserve scope.
- [ ] Restarting the observed application rotates scope after authoritative
  recheck.

---

## Phase 3: Console Composition and Protected Terminal Credential Entry

### Overview

Construct one target authority during startup, feed it the YAML default, add the
no-echo CLI option, and make shutdown ordering explicit.

### Changes Required

#### 1. No-echo credential prompt adapter

**Files**:

- `bifrost-console/go.mod`
- `bifrost-console/go.sum`

**New files**:

- `bifrost-console/internal/credentialprompt/prompt.go`
- `bifrost-console/internal/credentialprompt/prompt_test.go`

**Changes**:

- Add an exact pinned `golang.org/x/term` dependency and use
  `term.IsTerminal`/`term.ReadPassword` rather than homegrown terminal-mode
  manipulation.
- Require interactive terminal input and output; fail clearly on redirected
  stdin, unavailable terminal handles, EOF, interruption, or read failure.
- Write only a fixed prompt and terminating newline. Never echo, log, format,
  retain in CLI options, or include the key in an error.
- Use an injected prompt seam in tests. Interruption must return without a
  lingering prompt goroutine or leaving echo disabled; add representative
  platform coverage for Windows, Linux, and macOS behavior where CI permits.

#### 2. CLI option and startup composition

**Files**:

- `bifrost-console/cmd/bifrost-console/main.go`
- `bifrost-console/cmd/bifrost-console/main_test.go`
- `bifrost-console/internal/console/service.go`
- `bifrost-console/internal/console/security_integration_test.go`
- `bifrost-console/README.md`

**Changes**:

- Add the non-secret Boolean `--prompt-for-application-key`.
- Extend documentation coverage so every flag is declared.
- After profile/workspace verification, build the resolved application client
  factory and `TargetContext`; select the YAML target before browser serving.
- When prompt mode is requested, require that YAML selected a default target,
  read the key without echo, install it through `TargetContext`, and perform the
  same initial probe used by browser entry.
- Prompt failure is a startup error before listener creation. A target probe
  domain failure retains the selected target and allows the Console to start so
  the paired browser can display/recover from it; only prompt/configuration
  mechanics fail startup.
- Do not add a target/key command-line argument or environment-variable secret
  path.
- Shutdown in this order: stop process admission, close/cancel
  `TargetContext`, close browser sessions/pairing, stop the host, clean
  transient workspace best-effort, then release workspace/profile locks.

#### 3. Console assembly test seams

**Files**:

- `bifrost-console/internal/console/service.go`
- `bifrost-console/internal/console/security_integration_test.go`

**Changes**:

- Extend dependencies with factories for scope IDs, clocks/timers, application
  clients, and terminal input only where deterministic testing requires them.
- Keep secure production defaults when a test dependency is absent.
- Test that target/config/prompt establishment occurs after locks/workspace
  safety but before listener service, and that no upstream request occurs for
  the default target without a credential.

### Success Criteria

#### Automated Verification

- [x] CLI and prompt tests pass:
  `go test ./cmd/bifrost-console ./internal/credentialprompt`
- [x] Console assembly/security integration passes:
  `go test ./internal/console`
- [x] README configuration and flag tests pass:
  `go test ./internal/config`
- [ ] Prompt tests prove redirected input, interruption, read error, invalid key
  shape, and missing YAML target fail safely without credential disclosure.
- [ ] Console tests prove browser and terminal paths call the same
  `TargetContext` credential operation and provider.

#### Manual Verification

- [ ] `--prompt-for-application-key` accepts a key without visible echo on each
  supported release platform.
- [ ] Ctrl+C or terminal interruption exits cleanly and restores terminal echo.
- [ ] Shell history and process listings contain only the non-secret Boolean
  option.
- [ ] The paired browser sees the status resulting from terminal entry and
  cannot tell it came from a separate credential lifecycle.

---

## Phase 4: Browser Target API, Overview, and Scope Reset Boundary

### Overview

Adapt the target services into the protected browser realm and replace the
foundation page with the initial target-aware Overview.

### Changes Required

#### 1. Browser target handlers and shared error adapter

**Files**:

- `bifrost-console/internal/browserapi/router.go`
- `bifrost-console/internal/browserapi/errors.go`
- `bifrost-console/internal/browserapi/errors_test.go`
- `bifrost-console/internal/browserapi/security_integration_test.go`

**New files**:

- `bifrost-console/internal/browserapi/target.go`
- `bifrost-console/internal/browserapi/target_test.go`
- `bifrost-console/internal/browserapi/contracts.go`
- `bifrost-console/internal/browserapi/contracts_test.go`

**Changes**:

- Inject only the transport-neutral target interface/status provider into the
  router.
- Add the four approved routes with session-only versus session+CSRF controls.
- Apply the approved 4 KiB target-operation body bound while retaining the
  existing 1 KiB bound for pairing/session operations.
- Validate DTO shape strictly and clear local request-owned key bytes as soon as
  the target operation accepts them.
- Expand the browser error envelope with optional `targetScopeId` and typed
  bounded `details`.
- Keep Host/Origin/session/CSRF failures browser-local and before target-service
  invocation.
- Add authorization-order tests proving unpaired, foreign-origin, invalid-CSRF,
  wrong-method, oversized, duplicate-header, and malformed requests cause no
  target access or rotation.

#### 2. Browser-facing semantic fixtures

**New files**:

- `bifrost-console/browser-fixtures/target/bootstrap-no-target.json`
- `bifrost-console/browser-fixtures/target/bootstrap-authentication-required.json`
- `bifrost-console/browser-fixtures/target/bootstrap-connected.json`
- `bifrost-console/browser-fixtures/target/error-authentication-required.json`
- `bifrost-console/browser-fixtures/target/error-access-blocked.json`
- `bifrost-console/browser-fixtures/target/error-unavailable.json`
- `bifrost-console/browser-fixtures/target/error-incompatible.json`
- `bifrost-console/browser-fixtures/target/error-target-changed.json`

**Changes**:

- Byte-compare a complete reviewed Go-generated fixture inventory in browser API
  tests.
- Consume the same committed JSON from TypeScript API/component tests.
- Keep fixtures semantic and credential-free. Do not add a schema generator or
  copy Java application DTOs into browser fixtures.

#### 3. TypeScript target client and session boundary

**Files**:

- `bifrost-console/web/src/api/contracts.ts`
- `bifrost-console/web/src/api/client.ts`
- `bifrost-console/web/src/api/client.test.ts`
- `bifrost-console/web/src/security/BrowserSessionProvider.tsx`
- `bifrost-console/web/src/security/BrowserSessionProvider.test.tsx`
- `bifrost-console/web/src/security/sessionReducer.ts`
- `bifrost-console/web/src/security/sessionReducer.test.ts`

**New files**:

- `bifrost-console/web/src/target/TargetProvider.tsx`
- `bifrost-console/web/src/target/TargetProvider.test.tsx`
- `bifrost-console/web/src/target/targetReducer.ts`
- `bifrost-console/web/src/target/targetReducer.test.ts`

**Changes**:

- Add exact shared target/status/error unions and browser DTOs.
- Extend `BrowserAPIError` with optional scope/details while keeping browser
  session errors distinguishable by code.
- Keep CSRF/tab tokens inside `BrowserSessionProvider` refs and expose typed
  protected operations rather than making security values target-domain state.
- Clear application-key form state in `finally` after every submission result.
- Store no target key, status response, or diagnostic content in
  `localStorage`/`sessionStorage`.
- Initialize target state from bootstrap and let status refresh replace it
  without probing.
- On a different current `targetScopeId` or `TARGET_CHANGED`, discard all
  target-derived/presentation state, remount the target-scoped application
  boundary, and navigate to `/` with replace semantics.
- Do not treat connection/status updates within the same scope as resets.

#### 4. Target-aware Overview

**Files**:

- `bifrost-console/web/src/app/App.tsx`
- `bifrost-console/web/src/app/App.test.tsx`
- `bifrost-console/web/src/app/routes.tsx`
- `bifrost-console/web/src/styles/index.css`
- `bifrost-console/web/src/styles/tokens.css`

**New files**:

- `bifrost-console/web/src/target/Overview.tsx`
- `bifrost-console/web/src/target/Overview.test.tsx`
- focused target form/status components as justified

**Changes**:

- Replace the foundation route with stable Overview while retaining pairing as
  the outer security boundary.
- Present separate selection, connection, authentication, compatibility,
  runtime identity, instance ID, and live-monitoring facts with observation
  time.
- Provide address+key connect, key-only initial/replacement, and explicit
  recheck actions according to current state.
- Show normalized target address and a persistent **Unencrypted** label with
  precise network-exposure text for HTTP.
- Present the safe transport categories with the settled language, including
  proxy/host access blocking, namespace/context-path guidance, TLS categories,
  redirects, and exact expected/observed release strings.
- Never show a key, suffix, credential generation, CA path, raw internal error,
  aggregate health label, or automatic TLS bypass.
- Keep focus visible and stable; after submission, move focus to the resulting
  status heading/message only when needed for accessible operation feedback.
  Scope reset focuses the Overview heading after replacement navigation.
- Respect current WCAG, forced-colors, reduced-motion, zoom, and responsive
  baselines.

### Success Criteria

#### Automated Verification

- [x] Browser API unit/security tests pass:
  `go test ./internal/browserapi`
- [x] Frontend typecheck/unit tests pass:
  `npm --prefix web run typecheck && npm --prefix web test`
- [x] Browser fixture byte inventory and TypeScript fixture consumption pass.
- [ ] Tests cover connect, first credential, replacement, recheck, same-scope
  status refresh, changed scope, every PR 09 shared error, form clearing, and
  focus behavior.
- [ ] Security tests prove no key appears in DOM snapshots, errors, fixtures,
  console output, browser storage, URLs, or rendered status.
- [ ] Existing pairing/session/security tests remain unchanged in meaning.

#### Manual Verification

- [ ] A paired developer can connect, correct a mistyped key, replace a target,
  and recheck a failed target without restarting Console.
- [ ] Replacement explains the reset consequence before confirmation when the
  current scope is established.
- [ ] HTTP, TLS, authentication, access-blocked, unavailable, incompatible, and
  live-unavailable states are understandable and independent.
- [ ] Keyboard-only, 200 percent zoom, forced-colors, dark/light/system theme,
  and representative screen-reader flows remain usable.

---

## Phase 5: Cross-Layer Lifecycle, Race, and Release Verification

### Overview

Prove the assembled Console preserves the trust and scope boundary under
realistic protocol, cancellation, replacement, restart, and browser races.

### Changes Required

#### 1. Assembled mock application integration

**New files**:

- `bifrost-console/internal/console/target_integration_test.go`
- reusable in-process mock application helpers under the owning test package

**Changes**:

- Exercise the actual Console application client and target context against
  HTTP and TLS test servers.
- Cover correct status, rejected/malformed/duplicate key behavior, generic
  401/403, exact mismatch, missing namespace, redirect, 5xx retry, timeout,
  cancellation, body/header bounds, header/body instance mismatch, and changed
  instance.
- Assert no request other than `/instance` occurs in PR 09 and no request occurs
  before credentials exist.
- Record request hosts/headers to prove no redirect or different-origin
  credential forwarding.

#### 2. Late-result and authoritative-change integration

**Files**:

- `bifrost-console/internal/console/target_integration_test.go`
- `bifrost-console/internal/target/context_test.go`
- `bifrost-console/internal/browserapi/target_test.go`

**Changes**:

- Hold an old-scope response until after target replacement, credential
  replacement, instance rotation, and shutdown; prove it cannot alter status or
  return as a successful current result.
- Register fake future skill/activity/artifact owners and prove all are
  invalidated once before new-scope capture.
- Exercise simultaneous browser tabs performing recheck/replacement and prove
  one serialized authoritative outcome.
- Run high-contention lifecycle coverage under Go's race detector.

#### 3. Browser process tests

**Files**:

- `bifrost-console/web/e2e/fixtures/consoleProcess.ts`
- `bifrost-console/web/e2e/shell.spec.ts`
- new focused PR 09 Playwright spec(s)

**Changes**:

- Extend the executable fixture with a controlled HTTP/TLS mock Bifrost target
  and credential without exposing the key in Playwright diagnostics.
- Cover pairing-to-connect, reload/bootstrap, bad-key correction,
  access-blocked, unavailability/recheck, incompatibility, target replacement,
  scope reset, HTTP warning, and no direct browser request to the Java
  observability namespace.
- Assert browser storage and navigated URLs remain credential-free.
- Reference `WF-X-R5`, `WF-X-R6`, `WF-X-R7`, `WF-X-R10`, and `WF-X-R12` in the
  representative status/reset scenarios.

#### 4. Documentation and complete build verification

**Files**:

- `bifrost-console/README.md`
- relevant test comments/fixture README only where they improve boundary
  discoverability

**Changes**:

- Document exact YAML fields, path resolution, restart behavior, direct/no-proxy
  policy, HTTP warning, CA semantics, timeouts/retry, browser entry exposure,
  terminal alternative, scope/reset behavior, and sanitized failure meanings.
- State explicitly that application credentials are process-memory-only and
  absent after Console restart.
- Keep Java fixture documentation unchanged unless a real producer mismatch is
  corrected atomically.

### Success Criteria

#### Automated Verification

- [ ] All Go tests pass with race detection:
  `go test -race ./...`
- [x] Frontend verification passes:
  `npm --prefix web run typecheck && npm --prefix web run test:coverage`
- [x] Playwright target workflows pass:
  `npm --prefix web run test:e2e`
- [x] Java REST fixtures pass:
  `.\mvnw.cmd -pl bifrost-spring-boot-starter '-Dtest=ConsoleRestFixtureCorpusTest' test`
- [x] Canonical Console verification passes from `bifrost-console/`:
  `go run ./internal/buildtool verify`
- [ ] A second intentional Java fixture regeneration produces no diff when no
  producer correction was required.
- [x] `git diff --check` reports no whitespace errors.
- [x] Skill-authoring guidance remains unchanged, consistent with the no-impact
  assessment.

#### Manual Verification

- [ ] Run the packaged Console against a real compatible sample application via
  HTTP and verified HTTPS.
- [ ] Confirm key entry through both browser and terminal converges on identical
  status/scope behavior.
- [ ] Restart the application and confirm the old scope is discarded before the
  new instance appears.
- [ ] Replace the selected target while a delayed request is pending and confirm
  no stale result appears.
- [ ] Inspect local terminal output, browser devtools/storage/history, and
  ordinary application/Console logs for credential leakage.
- [ ] Confirm no Java runtime, Node runtime, database, or shared target
  filesystem is needed by the packaged executable.

---

## Testing Strategy

### Unit Tests

- Strict target configuration presence/defaults, duration/path/CA parsing, and
  secret-field exclusion.
- URL normalization/rejection and fixed namespace composition.
- HTTP/TLS construction, bounds, direct/no-proxy behavior, cancellation, and
  safe failure classification.
- Java fixture decoding, exact compatibility, problem mapping, and instance
  header/body agreement.
- Status invariants and every domain-error mapping/detail bound.
- Credential installation/replacement/clearing and leak sentinels.
- Target scope capture, rotation, owner invalidation, current-result checks,
  identity commitment, retry, and shutdown.
- Terminal TTY/no-echo/error/interruption behavior.
- Browser handler security/mapping and frontend reducer/form/status/reset
  behavior.

### Integration Tests

- Real `httptest` HTTP/TLS targets through the assembled application client and
  `TargetContext`.
- Browser router plus paired session and CSRF controls invoking the real target
  service.
- Concurrent replacement/probe/late-result races under `go test -race`.
- Go semantic consumption of Java-produced fixtures.
- Packaged browser-to-Go flow with a controlled mock application.

### Manual Testing Steps

1. Start compatible HTTP and HTTPS sample applications with known application
   keys and, for private HTTPS, a test CA.
2. Start Console with no target, a YAML default without prompt, and a YAML
   default with `--prompt-for-application-key`.
3. Exercise correct, missing, malformed, rejected, and replacement keys.
4. Exercise unreachable, namespace-not-found, redirect, TLS, generic 401/403,
   incompatible, and successful target results.
5. Delay a request while replacing the target and restart the application while
   connected; inspect scope and UI reset behavior.
6. Verify credential absence from terminal echo/history, process arguments,
   YAML, Console/application logs, URLs, browser storage, DOM, and error bodies.

**Note**: Before implementation, run `ai/commands/3_testing_plan.md` against this
plan to create the dedicated failing-test-first artifact, exact fixture matrix,
platform coverage, commands, and exit criteria.

## Performance Considerations

- One selected target owns at most one retry timer and one serialized instance
  probe. Successful connections are not polled.
- HTTP connection pools are small and closed on scope rotation/shutdown.
- Instance/problem responses and headers are strictly bounded; decompression is
  disabled.
- Status reads capture one immutable snapshot without network or filesystem
  work.
- Rotation invalidation hooks must detach visible state synchronously but leave
  potentially expensive physical cleanup to cancelled owner work where safe.
- Credential replacement and scope checks avoid global catalog/state copies.
- The browser adds no continuous interval or application-state materialization
  in this PR.

## Migration Notes

- Existing valid version-1 Console configurations remain valid and mean “no
  default target.”
- Developers may add the optional `target` mapping. No migration tool, alias,
  fallback spelling, or dual schema is needed.
- Browser-selected addresses and all application credentials are forgotten on
  restart. Only the YAML default and trust/timeout policy persist.
- There is no prior Go target state, browser target DTO, credential store, or
  target cache to migrate.
- Future PRs register their state owners with `TargetContext` during assembly
  and use captured scopes plus the final current-scope check; they must not add
  parallel target stores.

## Downstream PR Handoff

- **PR 10** uses `Capture`, `RequireCurrent`, `ConsoleStatusSnapshot`, and shared
  errors for skill/active/trace-catalog queries and mounts its providers beneath
  the browser target-scope reset boundary.
- **PR 11** registers the SSE/activity owner, reports handshake identity to
  `TargetContext`, and relies on rotation cancellation before admitting events.
- **PR 12** registers the artifact owner and binds acquisition, handles,
  partial cleanup, and raw downloads to captured scope.
- **PRs 13–14** bind indexes, continuations, analysis, and deep links to the
  artifact/scope lifecycle without redefining target errors.
- **PR 15** expands Playwright workflow/degraded-path coverage rather than
  moving lifecycle fixes into presentation workarounds.
- **PR 16** adapts the same side-effect-free status to MCP and keeps independent
  MCP authentication generation separate from target scope.
- **PRs 17–18** adapt the same target-scoped services/errors and never contact
  the application or retain a second credential lifecycle directly.
- **PR 19** can distinguish protocol capability, compatibility,
  authentication, connection, and evidence availability because PR 09 does not
  collapse them into health.

## References

- Original ticket:
  `ai/thoughts/tickets/bifrost-console-pr-09-target-context.md`
- Related research:
  `ai/thoughts/research/2026-07-26-bifrost-console-pr-09-target-context.md`
- Implementation roadmap:
  `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md`
- Phase 2 design:
  `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md`
- Approved workflows:
  `ai/thoughts/phases/bifrost_console_workflows.md`
- Framework compatibility lens:
  `ai/thoughts/framework-feature-design-lens.md`
- Java/Go fixture contract:
  `bifrost-console-fixtures/README.md`
- Current Console runtime:
  `bifrost-console/internal/console/service.go`
- Current browser security boundary:
  `bifrost-console/internal/browserapi/router.go`
