package com.lokiscale.bifrost.runtime.tool;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.util.List;

public interface ToolCallbackFactory
{
    List<ToolCallback> createToolCallbacks(
            BifrostSession session,
            YamlSkillDefinition definition,
            List<CapabilityMetadata> capabilities,
            @Nullable Authentication authentication);
}
