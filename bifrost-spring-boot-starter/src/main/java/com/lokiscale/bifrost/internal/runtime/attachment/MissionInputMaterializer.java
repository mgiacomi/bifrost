package com.lokiscale.bifrost.internal.runtime.attachment;

import com.lokiscale.bifrost.internal.core.BifrostSession;
import com.lokiscale.bifrost.internal.skill.YamlSkillDefinition;
import org.springframework.lang.Nullable;

import java.util.Map;

public interface MissionInputMaterializer
{
    RenderedMissionInput materialize(BifrostSession session,
            YamlSkillDefinition definition,
            String objective,
            @Nullable Map<String, Object> missionInput);
}
