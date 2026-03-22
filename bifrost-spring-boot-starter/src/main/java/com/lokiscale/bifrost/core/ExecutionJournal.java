package com.lokiscale.bifrost.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ExecutionJournal {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build();

    private final List<JournalEntry> entries;

    public ExecutionJournal() {
        this(List.of());
    }

    @JsonCreator
    public ExecutionJournal(@JsonProperty("entries") List<JournalEntry> entries) {
        this.entries = entries == null ? new ArrayList<>() : new ArrayList<>(entries);
    }

    public void append(Instant timestamp, JournalLevel level, JournalEntryType type, Object payload) {
        append(timestamp, level, type, payload, null, null);
    }

    public void append(Instant timestamp,
                       JournalLevel level,
                       JournalEntryType type,
                       Object payload,
                       @Nullable String frameId,
                       @Nullable String route) {
        entries.add(new JournalEntry(
                Objects.requireNonNull(timestamp, "timestamp must not be null"),
                Objects.requireNonNull(level, "level must not be null"),
                Objects.requireNonNull(type, "type must not be null"),
                toJsonNode(payload),
                frameId,
                route));
    }

    @JsonProperty("entries")
    public List<JournalEntry> getEntriesSnapshot() {
        return List.copyOf(entries);
    }

    private static JsonNode toJsonNode(Object payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        return payload instanceof JsonNode jsonNode ? jsonNode.deepCopy() : OBJECT_MAPPER.valueToTree(payload);
    }
}
