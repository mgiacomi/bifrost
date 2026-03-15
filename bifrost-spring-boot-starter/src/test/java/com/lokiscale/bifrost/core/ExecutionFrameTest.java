package com.lokiscale.bifrost.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionFrameTest {

    @Test
    void defensivelyCopiesExecutionFrameParameters() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("value", "before");

        ExecutionFrame frame = new ExecutionFrame(
                "frame-1",
                null,
                OperationType.CAPABILITY,
                "calculator.add",
                parameters,
                Instant.parse("2026-03-15T12:00:00Z"));

        parameters.put("value", "after");

        assertThat(frame.parameters()).containsEntry("value", "before");
        assertThatThrownBy(() -> frame.parameters().put("other", "nope"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requiresCorrelationMetadataOnExecutionFrame() {
        Instant openedAt = Instant.parse("2026-03-15T12:00:00Z");

        assertThatThrownBy(() -> new ExecutionFrame(null, null, OperationType.CAPABILITY, "route", Map.of(), openedAt))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("frameId");
        assertThatThrownBy(() -> new ExecutionFrame("frame", null, null, "route", Map.of(), openedAt))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("operationType");
        assertThatThrownBy(() -> new ExecutionFrame("frame", null, OperationType.CAPABILITY, null, Map.of(), openedAt))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("route");
        assertThatThrownBy(() -> new ExecutionFrame("frame", null, OperationType.CAPABILITY, "route", Map.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("openedAt");

        ExecutionFrame frame = new ExecutionFrame("frame", null, OperationType.CAPABILITY, "route", null, openedAt);

        assertThat(frame.parentFrameId()).isNull();
        assertThat(frame.parameters()).isEmpty();
    }
}
