package com.lokiscale.loomspan.internal.autoconfigure;

import com.lokiscale.loomspan.autoconfigure.AiDriver;
import com.lokiscale.loomspan.autoconfigure.LoomspanProperties;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;

public final class OllamaConnectionChatModelFactory implements AiConnectionChatModelFactory
{
    public OllamaConnectionChatModelFactory()
    {
    }

    @Override
    public AiDriver driver() { return AiDriver.OLLAMA; }

    @Override
    public ChatModel create(String connectionName, LoomspanProperties.ConnectionProperties properties)
    {
        OllamaApi api = OllamaApi.builder().baseUrl(properties.getBaseUrl()).build();
        return OllamaChatModel.builder().ollamaApi(api).build();
    }
}
