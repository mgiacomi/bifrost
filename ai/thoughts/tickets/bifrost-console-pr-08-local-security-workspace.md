# PR 08 — Profile, Workspace, and Local Browser Security

## Status

Proposed ticket brief. Depends on PR 07.

## Outcome

Establish safe single-profile ownership, disposable trace workspace lifecycle,
loopback serving, and the browser pairing/session/CSRF security boundary.

## In scope

- Add strict versioned YAML configuration and platform/default `--config`
  resolution.
- Acquire the exclusive profile lock and verified managed work-directory lock.
- Validate and clean only the managed transient subtree before serving.
- Bind the browser listener to loopback and validate exact listener authority
  and same-origin browser requests.
- Add startup pairing, bounded browser sessions, in-memory CSRF tokens, secure
  cookie and cache behavior, and sensitive-operation middleware.
- Add restrictive browser security headers and graceful/fatal workspace
  lifecycle handling.

## Guardrails

- Never adopt prior-process transient entries.
- Workspace establishment completes before the listener opens; later loss of
  safe workspace access terminates the host.
- Pairing, Host validation, Origin validation, session authentication, and CSRF
  remain independent controls.
- Secrets never enter URLs, logs, ordinary YAML, or browser storage.
- The plaintext listener remains loopback-only.

## Acceptance signals

- Path, symlink/reparse-point, ownership/permission, lock, cleanup, and restart
  cases are tested on supported platforms as applicable.
- Foreign origins, authorities, sessions, and CSRF values fail closed.
- Shutdown releases listeners and locks and cleans transient content best-effort.

## Detailed-planning focus

Research cross-platform config directories, file locking and permissions,
Windows path safety, dual IPv4/IPv6 loopback behavior, cookie policy, browser
bootstrap, session bounds, CSP, and fatal workspace detection.

## Out of scope

Application targets, MCP authentication, artifact interpretation, and remote use.

