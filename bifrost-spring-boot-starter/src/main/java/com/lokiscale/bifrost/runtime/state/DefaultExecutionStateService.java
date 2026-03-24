package com.lokiscale.bifrost.runtime.state;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.ExecutionFrame;
import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.OperationType;
import com.lokiscale.bifrost.core.TaskExecutionEvent;
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

public class DefaultExecutionStateService implements ExecutionStateService {

    private final Clock clock;
    private final SessionUsageService sessionUsageService;

    public DefaultExecutionStateService(Clock clock) {
        this(clock, new NoOpSessionUsageService());
    }

    public DefaultExecutionStateService(Clock clock, SessionUsageService sessionUsageService) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.sessionUsageService = Objects.requireNonNull(sessionUsageService, "sessionUsageService must not be null");
    }

    @Override
    public ExecutionFrame openMissionFrame(BifrostSession session, String route, Map<String, Object> parameters) {
        Objects.requireNonNull(session, "session must not be null");
        Instant now = clock.instant();
        ExecutionFrame frame = new ExecutionFrame(
                UUID.randomUUID().toString(),
                currentFrameId(session),
                OperationType.SKILL,
                route,
                parameters == null ? Map.of() : Map.copyOf(parameters),
                now);
        session.pushFrame(frame);
        return frame;
    }

    @Override
    public void closeMissionFrame(BifrostSession session, ExecutionFrame frame) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(frame, "frame must not be null");
        ExecutionFrame activeFrame = session.peekFrame();
        if (!activeFrame.equals(frame)) {
            throw new IllegalStateException("Attempted to close execution frame '%s' but active frame was '%s'."
                    .formatted(frame.frameId(), activeFrame.frameId()));
        }
        session.popFrame();
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
    public void logPlanCreated(BifrostSession session, ExecutionPlan plan) {
        Objects.requireNonNull(session, "session must not be null");
        session.logPlanCreated(clock.instant(), Objects.requireNonNull(plan, "plan must not be null"));
    }

    @Override
    public void logPlanUpdated(BifrostSession session, ExecutionPlan plan) {
        Objects.requireNonNull(session, "session must not be null");
        session.logPlanUpdated(clock.instant(), Objects.requireNonNull(plan, "plan must not be null"));
    }

    @Override
    public void logToolCall(BifrostSession session, TaskExecutionEvent event) {
        Objects.requireNonNull(session, "session must not be null");
        session.logToolExecution(clock.instant(), Objects.requireNonNull(event, "event must not be null"));
    }

    @Override
    public void logUnplannedToolCall(BifrostSession session, TaskExecutionEvent event) {
        Objects.requireNonNull(session, "session must not be null");
        session.logUnplannedToolExecution(clock.instant(), Objects.requireNonNull(event, "event must not be null"));
    }

    @Override
    public void logToolResult(BifrostSession session, TaskExecutionEvent event) {
        Objects.requireNonNull(session, "session must not be null");
        session.logToolResult(clock.instant(), Objects.requireNonNull(event, "event must not be null"));
    }

    @Override
    public void recordLinterOutcome(BifrostSession session, LinterOutcome outcome) {
        Objects.requireNonNull(session, "session must not be null");
        LinterOutcome recordedOutcome = Objects.requireNonNull(outcome, "outcome must not be null");
        session.setLastLinterOutcome(recordedOutcome);
        session.logLinterOutcome(clock.instant(), recordedOutcome);
        sessionUsageService.recordLinterOutcome(session, recordedOutcome);
    }

    @Override
    public void recordOutputSchemaOutcome(BifrostSession session, OutputSchemaOutcome outcome) {
        Objects.requireNonNull(session, "session must not be null");
        OutputSchemaOutcome recordedOutcome = Objects.requireNonNull(outcome, "outcome must not be null");
        session.setLastOutputSchemaOutcome(recordedOutcome);
        session.logOutputSchemaOutcome(clock.instant(), recordedOutcome);
    }

    @Override
    public void logError(BifrostSession session, Map<String, Object> payload) {
        Objects.requireNonNull(session, "session must not be null");
        session.logError(clock.instant(), payload == null ? Map.of() : Map.copyOf(payload));
    }

    private String currentFrameId(BifrostSession session) {
        List<ExecutionFrame> frames = session.getFramesSnapshot();
        return frames.isEmpty() ? null : frames.getFirst().frameId();
    }
}
