package com.lokiscale.bifrost.internal.chat;

import com.lokiscale.bifrost.internal.skill.EffectiveSkillExecutionConfiguration;
import org.springframework.ai.chat.model.ChatModel;

public interface SkillChatModelResolver
{
    ChatModel resolve(String skillName, EffectiveSkillExecutionConfiguration configuration);
}
