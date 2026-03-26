package com.lokiscale.bifrost.chat;

import com.lokiscale.bifrost.autoconfigure.AiProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class DefaultSkillChatModelResolverTests {

    @Test
    void resolvesConfiguredProviderModel() {
        ChatModel ollamaChatModel = mock(ChatModel.class);
        ChatModel taalasChatModel = mock(ChatModel.class);
        DefaultSkillChatModelResolver resolver = new DefaultSkillChatModelResolver(Map.of(
                AiProvider.OLLAMA, ollamaChatModel,
                AiProvider.TAALAS, taalasChatModel));

        assertThat(resolver.resolve("invoiceParser", AiProvider.OLLAMA)).isSameAs(ollamaChatModel);
        assertThat(resolver.resolve("taalasSkill", AiProvider.TAALAS)).isSameAs(taalasChatModel);
    }

    @Test
    void failsClearlyWhenProviderIsUnavailable() {
        DefaultSkillChatModelResolver resolver = new DefaultSkillChatModelResolver(Map.of(
                AiProvider.OPENAI, mock(ChatModel.class)));

        assertThatThrownBy(() -> resolver.resolve("invoiceParser", AiProvider.OLLAMA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No ChatModel configured for provider OLLAMA required by skill 'invoiceParser'");
    }
}
