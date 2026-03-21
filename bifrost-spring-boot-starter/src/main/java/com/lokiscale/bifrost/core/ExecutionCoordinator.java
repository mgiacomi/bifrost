package com.lokiscale.bifrost.core;

import com.lokiscale.bifrost.chat.SkillChatClientFactory;
import com.lokiscale.bifrost.runtime.MissionExecutionEngine;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import com.lokiscale.bifrost.runtime.tool.ToolCallbackFactory;
import com.lokiscale.bifrost.runtime.tool.ToolSurfaceService;
import com.lokiscale.bifrost.skill.YamlSkillCatalog;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ExecutionCoordinator {

    private final YamlSkillCatalog yamlSkillCatalog;
    private final CapabilityRegistry capabilityRegistry;
    private final SkillChatClientFactory skillChatClientFactory;
    private final ToolSurfaceService toolSurfaceService;
    private final ToolCallbackFactory toolCallbackFactory;
    private final MissionExecutionEngine missionExecutionEngine;
    private final ExecutionStateService executionStateService;
    private final boolean planningModeEnabled;

    public ExecutionCoordinator(YamlSkillCatalog yamlSkillCatalog,
                                CapabilityRegistry capabilityRegistry,
                                SkillChatClientFactory skillChatClientFactory,
                                ToolSurfaceService toolSurfaceService,
                                ToolCallbackFactory toolCallbackFactory,
                                MissionExecutionEngine missionExecutionEngine,
                                ExecutionStateService executionStateService,
                                boolean planningModeEnabled) {
        this.yamlSkillCatalog = Objects.requireNonNull(yamlSkillCatalog, "yamlSkillCatalog must not be null");
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry must not be null");
        this.skillChatClientFactory = Objects.requireNonNull(skillChatClientFactory, "skillChatClientFactory must not be null");
        this.toolSurfaceService = Objects.requireNonNull(toolSurfaceService, "toolSurfaceService must not be null");
        this.toolCallbackFactory = Objects.requireNonNull(toolCallbackFactory, "toolCallbackFactory must not be null");
        this.missionExecutionEngine = Objects.requireNonNull(missionExecutionEngine, "missionExecutionEngine must not be null");
        this.executionStateService = Objects.requireNonNull(executionStateService, "executionStateService must not be null");
        this.planningModeEnabled = planningModeEnabled;
    }

    public String execute(String skillName, String objective, BifrostSession session, @Nullable Authentication authentication) {
        Objects.requireNonNull(session, "session must not be null");
        requireNonBlank(objective, "objective");
        YamlSkillDefinition definition = requireYamlSkill(skillName);
        CapabilityMetadata rootCapability = requireCapability(skillName);
        ensureAuthorized(rootCapability, authentication);
        executionStateService.clearPlan(session);
        ExecutionFrame frame = executionStateService.openMissionFrame(session, rootCapability.name(), Map.of("objective", objective));
        try {
            ChatClient chatClient = skillChatClientFactory.create(definition.executionConfiguration());
            List<ToolCallback> visibleTools = toolCallbackFactory.createToolCallbacks(
                    session,
                    toolSurfaceService.visibleToolsFor(skillName, authentication),
                    authentication);
            return missionExecutionEngine.executeMission(
                    session,
                    skillName,
                    objective,
                    chatClient,
                    visibleTools,
                    definition.planningModeEnabled(planningModeEnabled),
                    authentication);
        }
        finally {
            executionStateService.closeMissionFrame(session, frame);
        }
    }

    private YamlSkillDefinition requireYamlSkill(String skillName) {
        YamlSkillDefinition definition = yamlSkillCatalog.getSkill(skillName);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown YAML skill '" + skillName + "'");
        }
        return definition;
    }

    private CapabilityMetadata requireCapability(String skillName) {
        CapabilityMetadata capability = capabilityRegistry.getCapability(skillName);
        if (capability == null) {
            throw new IllegalArgumentException("Unknown capability '" + skillName + "'");
        }
        return capability;
    }

    private void ensureAuthorized(CapabilityMetadata capability, @Nullable Authentication authentication) {
        if (capability.rbacRoles().isEmpty()) {
            return;
        }
        java.util.Set<String> authorities = authentication == null
                ? java.util.Set.of()
                : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());
        boolean authorized = capability.rbacRoles().stream().anyMatch(authorities::contains);
        if (!authorized) {
            throw new AccessDeniedException("Access denied for capability '" + capability.name() + "'");
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
