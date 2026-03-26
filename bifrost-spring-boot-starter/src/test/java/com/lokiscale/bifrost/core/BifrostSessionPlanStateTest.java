package com.lokiscale.bifrost.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BifrostSessionPlanStateTest {

    @Test
    void storesReplacesAndClearsExecutionPlanIndependentlyOfFrames() {
        BifrostSession session = new BifrostSession("session-1", 3);
        session.pushFrame(new ExecutionFrame(
                "frame-1",
                null,
                OperationType.SKILL,
                TraceFrameType.SKILL_EXECUTION,
                "root.visible.skill",
                Map.of("objective", "hello"),
                Instant.parse("2026-03-15T12:00:00Z")));

        ExecutionPlan firstPlan = plan("plan-1", PlanTaskStatus.PENDING, PlanTaskStatus.PENDING);
        ExecutionPlan secondPlan = plan("plan-2", PlanTaskStatus.IN_PROGRESS, PlanTaskStatus.PENDING);

        session.replaceExecutionPlan(firstPlan);
        assertThat(session.getExecutionPlan()).contains(firstPlan);

        session.replaceExecutionPlan(secondPlan);
        assertThat(session.getExecutionPlan()).contains(secondPlan);
        assertThat(session.getFramesSnapshot()).hasSize(1);

        session.clearExecutionPlan();
        assertThat(session.getExecutionPlan()).isEmpty();
        assertThat(session.getFramesSnapshot()).hasSize(1);
    }

    private static ExecutionPlan plan(String planId, PlanTaskStatus first, PlanTaskStatus second) {
        return new ExecutionPlan(
                planId,
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(
                        new PlanTask("task-1", "Plan", first, null),
                        new PlanTask("task-2", "Execute", second, null)));
    }
}
