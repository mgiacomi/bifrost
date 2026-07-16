package com.lokiscale.bifrost.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class BifrostPublicSurfaceArchitectureTest
{
    private static final Set<String> API_TYPES = Set.of(
            "com.lokiscale.bifrost.api.SkillTemplate",
            "com.lokiscale.bifrost.api.SkillExecutionView",
            "com.lokiscale.bifrost.api.SkillExecutionEvent",
            "com.lokiscale.bifrost.api.SkillMethod",
            "com.lokiscale.bifrost.api.SkillException",
            "com.lokiscale.bifrost.api.SkillInputValidationException",
            "com.lokiscale.bifrost.api.SkillInputValidationIssue");

    private static final Set<String> FRAMEWORK_INTEGRATION_TYPES = Set.of(
            "com.lokiscale.bifrost.autoconfigure.BifrostAutoConfiguration",
            "com.lokiscale.bifrost.autoconfigure.BifrostProperties",
            "com.lokiscale.bifrost.autoconfigure.ExecutionTraceProperties",
            "com.lokiscale.bifrost.autoconfigure.AiDriver");

    private static final Map<String, String> TECHNICALLY_PUBLIC_INTERNAL_TYPES = Map.ofEntries(
            Map.entry("com.lokiscale.bifrost.internal.autoconfigure.AiConnectionChatModelFactory", "Public only so BifrostAutoConfiguration can construct this type across the Spring integration boundary."),
            Map.entry("com.lokiscale.bifrost.internal.autoconfigure.AnthropicConnectionChatModelFactory", "Public only so BifrostAutoConfiguration can construct this type across the Spring integration boundary."),
            Map.entry("com.lokiscale.bifrost.internal.autoconfigure.GeminiConnectionChatModelFactory", "Public only so BifrostAutoConfiguration can construct this type across the Spring integration boundary."),
            Map.entry("com.lokiscale.bifrost.internal.autoconfigure.NamedAiConnectionRegistry", "Public only so BifrostAutoConfiguration can construct this type across the Spring integration boundary."),
            Map.entry("com.lokiscale.bifrost.internal.autoconfigure.OllamaConnectionChatModelFactory", "Public only so BifrostAutoConfiguration can construct this type across the Spring integration boundary."),
            Map.entry("com.lokiscale.bifrost.internal.autoconfigure.OpenAiConnectionChatModelFactory", "Public only so BifrostAutoConfiguration can construct this type across the Spring integration boundary."),
            Map.entry("com.lokiscale.bifrost.internal.chat.DefaultSkillAdvisorResolver", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.chat.DefaultSkillChatModelResolver", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.chat.SkillAdvisorResolver", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.chat.SkillChatClientFactory", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.chat.SkillChatModelResolver", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.chat.SkillChatOptionsAdapter", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.chat.SpringAiSkillChatClientFactory", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.AdvisorTraceContext", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.AdvisorTraceFact", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.AdvisorTraceRecorder", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.BifrostExceptionTransformer", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.BifrostSession", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.BifrostSessionRunner", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.BifrostStackOverflowException", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.CapabilityExecutionRouter", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.CapabilityInvoker", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.CapabilityKind", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.CapabilityMetadata", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.CapabilityRegistry", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.CapabilityToolDescriptor", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.DefaultBifrostExceptionTransformer", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.DefaultExecutionTraceRecorder", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.DefaultPlanTaskLinker", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.ExecutionCoordinator", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.ExecutionFrame", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.ExecutionJournal", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.ExecutionPlan", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.ExecutionTrace", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.ExecutionTraceHandle", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.ExecutionTraceReader", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.ExecutionTraceRecorder", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.InMemoryCapabilityRegistry", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.InMemorySkillImplementationTargetRegistry", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.JournalEntry", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.JournalEntryType", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.JournalLevel", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.MissionInputMessageFormatter", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.ModelExecutionIdentity", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.ModelTraceCallback", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.ModelTraceContext", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.ModelTraceResult", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.OperationType", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.PlanStatus", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.PlanTask", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.PlanTaskLinker", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.PlanTaskStatus", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.PublicSkillImplementationType", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.SessionContextRunner", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.SkillExecutionDescriptor", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.SkillImplementationTarget", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.SkillImplementationTargetRegistry", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.SkillMethodBeanPostProcessor", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.TaskExecutionEvent", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.ToolTraceContext", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.TraceCompletion", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.TraceFailureMetadata", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.TraceFrameType", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.TracePersistencePolicy", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.TraceRecord", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.core.TraceRecordType", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.linter.LinterCallAdvisor", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.linter.LinterOutcome", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.linter.LinterOutcomeRecorder", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.linter.LinterOutcomeStatus", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.outputschema.OutputSchemaCallAdvisor", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.outputschema.OutputSchemaFailureMode", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.outputschema.OutputSchemaOutcome", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.outputschema.OutputSchemaOutcomeRecorder", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.outputschema.OutputSchemaOutcomeStatus", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.outputschema.OutputSchemaPromptAugmentor", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.outputschema.OutputSchemaValidationIssue", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.outputschema.OutputSchemaValidationResult", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.outputschema.OutputSchemaValidator", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.attachment.BifrostAttachment", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.attachment.DefaultMissionInputMaterializer", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.attachment.MissionInputMaterializer", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.attachment.MissionUserMessageSender", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.attachment.RenderedMissionInput", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.attachment.SpringAiMissionUserMessageSender", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.BifrostMissionTimeoutException", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.BifrostQuotaExceededException", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.DefaultMissionExecutionEngine", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.evidence.EvidenceBackedOutputValidator", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.evidence.EvidenceContract", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.evidence.EvidenceContractCallAdvisor", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.evidence.EvidenceCoverageIssue", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.evidence.EvidenceCoverageResult", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.evidence.EvidenceCoverageValidator", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.input.SkillInputContract", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.input.SkillInputContractResolver", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.input.SkillInputPromptRenderer", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.input.SkillInputSchemaNode", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.input.SkillInputValidationIssue", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.input.SkillInputValidationResult", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.input.SkillInputValidator", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.MissionExecutionEngine", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.planning.DefaultPlanningService", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.planning.PlanningService", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.prompt.SkillPromptComposer", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.prompt.SkillPromptComposition", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.state.DefaultExecutionStateService", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.state.EvidenceSnapshot", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.state.ExecutionStateService", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.state.PlanSnapshot", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.step.StepLoopMissionExecutionEngine", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.tool.DefaultToolCallbackFactory", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.tool.DefaultToolSurfaceService", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.tool.ToolCallbackFactory", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.tool.ToolCallbackInputContracts", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.tool.ToolSurfaceService", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.trace.DefaultExecutionTraceHandle", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.trace.ExecutionJournalProjector", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.usage.DefaultSessionUsageService", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.usage.GuardrailType", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.usage.MicrometerUsageMetricsRecorder", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.usage.ModelUsageExtractor", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.usage.ModelUsageRecord", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.usage.NoOpSessionUsageService", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.usage.NoOpUsageMetricsRecorder", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.usage.SessionUsageService", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.usage.SessionUsageSnapshot", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.usage.UsageMetricsRecorder", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.runtime.usage.UsagePrecision", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.security.AccessGuard", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.security.DefaultAccessGuard", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.skill.DefaultSkillVisibilityResolver", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.skill.EffectiveSkillExecutionConfiguration", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.skill.SkillVisibilityResolver", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.skill.YamlSkillCapabilityRegistrar", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.skill.YamlSkillCatalog", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.skill.YamlSkillDefinition", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.skill.YamlSkillManifest", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.skillapi.DefaultSkillTemplate", "Public only so BifrostAutoConfiguration can construct the application facade implementation."),
            Map.entry("com.lokiscale.bifrost.internal.vfs.DefaultRefResolver", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.vfs.RefResolver", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.vfs.SessionLocalVirtualFileSystem", "Public only for Java collaboration between distinct internal subsystem packages."),
            Map.entry("com.lokiscale.bifrost.internal.vfs.VirtualFileSystem", "Public only for Java collaboration between distinct internal subsystem packages."));

    private final Set<JavaClass> productionClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.lokiscale.bifrost")
            .stream()
            .filter(javaClass -> !javaClass.isNestedClass())
            .collect(Collectors.toSet());

    @Test
    void apiPackageContainsExactlySevenApprovedPublicTypes()
    {
        assertThat(publicTopLevelTypesIn("com.lokiscale.bifrost.api"))
                .containsExactlyInAnyOrderElementsOf(API_TYPES);
    }

    @Test
    void autoconfigurePackageContainsExactlyFourIntegrationTypes()
    {
        assertThat(publicTopLevelTypesIn("com.lokiscale.bifrost.autoconfigure"))
                .containsExactlyInAnyOrderElementsOf(FRAMEWORK_INTEGRATION_TYPES);
    }

    @Test
    void everyExternallyAccessibleTopLevelTypeIsClassified()
    {
        Set<String> exposed = productionClasses.stream()
                .filter(this::isPublic)
                .map(JavaClass::getName)
                .filter(name -> !name.startsWith("com.lokiscale.bifrost.internal."))
                .collect(Collectors.toSet());

        assertThat(exposed)
                .as("Every externally accessible top-level type must be in the closed API or framework-integration allowlist")
                .containsExactlyInAnyOrderElementsOf(Stream.concat(
                                API_TYPES.stream(), FRAMEWORK_INTEGRATION_TYPES.stream())
                        .collect(Collectors.toSet()));
    }

    @Test
    void technicallyPublicInternalTypesHaveNonblankReasons()
    {
        Set<String> actual = productionClasses.stream()
                .filter(this::isPublic)
                .map(JavaClass::getName)
                .filter(name -> name.startsWith("com.lokiscale.bifrost.internal."))
                .collect(Collectors.toSet());

        assertThat(actual)
                .as("Every technically public internal type must be deliberately allowlisted")
                .containsExactlyInAnyOrderElementsOf(TECHNICALLY_PUBLIC_INTERNAL_TYPES.keySet());
        assertThat(TECHNICALLY_PUBLIC_INTERNAL_TYPES)
                .allSatisfy((name, reason) -> assertThat(reason)
                        .as("classification reason for %s", name)
                        .isNotBlank());
    }

    @Test
    void noSupportedSpiPackageOrTypeExists()
    {
        assertThat(productionClasses.stream().map(JavaClass::getPackageName))
                .noneMatch(packageName -> packageName.contains(".spi"));
    }

    @Test
    void apiSignaturesRecursivelyExcludeInternalAndAutoconfigureTypes() throws Exception
    {
        for (String typeName : API_TYPES)
        {
            Class<?> apiType = Class.forName(typeName);
            assertAnnotationsAreApiSafe(apiType, apiType.getName());
            assertApiSafe(apiType.getGenericSuperclass(), apiType.getName() + " superclass", new LinkedHashSet<>());
            for (Type interfaceType : apiType.getGenericInterfaces())
            {
                assertApiSafe(interfaceType, apiType.getName() + " interface", new LinkedHashSet<>());
            }

            for (var field : apiType.getDeclaredFields())
            {
                if (Modifier.isPublic(field.getModifiers()) || Modifier.isProtected(field.getModifiers()))
                {
                    assertApiSafe(field.getGenericType(), field.toString(), new LinkedHashSet<>());
                    assertAnnotationsAreApiSafe(field, field.toString());
                }
            }
            for (var constructor : apiType.getDeclaredConstructors())
            {
                if (Modifier.isPublic(constructor.getModifiers()) || Modifier.isProtected(constructor.getModifiers()))
                {
                    assertExecutableIsApiSafe(constructor, constructor.toString());
                }
            }
            for (var method : apiType.getDeclaredMethods())
            {
                if (Modifier.isPublic(method.getModifiers()) || Modifier.isProtected(method.getModifiers()))
                {
                    assertApiSafe(method.getGenericReturnType(), method.toString(), new LinkedHashSet<>());
                    assertExecutableIsApiSafe(method, method.toString());
                }
            }
            if (apiType.isRecord())
            {
                for (var component : apiType.getRecordComponents())
                {
                    assertApiSafe(component.getGenericType(), component.toString(), new LinkedHashSet<>());
                    assertAnnotationsAreApiSafe(component, component.toString());
                }
            }
        }
    }

    private Set<String> publicTopLevelTypesIn(String packageName)
    {
        return productionClasses.stream()
                .filter(this::isPublic)
                .filter(javaClass -> javaClass.getPackageName().equals(packageName))
                .map(JavaClass::getName)
                .collect(Collectors.toSet());
    }

    private boolean isPublic(JavaClass javaClass)
    {
        return javaClass.getModifiers().contains(JavaModifier.PUBLIC);
    }

    private void assertExecutableIsApiSafe(java.lang.reflect.Executable executable, String owner)
    {
        for (Type parameter : executable.getGenericParameterTypes())
        {
            assertApiSafe(parameter, owner, new LinkedHashSet<>());
        }
        for (Type exception : executable.getGenericExceptionTypes())
        {
            assertApiSafe(exception, owner, new LinkedHashSet<>());
        }
        assertAnnotationsAreApiSafe(executable, owner);
        Arrays.stream(executable.getParameterAnnotations())
                .flatMap(Arrays::stream)
                .map(Annotation::annotationType)
                .forEach(type -> assertClassIsApiSafe(type, owner));
    }

    private void assertApiSafe(Type type, String owner, Set<Type> visited)
    {
        if (type == null || !visited.add(type))
        {
            return;
        }
        if (type instanceof Class<?> clazz)
        {
            assertClassIsApiSafe(clazz, owner);
            if (clazz.isArray())
            {
                assertApiSafe(clazz.getComponentType(), owner, visited);
            }
        }
        else if (type instanceof ParameterizedType parameterized)
        {
            assertApiSafe(parameterized.getRawType(), owner, visited);
            assertApiSafe(parameterized.getOwnerType(), owner, visited);
            for (Type argument : parameterized.getActualTypeArguments())
            {
                assertApiSafe(argument, owner, visited);
            }
        }
        else if (type instanceof GenericArrayType array)
        {
            assertApiSafe(array.getGenericComponentType(), owner, visited);
        }
        else if (type instanceof WildcardType wildcard)
        {
            Stream.concat(Arrays.stream(wildcard.getUpperBounds()), Arrays.stream(wildcard.getLowerBounds()))
                    .forEach(bound -> assertApiSafe(bound, owner, visited));
        }
        else if (type instanceof TypeVariable<?> variable)
        {
            Arrays.stream(variable.getBounds()).forEach(bound -> assertApiSafe(bound, owner, visited));
        }
    }

    private void assertAnnotationsAreApiSafe(AnnotatedElement element, String owner)
    {
        Arrays.stream(element.getAnnotations())
                .map(Annotation::annotationType)
                .forEach(type -> assertClassIsApiSafe(type, owner));
    }

    private void assertClassIsApiSafe(Class<?> type, String owner)
    {
        Class<?> inspected = type.isArray() ? type.getComponentType() : type;
        if (inspected.getName().startsWith("com.lokiscale.bifrost."))
        {
            assertThat(inspected.getPackageName())
                    .as("Public API signature %s leaks Bifrost type %s", owner, inspected.getName())
                    .isEqualTo("com.lokiscale.bifrost.api");
        }
    }
}
