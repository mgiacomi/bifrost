package com.lokiscale.bifrost.skill;

import com.lokiscale.bifrost.autoconfigure.BifrostAutoConfiguration;
import com.lokiscale.bifrost.autoconfigure.AiProvider;
import com.lokiscale.bifrost.annotation.SkillMethod;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

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
            });

    @Test
    void defaultsThinkingLevelToMediumWhenModelSupportsThinking() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/default-thinking-skill.yaml")
                .run(context -> {
                    YamlSkillCatalog catalog = context.getBean(YamlSkillCatalog.class);

                    assertThat(catalog.getSkill("thinking.default.skill")).isNotNull();
                    assertThat(catalog.getSkill("thinking.default.skill").executionConfiguration())
                            .extracting(
                                    EffectiveSkillExecutionConfiguration::frameworkModel,
                                    EffectiveSkillExecutionConfiguration::provider,
                                    EffectiveSkillExecutionConfiguration::providerModel,
                                    EffectiveSkillExecutionConfiguration::thinkingLevel)
                            .containsExactly("gpt-5", AiProvider.OPENAI, "openai/gpt-5", "medium");
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
    void failsStartupWhenYamlSkillsShareDuplicateName() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/duplicate-name/*.yaml")
                .run(context -> {
                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasMessageContaining("second-skill.yaml")
                            .hasMessageContaining("field 'name'")
                            .hasMessageContaining("duplicate skill name 'duplicate.skill'");
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
    void loadsNoSkillsWhenConfiguredClasspathRootIsMissing() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/does-not-exist/**/*.yaml")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(YamlSkillCatalog.class).getSkills()).isEmpty();
                });
    }

    @Test
    void loadsNoSkillsWhenClasspathRootExistsButHasNoYamlMatches() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/empty/**/*.yaml")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(YamlSkillCatalog.class).getSkills()).isEmpty();
                });
    }

    @Test
    void loadsTypedManifestFieldsWhenPresent() {
        contextRunner
                .withUserConfiguration(TargetBeanConfiguration.class)
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
    void defaultsTypedManifestFieldsWhenMissing() {
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
    void loadsPlanningModeOverrideWhenPresent() {
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
}
