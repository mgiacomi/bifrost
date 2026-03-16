package com.lokiscale.bifrost.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "bifrost.skills")
public class BifrostSkillProperties {

    private List<String> locations = List.of("classpath:/skills/**/*.yaml");

    public List<String> getLocations() {
        return locations;
    }

    public void setLocations(List<String> locations) {
        this.locations = (locations == null || locations.isEmpty())
                ? List.of("classpath:/skills/**/*.yaml")
                : List.copyOf(locations);
    }
}
