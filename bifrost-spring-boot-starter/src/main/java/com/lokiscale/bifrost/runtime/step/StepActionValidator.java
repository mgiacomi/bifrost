package com.lokiscale.bifrost.runtime.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.PlanTask;
import com.lokiscale.bifrost.core.PlanTaskStatus;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates a proposed {@link StepAction} against the current plan state before any side effects occur.
 */
public final class StepActionValidator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private StepActionValidator() {
    }

    public static StepValidationResult validate(StepAction action,
                                                ExecutionPlan plan,
                                                List<ToolCallback> visibleTools,
                                                boolean strictCompletion) {
        Objects.requireNonNull(plan, "plan must not be null");

        if (action == null) {
            return StepValidationResult.rejected("Model returned null action.");
        }

        if (action.stepAction() == null) {
            return StepValidationResult.rejected("Step action type is null. Expected CALL_TOOL or FINAL_RESPONSE.");
        }

        return switch (action.stepAction()) {
            case CALL_TOOL -> validateCallTool(action, plan, visibleTools);
            case FINAL_RESPONSE -> validateFinalResponse(action, plan, strictCompletion);
        };
    }

    private static StepValidationResult validateCallTool(StepAction action,
                                                         ExecutionPlan plan,
                                                         List<ToolCallback> visibleTools) {
        boolean allTasksCompleted = plan.tasks().stream()
                .allMatch(task -> task.status() == PlanTaskStatus.COMPLETED);
        if (allTasksCompleted) {
            return StepValidationResult.rejected(
                    "All required plan tasks are already completed. Return FINAL_RESPONSE instead of CALL_TOOL.");
        }

        if (action.taskId() == null || action.taskId().isBlank()) {
            return StepValidationResult.rejected("CALL_TOOL action requires a taskId.");
        }

        if (action.toolName() == null || action.toolName().isBlank()) {
            return StepValidationResult.rejected("CALL_TOOL action requires a toolName.");
        }

        Optional<PlanTask> targetTask = plan.findTask(action.taskId());
        if (targetTask.isEmpty()) {
            return StepValidationResult.rejected(
                    "Task '%s' does not exist in the plan.".formatted(action.taskId()));
        }

        PlanTask task = targetTask.get();
        java.util.Map<String, PlanTask> tasksById = plan.tasks().stream()
                .collect(Collectors.toMap(PlanTask::taskId, current -> current, (left, right) -> left));
        if (!task.isReady(tasksById)) {
            return StepValidationResult.rejected(
                    "Task '%s' is not ready (status=%s). Only PENDING tasks with completed dependencies may be executed."
                            .formatted(action.taskId(), task.status()));
        }

        Set<String> validToolNames = visibleTools.stream()
                .filter(tool -> tool != null && tool.getToolDefinition() != null)
                .map(tool -> tool.getToolDefinition().name())
                .collect(Collectors.toSet());
        if (!validToolNames.contains(action.toolName())) {
            return StepValidationResult.rejected(
                    "Tool '%s' is not in the available tools: %s".formatted(action.toolName(), validToolNames));
        }

        if (task.capabilityName() == null || task.capabilityName().isBlank()) {
            return StepValidationResult.rejected(
                    "Task '%s' does not declare an allowed tool capability.".formatted(action.taskId()));
        }

        if (!task.capabilityName().equals(action.toolName())) {
            return StepValidationResult.rejected(
                    "Task '%s' expects tool '%s' but model proposed '%s'."
                            .formatted(action.taskId(), task.capabilityName(), action.toolName()));
        }

        StepValidationResult schemaValidation = validateRequiredToolArguments(action, visibleTools);
        if (!schemaValidation.valid()) {
            return schemaValidation;
        }

        return StepValidationResult.ok();
    }

    private static StepValidationResult validateRequiredToolArguments(StepAction action,
                                                                      List<ToolCallback> visibleTools) {
        Optional<ToolCallback> matchingTool = visibleTools.stream()
                .filter(tool -> tool != null && tool.getToolDefinition() != null)
                .filter(tool -> action.toolName().equals(tool.getToolDefinition().name()))
                .findFirst();
        if (matchingTool.isEmpty()) {
            return StepValidationResult.ok();
        }

        String inputSchema = matchingTool.get().getToolDefinition().inputSchema();
        if (inputSchema == null || inputSchema.isBlank()) {
            return StepValidationResult.ok();
        }

        try {
            JsonNode schema = OBJECT_MAPPER.readTree(inputSchema);
            if (!"object".equals(schema.path("type").asText())) {
                return StepValidationResult.ok();
            }

            JsonNode required = schema.path("required");
            if (!required.isArray() || required.isEmpty()) {
                return StepValidationResult.ok();
            }

            JsonNode properties = schema.path("properties");
            if (!properties.isObject() || properties.isEmpty()) {
                return StepValidationResult.ok();
            }

            Map<String, Object> arguments = action.toolArguments() == null ? Map.of() : action.toolArguments();
            List<String> missingRequiredFields = java.util.stream.StreamSupport.stream(required.spliterator(), false)
                    .map(JsonNode::asText)
                    .filter(fieldName -> !arguments.containsKey(fieldName) || arguments.get(fieldName) == null)
                    .toList();

            if (!missingRequiredFields.isEmpty()) {
                return StepValidationResult.rejected(
                        "Tool '%s' requires argument(s) %s by schema, but the proposed toolArguments were missing them."
                                .formatted(action.toolName(), missingRequiredFields));
            }
        } catch (Exception ignored) {
            return StepValidationResult.ok();
        }

        return StepValidationResult.ok();
    }

    private static StepValidationResult validateFinalResponse(StepAction action,
                                                              ExecutionPlan plan,
                                                              boolean strictCompletion) {
        if (action.finalResponse() == null || action.finalResponse().isNull()) {
            return StepValidationResult.rejected("FINAL_RESPONSE action requires a non-empty finalResponse.");
        }

        if (strictCompletion) {
            List<PlanTask> incompleteTasks = plan.tasks().stream()
                    .filter(task -> task.status() != PlanTaskStatus.COMPLETED)
                    .toList();

            if (!incompleteTasks.isEmpty()) {
                String incompleteIds = incompleteTasks.stream()
                        .map(task -> task.taskId() + "(" + task.status() + ")")
                        .collect(Collectors.joining(", "));
                return StepValidationResult.rejected(
                        "Cannot finalize: %d task(s) remain incomplete: [%s]."
                                .formatted(incompleteTasks.size(), incompleteIds));
            }
        }

        return StepValidationResult.ok();
    }
}
