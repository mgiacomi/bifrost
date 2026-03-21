package com.lokiscale.bifrost.skill;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.lokiscale.bifrost.autoconfigure.BifrostModelsProperties;
import com.lokiscale.bifrost.autoconfigure.BifrostSkillProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class YamlSkillCatalog implements InitializingBean {

    private static final String DEFAULT_THINKING_LEVEL = "medium";

    private final BifrostModelsProperties modelsProperties;
    private final BifrostSkillProperties skillProperties;
    private final ResourcePatternResolver resourcePatternResolver;
    private final ObjectMapper yamlObjectMapper;
    private final Map<String, YamlSkillDefinition> skillsByName = new LinkedHashMap<>();

    public YamlSkillCatalog(BifrostModelsProperties modelsProperties, BifrostSkillProperties skillProperties) {
        this(modelsProperties, skillProperties, new PathMatchingResourcePatternResolver(), defaultYamlObjectMapper());
    }

    YamlSkillCatalog(BifrostModelsProperties modelsProperties,
                     BifrostSkillProperties skillProperties,
                     ResourcePatternResolver resourcePatternResolver,
                     ObjectMapper yamlObjectMapper) {
        this.modelsProperties = Objects.requireNonNull(modelsProperties, "modelsProperties must not be null");
        this.skillProperties = Objects.requireNonNull(skillProperties, "skillProperties must not be null");
        this.resourcePatternResolver = Objects.requireNonNull(resourcePatternResolver, "resourcePatternResolver must not be null");
        this.yamlObjectMapper = Objects.requireNonNull(yamlObjectMapper, "yamlObjectMapper must not be null");
    }

    @Override
    public void afterPropertiesSet() {
        skillsByName.clear();
        if (modelsProperties.getModels().isEmpty()) {
            return;
        }
        for (Resource resource : discoverResources()) {
            YamlSkillDefinition definition = loadDefinition(resource);
            YamlSkillDefinition previous = skillsByName.putIfAbsent(definition.manifest().getName(), definition);
            if (previous != null) {
                throw invalidSkill(resource, "name", "duplicate skill name '" + definition.manifest().getName() + "'");
            }
        }
    }

    /**
     * Returns the typed YAML skill definitions discovered during startup in deterministic resource order.
     */
    public List<YamlSkillDefinition> getSkills() {
        return List.copyOf(skillsByName.values());
    }

    /**
     * Catalog lookups are the supported access pattern for loaded YAML skills after initialization.
     */
    public YamlSkillDefinition getSkill(String name) {
        return skillsByName.get(name);
    }

    private List<Resource> discoverResources() {
        List<Resource> resources = new ArrayList<>();
        for (String location : skillProperties.getLocations()) {
            try {
                Resource[] found = resourcePatternResolver.getResources(location);
                for (Resource resource : found) {
                    if (resource.exists()) {
                        resources.add(resource);
                    }
                }
            }
            catch (java.io.FileNotFoundException ex) {
                // Missing classpath roots simply mean there are no skills at this location.
            }
            catch (IOException ex) {
                throw new IllegalStateException("Failed to discover YAML skills from " + location, ex);
            }
        }
        resources.sort(Comparator.comparing(this::describe));
        return resources;
    }

    private YamlSkillDefinition loadDefinition(Resource resource) {
        YamlSkillManifest manifest = readManifest(resource);
        validateRequiredField(resource, "name", manifest.getName());
        validateRequiredField(resource, "description", manifest.getDescription());
        validateRequiredField(resource, "model", manifest.getModel());

        BifrostModelsProperties.ModelCatalogEntry catalogEntry = resolveModelCatalogEntry(resource, manifest);
        String effectiveThinkingLevel = resolveEffectiveThinkingLevel(manifest, catalogEntry);

        if (!catalogEntry.supportsThinkingLevel(effectiveThinkingLevel)) {
            throw invalidSkill(resource, "thinking_level",
                    "unsupported thinking_level '" + effectiveThinkingLevel + "' for model '" + manifest.getModel() + "'");
        }

        EffectiveSkillExecutionConfiguration effectiveConfiguration = new EffectiveSkillExecutionConfiguration(
                manifest.getModel(),
                catalogEntry.getProvider(),
                catalogEntry.getProviderModel(),
                effectiveThinkingLevel);

        return new YamlSkillDefinition(resource, manifest, effectiveConfiguration);
    }

    private BifrostModelsProperties.ModelCatalogEntry resolveModelCatalogEntry(Resource resource, YamlSkillManifest manifest) {
        BifrostModelsProperties.ModelCatalogEntry catalogEntry = modelsProperties.getModels().get(manifest.getModel());
        if (catalogEntry == null) {
            throw invalidSkill(resource, "model", "unknown model '" + manifest.getModel() + "'");
        }
        return catalogEntry;
    }

    private String resolveEffectiveThinkingLevel(YamlSkillManifest manifest,
                                                 BifrostModelsProperties.ModelCatalogEntry catalogEntry) {
        if (StringUtils.hasText(manifest.getThinkingLevel())) {
            return manifest.getThinkingLevel();
        }
        return catalogEntry.supportsThinking() ? DEFAULT_THINKING_LEVEL : null;
    }

    private YamlSkillManifest readManifest(Resource resource) {
        try (InputStream inputStream = resource.getInputStream()) {
            return yamlObjectMapper.readValue(inputStream, YamlSkillManifest.class);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to read YAML skill from " + describe(resource), ex);
        }
    }

    private void validateRequiredField(Resource resource, String fieldName, String value) {
        if (!StringUtils.hasText(value)) {
            throw invalidSkill(resource, fieldName, "required field is missing or blank");
        }
    }

    private IllegalStateException invalidSkill(Resource resource, String fieldName, String detail) {
        return new IllegalStateException("Invalid YAML skill '" + describe(resource) + "' for field '" + fieldName + "': " + detail);
    }

    private String describe(Resource resource) {
        try {
            return resource.getURI().toString();
        }
        catch (IOException ex) {
            return resource.getDescription();
        }
    }

    private static ObjectMapper defaultYamlObjectMapper() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }
}
