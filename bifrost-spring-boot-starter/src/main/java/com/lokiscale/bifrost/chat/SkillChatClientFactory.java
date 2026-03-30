package com.lokiscale.bifrost.chat;

import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import org.springframework.ai.chat.client.ChatClient;

public interface SkillChatClientFactory {

    ChatClient create(YamlSkillDefinition definition);

    default ChatClient createForStepExecution(YamlSkillDefinition definition) {
        throw new UnsupportedOperationException(
                "Step-loop execution requires SkillChatClientFactory.createForStepExecution(...) "
                        + "to be implemented explicitly.");
    }
}
