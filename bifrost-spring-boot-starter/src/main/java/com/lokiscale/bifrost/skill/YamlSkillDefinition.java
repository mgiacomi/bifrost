package com.lokiscale.bifrost.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import com.lokiscale.bifrost.runtime.evidence.EvidenceContract;
import com.lokiscale.bifrost.core.PublicSkillImplementationType;
import org.springframework.util.StringUtils;

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
    private static final ObjectMapper COPY_MAPPER = new ObjectMapper();

    public YamlSkillDefinition
    {
        manifest = copyManifest(manifest);
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
        return copyValue(manifest.getLinter(), YamlSkillManifest.LinterManifest.class);
    }

    public YamlSkillManifest.OutputSchemaManifest outputSchema()
    {
        return copyValue(manifest.getOutputSchema(), YamlSkillManifest.OutputSchemaManifest.class);
    }

    public String prompt()
    {
        return manifest.getPrompt();
    }

    public YamlSkillManifest.InputSchemaManifest inputSchema()
    {
        return copyValue(manifest.getInputSchema(), YamlSkillManifest.InputSchemaManifest.class);
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
        String targetId = manifest.getMapping().getTargetId();
        return StringUtils.hasText(targetId) ? targetId.trim() : null;
    }

    /** Returns a defensive copy so catalog state cannot be mutated after registration. */
    public YamlSkillManifest manifest()
    {
        return copyManifest(manifest);
    }

    public PublicSkillImplementationType implementationType()
    {
        return mappingTargetId() == null || mappingTargetId().isBlank()
                ? PublicSkillImplementationType.LLM_BACKED
                : PublicSkillImplementationType.MAPPED_JAVA;
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

    private static YamlSkillManifest copyManifest(YamlSkillManifest source)
    {
        if (source == null)
        {
            throw new NullPointerException("manifest must not be null");
        }
        return COPY_MAPPER.convertValue(source, YamlSkillManifest.class);
    }

    private static <T> T copyValue(T source, Class<T> type)
    {
        return source == null ? null : COPY_MAPPER.convertValue(source, type);
    }
}
