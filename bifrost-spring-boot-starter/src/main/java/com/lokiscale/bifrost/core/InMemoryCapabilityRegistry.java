package com.lokiscale.bifrost.core;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryCapabilityRegistry implements CapabilityRegistry
{
    private final ConcurrentMap<String, CapabilityMetadata> capabilitiesByName = new ConcurrentHashMap<>();

    @Override
    public void register(String capabilityName, CapabilityMetadata metadata)
    {
        String normalizedName = requireNonBlank(capabilityName, "capabilityName");
        CapabilityMetadata nonNullMetadata = Objects.requireNonNull(metadata, "metadata must not be null");

        if (!normalizedName.equals(nonNullMetadata.name()))
        {
            throw new IllegalArgumentException("capabilityName must match metadata.name");
        }

        CapabilityMetadata existing = capabilitiesByName.putIfAbsent(normalizedName, nonNullMetadata);
        if (existing != null)
        {
            throw new CapabilityCollisionException("Capability with name '" + normalizedName + "' is already registered.");
        }
    }

    @Override
    public CapabilityMetadata getCapability(String name)
    {
        if (name == null || name.isBlank())
        {
            return null;
        }
        return capabilitiesByName.get(name);
    }

    @Override
    public List<CapabilityMetadata> getAllCapabilities()
    {
        return List.copyOf(capabilitiesByName.values());
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
