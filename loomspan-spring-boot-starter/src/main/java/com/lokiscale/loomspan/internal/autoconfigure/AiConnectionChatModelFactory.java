package com.lokiscale.loomspan.internal.autoconfigure;

import com.lokiscale.loomspan.autoconfigure.AiDriver;
import com.lokiscale.loomspan.autoconfigure.LoomspanProperties;
import org.springframework.ai.chat.model.ChatModel;

public interface AiConnectionChatModelFactory
{
    AiDriver driver();

    ChatModel create(String connectionName, LoomspanProperties.ConnectionProperties properties);
}
