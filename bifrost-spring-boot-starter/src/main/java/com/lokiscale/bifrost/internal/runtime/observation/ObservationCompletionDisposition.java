package com.lokiscale.bifrost.internal.runtime.observation;

import com.lokiscale.bifrost.internal.core.TraceOutcome;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Objects;

public record ObservationCompletionDisposition(
        Status status,
        @Nullable TraceOutcome outcome,
        Instant closedAt)
{
    public ObservationCompletionDisposition
    {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(closedAt, "closedAt must not be null");
    }

    public enum Status
    {
        CORE_FINALIZATION_SUCCEEDED,
        CORE_FINALIZATION_FAILED
    }
}
