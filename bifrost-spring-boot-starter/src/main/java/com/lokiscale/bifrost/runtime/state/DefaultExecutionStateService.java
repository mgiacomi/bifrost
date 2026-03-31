package com.lokiscale.bifrost.runtime.state;

import com.lokiscale.bifrost.core.AdvisorTraceContext;
import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.ExecutionFrame;
import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.DefaultExecutionTraceRecorder;
import com.lokiscale.bifrost.core.ExecutionTraceRecorder;
import com.lokiscale.bifrost.core.ModelTraceCallback;
import com.lokiscale.bifrost.core.ModelTraceContext;
import com.lokiscale.bifrost.core.ModelTraceResult;
import com.lokiscale.bifrost.core.OperationType;
import com.lokiscale.bifrost.core.TaskExecutionEvent;
import com.lokiscale.bifrost.core.TraceFrameType;
import com.lokiscale.bifrost.core.TraceRecordType;
import com.lokiscale.bifrost.core.ToolTraceContext;
import com.lokiscale.bifrost.linter.LinterOutcome;
import com.lokiscale.bifrost.outputschema.OutputSchemaOutcome;
import com.lokiscale.bifrost.runtime.usage.NoOpSessionUsageService;
import com.lokiscale.bifrost.runtime.usage.SessionUsageService;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Collection;
import java.util.Set;

public class DefaultExecutionStateService implements ExecutionStateService {

    private final Clock clock;
    private final SessionUsageService sessionUsageService;
    // Runtime observability flows through one recorder boundary so feature code does not invent parallel trace semantics.
    private final ExecutionTraceRecorder traceRecorder;

    public DefaultExecutionStateService(Clock clock) {
        this(clock, new NoOpSessionUsageService());
    }

    public DefaultExecutionStateService(Clock clock, SessionUsageService sessionUsageService) {
        this(clock, sessionUsageService, new DefaultExecutionTraceRecorder(clock));
    }

    public DefaultExecutionStateService(Clock clock,
                                        SessionUsageService sessionUsageService,
                                        ExecutionTraceRecorder traceRecorder) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.sessionUsageService = Objects.requireNonNull(sessionUsageService, "sessionUsageService must not be null");
        this.traceRecorder = Objects.requireNonNull(traceRecorder, "traceRecorder must not be null");
    }

    @Override
    public ExecutionFrame openMissionFrame(BifrostSession session, String route, Map<String, Object> parameters) {
        return openFrame(session, TraceFrameType.ROOT_MISSION, route, parameters);
    }

    @Override
    public ExecutionFrame openFrame(BifrostSession session, TraceFrameType traceFrameType, String route, Map<String, Object> parameters) {
        Objects.requireNonNull(session, "session must not be null");
        Instant now = clock.instant();
        ExecutionFrame frame = new ExecutionFrame(
                UUID.randomUUID().toString(),
                currentFrameId(session),
                mapOperationType(traceFrameType),
                traceFrameType,
                route,
                parameters == null ? Map.of() : Map.copyOf(parameters),
                now);
        session.pushFrame(frame);
        try {
            traceRecorder.recordFrameOpened(session, frame);
        }
        catch (RuntimeException | Error ex) {
            rollbackFramePush(session, frame);
            throw ex;
        }
        return frame;
    }

    @Override
    public void closeMissionFrame(BifrostSession session, ExecutionFrame frame) {
        closeFrame(session, frame, Map.of());
    }

    @Override
    public void closeFrame(BifrostSession session, ExecutionFrame frame, Map<String, Object> metadata) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(frame, "frame must not be null");
        List<ExecutionFrame> frames = session.getFramesSnapshot();
        if (frames.isEmpty()) {
            return;
        }
        if (frames.stream().noneMatch(frame::equals)) {
            return;
        }
        ExecutionFrame activeFrame;
        try {
            activeFrame = session.peekFrame();
        }
        catch (IllegalStateException ex) {
            return;
        }
        if (!activeFrame.equals(frame)) {
            throw new IllegalStateException("Attempted to close execution frame '%s' but active frame was '%s'."
                    .formatted(frame.frameId(), activeFrame.frameId()));
        }
        traceRecorder.recordFrameClosed(session, frame, metadata == null ? Map.of() : Map.copyOf(metadata));
        try {
            session.popFrame();
        }
        catch (IllegalStateException ex) {
            if (session.getFramesSnapshot().stream().noneMatch(frame::equals)) {
                return;
            }
            throw ex;
        }
    }

    @Override
    public void storePlan(BifrostSession session, ExecutionPlan plan) {
        Objects.requireNonNull(session, "session must not be null");
        session.replaceExecutionPlan(Objects.requireNonNull(plan, "plan must not be null"));
    }

    @Override
    public void clearPlan(BifrostSession session) {
        Objects.requireNonNull(session, "session must not be null");
        session.clearExecutionPlan();
    }

    @Override
    public java.util.Optional<ExecutionPlan> currentPlan(BifrostSession session) {
        Objects.requireNonNull(session, "session must not be null");
        return session.getExecutionPlan();
    }

    @Override
    public PlanSnapshot snapshotPlan(BifrostSession session) {
        Objects.requireNonNull(session, "session must not be null");
        return PlanSnapshot.of(session.getExecutionPlan().orElse(null));
    }

    @Override
    public void restorePlan(BifrostSession session, PlanSnapshot snapshot) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (snapshot.plan() == null) {
            session.clearExecutionPlan();
            return;
        }
        session.replaceExecutionPlan(snapshot.plan());
    }

    @Override
    public EvidenceSnapshot snapshotEvidence(BifrostSession session) {
        Objects.requireNonNull(session, "session must not be null");
        return EvidenceSnapshot.of(session.getProducedEvidenceTypes());
    }

    @Override
    public void restoreEvidence(BifrostSession session, EvidenceSnapshot snapshot) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (snapshot.evidenceTypes() == null) {
            session.clearProducedEvidenceTypes();
            return;
        }
        session.replaceProducedEvidenceTypes(snapshot.evidenceTypes());
    }

    @Override
    public void logPlanCreated(BifrostSession session, ExecutionPlan plan) {
        Objects.requireNonNull(session, "session must not be null");
        traceRecorder.recordPlanCreated(session, Objects.requireNonNull(plan, "plan must not be null"));
    }

    @Override
    public void logPlanUpdated(BifrostSession session, ExecutionPlan plan) {
        Objects.requireNonNull(session, "session must not be null");
        traceRecorder.recordPlanUpdated(session, Objects.requireNonNull(plan, "plan must not be null"));
    }

    @Override
    public void recordPlanningEvent(BifrostSession session,
                                    ExecutionFrame frame,
                                    TraceRecordType recordType,
                                    Map<String, Object> metadata,
                                    Object payload) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(frame, "frame must not be null");
        Objects.requireNonNull(recordType, "recordType must not be null");
        session.appendTraceRecord(recordType, frame,
                metadata == null ? Map.of() : Map.copyOf(metadata),
                payload == null ? Map.of() : payload);
    }

    @Override
    public void recordModelRequestPrepared(BifrostSession session, ExecutionFrame frame, ModelTraceContext context, Object payload) {
        Objects.requireNonNull(session, "session must not be null");
        traceRecorder.recordModelRequestPrepared(
                session,
                Objects.requireNonNull(frame, "frame must not be null"),
                Objects.requireNonNull(context, "context must not be null"),
                payload);
    }

    @Override
    public void recordModelRequestSent(BifrostSession session, ExecutionFrame frame, ModelTraceContext context, Object payload) {
        Objects.requireNonNull(session, "session must not be null");
        traceRecorder.recordModelRequestSent(
                session,
                Objects.requireNonNull(frame, "frame must not be null"),
                Objects.requireNonNull(context, "context must not be null"),
                payload);
    }

    @Override
    public void recordModelResponseReceived(BifrostSession session, ExecutionFrame frame, ModelTraceContext context, Object payload) {
        Objects.requireNonNull(session, "session must not be null");
        traceRecorder.recordModelResponseReceived(
                session,
                Objects.requireNonNull(frame, "frame must not be null"),
                Objects.requireNonNull(context, "context must not be null"),
                payload);
    }

    @Override
    public <T> T traceModelCall(BifrostSession session,
                                ExecutionFrame frame,
                                ModelTraceContext context,
                                Object preparedPayload,
                                ModelTraceCallback<T> callback) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(frame, "frame must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(callback, "callback must not be null");
        traceRecorder.recordModelRequestPrepared(session, frame, context, preparedPayload);
        ModelTraceResult<T> result = callback.execute(sentPayload ->
                traceRecorder.recordModelRequestSent(session, frame, context, sentPayload));
        traceRecorder.recordModelResponseReceived(
                session,
                frame,
                context,
                result == null ? null : result.responsePayload());
        return result == null ? null : result.result();
    }

    @Override
    public void logToolCall(BifrostSession session, TaskExecutionEvent event) {
        Objects.requireNonNull(session, "session must not be null");
        TaskExecutionEvent safeEvent = Objects.requireNonNull(event, "event must not be null");
        ExecutionFrame frame = requireActiveFrame(session);
        ToolTraceContext context = toolContext(safeEvent, false);
        traceRecorder.recordToolRequested(session, frame, context, safeEvent);
        traceRecorder.recordToolStarted(session, frame, context, safeEvent);
    }

    @Override
    public void logUnplannedToolCall(BifrostSession session, TaskExecutionEvent event) {
        Objects.requireNonNull(session, "session must not be null");
        TaskExecutionEvent safeEvent = Objects.requireNonNull(event, "event must not be null");
        ExecutionFrame frame = requireActiveFrame(session);
        ToolTraceContext context = toolContext(safeEvent, true);
        traceRecorder.recordToolRequested(session, frame, context, safeEvent);
        traceRecorder.recordToolStarted(session, frame, context, safeEvent);
    }

    @Override
    public void logToolResult(BifrostSession session, TaskExecutionEvent event) {
        Objects.requireNonNull(session, "session must not be null");
        TaskExecutionEvent safeEvent = Objects.requireNonNull(event, "event must not be null");
        traceRecorder.recordToolCompleted(
                session,
                requireActiveFrame(session),
                toolContext(safeEvent, safeEvent.linkedTaskId() == null),
                safeEvent);
    }

    @Override
    public void logToolFailure(BifrostSession session, ToolTraceContext context, Object payload) {
        Objects.requireNonNull(session, "session must not be null");
        traceRecorder.recordToolFailed(
                session,
                requireActiveFrame(session),
                Objects.requireNonNull(context, "context must not be null"),
                payload);
    }

    @Override
    public void clearProducedEvidence(BifrostSession session) {
        Objects.requireNonNull(session, "session must not be null");
        session.clearProducedEvidenceTypes();
    }

    @Override
    public Set<String> currentEvidenceTypes(BifrostSession session) {
        Objects.requireNonNull(session, "session must not be null");
        return session.getProducedEvidenceTypes();
    }

    @Override
    public void recordProducedEvidence(BifrostSession session,
                                       String capabilityName,
                                       String linkedTaskId,
                                       boolean unplanned,
                                       Collection<String> evidenceTypes) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(capabilityName, "capabilityName must not be null");
        Objects.requireNonNull(evidenceTypes, "evidenceTypes must not be null");
        if (evidenceTypes.isEmpty()) {
            return;
        }
        session.addProducedEvidenceTypes(evidenceTypes);
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("capabilityName", capabilityName);
        metadata.put("unplanned", unplanned);
        if (linkedTaskId != null) {
            metadata.put("linkedTaskId", linkedTaskId);
        }
        session.appendTraceRecord(TraceRecordType.EVIDENCE_RECORDED, Map.copyOf(metadata), Map.of(
                "evidenceTypes", List.copyOf(evidenceTypes),
                "ledger", session.getProducedEvidenceTypes()));
    }

    @Override
    public void recordEvidenceValidation(BifrostSession session,
                                         boolean passed,
                                         Map<String, Object> metadata,
                                         Object payload) {
        Objects.requireNonNull(session, "session must not be null");
        session.appendTraceRecord(
                passed ? TraceRecordType.EVIDENCE_VALIDATION_PASSED : TraceRecordType.EVIDENCE_VALIDATION_FAILED,
                metadata == null ? Map.of() : Map.copyOf(metadata),
                payload == null ? Map.of() : payload);
    }

    @Override
    public void recordLinterOutcome(BifrostSession session, LinterOutcome outcome) {
        Objects.requireNonNull(session, "session must not be null");
        LinterOutcome recordedOutcome = Objects.requireNonNull(outcome, "outcome must not be null");
        session.setLastLinterOutcome(recordedOutcome);
        traceRecorder.recordLinterOutcome(session, recordedOutcome);
        sessionUsageService.recordLinterOutcome(session, recordedOutcome);
    }

    @Override
    public void recordOutputSchemaOutcome(BifrostSession session, OutputSchemaOutcome outcome) {
        Objects.requireNonNull(session, "session must not be null");
        OutputSchemaOutcome recordedOutcome = Objects.requireNonNull(outcome, "outcome must not be null");
        session.setLastOutputSchemaOutcome(recordedOutcome);
        traceRecorder.recordOutputSchemaOutcome(session, recordedOutcome);
    }

    @Override
    public void recordAdvisorRequestMutation(BifrostSession session, AdvisorTraceContext context, Object payload) {
        Objects.requireNonNull(session, "session must not be null");
        traceRecorder.recordAdvisorRequestMutation(session, Objects.requireNonNull(context, "context must not be null"), payload);
    }

    @Override
    public void recordAdvisorResponseMutation(BifrostSession session, AdvisorTraceContext context, Object payload) {
        Objects.requireNonNull(session, "session must not be null");
        traceRecorder.recordAdvisorResponseMutation(session, Objects.requireNonNull(context, "context must not be null"), payload);
    }

    @Override
    public void logError(BifrostSession session, Map<String, Object> payload) {
        Objects.requireNonNull(session, "session must not be null");
        traceRecorder.recordError(session, payload == null ? Map.of() : Map.copyOf(payload));
    }

    @Override
    public void recordStepEvent(BifrostSession session, ExecutionFrame frame, TraceRecordType recordType,
                                 Map<String, Object> metadata, Object payload) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(frame, "frame must not be null");
        Objects.requireNonNull(recordType, "recordType must not be null");
        session.appendTraceRecord(recordType, frame,
                metadata == null ? Map.of() : Map.copyOf(metadata),
                payload == null ? Map.of() : payload);
    }

    @Override
    public void finalizeTrace(BifrostSession session, Map<String, Object> metadata) {
        Objects.requireNonNull(session, "session must not be null");
        traceRecorder.finalizeTrace(session, com.lokiscale.bifrost.core.TraceCompletion.of(metadata));
    }

    private String currentFrameId(BifrostSession session) {
        List<ExecutionFrame> frames = session.getFramesSnapshot();
        return frames.isEmpty() ? null : frames.getFirst().frameId();
    }

    private OperationType mapOperationType(TraceFrameType traceFrameType) {
        if (traceFrameType == null) {
            return OperationType.SKILL;
        }
        return switch (traceFrameType) {
            case ROOT_MISSION -> OperationType.CAPABILITY;
            case SKILL_EXECUTION, PLANNING, MODEL_CALL, TOOL_INVOCATION, STEP_EXECUTION -> OperationType.SKILL;
            case RETRY -> OperationType.SUB_AGENT;
        };
    }

    private ExecutionFrame requireActiveFrame(BifrostSession session) {
        return session.peekFrame();
    }

    private ToolTraceContext toolContext(TaskExecutionEvent event, boolean unplanned) {
        return new ToolTraceContext(event.capabilityName(), event.linkedTaskId(), unplanned);
    }

    private void rollbackFramePush(BifrostSession session, ExecutionFrame frame) {
        List<ExecutionFrame> frames = session.getFramesSnapshot();
        if (frames.isEmpty() || !frame.equals(frames.getFirst())) {
            return;
        }
        try {
            session.popFrame();
        }
        catch (IllegalStateException ignored) {
            // Best-effort rollback only; preserve the original recorder failure.
        }
    }
}
