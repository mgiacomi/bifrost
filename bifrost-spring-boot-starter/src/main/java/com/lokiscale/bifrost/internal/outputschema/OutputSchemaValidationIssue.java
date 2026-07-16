package com.lokiscale.bifrost.internal.outputschema;

public record OutputSchemaValidationIssue(String path, String message, String canonicalField)
{
}
