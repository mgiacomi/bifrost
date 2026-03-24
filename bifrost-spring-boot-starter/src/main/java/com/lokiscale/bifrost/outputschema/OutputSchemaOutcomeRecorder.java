package com.lokiscale.bifrost.outputschema;

@FunctionalInterface
public interface OutputSchemaOutcomeRecorder {

    void record(OutputSchemaOutcome outcome);
}
