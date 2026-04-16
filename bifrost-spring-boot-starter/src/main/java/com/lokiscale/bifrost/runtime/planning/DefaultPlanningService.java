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
import com.lokiscale.bifrost.core.ModelTraceContext;
import com.lokiscale.bifrost.core.ModelTraceResult;
import com.lokiscale.bifrost.core.MissionInputMessageFormatter;
import com.lokiscale.bifrost.core.PlanStatus;
import com.lokiscale.bifrost.core.PlanTask;
import com.lokiscale.bifrost.core.PlanTaskLinker;
import com.lokiscale.bifrost.core.PlanTaskStatus;
import com.lokiscale.bifrost.core.TraceFrameType;
import com.lokiscale.bifrost.core.TraceRecordType;
import com.lokiscale.bifrost.outputschema.OutputSchemaCallAdvisor;
import com.lokiscale.bifrost.runtime.evidence.EvidenceContract;
import com.lokiscale.bifrost.runtime.evidence.EvidenceCoverageResult;
import com.lokiscale.bifrost.runtime.evidence.EvidenceCoverageValidator;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import com.lokiscale.bifrost.runtime.usage.ModelUsageExtractor;
import com.lokiscale.bifrost.runtime.usage.NoOpSessionUsageService;
import com.lokiscale.bifrost.runtime.usage.SessionUsageService;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DefaultPlanningService implements PlanningService
{
    private static final Logger log = LoggerFactory.getLogger(DefaultPlanningService.class);

    private static final ObjectMapper YAML_OBJECT_MAPPER = YAMLMapper.builder().findAndAddModules().build();
    private static final int MAX_PLAN_QUALITY_RETRIES = 1;

    private final PlanTaskLinker planTaskLinker;
    private final ExecutionStateService executionStateService;
    private final SessionUsageService sessionUsageService;
    private final ModelUsageExtractor modelUsageExtractor;
    private final ObjectMapper objectMapper;
    private final PlanQualityValidator planQualityValidator;
    private final EvidenceCoverageValidator evidenceCoverageValidator;

    public DefaultPlanningService(PlanTaskLinker planTaskLinker, ExecutionStateService executionStateService)
    {
        this(
                planTaskLinker,
                executionStateService,
                new NoOpSessionUsageService(),
                new ModelUsageExtractor(),
                defaultObjectMapper(),
                new PlanQualityValidator(),
                new EvidenceCoverageValidator());
    }

    public DefaultPlanningService(PlanTaskLinker planTaskLinker,
            ExecutionStateService executionStateService,
            SessionUsageService sessionUsageService,
            ModelUsageExtractor modelUsageExtractor)
    {
        this(
                planTaskLinker,
                executionStateService,
                sessionUsageService,
                modelUsageExtractor,
                defaultObjectMapper(),
                new PlanQualityValidator(),
                new EvidenceCoverageValidator());
    }

    DefaultPlanningService(PlanTaskLinker planTaskLinker,
            ExecutionStateService executionStateService,
            SessionUsageService sessionUsageService,
            ModelUsageExtractor modelUsageExtractor,
            ObjectMapper objectMapper,
            PlanQualityValidator planQualityValidator,
            EvidenceCoverageValidator evidenceCoverageValidator)
    {
        this.planTaskLinker = Objects.requireNonNull(planTaskLinker, "planTaskLinker must not be null");
        this.executionStateService = Objects.requireNonNull(executionStateService, "executionStateService must not be null");
        this.sessionUsageService = Objects.requireNonNull(sessionUsageService, "sessionUsageService must not be null");
        this.modelUsageExtractor = Objects.requireNonNull(modelUsageExtractor, "modelUsageExtractor must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.planQualityValidator = Objects.requireNonNull(planQualityValidator, "planQualityValidator must not be null");
        this.evidenceCoverageValidator = Objects.requireNonNull(evidenceCoverageValidator, "evidenceCoverageValidator must not be null");
    }

    @Override
    public Optional<ExecutionPlan> initializePlan(BifrostSession session,
            String objective,
            @Nullable Map<String, Object> missionInput,
            YamlSkillDefinition definition,
            ChatClient chatClient,
            List<ToolCallback> visibleTools)
    {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(objective, "objective must not be null");
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(chatClient, "chatClient must not be null");
        String capabilityName = definition.manifest().getName();
        var executionConfiguration = definition.executionConfiguration();

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
        try
        {
            return initializePlanWithQualityChecks(
                    session,
                    objective,
                    missionInput,
                    definition,
                    chatClient,
                    visibleTools,
                    planningFrame);
        }
        catch (RuntimeException ex)
        {
            planningFailure = ex;
            planningFrameStatus = Thread.currentThread().isInterrupted() ? "aborted" : "failed";
            throw ex;
        }
        finally
        {
            executionStateService.closeFrame(session, planningFrame, closeMetadata(planningFrameStatus, planningFailure));
        }
    }

    @Override
    public Optional<ExecutionPlan> markToolStarted(BifrostSession session, CapabilityMetadata capability, Map<String, Object> arguments)
    {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(capability, "capability must not be null");
        Map<String, Object> safeArguments = arguments == null ? Map.of() : Map.copyOf(arguments);

        return executionStateService.currentPlan(session)
                .flatMap(plan -> planTaskLinker.linkTask(plan, capability, safeArguments)
                        .map(taskId ->
                        {
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
    public Optional<ExecutionPlan> markTaskStarted(BifrostSession session,
            String taskId,
            String capabilityName,
            @Nullable Map<String, Object> arguments)
    {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(capabilityName, "capabilityName must not be null");

        return executionStateService.currentPlan(session)
                .flatMap(plan -> plan.findTask(taskId)
                        .map(task ->
                        {
                            requireBoundCapability(task, capabilityName);
                            ExecutionPlan updated = replacePlanTask(
                                    plan,
                                    taskId,
                                    current -> current.bindInProgress("Starting tool " + capabilityName))
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
            @Nullable Object result,
            EvidenceContract evidenceContract)
    {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(capabilityName, "capabilityName must not be null");

        Optional<ExecutionPlan> updatedPlan = executionStateService.currentPlan(session)
                .flatMap(plan -> plan.findTask(taskId)
                        .map(task ->
                        {
                            requireBoundCapability(task, capabilityName);
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

        if (updatedPlan.isPresent() && evidenceContract != null && !evidenceContract.isEmpty())
        {
            Set<String> evidenceTypes = evidenceContract.evidenceProducedByTool(capabilityName);
            if (!evidenceTypes.isEmpty())
            {
                executionStateService.recordProducedEvidence(session, capabilityName, taskId, false, evidenceTypes);
            }
        }
        return updatedPlan;
    }

    @Override
    public Optional<ExecutionPlan> markToolFailed(BifrostSession session,
            String taskId,
            String capabilityName,
            RuntimeException ex)
    {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(capabilityName, "capabilityName must not be null");
        Objects.requireNonNull(ex, "ex must not be null");

        return executionStateService.currentPlan(session)
                .flatMap(plan -> plan.findTask(taskId)
                        .map(task ->
                        {
                            requireBoundCapability(task, capabilityName);
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

    private Optional<ExecutionPlan> initializePlanWithQualityChecks(BifrostSession session,
            String objective,
            @Nullable Map<String, Object> missionInput,
            YamlSkillDefinition definition,
            ChatClient chatClient,
            List<ToolCallback> visibleTools,
            ExecutionFrame planningFrame)
    {
        String capabilityName = definition.manifest().getName();
        var executionConfiguration = definition.executionConfiguration();
        EvidenceContract evidenceContract = definition.evidenceContract();
        String retryFeedback = null;
        int retryCount = 0;

        while (true)
        {
            PlanningAttemptResult attemptResult = requestPlanAttempt(
                    session,
                    objective,
                    missionInput,
                    capabilityName,
                    executionConfiguration,
                    chatClient,
                    visibleTools,
                    retryFeedback,
                    evidenceContract);

            sessionUsageService.recordModelResponse(
                    session,
                    capabilityName,
                    modelUsageExtractor.extract(
                            attemptResult.chatResponse(),
                            attemptResult.userMessage(),
                            attemptResult.prompt(),
                            stringifyPlan(attemptResult.plan())));

            PlanQualityValidationResult validation = planQualityValidator.validate(attemptResult.plan(), visibleTools);
            EvidenceCoverageResult evidenceCoverage = evidenceCoverageValidator.validatePlanCoverage(
                    attemptResult.plan(),
                    evidenceContract);

            boolean hasDeterministicEvidenceGap = !evidenceCoverage.complete();
            if ((validation.hasErrors() || hasDeterministicEvidenceGap) && retryCount < MAX_PLAN_QUALITY_RETRIES)
            {
                recordPlanQualityEvent(session, planningFrame, TraceRecordType.PLAN_VALIDATION_FAILED, validation.errors(), retryCount);
                if (hasDeterministicEvidenceGap)
                {
                    recordEvidenceCoverageEvent(session, planningFrame, TraceRecordType.PLAN_VALIDATION_FAILED, evidenceCoverage, retryCount);
                }

                retryFeedback = mergeRetryFeedback(validation.retryFeedback(), evidenceCoverage.retryFeedback());
                recordPlanQualityEvent(session, planningFrame, TraceRecordType.PLAN_RETRY_REQUESTED, validation.errors(), retryCount);
                if (hasDeterministicEvidenceGap)
                {
                    recordEvidenceCoverageEvent(session, planningFrame, TraceRecordType.PLAN_RETRY_REQUESTED, evidenceCoverage, retryCount);
                }

                retryCount++;
                continue;
            }

            if (hasDeterministicEvidenceGap)
            {
                recordEvidenceCoverageEvent(session, planningFrame, TraceRecordType.PLAN_VALIDATION_FAILED, evidenceCoverage, retryCount);
                throw new IllegalStateException(
                        "Evidence coverage validation failed for skill '%s': %s"
                                .formatted(capabilityName, evidenceCoverage.retryFeedback()));
            }

            if (validation.hasWarnings())
            {
                recordPlanQualityEvent(session, planningFrame, TraceRecordType.PLAN_QUALITY_WARNING, validation.warnings(), retryCount);
            }

            if (validation.hasErrors())
            {
                recordPlanQualityEvent(session, planningFrame, TraceRecordType.PLAN_QUALITY_WARNING, validation.errors(), retryCount);
            }

            executionStateService.storePlan(session, attemptResult.plan());
            executionStateService.logPlanCreated(session, attemptResult.plan());
            return Optional.of(attemptResult.plan());
        }
    }

    private void recordEvidenceCoverageEvent(BifrostSession session,
            ExecutionFrame planningFrame,
            TraceRecordType recordType,
            EvidenceCoverageResult coverage,
            int retryCount)
    {
        if (coverage == null || coverage.complete())
        {
            return;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("retryCount", retryCount);
        metadata.put("issueCodes", List.of("evidence-coverage"));
        metadata.put("severity", "ERROR");
        metadata.put("claims", coverage.evaluatedClaims());
        metadata.put("missingEvidence", coverage.issues().stream()
                .flatMap(issue -> issue.missingEvidence().stream())
                .distinct()
                .toList());

        executionStateService.recordPlanningEvent(session, planningFrame, recordType, metadata, coverage.issues());
    }

    private PlanningAttemptResult requestPlanAttempt(BifrostSession session,
            String objective,
            @Nullable Map<String, Object> missionInput,
            String capabilityName,
            EffectiveSkillExecutionConfiguration executionConfiguration,
            ChatClient chatClient,
            List<ToolCallback> visibleTools,
            @Nullable String retryFeedback,
            @Nullable EvidenceContract evidenceContract)
    {
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
        String planningPrompt = buildPlanningPrompt(capabilityName, visibleTools, retryFeedback, evidenceContract);
        String planningUserMessage = MissionInputMessageFormatter.buildUserMessage(objective, missionInput);

        try
        {
            ModelTraceContext modelTraceContext = new ModelTraceContext(
                    executionConfiguration.provider().name(),
                    executionConfiguration.providerModel(),
                    capabilityName,
                    "planning");

            PlanningTraceResult planningResult = executionStateService.traceModelCall(
                    session,
                    modelFrame,
                    modelTraceContext,
                    Map.of(
                            "system", planningPrompt,
                            "user", planningUserMessage),
                    markRequestSent ->
                    {
                        Map<String, Object> sentPayload = Map.of(
                                "system", planningPrompt,
                                "user", planningUserMessage);
                        ChatClient.CallResponseSpec responseSpec = chatClient.prompt()
                                .system(planningPrompt)
                                .user(planningUserMessage)
                                .advisors(spec -> spec.param(OutputSchemaCallAdvisor.PLANNING_CALL_KEY, true))
                                .call();
                        markRequestSent.accept(sentPayload);
                        ChatResponse chatResponse;
                        String planPayload;

                        try
                        {
                            ChatClientResponse clientResponse = responseSpec.chatClientResponse();
                            chatResponse = clientResponse.chatResponse();
                            planPayload = extractContent(chatResponse);
                            log.debug(
                                    "Planning response retrieved via chatClientResponse() for capability='{}' payloadPreview={}...",
                                    capabilityName,
                                    preview(planPayload));
                        }
                        catch (UnsupportedOperationException ignored)
                        {
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

            return new PlanningAttemptResult(planningResult.plan(), planningResult.chatResponse(), planningPrompt, planningUserMessage);
        }
        catch (RuntimeException ex)
        {
            modelFailure = ex;
            modelFrameStatus = Thread.currentThread().isInterrupted() ? "aborted" : "failed";
            throw ex;
        }
        finally
        {
            executionStateService.closeFrame(session, modelFrame, closeMetadata(modelFrameStatus, modelFailure));
        }
    }

    private void recordPlanQualityEvent(BifrostSession session,
            ExecutionFrame planningFrame,
            TraceRecordType recordType,
            List<PlanQualityIssue> issues,
            int retryCount)
    {
        if (issues == null || issues.isEmpty())
        {
            return;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("retryCount", retryCount);
        metadata.put("severity", issues.getFirst().severity().name());
        metadata.put("issueCodes", issues.stream().map(PlanQualityIssue::code).distinct().toList());
        List<Map<String, Object>> payload = issues.stream()
                .map(issue -> Map.<String, Object>of(
                        "code", issue.code(),
                        "severity", issue.severity().name(),
                        "message", issue.message()))
                .toList();

        executionStateService.recordPlanningEvent(session, planningFrame, recordType, metadata, payload);
    }

    private static String buildPlanningPrompt(String capabilityName,
            List<ToolCallback> visibleTools,
            @Nullable String retryFeedback,
            @Nullable EvidenceContract evidenceContract)
    {
        String toolList = (visibleTools == null || visibleTools.isEmpty())
                ? "(none)"
                : visibleTools.stream()
                        .filter(t -> t != null && t.getToolDefinition() != null)
                        .map(DefaultPlanningService::describeTool)
                        .collect(Collectors.joining("\n"));

        String retrySection = retryFeedback == null || retryFeedback.isBlank()
                ? ""
                : """

                        Previous plan was too weak. Correct these issues in the next plan:
                        %s
                        """.formatted(retryFeedback);

        String evidenceConstraints = "";
        if (evidenceContract != null && !evidenceContract.isEmpty())
        {
            StringBuilder builder = new StringBuilder();
            List<String> sortedClaims = new ArrayList<>(evidenceContract.claims());
            Collections.sort(sortedClaims);

            for (String claim : sortedClaims)
            {
                Set<String> reqEvidence = evidenceContract.evidenceForClaim(claim);
                List<String> tools = evidenceContract.evidenceByTool().entrySet().stream()
                        .filter(e -> e.getValue().stream().anyMatch(reqEvidence::contains))
                        .map(Map.Entry::getKey)
                        .sorted()
                        .toList();

                if (!tools.isEmpty())
                {
                    builder.append("- You MUST explicitly include a task that uses the ")
                            .append(tools)
                            .append(" tool(s) to help determine the value for the '")
                            .append(claim)
                            .append("' output field.\n");
                }
            }

            if (!builder.isEmpty())
            {
                evidenceConstraints = "Evidence Constraints:\n" + builder.toString();
            }
        }

        return """
                Create an ordered flight plan for this mission before execution.
                Return ONLY valid JSON - no markdown, no explanation, no code fences.
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
                - Return raw JSON only - no additional text before or after
                Planning quality rules:
                - Each task must have a distinct purpose that advances the mission.
                - Bind each task to the tool that best matches that task's intent.
                - If multiple tools are available, consider whether the mission requires evidence from more than one.
                - Do not create report or conclusion tasks that are bound to extraction-only or lookup-only tools unless that tool is genuinely the right fit.
                - Gather enough evidence to support the final answer before the mission is complete.
                %s%s""".formatted(capabilityName, toolList, evidenceConstraints, retrySection);
    }

    private String mergeRetryFeedback(@Nullable String qualityFeedback, @Nullable String evidenceFeedback)
    {
        if ((qualityFeedback == null || qualityFeedback.isBlank())
                && (evidenceFeedback == null || evidenceFeedback.isBlank()))
        {
            return null;
        }
        if (qualityFeedback == null || qualityFeedback.isBlank())
        {
            return evidenceFeedback;
        }
        if (evidenceFeedback == null || evidenceFeedback.isBlank())
        {
            return qualityFeedback;
        }
        return qualityFeedback + "\n" + evidenceFeedback;
    }

    private static String describeTool(ToolCallback callback)
    {
        ToolDefinition definition = callback.getToolDefinition();
        String description = definition.description();

        if (description == null || description.isBlank())
        {
            description = "No description provided.";
        }

        return "- %s: %s".formatted(definition.name(), description);
    }

    private Map<String, Object> closeMetadata(String status, @Nullable Throwable failure)
    {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("status", Thread.currentThread().isInterrupted() ? "aborted" : status);

        if (failure != null)
        {
            metadata.put("exceptionType", failure.getClass().getName());
            if (failure.getMessage() != null && !failure.getMessage().isBlank())
            {
                metadata.put("message", failure.getMessage());
            }
        }

        return metadata;
    }

    private ExecutionPlan replacePlanTask(ExecutionPlan plan, String taskId, Function<PlanTask, PlanTask> updater)
    {
        return plan.updateTask(taskId, updater);
    }

    @Nullable
    private static String extractContent(@Nullable ChatResponse chatResponse)
    {
        return Optional.ofNullable(chatResponse)
                .map(ChatResponse::getResult)
                .map(Generation::getOutput)
                .map(AbstractMessage::getText)
                .orElse(null);
    }

    private ExecutionPlan parsePlan(String payload, String capabilityName)
    {
        String unwrapped = unwrapFencedBlock(payload);
        log.debug(
                "Parsing plan for capability='{}' looksLikeJson={} payloadPreview={}...",
                capabilityName,
                looksLikeJson(unwrapped),
                preview(unwrapped));

        try
        {
            JsonNode tree = parsePlanTree(unwrapped, capabilityName);
            normalizePlanTree(tree);
            return objectMapper.treeToValue(tree, ExecutionPlan.class);
        }
        catch (JsonProcessingException ex)
        {
            throw new IllegalStateException(
                    "Failed to parse planning response for capability '" + capabilityName
                            + "' as JSON or YAML. Payload preview: " + preview(unwrapped),
                    ex);
        }
    }

    private void requireBoundCapability(PlanTask task, String capabilityName)
    {
        if (!Objects.equals(task.capabilityName(), capabilityName))
        {
            throw new IllegalStateException(
                    "Task '%s' is bound to capability '%s' but received '%s'."
                            .formatted(task.taskId(), task.capabilityName(), capabilityName));
        }
    }

    private JsonNode parsePlanTree(String payload, String capabilityName) throws JsonProcessingException
    {
        if (looksLikeJson(payload))
        {
            try
            {
                return objectMapper.readTree(payload);
            }
            catch (JsonProcessingException ex)
            {
                log.debug("JSON plan parsing failed for capability='{}'; trying YAML tree parsing", capabilityName, ex);
            }
        }

        return YAML_OBJECT_MAPPER.readTree(payload);
    }

    private void normalizePlanTree(JsonNode tree)
    {
        if (!(tree instanceof ObjectNode planNode))
        {
            return;
        }

        normalizePlanStatus(planNode);
        normalizeTaskStatuses(planNode.get("tasks"));
    }

    private void normalizePlanStatus(ObjectNode planNode)
    {
        JsonNode statusNode = planNode.get("status");
        if (statusNode == null || !statusNode.isTextual())
        {
            return;
        }

        String normalizedStatus = normalizePlanStatusValue(statusNode.asText());
        if (normalizedStatus != null)
        {
            planNode.put("status", normalizedStatus);
        }
    }

    private void normalizeTaskStatuses(@Nullable JsonNode tasksNode)
    {
        if (!(tasksNode instanceof ArrayNode taskArray))
        {
            return;
        }
        for (JsonNode taskNode : taskArray)
        {
            if (!(taskNode instanceof ObjectNode objectTaskNode))
            {
                continue;
            }
            JsonNode statusNode = objectTaskNode.get("status");
            if (statusNode == null || !statusNode.isTextual())
            {
                continue;
            }
            String normalizedStatus = normalizeTaskStatusValue(statusNode.asText());
            if (normalizedStatus != null)
            {
                objectTaskNode.put("status", normalizedStatus);
            }
        }
    }

    @Nullable
    private String normalizePlanStatusValue(String rawStatus)
    {
        String normalized = canonicalizeEnumToken(rawStatus);
        return switch (normalized)
        {
            case "VALID", "STALE", "INVALID" -> normalized;
            case "EXECUTED", "EXECUTING", "COMPLETED", "COMPLETE", "SUCCESS", "SUCCEEDED", "DONE", "READY", "PENDING", "IN_PROGRESS", "INPROGRESS", "ACTIVE", "RUNNING", "OPEN", "NEW", "CURRENT", "ONGOING", "STARTED" -> PlanStatus.VALID.name();
            case "FAILED", "FAILURE", "ERROR", "BLOCKED" -> PlanStatus.INVALID.name();
            default -> null;
        };
    }

    @Nullable
    private String normalizeTaskStatusValue(String rawStatus)
    {
        String normalized = canonicalizeEnumToken(rawStatus);
        return switch (normalized)
        {
            case "PENDING", "IN_PROGRESS", "COMPLETED", "BLOCKED" -> normalized;
            case "SUCCESS", "SUCCEEDED", "DONE", "COMPLETE", "EXECUTED" -> PlanTaskStatus.COMPLETED.name();
            case "RUNNING", "ACTIVE", "EXECUTING", "INPROGRESS" -> PlanTaskStatus.IN_PROGRESS.name();
            case "WAITING", "READY", "TODO", "NEW", "OPEN", "QUEUED", "NOT_STARTED" -> PlanTaskStatus.PENDING.name();
            case "FAILED", "FAILURE", "ERROR", "INVALID", "STALE" -> PlanTaskStatus.BLOCKED.name();
            default -> null;
        };
    }

    private String canonicalizeEnumToken(String rawStatus)
    {
        return rawStatus == null
                ? ""
                : rawStatus.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private String unwrapFencedBlock(String payload)
    {
        String safePayload = payload == null ? "" : payload.trim();
        if (safePayload.startsWith("```"))
        {
            int firstNewline = safePayload.indexOf('\n');
            int lastFence = safePayload.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline)
            {
                return safePayload.substring(firstNewline + 1, lastFence).trim();
            }
        }

        if (safePayload.startsWith("---"))
        {
            safePayload = safePayload.substring(3).trim();
        }

        return safePayload;
    }

    private boolean looksLikeJson(String payload)
    {
        return payload.startsWith("{") || payload.startsWith("[");
    }

    private String preview(String payload)
    {
        if (payload == null || payload.isBlank())
        {
            return "<empty>";
        }

        String normalized = payload.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200);
    }

    private static ObjectMapper defaultObjectMapper()
    {
        return JsonMapper.builder().findAndAddModules().build();
    }

    private String stringifyPlan(ExecutionPlan plan)
    {
        return plan == null ? "" : plan.toString();
    }

    private record PlanningTraceResult(ExecutionPlan plan, @Nullable ChatResponse chatResponse)
    {
    }

    private record PlanningAttemptResult(ExecutionPlan plan,
            @Nullable ChatResponse chatResponse,
            String prompt,
            String userMessage)
    {
    }
}
