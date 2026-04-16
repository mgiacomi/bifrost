package com.lokiscale.bifrost.core;

import com.lokiscale.bifrost.autoconfigure.AiProvider;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import org.springframework.lang.Nullable;

import java.util.Objects;

public record SkillExecutionDescriptor(
        @Nullable String frameworkModel,
        @Nullable AiProvider provider,
        @Nullable String providerModel,
        @Nullable String thinkingLevel)
{
    public static SkillExecutionDescriptor none()
    {
        return new SkillExecutionDescriptor(null, null, null, null);
    }

    public static SkillExecutionDescriptor from(EffectiveSkillExecutionConfiguration configuration)
    {
        Objects.requireNonNull(configuration, "configuration must not be null");
        return new SkillExecutionDescriptor(
                configuration.frameworkModel(),
                configuration.provider(),
                configuration.providerModel(),
                configuration.thinkingLevel());
    }

    public boolean configured()
    {
        return frameworkModel != null;
    }
}
