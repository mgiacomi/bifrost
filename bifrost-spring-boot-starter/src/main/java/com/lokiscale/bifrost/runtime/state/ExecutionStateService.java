package com.lokiscale.bifrost.runtime.state;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.AdvisorTraceContext;
import com.lokiscale.bifrost.core.ExecutionFrame;
import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.ModelTraceCallback;
import com.lokiscale.bifrost.core.ModelTraceContext;
import com.lokiscale.bifrost.core.ModelTraceResult;
import com.lokiscale.bifrost.core.TaskExecutionEvent;
import com.lokiscale.bifrost.core.TraceFrameType;
import com.lokiscale.bifrost.core.TraceCompletion;
import com.lokiscale.bifrost.core.ToolTraceContext;
import com.lokiscale.bifrost.linter.LinterOutcome;
import com.lokiscale.bifrost.outputschema.OutputSchemaOutcome;

import java.util.Map;
import java.util.Optional;

public interface ExecutionStateService {

    ExecutionFrame openMissionFrame(BifrostSession session, String route, Map<String, Object> parameters);

    ExecutionFrame openFrame(BifrostSession session, TraceFrameType traceFrameType, String route, Map<String, Object> parameters);

    void closeMissionFrame(BifrostSession session, ExecutionFrame frame);

    void closeFrame(BifrostSession session, ExecutionFrame frame, Map<String, Object> metadata);

    void storePlan(BifrostSession session, ExecutionPlan plan);

    void clearPlan(BifrostSession session);

    Optional<ExecutionPlan> currentPlan(BifrostSession session);

    PlanSnapshot snapshotPlan(BifrostSession session);

    void restorePlan(BifrostSession session, PlanSnapshot snapshot);

    void logPlanCreated(BifrostSession session, ExecutionPlan plan);

    void logPlanUpdated(BifrostSession session, ExecutionPlan plan);

    void recordModelRequestPrepared(BifrostSession session, ExecutionFrame frame, ModelTraceContext context, Object payload);

    void recordModelRequestSent(BifrostSession session, ExecutionFrame frame, ModelTraceContext context, Object payload);

    void recordModelResponseReceived(BifrostSession session, ExecutionFrame frame, ModelTraceContext context, Object payload);

    <T> T traceModelCall(BifrostSession session,
                         ExecutionFrame frame,
                         ModelTraceContext context,
                         Object preparedPayload,
                         ModelTraceCallback<T> callback);

    void logToolCall(BifrostSession session, TaskExecutionEvent event);

    void logUnplannedToolCall(BifrostSession session, TaskExecutionEvent event);

    void logToolResult(BifrostSession session, TaskExecutionEvent event);

    void logToolFailure(BifrostSession session, ToolTraceContext context, Object payload);

    void recordLinterOutcome(BifrostSession session, LinterOutcome outcome);

    void recordOutputSchemaOutcome(BifrostSession session, OutputSchemaOutcome outcome);

    void recordAdvisorRequestMutation(BifrostSession session, AdvisorTraceContext context, Object payload);

    void recordAdvisorResponseMutation(BifrostSession session, AdvisorTraceContext context, Object payload);

    void logError(BifrostSession session, Map<String, Object> payload);

    void finalizeTrace(BifrostSession session, Map<String, Object> metadata);

    default void finalizeTrace(BifrostSession session, TraceCompletion completion) {
        finalizeTrace(session, completion == null ? Map.of() : completion.metadata());
    }
}
