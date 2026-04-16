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
        kind = kind == null ? CapabilityKind.JAVA_METHOD : kind;
        tool = tool == null ? CapabilityToolDescriptor.generic(name, description) : tool;
        inputContract = inputContract == null ? SkillInputContract.genericObject() : inputContract;
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
