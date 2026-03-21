package com.lokiscale.bifrost.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.lokiscale.bifrost.linter.LinterOutcome;
import com.lokiscale.bifrost.linter.LinterOutcomeStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionJournalTest {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @Test
    void roundTripsExecutionJournalThroughJackson() throws Exception {
        ExecutionJournal journal = new ExecutionJournal();
        journal.append(Instant.parse("2026-03-15T12:00:00Z"), JournalLevel.INFO, JournalEntryType.THOUGHT, "draft plan");
        journal.append(
                Instant.parse("2026-03-15T12:00:01Z"),
                JournalLevel.INFO,
                JournalEntryType.TOOL_CALL,
                Map.of("route", "tool.run", "arguments", Map.of("id", 42)));

        String json = OBJECT_MAPPER.writeValueAsString(journal);
        ExecutionJournal restored = OBJECT_MAPPER.readValue(json, ExecutionJournal.class);

        assertThat(restored.getEntriesSnapshot()).containsExactlyElementsOf(journal.getEntriesSnapshot());
    }

    @Test
    void supportsStructuredPayloadsWithoutCustomFixtureTypes() {
        ExecutionJournal journal = new ExecutionJournal();

        journal.append(Instant.parse("2026-03-15T12:00:00Z"), JournalLevel.INFO, JournalEntryType.THOUGHT, "draft plan");
        journal.append(
                Instant.parse("2026-03-15T12:00:01Z"),
                JournalLevel.INFO,
                JournalEntryType.TOOL_RESULT,
                Map.of("status", "ok", "result", Map.of("count", 2)));

        assertThat(journal.getEntriesSnapshot().get(0).payload().isTextual()).isTrue();
        assertThat(journal.getEntriesSnapshot().get(0).payload().textValue()).isEqualTo("draft plan");
        assertThat(journal.getEntriesSnapshot().get(1).payload().isObject()).isTrue();
        assertThat(journal.getEntriesSnapshot().get(1).payload().get("status").textValue()).isEqualTo("ok");
        assertThat(journal.getEntriesSnapshot().get(1).payload().get("result").get("count").intValue()).isEqualTo(2);
    }

    @Test
    void serializesPlanSpecificJournalEntryTypesWithStructuredPayloads() {
        ExecutionJournal journal = new ExecutionJournal();
        ExecutionPlan plan = new ExecutionPlan(
                "plan-1",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(
                        new PlanTask("task-1", "Plan", PlanTaskStatus.PENDING, null),
                        new PlanTask("task-2", "Execute", PlanTaskStatus.IN_PROGRESS, "started")));

        journal.append(Instant.parse("2026-03-15T12:00:00Z"), JournalLevel.INFO, JournalEntryType.PLAN_CREATED, plan);
        journal.append(Instant.parse("2026-03-15T12:00:01Z"), JournalLevel.INFO, JournalEntryType.PLAN_UPDATED, plan);

        assertThat(journal.getEntriesSnapshot()).extracting(JournalEntry::type)
                .containsExactly(JournalEntryType.PLAN_CREATED, JournalEntryType.PLAN_UPDATED);
        assertThat(journal.getEntriesSnapshot().get(0).payload().get("tasks").get(1).get("status").textValue())
                .isEqualTo("IN_PROGRESS");
        assertThat(journal.getEntriesSnapshot().get(0).payload().get("tasks").get(1).get("note").textValue())
                .isEqualTo("started");
    }

    @Test
    void serializesLinterOutcomesWithStableStructuredFields() {
        ExecutionJournal journal = new ExecutionJournal();
        LinterOutcome outcome = new LinterOutcome(
                "linted.skill",
                "regex",
                3,
                2,
                2,
                LinterOutcomeStatus.EXHAUSTED,
                "Return fenced YAML only.");

        journal.append(Instant.parse("2026-03-15T12:00:00Z"), JournalLevel.INFO, JournalEntryType.LINTER, outcome);

        assertThat(journal.getEntriesSnapshot().getFirst().payload().get("retryCount").intValue()).isEqualTo(2);
        assertThat(journal.getEntriesSnapshot().getFirst().payload().get("status").textValue()).isEqualTo("EXHAUSTED");
    }
}
