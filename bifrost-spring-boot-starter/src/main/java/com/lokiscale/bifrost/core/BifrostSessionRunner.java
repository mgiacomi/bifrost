package com.lokiscale.bifrost.core;

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
        if (maxDepth <= 0)
        {
            throw new IllegalArgumentException("maxDepth must be greater than zero");
        }

        this.maxDepth = maxDepth;
        this.tracePersistencePolicy = tracePersistencePolicy == null ? TracePersistencePolicy.ONERROR : tracePersistencePolicy;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
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
                clock);

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
                clock);

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
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("entryPoint", "session-runner");
        metadata.put("remainingFrames", session.getFramesSnapshot().size());

        if (!session.getFramesSnapshot().isEmpty())
        {
            session.markTraceErrored();
            metadata.put("status", "failed");
            metadata.put("message", "Standalone session completed with open execution frames");
            session.finalizeTrace(Map.copyOf(metadata));

            throw new IllegalStateException(
                    "Cannot finalize standalone session '%s' with %d open execution frame(s)."
                            .formatted(session.getSessionId(), session.getFramesSnapshot().size()));
        }

        metadata.put("status", failure == null ? "completed" : "failed");
        if (failure != null)
        {
            metadata.put("exceptionType", failure.getClass().getName());
            if (failure.getMessage() != null && !failure.getMessage().isBlank())
            {
                metadata.put("message", failure.getMessage());
            }
        }

        session.finalizeTrace(Map.copyOf(metadata));
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
