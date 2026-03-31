package com.lokiscale.bifrost.runtime.trace;

import com.lokiscale.bifrost.autoconfigure.AiProvider;
import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.DefaultPlanTaskLinker;
import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.TraceFrameType;
import com.lokiscale.bifrost.core.TraceRecord;
import com.lokiscale.bifrost.core.TraceRecordType;
import com.lokiscale.bifrost.runtime.DefaultMissionExecutionEngine;
import com.lokiscale.bifrost.runtime.SimpleChatClient;
import com.lokiscale.bifrost.runtime.planning.DefaultPlanningService;
import com.lokiscale.bifrost.runtime.state.DefaultExecutionStateService;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import com.lokiscale.bifrost.skill.YamlSkillManifest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayDeque;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionTraceContractTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC);
    private static final EffectiveSkillExecutionConfiguration EXECUTION_CONFIGURATION =
            new EffectiveSkillExecutionConfiguration("gpt-5", AiProvider.OPENAI, "openai/gpt-5", "medium");

    @Test
    void modelEventsAreSemanticallyEquivalentAcrossPlanningAndMission() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);

        BifrostSession planningSession = com.lokiscale.bifrost.core.TestBifrostSessions.withId("planning-trace", 3);
        planningService.initializePlan(
                planningSession,
                "hello",
                rootDefinition(),
                new SimpleChatClient(plan("plan-1"), "done"),
                List.<ToolCallback>of());

        List<TraceRecord> planningModelRecords = modelRecords(planningSession);

        BifrostSession missionSession = com.lokiscale.bifrost.core.TestBifrostSessions.withId("mission-trace", 3);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            DefaultMissionExecutionEngine engine = new DefaultMissionExecutionEngine(
                    new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService),
                    stateService,
                    Duration.ofSeconds(5),
                    executor);

            String missionResponse = engine.executeMission(
                    missionSession,
                    rootDefinition(),
                    "hello",
                    new SimpleChatClient(null, "mission complete"),
                    List.of(),
                    false,
                    null);

            assertThat(missionResponse).isEqualTo("mission complete");
        }

        List<TraceRecord> missionModelRecords = modelRecords(missionSession);

        assertThat(planningModelRecords).extracting(TraceRecord::recordType)
                .containsExactly(
                        TraceRecordType.MODEL_REQUEST_PREPARED,
                        TraceRecordType.MODEL_REQUEST_SENT,
                        TraceRecordType.MODEL_RESPONSE_RECEIVED);
        assertThat(missionModelRecords).extracting(TraceRecord::recordType)
                .containsExactlyElementsOf(planningModelRecords.stream().map(TraceRecord::recordType).toList());

        assertEquivalentEnvelope(planningModelRecords.get(0), missionModelRecords.get(0));
        assertEquivalentEnvelope(planningModelRecords.get(1), missionModelRecords.get(1));
        assertEquivalentEnvelope(planningModelRecords.get(2), missionModelRecords.get(2));
        assertThat(planningModelRecords).allMatch(record -> "planning".equals(record.metadata().get("segment")));
        assertThat(missionModelRecords).allMatch(record -> "mission".equals(record.metadata().get("segment")));
    }

    @Test
    void planCreationIsOwnedByPlanningFrameNotNestedModelFrame() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("planning-owner-trace", 3);

        planningService.initializePlan(
                session,
                "hello",
                rootDefinition(),
                new SimpleChatClient(plan("plan-1"), "done"),
                List.<ToolCallback>of());

        TraceRecord planCreated = readRecords(session).stream()
                .filter(record -> record.recordType() == TraceRecordType.PLAN_CREATED)
                .findFirst()
                .orElseThrow();

        assertThat(planCreated.frameType()).isEqualTo(TraceFrameType.PLANNING);
        assertThat(planCreated.route()).isEqualTo("root.visible.skill#planning");
    }

    @Test
    void planningQualityEventsStayUnderThePlanningFrame() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("planning-quality-trace", 3);

        planningService.initializePlan(
                session,
                "check invoice duplicates",
                duplicateInvoiceDefinition(),
                new SequencePlanningChatClient(weakPlanJson(), correctedPlanJson()),
                List.of(tool("invoiceParser", "Extract invoice fields from source documents"),
                        tool("expenseLookup", "Look up related expenses for comparison")));

        List<TraceRecord> records = readRecords(session);
        assertThat(records).anyMatch(record -> record.recordType() == TraceRecordType.PLAN_VALIDATION_FAILED
                && record.frameType() == TraceFrameType.PLANNING
                && record.metadata().containsKey("severity")
                && record.metadata().containsKey("issueCodes")
                && record.metadata().containsKey("retryCount"));
        assertThat(records).anyMatch(record -> record.recordType() == TraceRecordType.PLAN_RETRY_REQUESTED
                && record.frameType() == TraceFrameType.PLANNING);
    }

    @Test
    void exhaustedPlanQualityRetriesDegradeToPlanningWarningUnderPlanningFrame() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        DefaultPlanningService planningService = new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("planning-quality-retry-cap-trace", 3);

        planningService.initializePlan(
                session,
                "check invoice duplicates",
                duplicateInvoiceDefinition(),
                new SequencePlanningChatClient(weakPlanJson(), weakPlanJson()),
                List.of(tool("invoiceParser", "Extract invoice fields from source documents"),
                        tool("expenseLookup", "Look up related expenses for comparison")));

        List<TraceRecord> records = readRecords(session);
        assertThat(records).filteredOn(record -> record.recordType() == TraceRecordType.PLAN_VALIDATION_FAILED)
                .hasSize(1);
        assertThat(records).filteredOn(record -> record.recordType() == TraceRecordType.PLAN_RETRY_REQUESTED)
                .hasSize(1);
        assertThat(records).filteredOn(record -> record.recordType() == TraceRecordType.PLAN_QUALITY_WARNING)
                .hasSize(2);
        assertThat(records).filteredOn(record -> record.recordType() == TraceRecordType.PLAN_QUALITY_WARNING)
                .filteredOn(record -> "ERROR".equals(record.metadata().get("severity")))
                .hasSize(1)
                .first()
                .satisfies(record -> {
                    assertThat(record.frameType()).isEqualTo(TraceFrameType.PLANNING);
                    assertThat(record.metadata()).containsEntry("retryCount", 1);
                    assertThat(record.metadata()).containsEntry("severity", "ERROR");
                    assertThat(record.metadata()).containsKey("issueCodes");
                });
    }

    private static void assertEquivalentEnvelope(TraceRecord planningRecord, TraceRecord missionRecord) {
        assertThat(planningRecord.metadata().keySet()).containsExactlyElementsOf(missionRecord.metadata().keySet());
        assertThat(planningRecord.metadata()).containsEntry("provider", AiProvider.OPENAI.name());
        assertThat(missionRecord.metadata()).containsEntry("provider", AiProvider.OPENAI.name());
        assertThat(planningRecord.metadata()).containsEntry("providerModel", "openai/gpt-5");
        assertThat(missionRecord.metadata()).containsEntry("providerModel", "openai/gpt-5");
        assertThat(planningRecord.metadata()).containsEntry("skillName", "root.visible.skill");
        assertThat(missionRecord.metadata()).containsEntry("skillName", "root.visible.skill");
    }

    private static ExecutionPlan plan(String planId) {
        return new ExecutionPlan(
                planId,
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of());
    }

    private static YamlSkillDefinition rootDefinition() {
        return definition("root.visible.skill");
    }

    private static YamlSkillDefinition duplicateInvoiceDefinition() {
        return definition("duplicateInvoiceChecker");
    }

    private static YamlSkillDefinition definition(String name) {
        YamlSkillManifest manifest = new YamlSkillManifest();
        manifest.setName(name);
        manifest.setDescription(name);
        manifest.setModel("gpt-5");
        return new YamlSkillDefinition(
                new org.springframework.core.io.ByteArrayResource(new byte[0]),
                manifest,
                EXECUTION_CONFIGURATION);
    }

    private static List<TraceRecord> modelRecords(BifrostSession session) {
        return readRecords(session).stream()
                .filter(record -> record.recordType() == TraceRecordType.MODEL_REQUEST_PREPARED
                        || record.recordType() == TraceRecordType.MODEL_REQUEST_SENT
                        || record.recordType() == TraceRecordType.MODEL_RESPONSE_RECEIVED)
                .toList();
    }

    private static List<TraceRecord> readRecords(BifrostSession session) {
        List<TraceRecord> records = new ArrayList<>();
        session.readTraceRecords(records::add);
        return records;
    }

    private static ToolCallback tool(String name, String description) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = ToolDefinition.builder().name(name).description(description).inputSchema("{}").build();
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    private static String weakPlanJson() {
        return """
                {
                  "planId": "plan-weak",
                  "capabilityName": "duplicateInvoiceChecker",
                  "createdAt": "2026-03-15T12:00:00Z",
                  "status": "VALID",
                  "activeTaskId": null,
                  "tasks": [
                    {"taskId": "t-1", "title": "Parse invoice", "status": "PENDING", "capabilityName": "invoiceParser", "intent": "Extract invoice fields", "dependsOn": [], "expectedOutputs": ["parsed"], "autoCompletable": false, "note": ""},
                    {"taskId": "t-2", "title": "Check duplicates", "status": "PENDING", "capabilityName": "invoiceParser", "intent": "Check for matching expenses", "dependsOn": ["t-1"], "expectedOutputs": ["matches"], "autoCompletable": false, "note": ""},
                    {"taskId": "t-3", "title": "Final report", "status": "PENDING", "capabilityName": "invoiceParser", "intent": "Summarize duplicate findings", "dependsOn": ["t-2"], "expectedOutputs": ["report"], "autoCompletable": false, "note": ""}
                  ]
                }
                """;
    }

    private static String correctedPlanJson() {
        return """
                {
                  "planId": "plan-corrected",
                  "capabilityName": "duplicateInvoiceChecker",
                  "createdAt": "2026-03-15T12:00:00Z",
                  "status": "VALID",
                  "activeTaskId": null,
                  "tasks": [
                    {"taskId": "t-1", "title": "Parse invoice", "status": "PENDING", "capabilityName": "invoiceParser", "intent": "Extract invoice fields", "dependsOn": [], "expectedOutputs": ["parsed"], "autoCompletable": false, "note": ""},
                    {"taskId": "t-2", "title": "Look up matches", "status": "PENDING", "capabilityName": "expenseLookup", "intent": "Find matching expenses", "dependsOn": ["t-1"], "expectedOutputs": ["matches"], "autoCompletable": false, "note": ""},
                    {"taskId": "t-3", "title": "Compare evidence", "status": "PENDING", "capabilityName": "expenseLookup", "intent": "Compare invoice and expenses", "dependsOn": ["t-2"], "expectedOutputs": ["decision"], "autoCompletable": false, "note": ""}
                  ]
                }
                """;
    }

    private static final class SequencePlanningChatClient implements org.springframework.ai.chat.client.ChatClient {

        private final Deque<String> responses = new ArrayDeque<>();

        private SequencePlanningChatClient(String... responses) {
            this.responses.addAll(List.of(responses));
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
            @Override public <T> T entity(org.springframework.core.ParameterizedTypeReference<T> type) { throw new UnsupportedOperationException(); }
            @Override public <T> T entity(org.springframework.ai.converter.StructuredOutputConverter<T> converter) { throw new UnsupportedOperationException(); }
            @Override public <T> T entity(Class<T> type) { throw new UnsupportedOperationException(); }
            @Override public org.springframework.ai.chat.client.ChatClientResponse chatClientResponse() { throw new UnsupportedOperationException(); }
            @Override public org.springframework.ai.chat.model.ChatResponse chatResponse() { throw new UnsupportedOperationException(); }
            @Override public String content() { return content; }
            @Override public <T> org.springframework.ai.chat.client.ResponseEntity<org.springframework.ai.chat.model.ChatResponse, T> responseEntity(Class<T> type) { throw new UnsupportedOperationException(); }
            @Override public <T> org.springframework.ai.chat.client.ResponseEntity<org.springframework.ai.chat.model.ChatResponse, T> responseEntity(org.springframework.core.ParameterizedTypeReference<T> type) { throw new UnsupportedOperationException(); }
            @Override public <T> org.springframework.ai.chat.client.ResponseEntity<org.springframework.ai.chat.model.ChatResponse, T> responseEntity(org.springframework.ai.converter.StructuredOutputConverter<T> converter) { throw new UnsupportedOperationException(); }
        }
    }
}
