package com.lokiscale.bifrost.internal.runtime.tool;

import com.lokiscale.bifrost.autoconfigure.AiDriver;
import com.lokiscale.bifrost.internal.core.BifrostSession;
import com.lokiscale.bifrost.internal.core.CapabilityExecutionRouter;
import com.lokiscale.bifrost.internal.core.CapabilityKind;
import com.lokiscale.bifrost.internal.core.CapabilityMetadata;
import com.lokiscale.bifrost.internal.core.CapabilityToolDescriptor;
import com.lokiscale.bifrost.internal.core.ExecutionCoordinator;
import com.lokiscale.bifrost.internal.core.ExecutionFrame;
import com.lokiscale.bifrost.internal.core.SkillExecutionDescriptor;
import com.lokiscale.bifrost.internal.runtime.evidence.EvidenceContract;
import com.lokiscale.bifrost.internal.runtime.evidence.EvidenceCoverageValidator;
import com.lokiscale.bifrost.internal.runtime.planning.PlanningService;
import com.lokiscale.bifrost.internal.runtime.state.DefaultExecutionStateService;
import com.lokiscale.bifrost.internal.skill.EffectiveSkillExecutionConfiguration;
import com.lokiscale.bifrost.internal.skill.YamlSkillDefinition;
import com.lokiscale.bifrost.internal.skill.YamlSkillManifest;
import com.lokiscale.bifrost.internal.security.DefaultAccessGuard;
import com.lokiscale.bifrost.internal.vfs.RefResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.ai.tool.ToolCallback;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NestedSuccessfulSkillBoundaryTest
{
    @Test
    void successfulNestedCallCreditsOnlyItsPublicBoundaryName()
    {
        Harness harness = harness(false);

        harness.callback.call("{}");

        assertThat(harness.session.getSuccessfulDirectSkills())
                .containsExactly("classifyIncident", "investigateNetwork")
                .doesNotContain("checkDns");
        EvidenceContract parentContract = parentContract();
        assertThat(new EvidenceCoverageValidator().validateEvidenceForClaims(
                Set.of("likelyCause"), harness.session.getSuccessfulDirectSkills(), parentContract).complete()).isTrue();
        assertThat(new EvidenceCoverageValidator().validateEvidenceForClaims(
                Set.of("internalProbe"), harness.session.getSuccessfulDirectSkills(), parentContract).complete()).isFalse();
    }

    @Test
    void failedNestedCallRestoresParentAndCreditsNeitherBoundaryNorInternals()
    {
        Harness harness = harness(true);

        assertThatThrownBy(() -> harness.callback.call("{}"))
                .isInstanceOf(org.springframework.ai.tool.execution.ToolExecutionException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("nested failure");
        assertThat(harness.session.getSuccessfulDirectSkills())
                .containsExactly("classifyIncident")
                .doesNotContain("investigateNetwork", "checkDns");
    }

    private static Harness harness(boolean fail)
    {
        DefaultExecutionStateService state = new DefaultExecutionStateService(Clock.fixed(
                Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC));
        BifrostSession session = com.lokiscale.bifrost.internal.core.TestBifrostSessions.withId("nested", 3);
        ExecutionFrame parent = state.openMissionFrame(session, "handleIncident", Map.of());
        state.recordSuccessfulSkill(session, "classifyIncident", "task-classify", false);

        ExecutionCoordinator coordinator = mock(ExecutionCoordinator.class);
        when(coordinator.execute(eq("investigateNetwork"), any(), any(), eq(session), eq(null)))
                .thenAnswer(invocation ->
                {
                    state.clearSuccessfulSkills(session);
                    assertThat(session.getSuccessfulDirectSkills()).isEmpty();
                    state.recordSuccessfulSkill(session, "checkDns", "task-dns", false);
                    if (fail)
                    {
                        throw new IllegalStateException("nested failure");
                    }
                    return "network result";
                });
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("executionCoordinator", coordinator));
        RefResolver refs = (value, ignored) -> value;
        CapabilityExecutionRouter router = new CapabilityExecutionRouter(
                refs, beans.getBeanProvider(ExecutionCoordinator.class), state, new DefaultAccessGuard());
        PlanningService planning = mock(PlanningService.class);
        CapabilityMetadata capability = capability();
        when(planning.markToolStarted(eq(session), eq(capability), any())).thenReturn(Optional.empty());
        ToolCallback callback = new DefaultToolCallbackFactory(router, planning, state)
                .createToolCallbacks(session, definition(), List.of(capability), null)
                .getFirst();
        return new Harness(session, parent, callback);
    }

    private static CapabilityMetadata capability()
    {
        return new CapabilityMetadata(
                "yaml:investigateNetwork",
                "investigateNetwork",
                "Investigate network",
                SkillExecutionDescriptor.from(new EffectiveSkillExecutionConfiguration(
                        "gpt-5", "test-connection", AiDriver.OPENAI, "openai/gpt-5", "medium")),
                Set.of(),
                arguments -> "unused",
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("investigateNetwork", "Investigate network"),
                null);
    }

    private static YamlSkillDefinition definition()
    {
        YamlSkillManifest manifest = new YamlSkillManifest();
        manifest.setName("handleIncident");
        manifest.setDescription("Handle incident");
        manifest.setModel("gpt-5");
        return new YamlSkillDefinition(
                new org.springframework.core.io.ByteArrayResource(new byte[0]),
                manifest,
                new EffectiveSkillExecutionConfiguration(
                        "gpt-5", "test-connection", AiDriver.OPENAI, "openai/gpt-5", "medium"));
    }

    private static EvidenceContract parentContract()
    {
        YamlSkillManifest.OutputSchemaManifest schema = new YamlSkillManifest.OutputSchemaManifest();
        schema.setType("object");
        YamlSkillManifest.OutputSchemaManifest value = new YamlSkillManifest.OutputSchemaManifest();
        value.setType("string");
        schema.setProperties(Map.of("likelyCause", value, "internalProbe", value));
        return com.lokiscale.bifrost.internal.runtime.evidence.TestEvidenceContracts.compiled(Map.of(
                "likelyCause", "classifyIncident and investigateNetwork",
                "internalProbe", "checkDns"));
    }

    private record Harness(BifrostSession session, ExecutionFrame parent, ToolCallback callback) { }
}
