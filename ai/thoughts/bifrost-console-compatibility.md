# Bifrost Console Compatibility Contract

## Purpose

This document defines how Bifrost versions the external protocol between the application-side observability adapter and the standalone Go console. It is the canonical decision aid for research, planning, implementation, testing, and review of changes that may affect that boundary.

The console architecture has three principal layers:

```text
Bifrost application observability adapter
    -> versioned REST/SSE and trace-artifact contract
    -> standalone Go console host
        -> browser adapter
        -> MCP adapter
```

The Java adapter and Go console are separate runtime components but one coordinated release unit. They always receive the same Bifrost product release version and are published together through the Maven release process, including when a release changes only one component's implementation. Internal Java classes, Go view models, browser DTOs, and MCP schemas are not themselves external application protocol contracts. The finalized trace artifact is different: because Go directly consumes it from the application adapter, its shape and semantics are part of this compatibility boundary.

The shared product release version and `consoleCompatibilityVersion` have different purposes. The product version identifies the coordinated release. `consoleCompatibilityVersion` protects the Java-to-Go contract and changes only when that boundary changes. Mixed installations remain possible through configuration or developer error, so the runtime compatibility check remains required even though releases are produced as matched pairs.

This contract deliberately stops at the Java-to-Go boundary. It does not govern the embedded browser API, the Go MCP server's client-facing surface, or the portable Agent Skill. Those downstream seams use simpler mechanisms appropriate to their lifecycle:

| Boundary | Compatibility mechanism |
|---|---|
| Java observability adapter → Go console | Exact `consoleCompatibilityVersion` match. |
| Go console → embedded browser | One atomically built and distributed executable; no separately versioned browser/API contract in the initial product. |
| Go MCP server → MCP client | Standard MCP protocol negotiation and discovery, plus named Bifrost capabilities for the Bifrost-specific semantic surface. |
| Agent Skill → Bifrost MCP surface | Skill-declared required named capabilities, with optional capabilities used only when present. |

These mechanisms must not be collapsed into `consoleCompatibilityVersion` or another product-wide compatibility number. In particular, a missing MCP capability, an unsupported MCP protocol version, or a stale browser page is not evidence that the selected Java target is incompatible.

## `consoleCompatibilityVersion`

`consoleCompatibilityVersion` is the one hard-coded compatibility version embedded in both the application observability adapter and the Go console. It is not user-configurable, is not derived from a Bifrost or console release version, and is not reconstructed from several component-version comparisons.

The initial compatibility policy is exact matching. The authenticated instance-status request is the compatibility probe. Go initially reads only the required top-level `consoleCompatibilityVersion` field, whose name, location, and primitive representation remain stable for this purpose. If it matches, Go may interpret the remainder of instance status and use the other observability operations. If it does not match, Go reports the mismatch and does not attempt partial rendering, live relay, trace acquisition, fallback parsing, or protocol negotiation.

The version is an umbrella for every application-to-console behavior the Go console must understand, including:

- endpoint request and response contracts;
- snapshot and activity envelope semantics;
- field meaning, units, requiredness, and nullability;
- activity kinds and status values when unknown values cannot be handled safely;
- cursor, ordering, replay-gap, and stream-incarnation behavior;
- execution and trace-availability lifecycle semantics; and
- the finalized NDJSON trace record shape, requiredness, vocabulary, ordering, chunk reconstruction, units, and Bifrost-defined meanings;
- application problem-response shape, stable problem-code meanings, and incompatibility behavior on which the console relies.

## When to increment it

Increment `consoleCompatibilityVersion` when an older exactly matched console could reject, misread, silently misinterpret, or present an unsafe or materially incorrect view of changed application-to-console behavior.

Changes that normally require an increment include:

- removing or renaming a field or endpoint;
- changing a field's meaning, units, type, requiredness, or nullability;
- changing snapshot `resumeCursor`, delivery-cursor, ordering, replay-gap, refresh, or incarnation semantics;
- changing execution completion or trace-availability transitions;
- changing the trace artifact in a way that requires a coordinated Go parser or semantic-analysis change;
- changing an enum or activity vocabulary when older clients cannot safely treat the new value as unknown; and
- changing a stable application problem code, its meaning, or authentication/error semantics in a way that requires different Go behavior.

Changes that normally do not require an increment include:

- internal refactoring that preserves observable behavior;
- implementation fixes that restore the already documented contract;
- adding an optional field that existing readers safely ignore;
- adding an endpoint that existing clients do not call; and
- changing internal build or release metadata that is not part of the console contract.

These examples are judgment aids, not a mechanical schema-diff rule. Semantic changes matter even when the serialized shape is unchanged.

## Required development decision

Every ticket or plan that affects the application observability adapter, its protocol DTOs, the Go target client, activity relay semantics, or related contract fixtures must record one explicit decision:

```text
Console compatibility decision: No version increment.
Reason: [Why existing clients preserve the documented meaning.]
```

or:

```text
Console compatibility decision: Increment N -> N+1.
Reason: [What older clients would reject or misinterpret.]
```

The planning or coding agent should make the decision from the documented semantics and actual change rather than incrementing for every edit or preserving the version by default. If implementation reveals a different compatibility impact than the approved plan, that is a plan mismatch and must be surfaced before silently changing the protocol treatment.

When the version changes, Java and Go constants, reviewed protocol fixtures, compatibility rejection tests, and relevant documentation must change atomically. When it does not change, tests must still demonstrate that the affected existing semantics remain compatible.

## Deliberately not versioned separately

The console design does **not** introduce, require, advertise, negotiate, or maintain separate engine, observability-adapter, Go-console-release, or trace-schema compatibility versions. Those values must not be added to instance/status DTOs, trace catalogs, acquisition metadata, UI requirements, or compatibility checks as diagnostic conveniences. They do not affect a console decision and create additional maintenance without improving this current-process developer-tool contract.

As part of Phase 1, remove the existing `TraceRecord.schemaVersion` component, `CURRENT_SCHEMA_VERSION` constant, constructor/reader/writer propagation, and serialized NDJSON property from the Bifrost framework. The finalized trace artifact contains no independent version field. The adapter does not advertise a trace-schema version, Go does not negotiate or select among trace formats, and the design does not maintain historical readers, a schema registry, or a compatibility matrix.

Go implements the trace contract paired with the target's exact `consoleCompatibilityVersion` and rejects structurally or semantically invalid artifacts as invalid data. A downloaded or acquired trace is interpreted only in that matched target context; the raw file alone does not claim cross-release interpretability. Any trace-format or trace-semantic change that requires coordinated Go behavior increments `consoleCompatibilityVersion`.

Java owns the current trace structure and Bifrost-defined meanings, and Java-produced golden fixtures with expected Go semantic results protect that boundary. Fixtures are associated with the umbrella compatibility version by the test suite or fixture set, not by adding a version property to every trace record. Adding another Java-to-Go compatibility or diagnostic version requires a new explicit architectural decision; it is not an implementation detail or a future-review default.

## Downstream compatibility ownership

The Go executable embeds its production browser assets, so the Go host and browser application are one release artifact rather than independently installable peers. The production build must embed a self-consistent asset set, give content-addressed static assets immutable names, prevent the entry document from remaining stale across a console restart, and omit a service worker or other offline cache that can preserve an earlier application independently. Browser authentication and pairing are process-local, so an open page that reaches a restarted console must re-bootstrap or reload rather than continue under an old session. The initial product therefore has no browser API version, browser compatibility handshake, fallback DTO interpretation, or browser-to-Go compatibility matrix. Independently deployed browser assets would be an architectural change requiring an explicit new compatibility decision.

The Go MCP server uses the standard MCP initialization and protocol-version negotiation required by the selected stable MCP specification. MCP's ordinary tool and resource discovery describes concrete operations. Bifrost adds no surface-wide MCP semantic version. Instead, one small stable bootstrap/status operation reports a bounded set of named Bifrost MCP capabilities. A capability name denotes stable workflow semantics supplied by the MCP surface, not merely the presence of one tool and not the current availability of target data. The exact bootstrap wire name and initial capability catalog are settled with the exact MCP surface, after which the bootstrap identity and published capability meanings are compatibility commitments.

Replacing or upgrading the Go executable ends its process-local MCP transports and sessions. An IDE configuration may retain the same loopback endpoint and persistent MCP key, but the client must initialize again and rediscover the new process's protocol surface and Bifrost capabilities. The console does not preserve an earlier tool catalog inside a surviving cross-process MCP session.

Capability changes follow these rules:

- additive tools, resources, optional fields, or presentation improvements do not require a new capability when the existing capability's promised semantics remain true;
- a compatible implementation correction retains the capability name;
- an incompatible change to promised semantics introduces a new capability name, normally with a new suffix such as `.v2`, rather than incrementing one global surface version;
- an implementation must not advertise a capability unless its required operations and semantics are present; and
- current target state—no selected target, missing target credential, `INCOMPATIBLE_TARGET`, unavailable live monitoring, expired evidence, or another domain failure—does not remove an implementation capability and must be reported separately through the existing status or domain-error contracts.

The portable Agent Skill records its own package version for distribution and review, but that version is not a runtime gate. The skill declares in its reviewed instructions or focused MCP guide—not through an assumed nonstandard Agent Skills manifest extension—the named Bifrost capabilities required for its essential workflow and any optional capabilities that improve an investigation. It first uses the stable bootstrap/status operation to inspect those declarations. Missing required capabilities produce explicit skill-to-MCP missing-capability guidance; missing optional capabilities produce reduced behavior. The skill must not infer compatibility by probing or guessing individual tool names.

Failure ownership remains boundary-specific:

- `INCOMPATIBLE_TARGET` means only that the selected Java adapter and Go console failed the exact `consoleCompatibilityVersion` check;
- unsupported MCP protocol negotiation is an MCP initialization or transport failure;
- an absent required Bifrost capability is a skill-to-MCP compatibility limitation;
- an advertised capability whose required surface is internally absent is a server conformance defect; and
- target authentication, availability, evidence lifecycle, and other runtime conditions retain their existing Go domain meanings even when they prevent a capability from completing a particular request.
