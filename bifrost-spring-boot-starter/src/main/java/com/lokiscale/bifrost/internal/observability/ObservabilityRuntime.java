package com.lokiscale.bifrost.internal.observability;

import com.lokiscale.bifrost.autoconfigure.BifrostProperties;
import com.lokiscale.bifrost.internal.core.TracePersistencePolicy;
import com.lokiscale.bifrost.internal.runtime.observation.ActiveExecutionRegistry;
import com.lokiscale.bifrost.internal.runtime.observation.ActivityReplayBuffer;
import com.lokiscale.bifrost.internal.runtime.observation.ExecutionObservationHandleFactory;
import com.lokiscale.bifrost.internal.runtime.observation.LiveMonitoringAvailability;
import com.lokiscale.bifrost.internal.runtime.observation.catalog.FinalizedTraceCatalog;
import com.lokiscale.bifrost.internal.runtime.observation.catalog.RegisteredSkillCatalog;
import com.lokiscale.bifrost.internal.runtime.trace.CompletionGraceRetention;

import java.time.Clock;
import java.util.UUID;

public record ObservabilityRuntime(
        UUID instanceId,
        Clock clock,
        ExecutionObservationHandleFactory observationFactory,
        CompletionGraceRetention completionRetention,
        ActiveExecutionRegistry activeExecutions,
        ActivityReplayBuffer replayBuffer,
        LiveMonitoringAvailability liveMonitoring,
        RegisteredSkillCatalog skills,
        FinalizedTraceCatalog traces,
        BifrostProperties.Observability configuration,
        BifrostProperties.Session.Quotas quotas,
        TracePersistencePolicy tracePersistencePolicy) implements AutoCloseable
{
    @Override
    public void close()
    {
        completionRetention.close();
        traces.close();
    }
}
