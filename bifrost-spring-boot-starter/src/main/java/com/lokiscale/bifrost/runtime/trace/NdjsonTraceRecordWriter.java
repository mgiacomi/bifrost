package com.lokiscale.bifrost.runtime.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.lokiscale.bifrost.core.TraceRecord;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public final class NdjsonTraceRecordWriter
{
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build();

    private final Path tracePath;

    public NdjsonTraceRecordWriter(Path tracePath)
    {
        this.tracePath = Objects.requireNonNull(tracePath, "tracePath must not be null");
    }

    public synchronized void append(TraceRecord record) throws IOException
    {
        Files.createDirectories(tracePath.getParent());

        try (Writer writer = Files.newBufferedWriter(
                tracePath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND))
        {
            writer.write(OBJECT_MAPPER.writeValueAsString(record));
            writer.write('\n');
        }
    }
}
