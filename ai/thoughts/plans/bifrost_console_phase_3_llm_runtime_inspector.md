# Bifrost Console — Phase 3 LLM Runtime Inspector

## Status

Initial product and architecture direction. This document records decisions established during early planning and preserves the context required for later implementation planning. It is not yet an implementation plan.

The MCP specification and client ecosystem are evolving. Implementation planning must revalidate the current stable specification, representative client support, and available Go SDKs rather than assume the planning-time ecosystem remains unchanged.

## Related designs

Phase 3 depends on:

- [Bifrost Console — Phase 1 Observability Foundation](./bifrost_console_phase_1_observability_foundation.md) for canonical runtime publication, concise activity projection, active-execution state, trace access, and the Spring web adapter; and
- [Bifrost Console — Phase 2 Personal UI Console](./bifrost_console_phase_2_ui_console.md) for the standalone Go console host, selected-target connection, credential handling, protocol compatibility, and transport-neutral runtime query services.

Changes to the application-adapter REST/SSE boundary also follow [Bifrost Console Compatibility Contract](../bifrost-console-compatibility.md).

Phase 3 adds an LLM-facing adapter and portable debugging procedure. It must not introduce another observability source of truth.

## Product objective

Allow a developer's IDE-based LLM to inspect a running Bifrost application and help diagnose active or completed executions using authoritative runtime evidence.

Phase 3 should answer questions such as:

- What is running now?
- Why did this execution fail?
- Which skill and execution phase failed?
- Which skill path led to the failure?
- Where did time or usage accumulate?
- Why did Bifrost retry or reject an output?
- What plan, tool, validation, evidence, quota, or guardrail behavior affected the result?
- How does the runtime behavior relate to the YAML skills and Java code in the developer's workspace?

The result should be an evidence-backed developer explanation, not a generic summary of raw NDJSON.

## Product model: LLM, skill, and MCP

Phase 3 ships as a matched pair:

1. a read-only MCP runtime inspector in the Go console; and
2. a portable `bifrost-runtime-debugging` Agent Skill.

The responsibilities are:

- **LLM:** performs contextual reasoning and relates runtime evidence to repository code.
- **Agent Skill:** supplies the investigation playbook, Bifrost mental model, evidence rules, and answer format.
- **MCP server:** supplies authenticated, structured, bounded runtime evidence.

The skill is procedural guidance, not a security boundary or authoritative computation engine. Deterministic facts and calculations belong in Bifrost or the Go console runtime services.

Phase 3 assumes a capable frontier-class tool-using IDE model. The server should provide correct structure, identifiers, calculations, schemas, evidence relationships, bounded retrieval, and useful errors rather than attempting to reproduce model reasoning in deterministic console logic. The design is based on capability, not on any particular model vendor or version.

## Chosen architecture

```text
developer question
    -> IDE-based LLM
        -> bifrost-runtime-debugging skill
        -> local MCP adapter in Go console
            -> transport-neutral console runtime services
                -> selected Bifrost target through Phase 1 REST/SSE
```

The browser adapter and MCP adapter are peers over the same transport-neutral Go query services, while browser live delivery remains a separate adaptation of the single upstream SSE connection:

```text
Bifrost target client
    -> runtime status service
    -> skill query service
    -> execution query service
    -> trace query service
    -> recent activity query service
        -> browser API adapter
        -> MCP adapter

Bifrost SSE connection
    -> bounded recent-activity window
        -> browser live relay
        -> recent activity query service
```

The exact service names are illustrative. The boundary is mandatory: browser handlers must not own the runtime semantics that MCP later needs.

The browser's in-memory live view is not a Go or MCP source of truth. MCP calls transport-neutral Go runtime, trace, and recent-activity query services directly and remains snapshot-oriented. Go retains one bounded current-scope recent-activity window for browser relay and reconnect plus bounded on-demand queries from either adapter; it does not materialize durable activity history or a complete duplicate of application runtime state. Target scope, connection state, compatibility, credentials, and deterministic trace calculations remain below both adapters; browser navigation and live presentation state remain browser-owned.

## Why MCP belongs in the Go console

The Go console already:

- knows the selected Bifrost target;
- holds the selected target's application authentication material;
- handles target HTTP/HTTPS configuration and certificate trust;
- understands the supported observability protocol;
- owns compatibility negotiation;
- maintains connection and cursor state; and
- provides the same runtime evidence to the browser.

Placing MCP in the Go console avoids:

- adding MCP libraries and lifecycle to every Java application;
- exposing a second protocol directly from the Bifrost framework;
- duplicating target authentication and trace-query behavior;
- coupling IDE integrations to Java internals;
- allowing browser and LLM explanations to disagree because they use different evidence models; and
- reintroducing remote topology concerns that the console host already mediates.

The Phase 1 Spring adapter remains the authoritative external observability interface. MCP is a consumer-facing adaptation of that interface.

## MCP specification baseline

At planning time, the latest stable MCP specification is `2025-11-25`. New implementation work should target the then-current stable Streamable HTTP specification and should not adopt the deprecated legacy HTTP+SSE transport.

Useful specification references at planning time:

- [MCP transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
- [MCP resources](https://modelcontextprotocol.io/specification/2025-11-25/server/resources)
- [MCP tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)
- [MCP authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)

## MCP transport direction

### Primary transport: Streamable HTTP

The intended primary endpoint is served from the existing loopback-only Go listener, conceptually:

```text
http://127.0.0.1:<console-port>/mcp
```

This allows the MCP client to use the console's current selected target, in-memory target credential, connection state, and protocol client.

The MCP endpoint must:

- be disabled by default until deliberately enabled through the paired browser workflow;
- remain bound to the same loopback-only listener policy as the browser console;
- validate `Origin` according to the applicable MCP specification while accommodating legitimate non-browser clients;
- require a distinct high-entropy local MCP credential;
- never accept the upstream Bifrost credential as its own credential;
- never disclose either credential through MCP results, resources, errors, or logs;
- apply request deadlines, rate limits, concurrency limits, and output-size limits;
- close transport sessions and derived session state when the console exits while leaving the canonical MCP access-key file in place when that file says MCP is enabled; and
- remain unavailable remotely while the console listener is HTTP-only.

Sharing a listener does not create a shared authentication realm. The router must identify MCP routes before applying authentication and must use a fail-closed MCP-specific validation chain. MCP routes accept only the MCP bearer key; they do not accept the browser session cookie, pairing secret, CSRF token, or upstream application key. Conversely, browser routes do not accept the MCP key and retain their stricter browser-specific `Host`, same-origin `Origin`, session, and CSRF requirements. MCP handling for legitimate clients that omit browser-only headers must not become a permissive listener-wide policy. Shared low-level utilities are acceptable, but credential acceptance and request-validation policy remain route-scoped.

### Future transport

Stdio is a possible future compatibility feature and is not an implementation action or release requirement in this design. The initial Phase 3 transport is authenticated Streamable HTTP on the loopback-only Go listener. A future design must define stdio ownership and lifecycle before adding it.

## Local MCP authentication

The MCP credential is separate from:

- the browser pairing secret;
- the browser session cookie; and
- the upstream Bifrost application credential.

The upstream credential is the application observability access key entered into the paired Go console and retained in Go process memory. MCP clients never receive or present that upstream key. They authenticate only to the local Go endpoint with the separate MCP access key, and Go uses its current selected-target credential when serving an authorized runtime query. This separation permits either key to rotate without granting MCP configuration direct access to the application.

Upstream application authorization is checked when Go acquires evidence from the application; it is not rechecked for every query over a complete Go-acquired copy. If the application later rejects the current upstream key, MCP operations requiring new runtime state, catalog access, or artifact acquisition return `TARGET_AUTHENTICATION_REQUIRED`. Valid handle-based inspection of evidence already acquired into the current target scope may continue under MCP authentication until ordinary handle eviction, target-scope rotation, or console shutdown. The result must retain its original observation facts and must not imply that the target is currently authenticated or reachable.

MCP is independently opt-in. Its persistent state has one source of truth: the canonical protected `mcp-access-key` file beside the resolved static YAML configuration. A present, valid, safely owned regular file means MCP is enabled and its contents are the accepted bearer key. An absent file means MCP is disabled. There is no separate persisted `enabled` flag, state file, YAML field, or database record that can disagree with the credential.

State ownership is deliberately divided:

| State | Sole owner and representation | Mutation behavior |
|---|---|---|
| Restart-only MCP operational settings | Versioned static `config.yaml` `mcp` section | Read at startup; changes require restart. |
| Persistent MCP enablement | Presence or absence of the canonical `mcp-access-key` file | Changed immediately only by paired credential-management operations. |
| Persistent MCP bearer credential | Contents of the canonical `mcp-access-key` file | Created or atomically replaced by the credential store; never stored in YAML. |
| Current MCP authentication snapshot and generation | Process-local MCP credential store | Rebuilt at startup; changed immediately on enablement, regeneration, or disablement. |
| Upstream application credential | Phase 2 in-memory target credential provider | Never persisted and always absent again after console restart. |

One Go MCP credential-store component exclusively owns the canonical key path, file validation, persistent mutation, in-memory key, enabled state, mutation serialization, and authentication generation. Browser handlers request enable, reveal, regenerate, or disable operations through that component. The YAML loader, MCP transport handlers, and browser handlers must not write or delete the file independently. There is no separate “invalidate but remain enabled without a key” operation: regeneration means enabled with a new key, and disablement means no canonical key file and no accepted MCP credential.

The canonical key file contains only the generated high-entropy bearer secret in one strict documented text encoding. It is a regular file, not a symbolic link or Windows reparse point, and uses owner-only permissions or the closest enforceable platform equivalent. The credential store rejects malformed, empty, oversized, unsafe, weakly protected, or unreadable canonical files and fails MCP closed. It does not silently overwrite a questionable file or generate a replacement that would unexpectedly disconnect an IDE. The key is accepted through `Authorization: Bearer <key>` and must never appear in URLs, page source, logs, trace data, generated skill content, ordinary configuration examples, or MCP responses. A paired, CSRF-protected credential-management response may deliberately reveal it and uses `Cache-Control: no-store`.

The paired runtime mutations are serialized by the credential store and commit through the one canonical file:

- **Enable:** generate a key with a cryptographically secure random source, write a protected temporary sibling, durably flush as supported, atomically install it as the previously absent canonical file, then publish the enabled in-memory snapshot. If a canonical file already exists, enable does not replace it.
- **Regenerate:** generate and durably write a protected temporary sibling, atomically replace the canonical file, then publish the new in-memory key, advance the authentication generation, and disconnect prior-generation clients.
- **Disable:** while preventing new MCP authentication admission, delete the canonical file, then publish disabled state, advance the authentication generation, and disconnect prior-generation clients. If deletion fails, the operation fails and MCP remains enabled; the UI must not report successful disablement while the persistent key still exists.

Recognizable temporary sibling files are never authoritative credentials. Startup may remove them best-effort after validating their exact expected location and name, but it never enables MCP from them. The canonical file provides deterministic crash behavior:

| Operation | Crash before canonical filesystem commit | Crash after canonical filesystem commit |
|---|---|---|
| Enable | Disabled on restart. | Enabled with the new key. |
| Regenerate | Enabled with the old key. | Enabled with the new key. |
| Disable | Enabled with the old key. | Disabled on restart. |

The Go host maintains an internal MCP-auth generation distinct from `targetScopeId`. A request authenticates against an immutable credential-store snapshot and captures its generation. Regeneration or disablement advances the generation, closes transport sessions established under an older generation, cancels their in-flight MCP requests, and clears their derived session state. Before emitting a result, the adapter verifies that MCP remains enabled and the request generation is still current; cancellation alone is insufficient because completed work may race with the mutation. An old-generation result is discarded rather than delivered. Enable, regeneration, disablement, and authentication admission must be serialized or use equivalent atomic snapshot semantics so an old key cannot begin a new admitted request across the mutation boundary. These MCP mutations must not cancel unrelated browser work, rotate `targetScopeId`, or change the selected target.

The MCP access key normally survives console restarts so IDE configuration is a one-time operation. At startup, after static configuration is validated and the transient workspace is safely cleaned, the credential store examines only the canonical key file. A valid file enables MCP with a fresh process-local authentication generation; an absent file leaves MCP disabled; an invalid or unsafe file leaves MCP disabled with a clear paired status and diagnostic rather than replacement. Process-local transport sessions and generations never survive restart.

Because the upstream application credential is intentionally process-only, a restarted console can be locally MCP-ready while unable to inspect the selected target. A valid persistent MCP client may authenticate and receive console status equivalent to `console: READY`, `mcp: ENABLED`, and `targetAuthentication: REQUIRED`. MCP initialization succeeds; operations requiring new application access return `TARGET_AUTHENTICATION_REQUIRED` until the developer pairs the browser and supplies the application key again. The IDE's MCP endpoint and bearer key do not change. If no target is selected, status reports that separate fact rather than mislabeling it as application-key rejection.

The enablement and key screen keeps a prominent warning visible before and after enablement. It states that an authenticated MCP client may retrieve registered skill YAML files, runtime activity and summaries, recorded prompts and model requests or responses, tool inputs and outputs, errors, metadata, trace records and payloads, raw trace artifacts, and derived diagnostic results. Bifrost excludes its console authentication secrets but does not scan this recorded application content for embedded credentials, personal data, or other sensitive values. The warning also states that the MCP client may send retrieved content to its configured model provider and that Bifrost does not control that provider's storage, retention, or data handling. Enabling MCP and copying its key acknowledges this local developer-tool trust decision; the initial release does not add a confirmation to every query.

All authenticated MCP HTTP responses carrying diagnostic content use `Cache-Control: no-store`, including diagnostic errors and raw artifacts when exposed through HTTP. This prevents ordinary HTTP caching where clients honor the header; it does not prevent an authorized MCP client or model provider from retaining content after receipt.

Successful MCP access-key authentication is logged without recording the key, upstream application observability key, request payload, or diagnostic content. Per-tool or resource-access logging is not initially required.

OAuth is not required for the initial loopback-only personal product. It should be reconsidered only if remote MCP access becomes a real feature.

## Selected-target semantics

Phase 2 initially supports one selected Bifrost target at a time. Phase 3 follows that model:

- MCP queries operate on the console's current selected target.
- MCP tool calls do not accept arbitrary target URLs.
- An MCP client cannot turn the console into a general-purpose network proxy.
- Target selection, credential context, runtime identity commitment, and scope rotation remain exclusively governed by Phase 2's authoritative [`TargetContext` ownership](./bifrost_console_phase_2_ui_console.md#go-targetcontext-ownership). MCP consumes immutable scope snapshots and never adopts an identity or rotates `targetScopeId` itself.
- Results identify the target instance and observation time so the LLM can detect stale or mismatched evidence.

An application-runtime reset does not rotate the MCP access key or require a new MCP transport session. Existing authenticated clients remain connected to the local console, but in-flight prior-scope runtime requests are cancelled, late results are suppressed, and all subsequent queries observe only the new target scope. MCP provides no cross-restart trace or skill-definition history.

MCP clients do not consistently support resource invalidation, and evidence already returned into an LLM conversation cannot be recalled. The server therefore uses explicit scope rather than pretending to manage client conversation state. Current-runtime discovery calls require no prior scope input, but every target-specific result reports `targetScopeId`, application instance, process incarnation, and observation time. Discovery returns opaque scope-bound entity references for follow-up inspection. Drill-down references, resource URIs, and continuation tokens from an earlier scope produce the shared `TARGET_CHANGED` code after rotation; MCP does not expose a second generic stale-context meaning.

MCP resources are scope-bound, conceptually `bifrost://targets/{targetScopeId}/...`, so a cached resource cannot silently change meaning after a target or console restart. Human-readable session, trace, frame, and record identifiers remain separately present as evidence. The debugging skill instructs the LLM not to combine evidence carrying different target scopes unless it is explicitly comparing them. The design does not promise that an MCP client removes old resources or prior tool results from its own cache or conversation.

Multi-target MCP queries and fleet reasoning are not initial requirements.

## Read-only scope

Phase 3 is strictly observational.

It must not expose MCP tools that:

- invoke Bifrost skills;
- cancel or retry executions;
- edit skill manifests or application configuration;
- change trace retention;
- delete or mutate traces;
- enable or disable observability;
- change the selected target;
- reveal credentials; or
- perform arbitrary network, filesystem, or shell operations.

Any future control-plane capability requires a separate phase, explicit user-confirmation model, authorization design, and threat assessment.

## Debugging scenario catalog

Maintain a separate, reviewable Phase 3 debugging-scenario catalog. Scenarios are long-lived product examples, protocol fixtures, agent evaluations, and acceptance-test sources.

Each scenario should record:

- stable scenario ID and title;
- developer question;
- runtime and skill-topology setup;
- evidence available and unavailable;
- structural facts the server must expose or calculate;
- conclusions supported by the evidence;
- conclusions that would be overclaims;
- identifiers expected in a grounded answer;
- important variants and edge cases; and
- corresponding protocol fixture and eventual automated coverage.

Scenarios must not require an exact MCP call sequence or exact prose answer. The model may use any reasonable combination of general inspection primitives. Evaluation should focus on factual grounding, useful diagnosis, appropriate uncertainty, and whether the MCP surface supplied enough evidence.

Candidate initial scenario families include failure in a nested skill frame, quota termination, exhausted validation retries, an apparently stalled active execution, usage concentrated in nested planning, and a successful execution whose finalized trace expires after its completion grace window.

## Resources and tools strategy

Use MCP resources for identifiable runtime context and a small set of general, composable tools for bounded discovery and inspection.

Scenarios validate that these primitives provide sufficient evidence. They must not become server branches, prescribed tool sequences, or one specialized tool per anticipated debugging question.

The essential debugging workflow should remain possible through tools alone because clients may expose optional MCP capabilities differently. Resources provide useful stable links and browseable context where supported.

The exact surface remains a Phase 3 planning task. Candidate resource templates include:

```text
bifrost://targets/{targetScopeId}/instance/status
bifrost://targets/{targetScopeId}/skills/{skillName}
bifrost://targets/{targetScopeId}/executions/{sessionId}/summary
bifrost://targets/{targetScopeId}/executions/{sessionId}/activity
bifrost://targets/{targetScopeId}/traces/{traceId}/summary
bifrost://targets/{targetScopeId}/traces/{traceId}/frames/{frameId}
bifrost://targets/{targetScopeId}/traces/{traceId}/records/{sequence}
```

Candidate tools include:

- `bifrost_get_runtime`
- `bifrost_list_skills`
- `bifrost_get_skill`
- `bifrost_list_executions`
- `bifrost_get_execution`
- `bifrost_get_execution_activity`
- `bifrost_list_traces`
- `bifrost_get_trace`
- `bifrost_get_trace_frame`
- `bifrost_query_trace_records`

`bifrost_list_skills` returns the registered skill name, normalized skills-root-relative `sourcePath`, and server-generated link for each entry. `bifrost_get_skill` and the corresponding skill resource add the unchanged UTF-8 YAML content supplied by the application. MCP does not replace the YAML with an effective-definition DTO, workspace reconstruction, parsed defaults, runtime-resolved configuration, or sensitivity-filtered representation.

MCP treats `sourcePath` as descriptive source information, not a filesystem locator or tool input. It may return or display the YAML as recorded text but does not normalize, reserialize, or treat a separately parsed model as authoritative. New YAML fields do not change the MCP or Java-to-Go protocol merely because they appear in the transported file.

`bifrost_get_execution_activity` is an on-demand bounded query over Go's current-target recent-activity window, not a subscription or historical trace query. It filters by the scope-bound execution reference, returns complete activity envelopes in application delivery-cursor order, and reports the returned cursor range, observation time, whether more retained events follow, and whether the requested beginning has already left the window. A replay gap or expired beginning is explicit; the tool must not describe a returned suffix as the complete execution narrative. The finalized trace remains the complete detailed source when it becomes available.

The recent-activity service and MCP adapter preserve Phase 1's exceptional `EXECUTION_OBSERVATION_ENDED` activity. When its reason is `CORE_FINALIZATION_FAILED`, MCP may state only that observation ended incompletely and that no finalized application trace is available. It must not infer execution success, execution failure, root cause, or a recoverable pending artifact from this event. Any independently established execution outcome remains a separate fact, and detailed finalization cause remains in application diagnostics rather than MCP content.

`bifrost_query_trace_records` should use structured filters rather than an arbitrary query language. Candidate filters include trace identity, record types, frame IDs, route or skill, sequence range, time range, status or level, text search, explicit payload inclusion, page size, and cursor. Each response remains bounded, but artifact-handle-bound record and payload continuations make every matching item addressable while the artifact handle and target scope remain valid.

Tool names and exact schemas remain provisional. The final surface should prefer a small composable set over overlapping aliases or scenario-specific helpers such as `get_failure_context`.

Every tool should have:

- a precise purpose and description;
- strict JSON Schema input validation;
- bounded pagination or record windows;
- structured output with an output schema where appropriate, plus a concise text fallback for clients that do not present structured content well;
- read-only and non-destructive annotations where the MCP implementation supports them;
- explicit observation timestamps;
- stable Bifrost identifiers;
- truncation and current availability metadata;
- stable shared domain-error codes with safe messages; and
- resource links for progressive drill-down where useful.

The large Phase 1/browser collection pages are an upstream transport optimization, not an MCP result-size promise. Shared Go services may retrieve up to the application collection maximum internally, while MCP list tools apply their smaller MCP-specific item and byte limits before content enters model context. The starting MCP defaults remain `100` records/items and `256 KiB` per result, subject to later client validation; MCP continuations remain scope-bound and must not expose application pagination cursors as interchangeable client tokens.

## MCP mapping of shared service errors

MCP uses Phase 2's [transport-neutral Go service error contract](./bifrost_console_phase_2_ui_console.md#transport-neutral-go-service-error-contract). A valid authenticated tool call that reaches a shared service and fails for a target or diagnostic-domain reason returns a bounded error result containing the same stable `code`, safe `message`, optional `targetScopeId`, and permitted code-specific details. It is marked as an unsuccessful tool result using the selected MCP SDK's standard mechanism. The MCP adapter must not rename a shared code, infer a different cause from message text, or turn the safe message into instructions for the model to execute.

This includes `LIMIT_EXCEEDED` returned by Phase 1 when its fixed SSE-subscription or trace-download admission capacity is occupied and the same code produced by Go-owned bounds. Code-specific details must identify the bounded operation sufficiently to avoid confusing application admission with MCP result-size or concurrency limits, without exposing internal exception or credential data.

MCP transport and protocol errors remain separate. Invalid MCP framing, unsupported methods, malformed tool arguments rejected at the tool-schema boundary, MCP access-key rejection, and disabled-MCP handling use the appropriate MCP or HTTP transport failure. They must not be mislabeled as target authentication, target availability, or Bifrost diagnostic failures. Resource-read failures preserve the shared domain code in the protocol's available error data rather than inventing resource-specific meanings.

The Agent Skill may explain the documented response to a specific code, such as discarding prior-scope evidence after `TARGET_CHANGED`, starting a collection again after `STALE_CURSOR`, or reacquiring after `ARTIFACT_EXPIRED` only when the current catalog still offers the trace. Neither MCP results nor the skill add generic retryability, required-action, restart, configuration, or evidence-availability fields. A replay gap remains a successful bounded activity result with explicit missing-range information, not an unsuccessful tool call.

## Progressive disclosure

Progressive disclosure is an efficiency default, not a restriction on evidence. Initial summary calls should not automatically inject an entire runtime or large trace into LLM context. After Go has successfully acquired and parsed a trace, an authenticated client may deliberately request any record or reconstructed payload while the artifact handle and target scope remain valid. It may select broad ranges, use the largest allowed response window, and follow record or payload continuations. This is complete addressability through a bounded temporary copy, not a promise that an arbitrarily long traversal will finish before the handle, target scope, console process, or evidence availability ends. An implementation may also expose the raw trace artifact as a resource or attachment where client support and response bounds make that useful; it must not require Go to guess which evidence is relevant. Raw records and artifacts may include a filesystem path already recorded by the canonical trace implementation. MCP returns that authenticated raw diagnostic content unchanged under the Phase 1 exception, but does not expose the path as a resource identifier, accept it as tool input, or use it for filesystem access.

The recommended investigation shape is:

1. retrieve runtime or execution status;
2. identify the relevant session or trace;
3. inspect its neutral structural summary;
4. query a specific frame, record type, activity range, or record window; and
5. retrieve detailed payloads only when summarized evidence is insufficient.

This is guidance, not a mandatory call sequence. A capable IDE model may start with raw detail, request a broad range, or attempt to traverse the entire trace when that better fits its question and available context. Go supplies neutral structure and caller-selected filters; it must not silently suppress records or payloads it considers irrelevant.

The default investigation shape reduces latency, repeated transmission, accidental context consumption, and large-trace processing cost. Those benefits remain useful even for models with very large context windows, but they must be balanced by complete caller-directed addressability while the artifact handle remains valid.

Per-response byte, record-count, nesting, and time bounds protect Go stability and MCP-client interoperability. They are not redaction, sanitization, authorization, or data-egress controls. A truncated or partial result says so and provides an artifact-handle-bound continuation path whenever the immutable acquired evidence and query progress remain available. A payload larger than one result is retrieved through bounded byte or UTF-8-safe ranges using an opaque payload reference tied to the same artifact handle. Handle expiration, target change, or console shutdown may end retrieval explicitly rather than imposing an invisible semantic filter.

Every bounded result should indicate:

- whether more data exists;
- whether any data was truncated;
- the cursor, range, or resource needed to continue;
- the current `applicationTraceAvailability` fact;
- the opaque artifact handle when the result depends on a usable Go-acquired copy;
- whether the execution is still changing; and
- the time at which the result was observed.

Execution outcome and `applicationTraceAvailability` remain separate facts in shared Go services and MCP results. MCP must not expose or infer combined lifecycle states such as `COMPLETED_WITH_ARTIFACT` or a pending `FINALIZING` phase. `applicationTraceAvailability` describes only whether the artifact is currently obtainable from the application catalog. The separate presence of an `artifactHandle` means Go currently holds a usable acquired copy. Application unavailability does not alter the execution outcome or invalidate an existing handle, and it must not be described as expiration unless the console retained specific evidence of expiration.

Application authentication state is another separate fact. Under acquisition-time authorization, `TARGET_AUTHENTICATION_REQUIRED` prevents new application acquisition but does not retroactively invalidate a complete current-scope artifact handle or bounded evidence already admitted into Go. MCP must distinguish a successful cached-evidence query from a successful current-runtime query and must not use access to the former as evidence that upstream authentication remains valid. Supplying replacement target credentials rotates target scope through Phase 2 ownership and thereby invalidates the prior handles and evidence normally.

MCP does not add its own acquired-copy retention manager. It exposes the handles, continuations, payload ranges, and explicit stale or expired errors owned by the shared Go trace services. A successful handle-based call refreshes the copy's idle timeout according to those services. MCP cannot guarantee that an LLM calls again before that timeout, and an IDE conversation does not retain a copy merely by remaining open.

The application catalog covers only its current process incarnation. MCP must not search the application's filesystem, expose abandoned process directories, or present crash leftovers as historical traces. Once Phase 2's `TargetContext` authoritatively observes the new process incarnation, it rotates `targetScopeId` and the trace service clears all Go-acquired copies and indexes from the prior process. MCP therefore rejects prior-scope resources as stale rather than offering cross-restart history.

The core Java trace subsystem, not the application adapter or MCP, owns the canonical artifact's location, persistence policy, completion-grace expiration, and physical deletion. The adapter exposes only current-process catalog entries derived from core-issued finalized descriptors. MCP cannot extend application retention, request deletion, submit a filesystem path, scan for leftovers, or reinterpret physical file presence as supported availability. A Go-acquired copy has its separate bounded handle lifecycle and does not transfer ownership of the application artifact.

The shared Go workspace contract also applies across console crashes and restarts. MCP never scans or adopts an earlier Go process's `bifrost-console-work/transient` files. A new console process must acquire the workspace lock and complete safe startup cleanup before enabling MCP diagnostic requests; affected disk-backed operations return `TRACE_ANALYSIS_UNAVAILABLE` rather than exposing leftovers or using a weakly protected directory. MCP may still expose independently safe live or directly proxied capabilities reported by the shared Go services. Evidence already downloaded explicitly by an MCP client is outside console workspace ownership and cannot be recalled or cleaned by Bifrost Console.

## Authoritative runtime computations

The LLM should not be asked to reconstruct facts the server can calculate reliably.

Candidate Go console-derived facts include:

- final execution outcome and recorded error or validation locations;
- hierarchical skill/frame path;
- inclusive and self duration for frames and executions;
- direct and descendant usage totals, including usage precision;
- configured limits and observed values;
- attempts, retries, and validation outcomes;
- current application trace availability; and
- canonical record ordering, indexes, and relevant record sequences.

The Go shared services should not initially decide root cause, likely developer mistake, conceptual plan quality, which code change to make, or a natural-language diagnosis. Those are reasoning tasks for the capable IDE model using the structural evidence and workspace context.

The browser and MCP adapters should consume the same authoritative computations. They may format them differently but must not derive contradictory results independently.

These computations sit on the Phase 1 current-release trace diagnostic contract covered by `consoleCompatibilityVersion`. Java owns record structure and Bifrost-defined meanings; Go owns deterministic calculations and developer-facing views derived from those facts. Phase 3 does not add a second trace parser, reinterpret unknown records, or preserve historical trace formats for MCP. If shared Go trace services reject an artifact because its structure or semantics are invalid, MCP reports that analysis failure explicitly and may link to the unchanged raw artifact when available; it does not return a best-effort semantic prefix.

The Java-produced golden fixtures used at the Java/Go boundary also protect MCP semantics. Their expected hierarchy, timing, usage, failure attribution, and validation/retry outcomes must agree with the shared Go results exposed to both browser and MCP. A Go-only correction or improvement to a derived calculation updates those expected results without changing `consoleCompatibilityVersion` when the Java-owned evidence and meaning remain unchanged; a trace change requiring coordinated Go interpretation changes that umbrella version.

## Runtime-to-workspace correlation

The registered skill YAML file returned by the running application is the application-provided skill source available for comparison. The Agent Skill compares its `sourcePath` and YAML content with candidate YAML and Java code in the IDE workspace. Runtime activity and trace evidence remain authoritative for what the process actually did; a difference between the application-provided YAML and workspace source is development context or a possible change target rather than proof of deployment provenance.

A mapping identifier present in the YAML is a search hint for candidate Java code, not proof that the workspace contains the exact deployed source revision. Phase 3 does not add Git provenance, build attestation, or source mapping. When the application process incarnation changes, Go clears skill-file and trace state from the old process, so MCP cannot associate old evidence with files from the new runtime scope.

## Evidence and uncertainty contract

MCP tool schemas should describe their fields and identify mechanically calculated values where that distinction matters to interpretation. They do not need a universal provenance field or wrapper. The Agent Skill should guide the LLM to reason separately about recorded runtime evidence, documented Bifrost behavior, deterministic calculations, and inference that requires repository or developer context. It should also state when evidence is truncated, currently unavailable, or expired.

Stable identifiers should be included wherever available:

- target scope ID;
- instance identity;
- process-incarnation identity;
- session ID;
- trace ID;
- frame and parent-frame IDs;
- route;
- skill name;
- record sequence or range; and
- observation timestamp.

The skill should instruct the LLM to cite these identifiers in explanations rather than present unsupported certainty.

Within one `targetScopeId`, `sessionId` identifies a currently active execution and `traceId` identifies its finalized retained trace. Live results carry both for correlation. Once the active entry disappears, retrospective MCP lookup uses a scope-bound trace reference containing the human-readable `traceId`; MCP does not treat `sessionId` as a durable execution-history key. Frame IDs and record sequences are meaningful only within the selected trace. These are current-scope lookup rules, not promises of identity continuity across a target, application-runtime, or Go-console reset.

## Real-time MCP behavior

Continuous injection of live execution events into an LLM context is not an initial requirement.

Initial behavior should be snapshot-oriented:

- tools return current state when called;
- an execution-activity query returns a bounded snapshot from Go's current recent-activity window rather than opening a subscription;
- results include observation times and cursors;
- results describe active state and recent activity as best-effort observations rather than atomic or durable runtime records, and preserve known replay-gap or expired-window information;
- an `EXECUTION_OBSERVATION_ENDED` result with reason `CORE_FINALIZATION_FAILED` is treated as a truthful but incomplete terminal observation, not as a canonical completion record or evidence of an execution outcome;
- when the application reports `liveMonitoringAvailable: false`, active-execution and recent-activity tools return `LIVE_MONITORING_UNAVAILABLE` rather than state known to be incomplete or an attempted reconstruction; skill and finalized-trace inspection may continue because they do not depend on the live in-memory projection;
- the LLM may query again when the developer asks for updated state; and
- no tool call remains open indefinitely to stream execution activity into model context.

MCP resource subscriptions may be explored later if representative clients support them consistently and a developer workflow demonstrates value. The Phase 2 browser remains the primary real-time visual experience.

## Portable Agent Skill

Phase 3 includes one initial Agent Skill:

```text
bifrost-runtime-debugging/
├── SKILL.md
└── references/
    ├── runtime-model.md
    ├── debugging-playbooks.md
    ├── mcp-tool-guide.md
    ├── evidence-and-confidence.md
    └── common-failure-patterns.md
```

The canonical package should follow the portable Agent Skills format rather than one IDE's private directory convention. At planning time, the format is documented by the [Open Agent Skills specification](https://openagentskills.dev/docs/specification).

### Initial skill scope

The skill should activate when a developer asks to:

- debug a Bifrost execution or trace;
- inspect active Bifrost runtime status;
- understand a skill path;
- explain failure, latency, usage, retries, or validation behavior; or
- use the Bifrost Console MCP server for developer diagnosis.

Start with one debugging skill. Do not prematurely split monitoring, traces, failures, and usage into separate skills. Skill authoring is a meaningfully different concern and may later become its own skill.

### Skill responsibilities

The skill should teach the agent to:

- confirm that the Bifrost MCP server and selected target are available;
- normally begin with summaries when that is efficient, while recognizing that broad or raw inspection may be appropriate;
- choose subsequent queries based on current evidence without treating progressive disclosure as a mandatory call sequence;
- relate sessions, traces, frames, routes, skills, plans, models, tools, and validation;
- compare runtime facts with YAML skills and Java code available in the IDE workspace;
- distinguish root failures from downstream symptoms;
- stop when evidence is insufficient;
- identify truncation, expiration, or current unavailability;
- state inference and uncertainty explicitly; and
- produce a concise evidence-backed developer explanation.

The skill should remain a flexible investigation contract rather than a scenario decision tree. It should not prescribe one call sequence per failure type, require a fixed number of calls, or demand that every investigation use every answer section.

Recommended general behavior is to identify the relevant runtime entity, start with structural summaries, narrow using frames and record queries, retrieve payload detail only when needed, compare runtime evidence with workspace definitions, and stop when the question is answered or the available evidence cannot support a stronger conclusion.

For a live execution, the skill should state that conclusions are provisional, cite the observation time and latest sequence, and avoid interpreting temporary state as an execution-ending failure. The skill obtains named Bifrost capabilities through the stable MCP bootstrap/status operation. If a required capability is absent, it should identify that exact missing capability and explain that the installed skill and available Bifrost MCP surface do not support the requested workflow together. It may continue without an optional capability when the remaining evidence is sufficient. It must not infer compatibility by probing, guessing, or treating the absence of individual tool names as a Java target mismatch.

### Suggested answer structure

The skill may recommend:

```text
Summary
Likely cause
Runtime evidence
Relevant skill or code locations
What remains uncertain
Recommended next checks
```

The exact format should remain adaptable to the developer's question rather than force every answer into a long report.

### No scripts initially

The initial skill should contain instructions and focused references only. It should not execute scripts, access the network independently, manage credentials, or parse trace files itself. MCP is the supported data-access mechanism.

### Independent usability

The intended combination is MCP plus the skill, but each must degrade sensibly:

- **MCP alone:** usable through clear tool/resource names, schemas, structured results, and errors.
- **Skill alone:** explains Bifrost debugging practice but reports that live runtime inspection requires the MCP connection.
- **MCP plus skill:** complete intended IDE debugging experience.

The MCP server cannot assume every client supports Agent Skills.

## Broad IDE and agent compatibility

Broad compatibility should come from implementing the MCP and Agent Skills standards cleanly, not from embedding IDE-specific behavior in the Go server.

The essential investigation path should use the commonly supported MCP tool subset. Resources, resource links, and other optional capabilities may improve the experience but must remain supplementary. The server should return both structured results and a useful text representation so clients with different presentation capabilities can still expose the evidence.

Phase 3 should test a small representative set of IDE client and agent combinations and publish setup examples for them. Those tests validate the standards implementation; they should not produce separate tool surfaces, skill forks, or runtime plugins unless a demonstrated incompatibility cannot be addressed through the common protocol.

## Skill distribution

The canonical skill should be version controlled and distributed with the console source or release materials.

Possible initial user workflows include:

- documented manual copy or link into a supported IDE skill directory; or
- a console command that prints or exports the canonical skill directory to a developer-chosen destination.

Do not silently modify IDE configuration or install skills automatically. Automatic installation across specific IDEs should be considered only after representative client requirements are understood.

The skill must never contain target credentials, MCP tokens, machine-specific target URLs, or generated trace data.

## MCP prompts

MCP prompts are not the canonical debugging procedure in the initial design.

The portable Agent Skill is preferred because it can be version controlled, reviewed, progressively load references, work across compatible products, and relate MCP evidence to repository code.

If MCP prompts are later useful for clients without skill support, they should derive from or remain deliberately aligned with the canonical skill. They must not become a second divergent debugging playbook.

## Untrusted runtime content

Skill descriptions, model responses, tool outputs, error messages, trace payloads, routes, metadata, and prompts may contain malicious or accidental instructions.

The cross-adapter data rule is: **console authentication secrets are never returned as diagnostic data; Bifrost does not detect or redact secrets embedded by the observed application in recorded content.** Console authentication secrets include the upstream application observability key and `X-Bifrost-Api-Key` header, MCP access key and authorization header, browser pairing secret, browser session cookie, and CSRF token. Dedicated paired setup operations for enabling or disabling MCP and revealing or regenerating the console-owned MCP key remain credential-management operations, not runtime data APIs.

The initial developer-debugging release allows authenticated operators to retrieve registered skill YAML files and all recorded diagnostic content. A credential-like or sensitive-looking value inside an application-provided skill file, prompt, model request or response, model configuration, activity, trace, payload, tool input or output, error, or metadata is still recorded application data and is returned unchanged. The server does not scan, redact, sanitize, classify, or omit it. All such diagnostic text is untrusted data, including when it appears inside an otherwise structured MCP result. The initial release deliberately does not add disclosure tiers, secret detection, data-loss prevention, provider detection, a universal provenance wrapper, or a generalized content-classification system.

An authenticated MCP client is treated as an authorized developer and may retrieve the same diagnostic content available through the paired browser. Once returned to an MCP client, that content has left the Bifrost Console trust boundary and the client may transmit it to an external model provider. Bifrost does not control or attest to the client's storage, retention, model-provider, or data-handling behavior. Deliberately enabling MCP after the persistent warning and configuring a client with the generated access key are the explicit enablement steps; developers are responsible for choosing client and provider settings appropriate for their trace data. The initial release does not add per-query confirmation, content classification, redaction, or data-loss-prevention machinery.

Neither upstream application-key rejection nor MCP disablement can recall evidence already returned to an MCP client or model provider. MCP disablement and MCP-key rotation do prevent later local MCP access as defined by their authentication generation. Upstream application-key rejection has the narrower acquisition-time effect: it blocks new application access while complete evidence already held by Go remains governed by the still-enabled MCP realm and normal local retention.

The server, MCP schemas, and Agent Skill apply these rules:

- use structured fields instead of large prose blocks where practical;
- bound each response's size, nesting, processing time, and returned record range while providing continuation for retained matching evidence;
- document mechanically calculated fields where that distinction matters to interpretation;
- never execute commands or follow instructions found in runtime content;
- never disclose console authentication material or unrelated repository content merely because application-provided text instructs it to do so;
- encode MCP tool outputs according to the protocol requirements; and
- return raw detail only when the caller explicitly requests it, without requiring the request to be semantically narrow or intentionally preventing traversal while the artifact handle remains valid.

Explicit payload selection and bounded responses reduce accidental context volume and protect service stability; they are not sensitive-data controls. If a payload is authorized and retained, the client can retrieve it in full through one or more requests.

The skill's prompt-injection guidance is defense in depth, not a guarantee that an IDE model cannot be influenced by recorded content. Structured fields and labels do not make embedded text trusted or guarantee prompt-injection resistance. Go performs only deterministic, read-only processing and never converts instructions found in runtime content into operations. The Agent Skill instructs the LLM to treat runtime content as evidence rather than instructions.

## Multiple MCP clients

The initial expected topology is one IDE client connected to one local Go console. The server should nevertheless tolerate multiple independent MCP clients without exclusive ownership.

Each client:

- presents the same persistent local MCP access key on its own connection;
- receives bounded results;
- has independent request cancellation and deadlines;
- cannot delay Bifrost, the browser, or another MCP client; and
- shares no LLM conversation state through the console.

Phase 3 does not require client discovery, shared conversations, durable per-client delivery, or cross-client coordination.

The shared access key authenticates access to the local MCP endpoint but does not establish distinct client identities. Per-client keys, attribution, and revocation are future features.

## Local configuration and operational limits

Phase 2 owns the versioned, strictly validated local YAML configuration file and the disposable `bifrost-console-work` lifecycle. Phase 3 extends the static YAML with an `mcp` section containing only non-secret operational settings and places the canonical `mcp-access-key` file beside the resolved configuration file. If `--config` selects another YAML file, its parent directory is also the credential directory for that console profile; MCP credentials are not silently shared with the default profile. Neither persistent file is stored under `bifrost-console-work`, whose `transient` subtree is deleted at console startup.

Static YAML configuration behavior should initially be:

- restart required after YAML changes;
- unknown fields rejected;
- durations and byte sizes expressed with units;
- invalid, nonpositive, or unsafe values rejected clearly;
- operational limits documented and locally overridable within validated ranges; and
- no MCP `enabled` field, bearer key, authentication generation, or mutable credential state in YAML.

Credential-store enable, regenerate, and disable operations are immediate runtime mutations and do not rewrite or reload the YAML. While MCP is enabled, the canonical access-key file is present beside, but not inside, the resolved configuration. It is absent after successful disablement:

```text
<resolved-config-directory>/
    config.yaml
    mcp-access-key
```

The key-file basename is fixed initially rather than configurable. This keeps file presence authoritative and avoids another path whose mutation or aliasing could disagree with enablement. Static MCP limits apply after restart; the currently loaded limits continue unchanged across key enablement, regeneration, and disablement.

Candidate starting defaults for later implementation validation are:

| Setting | Starting default |
|---|---:|
| Maximum MCP clients | 4 |
| Concurrent requests per client | 4 |
| Global concurrent MCP requests | 12 |
| MCP request body | 64 KiB |
| MCP result size | 256 KiB |
| Records per query | 100 |
| Search results per query | 50 |
| General tool timeout | 30 seconds |
| Trace search timeout | 60 seconds |
| Maximum search text | 4 KiB |

These are planning defaults, not final performance claims. Representative traces and scenario tests should tune them. Acquired-copy idle timeout, per-artifact bytes, total retained bytes, retained-artifact count, and payload-range size must also receive finite validated defaults during implementation planning. Do not use `0` to mean unlimited.

The MCP limits, Go workspace bounds, and Phase 1's fixed SSE-subscription and trace-download admission limits protect specific owned resources. They do not provide comprehensive aggregate resource-exhaustion protection across the observed application, shared listener, Go console, browser, MCP clients, model clients, network, or operating system. General rate limiting, per-client fairness or attribution, adaptive admission, and coordinated cross-layer resource budgets are outside the initial release.

## Version and compatibility model

Phase 3 crosses several contracts, but it deliberately does not create one version number for the whole path. The compatibility mechanisms are:

| Boundary | Owner and signal | Compatibility rule | Failure behavior |
|---|---|---|---|
| Java observability adapter → Go console | Java and Go jointly maintain the hard-coded `consoleCompatibilityVersion`; authenticated application instance status reports it. | Exact match. It covers REST, SSE, acquisition, problem meanings, and the raw NDJSON trace contract. | Go reports `INCOMPATIBLE_TARGET` and does not partially use the application observability surface. |
| Go console → embedded browser | The Go build owns one self-consistent executable containing the browser assets and browser API implementation. | Atomic build and distribution, content-addressed assets, a non-stale entry document, and no independently persistent service worker. There is no browser API version or negotiation in the initial product. | A page from an earlier process reloads, bootstraps again, or re-pairs. It is never reported as target incompatibility. |
| Go MCP server → MCP client | The MCP implementation and client use standard MCP initialization, protocol-version negotiation, server identity, and operation discovery. | The negotiated stable MCP protocol governs transport and protocol features. Bifrost adds named semantic capabilities rather than a surface-wide MCP version. | Unsupported protocol is an MCP initialization/transport failure. MCP authentication and framing failures remain adapter failures rather than target failures. |
| Agent Skill → Bifrost MCP capability surface | The skill package owns its package version and declares required and optional named Bifrost capabilities; one stable MCP bootstrap/status operation reports the server's capabilities. | All required capabilities must be advertised. Optional capabilities may be absent. The skill version is distribution metadata, not a runtime gate or compatibility range. | Missing required capabilities produce exact missing-capability guidance; missing optional capabilities reduce behavior; neither becomes `INCOMPATIBLE_TARGET`. |

The Go console and application adapter ship as a coordinated Bifrost release pair with the same product release version. Go nevertheless requires the exact `consoleCompatibilityVersion` match before MCP target operations report readiness so a mixed installation fails safely. The authenticated application instance-status request remains the sole Java-to-Go compatibility probe, and Go reads only its stable top-level compatibility field until a match is established. Phase 1 removes `TraceRecord.schemaVersion`, so raw records contain no independent version property and MCP must not report or imply one.

Phase 3 does not introduce or expose engine, observability-adapter, Go-console-release, trace-schema, trace-container, browser-API, or Bifrost-MCP-surface versions. Those are not diagnostic requirements or compatibility gates. The MCP protocol version is negotiated according to MCP rather than copied into a Bifrost version family. The Agent Skill records its own version so developers can identify and distribute the package, but Go does not compare it and the skill does not use it as a proxy for server compatibility.

A Go console upgrade ends the old process's MCP transports and sessions. Existing IDE configuration may reuse the same loopback endpoint and persistent MCP key, but the client initializes again and rediscovers the new process's protocol operations and named Bifrost capabilities. No cross-process MCP session preserves an earlier tool catalog, while an independently copied older skill remains safe because it checks its required capabilities after reconnection.

### Named Bifrost MCP capabilities

One small bootstrap/status operation is the stable entry point for the Bifrost-specific MCP surface. Its exact wire name is settled with the exact tool/resource design, after which its identity is a compatibility commitment. In addition to console, MCP, selected-target, authentication, and Java compatibility status, it returns a bounded deterministic set of named Bifrost capabilities. Illustrative capability families are runtime-status inspection, skill inspection, active-execution inspection, recent-activity inspection, and retained-trace inspection; the exact initial catalog is chosen with the exact MCP surface rather than inferred from these examples.

A capability denotes stable workflow semantics exposed through one or more tools or resources. It does not merely repeat an individual tool name, and it does not assert that target data is currently obtainable. For example, a console may advertise retained-trace inspection while returning `TARGET_AUTHENTICATION_REQUIRED`, `INCOMPATIBLE_TARGET`, `ARTIFACT_EXPIRED`, or `TRACE_ANALYSIS_UNAVAILABLE` for a particular request. Those are current target or evidence facts, not changes to the Go implementation's MCP capability set.

Capability identifiers use stable names with an explicit semantic generation, for example `bifrost.trace-inspection.v1`. Their governance is:

- evolve tool and resource schemas additively where possible;
- keep the capability name for additive operations, optional fields, presentation improvements, and fixes that restore its documented meaning;
- introduce a new capability generation when an incompatible semantic change would make the prior promise false;
- do not advertise a capability unless all operations and semantics required by that capability are present; and
- treat an advertised capability whose required surface is absent or internally inconsistent as a server conformance defect, not as a target incompatibility or an invitation for the skill to guess another tool name.

The portable skill lists only the capabilities necessary for its essential workflow as required and treats enhancements as optional. These declarations live in the reviewed `SKILL.md` instructions or focused MCP tool guide; the design does not assume that the Agent Skills specification supports a custom machine-readable compatibility field. At the start of runtime inspection the skill calls the bootstrap/status operation, compares the returned capability set with its declarations, and reports exact missing capabilities before attempting a workflow that depends on them. The initial design does not need compatibility ranges, a global MCP surface version, or mappings between skill versions and Bifrost release versions.

The canonical cross-boundary policy and the reason it ends at Java→Go are also recorded in [Bifrost Console Compatibility Contract](../bifrost-console-compatibility.md).

## Phase 2 compatibility requirements

Phase 2 implementation must preserve these future Phase 3 requirements:

1. Runtime query and analysis services remain independent of browser HTTP handlers.
2. Browser DTO formatting does not become the only representation of runtime evidence.
3. The selected-target client and credential provider can be reused by another local adapter.
4. Go shared services provide bounded ranges, keyset pagination with application high-water semantics, opaque identifiers, streamed artifact handling, and a bounded transient trace workspace under the verified, exclusively locked `bifrost-console-work` root; startup cleans rather than adopts prior-process cache entries before browser or MCP diagnostic access.
5. Failure, usage, hierarchy, and current-availability calculations live in Go shared services below the UI and MCP adapters.
6. Browser connection events remain distinguishable from Bifrost activity events.
7. Every reusable target-specific service result carries the console-local opaque `targetScopeId` so browser and MCP adapters can reject stale work.
8. Phase 2's authoritative `TargetContext` boundary alone commits target identity and rotates `targetScopeId`; reusable services consume immutable scope snapshots, reject stale work, and clear their application-derived relay, catalog, execution, and trace state when that boundary rotates.
9. Browser session and CSRF handling remain adapter security concerns and do not become MCP authentication or runtime-service semantics.
10. Browser and MCP routes remain separate fail-closed authentication realms; neither accepts the other's credential or weakens the other's request validation.
11. MCP-key rotation advances a separate internal authentication generation, closes old-generation transport sessions, cancels their requests, and suppresses late results without affecting browser work or target selection.
12. Protocol fixtures are suitable for both browser and MCP adapter tests and verify that the same shared-service failure preserves the same domain code through both adapters.
13. A transport-neutral recent-activity query service exposes bounded current-window snapshots with cursor-range, observation-time, and explicit gap semantics; browser live relay remains separate, and MCP does not own an upstream subscription.
14. Shared services implement acquisition-time application authorization: upstream rejection blocks new application access but does not invalidate complete current-scope evidence already admitted into Go; adapters preserve original observation facts and current authentication status separately.
15. One MCP credential store owns the canonical sibling key file and process-local authentication generation. File presence is the sole persistent enabled state; static YAML contains no mutable MCP credential state; atomic enable, regenerate, and disable operations preserve the documented crash outcomes.
16. One stable MCP bootstrap/status operation exposes named Bifrost capabilities separately from current target, authentication, compatibility, and evidence availability state. The Agent Skill consumes those capabilities rather than inferring compatibility from missing tool names.

These requirements do not justify implementing MCP during Phase 2. They shape internal seams so Phase 3 is additive rather than a rewrite.

## Explicit non-goals

Phase 3 initially excludes:

- direct MCP exposure from the Java Spring adapter;
- remote MCP access;
- OAuth or multi-user MCP identity;
- write or execution-control tools;
- a full-runtime dump tool;
- continuous live event injection into LLM context;
- model sampling or elicitation initiated by the MCP server;
- MCP Apps or an additional MCP-hosted UI;
- a console-owned LLM for automatic trace analysis;
- automatic installation into every supported IDE;
- scripts inside the debugging skill;
- multiple simultaneous Bifrost targets;
- compliance-grade audit behavior;
- secret scanning, automatic redaction, per-query disclosure confirmation, disclosure tiers, data-loss prevention, or model-provider detection;
- retroactive revocation or recall of evidence already acquired by Go or returned to an MCP client solely because the upstream application key is later rejected; and
- cross-version reading of historical trace formats.

## Phase 3 completion criteria

Phase 3 is complete when a representative IDE-based LLM can, through the local Go console:

1. connect through a supported MCP transport and authenticate locally;
2. use the stable bootstrap/status operation to identify named Bifrost capabilities and the selected instance, authentication, and Java compatibility state without conflating them;
3. list and inspect skills;
4. list active executions, retrieve a current execution summary, and query a bounded recent-activity snapshot with explicit gap semantics;
5. discover finalized traces that are currently retained and report when a requested trace is unavailable;
6. retrieve bounded trace, frame, failure, usage, and payload-range evidence with artifact-handle-bound continuation while the acquired copy and target scope remain valid;
7. use progressive disclosure efficiently while allowing deliberate broad, raw, or complete trace inspection;
8. apply the portable debugging skill to produce an evidence-backed diagnosis;
9. cite stable runtime identifiers and state uncertainty accurately;
10. handle malformed, oversized, truncated, incompatible, expired, and unavailable data safely;
11. operate without exposing console authentication material or allowing runtime content to become executable instructions; and
12. preserve the shared Go domain-error meanings without converting replay gaps or adapter failures into misleading target errors; and
13. do all of the above without affecting the browser console, other MCP clients, or the Bifrost execution.

Completion also requires proving that MCP remains usable without the skill, that the skill responds safely when MCP is unavailable or a required capability is absent, that optional capability absence permits reduced behavior, and that a console restart with a valid persistent MCP key but no in-memory application credential accepts local MCP authentication while reporting `TARGET_AUTHENTICATION_REQUIRED` only for operations that need new target access. Tests must distinguish unsupported MCP protocol, missing Bifrost capability, `INCOMPATIBLE_TARGET`, and currently unavailable evidence rather than collapsing them into one compatibility failure.

## Still-open Phase 3 decisions for a future clean context

The cross-phase runtime, trace, authentication, and transport ownership decisions are settled. A future clean Phase 3 design context should resolve the following MCP- and skill-specific decisions before implementation planning. Each item preserves the current starting bias.

### Representative IDE clients

Which IDE agents are used for compatibility and end-to-end testing?

Starting bias: choose a small representative set based on real project users. Verify Agent Skill support, authenticated Streamable HTTP support, structured content handling, resource support, and local bearer-key configuration.

### Go MCP SDK

Which maintained Go SDK or protocol implementation should be used?

Starting bias: evaluate conformance to the then-current stable MCP specification, transport support, cancellation, structured outputs, testability, release cadence, dependency weight, and security posture. Do not choose solely by popularity or avoid an SDK solely to reduce dependencies.

### Exact resources and tools

Which candidate operations are resources, tools, or Go console-derived summary fields?

Starting bias: validate the proposed general tools against the debugging-scenario catalog. Keep the essential workflow tool-first, resources supplementary, the surface small, and every expensive or large query bounded. Add a convenience operation only when repeated evidence shows it is needed and its semantics can be authoritative; do not create one operation per scenario.

### Raw payload access

How should MCP expose detailed trace payloads within the initial all-content-allowed policy?

Starting bias: MCP may expose every developer-audit detail the console UI can access from a finalized trace successfully acquired into the Go transient workspace. Raw records and reconstructed payloads require explicit caller selection so summaries do not include them accidentally, but requests need not be semantically narrow. Per-response truncation, pagination, size, and time limits protect operation rather than disclosure; artifact-handle-bound record and payload-range continuation makes every item addressable while the handle and target scope remain valid, and a suitable client may use broad ranges or a raw-artifact resource. “Full status” means complete inspectability through that bounded temporary copy, not automatic inclusion of every payload in every response or guaranteed completion after evidence becomes unavailable.

### MCP access-key setup experience

What protected-file permissions, browser reveal/copy/regenerate flow, and IDE bearer-header setup examples make the persistent MCP access key safe and convenient across supported platforms?

Starting bias: preserve the settled credential-store contract above while deciding only the remaining presentation and platform-specific setup details. MCP begins disabled because the canonical sibling `mcp-access-key` file is absent. Enable atomically creates that protected file, regeneration atomically replaces it and disconnects prior-generation clients, and disablement deletes it before reporting success. File presence is the only persisted enabled state; YAML contains only restart-time operational settings. The browser may reveal and copy the current key deliberately, regenerate it with a clear warning, or disable MCP immediately. There is no separate invalid-but-enabled state. Never place the key in URLs, logs, traces, page source, skill content, or MCP results.

### Skill installation

Which clients receive documented installation instructions or an export helper first?

Starting bias: publish one canonical portable skill package and support explicit developer-controlled installation. Avoid client-specific forks until incompatibility is demonstrated.

### Go console-derived debugging summaries

What are the exact shared definitions for inclusive and self duration, direct and descendant usage, attempts and retries, final execution outcome, error and validation indexes, and evidence completeness?

Starting bias: implement mechanical facts that must be correct or shared with the UI below both adapters. Keep root-cause judgment, likely developer mistakes, code-change recommendations, contextual repository comparison, and explanatory reasoning in the LLM/skill.

### Scenario catalog location and format

Where does the separately reviewable debugging-scenario catalog live, and how are its entries linked to protocol fixtures and automated evaluations?

Starting bias: keep it version controlled near these design documents with stable scenario IDs and a lightweight human-readable template. Do not encode scenarios as MCP server branches or require exact tool-call sequences or prose.

### Rate and concurrency limits

What bounds apply per client and across all MCP clients?

Starting bias: begin with the proposed defaults in this document, then validate them against representative traces and scenario tests. Keep every operational limit locally overridable within safe validated ranges, reject unlimited sentinels, and fail with actionable bounded errors rather than degrading the Go process.

## Handoff to future implementation planning

A future Phase 3 implementation-planning context should begin by:

1. revalidating the current stable MCP and Agent Skills specifications;
2. selecting representative IDE clients and recording their actual transport/skill capabilities;
3. verifying that Phase 2 exposes transport-neutral runtime services and representative fixtures;
4. creating and reviewing an initial debugging-scenario catalog;
5. validating the proposed general resource/tool surface against those scenarios without deriving scenario-specific tools;
6. defining structured output, evidence, pagination, and size contracts;
7. specifying and threat-modeling the persistent access-key and pairing experience; and
8. validating the skill against both normal and adversarial runtime content.

Do not start by wrapping every Phase 1 endpoint as an MCP tool, creating one tool per scenario, or hard-coding diagnoses in the console. MCP surface design should optimize for safe, efficient developer investigation rather than mirror REST mechanically.

The implementation plan should separate at least these workstreams:

1. MCP transport, lifecycle, access-key management, local configuration, standard protocol negotiation, and Bifrost bootstrap capability reporting;
2. resource/tool schemas and runtime-service adapters;
3. pagination, structured results, evidence links, and error semantics;
4. untrusted-content and resource-limit hardening;
5. portable Agent Skill and reference authoring;
6. skill distribution and client setup documentation;
7. representative IDE compatibility testing; and
8. scenario fixtures, agent evaluations, and cross-phase end-to-end debugging coverage.

Required test scenarios should include:

- startup with no canonical MCP key file leaves MCP disabled;
- successful enablement atomically creates the canonical key file and makes the returned key usable;
- regeneration atomically replaces the key, rejects the old key, advances the authentication generation, disconnects old sessions, and suppresses raced old-generation results;
- disablement removes the canonical key before reporting success, rejects later MCP authentication, and suppresses raced old-generation results;
- simulated interruption before and after each canonical filesystem commit produces the documented old-or-new crash outcome without a conflicting enabled flag;
- a malformed, symlinked or reparse-point, unreadable, oversized, or insufficiently protected canonical key file fails MCP closed without silent replacement;
- recognizable temporary sibling files never enable MCP and may be cleaned only through exact safe-path handling;
- restart with a valid canonical MCP key and no process-local application credential permits MCP initialization and console status but returns `TARGET_AUTHENTICATION_REQUIRED` for new target access until the browser supplies the application key;
- MCP available and selected target healthy;
- skill with all required capabilities, skill missing a required capability, and skill missing only an optional capability;
- a capability advertised with its required operation absent, which fails conformance testing rather than being treated as target incompatibility;
- MCP available but no target selected;
- unsupported MCP protocol negotiation kept distinct from Bifrost capability and target failures;
- incompatible observability protocol;
- target unavailable or authentication rejected;
- active execution with nested skill frames;
- successful retained trace;
- failed retained trace;
- completed execution whose trace has expired;
- replay gap or changing live state;
- the same shared-service failure observed through browser and MCP with the same domain code;
- large and chunked trace payloads;
- truncated evidence;
- malformed MCP input and malformed target data;
- malicious instructions embedded in model/tool/trace text;
- multiple MCP clients and a simultaneous browser client;
- client cancellation and console shutdown; and
- skill installed without MCP and MCP connected without the skill.

The Phase 3 release should be judged by whether an IDE LLM can investigate a real Bifrost execution efficiently, safely, and with traceable evidence—not by the number of MCP primitives or IDE-specific integrations it ships.
