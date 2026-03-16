package com.lokiscale.bifrost.chat;

import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import org.springframework.ai.chat.client.ChatClient;

public interface SkillChatClientFactory {

    ChatClient create(EffectiveSkillExecutionConfiguration executionConfiguration);
}
