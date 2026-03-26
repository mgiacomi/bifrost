package com.lokiscale.bifrost.core;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BifrostSessionRunnerTest {

    @Test
    void defaultsNewSessionsToRetainTraceOnError() {
        BifrostSessionRunner sessionRunner = new BifrostSessionRunner(4);

        TracePersistencePolicy persistencePolicy = sessionRunner.callWithNewSession(
                session -> session.getExecutionTrace().persistencePolicy());

        assertThat(persistencePolicy).isEqualTo(TracePersistencePolicy.ONERROR);
    }

    @Test
    void finalizesStandaloneRunnerSessionsAndWritesTerminalTraceRecord() throws Exception {
        BifrostSessionRunner sessionRunner = new BifrostSessionRunner(4, TracePersistencePolicy.ALWAYS);

        String tracePathText = sessionRunner.callWithNewSession(session -> {
            appendRecord(session, TraceRecordType.MODEL_REQUEST_SENT, Instant.parse("2026-03-15T12:00:00Z"), Map.of("segment", "test"), Map.of("objective", "runner"));
            return session.getExecutionTrace().filePath();
        });

        var tracePath = java.nio.file.Path.of(tracePathText);
        try {
            assertThat(Files.exists(tracePath)).isTrue();
            List<TraceRecord> records = new ArrayList<>();
            new com.lokiscale.bifrost.runtime.trace.NdjsonExecutionTraceReader().read(tracePath, records::add);
            assertThat(records).extracting(TraceRecord::recordType)
                    .contains(TraceRecordType.TRACE_COMPLETED);
        }
        finally {
            Files.deleteIfExists(tracePath);
        }
    }

    @Test
    void retainsErroredStandaloneRunnerTracesUnderOnErrorPolicy() throws Exception {
        BifrostSessionRunner sessionRunner = new BifrostSessionRunner(4, TracePersistencePolicy.ONERROR);

        java.util.concurrent.atomic.AtomicReference<String> tracePathText = new java.util.concurrent.atomic.AtomicReference<>();
        String sessionId = null;
        try {
            sessionRunner.callWithNewSession(session -> {
                appendRecord(session, TraceRecordType.MODEL_REQUEST_SENT, Instant.parse("2026-03-15T12:00:00Z"), Map.of("segment", "test"), Map.of("objective", "runner"));
                tracePathText.set(session.getExecutionTrace().filePath());
                throw new IllegalStateException(session.getSessionId());
            });
        }
        catch (IllegalStateException ex) {
            sessionId = ex.getMessage();
        }

        assertThat(sessionId).isNotBlank();
        assertThat(tracePathText.get()).isNotBlank();
        var tracePath = java.nio.file.Path.of(tracePathText.get());
        try {
            assertThat(Files.exists(tracePath)).isTrue();
            List<TraceRecord> records = new ArrayList<>();
            new com.lokiscale.bifrost.runtime.trace.NdjsonExecutionTraceReader().read(tracePath, records::add);
            assertThat(records.getLast().recordType()).isEqualTo(TraceRecordType.TRACE_COMPLETED);
            assertThat(records.getLast().metadata()).containsEntry("errored", true);
        }
        finally {
            Files.deleteIfExists(tracePath);
        }
    }

    @Test
    void recordsFailedTerminalStatusWhenStandaloneRunnerActionFailsBeforeOpeningFrames() throws Exception {
        BifrostSessionRunner sessionRunner = new BifrostSessionRunner(4, TracePersistencePolicy.ONERROR);

        java.util.concurrent.atomic.AtomicReference<String> tracePathText = new java.util.concurrent.atomic.AtomicReference<>();
        assertThatThrownBy(() -> sessionRunner.callWithNewSession(session -> {
            tracePathText.set(session.getExecutionTrace().filePath());
            throw new IllegalArgumentException("boom");
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("boom");

        assertThat(tracePathText.get()).isNotBlank();
        var tracePath = java.nio.file.Path.of(tracePathText.get());
        try {
            List<TraceRecord> records = new ArrayList<>();
            new com.lokiscale.bifrost.runtime.trace.NdjsonExecutionTraceReader().read(tracePath, records::add);
            assertThat(records.getLast().recordType()).isEqualTo(TraceRecordType.TRACE_COMPLETED);
            assertThat(records.getLast().metadata())
                    .containsEntry("errored", true)
                    .containsEntry("status", "failed")
                    .containsEntry("exceptionType", IllegalArgumentException.class.getName())
                    .containsEntry("message", "boom");
        }
        finally {
            Files.deleteIfExists(tracePath);
        }
    }

    @Test
    void createsDistinctSessionsAcrossConcurrentVirtualThreads() throws Exception {
        BifrostSessionRunner sessionRunner = new BifrostSessionRunner(4);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = executor.submit(() ->
                    sessionRunner.callWithNewSession(session -> BifrostSession.getCurrentSession().getSessionId()));
            Future<String> second = executor.submit(() ->
                    sessionRunner.callWithNewSession(session -> BifrostSession.getCurrentSession().getSessionId()));

            assertThat(Set.of(first.get(), second.get())).hasSize(2);
        }
    }

    @Test
    void isolatesFrameMutationAcrossConcurrentVirtualThreads() throws InterruptedException, ExecutionException {
        BifrostSessionRunner sessionRunner = new BifrostSessionRunner(4);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = executor.submit(() -> sessionRunner.callWithNewSession(session -> {
                session.pushFrame(frame("frame-1", "route.one"));
                String result = session.getSessionId() + ":" + session.getFramesSnapshot().size() + ":" + session.peekFrame().route();
                session.popFrame();
                return result;
            }));
            Future<String> second = executor.submit(() -> sessionRunner.callWithNewSession(session -> {
                session.pushFrame(frame("frame-2", "route.two"));
                String result = session.getSessionId() + ":" + session.getFramesSnapshot().size() + ":" + session.peekFrame().route();
                session.popFrame();
                return result;
            }));

            assertThat(first.get()).contains(":1:route.one");
            assertThat(second.get()).contains(":1:route.two");
            assertThat(first.get()).isNotEqualTo(second.get());
        }
    }

    @Test
    void rejectsStandaloneFinalizationWhenFramesRemainOpen() throws Exception {
        BifrostSessionRunner sessionRunner = new BifrostSessionRunner(4, TracePersistencePolicy.ALWAYS);
        java.util.concurrent.atomic.AtomicReference<BifrostSession> sessionRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> tracePathText = new java.util.concurrent.atomic.AtomicReference<>();

        assertThatThrownBy(() -> sessionRunner.callWithNewSession(session -> {
            sessionRef.set(session);
            tracePathText.set(session.getExecutionTrace().filePath());
            session.pushFrame(frame("frame-1", "route.one"));
            return "unreachable";
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot finalize standalone session");

        BifrostSession session = sessionRef.get();
        assertThat(session).isNotNull();
        assertThat(session.getExecutionTrace().completed()).isTrue();
        assertThat(session.getExecutionTrace().errored()).isTrue();

        var tracePath = java.nio.file.Path.of(tracePathText.get());
        try {
            assertThat(Files.exists(tracePath)).isTrue();
            List<TraceRecord> records = new ArrayList<>();
            new com.lokiscale.bifrost.runtime.trace.NdjsonExecutionTraceReader().read(tracePath, records::add);
            assertThat(records.getLast().recordType()).isEqualTo(TraceRecordType.TRACE_COMPLETED);
            assertThat(records.getLast().metadata())
                    .containsEntry("errored", true)
                    .containsEntry("remainingFrames", 1)
                    .containsEntry("entryPoint", "session-runner");
        }
        finally {
            Files.deleteIfExists(tracePath);
        }
    }

    @Test
    void isolatesJournalMutationAcrossConcurrentVirtualThreads() throws InterruptedException, ExecutionException {
        BifrostSessionRunner sessionRunner = new BifrostSessionRunner(4);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = executor.submit(() -> sessionRunner.callWithNewSession(session -> {
                ExecutionPlan plan = plan("plan-first");
                appendRecord(session, TraceRecordType.PLAN_CREATED, Instant.parse("2026-03-15T12:00:00Z"), Map.of("planId", plan.planId()), plan);
                appendRecord(session, TraceRecordType.TOOL_CALL_REQUESTED, Instant.parse("2026-03-15T12:00:01Z"), Map.of(), Map.of("route", "tool.one"));
                return session.getSessionId()
                        + ":"
                        + session.getJournalSnapshot().size()
                        + ":"
                        + session.getJournalSnapshot().get(0).type().name();
            }));
            Future<String> second = executor.submit(() -> sessionRunner.callWithNewSession(session -> {
                ExecutionPlan plan = plan("plan-second");
                appendRecord(session, TraceRecordType.PLAN_CREATED, Instant.parse("2026-03-15T12:00:02Z"), Map.of("planId", plan.planId()), plan);
                session.markTraceErrored();
                appendRecord(session, TraceRecordType.ERROR_RECORDED, Instant.parse("2026-03-15T12:00:03Z"), Map.of(), Map.of("message", "boom"));
                return session.getSessionId()
                        + ":"
                        + session.getJournalSnapshot().size()
                        + ":"
                        + session.getJournalSnapshot().get(0).type().name();
            }));

            assertThat(first.get()).contains(":2:PLAN_CREATED");
            assertThat(second.get()).contains(":2:PLAN_CREATED");
            assertThat(first.get()).isNotEqualTo(second.get());
        }
    }

    @Test
    void seedsAuthenticationIntoNewSessionWhenProvided() {
        BifrostSessionRunner sessionRunner = new BifrostSessionRunner(4);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "user",
                "pw",
                AuthorityUtils.createAuthorityList("ROLE_ALLOWED"));

        String authority = sessionRunner.callWithNewSession(authentication, session ->
                session.getAuthentication()
                        .orElseThrow()
                        .getAuthorities()
                        .iterator()
                        .next()
                        .getAuthority());

        assertThat(authority).isEqualTo("ROLE_ALLOWED");
    }

    @Test
    void usesConfiguredClockForLiveTraceTimestamps() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-03-15T12:34:56Z"), ZoneOffset.UTC);
        BifrostSessionRunner sessionRunner = new BifrostSessionRunner(4, TracePersistencePolicy.ALWAYS, fixedClock);

        Instant timestamp = sessionRunner.callWithNewSession(session -> {
            session.appendTraceRecord(TraceRecordType.MODEL_REQUEST_SENT, Map.of("segment", "test"), Map.of("objective", "runner"));
            List<TraceRecord> records = new ArrayList<>();
            session.readTraceRecords(records::add);
            return records.getLast().timestamp();
        });

        assertThat(timestamp).isEqualTo(Instant.parse("2026-03-15T12:34:56Z"));
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

    private static ExecutionPlan plan(String planId) {
        return new ExecutionPlan(
                planId,
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of());
    }
}
