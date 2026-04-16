package com.lokiscale.bifrost.outputschema;

public record OutputSchemaValidationIssue(String path, String message, String canonicalField)
{
}
