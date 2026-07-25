package com.lokiscale.bifrost.internal.runtime.observation;

import com.lokiscale.bifrost.internal.core.TraceRecord;

/**
 * Internal, optional observation boundary for one authoritative session.
 * Implementations must contain their own failures.
 */
public interface ExecutionObservationHandle
{
    void recordAppended(TraceRecord record);

    void close(ObservationCompletionDisposition disposition);
}
