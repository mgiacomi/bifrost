package com.lokiscale.bifrost.runtime.usage;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelUsageExtractorTest {

    private final ModelUsageExtractor extractor = new ModelUsageExtractor();

    @Test
    void extractsExactUsageFromChatResponseMetadata() {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(11);
        when(usage.getCompletionTokens()).thenReturn(7);
        when(usage.getTotalTokens()).thenReturn(18);
        when(usage.getNativeUsage()).thenReturn("native-usage");
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("done"))), metadata);

        ModelUsageRecord record = extractor.extract(response, "user prompt", "system prompt");

        assertThat(record.promptUnits()).isEqualTo(11);
        assertThat(record.completionUnits()).isEqualTo(7);
        assertThat(record.totalUnits()).isEqualTo(18);
        assertThat(record.precision()).isEqualTo(UsagePrecision.EXACT);
        assertThat(record.nativeUsage()).isEqualTo("native-usage");
    }

    @Test
    void fallsBackToHeuristicUsageWhenProviderUsageMissing() {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("heuristic output"))));

        ModelUsageRecord record = extractor.extract(response, "hello world", "system instructions");

        assertThat(record.precision()).isEqualTo(UsagePrecision.HEURISTIC);
        assertThat(record.totalUnits()).isGreaterThan(0);
        assertThat(record.promptUnits()).isGreaterThan(0);
        assertThat(record.completionUnits()).isGreaterThan(0);
    }
}
