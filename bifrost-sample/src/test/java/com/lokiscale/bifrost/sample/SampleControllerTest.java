package com.lokiscale.bifrost.sample;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.BifrostSessionRunner;
import com.lokiscale.bifrost.core.CapabilityExecutionRouter;
import com.lokiscale.bifrost.core.CapabilityRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SampleControllerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void trackedSessionDebugPayloadExposesExecutionTraceAndOptionalJournal() {
        SampleController controller = new SampleController(
                mock(CapabilityRegistry.class),
                mock(CapabilityExecutionRouter.class),
                new BifrostSessionRunner(4));
        BifrostSessionRunner sessionRunner = new BifrostSessionRunner(4);
        BifrostSession session = sessionRunner.callWithNewSession(currentSession -> currentSession);

        @SuppressWarnings("unchecked")
        Map<String, BifrostSession> recentSessions = (Map<String, BifrostSession>) ReflectionTestUtils.getField(controller, "recentSessions");
        recentSessions.put(session.getSessionId(), session);

        @SuppressWarnings("unchecked")
        Map<String, Object> withoutJournal = (Map<String, Object>) controller.getTrackedSession(session.getSessionId(), false);
        @SuppressWarnings("unchecked")
        Map<String, Object> withJournal = (Map<String, Object>) controller.getTrackedSession(session.getSessionId(), true);

        assertThat(withoutJournal).containsKeys("sessionId", "status", "frames", "executionTrace", "executionPlan", "lastLinterOutcome", "lastOutputSchemaOutcome", "sessionUsage");
        assertThat(withoutJournal).doesNotContainKey("executionJournal");
        assertThat(withJournal).containsKey("executionJournal");
        assertThat(withoutJournal.get("executionTrace")).isNotNull();
        assertThat(withJournal.get("executionJournal")).isNotNull();
        assertThat(withJournal.get("status")).isEqualTo("recent");
    }
}
