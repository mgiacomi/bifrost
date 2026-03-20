package com.lokiscale.bifrost.core;

import com.lokiscale.bifrost.chat.SkillChatClientFactory;
import com.lokiscale.bifrost.skill.SkillVisibilityResolver;
import com.lokiscale.bifrost.skill.YamlSkillCatalog;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class ExecutionCoordinator {

    private final YamlSkillCatalog yamlSkillCatalog;
    private final CapabilityRegistry capabilityRegistry;
    private final SkillVisibilityResolver skillVisibilityResolver;
    private final SkillChatClientFactory skillChatClientFactory;
    private final CapabilityToolCallbackAdapter toolCallbackAdapter;
    private final Clock clock;
    private final boolean planningModeEnabled;

    public ExecutionCoordinator(YamlSkillCatalog yamlSkillCatalog,
                                CapabilityRegistry capabilityRegistry,
                                SkillVisibilityResolver skillVisibilityResolver,
                                SkillChatClientFactory skillChatClientFactory,
                                CapabilityToolCallbackAdapter toolCallbackAdapter,
                                Clock clock,
                                boolean planningModeEnabled) {
        this.yamlSkillCatalog = Objects.requireNonNull(yamlSkillCatalog, "yamlSkillCatalog must not be null");
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry must not be null");
        this.skillVisibilityResolver = Objects.requireNonNull(skillVisibilityResolver, "skillVisibilityResolver must not be null");
        this.skillChatClientFactory = Objects.requireNonNull(skillChatClientFactory, "skillChatClientFactory must not be null");
        this.toolCallbackAdapter = Objects.requireNonNull(toolCallbackAdapter, "toolCallbackAdapter must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.planningModeEnabled = planningModeEnabled;
    }

    public String execute(String skillName, String objective, BifrostSession session, @Nullable Authentication authentication) {
        Objects.requireNonNull(session, "session must not be null");
        requireNonBlank(objective, "objective");
        YamlSkillDefinition definition = requireYamlSkill(skillName);
        CapabilityMetadata rootCapability = requireCapability(skillName);
        ensureAuthorized(rootCapability, authentication);
        session.clearExecutionPlan();
        Instant now = clock.instant();
        ExecutionFrame frame = new ExecutionFrame(
                UUID.randomUUID().toString(),
                currentFrameId(session),
                OperationType.SKILL,
                rootCapability.name(),
                Map.of("objective", objective),
                now);

        session.pushFrame(frame);
        try {
            ChatClient chatClient = skillChatClientFactory.create(definition.executionConfiguration());
            List<ToolCallback> visibleTools = toolCallbackAdapter.toToolCallbacks(
                    skillVisibilityResolver.visibleSkillsFor(skillName, authentication),
                    session,
                    authentication);

            if (definition.planningModeEnabled(planningModeEnabled)) {
                ExecutionPlan plan = chatClient.prompt()
                        .system("Create an ordered flight plan for this mission before execution.")
                        .user(objective)
                        .call()
                        .entity(ExecutionPlan.class);
                session.replaceExecutionPlan(plan);
                session.logPlanCreated(clock.instant(), plan);
            }

            String executionPrompt = session.getExecutionPlan()
                    .map(this::buildExecutionPrompt)
                    .orElse("Execute the mission using only the visible YAML tools when needed.");

            return chatClient.prompt()
                    .system(executionPrompt)
                    .user(objective)
                    .toolCallbacks(visibleTools)
                    .call()
                    .content();
        }
        finally {
            session.popFrame();
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

    private String currentFrameId(BifrostSession session) {
        List<ExecutionFrame> frames = session.getFramesSnapshot();
        return frames.isEmpty() ? null : frames.getFirst().frameId();
    }

    private String buildExecutionPrompt(ExecutionPlan plan) {
        String readyTaskLines = plan.readyTasks().stream()
                .sorted(Comparator.comparingInt(plan.tasks()::indexOf))
                .map(task -> "- [" + task.status() + "] " + task.taskId() + ": " + task.title()
                        + (task.note() == null ? "" : " (" + task.note() + ")"))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- No ready tasks");
        String blockedTaskLines = plan.tasks().stream()
                .filter(task -> task.status() == PlanTaskStatus.BLOCKED)
                .map(task -> "- " + task.taskId() + ": " + task.title()
                        + (task.note() == null ? "" : " (" + task.note() + ")"))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- No blocked tasks");
        String activeTask = plan.activeTask()
                .map(task -> task.taskId() + ": " + task.title())
                .orElse("none");
        return """
                Execute the mission using only the visible YAML tools when needed.
                Keep the stored flight plan as the execution anchor and advance work consistently with it.
                Active plan %s for capability %s is %s.
                Active task: %s
                Ready tasks:
                %s
                Blocked tasks:
                %s
                """.formatted(plan.planId(), plan.capabilityName(), plan.status(), activeTask, readyTaskLines, blockedTaskLines);
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
