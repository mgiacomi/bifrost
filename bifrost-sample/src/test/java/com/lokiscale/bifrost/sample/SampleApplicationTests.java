package com.lokiscale.bifrost.sample;

import com.lokiscale.bifrost.api.SkillTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = SampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SampleApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private SkillTemplate skillTemplate;

    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void loadsSupportedSkillTemplateFacade() {
        assertThat(skillTemplate).isNotNull();
    }

    @Test
    void invokesMappedYamlSkillThroughSupportedFacade() {
        assertThat(skillTemplate.invoke("expenseLookup", Map.of()))
                .contains("Software")
                .contains("Hardware");
    }
}
