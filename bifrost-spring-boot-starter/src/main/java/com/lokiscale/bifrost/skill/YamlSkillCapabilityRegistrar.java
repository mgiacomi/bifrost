package com.lokiscale.bifrost.skill;

import com.lokiscale.bifrost.core.CapabilityInvoker;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.CapabilityRegistry;
import com.lokiscale.bifrost.core.ModelPreference;
import com.lokiscale.bifrost.core.SkillExecutionDescriptor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

import java.util.Objects;

public class YamlSkillCapabilityRegistrar implements SmartInitializingSingleton {

    private final CapabilityRegistry capabilityRegistry;
    private final YamlSkillCatalog yamlSkillCatalog;

    public YamlSkillCapabilityRegistrar(CapabilityRegistry capabilityRegistry, YamlSkillCatalog yamlSkillCatalog) {
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry must not be null");
        this.yamlSkillCatalog = Objects.requireNonNull(yamlSkillCatalog, "yamlSkillCatalog must not be null");
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (YamlSkillDefinition definition : yamlSkillCatalog.getSkills()) {
            CapabilityMetadata metadata = new CapabilityMetadata(
                    capabilityId(definition.resource(), definition.manifest().getName()),
                    definition.manifest().getName(),
                    definition.manifest().getDescription(),
                    ModelPreference.LIGHT,
                    SkillExecutionDescriptor.from(definition.executionConfiguration()),
                    java.util.Set.of(),
                    resolveInvoker(definition));
            capabilityRegistry.register(metadata.name(), metadata);
        }
    }

    private CapabilityInvoker resolveInvoker(YamlSkillDefinition definition) {
        String targetId = definition.manifest().getMapping().getTargetId();
        if (StringUtils.hasText(targetId)) {
            CapabilityMetadata target = capabilityRegistry.getAllCapabilities().stream()
                    .filter(candidate -> targetId.equals(candidate.id()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Invalid YAML skill '" + definition.resource().getDescription()
                            + "' for field 'mapping.target_id': unknown target_id '" + targetId + "'"));
            return target.invoker();
        }

        return arguments -> {
            throw new UnsupportedOperationException("LLM-backed YAML execution is not implemented yet for skill '"
                    + definition.manifest().getName() + "'");
        };
    }

    private String capabilityId(Resource resource, String skillName) {
        String description = resource.getDescription().replace('\\', '/');
        return "yaml:" + skillName + ":" + description;
    }
}
