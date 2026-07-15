package com.lokiscale.bifrost.sample.travel;

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
class TravelSkillCatalogTests {

    private static final List<String> PUBLIC_TRAVEL_SKILLS = List.of(
            "planTrip",
            "understandPreferences",
            "planTransport",
            "planStay",
            "assembleItinerary",
            "searchFlights",
            "searchTrains",
            "searchHotels",
            "checkLoyaltyPerks",
            "rankTransportOptions");

    private static final List<String> LEAF_TARGETS = List.of(
            "travelCatalogService#searchFlights",
            "travelCatalogService#searchTrains",
            "travelCatalogService#searchHotels",
            "travelCatalogService#checkLoyaltyPerks",
            "travelCatalogService#rankTransportOptions");

    @Autowired
    private CapabilityRegistry capabilityRegistry;

    @Autowired
    private SkillImplementationTargetRegistry targetRegistry;

    @Autowired
    private YamlSkillCatalog yamlSkillCatalog;

    @Autowired
    private BifrostProperties bifrostProperties;

    @Test
    void registersTravelPublicSkillsAndKeepsTargetsInternal() {
        for (String skillName : PUBLIC_TRAVEL_SKILLS) {
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

        assertThat(capabilityRegistry.getCapability("searchFlights").skillExecution().configured()).isFalse();
        assertThat(capabilityRegistry.getCapability("rankTransportOptions").skillExecution().configured()).isFalse();
        assertThat(yamlSkillCatalog.getSkill("searchFlights").mappingTargetId())
                .isEqualTo("travelCatalogService#searchFlights");
        assertThat(yamlSkillCatalog.getSkill("rankTransportOptions").mappingTargetId())
                .isEqualTo("travelCatalogService#rankTransportOptions");
    }

    @Test
    void rootPlannerHasLockedAllowedSkillsAndMaxSteps() {
        YamlSkillDefinition root = yamlSkillCatalog.getSkill("planTrip");

        assertThat(root).isNotNull();
        assertThat(root.planningModeExplicitlyEnabled()).isTrue();
        assertThat(root.maxSteps(0)).isEqualTo(10);
        assertThat(root.allowedSkills()).containsExactly(
                "understandPreferences",
                "planTransport",
                "planStay",
                "assembleItinerary");
        assertThat(root.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(root.requireExecutionConfiguration().frameworkModel()).isEqualTo("qwen3-35b");
        assertThat(root.manifest().getModel()).isEqualTo("qwen3-35b");
    }

    @Test
    void midLevelPlannersHaveAllowListsAndNoEvidenceContract() {
        YamlSkillDefinition transport = yamlSkillCatalog.getSkill("planTransport");
        YamlSkillDefinition stay = yamlSkillCatalog.getSkill("planStay");

        assertThat(transport.planningModeExplicitlyEnabled()).isTrue();
        assertThat(stay.planningModeExplicitlyEnabled()).isTrue();
        assertThat(transport.maxSteps(0)).isEqualTo(6);
        assertThat(stay.maxSteps(0)).isEqualTo(6);
        assertThat(transport.allowedSkills()).containsExactly(
                "searchFlights", "searchTrains", "rankTransportOptions");
        assertThat(stay.allowedSkills()).containsExactly(
                "searchHotels", "checkLoyaltyPerks");
        assertThat(transport.evidenceContract().isEmpty()).isTrue();
        assertThat(stay.evidenceContract().isEmpty()).isTrue();
        assertThat(transport.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(stay.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(transport.requireExecutionConfiguration().frameworkModel()).isEqualTo("qwen3-35b");
        assertThat(stay.requireExecutionConfiguration().frameworkModel()).isEqualTo("qwen3-35b");
    }

    @Test
    void singleShotSkillsUseWorkerAliasWithoutPlanning() {
        YamlSkillDefinition understand = yamlSkillCatalog.getSkill("understandPreferences");
        YamlSkillDefinition assemble = yamlSkillCatalog.getSkill("assembleItinerary");

        assertThat(understand.planningModeExplicitlyEnabled()).isFalse();
        assertThat(assemble.planningModeExplicitlyEnabled()).isFalse();
        assertThat(understand.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(assemble.outputSchemaMaxRetries()).isEqualTo(2);
        assertThat(understand.requireExecutionConfiguration().frameworkModel()).isEqualTo("gpt-4o-mini");
        assertThat(assemble.requireExecutionConfiguration().frameworkModel()).isEqualTo("gpt-4o-mini");
        assertThat(understand.allowedSkills()).isEmpty();
        assertThat(assemble.allowedSkills()).isEmpty();
    }

    @Test
    void rootEvidenceContractMatchesLockedShape() {
        YamlSkillDefinition root = yamlSkillCatalog.getSkill("planTrip");
        var contract = root.evidenceContract();

        assertThat(contract.isEmpty()).isFalse();
        assertThat(contract.evidenceByClaim().get("summary")).containsExactly("itinerary_draft");
        assertThat(contract.evidenceByClaim().get("transport")).containsExactly("transport_digest");
        assertThat(contract.evidenceByClaim().get("hotel")).containsExactly("stay_digest");
        assertThat(contract.evidenceByClaim().get("estimatedTotal")).containsExactly("itinerary_draft");
        assertThat(contract.evidenceByClaim().get("rationale"))
                .containsExactlyInAnyOrder(
                        "trip_preferences", "transport_digest", "stay_digest", "itinerary_draft");
        assertThat(contract.evidenceByClaim().get("openQuestions"))
                .containsExactlyInAnyOrder("trip_preferences", "itinerary_draft");

        assertThat(contract.evidenceByTool().keySet()).containsExactlyInAnyOrder(
                "understandPreferences", "planTransport", "planStay", "assembleItinerary");
        assertThat(contract.evidenceByTool()).doesNotContainKeys(
                "searchFlights", "searchTrains", "searchHotels", "checkLoyaltyPerks", "rankTransportOptions");
        assertThat(contract.evidenceByTool().get("understandPreferences")).containsExactly("trip_preferences");
        assertThat(contract.evidenceByTool().get("planTransport")).containsExactly("transport_digest");
        assertThat(contract.evidenceByTool().get("planStay")).containsExactly("stay_digest");
        assertThat(contract.evidenceByTool().get("assembleItinerary")).containsExactly("itinerary_draft");
    }

    @Test
    void llmBackedTravelSkillsExposeLockedRequiredSchemaFields() {
        assertRequiredOutputFields("planTrip",
                "summary", "transport", "estimatedTotal", "rationale", "openQuestions");
        assertRequiredInputFields("planTrip", "requestText");

        assertRequiredOutputFields("understandPreferences",
                "origin", "destination", "startDate", "endDate", "partySize", "priorities", "constraints");
        assertRequiredInputFields("understandPreferences", "requestText");

        assertRequiredOutputFields("assembleItinerary",
                "summary", "transport", "estimatedTotal", "rationale", "openQuestions");
        assertRequiredInputFields("assembleItinerary",
                "requestText", "preferencesSummary", "transportSummary", "staySummary");

        assertRequiredOutputFields("planTransport",
                "domain", "summary", "mode", "toolsUsed", "findings");
        assertRequiredInputFields("planTransport", "requestText");
        assertRequiredOutputFields("planStay", "domain", "summary", "toolsUsed", "findings");
        assertRequiredInputFields("planStay", "requestText");
    }

    @Test
    void optionalTravelBudgetAndHotelFieldsAreNullable() {
        assertNullableOutputField("understandPreferences", "budgetTotal");
        assertNullableOutputField("understandPreferences", "loyaltyTier");
        assertNullableOutputField("understandPreferences", "hotelChain");
        assertNullableOutputField("planTrip", "hotel");
        assertNullableOutputField("assembleItinerary", "hotel");
        assertNullableOutputField("planTransport", "selectedReturn");
        assertNullableOutputField("planStay", "selectedHotel");
        assertNullableOutputField("planStay", "loyaltyPerks");
    }

    @Test
    void understandPreferencesExposesStructuredLoyaltyFields() {
        YamlSkillDefinition understand = yamlSkillCatalog.getSkill("understandPreferences");
        assertThat(understand.outputSchema()).isNotNull();
        assertThat(understand.outputSchema().getProperties().keySet())
                .contains("loyaltyTier", "hotelChain", "budgetTotal", "priorities");
        assertThat(understand.outputSchema().getRequired())
                .doesNotContain("loyaltyTier", "hotelChain");
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

    @Test
    void rankTransportOptionsToolContractMarksInputsOptional() {
        var target = targetRegistry.getTarget("travelCatalogService#rankTransportOptions");
        assertThat(target).isNotNull();
        assertThat(target.inputContract().schema().required()).isEmpty();
        assertThat(target.inputContract().schema().properties().keySet())
                .contains("options", "optionsJson", "sortBy");
        assertThat(target.inputSchema()).contains("options").contains("optionsJson").contains("sortBy");
    }

    @Test
    void planStayAcceptsStructuredLoyaltyFields() {
        YamlSkillDefinition stay = yamlSkillCatalog.getSkill("planStay");
        assertThat(stay.inputSchema()).isNotNull();
        assertThat(stay.inputSchema().getProperties().keySet())
                .contains("loyaltyTier", "hotelChain", "partySize", "destination");
        assertThat(stay.inputSchema().getRequired()).containsExactly("requestText");
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
