package com.lokiscale.bifrost.internal.outputschema;

@FunctionalInterface
public interface OutputSchemaOutcomeRecorder
{
    void record(OutputSchemaOutcome outcome);
}
