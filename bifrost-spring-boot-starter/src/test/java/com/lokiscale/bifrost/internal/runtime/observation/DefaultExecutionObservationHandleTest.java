package com.lokiscale.bifrost.internal.runtime.observation;

import com.lokiscale.bifrost.internal.core.TraceOutcome;
import com.lokiscale.bifrost.internal.core.TraceRecord;
import com.lokiscale.bifrost.internal.core.TraceRecordType;
import com.lokiscale.bifrost.internal.runtime.usage.SessionUsageSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(OutputCaptureExtension.class)
class DefaultExecutionObservationHandleTest
{
    @Test
    void holdsCanonicalCompletionUntilCoreSuccessClose()
    {
        DefaultExecutionObservationHandleFactory factory = new DefaultExecutionObservationHandleFactory();
        ExecutionObservationHandle handle = factory.create("session");
        handle.recordAppended(record(TraceRecordType.TRACE_STARTED, 1, Map.of()));
        handle.recordAppended(record(
                TraceRecordType.TRACE_COMPLETED,
                2,
                Map.of("outcome", "SUCCEEDED", "sessionUsageSnapshot", SessionUsageSnapshot.empty())));

        assertThat(factory.replayBuffer().replayAfter(0, 10).activities())
                .extracting(ExecutionActivity::kind)
                .containsExactly(ExecutionActivityKind.TRACE_STARTED);
        assertThat(factory.registry().activeCount()).isEqualTo(1);

        handle.close(disposition(
                ObservationCompletionDisposition.Status.CORE_FINALIZATION_SUCCEEDED,
                TraceOutcome.SUCCEEDED));

        assertThat(factory.replayBuffer().replayAfter(0, 10).activities())
                .extracting(ExecutionActivity::kind)
                .containsExactly(
                        ExecutionActivityKind.TRACE_STARTED,
                        ExecutionActivityKind.TRACE_COMPLETED);
        assertThat(factory.registry().activeCount()).isZero();
    }

    @Test
    void discardsHeldCompletionAndPublishesObservationEndedOnCoreFailure()
    {
        DefaultExecutionObservationHandleFactory factory = new DefaultExecutionObservationHandleFactory();
        ExecutionObservationHandle handle = factory.create("session");
        handle.recordAppended(record(TraceRecordType.TRACE_STARTED, 1, Map.of()));
        handle.recordAppended(record(
                TraceRecordType.TRACE_COMPLETED,
                2,
                Map.of("outcome", "FAILED", "sessionUsageSnapshot", SessionUsageSnapshot.empty())));

        handle.close(disposition(
                ObservationCompletionDisposition.Status.CORE_FINALIZATION_FAILED,
                TraceOutcome.FAILED));

        List<ExecutionActivity> activities = factory.replayBuffer().replayAfter(0, 10).activities();
        assertThat(activities).extracting(ExecutionActivity::kind)
                .containsExactly(
                        ExecutionActivityKind.TRACE_STARTED,
                        ExecutionActivityKind.EXECUTION_OBSERVATION_ENDED);
        assertThat(activities.getLast().canonicalSequence()).isNull();
        assertThat(activities.getLast().details())
                .containsEntry("reason", "CORE_FINALIZATION_FAILED")
                .containsEntry("outcome", "FAILED");
        assertThat(factory.registry().activeCount()).isZero();
    }

    @Test
    void containsReplayFailureAndFailsClosedWithoutThrowing()
    {
        LiveMonitoringAvailability availability = new LiveMonitoringAvailability();
        ActivityReplayBuffer throwing = new ActivityReplayBuffer()
        {
            @Override
            public ExecutionActivity append(ExecutionActivity activity)
            {
                throw new IllegalStateException("SECRET");
            }

            @Override
            public long currentCursor()
            {
                return 0;
            }

            @Override
            public ReplayResult replayAfter(long cursor, int limit)
            {
                return new ReplayResult(ReplayResult.Status.EMPTY, 0, List.of());
            }
        };
        DefaultExecutionObservationHandle handle = new DefaultExecutionObservationHandle(
                "session",
                new LiveActivityProjector(),
                new InMemoryActiveExecutionRegistry(),
                throwing,
                availability);

        assertThatCode(() -> handle.recordAppended(record(TraceRecordType.TRACE_STARTED, 1, Map.of())))
                .doesNotThrowAnyException();
        assertThat(availability.isAvailable()).isFalse();
        assertThat(availability.firstFailure().orElseThrow().operation())
                .isEqualTo("REPLAY_PUBLICATION_FAILED");
    }

    @Test
    void closesExactlyOnceUnderConcurrentConflictingCalls() throws Exception
    {
        DefaultExecutionObservationHandleFactory factory = new DefaultExecutionObservationHandleFactory();
        ExecutionObservationHandle handle = factory.create("session");
        handle.recordAppended(record(TraceRecordType.TRACE_STARTED, 1, Map.of()));
        handle.recordAppended(record(
                TraceRecordType.TRACE_COMPLETED,
                2,
                Map.of("outcome", "SUCCEEDED", "sessionUsageSnapshot", SessionUsageSnapshot.empty())));
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor())
        {
            var success = executor.submit(() -> {
                start.await();
                handle.close(disposition(
                        ObservationCompletionDisposition.Status.CORE_FINALIZATION_SUCCEEDED,
                        TraceOutcome.SUCCEEDED));
                return null;
            });
            var failure = executor.submit(() -> {
                start.await();
                handle.close(disposition(
                        ObservationCompletionDisposition.Status.CORE_FINALIZATION_FAILED,
                        TraceOutcome.FAILED));
                return null;
            });
            start.countDown();
            success.get();
            failure.get();
        }

        List<ExecutionActivityKind> terminal = factory.replayBuffer().replayAfter(0, 10).activities().stream()
                .map(ExecutionActivity::kind)
                .filter(kind -> kind == ExecutionActivityKind.TRACE_COMPLETED
                        || kind == ExecutionActivityKind.EXECUTION_OBSERVATION_ENDED)
                .toList();
        assertThat(terminal).hasSize(1);
        assertThat(factory.registry().activeCount()).isZero();
    }

    @Test
    void containsProjectorAndRegistryFailuresAndFailsClosed()
    {
        LiveMonitoringAvailability projectorAvailability = new LiveMonitoringAvailability();
        DefaultExecutionObservationHandle projectorFailure = new DefaultExecutionObservationHandle(
                "session",
                new LiveActivityProjector()
                {
                    @Override
                    Projection project(ExecutionProjectionState state, TraceRecord record)
                    {
                        throw new IllegalArgumentException("SECRET-PROJECTOR");
                    }
                },
                new InMemoryActiveExecutionRegistry(),
                new InMemoryActivityReplayBuffer(),
                projectorAvailability);
        assertThatCode(() -> projectorFailure.recordAppended(
                record(TraceRecordType.TRACE_STARTED, 1, Map.of()))).doesNotThrowAnyException();
        assertThat(projectorAvailability.firstFailure().orElseThrow().operation())
                .isEqualTo("PROJECTION_FAILED");

        LiveMonitoringAvailability registryAvailability = new LiveMonitoringAvailability();
        ActiveExecutionRegistry throwingRegistry = new ActiveExecutionRegistry()
        {
            @Override
            public ActiveExecutionSnapshot replace(ActiveExecutionSnapshot snapshot)
            {
                throw new IllegalStateException("SECRET-REGISTRY");
            }

            @Override
            public Optional<ActiveExecutionSnapshot> find(String sessionId)
            {
                return Optional.empty();
            }

            @Override
            public boolean remove(String sessionId)
            {
                return false;
            }

            @Override
            public int activeCount()
            {
                return 0;
            }

            @Override
            public long highestOrdinal()
            {
                return 0;
            }

            @Override
            public List<ActiveExecutionSnapshot> newestFirst(long highWaterMark, int limit)
            {
                return List.of();
            }
        };
        DefaultExecutionObservationHandle registryFailure = new DefaultExecutionObservationHandle(
                "session",
                new LiveActivityProjector(),
                throwingRegistry,
                new InMemoryActivityReplayBuffer(),
                registryAvailability);
        assertThatCode(() -> registryFailure.recordAppended(
                record(TraceRecordType.TRACE_STARTED, 1, Map.of()))).doesNotThrowAnyException();
        assertThat(registryAvailability.firstFailure().orElseThrow().operation())
                .isEqualTo("REGISTRY_UPDATE_FAILED");
    }

    @Test
    void terminalPublicationFailureStillRemovesActiveEntry()
    {
        InMemoryActiveExecutionRegistry registry = new InMemoryActiveExecutionRegistry();
        LiveMonitoringAvailability availability = new LiveMonitoringAvailability();
        InMemoryActivityReplayBuffer delegate = new InMemoryActivityReplayBuffer();
        ActivityReplayBuffer failSecond = new ActivityReplayBuffer()
        {
            private int publications;

            @Override
            public ExecutionActivity append(ExecutionActivity activity)
            {
                if (++publications == 2)
                {
                    throw new IllegalStateException("SECRET-TERMINAL");
                }
                return delegate.append(activity);
            }

            @Override
            public long currentCursor()
            {
                return delegate.currentCursor();
            }

            @Override
            public ReplayResult replayAfter(long cursor, int limit)
            {
                return delegate.replayAfter(cursor, limit);
            }
        };
        DefaultExecutionObservationHandle handle = new DefaultExecutionObservationHandle(
                "session", new LiveActivityProjector(), registry, failSecond, availability);
        handle.recordAppended(record(TraceRecordType.TRACE_STARTED, 1, Map.of()));
        handle.recordAppended(record(
                TraceRecordType.TRACE_COMPLETED,
                2,
                Map.of("outcome", "SUCCEEDED", "sessionUsageSnapshot", SessionUsageSnapshot.empty())));

        assertThatCode(() -> handle.close(disposition(
                ObservationCompletionDisposition.Status.CORE_FINALIZATION_SUCCEEDED,
                TraceOutcome.SUCCEEDED))).doesNotThrowAnyException();
        assertThat(registry.activeCount()).isZero();
        assertThat(availability.firstFailure().orElseThrow().operation())
                .isEqualTo("TERMINAL_PUBLICATION_FAILED");
    }

    @Test
    void missingHeldCompletionOnSuccessFailsClosedAndRemovesEntry()
    {
        DefaultExecutionObservationHandleFactory factory = new DefaultExecutionObservationHandleFactory();
        ExecutionObservationHandle handle = factory.create("session");
        handle.recordAppended(record(TraceRecordType.TRACE_STARTED, 1, Map.of()));

        handle.close(disposition(
                ObservationCompletionDisposition.Status.CORE_FINALIZATION_SUCCEEDED,
                TraceOutcome.SUCCEEDED));

        assertThat(factory.registry().activeCount()).isZero();
        assertThat(factory.availability().firstFailure().orElseThrow().operation())
                .isEqualTo("TERMINAL_PUBLICATION_FAILED");
    }

    @Test
    void logsOneSanitizedDiagnosticOnFirstFailure(CapturedOutput output)
    {
        LiveMonitoringAvailability availability = new LiveMonitoringAvailability();
        DefaultExecutionObservationHandle handle = new DefaultExecutionObservationHandle(
                "session-safe-id",
                new LiveActivityProjector()
                {
                    @Override
                    Projection project(ExecutionProjectionState state, TraceRecord record)
                    {
                        throw new IllegalArgumentException("SECRET-MESSAGE-CONTENT");
                    }
                },
                new InMemoryActiveExecutionRegistry(),
                new InMemoryActivityReplayBuffer(),
                availability);

        handle.recordAppended(record(TraceRecordType.TRACE_STARTED, 1, Map.of()));
        handle.recordAppended(record(TraceRecordType.TRACE_CAPTURE_POLICY_RECORDED, 2, Map.of()));

        assertThat(output.getOut())
                .contains("operation=PROJECTION_FAILED")
                .contains("sessionId=session-safe-id")
                .contains("traceId=trace")
                .contains("exceptionClass=java.lang.IllegalArgumentException")
                .doesNotContain("SECRET-MESSAGE-CONTENT");
        assertThat(output.getOut().split("Live monitoring unavailable", -1)).hasSize(2);
    }

    private TraceRecord record(TraceRecordType type, long sequence, Map<String, Object> metadata)
    {
        return new TraceRecord(
                "trace", "session", sequence, Instant.parse("2026-07-24T12:00:00Z"), type,
                null, null, null, null, "thread", metadata, null);
    }

    private ObservationCompletionDisposition disposition(
            ObservationCompletionDisposition.Status status,
            TraceOutcome outcome)
    {
        return new ObservationCompletionDisposition(
                status, outcome, Instant.parse("2026-07-24T12:01:00Z"));
    }
}
