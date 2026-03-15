package com.lokiscale.bifrost.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

public record JournalEntry(
        Instant timestamp,
        JournalLevel level,
        JournalEntryType type,
        JsonNode payload) {

    public JournalEntry {
        timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
        level = Objects.requireNonNull(level, "level must not be null");
        type = Objects.requireNonNull(type, "type must not be null");
        payload = Objects.requireNonNull(payload, "payload must not be null");
    }
}
