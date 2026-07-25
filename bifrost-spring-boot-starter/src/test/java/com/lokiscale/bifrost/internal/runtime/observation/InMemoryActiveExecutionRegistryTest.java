package com.lokiscale.bifrost.internal.runtime.observation;

import com.lokiscale.bifrost.internal.runtime.usage.SessionUsageSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryActiveExecutionRegistryTest
{
    @Test
    void assignsOneStableOrdinalPerSessionAndTraversesNewestFirst()
    {
        InMemoryActiveExecutionRegistry registry = new InMemoryActiveExecutionRegistry();

        ActiveExecutionSnapshot first = registry.replace(snapshot("one", 1));
        ActiveExecutionSnapshot second = registry.replace(snapshot("two", 1));
        ActiveExecutionSnapshot updated = registry.replace(snapshot("one", 2));

        assertThat(first.registryOrdinal()).isPositive();
        assertThat(second.registryOrdinal()).isGreaterThan(first.registryOrdinal());
        assertThat(updated.registryOrdinal()).isEqualTo(first.registryOrdinal());
        assertThat(registry.newestFirst(0, 10))
                .extracting(ActiveExecutionSnapshot::sessionId)
                .containsExactly("two", "one");
        assertThat(registry.remove("one")).isTrue();
        assertThat(registry.find("one")).isEmpty();
    }

    @Test
    void failsInsteadOfWrappingRegistryOrdinal()
    {
        AtomicLong value = new AtomicLong(Long.MAX_VALUE);
        InMemoryActiveExecutionRegistry registry = new InMemoryActiveExecutionRegistry(value::getAndIncrement);

        registry.replace(snapshot("one", 1));
        assertThatThrownBy(() -> registry.replace(snapshot("two", 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void supportsConcurrentIndependentSessionUpdates() throws Exception
    {
        InMemoryActiveExecutionRegistry registry = new InMemoryActiveExecutionRegistry();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor())
        {
            var futures = java.util.stream.IntStream.range(0, 128)
                    .mapToObj(index -> executor.submit(() ->
                            registry.replace(snapshot("session-" + index, 1))))
                    .toList();
            for (var future : futures)
            {
                future.get();
            }
        }
        assertThat(registry.activeCount()).isEqualTo(128);
        assertThat(registry.newestFirst(registry.highestOrdinal(), 128))
                .extracting(ActiveExecutionSnapshot::registryOrdinal)
                .doesNotHaveDuplicates()
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    static ActiveExecutionSnapshot snapshot(String sessionId, long sequence)
    {
        Instant now = Instant.parse("2026-07-24T12:00:00Z");
        return new ActiveExecutionSnapshot(
                sessionId, "trace-" + sessionId, 0, sequence, now, now, "entry", "RUNNING", "summary",
                List.of(), 0, false, SessionUsageSnapshot.empty(), null);
    }
}
