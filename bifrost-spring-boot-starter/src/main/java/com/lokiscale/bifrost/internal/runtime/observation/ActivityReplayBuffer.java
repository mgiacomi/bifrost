package com.lokiscale.bifrost.internal.runtime.observation;

public interface ActivityReplayBuffer
{
    ExecutionActivity append(ExecutionActivity activity);

    long currentCursor();

    ReplayResult replayAfter(long cursor, int limit);
}
