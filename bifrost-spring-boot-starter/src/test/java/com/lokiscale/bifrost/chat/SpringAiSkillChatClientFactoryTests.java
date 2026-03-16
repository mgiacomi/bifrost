package com.lokiscale.bifrost.chat;

import com.lokiscale.bifrost.autoconfigure.AiProvider;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiSkillChatClientFactoryTests {

    @Test
    void createsClientWithProviderModelAndNoThinkingOptionWhenThinkingIsNull() {
        FactoryClient created = createFactoryBackedClient(new EffectiveSkillExecutionConfiguration(
                "ollama-llama3",
                AiProvider.OLLAMA,
                "llama3.2",
                null));

        assertThat(created.options()).isInstanceOf(OllamaChatOptions.class);
        OllamaChatOptions options = (OllamaChatOptions) created.options();
        assertThat(options.getModel()).isEqualTo("llama3.2");
    }

    @Test
    void createsClientWithThinkingOptionWhenEffectiveConfigProvidesIt() {
        CapturedFactoryResult result = captureFactoryInvocation(new EffectiveSkillExecutionConfiguration(
                "gpt-5",
                AiProvider.OPENAI,
                "openai/gpt-5",
                "medium"));

        assertThat(result.client()).isNotNull();
        assertThat(result.options()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) result.options();
        assertThat(options.getModel()).isEqualTo("openai/gpt-5");
        assertThat(options.getReasoningEffort()).isEqualTo("medium");
    }

    @Test
    void dispatchesToProviderSpecificAdapter() {
        ChatOptions openAi = createFactoryBackedClient(new EffectiveSkillExecutionConfiguration(
                "gpt-5",
                AiProvider.OPENAI,
                "openai/gpt-5",
                "medium")).options();
        assertThat(openAi).isInstanceOf(OpenAiChatOptions.class);
        assertThat(((OpenAiChatOptions) openAi).getReasoningEffort()).isEqualTo("medium");

        ChatOptions anthropic = createFactoryBackedClient(new EffectiveSkillExecutionConfiguration(
                "claude-sonnet",
                AiProvider.ANTHROPIC,
                "anthropic/claude-sonnet-4",
                "medium")).options();
        assertThat(anthropic).isInstanceOf(AnthropicChatOptions.class);
        assertThat(((AnthropicChatOptions) anthropic).getThinking().type()).isEqualTo(AnthropicApi.ThinkingType.ENABLED);
        assertThat(((AnthropicChatOptions) anthropic).getThinking().budgetTokens()).isEqualTo(4096);

        ChatOptions gemini = createFactoryBackedClient(new EffectiveSkillExecutionConfiguration(
                "gemini-pro",
                AiProvider.GEMINI,
                "google/gemini-2.5-pro",
                "medium")).options();
        assertThat(gemini).isInstanceOf(GoogleGenAiChatOptions.class);
        assertThat(((GoogleGenAiChatOptions) gemini).getIncludeThoughts()).isTrue();
        assertThat(((GoogleGenAiChatOptions) gemini).getThinkingBudget()).isEqualTo(4096);

        ChatOptions ollama = createFactoryBackedClient(new EffectiveSkillExecutionConfiguration(
                "ollama-llama3",
                AiProvider.OLLAMA,
                "llama3.2",
                null)).options();
        assertThat(ollama).isInstanceOf(OllamaChatOptions.class);
        assertThat(((OllamaChatOptions) ollama).getModel()).isEqualTo("llama3.2");
    }

    private FactoryClient createFactoryBackedClient(EffectiveSkillExecutionConfiguration configuration) {
        return captureFactoryInvocation(configuration).client();
    }

    private CapturedFactoryResult captureFactoryInvocation(EffectiveSkillExecutionConfiguration configuration) {
        ChatClient.Builder rootBuilder = mock(ChatClient.Builder.class);
        ChatClient.Builder cloneBuilder = mock(ChatClient.Builder.class);
        FactoryClient client = new FactoryClient();
        when(rootBuilder.clone()).thenReturn(cloneBuilder);
        when(cloneBuilder.defaultOptions(any())).thenAnswer(invocation -> {
            client.setOptions(invocation.getArgument(0));
            return cloneBuilder;
        });
        when(cloneBuilder.build()).thenReturn(client);

        SpringAiSkillChatClientFactory factory = new SpringAiSkillChatClientFactory(
                rootBuilder,
                SpringAiSkillChatClientFactory.defaultAdapters());

        ChatClient created = factory.create(configuration);
        ArgumentCaptor<ChatOptions> captor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(cloneBuilder).defaultOptions(captor.capture());
        return new CapturedFactoryResult((FactoryClient) created, captor.getValue());
    }

    private record CapturedFactoryResult(FactoryClient client, ChatOptions options) {
    }

    private static final class FactoryClient implements ChatClient {

        private ChatOptions options;

        void setOptions(ChatOptions options) {
            this.options = options;
        }

        ChatOptions options() {
            return options;
        }

        @Override
        public ChatClientRequestSpec prompt() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatClientRequestSpec prompt(String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatClientRequestSpec prompt(org.springframework.ai.chat.prompt.Prompt prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Builder mutate() {
            throw new UnsupportedOperationException();
        }
    }
}
