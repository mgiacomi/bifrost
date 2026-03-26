package com.lokiscale.bifrost.core;

import com.lokiscale.bifrost.linter.LinterOutcome;
import com.lokiscale.bifrost.linter.LinterOutcomeStatus;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BifrostSessionTest {

    @Test
    void createsSessionWithGeneratedIdAndConfiguredMaxDepth() {
        BifrostSession session = new BifrostSession(3);

        assertThat(session.getSessionId()).isNotBlank();
        assertThat(session.getMaxDepth()).isEqualTo(3);
        assertThat(session.getFramesSnapshot()).isEmpty();
    }

    @Test
    void pushAndPopFrameUsesLifoOrder() {
        BifrostSession session = new BifrostSession(3);
        ExecutionFrame first = frame("frame-1", "route.one");
        ExecutionFrame second = frame("frame-2", "route.two");

        session.pushFrame(first);
        session.pushFrame(second);

        assertThat(session.peekFrame()).isEqualTo(second);
        assertThat(session.getFramesSnapshot()).containsExactly(second, first);
        assertThat(session.popFrame()).isEqualTo(second);
        assertThat(session.popFrame()).isEqualTo(first);
        assertThat(session.getFramesSnapshot()).isEmpty();
    }

    @Test
    void throwsWhenPushingBeyondMaxDepthWithoutMutatingStack() {
        BifrostSession session = new BifrostSession("session-1", 1);
        ExecutionFrame first = frame("frame-1", "route.one");
        ExecutionFrame second = frame("frame-2", "route.two");
        session.pushFrame(first);

        assertThatThrownBy(() -> session.pushFrame(second))
                .isInstanceOf(BifrostStackOverflowException.class)
                .hasMessageContaining("session-1")
                .hasMessageContaining("route.two")
                .hasMessageContaining("1");

        assertThat(session.peekFrame()).isEqualTo(first);
        assertThat(session.getFramesSnapshot()).containsExactly(first);
    }

    @Test
    void throwsWhenPoppingEmptyStack() {
        BifrostSession session = new BifrostSession(2);

        assertThatThrownBy(session::popFrame)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot pop execution frame from an empty session stack.");
    }

    @Test
    void exposesImmutableFrameSnapshots() {
        BifrostSession session = new BifrostSession(2);
        session.pushFrame(frame("frame-1", "route.one"));

        List<ExecutionFrame> snapshot = session.getFramesSnapshot();

        assertThatThrownBy(() -> snapshot.add(frame("frame-2", "route.two")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void appendsJournalEntriesInSequentialOrder() {
        BifrostSession session = new BifrostSession("session-1", 4);

        ExecutionPlan plan = plan("plan-1");
        appendRecord(session, TraceRecordType.PLAN_CREATED, Instant.parse("2026-03-15T12:00:00Z"), Map.of("planId", plan.planId()), plan);
        appendRecord(
                session,
                TraceRecordType.TOOL_CALL_REQUESTED,
                Instant.parse("2026-03-15T12:00:01Z"),
                Map.of(),
                Map.of("route", "tool.run", "arguments", Map.of("id", 42)));
        appendError(session, Instant.parse("2026-03-15T12:00:02Z"), "boom");

        assertThat(session.getJournalSnapshot())
                .extracting(JournalEntry::type)
                .containsExactly(JournalEntryType.PLAN_CREATED, JournalEntryType.TOOL_CALL, JournalEntryType.ERROR);
        assertThat(session.getJournalSnapshot())
                .extracting(JournalEntry::level)
                .containsExactly(JournalLevel.INFO, JournalLevel.INFO, JournalLevel.ERROR);
        assertThat(session.getJournalSnapshot().get(0).payload().get("planId").textValue()).isEqualTo("plan-1");
        assertThat(session.getJournalSnapshot().get(1).payload().get("capabilityName").textValue()).isEqualTo("tool.run");
        assertThat(session.getJournalSnapshot().get(1).payload().get("details").get("arguments").get("id").intValue()).isEqualTo(42);
        assertThat(session.getJournalSnapshot().get(2).payload().textValue()).isEqualTo("boom");
    }

    @Test
    void sessionBindsFramesToJournalEntries() {
        BifrostSession session = new BifrostSession("session-1", 4);
        ExecutionFrame frame = frame("frame-1", "route.one");

        session.pushFrame(frame);
        ExecutionPlan plan = plan("plan-1");
        appendRecord(session, TraceRecordType.PLAN_CREATED, Instant.parse("2026-03-15T12:00:00Z"), Map.of("planId", plan.planId()), plan);
        appendRecord(
                session,
                TraceRecordType.TOOL_CALL_REQUESTED,
                Instant.parse("2026-03-15T12:00:01Z"),
                Map.of("capabilityName", "tool.run", "linkedTaskId", "task-1"),
                TaskExecutionEvent.linked("tool.run", "task-1", Map.of("arguments", Map.of("id", 42)), null));
        session.popFrame();

        assertThat(session.getJournalSnapshot()).allSatisfy(entry -> {
            assertThat(entry.frameId()).isEqualTo("frame-1");
            assertThat(entry.route()).isEqualTo("route.one");
        });
    }

    @Test
    void exposesImmutableJournalSnapshots() {
        BifrostSession session = new BifrostSession(2);
        ExecutionPlan plan = plan("plan-1");
        appendRecord(session, TraceRecordType.PLAN_CREATED, Instant.parse("2026-03-15T12:00:00Z"), Map.of("planId", plan.planId()), plan);

        List<JournalEntry> snapshot = session.getJournalSnapshot();

        assertThatThrownBy(() -> snapshot.add(new JournalEntry(
                Instant.parse("2026-03-15T12:00:01Z"),
                JournalLevel.INFO,
                JournalEntryType.THOUGHT,
                new com.fasterxml.jackson.databind.ObjectMapper().valueToTree("extra"),
                null,
                null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void journalsPlanCreationAndUpdateSeparatelyFromActivePlanState() {
        BifrostSession session = new BifrostSession("session-1", 2);
        ExecutionPlan created = new ExecutionPlan(
                "plan-1",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(new PlanTask("task-1", "Plan", PlanTaskStatus.PENDING, null)));
        ExecutionPlan updated = created.updateTask("task-1",
                task -> task.withStatus(PlanTaskStatus.COMPLETED, "done"));

        session.replaceExecutionPlan(created);
        appendRecord(session, TraceRecordType.PLAN_CREATED, Instant.parse("2026-03-15T12:00:00Z"), Map.of("planId", created.planId()), created);
        session.replaceExecutionPlan(updated);
        appendRecord(session, TraceRecordType.PLAN_UPDATED, Instant.parse("2026-03-15T12:00:01Z"), Map.of("planId", updated.planId()), updated);

        assertThat(session.getExecutionPlan()).contains(updated);
        assertThat(session.getJournalSnapshot()).extracting(JournalEntry::type)
                .containsExactly(JournalEntryType.PLAN_CREATED, JournalEntryType.PLAN_UPDATED);
        assertThat(session.getJournalSnapshot().get(1).payload().get("tasks").get(0).get("status").textValue())
                .isEqualTo("COMPLETED");
    }

    @Test
    void storesAuthenticationAsRuntimeOnlySessionState() {
        BifrostSession session = new BifrostSession("session-1", 2);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "user",
                "pw",
                AuthorityUtils.createAuthorityList("ROLE_ALLOWED"));

        session.setAuthentication(authentication);

        assertThat(session.getAuthentication()).contains(authentication);
    }

    @Test
    void storesLastLinterOutcomeAndJournalsItSeparately() {
        BifrostSession session = new BifrostSession("session-1", 2);
        LinterOutcome outcome = new LinterOutcome(
                "linted.skill",
                "regex",
                2,
                1,
                2,
                LinterOutcomeStatus.PASSED,
                "Return fenced YAML only.");

        session.setLastLinterOutcome(outcome);
        appendRecord(
                session,
                TraceRecordType.LINTER_RECORDED,
                Instant.parse("2026-03-15T12:00:03Z"),
                Map.of("skillName", outcome.skillName(), "status", outcome.status().name()),
                outcome);

        assertThat(session.getLastLinterOutcome()).contains(outcome);
        assertThat(session.getJournalSnapshot()).extracting(JournalEntry::type)
                .containsExactly(JournalEntryType.LINTER);
        assertThat(session.getJournalSnapshot().getFirst().payload().get("status").textValue()).isEqualTo("PASSED");
    }

    @Test
    void preservesFinalizedJournalAcrossRepeatedFinalizationAfterTraceDeletion() {
        BifrostSession session = TestBifrostSessions.withId(
                "session-repeat-finalize",
                2,
                null,
                TracePersistencePolicy.ONERROR,
                java.time.Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), java.time.ZoneOffset.UTC));
        ExecutionPlan plan = plan("plan-1");
        appendRecord(session, TraceRecordType.PLAN_CREATED, Instant.parse("2026-03-15T12:00:00Z"), Map.of("planId", plan.planId()), plan);

        session.finalizeTrace(Map.of("status", "completed"));
        session.finalizeTrace(Map.of("entryPoint", "session-runner", "status", "completed"));

        assertThat(session.getExecutionTrace().completed()).isTrue();
        assertThat(session.getExecutionTrace().filePath()).isNull();
        assertThat(session.getExecutionJournal().getEntriesSnapshot())
                .extracting(JournalEntry::type)
                .containsExactly(JournalEntryType.PLAN_CREATED);
    }

    private static ExecutionFrame frame(String frameId, String route) {
        return new ExecutionFrame(
                frameId,
                null,
                OperationType.CAPABILITY,
                TraceFrameType.ROOT_MISSION,
                route,
                Map.of("route", route),
                Instant.parse("2026-03-15T12:00:00Z"));
    }

    private static void appendError(BifrostSession session, Instant timestamp, Object payload) {
        session.markTraceErrored();
        appendRecord(session, TraceRecordType.ERROR_RECORDED, timestamp, Map.of(), payload);
    }

    private static ExecutionPlan plan(String planId) {
        return new ExecutionPlan(
                planId,
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(new PlanTask("task-1", "Plan", PlanTaskStatus.PENDING, null)));
    }

    private static void appendRecord(BifrostSession session,
                                     TraceRecordType type,
                                     Instant timestamp,
                                     Map<String, Object> metadata,
                                     Object payload) {
        java.util.LinkedHashMap<String, Object> traceMetadata = new java.util.LinkedHashMap<>();
        if (metadata != null) {
            traceMetadata.putAll(metadata);
        }
        traceMetadata.put("timestampOverride", timestamp.toString());
        session.appendTraceRecord(type, traceMetadata, payload);
    }
}
