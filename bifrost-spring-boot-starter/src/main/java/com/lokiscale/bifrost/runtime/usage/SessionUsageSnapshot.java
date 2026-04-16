package com.lokiscale.bifrost.runtime.usage;

import java.util.Objects;

public record SessionUsageSnapshot(
        int skillInvocations,
        int toolInvocations,
        int linterRetries,
        int modelCalls,
        int promptUnits,
        int completionUnits,
        int usageUnits,
        int exactModelResponses,
        int heuristicModelResponses,
        int unavailableModelResponses)
{
    public SessionUsageSnapshot
    {
        validateNonNegative(skillInvocations, "skillInvocations");
        validateNonNegative(toolInvocations, "toolInvocations");
        validateNonNegative(linterRetries, "linterRetries");
        validateNonNegative(modelCalls, "modelCalls");
        validateNonNegative(promptUnits, "promptUnits");
        validateNonNegative(completionUnits, "completionUnits");
        validateNonNegative(usageUnits, "usageUnits");
        validateNonNegative(exactModelResponses, "exactModelResponses");
        validateNonNegative(heuristicModelResponses, "heuristicModelResponses");
        validateNonNegative(unavailableModelResponses, "unavailableModelResponses");
    }

    public static SessionUsageSnapshot empty()
    {
        return new SessionUsageSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public SessionUsageSnapshot incrementSkillInvocations()
    {
        return new SessionUsageSnapshot(
                skillInvocations + 1,
                toolInvocations,
                linterRetries,
                modelCalls,
                promptUnits,
                completionUnits,
                usageUnits,
                exactModelResponses,
                heuristicModelResponses,
                unavailableModelResponses);
    }

    public SessionUsageSnapshot incrementToolInvocations()
    {
        return new SessionUsageSnapshot(
                skillInvocations,
                toolInvocations + 1,
                linterRetries,
                modelCalls,
                promptUnits,
                completionUnits,
                usageUnits,
                exactModelResponses,
                heuristicModelResponses,
                unavailableModelResponses);
    }

    public SessionUsageSnapshot incrementLinterRetries()
    {
        return new SessionUsageSnapshot(
                skillInvocations,
                toolInvocations,
                linterRetries + 1,
                modelCalls,
                promptUnits,
                completionUnits,
                usageUnits,
                exactModelResponses,
                heuristicModelResponses,
                unavailableModelResponses);
    }

    public SessionUsageSnapshot recordModelUsage(ModelUsageRecord usageRecord)
    {
        Objects.requireNonNull(usageRecord, "usageRecord must not be null");
        return new SessionUsageSnapshot(
                skillInvocations,
                toolInvocations,
                linterRetries,
                modelCalls + 1,
                promptUnits + usageRecord.promptUnits(),
                completionUnits + usageRecord.completionUnits(),
                usageUnits + usageRecord.totalUnits(),
                exactModelResponses + (usageRecord.precision() == UsagePrecision.EXACT ? 1 : 0),
                heuristicModelResponses + (usageRecord.precision() == UsagePrecision.HEURISTIC ? 1 : 0),
                unavailableModelResponses + (usageRecord.precision() == UsagePrecision.UNAVAILABLE ? 1 : 0));
    }

    private static void validateNonNegative(int value, String name)
    {
        if (value < 0)
        {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
