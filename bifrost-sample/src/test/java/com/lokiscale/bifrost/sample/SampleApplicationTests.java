package com.lokiscale.bifrost.sample;

import com.lokiscale.bifrost.api.SkillTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    void loadsAllMigratedRootEvidenceContracts() throws IOException {
        assertManifestContains("skills/basics/duplicate_invoice_checker.yml", "isDuplicate", "invoiceParser and expenseLookup");
        assertManifestContains("skills/incidents/handle_incident.yml", "likelyCause", "classifyIncident and (investigateNetwork or investigateApp)");
        assertManifestContains("skills/insurance/process_claim.yml", "disposition", "assessCoverage and fraudScreen and recommendDisposition");
        assertManifestContains("skills/support/resolve_support_case.yml", "disposition", "understandIntent and (handleBilling or handleTechnical or handleHowTo) and composeReply");
        assertManifestContains("skills/travel/plan_trip.yml", "rationale", "understandPreferences and planTransport and planStay and assembleItinerary");
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

    private static void assertManifestContains(String path, String property, String expression) throws IOException {
        assertThat(new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8))
                .contains("    " + property + ":")
                .contains("      evidence: " + expression);
    }
}
