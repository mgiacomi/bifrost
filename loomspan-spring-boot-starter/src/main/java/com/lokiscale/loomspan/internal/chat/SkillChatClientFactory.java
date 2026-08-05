package com.lokiscale.loomspan.internal.chat;

import com.lokiscale.loomspan.internal.skill.YamlSkillDefinition;
import org.springframework.ai.chat.client.ChatClient;

public interface SkillChatClientFactory
{
    ChatClient create(YamlSkillDefinition definition);

    default ChatClient createForStepExecution(YamlSkillDefinition definition)
    {
        throw new UnsupportedOperationException(
                "Step-loop execution requires SkillChatClientFactory.createForStepExecution(...) "
                        + "to be implemented explicitly.");
    }
}
