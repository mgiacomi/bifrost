package com.lokiscale.bifrost.runtime.tool;

import com.lokiscale.bifrost.runtime.input.SkillInputContract;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.lang.Nullable;

import java.util.Objects;

public final class ContractAwareToolCallback implements ToolCallback
{
    private final ToolCallback delegate;
    private final SkillInputContract inputContract;

    public ContractAwareToolCallback(ToolCallback delegate, SkillInputContract inputContract)
    {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.inputContract = Objects.requireNonNull(inputContract, "inputContract must not be null");
    }

    public SkillInputContract inputContract()
    {
        return inputContract;
    }

    @Override
    public ToolDefinition getToolDefinition()
    {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata()
    {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput)
    {
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext)
    {
        return delegate.call(toolInput, toolContext);
    }
}
