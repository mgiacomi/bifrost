package com.lokiscale.bifrost.skill;

import org.springframework.core.io.Resource;

import java.util.List;

public record YamlSkillDefinition(
        Resource resource,
        YamlSkillManifest manifest,
        EffectiveSkillExecutionConfiguration executionConfiguration) {

    public List<String> allowedSkills() {
        return manifest.getAllowedSkills();
    }

    public List<String> rbacRoles() {
        return manifest.getRbacRoles();
    }

    public String mappingTargetId() {
        return manifest.getMapping().getTargetId();
    }

    public boolean planningModeEnabled(boolean defaultValue) {
        return manifest.getPlanningMode() == null ? defaultValue : manifest.getPlanningMode();
    }
}
