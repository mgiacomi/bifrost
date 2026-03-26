package com.lokiscale.bifrost.runtime.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.lokiscale.bifrost.core.ExecutionFrame;
import com.lokiscale.bifrost.core.ExecutionTrace;
import com.lokiscale.bifrost.core.ExecutionTraceHandle;
import com.lokiscale.bifrost.core.TraceFrameType;
import com.lokiscale.bifrost.core.TracePersistencePolicy;
import com.lokiscale.bifrost.core.TraceRecord;
import com.lokiscale.bifrost.core.TraceRecordType;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class DefaultExecutionTraceHandle implements ExecutionTraceHandle {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build();

    private static final int DEFAULT_CHUNK_SIZE = 4096;

    private final String traceId;
    private final String sessionId;
    private final Path tracePath;
    private final TracePersistencePolicy persistencePolicy;
    private final Clock clock;
    private final NdjsonTraceRecordWriter writer;
    private final NdjsonExecutionTraceReader reader;
    private final AtomicLong sequence;
    private final AtomicBoolean initialized;

    private volatile boolean errored;
    private volatile boolean completed;

    public DefaultExecutionTraceHandle(String sessionId, TracePersistencePolicy persistencePolicy, Clock clock) {
        this(newTraceId(), sessionId, null, persistencePolicy, false, false, clock, 0L, false);
        resetTraceFile();
        initialize();
    }

    private DefaultExecutionTraceHandle(
            String traceId,
            String sessionId,
            Path tracePath,
            TracePersistencePolicy persistencePolicy,
            boolean errored,
            boolean completed,
            Clock clock,
            long startingSequence,
            boolean initialized) {
        this.traceId = requireNonBlank(traceId, "traceId");
        this.sessionId = requireNonBlank(sessionId, "sessionId");
        this.tracePath = tracePath == null ? defaultPath(this.sessionId, this.traceId) : tracePath;
        this.persistencePolicy = persistencePolicy == null ? TracePersistencePolicy.NEVER : persistencePolicy;
        this.errored = errored;
        this.completed = completed;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.writer = new NdjsonTraceRecordWriter(this.tracePath);
        this.reader = new NdjsonExecutionTraceReader();
        this.sequence = new AtomicLong(startingSequence);
        this.initialized = new AtomicBoolean(initialized);
    }

    private void initialize() {
        try {
            if (initialized.compareAndSet(false, true)) {
                appendInternal(TraceRecordType.TRACE_STARTED, null, null, null, null, Map.of("tracePath", tracePath.toString()), Map.of("sessionId", sessionId));
                appendInternal(TraceRecordType.TRACE_CAPTURE_POLICY_RECORDED, null, null, null, null, Map.of("persistencePolicy", persistencePolicy.name()), null);
            }
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to initialize execution trace for session '" + sessionId + "'", ex);
        }
    }

    @Override
    public synchronized TraceRecord append(
            TraceRecordType recordType,
            ExecutionFrame frame,
            TraceFrameType frameType,
            Map<String, Object> metadata,
            Object data) throws IOException {
        Objects.requireNonNull(frame, "frame must not be null");
        return appendInternal(
                recordType,
                frame.frameId(),
                frame.parentFrameId(),
                frameType,
                frame.route(),
                metadata,
                data);
    }

    @Override
    public synchronized TraceRecord append(TraceRecordType recordType, Map<String, Object> metadata, Object data) throws IOException {
        return appendInternal(recordType, null, null, null, null, metadata, data);
    }

    @Override
    public ExecutionTrace snapshot() {
        return new ExecutionTrace(traceId, sessionId, visibleTracePath(), persistencePolicy, errored, completed);
    }

    @Override
    public Path tracePath() {
        return tracePath;
    }

    @Override
    public synchronized void markErrored() {
        errored = true;
    }

    @Override
    public synchronized void finalizeTrace(Map<String, Object> completionMetadata) throws IOException {
        if (completed) {
            return;
        }
        initialize();
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (completionMetadata != null) {
            metadata.putAll(completionMetadata);
        }
        metadata.put("errored", errored);
        metadata.put("persistencePolicy", persistencePolicy.name());
        append(TraceRecordType.TRACE_COMPLETED, metadata, null);
        completed = true;
        if (shouldDeleteAfterCompletion()) {
            Files.deleteIfExists(tracePath);
        }
    }

    @Override
    public synchronized void readRecords(Consumer<TraceRecord> consumer) throws IOException {
        reader.read(tracePath, consumer);
    }

    private void resetTraceFile() {
        try {
            Files.deleteIfExists(tracePath);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to reset execution trace file for session '" + sessionId + "'", ex);
        }
    }

    private TraceRecord appendInternal(
            TraceRecordType recordType,
            @Nullable String frameId,
            @Nullable String parentFrameId,
            @Nullable TraceFrameType frameType,
            @Nullable String route,
            Map<String, Object> metadata,
            Object data) throws IOException {
        if (completed) {
            throw new IllegalStateException("Execution trace '" + traceId + "' is already completed");
        }
        initialize();
        JsonNode jsonData = toJson(data);
        Map<String, Object> safeMetadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
        long nextSequence = sequence.incrementAndGet();
        if (jsonData != null) {
            String serialized = jsonData.isTextual() ? jsonData.asText() : OBJECT_MAPPER.writeValueAsString(jsonData);
            if (serialized.length() > DEFAULT_CHUNK_SIZE) {
                String payloadId = UUID.randomUUID().toString();
                int chunkCount = (int) Math.ceil((double) serialized.length() / DEFAULT_CHUNK_SIZE);
                safeMetadata.put("payloadId", payloadId);
                safeMetadata.put("chunkCount", chunkCount);
                safeMetadata.put("payloadChunked", true);
                safeMetadata.put("contentType", jsonData.isTextual() ? "text/plain" : "application/json");
                TraceRecord envelope = buildRecord(
                        nextSequence,
                        recordType,
                        frameId,
                        parentFrameId,
                        frameType,
                        route,
                        safeMetadata,
                        null);
                writer.append(envelope);
                writeChunks(payloadId, chunkCount, serialized, frameId, parentFrameId, frameType, route, safeMetadata);
                return envelope;
            }
        }
        TraceRecord record = buildRecord(nextSequence, recordType, frameId, parentFrameId, frameType, route, safeMetadata, jsonData);
        writer.append(record);
        return record;
    }

    private void writeChunks(
            String payloadId,
            int chunkCount,
            String serialized,
            @Nullable String frameId,
            @Nullable String parentFrameId,
            @Nullable TraceFrameType frameType,
            @Nullable String route,
            Map<String, Object> baseMetadata) throws IOException {
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            int start = chunkIndex * DEFAULT_CHUNK_SIZE;
            int end = Math.min(serialized.length(), start + DEFAULT_CHUNK_SIZE);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("payloadId", payloadId);
            metadata.put("chunkIndex", chunkIndex);
            metadata.put("chunkCount", chunkCount);
            metadata.put("contentType", baseMetadata.get("contentType"));
            TraceRecord chunk = buildRecord(
                    sequence.incrementAndGet(),
                    TraceRecordType.PAYLOAD_CHUNK_APPENDED,
                    frameId,
                    parentFrameId,
                    frameType,
                    route,
                    metadata,
                    TextNode.valueOf(serialized.substring(start, end)));
            writer.append(chunk);
        }
    }

    private TraceRecord buildRecord(
            long sequenceNumber,
            TraceRecordType recordType,
            @Nullable String frameId,
            @Nullable String parentFrameId,
            @Nullable TraceFrameType frameType,
            @Nullable String route,
            Map<String, Object> metadata,
            @Nullable JsonNode data) {
        return new TraceRecord(
                TraceRecord.CURRENT_SCHEMA_VERSION,
                traceId,
                sessionId,
                sequenceNumber,
                resolveTimestamp(metadata),
                recordType,
                frameId,
                parentFrameId,
                frameType,
                route,
                Thread.currentThread().getName(),
                metadata,
                data);
    }

    private Instant resolveTimestamp(Map<String, Object> metadata) {
        Object timestampOverride = metadata == null ? null : metadata.get("timestampOverride");
        if (timestampOverride instanceof String timestampText && !timestampText.isBlank()) {
            try {
                return Instant.parse(timestampText);
            }
            catch (RuntimeException ignored) {
                // Fall back to append time if the override is malformed.
            }
        }
        return Instant.now(clock);
    }

    @Nullable
    private JsonNode toJson(Object data) {
        if (data == null) {
            return null;
        }
        if (data instanceof JsonNode jsonNode) {
            return jsonNode.deepCopy();
        }
        return OBJECT_MAPPER.valueToTree(data);
    }

    private boolean shouldDeleteAfterCompletion() {
        return switch (persistencePolicy) {
            case NEVER -> true;
            case ONERROR -> !errored;
            case ALWAYS -> false;
        };
    }

    @Nullable
    private String visibleTracePath() {
        if (completed && shouldDeleteAfterCompletion() && Files.notExists(tracePath)) {
            return null;
        }
        return tracePath.toString();
    }

    private static Path defaultPath(String sessionId, String traceId) {
        return Path.of(System.getProperty("java.io.tmpdir"), sessionId + "." + traceId + ".execution-trace.ndjson");
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString();
    }

    private static long maxSequence(Path path) {
        AtomicLong maxSequence = new AtomicLong();
        try {
            new NdjsonExecutionTraceReader().read(path, record -> maxSequence.set(Math.max(maxSequence.get(), record.sequence())));
        }
        catch (IOException ignored) {
            return 0L;
        }
        return maxSequence.get();
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
