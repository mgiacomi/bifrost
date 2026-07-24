# PR 16 — MCP Authentication and Lifecycle Foundation

## Status

Proposed ticket brief. Depends on PRs 09 and 15.

## Outcome

Prove the chosen MCP SDK and lifecycle model, establish the independent local MCP
security boundary, and expose authenticated bootstrap/status with the complete
runtime-status capability.

## In scope

- Revalidate the current stable MCP specification and official Go SDK, then pin
  an exact stable release.
- Complete the required lifecycle spike for session tracking, request
  cancellation, key-generation change, late-result suppression, reconnect, and
  shutdown.
- Add the single profile-owned MCP credential store and protected canonical
  sibling key file.
- Add paired browser enable, reveal, regenerate, and disable operations with
  atomic crash-safe mutation.
- Mount stateful Streamable HTTP on the existing loopback listener behind
  MCP-specific authority, supplied-Origin, and bearer-key middleware.
- Add `bifrost_get_runtime` over the shared status snapshot and advertise only
  the complete `bifrost.runtime-status.v1` capability delivered by this PR.

## Guardrails

- Browser, MCP, and upstream application credentials and authentication realms
  are non-interchangeable.
- Validate authority before authentication, session lookup, or body processing.
- An absent `Origin` may support non-browser clients but weakens no other control.
- SDK types remain inside the thin MCP adapter.
- Do not advertise a named Bifrost capability until every operation and semantic
  promise required by that capability is present.
- Do not advertise OAuth, sampling, elicitation, prompts, subscriptions, event
  replay, or an SDK-owned listener.

## Acceptance signals

- Regeneration and disablement cancel old-generation work, close sessions, and
  suppress raced results without affecting browser or target state.
- Restart preserves enabled key-file state but not upstream application
  credentials or sessions.
- SDK in-memory tests, assembled HTTP security tests, and MCP conformance pass.
- Bootstrap separates capabilities, current status facts, and operation results.
- Capability conformance proves PR 16 advertises
  `bifrost.runtime-status.v1` without prematurely advertising later runtime or
  trace families.

## Detailed-planning focus

Recheck the then-current MCP transport requirements and SDK lifecycle APIs;
research file permission/atomic replacement behavior, credential UI, route
middleware ordering, session registry, client reconnect, server identity, and
fallback criteria.

## Out of scope

Runtime inspection tools beyond bootstrap, trace tools, Agent Skill, remote MCP,
and stdio.
