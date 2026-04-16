package com.lokiscale.bifrost.runtime.usage;

import com.lokiscale.bifrost.autoconfigure.BifrostSessionProperties;
import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.linter.LinterOutcome;
import com.lokiscale.bifrost.linter.LinterOutcomeStatus;
import com.lokiscale.bifrost.runtime.BifrostQuotaExceededException;

import java.util.Objects;
import java.util.function.UnaryOperator;

public class DefaultSessionUsageService implements SessionUsageService
{
    private final BifrostSessionProperties.Quotas quotas;
    private final UsageMetricsRecorder usageMetricsRecorder;

    public DefaultSessionUsageService(BifrostSessionProperties.Quotas quotas, UsageMetricsRecorder usageMetricsRecorder)
    {
        this.quotas = Objects.requireNonNull(quotas, "quotas must not be null");
        this.usageMetricsRecorder = Objects.requireNonNull(usageMetricsRecorder, "usageMetricsRecorder must not be null");
    }

    @Override
    public SessionUsageSnapshot snapshot(BifrostSession session)
    {
        Objects.requireNonNull(session, "session must not be null");
        return session.getSessionUsage().orElse(SessionUsageSnapshot.empty());
    }

    @Override
    public void recordMissionStart(BifrostSession session, String skillName)
    {
        Objects.requireNonNull(session, "session must not be null");
        SessionUsageSnapshot updated = update(session, SessionUsageSnapshot::incrementSkillInvocations);
        usageMetricsRecorder.recordSkillInvocation(skillName);
        enforce(session, skillName, GuardrailType.MAX_SKILL_INVOCATIONS, quotas.getMaxSkillInvocations(), updated.skillInvocations());
    }

    @Override
    public void recordModelResponse(BifrostSession session, String skillName, ModelUsageRecord usageRecord)
    {
        Objects.requireNonNull(session, "session must not be null");
        SessionUsageSnapshot updated = update(session, snapshot -> snapshot.recordModelUsage(Objects.requireNonNull(usageRecord, "usageRecord must not be null")));
        usageMetricsRecorder.recordModelUsage(skillName, usageRecord);
        enforce(session, skillName, GuardrailType.MAX_MODEL_CALLS, quotas.getMaxModelCalls(), updated.modelCalls());
        enforce(session, skillName, GuardrailType.MAX_USAGE_UNITS, quotas.getMaxUsageUnits(), updated.usageUnits());
    }

    @Override
    public void recordToolCall(BifrostSession session, String skillName, String capabilityName)
    {
        Objects.requireNonNull(session, "session must not be null");
        SessionUsageSnapshot updated = update(session, SessionUsageSnapshot::incrementToolInvocations);
        session.markToolActivityForCurrentFrame();
        enforce(session, skillName, GuardrailType.MAX_TOOL_INVOCATIONS, quotas.getMaxToolInvocations(), updated.toolInvocations());
    }

    @Override
    public void recordLinterOutcome(BifrostSession session, LinterOutcome outcome)
    {
        Objects.requireNonNull(session, "session must not be null");
        LinterOutcome recordedOutcome = Objects.requireNonNull(outcome, "outcome must not be null");
        SessionUsageSnapshot updated = recordedOutcome.status() == LinterOutcomeStatus.RETRYING
                ? update(session, SessionUsageSnapshot::incrementLinterRetries)
                : snapshot(session);

        usageMetricsRecorder.recordLinterOutcome(recordedOutcome);

        if (recordedOutcome.status() == LinterOutcomeStatus.RETRYING)
        {
            enforce(session,
                    recordedOutcome.skillName(),
                    GuardrailType.MAX_LINTER_RETRIES,
                    quotas.getMaxLinterRetries(),
                    updated.linterRetries());
            return;
        }

        int toolInvocationCount = recordedOutcome.terminal() && belongsToCurrentSkillExecution(session, recordedOutcome.skillName())
                ? session.consumeToolActivityCountForCurrentFrame()
                : 0;

        if (toolInvocationCount > 0)
        {
            for (int i = 0; i < toolInvocationCount; i++)
            {
                usageMetricsRecorder.recordToolAccuracy(
                        recordedOutcome.skillName(),
                        recordedOutcome.linterType(),
                        recordedOutcome.passed() ? "accurate" : "inaccurate");
            }
        }
    }

    private SessionUsageSnapshot update(BifrostSession session, UnaryOperator<SessionUsageSnapshot> updater)
    {
        return session.updateSessionUsage(existing -> Objects.requireNonNull(updater.apply(existing == null ? SessionUsageSnapshot.empty() : existing),
                "updated session usage must not be null"))
                .orElseGet(() ->
                {
                    SessionUsageSnapshot updated = Objects.requireNonNull(updater.apply(SessionUsageSnapshot.empty()),
                            "updated session usage must not be null");
                    session.setSessionUsage(updated);
                    return updated;
                });
    }

    private void enforce(BifrostSession session, String skillName, GuardrailType guardrailType, long limit, long observed)
    {
        if (limit <= 0)
        {
            return;
        }
        if (observed <= limit)
        {
            return;
        }

        usageMetricsRecorder.recordGuardrailTrip(skillName, guardrailType);

        throw new BifrostQuotaExceededException(session.getSessionId(), guardrailType, limit, observed);
    }

    private boolean belongsToCurrentSkillExecution(BifrostSession session, String skillName)
    {
        try
        {
            return session.peekFrame().route().equals(skillName);
        }
        catch (IllegalStateException ignored)
        {
            return false;
        }
    }
}
