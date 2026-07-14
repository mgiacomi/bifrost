package com.lokiscale.bifrost.core;

import com.lokiscale.bifrost.runtime.input.SkillInputContract;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class SkillImplementationTargetTest
{
    @Test
    void storesInternalIdentityInvokerSchemaAndContract()
    {
        CapabilityInvoker invoker = arguments -> "ok";
        SkillInputContract contract = SkillInputContract.genericObject();
        SkillImplementationTarget target = new SkillImplementationTarget(
                "bean#method", "description", ModelPreference.HEAVY, invoker,
                "{\"type\":\"object\"}", contract);

        assertThat(target.id()).isEqualTo("bean#method");
        assertThat(target.description()).isEqualTo("description");
        assertThat(target.modelPreference()).isEqualTo(ModelPreference.HEAVY);
        assertThat(target.invoker()).isSameAs(invoker);
        assertThat(target.inputSchema()).isEqualTo("{\"type\":\"object\"}");
        assertThat(target.inputContract()).isSameAs(contract);
    }

    @Test
    void doesNotExposeProviderFacingToolDescriptor()
    {
        assertThat(Arrays.stream(SkillImplementationTarget.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getType))
                .doesNotContain(CapabilityToolDescriptor.class, org.springframework.ai.tool.definition.ToolDefinition.class);
    }
}
