package com.lokiscale.loomspan.internal.autoconfigure;

import com.lokiscale.loomspan.autoconfigure.AiDriver;
import com.lokiscale.loomspan.autoconfigure.LoomspanProperties;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.util.StringUtils;

public final class AnthropicConnectionChatModelFactory implements AiConnectionChatModelFactory
{
    public AnthropicConnectionChatModelFactory()
    {
    }

    @Override
    public AiDriver driver() { return AiDriver.ANTHROPIC; }

    @Override
    public ChatModel create(String connectionName, LoomspanProperties.ConnectionProperties properties)
    {
        AnthropicApi.Builder builder = AnthropicApi.builder().apiKey(properties.getApiKey());
        if (StringUtils.hasText(properties.getBaseUrl())) builder.baseUrl(properties.getBaseUrl());
        LoomspanProperties.AnthropicOptions options = properties.getAnthropic();
        if (options != null)
        {
            if (StringUtils.hasText(options.getCompletionsPath())) builder.completionsPath(options.getCompletionsPath());
            if (StringUtils.hasText(options.getVersion())) builder.anthropicVersion(options.getVersion());
            if (StringUtils.hasText(options.getBetaVersion())) builder.anthropicBetaFeatures(options.getBetaVersion());
        }
        return AnthropicChatModel.builder().anthropicApi(builder.build()).build();
    }
}
