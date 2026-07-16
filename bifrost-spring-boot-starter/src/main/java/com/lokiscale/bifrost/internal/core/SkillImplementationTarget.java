package com.lokiscale.bifrost.internal.core;

import com.lokiscale.bifrost.internal.runtime.input.SkillInputContract;

import java.util.Objects;

public record SkillImplementationTarget(
        String id,
        String description,
        CapabilityInvoker invoker,
        String inputSchema,
        SkillInputContract inputContract)
{
    public SkillImplementationTarget
    {
        id = requireNonBlank(id, "id");
        description = requireNonBlank(description, "description");
        invoker = Objects.requireNonNull(invoker, "invoker must not be null");
        inputSchema = requireNonBlank(inputSchema, "inputSchema");
        inputContract = Objects.requireNonNull(inputContract, "inputContract must not be null");
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
