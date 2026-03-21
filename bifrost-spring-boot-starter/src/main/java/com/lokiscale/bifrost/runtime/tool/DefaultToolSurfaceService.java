package com.lokiscale.bifrost.runtime.tool;

import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.skill.SkillVisibilityResolver;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Objects;

public class DefaultToolSurfaceService implements ToolSurfaceService {

    private final SkillVisibilityResolver skillVisibilityResolver;

    public DefaultToolSurfaceService(SkillVisibilityResolver skillVisibilityResolver) {
        this.skillVisibilityResolver = Objects.requireNonNull(skillVisibilityResolver, "skillVisibilityResolver must not be null");
    }

    @Override
    public List<CapabilityMetadata> visibleToolsFor(String rootSkillName, @Nullable Authentication authentication) {
        return skillVisibilityResolver.visibleSkillsFor(rootSkillName, authentication);
    }
}
