package com.lokiscale.bifrost.core;

import java.util.List;

public interface CapabilityRegistry
{
    void register(String capabilityName, CapabilityMetadata metadata);

    CapabilityMetadata getCapability(String name);

    List<CapabilityMetadata> getAllCapabilities();
}
