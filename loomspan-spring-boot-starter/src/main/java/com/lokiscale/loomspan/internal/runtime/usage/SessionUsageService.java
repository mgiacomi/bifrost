package com.lokiscale.loomspan.internal.runtime.usage;

import com.lokiscale.loomspan.internal.core.LoomspanSession;
import com.lokiscale.loomspan.internal.linter.LinterOutcome;
import com.lokiscale.loomspan.internal.core.ModelExecutionIdentity;

public interface SessionUsageService
{
    SessionUsageSnapshot snapshot(LoomspanSession session);

    void recordMissionStart(LoomspanSession session, String skillName);

    void recordModelResponse(LoomspanSession session, String skillName, ModelExecutionIdentity identity, ModelUsageRecord usageRecord);

    void recordToolCall(LoomspanSession session, String skillName, String capabilityName);

    void recordLinterOutcome(LoomspanSession session, LinterOutcome outcome);
}
