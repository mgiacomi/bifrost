package com.lokiscale.bifrost.chat;

import com.lokiscale.bifrost.autoconfigure.AiProvider;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import com.lokiscale.bifrost.skill.YamlSkillManifest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiSkillChatClientFactoryTests {

    @Test
    void selectsChatModelFromResolvedProvider() {
        ChatModel ollamaChatModel = mock(ChatModel.class);
        SkillChatModelResolver chatModelResolver = mock(SkillChatModelResolver.class);
        when(chatModelResolver.resolve("test.skill", AiProvider.OLLAMA)).thenReturn(ollamaChatModel);

        CapturedFactoryResult result = captureFactoryInvocation(
                definition(new EffectiveSkillExecutionConfiguration(
                        "ollama-llama3",
                        AiProvider.OLLAMA,
                        "llama3.2",
                        null)),
                new NoOpSkillAdvisorResolver(),
                chatModelResolver);

        assertThat(result.resolvedChatModel()).isSameAs(ollamaChatModel);
        assertThat(result.options()).isInstanceOf(OllamaChatOptions.class);
        verify(chatModelResolver).resolve("test.skill", AiProvider.OLLAMA);
    }

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
                new NoOpSkillAdvisorResolver(),
                resolver(Map.of(AiProvider.OPENAI, mock(ChatModel.class))));

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
        verify(result.builder()).defaultOptions(any(ChatOptions.class));
        verify(result.builder()).defaultAdvisors(List.of(advisor));
        verify(result.builder()).build();
    }

    @Test
    void doesNotAttachAdvisorsWhenResolverReturnsEmptyList() {
        YamlSkillDefinition definition = definition(new EffectiveSkillExecutionConfiguration(
                "gpt-5",
                AiProvider.OPENAI,
                "openai/gpt-5",
                "medium"));

        CapturedFactoryResult result = captureFactoryInvocation(definition, new NoOpSkillAdvisorResolver());

        assertThat(result.client()).isSameAs(result.factoryClient());
        assertThat(result.advisors()).isEmpty();
        verify(result.builder()).defaultOptions(any(ChatOptions.class));
        verify(result.builder(), never()).defaultAdvisors(anyList());
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

        ChatOptions taalas = createFactoryBackedClient(definition(new EffectiveSkillExecutionConfiguration(
                "taalas-llama",
                AiProvider.TAALAS,
                "llama3.1-8B",
                null))).options();
        assertThat(taalas.getModel()).isEqualTo("llama3.1-8B");
    }

    @Test
    void throwsExecutionTimeErrorForUnavailableProvider() {
        SpringAiSkillChatClientFactory factory = new SpringAiSkillChatClientFactory(
                new DefaultSkillChatModelResolver(Map.of(AiProvider.OPENAI, mock(ChatModel.class))),
                SpringAiSkillChatClientFactory.defaultAdapters(),
                new NoOpSkillAdvisorResolver());

        assertThatThrownBy(() -> factory.create(definition(new EffectiveSkillExecutionConfiguration(
                "ollama-llama3",
                AiProvider.OLLAMA,
                "llama3.2",
                null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No ChatModel configured for provider OLLAMA required by skill 'test.skill'");
    }

    private FactoryClient createFactoryBackedClient(YamlSkillDefinition definition) {
        return captureFactoryInvocation(definition, new NoOpSkillAdvisorResolver()).factoryClient();
    }

    private CapturedFactoryResult captureFactoryInvocation(YamlSkillDefinition definition, SkillAdvisorResolver skillAdvisorResolver) {
        EffectiveSkillExecutionConfiguration executionConfiguration = definition.executionConfiguration();
        return captureFactoryInvocation(
                definition,
                skillAdvisorResolver,
                resolver(Map.of(executionConfiguration.provider(), mock(ChatModel.class))));
    }

    private CapturedFactoryResult captureFactoryInvocation(YamlSkillDefinition definition,
                                                            SkillAdvisorResolver skillAdvisorResolver,
                                                            SkillChatModelResolver chatModelResolver) {
        RecordingBuilderFactory builderFactory = new RecordingBuilderFactory();
        SpringAiSkillChatClientFactory factory = new SpringAiSkillChatClientFactory(
                chatModelResolver,
                SpringAiSkillChatClientFactory.defaultAdapters(),
                skillAdvisorResolver,
                builderFactory);

        ChatClient created = factory.create(definition);
        return builderFactory.result(created);
    }

    private SkillChatModelResolver resolver(Map<AiProvider, ChatModel> modelsByProvider) {
        return new DefaultSkillChatModelResolver(new EnumMap<>(modelsByProvider));
    }

    private YamlSkillDefinition definition(EffectiveSkillExecutionConfiguration configuration) {
        YamlSkillManifest manifest = new YamlSkillManifest();
        manifest.setName("test.skill");
        manifest.setDescription("test.skill");
        manifest.setModel(configuration.frameworkModel());
        return new YamlSkillDefinition(new ByteArrayResource(new byte[0]), manifest, configuration);
    }

    private record CapturedFactoryResult(ChatClient client,
                                          FactoryClient factoryClient,
                                          ChatOptions options,
                                          ChatClient.Builder builder,
                                          ChatModel resolvedChatModel) {

        List<Advisor> advisors() {
            return factoryClient.advisors();
        }
    }

    private static final class RecordingBuilderFactory implements SpringAiSkillChatClientFactory.ChatClientBuilderFactory {

        private ChatModel resolvedChatModel;
        private ChatClient.Builder builder;
        private FactoryClient factoryClient;

        @Override
        public ChatClient.Builder create(ChatModel chatModel) {
            this.resolvedChatModel = chatModel;
            this.builder = mock(ChatClient.Builder.class);
            this.factoryClient = new FactoryClient();
            when(builder.defaultOptions(any())).thenAnswer(invocation -> {
                factoryClient.setOptions(invocation.getArgument(0));
                return builder;
            });
            when(builder.defaultAdvisors(anyList())).thenAnswer(invocation -> {
                factoryClient.setAdvisors(List.copyOf(invocation.getArgument(0)));
                return builder;
            });
            when(builder.build()).thenReturn(factoryClient);
            return builder;
        }

        private CapturedFactoryResult result(ChatClient created) {
            return new CapturedFactoryResult(created, factoryClient, factoryClient.options(), builder, resolvedChatModel);
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
