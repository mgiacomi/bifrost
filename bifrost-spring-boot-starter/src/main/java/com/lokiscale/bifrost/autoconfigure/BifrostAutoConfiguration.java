package com.lokiscale.bifrost.autoconfigure;

import com.lokiscale.bifrost.core.CapabilityRegistry;
import com.lokiscale.bifrost.core.InMemoryCapabilityRegistry;
import com.lokiscale.bifrost.core.SkillMethodBeanPostProcessor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.boot.autoconfigure.AutoConfiguration;

@AutoConfiguration
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class BifrostAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CapabilityRegistry.class)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public CapabilityRegistry capabilityRegistry() {
        return new InMemoryCapabilityRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static SkillMethodBeanPostProcessor skillMethodBeanPostProcessor(CapabilityRegistry capabilityRegistry) {
        return new SkillMethodBeanPostProcessor(capabilityRegistry);
    }
}
