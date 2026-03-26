package com.lokiscale.bifrost.autoconfigure;

import com.lokiscale.bifrost.chat.DefaultSkillAdvisorResolver;
import com.lokiscale.bifrost.chat.DefaultSkillChatModelResolver;
import com.lokiscale.bifrost.chat.SkillAdvisorResolver;
import com.lokiscale.bifrost.chat.SkillChatClientFactory;
import com.lokiscale.bifrost.chat.SkillChatModelResolver;
import com.lokiscale.bifrost.chat.SkillChatOptionsAdapter;
import com.lokiscale.bifrost.chat.SpringAiSkillChatClientFactory;
import com.lokiscale.bifrost.chat.TaalasChatModel;
import com.lokiscale.bifrost.core.BifrostExceptionTransformer;
import com.lokiscale.bifrost.core.CapabilityRegistry;
import com.lokiscale.bifrost.core.BifrostSessionRunner;
import com.lokiscale.bifrost.core.CapabilityExecutionRouter;
import com.lokiscale.bifrost.core.DefaultBifrostExceptionTransformer;
import com.lokiscale.bifrost.core.DefaultPlanTaskLinker;
import com.lokiscale.bifrost.core.ExecutionCoordinator;
import com.lokiscale.bifrost.core.InMemoryCapabilityRegistry;
import com.lokiscale.bifrost.core.PlanTaskLinker;
import com.lokiscale.bifrost.core.SkillMethodBeanPostProcessor;
import com.lokiscale.bifrost.runtime.DefaultMissionExecutionEngine;
import com.lokiscale.bifrost.runtime.MissionExecutionEngine;
import com.lokiscale.bifrost.runtime.planning.DefaultPlanningService;
import com.lokiscale.bifrost.runtime.planning.PlanningService;
import com.lokiscale.bifrost.runtime.state.DefaultExecutionStateService;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import com.lokiscale.bifrost.runtime.tool.DefaultToolCallbackFactory;
import com.lokiscale.bifrost.runtime.tool.DefaultToolSurfaceService;
import com.lokiscale.bifrost.runtime.tool.ToolCallbackFactory;
import com.lokiscale.bifrost.runtime.tool.ToolSurfaceService;
import com.lokiscale.bifrost.runtime.usage.DefaultSessionUsageService;
import com.lokiscale.bifrost.runtime.usage.MicrometerUsageMetricsRecorder;
import com.lokiscale.bifrost.runtime.usage.ModelUsageExtractor;
import com.lokiscale.bifrost.runtime.usage.NoOpUsageMetricsRecorder;
import com.lokiscale.bifrost.runtime.usage.SessionUsageService;
import com.lokiscale.bifrost.runtime.usage.UsageMetricsRecorder;
import com.lokiscale.bifrost.security.AccessGuard;
import com.lokiscale.bifrost.security.DefaultAccessGuard;
import com.lokiscale.bifrost.skill.DefaultSkillVisibilityResolver;
import com.lokiscale.bifrost.skill.SkillVisibilityResolver;
import com.lokiscale.bifrost.skill.YamlSkillCapabilityRegistrar;
import com.lokiscale.bifrost.skill.YamlSkillCatalog;
import com.lokiscale.bifrost.vfs.DefaultRefResolver;
import com.lokiscale.bifrost.vfs.RefResolver;
import com.lokiscale.bifrost.vfs.SessionLocalVirtualFileSystem;
import com.lokiscale.bifrost.vfs.VirtualFileSystem;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.boot.autoconfigure.AutoConfiguration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@AutoConfiguration
@EnableConfigurationProperties({
        BifrostSessionProperties.class,
        ExecutionTraceProperties.class,
        BifrostModelsProperties.class,
        BifrostSkillProperties.class,
        TaalasChatProperties.class
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
    public BifrostSessionRunner bifrostSessionRunner(BifrostSessionProperties sessionProperties,
                                                     Clock bifrostClock,
                                                     ExecutionTraceProperties executionTraceProperties) {
        return new BifrostSessionRunner(
                sessionProperties.getMaxDepth(),
                executionTraceProperties.getPersistence(),
                bifrostClock);
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public YamlSkillCatalog yamlSkillCatalog(BifrostModelsProperties modelsProperties,
                                             BifrostSkillProperties skillProperties) {
        // The catalog is the YAML discovery/loading boundary that downstream runtime beans build on.
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
    public AccessGuard accessGuard() {
        return new DefaultAccessGuard();
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public SkillVisibilityResolver skillVisibilityResolver(YamlSkillCatalog yamlSkillCatalog,
                                                           CapabilityRegistry capabilityRegistry,
                                                           AccessGuard accessGuard) {
        return new DefaultSkillVisibilityResolver(yamlSkillCatalog, capabilityRegistry, accessGuard);
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
                                                               org.springframework.beans.factory.ObjectProvider<ExecutionCoordinator> executionCoordinatorProvider,
                                                               ExecutionStateService executionStateService,
                                                               AccessGuard accessGuard) {
        return new CapabilityExecutionRouter(refResolver, executionCoordinatorProvider, executionStateService, accessGuard);
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
    public ModelUsageExtractor modelUsageExtractor() {
        return new ModelUsageExtractor();
    }

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public UsageMetricsRecorder usageMetricsRecorder(MeterRegistry meterRegistry) {
        return new MicrometerUsageMetricsRecorder(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(UsageMetricsRecorder.class)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public UsageMetricsRecorder noOpUsageMetricsRecorder() {
        return new NoOpUsageMetricsRecorder();
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public SessionUsageService sessionUsageService(BifrostSessionProperties sessionProperties,
                                                    UsageMetricsRecorder usageMetricsRecorder) {
        return new DefaultSessionUsageService(sessionProperties.getQuotas(), usageMetricsRecorder);
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public ExecutionStateService executionStateService(Clock bifrostClock,
                                                        SessionUsageService sessionUsageService) {
        return new DefaultExecutionStateService(bifrostClock, sessionUsageService);
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public PlanningService planningService(PlanTaskLinker planTaskLinker,
                                           ExecutionStateService executionStateService,
                                           SessionUsageService sessionUsageService,
                                           ModelUsageExtractor modelUsageExtractor) {
        return new DefaultPlanningService(planTaskLinker, executionStateService, sessionUsageService, modelUsageExtractor);
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public ToolSurfaceService toolSurfaceService(SkillVisibilityResolver skillVisibilityResolver) {
        return new DefaultToolSurfaceService(skillVisibilityResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public ToolCallbackFactory toolCallbackFactory(CapabilityExecutionRouter capabilityExecutionRouter,
                                                    PlanningService planningService,
                                                    ExecutionStateService executionStateService,
                                                    SessionUsageService sessionUsageService,
                                                    UsageMetricsRecorder usageMetricsRecorder) {
        return new DefaultToolCallbackFactory(
                capabilityExecutionRouter,
                planningService,
                executionStateService,
                sessionUsageService,
                usageMetricsRecorder);
    }

    @Bean(name = "bifrostMissionExecutor", destroyMethod = "close")
    @ConditionalOnMissingBean(name = "bifrostMissionExecutor")
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public ExecutorService bifrostMissionExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public MissionExecutionEngine missionExecutionEngine(PlanningService planningService,
                                                         ExecutionStateService executionStateService,
                                                         BifrostSessionProperties sessionProperties,
                                                         SessionUsageService sessionUsageService,
                                                         ModelUsageExtractor modelUsageExtractor,
                                                         @Qualifier("bifrostMissionExecutor") ExecutorService bifrostMissionExecutor) {
        return new DefaultMissionExecutionEngine(
                planningService,
                executionStateService,
                sessionProperties.getMissionTimeout(),
                bifrostMissionExecutor,
                sessionUsageService,
                modelUsageExtractor);
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.taalas", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(name = "taalasHttpClient")
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public HttpClient taalasHttpClient() {
        return HttpClient.newHttpClient();
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.taalas", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(name = "taalasChatModel")
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public TaalasChatModel taalasChatModel(@Qualifier("taalasHttpClient") HttpClient taalasHttpClient,
                                           ObjectProvider<ObjectMapper> objectMapperProvider,
                                           TaalasChatProperties taalasChatProperties) {
        return new TaalasChatModel(taalasHttpClient, objectMapperProvider.getIfAvailable(ObjectMapper::new), taalasChatProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public SkillChatModelResolver skillChatModelResolver(ObjectProvider<OpenAiChatModel> openAiChatModelProvider,
                                                         ObjectProvider<AnthropicChatModel> anthropicChatModelProvider,
                                                         ObjectProvider<GoogleGenAiChatModel> googleGenAiChatModelProvider,
                                                         ObjectProvider<OllamaChatModel> ollamaChatModelProvider,
                                                         @Qualifier("taalasChatModel") ObjectProvider<TaalasChatModel> taalasChatModelProvider) {
        Map<AiProvider, ChatModel> modelsByProvider = new EnumMap<>(AiProvider.class);
        registerChatModel(modelsByProvider, AiProvider.OPENAI, openAiChatModelProvider.getIfAvailable());
        registerChatModel(modelsByProvider, AiProvider.ANTHROPIC, anthropicChatModelProvider.getIfAvailable());
        registerChatModel(modelsByProvider, AiProvider.GEMINI, googleGenAiChatModelProvider.getIfAvailable());
        registerChatModel(modelsByProvider, AiProvider.OLLAMA, ollamaChatModelProvider.getIfAvailable());
        registerChatModel(modelsByProvider, AiProvider.TAALAS, taalasChatModelProvider.getIfAvailable());
        return new DefaultSkillChatModelResolver(modelsByProvider);
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
    @ConditionalOnMissingBean(name = "taalasSkillChatOptionsAdapter")
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public SkillChatOptionsAdapter taalasSkillChatOptionsAdapter() {
        return SpringAiSkillChatClientFactory.defaultAdapters().get(4);
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public SkillAdvisorResolver skillAdvisorResolver(ExecutionStateService executionStateService) {
        return new DefaultSkillAdvisorResolver(executionStateService);
    }

    @Bean
    @ConditionalOnBean(SkillChatModelResolver.class)
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public SkillChatClientFactory skillChatClientFactory(SkillChatModelResolver chatModelResolver,
                                                         List<SkillChatOptionsAdapter> adapters,
                                                         SkillAdvisorResolver skillAdvisorResolver) {
        return new SpringAiSkillChatClientFactory(chatModelResolver, adapters, skillAdvisorResolver);
    }

    @Bean
    @ConditionalOnBean(SkillChatClientFactory.class)
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public ExecutionCoordinator executionCoordinator(YamlSkillCatalog yamlSkillCatalog,
                                                     CapabilityRegistry capabilityRegistry,
                                                     SkillChatClientFactory skillChatClientFactory,
                                                     ToolSurfaceService toolSurfaceService,
                                                     ToolCallbackFactory toolCallbackFactory,
                                                     MissionExecutionEngine missionExecutionEngine,
                                                     ExecutionStateService executionStateService,
                                                     AccessGuard accessGuard) {
        return new ExecutionCoordinator(
                yamlSkillCatalog,
                capabilityRegistry,
                skillChatClientFactory,
                toolSurfaceService,
                toolCallbackFactory,
                missionExecutionEngine,
                executionStateService,
                accessGuard,
                true);
    }

    private static void registerChatModel(Map<AiProvider, ChatModel> modelsByProvider,
                                           AiProvider provider,
                                           ChatModel chatModel) {
        if (chatModel != null) {
            modelsByProvider.put(provider, chatModel);
        }
    }
}
