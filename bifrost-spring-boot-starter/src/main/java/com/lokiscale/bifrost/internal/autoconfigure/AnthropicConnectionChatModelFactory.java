package com.lokiscale.bifrost.internal.autoconfigure;

import com.lokiscale.bifrost.autoconfigure.AiDriver;
import com.lokiscale.bifrost.autoconfigure.BifrostProperties;
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
    public ChatModel create(String connectionName, BifrostProperties.ConnectionProperties properties)
    {
        AnthropicApi.Builder builder = AnthropicApi.builder().apiKey(properties.getApiKey());
        if (StringUtils.hasText(properties.getBaseUrl())) builder.baseUrl(properties.getBaseUrl());
        BifrostProperties.AnthropicOptions options = properties.getAnthropic();
        if (options != null)
        {
            if (StringUtils.hasText(options.getCompletionsPath())) builder.completionsPath(options.getCompletionsPath());
            if (StringUtils.hasText(options.getVersion())) builder.anthropicVersion(options.getVersion());
            if (StringUtils.hasText(options.getBetaVersion())) builder.anthropicBetaFeatures(options.getBetaVersion());
        }
        return AnthropicChatModel.builder().anthropicApi(builder.build()).build();
    }
}
