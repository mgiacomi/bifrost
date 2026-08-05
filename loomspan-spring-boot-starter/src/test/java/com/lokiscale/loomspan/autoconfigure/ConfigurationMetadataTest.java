package com.lokiscale.loomspan.autoconfigure;

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
                    .contains("loomspan.connections.*.driver")
                    .contains("loomspan.connections.*.openai.chat-completions-path")
                    .contains("loomspan.connections.*.gemini.credentials-uri")
                    .contains("loomspan.models.*.connection")
                    .contains("loomspan.models.*.thinking-levels")
                    .contains("loomspan.observability.enabled")
                    .contains("loomspan.observability.auth.api-key")
                    .contains("loomspan.observability.completion-grace-ttl")
                    .contains("loomspan.observability.trace-catalog-metadata-ttl")
                    .contains("\"value\": \"openai\"")
                    .doesNotContain("loomspan.models.*.provider\"");
        }
    }
}
