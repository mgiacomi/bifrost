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

        session.logThought(Instant.parse("2026-03-15T12:00:00Z"), "plan");
        session.logToolExecution(
                Instant.parse("2026-03-15T12:00:01Z"),
                Map.of("route", "tool.run", "arguments", Map.of("id", 42)));
        session.logError(Instant.parse("2026-03-15T12:00:02Z"), "boom");

        assertThat(session.getJournalSnapshot())
                .extracting(JournalEntry::type)
                .containsExactly(JournalEntryType.THOUGHT, JournalEntryType.TOOL_CALL, JournalEntryType.ERROR);
        assertThat(session.getJournalSnapshot())
                .extracting(JournalEntry::level)
                .containsExactly(JournalLevel.INFO, JournalLevel.INFO, JournalLevel.ERROR);
        assertThat(session.getJournalSnapshot().get(0).payload().textValue()).isEqualTo("plan");
        assertThat(session.getJournalSnapshot().get(1).payload().get("route").textValue()).isEqualTo("tool.run");
        assertThat(session.getJournalSnapshot().get(1).payload().get("arguments").get("id").intValue()).isEqualTo(42);
        assertThat(session.getJournalSnapshot().get(2).payload().textValue()).isEqualTo("boom");
    }

    @Test
    void exposesImmutableJournalSnapshots() {
        BifrostSession session = new BifrostSession(2);
        session.logThought(Instant.parse("2026-03-15T12:00:00Z"), "plan");

        List<JournalEntry> snapshot = session.getJournalSnapshot();

        assertThatThrownBy(() -> snapshot.add(new JournalEntry(
                Instant.parse("2026-03-15T12:00:01Z"),
                JournalLevel.INFO,
                JournalEntryType.THOUGHT,
                new com.fasterxml.jackson.databind.ObjectMapper().valueToTree("extra"))))
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
        session.logPlanCreated(Instant.parse("2026-03-15T12:00:00Z"), created);
        session.replaceExecutionPlan(updated);
        session.logPlanUpdated(Instant.parse("2026-03-15T12:00:01Z"), updated);

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
        session.logLinterOutcome(Instant.parse("2026-03-15T12:00:03Z"), outcome);

        assertThat(session.getLastLinterOutcome()).contains(outcome);
        assertThat(session.getJournalSnapshot()).extracting(JournalEntry::type)
                .containsExactly(JournalEntryType.LINTER);
        assertThat(session.getJournalSnapshot().getFirst().payload().get("status").textValue()).isEqualTo("PASSED");
    }

    private static ExecutionFrame frame(String frameId, String route) {
        return new ExecutionFrame(
                frameId,
                null,
                OperationType.CAPABILITY,
                route,
                Map.of("route", route),
                Instant.parse("2026-03-15T12:00:00Z"));
    }
}
