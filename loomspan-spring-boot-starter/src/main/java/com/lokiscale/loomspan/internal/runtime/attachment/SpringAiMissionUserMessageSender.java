package com.lokiscale.loomspan.internal.runtime.attachment;

import com.lokiscale.loomspan.internal.skill.EffectiveSkillExecutionConfiguration;
import com.lokiscale.loomspan.internal.core.ModelTraceContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.MimeTypeUtils;

import java.util.List;
import java.util.stream.Collectors;

public class SpringAiMissionUserMessageSender implements MissionUserMessageSender
{
    @Override
    public ChatClient.CallResponseSpec send(ChatClient chatClient,
            String systemPrompt,
            RenderedMissionInput renderedInput,
            List<ToolCallback> visibleTools,
            String skillName,
            EffectiveSkillExecutionConfiguration executionConfiguration,
            ModelTraceContext modelTraceContext)
    {
        try
        {
            ChatClient.ChatClientRequestSpec request = chatClient.prompt().system(systemPrompt);
            if (renderedInput.attachments().isEmpty())
            {
                request = request.user(renderedInput.userText());
            }
            else
            {
                request = request.user(user ->
                {
                    user.text(renderedInput.userText());
                    for (LoomspanAttachment attachment : renderedInput.attachments())
                    {
                        user.media(MimeTypeUtils.parseMimeType(attachment.contentType()), attachment.resource());
                    }
                });
            }
            if (visibleTools != null && !visibleTools.isEmpty())
            {
                request = request.toolCallbacks(visibleTools);
            }
            return request
                    .advisors(spec -> spec.param(ModelTraceContext.REQUEST_CONTEXT_KEY, modelTraceContext))
                    .call();
        }
        catch (RuntimeException ex)
        {
            if (renderedInput.attachments().isEmpty())
            {
                throw ex;
            }
            String mediaDetails = renderedInput.attachments().stream()
                    .map(attachment -> attachment.mediaType() + "/" + attachment.contentType())
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException("Model call for skill '" + skillName + "' using framework model '"
                    + executionConfiguration.frameworkModel() + "' through connection '" + executionConfiguration.connection()
                    + "' (driver " + executionConfiguration.driver().name() + ", provider model '"
                    + executionConfiguration.providerModel() + "') failed with " + renderedInput.attachments().size()
                    + " attachment(s) [" + mediaDetails
                    + "]. Use a model/driver that supports the declared attachment media.", ex);
        }
    }
}
