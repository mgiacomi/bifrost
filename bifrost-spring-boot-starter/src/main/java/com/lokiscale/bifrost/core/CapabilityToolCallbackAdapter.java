package com.lokiscale.bifrost.core;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class CapabilityToolCallbackAdapter {

    private final CapabilityExecutionRouter capabilityExecutionRouter;
    private final PlanTaskLinker planTaskLinker;
    private final Clock clock;

    public CapabilityToolCallbackAdapter(CapabilityExecutionRouter capabilityExecutionRouter,
                                         PlanTaskLinker planTaskLinker,
                                         Clock clock) {
        this.capabilityExecutionRouter = Objects.requireNonNull(
                capabilityExecutionRouter,
                "capabilityExecutionRouter must not be null");
        this.planTaskLinker = Objects.requireNonNull(planTaskLinker, "planTaskLinker must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public List<ToolCallback> toToolCallbacks(List<CapabilityMetadata> capabilities,
                                              BifrostSession session,
                                              @Nullable Authentication authentication) {
        Objects.requireNonNull(capabilities, "capabilities must not be null");
        Objects.requireNonNull(session, "session must not be null");
        return capabilities.stream().map(capability -> toToolCallback(capability, session, authentication)).toList();
    }

    private ToolCallback toToolCallback(CapabilityMetadata capability,
                                        BifrostSession session,
                                        @Nullable Authentication authentication) {
        return FunctionToolCallback.<Map<String, Object>, Object>builder(
                        capability.tool().name(),
                        arguments -> invokeCapability(capability, arguments, session, authentication))
                .description(capability.tool().description())
                .inputType(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .inputSchema(capability.tool().inputSchema())
                .build();
    }

    private Object invokeCapability(CapabilityMetadata capability,
                                    Map<String, Object> arguments,
                                    BifrostSession session,
                                    @Nullable Authentication authentication) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        String linkedTaskId = linkTask(session, capability, safeArguments).orElse(null);
        Instant now = clock.instant();
        if (linkedTaskId == null) {
            session.logUnplannedToolExecution(now, TaskExecutionEvent.unlinked(
                    capability.name(),
                    Map.of("arguments", safeArguments),
                    "No unique ready task matched this tool call"));
        }
        else {
            session.logToolExecution(now, TaskExecutionEvent.linked(
                    capability.name(),
                    linkedTaskId,
                    Map.of("arguments", safeArguments),
                    null));
            markTaskStarted(session, linkedTaskId, capability.name());
        }
        try {
            Object result = capabilityExecutionRouter.execute(capability, safeArguments, session, authentication);
            maybeMarkTaskCompleted(session, linkedTaskId, capability.name());
            session.logToolResult(clock.instant(), linkedTaskId == null
                    ? TaskExecutionEvent.unlinked(capability.name(), Map.of("result", result), null)
                    : TaskExecutionEvent.linked(capability.name(), linkedTaskId, Map.of("result", result), null));
            return result;
        }
        catch (RuntimeException ex) {
            markTaskBlocked(session, linkedTaskId, capability.name(), ex);
            session.logError(clock.instant(), Map.of(
                    "tool", capability.name(),
                    "linkedTaskId", linkedTaskId,
                    "message", ex.getMessage(),
                    "exceptionType", ex.getClass().getSimpleName()));
            throw ex;
        }
    }

    private Optional<String> linkTask(BifrostSession session,
                                      CapabilityMetadata capability,
                                      Map<String, Object> arguments) {
        return session.getExecutionPlan()
                .flatMap(plan -> planTaskLinker.linkTask(plan, capability, arguments));
    }

    private void markTaskStarted(BifrostSession session, String taskId, String capabilityName) {
        session.updateExecutionPlan(plan -> replacePlanTask(plan, taskId, task -> task.bindInProgress("Starting tool " + capabilityName))
                .withActiveTask(taskId));
        session.getExecutionPlan().ifPresent(plan -> session.logPlanUpdated(clock.instant(), plan));
    }

    private void maybeMarkTaskCompleted(BifrostSession session, String taskId, String capabilityName) {
        if (taskId == null) {
            return;
        }
        boolean completed = session.updateExecutionPlan(plan -> plan.findTask(taskId)
                .map(task -> replacePlanTask(plan, taskId, current -> current.complete("Completed tool " + capabilityName)).clearActiveTask())
                .orElse(plan))
                .flatMap(plan -> plan.findTask(taskId))
                .map(task -> task.status() == PlanTaskStatus.COMPLETED)
                .orElse(false);
        if (completed) {
            session.getExecutionPlan().ifPresent(plan -> session.logPlanUpdated(clock.instant(), plan));
        }
    }

    private void markTaskBlocked(BifrostSession session, String taskId, String capabilityName, RuntimeException ex) {
        if (taskId == null) {
            return;
        }
        session.updateExecutionPlan(plan -> replacePlanTask(plan, taskId,
                current -> current.block("Tool " + capabilityName + " failed: " + ex.getClass().getSimpleName()))
                .withStatus(PlanStatus.STALE)
                .clearActiveTask());
        session.getExecutionPlan().ifPresent(plan -> session.logPlanUpdated(clock.instant(), plan));
    }

    private ExecutionPlan replacePlanTask(ExecutionPlan plan,
                                          String taskId,
                                          java.util.function.Function<PlanTask, PlanTask> updater) {
        return plan.updateTask(taskId, updater);
    }
}
