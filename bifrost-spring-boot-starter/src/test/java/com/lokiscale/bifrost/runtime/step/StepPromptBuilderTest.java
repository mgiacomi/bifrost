package com.lokiscale.bifrost.runtime.step;

import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.PlanStatus;
import com.lokiscale.bifrost.core.PlanTask;
import com.lokiscale.bifrost.core.PlanTaskStatus;
import com.lokiscale.bifrost.skill.YamlSkillManifest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StepPromptBuilderTest {

    private static ToolCallback mockTool(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    @Test
    void buildStepPromptContainsObjective() {
        ExecutionPlan plan = createTwoTaskPlan();
        String prompt = StepPromptBuilder.buildStepPrompt(
                plan, "Check for duplicate invoices", 1, null, null,
                List.of(mockTool("invoiceParser")), false, null);
        assertThat(prompt).contains("Check for duplicate invoices");
    }

    @Test
    void buildStepPromptRemovesSkillNameFromMissionContext() {
        ExecutionPlan plan = createTwoTaskPlan();
        String prompt = StepPromptBuilder.buildStepPrompt(
                plan,
                "Execute YAML skill 'duplicateInvoiceChecker' using these tool arguments:\n{\"payload\":\"x\"}",
                1,
                null,
                null,
                List.of(),
                false,
                null);
        assertThat(prompt).doesNotContain("duplicateInvoiceChecker");
        assertThat(prompt).contains("Use these mission inputs:");
    }

    @Test
    void buildStepPromptContainsStepNumber() {
        ExecutionPlan plan = createTwoTaskPlan();
        String prompt = StepPromptBuilder.buildStepPrompt(
                plan, "objective", 5, null, null, List.of(), false, null);
        assertThat(prompt).contains("Step: 5");
    }

    @Test
    void buildStepPromptShowsReadyTasks() {
        ExecutionPlan plan = createTwoTaskPlan();
        String prompt = StepPromptBuilder.buildStepPrompt(
                plan, "objective", 1, null, null, List.of(), false, null);
        assertThat(prompt).contains("READY TASKS");
        assertThat(prompt).contains("t-1");
        assertThat(prompt).contains("Parse invoice");
    }

    @Test
    void buildStepPromptShowsWaitingTasks() {
        ExecutionPlan plan = createTwoTaskPlan();
        String prompt = StepPromptBuilder.buildStepPrompt(
                plan, "objective", 1, null, null, List.of(), false, null);
        assertThat(prompt).contains("PENDING TASKS WAITING ON DEPENDENCIES");
        assertThat(prompt).contains("t-2");
        assertThat(prompt).contains("waiting on: t-1");
    }

    @Test
    void buildStepPromptShowsBlockedTasks() {
        ExecutionPlan plan = createTwoTaskPlan()
                .updateTask("t-2", task -> task.block("tool failed"));
        String prompt = StepPromptBuilder.buildStepPrompt(
                plan, "objective", 1, null, null, List.of(), false, null);
        assertThat(prompt).contains("BLOCKED TASKS");
        assertThat(prompt).contains("t-2");
        assertThat(prompt).contains("tool failed");
    }

    @Test
    void buildStepPromptShowsCompletedTasks() {
        ExecutionPlan plan = createTwoTaskPlan()
                .updateTask("t-1", task -> task.complete("parsed successfully"));
        String prompt = StepPromptBuilder.buildStepPrompt(
                plan, "objective", 2, null, null, List.of(), false, null);
        assertThat(prompt).contains("COMPLETED TASKS");
        assertThat(prompt).contains("t-1");
    }

    @Test
    void buildStepPromptIncludesActiveTask() {
        ExecutionPlan plan = createTwoTaskPlan().withActiveTask("t-1");
        String prompt = StepPromptBuilder.buildStepPrompt(
                plan, "objective", 2, null, null, List.of(), false, null);
        assertThat(prompt).contains("Active task: t-1: Parse invoice");
    }

    @Test
    void buildStepPromptIncludesLastToolResultTruncated() {
        ExecutionPlan plan = createTwoTaskPlan();
        String longResult = "X".repeat(2000);
        String prompt = StepPromptBuilder.buildStepPrompt(
                plan, "objective", 2, longResult, null, List.of(), false, null);
        assertThat(prompt).contains("LAST TOOL RESULT");
        assertThat(prompt).contains("truncated");
        assertThat(prompt.length()).isLessThan(3000);
    }

    @Test
    void buildStepPromptIncludesExecutionSummary() {
        ExecutionPlan plan = createTwoTaskPlan();
        String summary = "Step 1: Called invoiceParser for t-1 -> parsed vendor=Acme";
        String prompt = StepPromptBuilder.buildStepPrompt(
                plan, "objective", 2, null, summary, List.of(), false, null);
        assertThat(prompt).contains("EXECUTION SUMMARY");
        assertThat(prompt).contains("invoiceParser");
    }

    @Test
    void buildStepPromptContainsActionContract() {
        ExecutionPlan plan = createTwoTaskPlan();
        String prompt = StepPromptBuilder.buildStepPrompt(
                plan, "objective", 1, null, null, List.of(), false, null);
        assertThat(prompt).contains("CALL_TOOL");
        assertThat(prompt).contains("FINAL_RESPONSE");
        assertThat(prompt).contains("stepAction");
        assertThat(prompt).contains("valid JSON");
        assertThat(prompt).contains("Do NOT pick waiting, blocked, or completed tasks");
    }

    @Test
    void buildStepPromptHighlightsExactToolBindingForSingleReadyTask() {
        ExecutionPlan plan = createTwoTaskPlan()
                .updateTask("t-1", task -> task.complete("parsed"));
        String prompt = StepPromptBuilder.buildStepPrompt(
                plan, "objective", 2, "{\"vendor\":\"Acme\"}", null, List.of(), false, null);
        assertThat(prompt).contains("CURRENT EXECUTABLE TASK");
        assertThat(prompt).contains("Use taskId t-2.");
        assertThat(prompt).contains("The only valid toolName for this step is expenseLookup.");
        assertThat(prompt).contains("Do not call the parent mission skill");
    }

    @Test
    void buildStepPromptListsToolNames() {
        ExecutionPlan plan = createTwoTaskPlan();
        String prompt = StepPromptBuilder.buildStepPrompt(
                plan, "objective", 1, null, null,
                List.of(mockTool("invoiceParser"), mockTool("expenseLookup")), false, null);
        assertThat(prompt).contains("AVAILABLE TOOLS");
        assertThat(prompt).contains("invoiceParser");
        assertThat(prompt).contains("expenseLookup");
    }

    @Test
    void buildStepPromptNoToolsShowsNone() {
        ExecutionPlan plan = createTwoTaskPlan();
        String prompt = StepPromptBuilder.buildStepPrompt(
                plan, "objective", 1, null, null, List.of(), false, null);
        assertThat(prompt).contains("(none)");
    }

    @Test
    void buildStepPromptForCompletedPlanRequiresFinalResponseOnly() {
        ExecutionPlan plan = createTwoTaskPlan()
                .updateTask("t-1", task -> task.complete("parsed"))
                .updateTask("t-2", task -> task.complete("matched"));
        String prompt = StepPromptBuilder.buildStepPrompt(
                plan, "objective", 3, null, "Step 2 complete", List.of(mockTool("invoiceParser")), true, null);
        assertThat(prompt).contains("All required plan tasks are already COMPLETE");
        assertThat(prompt).contains("You must return a FINAL_RESPONSE action");
        assertThat(prompt).doesNotContain("Option 1 - Call a tool for a ready task");
        assertThat(prompt).doesNotContain("\"stepAction\": \"CALL_TOOL\"");
    }

    @Test
    void buildStepPromptShowsOutputSchemaInFinalResponseMode() {
        ExecutionPlan plan = createTwoTaskPlan()
                .updateTask("t-1", task -> task.complete("parsed"))
                .updateTask("t-2", task -> task.complete("matched"));
        String prompt = StepPromptBuilder.buildStepPrompt(
                plan, "objective", 3, null, "Step 2 complete", List.of(mockTool("invoiceParser")), true, duplicateInvoiceOutputSchema());
        assertThat(prompt).contains("REQUIRED FINAL RESPONSE SHAPE");
        assertThat(prompt).contains("\"isDuplicate\": <boolean>");
        assertThat(prompt).contains("\"reasoning\": \"<string>\"");
        assertThat(prompt).contains("Required top-level fields: isDuplicate, vendorName, totalAmount, invoiceDate, reasoning");
        assertThat(prompt).contains("Do not add fields that are not in this schema.");
    }

    private YamlSkillManifest.OutputSchemaManifest duplicateInvoiceOutputSchema() {
        YamlSkillManifest.OutputSchemaManifest schema = new YamlSkillManifest.OutputSchemaManifest();
        schema.setType("object");
        schema.setAdditionalProperties(false);

        YamlSkillManifest.OutputSchemaManifest isDuplicate = new YamlSkillManifest.OutputSchemaManifest();
        isDuplicate.setType("boolean");
        YamlSkillManifest.OutputSchemaManifest vendorName = new YamlSkillManifest.OutputSchemaManifest();
        vendorName.setType("string");
        YamlSkillManifest.OutputSchemaManifest totalAmount = new YamlSkillManifest.OutputSchemaManifest();
        totalAmount.setType("number");
        YamlSkillManifest.OutputSchemaManifest invoiceDate = new YamlSkillManifest.OutputSchemaManifest();
        invoiceDate.setType("string");
        YamlSkillManifest.OutputSchemaManifest reasoning = new YamlSkillManifest.OutputSchemaManifest();
        reasoning.setType("string");

        schema.setProperties(Map.of(
                "isDuplicate", isDuplicate,
                "vendorName", vendorName,
                "totalAmount", totalAmount,
                "invoiceDate", invoiceDate,
                "reasoning", reasoning));
        schema.setRequired(List.of("isDuplicate", "vendorName", "totalAmount", "invoiceDate", "reasoning"));
        return schema;
    }

    private ExecutionPlan createTwoTaskPlan() {
        PlanTask task1 = new PlanTask("t-1", "Parse invoice", PlanTaskStatus.PENDING,
                "invoiceParser", "Extract vendor, amount, date",
                List.of(), List.of("parsedInvoice"), false, null);
        PlanTask task2 = new PlanTask("t-2", "Look up expenses", PlanTaskStatus.PENDING,
                "expenseLookup", "Find matching expenses",
                List.of("t-1"), List.of("expenses"), false, null);
        return new ExecutionPlan("plan-1", "duplicateInvoiceChecker", Instant.now(),
                PlanStatus.VALID, null, List.of(task1, task2));
    }
}
