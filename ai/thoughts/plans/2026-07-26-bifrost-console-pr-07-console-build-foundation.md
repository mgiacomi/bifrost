# PR 07 — Console Project and Reproducible Build Foundation Implementation Plan

## Overview

Create the independent `bifrost-console/` Go module and its embedded
React/TypeScript/Vite application. The change establishes one reproducible,
versioned executable, a minimal browser shell, frontend and Go test harnesses,
fresh-asset verification, and a loopback-only hot-reload workflow without
joining the Maven reactor or inheriting the deprecated CLI.

The implementation deliberately creates only the build and browser-host
foundation needed by later Console pull requests. PR 08 will replace the
foundation listener defaults with profile-owned configuration, workspace
establishment, pairing, browser sessions, CSRF, and complete local-browser
security. PR 15 will own release CI, platform packages, dependency notices,
checksums, and full workflow-level Playwright verification.

## Current State Analysis

- The root Maven reactor contains only `bifrost-spring-boot-starter` and
  `bifrost-sample`; there is no Console module or polyglot root build
  (`pom.xml:39-42`).
- The Maven parent is the current product-version authority and presently
  declares the complete value `0.1.0-SNAPSHOT` (`pom.xml:7-10`).
- Java filters that exact value into
  `META-INF/bifrost-release.properties` as
  `consoleCompatibilityVersion=${project.version}`, and the focused test
  protects qualifier retention
  (`bifrost-spring-boot-starter/src/main/resources-filtered/META-INF/bifrost-release.properties:1`,
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/BifrostReleaseVersionTest.java:7-13`).
- There is no `bifrost-console/`, Node declaration, frontend manifest,
  committed frontend lockfile, Console asset tree, Console build command, or
  tracked CI configuration.
- `bifrost-cli` is an independent, deprecated proof of concept. Its types,
  discovery, commands, architecture, and hard-coded version are not a
  compatibility target
  (`bifrost-cli/README.md:1-3`,
  `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:122-126`).
- The settled design requires a new top-level Go module containing
  `cmd/bifrost-console`, internal packages, a dedicated embedded-asset package,
  and `web/` with a committed npm lockfile
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:128-143`).
- Production must install the locked graph, run frontend tests, clean and build
  assets, verify the entry document and manifest, run Go tests, then compile
  with those fresh assets
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:157-168`).
- The existing `.gitignore` covers Maven output but has no Console,
  `node_modules`, frontend coverage, generated asset, or Console binary entries
  (`.gitignore:1-38`).

## Desired End State

A clean checkout with the pinned Go, Node.js, and npm toolchains can run one
Console build command from `bifrost-console/`. That command:

1. verifies the exact toolchain versions;
2. reads the direct Maven parent `<project>/<version>` as the only product
   version source;
3. installs the committed npm graph with `npm ci`;
4. type-checks and tests the frontend with coverage;
5. removes every prior generated production asset except the tracked
   non-production embed placeholder;
6. builds Vite assets with content-addressed names and the complete product
   version;
7. writes and verifies a deterministic asset-integrity manifest;
8. runs all Go tests against that freshly generated asset set; and
9. builds a Go executable with the same complete version injected through
   linker flags.

The resulting executable validates the embedded asset manifest/version before
opening its minimal loopback listener, serves the SPA and client-side route
fallback, applies correct entry-document versus hashed-asset cache behavior,
and exposes the exact version through `--version`. Missing, modified, extra, or
differently versioned assets fail the build and fail runtime initialization.

The development workflow runs Vite on an explicit loopback address with hot
reload and proxies only the reserved Console browser API/event path prefixes to
the loopback Go host. No Vite development behavior or proxy is present in the
production executable.

### Key Discoveries

- Browser assets and the Go host are one atomically built/distributed
  component, so they do not need an independent browser protocol version
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:143-147`).
- Java and Console retain independent build entry points but use the same
  release string and tag
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:145-149`).
- The production entry document must not retain an older asset set, while
  content-addressed assets may be immutable; no service worker is allowed
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:190-194`).
- PR 08 directly consumes the executable/browser-host boundary and owns its
  final listener, profile, workspace, and browser-security lifecycle
  (`ai/thoughts/tickets/bifrost-console-pr-08-local-security-workspace.md:8-24`).
- PRs 09-18 rely on transport-neutral Go services remaining below browser and
  MCP adapters, so PR 07 must establish `internal/` without placing product
  semantics in the static-asset handler
  (`ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:89-120`).

## Resolved Research Decisions

### Exact dependency and toolchain baseline

Use the stable versions available during planning on 2026-07-26. Every direct
npm dependency is declared without `^`, `~`, tags, or ranges; transitive
versions are fixed by `package-lock.json`.

| Selection | Exact version | Role/license note |
| --- | ---: | --- |
| Go | `1.26.5` | Current stable patch; standard library only in PR 07 |
| Node.js | `24.18.0` | Current Active LTS patch |
| npm | `12.0.1` | Exact build-time package manager |
| React / React DOM | `19.2.8` | MIT |
| React Router | `8.3.0` | MIT; use the `react-router` browser APIs |
| React Aria Components | `1.19.0` | Apache-2.0 |
| Tailwind CSS / `@tailwindcss/vite` | `4.3.3` | MIT |
| TypeScript | `7.0.2` | Apache-2.0 |
| Vite | `8.1.5` | MIT |
| `@vitejs/plugin-react` | `6.0.4` | MIT |
| Vitest / `@vitest/coverage-v8` | `4.1.10` | MIT |
| jsdom | `29.1.1` | MIT |
| React Testing Library | `16.3.2` | MIT |
| Testing Library DOM | `10.4.1` | MIT |
| Testing Library jest-dom | `7.0.0` | MIT |
| Testing Library user-event | `14.6.1` | MIT |
| Playwright test | `1.62.0` | Apache-2.0 |
| React type declarations | `19.2.17` | MIT |
| React DOM type declarations | `19.2.3` | MIT |

Go records `go 1.26.0` plus `toolchain go1.26.5`. PR 07 introduces no
third-party Go dependency merely to create a `go.sum`; `go.sum` is committed
when an actual module dependency first requires it. The frontend manifest
records exact `engines.node`, `engines.npm`, and `packageManager`, and
`bifrost-console/.node-version` records `24.18.0`.

Version evidence:

- [Go 1.26.5 official downloads](https://go.dev/dl/)
- [Node.js 24.18.0 LTS release](https://nodejs.org/en/blog/release/v24.18.0)
- [npm registry package metadata](https://www.npmjs.com/package/npm)
- [Vite registry package metadata](https://www.npmjs.com/package/vite)

### Build-command surface

Use a small standard-library-only Go command at
`bifrost-console/internal/buildtool`. Invoke it with:

```text
go run ./internal/buildtool verify
go run ./internal/buildtool build
```

`verify` performs the complete clean verification sequence without retaining a
runtime binary. `build` performs the same sequence and writes the current
platform binary beneath ignored `bifrost-console/build/`. This keeps the
workflow cross-platform, versioned with the Go project, and independent from
Maven, shell-specific scripts, globally installed task runners, and the npm
application manifest.

### Generated assets and stale marker

Vite writes to
`bifrost-console/internal/webassets/generated/` with
`build.manifest=true`. A tracked `embed-placeholder.txt` makes the Go asset
package compilable before the first frontend build but is never a valid
production asset.

After Vite completes, the build tool writes
`bifrost-assets.json` containing:

- schema version;
- exact complete Bifrost product version;
- entry document path;
- Vite manifest path and SHA-256;
- a sorted inventory of every production asset path, byte length, and SHA-256.

Verification requires `index.html`, the Vite manifest, at least one
content-addressed JavaScript asset referenced by the entry, no unexpected
non-placeholder files, exact hashes/lengths, and exact product-version equality.
The runtime asset package repeats this validation against the embedded files
before the listener opens.

### Product-version supply

The build tool parses only the direct version child of the root Maven
`<project>`, preserving qualifiers such as `-SNAPSHOT`. It supplies that value
to Vite as `VITE_BIFROST_VERSION`, records it in `bifrost-assets.json`, and
injects it into an otherwise invalid `internal/release.productVersion` variable
with Go `-ldflags -X`. Any optional caller-supplied expected version must equal
the parsed POM version; it cannot override it and create a second authority.

### CI, licensing, and packaging boundary

PR 07 provides deterministic commands suitable for any CI provider but does not
select a provider or add release automation where none exists. It reviews the
direct build/runtime dependency licenses above and does not add a dependency
with an unreviewed or incompatible license. PR 15 owns the repository release
workflow, third-party notice inventory, inclusion of the applicable Bifrost
license/runtime README, checksums, and the three supported platform packages
(`ai/thoughts/tickets/bifrost-console-pr-15-diagnostic-workflows.md:18-23`,
`ai/thoughts/tickets/bifrost-console-pr-15-diagnostic-workflows.md:46-49`).

## What We're NOT Doing

- Adding Console to `<modules>` in `pom.xml` or making ordinary Maven builds
  require Go, Node.js, or npm.
- Reusing or preserving `bifrost-cli` code, types, filesystem discovery,
  commands, architecture, version, or distribution.
- Adding target configuration, application credentials, target compatibility
  negotiation, pairing, browser sessions, CSRF, workspace ownership, trace
  storage, or finalized security middleware; these begin in PRs 08-09.
- Adding trace parsing, REST/SSE application clients, execution views, trace
  pages, product workflows, or MCP routes.
- Adding a separate SPA deployment, service worker, browser API version,
  runtime Node dependency, database, installer, updater, container image, npm
  package, or deprecated CLI package.
- Adding release-provider CI, cross-platform archives, license bundles,
  checksums, or publishing; PR 15 completes those concerns.
- Adding full Playwright workflow/accessibility/security coverage. PR 07 adds
  the configured harness and one foundation smoke scenario; PR 15 adds the
  approved workflow suite and platform release evidence.

## Skill-Authoring Documentation Impact

**Impact**: No impact

- **Rationale**: This work creates a separately running Console build and
  embedded browser shell. It does not change skill manifest syntax or
  validation, mappings, execution/planning semantics, evidence, input/output
  contracts, capability visibility/RBAC, attachments, model selection, limits,
  traces, debugging semantics, or skill-tree testing guidance.
- **Documents to update**: None.
- **Supporting evidence**:
  `ai/thoughts/tickets/bifrost-console-pr-07-console-build-foundation.md:8-39`
  limits the change to Console scaffolding/build behavior, while
  `ai/skill-authoring/README.md:28-66` routes only author-facing framework
  topics.
- **Coverage table update**: Not required; no authoring topic, task boundary,
  behavior, or confidence level changes.
- **LLM-first usability**: Not applicable.

## Contract and Compatibility Impact

| Surface | Classification and supporting evidence | Planned compatibility treatment |
| --- | --- | --- |
| Application API | No Java Application API change. The current closed allowlist remains protected by `BifrostPublicSurfaceArchitectureTest` (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:23-31`). | Preserve unchanged; no Java source or signature changes. |
| Supported SPI | No impact. The repository currently asserts that no supported SPI package exists (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:296-302`). | Preserve unchanged; introduce no Java or Go extension point. |
| Configuration and manifest contracts | New build-time contracts are the exact Go/Node/npm declarations, `package.json`, lockfile, Vite configuration, asset-integrity manifest, and build command. They are developer/release configuration, not skill manifests. | Establish them atomically and test fail-closed parsing. Future changes are intentional dependency/build-contract updates. No fallback names or dual formats. |
| Persisted or serialized contracts | No independently persistent browser data or cross-release browser protocol. `bifrost-assets.json` is build metadata embedded into the same executable as its only consumer. | Keep browser/Go atomic; no service worker, migration reader, browser compatibility marker, or old-manifest fallback. |
| Ephemeral diagnostic formats | No trace or other diagnostic format is read or changed. The existing current-release fixture corpus remains untouched (`bifrost-console-fixtures/README.md:1-3`). | No compatibility treatment required. |
| Internal or accidentally exposed implementation | New Go internal packages, build tool, asset layout, static handler, and browser source are repository/product internals. The minimal local browser-host behavior is a foundation that PR 08 will harden atomically. | Keep Go product semantics under `internal/`; expose no reusable library API. Later PRs update internal consumers atomically. |

- **Evidence of supported contracts**: The approved PR 07 ticket and settled
  Phase 2 build/release design establish the new Console build contract. No
  current application or SPI consumer exists.
- **Intended breaks**: None. This creates a new project.
- **In-repository consumers to update**: Root `.gitignore`; the root project
  inventory in `README.md`; new Console Go packages/tests; new frontend
  source/tests/configuration; and build documentation. Maven modules, Java
  fixtures, and the deprecated CLI remain unchanged.
- **Public-surface delta**: One end-user executable entry point and its
  `--version` flag are added. No Java public type, constructor, method, Spring
  bean, SPI, or supported Go library API is added. The temporary minimal host
  accepts no product data and exposes no application-facing protocol.
- **Shim decision**: **No shim.** There is no protected predecessor; the
  deprecated CLI is explicitly not a compatibility target, and all new
  browser/Go files ship atomically.
- **Java-to-Go boundary coordination**: **Not required.** The build reads the
  Maven parent release string but does not change Java REST, SSE, acquisition,
  problem, NDJSON, or fixture semantics. PR 09 will consume the exact
  `consoleCompatibilityVersion` at runtime.

## Implementation Approach

Establish the project from the outside inward:

1. pin tools and declare the independent module/package graphs;
2. build a tested frontend shell with routing, semantic tokens, and selective
   accessible-component wiring;
3. implement versioned asset generation, deterministic integrity metadata,
   embedding, and a minimal static host;
4. compose those pieces through the standard-library Go build tool and document
   the production and hot-reload workflows.

The executable entry point stays thin. Release identity belongs in
`internal/release`, asset validation/serving belongs in
`internal/webassets`, and build-only orchestration belongs in
`internal/buildtool`. This leaves `internal/` available for the
transport-neutral services and thin browser/MCP adapters introduced by later
PRs without turning the PR 07 HTTP handler into a domain layer.

## Phase 1: Pin Toolchains and Scaffold the Independent Projects

### Overview

Create the new module boundaries, exact toolchain declarations, dependency
graphs, ignore rules, and developer-facing project documentation.

### Changes Required

#### 1. Go module and entry-point skeleton

**Files**:

- `bifrost-console/go.mod`
- `bifrost-console/cmd/bifrost-console/main.go`
- `bifrost-console/internal/release/version.go`
- `bifrost-console/internal/release/version_test.go`

**Changes**:

- Declare module `github.com/mgiacomi/bifrost/bifrost-console`, Go language
  `1.26.0`, and toolchain `go1.26.5`.
- Use only the Go standard library in PR 07; do not copy the CLI or add a
  convenience dependency.
- Add an invalid-by-default product-version slot that production builds must
  populate with linker flags.
- Keep `main` limited to option parsing, version output, release/asset
  validation, minimal host construction, signal-aware shutdown, and exit-code
  handling.
- Test blank/development/unresolved release rejection and exact
  qualifier-bearing version preservation.

#### 2. Exact Node/npm and frontend dependency declarations

**Files**:

- `bifrost-console/.node-version`
- `bifrost-console/web/package.json`
- `bifrost-console/web/package-lock.json`

**Changes**:

- Record Node `24.18.0`, npm `12.0.1`, the exact direct dependency table above,
  and private/non-publishable package metadata.
- Use exact dependency strings and a committed lockfile produced by that npm
  version.
- Add scripts for `dev`, `typecheck`, `test`, `test:coverage`, `test:e2e`, and
  `build:web`; do not make an npm script the authoritative whole-product build.
- Add no general state manager, component/theme library, chart library,
  service-worker package, or independent frontend publishing configuration.

#### 3. Repository ignore and project inventory

**Files**:

- `.gitignore`
- `README.md`
- `bifrost-console/README.md`

**Changes**:

- Ignore Console `node_modules/`, coverage, Playwright output, generated
  production assets except the tracked embed placeholder, and local binaries.
- Add Console to the root project inventory as an independent build, explicitly
  separate from Maven and the deprecated CLI.
- Document prerequisites, exact versions, canonical verify/build commands,
  generated-file policy, and the boundary between PR 07 and later Console work.

### Success Criteria

#### Automated Verification

- [x] `go env GOTOOLCHAIN` and `go version` resolve the declared
  `go1.26.5` toolchain.
- [x] `npm ci` succeeds from the committed
  `bifrost-console/web/package-lock.json` under Node `24.18.0` and npm `12.0.1`.
- [x] `npm ls --all` reports a valid locked dependency graph.
- [x] A script/test rejects non-exact direct dependency declarations and
  mismatched Node/npm declarations.
- [x] `go test ./internal/release` passes from `bifrost-console/`.
- [x] `.\mvnw.cmd test` still succeeds without invoking Console tooling.

#### Manual Verification

- [x] The root and Console READMEs make the Maven/Console/CLI boundaries and
  prerequisites unambiguous.
- [x] The lockfile diff is explainable entirely by the declared frontend
  foundation dependencies.
- [x] Direct dependency licenses match the reviewed MIT/Apache-2.0 set; npm
  remains a build tool and no new dependency is included without review.

---

## Phase 2: Create the Frontend Shell and Test Harnesses

### Overview

Build the smallest accessible, routed Bifrost shell that proves React,
TypeScript, Vite, React Router, Tailwind semantic tokens, React Aria, and the
test stack work together without introducing product pages.

### Changes Required

#### 1. Vite and TypeScript configuration

**Files**:

- `bifrost-console/web/index.html`
- `bifrost-console/web/vite.config.ts`
- `bifrost-console/web/tsconfig.json`
- `bifrost-console/web/tsconfig.app.json`
- `bifrost-console/web/tsconfig.node.json`
- `bifrost-console/web/src/vite-env.d.ts`

**Changes**:

- Configure React and Tailwind plugins, Vite's pinned release-time
  `baseline-widely-available` production target, content-addressed assets, and
  `build.manifest=true`.
- Emit to `../internal/webassets/generated` only during an explicit production
  build.
- Inject `VITE_BIFROST_VERSION` into typed build metadata; fail production
  builds when it is missing or unresolved.
- Keep the development server on explicit loopback with a strict port.
- Proxy exactly the reserved `/api/console/` prefix, including its future
  `/api/console/events/` subtree, to an explicitly configured loopback Go
  origin; reject non-loopback proxy targets. Do not proxy
  `/_bifrost/observability/` or any arbitrary path.

#### 2. Routed shell and semantic-token foundation

**Files**:

- `bifrost-console/web/src/main.tsx`
- `bifrost-console/web/src/app/App.tsx`
- `bifrost-console/web/src/app/routes.tsx`
- `bifrost-console/web/src/app/ThemeSelect.tsx`
- `bifrost-console/web/src/styles/index.css`
- `bifrost-console/web/src/styles/tokens.css`

**Changes**:

- Add a browser router with a shell/root route and a safe not-found route that
  proves client-side fallback without creating product screens.
- Render the Bifrost Console name and complete injected version as ordinary
  text.
- Establish a small Bifrost semantic-token set for background, surface, text,
  muted text, border, accent, focus, success, warning, and danger, with
  light/dark/follow-system behavior and scope-bound `sessionStorage`
  persistence.
- Add reset/global focus/reduced-motion/forced-colors rules and Tailwind-owned
  layout/spacing/typography. Do not add a Tailwind component library.
- Use React Aria Components for the light/dark/follow-system selector so the
  foundation exercises a real accessible interaction while retaining
  Bifrost-owned presentation; do not build speculative menus, tables, or
  domain widgets.

#### 3. Component and browser-foundation tests

**Files**:

- `bifrost-console/web/vitest.config.ts`
- `bifrost-console/web/playwright.config.ts`
- `bifrost-console/web/src/test/setup.ts`
- `bifrost-console/web/src/app/App.test.tsx`
- `bifrost-console/web/e2e/shell.spec.ts`

**Changes**:

- Configure Vitest, jsdom, React Testing Library, jest-dom, user-event, and V8
  coverage.
- Test shell rendering, exact version display, routing/not-found behavior,
  keyboard-visible interaction, and absence of unsafe raw HTML use.
- Configure Playwright for the pinned Chromium used by local/CI callers and add
  one production-shell/deep-link smoke scenario. Leave approved diagnostic
  workflows and comprehensive accessibility/security cases to PR 15.

### Success Criteria

#### Automated Verification

- [x] Frontend type checking passes: `npm run typecheck`.
- [x] Component tests and coverage pass: `npm run test:coverage`.
- [x] The production frontend build rejects a missing
  `VITE_BIFROST_VERSION`.
- [x] Vite emits `index.html`, `.vite/manifest.json`, and content-addressed
  JavaScript/CSS names.
- [x] The foundation Playwright smoke test passes: `npm run test:e2e`.
- [x] Tests assert a deep browser route renders the SPA shell rather than a
  server 404 once served through the Go host.

#### Manual Verification

- [x] The shell is usable by keyboard with visible focus.
- [ ] Light/dark system preference, forced colors, and reduced motion produce a
  readable foundation without relying on color alone.
- [ ] Browser zoom to 200 percent remains usable without whole-page horizontal
  scrolling.
- [x] No target, trace, execution, or speculative product page is present.

---

## Phase 3: Establish the Versioned Embedded-Asset Contract and Minimal Host

### Overview

Generate, verify, embed, and serve exactly one freshly built frontend asset set
whose version must match the Go executable.

### Changes Required

#### 1. Embedded asset package and deterministic manifest model

**Files**:

- `bifrost-console/internal/webassets/assets.go`
- `bifrost-console/internal/webassets/manifest.go`
- `bifrost-console/internal/webassets/verify.go`
- `bifrost-console/internal/webassets/generated/embed-placeholder.txt`
- `bifrost-console/internal/webassets/*_test.go`

**Changes**:

- Embed the generated directory while treating the placeholder as
  non-production content.
- Define a strict, versioned internal JSON manifest schema with unknown-field
  rejection, normalized slash-separated relative paths, sorted unique
  inventory, size bounds, and SHA-256 hashes.
- Validate exact version equality, `index.html`, the Vite manifest, entry
  references, content-addressed filenames, complete inventory equality, and
  file hashes/lengths.
- Reject missing, blank, unresolved, absolute, traversal, duplicate, extra,
  stale-version, and hash-mismatched cases before serving.
- Do not add a legacy manifest reader, fallback asset tree, or embedded
  development build.

#### 2. Minimal production SPA handler

**Files**:

- `bifrost-console/internal/webhost/host.go`
- `bifrost-console/internal/webhost/static.go`
- `bifrost-console/internal/webhost/*_test.go`
- `bifrost-console/cmd/bifrost-console/main.go`

**Changes**:

- Serve the validated embedded filesystem from a minimal Go HTTP host that
  binds only to an explicit loopback address in PR 07.
- Serve real content-addressed assets with immutable cache headers and
  `X-Content-Type-Options: nosniff`.
- Serve `index.html` with `Cache-Control: no-store` for `/` and valid
  client-side navigation paths; do not convert missing asset-like requests or
  reserved API paths into the entry document.
- Set explicit content types, support `HEAD`, and reject unsupported methods.
- Keep the route seam small enough for PR 08 to add profile resolution,
  authority/origin checks, pairing/session/CSRF middleware, CSP, and verified
  workspace establishment before opening the listener.
- Add graceful interrupt/termination shutdown without introducing a second
  lifecycle or background service.

#### 3. Runtime version/asset coherence

**Files**:

- `bifrost-console/internal/release/version.go`
- `bifrost-console/internal/webassets/verify.go`
- `bifrost-console/cmd/bifrost-console/main.go`

**Changes**:

- Validate the linker-injected product version before starting.
- Require it to equal the embedded manifest product version exactly, including
  prerelease/build qualifiers.
- Print the same value for `bifrost-console --version`.
- Fail startup clearly and before binding when the embedded set is missing,
  malformed, stale, or inconsistent.

### Success Criteria

#### Automated Verification

- [x] Go tests pass against freshly generated assets: `go test ./...`.
- [x] Table-driven tests cover missing entry/manifest, stale version, modified
  bytes, extra files, path traversal, duplicate inventory, unresolved
  placeholders, and non-content-addressed production assets.
- [x] Runtime tests prove no listener is opened when release or asset
  validation fails.
- [x] Static-handler tests cover `/`, a deep link, hashed assets, missing
  asset-like paths, reserved API paths, cache headers, content types, `HEAD`,
  and unsupported methods.
- [x] The compiled binary reports the exact POM value, currently
  `0.1.0-SNAPSHOT`, through `--version`.
- [x] The generated asset inventory is deterministic across two consecutive
  clean builds from the same source/toolchains after excluding timestamps and
  output binary metadata.

#### Manual Verification

- [x] Running the executable opens only the documented loopback listener and
  serves the embedded shell without Node.js at runtime.
- [x] Refreshing a nested shell route loads the application.
- [x] A missing hashed asset returns a real not-found response rather than the
  entry document.
- [x] Replacing the asset marker or executable version with a mismatched value
  yields a clear startup failure.

---

## Phase 4: Compose the Reproducible Build and Hot-Reload Workflow

### Overview

Add one cross-platform orchestration command that owns toolchain checks,
version propagation, clean asset production, verification ordering, Go tests,
and final compilation, then document a separate development hot-reload path.

### Changes Required

#### 1. Standard-library build orchestrator

**Files**:

- `bifrost-console/internal/buildtool/main.go`
- `bifrost-console/internal/buildtool/toolchains.go`
- `bifrost-console/internal/buildtool/productversion.go`
- `bifrost-console/internal/buildtool/frontend.go`
- `bifrost-console/internal/buildtool/assets.go`
- `bifrost-console/internal/buildtool/gobuild.go`
- `bifrost-console/internal/buildtool/*_test.go`

**Changes**:

- Resolve repository/module/web/output paths from the command source rather
  than the caller's arbitrary working directory.
- Check exact Go, Node, and npm versions with actionable failures before
  mutation.
- Parse the root Maven project version with `encoding/xml`, require exactly one
  direct nonblank resolved value, and preserve it byte-for-byte.
- Run `npm ci`, type checking, and coverage tests before changing the prior
  generated asset tree.
- Clean only the resolved generated directory after verifying it remains
  beneath `bifrost-console/internal/webassets/`; preserve/recreate only the
  known placeholder.
- Run Vite with the exact version, generate the deterministic asset manifest,
  verify it, run `go test ./...`, and only then invoke `go build -trimpath`.
- Inject the same version with a fully qualified `-ldflags -X` target and place
  the binary under ignored `build/`.
- Abort on the first failure, never compile after a failed frontend test or
  asset check, and never fall back to prior output.
- Keep subprocess arguments as structured argument arrays and avoid shell
  interpretation, so qualifiers and paths behave consistently across Windows,
  Linux, and macOS.

#### 2. Build-tool tests

**Files**:

- `bifrost-console/internal/buildtool/*_test.go`
- `bifrost-console/internal/buildtool/testdata/**`

**Changes**:

- Unit-test direct-POM-version parsing, including comments, namespace,
  inherited-looking nested versions, qualifiers, missing/duplicate/unresolved
  values, and malformed XML.
- Test exact toolchain parsing and mismatch diagnostics.
- Test generated-directory containment and refusal to clean symlink/reparse or
  out-of-module targets as supported by the platform.
- Use fake subprocess executors to prove phase ordering, early exit, argument
  preservation, no stale-asset fallback, and no binary build after failure.
- Add an integration test that tampers with a generated asset after Vite and
  proves verification fails before Go compilation.

#### 3. Development workflow

**Files**:

- `bifrost-console/web/vite.config.ts`
- `bifrost-console/web/package.json`
- `bifrost-console/README.md`

**Changes**:

- Document two-terminal development: run the minimal Go loopback host, then
  start Vite using its fixed loopback host/port and explicit Go proxy origin.
- Keep Vite as the browser origin, with HMR assets handled by Vite and only the
  reserved `/api/console/` prefix forwarded to Go.
- Validate the proxy destination as loopback and keep all proxy configuration
  out of the production build and embedded files.
- State that PR 08 will add the sole configured development-origin allowance
  together with final Host, Origin, pairing, session, CSRF, and listener rules;
  PR 07 does not claim that the minimal host is the completed security model.

### Success Criteria

#### Automated Verification

- [x] Complete verification succeeds from `bifrost-console/`:
  `go run ./internal/buildtool verify`.
- [x] Complete current-platform build succeeds:
  `go run ./internal/buildtool build`.
- [x] A clean checkout with no generated production assets produces one
  executable beneath `bifrost-console/build/`.
- [x] Frontend test failure prevents asset cleanup/build and Go compilation.
- [x] Missing, stale, modified, or extra generated assets prevent Go
  compilation.
- [x] The build command fails on any Go/Node/npm patch mismatch.
- [x] `git status --short` after two successful clean builds shows no generated
  asset, coverage, dependency, or binary output.
- [x] Root Java verification remains independent and passes:
  `.\mvnw.cmd test` on Windows or `./mvnw test` on Unix.

#### Manual Verification

- [ ] The two-terminal Vite workflow binds only to loopback and HMR updates the
  shell without rebuilding the Go executable.
- [ ] Browser network inspection shows only reserved Console API/event paths
  are eligible for proxying; application observability paths are not.
- [ ] Stopping either development process produces an understandable local
  development failure without silently using embedded production assets.
- [x] Copying the built executable to a directory without the repository,
  Node.js, npm, a JVM, or static files still serves the embedded shell.

---

## Testing Strategy

Create the dedicated PR 07 testing plan with `ai/commands/3_testing_plan.md`
before implementation. It should make the build-order and stale-asset tests
fail first, identify platform-specific containment cases, and define the exact
exit evidence for clean-checkout and runtime-independence verification.

### Unit Tests

- Release-value validation and exact qualifier preservation.
- Root-POM direct-version parsing and duplicate/unresolved rejection.
- Toolchain version parsing and mismatch messages.
- Strict asset-manifest decoding, normalized paths, inventory equality, sizes,
  hashes, entry references, and exact version equality.
- Static-handler routing, SPA fallback boundaries, cache headers, content
  types, methods, and asset-not-found behavior.
- Build phase ordering, subprocess argument handling, cleanup containment, and
  failure short-circuiting.
- Frontend shell rendering, version display, routing, keyboard interaction,
  and semantic-token behavior.

### Integration Tests

- Locked frontend install, type check, coverage, and Vite build.
- Fresh asset generation followed by Go tests and compilation.
- Tampered/missing/stale/extra asset rejection before compilation and before
  listener startup.
- Compiled binary `--version` versus POM and embedded manifest.
- Production server root, nested route, and content-addressed asset requests.
- Minimal Playwright production-shell smoke test.

### Manual Testing Steps

1. Remove all ignored Console outputs and run the canonical build from a clean
   checkout.
2. Run the binary outside the repository and verify the shell, nested-route
   refresh, version, and browser caching behavior.
3. Run Go plus Vite in development mode and verify loopback binding, HMR, and
   narrow proxy eligibility.
4. Change one generated byte/version marker and verify both build-time and
   runtime fail-closed behavior.
5. Repeat the build on representative Windows, Linux, and macOS environments
   when available; PR 15 turns those checks into release gates.

## Performance Considerations

- Keep the shell and direct dependency set intentionally small; establish a
  recorded compressed/uncompressed asset-size baseline but do not add an
  arbitrary release gate before product pages exist.
- Hash files by streaming rather than loading the complete asset tree into
  memory.
- Use `embed.FS` without copying the whole tree at startup; validate files
  sequentially and retain only parsed manifest metadata.
- Do not add runtime compression middleware in PR 07 unless measurement proves
  it is needed; PR 08 security/middleware composition should not inherit an
  accidental compression policy.
- `npm ci` and coverage dominate build time by design and must remain before
  production asset generation so a failed test cannot produce trusted output.

## Migration Notes

There is no existing Console installation, build, persisted data, or supported
CLI contract to migrate. The new project is added atomically. No compatibility
shim, CLI bridge, old asset reader, or dual build path is required.

Future PRs must preserve these foundation decisions unless an explicit design
change is approved:

- PR 08 wraps/replaces the minimal host startup with verified profile,
  workspace, loopback authority, pairing, browser session, CSRF, and security
  middleware while retaining the embedded-asset/version contract.
- PR 09 uses the same complete release identity for the Java target handshake.
- PRs 10-14 add browser/domain behavior below the established internal
  boundary and rebuild browser plus Go atomically.
- PR 15 adds provider-specific release CI, full Playwright workflows,
  cross-platform package assembly, license/runtime documentation, and
  checksums.
- PR 16 mounts MCP on the PR 08/09 listener and shared services; it does not
  create a second executable or listener.

## References

- Original ticket:
  `ai/thoughts/tickets/bifrost-console-pr-07-console-build-foundation.md`
- Research:
  `ai/thoughts/research/2026-07-26-bifrost-console-pr-07-build-foundation.md`
- Phase 2 design:
  `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md`
- PR roadmap:
  `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md`
- Framework compatibility policy:
  `ai/thoughts/framework-feature-design-lens.md`
- Future security/workspace consumer:
  `ai/thoughts/tickets/bifrost-console-pr-08-local-security-workspace.md`
- Future target/version consumer:
  `ai/thoughts/tickets/bifrost-console-pr-09-target-context.md`
- Future release hardening:
  `ai/thoughts/tickets/bifrost-console-pr-15-diagnostic-workflows.md`
- Future shared-listener MCP consumer:
  `ai/thoughts/tickets/bifrost-console-pr-16-mcp-foundation.md`
- Existing Java-to-Go fixture ownership:
  `bifrost-console-fixtures/README.md`
