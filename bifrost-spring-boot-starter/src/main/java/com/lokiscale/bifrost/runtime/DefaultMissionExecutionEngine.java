package com.lokiscale.bifrost.runtime;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.BifrostStackOverflowException;
import com.lokiscale.bifrost.core.ExecutionFrame;
import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.ModelTraceResult;
import com.lokiscale.bifrost.core.ModelTraceContext;
import com.lokiscale.bifrost.core.PlanTaskStatus;
import com.lokiscale.bifrost.core.SessionContextRunner;
import com.lokiscale.bifrost.core.TraceFrameType;
import com.lokiscale.bifrost.runtime.planning.PlanningService;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import com.lokiscale.bifrost.runtime.usage.ModelUsageExtractor;
import com.lokiscale.bifrost.runtime.usage.NoOpSessionUsageService;
import com.lokiscale.bifrost.runtime.usage.SessionUsageService;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class DefaultMissionExecutionEngine implements MissionExecutionEngine {

    private enum CleanupOwner {
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

    public DefaultMissionExecutionEngine(PlanningService planningService,
                                         ExecutionStateService executionStateService,
                                         Duration missionTimeout,
                                         ExecutorService missionExecutor) {
        this(planningService, executionStateService, missionTimeout, missionExecutor, new NoOpSessionUsageService(), new ModelUsageExtractor());
    }

    public DefaultMissionExecutionEngine(PlanningService planningService,
                                         ExecutionStateService executionStateService,
                                         Duration missionTimeout,
                                         ExecutorService missionExecutor,
                                         SessionUsageService sessionUsageService,
                                         ModelUsageExtractor modelUsageExtractor) {
        this.planningService = Objects.requireNonNull(planningService, "planningService must not be null");
        this.executionStateService = Objects.requireNonNull(executionStateService, "executionStateService must not be null");
        this.missionTimeout = Objects.requireNonNull(missionTimeout, "missionTimeout must not be null");
        this.missionExecutor = Objects.requireNonNull(missionExecutor, "missionExecutor must not be null");
        this.sessionUsageService = Objects.requireNonNull(sessionUsageService, "sessionUsageService must not be null");
        this.modelUsageExtractor = Objects.requireNonNull(modelUsageExtractor, "modelUsageExtractor must not be null");
    }

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
                    planningService.initializePlan(session, objective, skillName, executionConfiguration, chatClient, visibleTools);
                }

                String executionPrompt = executionStateService.currentPlan(session)
                        .map(this::buildExecutionPrompt)
                        .orElse("Execute the mission using only the visible YAML tools when needed.");
                ExecutionFrame modelFrame = executionStateService.openFrame(
                        session,
                        TraceFrameType.MODEL_CALL,
                        skillName + "#mission-model",
                        Map.of(
                                "provider", executionConfiguration.provider().name(),
                                "providerModel", executionConfiguration.providerModel()));
                String modelFrameStatus = "completed";
                Throwable modelFailure = null;
                try {
                    ModelTraceContext modelTraceContext = new ModelTraceContext(
                            executionConfiguration.provider().name(),
                            executionConfiguration.providerModel(),
                            skillName,
                            "mission");
                    MissionTraceResult missionResult = executionStateService.traceModelCall(
                            session,
                            modelFrame,
                            modelTraceContext,
                            Map.of(
                                    "system", executionPrompt,
                                    "user", objective),
                            markRequestSent -> {
                                Map<String, Object> sentPayload = buildMissionSentPayload(executionPrompt, objective, visibleTools);
                                ChatClient.CallResponseSpec responseSpec = chatClient.prompt()
                                        .system(executionPrompt)
                                        .user(objective)
                                        .toolCallbacks(visibleTools)
                                        .call();
                                markRequestSent.accept(sentPayload);
                                ChatResponse chatResponse;
                                String content;
                                try {
                                    ChatClientResponse clientResponse = responseSpec.chatClientResponse();
                                    chatResponse = clientResponse.chatResponse();
                                    content = extractContentFromChatResponse(chatResponse);
                                }
                                catch (UnsupportedOperationException ignored) {
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
                            modelUsageExtractor.extract(missionResult.chatResponse(), objective, executionPrompt, content));
                    return content;
                }
                catch (RuntimeException ex) {
                    modelFailure = ex;
                    modelFrameStatus = Thread.currentThread().isInterrupted() ? "aborted" : "failed";
                    throw ex;
                }
                finally {
                    if (cleanupOwner.get() != CleanupOwner.CALLER) {
                        executionStateService.closeFrame(session, modelFrame, closeMetadata(modelFrameStatus, modelFailure));
                    }
                }
            }
            finally {
                try {
                    if (cleanupOwner.compareAndSet(CleanupOwner.NONE, CleanupOwner.WORKER)) {
                        unwindMissionFrames(session, baselineFrameDepth);
                    }
                }
                finally {
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
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        catch (ExecutionException | TimeoutException | java.util.concurrent.CancellationException ignored) {
            // Best-effort wait only.
        }
        if (cleanupComplete.getCount() == 0L) {
            return;
        }
        if (cleanupOwner.compareAndSet(CleanupOwner.NONE, CleanupOwner.CALLER)) {
            try {
                unwindMissionFrames(session, baselineFrameDepth);
            }
            finally {
                cleanupComplete.countDown();
            }
            return;
        }
        try {
            cleanupComplete.await(250, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
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

    private Map<String, Object> buildMissionSentPayload(String executionPrompt,
                                                        String objective,
                                                        List<ToolCallback> visibleTools) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("system", executionPrompt);
        payload.put("user", objective);
        payload.put("toolCallbackCount", visibleTools.size());
        payload.put("toolNames", visibleTools.stream()
                .map(callback -> {
                    if (callback == null) return "<null>";
                    var def = callback.getToolDefinition();
                    return def != null ? def.name() : "<unknown>";
                })
                .toList());
        return Map.copyOf(payload);
    }

    @Nullable
    private static String extractContentFromChatResponse(@Nullable ChatResponse chatResponse) {
        return java.util.Optional.ofNullable(chatResponse)
                .map(ChatResponse::getResult)
                .map(Generation::getOutput)
                .map(AbstractMessage::getText)
                .orElse(null);
    }

    private String buildExecutionPrompt(ExecutionPlan plan) {
        String readyTaskLines = plan.readyTasks().stream()
                .sorted(Comparator.comparingInt(plan.tasks()::indexOf))
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

    private record MissionTraceResult(String content, @Nullable ChatResponse chatResponse) {
    }
}
