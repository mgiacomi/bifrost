package com.lokiscale.bifrost.chat;

import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import org.springframework.ai.chat.model.ChatModel;

public interface SkillChatModelResolver
{
    ChatModel resolve(String skillName, EffectiveSkillExecutionConfiguration configuration);
}
