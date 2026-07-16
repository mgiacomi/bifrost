package com.lokiscale.bifrost.internal.autoconfigure;

import com.lokiscale.bifrost.autoconfigure.BifrostProperties;
import com.lokiscale.bifrost.autoconfigure.AiDriver;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.DisposableBean;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class NamedAiConnectionRegistry implements DisposableBean
{
    private final Map<String, ChatModel> modelsByConnection;

    public NamedAiConnectionRegistry(Map<String, BifrostProperties.ConnectionProperties> connections,
            List<AiConnectionChatModelFactory> factories)
    {
        Objects.requireNonNull(connections, "connections must not be null");
        EnumMap<AiDriver, AiConnectionChatModelFactory> byDriver = new EnumMap<>(AiDriver.class);
        for (AiConnectionChatModelFactory factory : factories)
        {
            AiConnectionChatModelFactory previous = byDriver.put(factory.driver(), factory);
            if (previous != null)
            {
                throw new IllegalStateException("Multiple connection factories configured for driver " + factory.driver());
            }
        }
        Map<String, ChatModel> built = new LinkedHashMap<>();
        for (Map.Entry<String, BifrostProperties.ConnectionProperties> entry : connections.entrySet())
        {
            String name = entry.getKey();
            BifrostProperties.ConnectionProperties properties = entry.getValue();
            AiConnectionChatModelFactory factory = byDriver.get(properties.getDriver());
            if (factory == null)
            {
                throw new IllegalStateException("No connection factory configured for driver " + properties.getDriver()
                        + " required by connection '" + name + "'");
            }
            try
            {
                built.put(name, factory.create(name, properties));
            }
            catch (SafeAiConnectionConfigurationException ex)
            {
                cleanupAfterConstructionFailure(built, ex);
                throw ex;
            }
            catch (RuntimeException ex)
            {
                IllegalStateException failure = new IllegalStateException(
                        "Failed to construct AI connection '" + name + "' for driver " + properties.getDriver());
                cleanupAfterConstructionFailure(built, failure);
                throw failure;
            }
        }
        modelsByConnection = Map.copyOf(built);
    }

    ChatModel get(String connectionName)
    {
        return modelsByConnection.get(connectionName);
    }

    public Map<String, ChatModel> asMap()
    {
        return modelsByConnection;
    }

    @Override
    public void destroy() throws Exception
    {
        Exception failure = destroyModels(modelsByConnection);
        if (failure != null) throw failure;
    }

    private static void cleanupAfterConstructionFailure(Map<String, ChatModel> built, RuntimeException failure)
    {
        Exception cleanupFailure = destroyModels(built);
        if (cleanupFailure != null) failure.addSuppressed(cleanupFailure);
    }

    private static Exception destroyModels(Map<String, ChatModel> models)
    {
        Exception failure = null;
        for (ChatModel model : models.values())
        {
            try
            {
                if (model instanceof DisposableBean disposableBean) disposableBean.destroy();
                else if (model instanceof AutoCloseable closeable) closeable.close();
            }
            catch (Exception ex)
            {
                if (failure == null) failure = ex; else failure.addSuppressed(ex);
            }
        }
        return failure;
    }
}
