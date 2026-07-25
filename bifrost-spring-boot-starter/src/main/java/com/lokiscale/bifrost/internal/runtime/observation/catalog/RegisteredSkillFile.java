package com.lokiscale.bifrost.internal.runtime.observation.catalog;

import java.util.Objects;

public record RegisteredSkillFile(String registeredName, String sourcePath, String yaml)
{
    public RegisteredSkillFile
    {
        registeredName = requireNonBlank(registeredName, "registeredName");
        sourcePath = requireNonBlank(sourcePath, "sourcePath");
        Objects.requireNonNull(yaml, "yaml must not be null");
    }

    public Summary summary()
    {
        return new Summary(registeredName, sourcePath);
    }

    public record Summary(String registeredName, String sourcePath)
    {
        public Summary
        {
            registeredName = requireNonBlank(registeredName, "registeredName");
            sourcePath = requireNonBlank(sourcePath, "sourcePath");
        }
    }

    private static String requireNonBlank(String value, String name)
    {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank())
        {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
