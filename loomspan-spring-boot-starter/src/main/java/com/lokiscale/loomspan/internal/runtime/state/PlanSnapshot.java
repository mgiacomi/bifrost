package com.lokiscale.loomspan.internal.runtime.state;

import com.lokiscale.loomspan.internal.core.ExecutionPlan;
import org.springframework.lang.Nullable;

public record PlanSnapshot(@Nullable ExecutionPlan plan)
{
    public static PlanSnapshot of(@Nullable ExecutionPlan plan)
    {
        return new PlanSnapshot(plan);
    }
}
