package com.lokiscale.bifrost.skill;

import org.springframework.core.io.Resource;
import com.lokiscale.bifrost.runtime.evidence.EvidenceContract;

import java.util.List;

/**
 * Stable typed catalog entry for a YAML skill manifest and its resolved execution configuration.
 */
public record YamlSkillDefinition(
        Resource resource,
        YamlSkillManifest manifest,
        EffectiveSkillExecutionConfiguration executionConfiguration,
        EvidenceContract evidenceContract)
{
    public YamlSkillDefinition
    {
        evidenceContract = evidenceContract == null ? EvidenceContract.empty() : evidenceContract;
    }

    public YamlSkillDefinition(Resource resource,
            YamlSkillManifest manifest,
            EffectiveSkillExecutionConfiguration executionConfiguration)
    {
        this(resource, manifest, executionConfiguration, EvidenceContract.empty());
    }

    public List<String> allowedSkills()
    {
        return manifest.getAllowedSkills();
    }

    public List<String> rbacRoles()
    {
        return manifest.getRbacRoles();
    }

    public YamlSkillManifest.LinterManifest linter()
    {
        return manifest.getLinter();
    }

    public YamlSkillManifest.OutputSchemaManifest outputSchema()
    {
        return manifest.getOutputSchema();
    }

    public YamlSkillManifest.OutputSchemaManifest inputSchema()
    {
        return manifest.getInputSchema();
    }

    public boolean hasDeclaredInputSchema()
    {
        return manifest.getInputSchema() != null;
    }

    public boolean hasInheritedInputContract()
    {
        return !hasDeclaredInputSchema() && mappingTargetId() != null && !mappingTargetId().isBlank();
    }

    public boolean hasGenericInputContract()
    {
        return !hasDeclaredInputSchema() && !hasInheritedInputContract();
    }

    public int outputSchemaMaxRetries()
    {
        return manifest.getOutputSchemaMaxRetries() == null ? 0 : manifest.getOutputSchemaMaxRetries();
    }

    public String mappingTargetId()
    {
        return manifest.getMapping().getTargetId();
    }

    public boolean planningModeEnabled(boolean defaultValue)
    {
        return manifest.getPlanningMode() == null ? defaultValue : manifest.getPlanningMode();
    }

    public boolean planningModeExplicitlyEnabled()
    {
        return Boolean.TRUE.equals(manifest.getPlanningMode());
    }

    public int maxSteps(int defaultValue)
    {
        return manifest.getMaxSteps() == null ? defaultValue : manifest.getMaxSteps();
    }
}
