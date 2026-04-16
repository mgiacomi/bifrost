package com.lokiscale.bifrost.runtime.usage;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

public class ModelUsageExtractor
{
    public ModelUsageRecord extract(@Nullable ChatResponse response, String userPrompt, String systemPrompt)
    {
        return extract(response, userPrompt, systemPrompt, null);
    }

    public ModelUsageRecord extract(@Nullable ChatResponse response, String userPrompt, String systemPrompt, @Nullable String fallbackResponseText)
    {
        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();

        if (usage != null && hasExactUsage(usage))
        {
            Usage exactUsage = usage;
            int promptUnits = asInt(exactUsage.getPromptTokens());
            int completionUnits = firstNonNegative(exactUsage.getCompletionTokens(), exactUsage.getTotalTokens());
            int totalUnits = firstNonNegative(exactUsage.getTotalTokens(), (long) promptUnits + completionUnits);
            return new ModelUsageRecord(
                    promptUnits,
                    completionUnits,
                    totalUnits,
                    UsagePrecision.EXACT,
                    exactUsage.getNativeUsage());
        }

        String responseText = extractContent(response, fallbackResponseText);

        if (!StringUtils.hasText(userPrompt) && !StringUtils.hasText(systemPrompt) && !StringUtils.hasText(responseText))
        {
            return new ModelUsageRecord(0, 0, 0, UsagePrecision.UNAVAILABLE, usage == null ? null : usage.getNativeUsage());
        }

        int heuristicPrompt = estimateUnits(systemPrompt) + estimateUnits(userPrompt);
        int heuristicCompletion = estimateUnits(responseText);

        return new ModelUsageRecord(
                heuristicPrompt,
                heuristicCompletion,
                heuristicPrompt + heuristicCompletion,
                UsagePrecision.HEURISTIC,
                usage == null ? null : usage.getNativeUsage());
    }

    protected int estimateUnits(@Nullable String text)
    {
        if (!StringUtils.hasText(text))
        {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.trim().length() / 4.0d));
    }

    private boolean hasExactUsage(@Nullable Usage usage)
    {
        if (usage == null)
        {
            return false;
        }

        return positive(usage.getTotalTokens())
                || positive(usage.getPromptTokens())
                || positive(usage.getCompletionTokens());
    }

    private String extractContent(@Nullable ChatResponse response, @Nullable String fallbackResponseText)
    {
        if (response == null || response.getResult() == null)
        {
            return fallbackResponseText == null ? "" : fallbackResponseText;
        }

        AssistantMessage output = response.getResult().getOutput();

        return output == null || output.getText() == null
                ? (fallbackResponseText == null ? "" : fallbackResponseText)
                : output.getText();
    }

    private static boolean positive(@Nullable Number value)
    {
        return value != null && value.longValue() > 0;
    }

    private static int asInt(@Nullable Number value)
    {
        return value == null ? 0 : Math.toIntExact(value.longValue());
    }

    private static int firstNonNegative(@Nullable Number primary, @Nullable Number fallback)
    {
        if (primary != null && primary.longValue() >= 0)
        {
            return asInt(primary);
        }
        return fallback == null ? 0 : asInt(fallback);
    }
}
