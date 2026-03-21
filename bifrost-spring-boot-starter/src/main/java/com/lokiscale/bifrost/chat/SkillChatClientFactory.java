package com.lokiscale.bifrost.chat;

import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import org.springframework.ai.chat.client.ChatClient;

public interface SkillChatClientFactory {

    ChatClient create(YamlSkillDefinition definition);
}
