package com.lokiscale.bifrost.internal.skill;

import com.lokiscale.bifrost.autoconfigure.AiDriver;
import org.springframework.lang.Nullable;

public record EffectiveSkillExecutionConfiguration(
                String frameworkModel,
                String connection,
                AiDriver driver,
                String providerModel,
                @Nullable String thinkingLevel)
{
}
