package com.lokiscale.loomspan.internal.observability.web;

import com.lokiscale.loomspan.internal.observability.ObservabilityActivationCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = ObservabilityInvalidActivationIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "loomspan.observability.enabled=true",
                "loomspan.observability.auth.api-key=too-short",
                "loomspan.skills.locations=classpath:/observability-test/*.yaml",
                "loomspan.connections.local.driver=ollama",
                "loomspan.connections.local.base-url=http://localhost:11434",
                "loomspan.models.test.connection=local",
                "loomspan.models.test.provider-model=test-model"
        })
@AutoConfigureMockMvc
class ObservabilityInvalidActivationIntegrationTest
{
    @Autowired
    MockMvc mvc;

    @Autowired
    ObservabilityActivationCoordinator activation;

    @Test
    void semanticInvalidityDisablesWithoutPartialRoutes() throws Exception
    {
        assertThat(activation.state()).isEqualTo(ObservabilityActivationCoordinator.State.DISABLED);
        assertThat(activation.runtime()).isEmpty();
        mvc.perform(get(ObservabilityApiPaths.INSTANCE))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(ObservabilityApiKeyFilter.INSTANCE_HEADER));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = SecurityAutoConfiguration.class)
    static class TestApplication
    {
    }
}
