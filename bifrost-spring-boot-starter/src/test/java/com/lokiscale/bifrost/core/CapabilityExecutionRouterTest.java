package com.lokiscale.bifrost.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lokiscale.bifrost.runtime.input.SkillInputContractResolver;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import com.lokiscale.bifrost.runtime.state.PlanSnapshot;
import com.lokiscale.bifrost.security.DefaultAccessGuard;
import com.lokiscale.bifrost.vfs.RefResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityExecutionRouterTest {

    @Test
    void restoresParentPlanViaStateService() {
        RefResolver refResolver = mock(RefResolver.class);
        ExecutionStateService stateService = mock(ExecutionStateService.class);
        ExecutionCoordinator coordinator = mock(ExecutionCoordinator.class);
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory(Map.of("executionCoordinator", coordinator));
        CapabilityExecutionRouter router = new CapabilityExecutionRouter(
                refResolver,
                beanFactory.getBeanProvider(ExecutionCoordinator.class),
                new ObjectMapper(),
                stateService,
                new DefaultAccessGuard());
        BifrostSession session = new BifrostSession("session-1", 2);
        CapabilityMetadata capability = new CapabilityMetadata(
                "yaml:child",
                "child.llm.skill",
                "child",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(new com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration(
                        "gpt-5",
                        com.lokiscale.bifrost.autoconfigure.AiProvider.OPENAI,
                        "openai/gpt-5",
                        "medium")),
                java.util.Set.of(),
                arguments -> "unused",
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("child.llm.skill", "child"),
                null);
        PlanSnapshot snapshot = PlanSnapshot.of(null);
        com.lokiscale.bifrost.runtime.state.EvidenceSnapshot evidenceSnapshot = com.lokiscale.bifrost.runtime.state.EvidenceSnapshot.of(java.util.Set.of("parsed_invoice"));

        when(stateService.snapshotPlan(session)).thenReturn(snapshot);
        when(stateService.snapshotEvidence(session)).thenReturn(evidenceSnapshot);
        when(coordinator.execute(eq("child.llm.skill"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap(), eq(session), eq(null)))
                .thenReturn("child result");

        Object result = router.execute(capability, Map.of("topic", "mars"), session, null);

        assertThat(result).isEqualTo("child result");
        verify(stateService).restorePlan(session, snapshot);
        verify(stateService).restoreEvidence(session, evidenceSnapshot);
    }

    @Test
    void deniesProtectedCapabilityWithoutMatchingAuthority() {
        RefResolver refResolver = mock(RefResolver.class);
        ExecutionStateService stateService = mock(ExecutionStateService.class);
        CapabilityExecutionRouter router = new CapabilityExecutionRouter(
                refResolver,
                new StaticListableBeanFactory().getBeanProvider(ExecutionCoordinator.class),
                new ObjectMapper(),
                stateService,
                new DefaultAccessGuard());
        BifrostSession session = new BifrostSession("session-1", 2);
        CapabilityMetadata capability = new CapabilityMetadata(
                "yaml:child",
                "child.llm.skill",
                "child",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(new com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration(
                        "gpt-5",
                        com.lokiscale.bifrost.autoconfigure.AiProvider.OPENAI,
                        "openai/gpt-5",
                        "medium")),
                java.util.Set.of("ROLE_ALLOWED"),
                arguments -> "unused",
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("child.llm.skill", "child"),
                "targetBean#deterministicTarget");

        assertThatThrownBy(() -> router.execute(
                capability,
                Map.of("topic", "mars"),
                session,
                UsernamePasswordAuthenticationToken.authenticated(
                        "user",
                        "pw",
                        AuthorityUtils.createAuthorityList("ROLE_OTHER"))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("child.llm.skill");
    }

    @Test
    void authorizesNestedYamlDelegationUsingSessionFallbackAndRestoresPlan() {
        RefResolver refResolver = mock(RefResolver.class);
        ExecutionStateService stateService = mock(ExecutionStateService.class);
        ExecutionCoordinator coordinator = mock(ExecutionCoordinator.class);
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory(Map.of("executionCoordinator", coordinator));
        CapabilityExecutionRouter router = new CapabilityExecutionRouter(
                refResolver,
                beanFactory.getBeanProvider(ExecutionCoordinator.class),
                new ObjectMapper(),
                stateService,
                new DefaultAccessGuard());
        BifrostSession session = new BifrostSession("session-1", 2);
        session.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "user",
                "pw",
                AuthorityUtils.createAuthorityList("ROLE_ALLOWED")));
        CapabilityMetadata capability = new CapabilityMetadata(
                "yaml:child",
                "child.llm.skill",
                "child",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(new com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration(
                        "gpt-5",
                        com.lokiscale.bifrost.autoconfigure.AiProvider.OPENAI,
                        "openai/gpt-5",
                        "medium")),
                java.util.Set.of("ROLE_ALLOWED"),
                arguments -> "unused",
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("child.llm.skill", "child"),
                null);
        PlanSnapshot snapshot = PlanSnapshot.of(null);
        com.lokiscale.bifrost.runtime.state.EvidenceSnapshot evidenceSnapshot = com.lokiscale.bifrost.runtime.state.EvidenceSnapshot.of(java.util.Set.of("parsed_invoice"));

        when(stateService.snapshotPlan(session)).thenReturn(snapshot);
        when(stateService.snapshotEvidence(session)).thenReturn(evidenceSnapshot);
        when(coordinator.execute(eq("child.llm.skill"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap(), eq(session), eq(null)))
                .thenReturn("child result");

        Object result = router.execute(capability, Map.of("topic", "mars"), session, null);

        assertThat(result).isEqualTo("child result");
        verify(stateService).restorePlan(session, snapshot);
        verify(stateService).restoreEvidence(session, evidenceSnapshot);
        verify(refResolver, never()).resolveArguments(org.mockito.ArgumentMatchers.any(), eq(session));
    }

    @Test
    void nestedYamlDelegationStartsWithFreshEvidenceAndRestoresParentEvidenceAfterward() {
        RefResolver refResolver = mock(RefResolver.class);
        ExecutionStateService stateService = new com.lokiscale.bifrost.runtime.state.DefaultExecutionStateService(
                java.time.Clock.fixed(java.time.Instant.parse("2026-03-15T12:00:00Z"), java.time.ZoneOffset.UTC));
        CapabilityMetadata capability = new CapabilityMetadata(
                "yaml:child",
                "child.llm.skill",
                "child",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(new com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration(
                        "gpt-5",
                        com.lokiscale.bifrost.autoconfigure.AiProvider.OPENAI,
                        "openai/gpt-5",
                        "medium")),
                java.util.Set.of(),
                arguments -> "unused",
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("child.llm.skill", "child"),
                null);
        CapabilityRegistry capabilityRegistry = mock(CapabilityRegistry.class);
        when(capabilityRegistry.getCapability("child.llm.skill")).thenReturn(capability);

        com.lokiscale.bifrost.skill.YamlSkillManifest manifest = new com.lokiscale.bifrost.skill.YamlSkillManifest();
        manifest.setName("child.llm.skill");
        manifest.setDescription("child.llm.skill");
        manifest.setModel("gpt-5");
        com.lokiscale.bifrost.skill.YamlSkillDefinition definition = new com.lokiscale.bifrost.skill.YamlSkillDefinition(
                new org.springframework.core.io.ByteArrayResource(new byte[0]),
                manifest,
                new com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration(
                        "gpt-5",
                        com.lokiscale.bifrost.autoconfigure.AiProvider.OPENAI,
                        "openai/gpt-5",
                        "medium"));
        com.lokiscale.bifrost.skill.YamlSkillCatalog catalog = mock(com.lokiscale.bifrost.skill.YamlSkillCatalog.class);
        when(catalog.getSkill("child.llm.skill")).thenReturn(definition);

        com.lokiscale.bifrost.chat.SkillChatClientFactory chatClientFactory = mock(com.lokiscale.bifrost.chat.SkillChatClientFactory.class);
        when(chatClientFactory.create(definition)).thenReturn(mock(org.springframework.ai.chat.client.ChatClient.class));
        when(chatClientFactory.createForStepExecution(definition)).thenReturn(mock(org.springframework.ai.chat.client.ChatClient.class));

        com.lokiscale.bifrost.runtime.MissionExecutionEngine engine = (session, skillDefinition, objective, missionInput, chatClient, visibleTools, planningEnabled, authentication) -> {
            assertThat(session.getProducedEvidenceTypes()).isEmpty();
            session.addProducedEvidenceTypes(java.util.List.of("expense_match_search"));
            return "child result";
        };
        ExecutionCoordinator coordinator = new ExecutionCoordinator(
                catalog,
                capabilityRegistry,
                chatClientFactory,
                (skillName, session, authentication) -> java.util.List.of(),
                (session, skillDefinition, capabilities, authentication) -> java.util.List.of(),
                engine,
                engine,
                stateService,
                new DefaultAccessGuard());
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory(Map.of("executionCoordinator", coordinator));
        CapabilityExecutionRouter router = new CapabilityExecutionRouter(
                refResolver,
                beanFactory.getBeanProvider(ExecutionCoordinator.class),
                new ObjectMapper(),
                stateService,
                new DefaultAccessGuard());
        BifrostSession session = new BifrostSession("session-1", 2);
        session.addProducedEvidenceTypes(java.util.List.of("parsed_invoice"));
        ExecutionFrame parentFrame = stateService.openMissionFrame(session, "parent.visible.skill", Map.of("objective", "parent"));

        Object result = router.execute(capability, Map.of("topic", "mars"), session, null);

        assertThat(result).isEqualTo("child result");
        assertThat(session.getProducedEvidenceTypes()).containsExactly("parsed_invoice");
        stateService.closeMissionFrame(session, parentFrame);
    }

    @Test
    void nestedYamlDelegationPassesCanonicalMissionInputWithoutSerializingItIntoObjective() {
        RefResolver refResolver = mock(RefResolver.class);
        ExecutionStateService stateService = mock(ExecutionStateService.class);
        ExecutionCoordinator coordinator = mock(ExecutionCoordinator.class);
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory(Map.of("executionCoordinator", coordinator));
        CapabilityExecutionRouter router = new CapabilityExecutionRouter(
                refResolver,
                beanFactory.getBeanProvider(ExecutionCoordinator.class),
                new ObjectMapper(),
                stateService,
                new DefaultAccessGuard());
        BifrostSession session = new BifrostSession("session-1", 2);
        CapabilityMetadata capability = new CapabilityMetadata(
                "yaml:child",
                "child.llm.skill",
                "child",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(new com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration(
                        "gpt-5",
                        com.lokiscale.bifrost.autoconfigure.AiProvider.OPENAI,
                        "openai/gpt-5",
                        "medium")),
                java.util.Set.of(),
                arguments -> "unused",
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("child.llm.skill", "child"),
                new SkillInputContractResolver().resolveFromToolSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "invoiceId": { "type": "string" }
                          },
                          "required": ["invoiceId"],
                          "additionalProperties": false
                        }
                        """),
                null);
        PlanSnapshot snapshot = PlanSnapshot.of(null);
        com.lokiscale.bifrost.runtime.state.EvidenceSnapshot evidenceSnapshot = com.lokiscale.bifrost.runtime.state.EvidenceSnapshot.of(java.util.Set.of());

        when(stateService.snapshotPlan(session)).thenReturn(snapshot);
        when(stateService.snapshotEvidence(session)).thenReturn(evidenceSnapshot);
        when(coordinator.execute(eq("child.llm.skill"), eq("Execute YAML skill 'child.llm.skill' using the provided mission input object."),
                eq(Map.of("invoiceId", "INV-7")), eq(session), eq(null)))
                .thenReturn("child result");

        Object result = router.execute(capability, Map.of("invoiceId", "INV-7"), session, null);

        assertThat(result).isEqualTo("child result");
        verify(refResolver, never()).resolveArguments(org.mockito.ArgumentMatchers.any(), eq(session));
    }

    @Test
    void javaCapabilityStillAcceptsDirectRefBackedObjectsOnRootInvocationPath() {
        RefResolver refResolver = mock(RefResolver.class);
        ExecutionStateService stateService = mock(ExecutionStateService.class);
        CapabilityExecutionRouter router = new CapabilityExecutionRouter(
                refResolver,
                new StaticListableBeanFactory().getBeanProvider(ExecutionCoordinator.class),
                new ObjectMapper(),
                stateService,
                new DefaultAccessGuard());
        BifrostSession session = new BifrostSession("session-1", 2);
        ByteArrayResource payload = new ByteArrayResource(new byte[]{1, 2, 3});
        CapabilityMetadata capability = new CapabilityMetadata(
                "java:binaryTool",
                "binaryTool",
                "binary tool",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.none(),
                java.util.Set.of(),
                arguments -> arguments.get("payload"),
                CapabilityKind.JAVA_METHOD,
                new CapabilityToolDescriptor("binaryTool", "binary tool", """
                        {
                          "type": "object",
                          "properties": {
                            "payload": {
                              "type": "string",
                              "description": "Provide a ref:// URI for binary content or an inline string value when appropriate.",
                              "x-bifrost-runtime-ref-capable": true
                            }
                          },
                          "required": ["payload"],
                          "additionalProperties": false
                        }
                        """),
                new SkillInputContractResolver().resolveFromToolSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "payload": {
                              "type": "string",
                              "description": "Provide a ref:// URI for binary content or an inline string value when appropriate.",
                              "x-bifrost-runtime-ref-capable": true
                            }
                          },
                          "required": ["payload"],
                          "additionalProperties": false
                        }
                        """),
                null);

        when(refResolver.resolveArguments(any(), eq(session))).thenAnswer(invocation -> invocation.getArgument(0));

        Object result = router.execute(capability, Map.of("payload", payload), session, null);

        assertThat(result).isSameAs(payload);
        verify(refResolver).resolveArguments(any(), eq(session));
    }
}
