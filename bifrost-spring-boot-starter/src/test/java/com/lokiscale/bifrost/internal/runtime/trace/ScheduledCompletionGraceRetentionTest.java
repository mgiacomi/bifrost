package com.lokiscale.bifrost.internal.runtime.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduledCompletionGraceRetentionTest
{
    @TempDir
    Path tempDir;

    @Test
    void zeroGraceDeletesSynchronouslyAndNonzeroCloseCancelsWithoutDeleting() throws Exception
    {
        Path immediate = Files.writeString(tempDir.resolve("immediate"), "trace");
        try (ScheduledCompletionGraceRetention retention =
                     new ScheduledCompletionGraceRetention(Duration.ZERO))
        {
            assertThat(retention.retainOrDelete(
                    immediate, Instant.parse("2026-07-24T12:00:00Z"), "trace", "session")).isEmpty();
        }
        assertThat(immediate).doesNotExist();

        Path held = Files.writeString(tempDir.resolve("held"), "trace");
        ScheduledCompletionGraceRetention retention =
                new ScheduledCompletionGraceRetention(Duration.ofHours(1));
        assertThat(retention.retainOrDelete(
                held, Instant.parse("2026-07-24T12:00:00Z"), "trace", "session"))
                .contains(new CompletionGraceRetention.RetainedArtifact(
                        Instant.parse("2026-07-24T13:00:00Z"),
                        Files.size(held)));
        retention.close();
        assertThat(held).exists();
    }

    @Test
    void rejectsNegativeGrace()
    {
        assertThatThrownBy(() -> new ScheduledCompletionGraceRetention(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
