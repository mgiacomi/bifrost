package com.lokiscale.bifrost.internal.core;

import java.util.List;

/**
 * Public registry of YAML-authored Bifrost skills.
 * Implementations must reject metadata whose kind is not {@link CapabilityKind#YAML_SKILL}.
 */
public interface CapabilityRegistry
{
    void register(String capabilityName, CapabilityMetadata metadata);

    CapabilityMetadata getCapability(String name);

    List<CapabilityMetadata> getAllCapabilities();
}
