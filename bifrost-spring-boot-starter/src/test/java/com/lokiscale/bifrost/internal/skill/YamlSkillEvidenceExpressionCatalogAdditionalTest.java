package com.lokiscale.bifrost.internal.skill;

import com.lokiscale.bifrost.autoconfigure.BifrostAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class YamlSkillEvidenceExpressionCatalogAdditionalTest
{
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    BifrostAutoConfiguration.class))
            .withInitializer(context ->
            {
                try
                {
                    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
                    for (PropertySource<?> propertySource : loader.load(
                            "application-test", new ClassPathResource("application-test.yml")))
                    {
                        context.getEnvironment().getPropertySources().addLast(propertySource);
                    }
                }
                catch (java.io.IOException ex)
                {
                    throw new IllegalStateException("Failed to load application-test.yml", ex);
                }
            });

    @ParameterizedTest
    @ValueSource(strings = {
            "evidence-contract-list-expression-skill.yaml",
            "evidence-contract-object-expression-skill.yaml",
            "evidence-contract-number-expression-skill.yaml",
            "evidence-contract-boolean-expression-skill.yaml",
            "evidence-contract-null-expression-skill.yaml"
    })
    void rejectsEveryNonStringExpressionShapeWithoutCoercion(String filename)
    {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/" + filename)
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining(filename)
                        .hasMessageContaining("field 'evidence_contract.claims.vendorName'"));
    }

    @Test
    void rejectsNondirectChildAtItsExactColumn()
    {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/evidence-contract-nondirect-child-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("field 'evidence_contract.claims.likelyCause'")
                        .hasMessageContaining("column 1")
                        .hasMessageContaining("skill 'checkDns' is not a direct allowed child"));
    }

    @Test
    void rejectsReservedOperatorAsAChildReference()
    {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/evidence-contract-reserved-child-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("field 'evidence_contract.claims.result'")
                        .hasMessageContaining("column 1")
                        .hasMessageContaining("reserved operator 'and'"));
    }

    @Test
    void omitsSuggestionWhenCaseInsensitiveReferenceIsAmbiguous()
    {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/evidence-contract-ambiguous-case-child-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("skill 'INVOICEPARSER' is not a direct allowed child")
                        .hasMessageNotContaining("did you mean"));
    }

    @Test
    void requiresOutputSchemaWhenEvidenceContractIsPresent()
    {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/evidence-contract-missing-output-schema-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("field 'evidence_contract'")
                        .hasMessageContaining("requires output_schema"));
    }
}
