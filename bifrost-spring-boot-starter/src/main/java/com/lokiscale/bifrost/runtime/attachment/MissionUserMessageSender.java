package com.lokiscale.bifrost.runtime.attachment;

import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

public interface MissionUserMessageSender
{
    ChatClient.CallResponseSpec send(ChatClient chatClient,
            String systemPrompt,
            RenderedMissionInput renderedInput,
            List<ToolCallback> visibleTools,
            String skillName,
            EffectiveSkillExecutionConfiguration executionConfiguration);
}
