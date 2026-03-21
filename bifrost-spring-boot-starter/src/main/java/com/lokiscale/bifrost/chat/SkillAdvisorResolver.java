package com.lokiscale.bifrost.chat;

import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.List;

public interface SkillAdvisorResolver {

    List<Advisor> resolve(YamlSkillDefinition definition);
}
