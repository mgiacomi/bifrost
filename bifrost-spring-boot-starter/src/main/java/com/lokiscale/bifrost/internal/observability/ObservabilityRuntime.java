package com.lokiscale.bifrost.internal.observability;

import com.lokiscale.bifrost.autoconfigure.BifrostProperties;
import com.lokiscale.bifrost.internal.core.TracePersistencePolicy;
import com.lokiscale.bifrost.internal.observability.web.ObservabilityActivityDelivery;
import com.lokiscale.bifrost.internal.runtime.observation.ActiveExecutionRegistry;
import com.lokiscale.bifrost.internal.runtime.observation.ActivityReplayBuffer;
import com.lokiscale.bifrost.internal.runtime.observation.ExecutionObservationHandleFactory;
import com.lokiscale.bifrost.internal.runtime.observation.LiveMonitoringAvailability;
import com.lokiscale.bifrost.internal.runtime.observation.catalog.FinalizedTraceCatalog;
import com.lokiscale.bifrost.internal.runtime.observation.catalog.RegisteredSkillCatalog;
import com.lokiscale.bifrost.internal.runtime.trace.CompletionGraceRetention;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ObservabilityRuntime implements AutoCloseable
{
    private final UUID instanceId;
    private final Clock clock;
    private final ExecutionObservationHandleFactory observationFactory;
    private final ObservabilityActivityDelivery activityDelivery;
    private final CompletionGraceRetention completionRetention;
    private final ActiveExecutionRegistry activeExecutions;
    private final ActivityReplayBuffer replayBuffer;
    private final LiveMonitoringAvailability liveMonitoring;
    private final RegisteredSkillCatalog skills;
    private final FinalizedTraceCatalog traces;
    private final BifrostProperties.Observability configuration;
    private final BifrostProperties.Session.Quotas quotas;
    private final TracePersistencePolicy tracePersistencePolicy;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ObservabilityRuntime(
            UUID instanceId,
            Clock clock,
            ExecutionObservationHandleFactory observationFactory,
            ObservabilityActivityDelivery activityDelivery,
            CompletionGraceRetention completionRetention,
            ActiveExecutionRegistry activeExecutions,
            ActivityReplayBuffer replayBuffer,
            LiveMonitoringAvailability liveMonitoring,
            RegisteredSkillCatalog skills,
            FinalizedTraceCatalog traces,
            BifrostProperties.Observability configuration,
            BifrostProperties.Session.Quotas quotas,
            TracePersistencePolicy tracePersistencePolicy)
    {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.observationFactory = Objects.requireNonNull(observationFactory, "observationFactory must not be null");
        this.activityDelivery = Objects.requireNonNull(activityDelivery, "activityDelivery must not be null");
        this.completionRetention = Objects.requireNonNull(
                completionRetention, "completionRetention must not be null");
        this.activeExecutions = Objects.requireNonNull(activeExecutions, "activeExecutions must not be null");
        this.replayBuffer = Objects.requireNonNull(replayBuffer, "replayBuffer must not be null");
        this.liveMonitoring = Objects.requireNonNull(liveMonitoring, "liveMonitoring must not be null");
        this.skills = Objects.requireNonNull(skills, "skills must not be null");
        this.traces = Objects.requireNonNull(traces, "traces must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.quotas = Objects.requireNonNull(quotas, "quotas must not be null");
        this.tracePersistencePolicy = Objects.requireNonNull(
                tracePersistencePolicy, "tracePersistencePolicy must not be null");
    }

    public UUID instanceId() { return instanceId; }
    public Clock clock() { return clock; }
    public ExecutionObservationHandleFactory observationFactory() { return observationFactory; }
    public ObservabilityActivityDelivery activityDelivery() { return activityDelivery; }
    public CompletionGraceRetention completionRetention() { return completionRetention; }
    public ActiveExecutionRegistry activeExecutions() { return activeExecutions; }
    public ActivityReplayBuffer replayBuffer() { return replayBuffer; }
    public LiveMonitoringAvailability liveMonitoring() { return liveMonitoring; }
    public RegisteredSkillCatalog skills() { return skills; }
    public FinalizedTraceCatalog traces() { return traces; }
    public BifrostProperties.Observability configuration() { return configuration; }
    public BifrostProperties.Session.Quotas quotas() { return quotas; }
    public TracePersistencePolicy tracePersistencePolicy() { return tracePersistencePolicy; }

    @Override
    public void close()
    {
        if (!closed.compareAndSet(false, true))
        {
            return;
        }
        Throwable failure = close(null, activityDelivery);
        failure = close(failure, completionRetention);
        failure = close(failure, traces);
        if (failure instanceof RuntimeException runtime)
        {
            throw runtime;
        }
        if (failure instanceof Error error)
        {
            throw error;
        }
    }

    private static Throwable close(Throwable first, AutoCloseable resource)
    {
        try
        {
            resource.close();
        }
        catch (Throwable failure)
        {
            if (first == null)
            {
                return failure;
            }
            first.addSuppressed(failure);
        }
        return first;
    }
}
