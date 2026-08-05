package com.lokiscale.loomspan.internal.core;

import java.util.Map;
import java.util.Optional;

public interface PlanTaskLinker
{
    Optional<String> linkTask(ExecutionPlan plan, CapabilityMetadata capability, Map<String, Object> arguments);
}
