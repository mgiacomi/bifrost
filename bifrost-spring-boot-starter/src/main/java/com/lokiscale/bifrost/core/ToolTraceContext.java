package com.lokiscale.bifrost.core;

import org.springframework.lang.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ToolTraceContext(
        String capabilityName,
        @Nullable String linkedTaskId,
        boolean unplanned) {

    public ToolTraceContext {
        Objects.requireNonNull(capabilityName, "capabilityName must not be null");
        if (capabilityName.isBlank()) {
            throw new IllegalArgumentException("capabilityName must not be blank");
        }
    }

    public Map<String, Object> metadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("capabilityName", capabilityName);
        if (linkedTaskId != null && !linkedTaskId.isBlank()) {
            metadata.put("linkedTaskId", linkedTaskId);
        }
        if (unplanned) {
            metadata.put("unplanned", true);
        }
        return Map.copyOf(metadata);
    }
}
