package com.lokiscale.loomspan.internal.runtime.trace;

import com.lokiscale.loomspan.internal.core.ExecutionTraceReader;

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
