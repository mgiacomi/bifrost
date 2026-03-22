package com.lokiscale.bifrost.core;

import com.lokiscale.bifrost.autoconfigure.BifrostAutoConfiguration;
import com.lokiscale.bifrost.autoconfigure.BifrostSessionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

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
            assertThat(properties.getMissionTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.getQuotas().getMaxSkillInvocations()).isEqualTo(64);
            assertThat(properties.getQuotas().getMaxToolInvocations()).isEqualTo(128);
            assertThat(properties.getQuotas().getMaxLinterRetries()).isEqualTo(32);
            assertThat(properties.getQuotas().getMaxModelCalls()).isEqualTo(64);
            assertThat(properties.getQuotas().getMaxUsageUnits()).isEqualTo(200_000);
        });

        contextRunner
                .withPropertyValues(
                        "bifrost.session.max-depth=3",
                        "bifrost.session.mission-timeout=5s",
                        "bifrost.session.quotas.max-skill-invocations=4",
                        "bifrost.session.quotas.max-tool-invocations=9",
                        "bifrost.session.quotas.max-linter-retries=7",
                        "bifrost.session.quotas.max-model-calls=5",
                        "bifrost.session.quotas.max-usage-units=1234")
                .run(context -> {
                    BifrostSessionProperties properties = context.getBean(BifrostSessionProperties.class);
                    BifrostSessionRunner runner = context.getBean(BifrostSessionRunner.class);

                    assertThat(properties.getMaxDepth()).isEqualTo(3);
                    assertThat(properties.getMissionTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.getQuotas().getMaxSkillInvocations()).isEqualTo(4);
                    assertThat(properties.getQuotas().getMaxToolInvocations()).isEqualTo(9);
                    assertThat(properties.getQuotas().getMaxLinterRetries()).isEqualTo(7);
                    assertThat(properties.getQuotas().getMaxModelCalls()).isEqualTo(5);
                    assertThat(properties.getQuotas().getMaxUsageUnits()).isEqualTo(1234);
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

        contextRunner
                .withPropertyValues("bifrost.session.mission-timeout=0s")
                .run(context -> {
                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasRootCauseInstanceOf(IllegalArgumentException.class);
                    assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                            .hasMessageContaining("missionTimeout must be greater than zero");
                });

        contextRunner
                .withPropertyValues("bifrost.session.quotas.max-model-calls=0")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(BifrostSessionProperties.class).getQuotas().getMaxModelCalls()).isZero();
                });

        contextRunner
                .withPropertyValues("bifrost.session.quotas.max-model-calls=-1")
                .run(context -> {
                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasRootCauseInstanceOf(BindValidationException.class);
                });
    }
}
