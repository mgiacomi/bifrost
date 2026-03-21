package com.lokiscale.bifrost.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BifrostSessionJsonTest {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @Test
    void roundTripsSessionWithEmbeddedJournalThroughJackson() throws Exception {
        BifrostSession session = new BifrostSession("session-1", 4);
        session.pushFrame(frame("frame-1", "route.one"));
        session.logThought(Instant.parse("2026-03-15T12:00:00Z"), "plan");
        session.logToolExecution(
                Instant.parse("2026-03-15T12:00:01Z"),
                Map.of("route", "tool.run", "arguments", Map.of("id", 42)));
        session.replaceExecutionPlan(new ExecutionPlan(
                "plan-1",
                "root.visible.skill",
                Instant.parse("2026-03-15T12:00:00Z"),
                List.of(new PlanTask("task-1", "Plan", PlanTaskStatus.PENDING, null))));
        session.logPlanCreated(Instant.parse("2026-03-15T12:00:02Z"), session.getExecutionPlan().orElseThrow());

        String json = OBJECT_MAPPER.writeValueAsString(session);
        BifrostSession restored = OBJECT_MAPPER.readValue(json, BifrostSession.class);

        assertThat(restored.getSessionId()).isEqualTo("session-1");
        assertThat(restored.getMaxDepth()).isEqualTo(4);
        assertThat(restored.getFramesSnapshot()).containsExactlyElementsOf(session.getFramesSnapshot());
        assertThat(restored.getJournalSnapshot()).extracting(JournalEntry::type)
                .containsExactlyElementsOf(session.getJournalSnapshot().stream().map(JournalEntry::type).toList());
        assertThat(restored.getJournalSnapshot()).hasSize(session.getJournalSnapshot().size());
        assertThat(restored.getExecutionPlan()).contains(session.getExecutionPlan().orElseThrow());
    }

    @Test
    void rehydratesFreshLockAfterSessionDeserialization() throws Exception {
        BifrostSession session = new BifrostSession("session-1", 4);
        session.logThought(Instant.parse("2026-03-15T12:00:00Z"), "plan");
        session.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "user",
                "pw",
                AuthorityUtils.createAuthorityList("ROLE_ALLOWED")));

        String json = OBJECT_MAPPER.writeValueAsString(session);
        BifrostSession restored = OBJECT_MAPPER.readValue(json, BifrostSession.class);

        restored.logThought(Instant.parse("2026-03-15T12:00:01Z"), "after");
        restored.pushFrame(frame("frame-1", "route.one"));

        assertThat(restored.getJournalSnapshot()).hasSize(2);
        assertThat(restored.peekFrame().route()).isEqualTo("route.one");
        assertThat(restored.getAuthentication()).isEmpty();
    }

    private static ExecutionFrame frame(String frameId, String route) {
        return new ExecutionFrame(
                frameId,
                null,
                OperationType.CAPABILITY,
                route,
                Map.of("route", route),
                Instant.parse("2026-03-15T12:00:00Z"));
    }
}
