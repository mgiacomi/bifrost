package com.lokiscale.bifrost.internal.runtime.tool;

import com.lokiscale.bifrost.internal.runtime.input.SkillInputContract;
import org.springframework.ai.tool.ToolCallback;

public final class ContractAwareToolCallbacks
{
    private ContractAwareToolCallbacks()
    {
    }

    public static ToolCallback wrap(ToolCallback delegate, SkillInputContract inputContract)
    {
        return new ContractAwareToolCallback(delegate, inputContract);
    }
}
