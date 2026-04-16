package com.lokiscale.bifrost.skill;

import com.lokiscale.bifrost.autoconfigure.AiProvider;
import org.springframework.lang.Nullable;

public record EffectiveSkillExecutionConfiguration(
                String frameworkModel,
                AiProvider provider,
                String providerModel,
                @Nullable String thinkingLevel)
{
}
