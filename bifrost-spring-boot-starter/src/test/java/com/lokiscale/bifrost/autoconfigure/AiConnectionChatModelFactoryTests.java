package com.lokiscale.bifrost.autoconfigure;

import com.lokiscale.bifrost.autoconfigure.BifrostProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiConnectionChatModelFactoryTests {

    @Test
    void constructsDistinctOpenAiAndOllamaModelsPerConnection() {
        BifrostProperties.ConnectionProperties openAi = new BifrostProperties.ConnectionProperties();
        openAi.setApiKey("test-key");
        OpenAiConnectionChatModelFactory openAiFactory = new OpenAiConnectionChatModelFactory();
        assertThat(openAiFactory.create("one", openAi)).isInstanceOf(OpenAiChatModel.class)
                .isNotSameAs(openAiFactory.create("two", openAi));

        BifrostProperties.ConnectionProperties ollama = new BifrostProperties.ConnectionProperties();
        ollama.setBaseUrl("http://localhost:11434");
        OllamaConnectionChatModelFactory ollamaFactory = new OllamaConnectionChatModelFactory();
        assertThat(ollamaFactory.create("one", ollama)).isInstanceOf(OllamaChatModel.class)
                .isNotSameAs(ollamaFactory.create("two", ollama));
    }

    @Test
    void constructsAnthropicAndBothGeminiCredentialModes() {
        BifrostProperties.ConnectionProperties anthropic = new BifrostProperties.ConnectionProperties();
        anthropic.setApiKey("test-key");
        assertThat(new AnthropicConnectionChatModelFactory().create("anthropic", anthropic))
                .isInstanceOf(AnthropicChatModel.class);

        ResourceLoader resourceLoader = mock(ResourceLoader.class);
        when(resourceLoader.getResource("test:credentials")).thenReturn(new ByteArrayResource("""
                {"type":"authorized_user","client_id":"client","client_secret":"secret","refresh_token":"token"}
                """.getBytes(StandardCharsets.UTF_8)));
        GeminiConnectionChatModelFactory geminiFactory = new GeminiConnectionChatModelFactory(resourceLoader);
        BifrostProperties.ConnectionProperties apiKeyGemini = new BifrostProperties.ConnectionProperties();
        apiKeyGemini.setApiKey("test-key");
        assertThat(geminiFactory.create("gemini-key", apiKeyGemini)).isInstanceOf(GoogleGenAiChatModel.class);

        BifrostProperties.ConnectionProperties vertexGemini = new BifrostProperties.ConnectionProperties();
        BifrostProperties.GeminiOptions vertex = new BifrostProperties.GeminiOptions();
        vertex.setVertexAi(true);
        vertex.setProjectId("test-project");
        vertex.setLocation("us-central1");
        vertex.setCredentialsUri("test:credentials");
        vertexGemini.setGemini(vertex);
        assertThat(geminiFactory.create("gemini-vertex", vertexGemini)).isInstanceOf(GoogleGenAiChatModel.class);
    }
}
