package com.lokiscale.bifrost.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lokiscale.bifrost.annotation.SkillMethod;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.ToolParam;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
