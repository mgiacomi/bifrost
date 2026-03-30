package com.lokiscale.bifrost.runtime.step;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.BifrostStackOverflowException;
import com.lokiscale.bifrost.core.CapabilityRegistry;
import com.lokiscale.bifrost.core.ExecutionFrame;
import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.ModelTraceContext;
import com.lokiscale.bifrost.core.ModelTraceResult;
import com.lokiscale.bifrost.core.PlanTask;
import com.lokiscale.bifrost.core.PlanTaskStatus;
import com.lokiscale.bifrost.core.SessionContextRunner;
import com.lokiscale.bifrost.core.TraceFrameType;
import com.lokiscale.bifrost.core.TraceRecordType;
import com.lokiscale.bifrost.linter.LinterOutcome;
import com.lokiscale.bifrost.linter.LinterOutcomeStatus;
import com.lokiscale.bifrost.outputschema.OutputSchemaOutcome;
import com.lokiscale.bifrost.outputschema.OutputSchemaOutcomeStatus;
import com.lokiscale.bifrost.outputschema.OutputSchemaValidationIssue;
import com.lokiscale.bifrost.outputschema.OutputSchemaValidationResult;
import com.lokiscale.bifrost.outputschema.OutputSchemaValidator;
import com.lokiscale.bifrost.runtime.BifrostMissionTimeoutException;
import com.lokiscale.bifrost.runtime.MissionExecutionEngine;
import com.lokiscale.bifrost.runtime.planning.PlanningService;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import com.lokiscale.bifrost.runtime.tool.DefaultToolCallbackFactory;
import com.lokiscale.bifrost.runtime.usage.ModelUsageExtractor;
import com.lokiscale.bifrost.runtime.usage.SessionUsageService;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import com.lokiscale.bifrost.skill.YamlSkillCatalog;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plan-step execution engine that replaces the single-shot mission model call with a deterministic loop.
 */
public class StepLoopMissionExecutionEngine implements MissionExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(StepLoopMissionExecutionEngine.class);

    private static final int DEFAULT_MAX_STEPS = 10;
    private static final int MAX_INVALID_ACTION_RETRIES = 1;
    private static final int MAX_EXECUTION_SUMMARY_LINES = 5;

    private enum CleanupOwner {
        NONE,
        WORKER,
        CALLER
    }

    private final PlanningService planningService;
    private final ExecutionStateService executionStateService;
    @SuppressWarnings("unused")
    private final CapabilityRegistry capabilityRegistry;
    private final YamlSkillCatalog yamlSkillCatalog;
    private final Duration missionTimeout;
    private final ExecutorService missionExecutor;
    private final SessionUsageService sessionUsageService;
    private final ModelUsageExtractor modelUsageExtractor;
    private final ObjectMapper objectMapper;
    private final OutputSchemaValidator outputSchemaValidator;
    private final int defaultMaxSteps;

    public StepLoopMissionExecutionEngine(PlanningService planningService,
                                          ExecutionStateService executionStateService,
                                          CapabilityRegistry capabilityRegistry,
                                          YamlSkillCatalog yamlSkillCatalog,
                                          Duration missionTimeout,
                                          ExecutorService missionExecutor,
                                          SessionUsageService sessionUsageService,
                                          ModelUsageExtractor modelUsageExtractor) {
        this(planningService, executionStateService, capabilityRegistry, yamlSkillCatalog, missionTimeout, missionExecutor,
                sessionUsageService, modelUsageExtractor, DEFAULT_MAX_STEPS);
    }

    public StepLoopMissionExecutionEngine(PlanningService planningService,
                                          ExecutionStateService executionStateService,
                                          CapabilityRegistry capabilityRegistry,
                                          YamlSkillCatalog yamlSkillCatalog,
                                          Duration missionTimeout,
                                          ExecutorService missionExecutor,
                                          SessionUsageService sessionUsageService,
                                          ModelUsageExtractor modelUsageExtractor,
                                          int defaultMaxSteps) {
        this.planningService = Objects.requireNonNull(planningService, "planningService must not be null");
        this.executionStateService = Objects.requireNonNull(executionStateService, "executionStateService must not be null");
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry must not be null");
        this.yamlSkillCatalog = Objects.requireNonNull(yamlSkillCatalog, "yamlSkillCatalog must not be null");
        this.missionTimeout = Objects.requireNonNull(missionTimeout, "missionTimeout must not be null");
        this.missionExecutor = Objects.requireNonNull(missionExecutor, "missionExecutor must not be null");
        this.sessionUsageService = Objects.requireNonNull(sessionUsageService, "sessionUsageService must not be null");
        this.modelUsageExtractor = Objects.requireNonNull(modelUsageExtractor, "modelUsageExtractor must not be null");
        this.objectMapper = JsonMapper.builder().findAndAddModules().build();
        this.outputSchemaValidator = new OutputSchemaValidator();
        this.defaultMaxSteps = defaultMaxSteps;
    }

    @Override
    public String executeMission(BifrostSession session,
                                 String skillName,
                                 String objective,
                                 EffectiveSkillExecutionConfiguration executionConfiguration,
                                 ChatClient chatClient,
                                 List<ToolCallback> visibleTools,
                                 boolean planningEnabled,
                                 @Nullable Authentication authentication) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(skillName, "skillName must not be null");
        Objects.requireNonNull(objective, "objective must not be null");
        Objects.requireNonNull(chatClient, "chatClient must not be null");
        Objects.requireNonNull(visibleTools, "visibleTools must not be null");

        int baselineFrameDepth = session.getFramesSnapshot().size();
        AtomicReference<CleanupOwner> cleanupOwner = new AtomicReference<>(CleanupOwner.NONE);
        CountDownLatch cleanupComplete = new CountDownLatch(1);

        Callable<String> missionCall = () -> SessionContextRunner.callWithSession(session, () -> {
            try {
                sessionUsageService.recordMissionStart(session, skillName);

                if (planningEnabled) {
                    planningService.initializePlan(
                            session,
                            objective,
                            skillName,
                            executionConfiguration,
                            chatClient,
                            visibleTools,
                            true);
                }

                Optional<ExecutionPlan> planOpt = executionStateService.currentPlan(session);
                if (planOpt.isEmpty()) {
                    throw new IllegalStateException(
                            "Step-loop execution requires a plan but none was created for skill '" + skillName + "'");
                }

                return executeStepLoop(session, skillName, objective, executionConfiguration, chatClient, visibleTools);
            } finally {
                try {
                    if (cleanupOwner.compareAndSet(CleanupOwner.NONE, CleanupOwner.WORKER)) {
                        unwindMissionFrames(session, baselineFrameDepth);
                    }
                } finally {
                    cleanupComplete.countDown();
                }
            }
        });

        Future<String> mission = missionExecutor.submit(missionCall);
        try {
            return mission.get(missionTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            mission.cancel(true);
            awaitMissionCleanup(session, mission, baselineFrameDepth, cleanupOwner, cleanupComplete);
            throw new BifrostMissionTimeoutException(session.getSessionId(), skillName, missionTimeout, ex);
        } catch (InterruptedException ex) {
            mission.cancel(true);
            awaitMissionCleanup(session, mission, baselineFrameDepth, cleanupOwner, cleanupComplete);
            Thread.currentThread().interrupt();
            throw new BifrostMissionTimeoutException(session.getSessionId(), skillName, missionTimeout, ex);
        } catch (ExecutionException ex) {
            awaitMissionCleanup(session, mission, baselineFrameDepth, cleanupOwner, cleanupComplete);
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw unwrapMissionFailure(runtimeException);
            }
            throw new IllegalStateException("Mission execution failed for skill '" + skillName + "'", cause);
        }
    }

    private String executeStepLoop(BifrostSession session,
                                   String skillName,
                                   String objective,
                                   EffectiveSkillExecutionConfiguration executionConfiguration,
                                   ChatClient chatClient,
                                   List<ToolCallback> visibleTools) {
        int maxSteps = defaultMaxSteps;
        YamlSkillDefinition skillDefinition = yamlSkillCatalog.getSkill(skillName);
        if (skillDefinition != null) {
            maxSteps = skillDefinition.maxSteps(defaultMaxSteps);
        }
        if (maxSteps <= 0) {
            recordTerminalFailure(session, skillName, 0,
                    "Step-loop execution rejected invalid max_steps value: " + maxSteps);
            throw new IllegalStateException(
                    "Step-loop execution requires max_steps > 0 for skill '%s' but was %d."
                            .formatted(skillName, maxSteps));
        }

        validatePlanForStepLoop(session, skillName, executionStateService.currentPlan(session)
                .orElseThrow(() -> new IllegalStateException(
                        "Plan disappeared before step loop started for skill '" + skillName + "'")));

        Deque<String> executionSummary = new ArrayDeque<>();
        String lastToolResult = null;

        for (int stepNumber = 1; stepNumber <= maxSteps; stepNumber++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new BifrostMissionTimeoutException(session.getSessionId(), skillName, missionTimeout,
                        new InterruptedException("Step loop interrupted"));
            }

            ExecutionPlan plan = executionStateService.currentPlan(session)
                    .orElseThrow(() -> new IllegalStateException(
                            "Plan disappeared during step loop for skill '" + skillName + "'"));

            List<PlanTask> readyTasks = plan.readyTasks();
            boolean allDone = plan.tasks().stream().allMatch(task -> task.status() == PlanTaskStatus.COMPLETED);
            boolean noneReady = readyTasks.isEmpty();

            if (allDone) {
                log.debug("All tasks completed at step {} for skill '{}', requesting final response", stepNumber, skillName);
            }

            if (noneReady && !allDone) {
                recordTerminalFailure(session, skillName, stepNumber,
                        "Plan deadlock: no ready tasks remain while the plan still has incomplete tasks.");
                throw new IllegalStateException(
                        "Step-loop deadlock at step %d for skill '%s': no ready tasks remain while the plan is incomplete."
                                .formatted(stepNumber, skillName));
            }

            StepResult stepResult = executeOneStep(
                    session,
                    skillName,
                    objective,
                    executionConfiguration,
                    chatClient,
                    visibleTools,
                    plan,
                    stepNumber,
                    lastToolResult,
                    formatExecutionSummary(executionSummary),
                    skillDefinition,
                    true,
                    allDone);

            if (stepResult.isFinalResponse()) {
                return stepResult.finalResponse();
            }

            lastToolResult = stepResult.toolResult();
            if (stepResult.summaryLine() != null) {
                appendExecutionSummary(executionSummary, stepResult.summaryLine());
            }
        }

        recordTerminalFailure(session, skillName, maxSteps,
                "Step limit reached before the plan completed.");
        throw new IllegalStateException("Step-loop exhausted %d steps for skill '%s' before the plan completed."
                .formatted(maxSteps, skillName));
    }

    private StepResult executeOneStep(BifrostSession session,
                                      String skillName,
                                      String objective,
                                      EffectiveSkillExecutionConfiguration executionConfiguration,
                                      ChatClient chatClient,
                                      List<ToolCallback> visibleTools,
                                      ExecutionPlan plan,
                                      int stepNumber,
                                      @Nullable String lastToolResult,
                                      @Nullable String executionSummary,
                                      @Nullable YamlSkillDefinition skillDefinition,
                                      boolean strictCompletion,
                                      boolean finalResponseOnly) {
        ExecutionFrame stepFrame = executionStateService.openFrame(
                session,
                TraceFrameType.STEP_EXECUTION,
                skillName + "#step-" + stepNumber,
                Map.of("stepNumber", stepNumber));

        executionStateService.recordStepEvent(session, stepFrame, TraceRecordType.STEP_STARTED,
                Map.of("stepNumber", stepNumber, "readyTasks", plan.readyTasks().size()),
                Map.of("planStatus", plan.status().name()));

        String stepFrameStatus = "completed";
        Throwable stepFailure = null;
        try {
            String stepPrompt = StepPromptBuilder.buildStepPrompt(
                    plan,
                    objective,
                    stepNumber,
                    lastToolResult,
                    executionSummary,
                    visibleTools,
                    finalResponseOnly,
                    skillDefinition == null ? null : skillDefinition.outputSchema());
            String stepUserMessage = StepPromptBuilder.buildStepUserMessage(plan, objective);

            int invalidActionRetryCount = 0;
            int linterAttempt = 1;
            int outputSchemaAttempt = 1;
            while (true) {
                String effectivePrompt = invalidActionRetryCount == 0
                        ? stepPrompt
                        : stepPrompt + "\n\nPREVIOUS ATTEMPT WAS INVALID. Please correct and try again.";

                String modelResponse = callModelForStep(
                        session, skillName, executionConfiguration, chatClient, effectivePrompt, stepUserMessage, stepNumber);

                StepAction action = parseStepAction(modelResponse, finalResponseOnly);
                if (action == null) {
                    executionStateService.recordStepEvent(session, stepFrame, TraceRecordType.STEP_ACTION_REJECTED,
                            Map.of("retry", invalidActionRetryCount, "reason", "Failed to parse model response as StepAction"),
                            Map.of("rawResponse", truncate(modelResponse, 500)));
                    if (invalidActionRetryCount >= MAX_INVALID_ACTION_RETRIES) {
                        recordTerminalFailure(session, skillName, stepNumber,
                                "Model failed to produce a valid step action after %d attempts."
                                        .formatted(MAX_INVALID_ACTION_RETRIES + 1));
                        throw new IllegalStateException(
                                "Model failed to produce a valid step action after %d attempts at step %d for skill '%s'."
                                        .formatted(MAX_INVALID_ACTION_RETRIES + 1, stepNumber, skillName));
                    }
                    invalidActionRetryCount++;
                    continue;
                }

                executionStateService.recordStepEvent(session, stepFrame, TraceRecordType.STEP_ACTION_PROPOSED,
                        Map.of("stepAction", actionName(action),
                                "taskId", action.taskId() == null ? "" : action.taskId(),
                                "toolName", action.toolName() == null ? "" : action.toolName()),
                        Map.of());

                StepValidationResult validation = StepActionValidator.validate(action, plan, visibleTools, strictCompletion);
                boolean skillValidationRejected = false;
                if (validation.valid() && action.stepAction() == StepActionType.FINAL_RESPONSE) {
                    FinalResponseValidationOutcome finalValidation = validateFinalResponseForSkill(
                            session, action, skillDefinition, linterAttempt, outputSchemaAttempt);
                    validation = finalValidation.validation();
                    linterAttempt = finalValidation.nextLinterAttempt();
                    outputSchemaAttempt = finalValidation.nextOutputSchemaAttempt();
                    skillValidationRejected = !validation.valid();
                    if (!validation.valid() && finalValidation.exhausted()) {
                        executionStateService.recordStepEvent(session, stepFrame, TraceRecordType.STEP_ACTION_REJECTED,
                                Map.of("reason", validation.rejectionReason(), "exhausted", true),
                                Map.of("stepAction", actionName(action)));
                        recordTerminalFailure(session, skillName, stepNumber,
                                "Final response validation exhausted: " + validation.rejectionReason());
                        throw new IllegalStateException("Final response validation exhausted at step %d for skill '%s': %s"
                                .formatted(stepNumber, skillName, validation.rejectionReason()));
                    }
                }
                if (!validation.valid()) {
                    executionStateService.recordStepEvent(session, stepFrame, TraceRecordType.STEP_ACTION_REJECTED,
                            Map.of("retry", invalidActionRetryCount, "reason", validation.rejectionReason()),
                            Map.of("stepAction", actionName(action)));
                    log.debug("Step {} action rejected (retry {}): {}", stepNumber, invalidActionRetryCount, validation.rejectionReason());
                    if (!skillValidationRejected && invalidActionRetryCount >= MAX_INVALID_ACTION_RETRIES) {
                        recordTerminalFailure(session, skillName, stepNumber,
                                "Step action validation exhausted: " + validation.rejectionReason());
                        throw new IllegalStateException("Step action validation exhausted at step %d for skill '%s': %s"
                                .formatted(stepNumber, skillName, validation.rejectionReason()));
                    }
                    stepPrompt = stepPrompt + "\n\nYOUR PREVIOUS ACTION WAS INVALID: " + validation.rejectionReason()
                            + "\nPlease correct and try again.";
                    if (!skillValidationRejected) {
                        invalidActionRetryCount++;
                    }
                    continue;
                }

                executionStateService.recordStepEvent(session, stepFrame, TraceRecordType.STEP_ACTION_VALIDATED,
                        Map.of("stepAction", actionName(action)), Map.of());

                return switch (action.stepAction()) {
                    case CALL_TOOL -> executeToolAction(session, action, visibleTools, stepFrame, stepNumber);
                    case FINAL_RESPONSE -> {
                        String finalResponse = serializeFinalResponse(action.finalResponse());
                executionStateService.recordStepEvent(session, stepFrame, TraceRecordType.STEP_COMPLETED,
                        Map.of("stepAction", "FINAL_RESPONSE", "stepNumber", stepNumber), Map.of());
                        yield StepResult.finalResponse(finalResponse);
                    }
                };
            }
        } catch (RuntimeException ex) {
            stepFailure = ex;
            stepFrameStatus = Thread.currentThread().isInterrupted() ? "aborted" : "failed";
            throw ex;
        } finally {
            executionStateService.closeFrame(session, stepFrame, closeMetadata(stepFrameStatus, stepFailure));
        }
    }

    private String callModelForStep(BifrostSession session,
                                    String skillName,
                                    EffectiveSkillExecutionConfiguration executionConfiguration,
                                    ChatClient chatClient,
                                    String stepPrompt,
                                    String stepUserMessage,
                                    int stepNumber) {
        ExecutionFrame modelFrame = executionStateService.openFrame(
                session,
                TraceFrameType.MODEL_CALL,
                skillName + "#step-" + stepNumber + "-model",
                Map.of(
                        "provider", executionConfiguration.provider().name(),
                        "providerModel", executionConfiguration.providerModel(),
                        "segment", "step-" + stepNumber));

        String modelFrameStatus = "completed";
        Throwable modelFailure = null;
        try {
            ModelTraceContext modelTraceContext = new ModelTraceContext(
                    executionConfiguration.provider().name(),
                    executionConfiguration.providerModel(),
                    skillName,
                    "step-" + stepNumber);

            return executionStateService.traceModelCall(
                    session,
                    modelFrame,
                    modelTraceContext,
                    Map.of("system", stepPrompt, "user", stepUserMessage),
                    markRequestSent -> {
                        Map<String, Object> sentPayload = Map.of("system", stepPrompt, "user", stepUserMessage);
                        ChatClient.CallResponseSpec responseSpec = chatClient.prompt()
                                .system(stepPrompt)
                                .user(stepUserMessage)
                                .call();
                        markRequestSent.accept(sentPayload);

                        ChatResponse chatResponse;
                        String responseContent;
                        try {
                            ChatClientResponse clientResponse = responseSpec.chatClientResponse();
                            chatResponse = clientResponse.chatResponse();
                            responseContent = extractContentFromChatResponse(chatResponse);
                        } catch (UnsupportedOperationException ignored) {
                            chatResponse = null;
                            responseContent = responseSpec.content();
                        }

                        sessionUsageService.recordModelResponse(
                                session,
                                skillName,
                                modelUsageExtractor.extract(chatResponse, stepUserMessage, stepPrompt, responseContent));
                        return ModelTraceResult.of(
                                responseContent,
                                Map.of("content", responseContent == null ? "" : responseContent));
                    });
        } catch (RuntimeException ex) {
            modelFailure = ex;
            modelFrameStatus = Thread.currentThread().isInterrupted() ? "aborted" : "failed";
            throw ex;
        } finally {
            executionStateService.closeFrame(session, modelFrame, closeMetadata(modelFrameStatus, modelFailure));
        }
    }

    private StepResult executeToolAction(BifrostSession session,
                                         StepAction action,
                                         List<ToolCallback> visibleTools,
                                         ExecutionFrame stepFrame,
                                         int stepNumber) {
        ToolCallback toolCallback = visibleTools.stream()
                .filter(t -> t != null && t.getToolDefinition() != null
                        && action.toolName().equals(t.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Tool '%s' validated but not found in visible tools".formatted(action.toolName())));

        planningService.markTaskStarted(session,
                action.taskId(),
                action.toolName(),
                action.toolArguments() == null ? Map.of() : action.toolArguments());

        String toolResult;
        try {
            String argumentsJson;
            try {
                argumentsJson = objectMapper.writeValueAsString(
                        action.toolArguments() == null ? Map.of() : action.toolArguments());
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Failed to serialize tool arguments for tool '" + action.toolName() + "'", ex);
            }

            String rawResult = toolCallback.call(argumentsJson, new ToolContext(Map.of(
                    DefaultToolCallbackFactory.STEP_LOOP_TASK_ID_CONTEXT_KEY, action.taskId())));
            toolResult = rawResult == null ? "null" : rawResult;
            planningService.markToolCompleted(session, action.taskId(), action.toolName(), toolResult);
        } catch (RuntimeException ex) {
            planningService.markToolFailed(session, action.taskId(), action.toolName(), ex);
            executionStateService.recordStepEvent(session, stepFrame, TraceRecordType.STEP_COMPLETED,
                    Map.of("stepAction", "CALL_TOOL", "stepNumber", stepNumber,
                            "taskId", action.taskId(), "toolName", action.toolName(), "status", "failed"),
                    Map.of("error", truncate(ex.getMessage(), 200)));
            RuntimeException unwrapped = unwrapMissionFailure(ex);
            if (unwrapped instanceof BifrostStackOverflowException || unwrapped instanceof BifrostMissionTimeoutException) {
                throw unwrapped;
            }
            throw new IllegalStateException("Step %d tool '%s' failed for task '%s': %s"
                    .formatted(stepNumber, action.toolName(), action.taskId(), ex.getMessage()), ex);
        }

        String summaryLine = "Step %d: Called %s for task %s -> %s".formatted(
                stepNumber,
                action.toolName(),
                action.taskId(),
                toolResult.length() > 100 ? toolResult.substring(0, 100) + "..." : toolResult);

        executionStateService.recordStepEvent(session, stepFrame, TraceRecordType.STEP_COMPLETED,
                Map.of("stepAction", "CALL_TOOL", "stepNumber", stepNumber,
                        "taskId", action.taskId(), "toolName", action.toolName()),
                Map.of("resultPreview", truncate(toolResult, 200)));

        return StepResult.toolExecuted(toolResult, summaryLine);
    }

    @Nullable
    private StepAction parseStepAction(String modelResponse, boolean finalResponseOnly) {
        if (modelResponse == null || modelResponse.isBlank()) {
            return null;
        }
        String unwrapped = unwrapFencedBlock(modelResponse);
        try {
            StepAction parsed = objectMapper.readValue(unwrapped, StepAction.class);
            if (finalResponseOnly && (parsed.stepAction() == null || parsed.stepAction() != StepActionType.FINAL_RESPONSE)) {
                JsonNode node = objectMapper.readTree(unwrapped);
                if (looksLikeBareFinalResponsePayload(node)) {
                    return StepAction.finalResponse(node);
                }
            }
            return parsed;
        } catch (JsonProcessingException ex) {
            log.debug("Failed to parse step action JSON: {}", ex.getMessage());
            return null;
        }
    }

    private boolean looksLikeBareFinalResponsePayload(@Nullable JsonNode node) {
        return node != null
                && node.isObject()
                && !node.has("stepAction")
                && !node.has("action")
                && !node.has("finalResponse");
    }

    private String unwrapFencedBlock(String payload) {
        String safePayload = payload.trim();
        if (safePayload.startsWith("```")) {
            int firstNewline = safePayload.indexOf('\n');
            int lastFence = safePayload.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return safePayload.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return safePayload;
    }

    @Nullable
    private static String extractContentFromChatResponse(@Nullable ChatResponse chatResponse) {
        return Optional.ofNullable(chatResponse)
                .map(ChatResponse::getResult)
                .map(Generation::getOutput)
                .map(AbstractMessage::getText)
                .orElse(null);
    }

    private void recordTerminalFailure(BifrostSession session,
                                       String skillName,
                                       int stepNumber,
                                       String message) {
        executionStateService.logError(session, Map.of(
                "skillName", skillName,
                "stepNumber", stepNumber,
                "phase", "step-loop",
                "message", message));
    }

    private void validatePlanForStepLoop(BifrostSession session, String skillName, ExecutionPlan plan) {
        List<String> duplicateTaskIds = plan.tasks().stream()
                .map(PlanTask::taskId)
                .collect(java.util.stream.Collectors.groupingBy(
                        java.util.function.Function.identity(),
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
        List<String> unsupportedTaskIds = plan.tasks().stream()
                .filter(PlanTask::autoCompletable)
                .map(PlanTask::taskId)
                .toList();
        List<String> invalidDependencies = plan.tasks().stream()
                .flatMap(task -> task.dependsOn().stream()
                        .filter(dependencyId -> plan.findTask(dependencyId).isEmpty())
                        .map(dependencyId -> task.taskId() + "->" + dependencyId))
                .toList();
        List<String> unboundToolTasks = plan.tasks().stream()
                .filter(task -> !task.autoCompletable())
                .filter(task -> task.capabilityName() == null || task.capabilityName().isBlank())
                .map(PlanTask::taskId)
                .toList();
        if (duplicateTaskIds.isEmpty() && unsupportedTaskIds.isEmpty() && invalidDependencies.isEmpty()
                && unboundToolTasks.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder("Step-loop execution rejected an invalid Phase 6 plan.");
        if (!duplicateTaskIds.isEmpty()) {
            message.append(" Task IDs must be unique. Duplicates: ").append(duplicateTaskIds).append(".");
        }
        if (!unsupportedTaskIds.isEmpty()) {
            message.append(" autoCompletable tasks are not supported: ").append(unsupportedTaskIds).append(".");
        }
        if (!invalidDependencies.isEmpty()) {
            message.append(" Missing task dependencies: ").append(invalidDependencies).append(".");
        }
        if (!unboundToolTasks.isEmpty()) {
            message.append(" Tasks missing capability bindings: ").append(unboundToolTasks).append(".");
        }
        recordTerminalFailure(session, skillName, 0, message.toString());
        throw new IllegalStateException(message.toString());
    }

    private FinalResponseValidationOutcome validateFinalResponseForSkill(BifrostSession session,
                                                                        StepAction action,
                                                                        @Nullable YamlSkillDefinition skillDefinition,
                                                                        int linterAttempt,
                                                                        int outputSchemaAttempt) {
        if (skillDefinition == null) {
            return FinalResponseValidationOutcome.ok(linterAttempt, outputSchemaAttempt);
        }

        String finalResponse = serializeFinalResponse(action.finalResponse());
        ValidatorAttemptOutcome linterValidation = validateLinter(skillDefinition, finalResponse, linterAttempt);
        if (!linterValidation.valid()) {
            return new FinalResponseValidationOutcome(
                    linterValidation.validation(),
                    linterValidation.nextAttempt(),
                    outputSchemaAttempt,
                    linterValidation.exhausted());
        }

        ValidatorAttemptOutcome outputSchemaValidation = validateOutputSchema(
                session, skillDefinition, finalResponse, outputSchemaAttempt);
        return new FinalResponseValidationOutcome(
                outputSchemaValidation.validation(),
                linterAttempt,
                outputSchemaValidation.nextAttempt(),
                outputSchemaValidation.exhausted());
    }

    private ValidatorAttemptOutcome validateLinter(YamlSkillDefinition skillDefinition,
                                                   @Nullable String finalResponse,
                                                   int attempt) {
        var linter = skillDefinition.linter();
        if (linter == null || !"regex".equals(linter.getType()) || linter.getRegex() == null) {
            return ValidatorAttemptOutcome.passed(attempt);
        }

        int maxRetries = linter.getMaxRetries() == null ? 0 : linter.getMaxRetries();
        boolean matches = finalResponse != null && java.util.regex.Pattern
                .compile(linter.getRegex().getPattern())
                .matcher(finalResponse)
                .matches();
        LinterOutcomeStatus status = matches
                ? LinterOutcomeStatus.PASSED
                : attempt <= maxRetries ? LinterOutcomeStatus.RETRYING : LinterOutcomeStatus.EXHAUSTED;
        String detail = matches
                ? "Final response matched configured regex linter."
                : (linter.getRegex().getMessage() == null || linter.getRegex().getMessage().isBlank()
                ? "Final response did not match the configured regex linter."
                : linter.getRegex().getMessage());

        executionStateService.recordLinterOutcome(BifrostSession.getCurrentSession(), new LinterOutcome(
                skillDefinition.manifest().getName(),
                linter.getType(),
                attempt,
                attempt - 1,
                maxRetries,
                status,
                detail));

        return matches
                ? ValidatorAttemptOutcome.passed(attempt)
                : ValidatorAttemptOutcome.failed(detail, attempt + 1, status == LinterOutcomeStatus.EXHAUSTED);
    }

    private ValidatorAttemptOutcome validateOutputSchema(BifrostSession session,
                                                         YamlSkillDefinition skillDefinition,
                                                         @Nullable String finalResponse,
                                                         int attempt) {
        if (skillDefinition.outputSchema() == null) {
            return ValidatorAttemptOutcome.passed(attempt);
        }

        int maxRetries = skillDefinition.outputSchemaMaxRetries();
        OutputSchemaValidationResult result = outputSchemaValidator.validate(finalResponse, skillDefinition.outputSchema());
        OutputSchemaOutcomeStatus status = result.valid()
                ? OutputSchemaOutcomeStatus.PASSED
                : attempt <= maxRetries ? OutputSchemaOutcomeStatus.RETRYING : OutputSchemaOutcomeStatus.EXHAUSTED;
        executionStateService.recordOutputSchemaOutcome(session, new OutputSchemaOutcome(
                skillDefinition.manifest().getName(),
                result.failureMode(),
                attempt,
                attempt - 1,
                maxRetries,
                status,
                result.issues()));
        if (result.valid()) {
            return ValidatorAttemptOutcome.passed(attempt);
        }

        return ValidatorAttemptOutcome.failed(
                "Final response violates output_schema: " + summarizeOutputSchemaIssues(result.issues()),
                attempt + 1,
                status == OutputSchemaOutcomeStatus.EXHAUSTED);
    }

    private String actionName(StepAction action) {
        if (action == null || action.stepAction() == null) {
            return "";
        }
        return action.stepAction().name();
    }

    private String serializeFinalResponse(@Nullable com.fasterxml.jackson.databind.JsonNode finalResponseNode) {
        if (finalResponseNode == null || finalResponseNode.isNull()) {
            return "";
        }
        if (finalResponseNode.isTextual()) {
            return finalResponseNode.asText();
        }
        try {
            return objectMapper.writeValueAsString(finalResponseNode);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize FINAL_RESPONSE payload", ex);
        }
    }

    private String summarizeOutputSchemaIssues(List<OutputSchemaValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return "unknown schema validation error";
        }
        return issues.stream()
                .limit(3)
                .map(issue -> {
                    String field = issue.canonicalField() == null || issue.canonicalField().isBlank()
                            ? issue.path()
                            : issue.canonicalField();
                    return field + ": " + issue.message();
                })
                .reduce((left, right) -> left + "; " + right)
                .orElse("unknown schema validation error");
    }

    @Nullable
    private String formatExecutionSummary(Deque<String> executionSummary) {
        if (executionSummary == null || executionSummary.isEmpty()) {
            return null;
        }
        return String.join("\n", executionSummary);
    }

    private void appendExecutionSummary(Deque<String> executionSummary, String summaryLine) {
        if (summaryLine == null || summaryLine.isBlank()) {
            return;
        }
        executionSummary.addLast(summaryLine);
        while (executionSummary.size() > MAX_EXECUTION_SUMMARY_LINES) {
            executionSummary.removeFirst();
        }
    }

    private void unwindMissionFrames(BifrostSession session, int baselineFrameDepth) {
        while (session.getFramesSnapshot().size() > baselineFrameDepth) {
            ExecutionFrame activeFrame = session.peekFrame();
            executionStateService.closeFrame(session, activeFrame, Map.of(
                    "status", "aborted",
                    "reason", "mission-cleanup"));
        }
    }

    private RuntimeException unwrapMissionFailure(RuntimeException runtimeException) {
        if (runtimeException instanceof ToolExecutionException toolExecutionException
                && toolExecutionException.getCause() instanceof RuntimeException nestedRuntimeException
                && (nestedRuntimeException instanceof BifrostStackOverflowException
                || nestedRuntimeException instanceof BifrostMissionTimeoutException)) {
            return nestedRuntimeException;
        }
        return runtimeException;
    }

    private void awaitMissionCleanup(BifrostSession session,
                                     Future<String> mission,
                                     int baselineFrameDepth,
                                     AtomicReference<CleanupOwner> cleanupOwner,
                                     CountDownLatch cleanupComplete) {
        try {
            mission.get(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | java.util.concurrent.CancellationException ignored) {
            // Best effort wait only.
        }
        if (cleanupComplete.getCount() == 0L) {
            return;
        }
        if (cleanupOwner.compareAndSet(CleanupOwner.NONE, CleanupOwner.CALLER)) {
            try {
                unwindMissionFrames(session, baselineFrameDepth);
            } finally {
                cleanupComplete.countDown();
            }
            return;
        }
        try {
            cleanupComplete.await(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, Object> closeMetadata(String status, @Nullable Throwable failure) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("status", Thread.currentThread().isInterrupted() ? "aborted" : status);
        if (failure != null) {
            metadata.put("exceptionType", failure.getClass().getName());
            if (failure.getMessage() != null && !failure.getMessage().isBlank()) {
                metadata.put("message", failure.getMessage());
            }
        }
        return metadata;
    }

    private static String truncate(@Nullable String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private record StepResult(@Nullable String finalResponse,
                              @Nullable String toolResult,
                              @Nullable String summaryLine) {

        boolean isFinalResponse() {
            return finalResponse != null;
        }

        static StepResult finalResponse(String response) {
            return new StepResult(response, null, null);
        }

        static StepResult toolExecuted(String toolResult, String summaryLine) {
            return new StepResult(null, toolResult, summaryLine);
        }
    }

    private record FinalResponseValidationOutcome(StepValidationResult validation,
                                                  int nextLinterAttempt,
                                                  int nextOutputSchemaAttempt,
                                                  boolean exhausted) {

        static FinalResponseValidationOutcome ok(int linterAttempt, int outputSchemaAttempt) {
            return new FinalResponseValidationOutcome(StepValidationResult.ok(), linterAttempt, outputSchemaAttempt, false);
        }
    }

    private record ValidatorAttemptOutcome(StepValidationResult validation,
                                           int nextAttempt,
                                           boolean exhausted) {

        boolean valid() {
            return validation.valid();
        }

        static ValidatorAttemptOutcome passed(int attempt) {
            return new ValidatorAttemptOutcome(StepValidationResult.ok(), attempt, false);
        }

        static ValidatorAttemptOutcome failed(String reason, int nextAttempt, boolean exhausted) {
            return new ValidatorAttemptOutcome(StepValidationResult.rejected(reason), nextAttempt, exhausted);
        }
    }
}
