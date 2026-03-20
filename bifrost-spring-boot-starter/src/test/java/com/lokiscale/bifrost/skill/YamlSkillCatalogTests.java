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

import static org.assertj.core.api.Assertions.assertThat;

class YamlSkillCatalogTests {

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
            })
            .withUserConfiguration(TargetBeanConfiguration.class);

    @Test
    void defaultsThinkingLevelToMediumWhenModelSupportsThinking() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/default-thinking-skill.yaml")
                .run(context -> {
                    YamlSkillCatalog catalog = context.getBean(YamlSkillCatalog.class);

                    assertThat(catalog.getSkill("thinking.default.skill")).isNotNull();
                    assertThat(catalog.getSkill("thinking.default.skill").executionConfiguration().frameworkModel()).isEqualTo("gpt-5");
                    assertThat(catalog.getSkill("thinking.default.skill").executionConfiguration().thinkingLevel()).isEqualTo("medium");
                });
    }

    @Test
    void omitsThinkingLevelWhenSelectedModelHasNoThinkingSupport() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/non-thinking-skill.yaml")
                .run(context -> {
                    YamlSkillCatalog catalog = context.getBean(YamlSkillCatalog.class);

                    assertThat(catalog.getSkill("non.thinking.skill")).isNotNull();
                    assertThat(catalog.getSkill("non.thinking.skill").executionConfiguration().providerModel()).isEqualTo("llama3.2");
                    assertThat(catalog.getSkill("non.thinking.skill").executionConfiguration().thinkingLevel()).isNull();
                });
    }

    @Test
    void failsStartupWhenYamlSkillReferencesUnknownModel() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/unknown-model-skill.yaml")
                .run(context -> {
                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasMessageContaining("unknown-model-skill.yaml")
                            .hasMessageContaining("field 'model'")
                            .hasMessageContaining("unknown model 'missing-model'");
                });
    }

    @Test
    void failsStartupWhenThinkingLevelIsUnsupportedForModel() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/unsupported-thinking-skill.yaml")
                .run(context -> {
                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasMessageContaining("unsupported-thinking-skill.yaml")
                            .hasMessageContaining("field 'thinking_level'")
                            .hasMessageContaining("unsupported thinking_level 'high'");
                });
    }

    @Test
    void loadsYamlSkillsFromClasspathSkillsPattern() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/pattern/**/*.yaml")
                .run(context -> {
                    YamlSkillCatalog catalog = context.getBean(YamlSkillCatalog.class);

                    assertThat(catalog.getSkills()).hasSize(2);
                    assertThat(catalog.getSkills())
                            .extracting(definition -> definition.manifest().getName())
                            .containsExactly("pattern.two.skill", "pattern.one.skill");
                });
    }

    @Test
    void mapsDeterministicYamlSkillToDiscoveredSkillMethodTarget() {
        contextRunner
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
                    assertThat(metadata.invoker().invoke(java.util.Map.of(parameterName, "alpha"))).isEqualTo("\"mapped:alpha\"");
                });
    }

    @Test
    void parsesAllowedSkillsWhenPresent() {
        contextRunner
                .withPropertyValues(
                        "bifrost.skills.locations=classpath:/skills/valid/allowed-skills-root.yaml,classpath:/skills/valid/allowed-child-skill.yaml")
                .run(context -> {
                    YamlSkillCatalog catalog = context.getBean(YamlSkillCatalog.class);

                    assertThat(catalog.getSkill("root.visible.skill")).isNotNull();
                    assertThat(catalog.getSkill("root.visible.skill").allowedSkills())
                            .containsExactly("allowed.visible.skill", "internal.only.target", "disallowed.visible.skill");
                    assertThat(catalog.getSkill("allowed.visible.skill").rbacRoles())
                            .containsExactly("ROLE_ALLOWED");
                });
    }

    @Test
    void defaultsAllowedSkillsToEmptyListWhenMissing() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/default-thinking-skill.yaml")
                .run(context -> {
                    YamlSkillCatalog catalog = context.getBean(YamlSkillCatalog.class);

                    assertThat(catalog.getSkill("thinking.default.skill").allowedSkills()).isEmpty();
                    assertThat(catalog.getSkill("thinking.default.skill").rbacRoles()).isEmpty();
                    assertThat(catalog.getSkill("thinking.default.skill").manifest().getPlanningMode()).isNull();
                });
    }

    @Test
    void parsesPlanningModeOverrideWhenPresent() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/planning-disabled-skill.yaml")
                .run(context -> {
                    YamlSkillCatalog catalog = context.getBean(YamlSkillCatalog.class);

                    assertThat(catalog.getSkill("planning.disabled.skill")).isNotNull();
                    assertThat(catalog.getSkill("planning.disabled.skill").manifest().getPlanningMode()).isFalse();
                    assertThat(catalog.getSkill("planning.disabled.skill").planningModeEnabled(true)).isFalse();
                    assertThat(catalog.getSkill("planning.disabled.skill").planningModeEnabled(false)).isFalse();
                });
    }

    @Test
    void registersYamlCapabilitiesWithManifestRbacRoles() {
        contextRunner
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
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class,
                        BifrostAutoConfiguration.class))
                .withInitializer(context -> {
                    try {
                        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
                        for (PropertySource<?> propertySource
                                : loader.load("application-test", new ClassPathResource("application-test.yml"))) {
                            context.getEnvironment().getPropertySources().addLast(propertySource);
                        }
                    }
                    catch (java.io.IOException ex) {
                        throw new IllegalStateException("Failed to load application-test.yml", ex);
                    }
                })
                .withUserConfiguration(ThrowingTargetBeanConfiguration.class)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/mapped-method-skill.yaml")
                .run(context -> {
                    CapabilityMetadata metadata = context.getBean(CapabilityRegistry.class)
                            .getCapability("mapped.method.skill");
                    String parameterName = getDeclaredMethod(ThrowingTargetBean.class, "deterministicTarget", String.class)
                            .getParameters()[0]
                            .getName();

                    assertThat(metadata.invoker().invoke(java.util.Map.of(parameterName, "alpha")))
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
