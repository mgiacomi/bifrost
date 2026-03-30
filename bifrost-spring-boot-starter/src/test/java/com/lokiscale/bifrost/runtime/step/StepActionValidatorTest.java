package com.lokiscale.bifrost.runtime.step;

import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.PlanStatus;
import com.lokiscale.bifrost.core.PlanTask;
import com.lokiscale.bifrost.core.PlanTaskStatus;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StepActionValidatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

    private ExecutionPlan plan;
    private List<ToolCallback> visibleTools;

    private static ToolCallback mockTool(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    private static ToolCallback mockTool(String name, String inputSchema) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = ToolDefinition.builder().name(name).description(name).inputSchema(inputSchema).build();
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    @BeforeEach
    void setUp() {
        PlanTask task1 = new PlanTask("t-1", "Parse invoice", PlanTaskStatus.PENDING,
                "invoiceParser", "Extract vendor, amount, date",
                List.of(), List.of("parsedInvoice"), false, null);
        PlanTask task2 = new PlanTask("t-2", "Look up expenses", PlanTaskStatus.PENDING,
                "expenseLookup", "Find matching expenses",
                List.of("t-1"), List.of("expenses"), false, null);
        plan = new ExecutionPlan("plan-1", "duplicateInvoiceChecker", Instant.now(),
                PlanStatus.VALID, null, List.of(task1, task2));
        visibleTools = List.of(mockTool("invoiceParser"), mockTool("expenseLookup"));
    }

    @Nested
    class NullAndMissingInput {
        @Test
        void nullActionRejected() {
            StepValidationResult result = StepActionValidator.validate(null, plan, visibleTools, true);
            assertThat(result.valid()).isFalse();
            assertThat(result.rejectionReason()).contains("null action");
        }

        @Test
        void nullActionTypeRejected() {
            StepAction action = new StepAction(null, "t-1", "invoiceParser", Map.of(), null);
            StepValidationResult result = StepActionValidator.validate(action, plan, visibleTools, true);
            assertThat(result.valid()).isFalse();
            assertThat(result.rejectionReason()).contains("Step action type is null");
        }
    }

    @Nested
    class CallToolValidation {
        @Test
        void validCallToolReadyTaskPasses() {
            StepAction action = StepAction.callTool("t-1", "invoiceParser", Map.of("input", "text"));
            StepValidationResult result = StepActionValidator.validate(action, plan, visibleTools, true);
            assertThat(result.valid()).isTrue();
        }

        @Test
        void missingTaskIdRejected() {
            StepAction action = new StepAction(StepActionType.CALL_TOOL, null, "invoiceParser", Map.of(), null);
            StepValidationResult result = StepActionValidator.validate(action, plan, visibleTools, true);
            assertThat(result.valid()).isFalse();
            assertThat(result.rejectionReason()).contains("taskId");
        }

        @Test
        void missingToolNameRejected() {
            StepAction action = new StepAction(StepActionType.CALL_TOOL, "t-1", null, Map.of(), null);
            StepValidationResult result = StepActionValidator.validate(action, plan, visibleTools, true);
            assertThat(result.valid()).isFalse();
            assertThat(result.rejectionReason()).contains("toolName");
        }

        @Test
        void unknownTaskIdRejected() {
            StepAction action = StepAction.callTool("t-99", "invoiceParser", Map.of());
            StepValidationResult result = StepActionValidator.validate(action, plan, visibleTools, true);
            assertThat(result.valid()).isFalse();
            assertThat(result.rejectionReason()).contains("does not exist");
        }

        @Test
        void dependencyBlockedTaskRejected() {
            StepAction action = StepAction.callTool("t-2", "expenseLookup", Map.of());
            StepValidationResult result = StepActionValidator.validate(action, plan, visibleTools, true);
            assertThat(result.valid()).isFalse();
            assertThat(result.rejectionReason()).contains("not ready");
        }

        @Test
        void missingDependencyTaskRejected() {
            ExecutionPlan invalidPlan = new ExecutionPlan("plan-1", "duplicateInvoiceChecker", Instant.now(),
                    PlanStatus.VALID, null, List.of(
                    new PlanTask("t-2", "Look up expenses", PlanTaskStatus.PENDING,
                            "expenseLookup", "Find matching expenses",
                            List.of("missing-task"), List.of("expenses"), false, null)));
            StepAction action = StepAction.callTool("t-2", "expenseLookup", Map.of());
            StepValidationResult result = StepActionValidator.validate(action, invalidPlan, List.of(mockTool("expenseLookup")), true);
            assertThat(result.valid()).isFalse();
            assertThat(result.rejectionReason()).contains("not ready");
        }

        @Test
        void completedTaskRejected() {
            ExecutionPlan updated = plan.updateTask("t-1", task -> task.complete("done"));
            StepAction action = StepAction.callTool("t-1", "invoiceParser", Map.of());
            StepValidationResult result = StepActionValidator.validate(action, updated, visibleTools, true);
            assertThat(result.valid()).isFalse();
            assertThat(result.rejectionReason()).contains("not ready");
        }

        @Test
        void unknownToolRejected() {
            StepAction action = StepAction.callTool("t-1", "nonExistentTool", Map.of());
            StepValidationResult result = StepActionValidator.validate(action, plan, visibleTools, true);
            assertThat(result.valid()).isFalse();
            assertThat(result.rejectionReason()).contains("not in the available tools");
        }

        @Test
        void wrongToolForTaskRejected() {
            StepAction action = StepAction.callTool("t-1", "expenseLookup", Map.of());
            StepValidationResult result = StepActionValidator.validate(action, plan, visibleTools, true);
            assertThat(result.valid()).isFalse();
            assertThat(result.rejectionReason()).contains("expects tool 'invoiceParser'");
        }

        @Test
        void taskWithoutCapabilityBindingRejected() {
            ExecutionPlan invalidPlan = new ExecutionPlan("plan-1", "duplicateInvoiceChecker", Instant.now(),
                    PlanStatus.VALID, null, List.of(
                    new PlanTask("t-1", "Parse invoice", PlanTaskStatus.PENDING,
                            null, "Extract vendor, amount, date",
                            List.of(), List.of("parsedInvoice"), false, null)));
            StepAction action = StepAction.callTool("t-1", "invoiceParser", Map.of());
            StepValidationResult result = StepActionValidator.validate(action, invalidPlan, visibleTools, true);
            assertThat(result.valid()).isFalse();
            assertThat(result.rejectionReason()).contains("does not declare an allowed tool capability");
        }

        @Test
        void dependencySatisfiedTaskPasses() {
            ExecutionPlan updated = plan.updateTask("t-1", task -> task.complete("done"));
            StepAction action = StepAction.callTool("t-2", "expenseLookup", Map.of());
            StepValidationResult result = StepActionValidator.validate(action, updated, visibleTools, true);
            assertThat(result.valid()).isTrue();
        }

        @Test
        void missingRequiredArgumentsFromConcreteToolSchemaRejected() {
            List<ToolCallback> schemaAwareTools = List.of(mockTool("invoiceParser", """
                    {
                      "type": "object",
                      "properties": {
                        "rawText": { "type": "string" }
                      },
                      "required": ["rawText"],
                      "additionalProperties": false
                    }
                    """));
            StepAction action = StepAction.callTool("t-1", "invoiceParser", Map.of());
            StepValidationResult result = StepActionValidator.validate(action, plan, schemaAwareTools, true);
            assertThat(result.valid()).isFalse();
            assertThat(result.rejectionReason()).contains("requires argument(s) [rawText]");
        }

        @Test
        void requiredArgumentsFromConcreteToolSchemaPassWhenPresent() {
            List<ToolCallback> schemaAwareTools = List.of(mockTool("invoiceParser", """
                    {
                      "type": "object",
                      "properties": {
                        "rawText": { "type": "string" }
                      },
                      "required": ["rawText"],
                      "additionalProperties": false
                    }
                    """));
            StepAction action = StepAction.callTool("t-1", "invoiceParser", Map.of("rawText", "INV-1"));
            StepValidationResult result = StepActionValidator.validate(action, plan, schemaAwareTools, true);
            assertThat(result.valid()).isTrue();
        }

        @Test
        void genericObjectSchemaDoesNotTriggerRequiredArgumentRejection() {
            List<ToolCallback> genericTools = List.of(mockTool("invoiceParser", """
                    {
                      "type": "object",
                      "properties": {},
                      "additionalProperties": true
                    }
                    """));
            StepAction action = StepAction.callTool("t-1", "invoiceParser", Map.of());
            StepValidationResult result = StepActionValidator.validate(action, plan, genericTools, true);
            assertThat(result.valid()).isTrue();
        }
    }

    @Nested
    class FinalResponseValidation {
        @Test
        void strictPolicyReadyTasksRemainRejected() {
            StepAction action = StepAction.finalResponse(JSON.createObjectNode().put("message", "All done!"));
            StepValidationResult result = StepActionValidator.validate(action, plan, visibleTools, true);
            assertThat(result.valid()).isFalse();
            assertThat(result.rejectionReason()).contains("remain incomplete");
            assertThat(result.rejectionReason()).contains("t-1(PENDING)");
            assertThat(result.rejectionReason()).contains("t-2(PENDING)");
        }

        @Test
        void strictPolicyWaitingTasksRemainRejected() {
            ExecutionPlan updated = plan.updateTask("t-1", task -> task.complete("done"));
            StepAction action = StepAction.finalResponse(JSON.createObjectNode().put("message", "All done!"));
            StepValidationResult result = StepActionValidator.validate(action, updated, visibleTools, true);
            assertThat(result.valid()).isFalse();
            assertThat(result.rejectionReason()).contains("t-2(PENDING)");
        }

        @Test
        void strictPolicyBlockedTaskRejected() {
            ExecutionPlan updated = plan
                    .updateTask("t-1", task -> task.complete("done"))
                    .updateTask("t-2", task -> task.block("tool failed"));
            StepAction action = StepAction.finalResponse(JSON.createObjectNode().put("message", "Done!"));
            StepValidationResult result = StepActionValidator.validate(action, updated, visibleTools, true);
            assertThat(result.valid()).isFalse();
            assertThat(result.rejectionReason()).contains("t-2(BLOCKED)");
        }

        @Test
        void strictPolicyAllCompletePasses() {
            ExecutionPlan updated = plan
                    .updateTask("t-1", task -> task.complete("done"))
                    .updateTask("t-2", task -> task.complete("done"));
            StepAction action = StepAction.finalResponse(JSON.createObjectNode().put("message", "All done!"));
            StepValidationResult result = StepActionValidator.validate(action, updated, visibleTools, true);
            assertThat(result.valid()).isTrue();
        }

        @Test
        void advisoryPolicyReadyTasksRemainPasses() {
            StepAction action = StepAction.finalResponse(JSON.createObjectNode().put("message", "All done!"));
            StepValidationResult result = StepActionValidator.validate(action, plan, visibleTools, false);
            assertThat(result.valid()).isTrue();
        }

        @Test
        void emptyFinalResponseRejected() {
            StepAction action = StepAction.finalResponse(JSON.nullNode());
            StepValidationResult result = StepActionValidator.validate(action, plan, visibleTools, false);
            assertThat(result.valid()).isFalse();
            assertThat(result.rejectionReason()).contains("non-empty finalResponse");
        }

        @Test
        void strictPolicyInProgressTaskRejected() {
            ExecutionPlan updated = plan
                    .updateTask("t-1", task -> task.complete("done"))
                    .updateTask("t-2", task -> task.bindInProgress("working"));
            StepAction action = StepAction.finalResponse(JSON.createObjectNode().put("message", "Done!"));
            StepValidationResult result = StepActionValidator.validate(action, updated, visibleTools, true);
            assertThat(result.valid()).isFalse();
            assertThat(result.rejectionReason()).contains("t-2(IN_PROGRESS)");
        }
    }
}
