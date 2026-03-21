package com.lokiscale.bifrost.skill;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.util.List;

public interface SkillVisibilityResolver {

    List<CapabilityMetadata> visibleSkillsFor(String currentSkillName,
                                              BifrostSession session,
                                              @Nullable Authentication authentication);
}
