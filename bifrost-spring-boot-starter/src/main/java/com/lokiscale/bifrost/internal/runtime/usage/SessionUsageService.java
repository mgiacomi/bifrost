package com.lokiscale.bifrost.internal.runtime.usage;

import com.lokiscale.bifrost.internal.core.BifrostSession;
import com.lokiscale.bifrost.internal.linter.LinterOutcome;
import com.lokiscale.bifrost.internal.core.ModelExecutionIdentity;

public interface SessionUsageService
{
    SessionUsageSnapshot snapshot(BifrostSession session);

    void recordMissionStart(BifrostSession session, String skillName);

    void recordModelResponse(BifrostSession session, String skillName, ModelExecutionIdentity identity, ModelUsageRecord usageRecord);

    void recordToolCall(BifrostSession session, String skillName, String capabilityName);

    void recordLinterOutcome(BifrostSession session, LinterOutcome outcome);
}
