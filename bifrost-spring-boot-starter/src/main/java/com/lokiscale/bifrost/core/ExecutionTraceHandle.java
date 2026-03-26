package com.lokiscale.bifrost.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

public interface ExecutionTraceHandle {

    TraceRecord append(
            TraceRecordType recordType,
            ExecutionFrame frame,
            TraceFrameType frameType,
            Map<String, Object> metadata,
            Object data) throws IOException;

    TraceRecord append(
            TraceRecordType recordType,
            Map<String, Object> metadata,
            Object data) throws IOException;

    ExecutionTrace snapshot();

    Path tracePath();

    void markErrored();

    void finalizeTrace(Map<String, Object> completionMetadata) throws IOException;

    void readRecords(Consumer<TraceRecord> consumer) throws IOException;
}
