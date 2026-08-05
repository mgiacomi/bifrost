package com.lokiscale.loomspan.internal.chat;

import com.lokiscale.loomspan.internal.skill.EffectiveSkillExecutionConfiguration;
import org.springframework.ai.chat.model.ChatModel;

public interface SkillChatModelResolver
{
    ChatModel resolve(String skillName, EffectiveSkillExecutionConfiguration configuration);
}
