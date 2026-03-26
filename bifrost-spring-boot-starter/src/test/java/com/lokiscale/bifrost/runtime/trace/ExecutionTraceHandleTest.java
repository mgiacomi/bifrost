package com.lokiscale.bifrost.runtime.trace;

import com.lokiscale.bifrost.core.TracePersistencePolicy;
import com.lokiscale.bifrost.core.TraceRecord;
import com.lokiscale.bifrost.core.TraceRecordType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionTraceHandleTest {

    @Test
    void appliesNeverOnErrorAndAlwaysPersistencePolicies() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-03-24T12:00:00Z"), ZoneOffset.UTC);

        DefaultExecutionTraceHandle never = new DefaultExecutionTraceHandle("never-trace", TracePersistencePolicy.NEVER, clock);
        never.finalizeTrace(java.util.Map.of("status", "ok"));
        assertThat(Files.exists(never.tracePath())).isFalse();

        DefaultExecutionTraceHandle onError = new DefaultExecutionTraceHandle("onerror-trace", TracePersistencePolicy.ONERROR, clock);
        onError.markErrored();
        onError.append(TraceRecordType.ERROR_RECORDED, java.util.Map.of("kind", "runtime"), java.util.Map.of("message", "boom"));
        onError.finalizeTrace(java.util.Map.of("status", "error"));
        assertThat(Files.exists(onError.tracePath())).isTrue();

        DefaultExecutionTraceHandle always = new DefaultExecutionTraceHandle("always-trace", TracePersistencePolicy.ALWAYS, clock);
        always.finalizeTrace(java.util.Map.of("status", "ok"));
        assertThat(Files.exists(always.tracePath())).isTrue();
    }

    @Test
    void honorsExplicitTimestampOverridesInTraceEnvelope() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-03-24T12:00:00Z"), ZoneOffset.UTC);
        DefaultExecutionTraceHandle handle = new DefaultExecutionTraceHandle("override-trace", TracePersistencePolicy.ALWAYS, clock);

        handle.append(
                TraceRecordType.MODEL_REQUEST_SENT,
                java.util.Map.of("timestampOverride", "2026-03-20T05:06:07Z"),
                java.util.Map.of("objective", "after"));

        List<TraceRecord> records = readRecords(handle);

        assertThat(records.getLast().recordType()).isEqualTo(TraceRecordType.MODEL_REQUEST_SENT);
        assertThat(records.getLast().timestamp()).isEqualTo(Instant.parse("2026-03-20T05:06:07Z"));
        Files.deleteIfExists(handle.tracePath());
    }

    @Test
    void usesSessionNamedTempFiles() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-03-24T12:00:00Z"), ZoneOffset.UTC);
        DefaultExecutionTraceHandle handle = new DefaultExecutionTraceHandle("shared-session", TracePersistencePolicy.ALWAYS, clock);

        assertThat(handle.tracePath().getFileName().toString())
                .startsWith("shared-session.")
                .endsWith(".execution-trace.ndjson");

        Files.deleteIfExists(handle.tracePath());
    }

    @Test
    void usesDistinctTraceFilesForRepeatedRunsOfTheSameSessionId() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-03-24T12:00:00Z"), ZoneOffset.UTC);
        DefaultExecutionTraceHandle first = new DefaultExecutionTraceHandle("shared-session", TracePersistencePolicy.ALWAYS, clock);
        DefaultExecutionTraceHandle second = new DefaultExecutionTraceHandle("shared-session", TracePersistencePolicy.ALWAYS, clock);

        assertThat(first.tracePath()).isNotEqualTo(second.tracePath());
        assertThat(first.tracePath().getFileName().toString()).startsWith("shared-session.");
        assertThat(second.tracePath().getFileName().toString()).startsWith("shared-session.");

        Files.deleteIfExists(first.tracePath());
        Files.deleteIfExists(second.tracePath());
    }

    @Test
    void rejectsAppendsAfterTraceFinalization() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-03-24T12:00:00Z"), ZoneOffset.UTC);
        DefaultExecutionTraceHandle handle = new DefaultExecutionTraceHandle("completed-trace", TracePersistencePolicy.ALWAYS, clock);

        handle.finalizeTrace(java.util.Map.of("status", "ok"));

        assertThatThrownBy(() -> handle.append(TraceRecordType.MODEL_REQUEST_SENT, java.util.Map.of(), java.util.Map.of("objective", "late")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already completed");

        Files.deleteIfExists(handle.tracePath());
    }

    private static List<TraceRecord> readRecords(DefaultExecutionTraceHandle handle) throws Exception {
        List<TraceRecord> records = new ArrayList<>();
        handle.readRecords(records::add);
        return records;
    }
}
