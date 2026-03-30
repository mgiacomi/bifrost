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
import com.lokiscale.bifrost.core.TraceFrameType;
import com.lokiscale.bifrost.core.TraceRecord;
import com.lokiscale.bifrost.core.TraceRecordType;
import com.lokiscale.bifrost.runtime.SimpleChatClient;
import com.lokiscale.bifrost.runtime.state.DefaultExecutionStateService;
import com.lokiscale.bifrost.runtime.usage.ModelUsageExtractor;
import com.lokiscale.bifrost.runtime.usage.SessionUsageSnapshot;
import com.lokiscale.bifrost.runtime.usage.SessionUsageService;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanningServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC);
    private static final EffectiveSkillExecutionConfiguration EXECUTION_CONFIGURATION =
            new EffectiveSkillExecutionConfiguration("gpt-5", AiProvider.OPENAI, "openai/gpt-5", "medium");

    private static final String YAML_PLAN_WITH_LLM_STATUSES = """
            ---
            planId: 12345
            capabilityName: invoiceParser
            createdAt: 2023-03-15T14:30:00.000Z
            status: EXECUTED
            activeTaskId: 67890
            tasks:
              - taskId: 67890
                title: Parse Invoice
                status: SUCCESS
                capabilityName: invoiceParser
                intent: Parse the invoice data
                dependsOn: []
                expectedOutputs: []
                autoCompletable: false
                note: Parsed successfully
            """;

    @Test
    void initializesPlanOnlyWhenInvoked() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-1", 3);
        ExecutionPlan plan = plan("plan-1", PlanTaskStatus.PENDING);

        assertThat(session.getExecutionPlan()).isEmpty();
        assertThat(planningService.initializePlan(session, "hello", "root.visible.skill", EXECUTION_CONFIGURATION, new SimpleChatClient(plan, "done"), List.<ToolCallback>of()))
                .contains(plan);
        assertThat(session.getExecutionPlan()).contains(plan);
    }

    @Test
    void recordsPlanningModelUsageAgainstTheSession() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        RecordingSessionUsageService usageService = new RecordingSessionUsageService();
        DefaultPlanningService planningService = new DefaultPlanningService(
                new DefaultPlanTaskLinker(),
                stateService,
                usageService,
                new ModelUsageExtractor());
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-usage", 3);
        ExecutionPlan plan = plan("plan-usage", PlanTaskStatus.PENDING);

        assertThat(planningService.initializePlan(session, "hello", "root.visible.skill", EXECUTION_CONFIGURATION, new SimpleChatClient(plan, "done"), List.of()))
                .contains(plan);
        assertThat(usageService.lastSkillName).isEqualTo("root.visible.skill");
        assertThat(usageService.snapshot(session).modelCalls()).isEqualTo(1);
        assertThat(usageService.snapshot(session).usageUnits()).isGreaterThan(0);
    }

    @Test
    void initializesPlanFromYamlWithNormalizedStatuses() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-yaml", 3);

        ExecutionPlan plan = planningService.initializePlan(
                        session,
                        "parse invoice",
                        "invoiceParser",
                        EXECUTION_CONFIGURATION,
                        new SimpleChatClient(null, YAML_PLAN_WITH_LLM_STATUSES),
                        List.<ToolCallback>of())
                .orElseThrow();

        assertThat(plan.planId()).isEqualTo("12345");
        assertThat(plan.status()).isEqualTo(PlanStatus.VALID);
        assertThat(plan.findTask("67890")).isPresent();
        assertThat(plan.findTask("67890").orElseThrow().status()).isEqualTo(PlanTaskStatus.COMPLETED);
    }

    @Test
    void rejectsPlanningResponseWithUnboundNonAutoCompletableTask() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-invalid-unbound", 3);

        ExecutionPlan invalidPlan = new ExecutionPlan(
                "plan-invalid-unbound",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(
                        new PlanTask("task-1", "Use tool", PlanTaskStatus.PENDING,
                                "allowed.visible.skill", "Use tool", List.of(), List.of(), false, null),
                        new PlanTask("task-2", "Summarize", PlanTaskStatus.PENDING, null)));

        assertThatThrownBy(() -> planningService.initializePlan(
                session,
                "hello",
                "root.visible.skill",
                EXECUTION_CONFIGURATION,
                new SimpleChatClient(invalidPlan, "done"),
                List.of(),
                true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("violated the step-loop plan contract")
                .hasMessageContaining("Missing: [task-2]");
        assertThat(session.getExecutionPlan()).isEmpty();
    }

    @Test
    void rejectsPlanningResponseWithAutoCompletableTask() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-invalid-auto", 3);

        ExecutionPlan invalidPlan = new ExecutionPlan(
                "plan-invalid-auto",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(new PlanTask("task-1", "Summarize", PlanTaskStatus.PENDING,
                        null, "Summarize results", List.of(), List.of("summary"), true, null)));

        assertThatThrownBy(() -> planningService.initializePlan(
                session,
                "hello",
                "root.visible.skill",
                EXECUTION_CONFIGURATION,
                new SimpleChatClient(invalidPlan, "done"),
                List.of(),
                true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("violated the step-loop plan contract")
                .hasMessageContaining("autoCompletable tasks are not supported: [task-1]");
        assertThat(session.getExecutionPlan()).isEmpty();
    }

    @Test
    void rejectsPlanningResponseWithDuplicateTaskIds() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-invalid-duplicate-task-id", 3);

        ExecutionPlan invalidPlan = new ExecutionPlan(
                "plan-invalid-duplicate-task-id",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(
                        new PlanTask("task-1", "Use first tool", PlanTaskStatus.PENDING,
                                "allowed.visible.skill", "Use tool", List.of(), List.of(), false, null),
                        new PlanTask("task-1", "Use second tool", PlanTaskStatus.PENDING,
                                "allowed.visible.skill", "Use tool again", List.of(), List.of(), false, null)));

        assertThatThrownBy(() -> planningService.initializePlan(
                session,
                "hello",
                "root.visible.skill",
                EXECUTION_CONFIGURATION,
                new SimpleChatClient(invalidPlan, "done"),
                List.of(),
                true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("violated the step-loop plan contract")
                .hasMessageContaining("Task IDs must be unique")
                .hasMessageContaining("task-1");
        assertThat(session.getExecutionPlan()).isEmpty();
    }

    @Test
    void allowsLegacyPlanningResponsesWithoutStepLoopContract() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-legacy-plan", 3);

        ExecutionPlan legacyPlan = new ExecutionPlan(
                "plan-legacy",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(new PlanTask("task-1", "Summarize", PlanTaskStatus.PENDING,
                        null, "Summarize results", List.of(), List.of("summary"), true, null)));

        assertThat(planningService.initializePlan(
                session,
                "hello",
                "root.visible.skill",
                EXECUTION_CONFIGURATION,
                new SimpleChatClient(legacyPlan, "done"),
                List.of()))
                .isPresent();
        assertThat(session.getExecutionPlan()).isPresent();
    }

    @Test
    void recordsPlanningTraceWithRealProviderMetadata() throws Exception {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-trace", 3);

        planningService.initializePlan(
                session,
                "hello",
                "root.visible.skill",
                EXECUTION_CONFIGURATION,
                new SimpleChatClient(plan("plan-trace", PlanTaskStatus.PENDING), "done"),
                List.of());

        List<TraceRecord> records = readRecords(session);

        TraceRecord modelRequest = records.stream()
                .filter(record -> record.recordType() == TraceRecordType.MODEL_REQUEST_PREPARED)
                .findFirst()
                .orElseThrow();
        TraceRecord planningFrame = records.stream()
                .filter(record -> record.recordType() == TraceRecordType.FRAME_OPENED
                        && record.frameType() == TraceFrameType.PLANNING)
                .findFirst()
                .orElseThrow();
        TraceRecord modelFrame = records.stream()
                .filter(record -> record.recordType() == TraceRecordType.FRAME_OPENED
                        && record.frameType() == TraceFrameType.MODEL_CALL
                        && "root.visible.skill#planning-model".equals(record.route()))
                .findFirst()
                .orElseThrow();

        assertThat(modelRequest.metadata()).containsEntry("provider", AiProvider.OPENAI.name());
        assertThat(modelRequest.metadata()).containsEntry("providerModel", "openai/gpt-5");
        assertThat(modelRequest.metadata()).containsEntry("segment", "planning");
        assertThat(modelFrame.parentFrameId()).isEqualTo(planningFrame.frameId());

        TraceRecord sentRecord = records.stream()
                .filter(record -> record.recordType() == TraceRecordType.MODEL_REQUEST_SENT)
                .filter(record -> record.frameType() == TraceFrameType.MODEL_CALL)
                .findFirst()
                .orElseThrow();
        assertThat(sentRecord.data()).isNotNull();
        assertThat(sentRecord.data().get("system").asText()).contains("Create an ordered flight plan");
        assertThat(sentRecord.data().get("user").asText()).isEqualTo("hello");
    }

    @Test
    void marksLinkedTaskStartedCompletedAndBlocked() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-1", 3);
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

    @Test
    void marksExplicitTaskStartedWithoutRelinking() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-explicit-task", 3);

        stateService.storePlan(session, new ExecutionPlan(
                "plan-explicit",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(
                        new PlanTask("task-1", "Use tool once", PlanTaskStatus.PENDING, "allowed.visible.skill", "Use tool", List.of(), List.of(), false, null),
                        new PlanTask("task-2", "Use tool twice", PlanTaskStatus.PENDING, "allowed.visible.skill", "Use tool again", List.of(), List.of(), false, null))));

        ExecutionPlan started = planningService.markTaskStarted(session, "task-2", "allowed.visible.skill", Map.of("value", "hello"))
                .orElseThrow();

        assertThat(started.activeTaskId()).isEqualTo("task-2");
        assertThat(started.findTask("task-2").orElseThrow().status()).isEqualTo(PlanTaskStatus.IN_PROGRESS);
        assertThat(started.findTask("task-1").orElseThrow().status()).isEqualTo(PlanTaskStatus.PENDING);
    }

    @Test
    void rejectsStartingTaskWithMismatchedCapabilityBinding() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-mismatched-start", 3);

        stateService.storePlan(session, new ExecutionPlan(
                "plan-explicit",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(new PlanTask("task-1", "Use tool once", PlanTaskStatus.PENDING,
                        "allowed.visible.skill", "Use tool", List.of(), List.of(), false, null))));

        assertThatThrownBy(() -> planningService.markTaskStarted(session, "task-1", "different.visible.skill", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("task-1")
                .hasMessageContaining("allowed.visible.skill")
                .hasMessageContaining("different.visible.skill");
    }

    @Test
    void rejectsCompletingTaskWithMismatchedCapabilityBinding() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-mismatched-complete", 3);

        stateService.storePlan(session, new ExecutionPlan(
                "plan-explicit",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(new PlanTask("task-1", "Use tool once", PlanTaskStatus.IN_PROGRESS,
                        "allowed.visible.skill", "Use tool", List.of(), List.of(), false, null))));

        assertThatThrownBy(() -> planningService.markToolCompleted(session, "task-1", "different.visible.skill", "done"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("task-1")
                .hasMessageContaining("allowed.visible.skill")
                .hasMessageContaining("different.visible.skill");
    }

    @Test
    void rejectsFailingTaskWithMismatchedCapabilityBinding() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-mismatched-fail", 3);

        stateService.storePlan(session, new ExecutionPlan(
                "plan-explicit",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(new PlanTask("task-1", "Use tool once", PlanTaskStatus.IN_PROGRESS,
                        "allowed.visible.skill", "Use tool", List.of(), List.of(), false, null))));

        assertThatThrownBy(() -> planningService.markToolFailed(
                session,
                "task-1",
                "different.visible.skill",
                new IllegalStateException("boom")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("task-1")
                .hasMessageContaining("allowed.visible.skill")
                .hasMessageContaining("different.visible.skill");
    }

    @Test
    void doesNotLogPlanUpdateWhenCompletedTaskIsMissing() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-missing-task", 3);

        stateService.storePlan(session, new ExecutionPlan(
                "plan-1",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(new PlanTask("task-1", "Use tool", PlanTaskStatus.PENDING, "allowed.visible.skill", "Use tool", List.of(), List.of(), false, null))));

        assertThat(planningService.markToolCompleted(session, "missing-task", "allowed.visible.skill", "done")).isEmpty();
        assertThat(session.getJournalSnapshot()).isEmpty();
    }

    @Test
    void planningPromptIncludesVisibleToolNames() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-tool-names", 3);
        SimpleChatClient chatClient = new SimpleChatClient(plan("plan-tools", PlanTaskStatus.PENDING), "done");

        ToolCallback tool1 = toolCallbackWithName("invoiceParser");
        ToolCallback tool2 = toolCallbackWithName("expenseLookup");

        planningService.initializePlan(session, "check invoice", "duplicateInvoiceChecker", EXECUTION_CONFIGURATION, chatClient, List.of(tool1, tool2));

        String systemPrompt = chatClient.getSystemMessagesSeen().getFirst();
        assertThat(systemPrompt).contains("invoiceParser");
        assertThat(systemPrompt).contains("expenseLookup");
        assertThat(systemPrompt).contains("Available sub-skills");
    }

    @Test
    void planningPromptShowsNoneWhenNoToolsProvided() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-no-tools", 3);
        SimpleChatClient chatClient = new SimpleChatClient(plan("plan-no-tools", PlanTaskStatus.PENDING), "done");

        planningService.initializePlan(session, "check invoice", "duplicateInvoiceChecker", EXECUTION_CONFIGURATION, chatClient, List.of());

        String systemPrompt = chatClient.getSystemMessagesSeen().getFirst();
        assertThat(systemPrompt).contains("(none)");
    }

    @Test
    void planningPromptHardcodesTopLevelCapabilityName() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-cap-name", 3);
        SimpleChatClient chatClient = new SimpleChatClient(plan("plan-cap", PlanTaskStatus.PENDING), "done");

        planningService.initializePlan(session, "check invoice", "duplicateInvoiceChecker", EXECUTION_CONFIGURATION, chatClient, List.of());

        String systemPrompt = chatClient.getSystemMessagesSeen().getFirst();
        assertThat(systemPrompt).contains("\"capabilityName\": \"duplicateInvoiceChecker\"");
    }

    private static ToolCallback toolCallbackWithName(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
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
                List.of(new PlanTask("task-1", "Use tool", status,
                        "allowed.visible.skill", "Use tool", List.of(), List.of(), false, null)));
    }

    private static List<TraceRecord> readRecords(BifrostSession session) {
        List<TraceRecord> records = new ArrayList<>();
        session.readTraceRecords(records::add);
        return records;
    }

    private static final class RecordingSessionUsageService implements SessionUsageService {

        private String lastSkillName;

        @Override
        public SessionUsageSnapshot snapshot(BifrostSession session) {
            return session.getSessionUsage().orElse(SessionUsageSnapshot.empty());
        }

        @Override
        public void recordMissionStart(BifrostSession session, String skillName) {
        }

        @Override
        public void recordModelResponse(BifrostSession session,
                                        String skillName,
                                        com.lokiscale.bifrost.runtime.usage.ModelUsageRecord usageRecord) {
            lastSkillName = skillName;
            SessionUsageSnapshot existing = snapshot(session);
            session.setSessionUsage(existing.recordModelUsage(usageRecord));
        }

        @Override
        public void recordToolCall(BifrostSession session, String skillName, String capabilityName) {
        }

        @Override
        public void recordLinterOutcome(BifrostSession session, com.lokiscale.bifrost.linter.LinterOutcome outcome) {
        }
    }
}
