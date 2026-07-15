package com.lokiscale.bifrost.autoconfigure;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;

final class OllamaConnectionChatModelFactory implements AiConnectionChatModelFactory
{
    @Override
    public AiDriver driver() { return AiDriver.OLLAMA; }

    @Override
    public ChatModel create(String connectionName, BifrostProperties.ConnectionProperties properties)
    {
        OllamaApi api = OllamaApi.builder().baseUrl(properties.getBaseUrl()).build();
        return OllamaChatModel.builder().ollamaApi(api).build();
    }
}
