package com.lokiscale.bifrost.sample.support;

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
class SupportSkillCatalogTests {

    private static final List<String> PUBLIC_SUPPORT_SKILLS = List.of(
            "resolveSupportCase",
            "understandIntent",
            "handleBilling",
            "handleTechnical",
            "handleHowTo",
            "composeReply",
            "checkRefundPolicy",
            "lookupCustomer",
            "lookupInvoices",
            "lookupRefundPolicy",
            "lookupAccountStatus",
            "searchKnownIssues",
            "createBugTicket",
            "searchHelpCenter");

    private static final List<String> LEAF_TARGETS = List.of(
            "supportCrmService#lookupCustomer",
            "supportCrmService#lookupInvoices",
            "supportCrmService#lookupRefundPolicy",
            "supportCrmService#lookupAccountStatus",
            "supportCrmService#searchKnownIssues",
            "supportCrmService#createBugTicket",
            "supportCrmService#searchHelpCenter");

    @Autowired
    private CapabilityRegistry capabilityRegistry;

    @Autowired
    private SkillImplementationTargetRegistry targetRegistry;

    @Autowired
    private YamlSkillCatalog yamlSkillCatalog;

    @Autowired
    private BifrostProperties bifrostProperties;

    @Test
    void registersSupportPublicSkillsAndKeepsTargetsInternal() {
        for (String skillName : PUBLIC_SUPPORT_SKILLS) {
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

        assertThat(capabilityRegistry.getCapability("lookupCustomer").skillExecution().configured()).isFalse();
        assertThat(capabilityRegistry.getCapability("createBugTicket").skillExecution().configured()).isFalse();
        assertThat(yamlSkillCatalog.getSkill("lookupCustomer").mappingTargetId())
                .isEqualTo("supportCrmService#lookupCustomer");
        assertThat(yamlSkillCatalog.getSkill("lookupRefundPolicy").mappingTargetId())
                .isEqualTo("supportCrmService#lookupRefundPolicy");
    }

    @Test
    void rootPlannerHasLockedAllowedSkillsAndMaxSteps() {
        YamlSkillDefinition root = yamlSkillCatalog.getSkill("resolveSupportCase");

        assertThat(root).isNotNull();
        assertThat(root.planningModeExplicitlyEnabled()).isTrue();
        assertThat(root.maxSteps(0)).isEqualTo(10);
        assertThat(root.allowedSkills()).containsExactly(
                "understandIntent",
                "handleBilling",
                "handleTechnical",
                "handleHowTo",
                "composeReply");
        assertThat(root.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(root.requireExecutionConfiguration().frameworkModel()).isEqualTo("qwen3-35b");
        assertThat(root.manifest().getModel()).isEqualTo("qwen3-35b");
    }

    @Test
    void midLevelPlannersHaveAllowListsAndNoEvidenceContract() {
        YamlSkillDefinition billing = yamlSkillCatalog.getSkill("handleBilling");
        YamlSkillDefinition technical = yamlSkillCatalog.getSkill("handleTechnical");
        YamlSkillDefinition howTo = yamlSkillCatalog.getSkill("handleHowTo");

        assertThat(billing.planningModeExplicitlyEnabled()).isTrue();
        assertThat(technical.planningModeExplicitlyEnabled()).isTrue();
        assertThat(howTo.planningModeExplicitlyEnabled()).isTrue();
        assertThat(billing.maxSteps(0)).isEqualTo(6);
        assertThat(technical.maxSteps(0)).isEqualTo(6);
        assertThat(howTo.maxSteps(0)).isEqualTo(4);
        assertThat(billing.allowedSkills()).containsExactly(
                "lookupCustomer", "lookupInvoices", "lookupRefundPolicy", "checkRefundPolicy");
        assertThat(technical.allowedSkills()).containsExactly(
                "lookupAccountStatus", "searchKnownIssues", "createBugTicket");
        assertThat(howTo.allowedSkills()).containsExactly("searchHelpCenter");
        assertThat(billing.evidenceContract().isEmpty()).isTrue();
        assertThat(technical.evidenceContract().isEmpty()).isTrue();
        assertThat(howTo.evidenceContract().isEmpty()).isTrue();
        assertThat(billing.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(technical.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(howTo.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(billing.requireExecutionConfiguration().frameworkModel()).isEqualTo("qwen3-35b");
        assertThat(technical.requireExecutionConfiguration().frameworkModel()).isEqualTo("qwen3-35b");
        assertThat(howTo.requireExecutionConfiguration().frameworkModel()).isEqualTo("qwen3-35b");
    }

    @Test
    void singleShotSkillsUseWorkerAliasWithoutPlanning() {
        YamlSkillDefinition understand = yamlSkillCatalog.getSkill("understandIntent");
        YamlSkillDefinition compose = yamlSkillCatalog.getSkill("composeReply");
        YamlSkillDefinition checkRefund = yamlSkillCatalog.getSkill("checkRefundPolicy");

        assertThat(understand.planningModeExplicitlyEnabled()).isFalse();
        assertThat(compose.planningModeExplicitlyEnabled()).isFalse();
        assertThat(checkRefund.planningModeExplicitlyEnabled()).isFalse();
        assertThat(understand.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(compose.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(checkRefund.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(understand.requireExecutionConfiguration().frameworkModel()).isEqualTo("gpt-4o-mini");
        assertThat(compose.requireExecutionConfiguration().frameworkModel()).isEqualTo("gpt-4o-mini");
        assertThat(checkRefund.requireExecutionConfiguration().frameworkModel()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void rootEvidenceContractMatchesLockedShape() {
        YamlSkillDefinition root = yamlSkillCatalog.getSkill("resolveSupportCase");
        var contract = root.evidenceContract();

        assertThat(contract.isEmpty()).isFalse();
        assertThat(contract.evidenceByClaim().get("intents")).containsExactly("intent_classification");
        assertThat(contract.evidenceByClaim().get("disposition"))
                .containsExactlyInAnyOrder("intent_classification", "case_facts", "response_draft");
        assertThat(contract.evidenceByClaim().get("refundRecommended")).containsExactly("case_facts");
        assertThat(contract.evidenceByClaim().get("factsSummary")).containsExactly("case_facts");
        assertThat(contract.evidenceByClaim().get("draftReply")).containsExactly("response_draft");
        assertThat(contract.evidenceByClaim().get("internalNotes"))
                .containsExactlyInAnyOrder("intent_classification", "case_facts", "response_draft");

        assertThat(contract.evidenceByTool().keySet()).containsExactlyInAnyOrder(
                "understandIntent", "handleBilling", "handleTechnical", "handleHowTo", "composeReply");
        assertThat(contract.evidenceByTool()).doesNotContainKeys(
                "lookupCustomer", "lookupInvoices", "lookupRefundPolicy", "lookupAccountStatus",
                "searchKnownIssues", "createBugTicket", "searchHelpCenter", "checkRefundPolicy");
        assertThat(contract.evidenceByTool().get("understandIntent")).containsExactly("intent_classification");
        assertThat(contract.evidenceByTool().get("handleBilling")).containsExactly("case_facts");
        assertThat(contract.evidenceByTool().get("handleTechnical")).containsExactly("case_facts");
        assertThat(contract.evidenceByTool().get("handleHowTo")).containsExactly("case_facts");
        assertThat(contract.evidenceByTool().get("composeReply")).containsExactly("response_draft");
    }

    @Test
    void llmBackedSupportSkillsExposeLockedRequiredSchemaFields() {
        assertRequiredOutputFields("resolveSupportCase",
                "intents", "disposition", "refundRecommended", "factsSummary", "draftReply", "internalNotes");
        assertRequiredInputFields("resolveSupportCase", "emailText");

        assertRequiredOutputFields("understandIntent", "intents", "sentiment", "entities", "summary");
        assertRequiredInputFields("understandIntent", "emailText");

        assertRequiredOutputFields("composeReply", "draftReply", "internalNotes");
        assertRequiredInputFields("composeReply", "emailText", "factsSummary");

        assertRequiredOutputFields("checkRefundPolicy", "refundRecommended", "rationale", "policyNotes");
        assertRequiredInputFields("checkRefundPolicy", "emailText", "policyFactsSummary");

        assertRequiredOutputFields("handleBilling",
                "domain", "summary", "findings", "toolsUsed", "refundRecommended");
        assertRequiredInputFields("handleBilling", "emailText");
        assertRequiredOutputFields("handleTechnical", "domain", "summary", "findings", "toolsUsed");
        assertRequiredInputFields("handleTechnical", "emailText");
        assertRequiredOutputFields("handleHowTo", "domain", "summary", "findings", "toolsUsed");
        assertRequiredInputFields("handleHowTo", "emailText");
    }

    @Test
    void optionalSupportAmountAndTicketFieldsAreNullable() {
        assertNullableOutputField("resolveSupportCase", "refundAmount");
        assertNullableOutputField("resolveSupportCase", "bugTicketId");
        assertNullableOutputField("handleBilling", "refundAmount");
        assertNullableOutputField("checkRefundPolicy", "refundAmount");
        assertNullableOutputField("handleTechnical", "bugTicketId");

        YamlSkillDefinition understand = yamlSkillCatalog.getSkill("understandIntent");
        assertThat(understand.outputSchema().getProperties().get("entities").getProperties())
                .containsKeys("orderId", "amount", "product", "customerId");
        for (String field : List.of("orderId", "amount", "product", "customerId")) {
            assertThat(understand.outputSchema().getProperties().get("entities").getProperties().get(field).getNullable())
                    .as("nullable entities.%s on understandIntent", field)
                    .isTrue();
        }
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

    private void assertNullableOutputField(String skillName, String field) {
        YamlSkillDefinition definition = yamlSkillCatalog.getSkill(skillName);
        assertThat(definition.outputSchema()).isNotNull();
        assertThat(definition.outputSchema().getProperties())
                .as("output field %s on %s", field, skillName)
                .containsKey(field);
        assertThat(definition.outputSchema().getProperties().get(field).getNullable())
                .as("nullable %s on %s", field, skillName)
                .isTrue();
    }
}
