package com.lokiscale.bifrost.core;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.ParameterizedTypeReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class FakeCoordinatorChatClient implements ChatClient {

    private final ExecutionPlan plan;
    private final String content;
    private final String toolPayloadJson;
    private final boolean firstCallReturnsPlan;
    final List<String> toolNamesSeen = new ArrayList<>();
    final List<String> systemMessagesSeen = new ArrayList<>();
    final List<String> userMessagesSeen = new ArrayList<>();
    final List<List<String>> toolNamesByCall = new ArrayList<>();
    Object lastToolResult;
    private int callCount;

    FakeCoordinatorChatClient(ExecutionPlan plan, String content, String toolPayloadJson) {
        this(plan, content, toolPayloadJson, true);
    }

    FakeCoordinatorChatClient(ExecutionPlan plan, String content, String toolPayloadJson, boolean firstCallReturnsPlan) {
        this.plan = plan;
        this.content = content;
        this.toolPayloadJson = toolPayloadJson;
        this.firstCallReturnsPlan = firstCallReturnsPlan;
    }

    @Override
    public ChatClientRequestSpec prompt() {
        return new FakeRequestSpec();
    }

    @Override
    public ChatClientRequestSpec prompt(String content) {
        return new FakeRequestSpec();
    }

    @Override
    public ChatClientRequestSpec prompt(org.springframework.ai.chat.prompt.Prompt prompt) {
        return new FakeRequestSpec();
    }

    @Override
    public Builder mutate() {
        throw new UnsupportedOperationException();
    }

    private final class FakeRequestSpec implements ChatClientRequestSpec {

        private List<ToolCallback> toolCallbacks = List.of();

        @Override
        public Builder mutate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatClientRequestSpec advisors(java.util.function.Consumer<AdvisorSpec> consumer) {
            return this;
        }

        @Override
        public ChatClientRequestSpec advisors(org.springframework.ai.chat.client.advisor.api.Advisor... advisors) {
            return this;
        }

        @Override
        public ChatClientRequestSpec advisors(List<org.springframework.ai.chat.client.advisor.api.Advisor> advisors) {
            return this;
        }

        @Override
        public ChatClientRequestSpec messages(org.springframework.ai.chat.messages.Message... messages) {
            return this;
        }

        @Override
        public ChatClientRequestSpec messages(List<org.springframework.ai.chat.messages.Message> messages) {
            return this;
        }

        @Override
        public <T extends org.springframework.ai.chat.prompt.ChatOptions> ChatClientRequestSpec options(T options) {
            return this;
        }

        @Override
        public ChatClientRequestSpec toolNames(String... toolNames) {
            return this;
        }

        @Override
        public ChatClientRequestSpec tools(Object... tools) {
            return this;
        }

        @Override
        public ChatClientRequestSpec toolCallbacks(ToolCallback... toolCallbacks) {
            this.toolCallbacks = List.of(toolCallbacks);
            return this;
        }

        @Override
        public ChatClientRequestSpec toolCallbacks(List<ToolCallback> toolCallbacks) {
            this.toolCallbacks = List.copyOf(toolCallbacks);
            return this;
        }

        @Override
        public ChatClientRequestSpec toolCallbacks(org.springframework.ai.tool.ToolCallbackProvider... providers) {
            return this;
        }

        @Override
        public ChatClientRequestSpec toolContext(Map<String, Object> toolContext) {
            return this;
        }

        @Override
        public ChatClientRequestSpec system(String text) {
            systemMessagesSeen.add(text);
            return this;
        }

        @Override
        public ChatClientRequestSpec system(org.springframework.core.io.Resource resource, java.nio.charset.Charset charset) {
            return this;
        }

        @Override
        public ChatClientRequestSpec system(org.springframework.core.io.Resource resource) {
            return this;
        }

        @Override
        public ChatClientRequestSpec system(java.util.function.Consumer<PromptSystemSpec> consumer) {
            return this;
        }

        @Override
        public ChatClientRequestSpec user(String text) {
            userMessagesSeen.add(text);
            return this;
        }

        @Override
        public ChatClientRequestSpec user(org.springframework.core.io.Resource resource, java.nio.charset.Charset charset) {
            return this;
        }

        @Override
        public ChatClientRequestSpec user(org.springframework.core.io.Resource resource) {
            return this;
        }

        @Override
        public ChatClientRequestSpec user(java.util.function.Consumer<PromptUserSpec> consumer) {
            return this;
        }

        @Override
        public ChatClientRequestSpec templateRenderer(org.springframework.ai.template.TemplateRenderer renderer) {
            return this;
        }

        @Override
        public CallResponseSpec call() {
            int current = ++callCount;
            toolNamesSeen.clear();
            toolNamesSeen.addAll(toolCallbacks.stream().map(callback -> callback.getToolDefinition().name()).toList());
            toolNamesByCall.add(List.copyOf(toolNamesSeen));
            if ((!firstCallReturnsPlan && current == 1 || firstCallReturnsPlan && current == 2) && !toolCallbacks.isEmpty()) {
                lastToolResult = toolCallbacks.getFirst().call(toolPayloadJson);
            }
            Object payload = firstCallReturnsPlan && current == 1 ? plan : content;
            return new FakeCallResponseSpec(payload);
        }

        @Override
        public StreamResponseSpec stream() {
            throw new UnsupportedOperationException();
        }
    }

    private record FakeCallResponseSpec(Object payload) implements CallResponseSpec {

        @Override
        public <T> T entity(ParameterizedTypeReference<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T entity(org.springframework.ai.converter.StructuredOutputConverter<T> converter) {
            throw new UnsupportedOperationException();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T entity(Class<T> type) {
            return (T) payload;
        }

        @Override
        public ChatClientResponse chatClientResponse() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResponse chatResponse() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String content() {
            return String.valueOf(payload);
        }

        @Override
        public <T> org.springframework.ai.chat.client.ResponseEntity<ChatResponse, T> responseEntity(Class<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> org.springframework.ai.chat.client.ResponseEntity<ChatResponse, T> responseEntity(ParameterizedTypeReference<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> org.springframework.ai.chat.client.ResponseEntity<ChatResponse, T> responseEntity(
                org.springframework.ai.converter.StructuredOutputConverter<T> converter) {
            throw new UnsupportedOperationException();
        }
    }
}
