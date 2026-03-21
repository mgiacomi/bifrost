package com.lokiscale.bifrost.core;

import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

public class BifrostSessionRunner {

    private final int maxDepth;

    public BifrostSessionRunner(int maxDepth) {
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be greater than zero");
        }
        this.maxDepth = maxDepth;
    }

    public void runWithNewSession(Consumer<BifrostSession> action) {
        runWithNewSession(null, action);
    }

    public void runWithNewSession(@Nullable Authentication authentication, Consumer<BifrostSession> action) {
        Objects.requireNonNull(action, "action must not be null");
        BifrostSession session = new BifrostSession(maxDepth, authentication);
        BifrostSessionHolder.runWithSession(session, () -> action.accept(session));
    }

    public <T> T callWithNewSession(Function<BifrostSession, T> action) {
        return callWithNewSession(null, action);
    }

    public <T> T callWithNewSession(@Nullable Authentication authentication, Function<BifrostSession, T> action) {
        Objects.requireNonNull(action, "action must not be null");
        BifrostSession session = new BifrostSession(maxDepth, authentication);
        return BifrostSessionHolder.callWithSession(session, () -> action.apply(session));
    }
}
