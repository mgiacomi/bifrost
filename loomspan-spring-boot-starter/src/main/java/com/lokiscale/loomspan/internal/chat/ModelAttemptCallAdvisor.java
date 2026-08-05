package com.lokiscale.loomspan.internal.chat;

import com.lokiscale.loomspan.internal.core.LoomspanSession;
import com.lokiscale.loomspan.internal.core.ExecutionFrame;
import com.lokiscale.loomspan.internal.core.ModelTraceContext;
import com.lokiscale.loomspan.internal.runtime.state.ExecutionStateService;
import com.lokiscale.loomspan.internal.runtime.usage.ModelUsageExtractor;
import com.lokiscale.loomspan.internal.runtime.usage.ModelUsageRecord;
import com.lokiscale.loomspan.internal.runtime.usage.SessionUsageService;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.Ordered;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ModelAttemptCallAdvisor implements CallAdvisor
{
    static final int ORDER = Ordered.LOWEST_PRECEDENCE - 1;

    private final ExecutionStateService executionStateService;
    private final ModelUsageExtractor modelUsageExtractor;
    private final SessionUsageService sessionUsageService;

    ModelAttemptCallAdvisor(ExecutionStateService executionStateService,
            ModelUsageExtractor modelUsageExtractor,
            SessionUsageService sessionUsageService)
    {
        this.executionStateService = Objects.requireNonNull(executionStateService, "executionStateService must not be null");
        this.modelUsageExtractor = Objects.requireNonNull(modelUsageExtractor, "modelUsageExtractor must not be null");
        this.sessionUsageService = Objects.requireNonNull(sessionUsageService, "sessionUsageService must not be null");
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain)
    {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(chain, "chain must not be null");

        Object rawContext = request.context().get(ModelTraceContext.REQUEST_CONTEXT_KEY);
        if (!(rawContext instanceof ModelTraceContext context))
        {
            throw new IllegalStateException("loomspan model call is missing its call-local trace context");
        }

        LoomspanSession session = LoomspanSession.getCurrentSession();
        ExecutionFrame frame = session.peekFrame();
        Map<String, Object> attempt = context.nextAttempt();
        Map<String, Object> requestPayload = requestPayload(request);

        executionStateService.recordModelRequestPrepared(session, frame, context, attempt, requestPayload);
        executionStateService.recordModelRequestSent(session, frame, context, attempt, requestPayload);

        ChatClientResponse response = chain.nextCall(request);
        ChatClientResponse linkedResponse = (response == null ? ChatClientResponse.builder() : response.mutate())
                .context(ModelTraceContext.RESPONSE_ATTEMPT_CONTEXT_KEY, attempt)
                .build();
        String responseText = responseText(linkedResponse);
        ModelUsageRecord usage = modelUsageExtractor.extract(
                linkedResponse.chatResponse(),
                request.prompt().getUserMessage().getText(),
                request.prompt().getSystemMessage().getText(),
                responseText);

        executionStateService.recordModelResponseReceived(
                session,
                frame,
                context,
                attempt,
                usage,
                Map.of("content", responseText));
        sessionUsageService.recordModelResponse(session, context.skillName(), context.identity(), usage);
        return linkedResponse;
    }

    @Override
    public String getName()
    {
        return "LoomspanModelAttemptCallAdvisor";
    }

    @Override
    public int getOrder()
    {
        return ORDER;
    }

    private Map<String, Object> requestPayload(ChatClientRequest request)
    {
        List<Map<String, Object>> messages = request.prompt().getInstructions().stream()
                .map(this::messagePayload)
                .toList();
        return Map.of("messages", messages);
    }

    private Map<String, Object> messagePayload(Message message)
    {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageType", message.getMessageType().name());
        payload.put("text", message.getText() == null ? "" : message.getText());
        return Map.copyOf(payload);
    }

    private String responseText(ChatClientResponse response)
    {
        if (response.chatResponse() == null
                || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null
                || response.chatResponse().getResult().getOutput().getText() == null)
        {
            return "";
        }
        return response.chatResponse().getResult().getOutput().getText();
    }
}
