package com.lokiscale.loomspan.internal.runtime.attachment;

import com.lokiscale.loomspan.internal.skill.EffectiveSkillExecutionConfiguration;
import com.lokiscale.loomspan.internal.core.ModelTraceContext;
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
            EffectiveSkillExecutionConfiguration executionConfiguration,
            ModelTraceContext modelTraceContext);
}
