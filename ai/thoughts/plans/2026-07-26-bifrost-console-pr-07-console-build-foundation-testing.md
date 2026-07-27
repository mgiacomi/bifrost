# PR 07 — Console Project and Reproducible Build Foundation Testing Plan

## Change Summary

- Add the independent `bifrost-console/` Go module, exact Go/Node/npm
  declarations, exact frontend dependency graph, and committed npm lockfile.
- Add a React/TypeScript/Vite shell with React Router, Tailwind semantic
  tokens, a React Aria theme selector, Vitest/React Testing Library, and a
  foundation Playwright harness.
- Add deterministic frontend asset generation, a strict
  `bifrost-assets.json` inventory, embedded-asset validation, and a minimal
  loopback SPA host.
- Use the root Maven project version as the only Bifrost release source, inject
  the complete value into browser assets and Go, and reject missing or stale
  version/asset combinations before listening.
- Add a standard-library Go build tool whose mandatory ordering is: exact
  toolchain check, locked install, frontend checks/tests, clean Vite build,
  asset verification, Go tests, then versioned Go compilation.
- Keep Maven independent, retain the existing Java Application API and absence
  of a Supported SPI, and do not alter the Java-to-Go REST/SSE/artifact/NDJSON
  boundary.

## Test Objectives

1. Prove a clean build never compiles from pre-existing or unverified browser
   assets.
2. Prove the root Maven version, asset manifest, rendered shell, linker-injected
   Go version, and `--version` output are exactly equal, including qualifiers.
3. Prove malformed, missing, extra, modified, path-unsafe, or stale assets fail
   before the listener opens.
4. Prove the static host distinguishes browser navigation from asset/API
   requests and applies correct cache/content behavior.
5. Prove exact toolchain/dependency pins and cross-platform subprocess handling
   are enforceable rather than documentary.
6. Prove the minimal frontend shell, routing, theme state, keyboard operation,
   and production deep-link behavior work without adding later product
   workflows.
7. Prove ordinary Maven verification remains independent of Console tooling
   and no Java public/API/SPI or diagnostic-format behavior changes.

## Impacted Areas

- `bifrost-console/go.mod` and `bifrost-console/.node-version`
- `bifrost-console/web/package.json` and `package-lock.json`
- `bifrost-console/web/vite.config.ts` and TypeScript/Vitest/Playwright
  configuration
- `bifrost-console/web/src/app/**`, `src/styles/**`, and frontend tests
- `bifrost-console/internal/release/**`
- `bifrost-console/internal/buildtool/**`
- `bifrost-console/internal/webassets/**`
- `bifrost-console/internal/webhost/**`
- `bifrost-console/cmd/bifrost-console/**`
- Root `.gitignore`, root `README.md`, and `bifrost-console/README.md`
- Existing Maven release and architecture regression tests

## Risk Assessment

### High Risk

- **Build-order bypass**: a failed frontend test, missing manifest, or stale
  asset might still permit Go compilation from an older generated tree.
- **Split version authority**: the POM, Vite build, asset marker, Go linker
  value, and displayed/CLI version might diverge or lose `-SNAPSHOT`.
- **Unsafe cleanup**: a resolved generated directory, symlink, or Windows
  reparse point might escape the Console asset subtree and delete unrelated
  files.
- **Incomplete inventory validation**: a verifier that checks only listed files
  could accept extra files, path aliases, traversal, duplicate entries, or an
  entry document that references an untracked asset.
- **Startup race/bypass**: the executable might bind before embedded version
  and asset validation completes.
- **SPA fallback overreach**: missing JavaScript/CSS or reserved API routes
  might return `index.html`, hiding deployment errors or weakening later route
  isolation.
- **Toolchain drift**: permissive version parsing, npm ranges, or an updated
  lockfile could make nominally identical builds use different tools or graphs.

### Medium Risk

- Incorrect cache headers could retain an old `index.html` or prevent immutable
  caching of content-addressed assets.
- Cross-platform quoting/path behavior could corrupt a qualifier-bearing
  linker value or invoke a shell unexpectedly.
- Vite could expose its development server beyond loopback or proxy arbitrary
  paths/targets.
- Theme persistence, keyboard behavior, route fallback, or version rendering
  could regress in the initial shell.
- Generated outputs could become tracked or leave the worktree dirty.

### Lower Risk / Manual Emphasis

- Visual quality at 200% zoom, forced colors, reduced motion, and representative
  Windows/macOS/Linux desktop rendering.
- HMR responsiveness and understandable behavior when either development
  process stops.
- Operation of the copied executable on a machine/directory without Node.js,
  npm, a JVM, or repository static files.

### Contract and Compatibility Scope

| Surface | Test treatment |
| --- | --- |
| Application API | No changed API. Run the existing architecture and full Maven suites to prove the closed Java API remains unchanged. |
| Supported SPI | No changed SPI. Retain the existing assertion that no SPI package/type exists. |
| Configuration and manifest contracts | Primary protected new surface. Test exact toolchain/package declarations, strict POM version parsing, strict asset manifest decoding, build-command ordering, and no legacy/fallback formats. |
| Persisted or serialized contracts | No independent browser persistence or cross-release browser protocol. Test only current executable/asset atomicity and absence of a service worker; do not create migration/old-manifest tests. |
| Ephemeral diagnostic formats | No impact. Run existing fixture tests through the Maven suite as regression coverage; add no historical trace or Go reader tests in PR 07. |
| Internal or accidentally exposed implementation | Test current build tool, asset package, host, and frontend behavior. Do not freeze package subdivision or create compatibility tests for `bifrost-cli`. |

### Protected and Intentionally Unsupported Paths

- Protect the complete Maven release string, including qualifier, as the sole
  product-version source.
- Protect the new exact build declarations and strict current manifest format
  once introduced by this PR.
- Protect Maven's ability to build/test without invoking Go or Node.
- Do not preserve the deprecated CLI's version, commands, tests, types, or
  filesystem behavior.
- Do not accept an old manifest schema, fallback asset tree, second version
  source, independently deployed SPA, service worker, or browser compatibility
  marker.
- Do not add Java-to-Go protocol fixture assertions beyond existing Maven
  regression coverage because this PR does not consume or change that boundary.

## Existing Test Coverage

- `BifrostReleaseVersionTest.loadsCompleteFilteredMavenReleaseIncludingQualifier`
  proves Java currently loads `0.1.0-SNAPSHOT` with its qualifier
  (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/BifrostReleaseVersionTest.java:7-13`).
- `BifrostPublicSurfaceArchitectureTest` protects the closed Java API,
  classifies technically public internals, and asserts that no Supported SPI
  exists
  (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:23-31`,
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:253-302`).
- Console fixture corpus tests generate into temporary directories and compare
  exact committed inventory/bytes. This is a useful determinism pattern, but
  the fixtures themselves are not modified or consumed by PR 07
  (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ConsoleRestFixtureCorpusTest.java:24-59`,
  `bifrost-console-fixtures/README.md:7-19`).
- `bifrost-cli/main_test.go` demonstrates ordinary standard-library Go test
  style but covers only deprecated CLI behavior and must not be copied as
  Console compatibility coverage.
- There are no existing Console Go, frontend, Vite, Vitest, React Testing
  Library, or Playwright tests. All PR 07 behavior is currently uncovered.

## Bug Reproduction / Failing Test First

PR 07 is greenfield behavior rather than a bug fix, so there is no current
incorrect implementation to reproduce. The first red test will establish the
most important build invariant before pipeline production code is written.

- **Name**: `TestRunPipelineStopsWhenFrontendTestsFail`
- **Type**: Unit
- **Location**:
  `bifrost-console/internal/buildtool/pipeline_test.go`
- **Arrange**:
  - Scaffold the Go module and the `internal/buildtool` test package.
  - Provide a fake structured command runner.
  - Return success for toolchain checks, `npm ci`, and type checking.
  - Return a sentinel failure for `npm run test:coverage`.
  - Record every requested phase and expose sentinel files representing an old
    generated tree and old binary.
- **Act**: Call the planned `runPipeline(verifyMode, dependencies)` seam.
- **Assert**:
  - the sentinel frontend failure is returned;
  - calls stop immediately after coverage;
  - asset cleanup, Vite, manifest generation/verification, Go tests, and
    `go build` are never requested;
  - the pre-existing generated sentinel remains untouched; and
  - no binary is written.
- **Expected failure before implementation**: The test initially fails to
  compile because `runPipeline` and its injected dependencies do not exist.
  After the minimal seam is introduced but before control flow is implemented,
  it fails by observing later phases or an incorrect result.
- **Why this test is first**: It is deterministic, requires no Node/browser or
  real filesystem mutation, and prevents the most dangerous stale-output path
  before broader integration work begins.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: New protected build ordering; no fallback to
  obsolete/pre-existing output.

The next red tests should be
`TestVerifyManifestRejectsVersionMismatch` and
`TestRunRejectsInvalidAssetsBeforeListen`, ensuring the two independent
fail-closed gates exist before adding happy-path hosting.

## Tests to Add or Update

### 1. Release Value Validation

- **Names**:
  - `TestValidateProductVersion`
  - `TestVersionPreservesCompleteQualifier`
- **Type**: Unit
- **Location**:
  `bifrost-console/internal/release/version_test.go`
- **What it proves**:
  - blank, whitespace, `development`, and unresolved placeholder values fail;
  - `0.1.0-SNAPSHOT` and representative prerelease/build qualifiers remain
    exact;
  - validation does not normalize, trim an otherwise valid value, or invent a
    default.
- **Fixtures/data**: Table-driven strings, including `${project.version}`,
  `0.1.0-SNAPSHOT`, `1.2.3-rc.1+build.7`, whitespace, and control characters.
- **Mocks**: None.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: Protect the single exact product release
  value; no alias or fallback version.

### 2. Direct Maven Product-Version Parsing

- **Names**:
  - `TestReadProductVersionReadsOnlyDirectProjectVersion`
  - `TestReadProductVersionRejectsInvalidDocuments`
- **Type**: Unit
- **Location**:
  `bifrost-console/internal/buildtool/productversion_test.go`
- **What it proves**:
  - XML namespaces/comments/formatting are accepted;
  - dependency, plugin, property, and parent versions are ignored;
  - the direct root project version is preserved exactly;
  - missing, blank, duplicate, malformed, and unresolved values fail clearly.
- **Fixtures/data**:
  `bifrost-console/internal/buildtool/testdata/pom/*.xml` with one minimal named
  case for each condition.
- **Mocks**: Temporary filesystem only; no Maven invocation.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: Protect the root POM as the only authority;
  reject alternate/nested sources.

### 3. Exact Toolchain and Dependency Declarations

- **Names**:
  - `TestValidateToolchainVersionsRequiresExactPatches`
  - `TestProjectDeclarationsMatchPinnedToolchains`
  - `TestPackageManifestUsesExactDirectVersions`
- **Type**: Unit/integration
- **Locations**:
  - `bifrost-console/internal/buildtool/toolchains_test.go`
  - `bifrost-console/internal/buildtool/projectdeclarations_test.go`
- **What it proves**:
  - only Go `1.26.5`, Node `24.18.0`, and npm `12.0.1` output forms pass;
  - suffix/noise is parsed deliberately, while wrong major/minor/patch fails;
  - `go.mod`, `.node-version`, `engines`, and `packageManager` agree;
  - every direct dependency/devDependency is the planned exact version with no
    range/tag/workspace protocol;
  - the package is private and has no publish configuration;
  - the lockfile was produced for the declared graph and `npm ls --all` is
    valid.
- **Fixtures/data**: Real project declaration files plus table-driven command
  output strings.
- **Mocks**: Fake command output for unit parsing; real `npm ci`/`npm ls` only
  in the integration command.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: Protect exact pins; dependency upgrades are
  explicit future changes.

### 4. Build Pipeline Ordering and Short-Circuiting

- **Names**:
  - `TestRunPipelineExecutesRequiredOrder`
  - `TestRunPipelineStopsAtEveryFailedPhase`
  - `TestVerifyModeDoesNotBuildBinary`
  - `TestBuildModeInjectsVersionAndUsesTrimpath`
- **Type**: Unit
- **Location**:
  `bifrost-console/internal/buildtool/pipeline_test.go`
- **What it proves**:
  - the exact order is toolchains, `npm ci`, typecheck, coverage, safe cleanup,
    Vite, manifest generation, manifest verification, Go tests, then optional
    Go build;
  - every phase failure prevents every later phase;
  - `verify` never emits a binary;
  - `build` invokes `go build -trimpath` only after all gates pass;
  - linker arguments contain the exact qualifier-bearing POM value as one
    structured argument.
- **Fixtures/data**: Table of each injectable phase and its sentinel error;
  recorded fake command calls.
- **Mocks**: Fake command runner, fake filesystem operations, fake version and
  asset-verification functions. Do not invoke a shell.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: Protect the new canonical build command and
  remove any opportunity for a stale-output fallback.

### 5. Generated-Directory Cleanup Containment

- **Names**:
  - `TestCleanGeneratedAssetsRemovesOnlyValidatedChildren`
  - `TestCleanGeneratedAssetsRejectsEscapes`
  - `TestCleanGeneratedAssetsRejectsSymlinkOrReparseBoundary`
- **Type**: Unit/platform integration
- **Location**:
  `bifrost-console/internal/buildtool/cleanup_test.go`
- **What it proves**:
  - only the resolved
    `bifrost-console/internal/webassets/generated/` contents are cleaned;
  - the known embed placeholder is recreated/preserved;
  - relative traversal, absolute external paths, prefix-confusion siblings,
    symlinks, and Windows reparse points fail before deletion;
  - unrelated sentinel files survive every rejection.
- **Fixtures/data**: Per-test temporary directory tree with inside/outside
  sentinels. Create a symlink/junction only when the current platform and test
  permissions allow it; otherwise explicitly skip that platform case while
  retaining pure path-resolution coverage.
- **Mocks**: Real temporary filesystem for containment; injected platform
  metadata seam for deterministic reparse/symlink unit cases.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current safe cleanup behavior; no protected
  external path or legacy output location.

### 6. Asset Manifest Strictness and Inventory Integrity

- **Names**:
  - `TestVerifyManifestAcceptsCompleteCurrentAssetSet`
  - `TestVerifyManifestRejectsInvalidManifest`
  - `TestVerifyManifestRejectsInventoryMismatch`
  - `TestVerifyManifestRejectsInvalidViteEntryReferences`
- **Type**: Unit
- **Location**:
  `bifrost-console/internal/webassets/verify_test.go`
- **What it proves**:
  - a schema-versioned, sorted, unique, fully hashed current asset set passes;
  - unknown fields, wrong schema/version, blank/unresolved version, duplicate
    or unsorted paths, absolute/traversal/backslash/alias paths, invalid sizes,
    invalid hashes, and oversized manifest input fail;
  - missing, extra, modified, truncated, or length-mismatched files fail;
  - `index.html`, `.vite/manifest.json`, and at least one content-addressed JS
    entry are required;
  - every Vite/HTML referenced production asset belongs to the inventory;
  - `embed-placeholder.txt` is not accepted as production content.
- **Fixtures/data**: Small in-memory `fstest.MapFS` valid baseline plus one
  named mutation per table row. Keep fixture bodies minimal and readable.
- **Mocks**: In-memory filesystem; real SHA-256.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: Protect only the current strict schema; no
  old-schema reader, unknown-field tolerance, or fallback assets.

### 7. Deterministic Manifest Generation

- **Names**:
  - `TestGenerateManifestSortsAndHashesDeterministically`
  - `TestCleanFrontendBuildIsDeterministic`
- **Type**: Unit and real-toolchain integration
- **Locations**:
  - `bifrost-console/internal/buildtool/assets_test.go`
  - `bifrost-console/internal/buildtool/frontend_integration_test.go`
- **What it proves**:
  - filesystem enumeration order and file timestamps do not affect JSON bytes;
  - paths, lengths, and SHA-256 values are stable and sorted;
  - two clean Vite builds from identical source/toolchains produce byte-equal
    `bifrost-assets.json` and identical asset inventory/hashes.
- **Fixtures/data**: Temporary asset trees created in different orders; real
  frontend source for the integration case.
- **Mocks**: Unit test uses temporary files. Integration test uses real npm and
  Vite, is serialized, and writes only beneath a temporary output root exposed
  through an internal test seam.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: Current-build reproducibility, not historical
  asset readability.

### 8. Runtime Asset/Version Gate and CLI Version

- **Names**:
  - `TestRunRejectsInvalidReleaseBeforeListen`
  - `TestRunRejectsInvalidAssetsBeforeListen`
  - `TestRunStartsOnlyAfterAssetValidation`
  - `TestVersionFlagPrintsInjectedProductVersion`
- **Type**: Unit/integration
- **Locations**:
  - `bifrost-console/cmd/bifrost-console/main_test.go`
  - `bifrost-console/internal/webassets/assets_test.go`
- **What it proves**:
  - invalid linker value or embedded manifest/version mismatch returns a
    nonzero error without invoking the injected listener factory;
  - listener construction occurs only after release and asset validation;
  - `--version` prints exactly the linker-injected complete version and exits
    without opening a listener;
  - a production-built executable prints the same value as the direct root POM
    and embedded manifest.
- **Fixtures/data**: Valid and mutated in-memory embedded files; production
  binary from the canonical build.
- **Mocks**: Injected listener factory/call recorder for unit tests; real
  subprocess for the compiled-binary assertion.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: Protect exact release identity and fail
  closed; no development/default version.

### 9. Static SPA Handler Boundaries

- **Names**:
  - `TestStaticHandlerServesEntryAndDeepLinks`
  - `TestStaticHandlerServesContentAddressedAssets`
  - `TestStaticHandlerDoesNotFallbackAssetOrReservedPaths`
  - `TestStaticHandlerMethodAndHeaderPolicy`
- **Type**: Unit/integration
- **Location**:
  `bifrost-console/internal/webhost/static_test.go`
- **What it proves**:
  - `/` and valid navigation paths return `index.html`;
  - hashed JS/CSS and other real files return exact bytes/content types;
  - missing file-like paths, `/api/console/` paths, traversal/encoded traversal,
    and malformed paths never return the SPA entry;
  - entry responses use `Cache-Control: no-store`;
  - content-addressed assets use immutable cache policy;
  - `X-Content-Type-Options: nosniff`, explicit content lengths/types, `GET`,
    `HEAD`, unsupported methods, and not-found responses behave consistently.
- **Fixtures/data**: Small validated in-memory asset FS with `index.html`, one
  JS asset, one CSS asset, and manifest.
- **Mocks**: `httptest.ResponseRecorder`/`httptest.NewServer`; no external
  network.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current atomic browser/host behavior; PR 08
  may wrap it with security middleware without preserving internal handler
  structure.

### 10. Loopback Startup and Graceful Shutdown

- **Names**:
  - `TestHostRejectsNonLoopbackAddress`
  - `TestHostServesOnlyAfterValidation`
  - `TestHostShutsDownOnContextCancellation`
- **Type**: Unit/integration
- **Location**:
  `bifrost-console/internal/webhost/host_test.go`
- **What it proves**:
  - wildcard, LAN, hostname-ambiguous, and public bind addresses fail;
  - explicit IPv4 and supported IPv6 loopback forms are accepted deliberately;
  - no socket is opened until validation completes;
  - cancellation closes the listener and stops serving without leaked
    goroutines.
- **Fixtures/data**: Loopback ephemeral port; invalid address table.
- **Mocks**: Injected `net.Listen` recorder for ordering/rejection tests; real
  loopback listener for lifecycle test.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: PR 07 foundation only; PR 08 owns final
  listener authority/security behavior.

### 11. Frontend Shell, Routing, Version, and Theme

- **Names**:
  - `renders the console shell and exact build version`
  - `renders a safe not-found route without raw HTML`
  - `changes theme by keyboard and stores it only in sessionStorage`
  - `falls back to system theme when stored state is absent or invalid`
- **Type**: Component
- **Locations**:
  - `bifrost-console/web/src/app/App.test.tsx`
  - `bifrost-console/web/src/app/ThemeSelect.test.tsx`
- **What it proves**:
  - the shell title and exact injected version render as text;
  - root/deep/not-found routing is deterministic;
  - the React Aria selector has an accessible name and full keyboard path;
  - light/dark/system changes update the document theme and only
    `sessionStorage`;
  - invalid stored values fail safely to system;
  - no `localStorage`, service-worker registration, or `dangerouslySetInnerHTML`
    behavior is introduced.
- **Fixtures/data**: Memory router routes, typed build metadata, stubbed
  `matchMedia`, isolated session storage.
- **Mocks**: jsdom browser APIs only; use user-event rather than calling event
  handlers directly.
- **Contract classification**: Internal or accidentally exposed
  implementation.
- **Compatibility expectation**: Current embedded frontend behavior; no
  independently versioned/persisted browser contract.

### 12. Vite Production and Development Configuration

- **Names**:
  - `production config requires exact Bifrost version`
  - `development config binds loopback and proxies only console paths`
  - `development config rejects non-loopback Go origin`
  - `production config contains no proxy or service worker`
- **Type**: Unit/integration
- **Location**:
  `bifrost-console/web/vite.config.test.ts`
- **What it proves**:
  - production configuration fails without a resolved
    `VITE_BIFROST_VERSION`;
  - Vite uses `baseline-widely-available`, `build.manifest=true`, and the exact
    generated directory;
  - development binds an explicit loopback address and strict port;
  - only `/api/console/`, including its event subtree, proxies to the validated
    loopback Go origin;
  - `/_bifrost/observability/`, arbitrary paths, wildcard/public proxy targets,
    and production proxy configuration are absent.
- **Fixtures/data**: Pure exported configuration factory inputs for production,
  valid development, and invalid origin cases.
- **Mocks**: Environment/config inputs only; no live proxy server in unit tests.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: Protect the narrow current development
  configuration; production never falls back to Vite.

### 13. Production Browser Smoke Test

- **Name**: `embedded shell serves root, deep link, version, theme, and assets`
- **Type**: End-to-end
- **Location**:
  `bifrost-console/web/e2e/shell.spec.ts`
- **What it proves**:
  - the production executable starts on loopback and serves the embedded shell;
  - root and a direct deep-link navigation both load;
  - the visible version equals the expected root POM value;
  - keyboard theme selection survives a page reload within the tab/session;
  - entry and hashed-asset response cache headers differ correctly;
  - a missing hashed asset and `/api/console/missing` do not return HTML;
  - no request is made to the Java observability namespace and no service
    worker controls the page.
- **Fixtures/data**: Binary created by `go run ./internal/buildtool build`,
  Playwright-managed free loopback port, expected product version read by test
  setup.
- **Mocks**: No application target. Use the real compiled host and browser;
  intercept only to assert unexpected external requests.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: Protect atomic executable/browser behavior;
  not a browser protocol compatibility suite.

### 14. Clean Checkout, Runtime Independence, and Worktree Hygiene

- **Names**:
  - `clean checkout verify/build acceptance`
  - `copied executable runtime-independence acceptance`
- **Type**: Integration/manual acceptance
- **Location**: Canonical build commands plus temporary runtime directory; do
  not add a shell-specific permanent script.
- **What it proves**:
  - deleting ignored Console outputs followed by canonical build yields exactly
    one current-platform executable;
  - the build does not invoke Maven or the deprecated CLI;
  - the copied executable serves without repository files, Node.js/npm on
    `PATH`, a JVM on `PATH`, or a separate static directory;
  - two identical clean builds yield identical asset manifests/inventories;
  - ignored dependencies, coverage, Playwright output, assets, and binaries do
    not create new tracked/untracked worktree entries.
- **Fixtures/data**: Clean source checkout or clean temporary worktree and a
  separate empty runtime directory.
- **Mocks**: Sanitized subprocess environment for runtime independence; retain
  only operating-system variables required to launch the binary.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: Protect the standalone runtime and
  independent Maven/Console build boundary.

### 15. Existing Java Release Regression

- **Name**:
  `BifrostReleaseVersionTest.loadsCompleteFilteredMavenReleaseIncludingQualifier`
- **Type**: Regression/unit
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/BifrostReleaseVersionTest.java`
- **What it proves**: Java still filters and loads the same exact direct POM
  version, including `-SNAPSHOT`, while Console adopts it as a consumer.
- **Fixtures/data**: Existing filtered Maven release resource.
- **Mocks**: None.
- **Contract classification**: Configuration and manifest contracts.
- **Compatibility expectation**: Protect the single repository release value;
  Console does not alter or replace Java release metadata.

### 16. Existing Java Application API Regression

- **Names**: Existing `BifrostPublicSurfaceArchitectureTest` API allowlist and
  signature-boundary tests.
- **Type**: Regression/architecture
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java`
- **What it proves**: The closed Java Application API and its signature
  boundaries remain exactly unchanged after adding the independent Console
  project.
- **Fixtures/data**: Compiled production Java classes.
- **Mocks**: None.
- **Contract classification**: Application API.
- **Compatibility expectation**: Protected path remains green; PR 07 adds no
  Java type, constructor, method, bean, or leaked signature type.

### 17. Existing Supported SPI Absence Regression

- **Name**:
  `BifrostPublicSurfaceArchitectureTest.noSupportedSpiPackageOrTypeExists`
- **Type**: Regression/architecture
- **Location**:
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java`
- **What it proves**: Adding Console does not create or imply a Java Supported
  SPI.
- **Fixtures/data**: Compiled production Java packages.
- **Mocks**: None.
- **Contract classification**: Supported SPI.
- **Compatibility expectation**: Protected absence remains green; no
  extension-point compatibility suite is created for Go internals.

### 18. Existing Diagnostic Fixture Regression

- **Names**: Existing REST, SSE, artifact, and NDJSON fixture corpus tests
  through the normal Maven suite.
- **Type**: Regression/integration
- **Locations**:
  - `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ConsoleRestFixtureCorpusTest.java`
  - `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ConsoleSseFixtureCorpusTest.java`
  - `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ConsoleArtifactFixtureCorpusTest.java`
  - existing trace fixture corpus test
- **What it proves**: Java-to-Go protocol and current-run trace fixture bytes
  remain current and untouched while PR 07 adds no reader or protocol client.
- **Fixtures/data**: Existing `bifrost-console-fixtures/` corpus; regeneration
  mode is not enabled.
- **Mocks**: Existing test-owned producers only.
- **Contract classification**: Ephemeral diagnostic formats.
- **Compatibility expectation**: Current-checkout coherence remains green; no
  historical-readability promise or new Go consumer is introduced.

## Test Implementation Order

1. Scaffold the Go module and add
   `TestRunPipelineStopsWhenFrontendTestsFail`; capture its expected red result.
2. Add release/POM/toolchain unit tests and the minimum production seams needed
   to make them green.
3. Add strict asset verifier tests, beginning with stale version, missing
   entry, extra file, and modified hash; then implement generation/verification.
4. Add pipeline ordering and cleanup-containment tables; implement the build
   orchestrator without real-tool integration first.
5. Add static-handler and startup-gate tests; implement embedding/hosting only
   after failure cases are green.
6. Add frontend component/configuration tests; implement the shell, semantic
   tokens, theme selector, and Vite settings.
7. Generate the real locked dependency graph and run real-toolchain
   deterministic build verification.
8. Add the production Playwright smoke test and copied-binary acceptance test.
9. Run the complete Console and Maven regression matrix, then perform manual
   cross-platform/accessibility/hot-reload checks.

## How to Run

### Prerequisites

- Go `1.26.5`
- Node.js `24.18.0`
- npm `12.0.1`
- Java 21+ and the repository Maven wrapper for Java regression coverage
- Playwright Chromium installed by the pinned package:
  `npx playwright install chromium`

No application target, credential, trace fixture regeneration flag, database,
or persistent profile is required. The canonical build tool supplies
`VITE_BIFROST_VERSION` internally from the root POM; developers must not set a
different product-version override.

### Focused Red/Green Commands

From `bifrost-console/`:

```text
go test ./internal/buildtool -run TestRunPipelineStopsWhenFrontendTestsFail
go test ./internal/release ./internal/buildtool ./internal/webassets ./internal/webhost ./cmd/bifrost-console
go vet ./...
```

From `bifrost-console/web/`:

```text
npm ci
npm run typecheck
npm run test:coverage
```

### Real-Toolchain Integration

From `bifrost-console/`:

```text
go test -tags=integration ./internal/buildtool -run TestCleanFrontendBuildIsDeterministic
go run ./internal/buildtool verify
go run ./internal/buildtool build
```

`verify` is the authoritative full build/test order without a retained binary.
`build` repeats the protected sequence and creates the current-platform binary.
Neither command may trust a previously generated production asset.

### Browser Smoke

From `bifrost-console/web/`, after the canonical binary build:

```text
npx playwright install chromium
npm run test:e2e
```

### Java/Repository Regression

From the repository root on Windows:

```text
.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostReleaseVersionTest,BifrostPublicSurfaceArchitectureTest test
.\mvnw.cmd test
```

On Linux/macOS, use the equivalent `./mvnw` commands.

### Optional Diagnostic Commands

When the platform has the required C toolchain:

```text
go test -race ./...
```

Use this as additional lifecycle/goroutine evidence, not as a substitute for
the mandatory commands.

## Manual Verification

### Production Artifact

1. Start from a clean temporary worktree or remove only documented ignored
   Console outputs.
2. Run `go run ./internal/buildtool build`.
3. Run the binary with `--version` and compare the output byte-for-byte with
   the direct root POM version and `bifrost-assets.json`.
4. Copy the binary to an empty temporary directory.
5. Launch it with Node, npm, Java, and repository paths removed from `PATH`.
6. Verify root/deep routes, missing asset/API behavior, cache headers, and
   graceful shutdown.

### Development Hot Reload

1. Start the documented Go loopback host.
2. Start Vite with the documented loopback Go origin.
3. Confirm Vite itself listens only on loopback.
4. Edit shell text/style and confirm HMR updates without rebuilding Go.
5. Confirm only `/api/console/` is proxy-eligible and
   `/_bifrost/observability/` is not proxied.
6. Stop Vite, then Go, and confirm each failure is visible without silently
   substituting embedded assets.

### Accessibility and Responsive Foundation

1. Navigate the shell and theme selector using only the keyboard.
2. Confirm visible/unobscured focus and no focus theft on load/navigation.
3. Verify light, dark, follow-system, forced-colors, and reduced-motion modes.
4. Verify 200% browser zoom without whole-page horizontal scrolling.
5. Confirm the shell contains no target, trace, execution, or speculative
   product workflow.

### Representative Platforms

- Windows x86-64: mandatory for the current Windows development environment,
  including reparse/path separator and `.exe` behavior.
- Linux x86-64: run the complete canonical build, integration, and browser
  smoke commands when available.
- macOS Apple Silicon: run the complete canonical build, integration, and
  browser smoke commands when available.

PR 15 turns all three platforms into release packaging gates. PR 07 must still
record which representative platforms were actually exercised and must not
claim an untested platform result.

## Exit Criteria

- [x] The first red build-order test is recorded failing before production
  pipeline implementation and passes afterward.
- [x] Release, POM parsing, toolchain declaration, build ordering, cleanup
  containment, manifest generation/verification, runtime startup, host, and
  frontend component tests all pass.
- [x] Every pipeline phase has a failure case proving no later phase executes.
- [x] Missing, stale-version, modified, truncated, extra, path-unsafe,
  duplicate, unknown-field, and unresolved-placeholder asset cases fail
  closed.
- [x] Invalid release/assets are rejected before any listener factory is
  invoked.
- [x] Two clean real-toolchain frontend builds produce identical asset
  manifests and asset hashes.
- [x] `go run ./internal/buildtool verify` passes from a clean source state.
- [x] `go run ./internal/buildtool build` produces one current-platform
  executable only after frontend and Go verification pass.
- [x] The compiled binary, visible browser shell, embedded manifest, and direct
  root POM all carry the exact same complete version.
- [x] The Playwright foundation smoke test passes for root/deep routes, cache
  behavior, theme persistence, missing asset/API paths, and no service worker.
- [x] The copied executable serves without Node.js, npm, a JVM, repository
  files, a database, or separate static assets.
- [x] The development server/proxy is loopback-only, forwards only
  `/api/console/`, and is absent from production configuration.
- [x] Generated dependencies, coverage, Playwright artifacts, browser assets,
  and binaries create no unexpected worktree entries.
- [x] Existing `BifrostReleaseVersionTest`,
  `BifrostPublicSurfaceArchitectureTest`, fixture tests, and the full Maven
  suite pass without invoking Console tooling.
- [x] The Java Application API and Supported SPI classifications remain
  unchanged; no compatibility shim, CLI bridge, old manifest reader, browser
  compatibility marker, or dual asset behavior is present.
- [x] No Java-to-Go REST/SSE/acquisition/problem/NDJSON semantics or fixtures
  changed; no historical diagnostic-format promise was added.
- [x] No test or documentation evidence is required for skill-authoring
  guidance because the approved implementation plan classifies that impact as
  `No impact`.
- [ ] Keyboard, theme/preference, 200% zoom, HMR, runtime independence, and
  representative-platform manual checks are completed and recorded.

## References

- Implementation plan:
  `ai/thoughts/plans/2026-07-26-bifrost-console-pr-07-console-build-foundation.md`
- Ticket:
  `ai/thoughts/tickets/bifrost-console-pr-07-console-build-foundation.md`
- Research:
  `ai/thoughts/research/2026-07-26-bifrost-console-pr-07-build-foundation.md`
- Phase 2 design:
  `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md`
- Roadmap:
  `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md`
- Contract policy:
  `ai/thoughts/framework-feature-design-lens.md`
