package com.lokiscale.bifrost.runtime.state;

import org.springframework.lang.Nullable;

import java.util.Set;

public record EvidenceSnapshot(@Nullable Set<String> evidenceTypes)
{
    public static EvidenceSnapshot of(@Nullable Set<String> evidenceTypes)
    {
        return new EvidenceSnapshot(evidenceTypes == null ? null : Set.copyOf(evidenceTypes));
    }
}
