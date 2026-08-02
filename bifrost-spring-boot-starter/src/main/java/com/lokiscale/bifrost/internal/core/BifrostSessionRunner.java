package com.lokiscale.bifrost.internal.core;

import com.lokiscale.bifrost.internal.runtime.observation.ExecutionObservationHandleFactory;
import com.lokiscale.bifrost.internal.runtime.observation.NoOpExecutionObservationHandleFactory;
import com.lokiscale.bifrost.internal.runtime.trace.CompletionGraceRetention;
import com.lokiscale.bifrost.internal.runtime.trace.ConfiguredLimitsSnapshot;
import com.lokiscale.bifrost.autoconfigure.BifrostProperties;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class BifrostSessionRunner
{
    private final int maxDepth;
    private final TracePersistencePolicy tracePersistencePolicy;
    private final Clock clock;
    private final ExecutionObservationHandleFactory observationHandleFactory;
    private final InternalExecutionTraceHandleFactory traceHandleFactory;

    public BifrostSessionRunner(int maxDepth)
    {
        this(maxDepth, TracePersistencePolicy.ONERROR, Clock.systemUTC());
    }

    public BifrostSessionRunner(int maxDepth, TracePersistencePolicy tracePersistencePolicy)
    {
        this(maxDepth, tracePersistencePolicy, Clock.systemUTC());
    }

    public BifrostSessionRunner(int maxDepth, TracePersistencePolicy tracePersistencePolicy, Clock clock)
    {
        this(maxDepth, tracePersistencePolicy, clock, NoOpExecutionObservationHandleFactory.INSTANCE);
    }

    public BifrostSessionRunner(
            int maxDepth,
            TracePersistencePolicy tracePersistencePolicy,
            Clock clock,
            ExecutionObservationHandleFactory observationHandleFactory)
    {
        this(maxDepth, tracePersistencePolicy, clock, observationHandleFactory,
                com.lokiscale.bifrost.internal.runtime.trace.DefaultExecutionTraceHandle::new);
    }

    public BifrostSessionRunner(
            int maxDepth,
            TracePersistencePolicy tracePersistencePolicy,
            Clock clock,
            ExecutionObservationHandleFactory observationHandleFactory,
            CompletionGraceRetention completionGraceRetention)
    {
        this(maxDepth, tracePersistencePolicy, clock, observationHandleFactory,
                (sessionId, policy, handleClock, observationHandle) ->
                        new com.lokiscale.bifrost.internal.runtime.trace.DefaultExecutionTraceHandle(
                                sessionId,
                                policy,
                                handleClock,
                                observationHandle,
                                Objects.requireNonNull(
                                        completionGraceRetention,
                                        "completionGraceRetention must not be null")));
    }

    public BifrostSessionRunner(
            int maxDepth,
            TracePersistencePolicy tracePersistencePolicy,
            Clock clock,
            ExecutionObservationHandleFactory observationHandleFactory,
            CompletionGraceRetention completionGraceRetention,
            BifrostProperties.Session.Quotas quotas)
    {
        this(maxDepth, tracePersistencePolicy, clock, observationHandleFactory,
                (sessionId, policy, handleClock, observationHandle) ->
                        new com.lokiscale.bifrost.internal.runtime.trace.DefaultExecutionTraceHandle(
                                sessionId,
                                policy,
                                handleClock,
                                observationHandle,
                                Objects.requireNonNull(
                                        completionGraceRetention,
                                        "completionGraceRetention must not be null"),
                                ConfiguredLimitsSnapshot.from(quotas)));
    }

    BifrostSessionRunner(
            int maxDepth,
            TracePersistencePolicy tracePersistencePolicy,
            Clock clock,
            ExecutionObservationHandleFactory observationHandleFactory,
            InternalExecutionTraceHandleFactory traceHandleFactory)
    {
        if (maxDepth <= 0)
        {
            throw new IllegalArgumentException("maxDepth must be greater than zero");
        }

        this.maxDepth = maxDepth;
        this.tracePersistencePolicy = tracePersistencePolicy == null ? TracePersistencePolicy.ONERROR : tracePersistencePolicy;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.observationHandleFactory = Objects.requireNonNull(
                observationHandleFactory, "observationHandleFactory must not be null");
        this.traceHandleFactory = Objects.requireNonNull(traceHandleFactory, "traceHandleFactory must not be null");
    }

    public void runWithNewSession(Consumer<BifrostSession> action)
    {
        runWithNewSession(null, action);
    }

    public void runWithNewSession(@Nullable Authentication authentication, Consumer<BifrostSession> action)
    {
        Objects.requireNonNull(action, "action must not be null");
        BifrostSession session = new BifrostSession(
                UUID.randomUUID().toString(),
                maxDepth,
                authentication,
                tracePersistencePolicy,
                clock,
                observationHandleFactory,
                traceHandleFactory);

        BifrostSessionHolder.runWithSession(session, () ->
        {
            Throwable failure = null;
            try
            {
                action.accept(session);
            }
            catch (RuntimeException | Error ex)
            {
                failure = ex;
                session.markTraceErrored();
                throw ex;
            }
            finally
            {
                completeSession(session, failure);
            }
        });
    }

    public <T> T callWithNewSession(Function<BifrostSession, T> action)
    {
        return callWithNewSession(null, action);
    }

    public <T> T callWithNewSession(@Nullable Authentication authentication, Function<BifrostSession, T> action)
    {
        Objects.requireNonNull(action, "action must not be null");
        BifrostSession session = new BifrostSession(
                UUID.randomUUID().toString(),
                maxDepth,
                authentication,
                tracePersistencePolicy,
                clock,
                observationHandleFactory,
                traceHandleFactory);

        return BifrostSessionHolder.callWithSession(session, () ->
        {
            Throwable failure = null;
            try
            {
                return action.apply(session);
            }
            catch (RuntimeException | Error ex)
            {
                failure = ex;
                session.markTraceErrored();
                throw ex;
            }
            finally
            {
                completeSession(session, failure);
            }
        });
    }

    private void finalizeSessionTrace(BifrostSession session, @Nullable Throwable failure)
    {
        if (session.getExecutionTrace().completed())
        {
            return;
        }
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("entryPoint", "session-runner");
        metadata.put("remainingFrames", session.getFramesSnapshot().size());

        if (!session.getFramesSnapshot().isEmpty())
        {
            IllegalStateException openFrameFailure = new IllegalStateException(
                    "Cannot finalize standalone session '%s' with %d open execution frame(s)."
                            .formatted(session.getSessionId(), session.getFramesSnapshot().size()));
            String failureId = UUID.randomUUID().toString();
            session.markTraceErrored();
            session.appendTraceRecord(
                    TraceRecordType.ERROR_RECORDED,
                    Map.of("failureId", failureId),
                    Map.of(
                            "exceptionType", openFrameFailure.getClass().getName(),
                            "message", "Standalone session completed with open execution frames"));
            session.finalizeTrace(new TraceCompletion(
                    TraceOutcome.FAILED,
                    session.getSessionUsage().orElse(
                            com.lokiscale.bifrost.internal.runtime.usage.SessionUsageSnapshot.empty()),
                    failureId,
                    Map.copyOf(metadata)));
            throw openFrameFailure;
        }

        String terminalFailureId = null;
        if (failure != null)
        {
            terminalFailureId = UUID.randomUUID().toString();
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            TraceFailureMetadata.addTo(payload, failure, "Session execution failed");
            session.appendTraceRecord(
                    TraceRecordType.ERROR_RECORDED,
                    Map.of("failureId", terminalFailureId),
                    Map.copyOf(payload));
        }

        session.finalizeTrace(new TraceCompletion(
                failure == null
                        ? TraceOutcome.SUCCEEDED
                        : (Thread.currentThread().isInterrupted() ? TraceOutcome.ABORTED : TraceOutcome.FAILED),
                session.getSessionUsage().orElse(
                        com.lokiscale.bifrost.internal.runtime.usage.SessionUsageSnapshot.empty()),
                terminalFailureId,
                Map.copyOf(metadata)));
    }

    private void completeSession(BifrostSession session, @Nullable Throwable failure)
    {
        RuntimeException cleanupFailure = null;

        try
        {
            finalizeSessionTrace(session, failure);
        }
        catch (RuntimeException ex)
        {
            cleanupFailure = ex;
        }

        if (cleanupFailure != null)
        {
            if (failure != null)
            {
                failure.addSuppressed(cleanupFailure);
            }
            else
            {
                throw cleanupFailure;
            }
        }
    }
}
