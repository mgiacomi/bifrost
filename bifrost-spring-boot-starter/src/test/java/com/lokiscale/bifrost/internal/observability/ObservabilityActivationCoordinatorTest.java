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
import com.lokiscale.bifrost.internal.observability.web.ObservabilityActivityDelivery;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ObservabilityActivationCoordinatorTest
{
    @Test
    void closeDisablesActivationAndClosesOwnedRuntimeResources()
    {
        CompletionGraceRetention retention = mock(CompletionGraceRetention.class);
        FinalizedTraceCatalog traces = mock(FinalizedTraceCatalog.class);
        ObservabilityActivityDelivery delivery = mock(ObservabilityActivityDelivery.class);
        ObservabilityRuntime runtime = new ObservabilityRuntime(
                UUID.randomUUID(),
                Clock.systemUTC(),
                mock(ExecutionObservationHandleFactory.class),
                delivery,
                retention,
                mock(ActiveExecutionRegistry.class),
                mock(ActivityReplayBuffer.class),
                new LiveMonitoringAvailability(),
                mock(RegisteredSkillCatalog.class),
                traces,
                new BifrostProperties.Observability(),
                new BifrostProperties.Session.Quotas(),
                TracePersistencePolicy.ONERROR);
        ObservabilityActivationCoordinator coordinator = new ObservabilityActivationCoordinator();
        coordinator.enable(runtime);

        coordinator.close();
        coordinator.close();

        assertThat(coordinator.state()).isEqualTo(ObservabilityActivationCoordinator.State.DISABLED);
        assertThat(coordinator.runtime()).isEmpty();
        verify(delivery).close();
        verify(retention).close();
        verify(traces).close();
    }
}
