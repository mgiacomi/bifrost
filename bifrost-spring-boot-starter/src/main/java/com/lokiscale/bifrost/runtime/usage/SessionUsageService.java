package com.lokiscale.bifrost.runtime.usage;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.linter.LinterOutcome;

public interface SessionUsageService
{
    SessionUsageSnapshot snapshot(BifrostSession session);

    void recordMissionStart(BifrostSession session, String skillName);

    void recordModelResponse(BifrostSession session, String skillName, ModelUsageRecord usageRecord);

    void recordToolCall(BifrostSession session, String skillName, String capabilityName);

    void recordLinterOutcome(BifrostSession session, LinterOutcome outcome);
}
