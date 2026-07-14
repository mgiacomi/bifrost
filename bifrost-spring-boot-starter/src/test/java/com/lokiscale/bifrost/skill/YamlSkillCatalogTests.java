package com.lokiscale.bifrost.skill;

import com.lokiscale.bifrost.autoconfigure.BifrostAutoConfiguration;
import com.lokiscale.bifrost.autoconfigure.AiProvider;
import com.lokiscale.bifrost.annotation.SkillMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class YamlSkillCatalogTests {

    private final ApplicationContextRunner modelFreeContextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    BifrostAutoConfiguration.class));

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
    void discoversMappedSkillWhenModelCatalogIsEmpty() {
        modelFreeContextRunner
                .withUserConfiguration(TargetBeanConfiguration.class)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/model-free-mapped-skill.yaml")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    YamlSkillDefinition definition = context.getBean(YamlSkillCatalog.class)
                            .getSkill("model.free.mapped.skill");
                    assertThat(definition).isNotNull();
                    assertThat(definition.implementationType())
                            .isEqualTo(com.lokiscale.bifrost.core.PublicSkillImplementationType.MAPPED_JAVA);
                    assertThat(definition.executionConfiguration()).isNull();
                });
    }

    @Test
    void rejectsLlmSkillWhenModelCatalogIsEmpty() {
        modelFreeContextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/llm-missing-model.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("llm.missing.model")
                        .hasMessageContaining("llm-missing-model.yaml")
                        .hasMessageContaining("field 'model'")
                        .hasMessageContaining("required field is missing or blank")
                        .hasMessageContaining("declare a configured model"));
    }

    @TestFactory
    List<DynamicTest> rejectsDeclaredMappingWithoutNonBlankTarget() {
        record Case(String filename, String skillName) {}
        return List.of(
                new Case("mapped-null-mapping.yaml", "mapped.null.mapping"),
                new Case("mapped-empty-mapping.yaml", "mapped.empty.mapping"),
                new Case("mapped-non-object-mapping.yaml", "mapped.non.object.mapping"),
                new Case("mapped-non-string-target.yaml", "mapped.non.string.target"),
                new Case("mapped-null-target.yaml", "mapped.null.target"),
                new Case("mapped-blank-target.yaml", "mapped.blank.target"))
                .stream()
                .map(testCase -> DynamicTest.dynamicTest(testCase.filename(), () -> contextRunner
                        .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/" + testCase.filename())
                        .run(context -> assertThat(context.getStartupFailure())
                                .isNotNull()
                                .hasMessageContaining(testCase.skillName())
                                .hasMessageContaining(testCase.filename())
                                .hasMessageContaining("field 'mapping.target_id'")
                                .hasMessageContaining("target_id must be non-blank")
                                .hasMessageContaining("declare a valid Java target or remove mapping"))))
                .toList();
    }

    @TestFactory
    List<DynamicTest> rejectsInapplicableMappedFieldByDeclarationPresence() {
        record Case(String filename, String skillName, String field, String explanation, String remedy) {}
        return List.of(
                new Case("mapped-with-model-null.yaml", "mapped.with.model.null", "model", "no model executes", "remove the field"),
                new Case("mapped-with-thinking-level-blank.yaml", "mapped.with.thinking.blank", "thinking_level", "no model executes", "remove the field"),
                new Case("mapped-with-prompt-null.yaml", "mapped.with.prompt.null", "prompt", "no model executes", "remove the field"),
                new Case("mapped-with-input-schema-null.yaml", "mapped.with.input.null", "input_schema", "reflected input contract", "create a Java adapter target"),
                new Case("mapped-with-output-schema-null.yaml", "mapped.with.output.null", "output_schema", "owns the returned value", "create a Java adapter target"),
                new Case("mapped-with-planning-mode-false.yaml", "mapped.with.planning.false", "planning_mode", "no model executes", "remove the field"),
                new Case("mapped-with-planning-mode-object.yaml", "mapped.with.planning.object", "planning_mode", "no model executes", "remove the field"),
                new Case("mapped-with-max-steps-zero.yaml", "mapped.with.steps.zero", "max_steps", "no model executes", "remove the field"),
                new Case("mapped-with-max-steps-text.yaml", "mapped.with.steps.text", "max_steps", "no model executes", "remove the field"),
                new Case("mapped-with-allowed-skills-empty.yaml", "mapped.with.allowed.empty", "allowed_skills", "LLM parent", "declare the mapped child"),
                new Case("mapped-with-linter-null.yaml", "mapped.with.linter.null", "linter", "model-output validation", "remove the field"),
                new Case("mapped-with-output-schema-retries-zero.yaml", "mapped.with.retries.zero", "output_schema_max_retries", "model-output validation", "remove the field"),
                new Case("mapped-with-evidence-contract-null.yaml", "mapped.with.evidence.null", "evidence_contract", "invoking LLM parent", "declare tool evidence"))
                .stream()
                .map(testCase -> DynamicTest.dynamicTest(testCase.filename(), () -> contextRunner
                        .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/" + testCase.filename())
                        .run(context -> assertThat(context.getStartupFailure())
                                .isNotNull()
                                .hasMessageContaining(testCase.skillName())
                                .hasMessageContaining(testCase.filename())
                                .hasMessageContaining("field '" + testCase.field() + "'")
                                .hasMessageContaining(testCase.explanation())
                                .hasMessageContaining(testCase.remedy()))))
                .toList();
    }

    @Test
    void reportsMappedApplicabilityErrorsInStableOrderBeforeContentAndTargetValidation() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/mapped-with-multiple-inapplicable-fields.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("mapped.with.multiple.invalid")
                        .hasMessageContaining("mapped-with-multiple-inapplicable-fields.yaml")
                        .hasMessageContaining("field 'model'")
                        .hasMessageContaining("no model executes")
                        .hasMessageNotContaining("unknown implementation target"));
    }

    @Test
    void exposesCompleteStableMappedApplicabilityOrder() {
        assertThat(YamlSkillCatalog.mappedInapplicableFields())
                .extracting(YamlSkillManifest.Field::yamlName)
                .containsExactly(
                        "model",
                        "thinking_level",
                        "prompt",
                        "input_schema",
                        "output_schema",
                        "planning_mode",
                        "max_steps",
                        "allowed_skills",
                        "linter",
                        "output_schema_max_retries",
                        "evidence_contract");
    }

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
                            .hasMessageContaining("invalid.unsupported.thinking.skill")
                            .hasMessageContaining("unsupported-thinking-skill.yaml")
                            .hasMessageContaining("field 'thinking_level'")
                            .hasMessageContaining("unsupported thinking_level 'high'");
                });
    }

    @Test
    void includesPublicSkillNameInPostParseLlmValidationErrors() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/negative-linter-max-retries-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("invalid.negative.linter.max.retries.skill")
                        .hasMessageContaining("negative-linter-max-retries-skill.yaml")
                        .hasMessageContaining("field 'linter.max_retries'"));
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
                            .containsExactly("allowed.visible.skill", "targetBean#deterministicTarget", "disallowed.visible.skill");
                    assertThat(catalog.getSkill("allowed.visible.skill").rbacRoles())
                            .containsExactly("ROLE_ALLOWED");
                });
    }

    @Test
    void returnsDefensiveManifestCopiesFromPublicCatalog() {
        contextRunner
                .withUserConfiguration(TargetBeanConfiguration.class)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/allowed-child-skill.yaml")
                .run(context -> {
                    YamlSkillCatalog catalog = context.getBean(YamlSkillCatalog.class);
                    YamlSkillDefinition definition = catalog.getSkill("allowed.visible.skill");

                    definition.manifest().setName("mutated.name");
                    definition.manifest().setRbacRoles(java.util.List.of("ROLE_MUTATED"));
                    definition.manifest().getMapping().setTargetId("mutatedBean#method");

                    assertThat(catalog.getSkill("allowed.visible.skill").manifest().getName())
                            .isEqualTo("allowed.visible.skill");
                    assertThat(catalog.getSkill("allowed.visible.skill").rbacRoles())
                            .containsExactly("ROLE_ALLOWED");
                    assertThat(catalog.getSkill("allowed.visible.skill").mappingTargetId())
                            .isEqualTo("targetBean#deterministicTarget");
                    assertThat(catalog.getSkill("mutated.name")).isNull();
                });
    }

    @Test
    void returnsDefensiveCopiesFromNestedManifestAccessors() {
        contextRunner
                .withPropertyValues(
                        "bifrost.skills.locations=classpath:/skills/valid/regex-linter-skill.yaml,"
                                + "classpath:/skills/valid/input-schema-skill.yaml,"
                                + "classpath:/skills/valid/output-schema-skill.yaml")
                .run(context -> {
                    YamlSkillCatalog catalog = context.getBean(YamlSkillCatalog.class);

                    catalog.getSkill("linted.skill").linter().setType("mutated");
                    catalog.getSkill("input.schema.skill").inputSchema().setType("string");
                    catalog.getSkill("output.schema.skill").outputSchema().setType("string");

                    assertThat(catalog.getSkill("linted.skill").linter().getType()).isEqualTo("regex");
                    assertThat(catalog.getSkill("input.schema.skill").inputSchema().getType()).isEqualTo("object");
                    assertThat(catalog.getSkill("output.schema.skill").outputSchema().getType()).isEqualTo("object");
                });
    }

    @Test
    void distinguishesLlmBackedAndMappedJavaImplementationTypes() {
        contextRunner
                .withUserConfiguration(TargetBeanConfiguration.class)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/default-thinking-skill.yaml,classpath:/skills/valid/mapped-method-skill.yaml")
                .run(context -> {
                    YamlSkillCatalog catalog = context.getBean(YamlSkillCatalog.class);

                    assertThat(catalog.getSkill("thinking.default.skill").implementationType())
                            .isEqualTo(com.lokiscale.bifrost.core.PublicSkillImplementationType.LLM_BACKED);
                    assertThat(catalog.getSkill("mapped.method.skill").implementationType())
                            .isEqualTo(com.lokiscale.bifrost.core.PublicSkillImplementationType.MAPPED_JAVA);
                    assertThat(catalog.getSkill("mapped.method.skill").mappingTargetId())
                            .isEqualTo("targetBean#deterministicTarget");
                    assertThat(catalog.getSkill("mapped.method.skill").executionConfiguration()).isNull();
                });
    }

    @Test
    void loadsYamlSkillPromptAndNormalizesBlankPrompt() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/prompt-skill.yaml,classpath:/skills/valid/blank-prompt-skill.yaml")
                .run(context -> {
                    YamlSkillCatalog catalog = context.getBean(YamlSkillCatalog.class);

                    assertThat(catalog.getSkill("prompt.skill").prompt())
                            .contains("LONG_PROMPT_SENTINEL")
                            .contains("Follow the private skill instructions.");
                    assertThat(catalog.getSkill("blank.prompt.skill").prompt()).isNull();
                });
    }

    @Test
    void rejectsUnknownPromptLikeFields() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/unknown-prompt-like-field-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("unknown-prompt-like-field-skill.yaml")
                        .hasMessageContaining("field 'system_prompt'")
                        .hasMessageContaining("unknown field"));
    }

    @Test
    void rejectsPromptOnMappedYamlSkill() {
        contextRunner
                .withUserConfiguration(TargetBeanConfiguration.class)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/mapped-skill-with-prompt.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("mapped-skill-with-prompt.yaml")
                        .hasMessageContaining("field 'prompt'")
                        .hasMessageContaining("no model executes"));
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
    void defaultsOutputSchemaMaxRetriesToTwoWhenSchemaIsPresent() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/output-schema-skill.yaml")
                .run(context -> {
                    YamlSkillCatalog catalog = context.getBean(YamlSkillCatalog.class);

                    assertThat(catalog.getSkill("output.schema.skill")).isNotNull();
                    assertThat(catalog.getSkill("output.schema.skill").manifest().getOutputSchemaMaxRetries()).isEqualTo(2);
                });
    }

    @Test
    void loadsYamlSkillInputSchemaWhenPresent() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/input-schema-skill.yaml")
                .run(context -> {
                    YamlSkillDefinition definition = context.getBean(YamlSkillCatalog.class).getSkill("input.schema.skill");

                    assertThat(definition).isNotNull();
                    assertThat(definition.inputSchema()).isNotNull();
                    assertThat(definition.inputSchema().getType()).isEqualTo("object");
                    assertThat(definition.inputSchema().getRequired()).containsExactly("payload");
                });
    }

    @Test
    void acceptsAttachmentOnlyInInputSchema() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/attachment-input-skill.yaml")
                .run(context -> {
                    YamlSkillDefinition definition = context.getBean(YamlSkillCatalog.class).getSkill("attachment.input.skill");

                    assertThat(definition).isNotNull();
                    assertThat(definition.inputSchema().getProperties().get("image").getType()).isEqualTo("attachment");
                    assertThat(definition.inputSchema().getProperties().get("image").getMediaType()).isEqualTo("image");
                    assertThat(definition.inputSchema().getProperties().get("image").getAllowedContentTypes())
                            .containsExactly("image/jpeg");
                });
    }

    @Test
    void rejectsAttachmentFieldsOnOutputSchema() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/attachment-output-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("attachment-output-skill.yaml")
                        .hasMessageContaining("field 'output_schema.properties.image.type'")
                        .hasMessageContaining("unsupported schema type 'attachment'"));
    }

    @Test
    void requiresAllowedContentTypesForAttachmentInputAndRejectsMediaFieldsElsewhere() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/attachment-missing-allowed-content-types-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("attachment-missing-allowed-content-types-skill.yaml")
                        .hasMessageContaining("field 'input_schema.properties.image.allowed_content_types'")
                        .hasMessageContaining("must declare at least one content type"));

        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/non-attachment-media-field-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("non-attachment-media-field-skill.yaml")
                        .hasMessageContaining("field 'input_schema.properties.payload.media_type'")
                        .hasMessageContaining("is only supported for attachment schemas"));
    }

    @Test
    void failsStartupWhenInputSchemaUsesUnsupportedKeywordOrNonObjectRoot() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/input-schema-root-array-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("input-schema-root-array-skill.yaml")
                        .hasMessageContaining("field 'input_schema.type'")
                        .hasMessageContaining("root input_schema type must be 'object'"));

        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/input-schema-unsupported-keyword-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("input-schema-unsupported-keyword-skill.yaml")
                        .hasMessageContaining("field 'input_schema.properties.payload.oneOf'")
                        .hasMessageContaining("unknown field"));
    }

    @Test
    void mappedYamlSkillWithMismatchedInputSchemaFailsStartup() {
        contextRunner
                .withUserConfiguration(TargetBeanConfiguration.class)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/mapped-method-skill-mismatched-input-schema.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("mapped-method-skill-mismatched-input-schema.yaml")
                        .hasMessageContaining("field 'input_schema'")
                        .hasMessageContaining("Java target's reflected input contract"));
    }

    @Test
    void mappedYamlSkillWithFormatMismatchedInputSchemaFailsStartup() {
        contextRunner
                .withUserConfiguration(TargetBeanConfiguration.class)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/mapped-method-skill-format-mismatched-input-schema.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("mapped-method-skill-format-mismatched-input-schema.yaml")
                        .hasMessageContaining("field 'input_schema'")
                        .hasMessageContaining("Java target's reflected input contract"));
    }

    @Test
    void loadsEvidenceContractWhenManifestDeclaresOne() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/evidence-contract-skill.yaml")
                .run(context -> {
                    YamlSkillCatalog catalog = context.getBean(YamlSkillCatalog.class);

                    assertThat(catalog.getSkill("evidence.contract.skill")).isNotNull();
                    assertThat(catalog.getSkill("evidence.contract.skill").evidenceContract().evidenceForClaim("isDuplicate"))
                            .containsExactlyInAnyOrder("parsed_invoice", "expense_match_search");
                    assertThat(catalog.getSkill("evidence.contract.skill").evidenceContract().evidenceProducedByTool("expenseLookup"))
                            .containsExactly("expense_match_search");
                });
    }

    @Test
    void failsStartupWhenEvidenceContractReferencesUnknownClaim() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/evidence-contract-unknown-claim-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("evidence-contract-unknown-claim-skill.yaml")
                        .hasMessageContaining("field 'evidence_contract.claims.isDuplicate'")
                        .hasMessageContaining("unknown output_schema property 'isDuplicate'"));
    }

    @Test
    void failsStartupWhenEvidenceContractContainsBlankEvidenceIds() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/evidence-contract-blank-evidence-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("evidence-contract-blank-evidence-skill.yaml")
                        .hasMessageContaining("field 'evidence_contract.claims.vendorName'")
                        .hasMessageContaining("evidence ids must not be blank"));
    }

    @Test
    void failsStartupWhenEvidenceContractClaimKeysDifferOnlyByCase() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/evidence-contract-duplicate-claim-case-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("evidence-contract-duplicate-claim-case-skill.yaml")
                        .hasMessageContaining("field 'evidence_contract.claims.VendorName'")
                        .hasMessageContaining("duplicates claim 'vendorName'"));
    }

    @Test
    void failsStartupWhenEvidenceContractToolKeysDifferOnlyByCase() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/evidence-contract-duplicate-tool-case-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("evidence-contract-duplicate-tool-case-skill.yaml")
                        .hasMessageContaining("field 'evidence_contract.tool_evidence.InvoiceParser'")
                        .hasMessageContaining("duplicates tool 'invoiceParser'"));
    }

    @Test
    void allowsHashInEvidenceToolNameUntilPublicNameValidationIsImplemented() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/evidence-contract-hash-public-name-skill.yaml")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(YamlSkillCatalog.class)
                            .getSkill("evidence.hash.name.skill")
                            .evidenceContract()
                            .evidenceProducedByTool("review#skill"))
                            .containsExactly("review_result");
                });
    }

    @Test
    void failsStartupWhenOutputSchemaMaxRetriesIsPresentWithoutSchema() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/output-schema-max-retries-without-schema-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("output-schema-max-retries-without-schema-skill.yaml")
                        .hasMessageContaining("field 'output_schema_max_retries'")
                        .hasMessageContaining("may only be configured when output_schema is present"));
    }

    @Test
    void failsStartupWhenOutputSchemaUsesUnsupportedKeyword() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/output-schema-unsupported-keyword-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("output-schema-unsupported-keyword-skill.yaml")
                        .hasMessageContaining("field 'output_schema.properties.vendorName.oneOf'")
                        .hasMessageContaining("unknown field"));
    }

    @Test
    void failsStartupWhenOutputSchemaUsesEnumOnObjectSchema() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/output-schema-object-enum-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("output-schema-object-enum-skill.yaml")
                        .hasMessageContaining("field 'output_schema.enum'")
                        .hasMessageContaining("is only supported for string schemas in the MVP"));
    }

    @Test
    void failsStartupWhenRootSchemaIsNotObject() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/output-schema-root-array-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("output-schema-root-array-skill.yaml")
                        .hasMessageContaining("field 'output_schema.type'")
                        .hasMessageContaining("root output_schema type must be 'object'"));
    }

    @Test
    void failsStartupWhenRequiredFieldIsMissingFromProperties() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/output-schema-missing-required-property-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("output-schema-missing-required-property-skill.yaml")
                        .hasMessageContaining("field 'output_schema.required'")
                        .hasMessageContaining("references unknown property 'vendorName'"));
    }

    @Test
    void failsStartupWhenPropertiesDifferOnlyByCase() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/output-schema-duplicate-properties-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("output-schema-duplicate-properties-skill.yaml")
                        .hasMessageContaining("field 'output_schema.properties.VendorName'")
                        .hasMessageContaining("duplicates property 'vendorName'"));
    }

    @Test
    void defaultsAdditionalPropertiesToFalseWhenOmitted() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/output-schema-skill.yaml")
                .run(context -> {
                    YamlSkillCatalog catalog = context.getBean(YamlSkillCatalog.class);

                    assertThat(catalog.getSkill("output.schema.skill").manifest().getOutputSchema().getAdditionalProperties())
                            .isFalse();
                });
    }

    @Test
    void logsWarningForComplexButSupportedSchema(CapturedOutput output) {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/output-schema-complex-skill.yaml")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(YamlSkillCatalog.class).getSkill("output.schema.complex.skill")).isNotNull();
                    assertThat(output.getOut())
                            .contains("output_schema")
                            .contains("recommended");
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
                            .hasMessageContaining("unknown.mapping.field.skill")
                            .hasMessageContaining("unknown-mapping-field-skill.yaml")
                            .hasMessageContaining("field 'mapping.target_ids'")
                            .hasMessageContaining("unknown field")
                            .hasMessageContaining("mapping allows only target_id");
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

        @SkillMethod(description = "Deterministic target")
        String deterministicTarget(String input) {
            return "mapped:" + input;
        }
    }
}
