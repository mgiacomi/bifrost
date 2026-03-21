package com.lokiscale.bifrost.chat;

import com.lokiscale.bifrost.autoconfigure.AiProvider;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import com.lokiscale.bifrost.skill.YamlSkillManifest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import static org.assertj.core.api.Assertions.assertThat;

class SkillAdvisorResolverTests {

    private final NoOpSkillAdvisorResolver resolver = new NoOpSkillAdvisorResolver();

    @Test
    void noOpResolverReturnsEmptyAdvisorListForSkillWithoutLinter() {
        assertThat(resolver.resolve(definition(false))).isEmpty();
    }

    @Test
    void noOpResolverAlsoReturnsEmptyAdvisorListForSkillWithLinterMetadata() {
        assertThat(resolver.resolve(definition(true))).isEmpty();
    }

    private YamlSkillDefinition definition(boolean withLinter) {
        YamlSkillManifest manifest = new YamlSkillManifest();
        manifest.setName(withLinter ? "linted.skill" : "plain.skill");
        manifest.setDescription(manifest.getName());
        manifest.setModel("gpt-5");
        if (withLinter) {
            YamlSkillManifest.RegexManifest regex = new YamlSkillManifest.RegexManifest();
            regex.setPattern("OK");
            regex.setMessage("must contain OK");
            YamlSkillManifest.LinterManifest linter = new YamlSkillManifest.LinterManifest();
            linter.setType("regex");
            linter.setMaxRetries(2);
            linter.setRegex(regex);
            manifest.setLinter(linter);
        }
        return new YamlSkillDefinition(
                new ByteArrayResource(new byte[0]),
                manifest,
                new EffectiveSkillExecutionConfiguration("gpt-5", AiProvider.OPENAI, "openai/gpt-5", "medium"));
    }
}
