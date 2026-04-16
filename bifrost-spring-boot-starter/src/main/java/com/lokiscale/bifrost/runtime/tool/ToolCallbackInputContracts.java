package com.lokiscale.bifrost.runtime.tool;

import com.lokiscale.bifrost.runtime.input.SkillInputContract;
import com.lokiscale.bifrost.runtime.input.SkillInputContractResolver;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;

public final class ToolCallbackInputContracts
{
    private static final SkillInputContractResolver INPUT_CONTRACT_RESOLVER = new SkillInputContractResolver();

    private ToolCallbackInputContracts()
    {
    }

    public static SkillInputContract resolve(@Nullable ToolCallback callback)
    {
        if (callback instanceof ContractAwareToolCallback contractAwareToolCallback)
        {
            return contractAwareToolCallback.inputContract();
        }
        if (callback == null || callback.getToolDefinition() == null)
        {
            return SkillInputContract.genericObject();
        }

        String inputSchema = callback.getToolDefinition().inputSchema();
        if (inputSchema == null || inputSchema.isBlank())
        {
            return SkillInputContract.genericObject();
        }

        try
        {
            return INPUT_CONTRACT_RESOLVER.resolveFromToolSchema(inputSchema);
        }
        catch (RuntimeException ignored)
        {
            return SkillInputContract.genericObject();
        }
    }
}
