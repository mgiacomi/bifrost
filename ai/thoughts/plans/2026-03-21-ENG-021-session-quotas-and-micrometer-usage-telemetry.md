# ENG-021 Session Quotas and Micrometer Usage Telemetry Implementation Plan

## Overview

Implement session-level usage accounting, configurable quota enforcement, and Micrometer-backed runtime telemetry for Bifrost missions. The implementation should extend the existing session/runtime boundaries so quota checks and metrics are emitted from the same execution path that currently handles mission frames, tool execution, and linter outcomes.

## Current State Analysis

Bifrost already enforces two session guardrails: maximum execution depth through `BifrostSession.pushFrame(...)` and mission timeout through `DefaultMissionExecutionEngine.executeMission(...)` ([C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:207](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java#L207), [C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:72](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java#L72)). Runtime event writes already pass through `DefaultExecutionStateService`, which logs plan creation/updates, tool calls/results, linter outcomes, and errors into the session journal ([C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:25](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java#L25)).

What is missing for ENG-021 is a dedicated usage-accounting abstraction, quota configuration beyond `maxDepth` and `missionTimeout`, consumption of Spring AI usage metadata in the mission path, and Micrometer metrics emitted from checked-in source. `DefaultMissionExecutionEngine` currently ends the model call at `.call().content()`, which discards `ChatResponseMetadata` and `Usage` data that Spring AI 1.1.2 makes available ([C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:65](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java#L65)). The current starter module also has no explicit Micrometer dependency or `MeterRegistry` integration in checked-in code ([C:/opendev/code/bifrost/bifrost-spring-boot-starter/pom.xml:18](C:/opendev/code/bifrost/bifrost-spring-boot-starter/pom.xml#L18)).

## Desired End State

After implementation, Bifrost should create and maintain session-local usage state through a dedicated runtime abstraction, enforce configured session workload limits in the main execution path, and publish Micrometer metrics that describe model activity, tool activity, linter outcomes, and quota violations. The runtime should consume Spring AI `ChatResponse` usage metadata when available, record whether usage values are exact or heuristic, and preserve current mission, tool, and linter behavior when quotas are not exceeded.

Verification should show that:
- configured quota boundaries fail sessions with explicit runtime exceptions
- below-threshold sessions still complete successfully
- Spring AI usage metadata is consumed when present and heuristic usage is recorded when not available
- Micrometer meters are emitted for mission/model activity, tool calls, linter outcomes, and guardrail trips

### Key Discoveries:
- `DefaultExecutionStateService` is already the session mutation boundary for plans, tools, linter outcomes, and errors ([C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:25](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java#L25)).
- `DefaultToolCallbackFactory` already centralizes tool-call start/completion/failure events with stable capability and task information ([C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:59](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java#L59)).
- `DefaultSkillAdvisorResolver` and `LinterCallAdvisor` already provide a single place to observe linter retries and final linter outcomes ([C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java:38](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java#L38), [C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java:54](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java#L54)).
- Spring AI 1.1.2 exposes `ChatResponse.getMetadata().getUsage()` as the common usage surface, with provider-specific native usage available under `Usage.getNativeUsage()` in local dependency sources.
- The current journal is a storage primitive and test surface; a developer-facing journal query API is explicitly scoped to ENG-022 rather than ENG-021 ([C:/opendev/code/bifrost/ai/thoughts/tickets/eng-022-safe-skill-thoughts-api-and-journal-views.md:35](C:/opendev/code/bifrost/ai/thoughts/tickets/eng-022-safe-skill-thoughts-api-and-journal-views.md#L35)).

## What We're NOT Doing

- Provider-billing-accurate accounting beyond what Spring AI or provider responses already expose
- A distributed or shared quota store across sessions or JVM instances
- A developer-facing journal query or `getSkillThoughts(...)` API
- Journal redaction or a separate diagnostics presentation layer
- Sample app walkthrough documentation changes

## Implementation Approach

Introduce a runtime usage-accounting subsystem that is session-scoped but kept behind dedicated services and value objects rather than scattered counters on `BifrostSession`. The plan should reuse the existing runtime entry points:
- mission execution for model-call accounting and quota checks
- tool callback factory for tool-invocation accounting and metrics
- linter outcome recording for retry/outcome accounting and accuracy metrics

Micrometer should be integrated through a dedicated metrics recorder abstraction so runtime logic can publish stable counters/timers without coupling business logic directly to `MeterRegistry`. Quota enforcement should happen immediately after each accounting mutation so failures occur in-band and deterministically.

## Phase 1: Usage Accounting Foundation

### Overview
Create the configuration, session accounting model, exceptions, and service abstractions needed to represent usage, evaluate quotas, and expose the information to downstream runtime code.

### Changes Required:

#### 1. Session quota properties and runtime configuration
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java`
**Changes**: Extend session properties with first-class quota settings for bounded workload signals such as max skill invocations, max tool invocations, max linter retries, max cumulative model calls, and max cumulative model tokens or heuristic units.

```java
public class BifrostSessionProperties {

    @Min(1)
    private int maxDepth = DEFAULT_MAX_DEPTH;

    @NotNull
    private Duration missionTimeout = DEFAULT_MISSION_TIMEOUT;

    @Valid
    @NotNull
    private Quotas quotas = new Quotas();

    public static class Quotas {
        @Min(1)
        private int maxSkillInvocations = 64;
        @Min(1)
        private int maxToolInvocations = 128;
        @Min(1)
        private int maxLinterRetries = 32;
        @Min(1)
        private int maxModelCalls = 64;
        @Min(1)
        private int maxUsageUnits = 200_000;
    }
}
```

#### 2. Usage-accounting model and quota evaluation abstraction
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/usage/...`
**Changes**: Add a dedicated package for usage state, model-usage snapshots, quota policies, violation types, and the main runtime service interface.

```java
public interface SessionUsageService {

    SessionUsageSnapshot snapshot(BifrostSession session);

    void recordMissionStart(BifrostSession session, String skillName);

    void recordModelResponse(BifrostSession session, String skillName, ModelUsageRecord usageRecord);

    void recordToolCall(BifrostSession session, String skillName, String capabilityName);

    void recordLinterOutcome(BifrostSession session, LinterOutcome outcome);
}
```

```java
public record ModelUsageRecord(
        int promptUnits,
        int completionUnits,
        int totalUnits,
        UsagePrecision precision,
        @Nullable Object nativeUsage) {
}
```

```java
public enum UsagePrecision {
    EXACT,
    HEURISTIC,
    UNAVAILABLE
}
```

#### 3. Session storage for usage state and explicit quota exception types
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
**Changes**: Add a dedicated usage-state field or snapshot container to the session so accounting remains session-local and serializable without scattering primitive counters throughout the runtime.

```java
public final class BifrostSession {

    private SessionUsageSnapshot sessionUsage;

    public Optional<SessionUsageSnapshot> getSessionUsage() { ... }

    public void setSessionUsage(SessionUsageSnapshot sessionUsage) { ... }
}
```

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/BifrostQuotaExceededException.java`
**Changes**: Add an explicit runtime exception carrying session ID, guardrail type, configured limit, observed value, and an operator-readable message.

```java
public class BifrostQuotaExceededException extends RuntimeException {
    public BifrostQuotaExceededException(String sessionId, GuardrailType guardrailType, long limit, long observed) {
        super("Session '%s' exceeded %s quota: observed=%d, limit=%d"
                .formatted(sessionId, guardrailType, observed, limit));
    }
}
```

### Success Criteria:

#### Automated Verification:
- [x] Starter compiles after adding quota properties and usage abstractions: `./mvnw -pl bifrost-spring-boot-starter test`
- [x] Property binding tests pass for new quota settings: `./mvnw -pl bifrost-spring-boot-starter -Dtest=BifrostSessionPropertiesTest test`
- [x] Session JSON tests pass with usage state included: `./mvnw -pl bifrost-spring-boot-starter -Dtest=BifrostSessionJsonTest test`
- [x] New usage-service unit tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*Usage*Test test`

#### Manual Verification:
- [ ] Quota configuration names and defaults read clearly in generated configuration metadata
- [ ] Exception messages are understandable enough for operators reading logs
- [ ] Usage snapshot shape is stable and readable when inspected in a serialized session
- [ ] Added configuration remains consistent with existing `bifrost.session.*` naming

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Runtime Integration and Quota Enforcement

### Overview
Thread usage accounting through mission execution, tool invocation, and linter retries so that quotas are evaluated in the normal execution path and Spring AI usage metadata is consumed when present.

### Changes Required:

#### 1. Mission execution path captures Spring AI usage metadata
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java`
**Changes**: Replace the direct `.content()` mission path with a response-aware path that preserves the `ChatResponse`, extracts `ChatResponseMetadata.getUsage()`, records model usage, and then returns the content string.

```java
ChatClient.CallResponseSpec responseSpec = chatClient.prompt()
        .system(executionPrompt)
        .user(objective)
        .toolCallbacks(visibleTools)
        .call();

ChatResponse chatResponse = responseSpec.chatResponse();
sessionUsageService.recordModelResponse(
        session,
        skillName,
        modelUsageExtractor.extract(chatResponse, objective, executionPrompt));

return chatResponse == null || chatResponse.getResult() == null
        ? ""
        : chatResponse.getResult().getOutput().getText();
```

#### 2. Common model-usage extraction and heuristic fallback
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/usage/ModelUsageExtractor.java`
**Changes**: Convert Spring AI `Usage` into Bifrost `ModelUsageRecord`, fall back to heuristic units when exact usage is missing, and preserve native provider usage for documentation and debugging.

```java
public ModelUsageRecord extract(@Nullable ChatResponse response, String userPrompt, String systemPrompt) {
    Usage usage = response != null && response.getMetadata() != null ? response.getMetadata().getUsage() : null;
    if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
        return new ModelUsageRecord(
                defaultZero(usage.getPromptTokens()),
                defaultZero(usage.getCompletionTokens()),
                defaultZero(usage.getTotalTokens()),
                UsagePrecision.EXACT,
                usage.getNativeUsage());
    }
    int heuristicPrompt = estimateUnits(systemPrompt) + estimateUnits(userPrompt);
    int heuristicCompletion = estimateUnits(extractContent(response));
    return new ModelUsageRecord(
            heuristicPrompt,
            heuristicCompletion,
            heuristicPrompt + heuristicCompletion,
            UsagePrecision.HEURISTIC,
            usage == null ? null : usage.getNativeUsage());
}
```

#### 3. Tool and linter accounting hooks use the shared service
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java`
**Changes**: Record tool invocation counts before and after capability execution so quota checks and metrics are emitted in the same path that already logs tool events.

```java
sessionUsageService.recordToolCall(session, currentSkillName(session), capability.name());
executionStateService.logToolCall(...);
```

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java`
**Changes**: Delegate linter-outcome recording to the usage-accounting service after journaling the outcome so retry counts and final statuses update shared usage state.

```java
public void recordLinterOutcome(BifrostSession session, LinterOutcome outcome) {
    session.setLastLinterOutcome(outcome);
    session.logLinterOutcome(clock.instant(), outcome);
    sessionUsageService.recordLinterOutcome(session, outcome);
}
```

#### 4. Starter wiring for usage services and runtime dependencies
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
**Changes**: Register the new usage-accounting components and thread them into `ExecutionStateService`, `MissionExecutionEngine`, `ToolCallbackFactory`, and `SkillAdvisorResolver`.

```java
@Bean
public SessionUsageService sessionUsageService(BifrostSessionProperties sessionProperties,
                                               UsageMetricsRecorder usageMetricsRecorder) {
    return new DefaultSessionUsageService(sessionProperties.getQuotas(), usageMetricsRecorder);
}
```

### Success Criteria:

#### Automated Verification:
- [x] Mission execution tests pass after switching to response-aware model capture: `./mvnw -pl bifrost-spring-boot-starter -Dtest=MissionExecutionEngineTest test`
- [x] Coordinator integration tests pass with quota-aware runtime wiring: `./mvnw -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorIntegrationTest test`
- [x] New quota enforcement tests pass for mission, tool, and linter boundaries: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*Quota*Test test`
- [x] Linter integration tests still pass with shared usage accounting: `./mvnw -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorLinterIntegrationTest test`

#### Manual Verification:
- [ ] A normal mission below thresholds returns the same final content as before
- [ ] A deliberately constrained session fails with an explicit quota error that names the tripped guardrail
- [ ] Heuristic vs exact usage behavior can be identified from session state or logs
- [ ] Existing stack-depth and timeout behavior still feel unchanged to callers

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Micrometer Telemetry, Documentation, and Final Verification

### Overview
Add the Micrometer integration layer, emit stable meters from runtime accounting events, and expand the test/documentation surface around exact-versus-heuristic accounting and guardrail outcomes.

### Changes Required:

#### 1. Add Micrometer dependency and metrics recorder abstraction
**File**: `bifrost-spring-boot-starter/pom.xml`
**Changes**: Add Micrometer core as an explicit starter dependency if it is not already available through the Spring Boot starter path in this module.

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-core</artifactId>
</dependency>
```

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/usage/UsageMetricsRecorder.java`
**Changes**: Add a dedicated metrics surface that receives normalized accounting events and writes Micrometer counters/timers with stable tags such as `skill`, `outcome`, `guardrail`, and `precision`.

```java
public interface UsageMetricsRecorder {

    void recordModelUsage(String skillName, ModelUsageRecord usageRecord);

    void recordToolInvocation(String skillName, String toolName, String outcome);

    void recordLinterOutcome(LinterOutcome outcome);

    void recordGuardrailTrip(String skillName, GuardrailType guardrailType);
}
```

#### 2. Micrometer-backed implementation and stable meter names
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/usage/MicrometerUsageMetricsRecorder.java`
**Changes**: Implement counters/timers with stable meter names and bounded tag sets.

```java
meterRegistry.counter("bifrost.model.calls",
        "skill", skillName,
        "precision", usageRecord.precision().name()).increment();

meterRegistry.counter("bifrost.tool.calls",
        "skill", skillName,
        "tool", toolName,
        "outcome", outcome).increment();

meterRegistry.counter("bifrost.linter.outcomes",
        "skill", outcome.skillName(),
        "status", outcome.status().name(),
        "linter", outcome.linterType()).increment();

meterRegistry.counter("bifrost.guardrail.trips",
        "skill", skillName,
        "guardrail", guardrailType.name()).increment();
```

#### 3. Tests and implementation notes for metrics and precision behavior
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/usage/...`
**Changes**: Add tests using a `SimpleMeterRegistry` or equivalent to prove meter emission for mission execution, tool execution, linter outcomes, and guardrail trips. Add focused tests that show when usage is exact and when the heuristic path is used.

```java
assertThat(meterRegistry.get("bifrost.guardrail.trips")
        .tag("guardrail", "MAX_MODEL_CALLS")
        .counter()
        .count()).isEqualTo(1.0);
```

**File**: `ai/thoughts/research/2026-03-21-ENG-021-session-quotas-and-micrometer-usage-telemetry.md`
**Changes**: If implementation changes any precise file ownership or exact-vs-heuristic details, append a short follow-up research note or update references so the research artifact stays aligned with the final implementation.

### Success Criteria:

#### Automated Verification:
- [ ] Starter tests pass with Micrometer enabled: `./mvnw -pl bifrost-spring-boot-starter test`
- [ ] Metrics-specific tests pass with `SimpleMeterRegistry`: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*Metrics*Test test`
- [ ] Quota and heuristic-behavior tests pass together: `./mvnw -pl bifrost-spring-boot-starter -Dtest=*Quota*Test,*Usage*Test test`
- [ ] Full module verification passes: `./mvnw verify`

#### Manual Verification:
- [ ] A local run can inspect emitted meter names and tags without high-cardinality explosion
- [ ] Guardrail trip metrics clearly distinguish the tripped quota type
- [ ] Model-usage meters show whether counts came from exact provider usage or heuristics
- [ ] Tool and linter metrics align with the observable runtime behavior of a sample mission

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before closing the ticket.

---

## Testing Strategy

### Unit Tests:
- Property binding and validation for new `bifrost.session.quotas.*` settings
- Usage extraction behavior for exact usage, missing usage, and heuristic fallback
- Quota evaluation for each guardrail type
- Micrometer recorder tests for meter names, tags, and counts
- Session serialization tests for usage state snapshots

### Integration Tests:
- End-to-end mission execution below thresholds
- Mission failure when model-call or usage-unit quotas are exceeded
- Tool-heavy mission failure when tool-invocation quotas are exceeded
- Linter retry flows that accumulate retries and emit linter outcome metrics
- Starter wiring tests that confirm quota settings and metrics beans are active together

**Note**: Prefer a dedicated testing plan artifact created via `/testing_plan` for full details, especially for failing-first quota tests and meter verification commands. This section is the high-level summary.

### Manual Testing Steps:
1. Configure a very low model-call or usage-unit quota and run a planning-enabled mission to confirm it fails with `BifrostQuotaExceededException`.
2. Run the same mission with relaxed limits and confirm the final response, plan updates, and tool behavior still succeed.
3. Execute a linted skill and confirm linter retries and final outcomes appear in both session state and Micrometer meters.
4. Inspect emitted Micrometer meters from a local app context and verify expected tags for `skill`, `guardrail`, `outcome`, and `precision`.

## Performance Considerations

Quota checks should be constant-time updates against session-local counters or snapshots. Heuristic usage estimation should avoid heavy tokenization dependencies unless the team chooses to introduce one deliberately; for the MVP, a lightweight deterministic heuristic keeps accounting cheap and predictable. Metrics emission should use bounded tags only and avoid session IDs or raw objectives in metric dimensions.

## Migration Notes

This work should be additive. Existing applications that do not configure the new quota properties should receive default thresholds and continue running without code changes. Session serialization will need backward-compatible handling so previously serialized sessions without usage state still deserialize cleanly.

## References

- Original ticket/requirements: [ai/thoughts/tickets/eng-021-session-quotas-and-micrometer-usage-telemetry.md](C:/opendev/code/bifrost/ai/thoughts/tickets/eng-021-session-quotas-and-micrometer-usage-telemetry.md)
- Related research: [ai/thoughts/research/2026-03-21-ENG-021-session-quotas-and-micrometer-usage-telemetry.md](C:/opendev/code/bifrost/ai/thoughts/research/2026-03-21-ENG-021-session-quotas-and-micrometer-usage-telemetry.md)
- Similar implementation seam: [DefaultExecutionStateService.java:25](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java#L25)
- Mission execution seam: [DefaultMissionExecutionEngine.java:57](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java#L57)
- Tool invocation seam: [DefaultToolCallbackFactory.java:59](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java#L59)
- Linter outcome seam: [DefaultSkillAdvisorResolver.java:38](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java#L38)
