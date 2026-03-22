package com.lokiscale.bifrost.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.lokiscale.bifrost.linter.LinterOutcome;
import com.lokiscale.bifrost.linter.LinterOutcomeStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkillThoughtTraceTest {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @Test
    void filtersAndSanitizesSkillThoughtsByRoute() throws Exception {
        BifrostSession session = new BifrostSession("session-1", 4);
        ExecutionFrame targetFrame = frame("frame-1", "target.route");
        ExecutionFrame otherFrame = frame("frame-2", "other.route");

        session.pushFrame(targetFrame);
        session.logThought(Instant.parse("2026-03-21T12:00:00Z"), "Plan carefully.");
        session.logToolExecution(
                Instant.parse("2026-03-21T12:00:01Z"),
                TaskExecutionEvent.linked(
                        "lookup.customer",
                        "task-1",
                        Map.of("arguments", Map.of("apiKey", "secret", "customerId", 42)),
                        null));
        session.logToolResult(
                Instant.parse("2026-03-21T12:00:02Z"),
                TaskExecutionEvent.linked(
                        "lookup.customer",
                        "task-1",
                        Map.of("result", Map.of("token", "secret", "count", 2)),
                        null));
        session.logLinterOutcome(
                Instant.parse("2026-03-21T12:00:03Z"),
                new LinterOutcome("target.route", "regex", 2, 1, 3, LinterOutcomeStatus.PASSED, "Return YAML only."));
        session.logError(
                Instant.parse("2026-03-21T12:00:04Z"),
                Map.of(
                        "tool", "lookup.customer",
                        "linkedTaskId", "task-1",
                        "message", "boom",
                        "exceptionType", "IllegalStateException",
                        "context", Map.of("secret", "value")));
        session.popFrame();

        session.pushFrame(otherFrame);
        session.logThought(Instant.parse("2026-03-21T12:00:05Z"), "Other route thought.");
        session.popFrame();

        SkillThoughtTrace trace = session.getSkillThoughts("target.route");
        String json = OBJECT_MAPPER.writeValueAsString(trace);
        SkillThoughtTrace restored = OBJECT_MAPPER.readValue(json, SkillThoughtTrace.class);

        assertThat(trace.route()).isEqualTo("target.route");
        assertThat(trace.thoughts()).extracting(SkillThought::content)
                .containsExactly(
                        "Plan carefully.",
                        "Tool call: lookup.customer (task task-1)",
                        "Tool result: lookup.customer completed (task task-1)",
                        "Linter PASSED for target.route (attempt 2, max retries 3) via regex",
                        "Error in lookup.customer: boom (IllegalStateException)");
        assertThat(trace.thoughts()).extracting(SkillThought::content)
                .allSatisfy(content -> {
                    assertThat(content).doesNotContain("secret");
                    assertThat(content).doesNotContain("apiKey");
                    assertThat(content).doesNotContain("customerId");
                    assertThat(content).doesNotContain("token");
                    assertThat(content).doesNotContain("Other route thought.");
                });
        assertThat(restored).isEqualTo(trace);
    }

    @Test
    void mapsBlankErrorMessagesWithoutLeakingPayloadDetails() {
        SkillThoughtMapper mapper = new SkillThoughtMapper();
        JournalEntry entry = new JournalEntry(
                Instant.parse("2026-03-21T12:00:00Z"),
                JournalLevel.ERROR,
                JournalEntryType.ERROR,
                OBJECT_MAPPER.valueToTree(Map.of(
                        "tool", "lookup.customer",
                        "message", "   ",
                        "exceptionType", "IllegalStateException",
                        "variables", Map.of("secret", "value"))),
                "frame-1",
                "target.route");

        SkillThoughtTrace trace = mapper.toTrace("target.route", List.of(entry));

        assertThat(trace.thoughts()).singleElement().satisfies(thought -> {
            assertThat(thought.content()).isEqualTo("Error in lookup.customer (IllegalStateException)");
            assertThat(thought.content()).doesNotContain("secret");
            assertThat(thought.content()).doesNotContain("variables");
        });
    }

    private static ExecutionFrame frame(String frameId, String route) {
        return new ExecutionFrame(
                frameId,
                null,
                OperationType.CAPABILITY,
                route,
                Map.of("route", route),
                Instant.parse("2026-03-21T12:00:00Z"));
    }
}
