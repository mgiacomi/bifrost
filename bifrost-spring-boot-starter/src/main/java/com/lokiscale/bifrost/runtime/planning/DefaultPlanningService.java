package com.lokiscale.bifrost.runtime.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.ExecutionFrame;
import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.ModelTraceResult;
import com.lokiscale.bifrost.core.ModelTraceContext;
import com.lokiscale.bifrost.core.PlanStatus;
import com.lokiscale.bifrost.core.PlanTask;
import com.lokiscale.bifrost.core.PlanTaskLinker;
import com.lokiscale.bifrost.core.PlanTaskStatus;
import com.lokiscale.bifrost.core.TraceFrameType;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import com.lokiscale.bifrost.runtime.usage.ModelUsageExtractor;
import com.lokiscale.bifrost.runtime.usage.NoOpSessionUsageService;
import com.lokiscale.bifrost.runtime.usage.SessionUsageService;
import com.lokiscale.bifrost.outputschema.OutputSchemaCallAdvisor;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class DefaultPlanningService implements PlanningService {

    private static final Logger log = LoggerFactory.getLogger(DefaultPlanningService.class);

    private static final ObjectMapper YAML_OBJECT_MAPPER = YAMLMapper.builder().findAndAddModules().build();

    private static String buildPlanningPrompt(String capabilityName, List<ToolCallback> visibleTools) {
        String toolList = (visibleTools == null || visibleTools.isEmpty())
                ? "(none)"
                : visibleTools.stream()
                        .filter(t -> t != null && t.getToolDefinition() != null)
                        .map(t -> "- " + t.getToolDefinition().name())
                        .collect(java.util.stream.Collectors.joining("\n"));
        return """
                Create an ordered flight plan for this mission before execution.
                Return ONLY valid JSON — no markdown, no explanation, no code fences.
                The JSON must match this exact structure:
                {
                  "planId": "<unique string>",
                  "capabilityName": "%s",
                  "createdAt": "<ISO-8601 timestamp, e.g. 2024-01-01T00:00:00Z>",
                  "status": "VALID",
                  "activeTaskId": null,
                  "tasks": [
                    {
                      "taskId": "<unique string>",
                      "title": "<short title>",
                      "status": "PENDING",
                      "capabilityName": "<one of the available sub-skills listed below>",
                      "intent": "<what this task must accomplish>",
                      "dependsOn": [],
                      "expectedOutputs": ["<output description>"],
                      "autoCompletable": false,
                      "note": "<optional note or empty string>"
                    }
                  ]
                }
                Available sub-skills (use these exact names for task capabilityName):
                %s
                Constraints:
                - plan status must be exactly: VALID
                - task status must be exactly: PENDING
                - autoCompletable must be a boolean (true or false)
                - dependsOn must be a JSON array of taskId strings (empty array if no dependencies)
                - expectedOutputs must be a JSON array of strings
                - activeTaskId must be null
                - Return raw JSON only — no additional text before or after
                """.formatted(capabilityName, toolList);
    }

    private final PlanTaskLinker planTaskLinker;
    private final ExecutionStateService executionStateService;
    private final SessionUsageService sessionUsageService;
    private final ModelUsageExtractor modelUsageExtractor;
    private final ObjectMapper objectMapper;

    public DefaultPlanningService(PlanTaskLinker planTaskLinker, ExecutionStateService executionStateService) {
        this(planTaskLinker, executionStateService, new NoOpSessionUsageService(), new ModelUsageExtractor(), defaultObjectMapper());
    }

    public DefaultPlanningService(PlanTaskLinker planTaskLinker,
                                  ExecutionStateService executionStateService,
                                  SessionUsageService sessionUsageService,
                                  ModelUsageExtractor modelUsageExtractor) {
        this(planTaskLinker, executionStateService, sessionUsageService, modelUsageExtractor, defaultObjectMapper());
    }

    DefaultPlanningService(PlanTaskLinker planTaskLinker,
                           ExecutionStateService executionStateService,
                           SessionUsageService sessionUsageService,
                           ModelUsageExtractor modelUsageExtractor,
                           ObjectMapper objectMapper) {
        this.planTaskLinker = Objects.requireNonNull(planTaskLinker, "planTaskLinker must not be null");
        this.executionStateService = Objects.requireNonNull(executionStateService, "executionStateService must not be null");
        this.sessionUsageService = Objects.requireNonNull(sessionUsageService, "sessionUsageService must not be null");
        this.modelUsageExtractor = Objects.requireNonNull(modelUsageExtractor, "modelUsageExtractor must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public Optional<ExecutionPlan> initializePlan(BifrostSession session,
                                                  String objective,
                                                  String capabilityName,
                                                  EffectiveSkillExecutionConfiguration executionConfiguration,
                                                  ChatClient chatClient,
                                                  List<ToolCallback> visibleTools) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(objective, "objective must not be null");
        Objects.requireNonNull(capabilityName, "capabilityName must not be null");
        Objects.requireNonNull(chatClient, "chatClient must not be null");
        log.debug(
                "Initializing plan for capability='{}' chatClientType={} visibleTools={}",
                capabilityName,
                chatClient.getClass().getName(),
                visibleTools == null ? 0 : visibleTools.size());
        ExecutionFrame planningFrame = executionStateService.openFrame(
                session,
                TraceFrameType.PLANNING,
                capabilityName + "#planning",
                Map.of(
                        "provider", executionConfiguration.provider().name(),
                        "providerModel", executionConfiguration.providerModel()));
        String planningFrameStatus = "completed";
        Throwable planningFailure = null;
        try {
            ExecutionFrame modelFrame = executionStateService.openFrame(
                    session,
                    TraceFrameType.MODEL_CALL,
                    capabilityName + "#planning-model",
                    Map.of(
                            "provider", executionConfiguration.provider().name(),
                            "providerModel", executionConfiguration.providerModel(),
                            "segment", "planning"));
            String modelFrameStatus = "completed";
            Throwable modelFailure = null;
            try {
                ModelTraceContext modelTraceContext = new ModelTraceContext(
                        executionConfiguration.provider().name(),
                        executionConfiguration.providerModel(),
                        capabilityName,
                        "planning");
                String planningPrompt = buildPlanningPrompt(capabilityName, visibleTools);
                PlanningTraceResult planningResult = executionStateService.traceModelCall(
                        session,
                        modelFrame,
                        modelTraceContext,
                        Map.of(
                                "system", planningPrompt,
                                "user", objective),
                        markRequestSent -> {
                            Map<String, Object> sentPayload = Map.of(
                                    "system", planningPrompt,
                                    "user", objective);
                            ChatClient.CallResponseSpec responseSpec = chatClient.prompt()
                                    .system(planningPrompt)
                                    .user(objective)
                                    .advisors(spec -> spec.param(OutputSchemaCallAdvisor.PLANNING_CALL_KEY, true))
                                    .call();
                            markRequestSent.accept(sentPayload);
                            ChatResponse chatResponse;
                            String planPayload;
                            try {
                                ChatClientResponse clientResponse = responseSpec.chatClientResponse();
                                chatResponse = clientResponse.chatResponse();
                                planPayload = extractContent(chatResponse);
                                log.debug(
                                        "Planning response retrieved via chatClientResponse() for capability='{}' payloadPreview={}...",
                                        capabilityName,
                                        preview(planPayload));
                            }
                            catch (UnsupportedOperationException ignored) {
                                chatResponse = null;
                                planPayload = responseSpec.content();
                                log.debug(
                                        "Planning response retrieved via content() fallback for capability='{}' payloadPreview={}...",
                                        capabilityName,
                                        preview(planPayload));
                            }
                            ExecutionPlan plan = parsePlan(planPayload, capabilityName);
                            return ModelTraceResult.of(
                                    new PlanningTraceResult(plan, chatResponse),
                                    Map.of("content", planPayload));
                        });
                ExecutionPlan plan = planningResult.plan();
                sessionUsageService.recordModelResponse(
                        session,
                        capabilityName,
                        modelUsageExtractor.extract(planningResult.chatResponse(), objective, planningPrompt, stringifyPlan(plan)));
                executionStateService.storePlan(session, plan);
                executionStateService.logPlanCreated(session, plan);
                return Optional.of(plan);
            }
            catch (RuntimeException ex) {
                modelFailure = ex;
                planningFailure = ex;
                modelFrameStatus = Thread.currentThread().isInterrupted() ? "aborted" : "failed";
                planningFrameStatus = modelFrameStatus;
                throw ex;
            }
            finally {
                executionStateService.closeFrame(session, modelFrame, closeMetadata(modelFrameStatus, modelFailure));
            }
        }
        catch (RuntimeException ex) {
            if (planningFailure == null) {
                planningFailure = ex;
                planningFrameStatus = Thread.currentThread().isInterrupted() ? "aborted" : "failed";
            }
            throw ex;
        }
        finally {
            executionStateService.closeFrame(session, planningFrame, closeMetadata(planningFrameStatus, planningFailure));
        }
    }

    @Override
    public Optional<ExecutionPlan> markToolStarted(BifrostSession session, CapabilityMetadata capability, Map<String, Object> arguments) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(capability, "capability must not be null");
        Map<String, Object> safeArguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        return executionStateService.currentPlan(session)
                .flatMap(plan -> planTaskLinker.linkTask(plan, capability, safeArguments)
                        .map(taskId -> {
                            ExecutionPlan updated = replacePlanTask(
                                    plan,
                                    taskId,
                                    task -> task.bindInProgress("Starting tool " + capability.name()))
                                    .withActiveTask(taskId);
                            executionStateService.storePlan(session, updated);
                            executionStateService.logPlanUpdated(session, updated);
                            return updated;
                        }));
    }

    @Override
    public Optional<ExecutionPlan> markToolCompleted(BifrostSession session,
                                                     String taskId,
                                                     String capabilityName,
                                                     @Nullable Object result) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(capabilityName, "capabilityName must not be null");
        return executionStateService.currentPlan(session)
                .flatMap(plan -> plan.findTask(taskId)
                        .map(task -> {
                            ExecutionPlan updated = replacePlanTask(
                                    plan,
                                    taskId,
                                    current -> current.complete("Completed tool " + capabilityName))
                                    .clearActiveTask();
                            executionStateService.storePlan(session, updated);
                            executionStateService.logPlanUpdated(session, updated);
                            return updated;
                        }))
                .filter(plan -> plan.findTask(taskId)
                        .map(task -> task.status() == PlanTaskStatus.COMPLETED)
                        .orElse(false));
    }

    @Override
    public Optional<ExecutionPlan> markToolFailed(BifrostSession session,
                                                  String taskId,
                                                  String capabilityName,
                                                  RuntimeException ex) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(capabilityName, "capabilityName must not be null");
        Objects.requireNonNull(ex, "ex must not be null");
        return executionStateService.currentPlan(session)
                .flatMap(plan -> plan.findTask(taskId)
                        .map(task -> {
                            ExecutionPlan updated = replacePlanTask(
                                    plan,
                                    taskId,
                                    current -> current.block("Tool " + capabilityName + " failed: " + ex.getClass().getSimpleName()))
                                    .withStatus(PlanStatus.STALE)
                                    .clearActiveTask();
                            executionStateService.storePlan(session, updated);
                            executionStateService.logPlanUpdated(session, updated);
                            return updated;
                        }));
    }


    private Map<String, Object> closeMetadata(String status, @Nullable Throwable failure) {
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("status", Thread.currentThread().isInterrupted() ? "aborted" : status);
        if (failure != null) {
            metadata.put("exceptionType", failure.getClass().getName());
            if (failure.getMessage() != null && !failure.getMessage().isBlank()) {
                metadata.put("message", failure.getMessage());
            }
        }
        return metadata;
    }

    private ExecutionPlan replacePlanTask(ExecutionPlan plan,
                                          String taskId,
                                          java.util.function.Function<PlanTask, PlanTask> updater) {
        return plan.updateTask(taskId, updater);
    }

    @Nullable
    private static String extractContent(@Nullable ChatResponse chatResponse) {
        return java.util.Optional.ofNullable(chatResponse)
                .map(ChatResponse::getResult)
                .map(Generation::getOutput)
                .map(AbstractMessage::getText)
                .orElse(null);
    }

    private ExecutionPlan parsePlan(String payload, String capabilityName) {
        String unwrapped = unwrapFencedBlock(payload);
        log.debug(
                "Parsing plan for capability='{}' looksLikeJson={} payloadPreview={}...",
                capabilityName,
                looksLikeJson(unwrapped),
                preview(unwrapped));
        try {
            JsonNode tree = parsePlanTree(unwrapped, capabilityName);
            normalizePlanTree(tree);
            return objectMapper.treeToValue(tree, ExecutionPlan.class);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Failed to parse planning response for capability '" + capabilityName
                            + "' as JSON or YAML. Payload preview: " + preview(unwrapped), ex);
        }
    }

    private JsonNode parsePlanTree(String payload, String capabilityName) throws JsonProcessingException {
        if (looksLikeJson(payload)) {
            try {
                return objectMapper.readTree(payload);
            }
            catch (JsonProcessingException ex) {
                log.debug("JSON plan parsing failed for capability='{}'; trying YAML tree parsing", capabilityName, ex);
            }
        }
        return YAML_OBJECT_MAPPER.readTree(payload);
    }

    private void normalizePlanTree(JsonNode tree) {
        if (!(tree instanceof ObjectNode planNode)) {
            return;
        }
        normalizePlanStatus(planNode);
        normalizeTaskStatuses(planNode.get("tasks"));
    }

    private void normalizePlanStatus(ObjectNode planNode) {
        JsonNode statusNode = planNode.get("status");
        if (statusNode == null || !statusNode.isTextual()) {
            return;
        }
        String normalizedStatus = normalizePlanStatusValue(statusNode.asText());
        if (normalizedStatus != null) {
            planNode.put("status", normalizedStatus);
        }
    }

    private void normalizeTaskStatuses(@Nullable JsonNode tasksNode) {
        if (!(tasksNode instanceof ArrayNode taskArray)) {
            return;
        }
        for (JsonNode taskNode : taskArray) {
            if (!(taskNode instanceof ObjectNode objectTaskNode)) {
                continue;
            }
            JsonNode statusNode = objectTaskNode.get("status");
            if (statusNode == null || !statusNode.isTextual()) {
                continue;
            }
            String normalizedStatus = normalizeTaskStatusValue(statusNode.asText());
            if (normalizedStatus != null) {
                objectTaskNode.put("status", normalizedStatus);
            }
        }
    }

    @Nullable
    private String normalizePlanStatusValue(String rawStatus) {
        String normalized = canonicalizeEnumToken(rawStatus);
        return switch (normalized) {
            case "VALID", "STALE", "INVALID" -> normalized;
            case "EXECUTED", "EXECUTING", "COMPLETED", "COMPLETE", "SUCCESS", "SUCCEEDED", "DONE",
                    "READY", "PENDING", "IN_PROGRESS", "INPROGRESS",
                    "ACTIVE", "RUNNING", "OPEN", "NEW", "CURRENT", "ONGOING", "STARTED" -> PlanStatus.VALID.name();
            case "FAILED", "FAILURE", "ERROR", "BLOCKED" -> PlanStatus.INVALID.name();
            default -> null;
        };
    }

    @Nullable
    private String normalizeTaskStatusValue(String rawStatus) {
        String normalized = canonicalizeEnumToken(rawStatus);
        return switch (normalized) {
            case "PENDING", "IN_PROGRESS", "COMPLETED", "BLOCKED" -> normalized;
            case "SUCCESS", "SUCCEEDED", "DONE", "COMPLETE", "EXECUTED" -> PlanTaskStatus.COMPLETED.name();
            case "RUNNING", "ACTIVE", "EXECUTING", "INPROGRESS" -> PlanTaskStatus.IN_PROGRESS.name();
            case "WAITING", "READY", "TODO", "NEW", "OPEN", "QUEUED", "NOT_STARTED" -> PlanTaskStatus.PENDING.name();
            case "FAILED", "FAILURE", "ERROR", "INVALID", "STALE" -> PlanTaskStatus.BLOCKED.name();
            default -> null;
        };
    }

    private String canonicalizeEnumToken(String rawStatus) {
        return rawStatus == null
                ? ""
                : rawStatus.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private String unwrapFencedBlock(String payload) {
        String safePayload = payload == null ? "" : payload.trim();
        if (safePayload.startsWith("```")) {
            int firstNewline = safePayload.indexOf('\n');
            int lastFence = safePayload.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return safePayload.substring(firstNewline + 1, lastFence).trim();
            }
        }
        // Strip leading YAML document marker
        if (safePayload.startsWith("---")) {
            safePayload = safePayload.substring(3).trim();
        }
        return safePayload;
    }

    private boolean looksLikeJson(String payload) {
        return payload.startsWith("{") || payload.startsWith("[");
    }

    private String preview(String payload) {
        if (payload == null || payload.isBlank()) {
            return "<empty>";
        }
        String normalized = payload.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200);
    }

    private static ObjectMapper defaultObjectMapper() {
        return JsonMapper.builder().findAndAddModules().build();
    }

    private String stringifyPlan(ExecutionPlan plan) {
        return plan == null ? "" : plan.toString();
    }

    private record PlanningTraceResult(ExecutionPlan plan, @Nullable ChatResponse chatResponse) {
    }
}
