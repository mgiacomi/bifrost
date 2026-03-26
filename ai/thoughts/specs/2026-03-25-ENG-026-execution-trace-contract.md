# ENG-026 Execution Trace Contract

## Status

Approved implementation contract for ENG-026.

## Authoritative Boundary

`ExecutionTrace` remains the canonical runtime record.

The authoritative semantic boundary is the canonical recorder layer (`ExecutionTraceRecorder` via `DefaultExecutionStateService`), not the chat-client seam. This is intentional: planning, mission, tool, advisor, and coordinator paths all converge there without fallback writer paths.

## Approved Writers

Only these boundaries may emit raw trace semantics:

- `DefaultExecutionTraceHandle` for transport-level trace lifecycle records (`TRACE_STARTED`, chunk envelopes/chunks, `TRACE_COMPLETED`)
- `DefaultExecutionTraceRecorder` for runtime semantic records
- `DefaultExecutionStateService` as the runtime-facing façade that routes feature flows into the recorder

Feature code must not emit raw `TraceRecordType` values directly. Planning and mission code use explicit model recorder operations. Advisors publish structured mutation facts through `AdvisorTraceRecorder`.

## Record Taxonomy And Sequencing

- `TRACE_STARTED` is the first record in a live trace.
- `TRACE_CAPTURE_POLICY_RECORDED` follows `TRACE_STARTED`.
- `FRAME_OPENED` is emitted when a frame is pushed.
- `FRAME_METADATA_RECORDED` is emitted immediately after `FRAME_OPENED`.
- Model calls emit `MODEL_REQUEST_PREPARED`, then `MODEL_REQUEST_SENT`, then `MODEL_RESPONSE_RECEIVED`.
- Tool calls emit `TOOL_CALL_REQUESTED`, then `TOOL_CALL_STARTED`, then exactly one terminal tool event: `TOOL_CALL_COMPLETED` or `TOOL_CALL_FAILED`.
- Advisor mutations emit `ADVISOR_REQUEST_MUTATION_RECORDED` and `ADVISOR_RESPONSE_MUTATION_RECORDED` as frame-aware events produced from structured advisor facts.
- `PLAN_CREATED`, `PLAN_UPDATED`, `LINTER_RECORDED`, `STRUCTURED_OUTPUT_RECORDED`, and `ERROR_RECORDED` are semantic records owned by the canonical recorder.
- `FRAME_CLOSED` is emitted before the frame is popped.
- `TRACE_COMPLETED` is the final accepted semantic record.

## Frame Invariants

- Every frame has a stable `frameId`, `traceFrameType`, `route`, and `openedAt`.
- Child frames record `parentFrameId` in the envelope.
- Frame graphs must be reconstructable from raw records alone; callers must not depend on in-memory session history.
- Frame-aware semantic records are written against the explicit frame supplied by the recorder or the session’s active frame when the contract defines that behavior.

## Model Semantics

- Planning and mission flows do not write `MODEL_*` records directly.
- Model record metadata is defined by `ModelTraceContext` and must include `provider`, `providerModel`, `skillName`, and `segment`.
- Equivalent model events across planning and mission must share the same semantic envelope shape and ordering.
- Provider-native thought capture is intentionally out of scope for the MVP hardening contract; `MODEL_THOUGHT_CAPTURED` remains reserved for future work rather than a required semantic event.

## Tool Semantics

- Tool sequencing is owned by the recorder and described by `ToolTraceContext`.
- Tool metadata must include `capabilityName`; linked executions must also include `linkedTaskId`; unplanned executions must set `unplanned=true`.
- Tool failures may also produce `ERROR_RECORDED`, but projector behavior must not rely on inconsistent parallel writers.

## Advisor Semantics

- Advisors must not import or emit `TraceRecordType` directly.
- Advisors report structured request/response mutation facts through `AdvisorTraceRecorder`.
- Advisor mutation records remain frame-aware and align with the active model frame when a session-managed execution is present.

## Error And Finalization Semantics

- `ERROR_RECORDED` marks the trace errored before appending the record.
- Exhausted linter and output-schema outcomes mark the trace errored before their semantic record is appended.
- `TRACE_COMPLETED` is the terminal append barrier.
- Any append attempt after completion must fail deterministically across all writer paths.
- Top-level finalization ownership remains with `ExecutionCoordinator`.

## Active Reads And Chunked Payloads

- Large payloads are written as an envelope record plus `PAYLOAD_CHUNK_APPENDED` records.
- Active reads must tolerate valid in-flight chunk sequences.
- If a chunked payload is incomplete, readers surface the envelope and any observed chunks without failing the whole read.
- If all chunks are present, readers reconstruct the envelope payload deterministically.

## Retention, Naming, And Runtime Boundaries

- Trace files are named `<sessionId>.<traceId>.execution-trace.ndjson` so the session id remains obvious while repeated or concurrent runs cannot collide on the same temp file.
- ENG-026 relies on the framework invariant that managed session ids are globally unique and non-reusable.
- Persistence behavior is controlled by `execution-trace.persistence`.
- `NEVER` deletes the trace after completion.
- `ONERROR` retains only errored traces.
- `ALWAYS` retains all traces.
- Deserialized sessions are snapshot objects only; the runtime framework does not support playback, rehydration, or completed-session readback from restored sessions.

## Post-Run Snapshot And Projection

- Serialized `BifrostSession` snapshots include `ExecutionTrace` metadata and a derived `ExecutionJournal`.
- `ExecutionJournal` is a projection over the hardened raw trace contract, not a compensation layer for inconsistent writers.
- Serialized snapshots exist for transport/debug visibility, not as a supported runtime restore mechanism.
