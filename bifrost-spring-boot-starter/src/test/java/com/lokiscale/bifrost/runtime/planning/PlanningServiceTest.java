package com.lokiscale.bifrost.runtime.planning;

import com.lokiscale.bifrost.autoconfigure.AiProvider;
import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.CapabilityKind;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.CapabilityToolDescriptor;
import com.lokiscale.bifrost.core.DefaultPlanTaskLinker;
import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.JournalEntry;
import com.lokiscale.bifrost.core.JournalEntryType;
import com.lokiscale.bifrost.core.ModelPreference;
import com.lokiscale.bifrost.core.PlanStatus;
import com.lokiscale.bifrost.core.PlanTask;
import com.lokiscale.bifrost.core.PlanTaskStatus;
import com.lokiscale.bifrost.core.SkillExecutionDescriptor;
import com.lokiscale.bifrost.runtime.SimpleChatClient;
import com.lokiscale.bifrost.runtime.state.DefaultExecutionStateService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanningServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void initializesPlanOnlyWhenInvoked() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = new BifrostSession("session-1", 3);
        ExecutionPlan plan = plan("plan-1", PlanTaskStatus.PENDING);

        assertThat(session.getExecutionPlan()).isEmpty();
        assertThat(planningService.initializePlan(session, "hello", "root.visible.skill", new SimpleChatClient(plan, "done"), List.<ToolCallback>of()))
                .contains(plan);
        assertThat(session.getExecutionPlan()).contains(plan);
    }

    @Test
    void marksLinkedTaskStartedCompletedAndBlocked() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = new BifrostSession("session-1", 3);
        CapabilityMetadata capability = capability("allowed.visible.skill");

        stateService.storePlan(session, new ExecutionPlan(
                "plan-1",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(
                        new PlanTask("task-1", "Use tool", PlanTaskStatus.PENDING, "allowed.visible.skill", "Use tool", List.of(), List.of(), false, null),
                        new PlanTask("task-2", "Summarize", PlanTaskStatus.PENDING, null))));

        ExecutionPlan started = planningService.markToolStarted(session, capability, Map.of("value", "hello")).orElseThrow();
        assertThat(started.activeTaskId()).isEqualTo("task-1");
        assertThat(started.findTask("task-1").orElseThrow().status()).isEqualTo(PlanTaskStatus.IN_PROGRESS);
        assertThat(session.getJournalSnapshot()).extracting(JournalEntry::type)
                .containsExactly(JournalEntryType.PLAN_UPDATED);

        ExecutionPlan completed = planningService.markToolCompleted(session, "task-1", capability.name(), "done").orElseThrow();
        assertThat(completed.activeTaskId()).isNull();
        assertThat(completed.findTask("task-1").orElseThrow().status()).isEqualTo(PlanTaskStatus.COMPLETED);

        stateService.storePlan(session, new ExecutionPlan(
                "plan-2",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:01:00Z"),
                List.of(new PlanTask("task-3", "Use tool", PlanTaskStatus.PENDING, "allowed.visible.skill", "Use tool", List.of(), List.of(), false, null))));
        ExecutionPlan blocked = planningService.markToolStarted(session, capability, Map.of()).orElseThrow();
        assertThat(planningService.markToolFailed(session, blocked.activeTaskId(), capability.name(), new IllegalStateException("boom")))
                .isPresent()
                .get()
                .extracting(ExecutionPlan::status)
                .isEqualTo(PlanStatus.STALE);
        assertThat(session.getExecutionPlan().orElseThrow().findTask("task-3").orElseThrow().status()).isEqualTo(PlanTaskStatus.BLOCKED);
        assertThat(session.getJournalSnapshot()).extracting(JournalEntry::type)
                .containsExactly(
                        JournalEntryType.PLAN_UPDATED,
                        JournalEntryType.PLAN_UPDATED,
                        JournalEntryType.PLAN_UPDATED,
                        JournalEntryType.PLAN_UPDATED);
    }

    private static CapabilityMetadata capability(String name) {
        return new CapabilityMetadata(
                "yaml:child",
                name,
                "child",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(new com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration(
                        "gpt-5",
                        AiProvider.OPENAI,
                        "openai/gpt-5",
                        "medium")),
                java.util.Set.of(),
                arguments -> "ok",
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic(name, "child"),
                "targetBean#deterministicTarget");
    }

    private static ExecutionPlan plan(String id, PlanTaskStatus status) {
        return new ExecutionPlan(
                id,
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(new PlanTask("task-1", "Use tool", status, null)));
    }
}
