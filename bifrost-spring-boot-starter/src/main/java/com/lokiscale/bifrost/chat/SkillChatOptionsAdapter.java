package com.lokiscale.bifrost.chat;

import com.lokiscale.bifrost.autoconfigure.AiDriver;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import org.springframework.ai.chat.prompt.ChatOptions;

public interface SkillChatOptionsAdapter
{
    AiDriver driver();

    ChatOptions createOptions(EffectiveSkillExecutionConfiguration executionConfiguration);
}
