package com.lokiscale.bifrost.internal.core;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemorySkillImplementationTargetRegistry implements SkillImplementationTargetRegistry
{
    private final ConcurrentMap<String, SkillImplementationTarget> targetsById = new ConcurrentHashMap<>();

    @Override
    public void register(SkillImplementationTarget target)
    {
        SkillImplementationTarget nonNullTarget = Objects.requireNonNull(target, "target must not be null");
        SkillImplementationTarget existing = targetsById.putIfAbsent(nonNullTarget.id(), nonNullTarget);
        if (existing != null)
        {
            throw new SkillImplementationTargetCollisionException(
                    "Duplicate skill implementation target ID '" + nonNullTarget.id() + "'. "
                            + "Each @SkillMethod target must have a unique beanName#methodName ID.");
        }
    }

    @Override
    public SkillImplementationTarget getTarget(String targetId)
    {
        if (targetId == null || targetId.isBlank())
        {
            return null;
        }
        return targetsById.get(targetId);
    }

    @Override
    public List<SkillImplementationTarget> getAllTargets()
    {
        return List.copyOf(targetsById.values());
    }
}
