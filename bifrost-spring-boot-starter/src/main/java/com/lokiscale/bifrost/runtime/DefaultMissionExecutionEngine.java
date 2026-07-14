package com.lokiscale.bifrost.runtime;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.BifrostStackOverflowException;
import com.lokiscale.bifrost.core.ExecutionFrame;
import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.ModelTraceResult;
import com.lokiscale.bifrost.core.ModelTraceContext;
import com.lokiscale.bifrost.runtime.attachment.DefaultMissionInputMaterializer;
import com.lokiscale.bifrost.runtime.attachment.MissionInputMaterializer;
import com.lokiscale.bifrost.runtime.attachment.MissionUserMessageSender;
import com.lokiscale.bifrost.runtime.attachment.RenderedMissionInput;
import com.lokiscale.bifrost.runtime.attachment.SpringAiMissionUserMessageSender;
import com.lokiscale.bifrost.core.PlanTaskStatus;
import com.lokiscale.bifrost.core.SessionContextRunner;
import com.lokiscale.bifrost.core.TraceFrameType;
import com.lokiscale.bifrost.runtime.planning.PlanningService;
import com.lokiscale.bifrost.runtime.prompt.SkillPromptComposer;
import com.lokiscale.bifrost.runtime.prompt.SkillPromptComposition;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import com.lokiscale.bifrost.runtime.usage.ModelUsageExtractor;
import com.lokiscale.bifrost.runtime.usage.NoOpSessionUsageService;
import com.lokiscale.bifrost.runtime.usage.SessionUsageService;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import com.lokiscale.bifrost.vfs.DefaultRefResolver;
import com.lokiscale.bifrost.vfs.SessionLocalVirtualFileSystem;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.time.Duration;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class DefaultMissionExecutionEngine implements MissionExecutionEngine
{
    private enum CleanupOwner
    {
        NONE,
        WORKER,
        CALLER
    }

    private final PlanningService planningService;
    private final ExecutionStateService executionStateService;
    private final Duration missionTimeout;
    private final ExecutorService missionExecutor;
    private final SessionUsageService sessionUsageService;
    private final ModelUsageExtractor modelUsageExtractor;
    private final MissionInputMaterializer missionInputMaterializer;
    private final MissionUserMessageSender missionUserMessageSender;

    public DefaultMissionExecutionEngine(PlanningService planningService,
            ExecutionStateService executionStateService,
            Duration missionTimeout,
            ExecutorService missionExecutor)
    {
        this(planningService, executionStateService, missionTimeout, missionExecutor, new NoOpSessionUsageService(), new ModelUsageExtractor());
    }

    public DefaultMissionExecutionEngine(PlanningService planningService,
            ExecutionStateService executionStateService,
            Duration missionTimeout,
            ExecutorService missionExecutor,
            SessionUsageService sessionUsageService,
            ModelUsageExtractor modelUsageExtractor)
    {
        this(planningService, executionStateService, missionTimeout, missionExecutor, sessionUsageService,
                modelUsageExtractor, defaultMaterializer(), new SpringAiMissionUserMessageSender());
    }

    public DefaultMissionExecutionEngine(PlanningService planningService,
            ExecutionStateService executionStateService,
            Duration missionTimeout,
            ExecutorService missionExecutor,
            SessionUsageService sessionUsageService,
            ModelUsageExtractor modelUsageExtractor,
            MissionInputMaterializer missionInputMaterializer,
            MissionUserMessageSender missionUserMessageSender)
    {
        this.planningService = Objects.requireNonNull(planningService, "planningService must not be null");
        this.executionStateService = Objects.requireNonNull(executionStateService, "executionStateService must not be null");
        this.missionTimeout = Objects.requireNonNull(missionTimeout, "missionTimeout must not be null");
        this.missionExecutor = Objects.requireNonNull(missionExecutor, "missionExecutor must not be null");
        this.sessionUsageService = Objects.requireNonNull(sessionUsageService, "sessionUsageService must not be null");
        this.modelUsageExtractor = Objects.requireNonNull(modelUsageExtractor, "modelUsageExtractor must not be null");
        this.missionInputMaterializer = Objects.requireNonNull(missionInputMaterializer, "missionInputMaterializer must not be null");
        this.missionUserMessageSender = Objects.requireNonNull(missionUserMessageSender, "missionUserMessageSender must not be null");
    }

    public String executeMission(BifrostSession session,
            YamlSkillDefinition definition,
            String objective,
            @Nullable Map<String, Object> missionInput,
            ChatClient chatClient,
            List<ToolCallback> visibleTools,
            boolean planningEnabled,
            @Nullable Authentication authentication)
    {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(objective, "objective must not be null");
        Objects.requireNonNull(chatClient, "chatClient must not be null");
        Objects.requireNonNull(visibleTools, "visibleTools must not be null");
        String skillName = definition.manifest().getName();
        EffectiveSkillExecutionConfiguration executionConfiguration = definition.requireExecutionConfiguration();
        int baselineFrameDepth = session.getFramesSnapshot().size();
        AtomicReference<CleanupOwner> cleanupOwner = new AtomicReference<>(CleanupOwner.NONE);
        CountDownLatch cleanupComplete = new CountDownLatch(1);

        Callable<String> missionCall = () -> SessionContextRunner.callWithSession(session, () ->
        {
            try
            {
                sessionUsageService.recordMissionStart(session, skillName);

                RenderedMissionInput renderedInput = missionInputMaterializer.materialize(session, definition, objective, missionInput);
                String userMessage = renderedInput.userText();
                if (planningEnabled)
                {
                    planningService.initializePlan(session, objective, planningInput(missionInput, renderedInput), definition, chatClient, visibleTools);
                }

                SkillPromptComposition promptComposition = executionStateService.currentPlan(session)
                        .map(plan -> SkillPromptComposer.composePlannedExecutionPrompt(definition, buildPlannedExecutionPrompt(plan)))
                        .orElseGet(() -> SkillPromptComposer.composeDefaultExecutionPrompt(definition));
                String executionPrompt = promptComposition.systemPrompt();

                ExecutionFrame modelFrame = executionStateService.openFrame(
                        session,
                        TraceFrameType.MODEL_CALL,
                        skillName + "#mission-model",
                        Map.of(
                                "provider", executionConfiguration.provider().name(),
                                "providerModel", executionConfiguration.providerModel()));

                String modelFrameStatus = "completed";
                Throwable modelFailure = null;

                try
                {
                    ModelTraceContext modelTraceContext = new ModelTraceContext(
                            executionConfiguration.provider().name(),
                            executionConfiguration.providerModel(),
                            skillName,
                            "mission");

                    MissionTraceResult missionResult = executionStateService.traceModelCall(
                            session,
                            modelFrame,
                            modelTraceContext,
                            buildMissionPreparedPayload(promptComposition, renderedInput),
                            markRequestSent ->
                            {
                                Map<String, Object> sentPayload = buildMissionSentPayload(promptComposition, renderedInput, visibleTools);
                                ChatClient.CallResponseSpec responseSpec = missionUserMessageSender.send(
                                        chatClient,
                                        executionPrompt,
                                        renderedInput,
                                        visibleTools,
                                        skillName,
                                        executionConfiguration);

                                markRequestSent.accept(sentPayload);
                                ChatResponse chatResponse;
                                String content;

                                try
                                {
                                    ChatClientResponse clientResponse = responseSpec.chatClientResponse();
                                    chatResponse = clientResponse.chatResponse();
                                    content = extractContentFromChatResponse(chatResponse);
                                }
                                catch (UnsupportedOperationException ignored)
                                {
                                    chatResponse = null;
                                    content = responseSpec.content();
                                }

                                return ModelTraceResult.of(
                                        new MissionTraceResult(content, chatResponse),
                                        Map.of("content", content));
                            });

                    String content = missionResult.content();
                    sessionUsageService.recordModelResponse(
                            session,
                            skillName,
                            modelUsageExtractor.extract(missionResult.chatResponse(), userMessage, executionPrompt, content));

                    return content;
                }
                catch (RuntimeException ex)
                {
                    modelFailure = ex;
                    modelFrameStatus = Thread.currentThread().isInterrupted() ? "aborted" : "failed";
                    throw ex;
                }
                finally
                {
                    if (cleanupOwner.get() != CleanupOwner.CALLER)
                    {
                        executionStateService.closeFrame(session, modelFrame, closeMetadata(modelFrameStatus, modelFailure));
                    }
                }
            }
            finally
            {
                try
                {
                    if (cleanupOwner.compareAndSet(CleanupOwner.NONE, CleanupOwner.WORKER))
                    {
                        unwindMissionFrames(session, baselineFrameDepth);
                    }
                }
                finally
                {
                    cleanupComplete.countDown();
                }
            }
        });

        Future<String> mission = missionExecutor.submit(missionCall);
        try
        {
            return mission.get(missionTimeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException ex)
        {
            mission.cancel(true);
            awaitMissionCleanup(session, mission, baselineFrameDepth, cleanupOwner, cleanupComplete);
            throw new BifrostMissionTimeoutException(session.getSessionId(), skillName, missionTimeout, ex);
        }
        catch (InterruptedException ex)
        {
            mission.cancel(true);
            awaitMissionCleanup(session, mission, baselineFrameDepth, cleanupOwner, cleanupComplete);
            Thread.currentThread().interrupt();
            throw new BifrostMissionTimeoutException(session.getSessionId(), skillName, missionTimeout, ex);
        }
        catch (ExecutionException ex)
        {
            awaitMissionCleanup(session, mission, baselineFrameDepth, cleanupOwner, cleanupComplete);
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException)
            {
                throw unwrapMissionFailure(runtimeException);
            }
            throw new IllegalStateException("Mission execution failed for skill '" + skillName + "'", cause);
        }
    }

    private void unwindMissionFrames(BifrostSession session, int baselineFrameDepth)
    {
        while (session.getFramesSnapshot().size() > baselineFrameDepth)
        {
            ExecutionFrame activeFrame = session.peekFrame();
            executionStateService.closeFrame(session, activeFrame, Map.of(
                    "status", "aborted",
                    "reason", "mission-cleanup"));
        }
    }

    private RuntimeException unwrapMissionFailure(RuntimeException runtimeException)
    {
        if (runtimeException instanceof ToolExecutionException toolExecutionException
                && toolExecutionException.getCause() instanceof RuntimeException nestedRuntimeException
                && (nestedRuntimeException instanceof BifrostStackOverflowException
                        || nestedRuntimeException instanceof BifrostMissionTimeoutException))
        {
            return nestedRuntimeException;
        }

        return runtimeException;
    }

    private void awaitMissionCleanup(BifrostSession session,
            Future<String> mission,
            int baselineFrameDepth,
            AtomicReference<CleanupOwner> cleanupOwner,
            CountDownLatch cleanupComplete)
    {
        try
        {
            mission.get(250, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }
        catch (ExecutionException | TimeoutException | java.util.concurrent.CancellationException ignored)
        {
            // Best-effort wait only.
        }

        if (cleanupComplete.getCount() == 0L)
        {
            return;
        }
        if (cleanupOwner.compareAndSet(CleanupOwner.NONE, CleanupOwner.CALLER))
        {
            try
            {
                unwindMissionFrames(session, baselineFrameDepth);
            }
            finally
            {
                cleanupComplete.countDown();
            }
            return;
        }

        try
        {
            cleanupComplete.await(250, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }
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

    private Map<String, Object> buildMissionPreparedPayload(SkillPromptComposition composition,
            RenderedMissionInput renderedInput)
    {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("system", composition.systemPrompt());
        payload.put("user", renderedInput.userText());
        payload.put("attachments", attachmentDescriptors(renderedInput));
        payload.put("attachmentCount", renderedInput.attachments().size());
        payload.putAll(composition.traceMetadata());
        return Map.copyOf(payload);
    }

    private Map<String, Object> buildMissionSentPayload(SkillPromptComposition composition,
            RenderedMissionInput renderedInput,
            List<ToolCallback> visibleTools)
    {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("system", composition.systemPrompt());
        payload.put("user", renderedInput.userText());
        payload.put("attachments", attachmentDescriptors(renderedInput));
        payload.put("attachmentCount", renderedInput.attachments().size());
        payload.put("toolCallbackCount", visibleTools.size());
        payload.putAll(composition.traceMetadata());

        payload.put("toolNames", visibleTools.stream()
                .map(callback ->
                {
                    if (callback == null)
                        return "<null>";
                    var def = callback.getToolDefinition();
                    return def != null ? def.name() : "<unknown>";
                })
                .toList());

        return Map.copyOf(payload);
    }

    private List<Map<String, Object>> attachmentDescriptors(RenderedMissionInput renderedInput)
    {
        return renderedInput.attachments().stream()
                .map(attachment -> attachment.descriptor())
                .toList();
    }

    @Nullable
    private Map<String, Object> planningInput(@Nullable Map<String, Object> originalInput, RenderedMissionInput renderedInput)
    {
        return renderedInput.attachments().isEmpty() ? originalInput : renderedInput.traceSafeInput();
    }

    private static MissionInputMaterializer defaultMaterializer()
    {
        return new DefaultMissionInputMaterializer(new DefaultRefResolver(
                new SessionLocalVirtualFileSystem(Paths.get(System.getProperty("java.io.tmpdir"), "bifrost-vfs"))));
    }

    @Nullable
    private static String extractContentFromChatResponse(@Nullable ChatResponse chatResponse)
    {
        return Optional.ofNullable(chatResponse)
                .map(ChatResponse::getResult)
                .map(Generation::getOutput)
                .map(AbstractMessage::getText)
                .orElse(null);
    }

    private String buildPlannedExecutionPrompt(ExecutionPlan plan)
    {
        String readyTaskLines = plan.readyTasks().stream()
                .map(task -> "- [" + task.status() + "] " + task.taskId() + ": " + task.title()
                        + (task.note() == null ? "" : " (" + task.note() + ")"))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- No ready tasks");

        String blockedTaskLines = plan.tasks().stream()
                .filter(task -> task.status() == PlanTaskStatus.BLOCKED)
                .map(task -> "- " + task.taskId() + ": " + task.title()
                        + (task.note() == null ? "" : " (" + task.note() + ")"))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- No blocked tasks");

        String activeTask = plan.activeTask()
                .map(task -> task.taskId() + ": " + task.title())
                .orElse("none");

        return """
                Execute the mission using only the visible YAML tools when needed.
                Keep the stored flight plan as the execution anchor and advance work consistently with it.
                Active plan %s for capability %s is %s.
                Active task: %s
                Ready tasks:
                %s
                Blocked tasks:
                %s
                """.formatted(plan.planId(), plan.capabilityName(), plan.status(), activeTask, readyTaskLines, blockedTaskLines);
    }

    private record MissionTraceResult(String content, @Nullable ChatResponse chatResponse)
    {
    }
}
