# PR 08 - Profile, Workspace, and Local Browser Security Testing Plan

## Change Summary

- Replace the PR 07 build-foundation host with a process-owned Console profile,
  strict schema-version 1 YAML, exclusive profile/work locks, a verified
  disposable workspace, and coordinated graceful/fatal shutdown.
- Derive one exact browser authority from one explicit loopback listener and
  reject foreign or ambiguous Host/Origin input before authentication or body
  processing.
- Add one-time pairing, bounded process-local sessions/tabs, per-tab CSRF,
  versioned browser security endpoints, and a restrictive response policy.
- Add the React pairing/bootstrap flow while keeping all security and diagnostic
  state out of persistent browser storage.
- Preserve the existing release/asset validation, explicit IPv4/IPv6 loopback
  choices, SPA navigation behavior, immutable content-addressed asset caching,
  and presentation-only theme storage where they remain compatible with the
  completed security model.

This is a net-new feature rather than a repair of one isolated defect. Testing
will therefore use a sequence of small failing-first tests at each ownership
boundary instead of one artificial end-to-end test that fails for many unrelated
reasons.

## Requirements and Invariant Map

| ID | Required behavior | Primary evidence |
| --- | --- | --- |
| CFG-01 | YAML is exactly schema version 1, one document, strict, uniquely keyed, bounded, and secret-free. | Config unit tests and valid/invalid fixtures |
| CFG-02 | Defaults and explicit `unlimited`/`never` values resolve exactly; zero, unsafe, ambiguous, and overflowing values fail. | Config value-table tests |
| PRF-01 | Config parent defines the profile; default paths and workspace hash are stable and CWD-independent. | Common and platform path tests |
| PRF-02 | One process exclusively owns a protected profile until explicit release. | Cross-process platform lock tests |
| WKS-01 | An absent work root is created with the exact marker/lock/transient layout; an unmarked existing root is untouched. | Workspace establishment tests |
| WKS-02 | Startup cleans only `transient/`, never adopts its entries, and never follows a symlink, junction, or reparse point. | Cleanup containment/platform tests |
| WKS-03 | Profile lock precedes work lock, safe cleanup precedes listening, and every failure unwinds in reverse order. | Startup/lifecycle integration tests |
| WKS-04 | Loss of marker, identity, lock, permissions, or managed I/O is process-fatal; an artifact-local failure is recoverable only after cleanup plus a successful full health probe. | Health/coordinator unit and integration tests |
| NET-01 | Only one explicit IPv4 or IPv6 loopback listener is accepted and its actual bound address defines the production authority. | Host/authority unit and real-listener tests |
| NET-02 | Browser Host and Origin policy is exact; development adds only its configured Vite authority/origin pair. | Request-policy matrix and Playwright development test |
| NET-03 | Route realm is selected before authentication, and browser checks happen before session lookup or body reading. | Router/middleware order tests |
| HDR-01 | CSP, anti-framing, MIME, referrer, permissions, opener/resource, cache, and content-type policy is correct for every response class. | Header response matrix |
| API-01 | Browser APIs are versioned POST routes with 1 KiB-or-smaller JSON bounds, one document, bounded errors, and no redirects. | Browser API unit/integration tests |
| PAIR-01 | Pairing values contain 256 random bits, are five-minute, single-use, atomically consumed, and invalidated on replacement/expiry/shutdown. | Pairing unit/concurrency tests |
| PAIR-02 | Manual pairing exposes its secret only to the owning terminal, keeps one current challenge, and rate-limits generation to 30 seconds. | Pairing handler/output tests |
| SES-01 | The eighth session and sixteenth tab are admitted; later admissions fail without eviction or cross-session effects. | Registry capacity tests |
| SES-02 | Sessions expire after eight idle hours; authenticated success, browser heartbeat, and a live relay refresh activity; disconnected tabs expire after two minutes. | Fake-clock session/tab and browser-provider tests |
| CSRF-01 | Each tab receives an independent 256-bit CSRF token; bootstrap rotates only that tab; validation is fixed-length and constant-time. | CSRF unit/concurrency tests |
| CSRF-02 | Pairing-link creation requires independent valid Host, Origin, session, and CSRF controls. | Security-stack integration matrix |
| WEB-01 | The SPA removes the pairing fragment from the current history entry before awaiting exchange and clears it from application state. | React unit and Playwright history tests |
| WEB-02 | Cookie reuse bootstraps a refreshed page; open tabs heartbeat; expired or BFCache-restored tab registrations re-bootstrap; session expiry returns only that browser to unpaired state. | Provider tests and Playwright session test |
| WEB-03 | Theme remains the only PR 08 browser-storage value; pairing, session, tab, CSRF, bootstrap, errors, and diagnostic data remain memory-only. | Storage-spy tests and browser inspection |
| LIFE-01 | Graceful shutdown closes admission, invalidates browser state, cleans transient content where safe, and releases work then profile locks. | Coordinator and process integration tests |
| LIFE-02 | Workspace-fatal shutdown follows the same order and returns the first fatal cause; request-local browser errors never cancel the process. | Cause/order and live-process tests |
| SECRET-01 | Pairing/session/CSRF candidates do not appear in YAML, ordinary logs, errors, browser storage, query strings, or reusable URLs. | Sentinel capture tests and manual browser inspection |

The ticket acceptance signals map to these IDs:

- path, ownership, permission, lock, cleanup, and restart:
  `PRF-01`, `PRF-02`, `WKS-01` through `WKS-04`, `LIFE-01`, `LIFE-02`;
- foreign origin, authority, session, and CSRF rejection:
  `NET-01` through `NET-03`, `PAIR-01`, `SES-01`, `CSRF-01`, `CSRF-02`;
- listener/lock release and best-effort transient cleanup:
  `WKS-03`, `LIFE-01`, `LIFE-02`.

## Impacted Areas

- `bifrost-console/cmd/bifrost-console/`
  - CLI precedence, startup ordering, browser opening, terminal output, reverse
    failure unwind, and `--version` no-mutation behavior.
- `bifrost-console/internal/config/`
  - New strict YAML parser, defaults, units/sentinels, bounds, and fixtures.
- `bifrost-console/internal/profile/`
  - New platform roots, profile identity, permissions/ACLs, protected atomic
    config creation, and cross-process OS locking.
- `bifrost-console/internal/workspace/`
  - New marker and lock contract, platform file identity, non-following cleanup,
    managed I/O health checks, and fatality classification.
- `bifrost-console/internal/lifecycle/`
  - New first-cause cancellation and ordered close coordination.
- `bifrost-console/internal/webhost/`
  - Listener authority capture, route composition, static response policy, and
    graceful/fatal server shutdown.
- `bifrost-console/internal/browserauth/`
  - New entropy, pairing, cookie, session/tab registry, CSRF, expiry, capacity,
    and shutdown behavior.
- `bifrost-console/internal/browserapi/`
  - New realm router, request policy, bounded error/body utilities, security
    headers, pairing/bootstrap/tab handlers, and middleware composition.
- `bifrost-console/internal/browseropen/`
  - New injected OS-specific default-browser integration.
- `bifrost-console/internal/console/`
  - New complete service assembly and live-process integration harness.
- `bifrost-console/web/src/api/` and `web/src/security/`
  - New same-origin client, pairing-fragment handling, session provider, and
    storage policy.
- Existing React shell/routing/theme files
  - Unpaired/pairing/paired states and protected preservation of presentation
    storage.
- Vite, Vitest, and Playwright configuration
  - Exact development-origin tests, coverage inclusion, and isolated
    per-test-process E2E profiles/workspaces.
- `bifrost-console/README.md`
  - Executable configuration examples that must parse with production code.

## Risk Assessment

### Critical

- Cleanup escaping the verified root or following a symlink/junction/reparse
  point could delete unrelated user data.
- Acquiring locks or cleaning in the wrong order could let a second process
  clean an active process's workspace.
- Continuing after loss of workspace identity or safe I/O would let later
  artifact services operate on an untrusted or partially failed root.
- Accepting a foreign Host/Origin, substituting one authentication realm for
  another, or processing a body before authority checks could expose local
  Console operations to a malicious web origin.
- Reusing, leaking, or persisting pairing/session/CSRF values could turn a
  one-time local bootstrap into a reusable credential.

### High

- YAML permissiveness, ambiguous units, or silent fallback could make two
  processes interpret the same profile differently.
- File locks tested only in-process could pass despite incorrect real OS
  exclusion semantics.
- Windows path aliases or reparse points could bypass tests written only for
  Unix symlinks.
- Shared CSRF state could cause one tab's refresh to invalidate another, while
  incorrectly shared tab/relay ownership could exceed settled bounds.
- Shutdown races could release locks before HTTP/workspace users stop or lose
  the fatal cause.
- Security headers or cache policy could be correct on successful HTML but
  absent on errors, API responses, HEAD, or not-found paths.

### Medium

- Browser-open failure could accidentally terminate an otherwise usable
  Console or suppress the printed fallback URL.
- E2E tests could mutate the developer's default profile, leave locks behind,
  or persist pairing secrets in test artifacts.
- New browser state could broaden `sessionStorage` beyond presentation-only
  state.
- Exact IPv6 authority formatting and an ephemeral `:0` port could diverge
  between the printed URL and accepted Host/Origin.

### Compatibility Paths

| Surface | Test expectation |
| --- | --- |
| Application API | No Java tests or fixtures change; PR 08 must not make the Maven/Java suite a Console dependency. |
| Supported SPI | No SPI tests are added because no supported extension point exists. |
| Configuration and manifest contracts | Protect the new exact version-1 YAML, CLI precedence, default paths, sentinel meanings, strict rejection, and README examples. Do not add a legacy reader. |
| Persisted or serialized contracts | Protect the exact work marker, ownership metadata interpretation, and cookie attributes/process lifetime. Prove `transient/` is never adopted. |
| Ephemeral diagnostic formats | No Java REST/SSE/problem/NDJSON fixture or cross-version trace test is required. Browser-local errors are tested only for current embedded-browser coherence, bounds, and secrecy. |
| Internal or accidentally exposed implementation | Replace the old `/api/console` reserved-404-only behavior atomically with the versioned browser adapter. Do not test both old and new API behavior or add browser API negotiation. Preserve SPA non-fallback for unknown API paths. |

The approved no-shim decision is verified by the absence of legacy config
versions, marker aliases, fallback workspaces, unauthenticated sensitive routes,
dual old/new browser APIs, and browser API compatibility negotiation.

## Existing Test Coverage

### Go

- `cmd/bifrost-console/main_test.go`
  - invalid product version and invalid embedded assets prevent listening;
  - asset validation precedes serving;
  - `--version` prints the injected complete version without listening.
- `internal/webhost/host_test.go`
  - listener announcement follows successful bind;
  - explicit IPv4/IPv6 loopback is accepted and wildcard, LAN, public, and
    hostname listeners are rejected;
  - context cancellation closes the real HTTP listener.
- `internal/webhost/static_test.go`
  - root/deep-link SPA fallback;
  - API and asset paths do not fall back to the SPA;
  - immutable hashed-asset versus `no-store` navigation caching;
  - GET/HEAD/method behavior and `nosniff`.
- `internal/buildtool/cleanup_test.go`
  - the build-only cleanup helper keeps an outside sentinel and rejects an
    escaped or symlinked generated directory.
- Buildtool pipeline tests
  - phases stop on failure and run in the required order;
  - clean frontend builds and asset tampering are integration-tested.

### React and Browser

- `web/src/app/App.test.tsx`
  - shell/version/deep-route behavior and safe text rendering.
- `web/src/app/ThemeSelect.test.tsx`
  - keyboard theme selection, `sessionStorage` use, and empty `localStorage`.
- `web/vite.config.test.ts`
  - production build isolation and loopback-only development proxy.
- `web/e2e/shell.spec.ts`
  - packaged root/deep-link/theme/assets behavior, no service worker, and no
    direct Java observability request.
- Vitest currently enforces 80% line/function/statement and 70% branch coverage
  for frontend source, excluding only `main.tsx` and type declarations.

### Baseline Verification

Before this testing plan was written:

- `go test ./...` passed all current Console Go packages.
- `npm test -- --run` passed 3 files and 8 tests.

### Gaps

- No YAML, profile, lock, permission/ACL, managed workspace, platform identity,
  cleanup monitor, or fatal coordinator exists.
- No request Host/Origin policy, realm router, complete security headers,
  bounded JSON error/body helper, or browser API exists.
- No entropy, pairing, session, tab, relay, CSRF, expiry, capacity, or
  concurrency tests exist.
- No React pairing/bootstrap/provider/storage tests exist.
- Current Playwright starts the binary against its default process environment,
  cannot pair, and has no isolated profile/workspace or in-memory pairing URL
  fixture.
- No real cross-process lock test or Windows junction/reparse test exists.
- No supported-target verification record convention currently exists because
  the repository has no checked-in CI workflow.

## Bug Reproduction / Failing Test First

This is a feature, not a regression with one reproduction. The first
implementation red test should nevertheless fail against a current executable
behavior rather than only fail to compile against a not-yet-created package.

- **Name**:
  `TestStaticHandlerAppliesCompleteBrowserSecurityHeaders`
- **Type**: unit
- **Location**:
  `bifrost-console/internal/webhost/static_test.go`
- **Arrange**:
  create the existing in-memory static filesystem and issue `GET /`.
- **Act**:
  invoke the current `StaticHandler`.
- **Assert**:
  require the settled CSP plus `X-Frame-Options: DENY`,
  `Referrer-Policy: no-referrer`, restrictive `Permissions-Policy`,
  `Cross-Origin-Opener-Policy: same-origin`,
  `Cross-Origin-Resource-Policy: same-origin`, existing `nosniff`, and
  `Cache-Control: no-store`.
- **Expected failure before implementation**:
  the current handler emits only `nosniff` and cache/content headers, so the
  test reliably fails on the first missing CSP/security header.
- **Contract classification**:
  Internal or accidentally exposed implementation.
- **Compatibility expectation**:
  approved atomic replacement of the PR 07 foundation response policy; do not
  preserve an unsecured response path.

After this initial red test, each implementation phase starts with its smallest
boundary test before production code:

1. `TestDecodeAcceptsCanonicalVersionOneDefaults` for configuration;
2. `TestOpenWorkspaceRejectsUnmarkedDirectoryWithoutMutation` for deletion
   safety;
3. `TestBrowserPolicyRejectsWrongHostBeforeReadingBody` for middleware order;
4. `TestPairingChallengeCanBeConsumedExactlyOnce` for pairing state;
5. `removes pairing fragment before awaiting exchange` for the browser;
6. `TestServiceUnwindsEveryAcquiredResourceInReverseOrder` for assembly.

## Test Infrastructure and Fixture Design

### Deterministic Go Dependencies

- Use injected `Clock`/timer interfaces for five-minute pairing, 30-second
  challenge rate, two-minute tab expiry, eight-hour session expiry, and
  30-second workspace health cadence. No unit test waits for wall-clock expiry.
- Use deterministic entropy readers that return recognizable 32-byte values and
  explicit short-read/error cases. Production entropy tests assert decoded
  length, not exact random output.
- Use order-recording fakes for profile/workspace/listener/browser opener and
  lifecycle components.
- Use an HTTP request body spy that records the first `Read` to prove Host,
  Origin, session, and CSRF rejection order.
- Use `httptest.ResponseRecorder` for handler matrices and a real
  `127.0.0.1:0`/`[::1]:0` listener only where actual bound authority matters.

### Configuration Fixtures

Add `bifrost-console/internal/config/testdata/`:

- `valid-default.yaml`
- `valid-explicit.yaml`
- `valid-unlimited-never.yaml`
- `invalid-version.yaml`
- `invalid-unknown-field.yaml`
- `invalid-duplicate-key.yaml`
- `invalid-multiple-documents.yaml`
- `invalid-zero.yaml`
- `invalid-negative.yaml`
- `invalid-bare-units.yaml`
- `invalid-overflow.yaml`
- `invalid-alias-depth.yaml`
- `invalid-secret-field.yaml`

Fixtures remain minimal and assert exact accepted/rejected shapes. The README
example is extracted or copied through a test helper and decoded by the same
production loader to prevent documentation drift.

### Cross-Process Lock Harness

- Add a test-only helper mode implemented through the Go test binary itself.
  The parent starts the helper with `-test.run` and a sentinel environment
  variable; the helper acquires the real profile/work lock, writes `locked\n`
  to its stdout pipe, and waits on stdin.
- Only after reading `locked\n` does the parent attempt the conflicting lock.
  The parent then closes the helper stdin, waits for release, and proves the
  lock can be reacquired. Do not coordinate with arbitrary sleeps.
- Run this harness in platform tests on Windows, Linux, and macOS.

### Filesystem Safety Fixtures

- Every destructive test creates its root with `t.TempDir()` and first resolves
  and asserts that all candidate paths remain beneath that exact test root.
- Each test places an outside sentinel in a sibling directory and verifies its
  bytes and identity after success or failure.
- Unix tests create real nested symbolic links where supported.
- Windows tests create a real junction/reparse fixture through a Windows-only
  test helper using Win32/x-sys primitives so the required junction case does
  not depend on developer-mode symlink privilege. A separate attribute-level
  test covers a generic reparse tag.
- Permission tests alter only test-owned directories and restore access in
  `t.Cleanup` before temporary-directory removal.

### Playwright Console Fixture

Replace the current static `webServer` dependency for PR 08 scenarios with a
worker-scoped `consoleProcess` fixture:

- create an isolated OS temp directory;
- allocate exact `--config` and `--work-dir` children within it;
- start the built executable on `127.0.0.1:0` with
  `--no-open-browser`;
- capture stdout in memory until the work root and pairing URL are available;
- expose the origin and one-time fragment URL to the test without writing the
  secret to disk, Playwright trace metadata, or console logs;
- stop the process and verify lock release/cleanup in fixture teardown.

The fixture redacts the pairing fragment from failure messages. Tests that need
a fresh process receive a fresh profile/work root and never touch the
developer's default profile.

## Tests to Add or Update

### 1. Strict YAML Contract Suite

- **Names**:
  - `TestDecodeAcceptsCanonicalVersionOneDefaults`
  - `TestDecodeAcceptsExplicitLimitsAndSentinels`
  - `TestDecodeRejectsUnknownDuplicateAndMultipleDocumentInput`
  - `TestDecodeRejectsUnsafeAmbiguousAndOverflowingValues`
  - `TestDecodeEnforcesInputAliasAndDepthBounds`
  - `TestDefaultConfigRoundTripsThroughStrictLoader`
  - `TestConfigSchemaContainsNoSecretFields`
- **Type**: unit/fixture
- **Location**:
  `internal/config/config_test.go`,
  `internal/config/testdata/*.yaml`
- **What it proves**:
  `CFG-01`, `CFG-02`; exact accepted shape and fail-closed invalid forms.
- **Fixtures/data**:
  the configuration fixtures listed above plus table-driven boundary values.
- **Mocks**:
  none; use byte readers and the production decoder.
- **Contract classification**:
  Configuration and manifest contracts.
- **Compatibility expectation**:
  protect schema version 1 only; unsupported versions fail and no legacy reader
  exists.

### 2. CLI Precedence and No-Mutation Suite

- **Names**:
  - `TestVersionFlagCreatesNoProfileWorkspaceOrListener`
  - `TestListenFlagOverridesValidatedYAMLForOneProcess`
  - `TestWorkDirFlagSelectsExactRoot`
  - `TestDevelopmentOriginIsProcessOnlyAndLoopbackExact`
  - `TestRunRejectsConfigurationBeforeWorkspaceAndListen`
  - `TestRunValidatesAssetsBeforeProfileMutation`
- **Type**: unit/integration
- **Location**:
  `cmd/bifrost-console/main_test.go`
- **What it proves**:
  CLI precedence, release/asset/config ordering, and no side effects for
  `--version` or pre-profile validation failures.
- **Fixtures/data**:
  temporary config paths and ordered dependency-call recorder.
- **Mocks**:
  injected asset verifier/profile/workspace/serve/browser-open functions.
- **Contract classification**:
  Configuration and manifest contracts.
- **Compatibility expectation**:
  protect `--version`, exact loopback `--listen`, and the new flags; remove no
  protected CLI path and add no fallback interpretation.

### 3. Platform Profile Path and Identity Suite

- **Names**:
  - `TestDefaultConfigAndStateRootsFollowPlatformContract`
  - `TestProfileIdentityIsStableAcrossWorkingDirectories`
  - `TestDistinctCanonicalProfilesHaveDistinctWorkspaceLeaves`
  - `TestWindowsProfileIdentityNormalizesCaseAndFinalPath`
  - `TestProfileRejectsLinkedOrReparseParent`
- **Type**: unit/platform integration
- **Location**:
  `internal/profile/paths_test.go`,
  `internal/profile/paths_windows_test.go`
- **What it proves**:
  `PRF-01`; exact platform roots and deterministic profile-to-workspace mapping.
- **Fixtures/data**:
  injected environment/home/local-root values and temporary path aliases.
- **Mocks**:
  environment/path-provider injection for pure root cases; real filesystem for
  final-path identity.
- **Contract classification**:
  Configuration and manifest contracts.
- **Compatibility expectation**:
  protect documented default resolution and exact `--config` identity.

### 4. Profile Protection and Cross-Process Lock Suite

- **Names**:
  - `TestOpenProfileCreatesProtectedDirectoryLockAndDefaultConfig`
  - `TestOpenProfileRejectsWeakExistingPermissionsOrACL`
  - `TestProfileLockExcludesAnotherProcessUntilRelease`
  - `TestProfileLockContentionDoesNotReadOrMutateConfig`
  - `TestProfileReleaseIsIdempotent`
  - `TestProfileConfigCreationIsAtomic`
- **Type**: platform integration
- **Location**:
  `internal/profile/profile_test.go`,
  `internal/profile/lock_platform_test.go`
- **What it proves**:
  `PRF-02`, `WKS-03`; real OS exclusion, protection verification, and safe
  creation sequencing.
- **Fixtures/data**:
  temp profiles, outside sentinels, helper-process lock harness.
- **Mocks**:
  injected atomic-write failure points only; lock behavior is never mocked in
  the platform test.
- **Contract classification**:
  Persisted or serialized contracts.
- **Compatibility expectation**:
  exact `.bifrost-console.lock` ownership behavior; no permissive fallback.

### 5. Managed Workspace Establishment Suite

- **Names**:
  - `TestOpenWorkspaceCreatesExactProtectedLayout`
  - `TestOpenWorkspaceAcceptsExactVersionOneMarker`
  - `TestOpenWorkspaceRejectsUnmarkedDirectoryWithoutMutation`
  - `TestOpenWorkspaceRejectsWrongMarkerWithoutMutation`
  - `TestWorkLockExcludesAnotherProfileProcess`
  - `TestWorkspaceRejectsRootOrRequiredChildIdentitySubstitution`
- **Type**: unit/platform integration
- **Location**:
  `internal/workspace/workspace_test.go`,
  `internal/workspace/workspace_platform_test.go`
- **What it proves**:
  `WKS-01`, `WKS-03`; exact metadata and exclusion before deletion.
- **Fixtures/data**:
  absent/marked/unmarked/wrong-marker temp roots and cross-process lock helper.
- **Mocks**:
  platform identity provider only in focused error-injection tests; real
  platform identity and lock in platform tests.
- **Contract classification**:
  Persisted or serialized contracts.
- **Compatibility expectation**:
  protect only `bifrost-console-work-v1\n`; no marker alias or adoption.

### 6. Non-Following Cleanup Suite

- **Names**:
  - `TestCleanupTransientRemovesNestedTreeAndPreservesMetadataAndSiblings`
  - `TestCleanupTransientRejectsEscapedTargetBeforeDeletion`
  - `TestCleanupTransientRejectsNestedSymlinkAndPreservesOutsideSentinel`
  - `TestCleanupTransientRejectsWindowsJunctionAndGenericReparsePoint`
  - `TestCleanupTransientRejectsIdentityChangeDuringTraversal`
  - `TestRestartCleanupNeverAdoptsPriorEntries`
- **Type**: unit/platform integration
- **Location**:
  `internal/workspace/cleanup_test.go`,
  `internal/workspace/cleanup_platform_test.go`
- **What it proves**:
  `WKS-02`; no traversal/link following and no prior-process adoption.
- **Fixtures/data**:
  mixed nested files/directories, outside sentinel, Unix symlink, Windows
  junction/reparse, injected identity-swap point.
- **Mocks**:
  injected directory operation wrapper only for deterministic race/failure
  points; real filesystem for containment and platform link cases.
- **Contract classification**:
  Persisted or serialized contracts.
- **Compatibility expectation**:
  protect the disposable/non-adopting contract; do not reuse the buildtool's
  generic recursive cleanup as a compatibility path.

### 7. Workspace Health and Fatality Suite

- **Names**:
  - `TestWorkspaceCheckAcceptsUnchangedOwnedRoot`
  - `TestWorkspaceCheckDetectsMarkerIdentityLockPermissionAndProbeFailure`
  - `TestWorkspaceMonitorUsesThirtySecondCadenceWithoutOverlappingChecks`
  - `TestArtifactLocalFailureRemainsScopedOnlyAfterCleanupAndHealthyProbe`
  - `TestArtifactLocalFailureBecomesFatalWhenCleanupOrProbeFails`
  - `TestCoordinatorPreservesFirstFatalCauseAndNotifiesOnce`
- **Type**: unit/integration
- **Location**:
  `internal/workspace/health_test.go`,
  `internal/lifecycle/coordinator_test.go`
- **What it proves**:
  `WKS-04`, `LIFE-02`; the settled fatal/request-local boundary and first-cause
  behavior.
- **Fixtures/data**:
  fake clock, identity/lock/probe failure table, ordered component recorder.
- **Mocks**:
  injected health primitives for each failure; at least one real managed probe
  success/failure test per platform.
- **Contract classification**:
  Internal or accidentally exposed implementation.
- **Compatibility expectation**:
  approved removal of any degraded-serving possibility; no workspace-unavailable
  browser error or fallback directory.

### 8. Host Authority and Listener Suite

- **Names**:
  - update `TestHostRejectsNonLoopbackAddress`
  - `TestHostDerivesAuthorityFromActualIPv4BoundAddress`
  - `TestHostDerivesBracketedIPv6Authority`
  - `TestHostUsesEphemeralBoundPortRatherThanConfiguredZero`
  - `TestHostReturnsFatalCancellationCauseAfterShutdown`
  - preserve `TestHostAnnouncesOnlyAfterListenSucceeds`
- **Type**: unit/real-listener integration
- **Location**:
  `internal/webhost/host_test.go`,
  `internal/webhost/authority_test.go`
- **What it proves**:
  `NET-01`, `LIFE-02`; explicit loopback and trustworthy bound authority.
- **Fixtures/data**:
  IPv4/IPv6 address table and real ephemeral listeners. IPv6 absence may skip
  only the real bind test; pure bracket formatting must always run.
- **Mocks**:
  injected listener for error/order cases; real listener for actual address.
- **Contract classification**:
  Internal or accidentally exposed implementation.
- **Compatibility expectation**:
  preserve one chosen IPv4/IPv6 loopback listener and intentionally reject
  coordinated/hostname/wildcard alternatives.

### 9. Realm, Host, Origin, and Middleware-Order Suite

- **Names**:
  - `TestRouterSelectsBrowserAndReservedMCPRealmsBeforeAuthentication`
  - `TestBrowserPolicyAcceptsOnlyActualProductionAuthorityAndOrigin`
  - `TestDevelopmentPolicyAddsOnlyConfiguredViteAuthorityOriginPair`
  - `TestBrowserPolicyRejectsMissingForeignMalformedDuplicatedAndCommaJoinedValues`
  - `TestBrowserPolicyRejectsWrongHostBeforeReadingBodyOrLookingUpSession`
  - `TestBrowserPolicyRejectsWrongOriginBeforeReadingBodyOrLookingUpSession`
  - `TestUnknownBrowserAPIPathNeverFallsBackToSPA`
- **Type**: unit/integration
- **Location**:
  `internal/browserapi/request_policy_test.go`,
  `internal/webhost/routes_test.go`
- **What it proves**:
  `NET-02`, `NET-03`, `API-01`; exact independent checks and realm isolation.
- **Fixtures/data**:
  authority/origin matrix, body-read spy, session-lookup spy, browser/MCP paths.
- **Mocks**:
  instrumented downstream handlers and registries.
- **Contract classification**:
  Internal or accidentally exposed implementation.
- **Compatibility expectation**:
  atomically replace reserved-only API behavior while preserving no-SPA-fallback
  for unknown API paths; do not accept “browser or MCP” credentials.

### 10. Security Header, Cache, Error, and Body Suite

- **Names**:
  - `TestStaticHandlerAppliesCompleteBrowserSecurityHeaders`
  - `TestResponsePolicyMatrixCoversEntryAssetAPIAuthErrorMethodAndNotFound`
  - `TestOnlyContentAddressedAssetsAreImmutable`
  - `TestAuthenticatedAndSecurityResponsesAreNoStore`
  - `TestBrowserErrorEnvelopeIsBoundedSanitizedAndStable`
  - `TestJSONDecoderRejectsOversizeUnknownTrailingAndMultipleDocuments`
  - `TestSecurityFailuresNeverRedirect`
- **Type**: unit
- **Location**:
  `internal/webhost/static_test.go`,
  `internal/browserapi/headers_test.go`,
  `internal/browserapi/errors_test.go`
- **What it proves**:
  `HDR-01`, `API-01`, `SECRET-01`; complete response-class coverage and bounds.
- **Fixtures/data**:
  response matrix; sentinel path, stack, secret, request body, and oversized
  safe-message inputs.
- **Mocks**:
  in-memory filesystem and response recorder.
- **Contract classification**:
  Internal or accidentally exposed implementation.
- **Compatibility expectation**:
  preserve immutable hashed assets and no-store entry behavior while removing
  unsecured response variants.

### 11. Pairing Challenge Suite

- **Names**:
  - `TestPairingChallengeUsesExactlyThirtyTwoRandomBytesAndBase64URL`
  - `TestPairingChallengeCanBeConsumedExactlyOnce`
  - `TestPairingChallengeRejectsWrongMalformedExpiredAndReplayedValues`
  - `TestPairingChallengeReplacementAndShutdownInvalidatePriorValue`
  - `TestConcurrentPairingExchangeCreatesAtMostOneSession`
  - `TestManualChallengePrintsOnlyToTerminalAndRateLimitsThirtySeconds`
  - `TestEntropyFailureProducesNoChallengeOrSecretOutput`
- **Type**: unit/concurrency
- **Location**:
  `internal/browserauth/pairing_test.go`,
  `internal/browserapi/pairing_test.go`
- **What it proves**:
  `PAIR-01`, `PAIR-02`, `SECRET-01`.
- **Fixtures/data**:
  deterministic 32-byte reader, short/error reader, fake clock, terminal/log
  buffers, simultaneous exchange barrier.
- **Mocks**:
  entropy, clock, terminal, logger, and session creator.
- **Contract classification**:
  Persisted or serialized contracts for the process-local credential behavior;
  browser endpoint spelling remains internal.
- **Compatibility expectation**:
  one current process-local flow only; no reusable or legacy pairing path.

### 12. Session, Tab, Cookie, and Relay Admission Suite

- **Names**:
  - `TestSessionCookieHasExactPlaintextLoopbackAttributes`
  - `TestRegistryAdmitsEightSessionsAndRejectsNinthWithoutEviction`
  - `TestRegistryAdmitsSixteenTabsAcrossSessionsAndRejectsSeventeenth`
  - `TestSuccessfulAuthenticatedRequestRefreshesOnlyItsSession`
  - `TestFailedAuthenticationDoesNotRefreshIdleTime`
  - `TestActiveRelayKeepsSessionAliveAndOneRelayPerTabIsEnforced`
  - `TestDisconnectedTabExpiresAfterTwoMinutes`
  - `TestSessionExpiresAfterEightIdleHoursAndClosesOnlyItsRelays`
  - `TestShutdownInvalidatesAllSessionsTabsTokensAndRelayAdmissions`
- **Type**: unit/race
- **Location**:
  `internal/browserauth/sessions_test.go`,
  `internal/browserauth/cookie_test.go`
- **What it proves**:
  `SES-01`, `SES-02`, `WEB-02`.
- **Fixtures/data**:
  fake clock, deterministic IDs, multiple session/tab table, fake relay closers.
- **Mocks**:
  clock/entropy/relay close callback.
- **Contract classification**:
  Persisted or serialized contracts for exact cookie serialization; registry
  decomposition is internal.
- **Compatibility expectation**:
  protect exact limits, lifetime, and nonpersistent cookie; do not add eviction
  or cross-session effects.

### 13. Per-Tab CSRF Suite

- **Names**:
  - `TestBootstrapIssuesThirtyTwoByteTokenBoundToSessionAndTab`
  - `TestBootstrapRotatesOnlyRequestingTabsToken`
  - `TestCSRFRejectsMissingDuplicateMalformedWrongTabAndStaleToken`
  - `TestCSRFRejectsTokenAfterSessionOrTabExpiry`
  - `TestConcurrentBootstrapAndValidationRemainRaceSafe`
  - `TestCSRFValidationUsesFixedDecodedLengthAndComparisonSeam`
- **Type**: unit/race
- **Location**:
  `internal/browserauth/csrf_test.go`,
  `internal/browserapi/bootstrap_test.go`
- **What it proves**:
  `CSRF-01`; independent per-tab state and safe validation.
- **Fixtures/data**:
  two sessions, multiple tabs, deterministic token bytes, fake clock.
- **Mocks**:
  entropy and clock. Constant-time behavior is established through the
  fixed-length decode/central comparison implementation seam, not timing
  benchmarks.
- **Contract classification**:
  Persisted or serialized contracts for process-local CSRF behavior.
- **Compatibility expectation**:
  no global token, cookie-only sensitive operation, or persistent CSRF state.

### 14. Complete Browser Security Stack Suite

- **Names**:
  - `TestPairingExchangeRequiresHostOriginAndOneTimeSecretIndependently`
  - `TestBootstrapRequiresHostOriginAndSessionIndependently`
  - `TestPairingLinkRequiresHostOriginSessionAndCSRFIndependently`
  - `TestSecurityFailureOrderPreventsBodyReadAndReturnsGenericNoStoreError`
  - `TestSuccessfulPairingConsumesChallengeBeforeCookieIssue`
  - `TestBootstrapReturnsOnlyPairedWorkspaceAndProcessFacts`
  - `TestBrowserErrorsDoNotCancelConsoleLifecycle`
- **Type**: handler integration/race
- **Location**:
  `internal/browserapi/security_integration_test.go`
- **What it proves**:
  `NET-03`, `CSRF-02`, `API-01`, `LIFE-02`, `SECRET-01`.
- **Fixtures/data**:
  full truth-table where exactly one control is invalid at a time; sentinel
  secret/path/internal cause; concurrent exchange barrier.
- **Mocks**:
  real auth registries with fake clock/entropy; downstream handler spies.
- **Contract classification**:
  Internal or accidentally exposed implementation.
- **Compatibility expectation**:
  completed fail-closed browser adapter only; no unauthenticated sensitive
  fallback or target/MCP credential substitution.

### 15. Browser Opener and Terminal Output Suite

- **Names**:
  - `TestPairingURLUsesActualOriginAndFragmentNotQuery`
  - `TestStartupAlwaysPrintsPairingURLAfterChallengeAndBind`
  - `TestNoOpenBrowserSuppressesOnlyOpener`
  - `TestBrowserOpenFailureIsSanitizedAndNonfatal`
  - `TestOpenerAndLogsDoNotReceiveSeparateSecretFields`
- **Type**: unit/platform smoke
- **Location**:
  `internal/browseropen/open_test.go`,
  `cmd/bifrost-console/main_test.go`
- **What it proves**:
  `PAIR-02`, `SECRET-01`; printed fallback and nonfatal OS integration.
- **Fixtures/data**:
  actual origin, deterministic fragment, output/log capture.
- **Mocks**:
  injected opener and terminal writer; one per-platform smoke test invokes only
  argument construction, not the user's real browser.
- **Contract classification**:
  Internal or accidentally exposed implementation.
- **Compatibility expectation**:
  new startup UX; no reusable credential or fatal opener dependency.

### 16. React Pairing Fragment and Client Suite

- **Names**:
  - `removes pairing fragment before awaiting exchange`
  - `submits pairing secret once in a same-origin bounded request`
  - `clears pairing value after success and failure`
  - `does not render or log invalid pairing candidates`
  - `maps browser security errors without target semantics`
- **Type**: React/unit
- **Location**:
  `web/src/security/pairingFragment.test.ts`,
  `web/src/security/PairingPage.test.tsx`,
  `web/src/api/client.test.ts`
- **What it proves**:
  `WEB-01`, `SECRET-01`.
- **Fixtures/data**:
  fake history, deferred exchange promise, fetch spy, sentinel secret/error.
- **Mocks**:
  `history.replaceState`, same-origin fetch, deferred promise.
- **Contract classification**:
  Internal or accidentally exposed implementation.
- **Compatibility expectation**:
  embedded browser and Go API update atomically; no browser API negotiation.

### 17. React Session Provider and Storage Suite

- **Names**:
  - `bootstraps with valid cookie and keeps CSRF only in memory`
  - `refresh replaces only current tabs CSRF state`
  - `session expiry clears security state and renders unpaired page`
  - `tab disposal sends best-effort release without blocking unload`
  - `stores only approved theme presentation state`
  - `does not persist bootstrap errors or security values`
- **Type**: React/unit
- **Location**:
  `web/src/security/BrowserSessionProvider.test.tsx`,
  `web/src/security/storagePolicy.test.ts`,
  `web/src/app/App.test.tsx`,
  `web/src/app/ThemeSelect.test.tsx`
- **What it proves**:
  `WEB-02`, `WEB-03`, `SECRET-01`.
- **Fixtures/data**:
  bootstrap/pairing DTOs, session-expiry responses, storage spies.
- **Mocks**:
  API client, page lifecycle events, `localStorage`/`sessionStorage`.
- **Contract classification**:
  Persisted or serialized contracts for the storage prohibition and
  presentation-only theme key; UI composition remains internal.
- **Compatibility expectation**:
  preserve theme storage and prohibit security/diagnostic persistence.

### 18. Service Startup, Fatality, and Reverse-Unwind Suite

- **Names**:
  - `TestServiceStartsInValidatedOwnershipOrder`
  - `TestServiceDoesNotListenForEveryPreListenFailure`
  - `TestServiceUnwindsEveryAcquiredResourceInReverseOrder`
  - `TestGracefulShutdownClosesAdmissionCleansTransientAndReleasesLocks`
  - `TestWorkspaceFatalShutdownReturnsFirstCauseAfterSameOrderedClose`
  - `TestUnsafeWorkspaceSkipsCleanupButStillReleasesSafeResources`
  - `TestRequestScopedBrowserFailureLeavesServiceRunning`
- **Type**: integration/race
- **Location**:
  `internal/console/service_test.go`,
  `internal/console/lifecycle_integration_test.go`
- **What it proves**:
  `WKS-03`, `WKS-04`, `LIFE-01`, `LIFE-02`.
- **Fixtures/data**:
  ordered resource recorder; failure injected after each acquisition and during
  each close step; real temp profile/workspace for final integration.
- **Mocks**:
  order recorder for exhaustive failure matrix; real profile/workspace/listener
  for representative graceful/fatal cases.
- **Contract classification**:
  Internal or accidentally exposed implementation.
- **Compatibility expectation**:
  preserve pre-listen asset validation and signal shutdown while replacing the
  minimal host lifecycle atomically.

### 19. Live HTTP Security Integration Suite

- **Names**:
  - `TestLiveConsolePairsBootstrapsAndPerformsProtectedOperation`
  - `TestLiveConsoleRejectsForeignAuthorityOriginSessionAndCSRF`
  - `TestLiveConsoleSessionAndTabLimitsDoNotAffectOtherClients`
  - `TestLiveConsoleRestartInvalidatesCookieAndCleansTransient`
  - `TestLiveConsoleShutdownReleasesProfileAndWorkLocks`
- **Type**: live-process integration
- **Location**:
  `internal/console/security_integration_test.go`
- **What it proves**:
  the assembled network/process behavior for `NET`, `PAIR`, `SES`, `CSRF`,
  `WKS`, and `LIFE` requirements.
- **Fixtures/data**:
  isolated temp profile/work root, real loopback port, Go cookie jar, deterministic
  test seam for challenge capture where needed.
- **Mocks**:
  real HTTP/profile/workspace/auth stack; injected browser opener and clock only.
- **Contract classification**:
  Internal or accidentally exposed implementation.
- **Compatibility expectation**:
  one same-release browser adapter with process-local state.

### 20. Playwright Pairing and Session Lifecycle Suite

- **Names**:
  - update `embedded shell serves root deep link version theme and assets`
  - `pairing fragment is removed before exchange completes`
  - `paired refresh reuses cookie and bootstraps a fresh tab token`
  - `manual pairing returns secret only through captured terminal channel`
  - `two tabs share session but retain independent tab state`
  - `server-rejected session returns only that browser to unpaired state`
  - `browser storage contains only presentation preferences`
  - `security and cache headers cover browser-visible success and error paths`
- **Type**: E2E
- **Location**:
  `web/e2e/shell.spec.ts`,
  `web/e2e/pairing.spec.ts`,
  `web/e2e/session-lifecycle.spec.ts`,
  `web/e2e/fixtures/consoleProcess.ts`,
  `web/playwright.config.ts`
- **What it proves**:
  `HDR-01`, `WEB-01` through `WEB-03`, `SECRET-01`, and browser-real cookie,
  Origin, history, storage, and multi-tab semantics.
- **Fixtures/data**:
  worker-scoped isolated Console process and in-memory redacted pairing URL.
- **Mocks**:
  no HTTP/auth mocks. Use a deliberately replaced cookie in one browser context
  to exercise the real rejection path while a second valid context remains
  paired. Eight-hour time expiry remains covered by fake-clock Go and React
  tests. No production test endpoint is added.
- **Contract classification**:
  Internal or accidentally exposed implementation.
- **Compatibility expectation**:
  preserve embedded-shell/assets/theme behavior behind the new pairing boundary.

### 21. Vite Development-Origin Suite

- **Names**:
  - preserve `development config binds loopback and proxies only console paths`
  - expand `development config rejects non-loopback Go origin`
  - `development origin allowance requires exact paired authority and origin`
  - `production build contains no development origin allowance`
- **Type**: frontend config/unit plus Go request-policy integration
- **Location**:
  `web/vite.config.test.ts`,
  `internal/browserapi/request_policy_test.go`
- **What it proves**:
  `NET-02`; development convenience cannot broaden production.
- **Fixtures/data**:
  IPv4/IPv6 loopback, wildcard, hostname, HTTPS, credentials, path/query/fragment
  values.
- **Mocks**:
  Vite config environment and Go policy options.
- **Contract classification**:
  Configuration and manifest contracts for the process CLI option; proxy
  assembly remains internal.
- **Compatibility expectation**:
  preserve narrow Vite proxy and add no production fallback.

### 22. Documentation Contract Suite

- **Names**:
  - `TestREADMEConfigurationExampleParses`
  - `TestREADMEDeclaresEveryRuntimeFlag`
  - `TestConfigurationTypesExposeNoSecretYAMLTags`
- **Type**: unit/documentation contract
- **Location**:
  `internal/config/documentation_test.go`
- **What it proves**:
  README and executable schema/CLI stay coherent and secret-free.
- **Fixtures/data**:
  fenced YAML example and documented flag list from
  `bifrost-console/README.md`.
- **Mocks**:
  none.
- **Contract classification**:
  Configuration and manifest contracts.
- **Compatibility expectation**:
  protect the newly documented version-1 contract; no authoring-document test
  is needed because skill-authoring impact is explicitly none.

## Platform Coverage Matrix

| Behavior | Windows x86-64 | Linux x86-64 | macOS Apple Silicon |
| --- | --- | --- | --- |
| Default config/local-state roots | Required automated | Required automated | Required automated |
| Profile/work cross-process locks | Real `LockFileEx` | Real advisory lock | Real advisory lock |
| Owner protection | Current-user owner + protected DACL | UID + exact mode | UID + exact mode |
| Root/final file identity | Volume/file ID and final path | Device/inode | Device/inode |
| Nested symlink rejection | Required where symlink creation is available | Required | Required |
| Junction/reparse rejection | Required real junction + generic reparse | Not applicable | Not applicable |
| Restart cleanup | Required automated | Required automated | Required automated |
| IPv4 live listener | Required | Required | Required |
| IPv6 live listener | Run when host IPv6 loopback is available; formatting test never skips | Same | Same |
| Browser opener | Argument-construction smoke only; no real browser | Same | Same |
| Playwright Chromium flow | Required on primary verification host; PR 15 later owns full release-platform browser matrix | Same expectation if run | Same expectation if run |

Platform-specific tests may skip only when the OS reports that an optional
facility is unavailable, and the skip message must name that facility. Windows
junction/reparse rejection, cross-process locking, permissions/ACL checks, and
the default root contract are release-target requirements and must not be
silently skipped in the supported-target verification record.

## Race, Failure, and Secret-Leak Testing

Run the following suites with `-race`:

- `internal/profile`
- `internal/workspace`
- `internal/lifecycle`
- `internal/browserauth`
- `internal/browserapi`
- `internal/console`

Concurrency barriers must cover:

- two pairing exchanges for one challenge;
- challenge replacement racing exchange;
- session expiry racing authenticated refresh;
- tab expiry racing bootstrap/token rotation;
- two relay admissions for one tab;
- CSRF rotation racing a protected operation;
- health-monitor fatality racing graceful signal cancellation;
- repeated fatal notifications;
- shutdown racing an in-flight browser request.

Secret-leak tests inject distinctive values such as
`PAIRING_SECRET_SENTINEL`, `SESSION_SECRET_SENTINEL`, and
`CSRF_SECRET_SENTINEL`, then assert absence from:

- captured ordinary logs;
- error bodies and messages;
- startup output except the explicitly allowed full pairing URL channel;
- YAML/default config;
- browser-rendered text;
- `localStorage` and unauthorized `sessionStorage` keys;
- query strings and non-fragment URLs;
- Playwright trace titles, annotations, and failure attachments.

Do not use elapsed-time comparisons to claim constant-time validation. Prove
that all secret comparisons flow through one fixed-length decoded comparison
helper and that malformed lengths fail before comparison.

## How to Run

### Baseline Before the First Test Change

From `bifrost-console/`:

```text
go test ./...
```

From `bifrost-console/web/`:

```text
npm test -- --run
```

Both commands passed while this testing plan was created.

### Focused Red/Green Commands

From `bifrost-console/`:

```text
go test ./internal/config
go test ./internal/profile
go test ./internal/workspace
go test ./internal/lifecycle
go test ./internal/webhost
go test ./internal/browserauth
go test ./internal/browserapi
go test ./internal/browseropen
go test ./internal/console
go test ./cmd/bifrost-console
```

From `bifrost-console/web/`:

```text
npm run typecheck
npm test -- --run
npm run test:coverage
```

### Race and Integration

From `bifrost-console/`:

```text
go test -race ./internal/profile ./internal/workspace ./internal/lifecycle ./internal/browserauth ./internal/browserapi ./internal/console
go test -tags=integration ./internal/console ./internal/buildtool
```

On Windows, the same package selection runs without `-race` if the pinned Go
toolchain/race runtime does not support that target; the concurrency tests
remain mandatory. Record that limitation rather than silently omitting the
suite.

### Browser E2E

Build the current executable from `bifrost-console/`:

```text
go run ./internal/buildtool build
```

Then from `bifrost-console/web/`:

```text
npm run test:e2e -- pairing.spec.ts session-lifecycle.spec.ts shell.spec.ts
```

The Playwright fixture must supply isolated `--config`, `--work-dir`,
`--listen 127.0.0.1:0`, and `--no-open-browser`; no E2E command may use the
developer's default profile.

### Full Canonical Verification

From `bifrost-console/`:

```text
gofmt -l .
go vet ./...
go test ./...
go run ./internal/buildtool verify
```

`gofmt -l .` must print no files. Execute the platform package and
cross-process suites on Windows x86-64, Linux x86-64, and macOS Apple Silicon
and attach their command/result summaries to the PR verification record. PR 08
does not introduce a provider-specific CI workflow; PR 15 owns release CI.

## Manual Verification

Perform on at least one Windows, one Linux, and one macOS supported-target
environment for filesystem/lock behavior. Browser interaction may use the
primary development host unless a platform-specific issue appears.

1. Launch with an isolated `--config` and `--work-dir`; verify the documented
   default YAML and exact marker/lock/transient layout and inspect permissions
   or ACLs.
2. Start a second process with the same profile and then with a different
   profile sharing the same work root; verify each conflict is clear and the
   first process remains undisturbed.
3. Point `--work-dir` at an existing unmarked directory containing a sentinel;
   verify refusal and byte-for-byte sentinel preservation.
4. Kill a process with nested files under `transient/`, restart it, and verify
   cleanup completes before the listener accepts a request.
5. Pair through the printed fragment URL; verify the fragment immediately
   disappears from the address/history and replay fails.
6. Inspect cookies, storage, request URLs, request headers, console output, and
   server logs. Confirm only the allowed channels contain their respective
   credentials and that storage contains only presentation state.
7. Open multiple tabs and a separate browser profile; verify shared cookie but
   independent tab/CSRF state, and verify limit errors do not evict existing
   clients.
8. Exercise manual re-pairing and browser-open failure. Confirm the owning
   terminal is the only secret-return channel and the printed URL remains
   usable.
9. Replace the URL host with `localhost`, another loopback literal, malformed
   authority, and a foreign Origin request. Confirm fail-closed responses and no
   redirects.
10. Inspect successful and error response headers for CSP, anti-framing,
    no-referrer, permissions, opener/resource isolation, and cache behavior.
11. Run the Go Console with the exact `--development-origin` and start Vite
    against its matching proxy origin; verify the proxy works, a near-match
    fails, and the production executable has no development allowance without
    the flag.
12. Gracefully terminate the process and verify the listener closes, transient
    cleanup is attempted, and both locks can be acquired immediately by a new
    process.

## Exit Criteria

### Failing-First and Automated Evidence

- [ ] The initial security-header test is committed in a red state before its
  production change and demonstrably fails against PR 07 behavior.
- [ ] Each implementation phase begins with at least one named boundary test
  from the failing-first sequence and records the expected pre-fix failure.
- [ ] All focused Go, React, integration, race, Playwright, and canonical build
  commands pass after implementation.
- [x] Existing PR 07 tests are updated only where the approved internal
  replacement requires it; preserved release, asset, loopback, navigation,
  cache, theme, and shutdown behaviors remain covered.

### Security and Lifecycle

- [x] Every independent browser control has a one-invalid-at-a-time negative
  test, and rejection precedes body reading/business handling.
- [ ] Pairing/session/CSRF concurrency tests pass under the race detector where
  supported.
- [ ] No sentinel secret appears outside its explicitly permitted channel.
- [ ] Header/cache/error/body bounds cover every response class, not only happy
  paths.
- [ ] Graceful and workspace-fatal shutdown both prove ordered closure and
  reverse lock release; the first fatal cause is retained.
- [ ] Request-local browser/auth errors are proven not to terminate the process.

### Filesystem and Platform Safety

- [x] Outside sentinels survive every successful and failed cleanup scenario.
- [ ] Unmarked and wrong-marker roots are rejected without mutation.
- [ ] Unix symlink and Windows junction/reparse cases prove non-following
  behavior using real platform fixtures.
- [ ] Real cross-process profile and work lock exclusion/reacquisition passes on
  Windows, Linux, and macOS.
- [ ] Ownership/permission/ACL and default-root tests pass on all supported
  targets.
- [x] Restart cleanup proves prior transient entries are deleted before
  listening and never adopted.
- [x] Artifact-local versus workspace-fatal classification is covered for
  cleanup success/failure and health-probe success/failure.

### Contract and Compatibility

- [ ] Exact schema-version 1 YAML, CLI precedence, README example, default
  paths, sentinel meanings, and strict rejection are protected.
- [x] Exact marker and cookie serialization/process-lifetime behavior is
  protected.
- [x] No legacy config reader, marker alias, fallback workspace,
  unauthenticated sensitive endpoint, dual browser API, or browser API
  negotiation remains.
- [x] Unknown `/api/console/` paths remain ineligible for SPA fallback while the
  approved versioned routes replace reserved-404-only behavior atomically.
- [x] No Java Application API, Supported SPI, Java/Go fixtures, application
  REST/SSE/problem contracts, or consumed NDJSON tests change.
- [x] No `ai/skill-authoring/` files or coverage table entries change because
  the approved authoring impact is none.

### Manual Completion

- [ ] Manual profile/workspace/lock verification is recorded for Windows
  x86-64, Linux x86-64, and macOS Apple Silicon.
- [ ] Browser pairing, history removal, refresh, multi-tab, re-pairing, storage,
  header, and shutdown checks are complete.
- [ ] Browser-open failure and `--no-open-browser` both leave the printed
  fallback usable.
- [ ] Documentation accurately describes observed first-run, conflict,
  security, and cleanup behavior.

## References

- Implementation plan:
  `ai/thoughts/plans/2026-07-26-bifrost-console-pr-08-local-security-workspace.md`
- Ticket:
  `ai/thoughts/tickets/bifrost-console-pr-08-local-security-workspace.md`
- Research:
  `ai/thoughts/research/2026-07-26-bifrost-console-pr-08-local-security-workspace.md`
- Phase 2 design:
  `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md`
- Testing-plan command:
  `ai/commands/3_testing_plan.md`
