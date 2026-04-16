package com.lokiscale.bifrost.skill;

import com.lokiscale.bifrost.core.CapabilityInvoker;
import com.lokiscale.bifrost.core.CapabilityKind;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.CapabilityToolDescriptor;
import com.lokiscale.bifrost.core.CapabilityRegistry;
import com.lokiscale.bifrost.core.ModelPreference;
import com.lokiscale.bifrost.core.SkillExecutionDescriptor;
import com.lokiscale.bifrost.runtime.input.SkillInputContract;
import com.lokiscale.bifrost.runtime.input.SkillInputContractResolver;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.Set;

public class YamlSkillCapabilityRegistrar implements SmartInitializingSingleton
{
    private final CapabilityRegistry capabilityRegistry;
    private final YamlSkillCatalog yamlSkillCatalog;
    private final SkillInputContractResolver inputContractResolver;

    public YamlSkillCapabilityRegistrar(CapabilityRegistry capabilityRegistry, YamlSkillCatalog yamlSkillCatalog)
    {
        this(capabilityRegistry, yamlSkillCatalog, new SkillInputContractResolver());
    }

    public YamlSkillCapabilityRegistrar(CapabilityRegistry capabilityRegistry,
            YamlSkillCatalog yamlSkillCatalog,
            SkillInputContractResolver inputContractResolver)
    {
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry must not be null");
        this.yamlSkillCatalog = Objects.requireNonNull(yamlSkillCatalog, "yamlSkillCatalog must not be null");
        this.inputContractResolver = Objects.requireNonNull(inputContractResolver, "inputContractResolver must not be null");
    }

    @Override
    public void afterSingletonsInstantiated()
    {
        for (YamlSkillDefinition definition : yamlSkillCatalog.getSkills())
        {
            CapabilityMetadata target = resolveMappedTarget(definition);
            SkillInputContract inputContract = resolveInputContract(definition, target);

            CapabilityMetadata metadata = new CapabilityMetadata(
                    capabilityId(definition.resource(), definition.manifest().getName()),
                    definition.manifest().getName(),
                    definition.manifest().getDescription(),
                    ModelPreference.LIGHT,
                    SkillExecutionDescriptor.from(definition.executionConfiguration()),
                    Set.copyOf(definition.rbacRoles()),
                    resolveInvoker(definition),
                    CapabilityKind.YAML_SKILL,
                    resolveToolDescriptor(definition, target, inputContract),
                    inputContract,
                    definition.mappingTargetId());

            capabilityRegistry.register(metadata.name(), metadata);
        }
    }

    private CapabilityInvoker resolveInvoker(YamlSkillDefinition definition)
    {
        String targetId = definition.manifest().getMapping().getTargetId();

        if (StringUtils.hasText(targetId))
        {
            CapabilityMetadata target = capabilityRegistry.getAllCapabilities().stream()
                    .filter(candidate -> targetId.equals(candidate.id()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Invalid YAML skill '" + definition.resource().getDescription()
                            + "' for field 'mapping.target_id': unknown target_id '" + targetId + "'"));

            return target.invoker();
        }

        return arguments ->
        {
            throw new UnsupportedOperationException("LLM-backed YAML execution is not implemented yet for skill '"
                    + definition.manifest().getName() + "'");
        };
    }

    private CapabilityToolDescriptor resolveToolDescriptor(YamlSkillDefinition definition, CapabilityMetadata target, SkillInputContract inputContract)
    {
        if (definition.hasDeclaredInputSchema() && !inputContract.isGeneric())
        {
            return new CapabilityToolDescriptor(
                    definition.manifest().getName(),
                    definition.manifest().getDescription(),
                    inputContractResolver.toJsonSchema(inputContract));
        }
        if (target == null)
        {
            return CapabilityToolDescriptor.generic(definition.manifest().getName(), definition.manifest().getDescription());
        }

        return new CapabilityToolDescriptor(definition.manifest().getName(), definition.manifest().getDescription(), target.tool().inputSchema());
    }

    private SkillInputContract resolveInputContract(YamlSkillDefinition definition, CapabilityMetadata target)
    {
        if (definition.hasDeclaredInputSchema() && target != null)
        {
            try
            {
                inputContractResolver.validateStructuralCompatibility(
                        inputContractResolver.resolveJavaCapability(target.tool().inputSchema()).schema(),
                        inputContractResolver.fromManifest(definition.inputSchema()),
                        "input_schema");
            }
            catch (IllegalStateException ex)
            {
                throw new IllegalStateException("Invalid YAML skill '" + definition.resource().getDescription()
                        + "' for field 'input_schema': " + ex.getMessage(), ex);
            }
        }

        return inputContractResolver.resolveYamlCapability(definition, target);
    }

    private CapabilityMetadata resolveMappedTarget(YamlSkillDefinition definition)
    {
        String targetId = definition.mappingTargetId();

        if (!StringUtils.hasText(targetId))
        {
            return null;
        }

        return capabilityRegistry.getAllCapabilities().stream()
                .filter(candidate -> targetId.equals(candidate.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Invalid YAML skill '" + definition.resource().getDescription()
                        + "' for field 'mapping.target_id': unknown target_id '" + targetId + "'"));
    }

    private String capabilityId(Resource resource, String skillName)
    {
        String description = resource.getDescription().replace('\\', '/');
        return "yaml:" + skillName + ":" + description;
    }
}
