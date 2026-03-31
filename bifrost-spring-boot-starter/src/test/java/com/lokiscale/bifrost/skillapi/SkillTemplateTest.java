package com.lokiscale.bifrost.skillapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lokiscale.bifrost.core.BifrostSessionRunner;
import com.lokiscale.bifrost.core.CapabilityExecutionRouter;
import com.lokiscale.bifrost.core.CapabilityInvoker;
import com.lokiscale.bifrost.core.CapabilityKind;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.CapabilityRegistry;
import com.lokiscale.bifrost.core.CapabilityToolDescriptor;
import com.lokiscale.bifrost.core.InMemoryCapabilityRegistry;
import com.lokiscale.bifrost.core.ModelPreference;
import com.lokiscale.bifrost.core.SkillExecutionDescriptor;
import com.lokiscale.bifrost.runtime.input.SkillInputContract;
import com.lokiscale.bifrost.runtime.input.SkillInputSchemaNode;
import com.lokiscale.bifrost.runtime.input.SkillInputValidator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillTemplateTest {

    @Test
    void skillTemplateInvokesYamlSkillsOnly() {
        CapabilityRegistry registry = new InMemoryCapabilityRegistry();
        CapabilityExecutionRouter router = mock(CapabilityExecutionRouter.class);
        DefaultSkillTemplate template = new DefaultSkillTemplate(
                registry,
                router,
                new BifrostSessionRunner(4, com.lokiscale.bifrost.core.TracePersistencePolicy.ALWAYS, fixedClock()),
                new ObjectMapper(),
                new SkillInputValidator());

        registry.register("javaSkill", new CapabilityMetadata(
                "bean#javaSkill",
                "javaSkill",
                "Java skill",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.none(),
                java.util.Set.of(),
                noopInvoker(),
                CapabilityKind.JAVA_METHOD,
                CapabilityToolDescriptor.generic("javaSkill", "Java skill"),
                SkillInputContract.genericObject(),
                null));

        assertThatThrownBy(() -> template.invoke("missingSkill", Map.of()))
                .isInstanceOf(SkillException.class)
                .hasMessageContaining("Unknown YAML skill");
        assertThatThrownBy(() -> template.invoke("javaSkill", Map.of()))
                .isInstanceOf(SkillException.class)
                .hasMessageContaining("only supports YAML skills");
    }

    @Test
    void skillTemplateNullInputAndObserverLifecycle() {
        CapabilityRegistry registry = new InMemoryCapabilityRegistry();
        CapabilityExecutionRouter router = mock(CapabilityExecutionRouter.class);
        DefaultSkillTemplate template = new DefaultSkillTemplate(
                registry,
                router,
                new BifrostSessionRunner(4, com.lokiscale.bifrost.core.TracePersistencePolicy.ALWAYS, fixedClock()),
                new ObjectMapper(),
                new SkillInputValidator());
        CapabilityMetadata yamlSkill = new CapabilityMetadata(
                "yaml:invoiceParser",
                "invoiceParser",
                "Invoice parser",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.none(),
                java.util.Set.of(),
                noopInvoker(),
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("invoiceParser", "Invoice parser"),
                new SkillInputContract(
                        SkillInputContract.SkillInputContractKind.YAML_EXPLICIT,
                        new SkillInputSchemaNode(
                                "object",
                                Map.of("payload", new SkillInputSchemaNode("string", Map.of(), List.of(), null, null, List.of(), null, null, false)),
                                List.of("payload"),
                                Boolean.FALSE,
                                null,
                                List.of(),
                                null,
                                null,
                                false)),
                null);
        registry.register("invoiceParser", yamlSkill);
        when(router.execute(eq(yamlSkill), eq(Map.of("payload", "hello")), any(), eq(null))).thenReturn("\"ok\"");

        assertThatThrownBy(() -> template.invoke("invoiceParser", (Object) null))
                .isInstanceOf(SkillInputValidationException.class);
        assertThatThrownBy(() -> template.invoke("invoiceParser", (Map<String, Object>) null))
                .isInstanceOf(SkillInputValidationException.class);

        AtomicReference<SkillExecutionView> observed = new AtomicReference<>();
        String result = template.invoke("invoiceParser", Map.of("payload", "hello"), observed::set);

        assertThat(result).isEqualTo("\"ok\"");
        assertThat(observed.get()).isNotNull();
        assertThat(observed.get().sessionId()).isNotBlank();
        assertThat(observed.get().executionJournal()).isNotNull();

        assertThatThrownBy(() -> template.invoke("invoiceParser", Map.of(), observed::set))
                .isInstanceOf(SkillInputValidationException.class);
        verify(router, never()).execute(eq(yamlSkill), eq(Map.of()), any(), eq(null));
    }

    @Test
    void objectOverloadDelegatesThroughValidatedMapPath() {
        CapabilityRegistry registry = new InMemoryCapabilityRegistry();
        CapabilityExecutionRouter router = mock(CapabilityExecutionRouter.class);
        DefaultSkillTemplate template = new DefaultSkillTemplate(
                registry,
                router,
                new BifrostSessionRunner(4, com.lokiscale.bifrost.core.TracePersistencePolicy.ALWAYS, fixedClock()),
                new ObjectMapper(),
                new SkillInputValidator());
        CapabilityMetadata yamlSkill = yamlSkillMetadata();
        registry.register("invoiceParser", yamlSkill);
        when(router.execute(eq(yamlSkill), eq(Map.of("payload", "hello")), any(), eq(null))).thenReturn("\"ok\"");

        String result = template.invoke("invoiceParser", new InvoiceRequest("hello"));

        assertThat(result).isEqualTo("\"ok\"");
        verify(router).execute(eq(yamlSkill), eq(Map.of("payload", "hello")), any(), eq(null));
    }

    @Test
    void observerExceptionPropagatesAfterExecutionCompletes() {
        CapabilityRegistry registry = new InMemoryCapabilityRegistry();
        CapabilityExecutionRouter router = mock(CapabilityExecutionRouter.class);
        DefaultSkillTemplate template = new DefaultSkillTemplate(
                registry,
                router,
                new BifrostSessionRunner(4, com.lokiscale.bifrost.core.TracePersistencePolicy.ALWAYS, fixedClock()),
                new ObjectMapper(),
                new SkillInputValidator());
        CapabilityMetadata yamlSkill = yamlSkillMetadata();
        registry.register("invoiceParser", yamlSkill);
        when(router.execute(eq(yamlSkill), eq(Map.of("payload", "hello")), any(), eq(null))).thenReturn("\"ok\"");

        assertThatThrownBy(() -> template.invoke("invoiceParser", Map.of("payload", "hello"), view -> {
            throw new IllegalStateException("observer failed");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("observer failed");
        verify(router).execute(eq(yamlSkill), eq(Map.of("payload", "hello")), any(), eq(null));
    }

    @Test
    void invalidInputDoesNotInvokeObserver() {
        CapabilityRegistry registry = new InMemoryCapabilityRegistry();
        CapabilityExecutionRouter router = mock(CapabilityExecutionRouter.class);
        DefaultSkillTemplate template = new DefaultSkillTemplate(
                registry,
                router,
                new BifrostSessionRunner(4, com.lokiscale.bifrost.core.TracePersistencePolicy.ALWAYS, fixedClock()),
                new ObjectMapper(),
                new SkillInputValidator());
        CapabilityMetadata yamlSkill = yamlSkillMetadata();
        registry.register("invoiceParser", yamlSkill);
        AtomicBoolean observerCalled = new AtomicBoolean(false);

        assertThatThrownBy(() -> template.invoke("invoiceParser", Map.of(), view -> observerCalled.set(true)))
                .isInstanceOf(SkillInputValidationException.class);

        assertThat(observerCalled.get()).isFalse();
        verify(router, never()).execute(eq(yamlSkill), eq(Map.of()), any(), eq(null));
    }

    private CapabilityMetadata yamlSkillMetadata() {
        return new CapabilityMetadata(
                "yaml:invoiceParser",
                "invoiceParser",
                "Invoice parser",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.none(),
                java.util.Set.of(),
                noopInvoker(),
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("invoiceParser", "Invoice parser"),
                new SkillInputContract(
                        SkillInputContract.SkillInputContractKind.YAML_EXPLICIT,
                        new SkillInputSchemaNode(
                                "object",
                                Map.of("payload", new SkillInputSchemaNode("string", Map.of(), List.of(), null, null, List.of(), null, null, false)),
                                List.of("payload"),
                                Boolean.FALSE,
                                null,
                                List.of(),
                                null,
                                null,
                                false)),
                null);
    }

    private record InvoiceRequest(String payload) {
    }

    private CapabilityInvoker noopInvoker() {
        return arguments -> "\"ok\"";
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-03-30T12:00:00Z"), ZoneOffset.UTC);
    }
}
