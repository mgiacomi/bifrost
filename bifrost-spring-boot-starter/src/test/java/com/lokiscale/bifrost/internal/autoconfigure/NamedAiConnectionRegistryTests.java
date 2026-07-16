package com.lokiscale.bifrost.internal.autoconfigure;

import com.lokiscale.bifrost.autoconfigure.AiDriver;
import com.lokiscale.bifrost.autoconfigure.BifrostProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.DisposableBean;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

class NamedAiConnectionRegistryTests {

    @Test
    void constructsAndKeepsDistinctNamedConnectionsUsingTheSameDriver() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel secondary = mock(ChatModel.class);
        AtomicInteger creations = new AtomicInteger();
        AiConnectionChatModelFactory factory = new AiConnectionChatModelFactory() {
            @Override
            public AiDriver driver() {
                return AiDriver.OLLAMA;
            }

            @Override
            public ChatModel create(String connectionName, BifrostProperties.ConnectionProperties properties) {
                creations.incrementAndGet();
                return connectionName.equals("primary") ? primary : secondary;
            }
        };

        BifrostProperties.ConnectionProperties first = connection("http://one.example");
        BifrostProperties.ConnectionProperties second = connection("http://two.example");
        NamedAiConnectionRegistry registry = new NamedAiConnectionRegistry(
                Map.of("primary", first, "secondary", second), List.of(factory));

        assertThat(registry.asMap()).containsEntry("primary", primary).containsEntry("secondary", secondary);
        assertThat(registry.get("primary")).isNotSameAs(registry.get("secondary"));
        assertThat(creations).hasValue(2);
    }

    @Test
    void preservesSafeFieldDiagnosticsAndCleansUpModelsBuiltBeforeFailure() throws Exception {
        ChatModel closeable = mock(ChatModel.class, withSettings().extraInterfaces(DisposableBean.class));
        AiConnectionChatModelFactory factory = new AiConnectionChatModelFactory() {
            @Override
            public AiDriver driver() {
                return AiDriver.GEMINI;
            }

            @Override
            public ChatModel create(String connectionName, BifrostProperties.ConnectionProperties properties) {
                if (connectionName.equals("first")) {
                    return closeable;
                }
                throw new SafeAiConnectionConfigurationException(
                        "bifrost.connections.second.gemini.credentials-uri could not be loaded");
            }
        };

        LinkedHashMap<String, BifrostProperties.ConnectionProperties> connections = new LinkedHashMap<>();
        connections.put("first", geminiConnection());
        connections.put("second", geminiConnection());

        assertThatThrownBy(() -> new NamedAiConnectionRegistry(connections, List.of(factory)))
                .isInstanceOf(SafeAiConnectionConfigurationException.class)
                .hasMessage("bifrost.connections.second.gemini.credentials-uri could not be loaded");
        verify((DisposableBean) closeable).destroy();
    }

    private static BifrostProperties.ConnectionProperties connection(String baseUrl) {
        BifrostProperties.ConnectionProperties properties = new BifrostProperties.ConnectionProperties();
        properties.setDriver(AiDriver.OLLAMA);
        properties.setBaseUrl(baseUrl);
        return properties;
    }

    private static BifrostProperties.ConnectionProperties geminiConnection() {
        BifrostProperties.ConnectionProperties properties = new BifrostProperties.ConnectionProperties();
        properties.setDriver(AiDriver.GEMINI);
        properties.setApiKey("test-key");
        return properties;
    }
}
