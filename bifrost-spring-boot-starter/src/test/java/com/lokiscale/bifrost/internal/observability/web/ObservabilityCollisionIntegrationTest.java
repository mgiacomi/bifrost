package com.lokiscale.bifrost.internal.observability.web;

import com.lokiscale.bifrost.internal.observability.ObservabilityActivationCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = ObservabilityCollisionIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "bifrost.observability.enabled=true",
                "bifrost.observability.auth.api-key=0123456789abcdef0123456789abcdef",
                "bifrost.skills.locations=classpath:/observability-no-skills/*.yaml"
        })
@AutoConfigureMockMvc
class ObservabilityCollisionIntegrationTest
{
    @Autowired
    MockMvc mvc;
    @Autowired
    ObservabilityActivationCoordinator activation;

    @Test
    void collisionLeavesHostRouteUsableAndDisablesEntireAdapter() throws Exception
    {
        assertThat(activation.state()).isEqualTo(ObservabilityActivationCoordinator.State.DISABLED);
        mvc.perform(get(ObservabilityApiPaths.INSTANCE))
                .andExpect(status().isOk())
                .andExpect(content().string("host-instance"))
                .andExpect(header().doesNotExist(ObservabilityApiKeyFilter.INSTANCE_HEADER));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = SecurityAutoConfiguration.class)
    @Import(TestApplication.HostController.class)
    static class TestApplication
    {
        @RestController
        static class HostController
        {
            @GetMapping(ObservabilityApiPaths.INSTANCE)
            String hostInstance()
            {
                return "host-instance";
            }
        }
    }
}
