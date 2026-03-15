package com.lokiscale.bifrost.autoconfigure;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "bifrost.session")
public class BifrostSessionProperties {

    private static final int DEFAULT_MAX_DEPTH = 32;

    @Min(1)
    private int maxDepth = DEFAULT_MAX_DEPTH;

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }
}
