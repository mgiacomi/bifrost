package com.lokiscale.bifrost.linter;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.BifrostSessionRunner;
import com.lokiscale.bifrost.core.JournalEntry;
import com.lokiscale.bifrost.core.JournalEntryType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class LinterCallAdvisorTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void returnsPassingResponseWithoutRetry() {
        LinterCallAdvisor advisor = advisor(1);
        RecordingChain chain = new RecordingChain(List.of("OK: valid"));

        ChatClientResponse response = advisor.adviseCall(request("Write YAML"), chain);

        assertThat(chain.requests).hasSize(1);
        assertThat(text(response)).isEqualTo("OK: valid");
        assertThat(response.context()).containsKey(LinterCallAdvisor.CONTEXT_KEY);
        assertThat((LinterOutcome) response.context().get(LinterCallAdvisor.CONTEXT_KEY))
                .extracting(LinterOutcome::status, LinterOutcome::retryCount)
                .containsExactly(LinterOutcomeStatus.PASSED, 0);
    }

    @Test
    void retriesWithCorrectiveHintAfterFailedValidation() {
        LinterCallAdvisor advisor = advisor(2);
        RecordingChain chain = new RecordingChain(List.of("invalid", "OK: corrected"));

        ChatClientResponse response = advisor.adviseCall(request("Write YAML"), chain);

        assertThat(chain.requests).hasSize(2);
        assertThat(chain.requests.get(1).prompt().getUserMessage().getText())
                .isEqualTo("Write YAML");
        assertThat(chain.requests.get(1).prompt().getSystemMessage().getText())
                .contains("Linter validation failed")
                .contains("Return fenced YAML only.");
        assertThat((LinterOutcome) response.context().get(LinterCallAdvisor.CONTEXT_KEY))
                .extracting(LinterOutcome::status, LinterOutcome::retryCount, LinterOutcome::attempt)
                .containsExactly(LinterOutcomeStatus.PASSED, 1, 2);
    }

    @Test
    void stopsRetryingWhenMaxRetriesAreExhausted() {
        LinterCallAdvisor advisor = advisor(2);
        RecordingChain chain = new RecordingChain(List.of("bad-1", "bad-2", "bad-3"));

        ChatClientResponse response = advisor.adviseCall(request("Write YAML"), chain);

        assertThat(chain.requests).hasSize(3);
        assertThat(text(response)).isEqualTo("bad-3");
        assertThat((LinterOutcome) response.context().get(LinterCallAdvisor.CONTEXT_KEY))
                .extracting(LinterOutcome::status, LinterOutcome::retryCount, LinterOutcome::attempt)
                .containsExactly(LinterOutcomeStatus.EXHAUSTED, 2, 3);
    }

    @Test
    void recordsObservableOutcomeOnBoundSession() {
        LinterCallAdvisor advisor = advisor(1);
        RecordingChain chain = new RecordingChain(List.of("bad", "OK: corrected"));
        BifrostSessionRunner runner = new BifrostSessionRunner(3);

        runner.callWithNewSession(session -> {
            ChatClientResponse response = advisor.adviseCall(request("Write YAML"), chain);

            assertThat(text(response)).isEqualTo("OK: corrected");
            assertThat(session.getLastLinterOutcome()).isPresent();
            assertThat(session.getLastLinterOutcome().orElseThrow())
                    .extracting(LinterOutcome::status, LinterOutcome::retryCount)
                    .containsExactly(LinterOutcomeStatus.PASSED, 1);
            assertThat(session.getJournalSnapshot())
                    .extracting(JournalEntry::type)
                    .containsExactly(JournalEntryType.LINTER, JournalEntryType.LINTER);
            return session;
        });
    }

    private static LinterCallAdvisor advisor(int maxRetries) {
        return new LinterCallAdvisor(
                "linted.skill",
                "regex",
                Pattern.compile("^OK:.*$"),
                "Return fenced YAML only.",
                maxRetries,
                FIXED_CLOCK);
    }

    private static ChatClientRequest request(String text) {
        return new ChatClientRequest(new Prompt(text), Map.of());
    }

    private static String text(ChatClientResponse response) {
        return response.chatResponse().getResult().getOutput().getText();
    }

    private static final class RecordingChain implements CallAdvisorChain {

        private final List<String> responses;
        private final List<ChatClientRequest> requests = new ArrayList<>();
        private int index;

        private RecordingChain(List<String> responses) {
            this.responses = responses;
        }

        @Override
        public ChatClientResponse nextCall(ChatClientRequest chatClientRequest) {
            requests.add(chatClientRequest.copy());
            String responseText = responses.get(Math.min(index, responses.size() - 1));
            index++;
            return ChatClientResponse.builder()
                    .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(responseText)))))
                    .build();
        }

        @Override
        public List<CallAdvisor> getCallAdvisors() {
            return List.of();
        }

        @Override
        public CallAdvisorChain copy(CallAdvisor after) {
            return this;
        }
    }
}
