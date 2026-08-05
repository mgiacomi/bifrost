package com.lokiscale.loomspan.internal.outputschema;

public record OutputSchemaValidationIssue(String path, String message, String canonicalField)
{
}
