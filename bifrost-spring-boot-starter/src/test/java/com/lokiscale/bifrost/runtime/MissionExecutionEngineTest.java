package com.lokiscale.bifrost.runtime;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.PlanTask;
import com.lokiscale.bifrost.core.PlanTaskStatus;
import com.lokiscale.bifrost.runtime.planning.PlanningService;
import com.lokiscale.bifrost.runtime.state.DefaultExecutionStateService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MissionExecutionEngineTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void executesPlanningEnabledMissionLoop() {
        PlanningService planningService = mock(PlanningService.class);
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultMissionExecutionEngine engine = new DefaultMissionExecutionEngine(planningService, stateService);
        BifrostSession session = new BifrostSession("session-1", 2);
        ExecutionPlan plan = new ExecutionPlan(
                "plan-1",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(
                        new PlanTask("task-1", "Use tool", PlanTaskStatus.PENDING, "allowed.visible.skill", "Use tool", List.of(), List.of(), false, null),
                        new PlanTask("task-2", "Blocked", PlanTaskStatus.BLOCKED, null)));
        MissionChatClient chatClient = new MissionChatClient("mission complete");
        ToolCallback callback = mock(ToolCallback.class);

        when(planningService.initializePlan(eq(session), eq("hello"), eq("root.visible.skill"), eq(chatClient), any()))
                .thenAnswer(invocation -> {
                    stateService.storePlan(session, plan);
                    return java.util.Optional.of(plan);
                });

        String response = engine.executeMission(session, "root.visible.skill", "hello", chatClient, List.of(callback), true, null);

        assertThat(response).isEqualTo("mission complete");
        assertThat(chatClient.systemMessagesSeen.getFirst()).contains("plan-1", "Ready tasks", "Blocked tasks");
    }

    @Test
    void skipsPlanningForPlanningDisabledMission() {
        PlanningService planningService = mock(PlanningService.class);
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultMissionExecutionEngine engine = new DefaultMissionExecutionEngine(planningService, stateService);
        BifrostSession session = new BifrostSession("session-1", 2);
        MissionChatClient chatClient = new MissionChatClient("mission complete");

        String response = engine.executeMission(session, "root.visible.skill", "hello", chatClient, List.of(), false, null);

        assertThat(response).isEqualTo("mission complete");
        assertThat(chatClient.systemMessagesSeen).containsExactly("Execute the mission using only the visible YAML tools when needed.");
        verify(planningService, never()).initializePlan(eq(session), eq("hello"), eq("root.visible.skill"), eq(chatClient), any());
    }

    private static final class MissionChatClient extends SimpleChatClient {

        private MissionChatClient(String content) {
            super(null, content);
        }
    }
}
