package com.lokiscale.bifrost.core;

import com.lokiscale.bifrost.chat.SkillChatClientFactory;
import com.lokiscale.bifrost.runtime.MissionExecutionEngine;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import com.lokiscale.bifrost.runtime.tool.ToolCallbackFactory;
import com.lokiscale.bifrost.runtime.tool.ToolSurfaceService;
import com.lokiscale.bifrost.security.AccessGuard;
import com.lokiscale.bifrost.skill.YamlSkillCatalog;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ExecutionCoordinator
{
    private final YamlSkillCatalog yamlSkillCatalog;
    private final CapabilityRegistry capabilityRegistry;
    private final SkillChatClientFactory skillChatClientFactory;
    private final ToolSurfaceService toolSurfaceService;
    private final ToolCallbackFactory toolCallbackFactory;
    private final MissionExecutionEngine missionExecutionEngine;
    private final MissionExecutionEngine stepLoopMissionExecutionEngine;
    private final ExecutionStateService executionStateService;
    private final AccessGuard accessGuard;

    public ExecutionCoordinator(YamlSkillCatalog yamlSkillCatalog,
            CapabilityRegistry capabilityRegistry,
            SkillChatClientFactory skillChatClientFactory,
            ToolSurfaceService toolSurfaceService,
            ToolCallbackFactory toolCallbackFactory,
            MissionExecutionEngine missionExecutionEngine,
            MissionExecutionEngine stepLoopMissionExecutionEngine,
            ExecutionStateService executionStateService,
            AccessGuard accessGuard)
    {
        this.yamlSkillCatalog = Objects.requireNonNull(yamlSkillCatalog, "yamlSkillCatalog must not be null");
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry must not be null");
        this.skillChatClientFactory = Objects.requireNonNull(skillChatClientFactory, "skillChatClientFactory must not be null");
        this.toolSurfaceService = Objects.requireNonNull(toolSurfaceService, "toolSurfaceService must not be null");
        this.toolCallbackFactory = Objects.requireNonNull(toolCallbackFactory, "toolCallbackFactory must not be null");
        this.missionExecutionEngine = Objects.requireNonNull(missionExecutionEngine, "missionExecutionEngine must not be null");
        this.stepLoopMissionExecutionEngine = Objects.requireNonNull(stepLoopMissionExecutionEngine, "stepLoopMissionExecutionEngine must not be null");
        this.executionStateService = Objects.requireNonNull(executionStateService, "executionStateService must not be null");
        this.accessGuard = Objects.requireNonNull(accessGuard, "accessGuard must not be null");
    }

    public String execute(String skillName, String objective, BifrostSession session, @Nullable Authentication authentication)
    {
        return execute(skillName, objective, null, session, authentication);
    }

    public String execute(String skillName,
            String objective,
            @Nullable Map<String, Object> missionInput,
            BifrostSession session,
            @Nullable Authentication authentication)
    {
        Objects.requireNonNull(session, "session must not be null");
        requireNonBlank(objective, "objective");
        YamlSkillDefinition definition = requireYamlSkill(skillName);
        CapabilityMetadata rootCapability = requireCapability(skillName);
        boolean topLevelInvocation = session.getFramesSnapshot().isEmpty();

        if (authentication != null || session.getFramesSnapshot().isEmpty())
        {
            session.setAuthentication(authentication);
        }

        accessGuard.checkAccess(rootCapability, session, authentication);
        executionStateService.clearPlan(session);

        // Every YAML skill run gets a fresh evidence ledger; nested invocations rely on
        // CapabilityExecutionRouter to snapshot and restore the parent's evidence afterward.
        executionStateService.clearProducedEvidence(session);
        LinkedHashMap<String, Object> frameParameters = new LinkedHashMap<>();
        frameParameters.put("objective", objective);

        if (missionInput != null && !missionInput.isEmpty())
        {
            frameParameters.put("missionInput", missionInput);
        }

        ExecutionFrame frame = executionStateService.openMissionFrame(session, rootCapability.name(), Map.copyOf(frameParameters));
        Throwable failure = null;

        try
        {
            return BifrostSessionHolder.callWithSession(session, () ->
            {
                boolean stepExecutionEnabled = definition.planningModeExplicitlyEnabled();
                MissionExecutionEngine engine = stepExecutionEnabled ? stepLoopMissionExecutionEngine : missionExecutionEngine;
                ChatClient chatClient = stepExecutionEnabled
                        ? skillChatClientFactory.createForStepExecution(definition)
                        : skillChatClientFactory.create(definition);

                List<ToolCallback> visibleTools = toolCallbackFactory.createToolCallbacks(
                        session,
                        definition,
                        toolSurfaceService.visibleToolsFor(skillName, session, authentication),
                        authentication);

                return engine.executeMission(
                        session,
                        definition,
                        objective,
                        missionInput,
                        chatClient,
                        visibleTools,
                        definition.planningModeExplicitlyEnabled(),
                        authentication);
            });
        }
        catch (RuntimeException | Error ex)
        {
            failure = ex;
            executionStateService.logError(session, errorPayload(skillName, objective, ex));
            session.markTraceErrored();
            throw ex;
        }
        finally
        {
            RuntimeException cleanupFailure = null;
            try
            {
                executionStateService.closeFrame(session, frame, closeMetadata(failure));
            }
            catch (RuntimeException ex)
            {
                cleanupFailure = ex;
            }
            if (topLevelInvocation)
            {
                try
                {
                    executionStateService.finalizeTrace(session, Map.of(
                            "skillName", skillName,
                            "objective", objective,
                            "remainingFrames", session.getFramesSnapshot().size()));
                }
                catch (RuntimeException ex)
                {
                    if (cleanupFailure == null)
                    {
                        cleanupFailure = ex;
                    }
                    else
                    {
                        cleanupFailure.addSuppressed(ex);
                    }
                }
            }
            if (cleanupFailure != null)
            {
                if (failure != null)
                {
                    failure.addSuppressed(cleanupFailure);
                }
                else
                {
                    throw cleanupFailure;
                }
            }
        }
    }

    private Map<String, Object> closeMetadata(@Nullable Throwable failure)
    {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("status", failure == null ? "completed" : (Thread.currentThread().isInterrupted() ? "aborted" : "failed"));
        if (failure != null)
        {
            metadata.put("exceptionType", failure.getClass().getName());
            if (failure.getMessage() != null && !failure.getMessage().isBlank())
            {
                metadata.put("message", failure.getMessage());
            }
        }
        return metadata;
    }

    private Map<String, Object> errorPayload(String skillName, String objective, Throwable failure)
    {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("skillName", skillName);
        payload.put("objective", objective);
        payload.put("message", failure.getMessage() == null || failure.getMessage().isBlank()
                ? "Mission execution failed"
                : failure.getMessage());
        payload.put("exceptionType", failure.getClass().getName());
        return payload;
    }

    private YamlSkillDefinition requireYamlSkill(String skillName)
    {
        YamlSkillDefinition definition = yamlSkillCatalog.getSkill(skillName);
        if (definition == null)
        {
            throw new IllegalArgumentException("Unknown YAML skill '" + skillName + "'");
        }
        return definition;
    }

    private CapabilityMetadata requireCapability(String skillName)
    {
        CapabilityMetadata capability = capabilityRegistry.getCapability(skillName);
        if (capability == null)
        {
            throw new IllegalArgumentException("Unknown capability '" + skillName + "'");
        }
        return capability;
    }

    private static String requireNonBlank(String value, String fieldName)
    {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank())
        {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
