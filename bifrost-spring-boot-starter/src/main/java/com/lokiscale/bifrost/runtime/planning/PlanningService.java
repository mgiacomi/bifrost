package com.lokiscale.bifrost.runtime.planning;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PlanningService {

    Optional<ExecutionPlan> initializePlan(
            BifrostSession session,
            String objective,
            String capabilityName,
            EffectiveSkillExecutionConfiguration executionConfiguration,
            ChatClient chatClient,
            List<ToolCallback> visibleTools);

    default Optional<ExecutionPlan> initializePlan(
            BifrostSession session,
            String objective,
            String capabilityName,
            EffectiveSkillExecutionConfiguration executionConfiguration,
            ChatClient chatClient,
            List<ToolCallback> visibleTools,
            boolean strictPlanContract) {
        return initializePlan(session, objective, capabilityName, executionConfiguration, chatClient, visibleTools);
    }

    Optional<ExecutionPlan> markToolStarted(BifrostSession session, CapabilityMetadata capability, Map<String, Object> arguments);

    Optional<ExecutionPlan> markTaskStarted(BifrostSession session, String taskId, String capabilityName, @Nullable Map<String, Object> arguments);

    Optional<ExecutionPlan> markToolCompleted(BifrostSession session, String taskId, String capabilityName, @Nullable Object result);

    Optional<ExecutionPlan> markToolFailed(BifrostSession session, String taskId, String capabilityName, RuntimeException ex);
}
