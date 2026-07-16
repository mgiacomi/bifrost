package com.lokiscale.bifrost.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.lokiscale.bifrost.autoconfigure.BifrostAutoConfiguration;
import com.lokiscale.bifrost.internal.chat.SkillChatModelResolver;
import com.lokiscale.bifrost.internal.security.AccessGuard;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class BifrostAutoConfigurationBoundaryTest
{
    private static final Set<String> FRAMEWORK_OWNED_BEAN_FACTORIES = Set.of(
            "capabilityRegistry", "skillImplementationTargetRegistry", "bifrostExceptionTransformer",
            "skillMethodBeanPostProcessor", "bifrostSessionRunner", "yamlSkillCatalog",
            "yamlSkillCapabilityRegistrar", "skillInputContractResolver", "skillInputValidator",
            "accessGuard", "skillVisibilityResolver", "virtualFileSystem", "refResolver",
            "missionInputMaterializer", "missionUserMessageSender", "capabilityExecutionRouter",
            "skillTemplate", "planTaskLinker", "modelUsageExtractor", "usageMetricsRecorder",
            "sessionUsageService", "executionStateService", "planningService", "toolSurfaceService",
            "toolCallbackFactory", "bifrostMissionExecutor", "missionExecutionEngine",
            "namedAiConnectionRegistry", "skillChatModelResolver", "openAiSkillChatOptionsAdapter",
            "anthropicSkillChatOptionsAdapter", "geminiSkillChatOptionsAdapter",
            "ollamaSkillChatOptionsAdapter", "skillAdvisorResolver", "skillChatClientFactory",
            "stepLoopMissionExecutionEngine", "executionCoordinator");

    private static final Set<String> SUPPORTED_BIFROST_BEAN_OVERRIDES = Set.of();

    private final Set<String> productionClassNames = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.lokiscale.bifrost")
            .stream()
            .map(javaClass -> javaClass.getName())
            .collect(Collectors.toSet());

    @Test
    void supportedBifrostBeanOverrideAllowlistIsEmpty()
    {
        assertThat(SUPPORTED_BIFROST_BEAN_OVERRIDES).isEmpty();
    }

    @Test
    void everyBeanFactoryIsClassifiedAndPackagePrivate()
    {
        var beanMethods = Arrays.stream(BifrostAutoConfiguration.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .toList();

        assertThat(beanMethods.stream().map(method -> method.getName()).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(FRAMEWORK_OWNED_BEAN_FACTORIES);
        assertThat(beanMethods)
                .allSatisfy(method -> assertThat(method.getModifiers())
                        .as("Framework-owned bean method %s must not be public or protected", method.getName())
                        .matches(modifiers -> !Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers)));
    }

    @Test
    void productionTypesDoNotUseConditionalOnMissingBean() throws Exception
    {
        List<String> offenders = new ArrayList<>();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        for (String className : productionClassNames)
        {
            Class<?> type = Class.forName(className, false, classLoader);
            if (hasAnnotationOrMetaAnnotation(type, ConditionalOnMissingBean.class, new HashSet<>()))
            {
                offenders.add(type.getName());
            }
            Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> hasAnnotationOrMetaAnnotation(
                            method, ConditionalOnMissingBean.class, new HashSet<>()))
                    .map(method -> type.getName() + "#" + method.getName())
                    .forEach(offenders::add);
        }

        assertThat(offenders)
                .as("No production Bifrost type or method may declare a direct or composed @ConditionalOnMissingBean replacement seam")
                .isEmpty();
    }

    @Test
    void accessGuardAndChatModelResolverAreInternalFrameworkOwnedTypes()
    {
        assertThat(AccessGuard.class.getPackageName()).startsWith("com.lokiscale.bifrost.internal.");
        assertThat(SkillChatModelResolver.class.getPackageName()).startsWith("com.lokiscale.bifrost.internal.");
        assertThat(FRAMEWORK_OWNED_BEAN_FACTORIES).contains("accessGuard", "skillChatModelResolver");
    }

    private boolean hasAnnotationOrMetaAnnotation(AnnotatedElement element,
            Class<? extends Annotation> target,
            Set<Class<? extends Annotation>> visited)
    {
        for (Annotation annotation : element.getDeclaredAnnotations())
        {
            Class<? extends Annotation> annotationType = annotation.annotationType();
            if (annotationType.equals(target))
            {
                return true;
            }
            if (visited.add(annotationType)
                    && hasAnnotationOrMetaAnnotation(annotationType, target, visited))
            {
                return true;
            }
        }
        return false;
    }
}
