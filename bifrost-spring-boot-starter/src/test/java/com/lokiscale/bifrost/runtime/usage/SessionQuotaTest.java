package com.lokiscale.bifrost.runtime.usage;

import com.lokiscale.bifrost.autoconfigure.BifrostSessionProperties;
import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.linter.LinterOutcome;
import com.lokiscale.bifrost.linter.LinterOutcomeStatus;
import com.lokiscale.bifrost.runtime.BifrostQuotaExceededException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionQuotaTest {

    @Test
    void throwsWhenModelCallQuotaExceeded() {
        DefaultSessionUsageService service = new DefaultSessionUsageService(quotas(10, 10, 10, 1, 100), new NoOpUsageMetricsRecorder());
        BifrostSession session = new BifrostSession("session-1", 3);

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
        BifrostSession session = new BifrostSession("session-2", 3);

        service.recordToolCall(session, "root.skill", "tool.one");

        assertThatThrownBy(() -> service.recordToolCall(session, "root.skill", "tool.one"))
                .isInstanceOf(BifrostQuotaExceededException.class)
                .extracting("guardrailType")
                .isEqualTo(GuardrailType.MAX_TOOL_INVOCATIONS);
    }

    @Test
    void throwsWhenLinterRetryQuotaExceeded() {
        DefaultSessionUsageService service = new DefaultSessionUsageService(quotas(10, 10, 1, 10, 100), new NoOpUsageMetricsRecorder());
        BifrostSession session = new BifrostSession("session-3", 3);

        service.recordLinterOutcome(session, outcome(LinterOutcomeStatus.RETRYING, 0, 1));

        assertThatThrownBy(() -> service.recordLinterOutcome(session, outcome(LinterOutcomeStatus.RETRYING, 1, 2)))
                .isInstanceOf(BifrostQuotaExceededException.class)
                .extracting("guardrailType", "observed")
                .containsExactly(GuardrailType.MAX_LINTER_RETRIES, 2L);
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
        return new LinterOutcome("linted.skill", "regex", attempt, retryCount, 4, status, "Return YAML");
    }
}
