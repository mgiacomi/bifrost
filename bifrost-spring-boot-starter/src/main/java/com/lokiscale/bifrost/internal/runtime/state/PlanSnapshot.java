package com.lokiscale.bifrost.internal.runtime.state;

import com.lokiscale.bifrost.internal.core.ExecutionPlan;
import org.springframework.lang.Nullable;

public record PlanSnapshot(@Nullable ExecutionPlan plan)
{
    public static PlanSnapshot of(@Nullable ExecutionPlan plan)
    {
        return new PlanSnapshot(plan);
    }
}
