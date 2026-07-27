# Bifrost Console PR 09 — TargetContext and Selected-Target Lifecycle Testing Plan

## Change Summary

- Extend strict Console schema-version 1 with an optional non-secret selected
  target, bounded network timeouts, and an optional custom CA bundle.
- Add the direct, no-redirect Java application protocol client for authenticated
  `/instance` probing and exact complete-release compatibility.
- Add the shared status/error contract and the single authoritative
  `TargetContext` for credential generation, immutable scopes, identity,
  rotation, cancellation, owner invalidation, retry, and late-result
  suppression.
- Add process-memory browser and no-echo terminal application-key entry through
  the same provider and target lifecycle.
- Add protected browser target routes, semantic fixtures, target-aware
  bootstrap/Overview, and a whole-application browser reset on target-scope
  change.

This is new feature behavior rather than a fix for one existing defect. Testing
will still begin with a minimal red specification test and proceed in
failing-test-first vertical slices.

## Impacted Areas

- `bifrost-console/internal/config/`
  - Strict YAML schema, defaults, duration/path validation, CA loading, README
    synchronization.
- `bifrost-console/internal/applicationclient/` (new)
  - URL normalization, trust construction, HTTP bounds, credential header,
    instance/problem wire DTOs, compatibility and identity checks.
- `bifrost-console/internal/consolecore/` (new)
  - Stable shared codes, bounded typed details, independent status facts.
- `bifrost-console/internal/target/` (new)
  - Credentials, scope capabilities, owner registration/invalidation,
    authoritative state transitions, probe/retry, shutdown.
- `bifrost-console/internal/credentialprompt/` (new)
  - Interactive terminal detection and no-echo reading.
- `bifrost-console/cmd/bifrost-console/`
  - `--prompt-for-application-key` parsing and handoff.
- `bifrost-console/internal/console/`
  - Startup/shutdown composition and real target integration.
- `bifrost-console/internal/browserapi/`
  - Protected target routes, split body limits, status/error DTO mapping,
    security ordering, browser fixture production.
- `bifrost-console/browser-fixtures/target/` (new)
  - Reviewed current-release browser-facing success and failure bodies.
- `bifrost-console/web/src/api/`, `web/src/security/`, `web/src/target/`, and
  `web/src/app/`
  - Typed client, in-memory security boundary, target state, Overview,
    scope-reset navigation, accessible failure presentation.
- `bifrost-console/web/e2e/`
  - Packaged browser-to-Go target workflows with controlled HTTP/TLS
    application doubles.
- `bifrost-console-fixtures/application-rest/`
  - Protected Java-generated semantic input consumed by new Go contract tests.
- `bifrost-console/README.md`
  - Exact configuration, security, terminal, retry, and lifecycle claims
    verified by focused tests.

## Risk Assessment

### Critical Risks

1. **Credential authority escape**
   - A key reaches a redirect, environment proxy, different origin, URL, log,
     returned error, fixture, DOM, browser storage, terminal echo, process
     argument, or test artifact.
2. **Stale-scope publication**
   - Cancellation loses a race and an old target result is returned, committed,
     cached, rendered, or made visible after target/credential/instance change.
3. **Split target authority**
   - Browser, terminal, retry, or later service seams commit identity or
     credentials outside `TargetContext`.
4. **Incorrect compatibility use**
   - Go consumes the full instance DTO or later endpoint semantics before the
     exact complete release string matches.
5. **Identity race**
   - Concurrent responses adopt the last-finishing `instanceId` rather than
     routing a changed established identity through serialized scope rotation.
6. **TLS weakening**
   - Custom roots replace system trust, hostname validation is disabled, an
     invalid bundle is ignored, or TLS errors are collapsed into unsafe advice.

### High Risks

1. Rotation cancels work but fails to invalidate registered owners before a new
   scope becomes capturable.
2. Initial credential installation rotates a YAML-selected scope unnecessarily,
   or replacement credentials fail to rotate because bytes happen to match.
3. Retry creates multiple timers/probes, retries nonretryable failures, polls
   after success, sleeps in tests, or survives rotation/shutdown.
4. A generic upstream 401/403 is mislabeled as a rejected Bifrost key.
5. Status reads perform a probe or collapse selection, connection,
   authentication, compatibility, identity, and live availability into one
   health fact.
6. Browser security checks occur after body decoding or target invocation.
7. The new 4 KiB target body limit accidentally expands existing pairing and
   session routes beyond 1 KiB.
8. Terminal interruption leaves echo disabled or a blocked goroutine/process.
9. Browser scope change retains route, form, error, selection, or future
   target-derived state under the new runtime.

### Medium Risks and Edge Cases

- Canonically equivalent URLs rotate because normalization differs, while
  distinct origins or context paths are treated as equal.
- IPv6, explicit default ports, escaped separators, repeated slashes, dot
  segments, Unicode authority, user info, query, and fragment parsing.
- Relative CA resolution accidentally depends on process working directory.
- Oversized/malformed headers, compressed bodies, multiple JSON values,
  unknown problem codes, wrong content type, or header/body identity mismatch.
- A committed selection is lost merely because its initial probe returns a
  domain error.
- A manual recheck races the scheduled retry.
- Multiple paired tabs issue replacement/recheck operations simultaneously.
- Browser 401 session rejection and target-authentication-required 401 are
  confused.
- The persistent HTTP warning disappears after refresh or status update.
- Focus is stolen by ordinary status refresh or not restored after scope reset.
- Tests themselves leak sentinel keys into failure messages, snapshots, traces,
  or retained Playwright artifacts.

## Contract and Compatibility Test Scope

| Surface | Impact | Test obligation |
| --- | --- | --- |
| Application API | No change | No new application API compatibility test. Existing architecture/API tests must remain green. |
| Supported SPI | No change | No target SPI compatibility test. New Go seams remain under `internal`. |
| Configuration and manifest contracts | Console version-1 YAML gains the optional `target` mapping; skill manifests are unchanged. | Protect all previously valid Console configurations and exact existing field meanings. Prove new fields/defaults/rejections and absence of aliases or secret fields. |
| Persisted or serialized contracts | Go begins consuming the Java `/instance` and problem fixtures; browser/Go gains atomically shipped JSON DTOs. | Byte-protect Java producer fixtures, semantically consume them in Go, reject exact-version mismatch, and byte-protect a complete browser fixture inventory consumed by TypeScript. |
| Ephemeral diagnostic formats | NDJSON is not read or changed by PR 09. | No historical/current NDJSON parser tests are added here. Existing fixture corpus remains untouched. |
| Internal or accidentally exposed implementation | New Go packages, constructors, owner hooks, retry logic, terminal adapter, reducers, and routes. | Test current coherent behavior; do not preserve speculative prior package shapes, route aliases, fallback config names, or dual behavior. |

### Protected Compatibility Paths

- Existing schema-version 1 YAML containing only `listener` and
  `trace-workspace` remains valid and resolves exactly as before.
- Existing listener/workspace validation, pairing, browser sessions, Host,
  Origin, CSRF, no-store, and body bounds remain protected.
- The Java-generated `application-rest` inventory and byte content remain the
  current-release Java-to-Go contract unless an intentional coordinated
  producer correction is approved.
- The complete release value `0.1.0-SNAPSHOT` in the current fixture is matched
  exactly, including its qualifier.
- Existing browser and Go assets still ship atomically with no independent
  browser compatibility negotiation.

### Intentionally Absent/Removed Paths

Tests must prove absence rather than preserve a fallback:

- no target key in YAML, command arguments, environment variables, URLs, status,
  fixtures, or returned diagnostics;
- no target-field aliases, old/new spellings, or schema-version fork;
- no environment proxy use;
- no redirect following or origin credential forwarding;
- no insecure TLS mode;
- no aggregate `healthy`, `ready`, or `degraded` status;
- no browser direct calls to `/_bifrost/observability/`;
- no target probing without a credential;
- no status polling after successful establishment;
- no browser-specific or terminal-specific credential store/target authority;
- no legacy Java/Go protocol reader and no secondary compatibility version.

## Existing Test Coverage

### Reusable Current Patterns

- `bifrost-console/internal/config/config_test.go`
  - Protects strict version-1 defaults, unknown/duplicate/multidocument
    rejection, explicit duration/byte units, sentinels, and secret-field
    exclusion.
- `bifrost-console/internal/config/documentation_test.go`
  - Parses the README YAML example and verifies documented runtime flags.
- `bifrost-console/internal/browserapi/security_integration_test.go`
  - Supplies deterministic pairing/session entropy, performs real router calls,
    proves independent Origin/session/CSRF controls, and verifies security
    failure before body read.
- `bifrost-console/internal/browserapi/errors_test.go`
  - Protects strict bounded JSON and sanitized stable error envelopes.
- `bifrost-console/internal/console/security_integration_test.go`
  - Starts the assembled service on an ephemeral loopback port, pairs over real
    HTTP, bootstraps, shuts down, and proves profile/workspace lock release.
- `bifrost-console/cmd/bifrost-console/main_test.go`
  - Injects runtime dependencies and verifies validation/flag handoff before
    service startup.
- `bifrost-console/web/src/api/client.test.ts`
  - Inspects same-origin fetch options and in-memory CSRF/tab headers.
- `bifrost-console/web/src/security/BrowserSessionProvider.test.tsx`
  - Verifies bootstrap/session recovery and absence of CSRF in rendering or
    browser storage.
- `bifrost-console/web/e2e/fixtures/consoleProcess.ts`
  - Builds an isolated temporary profile/workspace around the packaged
    executable and extracts the one-use pairing URL.
- `bifrost-console/web/e2e/shell.spec.ts`
  - Proves the browser never calls the Java observability namespace directly.
- `ConsoleRestFixtureCorpusTest`
  - Generates and byte-compares the complete Java REST/problem fixture
    inventory.
- `go run ./internal/buildtool verify`
  - Runs exact toolchain validation, locked frontend install, TypeScript
    checking, frontend coverage, fresh asset generation/verification, and all
    Go tests.

### Current Gaps

- No Go application client, target URL/TLS policy, Java fixture consumer,
  compatibility check, or upstream failure classifier.
- No shared domain status/error types.
- No target scope, cancellation, owner invalidation, credential generation,
  retry, or race tests.
- No terminal password/no-echo seam.
- No target browser route, DTO, fixture, frontend state, form, status, or reset
  tests.
- No assembled target mock or browser workflow coverage.
- The canonical build does not run Playwright; packaged E2E remains a separate
  required gate.

## Bug Reproduction / Failing Test First

This is a new feature, not a regression bug. The first red test will establish
the smallest protected configuration behavior before production scaffolding is
added.

- **Name**:
  `TestDecodeAcceptsOptionalTargetConfigurationAndAppliesNetworkDefaults`
- **Type**: Unit
- **Location**:
  `bifrost-console/internal/config/config_test.go`
- **Arrange**:
  - Create otherwise valid version-1 YAML with:
    `target.address: https://Application.Example:443/context/`.
  - Omit the three optional timeout fields and `ca-bundle`.
- **Act**:
  - Call `config.Decode` with a stable absolute configuration path.
- **Assert**:
  - Decode succeeds.
  - The target is present.
  - The configured address is retained for the application-client validator;
    normalization is proved separately in
    `TestNormalizeAddressAcceptsCanonicalHTTPHTTPSTargets`.
  - Resolved timeouts are 5 seconds, 10 seconds, and 30 seconds.
  - No CA bundle is selected.
- **Expected failure before implementation**:
  - Current `validateSchema` returns
    `configuration ... is invalid: contains an unknown field` for `target`.
- **Why this is first**:
  - It is deterministic, fast, requires no new package, and proves the initial
    protected user-visible contract rather than only testing scaffolding.

After this first red/green slice, each section below begins with its lowest-cost
test before production code for that section.

## Failing-Test-First Order

1. Target YAML acceptance/defaults and preservation of target-free defaults.
2. URL normalization and rejection table.
3. Java fixture compatibility gate and problem mapping.
4. Shared status/error invariants.
5. Scope rotation table and the noncooperative late-result race.
6. Retry serialization and fake-time schedule.
7. Terminal/provider convergence.
8. Browser security route matrix and fixture bytes.
9. Frontend form clearing and scope reset.
10. Assembled HTTP/TLS and packaged browser workflows.

No test in steps 1–9 should require a real external network, public certificate
authority, real clock delay, or user terminal.

## Tests to Add or Update

## 1. Configuration Contract

### `TestDecodeAcceptsOptionalTargetConfigurationAndAppliesNetworkDefaults`

- **Type**: Unit
- **Location**: `bifrost-console/internal/config/config_test.go`
- **What it proves**: The first red test described above.
- **Fixtures/data**: Inline version-1 YAML.
- **Mocks**: None.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: New protected additive path.

### `TestDecodePreservesTargetFreeVersionOneConfiguration`

- **Type**: Unit
- **Location**: `bifrost-console/internal/config/config_test.go`
- **What it proves**:
  - Existing `DefaultYAML` and an explicit pre-PR-09 YAML document still parse.
  - Resolved listener/workspace values are unchanged.
  - Target is absent and causes no default selection.
- **Fixtures/data**: Existing `DefaultYAML` plus the README's prior field set.
- **Mocks**: None.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: Protected existing configuration.

### `TestDecodeValidatesTargetDurationsAddressAndPresence`

- **Type**: Table-driven unit
- **Location**: `bifrost-console/internal/config/config_test.go`
- **What it proves**:
  - A present target requires a nonblank, at-most-2-KiB address scalar with no
    surrounding whitespace; full URL semantics remain owned by
    `applicationclient`.
  - Positive canonical `s`, `m`, and `h` values are accepted.
  - zero, negative, fractional, unitless, `never`, overflow, surrounding
    whitespace, and wrong scalar/mapping shapes are rejected.
  - Unknown target fields and aliases remain rejected.
- **Fixtures/data**: Inline YAML cases with one changed field.
- **Mocks**: None.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: Protected exact new contract; no aliases.

### `TestDecodeResolvesAndValidatesCustomCABundle`

- **Type**: Unit with temporary files
- **Location**: `bifrost-console/internal/config/config_test.go`
- **What it proves**:
  - Relative paths resolve from the resolved config directory, independent of
    process working directory.
  - Absolute paths remain absolute.
  - A regular PEM file at or below 1 MiB with at least one certificate is
    accepted.
  - missing, directory, oversized, empty, non-PEM, and PEM-with-no-certificate
    inputs fail without returning content or sensitive paths beyond the
    developer-selected config field context.
- **Fixtures/data**:
  - Programmatically generated test CA certificate.
  - `t.TempDir()` files; no committed private key.
- **Mocks**: None; system-root augmentation is isolated in the transport test
  below.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: Protected exact new contract.

### `TestDefaultConfigurationContainsNoTargetOrCredential`

- **Type**: Unit
- **Location**: `bifrost-console/internal/config/config_test.go`
- **What it proves**:
  - New profiles remain target-free.
  - Default YAML contains no secret/key/credential field.
- **Fixtures/data**: `DefaultYAML`.
- **Mocks**: None.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: Protected existing safe default.

### `TestREADMEConfigurationExampleAndRuntimeFlagsMatchCode`

- **Type**: Documentation contract unit
- **Location**:
  `bifrost-console/internal/config/documentation_test.go`
- **What it proves**:
  - The updated README example parses.
  - Every target field and sentinel/default described by README has focused
    executable coverage.
  - `--prompt-for-application-key` joins the complete runtime-flag inventory.
- **Fixtures/data**: `bifrost-console/README.md`.
- **Mocks**: None.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: Protected documentation/configuration
  coherence.

## 2. Target URL and Network Authority

### `TestNormalizeAddressAcceptsCanonicalHTTPHTTPSTargets`

- **Type**: Table-driven unit
- **Location**:
  `bifrost-console/internal/applicationclient/address_test.go`
- **What it proves**:
  - HTTP/HTTPS, DNS, IPv4, bracketed IPv6, nondefault ports, and clean context
    paths normalize exactly.
  - Scheme/DNS case and explicit default ports canonicalize.
  - A trailing context slash is removed.
  - The fixed observability namespace is appended structurally once.
  - HTTP produces `Unencrypted=true`; HTTPS does not.
- **Fixtures/data**: Inline input/normalized/root triples.
- **Mocks**: None.
- **Contract classification**: Internal or accidentally exposed
  implementation supporting a configuration contract.
- **Compatibility expectation**: Current coherent implementation of the
  approved contract.

### `TestNormalizeAddressRejectsAmbiguousOrUnsafeAuthorityAndPath`

- **Type**: Table-driven unit
- **Location**:
  `bifrost-console/internal/applicationclient/address_test.go`
- **What it proves**:
  - Empty/over-2-KiB, surrounding whitespace, unsupported scheme, opaque URL,
    user info, query, fragment, missing host, invalid port, Unicode authority,
    encoded authority, IPv6 zone, malformed escape, backslash, encoded slash or
    backslash, repeated slash, and dot-segment cases fail before selection.
  - Errors never echo embedded user info.
- **Fixtures/data**: One named input per rejection class plus a sentinel secret
  in user info.
- **Mocks**: None.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: Protected rejection behavior and security
  boundary.

## 3. HTTP/TLS Application Client

### `TestTransportUsesDirectSelectedAuthorityAndNeverFollowsRedirect`

- **Type**: Integration using `httptest`
- **Location**:
  `bifrost-console/internal/applicationclient/transport_test.go`
- **What it proves**:
  - A fake `HTTP_PROXY`/`HTTPS_PROXY` trap receives zero requests.
  - The selected target receives exactly one request.
  - A redirect target receives zero requests and no key.
  - The response classifies as `redirect`.
- **Fixtures/data**:
  - Selected server, redirect receiver, and proxy trap.
  - Fake non-loopback hostname mapped through an injected dialer so Go's
    localhost proxy bypass cannot hide a regression.
- **Mocks**: Dial mapping only; real `http.Transport` and servers.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current security coherence; no proxy/redirect
  fallback.

### `TestTransportAppliesConnectionHeaderBodyCompressionAndTimeoutBounds`

- **Type**: Integration using `httptest`
- **Location**:
  `bifrost-console/internal/applicationclient/transport_test.go`
- **What it proves**:
  - Configured dial, TLS handshake, response-header, and overall request
    timeouts cancel requests.
  - Response headers above 64 KiB and instance/problem bodies above 64 KiB are
    rejected.
  - Unsupported content encoding is rejected and automatic decompression is
    disabled.
  - Idle pool settings are exact and idle connections close on client close.
- **Fixtures/data**: Blocking handlers, oversize header/body, gzip response,
  connection-counting listener.
- **Mocks**: Fake clock is not used for socket deadlines; use millisecond-scale
  local test deadlines with a generous outer test timeout and no fixed sleeps.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current bounded-resource behavior.

### `TestTransportUsesSystemTrustPlusOptionalCustomCAWithoutWeakeningHostname`

- **Type**: Integration/unit
- **Location**:
  `bifrost-console/internal/applicationclient/transport_test.go`
- **What it proves**:
  - The injected system pool remains present after appending a custom CA.
  - A server signed by the configured CA and matching hostname succeeds.
  - wrong issuer, hostname mismatch, expired, not-yet-valid, and other
    handshake failures map to their exact safe category.
  - no path sets `InsecureSkipVerify`.
- **Fixtures/data**:
  - Programmatically generated CA/server certificates with fixed validity
    windows and hostnames.
- **Mocks**:
  - Inject system-root pool and TLS time for deterministic unit portions.
  - Use real TLS handshakes for end-to-end classification.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: Protected trust semantics.

### `TestInstanceRequestSendsExactlyOneBoundedCredentialHeader`

- **Type**: Integration using `httptest`
- **Location**:
  `bifrost-console/internal/applicationclient/client_test.go`
- **What it proves**:
  - Exactly one `X-Bifrost-Api-Key` is sent to the selected `/instance` path.
  - Printable ASCII keys of 32 and 512 bytes are accepted.
  - missing, short, long, whitespace/control, and non-ASCII keys fail before a
    request.
  - method is GET, query is absent, cache/encoding headers are exact, and the
    key never appears in URL/error formatting.
- **Fixtures/data**: Boundary keys and a sentinel value.
- **Mocks**: Real `httptest` request recorder.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Protected Java authentication boundary.

### `TestClientConsumesCommittedInstanceFixtureOnlyAfterExactCompatibility`

- **Type**: Fixture-backed unit/integration
- **Location**:
  `bifrost-console/internal/applicationclient/contract_test.go`
- **What it proves**:
  - The committed Java `instance-status.json` is decoded semantically.
  - Exact `0.1.0-SNAPSHOT` match succeeds.
  - case, prefix, suffix, missing qualifier, whitespace, or different version
    returns `INCOMPATIBLE_TARGET`.
  - A mismatched body with deliberately malformed noncompatibility fields still
    returns incompatibility, proving those fields were not consumed first.
  - Once compatibility matches, malformed required fields produce
    `upstream_protocol`.
- **Fixtures/data**:
  - Committed `bifrost-console-fixtures/application-rest/instance-status.json`.
  - In-memory single-field mutations; do not create historical fixtures.
- **Mocks**: Test server returning fixture bytes.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Protected exact current-release Java-to-Go
  boundary.

### `TestClientRequiresAuthenticatedHeaderBodyInstanceAgreement`

- **Type**: Table-driven integration
- **Location**:
  `bifrost-console/internal/applicationclient/client_test.go`
- **What it proves**:
  - Compatible success requires a valid UUID in both response header and body
    and exact equality.
  - missing, malformed, duplicate, or mismatched instance headers and malformed
    body identity fail as `upstream_protocol`.
  - incompatibility never establishes identity.
- **Fixtures/data**: Valid fixture with header/body mutations.
- **Mocks**: Test server.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Protected instance-lifecycle semantics.

### `TestClientMapsJavaProblemsAndGenericUpstreamFailuresPrecisely`

- **Type**: Fixture-backed table-driven integration
- **Location**:
  `bifrost-console/internal/applicationclient/contract_test.go`
- **What it proves**:
  - Every committed problem fixture parses with its exact status/code/message
    shape.
  - recognized `BIFROST_API_KEY_REJECTED` becomes authentication required;
    generic 401/403 becomes access blocked.
  - Java `APPLICATION_ERROR` and generic 5xx become retryable upstream-server
    unavailability.
  - namespace 404, unknown code, malformed problem, wrong content type, DNS,
    connection, timeout, and cancellation retain their approved distinctions.
  - no raw response body or cause text enters safe details.
- **Fixtures/data**: Complete committed `problem-*.json` inventory plus local
  malformed responses.
- **Mocks**: Test server and injected dial failure.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Protected Java problem semantics and current
  Go mapping.

### `ConsoleRestFixtureCorpusTest.generatedCorpusMatchesCommittedFixturesByteForByte`

- **Type**: Existing Java fixture contract
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ConsoleRestFixtureCorpusTest.java`
- **What it proves**: The producer inventory consumed by Go has not drifted.
- **Fixtures/data**: `bifrost-console-fixtures/application-rest/`.
- **Mocks**: Deterministic Java DTO construction.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Protected current-release producer contract.

## 4. Shared Status and Errors

### `TestStatusSnapshotRepresentsEveryIndependentTargetFactCombination`

- **Type**: Table-driven unit
- **Location**:
  `bifrost-console/internal/consolecore/status_test.go`
- **What it proves**:
  - No-target, selected/no-key, unknown/probing, reachable/authenticated,
    authentication-required, access-blocked, incompatible, unavailable,
    identity-established, and live-unavailable states remain independent.
  - Scope/identity fields appear and disappear together only in valid
    combinations.
  - No `healthy`, `ready`, or `degraded` field/type exists in serialized browser
    fixtures.
- **Fixtures/data**: Explicit status table.
- **Mocks**: Fixed clock.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Protected new shared status semantics.

### `TestStatusSnapshotAssemblyHasNoSideEffects`

- **Type**: Unit
- **Location**:
  `bifrost-console/internal/target/context_test.go`
- **What it proves**:
  - Repeated `Snapshot` calls make no client, timer, credential, owner,
    workspace, or retry call.
  - Only `observedAt` changes with the injected clock.
- **Fixtures/data**: Fake dependencies with panic/call counters.
- **Mocks**: Client, timer, owner, clock.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current shared-service invariant.

### `TestDomainErrorsHaveStableCodesTypedBoundedDetailsAndSafeFormatting`

- **Type**: Table-driven unit
- **Location**:
  `bifrost-console/internal/consolecore/errors_test.go`
- **What it proves**:
  - Complete settled code inventory and typed details serialize deterministically.
  - message/detail size limits are enforced.
  - `Error()` and adapter-safe views contain no internal cause, path, raw URL,
    response body, header, stack, or sentinel key.
  - code-specific fields do not appear on unrelated errors.
- **Fixtures/data**: One valid and invalid detail set per code.
- **Mocks**: Sentinel wrapped error.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Protected new shared error meanings.

## 5. Credentials, Scope, and Authoritative Target Lifecycle

### `TestCredentialProviderInstallsClearsAndNeverExposesOwnedSecret`

- **Type**: Unit
- **Location**:
  `bifrost-console/internal/target/credentials_test.go`
- **What it proves**:
  - Initial installation creates one opaque generation.
  - replacement always creates another generation without comparing bytes,
    including identical bytes.
  - old owned storage is cleared after old-scope cancellation.
  - close clears current owned storage.
  - snapshots, formatted provider/context values, errors, and fake logs omit the
    sentinel key.
- **Fixtures/data**: Distinct and identical sentinel byte slices.
- **Mocks**: Cancellation/clear observer exposed only to package tests.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current security invariant; no second store.

### `TestContextCreatesOrRotatesScopeOnlyForAuthoritativeChanges`

- **Type**: Table-driven unit
- **Location**:
  `bifrost-console/internal/target/context_test.go`
- **What it proves**: Every row of the implementation plan's mutation table:
  no target, YAML selection, atomic browser connect, first credential,
  replacement credential, connection-authority change, ordinary status
  failure, same-identity reconnect, changed established identity, and shutdown.
- **Fixtures/data**: Deterministic scope-ID sequence and fake probe outcomes.
- **Mocks**: Scope ID source, application client, owner.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current authoritative lifecycle.

### `TestContextEstablishesFirstIdentityWithoutRotationAndRotatesChangedIdentity`

- **Type**: Unit
- **Location**:
  `bifrost-console/internal/target/context_test.go`
- **What it proves**:
  - first compatible authenticated identity commits inside the selected scope;
  - the same identity refresh preserves scope;
  - a changed established identity cancels/invalidates and publishes only in a
    new scope;
  - an incompatible or unauthenticated result never commits identity.
- **Fixtures/data**: Ordered valid/mismatched probe results.
- **Mocks**: Deterministic scope IDs and owner recorder.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current runtime-identity boundary.

### `TestRotationCancelsAndInvalidatesOwnersBeforeNewScopeCanBeCaptured`

- **Type**: Concurrency unit
- **Location**:
  `bifrost-console/internal/target/owners_test.go`
- **What it proves**:
  - old context is cancelled and current checks fail first;
  - owners run once in registration order and detach visible state;
  - a concurrent capture cannot receive the new scope until invalidation
    completes;
  - duplicate names, registration after serving, and callback reentry are
    rejected without deadlock.
- **Fixtures/data**: Blocking fake owners and channel barriers.
- **Mocks**: Owner callbacks and capture goroutine.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current downstream-owner invariant.

### `TestLateProbeResultCannotCommitReturnOrPublishAfterScopeRotation`

- **Type**: Concurrency unit/integration
- **Location**:
  `bifrost-console/internal/target/context_test.go`
- **What it proves**:
  - An intentionally noncooperative old client ignores cancellation and returns
    success after target/credential rotation.
  - The final current-scope check rejects it as `TARGET_CHANGED`.
  - It cannot change status/identity, notify an owner, or return a successful
    result to the initiating caller.
- **Fixtures/data**: Channel-controlled fake client with old/new instance IDs.
- **Mocks**: Noncooperative client; deterministic scope IDs.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current final late-result barrier.
- **Special execution**:
  - Run with `-race`.
  - Run at least 50 repetitions without timing sleeps.

### `TestContextSerializesConcurrentProbesAndCommitsOneAuthoritativeIdentity`

- **Type**: Concurrency unit
- **Location**:
  `bifrost-console/internal/target/probe_test.go`
- **What it proves**:
  - Simultaneous initial, manual, and retry probes never run more than one
    application request at once.
  - Completion order cannot overwrite a newer scope or identity.
  - Waiters receive the one current committed result or `TARGET_CHANGED`.
- **Fixtures/data**: Barrier-controlled fake client and call concurrency
  counter.
- **Mocks**: Client and scope IDs.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current single-authority invariant.

### `TestContextMapsFailuresToIndependentFactsWithoutUnapprovedRotation`

- **Type**: Table-driven unit
- **Location**:
  `bifrost-console/internal/target/probe_test.go`
- **What it proves**:
  - missing/rejected key, generic access block, DNS/connection/timeout/TLS,
    redirect, namespace, upstream protocol/server, and incompatibility update
    exact independent status/error facts;
  - none rotates scope by itself;
  - prior complete evidence is not represented or purged by target status.
- **Fixtures/data**: Typed application-client failures.
- **Mocks**: Fake client and owner call counter.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Protected shared error/status meanings.

### `TestRetryCoordinatorUsesOneTimerOneProbeAndExactBoundedSchedule`

- **Type**: Fake-time concurrency unit
- **Location**:
  `bifrost-console/internal/target/retry_test.go`
- **What it proves**:
  - delays are 1, 2, 4, 8, 16, and then 30 seconds;
  - injected jitter remains within ±20 percent;
  - at most one timer and one probe exist;
  - eligible transient failure continues, success resets/stops, and no polling
    occurs afterward;
  - no wall-clock sleep is used.
- **Fixtures/data**: Deterministic jitter extrema and fake outcomes.
- **Mocks**: Fake clock/timer/jitter/client.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current bounded retry behavior.

### `TestManualRecheckPreemptsDelayAndNonretryableFailuresStopRetry`

- **Type**: Fake-time unit
- **Location**:
  `bifrost-console/internal/target/retry_test.go`
- **What it proves**:
  - manual recheck cancels a pending timer and triggers one immediate serialized
    attempt;
  - authentication, access, TLS, redirect, namespace, protocol, and
    incompatibility outcomes schedule nothing;
  - rotation and shutdown cancel timer/probe and prevent later callback.
- **Fixtures/data**: Failure table and channel barriers.
- **Mocks**: Fake timer/client.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current retry/recovery semantics.

## 6. Terminal and Console Composition

### `TestPromptRequiresInteractiveTerminalReadsWithoutEchoAndSanitizesFailures`

- **Type**: Unit
- **Location**:
  `bifrost-console/internal/credentialprompt/prompt_test.go`
- **What it proves**:
  - input and output terminal checks are independent;
  - only the fixed prompt and one terminating newline are written;
  - the password reader result is returned as owned bytes and never echoed;
  - redirected input/output, EOF, read failure, and interruption return fixed
    safe errors;
  - no background read goroutine remains.
- **Fixtures/data**: Sentinel key and injected terminal/read functions.
- **Mocks**: `IsTerminal`, `ReadPassword`, input/output handles.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current protected terminal behavior.

### `TestRunPassesPromptFlagWithoutAcceptingSecretArguments`

- **Type**: Unit
- **Location**:
  `bifrost-console/cmd/bifrost-console/main_test.go`
- **What it proves**:
  - `--prompt-for-application-key` reaches `console.Options` as a Boolean;
  - candidate `--application-key`, `--target-key`, positional key, and
    environment-based secret flags are not accepted;
  - `--version` does not prompt or serve.
- **Fixtures/data**: CLI argument table.
- **Mocks**: Existing injected runtime dependencies.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Protected CLI security boundary.

### `TestConsolePromptAndBrowserUseSameCredentialOperationAndTargetContext`

- **Type**: Assembly integration
- **Location**:
  `bifrost-console/internal/console/target_integration_test.go`
- **What it proves**:
  - YAML default selection happens after profile/workspace safety and before
    listener service.
  - Without a key, no upstream request occurs.
  - Prompt entry calls the same target method/provider later used by browser
    credential entry.
  - Prompt mechanical failures stop before listener; target domain failures
    retain selection and still allow paired recovery.
- **Fixtures/data**: Temporary profile/workspace and controlled target service.
- **Mocks**: Prompt and target/client factory call recorder.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current single credential lifecycle.

### `TestConsoleShutdownCancelsTargetBeforeSessionsAndReleasesLocks`

- **Type**: Assembly integration
- **Location**:
  `bifrost-console/internal/console/target_integration_test.go`
- **What it proves**:
  - New shutdown order is deterministic.
  - Scope/retry/client close completes before locks release.
  - A delayed upstream result after cancellation is suppressed.
  - Existing profile/workspace reopen assertions still pass.
- **Fixtures/data**: Existing live Console test pattern plus blocking fake target.
- **Mocks**: Lifecycle recorder and delayed client.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Protected existing lock lifecycle plus new
  target lifecycle.

## 7. Browser API and Browser Fixture Contract

### `TestTargetRoutesApplySessionAndCSRFSecurityBeforeBodyOrTargetAccess`

- **Type**: Router integration
- **Location**:
  `bifrost-console/internal/browserapi/target_test.go`
- **What it proves**:
  - `/target/status` requires session but not CSRF.
  - connect, credential, and recheck independently require session, tab, and
    CSRF after Host/Origin.
  - rejected Host/Origin/session/CSRF/method causes no body read, key decode,
    probe, mutation, or scope rotation.
  - duplicate security headers fail closed.
- **Fixtures/data**: Existing deterministic pairing/session helper and read spy.
- **Mocks**: Fake target service with panic/call counters.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Protected browser security ordering.

### `TestTargetRoutesUseFourKiBBodiesWithoutExpandingExistingRouteLimit`

- **Type**: Router unit/integration
- **Location**:
  `bifrost-console/internal/browserapi/errors_test.go`
- **What it proves**:
  - Target requests at valid maximum encoding fit within 4 KiB.
  - Target requests above 4 KiB fail.
  - Existing pairing/session requests above 1 KiB still fail.
  - unknown fields, trailing JSON, multiple values, missing fields, invalid key
    shape, and over-2-KiB address fail as `INVALID_ARGUMENT`/browser invalid
    request before mutation.
- **Fixtures/data**: Boundary JSON bodies with escape-heavy 512-byte keys.
- **Mocks**: Fake target service.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Protected existing bound plus exact new bound.

### `TestTargetRoutesMapSharedErrorsToStableBrowserEnvelopeAndHTTPStatus`

- **Type**: Table-driven router unit
- **Location**:
  `bifrost-console/internal/browserapi/target_test.go`
- **What it proves**:
  - Every shared code maps to the approved coarse HTTP status.
  - code, safe message, optional scope, and only code-specific typed details are
    preserved.
  - `SESSION_REQUIRED` and `TARGET_AUTHENTICATION_REQUIRED` remain
    distinguishable despite both using HTTP 401.
  - response is JSON/no-store and bounded; cause/key/path/body is absent.
- **Fixtures/data**: One shared error per code with sentinel internal cause.
- **Mocks**: Fake target service.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Protected shared semantics and atomic browser
  adapter.

### `TestBootstrapAndStatusReturnSameSideEffectFreeTargetProjection`

- **Type**: Router unit/integration
- **Location**:
  `bifrost-console/internal/browserapi/target_test.go`
- **What it proves**:
  - Bootstrap and `/target/status` adapt the same snapshot fields.
  - Neither invokes a probe.
  - Normalized address/unencrypted are surrounding configuration facts.
  - no credential, generation, CA path, client, or aggregate health is present.
- **Fixtures/data**: No-target, selected/no-key, and established snapshots.
- **Mocks**: Snapshot provider with probe panic.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Protected new browser DTO meaning.

### `TestConnectCredentialAndRecheckPreserveCommittedStateOnProbeError`

- **Type**: Router/target integration
- **Location**:
  `bifrost-console/internal/browserapi/target_test.go`
- **What it proves**:
  - atomic connect rotates once and probes;
  - first credential preserves existing scope;
  - replacement rotates even for identical bytes;
  - recheck preserves scope unless identity changes;
  - a probe domain error returns its shared envelope while status retains the
    committed selection/credential lifecycle.
- **Fixtures/data**: Deterministic scope IDs and fake probe outcomes.
- **Mocks**: Real target context with fake application client.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current authoritative mutation behavior.

### `TestBrowserTargetFixtureCorpusMatchesCommittedInventoryByteForByte`

- **Type**: Fixture contract unit
- **Location**:
  `bifrost-console/internal/browserapi/contracts_test.go`
- **What it proves**:
  - Complete expected success/error inventory is generated deterministically.
  - Committed bytes match exactly and contain no sentinel key or internal data.
  - No unreviewed fixture is silently added or removed.
- **Fixtures/data**:
  `bifrost-console/browser-fixtures/target/*.json`.
- **Mocks**: Fixed clock, scope IDs, versions, and addresses.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Current atomically shipped Go/browser
  contract.

## 8. Frontend API, State, Overview, and Reset

### `submits target operations with same-origin security and clears credential ownership`

- **Type**: Vitest unit
- **Location**: `bifrost-console/web/src/api/client.test.ts`
- **What it proves**:
  - exact target paths, POST, no-store, redirect-error, same-origin
    credentials, and protected headers;
  - status is session-only, mutations use in-memory tab/CSRF;
  - shared error scope/details are parsed;
  - target-authentication 401 is not treated as session rejection.
- **Fixtures/data**: Shared committed browser target fixtures.
- **Mocks**: `fetch`.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Current atomic browser DTO.

### `target reducer replaces facts within a scope and resets everything on scope change`

- **Type**: Vitest reducer unit
- **Location**:
  `bifrost-console/web/src/target/targetReducer.test.ts`
- **What it proves**:
  - same-scope status updates preserve target-scope presentation generation;
  - different scope or `TARGET_CHANGED` clears error/form/presentation state;
  - no-target transition removes all target-derived state;
  - absent/malformed identity combinations are rejected by DTO guards.
- **Fixtures/data**: Shared browser target fixtures.
- **Mocks**: None.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current stale-browser-state barrier.

### `target provider initializes from bootstrap without probing and remounts scoped children`

- **Type**: React Testing Library integration
- **Location**:
  `bifrost-console/web/src/target/TargetProvider.test.tsx`
- **What it proves**:
  - bootstrap supplies initial state with zero status/probe fetch;
  - same-scope refresh does not remount;
  - changed scope remounts keyed target children and navigates to `/` with
    replace semantics;
  - ordinary update never steals focus.
- **Fixtures/data**: Shared bootstrap/status fixtures and a child mount counter.
- **Mocks**: API methods and memory router.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current future-provider reset boundary.

### `overview connects supplies replaces and rechecks while always clearing key state`

- **Type**: React Testing Library integration
- **Location**:
  `bifrost-console/web/src/target/Overview.test.tsx`
- **What it proves**:
  - current state exposes only valid actions;
  - key input and request-owned component state clear in `finally` on success,
    authentication rejection, access block, unavailability, incompatibility,
    and unexpected failure;
  - replacement confirmation appears when current-scope evidence may exist;
  - key/suffix never renders or enters local/session storage.
- **Fixtures/data**: Shared browser success/error fixtures; sentinel key.
- **Mocks**: Target provider operations and browser storage spies.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current browser credential workflow.

### `overview presents independent target facts and safe actionable categories`

- **Type**: React Testing Library table-driven component
- **Location**:
  `bifrost-console/web/src/target/Overview.test.tsx`
- **What it proves**:
  - all independent status facts, observation time, and instance ID are labeled;
  - HTTP always shows **Unencrypted** and the precise exposure warning;
  - authentication, access blocked, every safe transport/TLS category,
    namespace guidance, redirect, incompatibility versions, and
    live-unavailable text are distinct;
  - no aggregate health term or TLS bypass appears.
- **Fixtures/data**: Shared browser fixtures plus safe category variants.
- **Mocks**: None beyond provider.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Protected shared meanings in browser
  presentation.

### `overview and scope reset preserve accessible focus keyboard and storage boundaries`

- **Type**: React Testing Library integration
- **Location**:
  `bifrost-console/web/src/target/Overview.test.tsx`
- **What it proves**:
  - forms/actions are keyboard operable;
  - status feedback uses appropriate live/status semantics without announcing
    every refresh;
  - replacement navigation focuses the Overview heading;
  - normal refresh does not steal focus;
  - only existing theme presentation state enters `sessionStorage`.
- **Fixtures/data**: Established/replacement/status fixtures.
- **Mocks**: User Event, storage, memory router.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Protected accessibility/security behavior.

### Existing frontend tests to update

- `bifrost-console/web/src/app/App.test.tsx`
  - Replace “Console shell ready” expectations with the correct no-target
    Overview while retaining exact build version and safe not-found text.
- `bifrost-console/web/src/security/BrowserSessionProvider.test.tsx`
  - Verify bootstrap security tokens remain ref-owned while the safe target
    projection reaches `TargetProvider`.
- `bifrost-console/web/src/security/sessionReducer.test.ts`
  - Ensure target facts do not enter browser-session authentication state.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Atomic internal update; do not require both
  old foundation and new Overview.

## 9. Assembled Go Integration and Races

### `TestConsoleTargetIntegrationCoversProtocolAndSafeFailureMatrix`

- **Type**: Go assembly integration
- **Location**:
  `bifrost-console/internal/console/target_integration_test.go`
- **What it proves**:
  - Real config → application client → target context → browser adapter flow for
    HTTP and TLS.
  - Success, rejected/malformed/duplicate key, generic 401/403, mismatch,
    namespace 404, redirect, 5xx, timeout, oversize, content encoding,
    instance mismatch, and changed instance produce exact status/errors.
  - PR 09 sends only `/instance`; no snapshot/SSE/catalog/artifact route is
    called.
- **Fixtures/data**:
  - Controlled `httptest` handlers and committed instance/problem bodies.
- **Mocks**: Only clock/scope IDs; real HTTP stack and assembled services.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Protected Java-to-Go and browser-observable
  semantics.

### `TestConcurrentTabsReplacementRetryAndLateResponseHaveOneCurrentOutcome`

- **Type**: Go concurrency integration
- **Location**:
  `bifrost-console/internal/console/target_integration_test.go`
- **What it proves**:
  - Concurrent paired-tab recheck/replacement requests serialize.
  - Old delayed response and retry callback cannot publish after rotation.
  - All successful responses share the current scope; old callers receive
    `TARGET_CHANGED`.
  - no race or deadlock occurs.
- **Fixtures/data**: Real router sessions with barrier-controlled target server.
- **Mocks**: Deterministic scope IDs/timers only.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current cross-layer lifecycle invariant.
- **Special execution**:
  - `go test -race -count=20 ./internal/console -run
    TestConcurrentTabsReplacementRetryAndLateResponseHaveOneCurrentOutcome`

### Existing assembled security test to update

- `TestLiveConsolePairsBootstrapsAndReleasesLocks`
  - Assert default bootstrap includes the no-target status projection.
  - Assert startup generated no upstream request or retry.
  - Retain the existing pairing, no-store, shutdown, and lock-reopen
    assertions.
- **Contract classification**: Configuration and manifest contracts plus
  internal lifecycle.
- **Compatibility expectation**: Protected existing local-security behavior.

## 10. Packaged Browser E2E

### E2E fixture changes

**Location**:
`bifrost-console/web/e2e/fixtures/consoleProcess.ts`

- Start controlled Node HTTP targets on loopback:
  - compatible target accepting any valid-shape test key;
  - access-blocked/rejected/error modes selected by server state;
  - redirect receiver recording all headers;
  - mutable `instanceId` for restart simulation.
- Return only non-secret control handles/origins to tests.
- Generate credential values inside the browser page for credential-bearing
  workflows; do not pass them through Node test titles, logs, or fixture
  objects.
- Credential-entry specs run with Playwright trace, screenshot, and video
  capture disabled so transient input values cannot enter retained artifacts.
  Noncredential target status/navigation specs retain failure traces.
- Preserve isolated temporary profile/workspace cleanup and hidden child
  windows.

### `paired developer connects and refreshes independent target status`

- **Type**: Playwright E2E
- **Location**:
  `bifrost-console/web/e2e/target-context.spec.ts`
- **What it proves**:
  - Pairing → target/key submission → established independent facts.
  - Normalized address and HTTP warning persist across reload/bootstrap.
  - Browser makes no direct `/_bifrost/observability/` request.
  - URL and browser storage contain no key or diagnostic response.
- **Fixtures/data**: Compatible controlled HTTP target.
- **Mocks**: External application only; real packaged Console/browser.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Current end-to-end Console workflow.
- **Requirement IDs**: `WF-X-R5`, `WF-X-R6`, `WF-X-R7`.

### `developer corrects rejected credentials without a second target lifecycle`

- **Type**: Playwright E2E
- **Location**:
  `bifrost-console/web/e2e/target-context.spec.ts`
- **What it proves**:
  - Recognized rejection shows authentication-required, not access-blocked.
  - Complete replacement key is required and form clears after both attempts.
  - Replacement rotates scope once; browser resets to Overview and establishes
    the new status.
- **Fixtures/data**: Stateful controlled target rejects first probe.
- **Mocks**: External application only.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Current acquisition/authentication semantics.
- **Requirement IDs**: `WF-X-R5`, `WF-X-R10`, `WF-X-R12`.

### `access unavailable incompatible and recheck states remain distinct`

- **Type**: Playwright E2E
- **Location**:
  `bifrost-console/web/e2e/target-context.spec.ts`
- **What it proves**:
  - Generic access block, transient unavailability, incompatibility, and
    successful explicit recheck have distinct text/actions/facts.
  - Temporary failure preserves scope.
  - No aggregate health label appears.
- **Fixtures/data**: Controlled mode-switching target.
- **Mocks**: External application only.
- **Contract classification**: Persisted or serialized contracts.
- **Compatibility expectation**: Protected shared status/error meanings.
- **Requirement IDs**: `WF-X-R5`, `WF-X-R6`, `WF-X-R10`.

### `target replacement or changed application instance resets scoped browser state`

- **Type**: Playwright E2E
- **Location**:
  `bifrost-console/web/e2e/target-context.spec.ts`
- **What it proves**:
  - Replacement confirmation appears.
  - Rotation navigates with replacement semantics to Overview, focuses its
    heading, and removes prior-scope state.
  - Mutating the target's established `instanceId` and rechecking causes the
    same reset before new identity is shown.
- **Fixtures/data**: Two target origins and mutable instance target.
- **Mocks**: External applications only.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current stale-scope UI boundary.
- **Requirement IDs**: `WF-X-R8`, `WF-X-R10`.

### Existing Playwright tests to update

- `shell.spec.ts`
  - Expect no-target Overview instead of foundation.
  - Retain version, theme, asset caching, missing-route, no-service-worker, and
    no direct application request checks.
- `pairing.spec.ts` and `session-lifecycle.spec.ts`
  - Retain pairing/session behavior and prove target bootstrap additions do not
    persist security or target response data.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Protected existing local browser foundation
  with atomic Overview replacement.

## 11. Security and Leak-Detection Tests

### Sentinel strategy

Use a distinctive synthetic value such as:

```text
BIFROST_TEST_APPLICATION_KEY_DO_NOT_LEAK_7f63...
```

Generate it in test memory and never commit it to fixture JSON. For unit and Go
integration tests, collect:

- returned errors and `%v`/`%+v` formatting;
- status/browser JSON;
- captured Console output/log buffers;
- request URLs and redirect/proxy recorder headers;
- browser-rendered text and storage;
- generated fixture bytes.

Assert the sentinel is absent everywhere except the selected target's one
recorded request header and the test-owned input buffer.

### `TestApplicationCredentialNeverAppearsOutsideSelectedRequestHeader`

- **Type**: Cross-package integration
- **Location**:
  `bifrost-console/internal/console/target_integration_test.go`
- **What it proves**:
  - One selected-origin request receives the sentinel header.
  - All captured errors/status/output/fixtures/URLs/redirect/proxy requests omit
    it.
  - replacement/close clear provider-owned storage observable to package tests.
- **Fixtures/data**: Generated sentinel.
- **Mocks**: Selected/redirect/proxy recorders and output buffer.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current security boundary.

### Repository/artifact scan after tests

- Scan committed browser fixtures and generated test output for the fixed
  sentinel prefix:

```powershell
$scanPaths = @("browser-fixtures", "web/test-results", "web/coverage") |
  Where-Object { Test-Path -LiteralPath $_ }
if ($scanPaths.Count -gt 0) {
  rg -n "BIFROST_TEST_APPLICATION_KEY_DO_NOT_LEAK" $scanPaths
}
```

- The test source may construct the prefix; fixture/output matches are
  forbidden.
- Do not retain Playwright credential traces, screenshots, or videos.

## 12. Documentation and Architecture Regression Tests

### Existing Java architecture suite

- Run the existing `BifrostPublicSurfaceArchitectureTest` through the starter
  test suite to confirm PR 09 adds no application API/SPI or Java exposure.
- No new public signature test is required because production Java signatures
  are not planned to change.

### `TestGoProductionPackagesRemainInternalExceptExecutable`

- **Type**: Project declaration unit
- **Location**:
  `bifrost-console/internal/buildtool/projectdeclarations_test.go`
- **What it proves**:
  - `go list ./...` finds production packages only beneath
    `bifrost-console/internal` plus `cmd/bifrost-console`.
  - PR 09 does not create a supported external Go package accidentally.
  - Constructors and private fields are not snapshotted as compatibility
    promises.
- **Fixtures/data**: Current module package list.
- **Mocks**: Existing command runner/test module path helpers.
- **Contract classification**: Supported SPI.
- **Compatibility expectation**: No supported Go SPI added.

### Skill-authoring evidence

- The implementation plan records **No impact**.
- No `ai/skill-authoring/` document or coverage-table test is added.
- Exit review confirms the change remains Console-only; if implementation
  changes a skill-author-facing behavior unexpectedly, stop and revise both
  implementation and testing plans before changing that guidance.

## Mocking and Fixture Rules

- Prefer real `httptest.Server`/`httptest.NewTLSServer` for HTTP behavior.
- Use injected clocks/timers/jitter and channel barriers for lifecycle tests;
  never test retry with production sleeps.
- Generate TLS certificates in test code and keep private keys in temporary
  memory/files only.
- Consume Java fixture bytes from their committed repository location; do not
  copy them beneath the Go module.
- Browser fixture corpus uses fixed clocks/IDs/versions and byte-complete
  inventory comparison, following the Java fixture pattern.
- Frontend tests consume the committed browser fixtures rather than hand-writing
  divergent Java-like responses.
- Application doubles implement only the real Phase 1 `/instance` and problem
  behavior needed by PR 09. They must record unexpected snapshot/SSE/catalog/
  artifact requests as test failures.
- Test errors must be sanitized too: avoid `%#v` on objects that may own a key,
  and do not include request headers/bodies in assertion failures.

## Test Data Matrix

### Target Addresses

- Valid:
  - `http://127.0.0.1:8080`
  - `https://example.test`
  - `https://EXAMPLE.test:443/context/`
  - `http://[::1]:8080/application`
  - nondefault ports and clean multi-segment context paths.
- Invalid:
  - empty/whitespace/over-2-KiB;
  - ftp/file/data/opaque;
  - user info with sentinel;
  - missing/Unicode/percent-encoded host;
  - invalid/default-ambiguous port;
  - IPv6 zone;
  - query/fragment;
  - raw/encoded backslash or separator;
  - repeated slash, `.`/`..`, malformed escape.

### Credentials

- valid exactly 32 bytes;
- valid exactly 512 bytes;
- identical replacement;
- distinct replacement;
- absent, 31, 513;
- space, tab, newline, control, non-ASCII;
- sentinel printable key for leak assertions.

### Instance/Compatibility

- exact complete match;
- missing qualifier;
- prefix/suffix/case/whitespace/different release;
- first identity;
- same established identity;
- changed established identity;
- missing/malformed/duplicate/mismatched header/body identity;
- live monitoring true/false;
- malformed noncompatibility fields under mismatch versus match.

### Transport/Problem

- recognized key rejection;
- generic 401 and 403;
- namespace 404;
- every committed Java problem code;
- unknown/malformed/wrong-content-type problem;
- 301/302/307/308 redirect;
- DNS, refused connection, response timeout;
- custom CA success, untrusted issuer, hostname mismatch, expired,
  not-yet-valid, other handshake;
- 500/502/503;
- oversized headers/body and unsupported content encoding.

### Scope/Race

- no target → first selection;
- YAML selection → first key;
- target replacement;
- identical/distinct key replacement;
- status-only changes;
- first/same/changed identity;
- manual recheck versus scheduled retry;
- old noncooperative result after rotation;
- shutdown during timer, probe, and owner invalidation;
- two paired tabs replacing/rechecking concurrently.

## How to Run

All commands assume Windows PowerShell from the repository root unless a
working directory is stated.

### 1. Baseline Before Adding Red Tests

```powershell
Push-Location bifrost-console
go run ./internal/buildtool verify
Pop-Location

.\mvnw.cmd -pl bifrost-spring-boot-starter `
  '-Dtest=ConsoleRestFixtureCorpusTest' test
```

Record any pre-existing failure before implementation. Do not weaken an
existing assertion to make PR 09 pass.

### 2. First Red Test

```powershell
Push-Location bifrost-console
go test ./internal/config `
  -run TestDecodeAcceptsOptionalTargetConfigurationAndAppliesNetworkDefaults `
  -count=1
Pop-Location
```

Expected pre-fix result: failure because `target` is an unknown field.

### 3. Focused Go Development Loops

```powershell
Push-Location bifrost-console
go test ./internal/config
go test ./internal/applicationclient
go test ./internal/consolecore
go test -race ./internal/target
go test ./internal/credentialprompt ./cmd/bifrost-console
go test ./internal/browserapi
go test -race ./internal/console
Pop-Location
```

### 4. Race/Flake Stress

```powershell
Push-Location bifrost-console
go test -race -count=50 ./internal/target `
  -run 'TestLateProbeResultCannotCommitReturnOrPublishAfterScopeRotation|TestContextSerializesConcurrentProbesAndCommitsOneAuthoritativeIdentity|TestRotationCancelsAndInvalidatesOwnersBeforeNewScopeCanBeCaptured'

go test -race -count=20 ./internal/console `
  -run TestConcurrentTabsReplacementRetryAndLateResponseHaveOneCurrentOutcome
Pop-Location
```

These tests use barriers and fake time, so repetitions must not multiply
production-duration sleeps.

### 5. Frontend Unit and Coverage

```powershell
Push-Location bifrost-console
Push-Location web
npm ci --allow-remote=all
npm run typecheck
npm run test:coverage
Pop-Location
Pop-Location
```

### 6. Java Producer and Go Consumer Contract

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter `
  '-Dtest=ConsoleRestFixtureCorpusTest' test

Push-Location bifrost-console
go test ./internal/applicationclient -run 'Fixture|Compatibility|Problem'
go test ./internal/browserapi -run FixtureCorpus
Pop-Location
```

If and only if a reviewed Java producer correction is required:

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter `
  '-Dtest=ConsoleRestFixtureCorpusTest' `
  '-Dbifrost.console.fixtures.regenerate=true' test

$fixtureRoot = 'bifrost-console-fixtures/application-rest'
$beforeSecondRun = Get-ChildItem -LiteralPath $fixtureRoot -File |
  Sort-Object Name |
  ForEach-Object { "$($_.Name):$((Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash)" }

.\mvnw.cmd -pl bifrost-spring-boot-starter `
  '-Dtest=ConsoleRestFixtureCorpusTest' `
  '-Dbifrost.console.fixtures.regenerate=true' test

$afterSecondRun = Get-ChildItem -LiteralPath $fixtureRoot -File |
  Sort-Object Name |
  ForEach-Object { "$($_.Name):$((Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash)" }

if (Compare-Object $beforeSecondRun $afterSecondRun) {
  throw 'Second Java REST fixture regeneration changed the corpus'
}
```

The second regeneration must produce no additional diff. An intentional
fixture change is reviewed with synchronized Java/Go changes. When no producer
change was approved, require:

```powershell
git diff --exit-code -- bifrost-console-fixtures/application-rest
```

### 7. Full Go and Canonical Console Verification

```powershell
Push-Location bifrost-console
go test -race ./...
go run ./internal/buildtool verify
go run ./internal/buildtool build
Pop-Location
```

The separate race run is required because the canonical build currently runs
`go test ./...` without `-race`.

### 8. Packaged Browser E2E

```powershell
Push-Location bifrost-console
Push-Location web
npm run test:e2e
npm exec -- playwright test e2e/target-context.spec.ts --repeat-each=3
Pop-Location
Pop-Location
```

Credential-bearing specs must disable retained trace/screenshot/video artifacts
as described above.

### 9. Root Java Regression and Formatting

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter test
.\mvnw.cmd verify
git diff --check
```

### Required Environment and Test Data

- Exact repository-declared Go, Node.js, npm, Java, and Maven versions.
- Chromium installed for the pinned Playwright release.
- No external network service, DNS name, public CA, environment proxy, or
  observed application is required by automated tests.
- Tests that manipulate `HTTP_PROXY`/`HTTPS_PROXY` restore environment state
  with `t.Setenv`.
- TLS/private-key files live only under `t.TempDir()` or in test-process memory.
- Port selection uses OS-assigned loopback ports.
- Fake clocks/timers drive retry tests.
- Real packaged manual verification uses a compatible sample application and a
  deliberately generated nonproduction observability key.

## Manual Verification Matrix

| Scenario | Windows x86-64 | Linux x86-64 | macOS Apple Silicon |
| --- | --- | --- | --- |
| Browser connect/replacement/status/recheck | Required | Required | Required |
| HTTP persistent warning | Required | Required | Required |
| Public/system-trusted HTTPS | Required | Required | Required |
| Private CA bundle HTTPS | Required | Required | Required |
| Wrong issuer/hostname/expired certificate language | Required | Required | Required |
| No-echo terminal prompt | Required | Required | Required |
| Ctrl+C/interruption restores echo and exits | Required | Required | Required |
| Redirect/proxy receives no key | Required on one platform plus automated all-platform logic | Automated logic | Automated logic |
| Application restart rotates scope/reset | Required on one platform plus automated all-platform logic | Automated logic | Automated logic |
| Keyboard, focus, 200% zoom, forced colors/reduced motion | Required browser accessibility pass | Smoke | Smoke |

Manual steps:

1. Build the packaged executable from a clean generated-asset tree.
2. Run with no target and confirm no outbound application request.
3. Run with a YAML default and no prompt; confirm selection/authentication
   required and no probe.
4. Run with `--prompt-for-application-key`; inspect echo, interruption, shell
   history, process listing, and paired browser result.
5. Connect through browser over HTTP, system-trusted HTTPS, and private-CA
   HTTPS.
6. Exercise rejected key, generic 401/403, unavailable, redirect, namespace
   404, TLS failures, and incompatible release.
7. Delay a probe while replacing target/key and restart the application; verify
   stale results never appear.
8. Inspect Console/application logs, terminal output, browser DOM/devtools
   storage/history, URLs, and retained test artifacts for credentials.
9. Verify existing pairing/session/theme behavior and profile/workspace lock
   release.

## Exit Criteria

### Failing-Test Discipline

- [x] Baseline canonical Console and Java fixture tests were recorded before
  implementation.
- [x] The first configuration test was added first and failed for the expected
  unknown-`target` reason.
- [ ] Each major package/vertical slice began with its named lowest-cost failing
  test.
- [x] No production sleep, external network, or public trust dependency was
  introduced to make tests pass.

### Functional and Lifecycle

- [ ] Every target configuration, address, credential, compatibility,
  transport, error, status, mutation, and scope row in this plan has automated
  coverage.
- [ ] Initial credential and replacement credential scope behavior are
  distinguished explicitly.
- [ ] First/same/changed identity behavior is covered explicitly.
- [ ] Noncooperative late results cannot commit, return, install, or publish
  after rotation.
- [ ] Registered owners invalidate once, in order, before new-scope capture.
- [ ] Retry has one timer/probe, exact fake-time schedule/jitter bounds, no
  nonretryable retries, and no post-success polling.
- [ ] Browser and terminal paths demonstrably converge on the same provider and
  `TargetContext`.

### Security

- [ ] Credentials are present only in test-owned input/provider memory and the
  one selected-origin request header.
- [ ] Redirect and environment proxy recorders receive no credential and no
  follow-up request.
- [ ] YAML, CLI arguments, environment paths, URLs, logs, errors, status,
  browser fixtures, DOM, storage, and retained artifacts contain no key/suffix.
- [ ] Custom CA augments system roots and never weakens hostname/certificate
  verification.
- [ ] Browser Host/Origin/session/CSRF failures occur before body read and
  target invocation.
- [ ] Existing 1 KiB pairing/session body bounds remain protected; only target
  operations use 4 KiB.
- [ ] Credential-bearing E2E tests retain no trace, screenshot, or video.
- [ ] Terminal interruption restores echo on all supported release platforms.

### Compatibility and Contracts

- [x] Existing target-free schema-version 1 configurations still resolve
  identically.
- [x] No target-field alias, schema fork, compatibility shim, or old/new dual
  behavior exists.
- [x] Java REST/problem fixtures match their producer byte-for-byte and Go
  consumes their observable semantics.
- [x] Exact complete release mismatch is rejected before other instance fields
  are consumed.
- [x] Browser fixtures have a complete byte-compared inventory and are consumed
  by TypeScript tests.
- [x] Existing application API/SPI architecture tests pass; no supported
  Java/Go public surface was added.
- [x] No NDJSON format, fixture, reader, or historical compatibility test was
  added or changed.
- [x] The old foundation page is updated atomically to Overview; tests do not
  require both presentations.

### Automated Gates

- [x] Focused Go package tests pass.
- [ ] Target and Console race tests pass at the required repeat counts.
- [ ] `go test -race ./...` passes.
- [x] Frontend typecheck and coverage pass at repository thresholds.
- [x] Packaged Playwright target workflows pass, including three repeated runs.
- [x] `ConsoleRestFixtureCorpusTest` and the full starter suite pass.
- [x] Root Maven verification passes.
- [x] `go run ./internal/buildtool verify` and `build` pass from a clean asset
  tree.
- [x] `git diff --check` passes.

### Manual Gates

- [ ] The supported-platform terminal matrix is complete.
- [ ] Real HTTP, system-trusted HTTPS, and private-CA HTTPS checks are complete.
- [ ] Keyboard/focus/zoom/forced-colors/reduced-motion verification is complete.
- [ ] Application restart and delayed-response replacement behave as designed.
- [ ] Manual credential-leak inspection found no disclosure.

### Skill-Authoring Documentation

- [x] No `ai/skill-authoring/` change was made, consistent with the approved
  no-impact assessment.
- [x] Implementation review confirms no author-facing skill behavior changed;
  otherwise planning is reopened before documentation is altered.

## References

- Implementation plan:
  `ai/thoughts/plans/2026-07-27-bifrost-console-pr-09-target-context.md`
- Ticket:
  `ai/thoughts/tickets/bifrost-console-pr-09-target-context.md`
- Research:
  `ai/thoughts/research/2026-07-26-bifrost-console-pr-09-target-context.md`
- Java/Go fixture contract:
  `bifrost-console-fixtures/README.md`
- Phase 2 target/status/error design:
  `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md`
- Workflow requirements:
  `ai/thoughts/phases/bifrost_console_workflows.md`
- Framework compatibility lens:
  `ai/thoughts/framework-feature-design-lens.md`
