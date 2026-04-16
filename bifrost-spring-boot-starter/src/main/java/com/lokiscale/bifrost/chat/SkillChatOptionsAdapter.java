package com.lokiscale.bifrost.chat;

import com.lokiscale.bifrost.autoconfigure.AiProvider;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import org.springframework.ai.chat.prompt.ChatOptions;

public interface SkillChatOptionsAdapter
{
    AiProvider provider();

    ChatOptions createOptions(EffectiveSkillExecutionConfiguration executionConfiguration);
}
