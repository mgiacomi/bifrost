package com.lokiscale.bifrost.internal.linter;

@FunctionalInterface
public interface LinterOutcomeRecorder
{
    void record(LinterOutcome outcome);
}
