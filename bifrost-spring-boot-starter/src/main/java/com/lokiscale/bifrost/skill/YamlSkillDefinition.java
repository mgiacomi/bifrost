package com.lokiscale.bifrost.skill;

import org.springframework.core.io.Resource;

public record YamlSkillDefinition(
        Resource resource,
        YamlSkillManifest manifest,
        EffectiveSkillExecutionConfiguration executionConfiguration) {
}
