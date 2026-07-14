package com.lokiscale.bifrost.skill;

import com.lokiscale.bifrost.core.CapabilityInvoker;
import com.lokiscale.bifrost.core.CapabilityKind;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.CapabilityToolDescriptor;
import com.lokiscale.bifrost.core.CapabilityRegistry;
import com.lokiscale.bifrost.core.PublicSkillImplementationType;
import com.lokiscale.bifrost.core.SkillExecutionDescriptor;
import com.lokiscale.bifrost.core.SkillImplementationTarget;
import com.lokiscale.bifrost.core.SkillImplementationTargetRegistry;
import com.lokiscale.bifrost.runtime.input.SkillInputContract;
import com.lokiscale.bifrost.runtime.input.SkillInputContractResolver;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

public class YamlSkillCapabilityRegistrar implements SmartInitializingSingleton, BeanFactoryAware
{
    private final CapabilityRegistry capabilityRegistry;
    private final SkillImplementationTargetRegistry targetRegistry;
    private final YamlSkillCatalog yamlSkillCatalog;
    private final SkillInputContractResolver inputContractResolver;
    private BeanFactory beanFactory;

    public YamlSkillCapabilityRegistrar(CapabilityRegistry capabilityRegistry,
            SkillImplementationTargetRegistry targetRegistry,
            YamlSkillCatalog yamlSkillCatalog,
            SkillInputContractResolver inputContractResolver)
    {
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry must not be null");
        this.targetRegistry = Objects.requireNonNull(targetRegistry, "targetRegistry must not be null");
        this.yamlSkillCatalog = Objects.requireNonNull(yamlSkillCatalog, "yamlSkillCatalog must not be null");
        this.inputContractResolver = Objects.requireNonNull(inputContractResolver, "inputContractResolver must not be null");
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException
    {
        this.beanFactory = Objects.requireNonNull(beanFactory, "beanFactory must not be null");
    }

    @Override
    public void afterSingletonsInstantiated()
    {
        for (YamlSkillDefinition definition : yamlSkillCatalog.getSkills())
        {
            SkillImplementationTarget target = resolveMappedTarget(definition);
            SkillInputContract inputContract = resolveInputContract(definition, target);

            CapabilityMetadata metadata = new CapabilityMetadata(
                    capabilityId(definition.resource(), definition.manifest().getName()),
                    definition.manifest().getName(),
                    definition.manifest().getDescription(),
                    definition.implementationType() == PublicSkillImplementationType.MAPPED_JAVA
                            ? SkillExecutionDescriptor.none()
                            : SkillExecutionDescriptor.from(definition.requireExecutionConfiguration()),
                    Set.copyOf(definition.rbacRoles()),
                    resolveInvoker(definition, target),
                    CapabilityKind.YAML_SKILL,
                    resolveToolDescriptor(definition, target, inputContract),
                    inputContract,
                    definition.mappingTargetId());

            capabilityRegistry.register(metadata.name(), metadata);
        }
    }

    private CapabilityInvoker resolveInvoker(YamlSkillDefinition definition, SkillImplementationTarget target)
    {
        if (target != null)
        {
            return arguments ->
            {
                try
                {
                    return target.invoker().invoke(arguments);
                }
                catch (RuntimeException ex)
                {
                    throw new IllegalStateException("Mapped YAML skill '" + definition.manifest().getName()
                            + "' failed during deterministic execution", ex);
                }
            };
        }

        return arguments ->
        {
            throw new UnsupportedOperationException("LLM-backed YAML execution is not implemented yet for skill '"
                    + definition.manifest().getName() + "'");
        };
    }

    private CapabilityToolDescriptor resolveToolDescriptor(YamlSkillDefinition definition, SkillImplementationTarget target, SkillInputContract inputContract)
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

        return new CapabilityToolDescriptor(definition.manifest().getName(), definition.manifest().getDescription(), target.inputSchema());
    }

    private SkillInputContract resolveInputContract(YamlSkillDefinition definition, SkillImplementationTarget target)
    {
        return inputContractResolver.resolveYamlCapability(definition, target);
    }

    private SkillImplementationTarget resolveMappedTarget(YamlSkillDefinition definition)
    {
        String targetId = definition.mappingTargetId();

        if (!StringUtils.hasText(targetId))
        {
            return null;
        }

        SkillImplementationTarget target = targetRegistry.getTarget(targetId);
        if (target == null)
        {
            initializeMappedTargetBean(targetId);
            target = targetRegistry.getTarget(targetId);
        }
        if (target == null)
        {
            throw new IllegalStateException("Invalid YAML skill '" + definition.manifest().getName()
                    + "' in '" + describe(definition.resource())
                    + "' for field 'mapping.target_id': unknown implementation target '" + targetId
                    + "'; correct mapping.target_id to reference a registered bean#method target.");
        }
        if (!targetId.equals(target.id()))
        {
            throw new IllegalStateException("Invalid YAML skill '" + definition.manifest().getName()
                    + "' in '" + describe(definition.resource())
                    + "' for field 'mapping.target_id': registry returned implementation target '"
                    + target.id() + "' for requested ID '" + targetId
                    + "'; correct the target registry entry for mapping.target_id.");
        }
        return target;
    }

    private void initializeMappedTargetBean(String targetId)
    {
        int separator = targetId.lastIndexOf('#');
        if (beanFactory == null || separator <= 0)
        {
            return;
        }

        String beanName = targetId.substring(0, separator);
        if (beanFactory.containsBean(beanName))
        {
            beanFactory.getBean(beanName);
        }
    }

    private String capabilityId(Resource resource, String skillName)
    {
        String description = resource.getDescription().replace('\\', '/');
        return "yaml:" + skillName + ":" + description;
    }

    private String describe(Resource resource)
    {
        try
        {
            return resource.getURI().toString();
        }
        catch (IOException ex)
        {
            return resource.getDescription();
        }
    }
}
