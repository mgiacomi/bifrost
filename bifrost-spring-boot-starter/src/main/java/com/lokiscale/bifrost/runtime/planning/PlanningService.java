package com.lokiscale.bifrost.runtime.planning;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.runtime.evidence.EvidenceContract;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
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
            YamlSkillDefinition definition,
            ChatClient chatClient,
            List<ToolCallback> visibleTools);

    Optional<ExecutionPlan> markToolStarted(BifrostSession session, CapabilityMetadata capability, Map<String, Object> arguments);

    Optional<ExecutionPlan> markTaskStarted(BifrostSession session, String taskId, String capabilityName, @Nullable Map<String, Object> arguments);

    Optional<ExecutionPlan> markToolCompleted(BifrostSession session,
                                              String taskId,
                                              String capabilityName,
                                              @Nullable Object result,
                                              EvidenceContract evidenceContract);

    Optional<ExecutionPlan> markToolFailed(BifrostSession session, String taskId, String capabilityName, RuntimeException ex);
}
