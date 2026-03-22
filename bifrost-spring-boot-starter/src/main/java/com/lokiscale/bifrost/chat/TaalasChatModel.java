package com.lokiscale.bifrost.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lokiscale.bifrost.autoconfigure.TaalasChatProperties;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

public class TaalasChatModel implements ChatModel {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI generateUri;
    private final String apiKey;
    private final String defaultModel;

    public TaalasChatModel(HttpClient httpClient, ObjectMapper objectMapper, TaalasChatProperties properties) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        this.generateUri = URI.create(normalizeUrl(properties.getBaseUrl(), properties.getGeneratePath()));
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalArgumentException("spring.ai.taalas.api-key must not be blank when Taalas is enabled");
        }
        this.apiKey = properties.getApiKey();
        this.defaultModel = properties.getModel();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Objects.requireNonNull(prompt, "prompt must not be null");
        TaalasGenerateRequest request = createRequest(prompt);
        HttpRequest httpRequest = HttpRequest.newBuilder(generateUri)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(request), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        catch (IOException ex) {
            throw new IllegalStateException("Taalas request failed", ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Taalas request interrupted", ex);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Taalas request failed with status " + response.statusCode() + ": " + response.body());
        }
        JsonNode root = readJson(response.body());
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content(extractContent(root))
                .build();
        Generation generation = new Generation(
                assistantMessage,
                ChatGenerationMetadata.builder()
                        .finishReason(extractFinishReason(root))
                        .build());
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder()
                .id(extractId(root))
                .model(request.model());
        Usage usage = extractUsage(root);
        if (usage != null) {
            metadata.usage(usage);
        }
        return new ChatResponse(List.of(generation), metadata.build());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    private TaalasGenerateRequest createRequest(Prompt prompt) {
        return new TaalasGenerateRequest(
                prompt.getInstructions().stream().map(this::mapMessage).toList(),
                resolveModel(prompt.getOptions()),
                false);
    }

    private TaalasPromptMessage mapMessage(Message message) {
        return new TaalasPromptMessage(message.getMessageType().getValue(), Objects.toString(message.getText(), ""));
    }

    private String resolveModel(@Nullable ChatOptions options) {
        if (options != null && StringUtils.hasText(options.getModel())) {
            return options.getModel();
        }
        return defaultModel;
    }

    private String extractContent(JsonNode root) {
        String direct = textValue(root.get("content"));
        if (StringUtils.hasText(direct)) {
            return direct;
        }
        String response = textValue(root.get("response"));
        if (StringUtils.hasText(response)) {
            return response;
        }
        String text = textValue(root.get("text"));
        if (StringUtils.hasText(text)) {
            return text;
        }
        String output = textValue(root.get("output"));
        if (StringUtils.hasText(output)) {
            return output;
        }
        String messageContent = textValue(root.path("message").get("content"));
        if (StringUtils.hasText(messageContent)) {
            return messageContent;
        }
        JsonNode choices = root.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode firstChoice = choices.get(0);
            String choiceMessageContent = textValue(firstChoice.path("message").get("content"));
            if (StringUtils.hasText(choiceMessageContent)) {
                return choiceMessageContent;
            }
            String choiceText = textValue(firstChoice.get("text"));
            if (StringUtils.hasText(choiceText)) {
                return choiceText;
            }
        }
        throw new IllegalStateException("Taalas response did not contain assistant content");
    }

    private String extractFinishReason(JsonNode root) {
        String finishReason = textValue(root.get("finish_reason"));
        if (StringUtils.hasText(finishReason)) {
            return finishReason;
        }
        JsonNode choices = root.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            String choiceFinishReason = textValue(choices.get(0).get("finish_reason"));
            if (StringUtils.hasText(choiceFinishReason)) {
                return choiceFinishReason;
            }
        }
        return "stop";
    }

    private String extractId(JsonNode root) {
        String id = textValue(root.get("id"));
        return id == null ? "" : id;
    }

    private Usage extractUsage(JsonNode root) {
        JsonNode usageNode = root.get("usage");
        if (usageNode == null || usageNode.isNull()) {
            return null;
        }
        Integer promptTokens = intValue(usageNode, "prompt_tokens", "promptTokens", "input_tokens", "inputTokens");
        Integer completionTokens = intValue(usageNode, "completion_tokens", "completionTokens", "output_tokens", "outputTokens");
        return new TaalasUsage(promptTokens, completionTokens, usageNode);
    }

    private Integer intValue(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isNumber()) {
                return value.intValue();
            }
        }
        return null;
    }

    private String textValue(@Nullable JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isArray()) {
            StringBuilder joined = new StringBuilder();
            for (JsonNode element : node) {
                String text = textValue(element.get("text"));
                if (!StringUtils.hasText(text)) {
                    text = textValue(element);
                }
                if (StringUtils.hasText(text)) {
                    joined.append(text);
                }
            }
            return joined.isEmpty() ? null : joined.toString();
        }
        return null;
    }

    private JsonNode readJson(String body) {
        try {
            return objectMapper.readTree(body);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to parse Taalas response", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize Taalas request", ex);
        }
    }

    private static String normalizeUrl(String baseUrl, String path) {
        String normalizedBase = Objects.requireNonNull(baseUrl, "baseUrl must not be null").replaceAll("/+$", "");
        String normalizedPath = Objects.requireNonNull(path, "path must not be null");
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        return normalizedBase + normalizedPath;
    }

    private record TaalasGenerateRequest(List<TaalasPromptMessage> prompt, String model, boolean stream) {
    }

    private record TaalasPromptMessage(String role, String content) {
    }

    private record TaalasUsage(Integer promptTokens, Integer completionTokens, Object nativeUsage) implements Usage {

        @Override
        public Integer getPromptTokens() {
            return promptTokens;
        }

        @Override
        public Integer getCompletionTokens() {
            return completionTokens;
        }

        @Override
        public Object getNativeUsage() {
            return nativeUsage;
        }
    }
}
