package com.lokiscale.bifrost.internal.chat;

import com.lokiscale.bifrost.internal.skill.YamlSkillDefinition;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.List;

public interface SkillAdvisorResolver
{
    List<Advisor> resolve(YamlSkillDefinition definition);
}
