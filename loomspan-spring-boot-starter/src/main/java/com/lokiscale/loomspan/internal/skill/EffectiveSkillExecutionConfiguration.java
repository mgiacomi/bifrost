package com.lokiscale.loomspan.internal.skill;

import com.lokiscale.loomspan.autoconfigure.AiDriver;
import org.springframework.lang.Nullable;

public record EffectiveSkillExecutionConfiguration(
                String frameworkModel,
                String connection,
                AiDriver driver,
                String providerModel,
                @Nullable String thinkingLevel)
{
}
