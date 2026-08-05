package com.lokiscale.loomspan.internal.autoconfigure;

import com.lokiscale.loomspan.autoconfigure.AiDriver;
import com.lokiscale.loomspan.autoconfigure.LoomspanProperties;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;

public final class GeminiConnectionChatModelFactory implements AiConnectionChatModelFactory
{
    private final ResourceLoader resourceLoader;

    public GeminiConnectionChatModelFactory(ResourceLoader resourceLoader)
    {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public AiDriver driver() { return AiDriver.GEMINI; }

    @Override
    public ChatModel create(String connectionName, LoomspanProperties.ConnectionProperties properties)
    {
        Client.Builder builder = Client.builder();
        LoomspanProperties.GeminiOptions options = properties.getGemini();
        if (StringUtils.hasText(properties.getApiKey()))
        {
            builder.apiKey(properties.getApiKey());
        }
        else
        {
            builder.vertexAI(true).project(options.getProjectId()).location(options.getLocation());
            if (StringUtils.hasText(options.getCredentialsUri()))
            {
                builder.credentials(loadCredentials(connectionName, options.getCredentialsUri()));
            }
        }
        return GoogleGenAiChatModel.builder().genAiClient(builder.build()).build();
    }

    private GoogleCredentials loadCredentials(String connectionName, String uri)
    {
        try (InputStream input = resourceLoader.getResource(uri).getInputStream())
        {
            return GoogleCredentials.fromStream(input);
        }
        catch (IOException ex)
        {
            throw new SafeAiConnectionConfigurationException("loomspan.connections." + connectionName
                    + ".gemini.credentials-uri could not be loaded");
        }
    }
}
