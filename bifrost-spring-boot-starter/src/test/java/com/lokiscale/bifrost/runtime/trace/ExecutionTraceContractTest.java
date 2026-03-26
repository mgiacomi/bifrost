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
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

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
                "root.visible.skill",
                EXECUTION_CONFIGURATION,
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
                    "root.visible.skill",
                    "hello",
                    EXECUTION_CONFIGURATION,
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
                "root.visible.skill",
                EXECUTION_CONFIGURATION,
                new SimpleChatClient(plan("plan-1"), "done"),
                List.<ToolCallback>of());

        TraceRecord planCreated = readRecords(session).stream()
                .filter(record -> record.recordType() == TraceRecordType.PLAN_CREATED)
                .findFirst()
                .orElseThrow();

        assertThat(planCreated.frameType()).isEqualTo(TraceFrameType.PLANNING);
        assertThat(planCreated.route()).isEqualTo("root.visible.skill#planning");
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
}
