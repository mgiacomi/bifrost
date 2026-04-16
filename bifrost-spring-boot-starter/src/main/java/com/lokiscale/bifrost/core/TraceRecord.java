package com.lokiscale.bifrost.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record TraceRecord(
        int schemaVersion,
        String traceId,
        String sessionId,
        long sequence,
        Instant timestamp,
        TraceRecordType recordType,
        @Nullable String frameId,
        @Nullable String parentFrameId,
        @Nullable TraceFrameType frameType,
        @Nullable String route,
        String threadName,
        Map<String, Object> metadata,
        @Nullable JsonNode data)
{
    public static final int CURRENT_SCHEMA_VERSION = 1;

    @JsonCreator
    public TraceRecord(
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("traceId") String traceId,
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("sequence") long sequence,
            @JsonProperty("timestamp") Instant timestamp,
            @JsonProperty("recordType") TraceRecordType recordType,
            @JsonProperty("frameId") @Nullable String frameId,
            @JsonProperty("parentFrameId") @Nullable String parentFrameId,
            @JsonProperty("frameType") @Nullable TraceFrameType frameType,
            @JsonProperty("route") @Nullable String route,
            @JsonProperty("threadName") String threadName,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("data") @Nullable JsonNode data)
    {
        this.schemaVersion = schemaVersion <= 0 ? CURRENT_SCHEMA_VERSION : schemaVersion;
        this.traceId = requireNonBlank(traceId, "traceId");
        this.sessionId = requireNonBlank(sessionId, "sessionId");
        this.sequence = sequence;
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
        this.recordType = Objects.requireNonNull(recordType, "recordType must not be null");
        this.frameId = normalizeNullable(frameId);
        this.parentFrameId = normalizeNullable(parentFrameId);
        this.frameType = frameType;
        this.route = normalizeNullable(route);
        this.threadName = threadName == null || threadName.isBlank() ? "unknown" : threadName;
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        this.data = data == null ? null : data.deepCopy();
    }

    private static String requireNonBlank(String value, String fieldName)
    {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank())
        {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    @Nullable
    private static String normalizeNullable(@Nullable String value)
    {
        if (value == null || value.isBlank())
        {
            return null;
        }
        return value;
    }
}
