package com.lokiscale.bifrost.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import com.lokiscale.bifrost.runtime.state.PlanSnapshot;
import com.lokiscale.bifrost.vfs.RefResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
                stateService);
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

        when(stateService.snapshotPlan(session)).thenReturn(snapshot);
        when(coordinator.execute(eq("child.llm.skill"), org.mockito.ArgumentMatchers.anyString(), eq(session), eq(null)))
                .thenReturn("child result");

        Object result = router.execute(capability, Map.of("topic", "mars"), session, null);

        assertThat(result).isEqualTo("child result");
        verify(stateService).restorePlan(session, snapshot);
    }
}
