package com.lokiscale.bifrost.sample;

import com.lokiscale.bifrost.autoconfigure.BifrostAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = SampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SampleApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void loadsBifrostAutoConfiguration() {
        assertThat(applicationContext.containsBeanDefinition(BifrostAutoConfiguration.class.getName())).isTrue();
    }
}
