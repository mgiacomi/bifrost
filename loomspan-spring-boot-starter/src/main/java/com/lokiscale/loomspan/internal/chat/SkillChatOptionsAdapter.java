package com.lokiscale.loomspan.internal.chat;

import com.lokiscale.loomspan.autoconfigure.AiDriver;
import com.lokiscale.loomspan.internal.skill.EffectiveSkillExecutionConfiguration;
import org.springframework.ai.chat.prompt.ChatOptions;

public interface SkillChatOptionsAdapter
{
    AiDriver driver();

    ChatOptions createOptions(EffectiveSkillExecutionConfiguration executionConfiguration);
}
