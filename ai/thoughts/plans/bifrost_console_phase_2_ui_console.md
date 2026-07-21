# Bifrost Console — Phase 2 Personal UI Console

## Status

Initial product and architecture direction. This document records decisions established during early planning and identifies the UI questions still to resolve. It is not yet an implementation plan.

## Related design

Phase 2 depends on the observability contracts and safety properties defined in [Bifrost Console — Phase 1 Observability Foundation](./bifrost_console_phase_1_observability_foundation.md).

Changes to the application-adapter REST/SSE boundary also follow [Bifrost Console Compatibility Contract](../bifrost-console-compatibility.md).

Phase 1 owns engine publication, activity projection, active-execution state, trace discovery, and the Spring web adapter. Phase 2 owns the separately running developer console and its browser experience.

Phase 2 also owns **Go console-side trace analysis**. The application exposes its finalized current UTF-8 NDJSON trace files without repackaging; their compatibility is included in `consoleCompatibilityVersion`. The core Java trace subsystem continues to own each canonical file and its persistence or grace deletion, while the optional application adapter owns only current-process catalog metadata and authenticated streaming access. Go shared services parse acquired artifacts and provide frame trees, timelines, summaries, filtering, search, pagination, failure indexes, usage attribution, and other derived developer views to both browser and future MCP adapters. These query and analysis features are not Phase 1 application-server responsibilities.

## Product character

Bifrost Console is initially a **personal developer instrument that uses a browser for its interface**. It is not initially a shared observability service, fleet-management platform, or execution control plane.

Its purpose is to help a developer:

- understand what a running Bifrost application can do;
- see what Bifrost executions are doing now;
- follow a concise live execution narrative;
- understand execution paths, timing, usage, retries, and failures; and
- inspect retained traces deeply enough to perform a developer audit.

In this context, developer audit means reconstructing and understanding framework behavior. It does not mean business, regulatory, compliance, immutable, or long-term archival audit.

## Phase 2 objective

Deliver a compact standalone console executable that serves a rich browser UI and connects to the supported Phase 1 observability web adapter without depending on Bifrost Java internals or trace filesystem layout.

The initial product should support one developer, one console process, and one selected Bifrost application at a time.

## Chosen architecture

```text
browser
    -> loopback HTTP
    -> Go console host
        -> user-configured HTTP or HTTPS
        -> application Spring server
            -> Bifrost observability web adapter
            -> observability core
```

### Go console host

The console host is a separate Go application responsible for:

- serving the compiled browser application;
- embedding the browser assets into the executable;
- managing the selected Bifrost target connection;
- holding target credentials outside browser storage;
- calling Phase 1 REST endpoints;
- subscribing to the Phase 1 SSE activity stream;
- retaining one bounded current-scope recent-activity window for browser reconnect and transport-neutral on-demand queries;
- relaying activity to the browser through that window and bounded per-tab delivery state;
- reporting connection and protocol compatibility state;
- assigning a console-local opaque target scope and rejecting or discarding stale target work;
- applying target timeouts and certificate trust configuration;
- exposing transport-neutral runtime query and analysis services below browser handlers;
- parsing and analyzing finalized traces from the exactly matched application contract;
- maintaining a bounded transient trace workspace or index for interactive browser and future MCP queries; and
- proxying trace downloads.

The console host is a backend-for-frontend and the owner of console-side trace analysis, not another authoritative observability database or execution engine.

### Browser application

The browser UI should be a client-rich TypeScript single-page application. The exact framework is unresolved.

A rich client is preferred because the eventual trace and live-execution experiences require hierarchical navigation, incremental updates, selection preservation, filtering, expandable detail, split views, deep links, and potentially large-list virtualization.

The browser communicates only with its same-origin Go console host. It does not connect directly to the Bifrost application.

The browser owns the in-memory live view it renders. It obtains current snapshots through Go, applies relayed Phase 1 activity envelopes to its local UI store, and owns navigation, filtering, selection, expansion, and other presentation behavior. It does not parse raw NDJSON traces or independently calculate authoritative hierarchy, duration, usage, failure, or availability facts that browser and MCP must share.

### Spring web adapter

The Phase 1 Spring web adapter registers REST and SSE routes in the application's existing Spring web server. It does not start a second listener, configure TLS, change bind addresses, or expose internal Java objects as the protocol.

The initial adapter may support Spring MVC only. A non-web Bifrost application does not silently become a web application by adding observability core functionality.

## Reasons for keeping the Go host between browser and Bifrost

The Go host:

- avoids configuring CORS on every observed application;
- keeps target credentials out of browser storage and out of browser runtime after an intentional credential-submission request completes;
- centralizes target connectivity, certificate trust, timeouts, and compatibility handling;
- prevents the UI from depending on application topology or trace storage layout;
- provides a natural SSE relay and future fan-out boundary;
- enables a compact standalone executable;
- keeps the console operationally independent from the Java framework being monitored; and
- discourages accidentally sharing internal Java types between the engine and console, keeping the protocol explicit.

A static SPA that connects directly to Bifrost is not a planned deployment mode.

## Relationship to the existing CLI

The existing `bifrost-cli` is a deprecated proof of concept. It may be consulted temporarily for selected frame-tree, duration, failure-identification, and retrospective-debugging behavior, but it is not a supported predecessor, compatibility target, distribution companion, or fallback interface for Bifrost Console. It will be removed after the Bifrost Console implementation is complete.

Its filesystem-oriented trace loading, Go record types, filename parsing, and CLI presentation state model predate the supported Phase 1 web protocol. Implementation planning may deliberately salvage an algorithm or test idea, but new console code must not depend on the CLI project or preserve its types, directory discovery, architecture, commands, or behavior merely to ease migration. Phase 2 does not copy artifacts into a CLI directory or provide cross-process CLI discovery. A developer may manually point the legacy CLI at a specific readable directory while it still exists, but that is opportunistic raw-file inspection outside the supported console lifecycle.

## Packaging and operational independence

The compiled browser assets should be embedded into the Go executable so the initial console can be distributed as one compact artifact. The production browser and Go host are consequently one atomically built and distributed component, not independently versioned peers. The initial product has no browser API compatibility version or runtime browser-to-Go negotiation.

The Java Bifrost artifacts and Go console executable form one coordinated Bifrost release unit. They always receive the same product release version and are published together through the Maven release process, including when a release changes only the console or only the Java implementation. They remain separate runtime components despite this release coupling.

Running the console should not require:

- a JVM;
- Node.js or a frontend development toolchain at runtime;
- a separate static web server;
- shared filesystem access to the observed application; or
- a database.

The frontend toolchain is a build-time concern only.

Development may run the frontend development server separately for hot reload, but the production/release artifact must serve only the compiled embedded assets. The implementation plan should define a reproducible build that prevents stale frontend assets from being embedded accidentally. Production static assets should use content-addressed names and may be cached immutably, while the entry document must be revalidated or not stored so it cannot keep selecting an earlier asset set after a console restart. The initial product must not install a service worker or other offline cache that can preserve an earlier SPA independently of the executable. A page holding a process-local browser session against a restarted console bootstraps again, re-pairs, or reloads; it does not negotiate an old browser DTO contract with the new process. Independently deploying the browser would require a future explicit compatibility design.

## State ownership

State should remain deliberately divided:

- **Bifrost application:** authoritative skill catalog, active executions, activity stream, and retained traces.
- **Go console:** selected target configuration and opaque target scope, in-memory target credential, connection and exact-version compatibility state, one upstream SSE connection, a bounded recent-activity window exposed through transport-neutral query services, per-tab relay state, browser session pairing, and bounded transient trace parsing/indexing state.
- **Browser:** the current rendered live-execution view derived from snapshots and relayed activity, plus navigation, filters, selections, expanded nodes, pane sizes, and other presentation state.

The application remains authoritative. Go must not continuously materialize a complete duplicate of the skill catalog, active-execution registry, or execution history merely to serve browser-shaped state. It may request or briefly coalesce current snapshots, retain a bounded ring of already-received activity envelopes for browser reconnect and on-demand recent-activity queries, and use its bounded disposable local work directory to acquire, parse, and index a selected retained trace. The ring is a short-lived best-effort window, not execution history. All application-derived relay, catalog, execution, and trace state belongs to the current opaque `targetScopeId` and follows the authoritative [`TargetContext` ownership](#go-targetcontext-ownership) lifecycle. It is cleared on scope rotation or console shutdown and is never authoritative. A database is explicitly out of initial scope.

## Local configuration

Phase 2 owns one versioned, strictly validated local YAML configuration file in the platform-appropriate user configuration directory, with a `--config` option for an alternate location. It covers console listener behavior, selected-target defaults, target timeouts and trust configuration, transient workspace limits, and other non-secret operational settings.

Unknown fields are rejected, durations and byte sizes use explicit units, and unsafe or nonpositive values are rejected clearly. Changes to this static YAML initially require restart. Secrets are not stored inline in it. Phase 3 extends the YAML with restart-only, non-secret MCP operational settings and places the persistent MCP access key in a separate protected sibling file. Runtime enablement, regeneration, and disablement mutate only that credential file and the process-local MCP authentication snapshot; they are not YAML configuration changes and take effect immediately.

Persistent configuration, trust material, and the Phase 3 MCP access-key file never live inside the disposable work directory described below. There are no user-data or configuration exceptions inside its `transient` subtree.

## Disposable local work directory

The Go console uses one visible console-owned work directory rather than scattering acquired traces across platform temporary directories. By default it resolves and captures this absolute path once at process startup:

```text
<startup-working-directory>/bifrost-console-work/
  .bifrost-console-work
  .lock
  transient/
```

`startup-working-directory` means the process current working directory at launch; it is not necessarily the directory containing `bifrost-console.exe`. `--work-dir <path>` replaces the default with that exact work-directory path rather than adding another `bifrost-console-work` child. The console prints the resolved absolute path at startup and exposes it in paired status so developers can find it and notice when different launch contexts select different directories.

The marker identifies a directory created and managed by Bifrost Console; it is not an authentication credential. On first use, Go creates the root, marker, lock file, and `transient` child with owner-only access or the closest enforceable platform equivalent. On every later startup it resolves and verifies the exact root, rejects a root that is a symbolic link or Windows reparse point, requires the expected marker, and acquires an exclusive operating-system lock before deleting anything. If the named directory already exists without the valid marker, Go treats it as unrelated user data and refuses to clean or use it. It never guesses ownership from the directory name alone.

After acquiring the lock, startup deletes and recreates only the known `transient` child. Cleanup must remain beneath the verified work root and must not follow symbolic links, junctions, or other reparse points encountered within it. The marker and lock are console ownership metadata and are not part of the trace cache. A second console process cannot share this work directory: failure to acquire the lock produces a clear conflict and requires a different launch directory or `--work-dir`; it must never clean an active console's files.

Every acquired artifact, partial download, index, reconstructed-payload file, and other application-derived disk state belongs under `transient`. Go never scans, adopts, indexes, or serves its contents from a prior console process. Startup cleanup occurs before target connection, trace acquisition, browser diagnostic service, or MCP diagnostic service can use the workspace. Thus a crash may leave protected files until this same work directory is used again, but the next successful startup removes them rather than recovering them. Launching later from a different working directory does not find or clean the earlier directory; the displayed path and `--work-dir` allow a developer to choose one stable location. This is ordinary cleanup, not secure erasure.

Target-scope rotation and graceful shutdown delete current transient artifacts best-effort after cancelling their users; graceful shutdown may leave the empty managed root, marker, and unlocked lock file. Explicit raw downloads saved by a browser belong to the developer-selected download location and are not console-workspace files, so workspace cleanup does not remove them.

If Go cannot create, lock, permission, identify, or safely clean this directory, or cannot provide the required owner-only protection on the backing filesystem, it fails closed for disk-backed trace acquisition, indexing, and multi-request analysis. It does not fall back to a shared, weakly protected, or uncertain directory and does not silently reuse partially cleaned contents. The console may continue to provide independently safe target status, skill, live-monitoring, trace-catalog, and direct streaming-download proxy capabilities, while affected shared-service operations return `TRACE_ANALYSIS_UNAVAILABLE` with a safe workspace message. Workspace failure must not affect the observed Bifrost application.

## Transport policy

### Browser to Go console

Initial local operation uses HTTP with a strict loopback boundary:

- bind explicitly to `127.0.0.1` and optionally `::1`;
- never bind the HTTP listener to `0.0.0.0`, a LAN address, or a public interface;
- validate expected loopback `Host` and `Origin` values;
- use a one-time browser pairing mechanism;
- never put target credentials in browser URLs, browser storage, or logs; and
- do not provide a configuration switch that exposes the plaintext console listener remotely.

HTTPS is deferred until remote console access becomes a real requirement. That future feature may include user-provided certificates and Let's Encrypt/ACME integration for owned domain names.

### Go console to Bifrost application

The observed application owns its transport policy. The console accepts an intentionally configured HTTP or HTTPS target.

- HTTP targets are permitted.
- HTTPS targets use ordinary certificate and hostname verification.
- Private infrastructure may supply a custom CA bundle.
- The console identifies an HTTP target persistently as unencrypted without blocking it or repeatedly demanding confirmation. The warning explains that the application observability key and returned diagnostic data may be observed or modified by parties with access to the network path.
- The console must not silently weaken HTTPS verification.

Bifrost controls the safe formation and authorization of the observability data it emits. The host application controls how its observability routes are transported and exposed; transport confidentiality and integrity depend on the configured HTTP or HTTPS target.

## Local browser pairing

The personal console should not require user accounts, passwords, or OIDC. The current direction is a lightweight per-process pairing flow:

1. The console generates a high-entropy one-time pairing secret at startup.
2. It prints or opens a pairing URL.
3. The browser exchanges the secret once.
4. The console establishes a short-lived, `HttpOnly`, `SameSite=Strict` browser session.
5. The one-time secret becomes invalid.
6. Browser sessions disappear when the console exits.

The exact URL/exchange design must avoid placing reusable credentials in browser history or server logs.

## Target authentication and authorization ownership

The Go target client supports only authenticated Bifrost observability endpoints. A configured target URI identifies the application base including any externally visible servlet or reverse-proxy context path; Go appends the fixed `/_bifrost/observability/v1/` namespace. The application always authenticates and authorizes the supplied application observability key. The browser talks only to its paired loopback Go host and does not require CORS access or send the application key directly to the observed application.

For the personal console, the initial upstream authentication mechanism is the high-entropy static application observability key configured by the Bifrost application. Go supplies it to the application as `X-Bifrost-Api-Key: <key>`; it is entered through the paired browser workflow or a protected interactive console prompt and retained only in Go process memory. If entered in the browser, the key necessarily exists briefly in the input element, browser process, JavaScript submission state, and same-origin HTTP request to the loopback Go server; browser developer tools and sufficiently privileged extensions may observe it. It is not persisted in browser storage and is cleared from browser application state after the request completes. The protected interactive console prompt is the alternative for a developer who does not want the key to pass through the browser. Go uses the key for both browser-initiated and enabled MCP-initiated operations against the selected target. It must not be placed in the console's ordinary configuration file, command-line arguments, URLs, browser storage, or logs.

Possession of the application observability key establishes the application's sole `BIFROST_OPERATOR` authority. This is a deliberately narrow machine-to-machine authentication mechanism for the initial personal developer product, not a user identity, role matrix, or integration with every authentication scheme used by the host application.

The target-client boundary accepts a credential provider and passes the key in the fixed `X-Bifrost-Api-Key` header to the application, which remains the only authority that validates it. A `401` carrying Phase 1 `BIFROST_API_KEY_REJECTED` transitions the selected target to an unauthenticated state and maps to `TARGET_AUTHENTICATION_REQUIRED`; the developer must supply a new key. A generic `401` or `403` without that code maps to `TARGET_ACCESS_BLOCKED` and is presented as probable host Spring Security or upstream-proxy interference rather than mislabeled as an invalid Bifrost key. User accounts, refresh tokens, automatic login flows, Basic authentication, mTLS client identity, OAuth, alternative authentication headers, and provider-specific authentication are future credential-provider features.

The console deliberately uses **acquisition-time authorization** for application-derived evidence. `BIFROST_API_KEY_REJECTED` blocks every later operation that requires a new application request or SSE connection, but it does not rotate `targetScopeId`, invalidate a complete Go-acquired artifact handle, erase the bounded recent-activity window, or revoke another complete current-scope result already admitted into Go. Those copies remain accessible to an otherwise authorized paired browser or MCP client until their normal idle or capacity eviction, target-scope rotation, or console shutdown. Supplying replacement application credentials rotates `targetScopeId` under the existing rule and therefore clears prior-scope evidence.

Acquisition-time authorization never turns an incomplete transfer into cached evidence. If application authentication fails before or during a multi-request acquisition, or a trace download does not complete and validate, Go closes and deletes the partial state and issues no artifact handle. Evidence retained after rejection must preserve its original observation and cursor facts. An operation that promises current application state or requires refresh still returns `TARGET_AUTHENTICATION_REQUIRED`; adapters must not relabel an older cached observation as current merely because its scope has not yet rotated.

Successful browser pairing/session authentication is logged without recording the pairing secret, cookie, upstream application observability key, or diagnostic content. Per-request or operation-level access logging is not initially required.

### Application-data forwarding rule

The application-to-consumer rule is: **console authentication secrets are never returned as diagnostic data; Bifrost does not detect or redact secrets embedded by the observed application in recorded content.** Console authentication secrets include the upstream application observability key and `X-Bifrost-Api-Key` header, plus the console's pairing secret, browser session cookie, CSRF token, MCP access key, and other authentication headers. Those values remain part of their respective authentication or explicit credential-management flows rather than runtime, skill, activity, or trace results.

This rule does not classify the contents of application data. A value inside a skill YAML file, prompt, model request or response, application-provided model configuration, activity event, trace record, payload, tool input or output, error, or metadata remains application observability content even if it looks like a password, token, key, credential, personal data, or other sensitive value. Go must not scan, redact, sanitize, or omit it in the initial release. Browser and MCP adapters expose the same underlying application content; summaries, pagination, and caller-selected detail may shape individual responses but must not create adapter-specific disclosure filters. All application-provided diagnostic text is untrusted data. Go may parse it and calculate deterministic results from it, but must never interpret it as commands. The initial release does not add a universal provenance wrapper or content-classification model.

## Protocol handling inside the Go host

The Go host should not create a second semantic activity protocol merely because it relays SSE. The starting bias is:

- Phase 1 activity envelopes pass through with their identity, process/stream incarnation, delivery cursor, canonical sequence, timestamp, frame, route, kind, and details intact.
- The Phase 1 `EXECUTION_OBSERVATION_ENDED` exception also passes through intact but is not presented as a canonical trace projection. It may omit canonical sequence and execution outcome and reports only that observation ended incompletely because core diagnostic finalization failed.
- The Go host may add console-owned connection events, but those must be namespaced or transported separately so they cannot be confused with Bifrost execution activity.
- REST responses exposed to the browser may be proxied or mapped into explicit Go/browser DTOs, but the mapping must preserve Phase 1 semantics.
- The Go console and application adapter ship as a coordinated Bifrost release pair with the same product release version. They must also have the same exact `consoleCompatibilityVersion` before using REST snapshots, SSE activity, or downloaded NDJSON traces. The authenticated instance-status request is the compatibility probe, and Go reads only its stable top-level compatibility field until a match is established. This one umbrella covers all Java-to-Go runtime compatibility. There are no separately reported or negotiated engine, adapter, Go-release, trace-schema, or trace-container compatibility versions.
- Phase 1 removes `TraceRecord.schemaVersion`; raw NDJSON records contain no independent version property. Go interprets a trace only under the exact umbrella match and `targetScopeId` from which it was acquired.
- A `consoleCompatibilityVersion` mismatch produces `INCOMPATIBLE_TARGET` before the UI attempts partial rendering or trace analysis. Invalid trace content produces `INVALID_ARTIFACT`, not negotiation of another version.

The implementation plan must decide whether protocol DTOs are generated from an API description or maintained explicitly. In either case, the Phase 1 protocol—not internal Java classes—is authoritative.

## Go `TargetContext` ownership

The Go console has one authoritative `TargetContext` boundary for the selected application. It is the sole owner allowed to create or rotate the console-local `targetScopeId` and to commit the target identity used by shared Console, browser, and MCP services. Status handlers, the upstream SSE connection manager, trace acquisition, analysis services, browser handlers, and MCP handlers must not independently adopt a target, credential context, application instance ID, or process-incarnation ID.

`TargetContext` owns the normalized target address and connection-authority settings, an opaque credential generation and its protected credential provider, current authentication, connection, and exact-version compatibility status, the established application instance and process-incarnation identities, and the lifecycle of the one upstream SSE connection. Connection-authority settings include the target scheme and authority, externally visible context path, redirect or proxy policy where supported, and certificate-trust policy. The raw application credential remains behind the target-client or credential-provider boundary and is never included in snapshots given to browser, MCP, analysis, or other consumers.

Every target operation captures an immutable `TargetContext` scope snapshot when it begins. The snapshot supplies the current `targetScopeId`, established runtime identity and compatibility facts, and access to an appropriately scoped target client without exposing the credential. Before returning a result, installing an artifact or index, updating shared state, or publishing an event, the operation verifies that its captured scope is still current. `TargetContext` may publish successive immutable snapshots with the same `targetScopeId` as connection, authentication, compatibility, or availability status changes; equality of scope does not imply that those status facts remain unchanged.

A new Go console process creates a fresh `targetScopeId`. Replacing the selected target, changing connection-authority settings, or accepting replacement target credentials rotates it immediately. Credential replacement rotates the scope without comparing the old and new secret values. The first successful authenticated status check for that newly selected context establishes its application instance and process-incarnation identities without rotating the scope a second time.

After identity has been established, only a serialized reconnect status check or new upstream SSE handshake may propose a changed application identity to `TargetContext`. A different identity is committed only through scope rotation. If another authenticated target response carries a different instance or process-incarnation identity, the receiving service rejects that response and requests serialized status revalidation; it does not adopt whichever concurrent response finishes last. The SSE manager may report handshake identity to `TargetContext`, but it cannot commit that identity itself.

Ordinary timeout changes, authentication failure, exact-version incompatibility, and temporary transport disconnect or reconnect update current status but do not rotate the scope. A reconnect also preserves the scope when the selected target, credential context, revalidated instance ID, and process incarnation remain unchanged. Supplying replacement credentials after an authentication failure does rotate it. Operational timeout changes apply to later operations without invalidating otherwise usable current-scope diagnostic state. In particular, explicit upstream authentication rejection prevents new target access but leaves complete previously acquired evidence under its normal local lifecycle; this is the authoritative acquisition-time authorization rule, not an accidental cache behavior.

Rotating `targetScopeId` cancels prior-scope operations, closes the obsolete upstream stream, and directs scope-bound services to clear all application-derived state: skill-file entries and YAML content, active snapshots, activity relay and replay state, acquired trace files, indexes, derived analysis, and browser/MCP runtime views. Those services continue to own their bounded data; `TargetContext` coordinates the boundary rather than becoming a store for every catalog, activity envelope, or trace. Cancellation is advisory, so completed late work is still rejected by the scope check. A partial artifact is closed and deleted rather than admitted to the workspace. A browser download already in progress is cancelled; bytes already delivered cannot be recalled, but the incomplete result must not be accepted or cached as a valid artifact.

Every target-specific REST response and SSE event includes the scope snapshot's `targetScopeId`. It is an opaque UUID whose only supported operation is equality; it is not a numeric counter. All Go-owned target continuations, acquired-artifact handles, cached entries, and derived references bind to the scope in which they were created. Presenting any prior-scope reference or continuation produces `TARGET_CHANGED` rather than an empty page, a generic stale-context result, or reinterpretation against the current target. The binding may be an opaque server-side reference and does not require a signature because the local APIs are already authenticated.

Browser pairing and session state, MCP authentication state, selected-target configuration, and the protected in-memory target credential are console-owned and may survive an application-runtime identity reset. Browser and MCP consumers remain connected to the console but lose all prior application-derived data and must query the new target scope. A temporary disconnect may retain current-scope state while application identity is unknown; if authoritative revalidation reveals a different identity, `TargetContext` completes the full rotation before any new-runtime result is published.

A new Go console process always creates a fresh `targetScopeId` and never reloads the upstream application credential, even when non-secret selected-target defaults and the separate MCP access key persist. If a target is selected but no application credential has yet been entered, console and MCP status report the console as ready and the selected target as requiring authentication. MCP transport initialization may still succeed with its valid local key, while every operation requiring new application access returns `TARGET_AUTHENTICATION_REQUIRED`. The paired developer supplies the application key again without changing the IDE's MCP credential.

## Transport-neutral Go service error contract

Target, catalog, activity, acquisition, and trace-analysis services below the browser and MCP adapters share one small domain-error contract. A service error has a stable `code`, a safe human-readable `message`, the operation's `targetScopeId` when it is target-specific, and only bounded code-specific details needed to interpret that error. Adapters and callers branch on `code`, never on message text or an internal Go error string. Internal causes remain available for sanitized console diagnostics but are not automatically returned to clients.

The initial shared codes are:

| Code | Meaning |
|---|---|
| `INVALID_ARGUMENT` | A non-cursor service input is malformed or unsupported. |
| `TARGET_AUTHENTICATION_REQUIRED` | No usable in-memory application credential is present, or Phase 1 returned `BIFROST_API_KEY_REJECTED`. A credential is required for any operation needing new application access. Complete previously acquired evidence may remain locally inspectable under the acquisition-time authorization rule. |
| `TARGET_ACCESS_BLOCKED` | A host security layer or upstream proxy returned `401` or `403` without the Bifrost rejection code. The console must not claim that the Bifrost key is invalid. |
| `TARGET_UNAVAILABLE` | The target could not be reached or completed with a usable response because of DNS, connection, TLS, timeout, or upstream-server failure. |
| `INCOMPATIBLE_TARGET` | Authenticated instance status reported a different `consoleCompatibilityVersion`; expected and observed values may be included as code-specific details. |
| `TARGET_CHANGED` | Work or a reference belongs to a `targetScopeId` that is no longer current. The caller must discard prior-scope state; the current scope may be included as a code-specific detail. |
| `INVALID_CURSOR` | A cursor is malformed or does not match its endpoint, query, ordering, filter, artifact handle, or payload range. |
| `STALE_CURSOR` | A previously valid collection traversal can no longer continue. The caller begins that collection or baseline query again. |
| `NOT_FOUND` | A resource is not currently available and no stronger lifecycle fact is known. It must not be presented as proof of expiration. |
| `ARTIFACT_EXPIRED` | A valid current-scope `artifactHandle` no longer names a retained Go copy. The caller may reacquire only if the current application catalog still offers the trace. |
| `INVALID_ARTIFACT` | Downloaded trace bytes failed the current Java-owned structural or semantic contract. Code-specific details may state whether unchanged raw download remains available, but no partial analysis result is valid. |
| `LIVE_MONITORING_UNAVAILABLE` | The application reported that its live projection is unavailable. Skill and finalized-trace operations remain independent. |
| `TRACE_ANALYSIS_UNAVAILABLE` | Go cannot safely provide disk-backed acquisition or analysis, including because its managed workspace is unavailable. Independently safe target, skill, live, catalog, or direct-download operations may continue. |
| `LIMIT_EXCEEDED` | A configured request, response, artifact, workspace, or concurrency bound prevents the operation. Bounded details may identify the relevant limit and observed value when safe. |

Phase 1 problems map into this contract before reaching either adapter: `BIFROST_API_KEY_REJECTED` maps to `TARGET_AUTHENTICATION_REQUIRED`; a generic upstream `401` or `403` without that code maps to `TARGET_ACCESS_BLOCKED`; connection, TLS, timeout, `APPLICATION_ERROR`, and other unusable upstream failures map to `TARGET_UNAVAILABLE`; compatibility mismatch maps to `INCOMPATIBLE_TARGET`; `INVALID_REQUEST` maps to `INVALID_ARGUMENT`; and the Phase 1 cursor, not-found, live-monitoring, and `LIMIT_EXCEEDED` codes retain their corresponding Go meanings. Absence of an in-memory application credential produces the same Go-owned `TARGET_AUTHENTICATION_REQUIRED` code before an upstream request is attempted. Trace validation, workspace safety, scope rotation, handle retention, local continuations, and Go limits produce their Go-owned codes directly.

The contract deliberately has no universal `retryable`, `requiredAction`, `restartRequired`, `configurationRequired`, or `evidenceAvailable` fields. Those values would be unreliable across causes and would turn a developer tool into a troubleshooting state model. Recovery semantics stay with the specific code. Evidence availability is reported only where known, such as raw availability on `INVALID_ARTIFACT` or current application trace availability alongside an expired handle.

A replay gap is not a service error. A successful activity or reconnect result reports the retained cursor range, observation time, and missing beginning, after which the browser obtains a fresh active baseline and MCP accurately describes the returned suffix. Likewise, ordinary empty collections and a successful status reporting an unavailable independent feature are not converted into generic errors.

Browser HTTP handlers map shared errors to suitable HTTP status codes and a common local error envelope that preserves `code`, safe `message`, optional `targetScopeId`, and bounded details. HTTP status remains a coarse transport representation; browser behavior is keyed by the shared code. Pairing, browser-session, Host/Origin, and CSRF failures occur before shared target services and remain browser-adapter security errors rather than being mislabeled as target failures. Phase 3 defines the corresponding MCP mapping, including the same domain codes.

## Console-local browser API boundary

The browser-to-Go API is an explicit local adapter over transport-neutral Go services. Browser HTTP handlers must not become the owners of target connection semantics, trace parsing, failure and usage calculations, or other runtime facts that MCP later consumes. Conversely, Go is not required to build a complete browser-ready materialized mirror of Bifrost state.

This API is still explicit and tested, but it is not an independently supported cross-release protocol. Its browser caller is the asset set embedded in the same Go executable. Browser API DTO changes therefore require an atomic Go-and-assets build plus browser adapter tests, not a new compatibility number. A stale or unauthenticated page must be sent through reload, bootstrap, or pairing behavior and must never be classified as `INCOMPATIBLE_TARGET`, which is reserved for the Java-to-Go boundary.

The initial local API uses:

- REST for paired bootstrap, target and compatibility status, current snapshots, skills, retained-trace discovery, trace queries, and sensitive local operations; and
- SSE for relayed Bifrost activity and Go-owned connection or target-lifecycle events.

The browser builds its current live view from a best-effort application snapshot obtained through Go plus later activity envelopes. Go may map REST contracts into explicit browser DTOs, but it should pass Phase 1 activity identity and semantics through without asking the browser to reverse-engineer Go internals. The browser never receives the application credential and never parses the NDJSON artifact. Catalogs, links, and ordinary browser DTOs use opaque trace identifiers rather than filesystem paths. Authenticated raw-record inspection and raw artifact download remain explicit exceptions: they may show a filesystem path already present in the canonical trace, and Go does not add redaction or rewriting solely to remove it.

Go-owned events must remain distinguishable from Bifrost execution activity. At minimum, relayed execution envelopes occupy a `bifrost.activity` namespace and local connection, target-change, and replay-gap events occupy a `console.*` namespace. Names such as `console.connection`, `console.target_changed`, and `console.replay_gap` are the starting vocabulary; exact wire spelling may be settled during local API planning, but the namespace separation is mandatory. A local event must not look like a canonical Bifrost trace or activity record. Only `bifrost.activity` envelopes participate in the upstream delivery-cursor and bounded replay contract. `console.*` events report current local lifecycle state and may be refreshed through paired bootstrap or status REST responses rather than inserted into the Bifrost cursor sequence.

Target scoping, identity commitment, credential-context replacement, and stale-result rejection follow the authoritative [`TargetContext` ownership](#go-targetcontext-ownership) rules. Browser handlers consume scope snapshots and target-scoped services; they do not implement their own target commit or rotation logic.

The browser treats any `targetScopeId` change or mismatch as a whole-application reset boundary. It stops its local event stream, discards all in-memory application-derived and presentation state, and navigates with replacement semantics to the console root so paired bootstrap loads a clean application rather than reopening a stale trace, frame, record, filter, or selection route. The paired `HttpOnly` session cookie may remain valid because it is console-owned; no application-derived browser store survives the reset. This deliberately favors a simple reliable reload over surgical client-state reconciliation. Each open tab performs the same reset independently.

Go retains one small bounded ring of recently received Phase 1 activity envelopes for the current `targetScopeId`, in addition to bounded pending delivery per tab. The ring supports browser reconnect and transport-neutral bounded recent-activity queries; it is not authoritative or durable execution history and is cleared whenever the target scope rotates or the console shuts down. Queries may filter the current window by execution identity and cursor range and return only complete envelopes in application delivery-cursor order. Every successful result identifies the observed cursor range and time and states when the requested beginning is no longer present, so a caller cannot mistake the returned suffix for complete execution activity. A new or reconnecting tab obtains a fresh active baseline and may request events after its last cursor. If the range remains in the ring, Go replays it; otherwise the successful reconnect result reports a local replay gap and the tab proceeds from the fresh baseline. A replay gap is not a shared service error. Multiple tabs maintain independent UI state and cursors. One slow tab may be disconnected and refreshed without delaying the upstream connection or another tab.

After pairing, the local `HttpOnly`, `SameSite=Strict` session cookie authenticates browser requests. Expected loopback `Host` and same-origin `Origin` validation remain mandatory. Sensitive or state-changing browser operations additionally require a session-bound CSRF token returned through the paired bootstrap response, retained only in browser memory, and sent in a custom request header. This applies at least to submitting or replacing the selected target and application observability key; enabling or disabling MCP; revealing or regenerating its key; and any future local configuration mutation. Secrets and CSRF tokens must not appear in URLs, logs, or persistent browser storage. Every authenticated browser response carrying application diagnostic data, including trace attachments and diagnostic errors, uses `Cache-Control: no-store`; credential-management responses do as well. This prevents ordinary HTTP caching but does not control what the paired browser, extensions, or developer tools retain after receipt. Pairing, Host/Origin validation, the session cookie, and CSRF validation are complementary controls.

Browser and future MCP routes share the loopback listener but belong to distinct, fail-closed request-validation realms. Routing must select the realm before authentication rather than applying a listener-wide “cookie or bearer” policy. Browser routes accept only the paired browser session and apply their browser-specific `Host`, `Origin`, and CSRF rules; they must not accept the MCP access key or upstream application key as substitutes. MCP routes accept only the MCP bearer key and apply the MCP-specific `Host` and `Origin` rules defined by Phase 3; they must not accept the browser cookie, pairing secret, CSRF token, or upstream application key as substitutes. Accommodating legitimate non-browser MCP clients must never weaken browser-route validation. The realms may share low-level credential-comparison, request-bound, and logging utilities without sharing acceptance policy.

## Live activity relay and recent-activity queries

The Go host subscribes to the Phase 1 SSE activity stream and relays it to its browser client.

The relay must preserve:

- activity identity and ordering;
- cursor semantics;
- replay-gap signals;
- connection and reconnection state; and
- end-of-execution activity, including the exceptional `EXECUTION_OBSERVATION_ENDED` lifecycle activity when core finalization prevents a trustworthy canonical completion projection.

The same bounded current-scope window is exposed below adapters through a transport-neutral recent-activity query service. Browser handlers may use it for reconnect and current-window inspection. Phase 3 MCP may use it for bounded on-demand snapshots of recent activity for one execution. This shared service does not create another upstream subscription, extend retention, reconstruct missing events, or promise that the beginning of an execution remains present.

The browser should recover from an expired cursor by obtaining a fresh paginated active-execution baseline and resuming from its first page's `resumeCursor`, rather than pretending the missing activity was delivered.

The application provides one process-local stream cursor and a stream-incarnation identifier. The first active-execution page captures a registry high-water mark and includes a `resumeCursor` observed near baseline collection plus an `observedAt` time. Go begins or resumes the upstream stream after that cursor while traversing remaining high-water-bound pages. New executions arrive through SSE rather than shifting later pages; executions that complete during traversal may disappear before their page is read, with their post-cursor activity supplying the transition when replay remains available. This is a best-effort baseline, not an atomic cut through registry state and the stream. An expired cursor or changed incarnation triggers a fresh paginated baseline.

The Phase 1 stream is a best-effort, eventually consistent developer-monitoring projection rather than a durable or exactly-once feed. The Go host tracks the last successfully received upstream cursor, applies activity in cursor order, and tolerates duplicates. An ordinary disconnect may use bounded replay when available. If replay is unavailable, Go obtains a fresh paginated baseline, resumes after its first page's new `resumeCursor`, and may report that current execution state has been refreshed while some intermediate live activity may be missing. It must not imply that baseline recovery recreated the missing narrative.

Go also refreshes the active baseline at a low frequency while connected so ordinary pagination and stream races heal without production-streaming coordination. Exact refresh timing remains an implementation-planning choice; an initial interval on the order of tens of seconds is sufficient. A live execution may appear slightly late or remain visible briefly after completion, and that transient behavior is acceptable.

The application closes a slow subscriber when its bounded pending-delivery allowance or write deadline is exceeded. Go treats that closure like any other reconnect and uses replay or paginated-baseline recovery. Likewise, bounded fan-out to browser tabs must not let one slow tab delay the upstream connection or another tab; a lagging tab may be disconnected and refreshed from current Go state. Neither Go nor the browser attempts to reconstruct application projector state, and the finalized retained trace remains the detailed debugging fallback.

Phase 1 may reject a new upstream SSE subscription with `LIMIT_EXCEEDED` when its fixed process-wide subscription capacity is occupied. Go preserves that code, keeps its current target scope, and retries with bounded backoff rather than treating the response as target unavailability, authentication failure, or a replay gap. Because Go owns only one upstream subscription, this normally indicates other independent consoles or an incompletely closed prior connection rather than browser-tab pressure.

If the application reports `liveMonitoringAvailable: false`, Go must stop presenting the active baseline or activity as trustworthy current state. A request for either produces `LIVE_MONITORING_UNAVAILABLE`; reconnect, cursor replay, and periodic baseline refresh are not presented as recovery. Skill-catalog, retained-trace-catalog, acquisition, and analysis operations may continue because they do not depend on the application's live in-memory projection. Application restart may be required to make live monitoring available again; Go must not attempt to rebuild application projection state from the activity window or trace files.

The Go target connection manager operates the one upstream SSE connection owned by `TargetContext`, independent of browser-tab presence. It appends received envelopes to the bounded current-scope activity window and performs bounded fan-out to paired browser tabs. It reports handshake identity to `TargetContext` and never commits or rotates identity itself. Browser tabs do not own upstream subscriptions. MCP remains snapshot-oriented: it may query the shared recent-activity window when called but does not subscribe to the upstream stream or receive continuous event injection.

### Collection pagination through Go

Go consumes the Phase 1 keyset-pagination contract for trace, active-execution, and skill-summary collections. It should normally request large pages and must not turn the application defaults into many tiny upstream requests: the starting application and local-browser collection defaults are `1,000` items, the maximum requested size is `5,000`, and one uncompressed JSON response is limited to `16 MiB`. Go preserves `hasMore`, current-scope identity, observation time, and explicit stale-cursor behavior. Its browser DTOs may adapt cursor representation but must not convert keyset traversal into offset pagination or imply a transactional collection snapshot.

The browser network page size is independent of visible row count. It may receive thousands of concise summaries while rendering only a virtualized visible window. Skill YAML content, trace files, execution detail, records, and large payloads remain separate detail, streaming, pagination, or range operations. A target-scope reset invalidates every local continuation and reloads the application as already specified.

Finalized retained trace artifacts should be streamed into the Go host's bounded `bifrost-console-work/transient` workspace rather than buffered entirely in memory. Go shared services, not the application server or browser, own frame, record, search, pagination, timeline, failure, and usage analysis.

Go consumes the raw file incrementally according to the Phase 1 current-release trace diagnostic contract paired with `consoleCompatibilityVersion`. It reconstructs chunked logical payloads while preserving raw payload envelope and `PAYLOAD_CHUNK_APPENDED` records for framework inspection. It must enforce configured total-byte and line-size limits and reject malformed JSON, unknown record types or semantic enum values, malformed or missing required fields, inconsistent trace/session identities, invalid sequence or frame ordering, a missing or non-final canonical `TRACE_COMPLETED`, and missing, duplicate, or mismatched chunks with `INVALID_ARTIFACT`. It must not silently ignore an unknown semantic element, stop at the first bad record, or present the decoded prefix as a complete trace. Parser failure affects that artifact or view rather than terminating the console process; code-specific details may report that raw attachment download remains available.

### Acquired-artifact handles and bounded retention

Go installs a trace into its analysis workspace only after the complete artifact has been downloaded, its operation still belongs to the current `targetScopeId`, and the current trace record, identity, ordering, and chunk contracts have been validated. A target transport interruption produces `TARGET_UNAVAILABLE`, scope rotation produces `TARGET_CHANGED`, invalid content produces `INVALID_ARTIFACT`, and an over-limit acquisition produces `LIMIT_EXCEEDED`; none receives an analysis handle or is presented as a partial valid trace. Caller cancellation ends that request through the adapter's ordinary cancellation path rather than inventing another domain code. Raw attachment download may remain a separate option when the application artifact is still available.

A successfully installed immutable copy receives an opaque `artifactHandle` bound to its `targetScopeId`. Go retains the copy while it is being used, subject to a configured idle timeout and bounded workspace capacity. A successful query or continuation refreshes the idle timeout. Browser and MCP callers do not acquire, release, or manage a separate retention object. Exact idle lifetime, maximum retained-artifact count, per-artifact bytes, and total retained bytes are configuration decisions, but `0` must not mean unlimited.

Once installed, that immutable copy has completed its upstream authorization boundary. A later `BIFROST_API_KEY_REJECTED` does not invalidate its handle or require Go to reauthenticate before each local query. Paired-browser or MCP authentication still applies to every local request, and the copy remains subject to its ordinary handle, scope, capacity, and process-lifetime limits. Reacquisition after handle expiration is a new upstream operation and therefore requires valid application authentication as well as current catalog availability.

Go must not delete an acquired copy during an in-flight query. When its idle timeout expires, its handle expires and the copy becomes eligible for ordinary workspace eviction. When active work and retained copies occupy the configured capacity, Go rejects or delays a new acquisition rather than deleting a copy that an in-flight query is using. `targetScopeId` rotation and console shutdown invalidate all affected handles immediately, cancel in-flight analysis, and clear their files and indexes regardless of idle time. Application-side expiration does not affect a fully acquired Go copy, but it can prevent an expired handle from being reacquired.

Every record-query, search, and payload continuation is opaque and bound to the target scope, artifact handle, query or filter fingerprint, and next canonical record or byte position. A malformed token or reuse after a query or filter change produces `INVALID_CURSOR`; scope rotation produces `TARGET_CHANGED`; and handle removal or idle expiration produces `ARTIFACT_EXPIRED`. Go never silently restarts or applies a continuation to another query, artifact, or target. Tokens need no cryptographic signature because the loopback API is already authenticated and Go may manage the bounded state server-side.

Record pagination alone is insufficient for a reconstructed payload larger than one response. Such a record returns a payload descriptor containing its logical size, content type, inline status, opaque payload reference, and supported range information. Callers retrieve the payload through bounded ranges tied to the same artifact handle. Text delivery must preserve valid UTF-8 boundaries; otherwise the range contract returns encoded bytes rather than malformed partial text.

Search operates over the immutable acquired copy and orders results deterministically by canonical record sequence. Reaching a result, byte, or time bound returns the fully completed results so far plus a continuation from the last fully examined record when progress can continue. Search does not require a database snapshot or durable index version because the artifact handle identifies immutable bytes. Its continuation expires when the handle does.

Go shared trace services provide **complete inspectability while the artifact handle remains valid**, not guaranteed completion of an arbitrarily long traversal. No successfully parsed record or payload is intentionally excluded according to guessed relevance or sensitivity, and bounded pagination plus payload ranges make every item addressable under the configured artifact limits. Completion remains contingent on the handle, target scope, console process, and local resource limits remaining valid long enough. Per-response bounds protect console stability and client interoperability; they are not redaction or authorization rules.

The production console does not depend on filename parsing or shared filesystem paths used by the deprecated proof-of-concept CLI. It uses opaque catalog identifiers and ordinary HTTP response metadata. No manifest parser, archive reader, digest verifier, application-level decompressor, or second logical-trace wire representation is required initially.

## Multiple consoles observing one application

The expected initial topology is one developer console observing one Bifrost application. Phase 1 should nevertheless allow multiple independent Go console processes to observe the same application.

Each console:

- obtains its own snapshot;
- owns its own SSE subscription and cursor;
- reconnects independently;
- shares no selection or UI state with other consoles; and
- cannot delay or disrupt Bifrost or another console.

Independent consoles may observe the same application, but they cannot share one local work directory. Consoles launched from the same working directory conflict on the exclusive workspace lock; the later process fails clearly and must use a different launch directory or `--work-dir`. This local filesystem restriction does not reduce Phase 1's ability to serve multiple independent observers.

Phase 1's finite fixed process-wide admission limits still apply across those independent observers. If either the open observability-SSE or concurrent trace-download capacity is occupied, a new operation receives the shared `LIMIT_EXCEEDED` result and does not displace an admitted operation.

Phase 2 does not include console discovery, leader election, user presence, shared selections, cross-console synchronization, or per-console durable delivery.

## Initial product areas

### Instance overview

Candidate information includes:

- target name and address;
- the application `consoleCompatibilityVersion`;
- connection, authentication, and transport state;
- number of registered skills;
- number of active executions;
- recent failures still present in current operational state or activity recovery; and
- completion grace TTL, trace-retention policy, and current retained-trace availability.

### Skill catalog

The skill catalog displays the unique registered skill name and normalized skills-root-relative `sourcePath`, such as `incidents/check_dns.yml`. Skill detail displays the unchanged UTF-8 YAML content received from the application. It does not display an effective-definition DTO or add parsed defaults, resolved model connections, provider identifiers, compiled evidence contracts, Java objects, or registration facts.

`sourcePath` is descriptive metadata for display, grouping, and ordering. It is never treated as a filesystem locator, joined to a local path, or accepted as arbitrary file input. Go follows the server-generated link keyed by the registered skill name. Go and the browser may syntax-highlight or search the YAML as text but do not normalize, reserialize, or maintain an authoritative parsed skill model. Recent activity facts remain separate from the YAML content. The same transport-neutral skill file is available to the future MCP adapter.

The UI must not present recent execution failure as proof that the skill itself is unhealthy.

### Active executions

Candidate information includes:

- entry skill;
- session and trace identifiers;
- start time and elapsed time;
- current phase and active skill path;
- execution status;
- invocation and usage counts;
- configured limits; and
- latest concise activity summary.

Opening an execution should show a concise, continuously updating narrative of observable execution behavior. The UI must not claim to display hidden model thought or chain-of-thought.

Detailed frames, records, and payloads are not available for an active execution. They become inspectable after its trace is finalized and can be acquired while the application retains it. A configured `completion-grace-ttl`, initially defaulting to `15m`, tells the core Java trace subsystem to delay a deletion that `NEVER` or successful `ONERROR` would otherwise perform, providing a bounded acquisition window before the normal persistence policy applies. The adapter catalogs the core-issued finalized descriptor but does not own or extend that retention. A TTL of `0` preserves immediate core deletion and provides no grace window.

The UI must keep execution outcome separate from diagnostic artifact availability. It should not invent combined states such as “completed with trace” or expose a `FINALIZING` execution phase. An execution-completion activity reports the ordinary execution outcome plus `applicationTraceAvailability`, optional application expiration, and any concise known unavailability reason. When application availability is `AVAILABLE`, Go may acquire the artifact immediately because Phase 1 has already placed it in the catalog. `UNAVAILABLE` does not change or qualify the execution outcome and says nothing about whether Go already holds an acquired copy.

There is no initial pending-artifact workflow or later trace-available event. If the execution-completion activity is missed under the best-effort live model, ordinary trace-catalog refresh determines whether an artifact is currently available. Once activity and transient Go state are gone, the UI must say that an identifier is currently unavailable unless it has evidence that the artifact specifically expired; it must not infer expiration from a generic not-found response.

If Phase 1 reports `EXECUTION_OBSERVATION_ENDED` with reason `CORE_FINALIZATION_FAILED`, the UI removes the execution from its active view and states only that execution observation ended incompletely and no finalized application trace is available. It must not render the event as an execution success or execution failure unless a separate reliably established outcome is present, and it must not fabricate a retry, finalizing, or artifact-recovery workflow. The detailed finalization cause remains in application diagnostics. Failure of Phase 1 to publish this exceptional terminal event makes live monitoring unavailable under the existing fail-closed rule rather than allowing another execution to disappear silently from a supposedly trustworthy view.

Once Go has acquired a finalized trace, its bounded transient workspace may continue serving that copy after the application artifact expires while its artifact handle, current console process, and `targetScopeId` remain valid. The copy remains non-authoritative and becomes eligible for eviction after its idle timeout; it is also removed on target-scope rotation or console shutdown. A console restart cleans rather than adopts the prior process's copies before serving diagnostic operations. Responses report application trace availability separately and include an `artifactHandle` only when Go holds a usable acquired copy. The application adapter stops admitting new downloads at the core-calculated expiration; a download opened before expiration may finish even though core deletion becomes due.

The same acquired copy may remain locally inspectable after the application rejects the currently held upstream credential. The UI must show the selected target's authentication-required status and retain the evidence's original observation facts; it must not suggest that successful cached inspection proves the application is still reachable or authorized. Replacing the application credential rotates target scope and removes the old copy under the ordinary lifecycle rule.

Application trace availability is limited to the selected application's current process-incarnation catalog. After an application restart, the console must not expect the application adapter to scan the core trace location or rediscover files left by an earlier crashed or stopped process. Such files remain core-owned but are intentionally invisible to the protocol. When authoritative revalidation observes the new process incarnation, `TargetContext` rotates `targetScopeId` under its ownership rules and the trace service deletes Go copies and indexes from the prior process, so Go does not provide cross-restart trace recovery or history.

### Trace explorer

The detailed developer-audit experience is expected to include:

- application-side retained-trace discovery plus Go console-side filtering and analysis;
- execution summary;
- hierarchical frame and skill tree;
- timeline or waterfall;
- plan creation and evolution;
- model and tool transitions;
- validation, evidence, quota, guardrail, and failure outcomes;
- usage attribution by skill path;
- search and failure-focused navigation;
- raw record inspection for framework developers;
- current trace artifact download under the exact umbrella compatibility match; and
- deep links to a trace, frame, record, or failure.

The Phase 2 UI should consume server DTOs and opaque identifiers rather than filesystem paths or internal Java trace types. A path encountered inside an explicitly requested raw trace record is diagnostic text only and must not become navigation, lookup, or resource-addressing state.

Deep links are navigation conveniences within the current `targetScopeId`, not durable bookmarks. They carry or otherwise retain the scope that produced them so the browser can recognize an old link without reinterpreting its trace ID, frame ID, or record sequence against the current target. Any `TargetContext` scope rotation or Go console restart makes the link stale. Direct navigation to a stale link resets to the console root and reports that the previous target context is no longer available; the console does not preserve old data solely to keep the link working.

## Initial scope decisions

The first console supports:

- one personal developer;
- one console process;
- one selected Bifrost target at a time;
- one embedded browser application;
- REST snapshot and trace access;
- relayed SSE live activity;
- HTTP or HTTPS Bifrost targets, according to host policy;
- application authentication material retained in Go process memory; and
- multiple independent console processes observing the same application.

## Explicit non-goals

Phase 2 initially excludes:

- shared team-service deployment;
- a remotely accessible Go listener;
- HTTPS or Let's Encrypt integration for the Go listener;
- user accounts, OIDC, or a console-owned role system;
- multi-user SSE fan-out as a product workflow;
- multiple simultaneous target instances or fleet aggregation;
- a database or durable console cache;
- a complete Go-materialized mirror of application runtime state or browser presentation state;
- direct browser-to-Bifrost connections;
- compatibility with, migration support for, or continued distribution of the deprecated `bifrost-cli` after the console implementation is complete;
- editing skills or application configuration;
- starting, cancelling, retrying, or mutating executions;
- compliance-grade audit features;
- secret scanning, automatic redaction, per-query disclosure confirmation, disclosure tiers, data-loss prevention, provider detection, or a generalized content-classification system;
- durable, lossless, or exactly-once live activity history;
- retroactive invalidation of complete current-scope evidence solely because the application rejects a later upstream request; and
- exposure of hidden model reasoning.

## Architecture invariants

The following should remain true throughout UI design and implementation:

1. The browser never depends directly on Bifrost application endpoints.
2. The REST/SSE console protocol remains explicit and independent of internal Java types. The separate NDJSON trace boundary follows its concise Java-owned current-release diagnostic-artifact contract.
3. The Go host remains operationally independent from the observed Java application.
4. The Go plaintext listener remains restricted to loopback.
5. Target transport and authorization policy remain owned by the host application.
6. Credentials do not enter browser storage, URLs, ordinary configuration, or logs.
7. The console does not become a second authoritative store for executions or traces.
8. Live delivery failure does not erase the ability to refresh best-effort current operational state through snapshots and cursors; missing intermediate activity need not be reconstructed.
9. The product remains observational and read-only initially.
10. UI terminology describes observable activity rather than model thinking.
11. Browser handlers adapt transport-neutral Go services; they do not own runtime semantics needed by MCP.
12. Every target-specific response and event follows the authoritative `TargetContext` scope snapshot; stale scopes are discarded, and browser tabs reset after `TargetContext` rotation.
13. Bifrost activity and Go-owned connection events remain in distinct namespaces.
14. Browser and MCP adapters preserve the same transport-neutral Go domain-error code for the same shared-service failure; adapter authentication and protocol failures remain separate.
15. Application authentication is enforced for each new upstream acquisition, while complete evidence already admitted into Go remains governed by local authentication and its normal scope, handle, capacity, and process lifecycle. Upstream rejection does not silently purge it or permit it to masquerade as current state.
16. Static YAML remains restart-only and contains no MCP enablement or bearer secret. Phase 3's canonical sibling key file is the sole persistent MCP enabled state, and one credential store owns its atomic mutation plus the separate process-local authentication generation.
17. The production browser assets and Go browser API ship atomically in one executable. The initial product has no browser/API version negotiation, service-worker-preserved application, or browser failure that is reported as Java target incompatibility.

## Browser and network safety requirements

Observability data is not trusted presentation content merely because it came from Bifrost. Skill descriptions, model output, tool results, errors, trace payloads, routes, and metadata may contain attacker-controlled or malformed text.

The implementation and testing plans must preserve these requirements:

1. Render diagnostic strings as text by default; never inject trace or model content as HTML.
2. Do not render diagnostic content as Markdown or embedded HTML in the initial release; richer content handling is a future feature.
3. Establish a restrictive Content Security Policy suitable for the embedded SPA.
4. Prevent framing and MIME sniffing and use an appropriately restrictive referrer policy.
5. Do not construct executable links or resource URLs directly from trace payloads.
6. Treat downloaded traces as attachments with safe filenames and explicit content types.
7. Bound JSON/SSE message sizes, decompression, nesting, list rendering, and retained in-memory event state.
8. Ensure malformed or oversized records fail one view or request safely rather than destabilizing the console process.
9. Never log credentials, pairing secrets, authorization headers, raw sensitive payloads, or URLs containing user information.
10. Keep browser pairing, host/origin validation, and loopback binding independent; none substitutes for the others.
11. Require a session-bound CSRF header, in addition to pairing and Host/Origin validation, for target or credential changes, MCP enablement or disablement, MCP-key reveal or regeneration, and other sensitive or state-changing local operations.
12. Keep the CSRF token in browser memory only and apply `Cache-Control: no-store` to all authenticated diagnostic and credential-management responses.
13. Keep browser and MCP route authentication and request validation fail-closed and non-interchangeable even though both use the same listener.

The target URL is also a network authority boundary. The console should accept only supported `http` and `https` schemes, reject embedded URL credentials, disable upstream redirects initially, and avoid allowing an unpaired browser request to turn the Go process into a general-purpose network proxy. Target authentication material must never be forwarded to a different origin. The user may intentionally choose an internal or unencrypted target, but that choice must come through the paired console workflow. HTTP is an accepted target transport rather than an override requiring repeated confirmation; the UI must keep its unencrypted state visible and explain the exposure precisely.

The configured application target may be loopback, local-network, or remote and may use HTTP or HTTPS according to the application owner's transport policy. The loopback-only restriction applies to browser and MCP access to the Go console, not to the Go console's upstream target.

The initial threat model protects the Go listener from unauthenticated local or remote callers and malicious web origins through loopback binding, browser pairing, host/origin validation, and the MCP key. Bifrost endpoints separately require application authentication. Compromise of the developer's operating-system account or a process running as that account is outside the initial scope. Diagnostic strings remain untrusted presentation content; comprehensive disclosure controls, redaction, and sanitization are deferred to a future undetermined feature.

## Resource and lifecycle expectations

The console must remain bounded and disposable:

- no unbounded event queues, trace workspaces or indexes, goroutines, browser sessions, or upstream connections;
- acquired artifacts, retained bytes, payload ranges, continuations, and search work are bounded, and an acquired copy is never deleted during an in-flight query merely to admit new workspace work;
- cancellation and timeouts propagate when the browser disconnects or `targetScopeId` rotates;
- `TargetContext` rotation cancels operations from the prior scope, closes obsolete SSE streams, and clears all application-derived operational and transient trace state as defined by the authoritative ownership section;
- the console uses only its verified, exclusively locked `bifrost-console-work/transient` subtree for disk-backed analysis, cleans it before serving each process, and never adopts prior-process cache entries;
- console shutdown closes listeners, upstream responses, and streams cleanly and removes current transient workspace content best-effort;
- browser refresh and temporary disconnect behavior are explicit rather than accidental;
- large traces do not require loading the entire trace into Go or browser memory; and
- errors in one browser session or target response do not terminate the console host.

These console-local bounds and Phase 1's two fixed long-lived-operation admission limits are not a claim of comprehensive aggregate resource-exhaustion protection. General request-rate limiting, authenticated-client fairness, adaptive budgets, bandwidth governance, and coordinated capacity management across the application, Go console, browser, and MCP clients remain outside the initial personal developer product.

Implementation planning should set concrete limits and test them rather than leave “bounded” qualitative.

## Still-open Phase 2 product decisions for a future clean context

The cross-phase ownership and lifecycle decisions above are settled. A future clean Phase 2 design context should work through the following product decisions before producing an implementation plan.

### Information architecture

- What is the primary landing view?
- What global navigation best connects Overview, Skills, Live Executions, and Traces?
- Should active executions remain visible globally while inspecting another area?
- What information deserves persistent screen space versus drill-down detail?

### Core developer workflows

- What are the fastest paths for understanding a currently slow execution, a failed execution, an unexpectedly expensive execution, and an unfamiliar skill tree?
- How should the UI transition from a live execution to its retained completed trace?
- What should happen when the persistence policy does not retain that completed trace?

### Live execution presentation

- What activity becomes narrative text, a status update, a tree transition, or hidden detail?
- How should nested skills, planning, model calls, and tool calls appear without overwhelming the developer?
- What update frequency and animation make the system feel alive without creating visual noise?

### Trace explorer organization

- Should the primary detailed view be tree-first, timeline-first, or a synchronized split view?
- How are raw payloads, concise summaries, and framework metadata layered?
- How should very large traces and payloads be paged, searched, and virtualized?
- How should the browser present the failure and usage correlations computed by Go shared services?

### Target connection experience

- How does a developer enter, select, and reconnect to a target?
- Is the non-sensitive target URL persisted, and if so, where?
- How are unavailable targets, incompatible versions, HTTP transport, and certificate failures explained?
- How do the paired-browser application-key entry and protected console prompt coexist without leaving the key in browser state or command-line history?

### Browser pairing details

- Should startup open the browser automatically?
- Should the one-time secret use a URL fragment, manual code, or another exchange that avoids logs and history?
- How are multiple tabs and browser restarts handled within one console process?

### Browser session behavior

- How many paired browser sessions and tabs may coexist?
- What short-lived state is retained while the browser refreshes?
- How are expired browser sessions re-paired without interrupting the upstream SSE connection?

### Frontend foundation

- Which TypeScript framework and component strategy best support the expected trace complexity?
- What browser support baseline is required?
- What accessibility, keyboard navigation, responsive layout, and theme requirements belong in the first release?
- Which visualization libraries, if any, are justified for timelines and execution trees?

### Project placement, build, and distribution

- Does the separate console live as a top-level project in this repository initially or in its own repository?
- Which Go and Node toolchain versions are supported and pinned for reproducible builds?
- Which operating systems and CPU architectures receive initial binaries?
- How are frontend tests, Go tests, embedded-asset verification, and release packaging composed?
- How does development hot reload proxy to the Go host without creating a production direct-browser-to-Bifrost path?

### Protocol tooling

- Is the Phase 1 REST/SSE contract described using OpenAPI plus an explicit SSE schema, another interface description, or reviewed handwritten DTOs?
- Which types are generated for Go and TypeScript, and which remain deliberately hand-authored view models?
- How are exact-version compatibility, protocol fixtures, malformed events, and version mismatches tested across the Java, Go, and browser boundaries?

### Transient trace workspace limits

- What byte, trace-count, and idle-time limits bound the transient workspace?
- What eviction policy is used when those limits are reached?
- How does the UI explain that a trace is available only from the console cache after the application copy expires?

## Recommended next planning sequence

Continue Phase 2 design in this order:

1. define the primary developer workflows and landing-page information hierarchy;
2. sketch navigation and relationships among Skills, Live Executions, and Traces;
3. design the live-to-completed execution transition;
4. choose the trace explorer's primary mental model;
5. settle target connection and browser pairing workflows;
6. decide browser-session and transient-workspace behavior; and
7. choose the frontend framework and libraries after the interaction requirements are clear.

This order keeps technology selection subordinate to the developer experience the console must provide.

## Handoff to future implementation planning

Do not begin Phase 2 integration by reverse-engineering controllers. A future implementation-planning context should first revalidate this document, then require the Phase 1 external contracts, concise Java-owned current trace contract, Java-produced NDJSON golden fixtures, cursor behavior, and exact `consoleCompatibilityVersion` policy as explicit inputs. The fixtures must assert expected logical reconstruction, hierarchy, timing, usage attribution, failure attribution, validation/retry outcomes, and invalid-artifact results rather than prove only that Go can parse the JSON. Go shared services deliberately own strict parsing, chunk reconstruction, and derived analysis for the trace contract paired with that umbrella version.

UI work may proceed against reviewed protocol fixtures or a mock Phase 1 server, but final integration must use the supported Spring web adapter. The mock must model reconnect gaps, trace expiration, failures in nested skill frames, large payloads, exact-version rejection, and unavailable targets rather than only the happy path.

The implementation plan should separate at least these workstreams even if one team delivers them sequentially:

1. project/build/release foundation;
2. Go listener, pairing, target client, and protocol compatibility;
3. REST/SSE relay and bounded lifecycle management;
4. browser application shell and state model;
5. skills and instance experiences;
6. live execution experience;
7. trace explorer and large-trace behavior;
8. content/network security hardening; and
9. cross-boundary contract, failure, and end-to-end testing.

The initial release should be judged by whether a developer can understand a real Bifrost execution safely and reliably, not by how many framework, visualization, or deployment options it contains.
