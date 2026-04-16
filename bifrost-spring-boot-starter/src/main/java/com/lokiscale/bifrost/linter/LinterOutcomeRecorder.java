package com.lokiscale.bifrost.linter;

@FunctionalInterface
public interface LinterOutcomeRecorder
{
    void record(LinterOutcome outcome);
}
