package com.lokiscale.bifrost.internal.core;

public interface AdvisorTraceRecorder
{
    void record(AdvisorTraceFact fact);

    static AdvisorTraceRecorder noOp()
    {
        return new AdvisorTraceRecorder()
        {
            @Override
            public void record(AdvisorTraceFact fact)
            {
            }
        };
    }
}
