package com.lokiscale.bifrost.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BifrostPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    BifrostAutoConfiguration.class))
            .withPropertyValues("bifrost.skills.locations=classpath:/skills/none/**/*.yaml");

    @Test
    void bindsKnownUnifiedRootAndRejectsUnknownConnectionFields() {
        contextRunner
                .withPropertyValues(
                        "bifrost.session.max-depth=7",
                        "bifrost.connections.openai-main.driver=openai",
                        "bifrost.connections.openai-main.api-key=test-key",
                        "bifrost.connections.openrouter.driver=openai",
                        "bifrost.connections.openrouter.api-key=test-key-2",
                        "bifrost.models.fast.connection=openai-main",
                        "bifrost.models.fast.provider-model=gpt-fast",
                        "bifrost.models.routed.connection=openrouter",
                        "bifrost.models.routed.provider-model=gpt-routed")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    BifrostProperties properties = context.getBean(BifrostProperties.class);
                    assertThat(properties.getSession().getMaxDepth()).isEqualTo(7);
                    assertThat(properties.getConnections()).containsOnlyKeys("openai-main", "openrouter");
                    assertThat(properties.getModels()).containsOnlyKeys("fast", "routed");
                });

        contextRunner
                .withPropertyValues(
                        "bifrost.connections.primary.driver=openai",
                        "bifrost.connections.primary.api-key=test-key",
                        "bifrost.connections.primary.unknown-transport-field=value")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .rootCause()
                        .hasMessageContaining("bifrost.connections.primary.unknown-transport-field"));
    }

    @Test
    void rejectsRemovedProviderAndUnknownConnectionReferencesWithExactPaths() {
        contextRunner
                .withPropertyValues(
                        "bifrost.connections.primary.driver=openai",
                        "bifrost.connections.primary.api-key=test-key",
                        "bifrost.models.fast.provider=openai",
                        "bifrost.models.fast.connection=primary",
                        "bifrost.models.fast.provider-model=gpt-fast")
                .run(context -> assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageContaining("bifrost.models.fast.provider"));

        contextRunner
                .withPropertyValues(
                        "bifrost.models.fast.connection=missing",
                        "bifrost.models.fast.provider-model=gpt-fast")
                .run(context -> assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageContaining("bifrost.models.fast.connection")
                        .hasMessageNotContaining("test-key"));
    }

    @Test
    void validatesDriverSpecificFieldsAndRedactsSensitiveConnectionValues() {
        BifrostProperties properties = new BifrostProperties();
        BifrostProperties.ConnectionProperties connection = new BifrostProperties.ConnectionProperties();
        connection.setDriver(AiDriver.OLLAMA);
        connection.setBaseUrl("https://secret-endpoint.example");
        connection.setApiKey("secret-api-key");
        connection.setHeaders(java.util.Map.of("X-Secret", "secret-header-value"));
        properties.setConnections(java.util.Map.of("local", connection));

        assertThatThrownBy(properties::afterPropertiesSet)
                .hasMessageContaining("bifrost.connections.local.api-key")
                .hasMessageNotContaining("secret-api-key")
                .hasMessageNotContaining("secret-header-value")
                .hasMessageNotContaining("secret-endpoint.example");

        connection.setApiKey(null);
        assertThatThrownBy(properties::afterPropertiesSet)
                .hasMessageContaining("bifrost.connections.local.headers")
                .hasMessageNotContaining("secret-header-value")
                .hasMessageNotContaining("secret-endpoint.example");
        assertThat(connection.toString())
                .doesNotContain("secret-api-key", "secret-header-value", "secret-endpoint.example");
    }

    @Test
    void rejectsCommonFieldsThatTheSelectedDriverDoesNotConsume() {
        BifrostProperties.ConnectionProperties gemini = new BifrostProperties.ConnectionProperties();
        gemini.setDriver(AiDriver.GEMINI);
        gemini.setApiKey("test-key");
        gemini.setBaseUrl("https://internal-gemini.example");

        BifrostProperties geminiProperties = new BifrostProperties();
        geminiProperties.setConnections(java.util.Map.of("internal", gemini));
        assertThatThrownBy(geminiProperties::afterPropertiesSet)
                .hasMessageContaining("bifrost.connections.internal.base-url")
                .hasMessageContaining("not supported for driver GEMINI")
                .hasMessageNotContaining("internal-gemini.example");

        BifrostProperties.ConnectionProperties ollama = new BifrostProperties.ConnectionProperties();
        ollama.setDriver(AiDriver.OLLAMA);
        ollama.setBaseUrl("http://localhost:11434");
        ollama.setApiKey("ignored-secret");

        BifrostProperties ollamaProperties = new BifrostProperties();
        ollamaProperties.setConnections(java.util.Map.of("local", ollama));
        assertThatThrownBy(ollamaProperties::afterPropertiesSet)
                .hasMessageContaining("bifrost.connections.local.api-key")
                .hasMessageContaining("not supported for driver OLLAMA")
                .hasMessageNotContaining("ignored-secret");
    }

    @Test
    void validatesGeminiCredentialModesWithoutIgnoringTypedOptions() {
        BifrostProperties.ConnectionProperties mixedMode = new BifrostProperties.ConnectionProperties();
        mixedMode.setDriver(AiDriver.GEMINI);
        mixedMode.setApiKey("test-key");
        BifrostProperties.GeminiOptions vertex = new BifrostProperties.GeminiOptions();
        vertex.setVertexAi(true);
        vertex.setProjectId("project");
        vertex.setLocation("us-central1");
        mixedMode.setGemini(vertex);

        BifrostProperties mixedProperties = new BifrostProperties();
        mixedProperties.setConnections(java.util.Map.of("mixed", mixedMode));
        assertThatThrownBy(mixedProperties::afterPropertiesSet)
                .hasMessageContaining("bifrost.connections.mixed")
                .hasMessageContaining("exactly one");

        BifrostProperties.ConnectionProperties ignoredOptions = new BifrostProperties.ConnectionProperties();
        ignoredOptions.setDriver(AiDriver.GEMINI);
        ignoredOptions.setApiKey("test-key");
        ignoredOptions.setGemini(new BifrostProperties.GeminiOptions());

        BifrostProperties ignoredProperties = new BifrostProperties();
        ignoredProperties.setConnections(java.util.Map.of("api", ignoredOptions));
        assertThatThrownBy(ignoredProperties::afterPropertiesSet)
                .hasMessageContaining("bifrost.connections.api.gemini")
                .hasMessageContaining("only supported when gemini.vertex-ai=true");
    }
}
