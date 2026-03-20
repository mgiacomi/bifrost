package com.lokiscale.bifrost.skill;

import com.lokiscale.bifrost.core.CapabilityMetadata;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.util.List;

public interface SkillVisibilityResolver {

    List<CapabilityMetadata> visibleSkillsFor(String currentSkillName, @Nullable Authentication authentication);
}
