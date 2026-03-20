package com.lokiscale.bifrost.core;

public enum JournalEntryType {
    THOUGHT,
    PLAN_CREATED,
    PLAN_UPDATED,
    TOOL_CALL,
    UNPLANNED_TOOL_EXECUTION,
    TOOL_RESULT,
    ERROR
}
