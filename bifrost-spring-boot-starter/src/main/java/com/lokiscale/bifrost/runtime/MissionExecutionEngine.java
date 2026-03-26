package com.lokiscale.bifrost.runtime;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.util.List;

public interface MissionExecutionEngine {

    String executeMission(
            BifrostSession session,
            String skillName,
            String objective,
            EffectiveSkillExecutionConfiguration executionConfiguration,
            ChatClient chatClient,
            List<ToolCallback> visibleTools,
            boolean planningEnabled,
            @Nullable Authentication authentication);
}
