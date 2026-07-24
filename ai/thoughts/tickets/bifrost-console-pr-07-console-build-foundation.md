# PR 07 — Console Project and Reproducible Build Foundation

## Status

Proposed ticket brief. Depends on PR 01 for trace direction; may proceed while
later Phase 1 adapter work continues.

## Outcome

Create the independent `bifrost-console/` Go module and embedded React
application as one reproducibly built, versioned executable.

## In scope

- Scaffold the Go entry point, internal package boundary, asset package, and
  React/TypeScript/Vite application.
- Pin exact Go, Node.js, npm, frontend, and Go dependency versions.
- Add React Router, Tailwind foundation, semantic tokens, and test harnesses.
- Build frontend tests, clean production assets, verify the asset manifest, run
  Go tests, and compile from freshly generated embedded assets.
- Inject the complete Bifrost product version and test stale/missing assets.
- Add local development hot reload with loopback Vite and a narrowly scoped
  proxy to Go.

## Guardrails

- The console is not a Maven module or continuation of `bifrost-cli`.
- Node and npm are build-time dependencies only.
- Do not copy CLI types, filesystem discovery, commands, or architecture.
- No independently deployed SPA, service worker, browser API version, or stale
  prebuilt asset trust.

## Acceptance signals

- A clean checkout can produce one executable that serves the embedded SPA.
- Frontend tests run before asset generation; Go tests and compilation use the
  newly generated assets.
- Version and content-addressed asset checks fail safely when inconsistent.

## Detailed-planning focus

Research repository release/version conventions, CI composition, current stable
toolchain choices, frontend baseline, dependency policy, licenses, generated
asset handling, and supported packaging targets.

## Out of scope

Target access, pairing, trace parsing, product pages, and release publication.

