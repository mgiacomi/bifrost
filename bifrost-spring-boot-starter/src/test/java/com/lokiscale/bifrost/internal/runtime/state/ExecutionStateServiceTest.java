package com.lokiscale.bifrost.internal.runtime.state;

import com.lokiscale.bifrost.internal.core.BifrostSession;
import com.lokiscale.bifrost.internal.core.ExecutionFrame;
import com.lokiscale.bifrost.internal.core.ExecutionPlan;
import com.lokiscale.bifrost.internal.core.ExecutionTraceRecorder;
import com.lokiscale.bifrost.internal.core.JournalEntry;
import com.lokiscale.bifrost.internal.core.JournalEntryType;
import com.lokiscale.bifrost.internal.core.ModelTraceContext;
import com.lokiscale.bifrost.internal.core.PlanTask;
import com.lokiscale.bifrost.internal.core.PlanTaskStatus;
import com.lokiscale.bifrost.internal.core.TaskExecutionEvent;
import com.lokiscale.bifrost.internal.core.TraceFrameType;
import com.lokiscale.bifrost.internal.core.TraceRecord;
import com.lokiscale.bifrost.internal.core.TraceRecordType;
import com.lokiscale.bifrost.internal.linter.LinterOutcome;
import com.lokiscale.bifrost.internal.linter.LinterOutcomeStatus;
import com.lokiscale.bifrost.internal.outputschema.OutputSchemaFailureMode;
import com.lokiscale.bifrost.internal.outputschema.OutputSchemaOutcome;
import com.lokiscale.bifrost.internal.outputschema.OutputSchemaOutcomeStatus;
import com.lokiscale.bifrost.internal.outputschema.OutputSchemaValidationIssue;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionStateServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void successfulSkillSnapshotDefensivelyPreservesInsertionOrder() {
        LinkedHashSet<String> source = new LinkedHashSet<>(List.of("invoiceParser", "expenseLookup"));
        SuccessfulSkillSnapshot snapshot = new SuccessfulSkillSnapshot(source);
        source.add("taxLookup");

        assertThat(snapshot.successfulDirectSkills()).containsExactly("invoiceParser", "expenseLookup");
        assertThatThrownBy(() -> snapshot.successfulDirectSkills().add("taxLookup"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void managesFramePlanAndJournalWritesThroughSingleBoundary() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        BifrostSession session = com.lokiscale.bifrost.internal.core.TestBifrostSessions.withId("session-1", 3);
        ExecutionPlan plan = plan("plan-1");
        LinterOutcome outcome = new LinterOutcome(
                "lintedSkill",
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

        ExecutionFrame frame = stateService.openMissionFrame(session, "rootVisibleSkill", Map.of("objective", "hello"));
        stateService.storePlan(session, plan);
        stateService.logPlanCreated(session, plan);
        stateService.logToolCall(session, TaskExecutionEvent.linked("allowedVisibleSkill", "task-1", Map.of("arguments", Map.of("value", "hello")), null));
        stateService.logToolResult(session, TaskExecutionEvent.linked("allowedVisibleSkill", "task-1", Map.of("result", "done"), null));
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

        BifrostSession sessionWithParent = com.lokiscale.bifrost.internal.core.TestBifrostSessions.withId("session-parent", 3);
        ExecutionPlan parentPlan = plan("parent-plan");
        stateService.storePlan(sessionWithParent, parentPlan);
        PlanSnapshot snapshot = stateService.snapshotPlan(sessionWithParent);
        stateService.storePlan(sessionWithParent, plan("child-plan"));
        stateService.restorePlan(sessionWithParent, snapshot);

        BifrostSession sessionWithoutParent = com.lokiscale.bifrost.internal.core.TestBifrostSessions.withId("session-empty", 3);
        PlanSnapshot emptySnapshot = stateService.snapshotPlan(sessionWithoutParent);
        stateService.storePlan(sessionWithoutParent, plan("child-plan"));
        stateService.restorePlan(sessionWithoutParent, emptySnapshot);

        assertThat(sessionWithParent.getExecutionPlan()).contains(parentPlan);
        assertThat(sessionWithoutParent.getExecutionPlan()).isEmpty();
    }

    @Test
    void restoresParentSuccessfulSkillsAfterNestedMissionAndClearsWhenNoParentExists() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);

        BifrostSession sessionWithParent = com.lokiscale.bifrost.internal.core.TestBifrostSessions.withId("session-parent-evidence", 3);
        stateService.recordSuccessfulSkill(sessionWithParent, "invoiceParser", "task-1", false);
        stateService.recordSuccessfulSkill(sessionWithParent, "expenseLookup", "task-2", false);
        SuccessfulSkillSnapshot snapshot = stateService.snapshotSuccessfulSkills(sessionWithParent);
        assertThat(snapshot.successfulDirectSkills()).containsExactly("invoiceParser", "expenseLookup");
        stateService.recordSuccessfulSkill(sessionWithParent, "taxLookup", "task-3", false);
        stateService.restoreSuccessfulSkills(sessionWithParent, snapshot);

        BifrostSession sessionWithoutParent = com.lokiscale.bifrost.internal.core.TestBifrostSessions.withId("session-empty-evidence", 3);
        SuccessfulSkillSnapshot emptySnapshot = stateService.snapshotSuccessfulSkills(sessionWithoutParent);
        stateService.recordSuccessfulSkill(sessionWithoutParent, "expenseLookup", "task-2", false);
        stateService.restoreSuccessfulSkills(sessionWithoutParent, emptySnapshot);

        assertThat(sessionWithParent.getSuccessfulDirectSkills()).containsExactly("invoiceParser", "expenseLookup");
        assertThat(sessionWithoutParent.getSuccessfulDirectSkills()).isEmpty();
    }

    @Test
    void rejectsClosingFrameOutOfOrder() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        BifrostSession session = com.lokiscale.bifrost.internal.core.TestBifrostSessions.withId("session-frames", 3);

        ExecutionFrame parentFrame = stateService.openMissionFrame(session, "rootVisibleSkill", Map.of());
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
        BifrostSession session = com.lokiscale.bifrost.internal.core.TestBifrostSessions.withId("session-trace", 3);

        ExecutionFrame rootFrame = stateService.openMissionFrame(session, "rootVisibleSkill", Map.of("objective", "hello"));
        ExecutionFrame frame = stateService.openFrame(session, TraceFrameType.MODEL_CALL, "rootVisibleSkill#model", Map.of("driver", "openai"));
        stateService.recordModelRequestPrepared(
                session,
                frame,
                new ModelTraceContext(new com.lokiscale.bifrost.internal.core.ModelExecutionIdentity(
                        "gpt-5", "openai-main", com.lokiscale.bifrost.autoconfigure.AiDriver.OPENAI,
                        "openai/gpt-5"), "rootVisibleSkill", "unit"),
                Map.of("user", "hello"));
        stateService.logToolCall(session, TaskExecutionEvent.linked("allowedVisibleSkill", "task-1", Map.of("arguments", Map.of("value", "hello")), null));
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
        assertThat(modelRequest.route()).isEqualTo("rootVisibleSkill#model");
        assertThat(toolRequested.frameId()).isEqualTo(frame.frameId());
        assertThat(toolRequested.recordType()).isEqualTo(TraceRecordType.TOOL_CALL_REQUESTED);
        assertThat(toolStarted.frameId()).isEqualTo(frame.frameId());
        assertThat(toolStarted.recordType()).isEqualTo(TraceRecordType.TOOL_CALL_STARTED);
        assertThat(frameClosed.frameId()).isEqualTo(frame.frameId());
    }

    @Test
    void recordsSuccessfulSkillInLedgerAndTraceWithoutJournalEntries() {
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK);
        BifrostSession session = com.lokiscale.bifrost.internal.core.TestBifrostSessions.withId("session-evidence", 3);

        ExecutionFrame rootFrame = stateService.openMissionFrame(session, "rootVisibleSkill", Map.of());
        stateService.recordSuccessfulSkill(session, "invoiceParser", "task-1", false);
        stateService.recordEvidenceValidation(session, false, Map.of("skillName", "rootVisibleSkill"), Map.of("claims", List.of("isDuplicate")));
        stateService.closeMissionFrame(session, rootFrame);

        assertThat(session.getSuccessfulDirectSkills()).containsExactly("invoiceParser");
        assertThat(session.getJournalSnapshot()).isEmpty();
        TraceRecord evidenceRecord = readRecords(session).stream()
                .filter(record -> record.recordType() == TraceRecordType.EVIDENCE_RECORDED)
                .findFirst()
                .orElseThrow();
        assertThat(evidenceRecord.data().path("successfulSkill").asText()).isEqualTo("invoiceParser");
        assertThat(evidenceRecord.data().path("successfulDirectSkills")).hasSize(1);
        assertThat(evidenceRecord.data().has("evidenceTypes")).isFalse();
        assertThat(evidenceRecord.data().has("ledger")).isFalse();
        assertThat(readRecords(session)).anyMatch(record -> record.recordType() == TraceRecordType.EVIDENCE_VALIDATION_FAILED);
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
            public void recordToolRequested(BifrostSession session, ExecutionFrame frame, com.lokiscale.bifrost.internal.core.ToolTraceContext context, Object payload) {
            }

            @Override
            public void recordToolStarted(BifrostSession session, ExecutionFrame frame, com.lokiscale.bifrost.internal.core.ToolTraceContext context, Object payload) {
            }

            @Override
            public void recordToolCompleted(BifrostSession session, ExecutionFrame frame, com.lokiscale.bifrost.internal.core.ToolTraceContext context, Object payload) {
            }

            @Override
            public void recordToolFailed(BifrostSession session, ExecutionFrame frame, com.lokiscale.bifrost.internal.core.ToolTraceContext context, Object payload) {
            }

            @Override
            public void recordAdvisorRequestMutation(BifrostSession session, com.lokiscale.bifrost.internal.core.AdvisorTraceContext context, Object payload) {
            }

            @Override
            public void recordAdvisorResponseMutation(BifrostSession session, com.lokiscale.bifrost.internal.core.AdvisorTraceContext context, Object payload) {
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
            public void finalizeTrace(BifrostSession session, com.lokiscale.bifrost.internal.core.TraceCompletion completion) {
            }
        };
        DefaultExecutionStateService stateService = new DefaultExecutionStateService(FIXED_CLOCK, new com.lokiscale.bifrost.internal.runtime.usage.NoOpSessionUsageService(), failingRecorder);
        BifrostSession session = com.lokiscale.bifrost.internal.core.TestBifrostSessions.withId("session-open-failure", 3);

        assertThatThrownBy(() -> stateService.openMissionFrame(session, "rootVisibleSkill", Map.of("objective", "hello")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

        assertThat(session.getFramesSnapshot()).isEmpty();
    }

    private static ExecutionPlan plan(String planId) {
        return new ExecutionPlan(
                planId,
                "rootVisibleSkill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(new PlanTask("task-1", "Use tool", PlanTaskStatus.PENDING, null)));
    }

    private static List<TraceRecord> readRecords(BifrostSession session) {
        List<TraceRecord> records = new ArrayList<>();
        session.readTraceRecords(records::add);
        return records;
    }
}
