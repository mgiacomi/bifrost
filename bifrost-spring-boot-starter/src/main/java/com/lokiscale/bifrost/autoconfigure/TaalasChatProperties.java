package com.lokiscale.bifrost.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.ai.taalas")
public class TaalasChatProperties {

    private boolean enabled;

    private String apiKey;

    private String baseUrl = "https://api.taalas.com";

    private String generatePath = "/generate";

    private String model = "llama3.1-8B";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getGeneratePath() {
        return generatePath;
    }

    public void setGeneratePath(String generatePath) {
        this.generatePath = generatePath;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
