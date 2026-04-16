package com.lokiscale.bifrost.runtime;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;

public interface MissionExecutionEngine
{
    String executeMission(
            BifrostSession session,
            YamlSkillDefinition definition,
            String objective,
            @Nullable Map<String, Object> missionInput,
            ChatClient chatClient,
            List<ToolCallback> visibleTools,
            boolean planningEnabled,
            @Nullable Authentication authentication);
}
