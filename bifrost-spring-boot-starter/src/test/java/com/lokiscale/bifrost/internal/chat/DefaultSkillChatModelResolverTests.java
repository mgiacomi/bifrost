package com.lokiscale.bifrost.internal.chat;

import com.lokiscale.bifrost.autoconfigure.AiDriver;
import com.lokiscale.bifrost.internal.skill.EffectiveSkillExecutionConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class DefaultSkillChatModelResolverTests {

    @Test
    void resolvesDistinctModelsForTwoConnectionsUsingSameDriver() {
        ChatModel nativeOpenAi = mock(ChatModel.class);
        ChatModel openRouter = mock(ChatModel.class);
        DefaultSkillChatModelResolver resolver = new DefaultSkillChatModelResolver(Map.of(
                "openai-main", nativeOpenAi,
                "openrouter", openRouter));

        EffectiveSkillExecutionConfiguration nativeConfiguration = new EffectiveSkillExecutionConfiguration(
                "fast", "openai-main", AiDriver.OPENAI, "gpt-fast", null);
        EffectiveSkillExecutionConfiguration routedConfiguration = new EffectiveSkillExecutionConfiguration(
                "routed", "openrouter", AiDriver.OPENAI, "anthropic/sonnet", null);

        assertThat(resolver.resolve("fastSkill", nativeConfiguration)).isSameAs(nativeOpenAi);
        assertThat(resolver.resolve("routedSkill", routedConfiguration)).isSameAs(openRouter);
    }

    @Test
    void reusesOneConnectionModelForAliasesWithDifferentProviderModelIds() {
        ChatModel shared = mock(ChatModel.class);
        DefaultSkillChatModelResolver resolver = new DefaultSkillChatModelResolver(Map.of("openai-main", shared));

        assertThat(resolver.resolve("fastSkill", new EffectiveSkillExecutionConfiguration(
                "fast", "openai-main", AiDriver.OPENAI, "gpt-fast", null))).isSameAs(shared);
        assertThat(resolver.resolve("deepSkill", new EffectiveSkillExecutionConfiguration(
                "deep", "openai-main", AiDriver.OPENAI, "gpt-deep", "high"))).isSameAs(shared);
    }

    @Test
    void nestedChildResolutionUsesTheChildConnectionRatherThanTheParentConnection() {
        ChatModel parent = mock(ChatModel.class);
        ChatModel child = mock(ChatModel.class);
        DefaultSkillChatModelResolver resolver = new DefaultSkillChatModelResolver(Map.of(
                "parent-connection", parent, "child-connection", child));
        EffectiveSkillExecutionConfiguration parentConfiguration = new EffectiveSkillExecutionConfiguration(
                "parent-model", "parent-connection", AiDriver.OPENAI, "gpt-parent", null);
        EffectiveSkillExecutionConfiguration childConfiguration = new EffectiveSkillExecutionConfiguration(
                "child-model", "child-connection", AiDriver.OPENAI, "gpt-child", null);

        assertThat(resolver.resolve("parentSkill", parentConfiguration)).isSameAs(parent);
        assertThat(resolver.resolve("nestedChildSkill", childConfiguration)).isSameAs(child).isNotSameAs(parent);
    }

    @Test
    void failsClearlyWhenConnectionIsUnavailable() {
        DefaultSkillChatModelResolver resolver = new DefaultSkillChatModelResolver(Map.of(
                "openai-main", mock(ChatModel.class)));
        EffectiveSkillExecutionConfiguration configuration = new EffectiveSkillExecutionConfiguration(
                "local", "ollama-east", AiDriver.OLLAMA, "qwen3", null);

        assertThatThrownBy(() -> resolver.resolve("invoiceParser", configuration))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invoiceParser")
                .hasMessageContaining("local")
                .hasMessageContaining("ollama-east")
                .hasMessageContaining("OLLAMA");
    }
}
