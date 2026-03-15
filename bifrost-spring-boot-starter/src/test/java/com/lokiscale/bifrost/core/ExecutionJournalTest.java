package com.lokiscale.bifrost.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
}
