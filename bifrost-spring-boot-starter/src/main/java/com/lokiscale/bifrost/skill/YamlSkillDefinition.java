package com.lokiscale.bifrost.skill;

import org.springframework.core.io.Resource;

import java.util.List;

/**
 * Stable typed catalog entry for a YAML skill manifest and its resolved execution configuration.
 */
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
