package com.lokiscale.bifrost.internal.chat;

import com.lokiscale.bifrost.autoconfigure.AiDriver;
import com.lokiscale.bifrost.internal.skill.EffectiveSkillExecutionConfiguration;
import org.springframework.ai.chat.prompt.ChatOptions;

public interface SkillChatOptionsAdapter
{
    AiDriver driver();

    ChatOptions createOptions(EffectiveSkillExecutionConfiguration executionConfiguration);
}
