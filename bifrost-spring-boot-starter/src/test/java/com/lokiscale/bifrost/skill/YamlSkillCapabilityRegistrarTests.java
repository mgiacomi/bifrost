package com.lokiscale.bifrost.skill;

import com.lokiscale.bifrost.annotation.SkillMethod;
import com.lokiscale.bifrost.autoconfigure.BifrostAutoConfiguration;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.CapabilityRegistry;
import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.CapabilityExecutionRouter;
import com.lokiscale.bifrost.core.ExecutionCoordinator;
import com.lokiscale.bifrost.core.SkillImplementationTargetRegistry;
import com.lokiscale.bifrost.core.SkillImplementationTarget;
import com.lokiscale.bifrost.core.InMemoryCapabilityRegistry;
import com.lokiscale.bifrost.core.InMemorySkillImplementationTargetRegistry;
import com.lokiscale.bifrost.core.TestBifrostSessions;
import com.lokiscale.bifrost.runtime.input.SkillInputContract;
import com.lokiscale.bifrost.runtime.input.SkillInputContractResolver;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import com.lokiscale.bifrost.security.DefaultAccessGuard;
import com.lokiscale.bifrost.vfs.RefResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void exposesOnlySharedRegistryConstructor() {
        assertThat(YamlSkillCapabilityRegistrar.class.getConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterTypes()).containsExactly(
                        CapabilityRegistry.class,
                        SkillImplementationTargetRegistry.class,
                        YamlSkillCatalog.class,
                        SkillInputContractResolver.class));
    }

    @Test
    void rejectsCustomTargetRegistryReturningDifferentIdentity() {
        YamlSkillDefinition definition = definition("mapped.identity.skill", "targetBean#deterministicTarget");
        YamlSkillCatalog catalog = mock(YamlSkillCatalog.class);
        SkillImplementationTargetRegistry targetRegistry = mock(SkillImplementationTargetRegistry.class);
        when(catalog.getSkills()).thenReturn(List.of(definition));
        when(targetRegistry.getTarget("targetBean#deterministicTarget")).thenReturn(new SkillImplementationTarget(
                "otherBean#otherMethod",
                "wrong target",
                arguments -> "wrong",
                "{\"type\":\"object\"}",
                SkillInputContract.genericObject()));

        YamlSkillCapabilityRegistrar registrar = new YamlSkillCapabilityRegistrar(
                new InMemoryCapabilityRegistry(),
                targetRegistry,
                catalog,
                new SkillInputContractResolver());

        assertThatThrownBy(registrar::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mapped.identity.skill")
                .hasMessageContaining("otherBean#otherMethod")
                .hasMessageContaining("targetBean#deterministicTarget");
    }

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
                    assertThat(metadata.skillExecution().configured()).isFalse();
                    assertThat(metadata.skillExecution().frameworkModel()).isNull();
                    assertThat(metadata.skillExecution().provider()).isNull();
                    assertThat(metadata.skillExecution().providerModel()).isNull();
                    assertThat(metadata.skillExecution().thinkingLevel()).isNull();
                    assertThat(metadata.kind()).isEqualTo(com.lokiscale.bifrost.core.CapabilityKind.YAML_SKILL);
                    assertThat(metadata.mappedTargetId()).isEqualTo("targetBean#deterministicTarget");
                    assertThat(metadata.tool().inputSchema()).contains(parameterName);
                    assertThat(metadata.inputContract().kind())
                            .isEqualTo(com.lokiscale.bifrost.runtime.input.SkillInputContract.SkillInputContractKind.YAML_INHERITED);
                    assertThat(metadata.invoker().invoke(Map.of(parameterName, "alpha"))).isEqualTo("\"mapped:alpha\"");
                });
    }

    @Test
    void sameNamedYamlSkillAndJavaMethodRegisterInSeparateNamespaces() {
        contextRunner
                .withUserConfiguration(TargetBeanConfiguration.class)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/same-name-mapped-method-skill.yaml")
                .run(context -> {
                    CapabilityRegistry capabilityRegistry = context.getBean(CapabilityRegistry.class);
                    SkillImplementationTargetRegistry targetRegistry = context.getBean(SkillImplementationTargetRegistry.class);

                    assertThat(context).hasNotFailed();
                    assertThat(capabilityRegistry.getCapability("deterministicTarget"))
                            .isNotNull()
                            .extracting(CapabilityMetadata::kind)
                            .isEqualTo(com.lokiscale.bifrost.core.CapabilityKind.YAML_SKILL);
                    assertThat(capabilityRegistry.getAllCapabilities())
                            .extracting(CapabilityMetadata::name)
                            .containsExactly("deterministicTarget");
                    assertThat(capabilityRegistry.getCapability("targetBean#deterministicTarget")).isNull();
                    assertThat(targetRegistry.getTarget("targetBean#deterministicTarget")).isNotNull();
                });
    }

    @Test
    void multipleYamlSkillsCanShareOneTargetWithIndependentPublicMetadata() {
        contextRunner
                .withUserConfiguration(TargetBeanConfiguration.class)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/shared-target-*.yaml")
                .run(context -> {
                    CapabilityRegistry registry = context.getBean(CapabilityRegistry.class);
                    CapabilityMetadata first = registry.getCapability("shared.target.one");
                    CapabilityMetadata second = registry.getCapability("shared.target.two");

                    assertThat(first.mappedTargetId()).isEqualTo("targetBean#deterministicTarget");
                    assertThat(second.mappedTargetId()).isEqualTo("targetBean#deterministicTarget");
                    assertThat(first.description()).isNotEqualTo(second.description());
                    assertThat(first.rbacRoles()).containsExactly("ROLE_ONE");
                    assertThat(second.rbacRoles()).containsExactly("ROLE_TWO");
                    assertThat(first.inputContract().schema()).isEqualTo(second.inputContract().schema());
                    assertThat(first.implementationType()).isEqualTo(com.lokiscale.bifrost.core.PublicSkillImplementationType.MAPPED_JAVA);
                    assertThat(second.implementationType()).isEqualTo(com.lokiscale.bifrost.core.PublicSkillImplementationType.MAPPED_JAVA);

                    RefResolver refResolver = mock(RefResolver.class);
                    ExecutionStateService stateService = mock(ExecutionStateService.class);
                    BifrostSession session = TestBifrostSessions.withId("shared-target-session", 2);
                    CapabilityExecutionRouter router = new CapabilityExecutionRouter(
                            refResolver,
                            new StaticListableBeanFactory().getBeanProvider(ExecutionCoordinator.class),
                            stateService,
                            new DefaultAccessGuard());
                    when(refResolver.resolveArguments(any(), eq(session))).thenAnswer(invocation -> invocation.getArgument(0));

                    assertThat(router.execute(first, Map.of("input", "alpha"), session, authentication("ROLE_ONE")))
                            .isEqualTo("\"mapped:alpha\"");
                    assertThatThrownBy(() -> router.execute(first, Map.of("input", "alpha"), session, authentication("ROLE_TWO")))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThat(router.execute(second, Map.of("input", "beta"), session, authentication("ROLE_TWO")))
                            .isEqualTo("\"mapped:beta\"");
                    assertThatThrownBy(() -> router.execute(second, Map.of("input", "beta"), session, authentication("ROLE_ONE")))
                            .isInstanceOf(AccessDeniedException.class);
                });
    }

    private static UsernamePasswordAuthenticationToken authentication(String role) {
        return UsernamePasswordAuthenticationToken.authenticated(
                "user",
                "pw",
                AuthorityUtils.createAuthorityList(role));
    }

    @Test
    void mappedInvocationResolvesFinalAdvisedBean() {
        contextRunner
                .withUserConfiguration(AdvisedTargetBeanConfiguration.class)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/mapped-method-skill.yaml")
                .run(context -> {
                    CapabilityMetadata metadata = context.getBean(CapabilityRegistry.class).getCapability("mapped.method.skill");
                    String parameterName = getDeclaredMethod(TargetBean.class, "deterministicTarget", String.class)
                            .getParameters()[0].getName();

                    assertThat(metadata.invoker().invoke(Map.of(parameterName, "alpha"))).isEqualTo("\"mapped:alpha\"");
                    assertThat(context.getBean(AtomicInteger.class)).hasValue(1);
                    assertThat(context.getBean(AtomicBoolean.class)).isTrue();
                    assertThat(AopUtils.isAopProxy(context.getBean("targetBean"))).isTrue();
                });
    }

    @Test
    void initializesReferencedLazyAndPrototypeTargetsBeforeMappingResolution() {
        contextRunner
                .withUserConfiguration(ScopedTargetBeanConfiguration.class)
                .withPropertyValues(
                        "bifrost.skills.locations=classpath:/skills/valid/lazy-mapped-method-skill.yaml,classpath:/skills/valid/prototype-mapped-method-skill.yaml")
                .run(context -> {
                    CapabilityRegistry registry = context.getBean(CapabilityRegistry.class);
                    SkillImplementationTargetRegistry targets = context.getBean(SkillImplementationTargetRegistry.class);

                    assertThat(targets.getTarget("lazyTargetBean#execute")).isNotNull();
                    assertThat(targets.getTarget("prototypeTargetBean#execute")).isNotNull();
                    assertThat(registry.getCapability("lazy.mapped.skill").invoker().invoke(Map.of("input", "one")))
                            .isEqualTo("\"lazy:one\"");
                    assertThat(registry.getCapability("prototype.mapped.skill").invoker().invoke(Map.of("input", "two")))
                            .isEqualTo("\"prototype:two\"");
                    assertThat(registry.getCapability("prototype.mapped.skill").invoker().invoke(Map.of("input", "three")))
                            .isEqualTo("\"prototype:three\"");
                });
    }

    @Test
    void mappedYamlSkillWithoutInputSchemaInheritsJavaDerivedContract() {
        contextRunner
                .withUserConfiguration(TargetBeanConfiguration.class)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/mapped-method-skill.yaml")
                .run(context -> {
                    CapabilityMetadata metadata = context.getBean(CapabilityRegistry.class).getCapability("mapped.method.skill");

                    assertThat(metadata.inputContract().schema().required()).containsExactly("input");
                    assertThat(metadata.inputContract().schema().properties()).containsKey("input");
                });
    }

    @Test
    void explicitYamlInputSchemaPublishesConcreteToolSchemaForUnmappedSkill() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/input-schema-skill.yaml")
                .run(context -> {
                    CapabilityMetadata metadata = context.getBean(CapabilityRegistry.class).getCapability("input.schema.skill");

                    assertThat(metadata.inputContract().kind())
                            .isEqualTo(com.lokiscale.bifrost.runtime.input.SkillInputContract.SkillInputContractKind.YAML_EXPLICIT);
                    assertThat(metadata.tool().inputSchema()).contains("payload");
                    assertThat(metadata.tool().inputSchema()).doesNotContain("\"properties\":{}");
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

    @Test
    void mappedSerializationFailureUsesPublicYamlNameAtBoundary() {
        contextRunner
                .withUserConfiguration(UnserializableTargetBeanConfiguration.class)
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/mapped-method-skill.yaml")
                .run(context -> {
                    CapabilityMetadata metadata = context.getBean(CapabilityRegistry.class)
                            .getCapability("mapped.method.skill");

                    assertThatThrownBy(() -> metadata.invoker().invoke(Map.of("input", "alpha")))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("mapped.method.skill")
                            .hasMessageNotContaining("targetBean#deterministicTarget")
                            .satisfies(error -> assertThat(error.getCause())
                                    .hasMessageContaining("targetBean#deterministicTarget"));
                });
    }

    @Test
    void failsStartupWhenMappedYamlSkillReferencesUnknownTargetId() {
        contextRunner
                .withPropertyValues("bifrost.skills.locations=classpath:/skills/invalid/unknown-mapped-target-skill.yaml")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("unknown.mapped.target.skill")
                        .hasMessageContaining("unknown-mapped-target-skill.yaml")
                        .hasMessageContaining("field 'mapping.target_id'")
                        .hasMessageContaining("unknown implementation target 'missingBean#missingTarget'")
                        .hasMessageContaining("correct mapping.target_id to reference a registered bean#method target"));
    }

    private static YamlSkillDefinition definition(String name, String targetId) {
        YamlSkillManifest manifest = new YamlSkillManifest();
        manifest.setName(name);
        manifest.setDescription(name);
        YamlSkillManifest.MappingManifest mapping = new YamlSkillManifest.MappingManifest();
        mapping.setTargetId(targetId);
        manifest.setMapping(mapping);
        return new YamlSkillDefinition(
                new org.springframework.core.io.ByteArrayResource(new byte[0]),
                manifest,
                null);
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

        @SkillMethod(description = "Deterministic target")
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

        @SkillMethod(description = "Deterministic target")
        String deterministicTarget(String input) {
            throw new IllegalStateException("wrapper", new IllegalArgumentException("mapped boom"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UnserializableTargetBeanConfiguration {
        @Bean
        UnserializableTargetBean targetBean() {
            return new UnserializableTargetBean();
        }
    }

    static class UnserializableTargetBean {
        @SkillMethod(description = "Unserializable target")
        CyclicResult deterministicTarget(String input) {
            return new CyclicResult(input);
        }
    }

    static class CyclicResult {
        private final String value;

        CyclicResult(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public CyclicResult getSelf() {
            return this;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ScopedTargetBeanConfiguration {
        @Bean
        @Lazy
        LazyTargetBean lazyTargetBean() {
            return new LazyTargetBean();
        }

        @Bean
        @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
        PrototypeTargetBean prototypeTargetBean() {
            return new PrototypeTargetBean();
        }
    }

    static class LazyTargetBean {
        @SkillMethod(description = "Lazy target")
        String execute(String input) {
            return "lazy:" + input;
        }
    }

    static class PrototypeTargetBean {
        @SkillMethod(description = "Prototype target")
        String execute(String input) {
            return "prototype:" + input;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AdvisedTargetBeanConfiguration {
        @Bean
        AtomicInteger adviceCalls() {
            return new AtomicInteger();
        }

        @Bean
        AtomicBoolean rawBeanObservedBeforeProxying() {
            return new AtomicBoolean();
        }

        @Bean
        TargetBean targetBean() {
            return new TargetBean();
        }

        @Bean
        @DependsOn("skillMethodBeanPostProcessor")
        static BeanPostProcessor lateTargetProxyPostProcessor(
                AtomicInteger adviceCalls,
                AtomicBoolean rawBeanObservedBeforeProxying) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!"targetBean".equals(beanName)) {
                        return bean;
                    }
                    rawBeanObservedBeforeProxying.set(!AopUtils.isAopProxy(bean));
                    ProxyFactory factory = new ProxyFactory(bean);
                    factory.setProxyTargetClass(true);
                    factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
                        adviceCalls.incrementAndGet();
                        return invocation.proceed();
                    });
                    return factory.getProxy();
                }
            };
        }
    }
}
