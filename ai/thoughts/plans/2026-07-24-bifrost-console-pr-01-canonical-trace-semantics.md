# Bifrost Console PR 01 Canonical Trace Semantics and Executable Fixtures Implementation Plan

## Overview

Establish the current-release canonical trace evidence that the later Bifrost
Console analyzer will consume. This PR removes the obsolete per-record version,
records every physical provider attempt with explicit retry identity, writes the
same normalized usage fact into trace/session/quota/metric accounting, makes
terminal outcome and failure linkage explicit, and checks in a deterministic
Java-produced semantic fixture corpus.

This is the first PR in Phase 1 of the console roadmap. It establishes the
evidence contract required by PR 02's live projection, PR 06's artifact
streaming integration, PR 07's console project, and PR 13's Go analysis. It
does not implement those downstream consumers.

## Current State Analysis

- `TraceRecord` has a `schemaVersion` component and silently normalizes
  nonpositive values to `CURRENT_SCHEMA_VERSION` even though the repository
  classifies traces as current-run ephemeral diagnostics
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/TraceRecord.java:12`).
- The trace handle supplies that version on every append, and the chunk reader
  copies it when reconstructing an envelope
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:271`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/NdjsonExecutionTraceReader.java:185`).
- `DefaultExecutionStateService#traceModelCall` emits one outer
  prepared/sent/received trio per engine call
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/state/DefaultExecutionStateService.java:269`).
  The linter, output-schema, and evidence advisors can each re-enter the
  downstream Spring AI chain, so multiple physical provider calls can be
  collapsed inside that trio
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/linter/LinterCallAdvisor.java:86`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/outputschema/OutputSchemaCallAdvisor.java:96`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceContractCallAdvisor.java:59`).
- Spring AI 1.1.6 sorts `CallAdvisor`s by order and terminates the chain with
  `ChatModelCallAdvisor` at `Ordered.LOWEST_PRECEDENCE`. A Bifrost advisor at
  `Ordered.LOWEST_PRECEDENCE - 1` therefore wraps exactly the physical
  `ChatModel#call`, and every downstream retry traverses it.
- Planning, direct mission execution, and step execution currently normalize
  and account for only the outer response
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/planning/DefaultPlanningService.java:310`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/DefaultMissionExecutionEngine.java:205`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/step/StepLoopMissionExecutionEngine.java:614`).
- `DefaultSessionUsageService#recordModelResponse` already updates the session
  snapshot, Micrometer, and quota enforcement from one `ModelUsageRecord`
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/usage/DefaultSessionUsageService.java:41`),
  but that record is absent from the trace.
- Top-level execution writes a safe `ERROR_RECORDED` and failed/aborted root
  closure, but those records have no common failure identity. Finalization
  adds only `errored` and persistence policy to `TRACE_COMPLETED`
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionCoordinator.java:126`,
  `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java:141`).
- The journal intentionally ignores model lifecycle, frame lifecycle, and
  `TRACE_COMPLETED`; it remains the completed projection behind the supported
  `SkillExecutionView` Application API
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionJournalProjector.java:57`).
- There is no checked-in NDJSON semantic corpus or expected-result corpus.
  Existing trace tests generate temporary records only.

## Desired End State

One canonical current-release trace has these properties:

1. No record contains `schemaVersion`, and no reader, writer, constructor,
   test, fixture, or deprecated CLI type expects it.
2. Every physical model/provider call emits
   `MODEL_REQUEST_PREPARED`/`MODEL_REQUEST_SENT` from the final Spring AI
   request immediately around the terminal model call. A returned provider
   response additionally emits `MODEL_RESPONSE_RECEIVED`; a thrown provider
   call retains the prepared/sent attempt facts without inventing a response.
3. Every physical group has an opaque `retrySequenceId`, opaque `attemptId`,
   and positive `attemptNumber`; validation and retry records link to the exact
   attempt they evaluated or requested.
4. `MODEL_RESPONSE_RECEIVED` contains the normalized usage used by session
   totals, quotas, and Micrometer. Each physical retry that returns a response
   is counted once everywhere. A provider call that throws before returning a
   response does not increment the existing response-based `modelCalls`
   counter.
5. Final `TRACE_COMPLETED` contains `outcome`, the terminal session usage
   snapshot, and `terminalFailureId` only for a failed/aborted execution.
6. The terminal `ERROR_RECORDED`, successfully recorded failed/aborted root
   `FRAME_CLOSED`, and `TRACE_COMPLETED.terminalFailureId` share one opaque
   failure identity. If root closure itself fails, the terminal error and
   completion retain that identity without fabricating a closure record.
   Earlier non-terminal errors keep distinct IDs.
7. Java-generated fixtures and expected semantic results cover the ticket
   matrix and are deterministic and reproducible.
8. The completed journal and `SkillExecutionView` projection retain their
   existing behavior.

### Canonical serialized fields

Consumed semantic fields live in record `metadata`; request/response content
continues to live in `data`.

| Record(s) | Required metadata |
| --- | --- |
| Physical `MODEL_REQUEST_PREPARED`, `MODEL_REQUEST_SENT`, `MODEL_RESPONSE_RECEIVED` | `retrySequenceId` (opaque UUID string), `attemptId` (opaque UUID string), `attemptNumber` (positive integer), existing model identity, `skillName`, and `segment` |
| `MODEL_RESPONSE_RECEIVED` | `usage` object with `promptUnits`, `completionUnits`, `totalUnits`, and `precision` (`EXACT`, `HEURISTIC`, or `UNAVAILABLE`); provider-native usage remains opaque and unconsumed |
| Advisor/validation pass, failure, exhaustion, and retry-requested records | The enclosing `retrySequenceId` plus the exact `attemptId` and `attemptNumber` evaluated; planning-quality retry records use their planning retry sequence and physical attempt |
| Every `ERROR_RECORDED` | `failureId`; safe `exceptionType` and `message` remain unchanged |
| Failed/aborted root `FRAME_CLOSED` | The same terminal `failureId` as the terminal error |
| Final `TRACE_COMPLETED` | `outcome`, `sessionUsageSnapshot`, optional `terminalFailureId`, existing `errored`, and `persistencePolicy` |

`sessionUsageSnapshot` preserves the existing snapshot names:
`skillInvocations`, `toolInvocations`, `linterRetries`, `modelCalls`,
`promptUnits`, `completionUnits`, `usageUnits`, `exactModelResponses`,
`heuristicModelResponses`, and `unavailableModelResponses`.

Java does not persist a second `unattributedUsage` total. PR 13's Go analysis
derives the component-wise nonnegative difference between the terminal snapshot
and usage linked to physical response records:

```text
sum(attributed response usage) + unattributed usage = terminal session usage
```

An unavailable response is still a recorded response with
`precision: UNAVAILABLE`; it is not silently converted to zero usage evidence.
A missing response relationship remains unattributed. Attempt numbers are
meaningful only within their `retrySequenceId`.

### Retry-sequence ownership

- Direct mission calls and individual step-model calls create one retry
  sequence for their complete Spring AI call chain.
- Planning creates one retry sequence before the plan-quality loop and reuses
  it across every physical planning attempt.
- Nested skill/model invocations create independent sequences, so overlapping
  attempt numbers never imply a relationship.
- Nested validation advisors share the enclosing physical-call sequence and
  copy the exact attempt identity returned in `ChatClientResponse.context()`
  into their own outcome/retry trace facts. This deliberately gives each
  physical call one unambiguous sequence membership instead of inventing a
  many-to-many serialized relationship when several validators wrap the same
  call.

This resolves advisor nesting as one model/validation retry sequence per
logical call chain while preserving exact attempt-to-validation linkage.

### Key Discoveries

- The final pre-provider advisor is a narrower and more accurate seam than the
  three engine call sites; it sees mutated retry prompts and every nested
  advisor retry exactly once.
- Physical accounting is an intentional pre-1.0 behavior correction:
  `max-model-calls` and `max-usage-units` can now trip on validator-generated
  provider responses that were previously hidden by outer-call accounting.
  Provider calls that throw without a response remain visible as attempts but
  do not increment the existing response-based `modelCalls` counter.
- Trace append must precede session/metric/quota accounting for a received
  response. This keeps an intentionally thrown quota failure from hiding the
  response and normalized usage that caused it.
- When execution or an earlier cleanup step is known to have failed and the
  trace writer remains usable, finalization writes `TRACE_COMPLETED` with
  `FAILED`. Only failure to append `TRACE_COMPLETED` itself leaves a
  missing/non-final artifact. PR 02 alone owns the noncanonical
  `EXECUTION_OBSERVATION_ENDED/CORE_FINALIZATION_FAILED` live event for a core
  completion operation that does not return successfully.
- The legacy `bifrost-cli` is an in-repository consumer to update, but Phase 2
  explicitly classifies it as deprecated and not a compatibility target
  (`ai/thoughts/plans/bifrost_console_phase_2_ui_console.md:124`).

## What We're NOT Doing

- No live projection, observation handle, registry, catalog, REST, SSE,
  authentication, or artifact-streaming adapter (PRs 02-06).
- No Go console project, Go parsing, semantic calculations, browser UI, MCP, or
  portable debugging skill (PRs 07-19).
- No historical trace reader, schema migration, compatibility constructor,
  alias, fallback, dual record form, trace version, container version, or
  schema registry.
- No changes to trace persistence policy, file ownership, chunk layout,
  journal selection, or the public `SkillExecutionView` event vocabulary.
- No provider price table, monetary-cost calculation, inferred relationship,
  root-cause diagnosis, or inferred usage distribution.
- No fabricated `TRACE_COMPLETED` when its own append cannot succeed, and no
  PR 02 exceptional observation event.

## Skill-Authoring Documentation Impact

**Impact**: Affected

- **Rationale**: Skill authors need to know that model/validation retries are
  separate physical attempts, returned retry responses consume model-call and
  usage quotas, and attempts appear with explicit trace relationships. They
  also need the current-run-only trace limitation, terminal outcome/failure
  semantics, and the distinction between unavailable and Go-derived
  unattributed usage. No YAML field or invocation input changes.
- **Documents to update**:
  `ai/skill-authoring/traces-and-debugging.md` (new focused topic),
  `ai/skill-authoring/README.md`, and
  `ai/skill-authoring/evidence-contracts.md`.
- **Supporting evidence**:
  `ModelAttemptCallAdvisorTest`, `ExecutionTraceContractTest`,
  advisor tests, `SessionUsageServiceTest`, `SessionQuotaTest`, terminal
  coordinator/runner tests, and the checked-in console trace fixture corpus.
- **Coverage table update**: Required. Add a routing row for trace/retry/usage
  diagnosis; mark “Traces and debugging” as initial source-verified; note that
  “Execution limits and quotas” has foundational physical-model-attempt
  coverage while its complete topic remains incomplete.
- **LLM-first usability**: The new topic will lead with applicability and a
  compact field/meaning table, distinguish enforced behavior from diagnostic
  interpretation, link to evidence and quota topics instead of duplicating
  them, name current implementation/test anchors, and state that trace fields
  are current-checkout diagnostics rather than a durable author-facing schema.

## Contract and Compatibility Impact

| Surface | Classification and supporting evidence | Planned compatibility treatment |
| --- | --- | --- |
| Application API | `SkillTemplate` observer, `SkillExecutionView`, and `SkillExecutionEvent` are allowlisted/documented API consumers of the journal, not raw trace records (`BifrostPublicSurfaceArchitectureTest.java:28`, `README.md:146`). | Preserve. Regression-test unchanged journal entries and mapped execution views. |
| Supported SPI | No supported SPI package or type exists in this area (`BifrostPublicSurfaceArchitectureTest.java:247`). | No impact; add no SPI or replacement seam. |
| Configuration and manifest contracts | Trace persistence and session quota properties remain documented; linter/output-schema retry syntax remains unchanged. Every physical retry response now counts as one model response/usage fact; provider calls that throw without a response retain the existing no-count behavior. | Preserve syntax/defaults. Intentionally correct successful retry-response accounting and update focused tests plus skill-authoring guidance atomically. |
| Persisted or serialized contracts | No deliberately durable cross-version trace contract exists. `BifrostSession`/execution-view serialization is not changed. | Preserve non-trace serialization. Do not add migration or versioning. |
| Ephemeral diagnostic formats | `TraceRecord`, NDJSON, model/advisor metadata, completion metadata, and fixtures change. The design lens explicitly classifies traces here. | Intentional atomic current-release break: remove `schemaVersion`, add explicit relationships, update writer/reader/projector/tests/CLI/fixtures together. |
| Internal or accidentally exposed implementation | State/recorder APIs, trace contexts, engine constructors, advisor wiring, usage extraction placement, and technically public internal helper types change. | Replace atomically; remove obsolete outer tracing helpers and update the architecture allowlist. |

- **Evidence of supported contracts**: API architecture allowlist, README
  observer documentation, documented quota/persistence properties, approved PR
  01 ticket, and the Phase 1 current-release Java/Go agreement.
- **Intended breaks**:
  raw trace JSON loses `schemaVersion`; model lifecycle records become
  per-physical-attempt; model-call/usage quota accounting includes advisor
  retries that return responses; new terminal fields are mandatory for
  finalized current-release traces.
- **In-repository consumers to update**:
  trace writer/reader/projector constructors, execution engines, planning,
  advisors/resolver, session runner/coordinator, usage and trace tests,
  architecture allowlists, `bifrost-cli/main.go`, fixture corpus, README-adjacent
  skill-authoring guidance, and autoconfiguration tests.
- **Public-surface delta**:
  no Application API or Supported SPI change; no new Spring extension point.
  Technically public internal `ModelTraceCallback` and `ModelTraceResult` are
  removed, `ModelTraceContext`, `AdvisorTraceContext`, `TraceCompletion`,
  `ExecutionStateService`, `ExecutionTraceRecorder`, and affected constructors
  change atomically, and the technically public internal `TraceOutcome` enum is
  added for cross-package collaboration. Attempt-scope and advisor
  implementation types remain package-private. Update the architecture
  allowlist only for `TraceOutcome`, with an internal-collaboration rationale.
- **Shim decision**: **No shim.** Raw traces and affected Java types are
  ephemeral/internal pre-1.0 surfaces, the ticket explicitly forbids
  compatibility forms, and every repository consumer can change atomically.
- **Java-to-Go boundary coordination**: **Required, staged by roadmap.** PR 01
  establishes Java-produced NDJSON and semantic expected results without a Go
  parser. PR 06 must make production/acquisition fixtures reproducible across
  the adapter boundary; PR 13 must consume the exact fields and expected
  calculations. A later consumed-NDJSON change must update Java, Go, fixtures,
  semantic tests, and concise guidance in one Bifrost release. No independent
  trace compatibility marker is introduced.

## Implementation Approach

Use one package-private `ModelAttemptCallAdvisor` installed by
`SpringAiSkillChatClientFactory` for every Bifrost `ChatClient`. Give it order
`Ordered.LOWEST_PRECEDENCE - 1`, immediately before Spring AI's terminal model
advisor. Engine code opens/closes the existing `MODEL_CALL` frame and places a
call-local attempt scope and segment information into the request advisor
context. The physical advisor allocates the attempt, records final request
prepared/sent facts, calls downstream once, extracts normalized usage from the
returned response, and delegates a single completion operation that appends
the response before updating session totals, metrics, and quota enforcement.

Use a call-local attempt scope containing one opaque sequence ID and a
thread-safe monotonic attempt counter. It is not stored on the session and
cannot leak between concurrent or nested executions. The advisor returns the
immutable attempt facts in `ChatClientResponse.context()` so every wrapping
validator records the exact response it evaluated.

Replace the untyped terminal metadata assembly with a typed internal
`TraceCompletion`/`TraceOutcome` path. Create a terminal failure ID once at the
top-level failure boundary, reuse it for the terminal error and root closure,
and supply it to finalization. `DefaultExecutionStateService` adds the current
session usage snapshot before the trace handle appends `TRACE_COMPLETED`. Go
PR 13 derives unattributed usage from that snapshot and the response records.

Keep the journal projector ignorant of the new model/completion fields. Update
its constructors for the new `TraceRecord` shape and add a focused regression
that proves supported observer output is unchanged.

## Phase 1: Simplify the Current-Release Trace Envelope

### Overview

Remove per-record versioning and the obsolete outer model-call wrapper before
adding the new single physical-attempt path.

### Changes Required

#### 1. Remove `schemaVersion` everywhere

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/TraceRecord.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/NdjsonExecutionTraceReader.java`
- All Java tests constructing `TraceRecord`
- `bifrost-cli/main.go`

**Changes**:

- Delete the record component, constant, JSON property, defaulting behavior,
  writer argument, reader reconstruction copy, test literals, and Go decode
  field.
- Keep outer trace/session identity, sequence, timestamp, vocabulary, frame
  fields, metadata, data, and chunk reconstruction unchanged.
- Add serialization assertions proving `schemaVersion` is absent and a
  current-shape record round-trips.

#### 2. Remove the outer model tracing abstraction

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ModelTraceCallback.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ModelTraceResult.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/state/ExecutionStateService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/state/DefaultExecutionStateService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/DefaultMissionExecutionEngine.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/planning/DefaultPlanningService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/step/StepLoopMissionExecutionEngine.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java`

**Changes**:

- Delete `traceModelCall`, `ModelTraceCallback`, and `ModelTraceResult`; do not
  leave a compatibility overload.
- Retain engine ownership of `MODEL_CALL` frame open/close and failure status.
- Remove the three outer `recordModelResponse` calls and obsolete
  `ModelUsageExtractor` constructor dependencies; retain
  `SessionUsageService` where it is still needed for mission/tool/linter facts.
- Remove obsolete internal-public allowlist entries and update direct tests and
  constructor wiring atomically.

### Success Criteria

#### Automated Verification

- [x] `rg "schemaVersion|CURRENT_SCHEMA_VERSION" bifrost-spring-boot-starter bifrost-cli` returns no production/test fixture consumer.
- [x] Trace reader/writer/chunk tests pass:
  `mvn -pl bifrost-spring-boot-starter -Dtest=NdjsonExecutionTraceReaderTest,NdjsonTraceRecordWriterTest,ExecutionTraceHandleTest test`.
- [x] Compilation proves all outer wrapper callers and compatibility
  constructors are removed:
  `mvn -pl bifrost-spring-boot-starter -DskipTests compile`.

#### Manual Verification

- [x] Inspect one generated NDJSON line and confirm it begins with current trace
  identity fields and contains no record/container version.
- [x] Confirm no legacy read path or dual constructor remains.

---

## Phase 2: Record and Account for Physical Model Attempts

### Overview

Install the final pre-provider advisor and make one normalized response fact
drive trace, session, Micrometer, and quota accounting.

### Changes Required

#### 1. Add call-local attempt identity and final pre-provider instrumentation

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/chat/ModelAttemptCallAdvisor.java` (new, package-private)
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/chat/SpringAiSkillChatClientFactory.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ModelTraceContext.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/AdvisorTraceContext.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/chat/DefaultSkillAdvisorResolver.java`
- Linter/output-schema/evidence advisor implementations

**Changes**:

- Add a package-private attempt scope with a UUID retry sequence and atomic
  positive counter; add a fresh UUID attempt ID per downstream call.
- Register `ModelAttemptCallAdvisor` unconditionally, including step-execution
  clients with final-response validators filtered out.
- Assert its order is after all Bifrost validators and before Spring AI's
  terminal model advisor.
- Put immutable attempt facts into the returned response context.
- Extend advisor trace contexts/facts to carry the exact sequence and attempt
  evaluated, including pass, retry-requested, and exhaustion.
- Keep generated identities out of prompts, model options, and response data.

#### 2. Propagate scopes from each logical retry boundary

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/DefaultMissionExecutionEngine.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/planning/DefaultPlanningService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/step/StepLoopMissionExecutionEngine.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/attachment/MissionUserMessageSender.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/attachment/SpringAiMissionUserMessageSender.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/attachment/SpringAiMissionUserMessageSenderTest.java`

**Changes**:

- Create one sequence for the direct mission call and each step call.
- Create the planning sequence before the quality loop and reuse it on every
  plan attempt; attach the latest physical attempt to
  `PLAN_VALIDATION_FAILED` and `PLAN_RETRY_REQUESTED`.
- Pass segment and scope through Spring AI advisor context without making them
  public invocation or manifest parameters.
- Ensure nested skills create new scopes and concurrent sessions do not share
  counters.

#### 3. Unify response trace and accounting

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/state/ExecutionStateService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/state/DefaultExecutionStateService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionTraceRecorder.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/DefaultExecutionTraceRecorder.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/usage/ModelUsageExtractor.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/usage/DefaultSessionUsageService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`

**Changes**:

- Have the physical advisor build request/response diagnostic payloads from the
  final `ChatClientRequest` and returned `ChatClientResponse`.
- Extract one `ModelUsageRecord`, omit provider-native usage from consumed
  metadata, append `MODEL_RESPONSE_RECEIVED` with normalized usage, then pass
  that same object to `SessionUsageService#recordModelResponse`.
- Count every received physical attempt once in session totals, metrics, and
  quotas. Preserve quota behavior of updating the recorded snapshot before
  throwing.
- If the terminal provider advisor throws before producing a response, retain
  prepared/sent attempt records but do not call
  `SessionUsageService#recordModelResponse`; `modelCalls` therefore preserves
  its current response-based meaning.
- Keep no second engine-side extraction/accounting path.

### Success Criteria

#### Automated Verification

- [x] A focused `ModelAttemptCallAdvisorTest` proves one ordinary call produces
  attempt 1, downstream retries produce distinct attempts in the same
  sequence, nested executions use distinct sequences, and concurrent calls do
  not share identity/counters.
- [x] Advisor integration tests prove each validation/retry fact links to the
  response it evaluated.
- [x] Usage/quota/metric tests prove N physical responses produce N trace
  responses, N model calls, matching normalized totals/counters, and one quota
  trip at the configured boundary.
- [x] A provider exception after prepared/sent records produces no response
  usage and leaves `modelCalls` unchanged.
- [x] Cross-engine trace tests pass:
  `mvn -pl bifrost-spring-boot-starter -Dtest=ExecutionTraceContractTest,ModelAttemptCallAdvisorTest,SessionUsageServiceTest,SessionQuotaTest,MicrometerUsageMetricsRecorderTest test`.
- [x] Autoconfiguration and architecture boundary tests pass:
  `mvn -pl bifrost-spring-boot-starter -Dtest=BifrostAutoConfigurationTests,BifrostAutoConfigurationBoundaryTest,BifrostPublicSurfaceArchitectureTest test`.

#### Manual Verification

- [x] Inspect a validator retry trace and confirm every physical request and
  response has explicit identity and no relationship depends on adjacency.
- [x] Confirm an unavailable-usage response is visibly `UNAVAILABLE`, not
  presented as exact zero.

---

## Phase 3: Make Terminal Outcome, Failure, and Usage Explicit

### Overview

Create one terminal disposition before cleanup and make finalization serialize
the authoritative outcome, failure link, and reconciliation totals.

### Changes Required

#### 1. Add typed terminal disposition and failure identity

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/TraceCompletion.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/TraceOutcome.java` (new)
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionCoordinator.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSessionRunner.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/TraceFailureMetadata.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/state/ExecutionStateService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ExecutionTraceRecorder.java`

**Changes**:

- Use `SUCCEEDED`, `FAILED`, and `ABORTED` exactly.
- Allocate the terminal failure ID once when the top-level outcome becomes
  failed/aborted and reuse it on terminal error, root closure, and completion.
- Generate a separate ID for each non-terminal `ERROR_RECORDED`.
- Apply the same terminal completion rules to standalone session-runner paths,
  including open-frame failure, without exposing exception objects or unsafe
  messages.
- Preserve existing propagation/suppression behavior when root closure,
  journal projection, or trace finalization fails.

#### 2. Persist the terminal usage snapshot

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/BifrostSession.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/state/DefaultExecutionStateService.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/DefaultExecutionTraceHandle.java`
- Usage snapshot/value types

**Changes**:

- Snapshot authoritative session usage during completion.
- Do not calculate or persist a second Java-side unattributed total. The
  checked-in expected semantic results state the derived value PR 13 must
  calculate from response records and the terminal snapshot.
- Append `TRACE_COMPLETED` last with terminal semantics, then retain/delete the
  artifact under the unchanged policy.
- If execution, root closure, journal projection, or another earlier completion
  step is known to have failed but the trace remains appendable, create/reuse a
  terminal failure identity and append `TRACE_COMPLETED` with `FAILED`.
- If appending `TRACE_COMPLETED` itself fails, preserve the existing propagated
  or suppressed cleanup failure and leave the artifact missing/non-final; do
  not claim completion through another canonical record.

#### 3. Preserve the completed journal/Application API

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionJournalProjector.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionJournalProjectionContractTest.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skillapi/SkillExecutionViewMapper.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/skillapi/SkillExecutionViewMapperTest.java`

**Changes**:

- Keep new model/completion records excluded from journal projection.
- Ensure adding `failureId` to raw error metadata does not change the existing
  safe error summary or observer event ordering/content.

### Success Criteria

#### Automated Verification

- [x] Coordinator and session-runner tests cover success, failure, interrupt
  abort, non-terminal error followed by success, open-frame failure, and core
  finalization failure.
- [x] Terminal tests assert matching failure IDs, absence of terminal ID on
  success, final-record position, and the authoritative terminal usage snapshot.
- [x] Root-closure and journal-projection failures write a final
  `TRACE_COMPLETED/FAILED` when the trace remains appendable; failure to append
  `TRACE_COMPLETED` leaves no fabricated completion.
- [x] Journal and supported observer regression tests pass:
  `mvn -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorTest,BifrostSessionRunnerTest,ExecutionJournalProjectionContractTest,SkillExecutionViewMapperTest test`.
- [x] Persistence-policy and finalization-failure tests still pass.

#### Manual Verification

- [x] Compare failed and aborted traces and confirm outcome and terminal cause
  are independent from the legacy `errored` flag.
- [x] Confirm a known earlier cleanup failure is documented as
  `TRACE_COMPLETED/FAILED`, while failure of the completion append itself
  leaves no completion record.

---

## Phase 4: Establish the Java-Produced Semantic Fixture Corpus

### Overview

Check in the executable current-release agreement needed by later Go work,
including valid semantic results and deliberately invalid artifacts.

### Changes Required

#### 1. Add deterministic corpus ownership and regeneration

**Files**:

- `bifrost-console-fixtures/README.md` (new)
- `bifrost-console-fixtures/traces/*.ndjson` (new)
- `bifrost-console-fixtures/expected/*.json` (new)
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/trace/ConsoleTraceFixtureCorpusTest.java` (new)
- Test-only deterministic fixture builder/generator helpers

**Changes**:

- Use fixed clocks, session/trace/frame IDs, UUID suppliers, thread name, and
  stable map/list ordering.
- Generate valid artifacts through the real `DefaultExecutionTraceHandle` and
  writer. Derive malformed artifacts through named, minimal mutations of a
  generated valid artifact.
- In normal test mode, regenerate in a temporary directory and byte-compare
  every checked-in trace and expected result; reject extra or missing files.
- Support explicit regeneration only with:
  `mvn -pl bifrost-spring-boot-starter -Dtest=ConsoleTraceFixtureCorpusTest -Dbifrost.console.fixtures.regenerate=true test`.
- Document that PR 06 will exercise the same corpus through artifact streaming
  and PR 13 will make Go consume the expected results; do not add a second copy.

#### 2. Cover the complete semantic matrix

**Valid trace/expected-result cases**:

- `single-attempt-success`
- `terminal-failure`
- `terminal-abort`
- `advisor-retry`
- `nested-retry-sequences`
- `validation-exhaustion`
- `unavailable-usage`
- `unattributed-usage`
- `nonterminal-error-then-success`
- `chunked-payload`

**Invalid artifact cases**:

- malformed JSON
- inconsistent trace/session identity
- duplicate or non-monotonic sequence
- incomplete/mismatched chunks
- missing `TRACE_COMPLETED`
- non-final `TRACE_COMPLETED`
- unsupported consumed enum/value
- contradictory usage reconciliation

Expected JSON asserts identities, attempt/retry membership, outcome,
terminal-failure marking, normalized and terminal usage, Go-derived
unattributed usage, frame/chunk relationships, and validity. It does not
contain speculative Go UI shapes or diagnoses.

### Success Criteria

#### Automated Verification

- [x] Corpus verification passes:
  `mvn -pl bifrost-spring-boot-starter -Dtest=ConsoleTraceFixtureCorpusTest test`.
- [x] Every valid fixture has exactly one final `TRACE_COMPLETED`; every
  intentionally invalid fixture has one named invalidity and expected error
  category.
- [x] No fixture contains `schemaVersion`, inferred relationships, or
  nondeterministic IDs/timestamps/paths.
- [x] Full starter tests pass:
  `mvn -pl bifrost-spring-boot-starter test`.

#### Manual Verification

- [x] Review fixture diffs for semantic readability and verify generated noise
  does not obscure the relationship under test.
- [x] Confirm the corpus is accessible to a future top-level Go module without
  copying Java test resources.

---

## Phase 5: Synchronize Skill-Authoring Guidance and Final Boundaries

### Overview

Document the author-facing behavior established by executable code and perform
the final public/Spring surface audit.

### Changes Required

#### 1. Add focused trace/debugging guidance

**Files**:

- `ai/skill-authoring/traces-and-debugging.md` (new)
- `ai/skill-authoring/README.md`
- `ai/skill-authoring/evidence-contracts.md`

**Changes**:

- Explain physical attempts, retry sequence scope, usage precision,
  unavailable versus unattributed usage, terminal outcome/failure links, and
  current-checkout/current-run limitations.
- State that validator/evidence output correction can cause additional model
  attempts and consume model/usage quotas while reusing completed tool work.
- Add source/test/fixture anchors and update routing/coverage exactly as
  described in the impact section.
- Do not publish an exhaustive durable trace schema or imply cross-version
  support.

#### 2. Close architecture and compatibility checks

**Files**:

- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostAutoConfigurationBoundaryTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`

**Changes**:

- Assert no Application API, Supported SPI, bean override, or
  `@ConditionalOnMissingBean` surface was added.
- Keep new wiring package-private/framework-owned.
- Reconcile the internal-public allowlist with removed/changed types and reject
  accidental visibility.

### Success Criteria

#### Automated Verification

- [x] Architecture tests pass:
  `mvn -pl bifrost-spring-boot-starter -Dtest=BifrostPublicSurfaceArchitectureTest,BifrostAutoConfigurationBoundaryTest,BifrostAutoConfigurationTests test`.
- [x] Full repository verification passes: `mvn verify`.
- [x] Skill-authoring routing and coverage name the new document, and every
  material claim has a focused source/test/fixture anchor.
- [x] Guidance satisfies the README's LLM-First Authoring Standard and contains
  no unresolved or speculative semantics.

#### Manual Verification

- [x] Read the new topic in isolation and confirm an LLM can distinguish
  physical attempts, validation retries, missing usage, terminal failure, and
  the current-run compatibility boundary.
- [x] Review the final public type and Spring bean diff and confirm there is no
  accidental application extension point.

---

## Testing Strategy

Create the dedicated testing plan with `ai/commands/3_testing_plan.md` before
implementation. It should start with failing tests for physical retry
cardinality, terminal failure linkage, schema-version absence, and fixture
reconciliation, then cover:

### Unit Tests

- `TraceRecord` JSON shape and chunk reconstruction without a version field.
- Attempt-scope identity, positive numbering, isolation, and advisor ordering.
- Usage extraction/serialization and one-fact accounting.
- Terminal disposition, failure IDs, and terminal snapshot serialization.
- Deterministic fixture generation and malformed mutation classification.

### Integration Tests

- Direct, planning-quality, step, linter, output-schema, and evidence retry
  paths through a real Spring AI advisor chain.
- The physical-attempt advisor is traversed exactly once for every downstream
  provider call, preserves request context across validator retries, and still
  records the response when the caller obtains it through the `content()`
  fallback.
- Quota and Micrometer counts across multiple physical provider calls.
- Top-level success/failure/abort and standalone runner finalization.
- Journal-to-`SkillExecutionView` regression.
- Checked-in semantic corpus regenerated by the Java writer.

### Manual Testing Steps

1. Run one successful sample with trace persistence `ALWAYS` and inspect the
   physical attempt group and terminal usage snapshot.
2. Run a validation retry and verify the retry/attempt linkage and quota/metric
   cardinality agree.
3. Run a terminal failure and an interrupt abort and compare their explicit
   outcome/failure links.
4. Force an earlier cleanup failure and verify `TRACE_COMPLETED/FAILED`; then
   force the completion append itself to fail and verify no completion is
   claimed.
5. Regenerate the corpus and review that a second run produces no diff.

## Performance Considerations

- The final advisor adds bounded UUID allocation, small metadata maps, and
  existing trace writes per physical attempt; it adds no network call or
  unbounded collection.
- Attempt counters are call scoped and bounded by already-recorded model
  activity.
- Java snapshots terminal usage in O(1); it does not reread the NDJSON artifact
  or calculate the Go-owned unattributed delta.
- Recording every physical retry increases trace volume intentionally and
  proportionally to actual provider work.
- Keep provider-native usage opaque and out of required semantic metadata to
  avoid unbounded/cross-provider contract growth.

## Migration Notes

There is no migration. Old trace artifacts are not read by the new
current-release tooling, and no compatibility constructor or legacy reader is
retained. Regenerate all checked-in trace fixtures atomically. The deprecated
CLI drops only its obsolete decode field; it receives no new semantic-analysis
commitment.

The accounting correction can make `max-model-calls` and `max-usage-units`
enforce earlier for skills whose validators produce additional physical
responses. Provider calls that throw without returning a response continue not
to increment `modelCalls`. This is an intentional pre-1.0 behavior change and
must be called out in guidance and release notes rather than hidden behind dual
accounting.

## References

- Ticket:
  `ai/thoughts/tickets/bifrost-console-pr-01-canonical-trace-semantics.md`
- Research:
  `ai/thoughts/research/2026-07-24-bifrost-console-pr-01-canonical-trace-semantics.md`
- Framework policy:
  `ai/thoughts/framework-feature-design-lens.md`
- Roadmap:
  `ai/thoughts/plans/2026-07-23-bifrost-console-implementation-roadmap.md`
- Phase 1 design:
  `ai/thoughts/plans/bifrost_console_phase_1_observability_foundation.md`
- Phase 2 design:
  `ai/thoughts/plans/bifrost_console_phase_2_ui_console.md`
- Phase 3 design:
  `ai/thoughts/plans/bifrost_console_phase_3_llm_runtime_inspector.md`
- Developer workflows:
  `ai/thoughts/plans/bifrost_console_workflows.md`
- Spring AI 1.1.6 source verified locally from the Maven dependency:
  `DefaultAroundAdvisorChain`, `DefaultChatClient`, and
  `ChatModelCallAdvisor`.
