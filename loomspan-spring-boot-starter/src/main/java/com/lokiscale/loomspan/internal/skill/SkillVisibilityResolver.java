package com.lokiscale.loomspan.internal.skill;

import com.lokiscale.loomspan.internal.core.LoomspanSession;
import com.lokiscale.loomspan.internal.core.CapabilityMetadata;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.util.List;

public interface SkillVisibilityResolver
{
    List<CapabilityMetadata> visibleSkillsFor(String currentSkillName, LoomspanSession session, @Nullable Authentication authentication);
}
