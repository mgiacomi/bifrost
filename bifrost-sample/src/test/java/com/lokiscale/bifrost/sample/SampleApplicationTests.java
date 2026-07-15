package com.lokiscale.bifrost.sample;

import com.lokiscale.bifrost.autoconfigure.BifrostAutoConfiguration;
import com.lokiscale.bifrost.autoconfigure.BifrostProperties;
import com.lokiscale.bifrost.core.CapabilityRegistry;
import com.lokiscale.bifrost.core.SkillImplementationTargetRegistry;
import com.lokiscale.bifrost.skill.YamlSkillCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = SampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SampleApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private CapabilityRegistry capabilityRegistry;

    @Autowired
    private SkillImplementationTargetRegistry targetRegistry;

    @Autowired
    private YamlSkillCatalog yamlSkillCatalog;

    @Autowired
    private BifrostProperties bifrostProperties;

    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void loadsBifrostAutoConfiguration() {
        assertThat(applicationContext.containsBeanDefinition(BifrostAutoConfiguration.class.getName())).isTrue();
    }

    @Test
    void publishesYamlSkillsAndKeepsExpenseTargetInternal() {
        assertThat(capabilityRegistry.getCapability("expenseLookup")).isNotNull();
        assertThat(capabilityRegistry.getCapability("expenseLookup").skillExecution().configured()).isFalse();
        assertThat(capabilityRegistry.getCapability("invoiceParser")).isNotNull();
        assertThat(capabilityRegistry.getCapability("feedstockTicketParser")).isNotNull();
        assertThat(capabilityRegistry.getCapability("feedstockTicketParser").skillExecution().configured()).isFalse();
        assertThat(capabilityRegistry.getCapability("getLatestExpenses")).isNull();
        assertThat(capabilityRegistry.getCapability("expenseService#getLatestExpenses")).isNull();
        assertThat(targetRegistry.getTarget("expenseService#getLatestExpenses")).isNotNull();
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
        assertThat(bifrostProperties.getModels().get("openai-gpt-5-mini").getConnection()).isEqualTo("openai-main");
        assertThat(bifrostProperties.getConnections().get("ollama-main").getDriver().name()).isEqualTo("OLLAMA");
        assertThat(bifrostProperties.getConnections().get("ollama-secondary").getDriver().name()).isEqualTo("OLLAMA");
        assertThat(bifrostProperties.getConnections().get("ollama-main").getBaseUrl())
                .isNotEqualTo(bifrostProperties.getConnections().get("ollama-secondary").getBaseUrl());
    }
}
