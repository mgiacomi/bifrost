package com.lokiscale.bifrost.internal.chat;

import com.lokiscale.bifrost.internal.skill.EffectiveSkillExecutionConfiguration;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Map;
import java.util.Objects;

public class DefaultSkillChatModelResolver implements SkillChatModelResolver
{
    private final Map<String, ChatModel> modelsByConnection;

    public DefaultSkillChatModelResolver(Map<String, ChatModel> modelsByConnection)
    {
        Objects.requireNonNull(modelsByConnection, "modelsByConnection must not be null");
        this.modelsByConnection = Map.copyOf(modelsByConnection);
    }

    @Override
    public ChatModel resolve(String skillName, EffectiveSkillExecutionConfiguration configuration)
    {
        Objects.requireNonNull(skillName, "skillName must not be null");
        Objects.requireNonNull(configuration, "configuration must not be null");
        ChatModel chatModel = modelsByConnection.get(configuration.connection());

        if (chatModel == null)
        {
            throw new IllegalStateException("No ChatModel configured for connection '" + configuration.connection()
                    + "' (driver " + configuration.driver() + ", framework model '" + configuration.frameworkModel()
                    + "') required by skill '" + skillName + "'");
        }
        return chatModel;
    }
}
