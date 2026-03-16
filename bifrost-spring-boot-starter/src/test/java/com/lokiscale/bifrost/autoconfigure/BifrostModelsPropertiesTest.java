package com.lokiscale.bifrost.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class BifrostModelsPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    BifrostAutoConfiguration.class))
            .withPropertyValues("bifrost.skills.locations=classpath:/skills/none/**/*.yaml");

    @Test
    void rejectsInvalidModelCatalogEntriesAtStartup() {
        contextRunner
                .withPropertyValues(
                        "bifrost.models.invalid.provider-model=",
                        "bifrost.models.invalid.provider=openai")
                .run(context -> {
                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasRootCauseInstanceOf(BindValidationException.class);
                });

        contextRunner
                .withPropertyValues("bifrost.models.invalid.provider-model=openai/gpt-5")
                .run(context -> {
                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasRootCauseInstanceOf(BindValidationException.class);
                });
    }
}
