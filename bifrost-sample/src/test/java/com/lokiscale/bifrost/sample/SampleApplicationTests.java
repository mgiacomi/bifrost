package com.lokiscale.bifrost.sample;

import com.lokiscale.bifrost.autoconfigure.BifrostAutoConfiguration;
import com.lokiscale.bifrost.autoconfigure.BifrostModelsProperties;
import com.lokiscale.bifrost.core.CapabilityRegistry;
import com.lokiscale.bifrost.skill.YamlSkillCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = SampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.ai.openai.api-key=test-openai-api-key")
class SampleApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private CapabilityRegistry capabilityRegistry;

    @Autowired
    private YamlSkillCatalog yamlSkillCatalog;

    @Autowired
    private BifrostModelsProperties modelsProperties;

    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void loadsBifrostAutoConfiguration() {
        assertThat(applicationContext.containsBeanDefinition(BifrostAutoConfiguration.class.getName())).isTrue();
    }

    @Test
    void registersSkillsTocapabilityRegistry() {
        assertThat(capabilityRegistry.getCapability("getLatestExpenses")).isNotNull();
        assertThat(capabilityRegistry.getCapability("invoiceParser")).isNotNull();
    }

    @Test
    void feedstockPureYamlSkillHasPromptAttachmentAndNullableSchema() {
        var definition = yamlSkillCatalog.getSkill("feedstockTicketParserBySkill");

        assertThat(definition).isNotNull();
        assertThat(definition.prompt())
                .contains("high-precision document parser")
                .contains("Gross - Tare = Net")
                .contains("return null");
        assertThat(definition.inputSchema().getProperties().get("image").getType()).isEqualTo("attachment");
        assertThat(definition.inputSchema().getProperties().get("image").getMediaType()).isEqualTo("image");
        assertThat(definition.outputSchema().getProperties().values())
                .allSatisfy(field -> assertThat(field.getNullable()).isTrue());
        assertThat(definition.linter()).isNotNull();
        assertThat(modelsProperties.getModels().get("openai-gpt-5-mini").getProviderModel()).isEqualTo("gpt-5-mini");
    }
}
