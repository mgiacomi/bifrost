package com.lokiscale.bifrost.testing;

import com.lokiscale.bifrost.sample.SampleApplication;

import com.lokiscale.bifrost.internal.skill.YamlSkillCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = SampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SampleEvidenceContractCatalogTest
{
    @Autowired
    private YamlSkillCatalog catalog;

    @Test
    void loadsAllFiveMigratedSampleEvidenceContractsThroughTheRealCatalog()
    {
        assertContract("duplicateInvoiceChecker", Map.of(
                "vendorName", "invoiceParser",
                "invoiceDate", "invoiceParser",
                "totalAmount", "invoiceParser",
                "isDuplicate", "invoiceParser and expenseLookup",
                "reasoning", "invoiceParser and expenseLookup"));
        assertContract("handleIncident", Map.of(
                "severity", "classifyIncident",
                "category", "classifyIncident",
                "likelyCause", "classifyIncident and (investigateNetwork or investigateApp)",
                "evidenceSummary", "investigateNetwork or investigateApp",
                "recommendedAction", "investigateNetwork or investigateApp",
                "userMessage", "draftIncidentResponse"));
        assertContract("processClaim", Map.of(
                "disposition", "assessCoverage and fraudScreen and recommendDisposition",
                "payableAmount", "assessCoverage",
                "coverageSummary", "assessCoverage",
                "fraudRisk", "fraudScreen",
                "matchedExclusions", "assessCoverage",
                "rationale", "extractClaimFacts and assessCoverage and fraudScreen and recommendDisposition",
                "evidenceNotes", "extractClaimFacts and assessCoverage and fraudScreen"));
        assertContract("resolveSupportCase", Map.of(
                "intents", "understandIntent",
                "disposition", "understandIntent and (handleBilling or handleTechnical or handleHowTo) and composeReply",
                "refundRecommended", "handleBilling or handleTechnical or handleHowTo",
                "factsSummary", "handleBilling or handleTechnical or handleHowTo",
                "draftReply", "composeReply",
                "internalNotes", "understandIntent and (handleBilling or handleTechnical or handleHowTo) and composeReply"));
        assertContract("planTrip", Map.of(
                "summary", "assembleItinerary",
                "transport", "planTransport",
                "hotel", "planStay",
                "estimatedTotal", "assembleItinerary",
                "rationale", "understandPreferences and planTransport and planStay and assembleItinerary",
                "openQuestions", "understandPreferences and assembleItinerary"));
    }

    private void assertContract(String skillName, Map<String, String> expected)
    {
        assertThat(catalog.getSkill(skillName)).as(skillName).isNotNull();
        assertThat(catalog.getSkill(skillName).evidenceContract().claims())
                .containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((claim, expression) -> assertThat(
                catalog.getSkill(skillName).evidenceContract().canonicalExpressionForClaim(claim))
                .as(skillName + "." + claim)
                .isEqualTo(expression));
    }
}
