package com.lokiscale.bifrost.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lokiscale.bifrost.autoconfigure.AiProvider;
import com.lokiscale.bifrost.runtime.evidence.EvidenceContract;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlSkillDefinitionTest
{
    private static final EffectiveSkillExecutionConfiguration CONFIGURATION =
            new EffectiveSkillExecutionConfiguration("gpt-5", AiProvider.OPENAI, "openai/gpt-5", "medium");

    @Test
    void preservesDeclaredFieldsAcrossDefinitionDefensiveCopies()
    {
        YamlSkillManifest manifest = manifest("llm.copy.skill");
        manifest.setModel(null);
        manifest.setThinkingLevel(" ");
        manifest.setPrompt(null);
        manifest.setPlanningMode(false);
        manifest.setMaxSteps(0);
        manifest.setAllowedSkills(List.of());
        manifest.setInputSchema(null);
        manifest.setOutputSchema(null);
        manifest.setLinter(null);
        manifest.setOutputSchemaMaxRetries(0);
        manifest.setEvidenceContract(null);
        YamlSkillDefinition definition = new YamlSkillDefinition(resource(), manifest, CONFIGURATION);
        YamlSkillManifest firstCopy = definition.manifest();
        YamlSkillManifest secondCopy = definition.manifest();

        assertThat(firstCopy.declaredFields()).isEqualTo(manifest.declaredFields());
        assertThat(firstCopy.isDeclared(YamlSkillManifest.Field.MODEL)).isTrue();
        assertThat(firstCopy.isDeclared(YamlSkillManifest.Field.ALLOWED_SKILLS)).isTrue();
        firstCopy.setModel("mutated");
        firstCopy.setRbacRoles(List.of("ROLE_MUTATED"));
        assertThat(secondCopy.getModel()).isNull();
        assertThat(secondCopy.getRbacRoles()).isEmpty();
        assertThat(new ObjectMapper().valueToTree(firstCopy).has("declaredFields")).isFalse();

        YamlSkillManifest omitted = manifest("llm.omitted.copy.skill");
        YamlSkillDefinition llm = new YamlSkillDefinition(resource(), omitted, CONFIGURATION);
        assertThat(llm.manifest().declaredFields()).isEmpty();

        YamlSkillManifest mapped = mappedManifest("mapped.copy.skill", "targetBean#deterministicTarget");
        YamlSkillDefinition mappedDefinition = new YamlSkillDefinition(resource(), mapped, null);
        assertThat(mappedDefinition.manifest().isDeclared(YamlSkillManifest.Field.MAPPING)).isTrue();
    }

    @Test
    void enforcesExecutionConfigurationInvariantByImplementationType()
    {
        YamlSkillManifest llm = manifest("llm.skill");
        YamlSkillDefinition llmDefinition = new YamlSkillDefinition(resource(), llm, CONFIGURATION);
        assertThat(llmDefinition.requireExecutionConfiguration()).isSameAs(CONFIGURATION);

        YamlSkillManifest mapped = mappedManifest("mapped.skill", "targetBean#deterministicTarget");
        YamlSkillDefinition mappedDefinition = new YamlSkillDefinition(resource(), mapped, null);
        assertThat(mappedDefinition.executionConfiguration()).isNull();
        assertThatThrownBy(mappedDefinition::requireExecutionConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires an execution configuration");

        assertThatThrownBy(() -> new YamlSkillDefinition(resource(), llm, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LLM-backed");
        assertThatThrownBy(() -> new YamlSkillDefinition(resource(), mapped, CONFIGURATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mapped");

        for (String targetId : new String[] { null, "", "   " })
        {
            YamlSkillManifest malformedMapped = mappedManifest("malformed.mapped.skill", targetId);
            assertThatThrownBy(() -> new YamlSkillDefinition(resource(), malformedMapped, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-blank mapping target");
        }
    }

    @Test
    void rejectsMappedDeclarationsAndResolvedEvidenceThatBypassCatalogValidation()
    {
        YamlSkillManifest withInputSchema = mappedManifest("mapped.schema.skill", "targetBean#deterministicTarget");
        withInputSchema.setInputSchema(new YamlSkillManifest.InputSchemaManifest());
        assertThatThrownBy(() -> new YamlSkillDefinition(resource(), withInputSchema, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("input_schema");

        YamlSkillManifest mapped = mappedManifest("mapped.evidence.skill", "targetBean#deterministicTarget");
        YamlSkillManifest.EvidenceContractManifest evidenceManifest = new YamlSkillManifest.EvidenceContractManifest();
        evidenceManifest.setToolEvidence(Map.of("child.skill", List.of("receipt")));
        EvidenceContract evidenceContract = EvidenceContract.fromManifest(evidenceManifest, null);
        assertThatThrownBy(() -> new YamlSkillDefinition(resource(), mapped, null, evidenceContract))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence contract");
    }

    private static YamlSkillManifest manifest(String name)
    {
        YamlSkillManifest manifest = new YamlSkillManifest();
        manifest.setName(name);
        manifest.setDescription(name);
        return manifest;
    }

    private static YamlSkillManifest mappedManifest(String name, String targetId)
    {
        YamlSkillManifest manifest = manifest(name);
        YamlSkillManifest.MappingManifest mapping = new YamlSkillManifest.MappingManifest();
        mapping.setTargetId(targetId);
        manifest.setMapping(mapping);
        return manifest;
    }

    private static ByteArrayResource resource()
    {
        return new ByteArrayResource(new byte[0], "test-skill.yaml");
    }
}
