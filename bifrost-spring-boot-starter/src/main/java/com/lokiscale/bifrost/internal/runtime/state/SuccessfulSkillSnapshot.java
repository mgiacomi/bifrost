package com.lokiscale.bifrost.internal.runtime.state;

import org.springframework.lang.Nullable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public record SuccessfulSkillSnapshot(@Nullable Set<String> successfulDirectSkills)
{
    public SuccessfulSkillSnapshot
    {
        successfulDirectSkills = successfulDirectSkills == null
                ? null
                : Collections.unmodifiableSet(new LinkedHashSet<>(successfulDirectSkills));
    }

    public static SuccessfulSkillSnapshot of(@Nullable Set<String> successfulDirectSkills)
    {
        return new SuccessfulSkillSnapshot(successfulDirectSkills);
    }
}
