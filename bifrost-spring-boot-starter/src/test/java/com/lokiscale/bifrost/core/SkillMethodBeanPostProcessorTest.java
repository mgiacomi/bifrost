package com.lokiscale.bifrost.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lokiscale.bifrost.annotation.SkillMethod;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

    @Test
    void readsResourceBackedRefsIntoStringParameters() throws JsonProcessingException {
        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        SkillMethodBeanPostProcessor processor = new SkillMethodBeanPostProcessor(registry);

        processor.postProcessAfterInitialization(new RefStringInvocationBean(), "refStringInvocationBean");

        CapabilityMetadata metadata = registry.getCapability("readRefAsString");
        Method method = getDeclaredMethod(RefStringInvocationBean.class, "readRefAsString", String.class);
        Object rawResult = metadata.invoker().invoke(Map.of(
                method.getParameters()[0].getName(),
                new ByteArrayResource("hello text".getBytes(StandardCharsets.UTF_8))));

        assertThat(objectMapper.readValue((String) rawResult, String.class)).isEqualTo("hello text");
    }

    @Test
    void readsResourceBackedRefsIntoByteArrayParameters() throws JsonProcessingException {
        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        SkillMethodBeanPostProcessor processor = new SkillMethodBeanPostProcessor(registry);

        processor.postProcessAfterInitialization(new RefBytesInvocationBean(), "refBytesInvocationBean");

        CapabilityMetadata metadata = registry.getCapability("readRefAsBytes");
        Method method = getDeclaredMethod(RefBytesInvocationBean.class, "readRefAsBytes", byte[].class);
        Object rawResult = metadata.invoker().invoke(Map.of(
                method.getParameters()[0].getName(),
                new ByteArrayResource(new byte[]{0x01, 0x02, 0x03})));

        assertThat(objectMapper.readValue((String) rawResult, String.class)).isEqualTo("010203");
    }

    @Test
    void passesResourceBackedRefsThroughResourceAndInputStreamParameters() throws JsonProcessingException {
        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        SkillMethodBeanPostProcessor processor = new SkillMethodBeanPostProcessor(registry);

        processor.postProcessAfterInitialization(new RefResourceInvocationBean(), "refResourceInvocationBean");
        processor.postProcessAfterInitialization(new RefStreamInvocationBean(), "refStreamInvocationBean");

        CapabilityMetadata resourceMetadata = registry.getCapability("readRefAsResource");
        CapabilityMetadata streamMetadata = registry.getCapability("readRefAsStream");
        Method resourceMethod = getDeclaredMethod(RefResourceInvocationBean.class, "readRefAsResource", Resource.class);
        Method streamMethod = getDeclaredMethod(RefStreamInvocationBean.class, "readRefAsStream", InputStream.class);

        Object resourceResult = resourceMetadata.invoker().invoke(Map.of(
                resourceMethod.getParameters()[0].getName(),
                new ByteArrayResource("hello resource".getBytes(StandardCharsets.UTF_8))));
        Object streamResult = streamMetadata.invoker().invoke(Map.of(
                streamMethod.getParameters()[0].getName(),
                new ByteArrayResource("hello stream".getBytes(StandardCharsets.UTF_8))));

        assertThat(objectMapper.readValue((String) resourceResult, String.class)).isEqualTo("hello resource");
        assertThat(objectMapper.readValue((String) streamResult, String.class)).isEqualTo("hello stream");
    }

    @Test
    void materializesNestedResourceLeavesInsideTypedRecordParameters() throws JsonProcessingException {
        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        SkillMethodBeanPostProcessor processor = new SkillMethodBeanPostProcessor(registry);

        processor.postProcessAfterInitialization(new NestedRecordInvocationBean(), "nestedRecordInvocationBean");

        CapabilityMetadata metadata = registry.getCapability("readNestedRecord");
        Method method = getDeclaredMethod(NestedRecordInvocationBean.class, "readNestedRecord", NestedPayload.class);
        Object rawResult = metadata.invoker().invoke(Map.of(
                method.getParameters()[0].getName(),
                Map.of(
                        "document", Map.of(
                                "title", new ByteArrayResource("hello title".getBytes(StandardCharsets.UTF_8)),
                                "attachments", List.of(
                                        new ByteArrayResource(new byte[]{0x01, 0x02}),
                                        new ByteArrayResource(new byte[]{0x0A, 0x0B}))))));

        assertThat(objectMapper.readValue((String) rawResult, String.class)).isEqualTo("hello title|0102,0a0b");
    }

    @Test
    void materializesResourceLeavesInsideTypedCollectionParameters() throws JsonProcessingException {
        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        SkillMethodBeanPostProcessor processor = new SkillMethodBeanPostProcessor(registry);

        processor.postProcessAfterInitialization(new TypedCollectionInvocationBean(), "typedCollectionInvocationBean");

        CapabilityMetadata metadata = registry.getCapability("readTypedCollection");
        Method method = getDeclaredMethod(TypedCollectionInvocationBean.class, "readTypedCollection", List.class);
        Object rawResult = metadata.invoker().invoke(Map.of(
                method.getParameters()[0].getName(),
                List.of(
                        new ByteArrayResource("alpha".getBytes(StandardCharsets.UTF_8)),
                        new ByteArrayResource("beta".getBytes(StandardCharsets.UTF_8)),
                        new ByteArrayResource("gamma".getBytes(StandardCharsets.UTF_8)))));

        assertThat(objectMapper.readValue((String) rawResult, String.class)).isEqualTo("alpha|beta|gamma");
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

    static class RefStringInvocationBean {

        @SkillMethod(name = "readRefAsString", description = "Read ref as string")
        String readRefAsString(String payload) {
            return payload;
        }
    }

    static class RefBytesInvocationBean {

        @SkillMethod(name = "readRefAsBytes", description = "Read ref as bytes")
        String readRefAsBytes(byte[] payload) {
            StringBuilder builder = new StringBuilder();
            for (byte value : payload) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        }
    }

    static class RefResourceInvocationBean {

        @SkillMethod(name = "readRefAsResource", description = "Read ref as resource")
        String readRefAsResource(Resource payload) {
            try {
                return StreamUtils.copyToString(payload.getInputStream(), StandardCharsets.UTF_8);
            }
            catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    static class RefStreamInvocationBean {

        @SkillMethod(name = "readRefAsStream", description = "Read ref as stream")
        String readRefAsStream(InputStream payload) {
            try {
                return StreamUtils.copyToString(payload, StandardCharsets.UTF_8);
            }
            catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    static class NestedRecordInvocationBean {

        @SkillMethod(name = "readNestedRecord", description = "Read nested record")
        String readNestedRecord(NestedPayload payload) {
            return payload.document().title() + "|" + payload.document().attachments().stream()
                    .map(bytes -> {
                        StringBuilder builder = new StringBuilder();
                        for (byte value : bytes) {
                            builder.append(String.format("%02x", value));
                        }
                        return builder.toString();
                    })
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
        }
    }

    static class TypedCollectionInvocationBean {

        @SkillMethod(name = "readTypedCollection", description = "Read typed collection")
        String readTypedCollection(List<String> payload) {
            return String.join("|", payload);
        }
    }

    record NestedPayload(NestedDocument document) {
    }

    record NestedDocument(String title, List<byte[]> attachments) {
    }
}
