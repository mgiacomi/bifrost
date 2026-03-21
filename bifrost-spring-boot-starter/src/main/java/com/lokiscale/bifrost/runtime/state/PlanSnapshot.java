package com.lokiscale.bifrost.runtime.state;

import com.lokiscale.bifrost.core.ExecutionPlan;
import org.springframework.lang.Nullable;

public record PlanSnapshot(@Nullable ExecutionPlan plan) {

    public static PlanSnapshot of(@Nullable ExecutionPlan plan) {
        return new PlanSnapshot(plan);
    }
}
