package com.lokiscale.bifrost.skill;

import com.lokiscale.bifrost.annotation.SkillMethod;
import com.lokiscale.bifrost.autoconfigure.BifrostAutoConfiguration;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.CapabilityRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YamlSkillCapabilityRegistrarTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    BifrostAutoConfiguration.class))
            .withInitializer(context -> {
                try {
                    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
                    for (PropertySource<?> propertySource : loader.load("application-test", new ClassPathResource("application-test.yml"))) {
                        context.getEnvironment().getPropertySources().addLast(propertySource);
                    }
                }
                catch (java.io.IOException ex) {
                    throw new IllegalStateException("Failed to load application-test.yml", ex);
                }
            });

    @Test
    void mapsDeterministicYamlSkillToDiscoveredSkillMethodTarget() {
        contextRunner
                .withUserConfiguration(TargetBeanConfiguration.class)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/mapped-method-skill.yaml")
                .run(context -> {
                    CapabilityRegistry capabilityRegistry = context.getBean(CapabilityRegistry.class);
                    CapabilityMetadata metadata = capabilityRegistry.getCapability("mapped.method.skill");
                    String parameterName = getDeclaredMethod(TargetBean.class, "deterministicTarget", String.class)
                            .getParameters()[0]
                            .getName();

                    assertThat(metadata).isNotNull();
                    assertThat(metadata.skillExecution().frameworkModel()).isEqualTo("gpt-5");
                    assertThat(metadata.kind()).isEqualTo(com.lokiscale.bifrost.core.CapabilityKind.YAML_SKILL);
                    assertThat(metadata.mappedTargetId()).isEqualTo("targetBean#deterministicTarget");
                    assertThat(metadata.tool().inputSchema()).contains(parameterName);
                    assertThat(metadata.invoker().invoke(Map.of(parameterName, "alpha"))).isEqualTo("\"mapped:alpha\"");
                });
    }

    @Test
    void registersYamlCapabilitiesWithManifestRbacRoles() {
        contextRunner
                .withUserConfiguration(TargetBeanConfiguration.class)
                .withPropertyValues(
                        "bifrost.skills.locations=classpath:/skills/valid/allowed-skills-root.yaml,classpath:/skills/valid/allowed-child-skill.yaml,classpath:/skills/valid/disallowed-child-skill.yaml")
                .run(context -> {
                    CapabilityRegistry capabilityRegistry = context.getBean(CapabilityRegistry.class);

                    assertThat(capabilityRegistry.getCapability("allowed.visible.skill").rbacRoles())
                            .containsExactly("ROLE_ALLOWED");
                    assertThat(capabilityRegistry.getCapability("disallowed.visible.skill").rbacRoles())
                            .containsExactly("ROLE_BLOCKED");
                });
    }

    @Test
    void mappedDeterministicYamlSkillReturnsTransformedErrorWhenTargetThrows() {
        contextRunner
                .withUserConfiguration(ThrowingTargetBeanConfiguration.class)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/mapped-method-skill.yaml")
                .run(context -> {
                    CapabilityMetadata metadata = context.getBean(CapabilityRegistry.class)
                            .getCapability("mapped.method.skill");
                    String parameterName = getDeclaredMethod(ThrowingTargetBean.class, "deterministicTarget", String.class)
                            .getParameters()[0]
                            .getName();

                    assertThat(metadata.invoker().invoke(Map.of(parameterName, "alpha")))
                            .isEqualTo("ERROR: IllegalArgumentException. HINT: mapped boom");
                });
    }

    private static Method getDeclaredMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getDeclaredMethod(name, parameterTypes);
        }
        catch (NoSuchMethodException ex) {
            throw new IllegalStateException("Test fixture method lookup failed", ex);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TargetBeanConfiguration {

        @Bean
        TargetBean targetBean() {
            return new TargetBean();
        }
    }

    static class TargetBean {

        @SkillMethod(name = "deterministicTarget", description = "Deterministic target")
        String deterministicTarget(String input) {
            return "mapped:" + input;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ThrowingTargetBeanConfiguration {

        @Bean
        ThrowingTargetBean targetBean() {
            return new ThrowingTargetBean();
        }
    }

    static class ThrowingTargetBean {

        @SkillMethod(name = "deterministicTarget", description = "Deterministic target")
        String deterministicTarget(String input) {
            throw new IllegalStateException("wrapper", new IllegalArgumentException("mapped boom"));
        }
    }
}
