package com.lokiscale.bifrost.autoconfigure;

import com.lokiscale.bifrost.autoconfigure.BifrostProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionProtocolTest {

    @Test
    void openAiCompatibleBaseUrlEndingInV1DoesNotDuplicateTheVersionPath() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("""
                    {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"routed-model",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """));
            BifrostProperties.ConnectionProperties properties = new BifrostProperties.ConnectionProperties();
            properties.setApiKey("gateway-key");
            properties.setBaseUrl(server.url("/api/v1").toString());

            var model = new OpenAiConnectionChatModelFactory().create("gateway", properties);
            model.call(new Prompt("hello", OpenAiChatOptions.builder().model("routed-model").build()));

            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getPath()).isEqualTo("/api/v1/chat/completions");
        }
    }

    @Test
    void anthropicConnectionUsesConfiguredNativePathAndVersionHeaders() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("""
                    {"id":"msg_1","type":"message","role":"assistant","model":"claude-test",
                     "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","stop_sequence":null,
                     "usage":{"input_tokens":1,"output_tokens":1}}
                    """));
            BifrostProperties.ConnectionProperties properties = new BifrostProperties.ConnectionProperties();
            properties.setApiKey("anthropic-secret");
            properties.setBaseUrl(server.url("/").toString());
            BifrostProperties.AnthropicOptions anthropic = new BifrostProperties.AnthropicOptions();
            anthropic.setCompletionsPath("/custom/messages");
            anthropic.setVersion("2026-01-01");
            anthropic.setBetaVersion("test-beta");
            properties.setAnthropic(anthropic);

            var model = new AnthropicConnectionChatModelFactory().create("anthropic-main", properties);
            model.call(new Prompt("hello", AnthropicChatOptions.builder().model("claude-test").maxTokens(16).build()));

            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getPath()).isEqualTo("/custom/messages");
            assertThat(request.getHeader("x-api-key")).isEqualTo("anthropic-secret");
            assertThat(request.getHeader("anthropic-version")).isEqualTo("2026-01-01");
            assertThat(request.getHeader("anthropic-beta")).isEqualTo("test-beta");
            assertThat(request.getBody().readUtf8()).contains("\"model\":\"claude-test\"");
        }
    }

    @Test
    void openAiConnectionUsesConfiguredPathCredentialsAndHeaders() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("""
                    {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-test",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """));
            BifrostProperties.ConnectionProperties properties = new BifrostProperties.ConnectionProperties();
            properties.setApiKey("secret-key");
            properties.setBaseUrl(server.url("/").toString());
            properties.setHeaders(Map.of("X-Tenant", "tenant-a"));
            BifrostProperties.OpenAiOptions openAi = new BifrostProperties.OpenAiOptions();
            openAi.setOrganizationId("org-a");
            openAi.setProjectId("project-a");
            openAi.setChatCompletionsPath("/custom/chat/completions");
            properties.setOpenai(openAi);

            var model = new OpenAiConnectionChatModelFactory().create("openai-main", properties);
            model.call(new Prompt("hello", OpenAiChatOptions.builder().model("gpt-test").build()));

            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getPath()).isEqualTo("/custom/chat/completions");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer secret-key");
            assertThat(request.getHeader("X-Tenant")).isEqualTo("tenant-a");
            assertThat(request.getHeader("OpenAI-Organization")).isEqualTo("org-a");
            assertThat(request.getHeader("OpenAI-Project")).isEqualTo("project-a");
            assertThat(request.getBody().readUtf8()).contains("\"model\":\"gpt-test\"");
        }
    }

    @Test
    void ollamaConnectionUsesNativeChatEndpoint() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("""
                    {"model":"qwen","created_at":"2026-07-14T00:00:00Z",
                     "message":{"role":"assistant","content":"ok"},"done":true,"done_reason":"stop",
                     "prompt_eval_count":1,"eval_count":1}
                    """));
            BifrostProperties.ConnectionProperties properties = new BifrostProperties.ConnectionProperties();
            properties.setBaseUrl(server.url("/").toString());

            var model = new OllamaConnectionChatModelFactory().create("ollama-local", properties);
            model.call(new Prompt("hello", OllamaChatOptions.builder().model("qwen").build()));

            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getPath()).isEqualTo("/api/chat");
            assertThat(request.getBody().readUtf8()).contains("\"model\":\"qwen\"");
        }
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
