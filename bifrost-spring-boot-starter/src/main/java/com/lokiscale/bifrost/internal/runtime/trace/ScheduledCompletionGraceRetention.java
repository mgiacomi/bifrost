package com.lokiscale.bifrost.internal.runtime.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ScheduledCompletionGraceRetention implements CompletionGraceRetention
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledCompletionGraceRetention.class);

    private final Duration grace;
    private final ScheduledExecutorService executor;
    private final Set<ScheduledFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ScheduledCompletionGraceRetention(Duration grace)
    {
        this(grace, Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().daemon().name("bifrost-trace-grace").unstarted(runnable)));
    }

    ScheduledCompletionGraceRetention(Duration grace, ScheduledExecutorService executor)
    {
        this.grace = Objects.requireNonNull(grace, "grace must not be null");
        if (grace.isNegative())
        {
            throw new IllegalArgumentException("grace must not be negative");
        }
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    @Override
    public Optional<RetainedArtifact> retainOrDelete(
            Path artifactPath,
            Instant finalizedAt,
            String traceId,
            String sessionId) throws IOException
    {
        Objects.requireNonNull(artifactPath, "artifactPath must not be null");
        Objects.requireNonNull(finalizedAt, "finalizedAt must not be null");
        Instant expiresAt;
        try
        {
            expiresAt = finalizedAt.plus(grace);
        }
        catch (RuntimeException ex)
        {
            throw new IllegalArgumentException("finalizedAt plus grace must be representable", ex);
        }
        if (grace.isZero())
        {
            Files.deleteIfExists(artifactPath);
            return Optional.empty();
        }

        Path exactPath = artifactPath.toAbsolutePath().normalize();
        if (closed.get())
        {
            Files.deleteIfExists(exactPath);
            return Optional.empty();
        }
        long sizeBytes = Files.size(exactPath);
        try
        {
            AtomicReference<ScheduledFuture<?>> reference = new AtomicReference<>();
            ScheduledFuture<?> future = executor.schedule(() ->
            {
                try
                {
                    Files.deleteIfExists(exactPath);
                }
                catch (IOException ex)
                {
                    LOGGER.warn(
                            "Delayed trace deletion failed traceId={} sessionId={} exceptionClass={}",
                            traceId, sessionId, ex.getClass().getName());
                }
                finally
                {
                    ScheduledFuture<?> completedFuture = reference.get();
                    if (completedFuture != null)
                    {
                        pending.remove(completedFuture);
                    }
                }
            }, grace.toNanos(), TimeUnit.NANOSECONDS);
            reference.set(future);
            pending.add(future);
            if (future.isDone())
            {
                pending.remove(future);
            }
            return Optional.of(new RetainedArtifact(expiresAt, sizeBytes));
        }
        catch (RejectedExecutionException ex)
        {
            Files.deleteIfExists(exactPath);
            return Optional.empty();
        }
    }

    @Override
    public void close()
    {
        if (!closed.compareAndSet(false, true))
        {
            return;
        }
        pending.forEach(future -> future.cancel(false));
        pending.clear();
        executor.shutdownNow();
    }
}
