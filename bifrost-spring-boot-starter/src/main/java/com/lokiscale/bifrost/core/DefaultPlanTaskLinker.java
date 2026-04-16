package com.lokiscale.bifrost.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class DefaultPlanTaskLinker implements PlanTaskLinker
{
    @Override
    public Optional<String> linkTask(ExecutionPlan plan, CapabilityMetadata capability, Map<String, Object> arguments)
    {
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(capability, "capability must not be null");

        Optional<PlanTask> activeTask = plan.activeTask();
        if (activeTask.isPresent()
                && activeTask.get().status() == PlanTaskStatus.IN_PROGRESS
                && capability.name().equals(activeTask.get().capabilityName()))
        {
            return Optional.of(activeTask.get().taskId());
        }

        List<PlanTask> candidates = plan.readyTasks().stream()
                .filter(task -> capability.name().equals(task.capabilityName()))
                .toList();

        return candidates.size() == 1 ? Optional.of(candidates.getFirst().taskId()) : Optional.empty();
    }
}
