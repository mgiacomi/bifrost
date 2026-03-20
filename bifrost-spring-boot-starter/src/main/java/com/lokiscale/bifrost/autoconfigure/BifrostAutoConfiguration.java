package com.lokiscale.bifrost.autoconfigure;

import com.lokiscale.bifrost.chat.SkillChatClientFactory;
import com.lokiscale.bifrost.chat.SkillChatOptionsAdapter;
import com.lokiscale.bifrost.chat.SpringAiSkillChatClientFactory;
import com.lokiscale.bifrost.core.BifrostExceptionTransformer;
import com.lokiscale.bifrost.core.CapabilityToolCallbackAdapter;
import com.lokiscale.bifrost.core.CapabilityRegistry;
import com.lokiscale.bifrost.core.BifrostSessionRunner;
import com.lokiscale.bifrost.core.CapabilityExecutionRouter;
import com.lokiscale.bifrost.core.DefaultBifrostExceptionTransformer;
import com.lokiscale.bifrost.core.DefaultPlanTaskLinker;
import com.lokiscale.bifrost.core.ExecutionCoordinator;
import com.lokiscale.bifrost.core.InMemoryCapabilityRegistry;
import com.lokiscale.bifrost.core.PlanTaskLinker;
import com.lokiscale.bifrost.core.SkillMethodBeanPostProcessor;
import com.lokiscale.bifrost.skill.DefaultSkillVisibilityResolver;
import com.lokiscale.bifrost.skill.SkillVisibilityResolver;
import com.lokiscale.bifrost.skill.YamlSkillCapabilityRegistrar;
import com.lokiscale.bifrost.skill.YamlSkillCatalog;
import com.lokiscale.bifrost.vfs.DefaultRefResolver;
import com.lokiscale.bifrost.vfs.RefResolver;
import com.lokiscale.bifrost.vfs.SessionLocalVirtualFileSystem;
import com.lokiscale.bifrost.vfs.VirtualFileSystem;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.boot.autoconfigure.AutoConfiguration;

import java.nio.file.Paths;
import java.time.Clock;
import java.util.List;

@AutoConfiguration
@EnableConfigurationProperties({
        BifrostSessionProperties.class,
        BifrostModelsProperties.class,
        BifrostSkillProperties.class
})
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class BifrostAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CapabilityRegistry.class)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public CapabilityRegistry capabilityRegistry() {
        return new InMemoryCapabilityRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public BifrostExceptionTransformer bifrostExceptionTransformer() {
        return new DefaultBifrostExceptionTransformer();
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static SkillMethodBeanPostProcessor skillMethodBeanPostProcessor(
            CapabilityRegistry capabilityRegistry,
            BifrostExceptionTransformer bifrostExceptionTransformer) {
        return SkillMethodBeanPostProcessor.create(capabilityRegistry, bifrostExceptionTransformer);
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public BifrostSessionRunner bifrostSessionRunner(BifrostSessionProperties sessionProperties) {
        return new BifrostSessionRunner(sessionProperties.getMaxDepth());
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public YamlSkillCatalog yamlSkillCatalog(BifrostModelsProperties modelsProperties,
                                             BifrostSkillProperties skillProperties) {
        return new YamlSkillCatalog(modelsProperties, skillProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public YamlSkillCapabilityRegistrar yamlSkillCapabilityRegistrar(CapabilityRegistry capabilityRegistry,
                                                                     YamlSkillCatalog yamlSkillCatalog) {
        return new YamlSkillCapabilityRegistrar(capabilityRegistry, yamlSkillCatalog);
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public SkillVisibilityResolver skillVisibilityResolver(YamlSkillCatalog yamlSkillCatalog,
                                                           CapabilityRegistry capabilityRegistry) {
        return new DefaultSkillVisibilityResolver(yamlSkillCatalog, capabilityRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public VirtualFileSystem virtualFileSystem() {
        return new SessionLocalVirtualFileSystem(Paths.get(System.getProperty("java.io.tmpdir"), "bifrost-vfs"));
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public RefResolver refResolver(VirtualFileSystem virtualFileSystem) {
        return new DefaultRefResolver(virtualFileSystem);
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public CapabilityExecutionRouter capabilityExecutionRouter(RefResolver refResolver,
                                                               org.springframework.beans.factory.ObjectProvider<ExecutionCoordinator> executionCoordinatorProvider) {
        return new CapabilityExecutionRouter(refResolver, executionCoordinatorProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public Clock bifrostClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public PlanTaskLinker planTaskLinker() {
        return new DefaultPlanTaskLinker();
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public CapabilityToolCallbackAdapter capabilityToolCallbackAdapter(CapabilityExecutionRouter capabilityExecutionRouter,
                                                                       PlanTaskLinker planTaskLinker,
                                                                       Clock bifrostClock) {
        return new CapabilityToolCallbackAdapter(capabilityExecutionRouter, planTaskLinker, bifrostClock);
    }

    @Bean
    @ConditionalOnMissingBean(name = "openAiSkillChatOptionsAdapter")
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public SkillChatOptionsAdapter openAiSkillChatOptionsAdapter() {
        return SpringAiSkillChatClientFactory.defaultAdapters().get(0);
    }

    @Bean
    @ConditionalOnMissingBean(name = "anthropicSkillChatOptionsAdapter")
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public SkillChatOptionsAdapter anthropicSkillChatOptionsAdapter() {
        return SpringAiSkillChatClientFactory.defaultAdapters().get(1);
    }

    @Bean
    @ConditionalOnMissingBean(name = "geminiSkillChatOptionsAdapter")
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public SkillChatOptionsAdapter geminiSkillChatOptionsAdapter() {
        return SpringAiSkillChatClientFactory.defaultAdapters().get(2);
    }

    @Bean
    @ConditionalOnMissingBean(name = "ollamaSkillChatOptionsAdapter")
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public SkillChatOptionsAdapter ollamaSkillChatOptionsAdapter() {
        return SpringAiSkillChatClientFactory.defaultAdapters().get(3);
    }

    @Bean
    @ConditionalOnBean(ChatClient.Builder.class)
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public SkillChatClientFactory skillChatClientFactory(ChatClient.Builder chatClientBuilder,
                                                         List<SkillChatOptionsAdapter> adapters) {
        return new SpringAiSkillChatClientFactory(chatClientBuilder, adapters);
    }

    @Bean
    @ConditionalOnBean(SkillChatClientFactory.class)
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public ExecutionCoordinator executionCoordinator(YamlSkillCatalog yamlSkillCatalog,
                                                     CapabilityRegistry capabilityRegistry,
                                                     SkillVisibilityResolver skillVisibilityResolver,
                                                     SkillChatClientFactory skillChatClientFactory,
                                                     CapabilityToolCallbackAdapter capabilityToolCallbackAdapter,
                                                     Clock bifrostClock) {
        return new ExecutionCoordinator(
                yamlSkillCatalog,
                capabilityRegistry,
                skillVisibilityResolver,
                skillChatClientFactory,
                capabilityToolCallbackAdapter,
                bifrostClock,
                true);
    }
}
