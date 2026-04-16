package com.lokiscale.bifrost.autoconfigure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "bifrost.session")
public class BifrostSessionProperties
{
    private static final int DEFAULT_MAX_DEPTH = 32;
    private static final Duration DEFAULT_MISSION_TIMEOUT = Duration.ofSeconds(60);
    private static final int DEFAULT_MAX_SKILL_INVOCATIONS = 64;
    private static final int DEFAULT_MAX_TOOL_INVOCATIONS = 128;
    private static final int DEFAULT_MAX_LINTER_RETRIES = 32;
    private static final int DEFAULT_MAX_MODEL_CALLS = 64;
    private static final int DEFAULT_MAX_USAGE_UNITS = 200_000;

    @Min(1)
    private int maxDepth = DEFAULT_MAX_DEPTH;

    @NotNull
    private Duration missionTimeout = DEFAULT_MISSION_TIMEOUT;

    @Valid
    private Quotas quotas = new Quotas();

    public int getMaxDepth()
    {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth)
    {
        this.maxDepth = maxDepth;
    }

    public Duration getMissionTimeout()
    {
        return missionTimeout;
    }

    public void setMissionTimeout(Duration missionTimeout)
    {
        if (missionTimeout == null || missionTimeout.isZero() || missionTimeout.isNegative())
        {
            throw new IllegalArgumentException("missionTimeout must be greater than zero");
        }
        this.missionTimeout = missionTimeout;
    }

    public Quotas getQuotas()
    {
        return quotas;
    }

    public void setQuotas(Quotas quotas)
    {
        this.quotas = quotas == null ? new Quotas() : quotas;
    }

    public static class Quotas
    {

        @Min(0)
        private int maxSkillInvocations = DEFAULT_MAX_SKILL_INVOCATIONS;

        @Min(0)
        private int maxToolInvocations = DEFAULT_MAX_TOOL_INVOCATIONS;

        @Min(0)
        private int maxLinterRetries = DEFAULT_MAX_LINTER_RETRIES;

        @Min(0)
        private int maxModelCalls = DEFAULT_MAX_MODEL_CALLS;

        @Min(0)
        private int maxUsageUnits = DEFAULT_MAX_USAGE_UNITS;

        public int getMaxSkillInvocations()
        {
            return maxSkillInvocations;
        }

        public void setMaxSkillInvocations(int maxSkillInvocations)
        {
            this.maxSkillInvocations = maxSkillInvocations;
        }

        public int getMaxToolInvocations()
        {
            return maxToolInvocations;
        }

        public void setMaxToolInvocations(int maxToolInvocations)
        {
            this.maxToolInvocations = maxToolInvocations;
        }

        public int getMaxLinterRetries()
        {
            return maxLinterRetries;
        }

        public void setMaxLinterRetries(int maxLinterRetries)
        {
            this.maxLinterRetries = maxLinterRetries;
        }

        public int getMaxModelCalls()
        {
            return maxModelCalls;
        }

        public void setMaxModelCalls(int maxModelCalls)
        {
            this.maxModelCalls = maxModelCalls;
        }

        public int getMaxUsageUnits()
        {
            return maxUsageUnits;
        }

        public void setMaxUsageUnits(int maxUsageUnits)
        {
            this.maxUsageUnits = maxUsageUnits;
        }
    }
}
