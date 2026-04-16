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
import com.lokiscale.bifrost.runtime.evidence.EvidenceContract;
import com.lokiscale.bifrost.runtime.state.DefaultExecutionStateService;
import com.lokiscale.bifrost.runtime.usage.ModelUsageExtractor;
import com.lokiscale.bifrost.runtime.usage.SessionUsageSnapshot;
import com.lokiscale.bifrost.runtime.usage.SessionUsageService;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import com.lokiscale.bifrost.skill.YamlSkillManifest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
        assertThat(planningService.initializePlan(session, "hello", null, rootDefinition(), new SimpleChatClient(plan, "done"), List.<ToolCallback>of()))
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

        assertThat(planningService.initializePlan(session, "hello", null, rootDefinition(), new SimpleChatClient(plan, "done"), List.of()))
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
                        null,
                        rootDefinition("invoiceParser"),
                        new SimpleChatClient(null, YAML_PLAN_WITH_LLM_STATUSES),
                        List.<ToolCallback>of())
                .orElseThrow();

        assertThat(plan.planId()).isEqualTo("12345");
        assertThat(plan.status()).isEqualTo(PlanStatus.VALID);
        assertThat(plan.findTask("67890")).isPresent();
        assertThat(plan.findTask("67890").orElseThrow().status()).isEqualTo(PlanTaskStatus.COMPLETED);
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
                null,
                rootDefinition(),
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
                null,
                rootDefinition(),
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

        ExecutionPlan completed = planningService.markToolCompleted(session, "task-1", capability.name(), "done", EvidenceContract.empty()).orElseThrow();
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

        assertThatThrownBy(() -> planningService.markToolCompleted(session, "task-1", "different.visible.skill", "done", EvidenceContract.empty()))
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

        assertThat(planningService.markToolCompleted(session, "missing-task", "allowed.visible.skill", "done", EvidenceContract.empty())).isEmpty();
        assertThat(session.getJournalSnapshot()).isEmpty();
    }

    @Test
    void planningPromptIncludesToolDescriptionsAndAlignmentRules() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-tool-names", 3);
        SimpleChatClient chatClient = new SimpleChatClient(plan("plan-tools", PlanTaskStatus.PENDING), "done");

        ToolCallback tool1 = toolCallback("invoiceParser", "Extract invoice fields from source documents");
        ToolCallback tool2 = toolCallback("expenseLookup", "Look up prior expenses for a parsed invoice");

        planningService.initializePlan(session, "check invoice", null, rootDefinition("duplicateInvoiceChecker"), chatClient, List.of(tool1, tool2));

        String systemPrompt = chatClient.getSystemMessagesSeen().getFirst();
        assertThat(systemPrompt).contains("invoiceParser: Extract invoice fields from source documents");
        assertThat(systemPrompt).contains("expenseLookup: Look up prior expenses for a parsed invoice");
        assertThat(systemPrompt).contains("Available sub-skills");
        assertThat(systemPrompt).contains("Bind each task to the tool that best matches that task's intent.");
        assertThat(systemPrompt).contains("Gather enough evidence to support the final answer before the mission is complete.");
        assertThat(systemPrompt).contains("\"capabilityName\": \"duplicateInvoiceChecker\"");
    }

    @Test
    void planningPromptIncludesEvidenceConstraints() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-evidence-constraints", 3);
        SimpleChatClient chatClient = new SimpleChatClient(plan("plan-tools", PlanTaskStatus.PENDING), "done");

        ToolCallback tool1 = toolCallback("invoiceParser", "Extract invoice fields from source documents");
        ToolCallback tool2 = toolCallback("expenseLookup", "Look up prior expenses for a parsed invoice");

        assertThatThrownBy(() -> planningService.initializePlan(session, "check invoice", null, duplicateInvoiceDefinition(), chatClient, List.of(tool1, tool2)))
                .isInstanceOf(IllegalStateException.class);

        String systemPrompt = chatClient.getSystemMessagesSeen().getFirst();
        assertThat(systemPrompt).contains("Evidence Constraints:");
        assertThat(systemPrompt).contains("- You MUST explicitly include a task that uses the [expenseLookup, invoiceParser] tool(s) to help determine the value for the 'isDuplicate' output field.");
        assertThat(systemPrompt).contains("- You MUST explicitly include a task that uses the [invoiceParser] tool(s) to help determine the value for the 'vendorName' output field.");
    }

    @Test
    void planningPromptPreservesAuthoredDescriptionsVerbatim() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-authored-descriptions", 3);
        SimpleChatClient chatClient = new SimpleChatClient(plan("plan-tools-verbatim", PlanTaskStatus.PENDING), "done");

        String authoredDescription = "Reads invoice PDFs exactly as-authored. Keep JSON keys `invoice_id`, `vendor_name`, and \"line_items\".";
        ToolCallback tool = toolCallback("invoiceParser", authoredDescription);

        planningService.initializePlan(
                session,
                "check invoice",
                null,
                rootDefinition("duplicateInvoiceChecker"),
                chatClient,
                List.of(tool));

        String systemPrompt = chatClient.getSystemMessagesSeen().getFirst();
        assertThat(systemPrompt).contains("invoiceParser: " + authoredDescription);
    }

    @Test
    void retriesSingleToolOverusePlanWhenMultipleVisibleToolsExist() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-weak-plan-retry", 3);
        SequencePlanningChatClient chatClient = new SequencePlanningChatClient(
                weakSingleToolPlanJson(),
                correctedMultiToolPlanJson());

        ToolCallback invoiceParser = toolCallback("invoiceParser", "Extract invoice fields from source documents");
        ToolCallback expenseLookup = toolCallback("expenseLookup", "Look up related expenses for comparison");

        ExecutionPlan plan = planningService.initializePlan(
                        session,
                        "check invoice duplicates",
                        null,
                        rootDefinition("duplicateInvoiceChecker"),
                        chatClient,
                        List.of(invoiceParser, expenseLookup))
                .orElseThrow();

        assertThat(plan.tasks()).extracting(PlanTask::capabilityName)
                .containsExactly("invoiceParser", "expenseLookup", "expenseLookup");
        assertThat(chatClient.systemMessagesSeen()).hasSize(2);
        assertThat(chatClient.systemMessagesSeen().get(1)).contains("Previous plan was too weak");
        assertThat(chatClient.systemMessagesSeen().get(1)).contains("overuses 'invoiceParser'");
        assertThat(readRecords(session)).anyMatch(record -> record.recordType() == TraceRecordType.PLAN_VALIDATION_FAILED);
        assertThat(readRecords(session)).anyMatch(record -> record.recordType() == TraceRecordType.PLAN_RETRY_REQUESTED);
    }

    @Test
    void countsRejectedPlanningRetriesInSessionUsage() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        RecordingSessionUsageService usageService = new RecordingSessionUsageService();
        DefaultPlanningService planningService = new DefaultPlanningService(
                new DefaultPlanTaskLinker(),
                stateService,
                usageService,
                new ModelUsageExtractor());
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-weak-plan-retry-usage", 3);
        SequencePlanningChatClient chatClient = new SequencePlanningChatClient(
                weakSingleToolPlanJson(),
                correctedMultiToolPlanJson());

        ToolCallback invoiceParser = toolCallback("invoiceParser", "Extract invoice fields from source documents");
        ToolCallback expenseLookup = toolCallback("expenseLookup", "Look up related expenses for comparison");

        planningService.initializePlan(
                        session,
                        "check invoice duplicates",
                        null,
                        rootDefinition("duplicateInvoiceChecker"),
                        chatClient,
                        List.of(invoiceParser, expenseLookup))
                .orElseThrow();

        assertThat(usageService.lastSkillName).isEqualTo("duplicateInvoiceChecker");
        assertThat(usageService.snapshot(session).modelCalls()).isEqualTo(2);
        assertThat(usageService.snapshot(session).usageUnits()).isGreaterThan(0);
    }

    @Test
    void stopsRetryingAfterConfiguredPlanQualityRetryCap() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        RecordingSessionUsageService usageService = new RecordingSessionUsageService();
        DefaultPlanningService planningService = new DefaultPlanningService(
                new DefaultPlanTaskLinker(),
                stateService,
                usageService,
                new ModelUsageExtractor());
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-weak-plan-retry-cap", 3);
        SequencePlanningChatClient chatClient = new SequencePlanningChatClient(
                weakSingleToolPlanJson(),
                weakSingleToolPlanJson());

        ToolCallback invoiceParser = toolCallback("invoiceParser", "Extract invoice fields from source documents");
        ToolCallback expenseLookup = toolCallback("expenseLookup", "Look up related expenses for comparison");

        ExecutionPlan plan = planningService.initializePlan(
                        session,
                        "check invoice duplicates",
                        null,
                        rootDefinition("duplicateInvoiceChecker"),
                        chatClient,
                        List.of(invoiceParser, expenseLookup))
                .orElseThrow();

        assertThat(plan.tasks()).extracting(PlanTask::capabilityName)
                .containsExactly("invoiceParser", "invoiceParser", "invoiceParser");
        assertThat(chatClient.systemMessagesSeen()).hasSize(2);
        assertThat(chatClient.systemMessagesSeen().get(1)).contains("Previous plan was too weak");
        assertThat(usageService.snapshot(session).modelCalls()).isEqualTo(2);

        List<TraceRecord> records = readRecords(session);
        assertThat(records).filteredOn(record -> record.recordType() == TraceRecordType.PLAN_VALIDATION_FAILED)
                .hasSize(1);
        assertThat(records).filteredOn(record -> record.recordType() == TraceRecordType.PLAN_RETRY_REQUESTED)
                .hasSize(1);
        assertThat(records).filteredOn(record -> record.recordType() == TraceRecordType.PLAN_QUALITY_WARNING)
                .hasSize(2);
        assertThat(records).filteredOn(record -> record.recordType() == TraceRecordType.PLAN_QUALITY_WARNING)
                .filteredOn(record -> "ERROR".equals(record.metadata().get("severity")))
                .hasSize(1)
                .first()
                .satisfies(record -> {
                    assertThat(record.frameType()).isEqualTo(TraceFrameType.PLANNING);
                    assertThat(record.metadata()).containsEntry("retryCount", 1);
                    assertThat(record.metadata()).containsEntry("severity", "ERROR");
                });
    }

    @Test
    void warnsButAcceptsLegitimateRepeatedToolPlan() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-repeated-tool-warning", 3);
        SequencePlanningChatClient chatClient = new SequencePlanningChatClient(repeatedExtractionPlanJson());

        ToolCallback invoiceParser = toolCallback("invoiceParser", "Extract invoice fields from source documents");
        ToolCallback expenseLookup = toolCallback("expenseLookup", "Look up related expenses for comparison");

        ExecutionPlan plan = planningService.initializePlan(
                        session,
                        "extract invoice details",
                        null,
                        rootDefinition("duplicateInvoiceChecker"),
                        chatClient,
                        List.of(invoiceParser, expenseLookup))
                .orElseThrow();

        assertThat(plan.tasks()).extracting(PlanTask::capabilityName)
                .containsExactly("invoiceParser", "invoiceParser", "invoiceParser");
        assertThat(chatClient.systemMessagesSeen()).hasSize(1);
        List<TraceRecord> records = readRecords(session);
        assertThat(records).noneMatch(record -> record.recordType() == TraceRecordType.PLAN_VALIDATION_FAILED);
        assertThat(records).noneMatch(record -> record.recordType() == TraceRecordType.PLAN_RETRY_REQUESTED);
        assertThat(records).noneMatch(record -> record.recordType() == TraceRecordType.PLAN_QUALITY_WARNING);
    }

    @Test
    void ignoresSemanticMismatchHeuristicsWhenToolMetadataIsSparse() {
        PlanQualityValidator validator = new PlanQualityValidator();
        ExecutionPlan plan = new ExecutionPlan(
                "plan-sparse-metadata",
                "duplicateInvoiceChecker",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(new PlanTask(
                        "task-1",
                        "Final report",
                        PlanTaskStatus.PENDING,
                        "toolA",
                        "Summarize the result",
                        List.of(),
                        List.of("report"),
                        false,
                        "")));

        PlanQualityValidationResult validation = validator.validate(
                plan,
                List.of(
                        toolCallback("toolA", "Helper tool"),
                        toolCallback("toolB", "")));

        assertThat(validation.warnings()).isEmpty();
        assertThat(validation.errors()).isEmpty();
    }

    @Test
    void planningPromptShowsNoneWhenNoToolsProvided() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-no-tools", 3);
        SimpleChatClient chatClient = new SimpleChatClient(plan("plan-no-tools", PlanTaskStatus.PENDING), "done");

        planningService.initializePlan(session, "check invoice", null, rootDefinition("duplicateInvoiceChecker"), chatClient, List.of());

        String systemPrompt = chatClient.getSystemMessagesSeen().getFirst();
        assertThat(systemPrompt).contains("(none)");
    }

    @Test
    void planningPromptHardcodesTopLevelCapabilityName() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-cap-name", 3);
        SimpleChatClient chatClient = new SimpleChatClient(plan("plan-cap", PlanTaskStatus.PENDING), "done");

        planningService.initializePlan(session, "check invoice", null, rootDefinition("duplicateInvoiceChecker"), chatClient, List.of());

        String systemPrompt = chatClient.getSystemMessagesSeen().getFirst();
        assertThat(systemPrompt).contains("\"capabilityName\": \"duplicateInvoiceChecker\"");
    }

    @Test
    void rejectsContractBackedPlanWhenRequiredEvidenceRemainsUncoveredAfterRetries() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-evidence-fail", 3);
        SequencePlanningChatClient chatClient = new SequencePlanningChatClient(weakSingleToolPlanJson(), weakSingleToolPlanJson());

        assertThatThrownBy(() -> planningService.initializePlan(
                session,
                "check duplicate invoice",
                null,
                duplicateInvoiceDefinition(),
                chatClient,
                List.of(toolCallback("invoiceParser", "Extract invoice fields"), toolCallback("expenseLookup", "Look up matching expenses"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Evidence coverage validation failed");
        assertThat(session.getExecutionPlan()).isEmpty();
    }

    @Test
    void acceptsContractBackedPlanWhenTaskBindingsCoverAllRequiredEvidence() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-evidence-pass", 3);
        SequencePlanningChatClient chatClient = new SequencePlanningChatClient(correctedMultiToolPlanJson());

        ExecutionPlan plan = planningService.initializePlan(
                        session,
                        "check duplicate invoice",
                        null,
                        duplicateInvoiceDefinition(),
                        chatClient,
                        List.of(toolCallback("invoiceParser", "Extract invoice fields"), toolCallback("expenseLookup", "Look up matching expenses")))
                .orElseThrow();

        assertThat(plan.tasks()).extracting(PlanTask::capabilityName)
                .contains("invoiceParser", "expenseLookup");
    }

    private static ToolCallback toolCallback(String name, String description) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = ToolDefinition.builder().name(name).description(description).inputSchema("{}").build();
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    private static String weakSingleToolPlanJson() {
        return """
                {
                  "planId": "plan-weak",
                  "capabilityName": "duplicateInvoiceChecker",
                  "createdAt": "2026-03-15T12:00:00Z",
                  "status": "VALID",
                  "activeTaskId": null,
                  "tasks": [
                    {
                      "taskId": "t-1",
                      "title": "Parse invoice",
                      "status": "PENDING",
                      "capabilityName": "invoiceParser",
                      "intent": "Extract invoice fields",
                      "dependsOn": [],
                      "expectedOutputs": ["parsed invoice"],
                      "autoCompletable": false,
                      "note": ""
                    },
                    {
                      "taskId": "t-2",
                      "title": "Check duplicates",
                      "status": "PENDING",
                      "capabilityName": "invoiceParser",
                      "intent": "Check the invoice against prior expenses",
                      "dependsOn": ["t-1"],
                      "expectedOutputs": ["duplicate matches"],
                      "autoCompletable": false,
                      "note": ""
                    },
                    {
                      "taskId": "t-3",
                      "title": "Final report",
                      "status": "PENDING",
                      "capabilityName": "invoiceParser",
                      "intent": "Summarize the duplicate invoice result",
                      "dependsOn": ["t-2"],
                      "expectedOutputs": ["final report"],
                      "autoCompletable": false,
                      "note": ""
                    }
                  ]
                }
                """;
    }

    private static String correctedMultiToolPlanJson() {
        return """
                {
                  "planId": "plan-strong",
                  "capabilityName": "duplicateInvoiceChecker",
                  "createdAt": "2026-03-15T12:00:00Z",
                  "status": "VALID",
                  "activeTaskId": null,
                  "tasks": [
                    {
                      "taskId": "t-1",
                      "title": "Parse invoice",
                      "status": "PENDING",
                      "capabilityName": "invoiceParser",
                      "intent": "Extract invoice fields",
                      "dependsOn": [],
                      "expectedOutputs": ["parsed invoice"],
                      "autoCompletable": false,
                      "note": ""
                    },
                    {
                      "taskId": "t-2",
                      "title": "Look up matching expenses",
                      "status": "PENDING",
                      "capabilityName": "expenseLookup",
                      "intent": "Find matching expenses for the parsed invoice",
                      "dependsOn": ["t-1"],
                      "expectedOutputs": ["matching expenses"],
                      "autoCompletable": false,
                      "note": ""
                    },
                    {
                      "taskId": "t-3",
                      "title": "Compare evidence",
                      "status": "PENDING",
                      "capabilityName": "expenseLookup",
                      "intent": "Compare the parsed invoice against matching expenses",
                      "dependsOn": ["t-2"],
                      "expectedOutputs": ["duplicate decision"],
                      "autoCompletable": false,
                      "note": ""
                    }
                  ]
                }
                """;
    }

    private static String repeatedExtractionPlanJson() {
        return """
                {
                  "planId": "plan-repeat",
                  "capabilityName": "duplicateInvoiceChecker",
                  "createdAt": "2026-03-15T12:00:00Z",
                  "status": "VALID",
                  "activeTaskId": null,
                  "tasks": [
                    {
                      "taskId": "t-1",
                      "title": "Extract invoice header",
                      "status": "PENDING",
                      "capabilityName": "invoiceParser",
                      "intent": "Extract invoice number and vendor",
                      "dependsOn": [],
                      "expectedOutputs": ["invoice header"],
                      "autoCompletable": false,
                      "note": ""
                    },
                    {
                      "taskId": "t-2",
                      "title": "Extract invoice line items",
                      "status": "PENDING",
                      "capabilityName": "invoiceParser",
                      "intent": "Extract invoice line items",
                      "dependsOn": ["t-1"],
                      "expectedOutputs": ["line items"],
                      "autoCompletable": false,
                      "note": ""
                    },
                    {
                      "taskId": "t-3",
                      "title": "Extract tax details",
                      "status": "PENDING",
                      "capabilityName": "invoiceParser",
                      "intent": "Extract invoice tax details",
                      "dependsOn": ["t-2"],
                      "expectedOutputs": ["tax details"],
                      "autoCompletable": false,
                      "note": ""
                    }
                  ]
                }
                """;
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

    private static YamlSkillDefinition duplicateInvoiceDefinition() {
        YamlSkillManifest manifest = new YamlSkillManifest();
        manifest.setName("duplicateInvoiceChecker");
        manifest.setDescription("duplicateInvoiceChecker");
        manifest.setModel("gpt-5");
        YamlSkillManifest.OutputSchemaManifest schema = new YamlSkillManifest.OutputSchemaManifest();
        schema.setType("object");
        schema.setProperties(Map.of(
                "vendorName", scalarSchema("string"),
                "isDuplicate", scalarSchema("boolean")));
        schema.setRequired(List.of("vendorName", "isDuplicate"));
        schema.setAdditionalProperties(false);
        manifest.setOutputSchema(schema);
        manifest.setOutputSchemaMaxRetries(1);
        YamlSkillManifest.EvidenceContractManifest contract = new YamlSkillManifest.EvidenceContractManifest();
        contract.setClaims(Map.of(
                "vendorName", List.of("parsed_invoice"),
                "isDuplicate", List.of("parsed_invoice", "expense_match_search")));
        contract.setToolEvidence(Map.of(
                "invoiceParser", List.of("parsed_invoice"),
                "expenseLookup", List.of("expense_match_search")));
        manifest.setEvidenceContract(contract);
        return new YamlSkillDefinition(
                new org.springframework.core.io.ByteArrayResource(new byte[0]),
                manifest,
                EXECUTION_CONFIGURATION,
                EvidenceContract.fromManifest(contract, schema));
    }

    private static YamlSkillDefinition rootDefinition() {
        return rootDefinition("root.visible.skill");
    }

    private static YamlSkillDefinition rootDefinition(String name) {
        YamlSkillManifest manifest = new YamlSkillManifest();
        manifest.setName(name);
        manifest.setDescription(name);
        manifest.setModel("gpt-5");
        return new YamlSkillDefinition(
                new org.springframework.core.io.ByteArrayResource(new byte[0]),
                manifest,
                EXECUTION_CONFIGURATION);
    }

    private static YamlSkillManifest.OutputSchemaManifest scalarSchema(String type) {
        YamlSkillManifest.OutputSchemaManifest schema = new YamlSkillManifest.OutputSchemaManifest();
        schema.setType(type);
        return schema;
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

    private static final class SequencePlanningChatClient implements org.springframework.ai.chat.client.ChatClient {

        private final Deque<String> responses = new ArrayDeque<>();
        private final List<String> systemMessagesSeen = new ArrayList<>();

        private SequencePlanningChatClient(String... responses) {
            this.responses.addAll(List.of(responses));
        }

        private List<String> systemMessagesSeen() {
            return systemMessagesSeen;
        }

        @Override
        public ChatClientRequestSpec prompt() {
            return new SequenceRequestSpec();
        }

        @Override
        public ChatClientRequestSpec prompt(String content) {
            return prompt();
        }

        @Override
        public ChatClientRequestSpec prompt(org.springframework.ai.chat.prompt.Prompt prompt) {
            return prompt();
        }

        @Override
        public Builder mutate() {
            throw new UnsupportedOperationException();
        }

        private final class SequenceRequestSpec implements ChatClientRequestSpec {

            @Override
            public Builder mutate() {
                throw new UnsupportedOperationException();
            }

            @Override
            public ChatClientRequestSpec advisors(java.util.function.Consumer<AdvisorSpec> consumer) {
                return this;
            }

            @Override
            public ChatClientRequestSpec advisors(org.springframework.ai.chat.client.advisor.api.Advisor... advisors) {
                return this;
            }

            @Override
            public ChatClientRequestSpec advisors(List<org.springframework.ai.chat.client.advisor.api.Advisor> advisors) {
                return this;
            }

            @Override
            public ChatClientRequestSpec messages(org.springframework.ai.chat.messages.Message... messages) {
                return this;
            }

            @Override
            public ChatClientRequestSpec messages(List<org.springframework.ai.chat.messages.Message> messages) {
                return this;
            }

            @Override
            public <T extends org.springframework.ai.chat.prompt.ChatOptions> ChatClientRequestSpec options(T options) {
                return this;
            }

            @Override
            public ChatClientRequestSpec toolNames(String... toolNames) {
                return this;
            }

            @Override
            public ChatClientRequestSpec tools(Object... tools) {
                return this;
            }

            @Override
            public ChatClientRequestSpec toolCallbacks(ToolCallback... toolCallbacks) {
                return this;
            }

            @Override
            public ChatClientRequestSpec toolCallbacks(List<ToolCallback> toolCallbacks) {
                return this;
            }

            @Override
            public ChatClientRequestSpec toolCallbacks(org.springframework.ai.tool.ToolCallbackProvider... providers) {
                return this;
            }

            @Override
            public ChatClientRequestSpec toolContext(Map<String, Object> toolContext) {
                return this;
            }

            @Override
            public ChatClientRequestSpec system(String text) {
                systemMessagesSeen.add(text);
                return this;
            }

            @Override
            public ChatClientRequestSpec system(org.springframework.core.io.Resource resource, java.nio.charset.Charset charset) {
                return this;
            }

            @Override
            public ChatClientRequestSpec system(org.springframework.core.io.Resource resource) {
                return this;
            }

            @Override
            public ChatClientRequestSpec system(java.util.function.Consumer<PromptSystemSpec> consumer) {
                return this;
            }

            @Override
            public ChatClientRequestSpec user(String text) {
                return this;
            }

            @Override
            public ChatClientRequestSpec user(org.springframework.core.io.Resource resource, java.nio.charset.Charset charset) {
                return this;
            }

            @Override
            public ChatClientRequestSpec user(org.springframework.core.io.Resource resource) {
                return this;
            }

            @Override
            public ChatClientRequestSpec user(java.util.function.Consumer<PromptUserSpec> consumer) {
                return this;
            }

            @Override
            public ChatClientRequestSpec templateRenderer(org.springframework.ai.template.TemplateRenderer renderer) {
                return this;
            }

            @Override
            public CallResponseSpec call() {
                String next = responses.pollFirst();
                if (next == null) {
                    throw new IllegalStateException("No more queued chat responses");
                }
                return new ResponseSpec(next);
            }

            @Override
            public StreamResponseSpec stream() {
                throw new UnsupportedOperationException();
            }
        }

        private record ResponseSpec(String content) implements CallResponseSpec {

            @Override
            public <T> T entity(org.springframework.core.ParameterizedTypeReference<T> type) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T entity(org.springframework.ai.converter.StructuredOutputConverter<T> converter) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T entity(Class<T> type) {
                throw new UnsupportedOperationException();
            }

            @Override
            public org.springframework.ai.chat.client.ChatClientResponse chatClientResponse() {
                throw new UnsupportedOperationException();
            }

            @Override
            public org.springframework.ai.chat.model.ChatResponse chatResponse() {
                throw new UnsupportedOperationException();
            }

            @Override
            public String content() {
                return content;
            }

            @Override
            public <T> org.springframework.ai.chat.client.ResponseEntity<org.springframework.ai.chat.model.ChatResponse, T> responseEntity(Class<T> type) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> org.springframework.ai.chat.client.ResponseEntity<org.springframework.ai.chat.model.ChatResponse, T> responseEntity(
                    org.springframework.core.ParameterizedTypeReference<T> type) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> org.springframework.ai.chat.client.ResponseEntity<org.springframework.ai.chat.model.ChatResponse, T> responseEntity(
                    org.springframework.ai.converter.StructuredOutputConverter<T> converter) {
                throw new UnsupportedOperationException();
            }
        }
    }
}
