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

    public YamlSkillManifest.LinterManifest linter() {
        return manifest.getLinter();
    }

    public YamlSkillManifest.OutputSchemaManifest outputSchema() {
        return manifest.getOutputSchema();
    }

    public int outputSchemaMaxRetries() {
        return manifest.getOutputSchemaMaxRetries() == null ? 0 : manifest.getOutputSchemaMaxRetries();
    }

    public String mappingTargetId() {
        return manifest.getMapping().getTargetId();
    }

    public boolean planningModeEnabled(boolean defaultValue) {
        return manifest.getPlanningMode() == null ? defaultValue : manifest.getPlanningMode();
    }

    public boolean planningModeExplicitlyEnabled() {
        return Boolean.TRUE.equals(manifest.getPlanningMode());
    }

    public int maxSteps(int defaultValue) {
        return manifest.getMaxSteps() == null ? defaultValue : manifest.getMaxSteps();
    }
}
