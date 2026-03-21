package com.lokiscale.bifrost.runtime;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.BifrostStackOverflowException;
import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.PlanTaskStatus;
import com.lokiscale.bifrost.core.SessionContextRunner;
import com.lokiscale.bifrost.runtime.planning.PlanningService;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Callable;

public class DefaultMissionExecutionEngine implements MissionExecutionEngine {

    private final PlanningService planningService;
    private final ExecutionStateService executionStateService;
    private final Duration missionTimeout;
    private final ExecutorService missionExecutor;

    public DefaultMissionExecutionEngine(PlanningService planningService,
                                         ExecutionStateService executionStateService,
                                         Duration missionTimeout,
                                         ExecutorService missionExecutor) {
        this.planningService = Objects.requireNonNull(planningService, "planningService must not be null");
        this.executionStateService = Objects.requireNonNull(executionStateService, "executionStateService must not be null");
        this.missionTimeout = Objects.requireNonNull(missionTimeout, "missionTimeout must not be null");
        this.missionExecutor = Objects.requireNonNull(missionExecutor, "missionExecutor must not be null");
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
        Callable<String> missionCall = () -> SessionContextRunner.callWithSession(session, () -> {
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
        });
        Future<String> mission = missionExecutor.submit(missionCall);
        try {
            return mission.get(missionTimeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException ex) {
            mission.cancel(true);
            throw new BifrostMissionTimeoutException(session.getSessionId(), skillName, missionTimeout, ex);
        }
        catch (InterruptedException ex) {
            mission.cancel(true);
            Thread.currentThread().interrupt();
            throw new BifrostMissionTimeoutException(session.getSessionId(), skillName, missionTimeout, ex);
        }
        catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw unwrapMissionFailure(runtimeException);
            }
            throw new IllegalStateException("Mission execution failed for skill '" + skillName + "'", cause);
        }
    }

    private RuntimeException unwrapMissionFailure(RuntimeException runtimeException) {
        if (runtimeException instanceof ToolExecutionException toolExecutionException
                && toolExecutionException.getCause() instanceof RuntimeException nestedRuntimeException
                && (nestedRuntimeException instanceof BifrostStackOverflowException
                || nestedRuntimeException instanceof BifrostMissionTimeoutException)) {
            return nestedRuntimeException;
        }
        return runtimeException;
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
