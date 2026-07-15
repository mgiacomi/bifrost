package com.lokiscale.bifrost.sample.incident;

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
class IncidentSkillCatalogTests {

    private static final List<String> PUBLIC_INCIDENT_SKILLS = List.of(
            "handleIncident",
            "classifyIncident",
            "investigateNetwork",
            "investigateApp",
            "draftIncidentResponse",
            "checkDns",
            "checkLatency",
            "checkFirewallRules",
            "getErrorRate",
            "getRecentDeploys",
            "getServiceHealth",
            "lookupRunbook");

    private static final List<String> LEAF_TARGETS = List.of(
            "incidentTelemetryService#checkDns",
            "incidentTelemetryService#checkLatency",
            "incidentTelemetryService#checkFirewallRules",
            "incidentTelemetryService#getErrorRate",
            "incidentTelemetryService#getRecentDeploys",
            "incidentTelemetryService#getServiceHealth",
            "incidentTelemetryService#lookupRunbook");

    @Autowired
    private CapabilityRegistry capabilityRegistry;

    @Autowired
    private SkillImplementationTargetRegistry targetRegistry;

    @Autowired
    private YamlSkillCatalog yamlSkillCatalog;

    @Autowired
    private BifrostProperties bifrostProperties;

    @Test
    void registersIncidentPublicSkillsAndKeepsTargetsInternal() {
        for (String skillName : PUBLIC_INCIDENT_SKILLS) {
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

        assertThat(capabilityRegistry.getCapability("checkDns")).isNotNull();
        assertThat(capabilityRegistry.getCapability("checkDns").skillExecution().configured()).isFalse();
        assertThat(capabilityRegistry.getCapability("getErrorRate").skillExecution().configured()).isFalse();
        assertThat(capabilityRegistry.getCapability("lookupRunbook").skillExecution().configured()).isFalse();

        assertThat(capabilityRegistry.getCapability("checkDns")).isNotNull();
        assertThat(yamlSkillCatalog.getSkill("checkDns").mappingTargetId())
                .isEqualTo("incidentTelemetryService#checkDns");
    }

    @Test
    void rootPlannerHasLockedAllowedSkillsAndMaxSteps() {
        YamlSkillDefinition root = yamlSkillCatalog.getSkill("handleIncident");

        assertThat(root).isNotNull();
        assertThat(root.planningModeExplicitlyEnabled()).isTrue();
        assertThat(root.maxSteps(0)).isEqualTo(10);
        assertThat(root.allowedSkills()).containsExactly(
                "classifyIncident",
                "investigateNetwork",
                "investigateApp",
                "draftIncidentResponse",
                "lookupRunbook");
        assertThat(root.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(root.requireExecutionConfiguration().frameworkModel()).isEqualTo("qwen3-35b");
        assertThat(root.manifest().getModel()).isEqualTo("qwen3-35b");
    }

    @Test
    void midLevelPlannersHaveProbeAllowListsAndNoEvidenceContract() {
        YamlSkillDefinition network = yamlSkillCatalog.getSkill("investigateNetwork");
        YamlSkillDefinition app = yamlSkillCatalog.getSkill("investigateApp");

        assertThat(network.planningModeExplicitlyEnabled()).isTrue();
        assertThat(app.planningModeExplicitlyEnabled()).isTrue();
        assertThat(network.maxSteps(0)).isEqualTo(6);
        assertThat(app.maxSteps(0)).isEqualTo(6);
        assertThat(network.allowedSkills()).containsExactly("checkDns", "checkLatency", "checkFirewallRules");
        assertThat(app.allowedSkills()).containsExactly("getErrorRate", "getRecentDeploys", "getServiceHealth");
        assertThat(network.evidenceContract().isEmpty()).isTrue();
        assertThat(app.evidenceContract().isEmpty()).isTrue();
        assertThat(network.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(app.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(network.requireExecutionConfiguration().frameworkModel()).isEqualTo("qwen3-35b");
        assertThat(app.requireExecutionConfiguration().frameworkModel()).isEqualTo("qwen3-35b");
    }

    @Test
    void singleShotSkillsUseWorkerAliasWithoutPlanning() {
        YamlSkillDefinition classify = yamlSkillCatalog.getSkill("classifyIncident");
        YamlSkillDefinition draft = yamlSkillCatalog.getSkill("draftIncidentResponse");

        assertThat(classify.planningModeExplicitlyEnabled()).isFalse();
        assertThat(draft.planningModeExplicitlyEnabled()).isFalse();
        assertThat(classify.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(draft.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(classify.requireExecutionConfiguration().frameworkModel()).isEqualTo("gpt-4o-mini");
        assertThat(draft.requireExecutionConfiguration().frameworkModel()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void rootEvidenceContractMatchesLockedShape() {
        YamlSkillDefinition root = yamlSkillCatalog.getSkill("handleIncident");
        var contract = root.evidenceContract();

        assertThat(contract.isEmpty()).isFalse();
        assertThat(contract.evidenceByClaim().get("severity")).containsExactly("incident_classification");
        assertThat(contract.evidenceByClaim().get("category")).containsExactly("incident_classification");
        assertThat(contract.evidenceByClaim().get("likelyCause"))
                .containsExactlyInAnyOrder("incident_classification", "investigation_digest");
        assertThat(contract.evidenceByClaim().get("evidenceSummary")).containsExactly("investigation_digest");
        assertThat(contract.evidenceByClaim().get("recommendedAction")).containsExactly("investigation_digest");
        assertThat(contract.evidenceByClaim().get("userMessage")).containsExactly("response_draft");

        assertThat(contract.evidenceByTool().keySet()).containsExactlyInAnyOrder(
                "classifyIncident", "investigateNetwork", "investigateApp", "draftIncidentResponse");
        assertThat(contract.evidenceByTool()).doesNotContainKeys(
                "checkDns", "checkLatency", "checkFirewallRules",
                "getErrorRate", "getRecentDeploys", "getServiceHealth", "lookupRunbook");
        assertThat(contract.evidenceByTool().get("classifyIncident")).containsExactly("incident_classification");
        assertThat(contract.evidenceByTool().get("investigateNetwork")).containsExactly("investigation_digest");
        assertThat(contract.evidenceByTool().get("investigateApp")).containsExactly("investigation_digest");
        assertThat(contract.evidenceByTool().get("draftIncidentResponse")).containsExactly("response_draft");
    }

    @Test
    void llmBackedIncidentSkillsExposeLockedRequiredSchemaFields() {
        assertRequiredOutputFields("handleIncident",
                "severity", "category", "likelyCause", "evidenceSummary", "recommendedAction", "userMessage");
        assertRequiredInputFields("handleIncident", "ticketText");

        assertRequiredOutputFields("classifyIncident", "severity", "category", "rationale");
        assertRequiredInputFields("classifyIncident", "ticketText");

        assertRequiredOutputFields("investigateNetwork",
                "domain", "summary", "findings", "probesUsed", "confidence");
        assertRequiredInputFields("investigateNetwork", "ticketText");
        assertRequiredOutputFields("investigateApp",
                "domain", "summary", "findings", "probesUsed", "confidence");
        assertRequiredInputFields("investigateApp", "ticketText");

        assertRequiredOutputFields("draftIncidentResponse",
                "summary", "likelyCause", "recommendedAction", "userMessage");
        assertRequiredInputFields("draftIncidentResponse", "ticketText", "investigationSummary");
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
