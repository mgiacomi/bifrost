package com.lokiscale.bifrost.chat;

import com.lokiscale.bifrost.autoconfigure.AiProvider;
import org.springframework.ai.chat.model.ChatModel;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public class DefaultSkillChatModelResolver implements SkillChatModelResolver {

    private final Map<AiProvider, ChatModel> modelsByProvider;

    public DefaultSkillChatModelResolver(Map<AiProvider, ChatModel> modelsByProvider) {
        Objects.requireNonNull(modelsByProvider, "modelsByProvider must not be null");
        EnumMap<AiProvider, ChatModel> resolvedModels = new EnumMap<>(AiProvider.class);
        resolvedModels.putAll(modelsByProvider);
        this.modelsByProvider = Map.copyOf(resolvedModels);
    }

    @Override
    public ChatModel resolve(String skillName, AiProvider provider) {
        Objects.requireNonNull(skillName, "skillName must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        ChatModel chatModel = modelsByProvider.get(provider);
        if (chatModel == null) {
            throw new IllegalStateException(
                    "No ChatModel configured for provider " + provider + " required by skill '" + skillName + "'");
        }
        return chatModel;
    }
}
