package com.lokiscale.bifrost.runtime.evidence;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.lokiscale.bifrost.skill.YamlSkillManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceContractTests {

    @Test
    void normalizesClaimsToolsAndPresentClaimLookups() throws Exception {
        YamlSkillManifest.OutputSchemaManifest schema = new YamlSkillManifest.OutputSchemaManifest();
        schema.setType("object");
        schema.setProperties(Map.of(
                "vendorName", scalar("string"),
                "isDuplicate", scalar("boolean")));

        YamlSkillManifest.EvidenceContractManifest manifest = new YamlSkillManifest.EvidenceContractManifest();
        manifest.setClaims(Map.of(
                "vendorName", List.of("parsed_invoice"),
                "isDuplicate", List.of("parsed_invoice", "expense_match_search")));
        manifest.setToolEvidence(Map.of(
                "invoiceParser", List.of("parsed_invoice"),
                "expenseLookup", List.of("expense_match_search")));

        EvidenceContract contract = EvidenceContract.fromManifest(manifest, schema);
        EvidenceBackedOutputValidator validator = new EvidenceBackedOutputValidator();

        assertThat(contract.evidenceForClaim("VENDORNAME")).containsExactly("parsed_invoice");
        assertThat(contract.evidenceProducedByTool("expenselookup")).containsExactly("expense_match_search");
        assertThat(contract.requiredEvidenceForClaims(Set.of("vendorName", "isDuplicate")))
                .containsExactlyInAnyOrder("parsed_invoice", "expense_match_search");

        EvidenceCoverageResult result = validator.validate(
                JsonMapper.builder().findAndAddModules().build().readTree("""
                        {"vendorName":"Acme","isDuplicate":false}
                        """),
                contract,
                Set.of("parsed_invoice"));
        assertThat(result.complete()).isFalse();
        assertThat(result.issues()).singleElement()
                .extracting(EvidenceCoverageIssue::claimName)
                .isEqualTo("isDuplicate");
    }

    private static YamlSkillManifest.OutputSchemaManifest scalar(String type) {
        YamlSkillManifest.OutputSchemaManifest manifest = new YamlSkillManifest.OutputSchemaManifest();
        manifest.setType(type);
        return manifest;
    }
}
