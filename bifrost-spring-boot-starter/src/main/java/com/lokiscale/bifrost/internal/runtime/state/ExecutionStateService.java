package com.lokiscale.bifrost.internal.runtime.state;

import com.lokiscale.bifrost.internal.core.BifrostSession;
import com.lokiscale.bifrost.internal.core.AdvisorTraceContext;
import com.lokiscale.bifrost.internal.core.ExecutionFrame;
import com.lokiscale.bifrost.internal.core.ExecutionPlan;
import com.lokiscale.bifrost.internal.core.ModelTraceCallback;
import com.lokiscale.bifrost.internal.core.ModelTraceContext;
import com.lokiscale.bifrost.internal.core.TaskExecutionEvent;
import com.lokiscale.bifrost.internal.core.TraceFrameType;
import com.lokiscale.bifrost.internal.core.TraceCompletion;
import com.lokiscale.bifrost.internal.core.TraceRecordType;
import com.lokiscale.bifrost.internal.core.ToolTraceContext;
import com.lokiscale.bifrost.internal.linter.LinterOutcome;
import com.lokiscale.bifrost.internal.outputschema.OutputSchemaOutcome;
import org.springframework.lang.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface ExecutionStateService
{
    ExecutionFrame openMissionFrame(BifrostSession session, String route, Map<String, Object> parameters);

    ExecutionFrame openFrame(BifrostSession session, TraceFrameType traceFrameType, String route, Map<String, Object> parameters);

    void closeMissionFrame(BifrostSession session, ExecutionFrame frame);

    void closeFrame(BifrostSession session, ExecutionFrame frame, Map<String, Object> metadata);

    void storePlan(BifrostSession session, ExecutionPlan plan);

    void clearPlan(BifrostSession session);

    Optional<ExecutionPlan> currentPlan(BifrostSession session);

    PlanSnapshot snapshotPlan(BifrostSession session);

    void restorePlan(BifrostSession session, PlanSnapshot snapshot);

    SuccessfulSkillSnapshot snapshotSuccessfulSkills(BifrostSession session);

    void restoreSuccessfulSkills(BifrostSession session, SuccessfulSkillSnapshot snapshot);

    void logPlanCreated(BifrostSession session, ExecutionPlan plan);

    void logPlanUpdated(BifrostSession session, ExecutionPlan plan);

    void recordPlanningEvent(BifrostSession session,
            ExecutionFrame frame,
            TraceRecordType recordType,
            Map<String, Object> metadata,
            Object payload);

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

    void clearSuccessfulSkills(BifrostSession session);

    Set<String> currentSuccessfulSkills(BifrostSession session);

    void recordSuccessfulSkill(BifrostSession session,
            String capabilityName,
            @Nullable String linkedTaskId,
            boolean unplanned);

    void recordEvidenceValidation(BifrostSession session,
            boolean passed,
            Map<String, Object> metadata,
            Object payload);

    void recordLinterOutcome(BifrostSession session, LinterOutcome outcome);

    void recordOutputSchemaOutcome(BifrostSession session, OutputSchemaOutcome outcome);

    void recordAdvisorRequestMutation(BifrostSession session, AdvisorTraceContext context, Object payload);

    void recordAdvisorResponseMutation(BifrostSession session, AdvisorTraceContext context, Object payload);

    void logError(BifrostSession session, Map<String, Object> payload);

    void recordStepEvent(BifrostSession session, ExecutionFrame frame, TraceRecordType recordType,
            Map<String, Object> metadata, Object payload);

    void finalizeTrace(BifrostSession session, Map<String, Object> metadata);

    default void finalizeTrace(BifrostSession session, TraceCompletion completion)
    {
        finalizeTrace(session, completion == null ? Map.of() : completion.metadata());
    }
}
