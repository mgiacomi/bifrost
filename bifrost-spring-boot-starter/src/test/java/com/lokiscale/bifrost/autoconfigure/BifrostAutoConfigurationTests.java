package com.lokiscale.bifrost.autoconfigure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class BifrostAutoConfigurationTests {

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
}
