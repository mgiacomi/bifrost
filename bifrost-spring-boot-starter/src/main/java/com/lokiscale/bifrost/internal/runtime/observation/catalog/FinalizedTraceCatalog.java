package com.lokiscale.bifrost.internal.runtime.observation.catalog;

import com.lokiscale.bifrost.internal.core.FinalizedTraceArtifact;

import java.util.Optional;

public interface FinalizedTraceCatalog extends AutoCloseable
{
    FinalizedTraceCatalogEntry publish(FinalizedTraceArtifact artifact);

    Optional<FinalizedTraceCatalogEntry> find(String traceId);

    TraceCatalogSlice list(long highWaterOrdinal, long beforeOrdinal, int limit);

    int catalogedTraceCount();

    @Override
    void close();
}
