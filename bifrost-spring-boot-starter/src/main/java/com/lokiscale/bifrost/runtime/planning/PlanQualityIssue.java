package com.lokiscale.bifrost.runtime.planning;

import java.util.Objects;

public record PlanQualityIssue(String code, Severity severity, String message)
{
    public PlanQualityIssue
    {
        code = requireNonBlank(code, "code");
        severity = Objects.requireNonNull(severity, "severity must not be null");
        message = requireNonBlank(message, "message");
    }

    public enum Severity
    {
        WARNING,
        ERROR
    }

    private static String requireNonBlank(String value, String fieldName)
    {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank())
        {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
