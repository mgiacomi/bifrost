---
date: 2026-07-26 16:36:23 PDT
researcher: Codex
git_commit: d22ee6e8f79da75280984290d7d7ad27f2b9d0d4
branch: main
repository: bifrost
topic: "PR 07 — Console Project and Reproducible Build Foundation"
tags: [research, codebase, bifrost-console, go, react, vite, build, versioning]
status: complete
last_updated: 2026-07-26
last_updated_by: Codex
---

# Research: PR 07 — Console Project and Reproducible Build Foundation

**Date**: 2026-07-26 16:36:23 PDT  
**Researcher**: Codex  
**Git Commit**: d22ee6e8f79da75280984290d7d7ad27f2b9d0d4  
**Branch**: main  
**Repository**: bifrost

## Research Question

Research the current repository state needed to plan
`bifrost-console-pr-07-console-build-foundation.md`, including release/version
conventions, CI composition, toolchain and frontend baselines, dependency
policy, licenses, generated-asset handling, packaging targets, and the ways
future Console PRs use this foundation.

## Summary

PR 07 has no existing Console implementation to extend. At this commit there is
no `bifrost-console/` directory, root Go workspace, frontend `package.json` or
lockfile, Node version declaration, `.github/` workflow directory, or tracked
root `LICENSE`/`NOTICE` file. The repository consists of a Maven parent with two
Java modules, a separately located deprecated Go CLI proof of concept, and the
already-established `bifrost-console-fixtures/` Java-to-Go boundary corpus
(`pom.xml:39-42`, `README.md:38-42`, `bifrost-cli/README.md:3`).

The live release convention is the Maven parent version. The current complete
product release string is `0.1.0-SNAPSHOT` (`pom.xml:7-10`). Maven filters that
value into `META-INF/bifrost-release.properties`, and
`BifrostReleaseVersion.load()` requires exactly one resource and one nonblank,
resolved `consoleCompatibilityVersion` declaration
(`bifrost-spring-boot-starter/src/main/resources-filtered/META-INF/bifrost-release.properties:1`,
`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/BifrostReleaseVersion.java:11-48`).
The filtered value is executable behavior: the focused test expects the full
qualifier-bearing string `0.1.0-SNAPSHOT`
(`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/BifrostReleaseVersionTest.java:7-13`).

The settled Phase 2 design supplies the Console-specific baseline that is not
yet present in live code. It places an independent Go module at
`bifrost-console/`, with `cmd/bifrost-console`, `internal`, and a
React/TypeScript/Vite `web` application; production assets live inside a
dedicated Go asset package and are embedded into one executable
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:128-147`). It defines
React Router, Tailwind over Bifrost semantic tokens, selective React Aria, and
Vitest/React Testing Library/Playwright as the frontend and verification
baseline (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:73-99`).

The same design defines the reproducible build order: install the locked
frontend graph, run frontend tests, clean and rebuild production assets, verify
the entry document and content-addressed manifest, run Go tests, and compile the
versioned executable only from those fresh assets
(`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:157-168`). The
repository currently has no script, task runner, workflow, or Console files
implementing that order.

## Detailed Findings

### 1. Current repository and project boundaries

- The root Maven reactor contains only `bifrost-spring-boot-starter` and
  `bifrost-sample`; Console is not a Maven module (`pom.xml:39-42`).
- The root README describes three current projects: the two Maven projects and
  `bifrost-cli` (`README.md:38-42`).
- `bifrost-cli` is explicitly documented as a deprecated, unsupported
  proof-of-concept foundation (`bifrost-cli/README.md:3`).
- The CLI is its own Go module, `github.com/mgiacomi/bifrost-cli`, and currently
  declares `go 1.26.1` (`bifrost-cli/go.mod:1-5`). Its dependencies are all
  recorded in `go.mod`/`go.sum`; all requirements in the current module are
  marked indirect (`bifrost-cli/go.mod:5-28`).
- The CLI's user-visible version is a hard-coded `Bifrost CLI v0.1.0`, separate
  from Maven's current `0.1.0-SNAPSHOT`
  (`bifrost-cli/main.go:1298-1301`). Its README and Phase 2 design classify it as
  neither a compatibility target nor an architectural base for Console
  (`bifrost-cli/README.md:3`,
  `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:122-126`).
- The settled Console placement is a new top-level `bifrost-console/` module,
  with `go.mod`, `go.sum`, `cmd/bifrost-console/`, `internal/`, and
  `web/package.json` plus `web/package-lock.json`
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:128-143`).

### 2. Product version and compatibility source

- The Maven parent owns the current product version, `0.1.0-SNAPSHOT`
  (`pom.xml:7-10`). Both Java child POMs inherit the same parent version
  (`bifrost-spring-boot-starter/pom.xml:7-12`,
  `bifrost-sample/pom.xml:7-12`).
- `bifrost-spring-boot-starter` includes an ordinary resources directory and a
  separately filtered resources directory
  (`bifrost-spring-boot-starter/pom.xml:123-131`).
- The filtered resource declares
  `consoleCompatibilityVersion=${project.version}`
  (`bifrost-spring-boot-starter/src/main/resources-filtered/META-INF/bifrost-release.properties:1`).
- `BifrostReleaseVersion` locates
  `META-INF/bifrost-release.properties`, requires exactly one classpath copy,
  counts exactly one `consoleCompatibilityVersion` declaration, loads it as a
  Java property, and rejects a missing, blank, or still-placeholder-containing
  value
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/BifrostReleaseVersion.java:11-48`).
- The current unit test demonstrates that the qualifier is retained, expecting
  `0.1.0-SNAPSHOT`
  (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/BifrostReleaseVersionTest.java:7-13`).
- The architecture test classifies `BifrostReleaseVersion` as technically
  public only for framework-owned release metadata collaboration, rather than
  as Application API or Supported SPI
  (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:41-47`).
- The roadmap states that Java and Go use the exact complete Bifrost release
  string as `consoleCompatibilityVersion`, with no independent trace version
  (`ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:139-147`).
- The Phase 2 design states that Java artifacts and the Go executable are one
  coordinated release unit, built and published from the same tag, while Maven
  and Console builds remain independently invocable
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:145-149`).
- No live Console version declaration, Go linker injection, generated Go
  version source, or Console asset-version manifest exists at this commit.

### 3. Existing build and dependency conventions

#### Java and Maven

- Java compilation targets release 21 (`pom.xml:43-46`).
- The build enforces Java `[21,)` and Maven `[3.9.0,)`; these are minimum ranges,
  not exact toolchain pins (`pom.xml:120-134`).
- The Maven wrapper script declares wrapper version `3.3.4` and downloads exact
  Maven distribution `3.9.11`
  (`.mvn/wrapper/maven-wrapper.properties:1-3`).
- The parent centralizes direct version properties for Spring Boot `3.5.11`,
  Spring AI `1.1.6`, MockWebServer `4.12.0`, ArchUnit `1.4.2`, compiler plugin
  `3.13.0`, Surefire `3.5.2`, and Enforcer `3.5.0`
  (`pom.xml:47-57`).
- Spring Boot and Spring AI dependencies are aligned through imported BOMs;
  MockWebServer and ArchUnit are managed individually
  (`pom.xml:60-86`).
- Child POMs generally declare managed dependencies without versions. The
  sample's dependency on the starter uses `${project.version}`
  (`bifrost-sample/pom.xml:31-35`).

#### Go

- The only current Go precedent is the deprecated CLI module. It records a Go
  directive, an exact module graph in `go.mod`, and checksums in `go.sum`
  (`bifrost-cli/go.mod:1-28`, `bifrost-cli/go.sum:1-46`).
- The CLI does not use `go.work`, a task runner, release linker flags, or the
  Maven product version. Phase 2 explicitly says Console does not preserve the
  CLI's types, filesystem discovery, architecture, commands, or behavior
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:122-126`).

#### Node and frontend

- There is no tracked `package.json`, `package-lock.json`, `.nvmrc`,
  `.node-version`, or other Node/package-manager declaration.
- There is no current React, TypeScript, Vite, React Router, Tailwind, React
  Aria, Vitest, React Testing Library, or Playwright dependency.
- The design records how those future dependencies are controlled: one exact
  stable Go patch, one exact Active LTS Node patch, an exact npm version, a Go
  module/toolchain declaration, Node version declaration, `packageManager` and
  engine declarations, exact frontend versions, and a committed lockfile
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:151-155`).
- The design chooses Vite's pinned release-time Baseline Widely Available
  browser target, without an initial legacy transform/polyfill layer
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:87-89`).
- The repository does not currently record which exact Go, Node, npm, or
  frontend release PR 07 will select. The phase document describes those as
  release-time selections rather than already-set repository facts.

### 4. CI and build composition

- There is no `.github/` directory or another tracked CI configuration in the
  current checkout.
- Root `.gitignore` ignores Maven outputs, archives, IDE metadata, logs, and
  temporary files, but contains no Console, Node, Vite, or generated-web-asset
  entries (`.gitignore:1-38`).
- Ordinary root Maven operation does not include the deprecated Go module and
  cannot include the not-yet-created Console because the reactor lists only the
  two Java modules (`pom.xml:39-42`).
- The Phase 2 design keeps ordinary Java development and `mvn test` independent
  of Go and Node unless the Console build is explicitly requested
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:147-149`).
- The designed production sequence is:
  1. install the locked frontend dependency graph;
  2. run Vitest and React Testing Library coverage;
  3. clean the previous generated production assets and run Vite;
  4. verify the entry document and content-addressed manifest;
  5. run Go tests; and
  6. compile a versioned executable from the freshly generated assets
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:157-166`).
- Playwright workflow/browser integration tests belong in repository CI before
  release packaging. The design also states that the release path never trusts
  pre-existing generated assets and fails for missing, stale, or
  differently-versioned assets
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:168`).

### 5. Existing generated-content precedent

- The repository already has a deterministic generated-fixture convention for
  `bifrost-console-fixtures/`. The fixture README classifies it as the
  current-release Java-to-Go semantic contract and the traces as an Ephemeral
  diagnostic format (`bifrost-console-fixtures/README.md:1-3`).
- Normal fixture tests generate into temporary directories and compare the
  complete inventory with committed fixtures
  (`bifrost-console-fixtures/README.md:7-11`).
- Intentional fixture regeneration is activated by
  `-Dbifrost.console.fixtures.regenerate=true`, and the documented check is to
  regenerate twice and require no second diff
  (`bifrost-console-fixtures/README.md:13-19`).
- The trace, REST, and SSE fixture tests all have temporary generated
  directories and explicit regeneration branches
  (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ConsoleTraceFixtureCorpusTest.java:63-75`,
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ConsoleRestFixtureCorpusTest.java:31-46`,
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/observability/web/ConsoleSseFixtureCorpusTest.java:23-46`).
- REST and SSE protocol fixtures are Java-produced deterministic bodies/frames;
  the artifact fixture references an existing NDJSON body rather than copying
  it (`bifrost-console-fixtures/README.md:21-29`).
- Future Go consumers must first check the exact compatibility value in the
  instance-status fixture; PR 09 performs that rejection before other protocol
  use (`bifrost-console-fixtures/README.md:31-34`).
- This fixture corpus is protocol input to later Console work, not an existing
  web asset tree or Vite asset manifest.

### 6. Frontend and embedded-asset baseline from settled design

- The application is a React/TypeScript SPA built by Vite; React Router owns
  browser routing (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:73-79`).
- Tailwind owns layout, spacing, typography, responsive behavior, and
  interaction states over a small Bifrost semantic-token system. Authored CSS
  remains for resets, global theme/accessibility rules, and specialized
  visualizations. No Tailwind component/theme library is part of the initial
  baseline (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:73-76`).
- React Aria is selective for interaction-heavy accessible primitives.
  Frontend state begins with React composition, context, and reducers rather
  than a general global-state library
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:83-87`).
- Verification uses Vitest, React Testing Library, and Playwright
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:99`).
- Production assets are embedded into the Go executable; browser and Go are one
  atomically built/distributed component with no independent browser API
  compatibility version (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:143-147`).
- Production static assets use content-addressed names. The entry document is
  revalidated or not stored, and there is no service worker or other
  independently persistent offline cache
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:190-194`).
- Development Vite binds only to loopback, is the browser development origin,
  and proxies only the Console browser API and live-event paths to Go. The
  development proxy and allowances are excluded from production
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:190-194`).

### 7. Licensing and package shape

- The Maven project metadata declares Mozilla Public License 2.0 with repository
  distribution (`pom.xml:16-22`).
- No tracked root `LICENSE` or `NOTICE` file exists at this commit.
- The current repository has no frontend or new Console dependency license
  inventory and no release-package assembly.
- The settled initial targets are Windows x86-64, Linux x86-64, and macOS Apple
  Silicon (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:170-178`).
- Each target package is one executable with the applicable license, a short
  runtime README, and published checksums. The initial release excludes an
  installer, OS package-manager integration, updater, container image, separate
  browser deployment, npm package, and the deprecated CLI
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:178-180`).
- Runtime operation is designed not to require a JVM, Node/frontend toolchain,
  separate static server, shared target filesystem, or database
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:182-188`).

### 8. How later PRs consume PR 07

- The roadmap makes PR 07 the first Phase 2 change and allows it after PR 01
  settles current-release trace direction
  (`ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:81-93`).
- PR 08 directly depends on PR 07 and adds profile/workspace ownership,
  loopback serving, pairing, browser sessions, CSRF, browser security headers,
  and host/origin validation
  (`ai/thoughts/tickets/bifrost-console-pr-08-local-security-workspace.md:3-24`).
- PR 09 depends on PR 08 (and Phase 1 PR 06) and adds the target client,
  in-memory application credential, exact `consoleCompatibilityVersion`
  handshake, target scopes, cancellation, status, domain errors, and target
  setup UI (`ai/thoughts/tickets/bifrost-console-pr-09-target-context.md:3-27`).
- PRs 10–14 successively add operational views, live execution, centralized
  artifacts, trace analysis services, and trace exploration on the same Go/UI
  foundation
  (`ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:115-120`).
- PR 15 supplies full Playwright workflow/accessibility/security verification
  and clean packaging for the three supported platforms
  (`ai/thoughts/tickets/bifrost-console-pr-15-diagnostic-workflows.md:10-23`).
- PR 16 mounts MCP on the existing loopback listener, so it consumes the host
  and listener established through PRs 07–09 rather than introducing an
  independent executable or listener
  (`ai/thoughts/tickets/bifrost-console-pr-16-mcp-foundation.md:10-27`).
- PR 17 reuses PRs 09–11, and PR 18 reuses PRs 12–13; the browser and MCP
  adapters therefore converge on services housed under the PR 07 `internal/`
  boundary (`ai/thoughts/tickets/bifrost-console-pr-17-mcp-runtime-inspection.md:3-5`,
  `ai/thoughts/tickets/bifrost-console-pr-18-mcp-trace-inspection.md:3-5`).

## Contract Classification

Using the repository's six compatibility categories
(`ai/thoughts/framework-feature-design-lens.md:17-37`):

- **Application API:** PR 07 does not currently change the closed seven-type
  Java Application API. The current allowlist is enforced in
  `BifrostPublicSurfaceArchitectureTest`
  (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:23-31`).
- **Supported SPI:** no current supported Bifrost SPI exists; the architecture
  test explicitly checks that no SPI package is present
  (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:296-302`).
- **Configuration and manifest contracts:** PR 07's future Go/Node version
  declarations, package manifest, and development configuration are not yet
  present. Existing `bifrost.*` Java configuration is not in this ticket's
  stated scope.
- **Persisted or serialized contracts:** the phase design gives the embedded
  browser no independently versioned browser protocol or independently
  deployed asset contract. Browser and Go ship atomically
  (`ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:145-147`).
- **Ephemeral diagnostic formats:** the committed trace corpus is explicitly
  current-release-only. It is already the future Go semantic input but PR 07
  does not parse it (`bifrost-console-fixtures/README.md:1-3`).
- **Internal or accidentally exposed implementation:** the new Go internal
  packages, asset package, build scripts, generated asset layout, and
  browser-to-Go implementation are initially repository/product internals.
  `BifrostReleaseVersion` is already classified as technically public internal
  Java collaboration, not supported API
  (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:41-47`).

The protected cross-component consumer visible today is the future Go Console's
exact release-string check against the Java instance-status surface and its
Java-produced executable fixtures. PR 07 establishes the Console-side release
identity and atomic browser/Go asset unit; PR 09 performs target compatibility
and PRs 11–13 consume the REST/SSE/artifact/NDJSON fixtures
(`bifrost-console-fixtures/README.md:21-34`). PR 07 does not itself change those
Java protocol semantics.

## Architecture Documentation

The repository is currently a multi-build-system source tree rather than one
aggregated polyglot build:

```text
root Maven reactor
  ├─ bifrost-spring-boot-starter
  └─ bifrost-sample

independent Go module
  └─ bifrost-cli (deprecated proof of concept)

cross-language fixture corpus
  └─ bifrost-console-fixtures (generated/verified by Java; consumed later by Go)

planned independent Go module
  └─ bifrost-console
       ├─ cmd/bifrost-console
       ├─ internal
       ├─ embedded-asset package
       └─ web (React/TypeScript/Vite)
```

The planned production artifact boundary is one Go executable containing one
fresh browser asset set. Java remains a separate runtime component and Maven
artifact, but the Java adapter and Console share one release string and release
tag. Node/npm are build inputs only. The Console build is explicitly invoked
rather than joining the Maven reactor, while tagged release verification spans
Java, Go, frontend, and the Java-produced cross-language fixtures.

## Verification Performed

- `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostReleaseVersionTest test`
  passed on 2026-07-26: 1 test, 0 failures, 0 errors.
- `go test ./...` passed in `bifrost-cli` on 2026-07-26.
- `git status --short` was empty before document creation; the generated Maven
  `target/` output is ignored by the repository.

These checks verify the existing release-resource path and current legacy Go
module only. There is no Console build to execute at this commit.

## Code References

- `pom.xml:7-22` — product version and MPL-2.0 Maven metadata.
- `pom.xml:39-57` — Maven modules, Java release, dependency and plugin versions.
- `pom.xml:60-86` — BOM-based dependency management.
- `pom.xml:120-134` — Java and Maven minimum-version enforcement.
- `.mvn/wrapper/maven-wrapper.properties:1-3` — wrapper and Maven distribution.
- `bifrost-spring-boot-starter/pom.xml:123-131` — filtered release resource.
- `bifrost-spring-boot-starter/src/main/resources-filtered/META-INF/bifrost-release.properties:1`
  — product-version placeholder.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/observability/BifrostReleaseVersion.java:11-48`
  — release metadata validation/loading.
- `bifrost-cli/go.mod:1-28` — only existing Go toolchain/dependency declaration.
- `bifrost-cli/main.go:1298-1301` — legacy hard-coded CLI version.
- `bifrost-console-fixtures/README.md:1-34` — current-release fixture ownership,
  deterministic regeneration, and future Go consumers.
- `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:73-99` — frontend and
  test baseline.
- `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md:128-194` — project
  layout, release coupling, reproducible build, packages, and development proxy.
- `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md:78-120`
  — PR ordering and Phase 2 dependency chain.

## Historical Context (from ai/thoughts/)

- `ai/thoughts/tickets/bifrost-console-pr-07-console-build-foundation.md` defines
  the requested scaffold, pinning, asset verification, version injection, and
  hot-reload scope while excluding target and trace functionality.
- `ai/thoughts/phases/bifrost_console_phase_2_ui_console.md` is the authoritative
  design source for the frontend stack, source layout, build sequence,
  release-unit model, caching rules, development proxy, and packaging targets.
- `ai/thoughts/phases/2026-07-23-bifrost-console-implementation-roadmap.md`
  positions PR 07 after PR 01 and before the rest of Phase 2.
- `ai/thoughts/tickets/bifrost-console-pr-08-local-security-workspace.md` is the
  first direct consumer of the executable and browser-host foundation.
- `ai/thoughts/tickets/bifrost-console-pr-15-diagnostic-workflows.md` completes
  release packaging and platform verification begun by PR 07.
- `ai/thoughts/tickets/bifrost-console-pr-16-mcp-foundation.md` later mounts MCP
  onto the existing Console listener.
- `ai/thoughts/framework-feature-design-lens.md` supplies the compatibility
  categories used above.

## Related Research

No earlier document was present in `ai/thoughts/research/` at the time of this
research.

## Open Questions

The following facts are not defined in the current repository or ticket brief
and remain inputs for detailed planning:

- the exact current stable Go patch selected for the new Console module;
- the exact Active LTS Node patch and npm version;
- the exact versions of React, TypeScript, Vite, React Router, Tailwind, React
  Aria, Vitest, React Testing Library, Playwright, and any Go dependencies;
- the concrete build-command/task-runner surface that composes frontend and Go
  verification without adding Console to Maven;
- the concrete generated-asset directory, manifest verification representation,
  and stale-version marker;
- the mechanism that supplies the Maven product release string to the Go
  compile and embedded asset build;
- the CI provider/workflow structure and release automation, because none is
  tracked today; and
- the concrete dependency-license inventory and package assembly mechanism,
  because only Maven MPL-2.0 metadata exists today and no root license file is
  tracked.

These are unclassified implementation details in the current checkout, not
existing conventions that can be inferred from `bifrost-cli`.
