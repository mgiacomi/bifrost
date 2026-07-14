package com.lokiscale.bifrost.core;

import com.lokiscale.bifrost.runtime.input.SkillInputContract;
import org.springframework.lang.Nullable;

import java.util.Objects;
import java.util.Set;

public record CapabilityMetadata(
        String id,
        String name,
        String description,
        ModelPreference modelPreference,
        SkillExecutionDescriptor skillExecution,
        Set<String> rbacRoles,
        CapabilityInvoker invoker,
        CapabilityKind kind,
        CapabilityToolDescriptor tool,
        SkillInputContract inputContract,
        @Nullable String mappedTargetId)
{
    public CapabilityMetadata
    {
        id = requireNonBlank(id, "id");
        name = requireNonBlank(name, "name");
        description = requireNonBlank(description, "description");
        modelPreference = modelPreference == null ? ModelPreference.LIGHT : modelPreference;
        skillExecution = skillExecution == null ? SkillExecutionDescriptor.none() : skillExecution;
        rbacRoles = rbacRoles == null ? Set.of() : Set.copyOf(rbacRoles);
        invoker = Objects.requireNonNull(invoker, "invoker must not be null");
        kind = Objects.requireNonNull(kind, "kind must not be null");
        tool = tool == null ? CapabilityToolDescriptor.generic(name, description) : tool;
        if (!name.equals(tool.name()))
        {
            throw new IllegalArgumentException("tool.name must match the public YAML skill name");
        }
        inputContract = inputContract == null ? SkillInputContract.genericObject() : inputContract;
        mappedTargetId = mappedTargetId == null || mappedTargetId.isBlank() ? null : mappedTargetId.trim();
    }

    public CapabilityMetadata(String id,
            String name,
            String description,
            ModelPreference modelPreference,
            SkillExecutionDescriptor skillExecution,
            Set<String> rbacRoles,
            CapabilityInvoker invoker,
            CapabilityKind kind,
            CapabilityToolDescriptor tool,
            @Nullable String mappedTargetId)
    {
        this(id, name, description, modelPreference, skillExecution, rbacRoles, invoker, kind, tool, null, mappedTargetId);
    }

    public PublicSkillImplementationType implementationType()
    {
        return mappedTargetId == null || mappedTargetId.isBlank()
                ? PublicSkillImplementationType.LLM_BACKED
                : PublicSkillImplementationType.MAPPED_JAVA;
    }

    private static String requireNonBlank(String value, String fieldName)
    {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank())
        {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
