package com.lokiscale.bifrost.internal.core;

import com.lokiscale.bifrost.internal.runtime.input.SkillInputContract;
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
                "bean#method", "description", invoker,
                "{\"type\":\"object\"}", contract);

        assertThat(target.id()).isEqualTo("bean#method");
        assertThat(target.description()).isEqualTo("description");
        assertThat(target.invoker()).isSameAs(invoker);
        assertThat(target.inputSchema()).isEqualTo("{\"type\":\"object\"}");
        assertThat(target.inputContract()).isSameAs(contract);
    }

    @Test
    void doesNotExposeProviderFacingToolDescriptor()
    {
        assertThat(Arrays.stream(SkillImplementationTarget.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getType)
                .map(Class::getName))
                .doesNotContain(
                        CapabilityToolDescriptor.class.getName(),
                        org.springframework.ai.tool.definition.ToolDefinition.class.getName());
    }
}
