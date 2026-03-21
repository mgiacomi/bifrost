package com.lokiscale.bifrost.runtime.state;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.ExecutionFrame;
import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.TaskExecutionEvent;

import java.util.Map;
import java.util.Optional;

public interface ExecutionStateService {

    ExecutionFrame openMissionFrame(BifrostSession session, String route, Map<String, Object> parameters);

    void closeMissionFrame(BifrostSession session, ExecutionFrame frame);

    void storePlan(BifrostSession session, ExecutionPlan plan);

    void clearPlan(BifrostSession session);

    Optional<ExecutionPlan> currentPlan(BifrostSession session);

    PlanSnapshot snapshotPlan(BifrostSession session);

    void restorePlan(BifrostSession session, PlanSnapshot snapshot);

    void logPlanCreated(BifrostSession session, ExecutionPlan plan);

    void logPlanUpdated(BifrostSession session, ExecutionPlan plan);

    void logToolCall(BifrostSession session, TaskExecutionEvent event);

    void logUnplannedToolCall(BifrostSession session, TaskExecutionEvent event);

    void logToolResult(BifrostSession session, TaskExecutionEvent event);

    void logError(BifrostSession session, Map<String, Object> payload);
}
