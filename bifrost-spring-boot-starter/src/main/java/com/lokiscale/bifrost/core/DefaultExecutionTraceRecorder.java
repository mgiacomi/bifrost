package com.lokiscale.bifrost.core;

import com.lokiscale.bifrost.linter.LinterOutcome;
import com.lokiscale.bifrost.linter.LinterOutcomeStatus;
import com.lokiscale.bifrost.outputschema.OutputSchemaOutcome;
import com.lokiscale.bifrost.outputschema.OutputSchemaOutcomeStatus;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class DefaultExecutionTraceRecorder implements ExecutionTraceRecorder
{
    private final Clock clock;

    public DefaultExecutionTraceRecorder(Clock clock)
    {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public void recordFrameOpened(BifrostSession session, ExecutionFrame frame)
    {
        recordAgainstFrame(session, frame, TraceRecordType.FRAME_OPENED, Map.of(
                "openedAt", frame.openedAt().toString(),
                "operationType", frame.operationType().name(),
                "frameType", frame.traceFrameType().name()), frame.parameters());
    }

    @Override
    public void recordFrameClosed(BifrostSession session, ExecutionFrame frame, Map<String, Object> metadata)
    {
        recordAgainstFrame(session, frame, TraceRecordType.FRAME_CLOSED, metadata, Map.of(
                "frameId", frame.frameId(),
                "route", frame.route(),
                "closedAt", clock.instant().toString()));
    }

    @Override
    public void recordModelRequestPrepared(BifrostSession session, ExecutionFrame frame, ModelTraceContext context, Object payload)
    {
        recordAgainstFrame(session, frame, TraceRecordType.MODEL_REQUEST_PREPARED, context.metadata(), payload);
    }

    @Override
    public void recordModelRequestSent(BifrostSession session, ExecutionFrame frame, ModelTraceContext context, Object payload)
    {
        recordAgainstFrame(session, frame, TraceRecordType.MODEL_REQUEST_SENT, context.metadata(), payload);
    }

    @Override
    public void recordModelResponseReceived(BifrostSession session, ExecutionFrame frame, ModelTraceContext context, Object payload)
    {
        recordAgainstFrame(session, frame, TraceRecordType.MODEL_RESPONSE_RECEIVED, context.metadata(), payload);
    }

    @Override
    public void recordPlanCreated(BifrostSession session, ExecutionPlan plan)
    {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("planId", plan.planId());
        recordOnPlanFrame(session, TraceRecordType.PLAN_CREATED, metadata, plan);
    }

    @Override
    public void recordPlanUpdated(BifrostSession session, ExecutionPlan plan)
    {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("planId", plan.planId());
        recordOnPlanFrame(session, TraceRecordType.PLAN_UPDATED, metadata, plan);
    }

    @Override
    public void recordToolRequested(BifrostSession session, ExecutionFrame frame, ToolTraceContext context, Object payload)
    {
        recordAgainstFrame(session, frame, TraceRecordType.TOOL_CALL_REQUESTED, context.metadata(), payload);
    }

    @Override
    public void recordToolStarted(BifrostSession session, ExecutionFrame frame, ToolTraceContext context, Object payload)
    {
        recordAgainstFrame(session, frame, TraceRecordType.TOOL_CALL_STARTED, context.metadata(), payload);
    }

    @Override
    public void recordToolCompleted(BifrostSession session, ExecutionFrame frame, ToolTraceContext context, Object payload)
    {
        recordAgainstFrame(session, frame, TraceRecordType.TOOL_CALL_COMPLETED, context.metadata(), payload);
    }

    @Override
    public void recordToolFailed(BifrostSession session, ExecutionFrame frame, ToolTraceContext context, Object payload)
    {
        recordAgainstFrame(session, frame, TraceRecordType.TOOL_CALL_FAILED, context.metadata(), payload);
    }

    @Override
    public void recordAdvisorRequestMutation(BifrostSession session, AdvisorTraceContext context, Object payload)
    {
        recordOnActiveFrame(session, TraceRecordType.ADVISOR_REQUEST_MUTATION_RECORDED, context.metadata(), payload);
    }

    @Override
    public void recordAdvisorResponseMutation(BifrostSession session, AdvisorTraceContext context, Object payload)
    {
        recordOnActiveFrame(session, TraceRecordType.ADVISOR_RESPONSE_MUTATION_RECORDED, context.metadata(), payload);
    }

    @Override
    public void recordLinterOutcome(BifrostSession session, LinterOutcome outcome)
    {
        if (outcome.status() == LinterOutcomeStatus.EXHAUSTED)
        {
            session.markTraceErrored();
        }

        recordOnActiveFrame(session, TraceRecordType.LINTER_RECORDED, Map.of(
                "skillName", outcome.skillName(),
                "status", outcome.status().name()), outcome);
    }

    @Override
    public void recordOutputSchemaOutcome(BifrostSession session, OutputSchemaOutcome outcome)
    {
        if (outcome.status() == OutputSchemaOutcomeStatus.EXHAUSTED)
        {
            session.markTraceErrored();
        }

        recordOnActiveFrame(session, TraceRecordType.STRUCTURED_OUTPUT_RECORDED, Map.of(
                "skillName", outcome.skillName(),
                "status", outcome.status().name()), outcome);
    }

    @Override
    public void recordError(BifrostSession session, Object payload)
    {
        session.markTraceErrored();
        recordOnActiveFrame(session, TraceRecordType.ERROR_RECORDED, Map.of(), payload);
    }

    @Override
    public void finalizeTrace(BifrostSession session, TraceCompletion completion)
    {
        session.finalizeTrace(completion == null ? Map.of() : completion.metadata());
    }

    private void recordAgainstFrame(BifrostSession session,
            ExecutionFrame frame,
            TraceRecordType type,
            Map<String, Object> metadata,
            Object payload)
    {
        session.appendTraceRecord(type, Objects.requireNonNull(frame, "frame must not be null"), augmentMetadata(metadata), payload);
    }

    private void recordOnActiveFrame(BifrostSession session,
            TraceRecordType type,
            Map<String, Object> metadata,
            Object payload)
    {
        session.appendTraceRecord(type, augmentMetadata(metadata), payload);
    }

    private void recordOnPlanFrame(BifrostSession session,
            TraceRecordType type,
            Map<String, Object> metadata,
            Object payload)
    {
        ExecutionFrame frame = session.getFramesSnapshot().stream()
                .filter(candidate -> candidate.traceFrameType() == TraceFrameType.PLANNING)
                .findFirst()
                .orElseGet(() -> session.getFramesSnapshot().stream()
                        .filter(candidate -> candidate.traceFrameType() == TraceFrameType.ROOT_MISSION)
                        .findFirst()
                        .orElseGet(() -> session.getFramesSnapshot().stream()
                                .filter(candidate -> candidate.traceFrameType() != TraceFrameType.MODEL_CALL)
                                .findFirst()
                                .orElse(null)));

        if (frame == null)
        {
            recordOnActiveFrame(session, type, metadata, payload);
            return;
        }

        recordAgainstFrame(session, frame, type, metadata, payload);
    }

    private Map<String, Object> augmentMetadata(Map<String, Object> metadata)
    {
        Map<String, Object> safeMetadata = new LinkedHashMap<>();
        if (metadata != null)
        {
            safeMetadata.putAll(metadata);
        }
        safeMetadata.putIfAbsent("recordedAt", clock.instant().toString());
        return Map.copyOf(safeMetadata);
    }
}
