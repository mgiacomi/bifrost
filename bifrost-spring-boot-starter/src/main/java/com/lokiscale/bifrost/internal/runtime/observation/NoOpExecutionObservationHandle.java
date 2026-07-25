package com.lokiscale.bifrost.internal.runtime.observation;

import com.lokiscale.bifrost.internal.core.TraceRecord;

public enum NoOpExecutionObservationHandle implements ExecutionObservationHandle
{
    INSTANCE;

    @Override
    public void recordAppended(TraceRecord record)
    {
        // Observation is deliberately disabled.
    }

    @Override
    public void close(ObservationCompletionDisposition disposition)
    {
        // Observation is deliberately disabled.
    }
}
