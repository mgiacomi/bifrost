package com.lokiscale.bifrost.internal.runtime.observation;

import java.util.Objects;

public final class DefaultExecutionObservationHandleFactory implements ExecutionObservationHandleFactory
{
    private final LiveActivityProjector projector;
    private final ActiveExecutionRegistry registry;
    private final ActivityReplayBuffer replayBuffer;
    private final LiveMonitoringAvailability availability;

    public DefaultExecutionObservationHandleFactory()
    {
        this(
                new LiveActivityProjector(),
                new InMemoryActiveExecutionRegistry(),
                new InMemoryActivityReplayBuffer(),
                new LiveMonitoringAvailability());
    }

    public DefaultExecutionObservationHandleFactory(
            LiveActivityProjector projector,
            ActiveExecutionRegistry registry,
            ActivityReplayBuffer replayBuffer,
            LiveMonitoringAvailability availability)
    {
        this.projector = Objects.requireNonNull(projector, "projector must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.replayBuffer = Objects.requireNonNull(replayBuffer, "replayBuffer must not be null");
        this.availability = Objects.requireNonNull(availability, "availability must not be null");
    }

    @Override
    public ExecutionObservationHandle create(String sessionId)
    {
        return new DefaultExecutionObservationHandle(
                sessionId, projector, registry, replayBuffer, availability);
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
}
