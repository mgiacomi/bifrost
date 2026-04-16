package com.lokiscale.bifrost.chat;

import com.lokiscale.bifrost.autoconfigure.AiProvider;
import com.lokiscale.bifrost.linter.LinterCallAdvisor;
import com.lokiscale.bifrost.outputschema.OutputSchemaCallAdvisor;
import com.lokiscale.bifrost.runtime.evidence.EvidenceContractCallAdvisor;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
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

public class SpringAiSkillChatClientFactory implements SkillChatClientFactory
{
    private static final Logger log = LoggerFactory.getLogger(SpringAiSkillChatClientFactory.class);

    private static final int LOW_THINKING_BUDGET = 1024;
    private static final int MEDIUM_THINKING_BUDGET = 4096;
    private static final int HIGH_THINKING_BUDGET = 8192;

    private final SkillChatModelResolver chatModelResolver;
    private final ChatClientBuilderFactory chatClientBuilderFactory;
    private final Map<AiProvider, SkillChatOptionsAdapter> adaptersByProvider;
    private final SkillAdvisorResolver skillAdvisorResolver;

    public SpringAiSkillChatClientFactory(SkillChatModelResolver chatModelResolver,
            List<SkillChatOptionsAdapter> adapters,
            SkillAdvisorResolver skillAdvisorResolver)
    {
        this(chatModelResolver, adapters, skillAdvisorResolver, ChatClient::builder);
    }

    SpringAiSkillChatClientFactory(SkillChatModelResolver chatModelResolver,
            List<SkillChatOptionsAdapter> adapters,
            SkillAdvisorResolver skillAdvisorResolver,
            ChatClientBuilderFactory chatClientBuilderFactory)
    {
        this.chatModelResolver = Objects.requireNonNull(chatModelResolver, "chatModelResolver must not be null");
        Objects.requireNonNull(adapters, "adapters must not be null");
        this.skillAdvisorResolver = Objects.requireNonNull(skillAdvisorResolver, "skillAdvisorResolver must not be null");
        this.chatClientBuilderFactory = Objects.requireNonNull(chatClientBuilderFactory, "chatClientBuilderFactory must not be null");
        this.adaptersByProvider = new EnumMap<>(AiProvider.class);
        for (SkillChatOptionsAdapter adapter : adapters)
        {
            this.adaptersByProvider.put(adapter.provider(), adapter);
        }
    }

    @Override
    public ChatClient create(YamlSkillDefinition definition)
    {
        return create(definition, true);
    }

    @Override
    public ChatClient createForStepExecution(YamlSkillDefinition definition)
    {
        return create(definition, false);
    }

    private ChatClient create(YamlSkillDefinition definition, boolean includeFinalResponseValidators)
    {
        Objects.requireNonNull(definition, "definition must not be null");
        EffectiveSkillExecutionConfiguration executionConfiguration = definition.executionConfiguration();
        String skillName = definition.manifest().getName();
        SkillChatOptionsAdapter adapter = adaptersByProvider.get(executionConfiguration.provider());
        if (adapter == null)
        {
            throw new IllegalStateException("No ChatOptions adapter configured for provider " + executionConfiguration.provider());
        }
        ChatModel chatModel = chatModelResolver.resolve(skillName, executionConfiguration.provider());
        ChatOptions options = adapter.createOptions(executionConfiguration);
        List<Advisor> advisors = resolvedAdvisors(skillAdvisorResolver.resolve(definition), includeFinalResponseValidators);
        ChatClient.Builder builder = chatClientBuilderFactory.create(chatModel);
        builder.defaultOptions(options);
        if (!advisors.isEmpty())
        {
            builder.defaultAdvisors(advisors);
        }
        ChatClient delegate = builder.build();
        log.debug(
                "Created skill ChatClient for skill '{}' provider={} chatModelType={} delegateType={} advisors={}",
                skillName,
                executionConfiguration.provider(),
                chatModel.getClass().getName(),
                delegate.getClass().getName(),
                includeFinalResponseValidators ? advisorNames(advisors) : advisorNames(advisors) + " (step execution)");
        return delegate;
    }

    private List<Advisor> resolvedAdvisors(List<Advisor> advisors, boolean includeFinalResponseValidators)
    {
        if (advisors == null)
        {
            return List.of();
        }
        if (includeFinalResponseValidators)
        {
            return List.copyOf(advisors);
        }
        return advisors.stream()
                .filter(advisor -> !(advisor instanceof OutputSchemaCallAdvisor))
                .filter(advisor -> !(advisor instanceof EvidenceContractCallAdvisor))
                .filter(advisor -> !(advisor instanceof LinterCallAdvisor))
                .toList();
    }

    interface ChatClientBuilderFactory
    {

        ChatClient.Builder create(ChatModel chatModel);
    }

    public static List<SkillChatOptionsAdapter> defaultAdapters()
    {
        return List.of(
                new OpenAiOptionsAdapter(),
                new AnthropicOptionsAdapter(),
                new GeminiOptionsAdapter(),
                new OllamaOptionsAdapter(),
                new TaalasOptionsAdapter());
    }

    private static String advisorNames(List<Advisor> advisors)
    {
        return advisors.stream()
                .map(advisor -> advisor.getName() + ":" + advisor.getClass().getSimpleName())
                .toList()
                .toString();
    }

    private static final class OpenAiOptionsAdapter implements SkillChatOptionsAdapter
    {
        @Override
        public AiProvider provider()
        {
            return AiProvider.OPENAI;
        }

        @Override
        public ChatOptions createOptions(EffectiveSkillExecutionConfiguration executionConfiguration)
        {
            OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                    .model(executionConfiguration.providerModel());
            if (executionConfiguration.thinkingLevel() != null)
            {
                builder.reasoningEffort(executionConfiguration.thinkingLevel());
            }
            return builder.build();
        }
    }

    private static final class AnthropicOptionsAdapter implements SkillChatOptionsAdapter
    {
        @Override
        public AiProvider provider()
        {
            return AiProvider.ANTHROPIC;
        }

        @Override
        public ChatOptions createOptions(EffectiveSkillExecutionConfiguration executionConfiguration)
        {
            AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder()
                    .model(executionConfiguration.providerModel());
            if (executionConfiguration.thinkingLevel() != null)
            {
                builder.thinking(AnthropicApi.ThinkingType.ENABLED, thinkingBudget(executionConfiguration.thinkingLevel()));
            }
            return builder.build();
        }
    }

    private static final class GeminiOptionsAdapter implements SkillChatOptionsAdapter
    {
        @Override
        public AiProvider provider()
        {
            return AiProvider.GEMINI;
        }

        @Override
        public ChatOptions createOptions(EffectiveSkillExecutionConfiguration executionConfiguration)
        {
            GoogleGenAiChatOptions.Builder builder = GoogleGenAiChatOptions.builder()
                    .model(executionConfiguration.providerModel());
            if (executionConfiguration.thinkingLevel() != null)
            {
                builder.includeThoughts(true)
                        .thinkingBudget(thinkingBudget(executionConfiguration.thinkingLevel()));
            }
            return builder.build();
        }
    }

    private static final class OllamaOptionsAdapter implements SkillChatOptionsAdapter
    {
        @Override
        public AiProvider provider()
        {
            return AiProvider.OLLAMA;
        }

        @Override
        public ChatOptions createOptions(EffectiveSkillExecutionConfiguration executionConfiguration)
        {
            return OllamaChatOptions.builder()
                    .model(executionConfiguration.providerModel())
                    .build();
        }
    }

    private static final class TaalasOptionsAdapter implements SkillChatOptionsAdapter
    {
        @Override
        public AiProvider provider()
        {
            return AiProvider.TAALAS;
        }

        @Override
        public ChatOptions createOptions(EffectiveSkillExecutionConfiguration executionConfiguration)
        {
            return ChatOptions.builder()
                    .model(executionConfiguration.providerModel())
                    .build();
        }
    }

    private static int thinkingBudget(String thinkingLevel)
    {
        return switch (thinkingLevel)
        {
            case "low" -> LOW_THINKING_BUDGET;
            case "medium" -> MEDIUM_THINKING_BUDGET;
            case "high" -> HIGH_THINKING_BUDGET;
            default -> throw new IllegalArgumentException("Unsupported thinking level '" + thinkingLevel + "'");
        };
    }
}
