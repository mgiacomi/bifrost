package com.lokiscale.bifrost.autoconfigure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.lokiscale.bifrost.core.BifrostSessionRunner;
import com.lokiscale.bifrost.core.CapabilityRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class BifrostAutoConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    BifrostAutoConfiguration.class));

    @Test
    void hasAutoConfigurationAnnotation() {
        assertThat(BifrostAutoConfiguration.class.isAnnotationPresent(AutoConfiguration.class)).isTrue();
    }

    @Test
    void isRegisteredInAutoConfigurationImports() throws IOException {
        try (InputStream stream = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(stream).isNotNull();
            String imports = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(imports).contains("com.lokiscale.bifrost.autoconfigure.BifrostAutoConfiguration");
        }
    }

    @Test
    void autoConfiguresSessionRunnerAndProperties() {
        contextRunner
                .withPropertyValues("bifrost.session.max-depth=5")
                .run(context -> {
                    assertThat(context).hasSingleBean(BifrostSessionRunner.class);
                    assertThat(context).hasSingleBean(BifrostSessionProperties.class);
                    assertThat(context).hasSingleBean(CapabilityRegistry.class);
                    assertThat(context.getBean(BifrostSessionProperties.class).getMaxDepth()).isEqualTo(5);
                });
    }
}
