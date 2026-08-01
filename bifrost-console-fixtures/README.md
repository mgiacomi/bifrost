# Bifrost Console Trace Fixtures

This directory is the current-release Java-to-Go semantic contract for execution traces. The trace format itself is an ephemeral diagnostic format: these fixtures describe the current checkout and do not promise that older trace files remain readable.

`traces/` contains sixteen valid traces and twenty deliberately invalid artifacts. `expected/` contains only semantic results needed by future Console analysis: identity, outcome, terminal failure, physical attempts and retry usage, usage completeness, validation-to-attempt links, root/frame hierarchy facts, inclusive/self duration availability, direct/descendant/inclusive and unframed attributed usage, terminal usage, the derived unattributed remainder, payload descriptors, gaps, uncertainties, or one invalidity category. The corpus includes nested and repeated frames, incomplete and overlapping duration cases, chunked text and JSON payloads, independently reported component totals, and minimal chunk/frame/failure/attempt/usage/structural-limit mutations. It intentionally contains no UI model, MCP model, or diagnosis.

The Java test generates valid cases through `DefaultExecutionTraceHandle`; invalid cases are minimal named mutations. Normal tests generate into a temporary directory and byte-compare the complete inventory:

```text
mvn -pl bifrost-spring-boot-starter -Dtest=ConsoleTraceFixtureCorpusTest test
```

Regenerate intentionally with:

```text
mvn -pl bifrost-spring-boot-starter -Dtest=ConsoleTraceFixtureCorpusTest -Dbifrost.console.fixtures.regenerate=true test
```

Run regeneration twice and require the second run to produce no diff. PR 06 will stream this same corpus as artifacts, and PR 13 will consume these expected results from Go; neither should copy it elsewhere.

`application-rest/` contains deterministic REST and problem bodies produced by
Java. `application-sse/` contains complete handshake, activity, failure, and
replay frames produced by the application stream framer.

The SSE activity endpoint (`/_bifrost/observability/v1/activity`) streams
`text/event-stream` frames with two event types:

- **handshake**: emitted once on connection open, contains `instanceId`,
  `observedAt`, and `afterCursor` (the replay starting point).
- **activity**: emitted for each activity event, contains `id` (the cursor),
  `instanceId`, `cursor`, `sessionId`, `traceId`, `canonicalSequence`,
  `timestamp`, `kind`, `executionStatus`, `summary`, and `details`.

The Console's Go client (`applicationclient.ActivityStream`) parses these
frames with strict protocol and size limits. The `live.Service` maintains a
2,048-entry/8-MiB ring buffer of recent activities and relays them to the browser via
`/api/console/v1/activity/stream` (SSE) and `/api/console/v1/activity/recent`
(POST JSON).

`application-artifact/download-response.json` records the exact artifact route,
status, and response headers. Its `bodyFixture` points to the existing
`traces/single-attempt-success.ndjson`; transport fixtures never duplicate an
NDJSON body. The JSON is test metadata, not a runtime manifest or a separate
artifact version.

Future Go PRs must first require the exact `consoleCompatibilityVersion` from
`application-rest/instance-status.json`. PR 09 must reject a mismatch before
making any snapshot, SSE, catalog, or artifact request. PRs 11-13 consume these
transport fixtures and the existing semantic corpus without filesystem paths or
a second trace format.
