package com.lokiscale.bifrost.sample;

import com.lokiscale.bifrost.api.SkillExecutionView;
import com.lokiscale.bifrost.api.SkillTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = SampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "bifrost.live-provider-test", matches = "true")
class LiveProviderSmokeTest
{
    @Autowired
    private SkillTemplate skills;

    @Autowired
    private ResourceLoader resources;

    @Test
    void invokesBundledVisionSkillThroughConfiguredOpenAiConnection()
    {
        assertThat(System.getenv("OPENAI_API_KEY"))
                .as("OPENAI_API_KEY must be set when the live provider smoke test is enabled")
                .isNotBlank();

        Resource image = resources.getResource("classpath:/forms/feedstock-p1.jpg");
        AtomicReference<SkillExecutionView> observed = new AtomicReference<>();

        String result = skills.invoke(
                "feedstockTicketParserBySkill",
                Map.of("image", image),
                observed::set);

        assertThat(result).isNotBlank();
        assertThat(observed.get()).isNotNull();
        assertThat(observed.get().sessionId()).isNotBlank();
        assertThat(observed.get().events()).isNotNull();
    }
}
