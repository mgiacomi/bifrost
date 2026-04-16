package com.lokiscale.bifrost.runtime.usage;

import com.lokiscale.bifrost.linter.LinterOutcome;

public final class NoOpUsageMetricsRecorder implements UsageMetricsRecorder
{
    @Override
    public void recordSkillInvocation(String skillName)
    {
    }

    @Override
    public void recordModelUsage(String skillName, ModelUsageRecord usageRecord)
    {
    }

    @Override
    public void recordToolInvocation(String skillName, String toolName, String outcome)
    {
    }

    @Override
    public void recordToolAccuracy(String skillName, String linterType, String outcome)
    {
    }

    @Override
    public void recordLinterOutcome(LinterOutcome outcome)
    {
    }

    @Override
    public void recordGuardrailTrip(String skillName, GuardrailType guardrailType)
    {
    }
}
