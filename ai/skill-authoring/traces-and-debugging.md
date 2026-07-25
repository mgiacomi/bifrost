---
audience: bifrost-skill-builder
status: development
applies_to: current-repository-checkout
coverage: source-verified
---

# Traces and Debugging

## Applicability

Use execution traces to explain what Bifrost and the model provider did during one run: prompt mutation, physical provider attempts, validation retries, tools, evidence, failures, and final usage. Traces are diagnostics for the current checkout and current run. They are not a durable cross-version API, and authors MUST NOT build application behavior on their serialized shape.

## Model attempts and retries

One physical attempt is one downstream provider call. Each attempt has an `attemptId`, a positive `attemptNumber`, and a `retrySequenceId`. Attempts created while correcting one logical call share the retry sequence and increase the attempt number; a nested or separate logical call uses a different sequence.

For an attempt that reaches the provider and returns, the trace records prepared request, sent request, and received response facts with the same attempt identity. If the provider throws, prepared and sent facts can exist without a received response. Validator mutation facts identify the exact attempt whose output caused a pass, retry, or exhaustion.

Linter, output-schema, planning-quality, and evidence correction can therefore create additional physical model attempts. Those attempts consume model-call and usage quotas. Evidence output correction reuses already completed tool work; it does not rerun tools merely to retry the final model output.

## Usage interpretation

Response usage is normalized as prompt, completion, and total units with a precision:

| Precision | Meaning |
| --- | --- |
| `EXACT` | Provider supplied usable counts. |
| `ESTIMATED` | Bifrost estimated counts from available request/response content. |
| `UNAVAILABLE` | Neither provider counts nor a defensible estimate was available. |

Each returned physical attempt is traced before its usage is applied to quota and metrics accounting, and is accounted once. `UNAVAILABLE` is a property of an individual attempt. `Unattributed usage` is different: Console derives it component-wise when the terminal session snapshot exceeds the sum of attributed response facts. Java does not emit a separate unattributed counter.

## Terminal outcome and failures

The final trace record carries one outcome: `SUCCEEDED`, `FAILED`, or `ABORTED`, plus the authoritative terminal session-usage snapshot. A failed or aborted completion has a `terminalFailureId` that links to the corresponding `ERROR_RECORDED` fact. Success has no terminal failure ID. Earlier nonterminal errors can coexist with a successful outcome.

If finalization itself cannot append a completion record, do not infer one. A missing completion means the artifact is incomplete, not implicitly failed or successful.

## Debugging procedure

1. Confirm there is exactly one final completion record.
2. Read its outcome, terminal failure link, and terminal usage.
3. Group model response facts by retry sequence and order by attempt number.
4. Follow validator mutation facts back to the exact attempt.
5. Compare attributed response usage with terminal usage; treat a positive remainder as unattributed and a negative remainder as contradictory.
6. Inspect linked error facts and frame relationships, keeping recovered errors separate from the terminal cause.
7. Reproduce with the same checkout before treating a serialized-field difference as a runtime defect.

## Implementation and test anchors

- [`ModelAttemptCallAdvisor.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/chat/ModelAttemptCallAdvisor.java) owns the final pre-provider attempt boundary.
- [`ModelTraceContext.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/ModelTraceContext.java) owns retry-sequence and attempt identity.
- [`TraceCompletion.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/TraceCompletion.java) and [`TraceOutcome.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/TraceOutcome.java) define terminal semantics.
- [`ModelAttemptCallAdvisorIntegrationTest.java`](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/chat/ModelAttemptCallAdvisorIntegrationTest.java) protects retry cardinality, failure behavior, usage, and quota enforcement.
- [`BifrostSessionRunnerTest.java`](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/core/BifrostSessionRunnerTest.java) and [`ExecutionCoordinatorTest.java`](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/ExecutionCoordinatorTest.java) protect terminal failure linkage.
- [`bifrost-console-fixtures`](../../bifrost-console-fixtures/README.md) is the executable cross-language semantic corpus.
