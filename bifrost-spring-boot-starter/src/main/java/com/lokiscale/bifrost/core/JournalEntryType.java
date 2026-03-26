package com.lokiscale.bifrost.core;

public enum JournalEntryType {
    THOUGHT,
    PLAN_CREATED,
    PLAN_UPDATED,
    LINTER,
    OUTPUT_SCHEMA,
    TOOL_CALL,
    UNPLANNED_TOOL_EXECUTION,
    TOOL_FAILURE,
    TOOL_RESULT,
    ERROR
}
