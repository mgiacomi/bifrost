package com.lokiscale.bifrost.runtime;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.PlanTaskStatus;
import com.lokiscale.bifrost.runtime.planning.PlanningService;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class DefaultMissionExecutionEngine implements MissionExecutionEngine {

    private final PlanningService planningService;
    private final ExecutionStateService executionStateService;

    public DefaultMissionExecutionEngine(PlanningService planningService, ExecutionStateService executionStateService) {
        this.planningService = Objects.requireNonNull(planningService, "planningService must not be null");
        this.executionStateService = Objects.requireNonNull(executionStateService, "executionStateService must not be null");
    }

    @Override
    public String executeMission(BifrostSession session,
                                 String skillName,
                                 String objective,
                                 ChatClient chatClient,
                                 List<ToolCallback> visibleTools,
                                 boolean planningEnabled,
                                 @Nullable Authentication authentication) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(skillName, "skillName must not be null");
        Objects.requireNonNull(objective, "objective must not be null");
        Objects.requireNonNull(chatClient, "chatClient must not be null");
        Objects.requireNonNull(visibleTools, "visibleTools must not be null");

        if (planningEnabled) {
            planningService.initializePlan(session, objective, skillName, chatClient, visibleTools);
        }

        String executionPrompt = executionStateService.currentPlan(session)
                .map(this::buildExecutionPrompt)
                .orElse("Execute the mission using only the visible YAML tools when needed.");

        return chatClient.prompt()
                .system(executionPrompt)
                .user(objective)
                .toolCallbacks(visibleTools)
                .call()
                .content();
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
}
