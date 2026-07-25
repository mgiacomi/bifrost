package com.lokiscale.bifrost.internal.runtime.observation;

import com.lokiscale.bifrost.internal.core.TraceFrameType;
import com.lokiscale.bifrost.internal.core.TraceOutcome;
import com.lokiscale.bifrost.internal.runtime.usage.SessionUsageSnapshot;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class ExecutionProjectionState
{
    final String sessionId;
    final LinkedHashMap<String, ActiveExecutionSnapshot.FramePathEntry> frames = new LinkedHashMap<>();
    String traceId;
    Instant startedAt;
    String entrySkill;
    String phase = "STARTING";
    String summary = "Execution started";
    SessionUsageSnapshot usage = SessionUsageSnapshot.empty();
    TraceOutcome outcome;

    ExecutionProjectionState(String sessionId)
    {
        this.sessionId = sessionId;
    }

    void openFrame(String frameId, TraceFrameType frameType, String route)
    {
        if (frameId != null && frameType != null && route != null)
        {
            frames.put(frameId, new ActiveExecutionSnapshot.FramePathEntry(frameId, frameType, route));
        }
    }

    void closeFrame(@Nullable String frameId)
    {
        if (frameId != null)
        {
            frames.remove(frameId);
        }
    }

    Map<String, ActiveExecutionSnapshot.FramePathEntry> frameView()
    {
        return Map.copyOf(frames);
    }
}
