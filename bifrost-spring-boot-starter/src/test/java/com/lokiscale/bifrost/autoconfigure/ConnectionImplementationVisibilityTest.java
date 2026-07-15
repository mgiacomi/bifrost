package com.lokiscale.bifrost.autoconfigure;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionImplementationVisibilityTest {

    @Test
    void connectionConstructionTypesArePackagePrivateInfrastructure() throws Exception {
        List<Class<?>> implementationTypes = List.of(
                AiConnectionChatModelFactory.class,
                OpenAiConnectionChatModelFactory.class,
                AnthropicConnectionChatModelFactory.class,
                GeminiConnectionChatModelFactory.class,
                OllamaConnectionChatModelFactory.class,
                NamedAiConnectionRegistry.class);

        assertThat(implementationTypes)
                .allSatisfy(type -> assertThat(Modifier.isPublic(type.getModifiers())).isFalse());
        assertThat(BifrostAutoConfiguration.class.getDeclaredMethod(
                "namedAiConnectionRegistry", BifrostProperties.class, org.springframework.core.io.ResourceLoader.class)
                .getModifiers()).matches(modifiers -> !Modifier.isPublic(modifiers));
    }
}
