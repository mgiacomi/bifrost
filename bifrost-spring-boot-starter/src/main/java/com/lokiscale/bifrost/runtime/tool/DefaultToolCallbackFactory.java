package com.lokiscale.bifrost.runtime.tool;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.CapabilityExecutionRouter;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.TaskExecutionEvent;
import com.lokiscale.bifrost.runtime.planning.PlanningService;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import com.lokiscale.bifrost.runtime.usage.NoOpSessionUsageService;
import com.lokiscale.bifrost.runtime.usage.NoOpUsageMetricsRecorder;
import com.lokiscale.bifrost.runtime.usage.SessionUsageService;
import com.lokiscale.bifrost.runtime.usage.UsageMetricsRecorder;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class DefaultToolCallbackFactory implements ToolCallbackFactory {

    private final CapabilityExecutionRouter capabilityExecutionRouter;
    private final PlanningService planningService;
    private final ExecutionStateService executionStateService;
    private final SessionUsageService sessionUsageService;
    private final UsageMetricsRecorder usageMetricsRecorder;

    public DefaultToolCallbackFactory(CapabilityExecutionRouter capabilityExecutionRouter,
                                      PlanningService planningService,
                                      ExecutionStateService executionStateService) {
        this(capabilityExecutionRouter, planningService, executionStateService, new NoOpSessionUsageService(), new NoOpUsageMetricsRecorder());
    }

    public DefaultToolCallbackFactory(CapabilityExecutionRouter capabilityExecutionRouter,
                                      PlanningService planningService,
                                      ExecutionStateService executionStateService,
                                      SessionUsageService sessionUsageService,
                                      UsageMetricsRecorder usageMetricsRecorder) {
        this.capabilityExecutionRouter = Objects.requireNonNull(
                capabilityExecutionRouter,
                "capabilityExecutionRouter must not be null");
        this.planningService = Objects.requireNonNull(planningService, "planningService must not be null");
        this.executionStateService = Objects.requireNonNull(executionStateService, "executionStateService must not be null");
        this.sessionUsageService = Objects.requireNonNull(sessionUsageService, "sessionUsageService must not be null");
        this.usageMetricsRecorder = Objects.requireNonNull(usageMetricsRecorder, "usageMetricsRecorder must not be null");
    }

    @Override
    public List<ToolCallback> createToolCallbacks(BifrostSession session,
                                                  List<CapabilityMetadata> capabilities,
                                                  @Nullable Authentication authentication) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(capabilities, "capabilities must not be null");
        return capabilities.stream()
                .map(capability -> toToolCallback(capability, session, authentication))
                .toList();
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
        String currentSkillName = currentSkillName(session);
        sessionUsageService.recordToolCall(session, currentSkillName, capability.name());
        var startedPlan = planningService.markToolStarted(session, capability, safeArguments);
        String linkedTaskId = startedPlan.flatMap(plan -> plan.activeTask().map(task -> task.taskId())).orElse(null);
        if (linkedTaskId == null) {
            executionStateService.logUnplannedToolCall(session, TaskExecutionEvent.unlinked(
                    capability.name(),
                    Map.of("arguments", safeArguments),
                    "No unique ready task matched this tool call"));
        } else {
            executionStateService.logToolCall(session, TaskExecutionEvent.linked(
                    capability.name(),
                    linkedTaskId,
                    Map.of("arguments", safeArguments),
                    null));
        }
        try {
            Object result = capabilityExecutionRouter.execute(capability, safeArguments, session, authentication);
            if (linkedTaskId != null) {
                planningService.markToolCompleted(session, linkedTaskId, capability.name(), result);
            }
            usageMetricsRecorder.recordToolInvocation(currentSkillName, capability.name(), "success");
            executionStateService.logToolResult(session, linkedTaskId == null
                    ? TaskExecutionEvent.unlinked(capability.name(), Map.of("result", result), null)
                    : TaskExecutionEvent.linked(capability.name(), linkedTaskId, Map.of("result", result), null));
            return result;
        } catch (RuntimeException ex) {
            if (linkedTaskId != null) {
                planningService.markToolFailed(session, linkedTaskId, capability.name(), ex);
            }
            usageMetricsRecorder.recordToolInvocation(currentSkillName, capability.name(), "failure");
            executionStateService.logError(session, Map.of(
                    "tool", capability.name(),
                    "linkedTaskId", linkedTaskId,
                    "message", ex.getMessage(),
                    "exceptionType", ex.getClass().getSimpleName()));
            throw ex;
        }
    }

    private String currentSkillName(BifrostSession session) {
        try {
            return session.peekFrame().route();
        } catch (IllegalStateException ignored) {
            return session.getExecutionPlan().map(plan -> plan.capabilityName()).orElse("unknown");
        }
    }
}
