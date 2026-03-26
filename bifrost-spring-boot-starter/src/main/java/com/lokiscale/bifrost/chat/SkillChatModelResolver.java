package com.lokiscale.bifrost.chat;

import com.lokiscale.bifrost.autoconfigure.AiProvider;
import org.springframework.ai.chat.model.ChatModel;

public interface SkillChatModelResolver {

    ChatModel resolve(String skillName, AiProvider provider);
}
