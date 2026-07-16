package com.lokiscale.bifrost.internal.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPlanTaskLinkerTest {

    private final DefaultPlanTaskLinker linker = new DefaultPlanTaskLinker();

    @Test
    void linksExactlyOneReadyMatchingTask() {
        ExecutionPlan plan = new ExecutionPlan(
                "plan-1",
                "rootVisibleSkill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(
                        toolTask("task-1", "Use allowedVisibleSkill", "allowedVisibleSkill"),
                        new PlanTask("task-2", "Summarize", PlanTaskStatus.PENDING, null)));

        Optional<String> linkedTaskId = linker.linkTask(plan, capability("allowedVisibleSkill"), Map.of("value", "hello"));

        assertThat(linkedTaskId).contains("task-1");
    }

    @Test
    void refusesToLinkWhenMultipleReadyTasksMatchTheSameCapability() {
        ExecutionPlan plan = new ExecutionPlan(
                "plan-1",
                "rootVisibleSkill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(
                        toolTask("task-1", "Use allowedVisibleSkill for source A", "allowedVisibleSkill"),
                        toolTask("task-2", "Use allowedVisibleSkill for source B", "allowedVisibleSkill")));

        Optional<String> linkedTaskId = linker.linkTask(plan, capability("allowedVisibleSkill"), Map.of("value", "hello"));

        assertThat(linkedTaskId).isEmpty();
    }

    private static CapabilityMetadata capability(String name) {
        return new CapabilityMetadata(
                "yaml:" + name,
                name,
                name,
                SkillExecutionDescriptor.none(),
                java.util.Set.of(),
                arguments -> "ok",
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic(name, name),
                "targetBean#deterministicTarget");
    }

    private static PlanTask toolTask(String taskId, String title, String capabilityName) {
        return new PlanTask(
                taskId,
                title,
                PlanTaskStatus.PENDING,
                capabilityName,
                title,
                List.of(),
                List.of(),
                true,
                null);
    }
}
