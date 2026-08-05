package com.lokiscale.loomspan.internal.runtime.tool;

import com.lokiscale.loomspan.internal.runtime.input.SkillInputContract;
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
