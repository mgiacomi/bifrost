package com.lokiscale.bifrost.internal.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ModelTraceContext(
        ModelExecutionIdentity identity,
        String skillName,
        String segment)
{
    public ModelTraceContext
    {
        identity = Objects.requireNonNull(identity, "identity must not be null");
        skillName = requireNonBlank(skillName, "skillName");
        segment = requireNonBlank(segment, "segment");
    }

    public Map<String, Object> metadata()
    {
        Map<String, Object> metadata = new LinkedHashMap<>(identity.metadata());
        metadata.put("skillName", skillName);
        metadata.put("segment", segment);
        return Map.copyOf(metadata);
    }

    private static String requireNonBlank(String value, String fieldName)
    {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank())
        {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
