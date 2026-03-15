package com.lokiscale.bifrost.core;

import com.lokiscale.bifrost.autoconfigure.BifrostAutoConfiguration;
import com.lokiscale.bifrost.autoconfigure.BifrostSessionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class BifrostSessionPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    BifrostAutoConfiguration.class));

    @Test
    void bindsDefaultAndOverriddenSessionProperties() {
        contextRunner.run(context -> {
            BifrostSessionProperties properties = context.getBean(BifrostSessionProperties.class);
            assertThat(properties.getMaxDepth()).isEqualTo(32);
        });

        contextRunner
                .withPropertyValues("bifrost.session.max-depth=3")
                .run(context -> {
                    BifrostSessionProperties properties = context.getBean(BifrostSessionProperties.class);
                    BifrostSessionRunner runner = context.getBean(BifrostSessionRunner.class);

                    assertThat(properties.getMaxDepth()).isEqualTo(3);
                    assertThat(runner.callWithNewSession(BifrostSession::getMaxDepth)).isEqualTo(3);
                });
    }

    @Test
    void rejectsInvalidMaxDepthValues() {
        contextRunner
                .withPropertyValues("bifrost.session.max-depth=0")
                .run(context -> {
                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasRootCauseInstanceOf(BindValidationException.class);
                });
    }
}
