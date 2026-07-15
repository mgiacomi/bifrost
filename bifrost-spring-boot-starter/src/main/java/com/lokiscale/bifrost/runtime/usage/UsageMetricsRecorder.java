package com.lokiscale.bifrost.runtime.usage;

import com.lokiscale.bifrost.linter.LinterOutcome;
import com.lokiscale.bifrost.core.ModelExecutionIdentity;

public interface UsageMetricsRecorder
{
    void recordSkillInvocation(String skillName);

    void recordModelUsage(String skillName, ModelExecutionIdentity identity, ModelUsageRecord usageRecord);

    void recordToolInvocation(String skillName, String toolName, String outcome);

    void recordToolAccuracy(String skillName, String linterType, String outcome);

    void recordLinterOutcome(LinterOutcome outcome);

    void recordGuardrailTrip(String skillName, GuardrailType guardrailType);
}
