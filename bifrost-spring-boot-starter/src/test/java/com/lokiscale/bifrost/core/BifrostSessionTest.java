package com.lokiscale.bifrost.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BifrostSessionTest {

    @Test
    void createsSessionWithGeneratedIdAndConfiguredMaxDepth() {
        BifrostSession session = new BifrostSession(3);

        assertThat(session.getSessionId()).isNotBlank();
        assertThat(session.getMaxDepth()).isEqualTo(3);
        assertThat(session.getFramesSnapshot()).isEmpty();
    }

    @Test
    void pushAndPopFrameUsesLifoOrder() {
        BifrostSession session = new BifrostSession(3);
        ExecutionFrame first = frame("frame-1", "route.one");
        ExecutionFrame second = frame("frame-2", "route.two");

        session.pushFrame(first);
        session.pushFrame(second);

        assertThat(session.peekFrame()).isEqualTo(second);
        assertThat(session.getFramesSnapshot()).containsExactly(second, first);
        assertThat(session.popFrame()).isEqualTo(second);
        assertThat(session.popFrame()).isEqualTo(first);
        assertThat(session.getFramesSnapshot()).isEmpty();
    }

    @Test
    void throwsWhenPushingBeyondMaxDepthWithoutMutatingStack() {
        BifrostSession session = new BifrostSession("session-1", 1);
        ExecutionFrame first = frame("frame-1", "route.one");
        ExecutionFrame second = frame("frame-2", "route.two");
        session.pushFrame(first);

        assertThatThrownBy(() -> session.pushFrame(second))
                .isInstanceOf(BifrostStackOverflowException.class)
                .hasMessageContaining("session-1")
                .hasMessageContaining("route.two")
                .hasMessageContaining("1");

        assertThat(session.peekFrame()).isEqualTo(first);
        assertThat(session.getFramesSnapshot()).containsExactly(first);
    }

    @Test
    void throwsWhenPoppingEmptyStack() {
        BifrostSession session = new BifrostSession(2);

        assertThatThrownBy(session::popFrame)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot pop execution frame from an empty session stack.");
    }

    @Test
    void exposesImmutableFrameSnapshots() {
        BifrostSession session = new BifrostSession(2);
        session.pushFrame(frame("frame-1", "route.one"));

        List<ExecutionFrame> snapshot = session.getFramesSnapshot();

        assertThatThrownBy(() -> snapshot.add(frame("frame-2", "route.two")))
                .isInstanceOf(UnsupportedOperationException.class);
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
