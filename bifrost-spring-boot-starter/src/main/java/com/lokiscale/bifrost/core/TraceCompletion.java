package com.lokiscale.bifrost.core;

import java.util.LinkedHashMap;
import java.util.Map;

public record TraceCompletion(Map<String, Object> metadata) {

    public TraceCompletion {
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public static TraceCompletion of(Map<String, Object> metadata) {
        return new TraceCompletion(metadata);
    }
}
