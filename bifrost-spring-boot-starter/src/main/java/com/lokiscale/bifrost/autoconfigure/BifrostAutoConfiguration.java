package com.lokiscale.bifrost.autoconfigure;

import com.lokiscale.bifrost.internal.autoconfigure.AiConnectionChatModelFactory;
import com.lokiscale.bifrost.internal.autoconfigure.AnthropicConnectionChatModelFactory;
import com.lokiscale.bifrost.internal.autoconfigure.GeminiConnectionChatModelFactory;
import com.lokiscale.bifrost.internal.autoconfigure.NamedAiConnectionRegistry;
import com.lokiscale.bifrost.internal.autoconfigure.OllamaConnectionChatModelFactory;
import com.lokiscale.bifrost.internal.autoconfigure.OpenAiConnectionChatModelFactory;
import com.lokiscale.bifrost.internal.chat.DefaultSkillAdvisorResolver;
import com.lokiscale.bifrost.internal.chat.DefaultSkillChatModelResolver;
import com.lokiscale.bifrost.internal.chat.SkillAdvisorResolver;
import com.lokiscale.bifrost.internal.chat.SkillChatClientFactory;
import com.lokiscale.bifrost.internal.chat.SkillChatModelResolver;
import com.lokiscale.bifrost.internal.chat.SkillChatOptionsAdapter;
import com.lokiscale.bifrost.internal.chat.SpringAiSkillChatClientFactory;
import com.lokiscale.bifrost.internal.core.BifrostExceptionTransformer;
import com.lokiscale.bifrost.internal.core.CapabilityRegistry;
import com.lokiscale.bifrost.internal.core.BifrostSessionRunner;
import com.lokiscale.bifrost.internal.core.CapabilityExecutionRouter;
import com.lokiscale.bifrost.internal.core.DefaultBifrostExceptionTransformer;
import com.lokiscale.bifrost.internal.core.DefaultPlanTaskLinker;
import com.lokiscale.bifrost.internal.core.ExecutionCoordinator;
import com.lokiscale.bifrost.internal.core.InMemoryCapabilityRegistry;
import com.lokiscale.bifrost.internal.core.InMemorySkillImplementationTargetRegistry;
import com.lokiscale.bifrost.internal.core.PlanTaskLinker;
import com.lokiscale.bifrost.internal.core.SkillMethodBeanPostProcessor;
import com.lokiscale.bifrost.internal.core.SkillImplementationTargetRegistry;
import com.lokiscale.bifrost.internal.runtime.DefaultMissionExecutionEngine;
import com.lokiscale.bifrost.internal.runtime.MissionExecutionEngine;
import com.lokiscale.bifrost.internal.runtime.attachment.DefaultMissionInputMaterializer;
import com.lokiscale.bifrost.internal.runtime.attachment.MissionInputMaterializer;
import com.lokiscale.bifrost.internal.runtime.attachment.MissionUserMessageSender;
import com.lokiscale.bifrost.internal.runtime.attachment.SpringAiMissionUserMessageSender;
import com.lokiscale.bifrost.internal.runtime.planning.DefaultPlanningService;
import com.lokiscale.bifrost.internal.runtime.planning.PlanningService;
import com.lokiscale.bifrost.internal.runtime.input.SkillInputContractResolver;
import com.lokiscale.bifrost.internal.runtime.input.SkillInputValidator;
import com.lokiscale.bifrost.internal.runtime.state.DefaultExecutionStateService;
import com.lokiscale.bifrost.internal.runtime.state.ExecutionStateService;
import com.lokiscale.bifrost.internal.runtime.tool.DefaultToolCallbackFactory;
import com.lokiscale.bifrost.internal.runtime.tool.DefaultToolSurfaceService;
import com.lokiscale.bifrost.internal.runtime.tool.ToolCallbackFactory;
import com.lokiscale.bifrost.internal.runtime.tool.ToolSurfaceService;
import com.lokiscale.bifrost.internal.runtime.usage.DefaultSessionUsageService;
import com.lokiscale.bifrost.internal.runtime.usage.MicrometerUsageMetricsRecorder;
import com.lokiscale.bifrost.internal.runtime.usage.ModelUsageExtractor;
import com.lokiscale.bifrost.internal.runtime.usage.NoOpUsageMetricsRecorder;
import com.lokiscale.bifrost.internal.runtime.usage.SessionUsageService;
import com.lokiscale.bifrost.internal.runtime.usage.UsageMetricsRecorder;
import com.lokiscale.bifrost.internal.security.AccessGuard;
import com.lokiscale.bifrost.internal.security.DefaultAccessGuard;
import com.lokiscale.bifrost.internal.skill.DefaultSkillVisibilityResolver;
import com.lokiscale.bifrost.internal.skill.SkillVisibilityResolver;
import com.lokiscale.bifrost.internal.skill.YamlSkillCapabilityRegistrar;
import com.lokiscale.bifrost.internal.skill.YamlSkillCatalog;
import com.lokiscale.bifrost.internal.skillapi.DefaultSkillTemplate;
import com.lokiscale.bifrost.api.SkillTemplate;
import com.lokiscale.bifrost.internal.vfs.DefaultRefResolver;
import com.lokiscale.bifrost.internal.vfs.RefResolver;
import com.lokiscale.bifrost.internal.vfs.SessionLocalVirtualFileSystem;
import com.lokiscale.bifrost.internal.vfs.VirtualFileSystem;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.core.io.ResourceLoader;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@AutoConfiguration
@EnableConfigurationProperties({
        ExecutionTraceProperties.class,
        BifrostProperties.class
})
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class BifrostAutoConfiguration
{
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    CapabilityRegistry capabilityRegistry()
    {
        return new InMemoryCapabilityRegistry();
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    SkillImplementationTargetRegistry skillImplementationTargetRegistry()
    {
        return new InMemorySkillImplementationTargetRegistry();
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    BifrostExceptionTransformer bifrostExceptionTransformer()
    {
        return new DefaultBifrostExceptionTransformer();
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static SkillMethodBeanPostProcessor skillMethodBeanPostProcessor(
            SkillImplementationTargetRegistry skillImplementationTargetRegistry,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            BifrostExceptionTransformer bifrostExceptionTransformer,
            SkillInputContractResolver skillInputContractResolver)
    {
        return SkillMethodBeanPostProcessor.create(
                skillImplementationTargetRegistry,
                objectMapperProvider.getIfAvailable(ObjectMapper::new),
                bifrostExceptionTransformer,
                skillInputContractResolver);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    BifrostSessionRunner bifrostSessionRunner(BifrostProperties properties,
            ExecutionTraceProperties executionTraceProperties)
    {
        return new BifrostSessionRunner(
                properties.getSession().getMaxDepth(),
                executionTraceProperties.getPersistence(),
                Clock.systemUTC());
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    YamlSkillCatalog yamlSkillCatalog(BifrostProperties properties)
    {
        // The catalog is the YAML discovery/loading boundary that downstream runtime beans build on.
        return new YamlSkillCatalog(properties);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    YamlSkillCapabilityRegistrar yamlSkillCapabilityRegistrar(CapabilityRegistry capabilityRegistry,
            SkillImplementationTargetRegistry skillImplementationTargetRegistry,
            YamlSkillCatalog yamlSkillCatalog,
            SkillInputContractResolver skillInputContractResolver)
    {
        return new YamlSkillCapabilityRegistrar(
                capabilityRegistry,
                skillImplementationTargetRegistry,
                yamlSkillCatalog,
                skillInputContractResolver);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    SkillInputContractResolver skillInputContractResolver(ObjectProvider<ObjectMapper> objectMapperProvider)
    {
        return new SkillInputContractResolver(objectMapperProvider.getIfAvailable(ObjectMapper::new));
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    SkillInputValidator skillInputValidator()
    {
        return new SkillInputValidator();
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    AccessGuard accessGuard()
    {
        return new DefaultAccessGuard();
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    SkillVisibilityResolver skillVisibilityResolver(YamlSkillCatalog yamlSkillCatalog,
            CapabilityRegistry capabilityRegistry,
            AccessGuard accessGuard)
    {
        return new DefaultSkillVisibilityResolver(yamlSkillCatalog, capabilityRegistry, accessGuard);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    VirtualFileSystem virtualFileSystem()
    {
        return new SessionLocalVirtualFileSystem(Paths.get(System.getProperty("java.io.tmpdir"), "bifrost-vfs"));
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    RefResolver refResolver(VirtualFileSystem virtualFileSystem)
    {
        return new DefaultRefResolver(virtualFileSystem);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    MissionInputMaterializer missionInputMaterializer(RefResolver refResolver,
            SkillInputContractResolver skillInputContractResolver,
            BifrostProperties properties)
    {
        return new DefaultMissionInputMaterializer(
                refResolver,
                skillInputContractResolver,
                properties.getSession().getAttachments().getMaxSize());
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    MissionUserMessageSender missionUserMessageSender()
    {
        return new SpringAiMissionUserMessageSender();
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    CapabilityExecutionRouter capabilityExecutionRouter(RefResolver refResolver,
            org.springframework.beans.factory.ObjectProvider<ExecutionCoordinator> executionCoordinatorProvider,
            ExecutionStateService executionStateService,
            AccessGuard accessGuard,
            SkillInputValidator skillInputValidator)
    {
        return new CapabilityExecutionRouter(
                refResolver,
                executionCoordinatorProvider,
                executionStateService,
                accessGuard,
                skillInputValidator);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    SkillTemplate skillTemplate(CapabilityRegistry capabilityRegistry,
            CapabilityExecutionRouter capabilityExecutionRouter,
            BifrostSessionRunner bifrostSessionRunner,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            SkillInputValidator skillInputValidator)
    {
        return new DefaultSkillTemplate(
                capabilityRegistry,
                capabilityExecutionRouter,
                bifrostSessionRunner,
                objectMapperProvider.getIfAvailable(ObjectMapper::new),
                skillInputValidator);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    PlanTaskLinker planTaskLinker()
    {
        return new DefaultPlanTaskLinker();
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    ModelUsageExtractor modelUsageExtractor()
    {
        return new ModelUsageExtractor();
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    UsageMetricsRecorder usageMetricsRecorder(ObjectProvider<MeterRegistry> meterRegistryProvider)
    {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        return meterRegistry == null
                ? new NoOpUsageMetricsRecorder()
                : new MicrometerUsageMetricsRecorder(meterRegistry);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    SessionUsageService sessionUsageService(BifrostProperties properties,
            UsageMetricsRecorder usageMetricsRecorder)
    {
        return new DefaultSessionUsageService(properties.getSession().getQuotas(), usageMetricsRecorder);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    ExecutionStateService executionStateService(SessionUsageService sessionUsageService)
    {
        return new DefaultExecutionStateService(Clock.systemUTC(), sessionUsageService);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    PlanningService planningService(PlanTaskLinker planTaskLinker,
            ExecutionStateService executionStateService,
            SessionUsageService sessionUsageService,
            ModelUsageExtractor modelUsageExtractor)
    {
        return new DefaultPlanningService(planTaskLinker, executionStateService, sessionUsageService, modelUsageExtractor);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    ToolSurfaceService toolSurfaceService(SkillVisibilityResolver skillVisibilityResolver)
    {
        return new DefaultToolSurfaceService(skillVisibilityResolver);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    ToolCallbackFactory toolCallbackFactory(CapabilityExecutionRouter capabilityExecutionRouter,
            PlanningService planningService,
            ExecutionStateService executionStateService,
            SessionUsageService sessionUsageService,
            UsageMetricsRecorder usageMetricsRecorder)
    {
        return new DefaultToolCallbackFactory(
                capabilityExecutionRouter,
                planningService,
                executionStateService,
                sessionUsageService,
                usageMetricsRecorder);
    }

    @Bean(name = "bifrostMissionExecutor", destroyMethod = "close")
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    ExecutorService bifrostMissionExecutor()
    {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    MissionExecutionEngine missionExecutionEngine(PlanningService planningService,
            ExecutionStateService executionStateService,
            BifrostProperties properties,
            SessionUsageService sessionUsageService,
            ModelUsageExtractor modelUsageExtractor,
            MissionInputMaterializer missionInputMaterializer,
            MissionUserMessageSender missionUserMessageSender,
            @Qualifier("bifrostMissionExecutor") ExecutorService bifrostMissionExecutor)
    {
        return new DefaultMissionExecutionEngine(
                planningService,
                executionStateService,
                properties.getSession().getMissionTimeout(),
                bifrostMissionExecutor,
                sessionUsageService,
                modelUsageExtractor,
                missionInputMaterializer,
                missionUserMessageSender);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    NamedAiConnectionRegistry namedAiConnectionRegistry(BifrostProperties properties,
            ResourceLoader resourceLoader)
    {
        return new NamedAiConnectionRegistry(properties.getConnections(), List.of(
                new OpenAiConnectionChatModelFactory(),
                new AnthropicConnectionChatModelFactory(),
                new GeminiConnectionChatModelFactory(resourceLoader),
                new OllamaConnectionChatModelFactory()));
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    SkillChatModelResolver skillChatModelResolver(NamedAiConnectionRegistry registry)
    {
        return new DefaultSkillChatModelResolver(registry.asMap());
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    SkillChatOptionsAdapter openAiSkillChatOptionsAdapter()
    {
        return SpringAiSkillChatClientFactory.defaultAdapters().get(0);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    SkillChatOptionsAdapter anthropicSkillChatOptionsAdapter()
    {
        return SpringAiSkillChatClientFactory.defaultAdapters().get(1);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    SkillChatOptionsAdapter geminiSkillChatOptionsAdapter()
    {
        return SpringAiSkillChatClientFactory.defaultAdapters().get(2);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    SkillChatOptionsAdapter ollamaSkillChatOptionsAdapter()
    {
        return SpringAiSkillChatClientFactory.defaultAdapters().get(3);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    SkillAdvisorResolver skillAdvisorResolver(ExecutionStateService executionStateService)
    {
        return new DefaultSkillAdvisorResolver(executionStateService);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    SkillChatClientFactory skillChatClientFactory(SkillChatModelResolver chatModelResolver,
            List<SkillChatOptionsAdapter> adapters,
            SkillAdvisorResolver skillAdvisorResolver)
    {
        return new SpringAiSkillChatClientFactory(chatModelResolver, adapters, skillAdvisorResolver);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    com.lokiscale.bifrost.internal.runtime.step.StepLoopMissionExecutionEngine stepLoopMissionExecutionEngine(
            PlanningService planningService,
            ExecutionStateService executionStateService,
            CapabilityRegistry capabilityRegistry,
            YamlSkillCatalog yamlSkillCatalog,
            BifrostProperties properties,
            SessionUsageService sessionUsageService,
            ModelUsageExtractor modelUsageExtractor,
            MissionInputMaterializer missionInputMaterializer,
            MissionUserMessageSender missionUserMessageSender,
            @Qualifier("bifrostMissionExecutor") ExecutorService bifrostMissionExecutor)
    {
        return new com.lokiscale.bifrost.internal.runtime.step.StepLoopMissionExecutionEngine(
                planningService,
                executionStateService,
                capabilityRegistry,
                yamlSkillCatalog,
                properties.getSession().getMissionTimeout(),
                bifrostMissionExecutor,
                sessionUsageService,
                modelUsageExtractor,
                missionInputMaterializer,
                missionUserMessageSender);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    ExecutionCoordinator executionCoordinator(YamlSkillCatalog yamlSkillCatalog,
            CapabilityRegistry capabilityRegistry,
            SkillChatClientFactory skillChatClientFactory,
            ToolSurfaceService toolSurfaceService,
            ToolCallbackFactory toolCallbackFactory,
            MissionExecutionEngine missionExecutionEngine,
            com.lokiscale.bifrost.internal.runtime.step.StepLoopMissionExecutionEngine stepLoopMissionExecutionEngine,
            ExecutionStateService executionStateService,
            AccessGuard accessGuard)
    {
        return new ExecutionCoordinator(
                yamlSkillCatalog,
                capabilityRegistry,
                skillChatClientFactory,
                toolSurfaceService,
                toolCallbackFactory,
                missionExecutionEngine,
                stepLoopMissionExecutionEngine,
                executionStateService,
                accessGuard);
    }

}
