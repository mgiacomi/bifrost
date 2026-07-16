package com.lokiscale.bifrost.internal.core;

import java.util.Map;
import java.util.Objects;

/**
 * Adds failure details that are safe to persist in Bifrost-owned traces.
 * Provider and application exception messages are deliberately excluded because
 * they can contain configured endpoints, response bodies, credentials, or inputs.
 */
public final class TraceFailureMetadata
{
    private TraceFailureMetadata()
    {
    }

    public static void addTo(Map<String, Object> metadata, Throwable failure, String safeMessage)
    {
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(failure, "failure must not be null");
        Objects.requireNonNull(safeMessage, "safeMessage must not be null");
        if (safeMessage.isBlank())
        {
            throw new IllegalArgumentException("safeMessage must not be blank");
        }
        metadata.put("exceptionType", failure.getClass().getName());
        metadata.put("message", safeMessage);
    }
}
