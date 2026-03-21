package com.lokiscale.bifrost.runtime.planning;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.PlanStatus;
import com.lokiscale.bifrost.core.PlanTask;
import com.lokiscale.bifrost.core.PlanTaskLinker;
import com.lokiscale.bifrost.core.PlanTaskStatus;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class DefaultPlanningService implements PlanningService {

    private final PlanTaskLinker planTaskLinker;
    private final ExecutionStateService executionStateService;

    public DefaultPlanningService(PlanTaskLinker planTaskLinker, ExecutionStateService executionStateService) {
        this.planTaskLinker = Objects.requireNonNull(planTaskLinker, "planTaskLinker must not be null");
        this.executionStateService = Objects.requireNonNull(executionStateService, "executionStateService must not be null");
    }

    @Override
    public Optional<ExecutionPlan> initializePlan(BifrostSession session,
                                                  String objective,
                                                  String capabilityName,
                                                  ChatClient chatClient,
                                                  List<ToolCallback> visibleTools) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(objective, "objective must not be null");
        Objects.requireNonNull(capabilityName, "capabilityName must not be null");
        Objects.requireNonNull(chatClient, "chatClient must not be null");
        ExecutionPlan plan = chatClient.prompt()
                .system("Create an ordered flight plan for this mission before execution.")
                .user(objective)
                .call()
                .entity(ExecutionPlan.class);
        executionStateService.storePlan(session, plan);
        executionStateService.logPlanCreated(session, plan);
        return Optional.of(plan);
    }

    @Override
    public Optional<ExecutionPlan> markToolStarted(BifrostSession session, CapabilityMetadata capability, Map<String, Object> arguments) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(capability, "capability must not be null");
        Map<String, Object> safeArguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        return executionStateService.currentPlan(session)
                .flatMap(plan -> planTaskLinker.linkTask(plan, capability, safeArguments)
                        .map(taskId -> {
                            ExecutionPlan updated = replacePlanTask(
                                    plan,
                                    taskId,
                                    task -> task.bindInProgress("Starting tool " + capability.name()))
                                    .withActiveTask(taskId);
                            executionStateService.storePlan(session, updated);
                            executionStateService.logPlanUpdated(session, updated);
                            return updated;
                        }));
    }

    @Override
    public Optional<ExecutionPlan> markToolCompleted(BifrostSession session,
                                                     String taskId,
                                                     String capabilityName,
                                                     @Nullable Object result) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(capabilityName, "capabilityName must not be null");
        return updatePlan(session, plan -> plan.findTask(taskId)
                .map(task -> replacePlanTask(plan, taskId, current -> current.complete("Completed tool " + capabilityName))
                        .clearActiveTask())
                .orElse(plan))
                .filter(plan -> plan.findTask(taskId)
                        .map(task -> task.status() == PlanTaskStatus.COMPLETED)
                        .orElse(false));
    }

    @Override
    public Optional<ExecutionPlan> markToolFailed(BifrostSession session,
                                                  String taskId,
                                                  String capabilityName,
                                                  RuntimeException ex) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(capabilityName, "capabilityName must not be null");
        Objects.requireNonNull(ex, "ex must not be null");
        return updatePlan(session, plan -> replacePlanTask(plan, taskId,
                current -> current.block("Tool " + capabilityName + " failed: " + ex.getClass().getSimpleName()))
                .withStatus(PlanStatus.STALE)
                .clearActiveTask());
    }

    private Optional<ExecutionPlan> updatePlan(BifrostSession session,
                                               java.util.function.Function<ExecutionPlan, ExecutionPlan> updater) {
        return executionStateService.currentPlan(session).map(plan -> {
            ExecutionPlan updated = Objects.requireNonNull(updater.apply(plan), "updated plan must not be null");
            executionStateService.storePlan(session, updated);
            executionStateService.logPlanUpdated(session, updated);
            return updated;
        });
    }

    private ExecutionPlan replacePlanTask(ExecutionPlan plan,
                                          String taskId,
                                          java.util.function.Function<PlanTask, PlanTask> updater) {
        return plan.updateTask(taskId, updater);
    }
}
