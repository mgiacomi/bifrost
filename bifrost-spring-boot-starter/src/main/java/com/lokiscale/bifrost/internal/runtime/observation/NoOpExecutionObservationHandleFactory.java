package com.lokiscale.bifrost.internal.runtime.observation;

public enum NoOpExecutionObservationHandleFactory implements ExecutionObservationHandleFactory
{
    INSTANCE;

    @Override
    public ExecutionObservationHandle create(String sessionId)
    {
        return NoOpExecutionObservationHandle.INSTANCE;
    }
}
