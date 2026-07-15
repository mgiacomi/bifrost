package com.lokiscale.bifrost.sample.insurance;

import com.lokiscale.bifrost.autoconfigure.BifrostProperties;
import com.lokiscale.bifrost.core.CapabilityRegistry;
import com.lokiscale.bifrost.core.SkillImplementationTargetRegistry;
import com.lokiscale.bifrost.sample.SampleApplication;
import com.lokiscale.bifrost.skill.YamlSkillCatalog;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = SampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class InsuranceSkillCatalogTests {

    private static final List<String> PUBLIC_INSURANCE_SKILLS = List.of(
            "processClaim",
            "extractClaimFacts",
            "assessCoverage",
            "fraudScreen",
            "recommendDisposition",
            "getPolicy",
            "checkExclusions",
            "estimatePayout",
            "priorClaimsLookup",
            "anomalyScore",
            "addressRiskSignals");

    private static final List<String> LEAF_TARGETS = List.of(
            "insurancePolicyService#getPolicy",
            "insurancePolicyService#checkExclusions",
            "insurancePolicyService#estimatePayout",
            "claimsHistoryService#priorClaimsLookup",
            "claimsHistoryService#anomalyScore",
            "claimsHistoryService#addressRiskSignals");

    @Autowired
    private CapabilityRegistry capabilityRegistry;

    @Autowired
    private SkillImplementationTargetRegistry targetRegistry;

    @Autowired
    private YamlSkillCatalog yamlSkillCatalog;

    @Autowired
    private BifrostProperties bifrostProperties;

    @Test
    void registersInsurancePublicSkillsAndKeepsTargetsInternal() {
        for (String skillName : PUBLIC_INSURANCE_SKILLS) {
            assertThat(capabilityRegistry.getCapability(skillName))
                    .as("public skill %s", skillName)
                    .isNotNull();
        }

        for (String targetId : LEAF_TARGETS) {
            assertThat(targetRegistry.getTarget(targetId))
                    .as("target %s", targetId)
                    .isNotNull();
            assertThat(capabilityRegistry.getCapability(targetId)).isNull();
        }

        assertThat(capabilityRegistry.getCapability("getPolicy").skillExecution().configured()).isFalse();
        assertThat(capabilityRegistry.getCapability("anomalyScore").skillExecution().configured()).isFalse();
        assertThat(yamlSkillCatalog.getSkill("getPolicy").mappingTargetId())
                .isEqualTo("insurancePolicyService#getPolicy");
        assertThat(yamlSkillCatalog.getSkill("priorClaimsLookup").mappingTargetId())
                .isEqualTo("claimsHistoryService#priorClaimsLookup");
    }

    @Test
    void rootPlannerHasLockedAllowedSkillsEvidenceAndMaxSteps() {
        YamlSkillDefinition root = yamlSkillCatalog.getSkill("processClaim");

        assertThat(root).isNotNull();
        assertThat(root.planningModeExplicitlyEnabled()).isTrue();
        assertThat(root.maxSteps(0)).isEqualTo(10);
        assertThat(root.allowedSkills()).containsExactly(
                "extractClaimFacts",
                "assessCoverage",
                "fraudScreen",
                "recommendDisposition");
        assertThat(root.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(root.requireExecutionConfiguration().frameworkModel()).isEqualTo("qwen3-35b");
        assertThat(root.manifest().getModel()).isEqualTo("qwen3-35b");
    }

    @Test
    void midLevelPlannersHaveLeafAllowListsAndNoEvidenceContract() {
        YamlSkillDefinition coverage = yamlSkillCatalog.getSkill("assessCoverage");
        YamlSkillDefinition fraud = yamlSkillCatalog.getSkill("fraudScreen");

        assertThat(coverage.planningModeExplicitlyEnabled()).isTrue();
        assertThat(fraud.planningModeExplicitlyEnabled()).isTrue();
        assertThat(coverage.maxSteps(0)).isEqualTo(6);
        assertThat(fraud.maxSteps(0)).isEqualTo(6);
        assertThat(coverage.allowedSkills()).containsExactly("getPolicy", "checkExclusions", "estimatePayout");
        assertThat(fraud.allowedSkills()).containsExactly(
                "priorClaimsLookup", "anomalyScore", "addressRiskSignals");
        assertThat(coverage.evidenceContract().isEmpty()).isTrue();
        assertThat(fraud.evidenceContract().isEmpty()).isTrue();
        assertThat(coverage.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(fraud.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(coverage.requireExecutionConfiguration().frameworkModel()).isEqualTo("qwen3-35b");
        assertThat(fraud.requireExecutionConfiguration().frameworkModel()).isEqualTo("qwen3-35b");
    }

    @Test
    void singleShotSkillsUseWorkerAliasWithoutPlanning() {
        YamlSkillDefinition extract = yamlSkillCatalog.getSkill("extractClaimFacts");
        YamlSkillDefinition recommend = yamlSkillCatalog.getSkill("recommendDisposition");

        assertThat(extract.planningModeExplicitlyEnabled()).isFalse();
        assertThat(recommend.planningModeExplicitlyEnabled()).isFalse();
        assertThat(extract.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(recommend.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(extract.requireExecutionConfiguration().frameworkModel()).isEqualTo("gpt-4o-mini");
        assertThat(recommend.requireExecutionConfiguration().frameworkModel()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void rootEvidenceContractMatchesLockedShapeWithL2Only() {
        YamlSkillDefinition root = yamlSkillCatalog.getSkill("processClaim");
        var contract = root.evidenceContract();

        assertThat(contract.isEmpty()).isFalse();
        assertThat(contract.evidenceByClaim().get("disposition"))
                .containsExactlyInAnyOrder("coverage_assessment", "fraud_assessment", "disposition_recommendation");
        assertThat(contract.evidenceByClaim().get("payableAmount")).containsExactly("coverage_assessment");
        assertThat(contract.evidenceByClaim().get("coverageSummary")).containsExactly("coverage_assessment");
        assertThat(contract.evidenceByClaim().get("fraudRisk")).containsExactly("fraud_assessment");
        assertThat(contract.evidenceByClaim().get("matchedExclusions")).containsExactly("coverage_assessment");
        assertThat(contract.evidenceByClaim().get("rationale")).containsExactlyInAnyOrder(
                "claim_facts", "coverage_assessment", "fraud_assessment", "disposition_recommendation");
        assertThat(contract.evidenceByClaim().get("evidenceNotes")).containsExactlyInAnyOrder(
                "claim_facts", "coverage_assessment", "fraud_assessment");

        assertThat(contract.evidenceByTool().keySet()).containsExactlyInAnyOrder(
                "extractClaimFacts", "assessCoverage", "fraudScreen", "recommendDisposition");
        assertThat(contract.evidenceByTool()).doesNotContainKeys(
                "getPolicy", "checkExclusions", "estimatePayout",
                "priorClaimsLookup", "anomalyScore", "addressRiskSignals");
        assertThat(contract.evidenceByTool().get("extractClaimFacts")).containsExactly("claim_facts");
        assertThat(contract.evidenceByTool().get("assessCoverage")).containsExactly("coverage_assessment");
        assertThat(contract.evidenceByTool().get("fraudScreen")).containsExactly("fraud_assessment");
        assertThat(contract.evidenceByTool().get("recommendDisposition"))
                .containsExactly("disposition_recommendation");
    }

    @Test
    void llmBackedInsuranceSkillsExposeLockedRequiredSchemaFields() {
        assertRequiredOutputFields("processClaim",
                "disposition", "coverageSummary", "fraudRisk", "matchedExclusions", "rationale", "evidenceNotes");
        assertRequiredInputFields("processClaim", "claimText");
        assertThat(yamlSkillCatalog.getSkill("processClaim").outputSchema().getProperties().keySet())
                .contains("payableAmount");

        assertRequiredOutputFields("extractClaimFacts", "lossType", "description", "parties");
        assertRequiredInputFields("extractClaimFacts", "claimText");

        assertRequiredOutputFields("assessCoverage",
                "summary", "covered", "matchedExclusions", "estimatedPayable", "policyLimit",
                "deductible", "toolsUsed", "confidence");
        assertRequiredInputFields("assessCoverage", "claimText");
        assertThat(yamlSkillCatalog.getSkill("assessCoverage").outputSchema().getProperties()
                .get("estimatedPayable").getNullable()).isTrue();
        assertThat(yamlSkillCatalog.getSkill("assessCoverage").outputSchema().getProperties()
                .get("policyLimit").getNullable()).isTrue();
        assertThat(yamlSkillCatalog.getSkill("assessCoverage").outputSchema().getProperties()
                .get("deductible").getNullable()).isTrue();

        assertRequiredOutputFields("fraudScreen",
                "summary", "fraudRisk", "anomalyScore", "priorClaimsCount", "riskSignals",
                "toolsUsed", "confidence");
        assertRequiredInputFields("fraudScreen", "claimText");
        assertThat(yamlSkillCatalog.getSkill("fraudScreen").outputSchema().getProperties()
                .get("anomalyScore").getNullable()).isTrue();
        assertThat(yamlSkillCatalog.getSkill("fraudScreen").outputSchema().getProperties()
                .get("priorClaimsCount").getNullable()).isTrue();

        assertRequiredOutputFields("recommendDisposition",
                "disposition", "coverageSummary", "fraudRisk", "matchedExclusions", "rationale", "evidenceNotes");
        assertRequiredInputFields("recommendDisposition",
                "claimText", "extractedFactsSummary", "coverageSummary", "fraudSummary");
        assertThat(yamlSkillCatalog.getSkill("recommendDisposition").outputSchema().getProperties().keySet())
                .contains("payableAmount");
    }

    @Test
    void openRouterConnectionAndPlannerWorkerAliasesAreWired() {
        BifrostProperties.ConnectionProperties openrouter = bifrostProperties.getConnections().get("openrouter");
        assertThat(openrouter).isNotNull();
        assertThat(openrouter.getDriver().name()).isEqualTo("OPENAI");
        assertThat(openrouter.getBaseUrl()).isEqualTo("https://openrouter.ai/api/v1");
        assertThat(openrouter.getApiKey()).isEqualTo("test-openrouter-api-key");

        BifrostProperties.ModelCatalogEntry planner = bifrostProperties.getModels().get("qwen3-35b");
        BifrostProperties.ModelCatalogEntry worker = bifrostProperties.getModels().get("gpt-4o-mini");
        assertThat(planner.getConnection()).isEqualTo("openrouter");
        assertThat(planner.getProviderModel()).isEqualTo("qwen/qwen3.6-35b-a3b");
        assertThat(worker.getConnection()).isEqualTo("openrouter");
        assertThat(worker.getProviderModel()).isEqualTo("openai/gpt-4o-mini");
    }

    private void assertRequiredOutputFields(String skillName, String... fields) {
        YamlSkillDefinition definition = yamlSkillCatalog.getSkill(skillName);
        assertThat(definition.outputSchema()).isNotNull();
        assertThat(definition.outputSchema().getRequired()).containsExactlyInAnyOrder(fields);
        assertThat(definition.outputSchema().getProperties().keySet()).contains(fields);
    }

    private void assertRequiredInputFields(String skillName, String... fields) {
        YamlSkillDefinition definition = yamlSkillCatalog.getSkill(skillName);
        assertThat(definition.inputSchema()).isNotNull();
        assertThat(definition.inputSchema().getRequired()).containsExactlyInAnyOrder(fields);
        assertThat(definition.inputSchema().getProperties().keySet()).contains(fields);
    }
}
