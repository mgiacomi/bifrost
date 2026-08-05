package com.lokiscale.loomspan.internal.runtime.trace;

import com.lokiscale.loomspan.internal.core.TraceRecord;

import java.io.IOException;

@FunctionalInterface
interface TraceRecordWriter
{
    void append(TraceRecord record) throws IOException;
}
