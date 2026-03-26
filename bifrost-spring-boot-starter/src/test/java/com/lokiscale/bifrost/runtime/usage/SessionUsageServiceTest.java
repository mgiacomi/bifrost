package com.lokiscale.bifrost.runtime.usage;

import com.lokiscale.bifrost.autoconfigure.BifrostSessionProperties;
import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.ExecutionFrame;
import com.lokiscale.bifrost.core.OperationType;
import com.lokiscale.bifrost.core.TraceFrameType;
import com.lokiscale.bifrost.linter.LinterOutcome;
import com.lokiscale.bifrost.linter.LinterOutcomeStatus;
import com.lokiscale.bifrost.runtime.BifrostQuotaExceededException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionUsageServiceTest {

    @Test
    void throwsWhenModelCallQuotaExceeded() {
        DefaultSessionUsageService service = new DefaultSessionUsageService(quotas(10, 10, 10, 1, 100), new NoOpUsageMetricsRecorder());
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-1", 3);

        service.recordMissionStart(session, "root.skill");
        service.recordModelResponse(session, "root.skill", new ModelUsageRecord(1, 2, 3, UsagePrecision.EXACT, null));

        assertThatThrownBy(() -> service.recordModelResponse(session, "root.skill", new ModelUsageRecord(1, 2, 3, UsagePrecision.EXACT, null)))
                .isInstanceOf(BifrostQuotaExceededException.class)
                .extracting("guardrailType", "limit", "observed")
                .containsExactly(GuardrailType.MAX_MODEL_CALLS, 1L, 2L);
    }

    @Test
    void throwsWhenToolInvocationQuotaExceeded() {
        DefaultSessionUsageService service = new DefaultSessionUsageService(quotas(10, 1, 10, 10, 100), new NoOpUsageMetricsRecorder());
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-2", 3);

        service.recordToolCall(session, "root.skill", "tool.one");

        assertThatThrownBy(() -> service.recordToolCall(session, "root.skill", "tool.one"))
                .isInstanceOf(BifrostQuotaExceededException.class)
                .extracting("guardrailType")
                .isEqualTo(GuardrailType.MAX_TOOL_INVOCATIONS);
    }

    @Test
    void throwsWhenLinterRetryQuotaExceeded() {
        DefaultSessionUsageService service = new DefaultSessionUsageService(quotas(10, 10, 1, 10, 100), new NoOpUsageMetricsRecorder());
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-3", 3);

        service.recordLinterOutcome(session, outcome(LinterOutcomeStatus.RETRYING, 0, 1));

        assertThatThrownBy(() -> service.recordLinterOutcome(session, outcome(LinterOutcomeStatus.RETRYING, 1, 2)))
                .isInstanceOf(BifrostQuotaExceededException.class)
                .extracting("guardrailType", "observed")
                .containsExactly(GuardrailType.MAX_LINTER_RETRIES, 2L);
    }

    @Test
    void snapshotsAccumulatedUsage() {
        DefaultSessionUsageService service = new DefaultSessionUsageService(quotas(10, 10, 10, 10, 100), new NoOpUsageMetricsRecorder());
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-4", 3);

        service.recordMissionStart(session, "root.skill");
        service.recordToolCall(session, "root.skill", "tool.one");
        service.recordModelResponse(session, "root.skill", new ModelUsageRecord(3, 4, 7, UsagePrecision.HEURISTIC, null));
        service.recordLinterOutcome(session, outcome(LinterOutcomeStatus.RETRYING, 0, 1));

        assertThat(service.snapshot(session)).isEqualTo(new SessionUsageSnapshot(1, 1, 1, 1, 3, 4, 7, 0, 1, 0));
    }

    @Test
    void doesNotEnforceDisabledQuotas() {
        DefaultSessionUsageService service = new DefaultSessionUsageService(quotas(0, 0, 0, 0, 0), new NoOpUsageMetricsRecorder());
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-5", 3);

        service.recordMissionStart(session, "root.skill");
        service.recordMissionStart(session, "root.skill");
        service.recordToolCall(session, "root.skill", "tool.one");
        service.recordToolCall(session, "root.skill", "tool.two");
        service.recordModelResponse(session, "root.skill", new ModelUsageRecord(4, 5, 9, UsagePrecision.HEURISTIC, null));
        service.recordModelResponse(session, "root.skill", new ModelUsageRecord(4, 5, 9, UsagePrecision.HEURISTIC, null));
        service.recordLinterOutcome(session, outcome(LinterOutcomeStatus.RETRYING, 0, 1));
        service.recordLinterOutcome(session, outcome(LinterOutcomeStatus.RETRYING, 1, 2));

        assertThat(service.snapshot(session)).isEqualTo(new SessionUsageSnapshot(2, 2, 2, 2, 8, 10, 18, 0, 2, 0));
    }

    @Test
    void recordsToolAccuracyFromTerminalLinterOutcomeForCurrentFrame() {
        RecordingUsageMetricsRecorder recorder = new RecordingUsageMetricsRecorder();
        DefaultSessionUsageService service = new DefaultSessionUsageService(quotas(10, 10, 10, 10, 100), recorder);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-6", 3);
        session.pushFrame(new ExecutionFrame("frame-1", null, OperationType.SKILL, TraceFrameType.SKILL_EXECUTION, "root.skill", Map.of(), Instant.now()));

        service.recordToolCall(session, "root.skill", "tool.one");
        service.recordLinterOutcome(session, outcome(LinterOutcomeStatus.RETRYING, 0, 1));
        service.recordLinterOutcome(session, outcome(LinterOutcomeStatus.EXHAUSTED, 1, 2));

        assertThat(recorder.toolAccuracySamples).containsExactly("root.skill|regex|inaccurate");
    }

    @Test
    void recordsToolAccuracyForEachToolInvocationInCurrentFrame() {
        RecordingUsageMetricsRecorder recorder = new RecordingUsageMetricsRecorder();
        DefaultSessionUsageService service = new DefaultSessionUsageService(quotas(10, 10, 10, 10, 100), recorder);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-6b", 3);
        session.pushFrame(new ExecutionFrame("frame-1", null, OperationType.SKILL, TraceFrameType.SKILL_EXECUTION, "root.skill", Map.of(), Instant.now()));

        service.recordToolCall(session, "root.skill", "tool.one");
        service.recordToolCall(session, "root.skill", "tool.two");
        service.recordLinterOutcome(session, outcome(LinterOutcomeStatus.EXHAUSTED, 1, 2));

        assertThat(recorder.toolAccuracySamples)
                .containsExactly("root.skill|regex|inaccurate", "root.skill|regex|inaccurate");
    }

    @Test
    void doesNotRecordToolAccuracyWithoutToolActivityForCurrentFrame() {
        RecordingUsageMetricsRecorder recorder = new RecordingUsageMetricsRecorder();
        DefaultSessionUsageService service = new DefaultSessionUsageService(quotas(10, 10, 10, 10, 100), recorder);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-7", 3);
        session.pushFrame(new ExecutionFrame("frame-1", null, OperationType.SKILL, TraceFrameType.SKILL_EXECUTION, "root.skill", Map.of(), Instant.now()));

        service.recordLinterOutcome(session, outcome(LinterOutcomeStatus.PASSED, 0, 1));

        assertThat(recorder.toolAccuracySamples).isEmpty();
    }

    private static BifrostSessionProperties.Quotas quotas(int maxSkills, int maxTools, int maxLinterRetries, int maxModelCalls, int maxUsageUnits) {
        BifrostSessionProperties.Quotas quotas = new BifrostSessionProperties.Quotas();
        quotas.setMaxSkillInvocations(maxSkills);
        quotas.setMaxToolInvocations(maxTools);
        quotas.setMaxLinterRetries(maxLinterRetries);
        quotas.setMaxModelCalls(maxModelCalls);
        quotas.setMaxUsageUnits(maxUsageUnits);
        return quotas;
    }

    private static LinterOutcome outcome(LinterOutcomeStatus status, int retryCount, int attempt) {
        return new LinterOutcome("root.skill", "regex", attempt, retryCount, 4, status, "Return YAML");
    }

    private static final class RecordingUsageMetricsRecorder implements UsageMetricsRecorder {

        private final java.util.List<String> toolAccuracySamples = new java.util.ArrayList<>();

        @Override
        public void recordSkillInvocation(String skillName) {
        }

        @Override
        public void recordModelUsage(String skillName, ModelUsageRecord usageRecord) {
        }

        @Override
        public void recordToolInvocation(String skillName, String toolName, String outcome) {
        }

        @Override
        public void recordToolAccuracy(String skillName, String linterType, String outcome) {
            toolAccuracySamples.add(skillName + "|" + linterType + "|" + outcome);
        }

        @Override
        public void recordLinterOutcome(LinterOutcome outcome) {
        }

        @Override
        public void recordGuardrailTrip(String skillName, GuardrailType guardrailType) {
        }
    }
}
