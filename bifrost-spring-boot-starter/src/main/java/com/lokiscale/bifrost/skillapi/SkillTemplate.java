package com.lokiscale.bifrost.skillapi;

import java.util.Map;
import java.util.function.Consumer;

public interface SkillTemplate
{
    String invoke(String skillName, Object input);

    String invoke(String skillName, Map<String, Object> input);

    String invoke(String skillName, Object input, Consumer<SkillExecutionView> observer);

    String invoke(String skillName, Map<String, Object> input, Consumer<SkillExecutionView> observer);
}
