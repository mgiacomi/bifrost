---
date: 2026-07-24T15:38:09-07:00
researcher: mgiacomi
model: GPT-5
git_commit: d08f3c6a2d2507f833785488a4d08b91fbcb339c
branch: main
repository: bifrost
topic: "Bifrost Console PR 01 canonical trace semantics and executable fixtures"
tags: [research, codebase, observability, traces, retries, usage, failures, fixtures]
status: complete
last_updated: 2026-07-24
last_updated_by: mgiacomi
last_updated_note: "Added follow-up research on which open questions are settled by roadmap and phase plans versus implementation-planning decisions"
---

# Research: Bifrost Console PR 01 Canonical Trace Semantics and Executable Fixtures

**Date**: 2026-07-24T15:38:09-07:00  
**Researcher**: mgiacomi  
**Model**: GPT-5  
**Git Commit**: d08f3c6a2d2507f833785488a4d08b91fbcb339c  
**Branch**: main  
**Repository**: bifrost

## Research Question

Research the current codebase for the ticket
`ai/thoughts/tickets/bifrost-console-pr-01-canonical-trace-semantics.md`,
with emphasis on the canonical append/finalization flow, physical
provider-attempt seam, every `schemaVersion` consumer, usage aggregation,
metrics, quotas, validation records, failure recording, fixture ownership, and
skill-authoring impact.

## Summary

The live Java trace is a session-owned, UTF-8 NDJSON stream. A
`BifrostSession` creates one `DefaultExecutionTraceHandle`; the handle writes
`TRACE_STARTED` and `TRACE_CAPTURE_POLICY_RECORDED`, assigns a monotonically
increasing sequence, chunks large payloads, and appends `TRACE_COMPLETED` during
finalization. Persistence policy may delete the completed file. The reader
streams records and reconstructs chunked payloads. The same trace is projected
into the developer-facing execution journal and then into the supported
`SkillExecutionView` observer model.

Current model tracing is at an outer engine-call seam. Planning, direct mission,
and step execution each open a `MODEL_CALL` frame and ask
`DefaultExecutionStateService.traceModelCall` to emit exactly one
`MODEL_REQUEST_PREPARED`, one `MODEL_REQUEST_SENT`, and one
`MODEL_RESPONSE_RECEIVED`. Linter, output-schema, and evidence advisors run
inside the Spring AI call chain. The linter and output-schema advisors can call
their downstream chain repeatedly, so several physical provider interactions
can occur inside one outer trace trio. Their current retry identity consists of
advisor name, skill name, a positive local `attempt`, and status. There is no
`retrySequenceId`, `attemptId`, or cross-advisor attempt identity in the live
model.

Usage is already normalized into `ModelUsageRecord`, accumulated in
`SessionUsageSnapshot`, enforced by session quotas, and sent to Micrometer from
the same `DefaultSessionUsageService.recordModelResponse` call. The three
engine paths invoke that accounting after receiving their outer response.
Normalized usage and the terminal session snapshot are not written to the
trace. Because advisor-driven physical retries occur below the outer engine
boundary, the current accounting call sees the final outer response rather
than one explicit accounting event per downstream attempt.

Failure information is currently represented by safe `exceptionType` and
`message` fields on error payloads and failed/aborted frame closures.
`recordError` marks the trace errored; exhausted linter and structured-output
outcomes also mark it errored. `TRACE_COMPLETED` merges caller-supplied metadata
with `errored` and `persistencePolicy`. The top-level coordinator does not add
an explicit outcome, terminal failure identity, or usage snapshot. Standalone
session-runner completion adds a lowercase `status` of `completed` or `failed`,
but no stable failure identifier. No live type or record establishes
`failureId`, `terminalFailureId`, or the proposed `SUCCEEDED`/`FAILED`/`ABORTED`
terminal vocabulary.

`TraceRecord.schemaVersion` is produced by the Java handle, serialized
automatically by Jackson, copied when the Java reader reconstructs a chunked
envelope, referenced by a Java active-read test literal, and decoded by the
deprecated `bifrost-cli`. No repository-owned golden NDJSON fixture suite
exists today; trace tests create records or temporary files in test code.

Under the repository's framework feature design lens, raw trace records and
their NDJSON representation are explicitly an **Ephemeral diagnostic format**.
The public trace/usage implementation types are allowlisted as technically
public only for collaboration between internal packages, and the architecture
tests state that no Supported SPI exists. All Spring beans in the affected
path are framework-owned, package-private factory methods, and the repository
forbids `@ConditionalOnMissingBean`. Documented quota and trace-persistence
properties remain **Configuration and manifest contracts**. The
`SkillExecutionView`/`SkillExecutionEvent` observer is an **Application API**
consumer of the journal projection, not a raw trace-record API.

## Detailed Findings

### 1. Canonical Trace Ownership and Append Flow

- `BifrostSession` constructs a `DefaultExecutionTraceHandle` when a session is
  created. Session state and the trace handle are guarded by the session lock
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java:121`).
- The handle generates an independent UUID trace ID and writes to
  `${java.io.tmpdir}/{sessionId}.{traceId}.execution-trace.ndjson`
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:348`).
- Initialization appends `TRACE_STARTED` and
  `TRACE_CAPTURE_POLICY_RECORDED`. Each append increments one shared atomic
  sequence and records trace ID, session ID, timestamp, frame fields, current
  thread name, metadata, and data
  (`DefaultExecutionTraceHandle.java:81`, `DefaultExecutionTraceHandle.java:185`,
  `DefaultExecutionTraceHandle.java:271`).
- `BifrostSession.appendTraceRecord` has active-frame and explicit-frame forms.
  Both delegate to the handle; the explicit-frame form preserves the supplied
  frame rather than relying on current stack position
  (`BifrostSession.java:690`, `BifrostSession.java:695`).
- `DefaultExecutionTraceRecorder` is the central semantic recorder used by
  `DefaultExecutionStateService`. It maps runtime operations to record types
  and augments metadata with `recordedAt`
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/DefaultExecutionTraceRecorder.java:21`,
  `DefaultExecutionTraceRecorder.java:149`).
- The writer opens the file in create/write/append mode for each record and
  writes one Jackson JSON value followed by `\n`
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/NdjsonTraceRecordWriter.java:28`).
- Payloads longer than 4,096 serialized characters are represented by an
  envelope with `payloadId`, `chunkCount`, `payloadChunked`, and `contentType`,
  followed by `PAYLOAD_CHUNK_APPENDED` records. The streaming reader rebuilds
  completed payloads by chunk index and emits an incomplete envelope plus
  available chunks when reading an active partial trace
  (`DefaultExecutionTraceHandle.java:185`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/NdjsonExecutionTraceReader.java:27`,
  `NdjsonExecutionTraceReader.java:128`).

### 2. Finalization and Persistence

- The top-level `ExecutionCoordinator` detects whether an invocation began
  with an empty frame stack. In `finally`, it closes the root mission frame,
  then finalizes only for that top-level invocation
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionCoordinator.java:72`,
  `ExecutionCoordinator.java:135`, `ExecutionCoordinator.java:141`).
- Root `FRAME_CLOSED` metadata uses lowercase `completed`, `failed`, or
  `aborted`. Failure closure metadata contains a safe exception class and fixed
  message supplied through `TraceFailureMetadata`
  (`ExecutionCoordinator.java:176`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/TraceFailureMetadata.java:15`).
- Coordinator completion metadata currently contains `skillName`, `objective`,
  and `remainingFrames`; outcome is not added at this boundary
  (`ExecutionCoordinator.java:145`).
- A standalone `BifrostSessionRunner` separately finalizes sessions that did
  not go through a top-level coordinator. It records `entryPoint`,
  `remainingFrames`, and `status`; on a thrown action it also records safe
  failure metadata (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSessionRunner.java:112`).
- `BifrostSession.finalizeTrace` first projects the current trace into a
  journal, then asks the handle to finalize. It keeps projection and
  finalization failures separately and publishes the finalized journal only
  when both steps succeed (`BifrostSession.java:619`).
- The handle appends `TRACE_COMPLETED` with supplied metadata plus the current
  `errored` flag and persistence policy, marks itself completed, and deletes
  the file for `NEVER` or for successful `ONERROR` traces
  (`DefaultExecutionTraceHandle.java:141`,
  `DefaultExecutionTraceHandle.java:328`).
- The trace handle rejects appends after completion. The corresponding test is
  `ExecutionTraceHandleTest#rejectsAppendsAfterTraceFinalization`
  (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionTraceHandleTest.java:83`).

### 3. Current Physical Provider-Attempt Seam

- Planning, direct mission, and step execution all create a `MODEL_CALL` frame
  and use the same `ExecutionStateService.traceModelCall` outer boundary
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/planning/DefaultPlanningService.java:407`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/DefaultMissionExecutionEngine.java:151`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/step/StepLoopMissionExecutionEngine.java:567`).
- `traceModelCall` records prepared, lets the callback report sent, waits for
  the callback's final `ModelTraceResult`, and then records one received
  response (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/state/DefaultExecutionStateService.java:269`).
- `ModelTraceContext` currently supplies model identity, skill name, and
  segment metadata. It has no retry or attempt fields
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ModelTraceContext.java:7`).
- `LinterCallAdvisor` and `OutputSchemaCallAdvisor` each make
  `downstreamChain.nextCall` inside a retry loop
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/linter/LinterCallAdvisor.java:84`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/outputschema/OutputSchemaCallAdvisor.java:94`).
  These calls are the current physical downstream/provider interaction seam.
- Advisor trace facts carry local `attempt` and `status` through
  `AdvisorTraceContext`; `attempt` must be positive. Retry-requested, passed,
  schema-applied, and exhausted facts are written as advisor mutation records
  by `DefaultSkillAdvisorResolver`
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/AdvisorTraceContext.java:9`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/chat/DefaultSkillAdvisorResolver.java:59`).
- Linter and output-schema outcomes separately serialize their own attempt,
  retry count, maximum retries, and status into `LINTER_RECORDED` and
  `STRUCTURED_OUTPUT_RECORDED`. Evidence validation writes
  `EVIDENCE_VALIDATION_PASSED` or `EVIDENCE_VALIDATION_FAILED`, with
  expression/claim details supplied by the resolver
  (`DefaultSkillAdvisorResolver.java:95`, `DefaultSkillAdvisorResolver.java:107`,
  `DefaultSkillAdvisorResolver.java:142`).
- Planning-quality retry is a second retry mechanism outside advisors. It
  loops in `DefaultPlanningService`, makes a new outer model call for each
  planning attempt, and writes `PLAN_VALIDATION_FAILED` and
  `PLAN_RETRY_REQUESTED` with a retry count
  (`DefaultPlanningService.java:297`, `DefaultPlanningService.java:328`,
  `DefaultPlanningService.java:335`).

### 4. Usage, Quotas, and Metrics

- `ModelUsageRecord` contains nonnegative prompt, completion, and total units;
  `UsagePrecision`; and opaque provider-native usage
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/usage/ModelUsageRecord.java:5`).
- `ModelUsageExtractor` prefers positive provider usage metadata. Otherwise it
  derives heuristic units from prompt and response text. If all source text is
  absent it returns zero units with `UNAVAILABLE`
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/usage/ModelUsageExtractor.java:16`).
- `SessionUsageSnapshot` accumulates skill invocations, tool invocations,
  linter retries, model calls, prompt/completion/total units, and counts by
  exact/heuristic/unavailable precision
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/usage/SessionUsageSnapshot.java:5`,
  `SessionUsageSnapshot.java:81`).
- `DefaultSessionUsageService.recordModelResponse` performs three operations
  from the same `ModelUsageRecord`: update the session snapshot, publish
  Micrometer metrics, and enforce model-call and usage-unit quotas
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/usage/DefaultSessionUsageService.java:41`).
- Direct mission, planning, and step execution call the usage service after
  their outer response is available
  (`DefaultMissionExecutionEngine.java:205`,
  `DefaultPlanningService.java:310`,
  `StepLoopMissionExecutionEngine.java:614`).
- Micrometer publishes model calls, prompt units, completion units, and total
  usage units, tagged with skill, connection, driver, and precision where
  applicable
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/usage/MicrometerUsageMetricsRecorder.java:26`).
- Linter retry accounting is updated when a `LinterOutcome` has status
  `RETRYING`. That same path enforces `max-linter-retries` and publishes the
  linter outcome metric (`DefaultSessionUsageService.java:61`).
- Current trace response payloads contain response content only; the normalized
  `ModelUsageRecord` passed to accounting is not passed to
  `recordModelResponseReceived`. `TRACE_COMPLETED` likewise receives no
  `SessionUsageSnapshot` from the coordinator.

### 5. Current Error, Validation, and Terminal Semantics

- `DefaultExecutionTraceRecorder.recordError` marks the entire trace errored
  and appends `ERROR_RECORDED` on the active frame
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/DefaultExecutionTraceRecorder.java:136`).
- Exhausted linter and output-schema outcomes also mark the trace errored before
  recording their terminal validation outcome
  (`DefaultExecutionTraceRecorder.java:111`, `DefaultExecutionTraceRecorder.java:124`).
- Provider and application exception messages are deliberately excluded from
  trace failure metadata. `TraceFailureMetadata` stores exception class plus a
  caller-selected safe message (`TraceFailureMetadata.java:8`,
  `TraceFailureMetadata.java:15`).
- A top-level coordinator failure produces both an `ERROR_RECORDED` record and
  a failed/aborted root `FRAME_CLOSED`, but the two records have no shared
  failure identifier (`ExecutionCoordinator.java:126`,
  `ExecutionCoordinator.java:176`).
- `TRACE_COMPLETED.errored` reflects calls to `markErrored`. It is independent
  from the lowercase frame/session-runner status fields and is the only
  terminal error flag added unconditionally by the trace handle
  (`DefaultExecutionTraceHandle.java:141`).
- The journal preserves distinct tool-failure and error records and does not
  project raw model response, frame, or trace-completion records
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionJournalProjector.java:57`,
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionJournalProjectionContractTest.java:126`).

### 6. `schemaVersion` Inventory

| Surface | Current behavior | Classification/evidence |
|---|---|---|
| `TraceRecord` | Declares `int schemaVersion`, `CURRENT_SCHEMA_VERSION = 1`, a JSON creator property, and defaults nonpositive input to version 1 (`TraceRecord.java:12`, `TraceRecord.java:27`, `TraceRecord.java:29`). | Ephemeral diagnostic format; technically public internal record. |
| Java writer/handle | `buildRecord` passes `CURRENT_SCHEMA_VERSION` into every record (`DefaultExecutionTraceHandle.java:271`). Jackson serializes the record component. | Existing writer behavior. |
| Java chunk reader | Reconstructed envelopes copy `envelope.schemaVersion()` into a new record (`NdjsonExecutionTraceReader.java:185`). | Existing reader behavior. |
| Java tests | `NdjsonExecutionTraceReaderTest` constructors pass the version; its trailing-partial-record test contains a literal partial JSON line with `"schemaVersion":1` (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/NdjsonExecutionTraceReaderTest.java:173`, `NdjsonExecutionTraceReaderTest.java:192`). Journal projection tests also construct versioned records. | Executable evidence of the current shape, not a compatibility promise. |
| Deprecated Go CLI | `TraceRecord.SchemaVersion` decodes `json:"schemaVersion"` (`bifrost-cli/main.go:87`). No CLI behavior branches on it. | In-repository diagnostic consumer; historical plans classify the CLI as a deprecated proof of concept rather than a supported predecessor. |
| Documentation/plans | Phase 2 and Phase 3 plans state that PR 01 removes the field and that raw records have no independent version property. | Historical design context, not live behavior. |

No `schemaVersion` occurrence was found in current sample manifests,
configuration metadata, or skill-authoring guidance.

### 7. Tests and Fixture Ownership

- Trace writer/handle tests create real temporary NDJSON files and verify
  monotonic sequence, session-named paths, persistence policies, timestamp
  overrides, unique trace paths, and post-finalization rejection
  (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/NdjsonTraceRecordWriterTest.java:20`,
  `ExecutionTraceHandleTest.java:21`).
- Reader tests construct records and temporary files in Java. They cover
  payload reconstruction, chunk-index ordering, incomplete active payloads,
  trailing partial records, and streaming many records
  (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/NdjsonExecutionTraceReaderTest.java:27`).
- `ExecutionTraceContractTest` exercises planning and mission trace generation,
  asserts the common outer model trio, verifies safe provider-failure metadata,
  and checks planning retry record ownership
  (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionTraceContractTest.java:43`,
  `ExecutionTraceContractTest.java:97`,
  `ExecutionTraceContractTest.java:178`).
- Advisor tests protect retry behavior, attempt counters, terminal statuses,
  session recording, and active-frame advisor mutation records
  (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/linter/LinterCallAdvisorTest.java:39`,
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/outputschema/OutputSchemaCallAdvisorTest.java:44`).
- Usage tests cover exact and heuristic extraction, accumulated snapshots,
  quotas, metrics, and linter/tool accounting
  (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/usage/ModelUsageExtractorTest.java:21`,
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/usage/SessionUsageServiceTest.java:67`,
  `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/usage/MicrometerUsageMetricsRecorderTest.java:15`).
- No `.ndjson` file, golden trace resource directory, expected semantic result
  file, or Java-to-Go fixture generation harness exists in the current
  repository. The files under sample `fixtures/` are application input text,
  not execution traces.
- `bifrost-cli/main_test.go` contains two display-formatting tests. It does not
  consume Java-produced trace fixtures or assert usage, retry membership,
  terminal outcome, terminal failure linkage, or malformed artifact behavior.

### 8. Framework Surface Classification

| Lens category | Affected current surfaces | Evidence and current status |
|---|---|---|
| Application API | `SkillTemplate` observer returns `SkillExecutionView` containing immutable `SkillExecutionEvent` values. | Explicitly listed in the API allowlist (`BifrostPublicSurfaceArchitectureTest.java:28`) and documented in `README.md:146`. It consumes the journal projection, not raw `TraceRecord`. |
| Supported SPI | None in this area. | Architecture test states that there is no supported SPI package or type (`BifrostPublicSurfaceArchitectureTest.java:247`). |
| Configuration and manifest contracts | `execution-trace.persistence`; `bifrost.session.quotas.max-model-calls`, `max-usage-units`, `max-linter-retries`; linter/output-schema retry manifest semantics. | Properties and defaults are documented in `README.md:256`; manifest retry behavior is validated and tested. No proposed trace identifier is currently a skill manifest field. |
| Persisted or serialized contracts | `BifrostSession` and public execution-view DTOs are technically serialized in current code; raw trace NDJSON is not classified here by repository policy. | Serialization establishes existing behavior, but the reviewed sources do not independently classify `BifrostSession` JSON as a deliberately durable or cross-version contract. The feature-design lens expressly classifies execution traces separately. |
| Ephemeral diagnostic formats | `TraceRecord`, record types, metadata/data fields, NDJSON layout, chunks, trace path, and journal projection inputs. | The feature-design lens explicitly assigns execution traces to this category (`ai/thoughts/framework-feature-design-lens.md:29`). |
| Internal or accidentally exposed implementation | Recorder/state/handle/reader/writer, advisor context/facts, usage record/service/snapshot/metrics, engine constructors and interfaces. | Public internal types are allowlisted only for cross-package Java collaboration, including recorder and usage types (`BifrostPublicSurfaceArchitectureTest.java:43`, `BifrostPublicSurfaceArchitectureTest.java:71`, `BifrostPublicSurfaceArchitectureTest.java:169`). |

Spring wiring is framework-owned:

- `modelUsageExtractor`, `usageMetricsRecorder`, `sessionUsageService`, and
  `executionStateService` are package-private `@Bean` methods
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:257`).
- The bean-override allowlist is empty, and an architecture test rejects direct
  or composed `@ConditionalOnMissingBean` anywhere in production
  (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostAutoConfigurationBoundaryTest.java:40`,
  `BifrostAutoConfigurationBoundaryTest.java:71`).

### 9. Protected Consumers and Cross-Component Boundary

- The live Java reader consumes the writer's record shape for journal
  projection and explicit trace reads.
- `ExecutionJournalProjector` consumes selected trace types and fields. The
  supported `SkillExecutionView` mapper consumes that journal
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skillapi/SkillExecutionViewMapper.java:32`).
- Current Java tests are executable consumers of record constructors,
  metadata, sequence, chunking, failure redaction, advisor attempt fields, and
  journal projection.
- The repository's current Go program, `bifrost-cli`, decodes the raw record
  shape and displays record types, advisor attempt/status, `errored`, frame
  timing, and raw metadata/data (`bifrost-cli/main.go:86`,
  `bifrost-cli/main.go:741`, `bifrost-cli/main.go:828`). Historical Phase 2
  planning classifies it as deprecated and not a supported compatibility
  target.
- The planned Bifrost Console Go analyzer is not present in the live codebase.
  Phase 1 planning identifies Java-produced golden fixtures as its executable
  agreement and enumerates the consumed semantics: explicit attempt/retry
  identity, normalized response usage, terminal usage reconciliation, outcome,
  terminal failure linkage, frames, chunks, sequence, and malformed-artifact
  rejection (`ai/thoughts/plans/bifrost_console_phase_1_observability_foundation.md:555`,
  `ai/thoughts/plans/bifrost_console_phase_1_observability_foundation.md:567`).
- REST, SSE, acquisition, and problem-response implementations are not present
  for PR 01 and are explicitly outside the ticket. The historical plan states
  that later Java/Go protocol changes are coordinated under one product release
  compatibility string rather than a trace-specific version.

### 10. Skill-Authoring Impact in the Current Repository

- `ai/skill-authoring/README.md` marks both “Execution limits and quotas” and
  “Traces and debugging” as not yet documented. Retry/cost semantics under
  planning are also incomplete (`ai/skill-authoring/README.md:57`,
  `ai/skill-authoring/README.md:61`).
- `source-verification.md` directs authoring investigations to locate trace,
  retry, and terminal failure behavior and to distinguish live source/tests
  from plans (`ai/skill-authoring/source-verification.md:50`,
  `ai/skill-authoring/source-verification.md:134`).
- Evidence-contract guidance defines successful direct-child credit and states
  that output retries reuse completed work without calling tools again. That
  behavior is related to validation retry evidence, but the guide does not
  document physical model-attempt IDs or trace record fields
  (`ai/skill-authoring/evidence-contracts.md:69`).
- Current author-facing skill manifests expose linter and output-schema retry
  limits/status behavior. The proposed trace-only identifiers and terminal
  linkage have no current manifest syntax or application invocation parameter.

## Architecture Documentation

The current dataflow is:

```text
engine model frame
  -> DefaultExecutionStateService.traceModelCall
     -> outer prepared record
     -> Spring AI call/advisor chain
        -> zero or more advisor-owned retry loops
           -> downstream physical calls
           -> advisor mutation + validation outcome records
     -> outer sent/received records
  -> ModelUsageExtractor
  -> DefaultSessionUsageService
     -> SessionUsageSnapshot
     -> quota enforcement
     -> Micrometer counters

top-level coordinator / standalone session runner
  -> root frame closure and safe error records
  -> BifrostSession.finalizeTrace
     -> ExecutionJournalProjector
     -> TRACE_COMPLETED append
     -> persistence-policy deletion decision
```

The live architecture therefore has one canonical append path and one canonical
usage-accounting service, but their current lifecycle seams are different:
trace model events surround an outer Spring AI call, while normalized usage is
recorded after that call and physical advisor retries occur within it.

## Code References

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/TraceRecord.java:12`
  - Current raw-record envelope and per-record schema version.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:81`
  - Trace initialization, sequencing, chunking, completion, and persistence.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/NdjsonExecutionTraceReader.java:27`
  - Streaming NDJSON and chunk reconstruction.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/state/DefaultExecutionStateService.java:269`
  - Current outer model-call trace boundary.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/linter/LinterCallAdvisor.java:84`
  - Linter-owned physical retry loop.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/outputschema/OutputSchemaCallAdvisor.java:94`
  - Structured-output physical retry loop.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/usage/DefaultSessionUsageService.java:41`
  - Shared session, quota, and metric accounting from normalized model usage.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionCoordinator.java:126`
  - Top-level error recording, frame closure, and trace finalization.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionTraceContractTest.java:43`
  - Current cross-engine trace behavior.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java:43`
  - Classification of technically public internal types.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostAutoConfigurationBoundaryTest.java:40`
  - Empty supported bean-override allowlist and framework-owned wiring.
- `bifrost-cli/main.go:86`
  - Existing Go raw-record decoder.

## Historical Context (from `ai/thoughts/`)

- `ai/thoughts/framework-feature-design-lens.md` classifies traces as
  current-run ephemeral diagnostics, says technical visibility is not proof of
  support, and records the pre-1.0 policy of updating current writers, readers,
  projectors, fixtures, and tools atomically without historical readers or
  schema migration.
- `ai/thoughts/plans/bifrost_console_phase_1_observability_foundation.md:555`
  defines the planned current-release Java/Go trace agreement and makes
  Java-produced semantic fixtures authoritative.
- `ai/thoughts/plans/bifrost_console_phase_1_observability_foundation.md:567`
  records the planned attempt, usage, reconciliation, outcome, and terminal
  failure relationships reflected in the PR 01 ticket.
- `ai/thoughts/plans/bifrost_console_phase_2_ui_console.md:124` classifies
  `bifrost-cli` as a deprecated proof of concept rather than a supported
  predecessor or compatibility target.
- `ai/thoughts/plans/2026-07-23-bifrost-console-implementation-roadmap.md:97`
  places PR 01 first in Phase 1 and makes it the prerequisite for later console
  foundation work.

## Related Research

No earlier document exists under `ai/thoughts/research/` in this checkout.

## Open Questions

The live repository does not establish the following details; they remain
unclassified implementation decisions for later planning:

- which Spring AI interception object will own identity creation at the
  physical downstream-call seam when multiple advisors are nested;
- whether planning-quality retries and advisor retries share one retry identity
  abstraction or create identities at their respective loop boundaries;
- which metadata or data location will carry each proposed consumed field;
- how a response with no attributable frame/attempt relationship will be
  represented while still contributing to the terminal session snapshot;
- how core finalization failure fixtures will expose the absence of a final
  canonical record, since the current Java reader intentionally tolerates
  active trailing partial data;
- where Java-produced golden fixtures and expected Go semantic results will
  live, and which build step will generate or verify them; and
- whether the public execution-journal/Application API projection remains
  byte-for-byte unchanged or gains any newly projected terminal diagnostic
  event. The current projector ignores raw model response and
  `TRACE_COMPLETED` records.

## Follow-up Research 2026-07-24T15:47:51-07:00

### Question

Should the open questions above be answered before starting
`ai/commands/2_create_plan.md`, or should they be resolved as part of the PR 01
implementation plan?

### Planning-Process Constraint

`ai/commands/2_create_plan.md` requires the planning context to investigate
questions that code research can answer, ask only for genuine human-judgment
decisions, and finalize no plan while any question remains unresolved. The
research document may identify implementation decisions, but the completed
implementation plan must select exact code ownership, serialized field
placement, fixture paths, tests, compatibility treatment, and documentation
impact.

The roadmap and phase plans already settle the product and cross-phase
semantics. They deliberately leave exact field spelling, fixture-directory
layout, test-harness organization, and similar code-shape choices to detailed
implementation planning
(`ai/thoughts/plans/bifrost_console_phase_2_ui_console.md:325`,
`ai/thoughts/plans/bifrost_console_phase_2_ui_console.md:1111`).

### Disposition of the Original Open Questions

| Original question | What the roadmap/phases already settle | Where the remaining decision belongs |
|---|---|---|
| Which Spring AI interception object owns attempt identity at the physical downstream-call seam? | PR 01 must instrument the physical provider-interaction seam when advisors can issue several downstream calls. One outer prepared/sent/received trio cannot represent several physical attempts (`ai/thoughts/plans/bifrost_console_phase_1_observability_foundation.md:573`, `ai/thoughts/plans/bifrost_console_phase_1_observability_foundation.md:581`). | The PR 01 implementation plan must identify the exact Spring AI seam, identity lifetime, and affected constructors/interfaces after focused source investigation. This is a technical planning decision, not an unresolved product requirement. |
| Do planning-quality retries and advisor retries share one retry abstraction? | A `retrySequenceId` identifies one logical model/validation retry loop, while attempt numbers are only local to that loop. Nested retry sequences with overlapping attempt numbers are required fixture coverage (`ai/thoughts/plans/bifrost_console_phase_1_observability_foundation.md:577`, `ai/thoughts/plans/bifrost_console_phase_1_observability_foundation.md:604`). Consequently, independently operating retry loops have distinct sequence identities; equality of attempt numbers never joins them. | The PR 01 plan must map each existing loop to creation/propagation boundaries and define the internal Java representation. |
| Where do the new consumed fields live? | Normalized usage belongs on `MODEL_RESPONSE_RECEIVED` unless implementation demonstrates a genuinely separate lifecycle seam. No separate trace/container version is introduced (`ai/thoughts/plans/bifrost_console_phase_1_observability_foundation.md:584`, `ai/thoughts/plans/bifrost_console_phase_1_observability_foundation.md:563`). Applicable prepared, sent, received, validation/advisor, and retry-requested facts carry the explicit attempt relationships. | Exact JSON location and spelling for identifiers, usage snapshot, outcome, and failure links are cross-language contract details that the PR 01 plan must state explicitly and cover with fixtures. |
| How is usage without a trustworthy relationship represented? | Missing usage remains unknown rather than zero. Usage is never forced onto a nearby frame or attempt. Attributed usage plus explicit unattributed usage reconciles to the terminal recorded snapshot (`ai/thoughts/plans/bifrost_console_phase_1_observability_foundation.md:582`, `ai/thoughts/plans/bifrost_console_phase_1_observability_foundation.md:589`; `ai/thoughts/plans/bifrost_console_workflows.md:422`). | PR 01 must produce trace facts and expected semantic fixture results that preserve this distinction. The later Go arithmetic and invalid-artifact behavior belong to PR 13. |
| How does the core-finalization-failure fixture work? | Failed core finalization cannot produce a trustworthy final canonical `TRACE_COMPLETED`. The noncanonical `EXECUTION_OBSERVATION_ENDED/CORE_FINALIZATION_FAILED` activity is outside the trace relationship and belongs to the observation lifecycle introduced by PR 02 (`ai/thoughts/plans/bifrost_console_phase_1_observability_foundation.md:602`; `ai/thoughts/tickets/bifrost-console-pr-02-observation-lifecycle.md:9`). | PR 01 covers the canonical/malformed or non-final artifact side and existing core failure behavior. PR 02 owns the exceptional live lifecycle event. The PR 01 plan must keep that boundary explicit in its fixture matrix. |
| Where do fixtures and expected semantic results live, and how are they verified? | Java-produced fixtures are the executable current-release agreement. PR 01 establishes the semantic corpus; PR 06 later finalizes cross-boundary fixtures, reproducible production, adapter integration, and Phase 1 verification (`ai/thoughts/tickets/bifrost-console-pr-01-canonical-trace-semantics.md:22`; `ai/thoughts/tickets/bifrost-console-pr-06-artifact-streaming-integration.md:11`). Phase 2 says exact fixture paths and harness organization are implementation work. | The PR 01 plan must choose concrete initial paths, generators/builders, expected-result representation, and Java verification commands. It should identify the PR 06 handoff rather than leaving placement unresolved. |
| Does PR 01 change the public execution-journal/Application API projection? | Phase 1 preserves canonical trace and completed-journal failure semantics. The journal remains a separate completed projection and intentionally omits most model, frame, step, evidence, and trace lifecycle records; live activity is a distinct later projection (`ai/thoughts/plans/bifrost_console_phase_1_observability_foundation.md:106`, `ai/thoughts/plans/bifrost_console_phase_1_observability_foundation.md:109`, `ai/thoughts/plans/bifrost_console_phase_1_observability_foundation.md:154`). | PR 01 should treat the journal and `SkillExecutionView` behavior as a protected consumer to regression-test, not as a new terminal-event surface. Any mechanical constructor/record updates remain part of the atomic implementation. |

### Recommended Handoff to the Planning Context

No separate product-design phase is needed before running
`2_create_plan.md`. The settled decisions above should be supplied to the new
context alongside the ticket and this research document. The planning context
should resolve the remaining implementation choices through source research
and must not emit a final plan until it has:

1. selected and justified the physical provider-attempt interception seam;
2. mapped every retry loop and identity propagation path;
3. specified the exact serialized fields and terminal snapshot shape;
4. assigned concrete fixture paths, generation ownership, expected semantic
   outputs, and verification commands;
5. separated PR 01 canonical failure fixtures from PR 02 observation-lifecycle
   behavior and PR 13 Go calculations;
6. preserved the current journal/Application API projection with focused
   regression coverage;
7. completed the required contract/compatibility, public-surface,
   Spring-extension-point, shim/no-shim, Java-to-Go coordination, and
   skill-authoring documentation assessments; and
8. recommended the dedicated `3_testing_plan.md` pass after the implementation
   approach is agreed.
