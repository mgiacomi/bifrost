package com.lokiscale.bifrost.internal.core;

import java.util.List;

public interface SkillImplementationTargetRegistry
{
    void register(SkillImplementationTarget target);

    SkillImplementationTarget getTarget(String targetId);

    List<SkillImplementationTarget> getAllTargets();
}
