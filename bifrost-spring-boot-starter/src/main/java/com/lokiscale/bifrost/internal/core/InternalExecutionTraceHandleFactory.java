package com.lokiscale.bifrost.internal.core;

import com.lokiscale.bifrost.internal.runtime.observation.ExecutionObservationHandle;

import java.time.Clock;

@FunctionalInterface
interface InternalExecutionTraceHandleFactory
{
    ExecutionTraceHandle create(
            String sessionId,
            TracePersistencePolicy persistencePolicy,
            Clock clock,
            ExecutionObservationHandle observationHandle);
}
