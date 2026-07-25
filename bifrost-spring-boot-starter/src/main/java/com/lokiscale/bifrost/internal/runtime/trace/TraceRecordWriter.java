package com.lokiscale.bifrost.internal.runtime.trace;

import com.lokiscale.bifrost.internal.core.TraceRecord;

import java.io.IOException;

@FunctionalInterface
interface TraceRecordWriter
{
    void append(TraceRecord record) throws IOException;
}
