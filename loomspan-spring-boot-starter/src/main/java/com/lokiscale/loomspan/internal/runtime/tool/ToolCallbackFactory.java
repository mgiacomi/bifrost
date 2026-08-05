package com.lokiscale.loomspan.internal.runtime.tool;

import com.lokiscale.loomspan.internal.core.LoomspanSession;
import com.lokiscale.loomspan.internal.core.CapabilityMetadata;
import com.lokiscale.loomspan.internal.skill.YamlSkillDefinition;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.util.List;

public interface ToolCallbackFactory
{
    List<ToolCallback> createToolCallbacks(
            LoomspanSession session,
            YamlSkillDefinition definition,
            List<CapabilityMetadata> capabilities,
            @Nullable Authentication authentication);
}
