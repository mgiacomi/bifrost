package com.lokiscale.bifrost.core;

import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.time.Clock;

public final class TestBifrostSessions {

    private TestBifrostSessions() {
    }

    public static BifrostSession withId(String sessionId, int maxDepth) {
        return new BifrostSession(sessionId, maxDepth);
    }

    public static BifrostSession withId(String sessionId,
                                        int maxDepth,
                                        @Nullable Authentication authentication) {
        return new BifrostSession(sessionId, maxDepth, authentication);
    }

    public static BifrostSession withId(String sessionId,
                                        int maxDepth,
                                        @Nullable Authentication authentication,
                                        TracePersistencePolicy persistencePolicy) {
        return new BifrostSession(sessionId, maxDepth, authentication, persistencePolicy);
    }

    public static BifrostSession withId(String sessionId,
                                        int maxDepth,
                                        @Nullable Authentication authentication,
                                        TracePersistencePolicy persistencePolicy,
                                        Clock clock) {
        return new BifrostSession(sessionId, maxDepth, authentication, persistencePolicy, clock);
    }
}
