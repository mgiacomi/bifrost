package com.lokiscale.bifrost.runtime.tool;

import com.lokiscale.bifrost.autoconfigure.AiProvider;
import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.CapabilityExecutionRouter;
import com.lokiscale.bifrost.core.CapabilityKind;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.CapabilityToolDescriptor;
import com.lokiscale.bifrost.core.ExecutionFrame;
import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.ModelPreference;
import com.lokiscale.bifrost.core.PlanTask;
import com.lokiscale.bifrost.core.PlanTaskStatus;
import com.lokiscale.bifrost.core.SkillExecutionDescriptor;
import com.lokiscale.bifrost.core.TraceFrameType;
import com.lokiscale.bifrost.runtime.planning.PlanningService;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import com.lokiscale.bifrost.security.DefaultAccessGuard;
import com.lokiscale.bifrost.vfs.RefResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.ai.tool.ToolCallback;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolCallbackFactoryTest {

    @Test
    void buildsVisibleToolDefinitions() {
        CapabilityExecutionRouter router = mock(CapabilityExecutionRouter.class);
        PlanningService planningService = mock(PlanningService.class);
        ExecutionStateService stateService = mock(ExecutionStateService.class);
        DefaultToolCallbackFactory factory = new DefaultToolCallbackFactory(router, planningService, stateService);

        ToolCallback callback = factory.createToolCallbacks(com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-1", 2), List.of(capability()), null).getFirst();

        assertThat(callback.getToolDefinition().name()).isEqualTo("allowed.visible.skill");
        assertThat(callback.getToolDefinition().description()).isEqualTo("child");
        assertThat(callback.getToolDefinition().inputSchema()).contains("\"type\" : \"object\"");
    }

    @Test
    void routesUnplannedAndLinkedExecutionsThroughServices() {
        CapabilityExecutionRouter router = mock(CapabilityExecutionRouter.class);
        PlanningService planningService = mock(PlanningService.class);
        ExecutionStateService stateService = mock(ExecutionStateService.class);
        DefaultToolCallbackFactory factory = new DefaultToolCallbackFactory(router, planningService, stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-1", 2);
        CapabilityMetadata capability = capability();
        ExecutionFrame toolFrame = new ExecutionFrame(
                "tool-frame-1",
                null,
                com.lokiscale.bifrost.core.OperationType.SKILL,
                TraceFrameType.TOOL_INVOCATION,
                capability.name(),
                java.util.Map.of(),
                Instant.parse("2026-03-15T12:00:00Z"));
        ExecutionPlan linkedPlan = new ExecutionPlan(
                "plan-1",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                com.lokiscale.bifrost.core.PlanStatus.VALID,
                "task-1",
                List.of(new PlanTask("task-1", "Use tool", PlanTaskStatus.IN_PROGRESS, "allowed.visible.skill", "Use tool", List.of(), List.of(), false, "Starting")));

        when(planningService.markToolStarted(eq(session), eq(capability), any())).thenReturn(Optional.of(linkedPlan));
        when(stateService.openFrame(eq(session), eq(TraceFrameType.TOOL_INVOCATION), eq(capability.name()), any())).thenReturn(toolFrame);
        when(router.execute(eq(capability), any(), eq(session), eq(null))).thenReturn("child:hello");

        ToolCallback linkedCallback = factory.createToolCallbacks(session, List.of(capability), null).getFirst();
        Object linkedResult = linkedCallback.call("{\"value\":\"hello\"}");

        assertThat(linkedResult).isEqualTo("\"child:hello\"");
        verify(planningService).markToolCompleted(session, "task-1", capability.name(), "child:hello");
        org.mockito.InOrder inOrder = inOrder(stateService);
        inOrder.verify(stateService).openFrame(eq(session), eq(TraceFrameType.TOOL_INVOCATION), eq(capability.name()), any());
        inOrder.verify(stateService).logToolCall(eq(session), any());
        inOrder.verify(stateService).logToolResult(eq(session), any());
        inOrder.verify(stateService).closeFrame(eq(session), eq(toolFrame), any());

        when(planningService.markToolStarted(eq(session), eq(capability), any())).thenReturn(Optional.empty());
        when(stateService.openFrame(eq(session), eq(TraceFrameType.TOOL_INVOCATION), eq(capability.name()), any())).thenReturn(toolFrame);
        when(router.execute(eq(capability), any(), eq(session), eq(null))).thenReturn("child:again");

        ToolCallback unplannedCallback = factory.createToolCallbacks(session, List.of(capability), null).getFirst();
        Object unplannedResult = unplannedCallback.call("{\"value\":\"again\"}");

        assertThat(unplannedResult).isEqualTo("\"child:again\"");
        verify(stateService).logUnplannedToolCall(eq(session), any());
        verify(stateService, times(2)).closeFrame(eq(session), eq(toolFrame), any());
    }

    @Test
    void resolvesRefArgumentsBeforeDeterministicExecution() {
        PlanningService planningService = mock(PlanningService.class);
        ExecutionStateService stateService = mock(ExecutionStateService.class);
        RefResolver refResolver = (value, session) -> value instanceof String text && text.startsWith("ref://")
                ? "resolved-content"
                : value;
        CapabilityExecutionRouter router = new CapabilityExecutionRouter(
                refResolver,
                new StaticListableBeanFactory().getBeanProvider(com.lokiscale.bifrost.core.ExecutionCoordinator.class),
                stateService,
                new DefaultAccessGuard());
        DefaultToolCallbackFactory factory = new DefaultToolCallbackFactory(router, planningService, stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-1", 2);

        ToolCallback callback = factory.createToolCallbacks(session, List.of(capability()), null).getFirst();
        Object result = callback.call("{\"value\":\"ref://artifacts/input.txt\"}");

        assertThat(result).isEqualTo("\"child:resolved-content\"");
    }

    private static CapabilityMetadata capability() {
        return new CapabilityMetadata(
                "yaml:child",
                "allowed.visible.skill",
                "child",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(new com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration(
                        "gpt-5",
                        AiProvider.OPENAI,
                        "openai/gpt-5",
                        "medium")),
                java.util.Set.of(),
                arguments -> "child:" + arguments.get("value"),
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("allowed.visible.skill", "child"),
                "targetBean#deterministicTarget");
    }
}
