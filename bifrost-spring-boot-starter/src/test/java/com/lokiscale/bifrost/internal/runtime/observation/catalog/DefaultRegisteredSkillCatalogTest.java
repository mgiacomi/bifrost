package com.lokiscale.bifrost.internal.runtime.observation.catalog;

import com.lokiscale.bifrost.autoconfigure.AiDriver;
import com.lokiscale.bifrost.internal.runtime.evidence.EvidenceContract;
import com.lokiscale.bifrost.internal.skill.EffectiveSkillExecutionConfiguration;
import com.lokiscale.bifrost.internal.skill.YamlSkillDefinition;
import com.lokiscale.bifrost.internal.skill.YamlSkillManifest;
import com.lokiscale.bifrost.internal.skill.YamlSkillSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultRegisteredSkillCatalogTest
{
    @TempDir
    Path tempDir;

    @Test
    void preservesYamlAndTraversesByExactRegisteredName() throws Exception
    {
        String alphaYaml = "# comment\r\nname: Alpha\r\ndescription: first\r\n";
        String betaYaml = "name: beta\ndescription: second\n";
        DefaultRegisteredSkillCatalog catalog = new DefaultRegisteredSkillCatalog(
                List.of(definition("beta", betaYaml), definition("Alpha", alphaYaml)),
                new SkillSourcePathResolver());

        assertThat(catalog.listAfter(null, 10))
                .extracting(RegisteredSkillFile.Summary::registeredName)
                .containsExactly("Alpha", "beta");
        assertThat(catalog.listAfter("Alpha", 10))
                .extracting(RegisteredSkillFile.Summary::registeredName)
                .containsExactly("beta");
        assertThat(catalog.find("Alpha").orElseThrow().yaml()).isEqualTo(alphaYaml);
        assertThat(catalog.find("alpha")).isEmpty();
        assertThat(catalog.registeredSkillCount()).isEqualTo(2);
    }

    @Test
    void rejectsInvalidUtf8OnlyWhenInspectionCatalogIsConstructed() throws Exception
    {
        YamlSkillDefinition definition = definition("skill", "name: skill\n");
        YamlSkillSource invalid = new YamlSkillSource(
                definition.resource(),
                definition.source().locationPattern(),
                new byte[] {(byte) 0xC3, (byte) 0x28});
        YamlSkillDefinition withInvalidSource = new YamlSkillDefinition(
                definition.resource(),
                definition.manifest(),
                definition.executionConfiguration(),
                EvidenceContract.empty(),
                invalid);

        assertThat(withInvalidSource.manifest().getName()).isEqualTo("skill");
        assertThatThrownBy(() -> new DefaultRegisteredSkillCatalog(
                List.of(withInvalidSource), new SkillSourcePathResolver()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid UTF-8");
    }

    private YamlSkillDefinition definition(String name, String yaml) throws Exception
    {
        Path file = Files.writeString(tempDir.resolve(name + ".yaml"), yaml, StandardCharsets.UTF_8);
        YamlSkillManifest manifest = new YamlSkillManifest();
        manifest.setName(name);
        manifest.setDescription("description");
        manifest.setModel("model");
        return new YamlSkillDefinition(
                new FileSystemResource(file),
                manifest,
                new EffectiveSkillExecutionConfiguration(
                        "model", "connection", AiDriver.OPENAI, "provider-model", null),
                EvidenceContract.empty(),
                new YamlSkillSource(
                        new FileSystemResource(file),
                        file.toUri().toString(),
                        yaml.getBytes(StandardCharsets.UTF_8)));
    }
}
