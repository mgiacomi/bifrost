package com.lokiscale.bifrost.runtime.step;

import com.lokiscale.bifrost.autoconfigure.AiProvider;
import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.DefaultPlanTaskLinker;
import com.lokiscale.bifrost.core.CapabilityExecutionRouter;
import com.lokiscale.bifrost.core.CapabilityKind;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.CapabilityToolDescriptor;
import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.ModelPreference;
import com.lokiscale.bifrost.core.PlanStatus;
import com.lokiscale.bifrost.core.PlanTask;
import com.lokiscale.bifrost.core.PlanTaskStatus;
import com.lokiscale.bifrost.core.SkillExecutionDescriptor;
import com.lokiscale.bifrost.core.TraceRecord;
import com.lokiscale.bifrost.core.TraceRecordType;
import com.lokiscale.bifrost.linter.LinterOutcomeStatus;
import com.lokiscale.bifrost.outputschema.OutputSchemaOutcomeStatus;
import com.lokiscale.bifrost.runtime.planning.DefaultPlanningService;
import com.lokiscale.bifrost.runtime.planning.PlanningService;
import com.lokiscale.bifrost.runtime.state.DefaultExecutionStateService;
import com.lokiscale.bifrost.runtime.tool.DefaultToolCallbackFactory;
import com.lokiscale.bifrost.runtime.usage.ModelUsageExtractor;
import com.lokiscale.bifrost.runtime.usage.NoOpSessionUsageService;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import com.lokiscale.bifrost.skill.YamlSkillCatalog;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import com.lokiscale.bifrost.skill.YamlSkillManifest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ByteArrayResource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StepLoopMissionExecutionEngineTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC);
    private static final EffectiveSkillExecutionConfiguration EXECUTION_CONFIGURATION =
            new EffectiveSkillExecutionConfiguration("gpt-5", AiProvider.OPENAI, "openai/gpt-5", "medium");

    @Test
    void executesNewlyUnblockedTasksAcrossMultipleSteps() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        ExecutionPlan plan = twoTaskPlan();
        PlanningService planningService = new InitializingPlanningService(stateService, plan);
        SequenceChatClient chatClient = new SequenceChatClient(
                """
                {"stepAction":"CALL_TOOL","taskId":"t-1","toolName":"invoiceParser","toolArguments":{"rawText":"INV-1"}}
                """,
                """
                {"stepAction":"CALL_TOOL","taskId":"t-2","toolName":"expenseLookup","toolArguments":{"invoiceId":"INV-1"}}
                """,
                """
                {"stepAction":"FINAL_RESPONSE","finalResponse":"Mission complete"}
                """);

        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("step-loop-1", 3);
        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            StepLoopMissionExecutionEngine engine = engine(stateService, planningService, missionExecutor);

            String response = engine.executeMission(
                    session,
                    "root.visible.skill",
                    "Check duplicate invoices",
                    EXECUTION_CONFIGURATION,
                    chatClient,
                    List.of(tool("invoiceParser", "{\"vendor\":\"Acme\"}"), tool("expenseLookup", "{\"matches\":[]}")),
                    true,
                    null);

            assertThat(response).isEqualTo("Mission complete");
        }

        ExecutionPlan finalPlan = stateService.currentPlan(session).orElseThrow();
        assertThat(finalPlan.tasks()).extracting(PlanTask::status)
                .containsExactly(PlanTaskStatus.COMPLETED, PlanTaskStatus.COMPLETED);
        assertThat(chatClient.systemMessagesSeen()).hasSize(3);
        assertThat(chatClient.systemMessagesSeen().get(1)).contains("READY TASKS", "t-2");
        assertThat(chatClient.systemMessagesSeen().get(1)).doesNotContain("t-2: Look up expenses (waiting on: t-1)");
    }

    @Test
    void retriesInvalidActionBeforeProceeding() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        ExecutionPlan plan = singleTaskPlan();
        PlanningService planningService = new InitializingPlanningService(stateService, plan);
        SequenceChatClient chatClient = new SequenceChatClient(
                """
                {"stepAction":"FINAL_RESPONSE","finalResponse":"Too early"}
                """,
                """
                {"stepAction":"CALL_TOOL","taskId":"t-1","toolName":"invoiceParser","toolArguments":{"rawText":"INV-1"}}
                """,
                """
                {"stepAction":"FINAL_RESPONSE","finalResponse":"Finished"}
                """);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("step-loop-retry", 3);

        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            StepLoopMissionExecutionEngine engine = engine(stateService, planningService, missionExecutor);

            String response = engine.executeMission(
                    session,
                    "root.visible.skill",
                    "Check duplicate invoices",
                    EXECUTION_CONFIGURATION,
                    chatClient,
                    List.of(tool("invoiceParser", "{\"vendor\":\"Acme\"}")),
                    true,
                    null);

            assertThat(response).isEqualTo("Finished");
        }

        List<TraceRecord> records = readRecords(session);
        assertThat(records).anyMatch(record -> record.recordType() == TraceRecordType.STEP_ACTION_REJECTED);
        assertThat(chatClient.systemMessagesSeen()).hasSize(3);
        assertThat(chatClient.systemMessagesSeen().get(1)).contains("YOUR PREVIOUS ACTION WAS INVALID");
    }

    @Test
    void retriesMissingActionFieldBeforeProceeding() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        ExecutionPlan plan = singleTaskPlan();
        PlanningService planningService = new InitializingPlanningService(stateService, plan);
        SequenceChatClient chatClient = new SequenceChatClient(
                """
                {"taskId":"t-1","toolName":"invoiceParser","toolArguments":{"rawText":"INV-1"}}
                """,
                """
                {"stepAction":"CALL_TOOL","taskId":"t-1","toolName":"invoiceParser","toolArguments":{"rawText":"INV-1"}}
                """,
                """
                {"stepAction":"FINAL_RESPONSE","finalResponse":"Finished"}
                """);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("step-loop-missing-action", 3);

        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            StepLoopMissionExecutionEngine engine = engine(stateService, planningService, missionExecutor);

            String response = engine.executeMission(
                    session,
                    "root.visible.skill",
                    "Check duplicate invoices",
                    EXECUTION_CONFIGURATION,
                    chatClient,
                    List.of(tool("invoiceParser", "{\"vendor\":\"Acme\"}")),
                    true,
                    null);

            assertThat(response).isEqualTo("Finished");
        }

        List<TraceRecord> records = readRecords(session);
        assertThat(records).anyMatch(record -> record.recordType() == TraceRecordType.STEP_ACTION_REJECTED
                && String.valueOf(record.metadata().get("reason")).contains("Step action type"));
        assertThat(chatClient.systemMessagesSeen()).hasSize(3);
    }

    @Test
    void surfacesToolFailureAsExplicitTerminalFailure() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        ExecutionPlan plan = singleTaskPlan();
        PlanningService planningService = new InitializingPlanningService(stateService, plan);
        SequenceChatClient chatClient = new SequenceChatClient(
                """
                {"stepAction":"CALL_TOOL","taskId":"t-1","toolName":"invoiceParser","toolArguments":{"rawText":"INV-1"}}
                """);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("step-loop-failure", 3);

        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            StepLoopMissionExecutionEngine engine = engine(stateService, planningService, missionExecutor);

            assertThatThrownBy(() -> engine.executeMission(
                    session,
                    "root.visible.skill",
                    "Check duplicate invoices",
                    EXECUTION_CONFIGURATION,
                    chatClient,
                    List.of(failingTool("invoiceParser")),
                    true,
                    null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tool 'invoiceParser' failed")
                    .hasMessageNotContaining("deadlock");
        }

        List<TraceRecord> records = readRecords(session);
        assertThat(records).anyMatch(record -> record.recordType() == TraceRecordType.STEP_COMPLETED
                && "failed".equals(record.metadata().get("status")));
        assertThat(records).noneMatch(record -> record.recordType() == TraceRecordType.ERROR_RECORDED
                && String.valueOf(record.data()).contains("deadlock"));
    }

    @Test
    void retriesFinalResponseUntilOutputSchemaPasses() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        ExecutionPlan plan = singleTaskPlan().updateTask("t-1", task -> task.complete("parsed"));
        PlanningService planningService = new InitializingPlanningService(stateService, plan);
        SequenceChatClient chatClient = new SequenceChatClient(
                """
                {"stepAction":"FINAL_RESPONSE","finalResponse":"plain text"}
                """,
                """
                {"stepAction":"FINAL_RESPONSE","finalResponse":"{\\\"result\\\":\\\"Finished\\\"}"}
                """);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("step-loop-schema", 3);

        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            StepLoopMissionExecutionEngine engine = engine(
                    stateService,
                    planningService,
                    missionExecutor,
                    definitionWithOutputSchema());

            String response = engine.executeMission(
                    session,
                    "root.visible.skill",
                    "Check duplicate invoices",
                    EXECUTION_CONFIGURATION,
                    chatClient,
                    List.of(tool("invoiceParser", "{\"vendor\":\"Acme\"}")),
                    true,
                    null);

            assertThat(response).isEqualTo("{\"result\":\"Finished\"}");
        }

        assertThat(session.getLastOutputSchemaOutcome()).isPresent();
        assertThat(session.getLastOutputSchemaOutcome().orElseThrow().status()).isEqualTo(OutputSchemaOutcomeStatus.PASSED);
        assertThat(chatClient.systemMessagesSeen()).hasSize(2);
        assertThat(chatClient.systemMessagesSeen().get(1)).contains("Final response violates output_schema");
    }

    @Test
    void finalOnlyModeWrapsBarePayloadAsFinalResponseEnvelope() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        ExecutionPlan plan = singleTaskPlan().updateTask("t-1", task -> task.complete("parsed"));
        PlanningService planningService = new InitializingPlanningService(stateService, plan);
        SequenceChatClient chatClient = new SequenceChatClient(
                """
                {
                  "result": "Finished"
                }
                """);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("step-loop-bare-final-payload", 3);

        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            StepLoopMissionExecutionEngine engine = engine(
                    stateService,
                    planningService,
                    missionExecutor,
                    definitionWithOutputSchema());

            String response = engine.executeMission(
                    session,
                    "root.visible.skill",
                    "Check duplicate invoices",
                    EXECUTION_CONFIGURATION,
                    chatClient,
                    List.of(tool("invoiceParser", "{\"vendor\":\"Acme\"}")),
                    true,
                    null);

            assertThat(response).isEqualTo("{\"result\":\"Finished\"}");
        }

        assertThat(readRecords(session)).anyMatch(record -> record.recordType() == TraceRecordType.STEP_ACTION_VALIDATED
                && "FINAL_RESPONSE".equals(String.valueOf(record.metadata().get("stepAction"))));
    }

    @Test
    void completedPlanRepairsInvalidCallToolByConstrainingPromptToFinalResponse() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        ExecutionPlan plan = singleTaskPlan().updateTask("t-1", task -> task.complete("parsed"));
        PlanningService planningService = new InitializingPlanningService(stateService, plan);
        SequenceChatClient chatClient = new SequenceChatClient(
                """
                {"stepAction":"CALL_TOOL","taskId":"t-1","toolName":"invoiceParser","toolArguments":{"rawText":"INV-1"}}
                """,
                """
                {"stepAction":"FINAL_RESPONSE","finalResponse":"Finished"}
                """);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("step-loop-complete-plan-repair", 3);

        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            StepLoopMissionExecutionEngine engine = engine(stateService, planningService, missionExecutor);

            String response = engine.executeMission(
                    session,
                    "root.visible.skill",
                    "Check duplicate invoices",
                    EXECUTION_CONFIGURATION,
                    chatClient,
                    List.of(tool("invoiceParser", "{\"vendor\":\"Acme\"}")),
                    true,
                    null);

            assertThat(response).isEqualTo("Finished");
        }

        assertThat(chatClient.systemMessagesSeen()).hasSize(2);
        assertThat(chatClient.systemMessagesSeen().get(0)).contains("All required plan tasks are already COMPLETE");
        assertThat(chatClient.systemMessagesSeen().get(1))
                .contains("All required plan tasks are already completed. Return FINAL_RESPONSE instead of CALL_TOOL.")
                .contains("You must return a FINAL_RESPONSE action");
        assertThat(readRecords(session)).anyMatch(record -> record.recordType() == TraceRecordType.STEP_ACTION_REJECTED
                && String.valueOf(record.metadata().get("reason")).contains("Return FINAL_RESPONSE instead of CALL_TOOL"));
    }

    @Test
    void retriesWhenConcreteToolSchemaRequiresMissingArguments() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        ExecutionPlan plan = singleTaskPlan();
        PlanningService planningService = new InitializingPlanningService(stateService, plan);
        SequenceChatClient chatClient = new SequenceChatClient(
                """
                {"stepAction":"CALL_TOOL","taskId":"t-1","toolName":"invoiceParser","toolArguments":{}}
                """,
                """
                {"stepAction":"CALL_TOOL","taskId":"t-1","toolName":"invoiceParser","toolArguments":{"rawText":"INV-1"}}
                """,
                """
                {"stepAction":"FINAL_RESPONSE","finalResponse":"Finished"}
                """);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("step-loop-schema-aware-tool-args", 3);

        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            StepLoopMissionExecutionEngine engine = engine(stateService, planningService, missionExecutor);

            String response = engine.executeMission(
                    session,
                    "root.visible.skill",
                    "Check duplicate invoices",
                    EXECUTION_CONFIGURATION,
                    chatClient,
                    List.of(toolWithSchema("invoiceParser", """
                            {
                              "type": "object",
                              "properties": {
                                "rawText": { "type": "string" }
                              },
                              "required": ["rawText"],
                              "additionalProperties": false
                            }
                            """, "{\"vendor\":\"Acme\"}")),
                    true,
                    null);

            assertThat(response).isEqualTo("Finished");
        }

        assertThat(chatClient.systemMessagesSeen()).hasSize(3);
        assertThat(chatClient.systemMessagesSeen().get(1))
                .contains("requires argument(s) [rawText]")
                .contains("YOUR PREVIOUS ACTION WAS INVALID");
    }

    @Test
    void retriesWhenModelUsesParentSkillNameInsteadOfBoundReadyTaskTool() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        ExecutionPlan plan = twoTaskPlan()
                .updateTask("t-1", task -> task.complete("parsed"));
        PlanningService planningService = new InitializingPlanningService(stateService, plan);
        SequenceChatClient chatClient = new SequenceChatClient(
                """
                {"stepAction":"CALL_TOOL","taskId":"t-2","toolName":"duplicateInvoiceChecker","toolArguments":{"payload":"INV-1"}}
                """,
                """
                {"stepAction":"CALL_TOOL","taskId":"t-2","toolName":"expenseLookup","toolArguments":{"invoiceId":"INV-1"}}
                """,
                """
                {"stepAction":"FINAL_RESPONSE","finalResponse":"Finished"}
                """);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("step-loop-parent-skill-tool-confusion", 3);

        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            StepLoopMissionExecutionEngine engine = engine(stateService, planningService, missionExecutor);

            String response = engine.executeMission(
                    session,
                    "duplicateInvoiceChecker",
                    "Check duplicate invoices",
                    EXECUTION_CONFIGURATION,
                    chatClient,
                    List.of(tool("invoiceParser", "{\"vendor\":\"Acme\"}"), tool("expenseLookup", "{\"matches\":[]}")),
                    true,
                    null);

            assertThat(response).isEqualTo("Finished");
        }

        assertThat(chatClient.systemMessagesSeen()).hasSize(3);
        assertThat(chatClient.systemMessagesSeen().get(0))
                .contains("CURRENT EXECUTABLE TASK")
                .contains("The only valid toolName for this step is expenseLookup.")
                .doesNotContain("Skill: duplicateInvoiceChecker");
        assertThat(chatClient.userMessagesSeen()).isNotEmpty();
        assertThat(chatClient.userMessagesSeen().get(0)).doesNotContain("duplicateInvoiceChecker");
        assertThat(chatClient.systemMessagesSeen().get(1))
                .contains("Tool 'duplicateInvoiceChecker' is not in the available tools")
                .contains("The only valid toolName for this step is expenseLookup.")
                .contains("Do not call the parent mission skill");
        assertThat(readRecords(session)).anyMatch(record -> record.recordType() == TraceRecordType.STEP_ACTION_REJECTED
                && String.valueOf(record.metadata().get("reason"))
                .contains("Tool 'duplicateInvoiceChecker' is not in the available tools"));
    }

    @Test
    void retriesFinalResponseUntilRegexLinterPasses() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        ExecutionPlan plan = singleTaskPlan().updateTask("t-1", task -> task.complete("parsed"));
        PlanningService planningService = new InitializingPlanningService(stateService, plan);
        SequenceChatClient chatClient = new SequenceChatClient(
                """
                {"stepAction":"FINAL_RESPONSE","finalResponse":"Finished"}
                """,
                """
                {"stepAction":"FINAL_RESPONSE","finalResponse":"APPROVED: Finished"}
                """);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("step-loop-linter", 3);

        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            StepLoopMissionExecutionEngine engine = engine(
                    stateService,
                    planningService,
                    missionExecutor,
                    definitionWithRegexLinter());

            String response = engine.executeMission(
                    session,
                    "root.visible.skill",
                    "Check duplicate invoices",
                    EXECUTION_CONFIGURATION,
                    chatClient,
                    List.of(tool("invoiceParser", "{\"vendor\":\"Acme\"}")),
                    true,
                    null);

            assertThat(response).isEqualTo("APPROVED: Finished");
        }

        assertThat(session.getLastLinterOutcome()).isPresent();
        assertThat(session.getLastLinterOutcome().orElseThrow().status()).isEqualTo(LinterOutcomeStatus.PASSED);
        assertThat(chatClient.systemMessagesSeen()).hasSize(2);
        assertThat(chatClient.systemMessagesSeen().get(1)).contains("Must start with APPROVED:");
    }

    @Test
    void honorsConfiguredRegexLinterRetriesAcrossMultipleFinalResponses() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        ExecutionPlan plan = singleTaskPlan().updateTask("t-1", task -> task.complete("parsed"));
        PlanningService planningService = new InitializingPlanningService(stateService, plan);
        SequenceChatClient chatClient = new SequenceChatClient(
                """
                {"stepAction":"FINAL_RESPONSE","finalResponse":"Finished"}
                """,
                """
                {"stepAction":"FINAL_RESPONSE","finalResponse":"Still wrong"}
                """,
                """
                {"stepAction":"FINAL_RESPONSE","finalResponse":"APPROVED: Finished"}
                """);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("step-loop-linter-retries", 3);

        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            StepLoopMissionExecutionEngine engine = engine(
                    stateService,
                    planningService,
                    missionExecutor,
                    definitionWithRegexLinter(2));

            String response = engine.executeMission(
                    session,
                    "root.visible.skill",
                    "Check duplicate invoices",
                    EXECUTION_CONFIGURATION,
                    chatClient,
                    List.of(tool("invoiceParser", "{\"vendor\":\"Acme\"}")),
                    true,
                    null);

            assertThat(response).isEqualTo("APPROVED: Finished");
        }

        assertThat(session.getLastLinterOutcome()).isPresent();
        assertThat(session.getLastLinterOutcome().orElseThrow().attempt()).isEqualTo(3);
        assertThat(session.getLastLinterOutcome().orElseThrow().maxRetries()).isEqualTo(2);
        assertThat(chatClient.systemMessagesSeen()).hasSize(3);
    }

    @Test
    void rejectsAutoCompletablePlansInStepLoop() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        ExecutionPlan plan = new ExecutionPlan(
                "plan-1",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                PlanStatus.VALID,
                null,
                List.of(new PlanTask("t-1", "Summarize results", PlanTaskStatus.PENDING,
                        null, "Summarize mission findings", List.of(), List.of("summary"), true, null)));
        PlanningService planningService = new InitializingPlanningService(stateService, plan);
        SequenceChatClient chatClient = new SequenceChatClient();
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("step-loop-auto", 3);

        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            StepLoopMissionExecutionEngine engine = engine(stateService, planningService, missionExecutor);

            assertThatThrownBy(() -> engine.executeMission(
                    session,
                    "root.visible.skill",
                    "Check duplicate invoices",
                    EXECUTION_CONFIGURATION,
                    chatClient,
                    List.of(),
                    true,
                    null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("autoCompletable");
        }

        assertThat(readRecords(session)).anyMatch(record -> record.recordType() == TraceRecordType.ERROR_RECORDED
                && String.valueOf(record.data()).contains("autoCompletable"));
    }

    @Test
    void rejectsPlansWithMissingDependenciesInStepLoop() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        ExecutionPlan plan = new ExecutionPlan(
                "plan-missing-dependency",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                PlanStatus.VALID,
                null,
                List.of(new PlanTask("t-2", "Look up expenses", PlanTaskStatus.PENDING,
                        "expenseLookup", "Find matching expenses", List.of("missing-task"),
                        List.of("expenses"), false, null)));
        PlanningService planningService = new InitializingPlanningService(stateService, plan);
        SequenceChatClient chatClient = new SequenceChatClient();
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("step-loop-missing-dependency", 3);

        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            StepLoopMissionExecutionEngine engine = engine(stateService, planningService, missionExecutor);

            assertThatThrownBy(() -> engine.executeMission(
                    session,
                    "root.visible.skill",
                    "Check duplicate invoices",
                    EXECUTION_CONFIGURATION,
                    chatClient,
                    List.of(tool("expenseLookup", "{\"matches\":[]}")),
                    true,
                    null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Missing task dependencies")
                    .hasMessageContaining("t-2->missing-task");
        }

        assertThat(readRecords(session)).anyMatch(record -> record.recordType() == TraceRecordType.ERROR_RECORDED
                && String.valueOf(record.data()).contains("Missing task dependencies"));
    }

    @Test
    void rejectsPlansWithDuplicateTaskIdsInStepLoop() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        ExecutionPlan plan = new ExecutionPlan(
                "plan-duplicate-task-id",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                PlanStatus.VALID,
                null,
                List.of(
                        new PlanTask("t-1", "Parse invoice A", PlanTaskStatus.PENDING,
                                "invoiceParser", "Parse invoice A", List.of(), List.of("parsedA"), false, null),
                        new PlanTask("t-1", "Parse invoice B", PlanTaskStatus.PENDING,
                                "invoiceParser", "Parse invoice B", List.of(), List.of("parsedB"), false, null)));
        PlanningService planningService = new InitializingPlanningService(stateService, plan);
        SequenceChatClient chatClient = new SequenceChatClient();
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("step-loop-duplicate-task-id", 3);

        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            StepLoopMissionExecutionEngine engine = engine(stateService, planningService, missionExecutor);

            assertThatThrownBy(() -> engine.executeMission(
                    session,
                    "root.visible.skill",
                    "Check duplicate invoices",
                    EXECUTION_CONFIGURATION,
                    chatClient,
                    List.of(tool("invoiceParser", "{\"vendor\":\"Acme\"}")),
                    true,
                    null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Task IDs must be unique")
                    .hasMessageContaining("t-1");
        }

        assertThat(readRecords(session)).anyMatch(record -> record.recordType() == TraceRecordType.ERROR_RECORDED
                && String.valueOf(record.data()).contains("Task IDs must be unique"));
    }

    @Test
    void recordsTerminalFailureWhenInvalidActionRetriesAreExhausted() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        ExecutionPlan plan = singleTaskPlan();
        PlanningService planningService = new InitializingPlanningService(stateService, plan);
        SequenceChatClient chatClient = new SequenceChatClient(
                """
                {"stepAction":"FINAL_RESPONSE","finalResponse":"Too early"}
                """,
                """
                {"stepAction":"FINAL_RESPONSE","finalResponse":"Still too early"}
                """);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("step-loop-invalid-exhausted", 3);

        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            StepLoopMissionExecutionEngine engine = engine(stateService, planningService, missionExecutor);

            assertThatThrownBy(() -> engine.executeMission(
                    session,
                    "root.visible.skill",
                    "Check duplicate invoices",
                    EXECUTION_CONFIGURATION,
                    chatClient,
                    List.of(tool("invoiceParser", "{\"vendor\":\"Acme\"}")),
                    true,
                    null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Step action validation exhausted");
        }

        assertThat(readRecords(session)).anyMatch(record -> record.recordType() == TraceRecordType.ERROR_RECORDED
                && String.valueOf(record.data()).contains("Step action validation exhausted"));
    }

    @Test
    void executesBoundToolCallbacksWithoutRelinkingOrDuplicatePlanUpdates() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        ExecutionPlan plan = new ExecutionPlan(
                "plan-1",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                PlanStatus.VALID,
                null,
                List.of(
                        new PlanTask("t-1", "Parse first invoice", PlanTaskStatus.PENDING,
                                "invoiceParser", "Parse invoice A", List.of(), List.of("parsedA"), false, null),
                        new PlanTask("t-2", "Parse second invoice", PlanTaskStatus.PENDING,
                                "invoiceParser", "Parse invoice B", List.of(), List.of("parsedB"), false, null)));
        PlanningService planningService = new InitializingPlanningService(stateService, plan);
        SequenceChatClient chatClient = new SequenceChatClient(
                """
                {"stepAction":"CALL_TOOL","taskId":"t-2","toolName":"invoiceParser","toolArguments":{"rawText":"INV-2"}}
                """,
                """
                {"stepAction":"CALL_TOOL","taskId":"t-1","toolName":"invoiceParser","toolArguments":{"rawText":"INV-1"}}
                """,
                """
                {"stepAction":"FINAL_RESPONSE","finalResponse":"Mission complete"}
                """);
        AtomicInteger routerCalls = new AtomicInteger();
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("step-loop-real-tool", 3);
        ToolCallback realWrappedTool = realToolCallback(stateService, planningService, routerCalls, session);

        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            StepLoopMissionExecutionEngine engine = engine(stateService, planningService, missionExecutor);

            String response = engine.executeMission(
                    session,
                    "root.visible.skill",
                    "Check duplicate invoices",
                    EXECUTION_CONFIGURATION,
                    chatClient,
                    List.of(realWrappedTool),
                    true,
                    null);

            assertThat(response).isEqualTo("Mission complete");
        }

        assertThat(routerCalls.get()).isEqualTo(2);
        ExecutionPlan finalPlan = stateService.currentPlan(session).orElseThrow();
        assertThat(finalPlan.findTask("t-1").orElseThrow().status()).isEqualTo(PlanTaskStatus.COMPLETED);
        assertThat(finalPlan.findTask("t-2").orElseThrow().status()).isEqualTo(PlanTaskStatus.COMPLETED);

        long linkedTask2Calls = readRecords(session).stream()
                .filter(record -> record.recordType() == TraceRecordType.TOOL_CALL_STARTED)
                .filter(record -> "t-2".equals(record.metadata().get("linkedTaskId")))
                .count();
        assertThat(linkedTask2Calls).isEqualTo(1);
    }

    @Test
    void rejectsPlansWithTasksMissingCapabilityBindings() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        ExecutionPlan plan = new ExecutionPlan(
                "plan-unbound-task",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                PlanStatus.VALID,
                null,
                List.of(new PlanTask("t-1", "Parse invoice", PlanTaskStatus.PENDING,
                        null, "Extract invoice data", List.of(), List.of("parsedInvoice"), false, null)));
        PlanningService planningService = new InitializingPlanningService(stateService, plan);
        SequenceChatClient chatClient = new SequenceChatClient();
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("step-loop-unbound-capability", 3);

        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            StepLoopMissionExecutionEngine engine = engine(stateService, planningService, missionExecutor);

            assertThatThrownBy(() -> engine.executeMission(
                    session,
                    "root.visible.skill",
                    "Check duplicate invoices",
                    EXECUTION_CONFIGURATION,
                    chatClient,
                    List.of(tool("invoiceParser", "{\"vendor\":\"Acme\"}")),
                    true,
                    null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Tasks missing capability bindings")
                    .hasMessageContaining("t-1");
        }
    }

    @Test
    void rejectsNonPositiveMaxStepsBeforeLoopStarts() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        ExecutionPlan plan = singleTaskPlan();
        PlanningService planningService = new InitializingPlanningService(stateService, plan);
        SequenceChatClient chatClient = new SequenceChatClient();
        YamlSkillManifest manifest = new YamlSkillManifest();
        manifest.setName("root.visible.skill");
        manifest.setDescription("root.visible.skill");
        manifest.setModel("gpt-5");
        manifest.setPlanningMode(true);
        manifest.setMaxSteps(0);
        YamlSkillDefinition invalidDefinition = new YamlSkillDefinition(
                new ByteArrayResource(new byte[0]), manifest, EXECUTION_CONFIGURATION);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("step-loop-max-steps-zero", 3);

        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            StepLoopMissionExecutionEngine engine = engine(
                    stateService,
                    planningService,
                    missionExecutor,
                    invalidDefinition);

            assertThatThrownBy(() -> engine.executeMission(
                    session,
                    "root.visible.skill",
                    "Check duplicate invoices",
                    EXECUTION_CONFIGURATION,
                    chatClient,
                    List.of(tool("invoiceParser", "{\"vendor\":\"Acme\"}")),
                    true,
                    null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("max_steps > 0")
                    .hasMessageContaining("was 0");
        }
    }

    private static StepLoopMissionExecutionEngine engine(DefaultExecutionStateService stateService,
                                                         PlanningService planningService,
                                                         ExecutorService missionExecutor) {
        return engine(stateService, planningService, missionExecutor, definition());
    }

    private static StepLoopMissionExecutionEngine engine(DefaultExecutionStateService stateService,
                                                         PlanningService planningService,
                                                         ExecutorService missionExecutor,
                                                         YamlSkillDefinition definition) {
        return new StepLoopMissionExecutionEngine(
                planningService,
                stateService,
                mock(com.lokiscale.bifrost.core.CapabilityRegistry.class),
                new StubYamlSkillCatalog(definition),
                Duration.ofSeconds(5),
                missionExecutor,
                new NoOpSessionUsageService(),
                new ModelUsageExtractor());
    }

    private static YamlSkillDefinition definition() {
        YamlSkillManifest manifest = new YamlSkillManifest();
        manifest.setName("root.visible.skill");
        manifest.setDescription("root.visible.skill");
        manifest.setModel("gpt-5");
        manifest.setPlanningMode(true);
        return new YamlSkillDefinition(new ByteArrayResource(new byte[0]), manifest, EXECUTION_CONFIGURATION);
    }

    private static YamlSkillDefinition definitionWithOutputSchema() {
        YamlSkillManifest manifest = new YamlSkillManifest();
        manifest.setName("root.visible.skill");
        manifest.setDescription("root.visible.skill");
        manifest.setModel("gpt-5");
        manifest.setPlanningMode(true);
        manifest.setOutputSchemaMaxRetries(1);
        YamlSkillManifest.OutputSchemaManifest schema = new YamlSkillManifest.OutputSchemaManifest();
        schema.setType("object");
        schema.setAdditionalProperties(false);
        YamlSkillManifest.OutputSchemaManifest resultField = new YamlSkillManifest.OutputSchemaManifest();
        resultField.setType("string");
        schema.setProperties(Map.of("result", resultField));
        schema.setRequired(List.of("result"));
        manifest.setOutputSchema(schema);
        return new YamlSkillDefinition(new ByteArrayResource(new byte[0]), manifest, EXECUTION_CONFIGURATION);
    }

    private static YamlSkillDefinition definitionWithRegexLinter() {
        return definitionWithRegexLinter(1);
    }

    private static YamlSkillDefinition definitionWithRegexLinter(int maxRetries) {
        YamlSkillManifest manifest = new YamlSkillManifest();
        manifest.setName("root.visible.skill");
        manifest.setDescription("root.visible.skill");
        manifest.setModel("gpt-5");
        manifest.setPlanningMode(true);
        YamlSkillManifest.LinterManifest linter = new YamlSkillManifest.LinterManifest();
        linter.setType("regex");
        linter.setMaxRetries(maxRetries);
        YamlSkillManifest.RegexManifest regex = new YamlSkillManifest.RegexManifest();
        regex.setPattern("^APPROVED:.*$");
        regex.setMessage("Must start with APPROVED:");
        linter.setRegex(regex);
        manifest.setLinter(linter);
        return new YamlSkillDefinition(new ByteArrayResource(new byte[0]), manifest, EXECUTION_CONFIGURATION);
    }

    private static ExecutionPlan singleTaskPlan() {
        return new ExecutionPlan(
                "plan-1",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                PlanStatus.VALID,
                null,
                List.of(new PlanTask("t-1", "Parse invoice", PlanTaskStatus.PENDING,
                        "invoiceParser", "Extract invoice data", List.of(), List.of("parsedInvoice"), false, null)));
    }

    private static ExecutionPlan twoTaskPlan() {
        return new ExecutionPlan(
                "plan-1",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                PlanStatus.VALID,
                null,
                List.of(
                        new PlanTask("t-1", "Parse invoice", PlanTaskStatus.PENDING,
                                "invoiceParser", "Extract invoice data", List.of(), List.of("parsedInvoice"), false, null),
                        new PlanTask("t-2", "Look up expenses", PlanTaskStatus.PENDING,
                                "expenseLookup", "Find matching expenses", List.of("t-1"), List.of("expenses"), false, null)));
    }

    private static ToolCallback tool(String name, String result) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(org.mockito.ArgumentMatchers.anyString())).thenReturn(result);
        when(callback.call(org.mockito.ArgumentMatchers.anyString(), any())).thenReturn(result);
        return callback;
    }

    private static ToolCallback toolWithSchema(String name, String inputSchema, String result) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = ToolDefinition.builder().name(name).description(name).inputSchema(inputSchema).build();
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(org.mockito.ArgumentMatchers.anyString())).thenReturn(result);
        when(callback.call(org.mockito.ArgumentMatchers.anyString(), any())).thenReturn(result);
        return callback;
    }

    private static ToolCallback failingTool(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(org.mockito.ArgumentMatchers.anyString())).thenThrow(new IllegalStateException("parser exploded"));
        when(callback.call(org.mockito.ArgumentMatchers.anyString(), any())).thenThrow(new IllegalStateException("parser exploded"));
        return callback;
    }

    private static ToolCallback realToolCallback(DefaultExecutionStateService stateService,
                                                 PlanningService planningService,
                                                 AtomicInteger routerCalls,
                                                 BifrostSession session) {
        CapabilityMetadata capability = new CapabilityMetadata(
                "yaml:invoiceParser",
                "invoiceParser",
                "invoiceParser",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(EXECUTION_CONFIGURATION),
                java.util.Set.of(),
                arguments -> "unused",
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("invoiceParser", "invoiceParser"),
                null);
        CapabilityExecutionRouter router = mock(CapabilityExecutionRouter.class);
        when(router.execute(eq(capability), any(), eq(session), any())).thenAnswer(invocation -> {
            routerCalls.incrementAndGet();
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = (Map<String, Object>) invocation.getArgument(1);
            return Map.of("invoice", arguments.get("rawText"));
        });
        DefaultToolCallbackFactory factory = new DefaultToolCallbackFactory(router, planningService, stateService);
        return factory.createToolCallbacks(session, List.of(capability), null).getFirst();
    }

    private static List<TraceRecord> readRecords(BifrostSession session) {
        List<TraceRecord> records = new ArrayList<>();
        session.readTraceRecords(records::add);
        return records;
    }

    private static final class InitializingPlanningService implements PlanningService {

        private final DefaultExecutionStateService stateService;
        private final DefaultPlanningService delegate;
        private final ExecutionPlan initialPlan;

        private InitializingPlanningService(DefaultExecutionStateService stateService, ExecutionPlan initialPlan) {
            this.stateService = stateService;
            this.delegate = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
            this.initialPlan = initialPlan;
        }

        @Override
        public Optional<ExecutionPlan> initializePlan(BifrostSession session,
                                                      String objective,
                                                      String capabilityName,
                                                      EffectiveSkillExecutionConfiguration executionConfiguration,
                                                      ChatClient chatClient,
                                                      List<ToolCallback> visibleTools) {
            stateService.storePlan(session, initialPlan);
            stateService.logPlanCreated(session, initialPlan);
            return Optional.of(initialPlan);
        }

        @Override
        public Optional<ExecutionPlan> markToolStarted(BifrostSession session,
                                                       com.lokiscale.bifrost.core.CapabilityMetadata capability,
                                                       Map<String, Object> arguments) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ExecutionPlan> markTaskStarted(BifrostSession session,
                                                       String taskId,
                                                       String capabilityName,
                                                       Map<String, Object> arguments) {
            return delegate.markTaskStarted(session, taskId, capabilityName, arguments);
        }

        @Override
        public Optional<ExecutionPlan> markToolCompleted(BifrostSession session,
                                                         String taskId,
                                                         String capabilityName,
                                                         Object result) {
            return delegate.markToolCompleted(session, taskId, capabilityName, result);
        }

        @Override
        public Optional<ExecutionPlan> markToolFailed(BifrostSession session,
                                                      String taskId,
                                                      String capabilityName,
                                                      RuntimeException ex) {
            return delegate.markToolFailed(session, taskId, capabilityName, ex);
        }
    }

    private static final class StubYamlSkillCatalog extends YamlSkillCatalog {

        private final YamlSkillDefinition definition;

        private StubYamlSkillCatalog(YamlSkillDefinition definition) {
            super(new com.lokiscale.bifrost.autoconfigure.BifrostModelsProperties(),
                    new com.lokiscale.bifrost.autoconfigure.BifrostSkillProperties());
            this.definition = definition;
        }

        @Override
        public YamlSkillDefinition getSkill(String name) {
            return definition.manifest().getName().equals(name) ? definition : null;
        }
    }

    private static final class SequenceChatClient implements ChatClient {

        private final Deque<String> responses = new ArrayDeque<>();
        private final List<String> systemMessagesSeen = new ArrayList<>();
        private final List<String> userMessagesSeen = new ArrayList<>();

        private SequenceChatClient(String... responses) {
            this.responses.addAll(List.of(responses));
        }

        List<String> systemMessagesSeen() {
            return systemMessagesSeen;
        }

        List<String> userMessagesSeen() {
            return userMessagesSeen;
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
                userMessagesSeen.add(text);
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
