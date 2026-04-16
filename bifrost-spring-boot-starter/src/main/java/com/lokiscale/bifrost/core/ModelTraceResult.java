package com.lokiscale.bifrost.core;

import java.util.Objects;

public record ModelTraceResult<T>(T result, Object responsePayload)
{
    public ModelTraceResult
    {
        Objects.requireNonNull(result, "result must not be null");
    }

    public static <T> ModelTraceResult<T> of(T result, Object responsePayload)
    {
        return new ModelTraceResult<>(result, responsePayload);
    }
}
