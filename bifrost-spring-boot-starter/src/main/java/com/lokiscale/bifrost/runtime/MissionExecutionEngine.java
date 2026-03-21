package com.lokiscale.bifrost.runtime;

import com.lokiscale.bifrost.core.BifrostSession;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.util.List;

public interface MissionExecutionEngine {

    String executeMission(
            BifrostSession session,
            String skillName,
            String objective,
            ChatClient chatClient,
            List<ToolCallback> visibleTools,
            boolean planningEnabled,
            @Nullable Authentication authentication);
}
