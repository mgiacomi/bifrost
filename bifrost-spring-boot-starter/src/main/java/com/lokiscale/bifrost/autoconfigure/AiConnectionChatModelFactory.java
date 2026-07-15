package com.lokiscale.bifrost.autoconfigure;

import com.lokiscale.bifrost.autoconfigure.AiDriver;
import com.lokiscale.bifrost.autoconfigure.BifrostProperties;
import org.springframework.ai.chat.model.ChatModel;

interface AiConnectionChatModelFactory
{
    AiDriver driver();

    ChatModel create(String connectionName, BifrostProperties.ConnectionProperties properties);
}
