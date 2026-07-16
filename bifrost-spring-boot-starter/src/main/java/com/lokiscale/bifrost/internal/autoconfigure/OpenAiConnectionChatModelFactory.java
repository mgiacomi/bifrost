package com.lokiscale.bifrost.internal.autoconfigure;

import com.lokiscale.bifrost.autoconfigure.AiDriver;
import com.lokiscale.bifrost.autoconfigure.BifrostProperties;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

import java.net.URI;

public final class OpenAiConnectionChatModelFactory implements AiConnectionChatModelFactory
{
    public OpenAiConnectionChatModelFactory()
    {
    }

    @Override
    public AiDriver driver() { return AiDriver.OPENAI; }

    @Override
    public ChatModel create(String connectionName, BifrostProperties.ConnectionProperties properties)
    {
        OpenAiApi.Builder builder = OpenAiApi.builder().apiKey(properties.getApiKey());
        if (StringUtils.hasText(properties.getBaseUrl())) builder.baseUrl(properties.getBaseUrl());

        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        properties.getHeaders().forEach(headers::add);
        BifrostProperties.OpenAiOptions options = properties.getOpenai();
        if (options != null)
        {
            if (StringUtils.hasText(options.getOrganizationId())) headers.add("OpenAI-Organization", options.getOrganizationId());
            if (StringUtils.hasText(options.getProjectId())) headers.add("OpenAI-Project", options.getProjectId());
            if (StringUtils.hasText(options.getChatCompletionsPath())) builder.completionsPath(options.getChatCompletionsPath());
        }
        if ((options == null || !StringUtils.hasText(options.getChatCompletionsPath()))
                && baseUrlAlreadyEndsWithV1(properties.getBaseUrl()))
        {
            builder.completionsPath("/chat/completions");
        }
        if (!headers.isEmpty()) builder.headers(headers);
        return OpenAiChatModel.builder().openAiApi(builder.build()).build();
    }

    private static boolean baseUrlAlreadyEndsWithV1(String baseUrl)
    {
        if (!StringUtils.hasText(baseUrl)) return false;
        String path = URI.create(baseUrl).getPath();
        return path != null && path.replaceFirst("/+$", "").endsWith("/v1");
    }
}
