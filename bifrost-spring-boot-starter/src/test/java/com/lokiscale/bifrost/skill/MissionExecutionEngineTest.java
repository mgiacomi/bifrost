package com.lokiscale.bifrost.skill;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.PlanTask;
import com.lokiscale.bifrost.core.PlanTaskStatus;
import com.lokiscale.bifrost.runtime.BifrostMissionTimeoutException;
import com.lokiscale.bifrost.runtime.DefaultMissionExecutionEngine;
import com.lokiscale.bifrost.runtime.SimpleChatClient;
import com.lokiscale.bifrost.runtime.planning.PlanningService;
import com.lokiscale.bifrost.runtime.state.DefaultExecutionStateService;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MissionExecutionEngineTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void executesPlanningEnabledMissionLoop() {
        PlanningService planningService = mock(PlanningService.class);
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            DefaultMissionExecutionEngine engine = new DefaultMissionExecutionEngine(
                    planningService,
                    stateService,
                    Duration.ofSeconds(5),
                    missionExecutor);
            BifrostSession session = new BifrostSession("session-1", 2);
            ExecutionPlan plan = new ExecutionPlan(
                    "plan-1",
                    "root.visible.skill",
                    Instant.parse("2026-03-15T12:00:00Z"),
                    List.of(
                            new PlanTask("task-1", "Use tool", PlanTaskStatus.PENDING, "allowed.visible.skill", "Use tool", List.of(), List.of(), false, null),
                            new PlanTask("task-2", "Blocked", PlanTaskStatus.BLOCKED, null)));
            MissionChatClient chatClient = new MissionChatClient("mission complete");
            ToolCallback callback = mock(ToolCallback.class);

            when(planningService.initializePlan(eq(session), eq("hello"), eq("root.visible.skill"), eq(chatClient), any()))
                    .thenAnswer(invocation -> {
                        stateService.storePlan(session, plan);
                        return java.util.Optional.of(plan);
                    });

            String response = engine.executeMission(session, "root.visible.skill", "hello", chatClient, List.of(callback), true, null);

            assertThat(response).isEqualTo("mission complete");
            assertThat(chatClient.getSystemMessagesSeen().getFirst()).contains("plan-1", "Ready tasks", "Blocked tasks");
        }
    }

    @Test
    void skipsPlanningForPlanningDisabledMission() {
        PlanningService planningService = mock(PlanningService.class);
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            DefaultMissionExecutionEngine engine = new DefaultMissionExecutionEngine(
                    planningService,
                    stateService,
                    Duration.ofSeconds(5),
                    missionExecutor);
            BifrostSession session = new BifrostSession("session-1", 2);
            MissionChatClient chatClient = new MissionChatClient("mission complete");

            String response = engine.executeMission(session, "root.visible.skill", "hello", chatClient, List.of(), false, null);

            assertThat(response).isEqualTo("mission complete");
            assertThat(chatClient.getSystemMessagesSeen()).containsExactly("Execute the mission using only the visible YAML tools when needed.");
            verify(planningService, never()).initializePlan(eq(session), eq("hello"), eq("root.visible.skill"), eq(chatClient), any());
        }
    }

    @Test
    void timesOutBlockingMissionExecution() {
        PlanningService planningService = mock(PlanningService.class);
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        AtomicBoolean interrupted = new AtomicBoolean(false);

        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            DefaultMissionExecutionEngine engine = new DefaultMissionExecutionEngine(
                    planningService,
                    stateService,
                    Duration.ofMillis(25),
                    missionExecutor);
            BifrostSession session = new BifrostSession("session-timeout", 2);
            BlockingMissionChatClient chatClient = new BlockingMissionChatClient(interrupted);

            assertThatThrownBy(() -> engine.executeMission(
                    session,
                    "root.visible.skill",
                    "hello",
                    chatClient,
                    List.of(),
                    false,
                    null))
                    .isInstanceOf(BifrostMissionTimeoutException.class)
                    .hasMessageContaining("session-timeout")
                    .hasMessageContaining("root.visible.skill")
                    .hasMessageContaining("PT0.025S");
        }
    }

    @Test
    void timesOutWhilePlanningEnabledMissionInitializesPlan() {
        AtomicBoolean interrupted = new AtomicBoolean(false);
        PlanningService planningService = new BlockingPlanningService(interrupted);
        ExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);

        try (ExecutorService missionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            DefaultMissionExecutionEngine engine = new DefaultMissionExecutionEngine(
                    planningService,
                    stateService,
                    Duration.ofMillis(25),
                    missionExecutor);
            BifrostSession session = new BifrostSession("session-timeout", 2);
            MissionChatClient chatClient = new MissionChatClient("mission complete");

            assertThatThrownBy(() -> engine.executeMission(
                    session,
                    "root.visible.skill",
                    "hello",
                    chatClient,
                    List.of(),
                    true,
                    null))
                    .isInstanceOf(BifrostMissionTimeoutException.class)
                    .hasMessageContaining("session-timeout")
                    .hasMessageContaining("root.visible.skill")
                    .hasMessageContaining("PT0.025S");
            assertThat(interrupted.get()).isTrue();
            assertThat(session.getExecutionPlan()).isEmpty();
        }
    }

    private static final class MissionChatClient extends SimpleChatClient {

        private MissionChatClient(String content) {
            super(null, content);
        }
    }

    private static final class BlockingMissionChatClient extends SimpleChatClient {

        private final AtomicBoolean interrupted;

        private BlockingMissionChatClient(AtomicBoolean interrupted) {
            super(null, "unused");
            this.interrupted = interrupted;
        }

        @Override
        public ChatClientRequestSpec prompt() {
            return new BlockingRequestSpec();
        }

        private final class BlockingRequestSpec implements ChatClientRequestSpec {

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
            public ChatClientRequestSpec toolContext(java.util.Map<String, Object> toolContext) {
                return this;
            }

            @Override
            public ChatClientRequestSpec system(String text) {
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
                return new BlockingResponseSpec();
            }

            @Override
            public StreamResponseSpec stream() {
                throw new UnsupportedOperationException();
            }
        }

        private final class BlockingResponseSpec implements CallResponseSpec {

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
                try {
                    new CountDownLatch(1).await();
                    throw new AssertionError("Latch await returned unexpectedly");
                }
                catch (InterruptedException ex) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                    return "interrupted";
                }
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

    private static final class BlockingPlanningService implements PlanningService {

        private final AtomicBoolean interrupted;

        private BlockingPlanningService(AtomicBoolean interrupted) {
            this.interrupted = interrupted;
        }

        @Override
        public java.util.Optional<ExecutionPlan> initializePlan(BifrostSession session,
                                                                String objective,
                                                                String capabilityName,
                                                                org.springframework.ai.chat.client.ChatClient chatClient,
                                                                List<ToolCallback> visibleTools) {
            try {
                new CountDownLatch(1).await();
                throw new AssertionError("Latch await returned unexpectedly");
            }
            catch (InterruptedException ex) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Planning interrupted", ex);
            }
        }

        @Override
        public java.util.Optional<ExecutionPlan> markToolStarted(BifrostSession session,
                                                                 com.lokiscale.bifrost.core.CapabilityMetadata capability,
                                                                 java.util.Map<String, Object> arguments) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<ExecutionPlan> markToolCompleted(BifrostSession session,
                                                                   String taskId,
                                                                   String capabilityName,
                                                                   Object result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<ExecutionPlan> markToolFailed(BifrostSession session,
                                                                String taskId,
                                                                String capabilityName,
                                                                RuntimeException ex) {
            throw new UnsupportedOperationException();
        }
    }
}
