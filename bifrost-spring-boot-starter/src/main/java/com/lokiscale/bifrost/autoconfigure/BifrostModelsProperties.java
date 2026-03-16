package com.lokiscale.bifrost.autoconfigure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Validated
@ConfigurationProperties(prefix = "bifrost")
public class BifrostModelsProperties {

    @Valid
    private Map<String, ModelCatalogEntry> models = new LinkedHashMap<>();

    public Map<String, ModelCatalogEntry> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelCatalogEntry> models) {
        this.models = models == null ? new LinkedHashMap<>() : new LinkedHashMap<>(models);
    }

    public static class ModelCatalogEntry {

        @NotNull
        private AiProvider provider;

        @NotBlank
        private String providerModel;

        private Set<@NotBlank String> thinkingLevels = new LinkedHashSet<>();

        public AiProvider getProvider() {
            return provider;
        }

        public void setProvider(AiProvider provider) {
            this.provider = provider;
        }

        public String getProviderModel() {
            return providerModel;
        }

        public void setProviderModel(String providerModel) {
            this.providerModel = providerModel;
        }

        public Set<String> getThinkingLevels() {
            return Set.copyOf(thinkingLevels);
        }

        public void setThinkingLevels(Set<String> thinkingLevels) {
            this.thinkingLevels = thinkingLevels == null ? new LinkedHashSet<>() : new LinkedHashSet<>(thinkingLevels);
        }

        public boolean supportsThinking() {
            return !thinkingLevels.isEmpty();
        }

        public boolean supportsThinkingLevel(String thinkingLevel) {
            if (thinkingLevel == null || thinkingLevel.isBlank()) {
                return !supportsThinking();
            }
            return thinkingLevels.contains(thinkingLevel);
        }
    }
}
