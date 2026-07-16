package com.lokiscale.bifrost.internal.runtime.attachment;

import com.lokiscale.bifrost.autoconfigure.AiDriver;
import com.lokiscale.bifrost.internal.runtime.SimpleChatClient;
import com.lokiscale.bifrost.internal.skill.EffectiveSkillExecutionConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiMissionUserMessageSenderTest
{
    private final SpringAiMissionUserMessageSender sender = new SpringAiMissionUserMessageSender();

    @Test
    void capturesSpringAiMediaRequestShape()
    {
        SimpleChatClient chatClient = new SimpleChatClient(null, "ok");
        ByteArrayResource resource = new ByteArrayResource("image-bytes".getBytes())
        {
            @Override
            public String getFilename()
            {
                return "ticket.jpg";
            }
        };
        RenderedMissionInput renderedInput = new RenderedMissionInput(
                "Input descriptor",
                List.of(new BifrostAttachment(
                        "image",
                        "ticket.jpg",
                        "image/jpeg",
                        AttachmentMediaType.IMAGE,
                        "resource",
                        11L,
                        "digest",
                        Map.of(),
                        resource)),
                Map.of("image", Map.of("attachment", true)));

        sender.send(chatClient, "system", renderedInput, List.of(), "skill", config()).content();

        assertThat(chatClient.getUserMessagesSeen()).containsExactly("Input descriptor");
        assertThat(chatClient.getUserMediaSeen()).hasSize(1);
        assertThat(chatClient.getUserMediaSeen().get(0).mimeType().toString()).isEqualTo("image/jpeg");
        assertThat(chatClient.getUserMediaSeen().get(0).resource()).isSameAs(resource);
    }

    @Test
    void fallsBackToTextOnlyUserMessageWhenNoAttachmentsArePresent()
    {
        SimpleChatClient chatClient = new SimpleChatClient(null, "ok");

        sender.send(chatClient, "system", new RenderedMissionInput("Plain input", List.of(), Map.of()),
                List.of(), "skill", config()).content();

        assertThat(chatClient.getUserMessagesSeen()).containsExactly("Plain input");
        assertThat(chatClient.getUserMediaSeen()).isEmpty();
    }

    private EffectiveSkillExecutionConfiguration config()
    {
        return new EffectiveSkillExecutionConfiguration("model", "test-connection", AiDriver.OPENAI, "gpt-4.1-mini", null);
    }
}
