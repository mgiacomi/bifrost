package com.lokiscale.bifrost.sample;

import com.lokiscale.bifrost.autoconfigure.BifrostAutoConfiguration;
import com.lokiscale.bifrost.core.CapabilityRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = SampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SampleApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private CapabilityRegistry capabilityRegistry;

    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void loadsBifrostAutoConfiguration() {
        assertThat(applicationContext.containsBeanDefinition(BifrostAutoConfiguration.class.getName())).isTrue();
    }

    @Test
    void registersSkillsTocapabilityRegistry() {
        System.out.println("BEANS: " + java.util.Arrays.toString(applicationContext.getBeanNamesForType(com.lokiscale.bifrost.sample.ExpenseService.class)));
        System.out.println("CAPABILITIES: " + capabilityRegistry.getAllCapabilities().stream().map(com.lokiscale.bifrost.core.CapabilityMetadata::name).toList());
        assertThat(capabilityRegistry.getCapability("getLatestExpenses")).isNotNull();
        assertThat(capabilityRegistry.getCapability("invoiceParser")).isNotNull();
    }
}
