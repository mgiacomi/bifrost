package com.lokiscale.bifrost.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lokiscale.bifrost.annotation.SkillMethod;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class SkillMethodBeanPostProcessorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registersAnnotatedMethodAsCapability() {
        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        SkillMethodBeanPostProcessor processor = new SkillMethodBeanPostProcessor(registry);

        processor.postProcessAfterInitialization(new RegistrationBean(), "registrationBean");

        CapabilityMetadata metadata = registry.getCapability("testOperation");
        assertThat(metadata).isNotNull();
        assertThat(metadata.id()).isEqualTo("registrationBean#mappedOperation");
        assertThat(metadata.name()).isEqualTo("testOperation");
        assertThat(metadata.description()).isEqualTo("Test desc");
        assertThat(metadata.modelPreference()).isEqualTo(ModelPreference.HEAVY);
        assertThat(metadata.skillExecution().configured()).isFalse();
        assertThat(registry.getCapability("nonSkillOperation")).isNull();
        assertThat(registry.getAllCapabilities()).hasSize(1);
    }

    @Test
    void invokesCapabilityUsingEnvelopeMap() throws JsonProcessingException {
        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        SkillMethodBeanPostProcessor processor = new SkillMethodBeanPostProcessor(registry);

        processor.postProcessAfterInitialization(new InvocationBean(), "invocationBean");

        CapabilityMetadata metadata = registry.getCapability("combineValues");
        Method method = getDeclaredMethod(InvocationBean.class, "combineValues", String.class, Boolean.class);
        Map<String, Object> arguments = Map.of(
                method.getParameters()[0].getName(), "alpha",
                method.getParameters()[1].getName(), true);

        Object rawResult = metadata.invoker().invoke(arguments);

        assertThat(rawResult).isInstanceOf(String.class);
        assertThat(objectMapper.readValue((String) rawResult, String.class)).isEqualTo("alpha:true");
    }

    @Test
    void handlesMissingOptionalParameterWithoutCrashing() throws JsonProcessingException {
        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        SkillMethodBeanPostProcessor processor = new SkillMethodBeanPostProcessor(registry);

        processor.postProcessAfterInitialization(new OptionalInvocationBean(), "optionalInvocationBean");

        CapabilityMetadata metadata = registry.getCapability("optionalValues");
        Method method = getDeclaredMethod(OptionalInvocationBean.class, "optionalValues", String.class, String.class);
        Map<String, Object> arguments = Map.of(method.getParameters()[0].getName(), "base");

        Object rawResult = metadata.invoker().invoke(arguments);

        assertThat(rawResult).isInstanceOf(String.class);
        assertThat(objectMapper.readValue((String) rawResult, String.class)).isEqualTo("base:default");
    }

    @Test
    void returnsTransformedErrorWhenSkillMethodThrowsWrappedBusinessException() {
        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        SkillMethodBeanPostProcessor processor = new SkillMethodBeanPostProcessor(registry);

        processor.postProcessAfterInitialization(new ThrowingInvocationBean(), "throwingInvocationBean");

        CapabilityMetadata metadata = registry.getCapability("throwingOperation");

        assertThat(metadata.invoker().invoke(Map.of())).isEqualTo("ERROR: IllegalArgumentException. HINT: boom");
    }

    @Test
    void logsStackTraceButOmitsItFromReturnedPayload(CapturedOutput output) {
        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        SkillMethodBeanPostProcessor processor = new SkillMethodBeanPostProcessor(registry);

        processor.postProcessAfterInitialization(new ThrowingInvocationBean(), "throwingInvocationBean");

        CapabilityMetadata metadata = registry.getCapability("throwingOperation");
        Object rawResult = metadata.invoker().invoke(Map.of());

        assertThat(rawResult).isEqualTo("ERROR: IllegalArgumentException. HINT: boom");
        assertThat(output.getAll()).contains("Capability 'throwingOperation' failed during deterministic execution");
        assertThat(output.getAll()).contains("java.lang.IllegalStateException: wrapper");
        assertThat(output.getAll()).contains("Caused by: java.lang.IllegalArgumentException: boom");
        assertThat(rawResult.toString()).doesNotContain("at com.lokiscale");
        assertThat(rawResult.toString()).doesNotContain("IllegalStateException: wrapper");
    }

    private static Method getDeclaredMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getDeclaredMethod(name, parameterTypes);
        }
        catch (NoSuchMethodException ex) {
            throw new IllegalStateException("Test fixture method lookup failed", ex);
        }
    }

    static class RegistrationBean {

        @SkillMethod(name = "testOperation", description = "Test desc", modelPreference = ModelPreference.HEAVY)
        String mappedOperation() {
            return "ok";
        }

        void nonSkillOperation() {
        }
    }

    static class InvocationBean {

        @SkillMethod(name = "combineValues", description = "Combine values")
        String combineValues(String left, Boolean right) {
            return left + ":" + right;
        }
    }

    static class OptionalInvocationBean {

        @SkillMethod(name = "optionalValues", description = "Optional values")
        String optionalValues(String required, @ToolParam(required = false) String optional) {
            return required + ":" + (optional == null ? "default" : optional);
        }
    }

    static class ThrowingInvocationBean {

        @SkillMethod(name = "throwingOperation", description = "Throwing operation")
        String throwingOperation() {
            throw new IllegalStateException("wrapper", new IllegalArgumentException("boom"));
        }
    }
}
