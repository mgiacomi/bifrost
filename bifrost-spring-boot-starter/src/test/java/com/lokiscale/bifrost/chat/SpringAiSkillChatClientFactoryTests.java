package com.lokiscale.bifrost.chat;

import com.lokiscale.bifrost.autoconfigure.AiProvider;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import com.lokiscale.bifrost.skill.YamlSkillManifest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiSkillChatClientFactoryTests {

    @Test
    void createsClientWithProviderModelAndNoThinkingOptionWhenThinkingIsNull() {
        FactoryClient created = createFactoryBackedClient(definition(new EffectiveSkillExecutionConfiguration(
                "ollama-llama3",
                AiProvider.OLLAMA,
                "llama3.2",
                null)));

        assertThat(created.options()).isInstanceOf(OllamaChatOptions.class);
        OllamaChatOptions options = (OllamaChatOptions) created.options();
        assertThat(options.getModel()).isEqualTo("llama3.2");
    }

    @Test
    void createsClientWithThinkingOptionWhenEffectiveConfigProvidesIt() {
        CapturedFactoryResult result = captureFactoryInvocation(
                definition(new EffectiveSkillExecutionConfiguration(
                        "gpt-5",
                        AiProvider.OPENAI,
                        "openai/gpt-5",
                        "medium")),
                new NoOpSkillAdvisorResolver());

        assertThat(result.client()).isNotNull();
        assertThat(result.options()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) result.options();
        assertThat(options.getModel()).isEqualTo("openai/gpt-5");
        assertThat(options.getReasoningEffort()).isEqualTo("medium");
    }

    @Test
    void createsClientWithResolvedAdvisorsAndProviderOptions() {
        YamlSkillDefinition definition = definition(new EffectiveSkillExecutionConfiguration(
                "gpt-5",
                AiProvider.OPENAI,
                "openai/gpt-5",
                "medium"));
        Advisor advisor = mock(Advisor.class);

        CapturedFactoryResult result = captureFactoryInvocation(definition, ignored -> List.of(advisor));

        assertThat(result.options()).isInstanceOf(OpenAiChatOptions.class);
        assertThat(result.advisors()).containsExactly(advisor);
        verify(result.cloneBuilder()).defaultOptions(any(ChatOptions.class));
        verify(result.cloneBuilder()).defaultAdvisors(List.of(advisor));
        verify(result.cloneBuilder()).build();
    }

    @Test
    void doesNotAttachAdvisorsWhenResolverReturnsEmptyList() {
        YamlSkillDefinition definition = definition(new EffectiveSkillExecutionConfiguration(
                "gpt-5",
                AiProvider.OPENAI,
                "openai/gpt-5",
                "medium"));

        CapturedFactoryResult result = captureFactoryInvocation(definition, new NoOpSkillAdvisorResolver());

        assertThat(result.advisors()).isEmpty();
        verify(result.cloneBuilder()).defaultOptions(any(ChatOptions.class));
        verify(result.cloneBuilder(), never()).defaultAdvisors(anyList());
    }

    @Test
    void dispatchesToProviderSpecificAdapter() {
        ChatOptions openAi = createFactoryBackedClient(definition(new EffectiveSkillExecutionConfiguration(
                "gpt-5",
                AiProvider.OPENAI,
                "openai/gpt-5",
                "medium"))).options();
        assertThat(openAi).isInstanceOf(OpenAiChatOptions.class);
        assertThat(((OpenAiChatOptions) openAi).getReasoningEffort()).isEqualTo("medium");

        ChatOptions anthropic = createFactoryBackedClient(definition(new EffectiveSkillExecutionConfiguration(
                "claude-sonnet",
                AiProvider.ANTHROPIC,
                "anthropic/claude-sonnet-4",
                "medium"))).options();
        assertThat(anthropic).isInstanceOf(AnthropicChatOptions.class);
        assertThat(((AnthropicChatOptions) anthropic).getThinking().type()).isEqualTo(AnthropicApi.ThinkingType.ENABLED);
        assertThat(((AnthropicChatOptions) anthropic).getThinking().budgetTokens()).isEqualTo(4096);

        ChatOptions gemini = createFactoryBackedClient(definition(new EffectiveSkillExecutionConfiguration(
                "gemini-pro",
                AiProvider.GEMINI,
                "google/gemini-2.5-pro",
                "medium"))).options();
        assertThat(gemini).isInstanceOf(GoogleGenAiChatOptions.class);
        assertThat(((GoogleGenAiChatOptions) gemini).getIncludeThoughts()).isTrue();
        assertThat(((GoogleGenAiChatOptions) gemini).getThinkingBudget()).isEqualTo(4096);

        ChatOptions ollama = createFactoryBackedClient(definition(new EffectiveSkillExecutionConfiguration(
                "ollama-llama3",
                AiProvider.OLLAMA,
                "llama3.2",
                null))).options();
        assertThat(ollama).isInstanceOf(OllamaChatOptions.class);
        assertThat(((OllamaChatOptions) ollama).getModel()).isEqualTo("llama3.2");
    }

    private FactoryClient createFactoryBackedClient(YamlSkillDefinition definition) {
        return captureFactoryInvocation(definition, new NoOpSkillAdvisorResolver()).client();
    }

    private CapturedFactoryResult captureFactoryInvocation(YamlSkillDefinition definition, SkillAdvisorResolver skillAdvisorResolver) {
        ChatClient.Builder rootBuilder = mock(ChatClient.Builder.class);
        ChatClient.Builder cloneBuilder = mock(ChatClient.Builder.class);
        FactoryClient client = new FactoryClient();
        when(rootBuilder.clone()).thenReturn(cloneBuilder);
        when(cloneBuilder.defaultOptions(any())).thenAnswer(invocation -> {
            client.setOptions(invocation.getArgument(0));
            return cloneBuilder;
        });
        when(cloneBuilder.defaultAdvisors(anyList())).thenAnswer(invocation -> {
            client.setAdvisors(List.copyOf(invocation.getArgument(0)));
            return cloneBuilder;
        });
        when(cloneBuilder.build()).thenReturn(client);

        SpringAiSkillChatClientFactory factory = new SpringAiSkillChatClientFactory(
                rootBuilder,
                SpringAiSkillChatClientFactory.defaultAdapters(),
                skillAdvisorResolver);

        ChatClient created = factory.create(definition);
        ArgumentCaptor<ChatOptions> captor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(cloneBuilder).defaultOptions(captor.capture());
        return new CapturedFactoryResult((FactoryClient) created, captor.getValue(), cloneBuilder);
    }

    private YamlSkillDefinition definition(EffectiveSkillExecutionConfiguration configuration) {
        YamlSkillManifest manifest = new YamlSkillManifest();
        manifest.setName("test.skill");
        manifest.setDescription("test.skill");
        manifest.setModel(configuration.frameworkModel());
        return new YamlSkillDefinition(new ByteArrayResource(new byte[0]), manifest, configuration);
    }

    private record CapturedFactoryResult(FactoryClient client, ChatOptions options, ChatClient.Builder cloneBuilder) {

        List<Advisor> advisors() {
            return client.advisors();
        }
    }

    private static final class FactoryClient implements ChatClient {

        private ChatOptions options;
        private List<Advisor> advisors = List.of();

        void setOptions(ChatOptions options) {
            this.options = options;
        }

        void setAdvisors(List<Advisor> advisors) {
            this.advisors = advisors;
        }

        ChatOptions options() {
            return options;
        }

        List<Advisor> advisors() {
            return advisors;
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
