package com.lokiscale.bifrost.internal.runtime.observation;

import com.lokiscale.bifrost.internal.core.FinalizedTraceArtifact;
import com.lokiscale.bifrost.internal.runtime.observation.catalog.FinalizedTraceCatalog;
import com.lokiscale.bifrost.internal.runtime.observation.catalog.FinalizedTraceCatalogEntry;
import com.lokiscale.bifrost.internal.runtime.observation.catalog.TraceCatalogSlice;

import java.util.Optional;
import java.util.Objects;

public final class DefaultExecutionObservationHandleFactory implements ExecutionObservationHandleFactory
{
    private final LiveActivityProjector projector;
    private final ActiveExecutionRegistry registry;
    private final ActivityReplayBuffer replayBuffer;
    private final LiveMonitoringAvailability availability;
    private final FinalizedTraceCatalog traceCatalog;

    public DefaultExecutionObservationHandleFactory()
    {
        this(
                new LiveActivityProjector(),
                new InMemoryActiveExecutionRegistry(),
                new InMemoryActivityReplayBuffer(),
                new LiveMonitoringAvailability(),
                unavailableCatalog());
    }

    public DefaultExecutionObservationHandleFactory(
            LiveActivityProjector projector,
            ActiveExecutionRegistry registry,
            ActivityReplayBuffer replayBuffer,
            LiveMonitoringAvailability availability)
    {
        this(projector, registry, replayBuffer, availability, unavailableCatalog());
    }

    public DefaultExecutionObservationHandleFactory(
            LiveActivityProjector projector,
            ActiveExecutionRegistry registry,
            ActivityReplayBuffer replayBuffer,
            LiveMonitoringAvailability availability,
            FinalizedTraceCatalog traceCatalog)
    {
        this.projector = Objects.requireNonNull(projector, "projector must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.replayBuffer = Objects.requireNonNull(replayBuffer, "replayBuffer must not be null");
        this.availability = Objects.requireNonNull(availability, "availability must not be null");
        this.traceCatalog = Objects.requireNonNull(traceCatalog, "traceCatalog must not be null");
    }

    @Override
    public ExecutionObservationHandle create(String sessionId)
    {
        return new DefaultExecutionObservationHandle(
                sessionId, projector, registry, replayBuffer, availability, traceCatalog);
    }

    public ActiveExecutionRegistry registry()
    {
        return registry;
    }

    public ActivityReplayBuffer replayBuffer()
    {
        return replayBuffer;
    }

    public LiveMonitoringAvailability availability()
    {
        return availability;
    }

    public FinalizedTraceCatalog traceCatalog()
    {
        return traceCatalog;
    }

    private static FinalizedTraceCatalog unavailableCatalog()
    {
        return new FinalizedTraceCatalog()
        {
            @Override
            public FinalizedTraceCatalogEntry publish(FinalizedTraceArtifact artifact)
            {
                throw new IllegalStateException("No finalized trace catalog is configured");
            }

            @Override
            public Optional<FinalizedTraceCatalogEntry> find(String traceId)
            {
                return Optional.empty();
            }

            @Override
            public TraceCatalogSlice list(long highWaterOrdinal, long beforeOrdinal, int limit)
            {
                return new TraceCatalogSlice(0, java.util.List.of());
            }

            @Override
            public int catalogedTraceCount()
            {
                return 0;
            }

            @Override
            public void close()
            {
            }
        };
    }
}
