package com.lokiscale.bifrost.internal.runtime.trace;

import com.lokiscale.bifrost.internal.core.ExecutionTraceReader;

public final class ExecutionTraceReaders
{
    private ExecutionTraceReaders()
    {
    }

    public static ExecutionTraceReader ndjson()
    {
        return new NdjsonExecutionTraceReader();
    }
}
