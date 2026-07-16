package com.lokiscale.bifrost.internal.chat;

import com.lokiscale.bifrost.internal.skill.YamlSkillDefinition;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.List;

final class NoOpSkillAdvisorResolver implements SkillAdvisorResolver
{
    @Override
    public List<Advisor> resolve(YamlSkillDefinition definition)
    {
        return List.of();
    }
}
