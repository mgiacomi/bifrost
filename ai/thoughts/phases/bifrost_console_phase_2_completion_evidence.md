# Bifrost Console Phase 2 Completion Evidence

This is an evidence index for the authoritative Phase 2 design and the four
workflows in `bifrost_console_workflows.md`. It does not define another
requirement set. A row is marked passing only after its named command has run
on the named platform and date; hosted and manual evidence remains visibly
pending until it exists.

## Implementation and workflow evidence

| Scope | PR ownership | Executable evidence | Status |
| --- | --- | --- | --- |
| Local host, configuration, workspace, browser authority | PRs 07–09 | Go tests under `internal/config`, `internal/workspace`, `internal/webhost`, `internal/browserapi`, and `web/e2e/{shell,pairing,target-context}.spec.ts` | Passing on Windows, 2026-08-01 |
| Live monitoring and degraded lifecycle | PRs 10–11, 15 | `internal/live`; React activity/provider tests; `web/e2e/{activity-stream,live-executions}.spec.ts` | Passing on Windows, 2026-08-01 |
| Artifact lifecycle and trace analysis | PRs 12–13, 15 | Java/Go fixture corpus; `internal/{artifact,traceanalysis,browserapi}`; `web/e2e/artifact-storage.spec.ts` | Passing on Windows, 2026-08-01 |
| Shared explorer views and browser workflows | PRs 14–15 | `TraceExplorer.test.tsx`, `TraceViews.test.tsx`, `ActiveExecutionDetail.test.tsx`, `artifact-storage.spec.ts` | Passing on Windows, 2026-08-01 |
| `WF-FE-*` failed execution | PR 15 | `terminal-failure`; terminal transition/failure focus tests; failed-execution E2E | Passing on Windows, 2026-08-01 |
| `WF-SE-*` live step inspection | PRs 10, 14–15 | live reducer/provider/detail tests and live-executions E2E | Passing on Windows, 2026-08-01 |
| `WF-UE-*` expensive execution | PRs 13–15 | usage calculator/query tests, configured-limit corpus, usage view tests and E2E | Passing on Windows, 2026-08-01 |
| `WF-SP-*` unfamiliar skill path | PRs 13–15 | frame relationship queries, exact registered-name tests, inert YAML/source-path tests and E2E | Passing on Windows, 2026-08-01 |
| Security and authority boundaries | PRs 08–09, 12, 15 | browser middleware precedence tests, hostile-content presenter tests, production `dangerouslySetInnerHTML` guard | Passing on Windows, 2026-08-01 |
| Accessibility-critical browser behavior | PRs 09–11, 14–15 | component keyboard/focus tests; pinned axe scans and Playwright responsive/zoom/forced-colors/reduced-motion paths | Passing on Windows, 2026-08-01 |
| Response bounds and representative corpus | PRs 10–13, 15 | `limits.go` tests, 20,000-deep calculation, page continuations, deterministic multi-megabyte 64-KiB reads; fixture matrix in `bifrost-console-fixtures/README.md` | Passing on Windows, 2026-08-01 |

## Verification commands

| Evidence | Platform/date | Status |
| --- | --- | --- |
| `./mvnw.cmd -pl bifrost-spring-boot-starter test`; sample tests; `./mvnw.cmd verify` | Windows / 2026-08-01 | Passing; full reactor 790 starter tests and 76 sample tests (1 sample skip) |
| `go test ./...`; documented race command; `go run ./internal/buildtool verify` | Windows / 2026-08-01 | Passing |
| `npm --prefix web run test:e2e` including axe scans | Windows / 2026-08-01 | Passing; 27 tests |
| Fixture regeneration twice with no second diff | Windows / 2026-08-01 | Passing; trace and expected files are LF-only |
| `package` plus `smoke` | Windows x86-64 / 2026-08-01 | Passing; repeat archive SHA-256 `7a232df62a0d01fa3e879b286f06b6ab26627e4b285aecbbe3785ef96b5fdeca` |
| `.github/workflows/console-ci.yml` | GitHub-hosted Linux x86-64 / 2026-08-01 | Passing; [Console CI run 30729350889](https://github.com/mgiacomi/bifrost/actions/runs/30729350889) |
| `.github/workflows/console-release.yml` manual non-publishing validation | GitHub-hosted Windows x86-64, Linux x86-64, macOS arm64 / 2026-08-01 | Passing; [Console Release run 30735606164](https://github.com/mgiacomi/bifrost/actions/runs/30735606164): all three native package/smoke jobs and aggregate checksum verification passed; publish skipped |

## Release artifact evidence

The release contract is tested by `internal/buildtool/package_test.go` and
`projectdeclarations_test.go`: exact target names, one safe top-level directory,
only executable/`LICENSE`/runtime `README.md`, normalized metadata, deterministic
repeat bytes, sidecars, strict extraction, and least-privilege workflow policy.
The Windows x86-64 archive passed repeat-byte and native smoke verification with
SHA-256 `7a232df62a0d01fa3e879b286f06b6ab26627e4b285aecbbe3785ef96b5fdeca`.
[Console Release run 30735606164](https://github.com/mgiacomi/bifrost/actions/runs/30735606164)
then built and smoke-tested the Windows x86-64, Linux x86-64, and macOS arm64
packages on their native GitHub-hosted runners. Its aggregate job accepted
exactly three archives and three sidecars, verified every sidecar, generated
and verified the sorted `SHA256SUMS`, and retained the three platform artifacts
plus `console-release-0.1.0-SNAPSHOT`. Manual dispatch kept publication disabled.
A tag release is not implementation evidence and must not be created merely to
complete this index.

## Manual evidence still required

- Complete all four workflows by keyboard at desktop, 640px, 320px, and 200%
  zoom; confirm visible focus and local evidence scrolling.
- Exercise same-instance reconnect, application restart, authentication
  rejection, upstream/local expiry, target rotation, invalid artifacts, replay
  gaps, and core-finalization failure.
- Review hostile application text under forced colors and reduced motion and
  confirm it creates no active link, form, navigation, or request.
- On each native target, verify `SHA256SUMS`, exact contents, `--version`,
  isolated loopback startup/pairing, and a supported sample target connection.
- Review architecture, four workflows, skill-authoring guidance, bounds,
  accessibility checks, and release contents together.

Phase 2 targets WCAG 2.2 AA but deliberately requires no manual screen-reader
or representative assistive-technology acceptance pass. No row above claims
one. PRs 16–19 may reuse the shared Go services, neutral DTOs, and canonical
workflow IDs; this index does not claim their IDE, MCP, Agent Skill, or parity
features already exist.
