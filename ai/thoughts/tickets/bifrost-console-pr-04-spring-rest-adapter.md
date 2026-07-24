# PR 04 — Spring Adapter Foundation and REST Snapshots

## Status

Proposed ticket brief. Depends on PR 03.

## Outcome

Expose the secured, opt-in, version-matched Phase 1 REST boundary for instance,
skill, active-execution, and current-process trace snapshots.

## In scope

- Add opt-in module activation and strict observability configuration.
- Reserve `/_bifrost/observability/v1/**` and fail safely on route collision.
- Authenticate `X-Bifrost-Api-Key` and establish the sole operator authority.
- Add sanitized problem responses and centrally applied `instanceId` metadata.
- Expose compatibility/status, skill list/detail, active list/detail, and trace
  catalog endpoints with opaque continuations.
- Enforce page, parameter, response, and request bounds.
- Document host Spring Security pass-through configuration.

## Guardrails

- Do not install, replace, reorder, or broaden the host security configuration.
- Do not expose internal Java types or filesystem paths as protocol DTOs.
- Missing Bifrost credentials and upstream/host rejection remain distinguishable.
- Compatibility requires the exact complete release string before other
  application data is interpreted.
- Disabled observability preserves existing runtime behavior.

## Acceptance signals

- Authentication, context-path routing, collision, pagination, identity, problem
  mapping, compatibility, and host-security integration are executable tests.
- Unknown or invalid configuration fails clearly without partial routes.
- REST resources remain read-only and operator-scoped.

## Detailed-planning focus

Research Spring MVC and auto-configuration patterns, route registration,
security filters, error handling, configuration metadata, DTO boundaries,
pagination encoding, samples, and public/SPI classification.

## Out of scope

SSE delivery, artifact bytes, Go clients, CORS, Actuator, and execution control.

