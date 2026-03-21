package com.lokiscale.bifrost.runtime.tool;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.util.List;

public interface ToolSurfaceService {

    List<CapabilityMetadata> visibleToolsFor(String rootSkillName,
                                             BifrostSession session,
                                             @Nullable Authentication authentication);
}
