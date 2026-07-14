package com.lokiscale.bifrost.autoconfigure;

import com.lokiscale.bifrost.annotation.SkillMethod;
import com.lokiscale.bifrost.chat.DefaultSkillAdvisorResolver;
import com.lokiscale.bifrost.chat.SkillAdvisorResolver;
import com.lokiscale.bifrost.chat.SkillChatClientFactory;
import com.lokiscale.bifrost.chat.SkillChatModelResolver;
import com.lokiscale.bifrost.chat.SpringAiSkillChatClientFactory;
import com.lokiscale.bifrost.core.BifrostExceptionTransformer;
import com.lokiscale.bifrost.core.BifrostSessionRunner;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.CapabilityRegistry;
import com.lokiscale.bifrost.core.ExecutionCoordinator;
import com.lokiscale.bifrost.core.InMemorySkillImplementationTargetRegistry;
import com.lokiscale.bifrost.core.SkillMethodBeanPostProcessor;
import com.lokiscale.bifrost.core.SkillImplementationTargetRegistry;
import com.lokiscale.bifrost.runtime.input.SkillInputContractResolver;
import com.lokiscale.bifrost.runtime.input.SkillInputValidator;
import com.lokiscale.bifrost.skill.SkillVisibilityResolver;
import com.lokiscale.bifrost.skill.YamlSkillCatalog;
import com.lokiscale.bifrost.skillapi.SkillTemplate;
import com.lokiscale.bifrost.vfs.RefResolver;
import com.lokiscale.bifrost.vfs.VirtualFileSystem;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BifrostAutoConfigurationTests {

    private final ApplicationContextRunner modelFreeContextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    BifrostAutoConfiguration.class));

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    BifrostAutoConfiguration.class))
            .withInitializer(context -> {
                try {
                    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
                    for (PropertySource<?> propertySource : loader.load("application-test", new ClassPathResource("application-test.yml"))) {
                        context.getEnvironment().getPropertySources().addLast(propertySource);
                    }
                }
                catch (IOException ex) {
                    throw new IllegalStateException("Failed to load application-test.yml", ex);
                }
            });

    @Test
    void hasAutoConfigurationAnnotation() {
        assertThat(BifrostAutoConfiguration.class.isAnnotationPresent(AutoConfiguration.class)).isTrue();
    }

    @Test
    void isRegisteredInAutoConfigurationImports() throws IOException {
        try (InputStream stream = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(stream).isNotNull();
            String imports = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(imports).contains("com.lokiscale.bifrost.autoconfigure.BifrostAutoConfiguration");
        }
    }

    @Test
    void autoConfiguresSessionRunnerAndProperties() {
        contextRunner
                .withPropertyValues(
                        "bifrost.session.max-depth=5",
                        "bifrost.skills.locations=classpath:/skills/none/**/*.yaml")
                .run(context -> {
                    assertThat(context).hasSingleBean(BifrostSessionRunner.class);
                    assertThat(context).hasSingleBean(BifrostExceptionTransformer.class);
                    assertThat(context).hasSingleBean(BifrostSessionProperties.class);
                    assertThat(context).hasSingleBean(ExecutionTraceProperties.class);
                    assertThat(context).hasSingleBean(CapabilityRegistry.class);
                    assertThat(context).hasSingleBean(SkillImplementationTargetRegistry.class);
                    assertThat(context).hasSingleBean(BifrostModelsProperties.class);
                    assertThat(context).hasSingleBean(YamlSkillCatalog.class);
                    assertThat(context).hasSingleBean(SkillVisibilityResolver.class);
                    assertThat(context).hasSingleBean(VirtualFileSystem.class);
                    assertThat(context).hasSingleBean(RefResolver.class);
                    assertThat(context).hasSingleBean(Clock.class);
                    assertThat(context).hasSingleBean(SkillInputContractResolver.class);
                    assertThat(context).hasSingleBean(SkillInputValidator.class);
                    assertThat(context).hasSingleBean(SkillTemplate.class);
                    assertThat(context.getBean(BifrostSessionProperties.class).getMaxDepth()).isEqualTo(5);
                });
    }

    @Test
    void reusesSharedSkillInputContractResolverAcrossRegistrationPaths() {
        contextRunner
                .withUserConfiguration(MappedSkillTargetConfiguration.class)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/mapped-method-skill.yaml")
                .run(context -> {
                    SkillInputContractResolver resolver = context.getBean(SkillInputContractResolver.class);
                    SkillMethodBeanPostProcessor beanPostProcessor = context.getBean(SkillMethodBeanPostProcessor.class);
                    Object registrar = context.getBean("yamlSkillCapabilityRegistrar");

                    assertThat(ReflectionTestUtils.getField(beanPostProcessor, "inputContractResolver")).isSameAs(resolver);
                    assertThat(ReflectionTestUtils.getField(registrar, "inputContractResolver")).isSameAs(resolver);
                });
    }

    @Test
    void invokesMappedSkillWithoutModelsOrChatModel() {
        modelFreeContextRunner
                .withUserConfiguration(MappedSkillTargetConfiguration.class)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/model-free-mapped-skill.yaml")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(YamlSkillCatalog.class);
                    assertThat(context).hasSingleBean(SkillImplementationTargetRegistry.class);
                    assertThat(context).hasSingleBean(CapabilityRegistry.class);
                    assertThat(context).hasSingleBean(SkillTemplate.class);
                    assertThat(context.getBeansOfType(ChatModel.class)).isEmpty();

                    CapabilityMetadata metadata = context.getBean(CapabilityRegistry.class)
                            .getCapability("modelFreeMappedSkill");
                    assertThat(metadata.skillExecution().configured()).isFalse();
                    assertThat(context.getBean(SkillTemplate.class)
                            .invoke("modelFreeMappedSkill", Map.of("input", "alpha")))
                            .isEqualTo("\"mapped:alpha\"");
                });
    }

    @Test
    void autoConfiguresExecutionCoordinatorWhenSkillChatClientFactoryIsAvailable() {
        contextRunner
                .withBean(SkillChatClientFactory.class,
                        () -> definition -> Mockito.mock(org.springframework.ai.chat.client.ChatClient.class))
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/default-thinking-skill.yaml")
                .run(context -> assertThat(context).hasSingleBean(ExecutionCoordinator.class));
    }

    @Test
    void autoConfiguresDefaultSkillAdvisorResolver() {
        OpenAiChatModel openAiChatModel = Mockito.mock(OpenAiChatModel.class);
        contextRunner
                .withBean(OpenAiChatModel.class, () -> openAiChatModel)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/default-thinking-skill.yaml")
                .run(context -> {
                    assertThat(context).hasSingleBean(SkillAdvisorResolver.class);
                    assertThat(context).hasSingleBean(SkillChatModelResolver.class);
                    assertThat(context).hasSingleBean(SkillChatClientFactory.class);
                    assertThat(context.getBean(SkillAdvisorResolver.class)).isInstanceOf(DefaultSkillAdvisorResolver.class);
                });
    }

    @Test
    void allowsCustomSkillAdvisorResolverOverride() {
        SkillAdvisorResolver customResolver = definition -> List.of();
        OpenAiChatModel openAiChatModel = Mockito.mock(OpenAiChatModel.class);

        contextRunner
                .withBean(OpenAiChatModel.class, () -> openAiChatModel)
                .withBean(SkillAdvisorResolver.class, () -> customResolver)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/default-thinking-skill.yaml")
                .run(context -> {
                    assertThat(context).hasSingleBean(SkillAdvisorResolver.class);
                    assertThat(context).hasSingleBean(SkillChatClientFactory.class);
                    assertThat(context.getBean(SkillAdvisorResolver.class)).isSameAs(customResolver);
                });
    }

    @Test
    void bindsProviderAwareModelCatalogFromApplicationTestYaml() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/default-thinking-skill.yaml")
                .run(context -> {
                    BifrostModelsProperties properties = context.getBean(BifrostModelsProperties.class);

                    assertThat(properties.getModels()).containsKeys("gpt-5", "claude-sonnet", "gemini-pro", "ollama-llama3");
                    assertThat(properties.getModels().get("gpt-5").getProvider()).isEqualTo(AiProvider.OPENAI);
                    assertThat(properties.getModels().get("claude-sonnet").getProvider()).isEqualTo(AiProvider.ANTHROPIC);
                    assertThat(properties.getModels().get("gemini-pro").getProvider()).isEqualTo(AiProvider.GEMINI);
                    assertThat(properties.getModels().get("ollama-llama3").getProvider()).isEqualTo(AiProvider.OLLAMA);
                    assertThat(properties.getModels().get("gpt-5").getProviderModel()).isEqualTo("openai/gpt-5");
                    assertThat(properties.getModels().get("claude-sonnet").getThinkingLevels()).containsExactlyInAnyOrder("low", "medium", "high");
                    assertThat(properties.getModels().get("ollama-llama3").getThinkingLevels()).isEmpty();
                });
    }

    @Test
    void supportsMultiProviderResolverRegistrationWithoutTypeCollapse() {
        OpenAiChatModel openAiChatModel = Mockito.mock(OpenAiChatModel.class);
        OllamaChatModel ollamaChatModel = Mockito.mock(OllamaChatModel.class);

        contextRunner
                .withBean(OpenAiChatModel.class, () -> openAiChatModel)
                .withBean(OllamaChatModel.class, () -> ollamaChatModel)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/default-thinking-skill.yaml")
                .run(context -> {
                    SkillChatModelResolver resolver = context.getBean(SkillChatModelResolver.class);

                    assertThat(resolver.resolve("openaiSkill", AiProvider.OPENAI)).isSameAs(openAiChatModel);
                    assertThat(resolver.resolve("ollamaSkill", AiProvider.OLLAMA)).isSameAs(ollamaChatModel);
                });
    }

    @Test
    void exposesSkillChatClientFactoryBackedByResolver() {
        OpenAiChatModel openAiChatModel = Mockito.mock(OpenAiChatModel.class);

        contextRunner
                .withBean(OpenAiChatModel.class, () -> openAiChatModel)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/default-thinking-skill.yaml")
                .run(context -> {
                    assertThat(context).hasSingleBean(SkillChatModelResolver.class);
                    assertThat(context).hasSingleBean(SkillChatClientFactory.class);
                    assertThat(context.getBean(SkillChatClientFactory.class)).isInstanceOf(SpringAiSkillChatClientFactory.class);
                });
    }

    @Test
    void registersYamlCapabilityMetadataWithEffectiveExecutionDescriptor() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/default-thinking-skill.yaml")
                .run(context -> {
                    CapabilityRegistry capabilityRegistry = context.getBean(CapabilityRegistry.class);
                    CapabilityMetadata metadata = capabilityRegistry.getCapability("thinkingDefaultSkill");

                    assertThat(metadata).isNotNull();
                    assertThat(metadata.skillExecution().configured()).isTrue();
                    assertThat(metadata.skillExecution().frameworkModel()).isEqualTo("gpt-5");
                    assertThat(metadata.skillExecution().provider()).isEqualTo(AiProvider.OPENAI);
                    assertThat(metadata.skillExecution().providerModel()).isEqualTo("openai/gpt-5");
                    assertThat(metadata.skillExecution().thinkingLevel()).isEqualTo("medium");
                });
    }

    @Test
    void publishesYamlSkillAndKeepsDiscoveredTargetInternal() {
        contextRunner
                .withUserConfiguration(MappedSkillTargetConfiguration.class)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/mapped-method-skill.yaml")
                .run(context -> {
                    CapabilityRegistry capabilityRegistry = context.getBean(CapabilityRegistry.class);
                    SkillImplementationTargetRegistry targetRegistry = context.getBean(SkillImplementationTargetRegistry.class);

                    assertThat(capabilityRegistry.getCapability("deterministicTarget")).isNull();
                    assertThat(capabilityRegistry.getCapability("mappedMethodSkill")).isNotNull();
                    assertThat(targetRegistry.getTarget("targetBean#deterministicTarget")).isNotNull();
                });
    }

    @Test
    void backsOffWhenApplicationProvidesImplementationTargetRegistry() {
        SkillImplementationTargetRegistry customRegistry = new InMemorySkillImplementationTargetRegistry();

        contextRunner
                .withBean(SkillImplementationTargetRegistry.class, () -> customRegistry)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/none/**/*.yaml")
                .run(context -> {
                    assertThat(context).hasSingleBean(SkillImplementationTargetRegistry.class);
                    assertThat(context.getBean(SkillImplementationTargetRegistry.class)).isSameAs(customRegistry);
                    assertThat(context).hasSingleBean(CapabilityRegistry.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class MappedSkillTargetConfiguration {

        @Bean
        TargetBean targetBean() {
            return new TargetBean();
        }
    }

    static class TargetBean {

        @SkillMethod(description = "Deterministic target")
        String deterministicTarget(String input) {
            return "mapped:" + input;
        }
    }
}
