package com.lokiscale.bifrost.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ModelTraceContext(
        String provider,
        String providerModel,
        String skillName,
        String segment)
{
    public ModelTraceContext
    {
        provider = requireNonBlank(provider, "provider");
        providerModel = requireNonBlank(providerModel, "providerModel");
        skillName = requireNonBlank(skillName, "skillName");
        segment = requireNonBlank(segment, "segment");
    }

    public Map<String, Object> metadata()
    {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", provider);
        metadata.put("providerModel", providerModel);
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
