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

    @Test
    void loadsTypedRegexLinterConfigurationWhenPresent() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/regex-linter-skill.yaml")
                .run(context -> {
                    YamlSkillCatalog catalog = context.getBean(YamlSkillCatalog.class);

                    assertThat(catalog.getSkill("linted.skill")).isNotNull();
                    assertThat(catalog.getSkill("linted.skill").linter()).isNotNull();
                    assertThat(catalog.getSkill("linted.skill").linter().getType()).isEqualTo("regex");
                    assertThat(catalog.getSkill("linted.skill").linter().getMaxRetries()).isEqualTo(2);
                    assertThat(catalog.getSkill("linted.skill").linter().getRegex()).isNotNull();
                    assertThat(catalog.getSkill("linted.skill").linter().getRegex().getPattern()).isEqualTo("^```yaml[\\s\\S]*```$");
                    assertThat(catalog.getSkill("linted.skill").linter().getRegex().getMessage()).isEqualTo("Return fenced YAML only.");
                });
    }

    @Test
    void defaultsLinterToAbsentWhenManifestDoesNotDeclareOne() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/default-thinking-skill.yaml")
                .run(context -> {
                    YamlSkillCatalog catalog = context.getBean(YamlSkillCatalog.class);

                    assertThat(catalog.getSkill("thinking.default.skill")).isNotNull();
                    assertThat(catalog.getSkill("thinking.default.skill").linter()).isNull();
                });
    }

    @Test
    void failsStartupWhenLinterTypeIsMissing() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/missing-linter-type-skill.yaml")
                .run(context -> {
                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasMessageContaining("missing-linter-type-skill.yaml")
                            .hasMessageContaining("field 'linter.type'")
                            .hasMessageContaining("required field is missing or blank");
                });
    }

    @Test
    void failsStartupWhenLinterTypeIsUnsupported() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/unsupported-linter-type-skill.yaml")
                .run(context -> {
                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasMessageContaining("unsupported-linter-type-skill.yaml")
                            .hasMessageContaining("field 'linter.type'")
                            .hasMessageContaining("unsupported linter type 'external'");
                });
    }

    @Test
    void failsStartupWhenRegexBlockIsMissingForRegexType() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/missing-regex-block-skill.yaml")
                .run(context -> {
                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasMessageContaining("missing-regex-block-skill.yaml")
                            .hasMessageContaining("field 'linter.regex'")
                            .hasMessageContaining("required block is missing");
                });
    }

    @Test
    void failsStartupWhenRegexPatternIsMissingOrBlank() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/missing-regex-pattern-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("missing-regex-pattern-skill.yaml")
                        .hasMessageContaining("field 'linter.regex.pattern'")
                        .hasMessageContaining("required field is missing or blank"));

        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/blank-regex-pattern-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("blank-regex-pattern-skill.yaml")
                        .hasMessageContaining("field 'linter.regex.pattern'")
                        .hasMessageContaining("required field is missing or blank"));
    }

    @Test
    void failsStartupWhenRegexPatternIsInvalid() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/invalid-regex-linter-skill.yaml")
                .run(context -> {
                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasMessageContaining("invalid-regex-linter-skill.yaml")
                            .hasMessageContaining("field 'linter.regex.pattern'")
                            .hasMessageContaining("invalid regex pattern");
                });
    }

    @Test
    void failsStartupWhenLinterMaxRetriesIsMissing() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/missing-linter-max-retries-skill.yaml")
                .run(context -> {
                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasMessageContaining("missing-linter-max-retries-skill.yaml")
                            .hasMessageContaining("field 'linter.max_retries'")
                            .hasMessageContaining("required field is missing");
                });
    }

    @Test
    void failsStartupWhenLinterMaxRetriesIsOutOfRange() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/negative-linter-max-retries-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("negative-linter-max-retries-skill.yaml")
                        .hasMessageContaining("field 'linter.max_retries'")
                        .hasMessageContaining("must be between 0 and 3"));

        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/excessive-linter-max-retries-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("excessive-linter-max-retries-skill.yaml")
                        .hasMessageContaining("field 'linter.max_retries'")
                        .hasMessageContaining("must be between 0 and 3"));
    }

    @Test
    void failsStartupWhenLinterMaxRetriesHasWrongType() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/wrong-type-linter-max-retries-skill.yaml")
                .run(context -> {
                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasMessageContaining("wrong-type-linter-max-retries-skill.yaml")
                            .hasMessageContaining("field 'linter.max_retries'")
                            .hasMessageContaining("Cannot deserialize value of type `java.lang.Integer`");
                });
    }

    @Test
    void failsStartupWhenLinterContainsUnknownFields() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/unknown-linter-field-skill.yaml")
                .run(context -> {
                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasMessageContaining("unknown-linter-field-skill.yaml")
                            .hasMessageContaining("field 'linter.regex.patterns'")
                            .hasMessageContaining("unknown field");
                });
    }

    @Test
    void failsStartupWhenManifestContainsUnknownRootFields() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/unknown-root-field-skill.yaml")
                .run(context -> {
                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasMessageContaining("unknown-root-field-skill.yaml")
                            .hasMessageContaining("field 'lintr'")
                            .hasMessageContaining("unknown field");
                });
    }

    @Test
    void failsStartupWhenMappingContainsUnknownFields() {
        contextRunner
                .withUserConfiguration(TargetBeanConfiguration.class)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/unknown-mapping-field-skill.yaml")
                .run(context -> {
                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasMessageContaining("unknown-mapping-field-skill.yaml")
                            .hasMessageContaining("field 'mapping.target_ids'")
                            .hasMessageContaining("unknown field");
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
