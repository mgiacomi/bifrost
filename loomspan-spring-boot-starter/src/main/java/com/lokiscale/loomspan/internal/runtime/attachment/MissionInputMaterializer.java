package com.lokiscale.loomspan.internal.runtime.attachment;

import com.lokiscale.loomspan.internal.core.LoomspanSession;
import com.lokiscale.loomspan.internal.skill.YamlSkillDefinition;
import org.springframework.lang.Nullable;

import java.util.Map;

public interface MissionInputMaterializer
{
    RenderedMissionInput materialize(LoomspanSession session,
            YamlSkillDefinition definition,
            String objective,
            @Nullable Map<String, Object> missionInput);
}
