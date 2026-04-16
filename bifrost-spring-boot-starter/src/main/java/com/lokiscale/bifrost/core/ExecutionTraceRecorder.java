package com.lokiscale.bifrost.core;

import java.util.Map;

import com.lokiscale.bifrost.linter.LinterOutcome;
import com.lokiscale.bifrost.outputschema.OutputSchemaOutcome;

public interface ExecutionTraceRecorder
{
    void recordFrameOpened(BifrostSession session, ExecutionFrame frame);

    void recordFrameClosed(BifrostSession session, ExecutionFrame frame, Map<String, Object> metadata);

    void recordModelRequestPrepared(BifrostSession session, ExecutionFrame frame, ModelTraceContext context, Object payload);

    void recordModelRequestSent(BifrostSession session, ExecutionFrame frame, ModelTraceContext context, Object payload);

    void recordModelResponseReceived(BifrostSession session, ExecutionFrame frame, ModelTraceContext context, Object payload);

    void recordPlanCreated(BifrostSession session, ExecutionPlan plan);

    void recordPlanUpdated(BifrostSession session, ExecutionPlan plan);

    void recordToolRequested(BifrostSession session, ExecutionFrame frame, ToolTraceContext context, Object payload);

    void recordToolStarted(BifrostSession session, ExecutionFrame frame, ToolTraceContext context, Object payload);

    void recordToolCompleted(BifrostSession session, ExecutionFrame frame, ToolTraceContext context, Object payload);

    void recordToolFailed(BifrostSession session, ExecutionFrame frame, ToolTraceContext context, Object payload);

    void recordAdvisorRequestMutation(BifrostSession session, AdvisorTraceContext context, Object payload);

    void recordAdvisorResponseMutation(BifrostSession session, AdvisorTraceContext context, Object payload);

    void recordLinterOutcome(BifrostSession session, LinterOutcome outcome);

    void recordOutputSchemaOutcome(BifrostSession session, OutputSchemaOutcome outcome);

    void recordError(BifrostSession session, Object payload);

    void finalizeTrace(BifrostSession session, TraceCompletion completion);
}
