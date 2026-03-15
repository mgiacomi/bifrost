package com.lokiscale.bifrost.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BifrostSessionHolderTest {

    @Test
    void throwsWhenCurrentSessionIsAccessedOutsideScope() {
        assertThatThrownBy(BifrostSession::getCurrentSession)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No active Bifrost session is bound to the current execution.");
    }

    @Test
    void returnsCurrentSessionInsideScopedBoundary() {
        BifrostSession session = new BifrostSession("session-1", 4);

        BifrostSession resolved = BifrostSessionHolder.callWithSession(session, BifrostSession::getCurrentSession);

        assertThat(resolved).isSameAs(session);
    }
}
