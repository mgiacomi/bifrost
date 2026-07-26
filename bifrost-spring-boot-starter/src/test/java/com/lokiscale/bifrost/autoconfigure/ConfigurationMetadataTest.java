package com.lokiscale.bifrost.autoconfigure;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationMetadataTest {

    @Test
    void generatedMetadataDocumentsNamedConnectionSurfaceAndDriverHints() throws Exception {
        try (InputStream stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("META-INF/spring-configuration-metadata.json")) {
            assertThat(stream).isNotNull();
            String metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(metadata)
                    .contains("bifrost.connections.*.driver")
                    .contains("bifrost.connections.*.openai.chat-completions-path")
                    .contains("bifrost.connections.*.gemini.credentials-uri")
                    .contains("bifrost.models.*.connection")
                    .contains("bifrost.models.*.thinking-levels")
                    .contains("bifrost.observability.enabled")
                    .contains("bifrost.observability.auth.api-key")
                    .contains("bifrost.observability.completion-grace-ttl")
                    .contains("bifrost.observability.trace-catalog-metadata-ttl")
                    .contains("\"value\": \"openai\"")
                    .doesNotContain("bifrost.models.*.provider\"");
        }
    }
}
