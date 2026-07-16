package com.lokiscale.bifrost.integration;

import com.lokiscale.bifrost.api.SkillExecutionView;
import com.lokiscale.bifrost.api.SkillTemplate;
import com.lokiscale.bifrost.autoconfigure.BifrostAutoConfiguration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SupportedSurfaceIntegrationTest
{
    @Test
    void invokesLlmBackedYamlSkillThroughSupportedSurfaceAndStandardConnectionConfiguration() throws Exception
    {
        try (MockWebServer server = new MockWebServer())
        {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"id":"chatcmpl-supported-surface","object":"chat.completion","created":1,
                             "model":"integration-model",
                             "choices":[{"index":0,"message":{"role":"assistant","content":"supported surface response"},
                                         "finish_reason":"stop"}],
                             "usage":{"prompt_tokens":3,"completion_tokens":3,"total_tokens":6}}
                            """));

            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            ConfigurationPropertiesAutoConfiguration.class,
                            BifrostAutoConfiguration.class))
                    .withPropertyValues(
                            "bifrost.skills.locations=classpath:/skills/integration/supported-surface-skill.yml",
                            "bifrost.connections.integration.driver=openai",
                            "bifrost.connections.integration.base-url=" + server.url("/v1"),
                            "bifrost.connections.integration.api-key=integration-key",
                            "bifrost.models.integration.connection=integration",
                            "bifrost.models.integration.provider-model=integration-model",
                            "bifrost.session.mission-timeout=5s")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(SkillTemplate.class);

                        SkillTemplate skills = context.getBean(SkillTemplate.class);
                        AtomicReference<SkillExecutionView> observed = new AtomicReference<>();

                        assertThat(skills.invoke(
                                "supportedSurfaceSkill",
                                Map.of("message", "hello through the public API"),
                                observed::set))
                                .isEqualTo("supported surface response");

                        assertThat(observed.get()).isNotNull();
                        assertThat(observed.get().sessionId()).isNotBlank();
                        assertThat(observed.get().events()).isNotNull();
                    });

            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getPath()).isEqualTo("/v1/chat/completions");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer integration-key");
            assertThat(request.getBody().readUtf8())
                    .contains("\"model\":\"integration-model\"")
                    .contains("hello through the public API");
        }
    }
}
