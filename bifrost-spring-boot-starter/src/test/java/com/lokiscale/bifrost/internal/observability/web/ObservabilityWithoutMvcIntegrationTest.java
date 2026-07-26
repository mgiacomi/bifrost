package com.lokiscale.bifrost.internal.observability.web;

import com.lokiscale.bifrost.internal.observability.ObservabilityActivationCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ObservabilityWithoutMvcIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "bifrost.observability.enabled=true",
                "bifrost.observability.auth.api-key=0123456789abcdef0123456789abcdef",
                "bifrost.skills.locations=classpath:/observability-test/*.yaml",
                "bifrost.connections.local.driver=ollama",
                "bifrost.connections.local.base-url=http://localhost:11434",
                "bifrost.models.test.connection=local",
                "bifrost.models.test.provider-model=test-model"
        })
class ObservabilityWithoutMvcIntegrationTest
{
    @Autowired
    ObservabilityActivationCoordinator activation;

    @Test
    void disablesCleanlyWhenServletApplicationHasNoMvcInfrastructure()
    {
        assertThat(activation.state()).isEqualTo(ObservabilityActivationCoordinator.State.DISABLED);
        assertThat(activation.runtime()).isEmpty();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            SecurityAutoConfiguration.class,
            WebMvcAutoConfiguration.class
    })
    static class TestApplication
    {
    }
}
