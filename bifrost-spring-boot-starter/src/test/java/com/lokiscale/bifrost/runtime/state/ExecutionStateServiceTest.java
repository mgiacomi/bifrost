package com.lokiscale.bifrost.runtime.state;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.ExecutionFrame;
import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.ExecutionTraceRecorder;
import com.lokiscale.bifrost.core.JournalEntry;
import com.lokiscale.bifrost.core.JournalEntryType;
import com.lokiscale.bifrost.core.ModelTraceContext;
import com.lokiscale.bifrost.core.PlanTask;
import com.lokiscale.bifrost.core.PlanTaskStatus;
import com.lokiscale.bifrost.core.TaskExecutionEvent;
import com.lokiscale.bifrost.core.TraceFrameType;
import com.lokiscale.bifrost.core.TraceRecord;
import com.lokiscale.bifrost.core.TraceRecordType;
import com.lokiscale.bifrost.linter.LinterOutcome;
import com.lokiscale.bifrost.linter.LinterOutcomeStatus;
import com.lokiscale.bifrost.outputschema.OutputSchemaFailureMode;
import com.lokiscale.bifrost.outputschema.OutputSchemaOutcome;
import com.lokiscale.bifrost.outputschema.OutputSchemaOutcomeStatus;
import com.lokiscale.bifrost.outputschema.OutputSchemaValidationIssue;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionStateServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void managesFramePlanAndJournalWritesThroughSingleBoundary() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-1", 3);
        ExecutionPlan plan = plan("plan-1");
        LinterOutcome outcome = new LinterOutcome(
                "linted.skill",
                "regex",
                2,
                1,
                2,
                LinterOutcomeStatus.PASSED,
                "Return fenced YAML only.");
        OutputSchemaOutcome outputSchemaOutcome = new OutputSchemaOutcome(
                "schema.skill",
                OutputSchemaFailureMode.SCHEMA_VALIDATION_FAILED,
                1,
                0,
                2,
                OutputSchemaOutcomeStatus.RETRYING,
                List.of(new OutputSchemaValidationIssue("$.vendorName", "missing required field 'vendorName'", "vendorName")));

        ExecutionFrame frame = stateService.openMissionFrame(session, "root.visible.skill", Map.of("objective", "hello"));
        stateService.storePlan(session, plan);
        stateService.logPlanCreated(session, plan);
        stateService.logToolCall(session, TaskExecutionEvent.linked("allowed.visible.skill", "task-1", Map.of("arguments", Map.of("value", "hello")), null));
        stateService.logToolResult(session, TaskExecutionEvent.linked("allowed.visible.skill", "task-1", Map.of("result", "done"), null));
        stateService.recordLinterOutcome(session, outcome);
        stateService.recordOutputSchemaOutcome(session, outputSchemaOutcome);
        stateService.logError(session, Map.of("message", "boom"));
        stateService.closeMissionFrame(session, frame);
        stateService.clearPlan(session);

        assertThat(session.getFramesSnapshot()).isEmpty();
        assertThat(session.getExecutionPlan()).isEmpty();
        assertThat(session.getLastLinterOutcome()).contains(outcome);
        assertThat(session.getLastOutputSchemaOutcome()).contains(outputSchemaOutcome);
        assertThat(session.getJournalSnapshot()).extracting(JournalEntry::type)
                .containsExactly(
                        JournalEntryType.PLAN_CREATED,
                        JournalEntryType.TOOL_CALL,
                        JournalEntryType.TOOL_RESULT,
                        JournalEntryType.LINTER,
                        JournalEntryType.OUTPUT_SCHEMA,
                        JournalEntryType.ERROR);
        assertThat(session.getJournalSnapshot().get(3).payload().get("status").textValue()).isEqualTo("PASSED");
        assertThat(session.getJournalSnapshot().get(4).payload().get("failureMode").textValue()).isEqualTo("SCHEMA_VALIDATION_FAILED");
    }

    @Test
    void restoresParentPlanAfterNestedMissionAndClearsWhenNoParentExists() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);

        BifrostSession sessionWithParent = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-parent", 3);
        ExecutionPlan parentPlan = plan("parent-plan");
        stateService.storePlan(sessionWithParent, parentPlan);
        PlanSnapshot snapshot = stateService.snapshotPlan(sessionWithParent);
        stateService.storePlan(sessionWithParent, plan("child-plan"));
        stateService.restorePlan(sessionWithParent, snapshot);

        BifrostSession sessionWithoutParent = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-empty", 3);
        PlanSnapshot emptySnapshot = stateService.snapshotPlan(sessionWithoutParent);
        stateService.storePlan(sessionWithoutParent, plan("child-plan"));
        stateService.restorePlan(sessionWithoutParent, emptySnapshot);

        assertThat(sessionWithParent.getExecutionPlan()).contains(parentPlan);
        assertThat(sessionWithoutParent.getExecutionPlan()).isEmpty();
    }

    @Test
    void rejectsClosingFrameOutOfOrder() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-frames", 3);

        ExecutionFrame parentFrame = stateService.openMissionFrame(session, "root.visible.skill", Map.of());
        ExecutionFrame childFrame = stateService.openMissionFrame(session, "child.visible.skill", Map.of());

        assertThatThrownBy(() -> stateService.closeMissionFrame(session, parentFrame))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(parentFrame.frameId());
        assertThat(session.peekFrame()).isEqualTo(childFrame);
        assertThat(session.getFramesSnapshot()).containsExactly(childFrame, parentFrame);
    }

    @Test
    void recordsRuntimeTraceEventsAgainstTheActiveFrameAndIncludesRequestedAndRootMissionTyping() throws Exception {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-trace", 3);

        ExecutionFrame rootFrame = stateService.openMissionFrame(session, "root.visible.skill", Map.of("objective", "hello"));
        ExecutionFrame frame = stateService.openFrame(session, TraceFrameType.MODEL_CALL, "root.visible.skill#model", Map.of("provider", "openai"));
        stateService.recordModelRequestPrepared(
                session,
                frame,
                new ModelTraceContext("openai", "openai/gpt-5", "root.visible.skill", "unit"),
                Map.of("user", "hello"));
        stateService.logToolCall(session, TaskExecutionEvent.linked("allowed.visible.skill", "task-1", Map.of("arguments", Map.of("value", "hello")), null));
        stateService.closeFrame(session, frame, Map.of("status", "completed"));
        stateService.closeMissionFrame(session, rootFrame);

        List<TraceRecord> records = readRecords(session);

        TraceRecord frameOpened = records.stream()
                .filter(record -> record.recordType() == TraceRecordType.FRAME_OPENED && record.frameId().equals(rootFrame.frameId()))
                .findFirst()
                .orElseThrow();
        TraceRecord modelRequest = records.stream()
                .filter(record -> record.recordType() == TraceRecordType.MODEL_REQUEST_PREPARED)
                .findFirst()
                .orElseThrow();
        TraceRecord toolRequested = records.stream()
                .filter(record -> record.recordType() == TraceRecordType.TOOL_CALL_REQUESTED)
                .findFirst()
                .orElseThrow();
        TraceRecord toolStarted = records.stream()
                .filter(record -> record.recordType() == TraceRecordType.TOOL_CALL_STARTED)
                .findFirst()
                .orElseThrow();
        TraceRecord frameClosed = records.stream()
                .filter(record -> record.recordType() == TraceRecordType.FRAME_CLOSED)
                .findFirst()
                .orElseThrow();

        assertThat(frameOpened.frameId()).isEqualTo(rootFrame.frameId());
        assertThat(frameOpened.parentFrameId()).isNull();
        assertThat(frameOpened.frameType()).isEqualTo(TraceFrameType.ROOT_MISSION);
        assertThat(modelRequest.frameId()).isEqualTo(frame.frameId());
        assertThat(modelRequest.route()).isEqualTo("root.visible.skill#model");
        assertThat(toolRequested.frameId()).isEqualTo(frame.frameId());
        assertThat(toolRequested.recordType()).isEqualTo(TraceRecordType.TOOL_CALL_REQUESTED);
        assertThat(toolStarted.frameId()).isEqualTo(frame.frameId());
        assertThat(toolStarted.recordType()).isEqualTo(TraceRecordType.TOOL_CALL_STARTED);
        assertThat(frameClosed.frameId()).isEqualTo(frame.frameId());
    }

    @Test
    void rollsBackFramePushWhenRecorderFailsDuringOpen() {
        ExecutionTraceRecorder failingRecorder = new ExecutionTraceRecorder() {
            @Override
            public void recordFrameOpened(BifrostSession session, ExecutionFrame frame) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void recordFrameClosed(BifrostSession session, ExecutionFrame frame, Map<String, Object> metadata) {
            }

            @Override
            public void recordModelRequestPrepared(BifrostSession session, ExecutionFrame frame, ModelTraceContext context, Object payload) {
            }

            @Override
            public void recordModelRequestSent(BifrostSession session, ExecutionFrame frame, ModelTraceContext context, Object payload) {
            }

            @Override
            public void recordModelResponseReceived(BifrostSession session, ExecutionFrame frame, ModelTraceContext context, Object payload) {
            }

            @Override
            public void recordPlanCreated(BifrostSession session, ExecutionPlan plan) {
            }

            @Override
            public void recordPlanUpdated(BifrostSession session, ExecutionPlan plan) {
            }

            @Override
            public void recordToolRequested(BifrostSession session, ExecutionFrame frame, com.lokiscale.bifrost.core.ToolTraceContext context, Object payload) {
            }

            @Override
            public void recordToolStarted(BifrostSession session, ExecutionFrame frame, com.lokiscale.bifrost.core.ToolTraceContext context, Object payload) {
            }

            @Override
            public void recordToolCompleted(BifrostSession session, ExecutionFrame frame, com.lokiscale.bifrost.core.ToolTraceContext context, Object payload) {
            }

            @Override
            public void recordToolFailed(BifrostSession session, ExecutionFrame frame, com.lokiscale.bifrost.core.ToolTraceContext context, Object payload) {
            }

            @Override
            public void recordAdvisorRequestMutation(BifrostSession session, com.lokiscale.bifrost.core.AdvisorTraceContext context, Object payload) {
            }

            @Override
            public void recordAdvisorResponseMutation(BifrostSession session, com.lokiscale.bifrost.core.AdvisorTraceContext context, Object payload) {
            }

            @Override
            public void recordLinterOutcome(BifrostSession session, LinterOutcome outcome) {
            }

            @Override
            public void recordOutputSchemaOutcome(BifrostSession session, OutputSchemaOutcome outcome) {
            }

            @Override
            public void recordError(BifrostSession session, Object payload) {
            }

            @Override
            public void finalizeTrace(BifrostSession session, com.lokiscale.bifrost.core.TraceCompletion completion) {
            }
        };
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK, new com.lokiscale.bifrost.runtime.usage.NoOpSessionUsageService(), failingRecorder);
        BifrostSession session = com.lokiscale.bifrost.core.TestBifrostSessions.withId("session-open-failure", 3);

        assertThatThrownBy(() -> stateService.openMissionFrame(session, "root.visible.skill", Map.of("objective", "hello")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

        assertThat(session.getFramesSnapshot()).isEmpty();
    }

    private static ExecutionPlan plan(String planId) {
        return new ExecutionPlan(
                planId,
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(new PlanTask("task-1", "Use tool", PlanTaskStatus.PENDING, null)));
    }

    private static List<TraceRecord> readRecords(BifrostSession session) {
        List<TraceRecord> records = new ArrayList<>();
        session.readTraceRecords(records::add);
        return records;
    }
}
