package com.lokiscale.bifrost.runtime.usage;

import com.lokiscale.bifrost.linter.LinterOutcome;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Locale;
import java.util.Objects;

public class MicrometerUsageMetricsRecorder implements UsageMetricsRecorder
{
    private final MeterRegistry meterRegistry;

    public MicrometerUsageMetricsRecorder(MeterRegistry meterRegistry)
    {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    @Override
    public void recordSkillInvocation(String skillName)
    {
        meterRegistry.counter("bifrost.skill.calls", "skill", normalize(skillName)).increment();
    }

    @Override
    public void recordModelUsage(String skillName, ModelUsageRecord usageRecord)
    {
        Objects.requireNonNull(usageRecord, "usageRecord must not be null");

        meterRegistry.counter("bifrost.model.calls", "skill", normalize(skillName), "precision", usageRecord.precision().name()).increment();
        meterRegistry.counter("bifrost.model.prompt.units", "skill", normalize(skillName)).increment(usageRecord.promptUnits());
        meterRegistry.counter("bifrost.model.completion.units", "skill", normalize(skillName)).increment(usageRecord.completionUnits());
        meterRegistry.counter("bifrost.model.usage.units", "skill", normalize(skillName), "precision", usageRecord.precision().name()).increment(usageRecord.totalUnits());
    }

    @Override
    public void recordToolInvocation(String skillName, String toolName, String outcome)
    {
        meterRegistry.counter(
                "bifrost.tool.calls",
                "skill", normalize(skillName),
                "tool", normalize(toolName),
                "outcome", normalize(outcome)).increment();
    }

    @Override
    public void recordToolAccuracy(String skillName, String linterType, String outcome)
    {
        meterRegistry.counter(
                "bifrost.tool.accuracy.samples",
                "skill", normalize(skillName),
                "linter", normalize(linterType),
                "outcome", normalize(outcome)).increment();
    }

    @Override
    public void recordLinterOutcome(LinterOutcome outcome)
    {
        Objects.requireNonNull(outcome, "outcome must not be null");
        meterRegistry.counter(
                "bifrost.linter.outcomes",
                "skill", normalize(outcome.skillName()),
                "status", outcome.status().name(),
                "linter", normalize(outcome.linterType())).increment();
    }

    @Override
    public void recordGuardrailTrip(String skillName, GuardrailType guardrailType)
    {
        meterRegistry.counter(
                "bifrost.guardrail.trips",
                "skill", normalize(skillName),
                "guardrail", guardrailType.name(),
                "outcome", "quota_exceeded").increment();
    }

    private String normalize(String value)
    {
        if (value == null || value.isBlank())
        {
            return "unknown";
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
