package com.lokiscale.bifrost.chat;

import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.List;

public final class NoOpSkillAdvisorResolver implements SkillAdvisorResolver
{
    @Override
    public List<Advisor> resolve(YamlSkillDefinition definition)
    {
        return List.of();
    }
}
