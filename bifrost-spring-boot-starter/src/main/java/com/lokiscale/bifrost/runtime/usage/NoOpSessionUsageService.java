package com.lokiscale.bifrost.runtime.usage;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.linter.LinterOutcome;

public final class NoOpSessionUsageService implements SessionUsageService
{
    @Override
    public SessionUsageSnapshot snapshot(BifrostSession session)
    {
        return session == null ? SessionUsageSnapshot.empty() : session.getSessionUsage().orElse(SessionUsageSnapshot.empty());
    }

    @Override
    public void recordMissionStart(BifrostSession session, String skillName)
    {
    }

    @Override
    public void recordModelResponse(BifrostSession session, String skillName, ModelUsageRecord usageRecord)
    {
    }

    @Override
    public void recordToolCall(BifrostSession session, String skillName, String capabilityName)
    {
    }

    @Override
    public void recordLinterOutcome(BifrostSession session, LinterOutcome outcome)
    {
    }
}
