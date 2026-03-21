package com.lokiscale.bifrost.chat;

import com.lokiscale.bifrost.autoconfigure.AiProvider;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class SpringAiSkillChatClientFactory implements SkillChatClientFactory {

    private static final int LOW_THINKING_BUDGET = 1024;
    private static final int MEDIUM_THINKING_BUDGET = 4096;
    private static final int HIGH_THINKING_BUDGET = 8192;

    private final ChatClient.Builder chatClientBuilder;
    private final Map<AiProvider, SkillChatOptionsAdapter> adaptersByProvider;
    private final SkillAdvisorResolver skillAdvisorResolver;

    public SpringAiSkillChatClientFactory(ChatClient.Builder chatClientBuilder,
                                          List<SkillChatOptionsAdapter> adapters,
                                          SkillAdvisorResolver skillAdvisorResolver) {
        this.chatClientBuilder = Objects.requireNonNull(chatClientBuilder, "chatClientBuilder must not be null");
        Objects.requireNonNull(adapters, "adapters must not be null");
        this.skillAdvisorResolver = Objects.requireNonNull(skillAdvisorResolver, "skillAdvisorResolver must not be null");
        this.adaptersByProvider = new EnumMap<>(AiProvider.class);
        for (SkillChatOptionsAdapter adapter : adapters) {
            this.adaptersByProvider.put(adapter.provider(), adapter);
        }
    }

    @Override
    public ChatClient create(YamlSkillDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        EffectiveSkillExecutionConfiguration executionConfiguration = definition.executionConfiguration();
        SkillChatOptionsAdapter adapter = adaptersByProvider.get(executionConfiguration.provider());
        if (adapter == null) {
            throw new IllegalStateException("No ChatOptions adapter configured for provider " + executionConfiguration.provider());
        }
        ChatOptions options = adapter.createOptions(executionConfiguration);
        List<Advisor> advisors = skillAdvisorResolver.resolve(definition);
        ChatClient.Builder builder = chatClientBuilder.clone();
        builder.defaultOptions(options);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors);
        }
        return builder.build();
    }

    public static List<SkillChatOptionsAdapter> defaultAdapters() {
        return List.of(
                new OpenAiOptionsAdapter(),
                new AnthropicOptionsAdapter(),
                new GeminiOptionsAdapter(),
                new OllamaOptionsAdapter());
    }

    private static final class OpenAiOptionsAdapter implements SkillChatOptionsAdapter {

        @Override
        public AiProvider provider() {
            return AiProvider.OPENAI;
        }

        @Override
        public ChatOptions createOptions(EffectiveSkillExecutionConfiguration executionConfiguration) {
            OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                    .model(executionConfiguration.providerModel());
            if (executionConfiguration.thinkingLevel() != null) {
                builder.reasoningEffort(executionConfiguration.thinkingLevel());
            }
            return builder.build();
        }
    }

    private static final class AnthropicOptionsAdapter implements SkillChatOptionsAdapter {

        @Override
        public AiProvider provider() {
            return AiProvider.ANTHROPIC;
        }

        @Override
        public ChatOptions createOptions(EffectiveSkillExecutionConfiguration executionConfiguration) {
            AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder()
                    .model(executionConfiguration.providerModel());
            if (executionConfiguration.thinkingLevel() != null) {
                builder.thinking(AnthropicApi.ThinkingType.ENABLED, thinkingBudget(executionConfiguration.thinkingLevel()));
            }
            return builder.build();
        }
    }

    private static final class GeminiOptionsAdapter implements SkillChatOptionsAdapter {

        @Override
        public AiProvider provider() {
            return AiProvider.GEMINI;
        }

        @Override
        public ChatOptions createOptions(EffectiveSkillExecutionConfiguration executionConfiguration) {
            GoogleGenAiChatOptions.Builder builder = GoogleGenAiChatOptions.builder()
                    .model(executionConfiguration.providerModel());
            if (executionConfiguration.thinkingLevel() != null) {
                builder.includeThoughts(true)
                        .thinkingBudget(thinkingBudget(executionConfiguration.thinkingLevel()));
            }
            return builder.build();
        }
    }

    private static final class OllamaOptionsAdapter implements SkillChatOptionsAdapter {

        @Override
        public AiProvider provider() {
            return AiProvider.OLLAMA;
        }

        @Override
        public ChatOptions createOptions(EffectiveSkillExecutionConfiguration executionConfiguration) {
            return OllamaChatOptions.builder()
                    .model(executionConfiguration.providerModel())
                    .build();
        }
    }

    private static int thinkingBudget(String thinkingLevel) {
        return switch (thinkingLevel) {
            case "low" -> LOW_THINKING_BUDGET;
            case "medium" -> MEDIUM_THINKING_BUDGET;
            case "high" -> HIGH_THINKING_BUDGET;
            default -> throw new IllegalArgumentException("Unsupported thinking level '" + thinkingLevel + "'");
        };
    }
}
