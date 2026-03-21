package com.lokiscale.bifrost.autoconfigure;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "bifrost.session")
public class BifrostSessionProperties {

    private static final int DEFAULT_MAX_DEPTH = 32;
    private static final Duration DEFAULT_MISSION_TIMEOUT = Duration.ofSeconds(60);

    @Min(1)
    private int maxDepth = DEFAULT_MAX_DEPTH;

    @NotNull
    private Duration missionTimeout = DEFAULT_MISSION_TIMEOUT;

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public Duration getMissionTimeout() {
        return missionTimeout;
    }

    public void setMissionTimeout(Duration missionTimeout) {
        if (missionTimeout == null || missionTimeout.isZero() || missionTimeout.isNegative()) {
            throw new IllegalArgumentException("missionTimeout must be greater than zero");
        }
        this.missionTimeout = missionTimeout;
    }
}
