package com.lokiscale.bifrost.internal.runtime.observation;

public interface ExecutionObservationHandleFactory
{
    ExecutionObservationHandle create(String sessionId);
}
